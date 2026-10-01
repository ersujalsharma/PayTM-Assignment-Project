package money.paytm.seatreservation.auth;

import money.paytm.seatreservation.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a bearer token to a user id. Identity is ALWAYS derived here from
 * the token, never from any request body field. This is what makes "acting as
 * another user" impossible: the body is not consulted for identity.
 *
 * Two modes:
 *   - Configured map (app.auth.tokens = "tok1:user1,tok2:user2"): only known
 *     tokens authenticate.
 *   - Empty map (default, convenient for load testing): the token string IS the
 *     user id, so "Bearer alice" => user "alice". Still token-derived.
 */
@Component
public class TokenAuthenticator {

    private final Map<String, String> tokenToUser = new HashMap<>();
    private final boolean passthrough;

    public TokenAuthenticator(AppProperties props) {
        String raw = props.getAuth().getTokens();
        if (raw == null || raw.isBlank()) {
            this.passthrough = true;
        } else {
            this.passthrough = false;
            for (String pair : raw.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                    tokenToUser.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
    }

    public Optional<String> resolveUser(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        if (passthrough) {
            return Optional.of(bearerToken.trim());
        }
        return Optional.ofNullable(tokenToUser.get(bearerToken.trim()));
    }
}
