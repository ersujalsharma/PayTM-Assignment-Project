# Write-up — Seat Reservation at Scale

## 1. The atomic decision (why it is race-free)

A seat is a single row in the `seats` table with a `status`
(`AVAILABLE` / `HELD` / `CONFIRMED`) and a nullable `reservation_id`. Claiming a
seat is **one conditional SQL statement**:

```sql
UPDATE seats
SET status = 'CONFIRMED', reservation_id = :rid, version = version + 1
WHERE show_id = :showId AND seat_label IN (:labels) AND status = 'AVAILABLE';
```

The win condition is `rows_affected == number_of_requested_seats`. There is no
"read, then decide, then write" window: PostgreSQL evaluates the `WHERE` and
applies the write as one atomic operation. Under contention for one seat, exactly
one transaction's `UPDATE` matches the `status='AVAILABLE'` predicate and writes
the row; every other transaction's `UPDATE` matches **zero** rows (the row is no
longer `AVAILABLE` once the winner commits, and is lock-blocked until then). Losers
see `rows_affected = 0`, we roll back, and return a clean `409`. No seat is ever
written twice, and no loser produces a `5xx`.

**Belt-and-suspenders at the storage layer.** Even if application logic had a bug,
a double active owner is physically impossible because of a partial unique index:

```sql
CREATE UNIQUE INDEX uq_seat_active_owner ON seats (id) WHERE reservation_id IS NOT NULL;
```

combined with the `status='AVAILABLE'` guard — a seat only carries a
`reservation_id` while HELD/CONFIRMED, and the guarded update refuses to overwrite
a non-available seat. The database is the system of record for the decision, not
the application.

### Multi-seat requests and deadlock avoidance

Multi-seat requests are **all-or-nothing** (documented behaviour). Before claiming,
we take row locks on every requested seat **in a deterministic order**:

```sql
SELECT * FROM seats WHERE show_id = :showId AND seat_label IN (:labels)
ORDER BY seat_label
FOR UPDATE;
```

The service also sorts the requested labels before issuing any statement. Because
**every** caller acquires locks in the same global order (sorted `seat_label`), no
cyclic wait can form, so two overlapping multi-seat requests cannot deadlock — one
simply waits for the other. Then the single guarded `UPDATE` either flips **all**
requested seats from `AVAILABLE` or, if `rows_affected` ≠ requested count, we abort
the whole transaction. Rolling back erases any partial claim, so the user gets
every seat or none.

## 2. Idempotency

**Where the key lives.** Each reservation row carries
`UNIQUE (user_id, show_id, idempotency_key)` plus a `request_hash` (SHA-256 of the
show id + the sorted seat labels).

**How exactly-once is enforced.** It is the unique constraint, not an application
check, that guarantees exactly-once. The flow:

1. Look up `(user, show, key)`. If a row exists, it is a replay (see below).
2. Otherwise insert the reservation row and `flush`. If a concurrent request with
   the same key inserted first, Postgres raises a unique-violation; we catch it,
   re-read the winner, and treat our request as a replay. So even a dead-heat on
   the same key produces exactly one reservation.
3. Only then do we run the guarded seat `UPDATE`.

To close the read-then-insert window entirely under heavy same-user concurrency, a
transaction-scoped **advisory lock** on `(show, user)` is taken at the top of the
transaction (see §5), so a single user's parallel requests serialize and observe
each other's committed state.

**Same key, different body.** On a replay we compare the stored `request_hash` with
the current request's hash. Equal → return the original reservation unchanged
(`201` with the same `reservation_id`). Different → `409` with
`code: idempotency_conflict`. This is why a retry of `["A12"]` returns the original,
but reusing that key for `["A13"]` is rejected.

## 3. Holds and expiry

The default model confirms directly (`CONFIRMED`), which is the strongest guarantee
for the correctness bar. The schema and code also support a **time-boxed hold**:
reservations carry `expires_at`, and a scheduled `HoldExpirySweeper` periodically
moves expired `HELD` reservations to `EXPIRED` and releases their seats back to
`AVAILABLE`.

Crucially, release is guarded on `reservation_id`:

```sql
UPDATE seats SET status='AVAILABLE', reservation_id=NULL WHERE reservation_id = :rid;
```

so a release/expiry/cancel can only touch seats **still owned by that reservation**.
It can never resurrect a seat that has since been confirmed to someone else. A
released seat returns to `AVAILABLE` and is immediately re-bookable (proven by the
cancel test). Cancel is owner-only and idempotent.

## 4. Consistency vs availability under a partition

This is a **CP** system (consistent + partition-tolerant). The correctness bar — no
double-sell, exact reconciliation, exactly-once — is a strong-consistency
requirement, and we meet it by making a single PostgreSQL instance the arbiter of
every seat decision inside serializable-enough transactions (row locks + guarded
updates + unique constraints).

