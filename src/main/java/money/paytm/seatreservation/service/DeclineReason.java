package money.paytm.seatreservation.service;

/**
 * Why a reservation attempt was declined. Drives the
 * reservations_declined_total{reason=...} metric and the API error code.
 */
public enum DeclineReason {
    SEAT_TAKEN("seat_taken"),
    PER_USER_LIMIT("per_user_limit"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict"),
    SEAT_NOT_FOUND("seat_not_found");

    private final String tag;

    DeclineReason(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
