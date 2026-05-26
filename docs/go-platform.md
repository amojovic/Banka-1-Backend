# Go backend platform

This document captures the infrastructure conventions every Go service in this
repo follows so that:

* a Go service runs side-by-side with the Java stack without surprises;
* adding a new Go service (next up: `trading-service-go`) is mechanical;
* gateway/DB/RabbitMQ/JWT/observability behave the same as the Java services;
* there is one well-defined procedure to switch traffic to a Go service and
  roll back.

Scope is **infrastructure/platform only**. Business logic migration is out of
scope here.

---

## 1. Inventory

| Service              | Language | Default in compose | Profile gate     |
|----------------------|----------|--------------------|------------------|
| `user-service`       | Go       | yes (replaces Java) | none             |
| `market-service`     | Java     | yes                | `go-market` to also start `market-service-go` |
| `market-service-go`  | Go       | no                  | `go-market`      |
| `trading-service`    | Java     | yes                | `go-trading` overlay to also start `trading-service-go` |
| `trading-service-go` | Go       | not built yet       | `go-trading` (overlay file)   |
| `banking-core-service`, `credit-service`, `notification-service`, `saga-orchestrator-service`, `interbank-service` | Java | yes | none |

`user-service-go/Dockerfile` is built by the `user-service` compose entry — the
Go binary already serves traffic by default. The Java user-service was retired
in PR_02 C2.7.

---

## 2. Ports

| Service                   | Java/REST | Go REST | gRPC  |
|---------------------------|-----------|---------|-------|
| user-service              | 8081 (Go) | 8081    | —     |
| market-service / -go      | 8085      | 18085   | 19085 |
| trading-service / -go     | 8088      | 18088   | 19088 |
| banking-core-service      | 8084      | —       | —     |
| credit-service            | 8089      | —       | —     |
| notification-service      | 8006      | —       | —     |
| saga-orchestrator-service | 8095      | —       | —     |
| interbank-service         | 8091      | —       | —     |
| api-gateway (nginx)       | 80        | —       | —     |
| postgres                  | 5432      | —       | —     |
| redis                     | 6379      | —       | —     |
| rabbitmq                  | 5672 / 15672 mgmt | — | —     |

**Convention:** Go side-by-side variants live on the Java port + 10000 (REST)
and Java port + 11000 (gRPC). Pick the next pair from the table when you add a
new service.

---

## 3. Databases

`setup/init-db.sh` creates one database per logical service on the shared
Postgres container. The Go services reuse the database names already used by
the Java consolidated services, so parity testing against the same dataset
works:

| Service                   | DB name            | Env-var prefix              |
|---------------------------|--------------------|-----------------------------|
| user-service-go           | `user_service`     | `USER_SERVICE_DB_*`         |
| market-service-go         | `market_service`   | `MARKET_SERVICE_DB_*`       |
| trading-service-go        | `trading`          | `TRADING_DB_*`              |
| banking-core (Java)       | `banking_core`     | `BANKING_CORE_DB_*`         |

### Schema ownership

* `user-service-go` owns and migrates the `user_service` schema (the Go
  service runs its own migrations from `user-service-go/migrations`).
* `market-service-go` **does not** migrate. Until the gateway switch, the Java
  `market-service` is the schema owner and runs Liquibase. The Go service
  connects read/write against that schema for parity testing.
* `trading-service-go` will follow the same rule: Java `trading-service` keeps
  Liquibase ownership until the cut-over.

If you ever need a Go-owned schema for a future service, use `migrations/` next
to the binary and a small migration runner (see
`user-service-go/internal/platform/migrations.go` as the reference).

### Why not Liquibase in Go

Duplicating Java Liquibase XML in Go is a maintenance trap: two sources of
truth, one will drift. The current rule is "the language that owns writes owns
migrations". Until the Java service is retired, leave its Liquibase alone.

---

## 4. RabbitMQ

### Existing Java contract — Go services MUST preserve

The notification-service (`notification-service/src/main/java/app/config/RabbitConfig.java`)
declares **one durable topic exchange** (`employee.events` by default) and
binds **one queue** (`notification-service-queue`) with several routing-key
patterns:

