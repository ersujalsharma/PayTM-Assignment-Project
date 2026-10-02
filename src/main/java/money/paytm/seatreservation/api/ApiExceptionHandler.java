package money.paytm.seatreservation.api;

import money.paytm.seatreservation.service.ForbiddenException;
import money.paytm.seatreservation.service.NotFoundException;
import money.paytm.seatreservation.service.ReservationDeclinedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates exceptions into clean JSON responses. The key property for the
 * correctness bar: domain declines are 4xx, never 5xx. Only genuinely
 * unexpected errors become 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private Map<String, Object> body(String error, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        if (code != null) {
            m.put("code", code);
        }
        m.put("message", message);
        return m;
    }

    @ExceptionHandler(ReservationDeclinedException.class)
    public ResponseEntity<Map<String, Object>> declined(ReservationDeclinedException ex) {
        // All contention outcomes are 409 Conflict - a domain decline, not an error.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("declined", ex.getReason().tag(), ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body("not_found", null, ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body("forbidden", null, ex.getMessage()));
    }

    @ExceptionHandler(money.paytm.seatreservation.service.CapacityExhaustedException.class)
    public ResponseEntity<Map<String, Object>> saturated(
            money.paytm.seatreservation.service.CapacityExhaustedException ex) {
        // Overload / transient saturation -> 429 (a 4xx), NOT a 5xx. Tell the
        // client to back off and retry; the request was not processed, so no
        // seat was taken.
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(body("busy", "capacity_exhausted", ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("bad_request", null, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        // Genuine server error. Log at error with the correlation id already in MDC.
        log.error("unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("internal_error", null, "unexpected error"));
    }
}
