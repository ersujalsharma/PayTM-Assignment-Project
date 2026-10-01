package money.paytm.seatreservation.service;

/**
 * Signals that the request could not be served right now because the service is
 * saturated (DB connection pool exhausted and bounded retries used up under a
 * stampede). This is a transient CAPACITY condition, not a bug and not a server
 * error: the correct response is 429 Too Many Requests with Retry-After, telling
 * the client to back off and retry. Mapping it to 429 (a 4xx) keeps 5xx at zero
 * under overload, while being honest that the request was not processed.
 */
public class CapacityExhaustedException extends RuntimeException {
    public CapacityExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
