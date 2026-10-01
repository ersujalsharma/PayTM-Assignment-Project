package money.paytm.seatreservation.repo;

import money.paytm.seatreservation.domain.Reservation;
import money.paytm.seatreservation.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByUserIdAndShowIdAndIdempotencyKey(
            String userId, UUID showId, String idempotencyKey);

    /**
     * Count seats a user currently holds actively (HELD or CONFIRMED) for a show.
     * Used to enforce the per-user limit inside the reservation transaction.
     */
    @Query("""
            SELECT COALESCE(SUM(r.seatCount), 0) FROM Reservation r
            WHERE r.userId = :userId AND r.showId = :showId
              AND r.status IN (money.paytm.seatreservation.domain.ReservationStatus.HELD,
                               money.paytm.seatreservation.domain.ReservationStatus.CONFIRMED)
            """)
    int sumActiveSeatsForUser(@Param("userId") String userId, @Param("showId") UUID showId);

    /**
     * Expired holds: HELD reservations whose expires_at has passed. Fetched by
     * the sweeper to release their seats.
     */
    List<Reservation> findTop500ByStatusAndExpiresAtBefore(
            ReservationStatus status, OffsetDateTime cutoff);
}
