package money.paytm.seatreservation;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

/**
 * Boots a single real PostgreSQL instance (via zonky embedded-postgres, which
 * ships a native pg binary - no Docker needed) shared across all test classes.
 * Spring's datasource is pointed at it dynamically. This lets the concurrency
 * tests exercise the ACTUAL atomic SQL (FOR UPDATE, guarded UPDATE, partial
 * unique index) against a genuine Postgres engine.
 */
public abstract class AbstractEmbeddedPgTest {

    private static final EmbeddedPostgres PG;
    private static final String JDBC_URL;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
            // Default database is "postgres".
            JDBC_URL = PG.getJdbcUrl("postgres", "postgres");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
