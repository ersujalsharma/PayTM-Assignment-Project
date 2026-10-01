package money.paytm.seatreservation.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Readiness dependency check. Grouped under the "readiness" probe in
 * application.yml so /actuator/health/readiness fails closed (503) when the DB
 * is unreachable, while liveness stays up. This is what the platform's
 * readiness gate should poll.
 */
@Component("db")
public class DbHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public DbHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection c = dataSource.getConnection()) {
            // isValid runs a lightweight check against the live connection.
            if (c.isValid(2)) {
                return Health.up().withDetail("database", "reachable").build();
            }
            return Health.down().withDetail("database", "invalid-connection").build();
        } catch (Exception e) {
            return Health.down(e).withDetail("database", "unreachable").build();
        }
    }
}
