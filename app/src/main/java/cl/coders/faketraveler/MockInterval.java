package cl.coders.faketraveler;

/**
 * Converts the settings interval into the inject-loop delay.
 *
 * <p>The settings field is whole seconds. {@code 0} means continuous injection.
 * A literal 0&nbsp;ms loop saturates {@code system_server} and gets the process
 * killed, so continuous mode is a fixed fast cadence ({@link #CONTINUOUS_DELAY_MS}),
 * which the settings label states. Positive second values are not raised.
 */
public final class MockInterval {

    /** About 10 fixes per second. Documented in the interval setting as what 0 does. */
    public static final long CONTINUOUS_DELAY_MS = 100L;

    private static final long WATCHDOG_FLOOR_MS = 5_000L;

    private MockInterval() {
    }

    /**
     * @param intervalSeconds value stored in prefs / typed in settings
     * @return delay passed to the inject loop. Never negative. {@code <= 0} seconds
     * becomes {@link #CONTINUOUS_DELAY_MS}, not 1 second.
     */
    public static long toDelayMillis(int intervalSeconds) {
        if (intervalSeconds <= 0) return CONTINUOUS_DELAY_MS;
        return intervalSeconds * 1000L;
    }

    /**
     * Accepts a delay already expressed in milliseconds (intents, binder).
     * {@code <= 0} is continuous mode. Positive values are kept as-is
     * (no 200&nbsp;ms floor).
     */
    public static long sanitizeDelayMillis(long requestedMillis) {
        if (requestedMillis <= 0L) return CONTINUOUS_DELAY_MS;
        return requestedMillis;
    }

    /** Seconds to write back to prefs for a live delay. Continuous maps back to 0. */
    public static int toStoredSeconds(long delayMillis) {
        if (delayMillis <= CONTINUOUS_DELAY_MS) return 0;
        long seconds = delayMillis / 1000L;
        if (seconds > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) seconds;
    }

    /**
     * Divisor for the existing speed formula ({@code meters / divisor}).
     * Matches the inject delay so interval 0 does not divide by zero and does
     * not pretend the gap is one second.
     */
    public static float speedDivisorMillis(int intervalSeconds) {
        return toDelayMillis(intervalSeconds);
    }

    /** How long a missing tick can be before the watchdog restarts the loop. */
    public static long stallLimitMillis(long delayMillis) {
        long delay = sanitizeDelayMillis(delayMillis);
        if (delay > (Long.MAX_VALUE - 2_000L) / 2) return Long.MAX_VALUE;
        return Math.max(WATCHDOG_FLOOR_MS, delay * 2 + 2_000L);
    }

    public static long watchdogPeriodMillis(long delayMillis) {
        long delay = sanitizeDelayMillis(delayMillis);
        if (delay > Long.MAX_VALUE - 2_000L) return Long.MAX_VALUE;
        return Math.max(WATCHDOG_FLOOR_MS, delay + 2_000L);
    }
}
