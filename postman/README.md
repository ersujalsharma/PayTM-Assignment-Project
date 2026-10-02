# Postman collection

End-to-end API tests for the seat reservation service.

## Files
- `seat-reservation.postman_collection.json` — the requests + assertions.
- `seat-reservation.live.postman_environment.json` — targets the deployed Render URL.
- `seat-reservation.local.postman_environment.json` — targets `http://localhost:8080`.

## How to run
1. In Postman: **Import** → drop in the collection and one environment file.
2. Select the environment (top-right): **Live (Render)** or **Local**.
3. Either click through the requests top-to-bottom, or use the **Collection Runner**
   to run them all in order.

The requests chain automatically: creating a show stores `show_id`, the first
reserve stores `reservation_id`, and later requests reuse them. Each request has
test assertions (green = pass).

## What it verifies
- Create show, get state, reconciliation invariant (`available+held+confirmed == total`).
- Identity is token-derived: a reserve with **no token → 403**; `Bearer alice` acts as `alice`.
- **Idempotency:** same key+body replays the same reservation; **same key + different body → 409 `idempotency_conflict`**.
- **No double-sell:** a second user on a taken seat → 409 `seat_taken` (never 5xx).
- **Multi-seat all-or-nothing:** `[A1,A2]` with A1 taken → 409 and A2 stays free.
- **Owner-only cancel:** another user → 403; owner → 204; released seat is re-bookable.
- **Per-user limit (4):** the 5th hold → 409 `per_user_limit`.
- Prometheus metrics endpoint exposes the reservation counters/gauges.

## Notes
- Auth uses the service's **passthrough mode** (bearer token value = user id). If the
  deployment is configured with a fixed `AUTH_TOKENS` map, change the `aliceToken` /
  `bobToken` / `adminToken` environment values to real tokens.
- On the free tier the first request may **cold-start (~30s)**. Run `0. Health - readiness`
  once and wait for 200 before running the rest.
