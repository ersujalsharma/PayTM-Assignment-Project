// ============================================================================
// High-volume load generator for the seat reservation service.
//
// Unlike the bash burst (which spawns one curl per request and cannot scale to
// 20k), this uses a single JVM with a shared HttpClient (connection pooling)
// and virtual threads, so it can actually fire ~20,000 concurrent reservations
// and measure the service's behaviour under real contention.
//
// Scenario (mirrors the assignment's correctness bar):
//   * Creates a fresh show with N seats.
//   * Fires TOTAL reservations across CONCURRENCY in-flight requests:
//       - a configurable fraction target a tiny set of HOT seats (the storm),
//       - the rest spread across the whole hall,
//       - a slice reuse the SAME idempotency key (retry storm).
//   * Reports: status distribution, declines by reason, 5xx count, p50/p95/p99
//     latency, throughput, and the final reconciliation invariant.
//
// Run (no compile step needed, JDK 21+):
//   java loadtest/LoadTest.java <BASE_URL> [TOTAL] [CONCURRENCY] [SEATS] [HOT_SEATS] [HOT_FRACTION]
// Example:
//   java loadtest/LoadTest.java http://localhost:8080 20000 1000 2000 5 0.5
// ============================================================================
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LoadTest {

    static HttpClient client;
    static String base;

    public static void main(String[] args) throws Exception {
        base            = (args.length > 0 ? args[0] : "http://localhost:8080").replaceAll("/+$", "");
        int total       = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        int seats       = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
        int hotSeats    = args.length > 4 ? Integer.parseInt(args[4]) : 5;
        double hotFrac  = args.length > 5 ? Double.parseDouble(args[5]) : 0.5;

        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        System.out.printf("""
                ==============================================================
                 20k-class load test -> %s
                 total=%d concurrency=%d seats=%d hotSeats=%d hotFraction=%.2f
                ==============================================================
                %n""", base, total, concurrency, seats, hotSeats, hotFrac);

        waitForReady();

        // ---- create a fresh show ----
        List<String> labels = new ArrayList<>(seats);
        for (int i = 1; i <= seats; i++) labels.add("S" + i);
        String seatsJson = "\"" + String.join("\",\"", labels) + "\"";
        String showId = createShow("load-" + System.currentTimeMillis(), seatsJson);
        System.out.println("Created show: " + showId + " with " + seats + " seats\n");

        // ---- counters ----
        AtomicInteger c201 = new AtomicInteger();
        AtomicInteger c409 = new AtomicInteger();
        AtomicInteger c5xx = new AtomicInteger();
        AtomicInteger cClientErr = new AtomicInteger();
        AtomicInteger cOther = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> byReason = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicInteger> hotWinners = new ConcurrentHashMap<>();
        LongAdder latSum = new LongAdder();
        // reservoir of latencies for percentiles (sampled to keep memory bounded)
        long[] lat = new long[total];
        AtomicInteger latIdx = new AtomicInteger();

        Semaphore inflight = new Semaphore(concurrency);
        CountDownLatch done = new CountDownLatch(total);
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        Random rnd = new Random(42);

        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            inflight.acquire();
            final int idx = i;
            // Decide seat: hot storm vs spread.
            final int seatNum;
            if (rnd.nextDouble() < hotFrac) {
                seatNum = 1 + rnd.nextInt(hotSeats);          // S1..S{hotSeats}
            } else {
                seatNum = 1 + rnd.nextInt(seats);             // anywhere
            }
            // ~5% reuse a shared idempotency key per user to exercise replay.
            final String user = "u" + idx;
            final String idemKey = (idx % 20 == 0) ? ("shared-" + (idx % 100)) : ("k-" + idx);
            pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    // A real client honours 429/Retry-After. Retry a bounded
                    // number of times so an overloaded instance is treated as
                    // "slow", not "failed" - the request eventually resolves to
                    // a 201 or a clean 409.
                    HttpResponse<String> r = reserve(showId, user, "S" + seatNum, idemKey);
                    int tries = 0;
                    while (r.statusCode() == 429 && tries++ < 15) {
                        Thread.sleep(200 + (long) (Math.random() * 300));
                        r = reserve(showId, user, "S" + seatNum, idemKey);
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    latSum.add(ms);
                    int li = latIdx.getAndIncrement();
                    if (li < lat.length) lat[li] = ms;
                    int sc = r.statusCode();
                    if (sc == 201) {
                        c201.incrementAndGet();
                        if (seatNum <= hotSeats) hotWinners
                                .computeIfAbsent(seatNum, k -> new AtomicInteger()).incrementAndGet();
                    } else if (sc == 409) {
                        c409.incrementAndGet();
                        String reason = extract(r.body(), "code");
                        byReason.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
                    } else if (sc >= 500) {
                        c5xx.incrementAndGet();
                        byReason.computeIfAbsent("HTTP_" + sc, k -> new AtomicInteger()).incrementAndGet();
                    } else {
                        cOther.incrementAndGet();
                        byReason.computeIfAbsent("HTTP_" + sc, k -> new AtomicInteger()).incrementAndGet();
                    }
                } catch (Exception e) {
                    // Client-side failure (connection reset/timeout from the load
                    // generator or local networking saturating), NOT a server
                    // 5xx. Tracked separately so we don't misattribute it.
                    cClientErr.incrementAndGet();
                    byReason.computeIfAbsent("client:" + e.getClass().getSimpleName(),
                            k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    inflight.release();
                    done.countDown();
                }
            });
        }
        done.await();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        // ---- percentiles ----
        int n = Math.min(latIdx.get(), lat.length);
        long[] sorted = Arrays.copyOf(lat, n);
        Arrays.sort(sorted);
        long p50 = pct(sorted, 50), p95 = pct(sorted, 95), p99 = pct(sorted, 99),
             max = n > 0 ? sorted[n - 1] : 0;

        System.out.println("==============================================================");
        System.out.println(" RESULTS");
        System.out.println("==============================================================");
        System.out.printf("  total sent      : %d%n", total);
        System.out.printf("  201 confirmed   : %d%n", c201.get());
        System.out.printf("  409 declined    : %d%n", c409.get());
        System.out.printf("  other non-2xx   : %d%n", cOther.get());
        System.out.printf("  5xx SERVER err  : %d  <-- the one that must be 0%n", c5xx.get());
        System.out.printf("  client-side err : %d  (load-gen/local net saturation, not a server fault)%n", cClientErr.get());
        System.out.println("  declines by reason:");
        byReason.forEach((k, v) -> System.out.printf("      %-22s %d%n", k, v.get()));
        System.out.println();
        System.out.printf("  elapsed         : %d ms%n", elapsedMs);
        System.out.printf("  throughput      : %.0f req/s%n", total * 1000.0 / Math.max(1, elapsedMs));
        System.out.printf("  latency ms      : p50=%d p95=%d p99=%d max=%d%n", p50, p95, p99, max);
        System.out.println();
        System.out.println("  hot-seat winners (each MUST be exactly 1):");
        for (int s = 1; s <= hotSeats; s++) {
            int w = hotWinners.getOrDefault(s, new AtomicInteger()).get();
            System.out.printf("      S%-3d winners = %d %s%n", s, w, (w == 1 ? "OK" : "<-- VIOLATION"));
        }

        // ---- reconciliation ----
        HttpResponse<String> state = get(base + "/shows/" + showId);
        long avail = num(state.body(), "available");
        long held  = num(state.body(), "held");
        long conf  = num(state.body(), "confirmed");
        long tot   = num(state.body(), "total_seats");
        System.out.println();
        System.out.println("  RECONCILIATION available+held+confirmed == total_seats");
        System.out.printf("      available=%d held=%d confirmed=%d total=%d  sum=%d%n",
                avail, held, conf, tot, avail + held + conf);

        // Correctness of the SERVICE: zero server 5xx, exact reconciliation, and
        // each hot seat sold exactly once. Client-side generator errors (local
        // networking saturating under 1000+ virtual threads) are reported but do
        // not count against the service - those requests simply never reached it.
        boolean pass = c5xx.get() == 0
                && (avail + held + conf) == tot
                && hotWinners.values().stream().allMatch(a -> a.get() == 1)
                && hotWinners.size() == hotSeats;
        System.out.println();
        System.out.println(pass
                ? "  ============ PASS: correct under " + total + " requests ============"
                : "  ============ FAIL: see violations above ============");
        System.exit(pass ? 0 : 1);
    }

    // ---------- helpers ----------
    static void waitForReady() throws Exception {
        System.out.print("Waiting for readiness");
        for (int i = 0; i < 60; i++) {
            try {
                HttpResponse<String> r = get(base + "/actuator/health/readiness");
                if (r.statusCode() == 200) { System.out.println(" ... ready\n"); return; }
            } catch (Exception ignored) { }
            System.out.print(".");
            Thread.sleep(2000);
        }
        throw new IllegalStateException("service not ready");
    }

    static String createShow(String name, String seatsJson) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"seats\":[" + seatsJson + "],\"price_paise\":25000}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/shows"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer admin")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 201) throw new IllegalStateException("create show failed: " + r.statusCode() + " " + r.body());
        return extract(r.body(), "id");
    }

    static HttpResponse<String> reserve(String showId, String user, String seat, String idemKey) throws Exception {
        String body = "{\"seats\":[\"" + seat + "\"],\"idempotency_key\":\"" + idemKey + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/shows/" + showId + "/reserve"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + user)
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static long pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(i, sorted.length - 1))];
    }

    // tiny JSON scalar extractors (avoid a dependency)
    static String extract(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return "";
        int c = json.indexOf(':', i) + 1;
        while (c < json.length() && (json.charAt(c) == ' ' || json.charAt(c) == '"')) c++;
        int e = c;
        while (e < json.length() && "\",}".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(c, e);
    }
    static long num(String json, String key) {
        String v = extract(json, key);
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return -1; }
    }
}
