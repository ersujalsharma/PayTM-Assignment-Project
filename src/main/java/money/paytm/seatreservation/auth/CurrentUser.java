package money.paytm.seatreservation.auth;

/**
 * Holds the token-derived user id for the current request thread.
 * Set by AuthFilter, read by controllers. Using a ThreadLocal keeps the
 * identity off the request body and out of handler signatures.
 */
public final class CurrentUser {

    private static final ThreadLocal<String> USER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(String userId) {
        USER.set(userId);
    }

    public static String get() {
        return USER.get();
    }

    public static void clear() {
        USER.remove();
    }
}