| Routing key   | Producer                       | Consumer              |
|---------------|--------------------------------|-----------------------|
| `employee.#`  | user-service (employee events) | notification-service  |
| `client.#`    | user-service (client events)   | notification-service  |
| `card.#`      | banking-core (card events)     | notification-service  |
| `credit.#`    | credit-service                 | notification-service  |
| `order.#`     | trading-service (order events) | notification-service  |
| `tax.#`       | trading-service (tax events)   | notification-service  |
| `otc.#`       | trading-service / interbank    | notification-service  |
| `client.verification` | banking-core verification | notification-service |

Go services publish to the same exchange with the same routing-key patterns —
do not invent new exchange names. The exchange is declared idempotently on
boot by every publisher (`ExchangeDeclare(name, "topic", durable=true,
autoDelete=false)`).

### Go publisher convention

`user-service-go/internal/platform/rabbit.go` is the reference. Shape:

```go
type NotificationPublisher interface {
    PublishEmail(ctx context.Context, routingKey string, payload EmailNotification) error
    Close()
}
```

Rules:

1. **Dial on startup.** If RabbitMQ is unreachable, return a
   `NoopPublisher` that logs and discards. The service must still start
   (matches Java behavior with `RABBITMQ_LISTENER_ENABLED=false`).
2. **Topic exchange declared idempotently.** Durable, non-auto-delete.
3. **`DeliveryMode: amqp.Persistent`.** Messages survive broker restart.
4. **Per-publish `context.WithTimeout(5s)`.** No unbounded blocking.
5. **JSON body, `ContentType: application/json`.**
6. **Timestamp on every message.** notification-service uses it for retry
   bookkeeping.
7. **Publisher confirms / mandatory routing**: keep them off until
   notification-service consumes them; Java publishers do not use them
   either today.
8. **Consumer ack/nack** (future): always manual ack after the work is
   committed. Use nack-with-requeue=false for poison messages; we already
   have a retry pattern in notification-service.
9. **Idempotency.** Producers SHOULD include a stable `eventId` field in the
   payload so consumers can dedupe. Notification-service does best-effort
   dedup by `(routingKey, eventId)` for retried emails.

### Env vars

```
RABBITMQ_HOST=rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
NOTIFICATION_EXCHANGE=employee.events
NOTIFICATION_QUEUE=notification-service-queue
NOTIFICATION_<DOMAIN>_ROUTING_KEY=<domain>.#
```

---

## 5. API gateway

`api-gateway/default.conf.template` proxies the public paths to the upstream
services. The Go side-by-side variants are **not** in the gateway config and
**do not** receive gateway traffic.

### Switch procedure (later, not now)

1. **Verify parity.** Run the service's parity sweep (`market-service-go` has
   `cmd/paritycheck` + `parity.endpoints.example.json`). Sweep must exit 0.
2. **Update the upstream block.** Replace `server market-service:...;` with
   `server market-service-go:${MARKET_SERVICE_GO_PORT};`. A commented-out
   alternative is already in the template next to each Java upstream.
3. **Rebuild api-gateway** so nginx picks up the new template:
   ```powershell
   docker compose -f .\setup\docker-compose.yml up -d --build api-gateway
   ```
4. **Smoke test** through `http://localhost/<feature>` for the affected
   routes.

### Rollback

1. Restore the previous `upstream <name> { server <java-host>:<port>; }` line
   (revert the commented swap).
2. `docker compose -f .\setup\docker-compose.yml up -d --build api-gateway`.
3. Java service is still in the default stack — no need to start it
   separately, it never stopped.

The Java service container stays running the whole time. The switch is a pure
nginx-upstream change; there is no DB cut-over because Go and Java share the
same Postgres.

---

## 6. JWT / service auth

All Go services share the JWT contract from `security-lib` (Java side):

| Property                          | Default      | Source                                      |
|-----------------------------------|--------------|---------------------------------------------|
| Algorithm                         | HS256        | hardcoded                                   |
| `JWT_SECRET`                      | (env)        | shared with all services                    |
| `BANKA_SECURITY_ISSUER`           | `banka1`     | env, must match Java `banka.security.issuer` |
| `BANKA_SECURITY_ID_CLAIM`         | `id`         | env                                         |
| `BANKA_SECURITY_ROLES_CLAIM`      | `roles`      | env                                         |
| `BANKA_SECURITY_PERMISSIONS_CLAIM`| `permissions`| env                                         |
| `BANKA_SECURITY_EXPIRATION_TIME`  | 3600000 (ms) | env, used by user-service-go to mint tokens |

