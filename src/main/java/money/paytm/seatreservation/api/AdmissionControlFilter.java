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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission control / load shedding for the expensive write path.
 *
 * <p>Why this exists: a tens-of-thousands stampede on a small instance will peg
 * the CPU and exhaust the DB connection pool. Left unchecked, the process does
 * unbounded concurrent work and eventually falls over (the free-tier "CPU 100%,
 * server failed" case). The fix is to admit only as much concurrent write work
 * as the instance and DB pool can actually sustain.
 *
 * <p>How it works: a bounded semaphore caps the number of reservation requests
 * processed at once. A request that cannot get a permit waits up to
 * {@code max-wait-ms}; if a slot still does not free up, it is rejected
 * immediately with {@code 429 Too Many Requests} + {@code Retry-After} rather
 * than queueing forever and dragging the whole instance down.
 *
 * <p>Correctness properties this preserves:
 * <ul>
 *   <li>A shed request is a 4xx, not a 5xx, so the "zero server errors" bar
 *       still holds under overload.</li>
 *   <li>A shed request never entered the service, so it touches no seat and no
 *       reservation - the client simply retries.</li>
 * </ul>
 *
 * <p>Scope: only the write endpoints ({@code POST .../reserve} and
 * {@code .../cancel}) are gated. Reads, health probes, and metrics are never
 * shed, so the platform's liveness/readiness checks keep working even while the
 * service is shedding write load.
 *
 * <p>Ordering: runs just after {@link money.paytm.seatreservation.auth.AuthFilter}
 * so the request-id/correlation context is already in place for any shed response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdmissionControlFilter extends OncePerRequestFilter {

    /** Permits == max reservation requests processed concurrently. */
    private final Semaphore permits;

    /** How long a request will wait for a permit before being shed. */
    private final long maxWaitMs;

    /** Current number of in-flight (admitted, not yet finished) write requests. */
    private final AtomicInteger inFlight = new AtomicInteger();

    private final Counter shed;
    private final Counter admitted;

    public AdmissionControlFilter(
            @Value("${app.admission.max-concurrent:50}") int maxConcurrent,
            @Value("${app.admission.max-wait-ms:800}") long maxWaitMs,
            MeterRegistry registry) {
        // Fair semaphore so waiters are served roughly first-come-first-served;
        // this avoids starving early arrivals during a sustained storm.
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWaitMs = maxWaitMs;

        this.shed = Counter.builder("admission_shed_total")
                .description("Write requests shed with 429 by admission control")
                .register(registry);
        this.admitted = Counter.builder("admission_admitted_total")
                .description("Write requests admitted by admission control")
                .register(registry);
        Gauge.builder("admission_in_flight", inFlight, AtomicInteger::get)
                .description("Write requests currently being processed")
                .register(registry);
    }

    /** Only the mutating reservation endpoints are subject to shedding. */
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
        // Non-gated traffic (reads, health, metrics) passes straight through.
        if (!isGated(request)) {
            chain.doFilter(request, response);
            return;
        }

        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                // At capacity: shed cleanly with 429 + Retry-After. The request
                // never reaches the service, so no state is touched.
                shed.increment();
                writeBusy(response);
                return;
            }
            // Admitted: hold the permit for the duration of the request.
            admitted.increment();
            inFlight.incrementAndGet();
            chain.doFilter(request, response);
        } catch (InterruptedException ie) {
            // Interrupted while waiting for a permit: treat as "busy, retry".
            Thread.currentThread().interrupt();
            writeBusy(response);
        } finally {
            if (acquired) {
                inFlight.decrementAndGet();
                permits.release();
            }
        }
    }

    /** Standard 429 body telling the client to back off and retry. */
    private void writeBusy(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", "1");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"busy\",\"code\":\"admission_shed\"," +
                "\"message\":\"service at capacity, retry shortly\"}");
    }
}
