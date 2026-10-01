package money.paytm.seatreservation.repo;

import money.paytm.seatreservation.domain.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, ReservationSeat.PK> {
    List<ReservationSeat> findByReservationId(UUID reservationId);
}
