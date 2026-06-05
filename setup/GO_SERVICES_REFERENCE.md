# Go Services Reference

## Service Map

| Service | Go module | Docker container | Database |
|---------|-----------|-----------------|----------|
| banking-core-service-go | `banka1/banking-core-service-go` | `banka_banking_core_service` | `banking_core` |
| credit-service-go | `Banka1Back/credit-service-go` | `banka_credit_service` | `credit_db` |
| interbank-service | `github.com/raf-si-2025/banka-1-go/interbank-service` | `banka_interbank_service` | `interbank_service` |
| market-service-go | `banka1/market-service-go` | `banka_market_service` | `market_service` |
| notification-service-go | `Banka1Back/notification-service-go` | `banka_notification_service` | `notification_db` |
| saga-orchestrator-service | `github.com/raf-si-2025/banka-1-go/saga-orchestrator-service` | `banka_saga_orchestrator` | `saga_db` |
| trading-service-go | `banka1/trading-service-go` | `banka_trading_service` | `trading` |
| user-service-go | `banka1/user-service-go` | `banka_user_service` | `user_service` |
| go-platform | `banka1/go-platform` | — (shared library) | — |
| shared | `github.com/raf-si-2025/banka-1-go/shared` | — (shared library) | — |

---

## Packages per Service

### banking-core-service-go — 43 src / 26 test
```
cmd/server
internal/account
internal/card
internal/config
internal/db
internal/decimal
internal/http
internal/service
internal/uuid
```

### credit-service-go — 32 src / 16 test
```
cmd/credit-service
internal/api
internal/auth
internal/client
internal/config
internal/dto
internal/mapper
internal/messaging
internal/model
internal/service
internal/store
```

### interbank-service — 35 src / 31 test
```
internal/api        — HTTP handlers (inbound protocol + OTC outbound FE-facing)
internal/client     — outbound HTTP client to partner banks
internal/config
internal/grpc
internal/mock       — mock Banka 2 controller for tests
internal/protocol   — SAGA messages (NEW_TX / COMMIT_TX / ROLLBACK_TX)
internal/scheduler
internal/service    — OTC outbound business logic
internal/store      — DB layer
```

### market-service-go — 29 src / 18 test
```
cmd/paritycheck
cmd/server
internal/api
internal/auth
internal/clients
internal/fx
internal/grpc
internal/http       — handlers (listings, watchlists, price feed, exchanges...)
internal/market     — domain layer (service, repository, models, import)
internal/platform
```

### notification-service-go — 43 src / 15 test
```
cmd/server
internal/amqp
internal/amqp/dto
internal/domain/model
internal/domain/repository
internal/grpc/handler
internal/grpc/interceptor
internal/infrastructure/persistence
internal/notification
internal/notification/smtp
internal/notification/template
internal/service
internal/store
```

### saga-orchestrator-service — 20 src / 17 test
```
internal/api
internal/client
internal/config
internal/events
internal/rabbit
internal/saga       — SAGA state machine
internal/scheduler
internal/store
```

### trading-service-go — 97 src / 51 test  (largest service)
```
cmd/minttoken
cmd/paritycheck
cmd/server
internal/actuary
internal/analytics
internal/api        — DTO types
internal/audit
internal/auth
internal/clients    — HTTP clients (market, user, banking-core)
internal/dividend
internal/funds
internal/grpc
internal/http       — HTTP handlers
internal/interbank
internal/order      — order domain (service, repo, execution, worker, pricing, recurring...)
internal/otc
internal/platform
internal/portfolio
internal/tax
```

### user-service-go — 18 src / 6 test  (smallest service)
```
cmd/server
internal/platform   — JWT, config, migrations, router, password
internal/user       — handlers, service, repository, permissions, models
```

### go-platform — 21 src / 9 test  (shared library)
```
auth        — JWT mint/parse, middleware, Principal, RequireRoles
config
db
grpcx
health
httpx
log
otel
rabbitmq
```

### shared — 6 src / 6 test  (interbank + saga library)
```
auth        — RequireJWT, RequireXApiKey, RequirePermission, S2SIssuer, Claims/StringOrSlice
```

---

## Databases (all on container `banka_postgres`)

| Database | Service | Notable tables |
|----------|---------|----------------|
| `banking_core` | banking-core-service-go | `account_table` (NOT `accounts`), cols: `dnevni_limit`, `dnevna_potrosnja`, `daily_limit_remaining` |
| `credit_db` | credit-service-go | |
| `interbank_service` | interbank-service | |
| `market_service` | market-service-go | `listing`, `listing_daily_price_info`, `watchlists`, `watchlist_items` |
| `notification_db` | notification-service-go | |
| `saga_db` | saga-orchestrator-service | |
| `trading` | trading-service-go | `orders`, `transactions`, `stock_ownership_transfers` |
| `user_service` | user-service-go | `employees`, `zaposlen_permissions`, `clients`, `client_permissions` |

---

## Permission / Role System

### Employee roles (ADMIN > SUPERVISOR > AGENT > BASIC > SERVICE)

| Role | Permissions |
|------|-------------|
| ADMIN | BANKING_BASIC, CLIENT_MANAGE, SECURITIES_TRADE_LIMITED, SECURITIES_TRADE_UNLIMITED, TRADE_UNLIMITED, OTC_TRADE, FUND_AGENT_MANAGE, EMPLOYEE_MANAGE_ALL |
| SUPERVISOR | same as ADMIN without EMPLOYEE_MANAGE_ALL |
| AGENT | BANKING_BASIC, CLIENT_MANAGE, SECURITIES_TRADE_LIMITED, OTC_TRADE |
| BASIC | BANKING_BASIC, CLIENT_MANAGE |
| SERVICE | (none) |

### Client roles

| Role | Permissions |
|------|-------------|
| CLIENT_TRADING | CLIENT_SECURITIES_TRADE, CLIENT_OTC_TRADE |
| CLIENT_BASIC | CLIENT_ACCOUNT_ACCESS |

Permissions are stored per-user in `zaposlen_permissions` / `client_permissions` tables.
`employeePermissions(role)` / `clientPermissions(role)` in `user-service-go/internal/user/permissions.go`
are the **defaults used only at employee/client creation and role change** — not read at login.
JWT claims: `roles` (single string), `permissions` (array of strings).

---

## Docker Compose

All services run from `setup/docker-compose.yml`.

**Rebuild and restart a single service:**
```bash
cd setup
docker compose build <service-name>
docker compose up -d <service-name>
```

Service names: `user-service`, `trading-service`, `market-service`, `interbank-service`,
`banking-core-service`, `credit-service`, `notification-service`, `saga-orchestrator-service`

**After rebuilding user-service:** users must re-login to get a new JWT with updated permissions.

---

## Migration System

- Each service has a `migrations/` folder with numbered `.sql` files (e.g. `001_schema.sql`).
- Runner tracks applied migrations in `go_schema_migrations` table (per-database).
- Migrations run in a transaction — if SQL fails, the file is NOT marked as applied (safe to fix and retry).
- **Baseline logic:** if `go_schema_migrations` is empty but all schema tables already exist, all current migration files are marked as applied without executing them (avoids re-running schema on existing DB).

---

## Running Tests

```bash
# Single service
cd <service-dir>
go test ./...

# With coverage
go test ./... -coverprofile=coverage.out
go tool cover -html=coverage.out

# Specific package
go test ./internal/order/...
```
