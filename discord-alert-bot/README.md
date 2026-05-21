# Discord Alert Bot

Small Node.js service that receives Alertmanager webhook POSTs and forwards
each alert as a Discord DM to the developer running this stack.

It is part of the Monitoring / Alerting pipeline:

```
service crashes
  -> Prometheus rule fires (prometheus-rules.yml)
    -> Alertmanager (setup/alertmanager.yml)
      -> POST http://discord-alert-bot:9094/alert
        -> Discord API
          -> DM to DEVELOPER_DISCORD_ID
```

## How to set up the Discord bot account (one-time, manual)

You do this once in your browser to obtain a token. The bot account is shared
by the team — only the `DEVELOPER_DISCORD_ID` differs per developer.

1. Open <https://discord.com/developers/applications> and log in with Discord.
2. Click **New Application**, name it (e.g. `Banka-1 Alerts`), create.
3. Left sidebar → **Bot** → **Reset Token** → copy the token (shown only once).
   This is `DISCORD_BOT_TOKEN`.
4. Left sidebar → **OAuth2** → **URL Generator**:
   - Scopes: tick `bot`
   - Bot Permissions: leave empty (DMs do not require a guild permission)
5. Open the generated URL in a new tab, pick the Discord server where the team
   is, click **Authorize**. The bot now appears as a member (offline until you
   `docker compose up` this stack).

To obtain your own `DEVELOPER_DISCORD_ID`:

1. Discord → **User Settings** → **Advanced** → enable **Developer Mode**.
2. Right-click your avatar → **Copy User ID**.

## Docker Compose

This service is started together with the rest of the stack:

```bash
docker compose -f ./setup/docker-compose.yml up -d discord-alert-bot
```

It is also pulled in automatically by `alertmanager` via `depends_on`.

To build the image standalone from the repo root:

```bash
docker build -f discord-alert-bot/Dockerfile -t discord-alert-bot .
```

## Environment variables

| Variable | Required | Description |
|---|---|---|
| `DISCORD_BOT_TOKEN` | yes | Bot token from the Discord Developer Portal. |
| `DEVELOPER_DISCORD_ID` | yes | Numeric Discord user ID that will receive the DMs. |
| `PORT` | no | HTTP port the bot listens on (default `9094`). |

Set these in `setup/.env` (see `setup/.env.example` for the template).

## Endpoints (internal, banka-network only)

### `POST /alert`

Consumes the Alertmanager webhook payload. Example body (truncated):

```json
{
  "status": "firing",
  "alerts": [
    {
      "status": "firing",
      "labels": {
        "alertname": "BankingServiceDown",
        "severity": "critical",
        "service": "banking-service"
      },
      "annotations": {
        "summary": "banking-service is not reporting metrics",
        "description": "banking-service has not exported any JVM metrics for 2+ minutes."
      },
      "startsAt": "2026-05-19T10:00:00Z"
    }
  ]
}
```

Response: `{ "status": "ok", "delivered": <n> }` on success.

### `GET /health`

Returns `200 { "ready": true }` once the bot is logged in to Discord,
`503 { "ready": false }` while still connecting.

## Local manual test

After `docker compose up`, fake an alert from your shell:

```bash
curl -X POST http://localhost:9093/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d '[{"labels":{"alertname":"ManualTest","severity":"warning"},"annotations":{"summary":"hello from curl"}}]'
```

Alertmanager will forward it to the bot, which will DM you on Discord.

## Tests

```bash
cd discord-alert-bot
npm install
npm test
```

Uses the built-in Node test runner; no extra framework needed.
