package money.paytm.seatreservation.service;

/** Maps to HTTP 403 (e.g. trying to cancel someone else's reservation). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