* The `roles` claim is a **single string**, not a list. `ADMIN`, `SUPERVISOR`,
  `AGENT`, `BASIC`, `CLIENT_BASIC`, `CLIENT_TRADING`, `SERVICE`.
* The `SERVICE` role is for service-to-service calls (e.g. order-service →
  `/api/internal/listings/{id}/refresh`). Java protects these endpoints with
  `@PreAuthorize("hasRole('SERVICE')")`; Go protects them with
  `auth.RequireRoles("SERVICE")`.
* Permit-all quirks are explicit, not derived. The Java config in
  `market-service/.../application.properties` whitelists:
  `/stocks/public/**, /stocks/internal/**, /internal/calculate/**,
  /exchange/rates/current, /v3/api-docs/**, /v3/api-docs.yaml,
  /swagger-ui/**, /swagger-ui.html, /actuator/health/liveness,
  /actuator/health/readiness, /actuator/info`. The Go router mirrors this in
  `internal/http/router.go` (search `public := []string{...}`).

### Reference Go implementations

* `market-service-go/internal/auth/auth.go`: `JWTService{ ParseBearer,
  Middleware, RequireRoles, RequirePermissions }`.
* `user-service-go/internal/platform/auth.go`: same shape plus
  `GenerateAccessToken` (user-service is the token issuer).

### Minting a token for local testing

```powershell
$payload = @{ email='admin@banka.rs'; password='admin' } | ConvertTo-Json
$token = (Invoke-RestMethod -Uri 'http://localhost:8081/api/auth/login' -Method POST `
    -ContentType 'application/json' -Body $payload).token
```

For unit tests, sign locally with the same `JWT_SECRET` + claim names — see
`market-service-go/internal/auth/auth_test.go` for the exact pattern.

---

## 7. gRPC

* gRPC is **additive**, never a REST replacement. Public traffic stays HTTP/JSON
  through the gateway. gRPC ports are for internal service-to-service or
  same-host clients.
* Each Go service that exposes gRPC binds it on a dedicated port
  (`GRPC_PORT` env var, see ports table).
* Stubs are committed to the repo so consumers do not need `protoc` installed.

### Proto layout (reference: `market-service-go`)

```
market-service-go/
  proto/
    market/v1/
      market.proto        # source of truth
      market.pb.go        # generated
      market_grpc.pb.go   # generated
  buf.yaml
  buf.gen.yaml
  scripts/generate-proto.ps1
```

### Adding a proto file

1. Drop the `.proto` under `proto/<domain>/v1/<name>.proto`. Use proto3,
   `option go_package = "banka1/<service>/proto/<domain>/v1;<domain>v1";`.
2. Bump `buf.yaml` deps if you import shared protos (none today).
3. Regenerate: `.\market-service-go\scripts\generate-proto.ps1` (Docker-based
   `buf generate`).
4. Commit the generated `.pb.go` / `_grpc.pb.go` next to the source.

### Reproducibility

* `buf.gen.yaml` pins `protoc-gen-go` and `protoc-gen-go-grpc` versions.
* The generator script runs `buf generate` inside a pinned Docker image so the
  host does not need any Go toolchain mismatch.

---

## 8. Observability and logging

### Structured logging

Each Go service uses `log/slog` with `slog.NewJSONHandler` and a baseline
attribute set on every HTTP request:

```json
{
  "time": "2026-05-26T14:00:23Z",
  "level": "INFO",
  "msg": "http request",
  "service": "market-service-go",
  "correlationId": "8d4f1b9c4e92...",
  "method": "GET",
  "path": "/api/listings/15",
  "status": 200,
  "duration_ms": 12,
  "bytes": 1842
}
```

Java equivalent fields come from `company-observability-starter` and the
`X-Correlation-Id` interceptor; the schema is matched on purpose so Loki
queries across Java and Go stay unified.

### Correlation ID middleware

Both Go services (`market-service-go/internal/http/router.go`,
`user-service-go/internal/platform/router.go`) ship the same convention:

* If the request carries `X-Correlation-Id`, **propagate it** (preserve, do not
  overwrite).
* Otherwise **generate a 16-byte hex id** at the edge.
* Always **echo it back** in the response header and inject into `context.Context`
  so handlers and downstream callers can read it via the helper.

For new Go services, copy the four functions from either file (the constant
plus `correlationFromContext`, `newCorrelationID`, `statusRecorder`, and the
`correlationIDMiddleware`/`correlationID` wrapper).

### OpenTelemetry

Java services export traces + metrics via OTLP to `otel-collector:4318`. Go
services do **not** yet wire OTEL — only structured JSON logs collected by
promtail → loki. When you add OTEL to a Go service, use
`go.opentelemetry.io/otel` and read the same envs the Java services use
(`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`, `OTEL_TRACES_EXPORTER`,
`OTEL_METRICS_EXPORTER`).

---

## 9. Healthchecks

| Endpoint                       | Purpose                | Auth | Body shape          |
|--------------------------------|------------------------|------|---------------------|
| `GET /actuator/health/liveness`| docker-compose probe   | none | `{"status":"UP"}`   |
| `GET /actuator/health/readiness`| dependency probe       | none | `{"status":"UP"}` or 503 `{"code":"DB_UNAVAILABLE",...}` |
| `GET /actuator/health`         | aggregate (open in Go, auth-required in Java because not in permit-all) | none in Go | `{"status":"UP"}` |
| `GET /actuator/info`           | service metadata       | none | `{}` (matches Java default) |

Rules:

* **Liveness is lightweight.** Never touches DB/Redis/RabbitMQ. Just confirms
  the process is up and the HTTP server is accepting connections.
* **Readiness verifies what the service actually needs.** For market-service-go
  / user-service-go that is the Postgres ping. If a service also requires
  Redis or RabbitMQ to function, extend readiness — but only what is truly
  required. Do not over-couple readiness to optional integrations.
* **Both Go services must serve all four endpoints.** Docker healthcheck uses
  `/actuator/health/liveness`.

Compose healthcheck stanza for any Go service:

```yaml
healthcheck:
  test:
    - CMD-SHELL
    - "curl -f http://localhost:${SERVICE_PORT}/actuator/health/liveness || exit 1"
  interval: 15s
  timeout: 5s
  retries: 10
  start_period: 20s
