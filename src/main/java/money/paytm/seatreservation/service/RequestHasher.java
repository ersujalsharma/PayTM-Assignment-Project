package money.paytm.seatreservation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Produces a stable hash of the reservation's meaningful inputs. Used to detect
 * "same idempotency key, different request body" so we can reject it with 409
 * instead of silently replaying the original reservation.
 *
 * Seat order is normalized (sorted) so ["A12","A13"] and ["A13","A12"] are the
 * same logical request.
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(UUID showId, List<String> seats) {
        List<String> normalized = new ArrayList<>(seats);
        normalized.sort(String::compareTo);
        String canonical = showId + "|" + String.join(",", normalized);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
