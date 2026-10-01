package money.paytm.seatreservation;

import money.paytm.seatreservation.api.dto.CreateShowRequest;
import money.paytm.seatreservation.api.dto.ReservationResponse;
import money.paytm.seatreservation.api.dto.ShowResponse;
import money.paytm.seatreservation.service.DeclineReason;
import money.paytm.seatreservation.service.ReservationDeclinedException;
import money.paytm.seatreservation.service.ReservationService;
import money.paytm.seatreservation.service.ShowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ReservationConcurrencyTest extends AbstractEmbeddedPgTest {

    @Autowired
    ShowService showService;
    @Autowired
    ReservationService reservationService;

    private ShowResponse freshShow(int seatCount) {
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            seats.add("S" + i);
        }
        return showService.createShow(
                new CreateShowRequest("concurrency-" + UUID.randomUUID(), seats, 25000, 4), 4);
    }

    private void assertReconciles(UUID showId, int total) {
        ShowResponse s = showService.getShow(showId);
        long sum = s.counts().available() + s.counts().held() + s.counts().confirmed();
        assertEquals(total, sum, "reconciliation invariant violated");
        assertEquals(total, s.counts().total_seats());
    }

    @Test
    void hotSeatStorm_exactlyOneWinner_noDoubleSell_no5xx() throws Exception {
        ShowResponse show = freshShow(50);
        UUID showId = show.id();
        int contenders = 500;

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            final int u = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    reservationService.reserve(showId, "user" + u, List.of("S1"), "key-" + u);
                    confirmed.incrementAndGet();
                } catch (ReservationDeclinedException d) {
                    assertEquals(DeclineReason.SEAT_TAKEN, d.getReason());
                    declined.incrementAndGet();
                } catch (Exception e) {
                    // Transient serialization failures are retried by the service's
                    // transaction; anything surfacing here that is not a clean
                    // decline counts as an error (would be a 5xx).
                    errors.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, confirmed.get(), "exactly one user must win seat S1");
        assertEquals(contenders - 1, declined.get(), "all others must be cleanly declined");
        assertEquals(0, errors.get(), "no server errors (5xx) allowed");
        assertReconciles(showId, 50);

        ShowResponse after = showService.getShow(showId);
        assertEquals(1, after.counts().confirmed());
        assertEquals(49, after.counts().available());
    }

    @Test
    void perUserLimit_holdsUnderConcurrency() throws Exception {
        ShowResponse show = freshShow(50);
        UUID showId = show.id();
        int attempts = 10; // limit is 4

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            final int seat = 10 + i; // distinct seats so only the limit blocks
            futures.add(pool.submit(() -> {
                try {
                    go.await();
                    reservationService.reserve(showId, "greedy", List.of("S" + seat), "g-" + seat);
                    confirmed.incrementAndGet();
                } catch (ReservationDeclinedException ignored) {
                    // per-user-limit or seat-taken decline, both fine
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, errors.get(), "no server errors allowed");
        assertTrue(confirmed.get() <= 4,
                "user must never hold more than the per-user limit (got " + confirmed.get() + ")");
        assertReconciles(showId, 50);
    }

    @Test
    void idempotency_sameKeyReservesOnce_differentBodyConflicts() {
        ShowResponse show = freshShow(10);
        UUID showId = show.id();

        ReservationResponse first = reservationService.reserve(
                showId, "alice", List.of("S1"), "same-key");
        ReservationResponse replay = reservationService.reserve(
                showId, "alice", List.of("S1"), "same-key");

        assertEquals(first.reservation_id(), replay.reservation_id(),
                "same key must return the same reservation");

        // Same key, different seats -> conflict.
        ReservationDeclinedException ex = assertThrows(ReservationDeclinedException.class,
                () -> reservationService.reserve(showId, "alice", List.of("S2"), "same-key"));
        assertEquals(DeclineReason.IDEMPOTENCY_CONFLICT, ex.getReason());

        assertReconciles(showId, 10);
        assertEquals(1, showService.getShow(showId).counts().confirmed());
    }

    @Test
    void multiSeat_allOrNothing() {
        ShowResponse show = freshShow(10);
        UUID showId = show.id();

        // alice takes S1.
        reservationService.reserve(showId, "alice", List.of("S1"), "a1");

        // bob asks for [S1,S2] -> must get nothing (all-or-nothing), S2 stays free.
        ReservationDeclinedException ex = assertThrows(ReservationDeclinedException.class,
                () -> reservationService.reserve(showId, "bob", List.of("S1", "S2"), "b1"));
        assertEquals(DeclineReason.SEAT_TAKEN, ex.getReason());

        ShowResponse state = showService.getShow(showId);
        assertEquals(1, state.counts().confirmed(), "only alice's single seat confirmed");
        assertEquals(9, state.counts().available(), "S2 must NOT have been taken");
        assertReconciles(showId, 10);
    }

    @Test
    void cancel_releasesSeat_andOwnerOnly() {
        ShowResponse show = freshShow(5);
        UUID showId = show.id();

        ReservationResponse r = reservationService.reserve(showId, "alice", List.of("S1"), "a1");
        assertEquals(4, showService.getShow(showId).counts().available());

        // bob cannot cancel alice's reservation.
        assertThrows(money.paytm.seatreservation.service.ForbiddenException.class,
                () -> reservationService.cancel(r.reservation_id(), "bob"));

        // alice cancels; seat returns to available and is re-bookable.
        reservationService.cancel(r.reservation_id(), "alice");
        assertEquals(5, showService.getShow(showId).counts().available());

        ReservationResponse r2 = reservationService.reserve(showId, "bob", List.of("S1"), "b1");
        assertEquals("confirmed", r2.status());
        assertReconciles(showId, 5);
    }
}
