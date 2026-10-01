package money.paytm.seatreservation.service;

/**
 * A clean domain decline (maps to HTTP 409). NOT a server error.
 * These are expected outcomes under contention, never 5xx.
 */
public class ReservationDeclinedException extends RuntimeException {
    private final DeclineReason reason;

    public ReservationDeclinedException(DeclineReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DeclineReason getReason() {
        return reason;
    }
}
