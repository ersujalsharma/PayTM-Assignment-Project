package money.paytm.seatreservation.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reservation_seats")
@IdClass(ReservationSeat.PK.class)
public class ReservationSeat {

    @Id
    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Id
    @Column(name = "seat_id", nullable = false)
    private UUID seatId;

    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    protected ReservationSeat() {
    }

    public ReservationSeat(UUID reservationId, UUID seatId, String seatLabel) {
        this.reservationId = reservationId;
        this.seatId = seatId;
        this.seatLabel = seatLabel;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getSeatId() {
        return seatId;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public static class PK implements Serializable {
        private UUID reservationId;
        private UUID seatId;

        public PK() {
        }

        public PK(UUID reservationId, UUID seatId) {
            this.reservationId = reservationId;
            this.seatId = seatId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(reservationId, pk.reservationId)
                    && Objects.equals(seatId, pk.seatId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reservationId, seatId);
        }
    }
}
