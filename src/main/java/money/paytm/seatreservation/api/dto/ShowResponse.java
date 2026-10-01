package money.paytm.seatreservation.api.dto;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long price_paise,
        int per_user_limit,
        int total_seats,
        Counts counts,
        List<SeatView> seats
) {
    public record SeatView(String seat, String status) {
    }

    public record Counts(long available, long held, long confirmed, int total_seats) {
    }
}
