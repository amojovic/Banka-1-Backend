# Account Service – Upravljanje računima

Mikroservis za upravljanje bankovnim računima klijenata. Podržava tekuće račune (checking) i devizne račune (FX), transakcije, kartice i pretragu računa. Deo Banka 1 backend sistema, izgrađen na **Spring Boot 4.0.3** sa **PostgreSQL** bazom i **Liquibase** migracijama.

---

## Docker Compose

### Opcija 1: Hibridni režim (preporučeno za razvoj)

Pokrenite samo infrastrukturu u Dockeru:

```bash
cd account-service
docker compose -f docker-compose_intelij.yml up -d
```

Zatim pokrenite aplikaciju iz IntelliJ (`AccountServiceApplication`). Aplikacija koristi fallback vrednosti iz `application.properties`.

### Opcija 2: Puni Docker paket (ceo sistem)

```bash
docker compose -f setup/docker-compose.yml up -d --build account-service
```

Servis je dostupan na `http://localhost:8084` (direktno) ili `http://localhost/accounts/` (kroz API gateway).

**Korisne komande:**
```bash
docker compose -f setup/docker-compose.yml logs -f account-service   # Praćenje logova
docker compose -f setup/docker-compose.yml down                      # Gašenje svih kontejnera
docker compose -f setup/docker-compose.yml down -v                   # Gašenje + brisanje baze
```

---

## Environment Variables

Kreirati `.env` fajl u `setup/` folderu (primer u `setup/.env.example`):

| Varijabla | Opis | Primer |
|---|---|---|
| `ACCOUNT_SERVER_PORT` | Port na kome servis sluša | `8084` |
| `ACCOUNT_DB_HOST` | Hostname baze podataka | `postgres_account` |
| `ACCOUNT_DB_INTERNAL_PORT` | Interni port baze (unutar Docker mreže) | `5432` |
| `ACCOUNT_DB_EX_PORT` | Eksterni Docker port baze | `5436` |
| `ACCOUNT_DB_NAME` | Naziv baze podataka | `accountdb` |
| `ACCOUNT_DB_USER` | Korisničko ime baze | `postgres` |
| `ACCOUNT_DB_PASSWORD` | Lozinka baze | `postgres` |
| `JWT_SECRET` | HMAC-SHA256 secret (isti kao ostali servisi) | `my_secret_key` |
| `SERVICES_USER_URL` | URL employee-service-a (za proveru klijenta) | `http://employee-service` |
| `CLIENT_SERVER_PORT` | Port client-service-a | `8083` |
| `RABBITMQ_HOST` | Hostname RabbitMQ brokera | `rabbitmq` |
| `RABBITMQ_PORT` | Port RabbitMQ brokera | `5672` |
| `RABBITMQ_USERNAME` | Korisničko ime RabbitMQ | `guest` |
| `RABBITMQ_PASSWORD` | Lozinka RabbitMQ | `guest` |
| `NOTIFICATION_QUEUE` | Naziv RabbitMQ queue-a za notifikacije | `notification-service-queue` |
| `NOTIFICATION_EXCHANGE` | Naziv RabbitMQ exchange-a | `employee.events` |
| `NOTIFICATION_ROUTING_KEY` | Routing key za notifikacije | `employee.#` |

---

## API Endpoints

Svi endpointi zahtevaju Bearer JWT token u headeru:
```
Authorization: Bearer <token>
```

### Klijentski endpointi (`/client/*`) — zahteva `CLIENT_BASIC` rolu

#### Pregled sopstvenih računa

```
GET /client/accounts?page=0&size=10
```

#### Detalji računa

```
GET /client/accounts/{id}
```

#### Transakcije po računu

```
GET /client/accounts/{id}/transactions?page=0&size=10
```

#### Kartice po računu

```
GET /client/accounts/{id}/cards?page=0&size=10
```

#### Promena naziva računa

```
PATCH /client/accounts/{id}/name
Content-Type: application/json

{
  "name": "Moj štedni račun"
}
```

#### Promena limita računa

```
PATCH /client/accounts/{id}/limit
Content-Type: application/json

{
  "limit": 500.00
}
```

#### Nova uplata

```
POST /client/payments
Content-Type: application/json

{
  "fromAccountId": 1,
  "toAccountNumber": "RS35105008123123123173",
  "amount": 100.00,
  "description": "Uplata stanarine"
}
```

#### Odobravanje transakcije

```
POST /client/transactions/{id}/approve
Content-Type: application/json

{
  "approved": true
}
```

---

### Zaposleni endpointi (`/employee/*`) — zahteva `BASIC` rolu

#### Kreiranje tekućeg računa (checking)

```
POST /employee/accounts/checking
Content-Type: application/json

{
  "clientId": 42,
  "currencyCode": "RSD",
  "initialDeposit": 1000.00
}
```

#### Kreiranje deviznog računa (FX)

```
POST /employee/accounts/fx
Content-Type: application/json

{
  "clientId": 42,
  "currencyCode": "EUR",
  "initialDeposit": 500.00
}
```

#### Pretraga svih računa

```
GET /employee/accounts?imeVlasnikaRacuna=Petar&prezimeVlasnikaRacuna=Petrovic&accountNumber=&page=0&size=10
```

#### Ažuriranje kartice

```
PUT /employee/cards/{id}
Content-Type: application/json

{
  "status": "ACTIVE"
}
```

---

## Baza podataka i Liquibase

Projekat koristi PostgreSQL i Liquibase za migracije šeme. Hibernate je postavljen na `validate` mod — ne kreira tabele automatski.

**Pravila migracija:**
- NIKADA ne menjati postojeće `.sql` fajlove koji su već pokrenuti
- Za izmenu šeme kreirati novi numerisani fajl (npr. `003-dodaj-polje.sql`) i prijaviti ga u `db.changelog-master.xml`

---

## Pokretanje testova

```bash
./gradlew :account-service:test
```

Coverage izveštaj: `account-service/build/reports/jacoco/test/html/index.html`
