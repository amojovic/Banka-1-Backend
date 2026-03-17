# Client Service – Upravljanje klijentima banke

Mikroservis za CRUD operacije nad klijentima banke. Servis je deo Banka 1 backend sistema i dostupan je **isključivo zaposlenima** (putem JWT tokena).

---

## Docker Compose

### Opcija 1: Hibridni režim (preporučeno za razvoj)

Pokreni samo bazu u Dockeru:

```bash
cd client-service
docker compose up -d postgres
```

Zatim pokrenite aplikaciju iz IntelliJ (`ClientServiceApplication`). Aplikacija koristi fallback vrednosti iz `.env` fajla.

### Opcija 2: Puni Docker paket

```bash
./gradlew clean bootJar
docker compose up -d --build
```

Servis je dostupan na `http://localhost:8083`.

**Korisne komande:**
```bash
docker compose logs -f client-service   # Praćenje logova
docker compose down                     # Gašenje svih kontejnera
docker compose down -v                  # Gašenje + brisanje baze
```

---

## Environment Variables

Kreirati `.env` fajl u `client-service/` folderu (primer u `.env.example`):

| Varijabla | Opis | Primer |
|---|---|---|
| `CLIENT_SERVICE_PORT` | Port na kome servis sluša | `8083` |
| `CLIENT_SERVICE_DOCKER_PORT` | Eksterni Docker port | `8084` |
| `CLIENT_SERVICE_URL` | Javni URL servera (za OpenAPI dokumentaciju) | `http://localhost:8083` |
| `JWT_SECRET` | HMAC-SHA256 secret (isti kao user-service) | `my_secret_key` |
| `CLIENT_SERVICE_DB_HOST` | Hostname baze podataka | `localhost` |
| `CLIENT_SERVICE_DB_PORT` | Port baze podataka | `5433` |
| `CLIENT_SERVICE_DB_NAME` | Naziv baze podataka | `clientdb` |
| `CLIENT_SERVICE_DB_USER` | Korisničko ime baze | `postgres` |
| `CLIENT_SERVICE_DB_PASSWORD` | Lozinka baze | `postgres` |
| `RABBITMQ_HOST` | Hostname RabbitMQ brokera | `localhost` |
| `RABBITMQ_PORT` | Port RabbitMQ brokera | `5672` |
| `RABBITMQ_USERNAME` | Korisničko ime RabbitMQ | `rabbit` |
| `RABBITMQ_PASSWORD` | Lozinka RabbitMQ | `rabbit` |
| `NOTIFICATION_QUEUE` | Naziv RabbitMQ queue-a za notifikacije | `notification-service-queue` |
| `NOTIFICATION_EXCHANGE` | Naziv RabbitMQ exchange-a | `employee.events` |
| `NOTIFICATION_ROUTING_KEY` | Routing key za email notifikacije | `employee.#` |

---

## API Endpoints

Svi endpointi zahtevaju Bearer JWT token zaposlenog u headeru:
```
Authorization: Bearer <token>
```

### Pretraga klijenata

```
GET /customers?ime=Petar&prezime=Petrovic&email=&page=0&size=10
```

Filteri su opcioni. Rezultati su sortirani abecedno po prezimenu.

### Globalna pretraga

```
GET /customers/search?query=petar&page=0&size=10
```

### Kreiranje klijenta (AGENT+)

```
POST /customers
Content-Type: application/json

{
  "ime": "Petar",
  "prezime": "Petrović",
  "datumRodjenja": 641520000000,
  "pol": "M",
  "email": "petar@primer.rs",
  "brojTelefona": "+381641234567",
  "adresa": "Njegoševa 25, Beograd",
  "jmbg": "2005990710123"
}
```

### Izmena podataka klijenta

```
PUT /customers/{id}
Content-Type: application/json

{
  "prezime": "Novi Prezime",
  "email": "novi.email@primer.rs",
  "adresa": "Nova adresa 10"
}
```

> JMBG i password se **ne mogu menjati**. Sva polja su opciona – šalju se samo ona koja se menjaju.
> Pri promeni emaila automatski se proverava jedinstvenost.

### Brisanje klijenta (ADMIN)

```
DELETE /customers/{id}
```

### JMBG Lookup – samo SERVICE token

```
GET /customers/jmbg/{jmbg}
```

> Ovaj endpoint je dostupan **isključivo** za interne pozive između servisa (SERVICE token).
> JWT mora imati claim `roles: "SERVICE"`.

**Response:**
```json
{ "id": 42 }
```

---

## Baza podataka i Liquibase

Projekat koristi PostgreSQL i Liquibase za migracije šeme.

**Pravila migracija:**
- NIKADA ne menjati postojeće `.sql` fajlove koji su već pokrenuti
- Za izmenu šeme kreirati novi fajl (npr. `002-dodaj-polje.sql`) i prijaviti ga u `db.changelog-master.xml`

---

## Pokretanje testova

```bash
./gradlew test
```

Test izveštaj: `build/reports/tests/test/index.html`
Coverage izveštaj: `build/reports/jacoco/test/html/index.html`
