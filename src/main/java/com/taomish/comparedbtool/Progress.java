package com.taomish.comparedbtool;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The messages the tool prints while it works and when it is done: what ran, how many rows were found where, what differs.
 * Plain ASCII on stdout so they survive any console; the log files keep the full detail. --quiet turns them off.
 */
final class Progress {
    private static final Progress INSTANCE = new Progress();
    static Progress get() { return INSTANCE; }

    private volatile boolean quiet;
    private volatile long heartbeatSeconds = Long.getLong("compare.progress.seconds", 30);
    private ScheduledExecutorService scheduler;

    void quiet(boolean quiet) { this.quiet = quiet; }
    void heartbeatSeconds(long seconds) { this.heartbeatSeconds = seconds; }

    synchronized void line(String text) {
        if (!quiet) { System.out.println(text); System.out.flush(); }
    }

    /** A long-running step: announced when it starts, reported every heartbeat while it runs. */
    final class Handle {
        private final String label;
        private final long started = System.nanoTime();
        private volatile long rows;
        private ScheduledFuture<?> beat;
        private Handle(String label) { this.label = label; }
        void rows(long rows) { this.rows = rows; }
        long elapsedMillis() { return (System.nanoTime() - started) / 1_000_000; }
        void done() { if (beat != null) beat.cancel(false); }
    }

    Handle start(String id, String where) {
        String label = id + (where == null || where.isBlank() ? "" : " on " + where);
        Handle handle = new Handle(label);
        line("  > " + label + " ...");
        if (!quiet && heartbeatSeconds > 0) {
            synchronized (this) {
                if (scheduler == null) scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "progress"); t.setDaemon(true); return t; });
                handle.beat = scheduler.scheduleAtFixedRate(() -> line("  ... " + label + " still running (" + duration(handle.elapsedMillis())
                    + (handle.rows > 0 ? ", " + count(handle.rows) + " rows so far" : "") + ")"), heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
            }
        }
        return handle;
    }

    // ------------------------------------------------------------------ wording (pure, so it can be tested)

    static String count(long n) { return String.format(Locale.ROOT, "%,d", n); }

    static String duration(long millis) {
        if (millis < 1000) return millis + " ms";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        long s = millis / 1000;
        return s < 3600 ? (s / 60) + " min " + (s % 60) + " s" : (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    static String queryFound(String id, String db, long rows, long millis, boolean partial) {
        return "  " + (partial ? "PARTIAL " : "OK ") + " found " + count(rows) + (rows == 1 ? " row" : " rows") + " in " + id
            + " (" + db + ") in " + duration(millis) + (partial ? " - row cap reached, do not rely on this result" : "");
    }

    static String queryFailed(String id, String db, String reason) {
        String text = reason == null ? "" : reason.replaceAll("\\s+", " ");
        return "  FAILED " + id + " on " + db + ": " + (text.length() > 220 ? text.substring(0, 217) + "..." : text) + " (details: MANUAL_ACTIONS.md)";
    }

    static String compared(String id, String source, String target, ComparisonRunner.Result r) {
        StringBuilder text = new StringBuilder("  compare " + id + ": " + count(r.matched()) + " matched (" + count(r.equal()) + " equal, " + count(r.different()) + " differ)");
        text.append(", ").append(count(r.onlySource())).append(" only in ").append(source).append(", ").append(count(r.onlyTarget())).append(" only in ").append(target);
        if (r.duplicateSourceKeys() + r.duplicateTargetKeys() > 0) text.append(", ").append(count(r.duplicateSourceKeys() + r.duplicateTargetKeys())).append(" duplicate keys");
        if (r.schemaDifferent()) text.append(", columns differ between the two sides");
        if (!r.findings().isEmpty()) text.append("\n    finding: ").append(Findings.line(r.findings().getFirst(), 200));
        return text.toString();
    }
}
