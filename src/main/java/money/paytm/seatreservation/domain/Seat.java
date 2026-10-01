package money.paytm.seatreservation.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(nullable = false)
    private long version;

    protected Seat() {
    }

    public Seat(UUID id, UUID showId, String seatLabel) {
        this.id = id;
        this.showId = showId;
        this.seatLabel = seatLabel;
        this.status = SeatStatus.AVAILABLE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getShowId() {
        return showId;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public long getVersion() {
        return version;
    }
}
