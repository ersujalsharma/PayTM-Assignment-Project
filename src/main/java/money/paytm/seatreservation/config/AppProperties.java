package money.paytm.seatreservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Reservation reservation = new Reservation();
    private final Auth auth = new Auth();

    public Reservation getReservation() {
        return reservation;
    }

    public Auth getAuth() {
        return auth;
    }

    public static class Reservation {
        private int defaultPerUserLimit = 4;
        private long holdTtlSeconds = 120;
        private long sweepIntervalMs = 5000;

        public int getDefaultPerUserLimit() {
            return defaultPerUserLimit;
        }

        public void setDefaultPerUserLimit(int defaultPerUserLimit) {
            this.defaultPerUserLimit = defaultPerUserLimit;
        }

        public long getHoldTtlSeconds() {
            return holdTtlSeconds;
        }

        public void setHoldTtlSeconds(long holdTtlSeconds) {
            this.holdTtlSeconds = holdTtlSeconds;
        }

        public long getSweepIntervalMs() {
            return sweepIntervalMs;
        }

        public void setSweepIntervalMs(long sweepIntervalMs) {
            this.sweepIntervalMs = sweepIntervalMs;
        }
    }

    public static class Auth {
        /**
         * Optional explicit token->user map, format: "tok1:user1,tok2:user2".
         * If empty, the token value itself is treated as the user id (so
         * "Authorization: Bearer alice" authenticates as user "alice").
         */
        private String tokens = "";

        public String getTokens() {
            return tokens;
        }

        public void setTokens(String tokens) {
            this.tokens = tokens;
        }
    }
}
