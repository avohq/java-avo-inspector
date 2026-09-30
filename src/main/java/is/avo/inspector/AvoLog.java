package is.avo.inspector;

import java.util.concurrent.TimeUnit;

// Messages printed whatever the logging flag, each kind at most once per window: the first
// occurrence in a window is printed, later ones are counted and summed up in one line when the
// window ends. No message includes the API key or a request body (SPEC.md §7.5.1).
final class AvoLog {

    static final long WINDOW_MS = 10_000;
    // Test-only: a shorter window.
    static volatile long windowMsForTesting = 0;

    static final Channel DROPPED_EVENTS = new Channel("event(s) dropped");
    static final Channel NON_200 = new Channel("non-200 response(s)");

    private AvoLog() {
    }

    static void resetForTesting() {
        for (Channel channel : new Channel[]{DROPPED_EVENTS, NON_200}) {
            channel.reset();
        }
    }

    private static long windowNanos() {
        long override = windowMsForTesting;
        return TimeUnit.MILLISECONDS.toNanos(override > 0 ? override : WINDOW_MS);
    }

    static final class Channel {
        private final String summaryUnit;
        // Guarded by this.
        private boolean windowOpen;
        private long windowStart;
        private long suppressed;
        private boolean summaryScheduled;

        Channel(String summaryUnit) {
            this.summaryUnit = summaryUnit;
        }

        /** Prints line, or counts {@code count} toward the next summary if this window already printed. */
        void report(String line, long count) {
            String print = null;
            synchronized (this) {
                long now = System.nanoTime();
                if (!windowOpen || now - windowStart >= windowNanos()) {
                    windowOpen = true;
                    windowStart = now;
                    print = line;
                } else {
                    suppressed += count;
                    if (!summaryScheduled) {
                        summaryScheduled = true;
                        long delay = windowStart + windowNanos() - now;
                        if (!AvoBatcher.scheduleShared(new SummaryTask(this), delay)) {
                            summaryScheduled = false;
                        }
                    }
                }
            }
            if (print != null) {
                System.err.println(print);
            }
        }

        void summarize() {
            String print = null;
            synchronized (this) {
                summaryScheduled = false;
                if (suppressed > 0) {
                    print = "Avo Inspector: " + suppressed + " more " + summaryUnit + " in the last "
                            + TimeUnit.NANOSECONDS.toSeconds(windowNanos()) + " s.";
                    suppressed = 0;
                    windowOpen = true;
                    windowStart = System.nanoTime();
                }
            }
            if (print != null) {
                System.err.println(print);
            }
        }

        synchronized void reset() {
            windowOpen = false;
            suppressed = 0;
            summaryScheduled = false;
        }
    }

    // A named class, loaded with AvoLog, so no class is loaded late on the SDK's threads.
    static final class SummaryTask implements Runnable {
        private final Channel channel;

        SummaryTask(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            try {
                channel.summarize();
            } catch (Throwable ignored) {
                // Logging must never fail the SDK's threads.
            }
        }
    }
}
