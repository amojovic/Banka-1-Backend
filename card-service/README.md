# Card Service – Upravljanje karticama banke

Mikroservis za upravljanje debitnim karticama povezanim sa bankovnim računima. Servis je deo Banka 1 backend sistema i podržava kreiranje, blokiranje, deblokiranje i deaktivaciju kartica za klijente i zaposlene.

Za opšte informacije o podešavanju projekta, pre-push hookovima i dodavanju novih servisa pogledati [root README](../README.md).

---

## Docker Compose

### Opcija 1: Hibridni režim (preporučeno za razvoj)

Pokrenite samo bazu i RabbitMQ u Dockeru:

```bash
cd card-service
docker compose up -d postgres_card rabbitmq
```

Zatim pokrenite aplikaciju iz IntelliJ (`CardServiceApplication`). Aplikacija koristi fallback vrednosti iz `.env` fajla.

### Opcija 2: Puni Docker paket (ceo sistem)

```bash
docker compose -f setup/docker-compose.yml up -d --build card-service
```

Servis je dostupan na `http://localhost:8087` (direktno) ili `http://localhost/api/cards/` (kroz API gateway).

**Korisne komande:**
```bash
docker compose -f setup/docker-compose.yml logs -f card-service   # Praćenje logova
docker compose -f setup/docker-compose.yml down                    # Gašenje svih kontejnera
docker compose -f setup/docker-compose.yml down -v                 # Gašenje + brisanje baze
```

---

## Environment Variables

Kreirati `.env` fajl u `setup/` folderu (primer u `setup/.env.example`) ili lokalno u `card-service/` (primer u `card-service/.env.example`):

| Varijabla | Opis | Primer |
|---|---|---|
| `CARD_SERVER_PORT` | Port na kome servis sluša | `8087` |
| `CARD_DB_HOST` | Hostname baze podataka | `postgres_card` |
| `CARD_DB_PORT` | Interni port baze (unutar Docker mreže) | `5432` |
| `CARD_DB_EX_PORT` | Eksterni Docker port baze | `5437` |
| `CARD_DB_NAME` | Naziv baze podataka | `card_db` |
| `CARD_DB_USER` | Korisničko ime baze | `postgres` |
| `CARD_DB_PASSWORD` | Lozinka baze | `postgres` |
| `JWT_SECRET` | HMAC-SHA256 secret (isti kao ostali servisi) | `my_secret_key` |
| `RABBITMQ_HOST` | Hostname RabbitMQ brokera | `rabbitmq` |
| `RABBITMQ_PORT` | Port RabbitMQ brokera | `5672` |
| `RABBITMQ_USERNAME` | Korisničko ime RabbitMQ | `guest` |
| `RABBITMQ_PASSWORD` | Lozinka RabbitMQ | `guest` |
| `NOTIFICATION_QUEUE` | Naziv RabbitMQ queue-a za notifikacije | `notification-service-queue` |
| `NOTIFICATION_EXCHANGE` | Naziv RabbitMQ exchange-a | `employee.events` |
| `NOTIFICATION_ROUTING_KEY` | Routing key za email notifikacije | `employee.#` |

---

## API Endpoints

Svi endpointi zahtevaju Bearer JWT token u headeru:
```
Authorization: Bearer <token>
```

### Health check

```
GET /actuator/health
```

> Endpointi ispod su planirani i još nisu implementirani.

### Klijentski endpointi

```
GET  /api/cards/client/{clientId}        – lista svih kartica klijenta (maskirani brojevi)
GET  /api/cards/{cardNumber}             – detalji kartice
PUT  /api/cards/{cardNumber}/block       – blokiranje sopstvene kartice
PUT  /api/cards/{cardNumber}/limit       – promena limita kartice
```

### Endpointi za kreiranje

```
POST /api/cards/auto                     – automatsko kreiranje (interno, event-driven)
POST /api/cards/request                  – zahtev klijenta za novu karticu
POST /api/cards/request/business         – zahtev vlasnika poslovnog računa
```

### Zaposleni endpointi

```
GET  /api/cards/account/{accountNumber}  – sve kartice vezane za račun
PUT  /api/cards/{cardNumber}/block       – blokiranje kartice
PUT  /api/cards/{cardNumber}/unblock     – deblokiranje kartice (samo zaposleni)
PUT  /api/cards/{cardNumber}/deactivate  – deaktivacija kartice (ireverzibilno, samo zaposleni)
```

---

## Baza podataka i Liquibase

Projekat koristi PostgreSQL (`card_db`) i Liquibase za migracije šeme. Hibernate je postavljen na `validate` mod — ne kreira tabele automatski.

**Pravila migracija:**
- NIKADA ne menjati postojeće `.sql` fajlove koji su već pokrenuti
- Za izmenu šeme kreirati novi fajl (npr. `002-dodaj-polje.sql`) i prijaviti ga u `db.changelog-master.xml`

---

## Pokretanje testova

```bash
./gradlew :card-service:test
```

Coverage izveštaj: `card-service/build/reports/jacoco/test/html/index.html`
