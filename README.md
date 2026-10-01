# Seat Reservation at Scale

A JSON HTTP service that sells assigned seats for a show and stays **correct under
load**: it never sells the same seat twice, never lets a user exceed their booking
limit, and never double-charges a retried request — even when thousands of buyers
storm the same show at on-sale time.

- **Stack:** Java 21, Spring Boot 3.3, PostgreSQL (single database), Flyway, Micrometer/Prometheus.
- **Money:** integer paise everywhere, never floats.
- **Correctness mechanism:** a single atomic `UPDATE ... WHERE status='AVAILABLE'`
  guarded by a partial unique index, with a transaction-scoped advisory lock for the
  per-user limit. Details in [WRITEUP.md](./WRITEUP.md).

---

## Quick start (Docker, one command)

```bash
docker compose up --build
```

This starts Postgres and the service wired together. The API comes up on
**http://localhost:8080** and runs its Flyway migration automatically.

Health check:

```bash
curl localhost:8080/actuator/health/readiness
```

## Quick start (local JAR + local Postgres)

```bash
# 1. a Postgres with a 'seats' database and 'seats'/'seats' credentials
createuser -s seats && psql -d postgres -c "ALTER USER seats WITH PASSWORD 'seats';" && createdb -O seats seats

# 2. build and run
./mvnw -DskipTests package
java -jar target/seat-reservation-*.jar \
  --DATABASE_URL=jdbc:postgresql://localhost:5432/seats \
  --DATABASE_USERNAME=seats --DATABASE_PASSWORD=seats
```

---

## The one-command burst (on-sale stampede)

Reproduces the on-sale stampede against any running instance: a **hot-seat storm**
(hundreds of users fighting over one seat), a general stampede, an idempotency
retry test, and a per-user-limit test. It prints the outcome distribution and the
final reconciliation, and exits non-zero if any correctness check fails.

```bash
./burst.sh <BASE_URL>
# or
make burst BASE_URL=<BASE_URL>

# examples
./burst.sh http://localhost:8080
./burst.sh https://seat-reservation.onrender.com 200 500 400
#            ^ base url                           ^seats ^hot-seat users ^stampede users
```

Requires `bash`, `curl`, `jq`. Sample output:

```
>>> Hot-seat storm: 500 users all target seat S1 at once
   S1 winners (201): 1   losers (409): 499   5xx: 0
   ...
   201 confirmed  : 183
   409 declined   : 777
   5xx (MUST BE 0): 0
   declines by reason:
        6 per_user_limit
      771 seat_taken
 RECONCILIATION  available + held + confirmed == total_seats
   sum=200  total=200
 ALL CORRECTNESS CHECKS PASSED
```

---

## API

Identity is **always** derived from the bearer token, never from the request body.
In the default (passthrough) auth mode the token value *is* the user id, so
`Authorization: Bearer alice` authenticates as user `alice`. A fixed token→user map
can be configured via `AUTH_TOKENS` (e.g. `alice-token:alice,bob-token:bob`).

### Create a show — `POST /shows`
```bash
curl -X POST localhost:8080/shows -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer admin' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'
```
Returns `201` with the show `id`, `per_user_limit`, `total_seats`, per-seat status
and counts. `per_user_limit` is optional (defaults to 4).

### Reserve seats — `POST /shows/{id}/reserve`
```bash
curl -X POST localhost:8080/shows/<ID>/reserve -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' -H 'Idempotency-Key: abc123' \
  -d '{"seats":["A12"],"idempotency_key":"abc123"}'
```
- `201` on success with `reservation_id`, `seats`, `amount_paise`, `status`.
- `409` with `code: seat_taken` if any requested seat is already held/confirmed.
- `409` with `code: per_user_limit` if it would exceed the user's limit.
- `409` with `code: idempotency_conflict` if the same key is reused with a different body.
- A retry with the **same key and body** returns the original reservation (not a new one).
- The idempotency key may be supplied as the `Idempotency-Key` header or the
  `idempotency_key` body field (header wins). One of them is required.

**Multi-seat behaviour: all-or-nothing.** If you ask for `["A12","A13"]` and only one
is free, you get `409` and *neither* is taken. This holds under concurrency.

### Cancel — `POST /reservations/{id}/cancel`
Owner-only. Releases the seats back to `available`. A cancel can never free a seat
that now belongs to someone else. `204` on success, `403` if you are not the owner.

### Show state — `GET /shows/{id}`
Returns per-seat status (`available` / `held` / `confirmed`) and counts. The
invariant `available + held + confirmed == total_seats` always holds.

---

## Health, metrics, logs

- **Liveness:** `GET /actuator/health/liveness` — process is up.
- **Readiness:** `GET /actuator/health/readiness` — checks the DB and **fails closed
  (503)** if Postgres is unreachable. This is the platform's health-check path.
- **Metrics:** `GET /actuator/prometheus` — Prometheus scrape endpoint. Key series:
  - `reservations_confirmed_total` (counter)
  - `reservations_declined_total{reason="seat_taken|per_user_limit|idempotency_conflict|seat_not_found"}` (counter)
  - `reservations_idempotent_replay_total` (counter)
  - `seats_available` / `seats_held` / `seats_confirmed` (gauges)
- **Logs:** structured JSON to stdout, every line carrying a `requestId`
  correlation id (also returned in the `X-Request-Id` response header). On Render
  these are visible in the service's **Logs** tab.

---

## Tests

```bash
./mvnw test
```

Tests run against a **real embedded PostgreSQL** (via `zonky embedded-postgres`,
which ships a native pg binary — no Docker required), so they exercise the actual
atomic SQL. Coverage includes:

- **Hot-seat storm:** 500 threads on one seat → exactly 1 confirmed, 499 clean
  declines, 0 errors.
- **Per-user limit under concurrency:** 10 parallel reserves, limit 4 → at most 4.
- **Idempotency:** same key replays the one reservation; different body → conflict.
- **Multi-seat all-or-nothing** and **owner-only cancel + re-bookability**.
- **Full-stack API smoke test** over HTTP (migration, token identity, 409-not-5xx,
  reconciliation, prometheus endpoint).

---

## Deploy (Render)

The repo ships a [`render.yaml`](./render.yaml) blueprint: a Dockerized web service
plus a free managed Postgres, with `DATABASE_*` wired automatically.

1. Push this repo to GitHub.
2. In Render: **New + → Blueprint**, point at the repo, apply.
3. Render builds the Dockerfile, provisions Postgres, and deploys. The readiness
   probe (`/actuator/health/readiness`) gates traffic until the DB is reachable.

The app also accepts a platform-style `DATABASE_URL` (`postgres://user:pass@host/db`);
it is normalized to a JDBC URL at startup, so Railway/Fly/Heroku-style URLs work too.

### Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | local docker-compose PG | JDBC or `postgres://` URL |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `seats` / `seats` | DB credentials (ignored if embedded in URL) |
| `PORT` | `8080` | HTTP port |
| `AUTH_TOKENS` | empty (passthrough) | `tok1:user1,tok2:user2` map; empty = token value is the user id |
| `HOLD_TTL_SECONDS` | `120` | hold expiry window |
| `DB_POOL_SIZE` | `30` | Hikari max pool size |
