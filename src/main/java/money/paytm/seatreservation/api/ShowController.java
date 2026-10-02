package money.paytm.seatreservation.api;

import jakarta.validation.Valid;
import money.paytm.seatreservation.api.dto.*;
import money.paytm.seatreservation.auth.CurrentUser;
import money.paytm.seatreservation.config.AppProperties;
import money.paytm.seatreservation.service.ForbiddenException;
import money.paytm.seatreservation.service.ReservationService;
import money.paytm.seatreservation.service.ShowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * HTTP API for shows and reservations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /shows} - create a show with its seats (admin).</li>
 *   <li>{@code GET  /shows/{id}} - per-seat status and reconciliation counts.</li>
 *   <li>{@code POST /shows/{id}/reserve} - reserve seat(s) for the authenticated
 *       user, idempotently.</li>
 *   <li>{@code POST /reservations/{id}/cancel} - owner-only cancel.</li>
 * </ul>
 *
 * <p><b>Identity is always derived from the auth token</b> (via {@code AuthFilter}
 * -&gt; {@link CurrentUser}), never from the request body. A client therefore
 * cannot act "as" another user by putting a user id in the payload, and can only
 * cancel its own reservations.
 *
 * <p>Error mapping lives in {@code ApiExceptionHandler}: domain declines are 409,
 * overload is 429, and only genuinely unexpected failures are 5xx.
 */
@RestController
public class ShowController {

    private final ShowService showService;
    private final ReservationService reservationService;
    private final AppProperties props;

    public ShowController(ShowService showService,
                          ReservationService reservationService,
                          AppProperties props) {
        this.showService = showService;
        this.reservationService = reservationService;
        this.props = props;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest req) {
        ShowResponse resp = showService.createShow(req, props.getReservation().getDefaultPerUserLimit());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/shows/{id}")
    public ShowResponse getShow(@PathVariable UUID id) {
        return showService.getShow(id);
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID id,
            @Valid @RequestBody ReserveRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey) {

        String userId = requireUser();
        // Header wins over body for the idempotency key; one of them is required.
        String idemKey = (headerKey != null && !headerKey.isBlank())
                ? headerKey.trim()
                : (req.idempotency_key() != null ? req.idempotency_key().trim() : null);
        if (idemKey == null || idemKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required (header Idempotency-Key or body idempotency_key)");
        }

        ReservationResponse resp = reservationService.reserve(id, userId, req.seats(), idemKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        String userId = requireUser();
        reservationService.cancel(id, userId);
        return ResponseEntity.noContent().build();
    }

    private String requireUser() {
        String userId = CurrentUser.get();
        if (userId == null) {
            throw new ForbiddenException("missing or invalid bearer token");
        }
        return userId;
    }
}
