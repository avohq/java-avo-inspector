package is.avo.inspector;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Lines about lost data and failed sends, written to stderr whatever the logging flag (the
// cross-SDK logging rule, shared with Node and Go). Each kind (per drop reason, HTTP status or
// failure text) prints at most one line per 10 s: the first occurrence prints at once, later ones
// in the window are counted and reported with the next line after it. There is no timer, so
// nothing here keeps a thread alive. No line includes the API key or a property value.
final class AvoLog {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);

    static final String QUEUE_FULL = "queue full";
    static final String SEND_BACKLOG_FULL = "send backlog full";

    interface Clock {
        long nanoTime();
    }

    // Test-only clock.
    static volatile Clock clockForTesting;

    // Guarded by itself: per key, {start of the current window, count suppressed in it}.
    private static final Map<String, long[]> windows = new HashMap<>();

    private AvoLog() {
    }

    /** Events dropped because the unsent buffer ({@link #QUEUE_FULL}) or the send backlog is full. */
    static void dropped(long count, String reason) {
        long total = due("dropped:" + reason, count);
        if (total > 0) {
            System.err.println("Avo Inspector: dropped " + total + " event(s) (" + reason + ") in the last 10s.");
        }
    }

    /** A batch answered with an HTTP status other than 200. Only the status is logged. */
    static void rejected(int status) {
        long total = due("non200:" + status, 1);
        if (total > 0) {
            System.err.println("Avo Inspector: " + total + " batch(es) rejected with HTTP " + status + " in the last 10s.");
        }
    }

    /** A batch that could not be sent (network error, timeout, header guard). */
    static void failed(String reason) {
        long total = due("failed:" + reason, 1);
        if (total > 0) {
            System.err.println("Avo Inspector: schema sending failed: " + reason + "." + more(total));
        }
    }

    /**
     * An internal error, logged with its class name only, as in Node: its message or toString()
     * could carry text from a property value (a throwing getter or toString()), and getName()
     * runs no user code.
     */
    static void internal(Throwable error) {
        long total = due("internal", 1);
        if (total > 0) {
            System.err.println(Util.INTERNAL_ERROR_MESSAGE + more(total) + " (" + error.getClass().getName() + ")");
        }
    }

    /** A streamId containing ':' (warned on every call before; now once per window). */
    static void streamIdColon() {
        long total = due("streamid-colon", 1);
        if (total > 0) {
            System.err.println("Avo Inspector: streamId contains ':'; using the value verbatim." + more(total));
        }
    }

    /** An event tracked with a null, empty or whitespace-only name, sent under the placeholder. */
    static void missingEventName() {
        long total = due("missing-event-name", 1);
        if (total > 0) {
            System.err.println("Avo Inspector: " + total + " event(s) tracked without an event name in the last 10s, sent as \""
                    + AvoInspector.MISSING_EVENT_NAME + "\".");
        }
    }

    static void resetForTesting() {
        synchronized (windows) {
            windows.clear();
        }
    }

    // Counts amount for key. Returns the total to print (this amount plus what the previous window
    // counted) when a line is due, or 0 while the window is open.
    private static long due(String key, long amount) {
        Clock clock = clockForTesting;
        long now = clock != null ? clock.nanoTime() : System.nanoTime();
        synchronized (windows) {
            long[] window = windows.get(key);
            if (window != null && now - window[0] < WINDOW_NANOS) {
                window[1] += amount;
                return 0;
            }
            windows.put(key, new long[]{now, 0});
            return amount + (window != null ? window[1] : 0);
        }
    }

    private static String more(long total) {
        return total > 1 ? " (" + (total - 1) + " more in the last 10s)" : "";
    }
}
