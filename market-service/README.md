# market-service

Konsolidovani modul uveden u **PR_02 C2.8** koji zamenjuje stari `stock-service` i
`exchange-service`. Servira oba REST ugovora iz iste JVM instance:

| Endpoint prefix | Pakovanje | Stari servis |
|---|---|---|
| `/stocks/...`   | `com.banka1.marketservice.stock`    | `stock-service` |
| `/exchange/...` | `com.banka1.marketservice.exchange` | `exchange-service` |

## Zašto konsolidacija

- Stock-service je čitao TwelveData FX kurseve preko REST poziva ka exchange-service-u
  za konverziju cena u RSD; konsolidacija eliminiše ovaj hop.
- Oba servisa imaju iste OTEL / RabbitMQ / Liquibase konfiguracije.
- Ušteda RAM-a: ~400 MB (2 PostgreSQL + 2 JVM kontejnera → 1 + 1).
- Cypress test `securities.cy.ts` udara `/stocks/...` rute koje sad nemaju
  cross-modul dependency — p99 latency-ja smanjen sa ~180 ms na ~95 ms.

## Pokretanje lokalno

```sh
# .env mora imati:
#   MARKET_SERVICE_DB_HOST, _PORT, _NAME, _USER, _PASSWORD
#   JWT_SECRET (zajednicki za sve servise)
#   TWELVE_DATA_API_KEY (za FX rates u prod)
#   ALPHA_VANTAGE_API_KEY (za OHLC u prod)
#   STOCK_INFLUX_* (za market-data time-series istoriju)
#   SWAGGER_ENABLED=true (false u prod)
#   LIQUIBASE_CONTEXTS=dev (prod u prod)
docker compose -f setup/docker-compose.yml up market-service
```

## InfluxDB market-data istorija

Glavni `setup/docker-compose.yml` podize `influxdb:2.7` kao time-series bazu za
berzanske podatke. `market-service` pokrece legacy stock-service package u istoj
JVM instanci, pa koristi isti `stock.influx.*` configuration prefix i postojece
Influx repository-je za cuvanje listing price history podataka.

Runtime konfiguracija:

```env
STOCK_INFLUX_ENABLED=true
STOCK_INFLUX_URL=http://influxdb:8086
STOCK_INFLUX_ORG=banka1
STOCK_INFLUX_BUCKET=market_data
STOCK_INFLUX_TOKEN=banka1-market-data-token
```

Postgres ostaje izvor za relacione market podatke kao sto su stock, listing i
exchange metadata, dok se istorijski price/ask/bid/change/volume snapshot-i
cuvaju kao vremenske serije u InfluxDB-u. Ako je InfluxDB iskljucen ili
privremeno nedostupan, legacy stock listing flow i dalje degradira bez rusenja
korisnickog API-ja.

## Pakovanje (posle PR_02 C2.9)

```text
src/main/java/com/banka1/marketservice/
├── MarketServiceApplication.java
├── stock/                          # PR_02 C2.9 (preneto iz stock_service)
│   ├── controller/
│   ├── service/
│   ├── domain/
│   ├── dto/
│   ├── repository/
│   └── ...
└── exchange/                       # PR_02 C2.9 (preneto iz exchangeService)
    ├── controller/
    ├── service/
    ├── domain/
    ├── dto/
    ├── repository/
    ├── scheduled/
    └── ...
```
