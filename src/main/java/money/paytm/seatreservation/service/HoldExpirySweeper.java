package money.paytm.seatreservation.service;

import money.paytm.seatreservation.domain.Reservation;
import money.paytm.seatreservation.domain.ReservationStatus;
import money.paytm.seatreservation.repo.ReservationRepository;
import money.paytm.seatreservation.repo.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Periodically expires HELD reservations past their TTL and returns their seats
 * to AVAILABLE. CONFIRMED reservations are never touched, so a release can never
 * resurrect a seat already confirmed to someone.
 *
 * Note: in the current design reservations are created CONFIRMED directly, so
 * the sweeper is a safety net for any HELD model/extension. It is written to be
 * correct and idempotent regardless.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final ReservationRepository reservations;
    private final SeatRepository seats;

    public HoldExpirySweeper(ReservationRepository reservations, SeatRepository seats) {
        this.reservations = reservations;
        this.seats = seats;
    }

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval-ms:5000}")
    @Transactional
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Reservation> expired = reservations
                .findTop500ByStatusAndExpiresAtBefore(ReservationStatus.HELD, now);
        if (expired.isEmpty()) {
            return;
        }
        for (Reservation r : expired) {
            seats.releaseSeatsOfReservation(r.getId());
            r.setStatus(ReservationStatus.EXPIRED);
            r.setExpiresAt(null);
        }
        reservations.saveAll(expired);
        log.info("expired {} held reservations", expired.size());
    }
}
