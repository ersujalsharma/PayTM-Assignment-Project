package money.paytm.seatreservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the seat-reservation service.
 *
 * <p>{@code @EnableScheduling} activates the background {@code HoldExpirySweeper}
 * (reclaims expired holds) and the periodic seat-availability gauge refresh.
 * Schema is applied by Flyway on startup; configuration comes from
 * {@code application.yml} + environment variables (see README).
 */
@SpringBootApplication
@EnableScheduling
public class SeatReservationApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeatReservationApplication.class, args);
    }
}
