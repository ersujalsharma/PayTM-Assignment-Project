package money.paytm.seatreservation.api.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record CreateShowRequest(
        @NotBlank String name,
        @NotEmpty List<@NotBlank String> seats,
        @Min(0) long price_paise,
        // optional; defaults to app config when null
        @Min(1) Integer per_user_limit
) {
}
