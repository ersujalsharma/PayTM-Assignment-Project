package money.paytm.seatreservation.api.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Reserve request body. Note: there is intentionally NO user field here.
 * Identity is always derived from the auth token. The idempotency key may be
 * supplied in the body or via the Idempotency-Key header (header wins).
 */
public record ReserveRequest(
        @NotEmpty List<String> seats,
        String idempotency_key
) {
}
