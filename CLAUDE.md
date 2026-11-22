# order-service — Order bounded context

Part of the OMS. System-wide map, rules and other services: **`oms-workspace`**
(https://github.com/domt97/oms-workspace — `CLAUDE.md`, `architecture/`). Read it before any change
that publishes/consumes messages or calls another service. The design of this context is ADR 0007 (checkout placement)
and ADR 0006 (stock commands); the flow is `architecture/flows/checkout-placement.md`.

Cross-repo steps are workspace skills: `change-event`, `change-api`, `add-migration`, `add-aws-resource`. How much review a
change gets: workspace `CLAUDE.md` → *Review policy*. Branches, CI and oms-core releases in full: workspace
`architecture/delivery.md`.

## Owns

**Today: checkout intake and placement** (ADR 0007, ADR 0006). Consumes `PlaceCheckout` (Kafka, listener **off by
default**: `app.kafka.place-checkout.enabled`, D1: on only once the go-live gates are built: acceptance and cancel,
delivery quotes, `by-key/expire`, cart-service), checks every store with stores-service's internal `order-quote`, and
answers a failing checkout with `CheckoutRejected` through `outbox_events` (connector `order-outbox-cdc`, `development`
repo). A passing checkout is **placed**: a PENDING checkout, one order per store (lines, delivery snapshot), one
`ReserveStock` per order and one daily slot per store, in one transaction. Inventory's five replies (listeners **on by
default**, `app.kafka.stock-replies.enabled`, so a started saga always ends) decide it: `CheckoutPlaced` with the order
numbers, or `CheckoutRejected` with every reason. Two jobs: reserve timeout and the re-send of open Confirm/Release.
Planned: store accept/reject, the mock delivery partner, COD reconciliation, `by-key/expire`, delivery quotes (the
delivery fee stays null until then, D2).
Postgres DB `orders`, schema `orders` (`order` is a reserved word): `outbox_events` (V1), `checkouts` (V2, widened in
V3), `orders`, `order_lines`, `store_day_counters`, `order_transitions` (V3). Port **8068**.

### Checkout intake (`PlaceCheckoutService`)
Order of steps, every copy of a command answered the same way (BR-ORD-05):
1. the key's stored row for this customer (`checkouts`, unique `(cart_id, cart_version)`): same content hash → its
   outcome again (new eventId, same reasons and `checkoutId`); other hash → `CONTENT_MISMATCH` (reply only, no
   `checkoutId`, no row written or changed);
2. contract rules (`PlaceCheckoutValidator`) → `INVALID_REQUEST`. Lengths count Unicode code points (as JSON Schema
   `maxLength` does), blank means `Character.isWhitespace`, and no text field may hold NUL or an unpaired surrogate.
   A bad SKU (NUL, surrogate, blank, > 100) is reported on its store, never with its text (JSONB refuses `\u0000`); a
   bad delivery field (caps in BR-ORD-15) is one checkout-level reason, no text; a `lineId` used twice in the command
   is reported on the later line, or only on its store if that line's SKU is bad (BR-ORD-14);
3. `requestedAt` older than `app.checkout.stale-after` (10 min) → `STALE`;
4. one `order-quote` per store **outside any transaction**, within `quote-budget` (20 s) → `QuoteReasonMapper`
   (not ACTIVE → `STORE_INACTIVE`; not open → `STORE_CLOSED`; SUSPENDED → `MERCHANT_SUSPENDED`; found=false or another
   product → `UNKNOWN_SKU`; SKU/product not ACTIVE → `PRODUCT_INACTIVE`; SKU currency ≠ store currency or none →
   `CURRENCY_MISMATCH`; 404/400 → `INVALID_REQUEST` (404 logged at ERROR); 5xx, other 4xx, timeout, unreadable →
   `STORE_UNAVAILABLE`). What placement cannot snapshot is refused here too: no `localDate` → `STORE_CLOSED`; a store
   code not `[A-Z0-9]{1,20}` (D4, logged at ERROR), a null or negative price or a null product name (Q1) →
   `STORE_UNAVAILABLE`; a currency with no usable minor units (D7) → `CURRENCY_MISMATCH`;
5. any reason → `CheckoutTransactions.storeOutcome`: `INSERT … ON CONFLICT DO NOTHING` + the reply, one transaction
   (lock 2 s, statement 3 s, transaction 5 s); 0 rows → re-read and back to step 1.
6. no reason → `CheckoutTransactions.place` (transaction 10 s): the PENDING checkout (`reserve_due_at` = now + 150 s),
   the orders, a `ReserveStock` per order, then one slot per store (`store_day_counters`, the quote's `localDate`, D8;
   a cap ≤ 0 means 0, D6) taken last, in store order. A capped store rolls it all back → `DAILY_CAP_REACHED` through
   step 5. A lost insert re-reads the key; a PLACED row answers its copies with `CheckoutPlaced` again, a PENDING one
   with nothing (`IN_PROGRESS`).
