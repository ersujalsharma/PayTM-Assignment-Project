package money.paytm.seatreservation.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission control (load shedding) for the expensive write path.
 *
 * The single most important defence against CPU/connection saturation under a
 * 20k stampede: cap how many reservation requests are processed CONCURRENTLY to
 * a number the instance + DB pool can actually sustain. Everything in excess
 * waits briefly for a slot; if none frees up in time, it gets an immediate
 * 429 Too Many Requests + Retry-After instead of piling onto the CPU and taking
 * the whole process down.
 *
 * This turns "unbounded work -> 100% CPU -> crash" into "bounded work -> stable
 * throughput + clean shedding". The shed requests are a 4xx (client should
 * retry), so the zero-5xx correctness bar still holds, and no seat is touched by
 * a request that never ran.
 *
 * Only the write path (POST /shows/** reserve, cancel) is gated. Reads, health,
 * and metrics are always allowed so the platform's probes never get shed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after AuthFilter sets request id
public class AdmissionControlFilter extends OncePerRequestFilter {

    private final Semaphore permits;
    private final long maxWaitMs;
    private final AtomicInteger inFlight = new AtomicInteger();

    private final Counter shed;
    private final Counter admitted;

    public AdmissionControlFilter(
            @Value("${app.admission.max-concurrent:50}") int maxConcurrent,
            @Value("${app.admission.max-wait-ms:800}") long maxWaitMs,
            MeterRegistry registry) {
        // Fair semaphore so waiters are served roughly FIFO (avoids starvation
        // of early arrivals during a sustained storm).
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWaitMs = maxWaitMs;
        this.shed = Counter.builder("admission_shed_total")
                .description("Requests shed (429) by admission control")
                .register(registry);
        this.admitted = Counter.builder("admission_admitted_total")
                .description("Requests admitted by admission control")
                .register(registry);
        Gauge.builder("admission_in_flight", inFlight, AtomicInteger::get)
                .description("Reservation requests currently being processed")
                .register(registry);
    }

    private boolean isGated(HttpServletRequest req) {
        if (!"POST".equals(req.getMethod())) {
            return false;
        }
        String uri = req.getRequestURI();
        return uri.contains("/reserve") || uri.contains("/cancel");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isGated(request)) {
            chain.doFilter(request, response);
            return;
        }

        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                shed.increment();
                response.setStatus(HttpStatus_TOO_MANY_REQUESTS);
                response.setHeader("Retry-After", "1");
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"busy\",\"code\":\"admission_shed\"," +
                        "\"message\":\"service at capacity, retry shortly\"}");
                return;
            }
            admitted.increment();
            inFlight.incrementAndGet();
            chain.doFilter(request, response);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            response.setStatus(HttpStatus_TOO_MANY_REQUESTS);
            response.setHeader("Retry-After", "1");
        } finally {
            if (acquired) {
                inFlight.decrementAndGet();
                permits.release();
            }
        }
    }

    private static final int HttpStatus_TOO_MANY_REQUESTS = 429;
}
