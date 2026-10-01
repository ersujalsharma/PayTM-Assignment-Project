package money.paytm.seatreservation.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Platforms like Render/Railway/Heroku expose the database as a single URL in
 * the form:  postgres://user:password@host:port/dbname
 * The JDBC driver instead wants:  jdbc:postgresql://host:port/dbname  plus a
 * separate username/password.
 *
 * This post-processor detects a non-JDBC DATABASE_URL and rewrites it (and the
 * derived credentials) into Spring's datasource properties before the context
 * starts. If DATABASE_URL is already a jdbc: URL, it is left untouched, so an
 * explicit configuration still wins.
 */
public class DatabaseUrlNormalizer implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }
        if (databaseUrl.startsWith("jdbc:")) {
            return; // already JDBC-shaped; nothing to do
        }
        if (!(databaseUrl.startsWith("postgres://") || databaseUrl.startsWith("postgresql://"))) {
            return; // unknown shape; let Spring try as-is
        }

        try {
            URI uri = URI.create(databaseUrl);
            String userInfo = uri.getUserInfo(); // "user:password" or null
            String user = null;
            String password = null;
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                user = parts[0];
                password = parts.length > 1 ? parts[1] : "";
            }
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path;

            // Preserve query params (e.g. sslmode=require on managed Postgres).
            if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                jdbcUrl += "?" + uri.getQuery();
            }

            Map<String, Object> overrides = new HashMap<>();
            overrides.put("spring.datasource.url", jdbcUrl);
            if (user != null) {
                overrides.put("spring.datasource.username", user);
            }
            if (password != null) {
                overrides.put("spring.datasource.password", password);
            }
            // Highest precedence so it wins over the application.yml defaults.
            env.getPropertySources().addFirst(
                    new MapPropertySource("normalizedDatabaseUrl", overrides));
        } catch (Exception e) {
            // Non-fatal: fall back to whatever Spring resolves.
            System.err.println("DatabaseUrlNormalizer: could not parse DATABASE_URL: " + e.getMessage());
        }
    }
}