Reasons are distinct and sorted (store, SKU, code).

### Stock saga (`StockSagaTransactions`, ADR 0006)
- **One transaction per reply** (10 s): find the order by `(id, merchant_id, store_id)` (else the DLT, nothing written),
  lock the checkout, then its orders in id order, check the reply's lines against the order, move the order out of the
  one state the reply answers, decide the checkout. **Duplicates are recognised by state, never by `causationId`**: a
  repeated or late reply finds the order elsewhere and changes nothing.
- **Decide (D3)**: wait while a reserve is unanswered; all RESERVED → order numbers (`<storeCode>-<yyMMdd>-<seq>`),
  PLACED/CONFIRM_SENT, a Confirm per order, one `CheckoutPlaced`; anything else, or the reserve deadline → REJECTED,
  a Release (`restock = true`) for every held or unanswered order, every slot given back, one `CheckoutRejected`.
  `STALE_COMMAND` → `RESERVE_TIMEOUT`, `ORDER_CLOSED` → `RESERVE_FAILED`.
- **ConfirmationFailed (BR-ORD-16)** → CANCELLED/RELEASE_SENT (`STOCK_NOT_CONFIRMED`), slot back, Release; the checkout
  stays PLACED.
- **Lock order everywhere**: checkouts → orders (id order) → `store_day_counters` in `StoreOrder.BY_STORE_ID` order
  (the uuid **string**, which is Postgres's uuid order; `UUID.compareTo` is not). Never take counters in order-id order.
- **Jobs** (`infrastructure/job`, `fixedDelay` 10 s, scheduler pool 2, SKIP LOCKED so instances split the rows):
  `ReserveTimeoutService` (no work for 150 s after start; one checkout per transaction, ≤ 50 per run) and
  `StockCommandResendService` (batches of ≤ 50; re-send interval 150 s doubling to 10 min; ERROR + metric from attempt
  3; never gives up). Both services are **not** `@Transactional`; the run budget (5 s) is checked before each call. A
  row that fails is counted once, skipped for the run and deferred; a failed re-send batch is retried row by row (at
  least one). A connection failure or a failure that names no row stops the run (`jobAborted`). Worst run 85 s / 90 s.
  A poisoned checkout stays PENDING until an operator fixes it (oldest-PENDING gauge, `jobFailure` alert).

### Metrics and alerts
Micrometer, prefix `order.saga.`, on `/actuator/metrics`. Tags never carry an id. There is no alert backend yet; define
these with it: oldest-PENDING age (`oldest.pending.age.seconds`, by `created_at`) > 5 min; any
`stock.command.attempts.over3`; a rising `checkout.rejected{code=RESERVE_TIMEOUT}` / `reserve.timeout` rate;
`job.failure{kind=PERSISTENT}` > 0; `job.failure{kind=TRANSIENT}` ≥ 6 in 5 min; `job.consecutive.aborts` ≥ 3 (a gauge
reset by a clean run, not a time window: with the database down each aborted run takes ~30 s); any
`counter.giveback.missed`.

## Layout (`com.dotran.oms.order`)

Hexagonal layout (workspace system rule 3); packages appear with their first class. Use cases and services are grouped
per area (`<area>` = `checkout`, `order`, …) as in stores-service; inventory-service keeps them flat.

| Layer | Package | Contains |
|-------|---------|----------|
| Domain (no Spring) | `domain/model`, `valueobject`, `enums`, `event`, `exception` | Aggregates, value objects, statuses, domain exceptions |
| Application | `application/usecase/<area>`, `service/<area>` | Inbound ports `XxxUseCase`; `@UseCase` services, `@Transactional` here |
| | `application/command`, `dto`, `mapper`, `repository`, `client` | `XxxCmd` in, `XxxDto` out, MapStruct mappers, outbound ports |
| | `application/metrics`, `application/support` | `SagaMetrics` port; `DbFailures` (connection failure, transient kind, PII-free cause text) |
| Infrastructure | `infrastructure/rest/{api,internal,dto,mapper,exception}` | Controllers (`internal` = `@Hidden`, `/internal/v1/order/...`), `OrderExceptionHandler` (extends oms-core `GlobalExceptionHandler`) |
| | `infrastructure/persistence` | JDBC adapters (`Checkout`, `Order`, `StoreDayCounter`, `OutboxEvent`, `TransactionTimeout` + `PersistenceAdapter`; `JdbcValues`); JPA entities when an aggregate needs them |
| | `infrastructure/message` | Kafka consumers, `KafkaMdc`, external DTOs in `message/dto` (anti-corruption layer) |
| | `infrastructure/job`, `metrics` | `@JobAdapter` scheduled jobs (call a use case, nothing else); `MicrometerSagaMetrics` |
| | `infrastructure/client`, `config` | `StoresOrderQuoteClient`; Kafka error handling, the decoder bean, listener and scheduling config, properties |

## Logging (MDC)

Every log line carries the MDC (`logging.pattern.correlation: "[%X] "`): `requestId` (from `X-Request-Id` or generated,
echoed in the response) and the request values in `app.logging.mdc.fields` (oms-core defaults: `merchantId`,
`storeId`, `productId`, …). Add `orderId` (format `uuid`) there with the first `/orders/{orderId}` path. Outbound HTTP
built on the injected `RestClient.Builder` sends the MDC `requestId` as `X-Request-Id` (oms-core 1.2.0
`RequestIdClientConfig`); a third-party client (delivery partner) must use `RestClient.builder()` instead.

## Adding a use case — checklist

1. **Domain**: the rule in the aggregate method; throw a domain exception. No Spring.
2. **Port + command**: `application/usecase/<area>/XxxUseCase`, `application/command/<area>/XxxCmd`.
3. **Service**: `application/service/<area>/XxxService` with `@UseCase` + `@Transactional`. When the service calls
   another service or loops over rows, it is **not** transactional and calls a `XxxTransactions` bean, one bounded
   transaction (`timeout`) per call (`CheckoutTransactions`, `StockSagaTransactions`; `TransactionalAnnotationTest`).
4. **Message out** (event, command or reply): `OutboxEventRepository.saveAll` (`MANDATORY`) in the **same transaction**
   as the state change, never a Kafka producer (workspace system rules 2 and 8). Topic = `eventType`, key =
   `aggregateId`; set exactly one of `customerId` (checkout replies) or `merchantId` (stock commands): V1 CHECK.
   Declare `String TYPE` in `application/event/` (where the workspace facts script looks).
5. **Message in**: a consumer in `infrastructure/message` maps the external DTO to a command and is **idempotent**
   (Debezium delivers at least once).
6. **Errors**: map in `OrderExceptionHandler`; codes listed in this file (`BusinessException.withCode`).
7. **Migration**: `src/main/resources/db/migration/V<next>__<what>.sql` (latest is `V3`). Every
   merchant-owned table carries `merchant_id`, every customer-owned one `customer_id` (workspace system rule 4).
8. **Tests** mirroring the package; native SQL, constraints and transactions get an integration test.

## Tests

- **Unit tests** (`src/test`): JUnit 5 + Mockito, no DB. `CoreProfileConfigTest` guards `profiles.include: core`,
  the name, the port, the Flyway schema, `logServerErrorDetail=false`, no Flyway connection of its own, the pools and
  the actuator exposure; `PlaceCheckoutListenerConfigTest` that the listener is off by default,
  `StockReplyListenerConfigTest` that the reply listeners are on. The job services are tested with a moving clock
  (`ReserveTimeoutServiceTest`, `StockCommandResendServiceTest`: grace, budget, each failure path).
  `StoresOrderQuoteClientTest` runs against a real local JDK `HttpServer` (MockRestServiceServer replaces the request
  factory and cannot prove timeouts). Contract tests validate payloads against `src/contractTest/resources` (copies of
  the workspace schemas, byte-equal: re-copy when a schema changes). Run: `./gradlew test`.
- **Integration tests** (`src/integrationTest`, Gradle suite `integrationTest`, Docker needed): Testcontainers
  `postgres:16-alpine` (`wal_level=logical`), Flyway with the settings of `application.yaml`, the JPA slice
  (`support/PostgresIT`, with the checkout and saga services, the consumers and a stub order-quote client; no Kafka, no
  scheduler: the ITs call the job services). The outbox as Debezium sees it through a `test_decoding` slot
  (`support/WalSlot`); `support/Orders` and `support/Sagas` build placed checkouts and reply records.
  SQL: `CheckoutSqlIT`, `OrderSqlIT`, `StoreDayCounterSqlIT`, `OutboxSqlIT`, `FlywayMigrationIT`. Transactions:
  `CheckoutTransactionIT`, `PlacementTransactionIT`. Flows: `PlaceCheckoutConsumerIT`, `StockReplyConsumersIT`,
  `ReserveTimeoutJobIT`, `StockCommandResendJobIT`. Races: `StockSagaConcurrencyIT` (repeated 5 times). PII:
  `OrderPiiErrorIT`. Run: `./gradlew integrationTest`; CI job `Integration tests` (not a required check yet).

## Git workflow

Branch **from `develop`** and open PRs **into `develop`** (`feature/...`, `fix/...`, `docs/...`).
`master` only receives `develop` through release PRs — never target `master` directly.

## Build & run

oms-core: locally `../oms-core` next to this repo is used as a composite build (`oms-workspace/scripts/sync-repos.sh`);
CI and releases use the pinned `coreVersion` (`gradle.properties`, 1.2.0) from GitHub Packages (ADR 0005). Without
`../oms-core` a local build needs `gpr.user`/`gpr.key` (classic token, `read:packages`) in `~/.gradle/gradle.properties`.
Never merge an `-rc` pin (`core-pin` check). CI reads the package with `GITHUB_TOKEN`: the oms-core package must grant
this repository read access (package settings → *Manage Actions access*).
Infra from the `development` repo (database `orders` from `docker/postgres/init/01-databases.sh`; topics
`PlaceCheckout`, `CheckoutPlaced`, `CheckoutRejected` and the stock commands from `docker/kafka/create-topics.sh`).
`./gradlew bootRun --args='--spring.profiles.active=localdev'` → Swagger at `http://localhost:8068/swagger-ui.html`,
OpenAPI JSON at `/api-docs` (from oms-core's `application-core.yaml`, profile `core`).

## Pitfalls

- **Flyway creates the schema** (`spring.flyway.schemas: orders`), unlike inventory, where it is created by hand.
  `baseline-on-migrate` is deliberately off: with it, a table created in `orders` before the first migration would
  make Flyway skip `V1`.
- The Debezium heartbeat table is created by the connector SQL in `development`, never by Flyway. That SQL's guard must
  check that `V1` is **applied** (a `flyway_schema_history` row with `version = '1'`), not only that the history table
  exists: it exists from the first start, before any migration.
- **The PlaceCheckout listener is off** (`app.kafka.place-checkout.enabled: false`, also gates `PlaceCheckout.DLT`):
  D1, turn it on only for local tests until the go-live gates are built. A placed and confirmed order holds its stock
  sold and nothing can accept, cancel or auto-reject it yet; the app logs a WARN at startup when it is on.
- **The stock-reply listeners are on** even while PlaceCheckout is off (`app.kafka.stock-replies.enabled`, also gates
  the five reply DLTs): `false` only for a local run without Kafka.
- **Never log the delivery data** (BR-ORD-15). The decoder's mapper has `INCLUDE_SOURCE_IN_LOCATION` off and the DLT
  line of a parse error carries exception classes and position only; keep both. `PlaceCheckoutMessage.Delivery` /
  `Address` redact `toString`, as does `DeliverySnapshot`.
- **Every connection to the database sets `logServerErrorDetail=false`** (BR-ORD-15): pgjdbc otherwise puts the
  server's `Failing row contains (…)` and a failed batch entry's SQL with its values (name, phone, address) into the
  exception text. It is set on the Hikari pool, and Flyway uses that DataSource. A separate Flyway connection
  (`spring.flyway.url`, or `spring.flyway.user` alone, or a `@FlywayDataSource` bean) must set it too.
  `OrderPiiErrorIT` proves it with a positive control. Job logs print `DbFailures.describe` (class names and SQL states
  only), never an exception message.
- **Poll sizing**: `max.poll.records=1` on PlaceCheckout, `5` on the replies; the worst case per poll is written next to
  `app.kafka.place-checkout` (200 s) and `app.kafka.stock-replies` (210 s) in `application.yaml`, both < 300 s. Re-check
  them when a timeout, the budget, the pool size or the pool wait changes.
- **Actuator** (`health,metrics`) is not authenticated, like `/internal/**` (open question 65): keep the port off the
  public network.
- `excluded` id sets bind as `CAST(:excluded AS uuid[])` from a `{a,b}` literal (`JdbcValues.uuidArray`); an empty
  set binds `'{}'`.
- Spring 6.1 `RestClient` has no per-request timeout: `StoresOrderQuoteClient` builds a `SimpleClientHttpRequestFactory`
  per call (connect and read timeouts = min(configured, budget left)) on `builder.clone()`, which keeps the
  X-Request-Id interceptor. Do not switch to `JdkClientHttpRequestFactory`: its timeout ends when the headers arrive,
  so a body that stalls after them blocks the listener without a limit.
- **`PlaceCheckout.DLT` holds the full record, delivery data included** (BR-ORD-15): restrict who can read it and give
  it a retention like the command topic's (open question 17: no alerting or replay tooling on DLTs yet).
- Numbers in `PlaceCheckoutMessage` are `BigDecimal`: a non-whole `cartVersion` goes to the DLT, a non-whole
  `quantity` is `INVALID_REQUEST` (Jackson would otherwise turn 3.7 into 3).
