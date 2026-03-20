# Client Service – Upravljanje klijentima banke

Mikroservis za CRUD operacije nad klijentima banke. Servis je deo Banka 1 backend sistema i dostupan je **isključivo zaposlenima** (putem JWT tokena).

---

## Docker Compose

### Opcija 1: Hibridni režim (preporučeno za razvoj)

Pokrenite samo bazu i RabbitMQ u Dockeru:

```bash
cd client-service
docker compose up -d postgres rabbitmq
```

Zatim pokrenite aplikaciju iz IntelliJ (`ClientServiceApplication`). Aplikacija koristi fallback vrednosti iz `.env` fajla.

### Opcija 2: Puni Docker paket (ceo sistem)

```bash
docker compose -f setup/docker-compose.yml up -d --build client-service
```

Servis je dostupan na `http://localhost:8083` (direktno) ili `http://localhost/clients/` (kroz API gateway).

**Korisne komande:**
```bash
docker compose -f setup/docker-compose.yml logs -f client-service   # Praćenje logova
docker compose -f setup/docker-compose.yml down                     # Gašenje svih kontejnera
docker compose -f setup/docker-compose.yml down -v                  # Gašenje + brisanje baze
```

---

## Environment Variables

Kreirati `.env` fajl u `setup/` folderu (primer u `setup/.env.example`):

| Varijabla | Opis | Primer |
|---|---|---|
| `CLIENT_SERVICE_PORT` | Port na kome servis sluša | `8083` |
| `CLIENT_SERVICE_DB_HOST` | Hostname baze podataka | `postgres_client` |
| `CLIENT_SERVICE_DB_PORT` | Interni port baze (unutar Docker mreže) | `5432` |
| `CLIENT_SERVICE_DB_EX_PORT` | Eksterni Docker port baze | `5435` |
| `CLIENT_SERVICE_DB_NAME` | Naziv baze podataka | `clientdb` |
| `CLIENT_SERVICE_DB_USER` | Korisničko ime baze | `postgres` |
| `CLIENT_SERVICE_DB_PASSWORD` | Lozinka baze | `postgres` |
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

Projekat koristi PostgreSQL i Liquibase za migracije šeme. Hibernate je postavljen na `validate` mod — ne kreira tabele automatski.

**Pravila migracija:**
- NIKADA ne menjati postojeće `.sql` fajlove koji su već pokrenuti
- Za izmenu šeme kreirati novi fajl (npr. `002-dodaj-polje.sql`) i prijaviti ga u `db.changelog-master.xml`

---

## Pokretanje testova

```bash
./gradlew :client-service:test
```

Coverage izveštaj: `client-service/build/reports/jacoco/test/html/index.html`
