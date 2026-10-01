package money.paytm.seatreservation.repo;

import money.paytm.seatreservation.domain.Show;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, UUID> {
}
