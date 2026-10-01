package money.paytm.seatreservation.repo;

import money.paytm.seatreservation.domain.Seat;
import money.paytm.seatreservation.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    /**
     * Lock the requested seats FOR UPDATE in a deterministic order (seat_label).
     * Deterministic ordering across all callers is what prevents deadlock when
     * two multi-seat requests overlap: everyone acquires locks in the same
     * global order, so no cyclic wait can form.
     *
     * We ONLY use the returned rows to validate existence/labels; the actual
     * state transition is the guarded UPDATE below. The row locks serialize
     * competing transactions for the same seats.
     */
    @Query(value = """
            SELECT * FROM seats
            WHERE show_id = :showId AND seat_label IN (:labels)
            ORDER BY seat_label
            FOR UPDATE
            """, nativeQuery = true)
    List<Seat> lockSeatsForUpdate(@Param("showId") UUID showId,
                                  @Param("labels") List<String> labels);

    /**
     * The atomic claim. A single conditional UPDATE: flip AVAILABLE -> target
     * status and attach the reservation, but only for seats currently AVAILABLE.
     * Returns the number of rows actually transitioned.
     *
     * If this equals the number of requested seats, the caller won them all.
     * Otherwise some seat was already HELD/CONFIRMED and the caller loses
     * cleanly (we roll back and return 409). There is no read-then-write gap:
     * the WHERE clause is evaluated and the write applied as one statement
     * under the row locks held above.
     */
    @Modifying
    @Query(value = """
            UPDATE seats
            SET status = :#{#status.name()},
                reservation_id = :reservationId,
                version = version + 1
            WHERE show_id = :showId
              AND seat_label IN (:labels)
              AND status = 'AVAILABLE'
            """, nativeQuery = true)
    int claimSeats(@Param("showId") UUID showId,
                   @Param("labels") List<String> labels,
                   @Param("status") SeatStatus status,
                   @Param("reservationId") UUID reservationId);

    /**
     * Release seats owned by a reservation back to AVAILABLE. Guarded on
     * reservation_id so a release can never touch a seat that now belongs to
     * someone else (prevents "resurrecting" a confirmed seat).
     */
    @Modifying
    @Query(value = """
            UPDATE seats
            SET status = 'AVAILABLE',
                reservation_id = NULL,
                version = version + 1
            WHERE reservation_id = :reservationId
            """, nativeQuery = true)
    int releaseSeatsOfReservation(@Param("reservationId") UUID reservationId);

    List<Seat> findByShowIdOrderBySeatLabel(UUID showId);

    long countByShowIdAndStatus(UUID showId, SeatStatus status);

    long countByStatus(SeatStatus status);

    /**
     * Transaction-scoped advisory lock keyed on (showId, userId). All of a
     * single user's concurrent reserve attempts for the same show serialize on
     * this lock, so the per-user-limit count below always sees committed state.
     * Released automatically at transaction end. Different users/shows hash to
     * different keys and proceed in parallel, so this does not serialize the
     * whole system - only a single user's own concurrent requests.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    void acquireUserShowLock(@Param("key") String key);
}