```

---

## 10. Operational recipes

### Run the default Java stack

```powershell
docker compose --env-file .\setup\.env -f .\setup\docker-compose.yml up -d
```

### Run the default stack + Go market service for parity

```powershell
docker compose --env-file .\setup\.env -f .\setup\docker-compose.yml `
    --profile go-market up -d
```

### Run only the Go services you care about

```powershell
docker compose --env-file .\setup\.env -f .\setup\docker-compose.yml `
    up -d postgres redis market-service

docker compose --env-file .\setup\.env -f .\setup\docker-compose.yml `
    --profile go-market up -d market-service-go
```

### Add trading-service-go (when it exists)

```powershell
docker compose --env-file .\setup\.env `
    -f .\setup\docker-compose.yml `
    -f .\setup\docker-compose.go-trading.yml `
    --profile go-trading `
    up -d trading-service-go
```

The overlay is in `setup/docker-compose.go-trading.yml`. It is not loaded by
default and not validated unless you pass it via `-f`, so the rest of the
default compose stays clean.

### Tear down

```powershell
docker compose --env-file .\setup\.env -f .\setup\docker-compose.yml `
    --profile go-market down market-service-go
```

Use `down` instead of `stop` when you also want to remove the container.

---

## 11. Validation matrix

For each Go service, run:

```powershell
cd <service-go>
go mod tidy
go test ./...
go build ./...
docker build -t <service-go>-local -f Dockerfile .
```

Compose-config sanity:

```powershell
docker compose --env-file .\setup\.env -f .\setup\docker-compose.yml --profile go-market config > $null
```

Healthcheck smoke:

```powershell
curl http://localhost:18085/actuator/health/liveness   # market-service-go
curl http://localhost:18085/actuator/info
curl http://localhost:8081/actuator/health/liveness    # user-service-go
```

RabbitMQ smoke (uses the management API on 15672, default creds `guest:guest`):

```powershell
curl -u guest:guest http://localhost:15672/api/exchanges/%2F/employee.events
curl -u guest:guest http://localhost:15672/api/queues/%2F/notification-service-queue
```

---

## 12. Don'ts (until explicitly approved)

* **Do not** delete Java services.
* **Do not** change the default compose stack to route gateway traffic to a Go
  service.
* **Do not** start `trading-service-go` business logic.
* **Do not** invent new RabbitMQ exchange names — reuse `employee.events`.
* **Do not** add new public endpoints to the gateway that Java does not also
  serve; Go services are validated as drop-in replacements first.
