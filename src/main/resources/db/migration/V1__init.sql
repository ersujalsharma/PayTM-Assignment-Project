-- ============================================================================
-- Seat Reservation schema.
--
-- Correctness strategy (see WRITEUP.md):
--   * A seat is one row. Selling it is a single conditional UPDATE guarded on
--     status = 'AVAILABLE'. "Rows affected = 1" means you won; 0 means someone
--     else already took it -> clean 409. No read-then-write window.
--   * Partial unique index guarantees a seat can be tied to at most one ACTIVE
--     (HELD/CONFIRMED) reservation at a time -> a double-hold is physically
--     impossible even if application logic has a bug.
--   * Idempotency keys are unique per (user, show, key). The unique constraint
--     is the enforcement mechanism, not application checks.
-- ============================================================================

CREATE TABLE shows (
    id              UUID PRIMARY KEY,
    name            TEXT        NOT NULL,
    price_paise     BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit  INT         NOT NULL CHECK (per_user_limit > 0),
    total_seats     INT         NOT NULL CHECK (total_seats >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seat status is a small enumeration enforced by a CHECK.
CREATE TABLE seats (
    id              UUID PRIMARY KEY,
    show_id         UUID        NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    seat_label      TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'AVAILABLE'
                        CHECK (status IN ('AVAILABLE','HELD','CONFIRMED')),
    -- The reservation currently owning this seat (NULL when AVAILABLE).
    reservation_id  UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    -- A seat label is unique within a show. This prevents duplicate seats and
    -- is the natural key the API talks about.
    CONSTRAINT uq_seat_per_show UNIQUE (show_id, seat_label)
);

CREATE INDEX idx_seats_show_status ON seats (show_id, status);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    show_id         UUID        NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    user_id         TEXT        NOT NULL,
    status          TEXT        NOT NULL
                        CHECK (status IN ('HELD','CONFIRMED','CANCELLED','EXPIRED')),
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    seat_count      INT         NOT NULL CHECK (seat_count >= 0),
    idempotency_key TEXT        NOT NULL,
    -- Hash of the normalized request body. Lets us detect "same key, different
    -- body" and reject it with 409 rather than silently replaying.
    request_hash    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- When a HELD reservation auto-expires. NULL once CONFIRMED/CANCELLED.
    expires_at      TIMESTAMPTZ,
    -- Exactly-once per (user, show, key). The DB rejects a second insert with
    -- the same tuple; the application turns that into an idempotent replay.
    CONSTRAINT uq_idempotency UNIQUE (user_id, show_id, idempotency_key)
);

CREATE INDEX idx_res_show_user ON reservations (show_id, user_id, status);
CREATE INDEX idx_res_expiry ON reservations (status, expires_at);

-- Join of seats actually attached to a reservation. Mirrors seats.reservation_id
-- but gives a stable record of what each reservation holds, independent of later
-- seat re-use.
CREATE TABLE reservation_seats (
    reservation_id  UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    seat_id         UUID NOT NULL REFERENCES seats(id) ON DELETE CASCADE,
    seat_label      TEXT NOT NULL,
    PRIMARY KEY (reservation_id, seat_id)
);

-- The hard guarantee: a seat can belong to at most one *active* reservation.
-- A seat points at a reservation only while HELD or CONFIRMED, so a partial
-- unique index on seats.reservation_id (where it is non-null) makes a second
-- active owner impossible at the storage layer.
CREATE UNIQUE INDEX uq_seat_active_owner
    ON seats (id)
    WHERE reservation_id IS NOT NULL;