Under a network partition between the app and the database, the app **loses
availability on purpose**: the readiness probe checks the DB and fails closed
(`503`), so the platform stops routing traffic rather than let the service guess.
We never trade correctness for availability — selling a seat we cannot durably
record would be worse than returning an error. Horizontal scaling is safe: multiple
app instances can run against the one database because all the invariants are
enforced *in* the database, not in app memory. The database is the consistency
boundary; scaling it (read replicas, partitioning by show) would be the next step
if a single primary became the bottleneck.

## 5. The one race that a naive design misses — and the fix

The per-user limit is the subtle one. "Count the user's held seats, then insert if
under the limit" is itself a read-then-write race: under `READ COMMITTED`, ten
concurrent requests from one user each read "0 held" before any commits, all pass
the check, and all succeed — the limit is blown. The seat-level `FOR UPDATE` does
**not** save you here, because each request targets a *different* seat, so there is
no shared row to serialize on.

The fix is a transaction-scoped advisory lock keyed on the user+show:

```sql
SELECT pg_advisory_xact_lock(hashtextextended(:showId || ':' || :userId, 0));
```

A single user's concurrent reserves for one show now serialize behind this lock, so
the limit count always sees committed state. It is released automatically at
transaction end and is deadlock-free (one lock per user+show). It does **not**
throttle the system — different users and shows hash to different keys and run fully
in parallel; only one user's own parallel requests line up. The concurrency test
(`perUserLimit_holdsUnderConcurrency`) fired 10 parallel reserves and failed before
this fix (got 10), passes after (≤4).

## 6. Observability — what I would get paged for at 2am

- **`reservations_declined_total{reason="seat_taken"}` spiking toward 100%** of
  attempts → the show is effectively sold out, or a hot-seat storm; expected at
  on-sale, alarming if sustained for an unsold show (possible stuck seats).
- **Any `5xx`** → by design there should be none under contention; a non-zero rate
  means a genuine bug or the DB is in trouble.
- **Readiness flapping / `seats_available` flatlining while declines climb** → the
  DB connection pool is exhausted or Postgres is unreachable; readiness fails closed
  and the page fires.
- **Reconciliation drift** — `seats_available + seats_held + seats_confirmed` not
  equal to the known total for a show → the most serious possible alert, it means
  the core invariant broke; this should never happen and would be page-now.
- **`reservations_idempotent_replay_total` climbing fast** → clients are retrying
  heavily, usually a sign of upstream timeouts or a client bug; worth looking at.

Every log line carries a `requestId` (propagated from `X-Request-Id` or generated)
so a single reservation can be traced across the burst. Logs are structured JSON on
stdout, captured by the platform.

## 7. AI usage (directed vs decided)

Honest disclosure, as asked:

- **I decided the architecture.** The core choices — a guarded conditional `UPDATE`
  as the atomic primitive, the partial unique index as a storage-level backstop,
  deterministic lock ordering for multi-seat deadlock avoidance, the advisory lock
  for the per-user-limit race, the unique-constraint-driven idempotency, CP with
  fail-closed readiness — are deliberate engineering decisions, not generated
  defaults.
- **AI directed the mechanics.** I used an AI coding assistant to scaffold the
  Spring Boot boilerplate (entities, repositories, DTOs, config wiring), to write
  the first drafts of the burst script and tests, and to speed up turning the design
  into code. It also helped diagnose two real issues the tests surfaced.
- **The verification was real and caught real bugs.** The per-user-limit race was
  found by a failing concurrency test (10/10 got through) and fixed with the
  advisory lock; the Prometheus endpoint was silently disabled and found via the
  condition-evaluation report. I did not take green-on-first-try for granted — I ran
  the burst against a live instance until every invariant held.

I can extend this live: the depth (why each mechanism is race-free, where it would
break, how to scale past one primary) is genuinely mine.

## 8. What I would do next

- **Payments & true holds:** make reserve create a `HELD` reservation with a short
  TTL, confirm on payment success, let the sweeper reclaim abandoned holds — the
  schema and sweeper already support this.
- **Scale the data layer:** partition seats by show, add read replicas for
  `GET /shows/{id}`, and shard hot shows if a single primary saturates.
- **Richer metrics:** per-show gauges (bounded cardinality), reserve latency
  histograms, and a Grafana dashboard + alert rules matching §6.
- **Auth hardening:** replace the demo token map with real JWT verification /
  introspection; the identity seam (`TokenAuthenticator`) is already isolated.
- **Rate limiting / queueing** at the edge to smooth on-sale spikes before they hit
  the database.
