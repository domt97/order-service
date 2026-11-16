# order-service — Order bounded context

Part of the OMS. System-wide map, rules and other services: **`oms-workspace`**
(https://github.com/domt97/oms-workspace — `CLAUDE.md`, `architecture/`). Read it before any change
that publishes/consumes messages or calls another service. The design of this context is ADR 0007 (checkout placement)
and ADR 0006 (stock commands); the flow is `architecture/flows/checkout-placement.md`.

Cross-repo steps are workspace skills: `change-event`, `change-api`, `add-migration`, `add-aws-resource`. How much review a
change gets: workspace `CLAUDE.md` → *Review policy*. Branches, CI and oms-core releases in full: workspace
`architecture/delivery.md`.

## Owns

**Today: checkout intake** (ADR 0007, pre-reserve part). Consumes `PlaceCheckout` (Kafka, listener **off by default**:
`app.kafka.place-checkout.enabled`), checks every store with stores-service's internal `order-quote`, and answers a
failing checkout with `CheckoutRejected` through `outbox_events` (connector `order-outbox-cdc`, `development` repo).
A checkout that passes every check is **not placed yet**: nothing is stored or sent (decided 2026-11-16). Planned:
placement (orders per store, daily-cap slots, fees, the stock commands to inventory-service), store accept/reject, the
mock delivery partner, COD reconciliation, `by-key/expire`, delivery quotes.
Postgres DB `orders`, schema `orders` (`order` is a reserved word): `outbox_events` (V1), `checkouts` (V2). Port **8068**.

### Checkout intake (`PlaceCheckoutService`)
Order of steps, every copy of a command answered the same way (BR-ORD-05):
1. the key's stored row for this customer (`checkouts`, unique `(cart_id, cart_version)`): same content hash → its
   outcome again (new eventId, same reasons and `checkoutId`); other hash → `CONTENT_MISMATCH` (reply only, no
   `checkoutId`, no row written or changed);
2. contract rules (`PlaceCheckoutValidator`) → `INVALID_REQUEST`; a SKU with NUL or > 100 chars is reported on its
   store, never with its text (JSONB refuses `\u0000`);
3. `requestedAt` older than `app.checkout.stale-after` (10 min) → `STALE`;
4. one `order-quote` per store **outside any transaction**, within `quote-budget` (20 s) → `QuoteReasonMapper`
   (not ACTIVE → `STORE_INACTIVE`; not open → `STORE_CLOSED`; SUSPENDED → `MERCHANT_SUSPENDED`; found=false or another
   product → `UNKNOWN_SKU`; SKU/product not ACTIVE → `PRODUCT_INACTIVE`; SKU currency ≠ store currency or none →
   `CURRENCY_MISMATCH`; 404/400 → `INVALID_REQUEST` (404 logged at ERROR); 5xx, other 4xx, timeout, unreadable →
   `STORE_UNAVAILABLE`);
5. any reason → `CheckoutTransactions.storeOutcome`: `INSERT … ON CONFLICT DO NOTHING` + the reply, one transaction
   (lock 2 s, statement 3 s, transaction 5 s); 0 rows → re-read and back to step 1.
Daily cap and delivery fee are checked at placement (next PR). Reasons are distinct and sorted (store, SKU, code).

## Layout (`com.dotran.oms.order`)

Hexagonal layout (workspace system rule 3); packages appear with their first class. Use cases and services are grouped
per area (`<area>` = `checkout`, `order`, …) as in stores-service; inventory-service keeps them flat.

| Layer | Package | Contains |
|-------|---------|----------|
| Domain (no Spring) | `domain/model`, `valueobject`, `enums`, `event`, `exception` | Aggregates, value objects, statuses, domain exceptions |
| Application | `application/usecase/<area>`, `service/<area>` | Inbound ports `XxxUseCase`; `@UseCase` services, `@Transactional` here |
| | `application/command`, `dto`, `mapper`, `repository`, `client` | `XxxCmd` in, `XxxDto` out, MapStruct mappers, outbound ports |
| Infrastructure | `infrastructure/rest/{api,internal,dto,mapper,exception}` | Controllers (`internal` = `@Hidden`, `/internal/v1/order/...`), `OrderExceptionHandler` (extends oms-core `GlobalExceptionHandler`) |
| | `infrastructure/persistence` | JDBC adapters (`CheckoutPersistenceAdapter`, `OutboxEventPersistenceAdapter`, `TransactionTimeoutPersistenceAdapter`); JPA entities when an aggregate needs them |
| | `infrastructure/message` | Kafka consumers, `KafkaMdc`, external DTOs in `message/dto` (anti-corruption layer) |
| | `infrastructure/client`, `config` | `StoresOrderQuoteClient`; Kafka error handling, the decoder bean, properties |

## Logging (MDC)

Every log line carries the MDC (`logging.pattern.correlation: "[%X] "`): `requestId` (from `X-Request-Id` or generated,
echoed in the response) and the request values in `app.logging.mdc.fields` (oms-core defaults: `merchantId`,
`storeId`, `productId`, …). Add `orderId` (format `uuid`) there with the first `/orders/{orderId}` path. Outbound HTTP
built on the injected `RestClient.Builder` sends the MDC `requestId` as `X-Request-Id` (oms-core 1.2.0
`RequestIdClientConfig`); a third-party client (delivery partner) must use `RestClient.builder()` instead.

## Adding a use case — checklist

1. **Domain**: the rule in the aggregate method; throw a domain exception. No Spring.
2. **Port + command**: `application/usecase/<area>/XxxUseCase`, `application/command/<area>/XxxCmd`.
3. **Service**: `application/service/<area>/XxxService` with `@UseCase` + `@Transactional`.
4. **Message out** (event, command or reply): `OutboxEventRepository.saveAll` (`MANDATORY`) in the **same transaction**
   as the state change, never a Kafka producer (workspace system rules 2 and 8). Topic = `eventType`, key =
   `aggregateId`; set exactly one of `customerId` (checkout replies) or `merchantId` (stock commands): V1 CHECK.
   Declare `String TYPE` in `application/event/` (where the workspace facts script looks).
5. **Message in**: a consumer in `infrastructure/message` maps the external DTO to a command and is **idempotent**
   (Debezium delivers at least once).
6. **Errors**: map in `OrderExceptionHandler`; codes listed in this file (`BusinessException.withCode`).
7. **Migration**: `src/main/resources/db/migration/V<next>__<what>.sql` (latest is `V2`). Every
   merchant-owned table carries `merchant_id`, every customer-owned one `customer_id` (workspace system rule 4).
8. **Tests** mirroring the package; native SQL, constraints and transactions get an integration test.

## Tests

- **Unit tests** (`src/test`): JUnit 5 + Mockito, no DB. `CoreProfileConfigTest` guards `profiles.include: core`,
  the name, the port and the Flyway schema; `PlaceCheckoutListenerConfigTest` that the listener is off by default.
  `StoresOrderQuoteClientTest` runs against a real local JDK `HttpServer` (MockRestServiceServer replaces the request
  factory and cannot prove timeouts). Contract tests validate payloads against `src/contractTest/resources` (copies of
  the workspace schemas: re-copy when a schema changes). Run: `./gradlew test`.
- **Integration tests** (`src/integrationTest`, Gradle suite `integrationTest`, Docker needed): Testcontainers
  `postgres:16-alpine` (`wal_level=logical`), Flyway with the settings of `application.yaml`, the JPA slice
  (`support/PostgresIT`, with the checkout services, the consumer and a stub order-quote client). The outbox as
  Debezium sees it through a `test_decoding` slot (`support/WalSlot`). `CheckoutSqlIT`, `OutboxSqlIT`,
  `CheckoutTransactionIT` (rollback, SET LOCAL), `PlaceCheckoutConsumerIT` (JSON in → row + reply in the WAL; races
  with the same and with different content; NUL SKU), `FlywayMigrationIT`.
  Run: `./gradlew integrationTest`; CI job `Integration tests` (not a required check yet).

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
- The workspace contracts name this service as the publisher of `CheckoutPlaced`: the generated facts show a contract
  note for it until placement is built.
- **The PlaceCheckout listener is off** (`app.kafka.place-checkout.enabled: false`, also gates `PlaceCheckout.DLT`):
  turn it on only to test rejections, never where carts send real checkouts, until placement is built.
- **Never log the delivery data** (BR-ORD-15). The decoder's mapper has `INCLUDE_SOURCE_IN_LOCATION` off and the DLT
  line of a parse error carries exception classes and position only; keep both. `PlaceCheckoutMessage.Delivery` /
  `Address` redact `toString`.
- **Poll sizing**: `max.poll.records=1` on the listener; the worst case per record is written next to
  `app.kafka.place-checkout` in `application.yaml` (170 s < 300 s). Re-check it when a timeout, the budget or the
  pool wait changes.
- Spring 6.1 `RestClient` has no per-request timeout: `StoresOrderQuoteClient` builds a `SimpleClientHttpRequestFactory`
  per call (connect and read timeouts = min(configured, budget left)) on `builder.clone()`, which keeps the
  X-Request-Id interceptor. Do not switch to `JdkClientHttpRequestFactory`: its timeout ends when the headers arrive,
  so a body that stalls after them blocks the listener without a limit.
- **`PlaceCheckout.DLT` holds the full record, delivery data included** (BR-ORD-15): restrict who can read it and give
  it a retention like the command topic's (open question 17: no alerting or replay tooling on DLTs yet).
- Numbers in `PlaceCheckoutMessage` are `BigDecimal`: a non-whole `cartVersion` goes to the DLT, a non-whole
  `quantity` is `INVALID_REQUEST` (Jackson would otherwise turn 3.7 into 3).
