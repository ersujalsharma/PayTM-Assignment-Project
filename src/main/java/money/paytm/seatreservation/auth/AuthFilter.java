package money.paytm.seatreservation.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Establishes two things for every request:
 *   1. A correlation/request id (from X-Request-Id header or generated), placed
 *      in MDC so every structured log line is traceable, and echoed back in the
 *      response header.
 *   2. The token-derived identity for protected endpoints.
 *
 * Public paths (show creation is treated as admin but open for this exercise,
 * health, metrics, GET show) do not require a token.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final TokenAuthenticator authenticator;

    public AuthFilter(TokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = auth.substring(7).trim();
                Optional<String> user = authenticator.resolveUser(token);
                if (user.isPresent()) {
                    CurrentUser.set(user.get());
                    MDC.put("userId", user.get());
                }
            }
            chain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
            MDC.clear();
        }
    }
}
