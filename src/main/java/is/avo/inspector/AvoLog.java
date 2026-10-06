package is.avo.inspector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Lines about lost data and failed sends, written to stderr whatever the logging flag (the
// cross-SDK logging rule, shared with Node and Go). Each kind (per drop reason, HTTP status or
// failure) prints at most one line per 10 s: the first occurrence prints at once, later ones in the
// window are counted. The count is reported by the next line for that kind after the window, or by
// flushPending() on flush(), destroy() and the shutdown drain. "in the last Ns" is the real time
// since the window's first occurrence. There is no timer, so nothing here keeps a thread alive. No
// line includes the API key, a property value or an error message. Keys come from a fixed set.
final class AvoLog {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);

    static final String QUEUE_FULL = "queue full";
    static final String SEND_BACKLOG_FULL = "send backlog full";
    static final String INTERNAL_ERROR = "internal error";

    interface Clock {
        long nanoTime();
    }

    // Test-only clock.
    static volatile Clock clockForTesting;

    // Guarded by itself: one window per key.
    private static final Map<String, Window> windows = new HashMap<>();

    private AvoLog() {
    }

    /** Events dropped because the unsent buffer or the send backlog is full, or to an internal error. */
    static void dropped(long count, String reason) {
        report("dropped:" + reason, count, new Dropped(reason));
    }

    /** A batch answered with an HTTP status other than 200. Only the status is logged. */
    static void rejected(int status) {
        report("non200:" + status, 1, new Rejected(status));
    }

    /** A batch that could not be sent (network error, timeout, header guard); reason is a fixed text. */
    static void failed(String reason) {
        report("failed:" + reason, 1, new More("Avo Inspector: schema sending failed: " + reason + ".", ""));
    }

    /**
     * An internal error, logged with its class name only, as in Node: its message or toString()
     * could carry text from a property value (a throwing getter or toString()), and getName()
     * runs no user code.
     */
    static void internal(Throwable error) {
        report("internal", 1, new More(Util.INTERNAL_ERROR_MESSAGE, " (" + error.getClass().getName() + ")"));
    }

    /** A streamId containing ':' (warned on every call before; now once per window). */
    static void streamIdColon() {
        report("streamid-colon", 1, new More("Avo Inspector: streamId contains ':'; using the value verbatim.", ""));
    }

    /** An event tracked with a null, empty or whitespace-only name, sent under the placeholder. */
    static void missingEventName() {
        report("missing-event-name", 1, new MissingEventName());
    }

    /**
     * Prints, right away, the count each window has suppressed since its last line, and forgets
     * those windows, so a burst followed by quiet is still reported. flush() passes
     * onlyExpired = true: a window still within its 10 s keeps its count pending, so calling
     * flush() often does not undo the rate limit. destroy() and the shutdown drain print every
     * pending count.
     */
    static void flushPending(boolean onlyExpired) {
        List<String> lines = new ArrayList<>();
        long now = now();
        synchronized (windows) {
            for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
                Window window = it.next();
                if (window.suppressed > 0 && (!onlyExpired || now - window.start >= WINDOW_NANOS)) {
                    lines.add(window.line.pending(window.suppressed, seconds(now - window.start)));
                    it.remove();
                }
            }
        }
        for (String line : lines) {
            System.err.println(line);
        }
    }

    static void resetForTesting() {
        synchronized (windows) {
            windows.clear();
        }
    }

    // The first occurrence of a window prints at once; later ones in the window are counted. The
    // first one after the window prints itself together with what the previous window counted,
    // over the time since that window's first occurrence.
    private static void report(String key, long amount, Line line) {
        String print;
        long now = now();
        synchronized (windows) {
            Window window = windows.get(key);
            if (window != null && now - window.start < WINDOW_NANOS) {
                window.suppressed += amount;
                window.line = line;
                return;
            }
            long carried = window != null ? window.suppressed : 0;
            long since = carried > 0 ? window.start : now;
            windows.put(key, new Window(now, line));
            print = line.occurrence(amount + carried, seconds(now - since));
        }
        System.err.println(print);
    }

    private static long now() {
        Clock clock = clockForTesting;
        return clock != null ? clock.nanoTime() : System.nanoTime();
    }

    // Whole seconds, at least 1.
    private static long seconds(long nanos) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(nanos));
    }

    static final class Window {
        final long start;
        long suppressed;
        // The latest occurrence's line, which renders the pending count.
        Line line;

        Window(long start, Line line) {
            this.start = start;
            this.line = line;
        }
    }

    // Named classes, not lambdas: they are loaded up front (AvoBatcher's preload list), so a log
    // line never needs a class from a closed class loader.
    abstract static class Line {
        // A line printed for an occurrence, covering count occurrences.
        abstract String occurrence(long count, long seconds);

        // A line for count occurrences that were suppressed after an earlier line.
        abstract String pending(long count, long seconds);
    }

    // "<count> ... in the last Ns." lines: the count is the number reported.
    abstract static class Counted extends Line {
        abstract String text(long count, long seconds);

        @Override
        String occurrence(long count, long seconds) {
            return text(count, seconds);
        }

        @Override
        String pending(long count, long seconds) {
            return text(count, seconds);
        }
    }

    static final class Dropped extends Counted {
        private final String reason;

        Dropped(String reason) {
            this.reason = reason;
        }

        @Override
        String text(long count, long seconds) {
            return "Avo Inspector: dropped " + count + " event(s) (" + reason + ") in the last " + seconds + "s.";
        }
    }

    static final class Rejected extends Counted {
        private final int status;

        Rejected(int status) {
            this.status = status;
        }

        @Override
        String text(long count, long seconds) {
            return "Avo Inspector: " + count + " batch(es) rejected with HTTP " + status + " in the last " + seconds + "s.";
        }
    }

    static final class MissingEventName extends Counted {
        @Override
        String text(long count, long seconds) {
            return "Avo Inspector: " + count + " event(s) tracked without an event name in the last " + seconds
                    + "s, sent as \"" + AvoInspector.MISSING_EVENT_NAME + "\".";
        }
    }

    // A fixed text, with " (N more in the last Ns)" for occurrences beyond the one it reports.
    static final class More extends Line {
        private final String text;
        private final String suffix;

        More(String text, String suffix) {
            this.text = text;
            this.suffix = suffix;
        }

        @Override
        String occurrence(long count, long seconds) {
            return text + (count > 1 ? " (" + (count - 1) + " more in the last " + seconds + "s)" : "") + suffix;
        }

        @Override
        String pending(long count, long seconds) {
            return text + " (" + count + " more in the last " + seconds + "s)" + suffix;
        }
    }
}
