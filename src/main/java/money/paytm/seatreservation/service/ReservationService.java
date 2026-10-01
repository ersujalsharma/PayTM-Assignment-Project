package money.paytm.seatreservation.service;

import money.paytm.seatreservation.api.dto.ReservationResponse;
import money.paytm.seatreservation.domain.*;
import money.paytm.seatreservation.observability.ReservationMetrics;
import money.paytm.seatreservation.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core reservation logic. Correctness guarantees (all enforced inside ONE
 * database transaction):
 *
 *  1. No double-sell: seats are claimed with a single conditional UPDATE
 *     (SeatRepository.claimSeats) guarded on status='AVAILABLE'. If the number
 *     of rows updated != number requested, some seat was taken -> roll back,
 *     decline 409. Backed by a partial unique index so a double active owner is
 *     physically impossible.
 *
 *  2. Multi-seat is ALL-OR-NOTHING. We lock all requested seats FOR UPDATE in
 *     sorted (seat_label) order first; deterministic ordering prevents deadlock.
 *     Then the single guarded UPDATE either claims every seat or we abort.
 *
 *  3. Per-user limit: counted inside the transaction after acquiring locks, so
 *     concurrent reserves for the same user serialize on their existing rows.
 *
 *  4. Idempotency: the reservation row carries a UNIQUE(user, show, key). We try
 *     to insert; a duplicate key means a prior attempt exists -> we replay it
 *     (same body) or reject (different body, 409). The unique constraint, not an
 *     application read, is the exactly-once mechanism.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationMetrics metrics;
    private final long holdTtlSeconds;

    public ReservationService(ShowRepository shows,
                              SeatRepository seats,
                              ReservationRepository reservations,
                              ReservationSeatRepository reservationSeats,
                              ReservationMetrics metrics,
                              money.paytm.seatreservation.config.AppProperties props) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.metrics = metrics;
        this.holdTtlSeconds = props.getReservation().getHoldTtlSeconds();
    }

    @Transactional
    public ReservationResponse reserve(UUID showId, String userId,
                                       List<String> requestedSeats, String idempotencyKey) {
        Show show = shows.findById(showId)
                .orElseThrow(() -> new NotFoundException("show not found: " + showId));

        // Normalize + de-dup requested seats, keep sorted (also the lock order).
        List<String> labels = requestedSeats.stream()
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        String requestHash = RequestHasher.hash(showId, labels);

        // ---- Idempotency fast-path: has this (user, show, key) been seen? ----
        Optional<Reservation> existing = reservations
                .findByUserIdAndShowIdAndIdempotencyKey(userId, showId, idempotencyKey);
        if (existing.isPresent()) {
            return handleExistingKey(existing.get(), requestHash);
        }

        // ---- Lock the seats in deterministic order (deadlock-free). ----
        List<Seat> locked = seats.lockSeatsForUpdate(showId, labels);
        if (locked.size() != labels.size()) {
            // Some requested label doesn't exist in this show.
            metrics.declined(DeclineReason.SEAT_NOT_FOUND);
            throw new ReservationDeclinedException(DeclineReason.SEAT_NOT_FOUND,
                    "one or more seats do not exist in this show");
        }

        // ---- Per-user limit, evaluated under the locks we now hold. ----
        int alreadyHeld = reservations.sumActiveSeatsForUser(userId, showId);
        if (alreadyHeld + labels.size() > show.getPerUserLimit()) {
            metrics.declined(DeclineReason.PER_USER_LIMIT);
            throw new ReservationDeclinedException(DeclineReason.PER_USER_LIMIT,
                    "per-user seat limit exceeded (limit=" + show.getPerUserLimit()
                            + ", held=" + alreadyHeld + ", requested=" + labels.size() + ")");
        }

        // ---- Insert the reservation row first (claims the idempotency key). ----
        UUID reservationId = UUID.randomUUID();
        long amount = show.getPricePaise() * labels.size();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(holdTtlSeconds);
        Reservation reservation = new Reservation(reservationId, showId, userId,
                ReservationStatus.CONFIRMED, amount, labels.size(),
                idempotencyKey, requestHash, expiresAt);

        try {
            reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // Lost an idempotency-key race: another concurrent request with the
            // same (user, show, key) inserted first. Resolve as a replay.
            Reservation winner = reservations
                    .findByUserIdAndShowIdAndIdempotencyKey(userId, showId, idempotencyKey)
                    .orElseThrow(() -> e);
            return handleExistingKey(winner, requestHash);
        }

        // ---- The atomic claim: AVAILABLE -> CONFIRMED for exactly our seats. ----
        int claimed = seats.claimSeats(showId, labels, SeatStatus.CONFIRMED, reservationId);
        if (claimed != labels.size()) {
            // All-or-nothing: at least one seat was already taken. Roll back the
            // whole transaction (reservation row + any partial claim vanish).
            metrics.declined(DeclineReason.SEAT_TAKEN);
            throw new ReservationDeclinedException(DeclineReason.SEAT_TAKEN,
                    "one or more requested seats are no longer available");
        }

        // Record the seats attached to this reservation.
        Map<String, UUID> labelToId = locked.stream()
                .collect(Collectors.toMap(Seat::getSeatLabel, Seat::getId));
        List<ReservationSeat> rs = labels.stream()
                .map(l -> new ReservationSeat(reservationId, labelToId.get(l), l))
                .collect(Collectors.toList());
        reservationSeats.saveAll(rs);

        metrics.confirmed();
        log.info("reservation confirmed id={} seats={} amountPaise={}",
                reservationId, labels, amount);

        return new ReservationResponse(reservationId, showId, userId, labels, amount,
                ReservationStatus.CONFIRMED.name().toLowerCase());
    }

    private ReservationResponse handleExistingKey(Reservation existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            // Same key, different body -> conflict.
            metrics.declined(DeclineReason.IDEMPOTENCY_CONFLICT);
            throw new ReservationDeclinedException(DeclineReason.IDEMPOTENCY_CONFLICT,
                    "idempotency key reused with a different request body");
        }
        // True replay: return the original reservation unchanged.
        metrics.idempotentReplay();
        List<String> seatLabels = reservationSeats.findByReservationId(existing.getId())
                .stream().map(ReservationSeat::getSeatLabel).sorted().collect(Collectors.toList());
        return new ReservationResponse(existing.getId(), existing.getShowId(),
                existing.getUserId(), seatLabels, existing.getAmountPaise(),
                existing.getStatus().name().toLowerCase());
    }

    /**
     * Cancel a reservation. Only the owner may cancel. Releases seats back to
     * AVAILABLE. Guarded by reservation ownership and status so a cancel can
     * never free a seat that now belongs to someone else.
     */
    @Transactional
    public void cancel(UUID reservationId, String userId) {
        Reservation r = reservations.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("reservation not found: " + reservationId));
        if (!r.getUserId().equals(userId)) {
            throw new ForbiddenException("you can only cancel your own reservations");
        }
        if (r.getStatus() == ReservationStatus.CANCELLED
                || r.getStatus() == ReservationStatus.EXPIRED) {
            return; // idempotent cancel
        }
        // Release only seats still owned by this reservation.
        seats.releaseSeatsOfReservation(reservationId);
        r.setStatus(ReservationStatus.CANCELLED);
        r.setExpiresAt(null);
        reservations.save(r);
        log.info("reservation cancelled id={} user={}", reservationId, userId);
    }
}
