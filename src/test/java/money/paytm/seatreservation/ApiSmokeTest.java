package money.paytm.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-stack smoke test over real HTTP: Flyway migration runs, endpoints
 * respond, identity is token-derived, declines are 409 (not 5xx), and the
 * actuator probes + prometheus metrics are exposed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiSmokeTest extends AbstractEmbeddedPgTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    @Test
    void endToEnd_create_reserve_reconcile_authAndIdempotency() {
        // liveness + readiness
        assertEquals(HttpStatus.OK, rest.getForEntity(url("/actuator/health/liveness"), String.class).getStatusCode());
        assertEquals(HttpStatus.OK, rest.getForEntity(url("/actuator/health/readiness"), String.class).getStatusCode());

        // create show (admin)
        String createBody = "{\"name\":\"it-show\",\"seats\":[\"A1\",\"A2\",\"A3\"],\"price_paise\":25000}";
        ResponseEntity<JsonNode> created = rest.exchange(url("/shows"), HttpMethod.POST,
                new HttpEntity<>(createBody, authJson("admin")), JsonNode.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String showId = created.getBody().get("id").asText();
        assertEquals(3, created.getBody().get("total_seats").asInt());

        // reserve without a token -> 403 (identity is token-derived)
        ResponseEntity<JsonNode> noAuth = rest.exchange(url("/shows/" + showId + "/reserve"), HttpMethod.POST,
                new HttpEntity<>("{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}", authJson(null)), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, noAuth.getStatusCode());

        // alice reserves A1
        ResponseEntity<JsonNode> r1 = rest.exchange(url("/shows/" + showId + "/reserve"), HttpMethod.POST,
                new HttpEntity<>("{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}", authJson("alice")), JsonNode.class);
        assertEquals(HttpStatus.CREATED, r1.getStatusCode());
        assertEquals("alice", r1.getBody().get("user_id").asText());
        assertEquals(25000, r1.getBody().get("amount_paise").asInt());

        // bob tries A1 -> clean 409, not 5xx
        ResponseEntity<JsonNode> r2 = rest.exchange(url("/shows/" + showId + "/reserve"), HttpMethod.POST,
                new HttpEntity<>("{\"seats\":[\"A1\"],\"idempotency_key\":\"k2\"}", authJson("bob")), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, r2.getStatusCode());
        assertEquals("seat_taken", r2.getBody().get("code").asText());

        // alice replays k1 -> same reservation
        ResponseEntity<JsonNode> replay = rest.exchange(url("/shows/" + showId + "/reserve"), HttpMethod.POST,
                new HttpEntity<>("{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}", authJson("alice")), JsonNode.class);
        assertEquals(HttpStatus.CREATED, replay.getStatusCode());
        assertEquals(r1.getBody().get("reservation_id").asText(), replay.getBody().get("reservation_id").asText());

        // reconciliation
        ResponseEntity<JsonNode> state = rest.getForEntity(url("/shows/" + showId), JsonNode.class);
        JsonNode counts = state.getBody().get("counts");
        int sum = counts.get("available").asInt() + counts.get("held").asInt() + counts.get("confirmed").asInt();
        assertEquals(3, sum);
        assertEquals(1, counts.get("confirmed").asInt());

        // prometheus metrics exposed and include our counters
        ResponseEntity<String> metrics = rest.getForEntity(url("/actuator/prometheus"), String.class);
        assertEquals(HttpStatus.OK, metrics.getStatusCode());
        assertTrue(metrics.getBody().contains("reservations_confirmed_total"));
        assertTrue(metrics.getBody().contains("reservations_declined_total"));
    }
}
