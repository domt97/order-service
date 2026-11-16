# order-service — Order bounded context

Part of the OMS. System-wide map, rules and other services: **`oms-workspace`**
(https://github.com/domt97/oms-workspace — `CLAUDE.md`, `architecture/`). Read it before any change
that publishes/consumes messages or calls another service. The design of this context is ADR 0007 (checkout placement)
and ADR 0006 (stock commands); the flow is `architecture/flows/checkout-placement.md`.

Cross-repo steps are workspace skills: `change-event`, `change-api`, `add-migration`, `add-aws-resource`. How much review a
change gets: workspace `CLAUDE.md` → *Review policy*. Branches, CI and oms-core releases in full: workspace
`architecture/delivery.md`.

## Owns

**Today: a skeleton only** — the application, its configuration, CI and the test suites; no aggregate, endpoint,
consumer, migration or message yet. Planned (ADR 0007): checkouts and orders — one order per store with line snapshots,
the stock commands to inventory-service, store accept/reject, the mock delivery partner and COD reconciliation.
Postgres DB `orders`, schema `orders` (`order` is a reserved word). Port **8068**.

## Layout (`com.dotran.oms.order`)

Hexagonal layout (workspace system rule 3); packages appear with their first class. Use cases and services are grouped
per area (`<area>` = `checkout`, `order`, …) as in stores-service; inventory-service keeps them flat.

| Layer | Package | Contains |
|-------|---------|----------|
| Domain (no Spring) | `domain/model`, `valueobject`, `enums`, `event`, `exception` | Aggregates, value objects, statuses, domain exceptions |
| Application | `application/usecase/<area>`, `service/<area>` | Inbound ports `XxxUseCase`; `@UseCase` services, `@Transactional` here |
| | `application/command`, `dto`, `mapper`, `repository`, `client` | `XxxCmd` in, `XxxDto` out, MapStruct mappers, outbound ports |
| Infrastructure | `infrastructure/rest/{api,internal,dto,mapper,exception}` | Controllers (`internal` = `@Hidden`, `/internal/v1/order/...`), `OrderExceptionHandler` (extends oms-core `GlobalExceptionHandler`) |
| | `infrastructure/persistence/{entity,jpa}`, `message`, `client` | JPA adapters, Kafka consumers with their external DTOs (anti-corruption layer), clients of other services |

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
4. **Message out** (event, command or reply): through `outbox_events` in the **same transaction** as the state change,
   never a Kafka producer (workspace system rules 2 and 8). The outbox table does not exist yet: the first PR that
   publishes adds it (`V1`), with its scoping columns designed for this context (`CheckoutPlaced` / `CheckoutRejected`
   are customer-scoped and span merchants), the outbox adapter, and the `order-outbox-cdc` connector in `development`.
5. **Message in**: a consumer in `infrastructure/message` maps the external DTO to a command and is **idempotent**
   (Debezium delivers at least once).
6. **Errors**: map in `OrderExceptionHandler`; codes listed in this file (`BusinessException.withCode`).
7. **Migration**: `src/main/resources/db/migration/V<next>__<what>.sql` (none yet; the first is `V1`). Every
   merchant-owned table carries `merchant_id`, every customer-owned one `customer_id` (workspace system rule 4).
8. **Tests** mirroring the package; native SQL, constraints and transactions get an integration test.

## Tests

- **Unit tests** (`src/test`): JUnit 5 + Mockito, no Spring context, no DB. `CoreProfileConfigTest` guards
  `profiles.include: core`, the name, the port and the Flyway schema. Run: `./gradlew test`.
- **Integration tests** (`src/integrationTest`, Gradle suite `integrationTest`, Docker needed): Testcontainers
  `postgres:16-alpine` (`wal_level=logical`), Flyway with the settings of `application.yaml`, the JPA slice
  (`support/PostgresIT`). `FlywayMigrationIT`: schema `orders` and its history table, nothing in `public`.
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
- The workspace contracts already name this service as the publisher of `CheckoutPlaced` / `CheckoutRejected`: the
  generated facts show a contract note until the first publisher PR.
