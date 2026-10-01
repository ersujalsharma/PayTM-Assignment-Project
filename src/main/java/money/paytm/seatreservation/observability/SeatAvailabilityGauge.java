package money.paytm.seatreservation.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import money.paytm.seatreservation.domain.SeatStatus;
import money.paytm.seatreservation.repo.SeatRepository;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes seats_available as a gauge. It aggregates AVAILABLE seats across all
 * shows and is refreshed on a short schedule so Prometheus always sees a value
 * that reconciles with the API's GET /shows/{id} counts.
 *
 * A total-level gauge keeps cardinality bounded (no per-show label explosion
 * under a stampede that creates many shows). Per-show counts remain available
 * via the API's reconciliation endpoint.
 */
@Component
public class SeatAvailabilityGauge {

    private final SeatRepository seats;
    private final AtomicLong available = new AtomicLong(0);
    private final AtomicLong held = new AtomicLong(0);
    private final AtomicLong confirmed = new AtomicLong(0);

    public SeatAvailabilityGauge(MeterRegistry registry, SeatRepository seats) {
        this.seats = seats;
        Gauge.builder("seats_available", available, AtomicLong::get)
                .description("Seats currently available across all shows")
                .register(registry);
        Gauge.builder("seats_held", held, AtomicLong::get)
                .description("Seats currently held across all shows")
                .register(registry);
        Gauge.builder("seats_confirmed", confirmed, AtomicLong::get)
                .description("Seats currently confirmed across all shows")
                .register(registry);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.metrics.gauge-refresh-ms:2000}")
    public void refresh() {
        available.set(countAll(SeatStatus.AVAILABLE));
        held.set(countAll(SeatStatus.HELD));
        confirmed.set(countAll(SeatStatus.CONFIRMED));
    }

    private long countAll(SeatStatus status) {
        return seats.countByStatus(status);
    }
}
