package money.paytm.seatreservation.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import money.paytm.seatreservation.service.DeclineReason;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Prometheus-facing counters. The seats-available gauge is registered per-show
 * lazily (see SeatGaugeRegistrar) so it reflects live DB state.
 */
@Component
public class ReservationMetrics {

    private final Counter confirmed;
    private final Counter idempotentReplays;
    private final Map<DeclineReason, Counter> declines = new EnumMap<>(DeclineReason.class);

    public ReservationMetrics(MeterRegistry registry) {
        this.confirmed = Counter.builder("reservations_confirmed_total")
                .description("Reservations confirmed")
                .register(registry);
        this.idempotentReplays = Counter.builder("reservations_idempotent_replay_total")
                .description("Reservation requests served as idempotent replays")
                .register(registry);
        for (DeclineReason reason : DeclineReason.values()) {
            declines.put(reason, Counter.builder("reservations_declined_total")
                    .description("Reservations declined, by reason")
                    .tag("reason", reason.tag())
                    .register(registry));
        }
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void declined(DeclineReason reason) {
        declines.get(reason).increment();
    }
}
