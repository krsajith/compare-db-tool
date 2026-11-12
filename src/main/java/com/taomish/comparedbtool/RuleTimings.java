package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Monotonic wall-clock measurements; parallel/shared work must never be summed as rule time. */
final class RuleTimings {
    static final String HEADING = "## Rule timing";
    private final long started;
    RuleTimings(long started) { this.started = started; }
    private final List<Entry> entries = new ArrayList<>();
    record Entry(String type, String id, long millis, String status, String note) {}

    final class Step implements AutoCloseable {
        private final String type, id;
        private final long start = System.nanoTime();
        private final Progress.Handle progress;
        Step(String type, String id) {
            this.type = type; this.id = id;
            progress = Progress.get().start(type + " " + id, null);
        }
        @Override public void close() {
            progress.done();
            add(type, id, start, "COMPLETE", "");
        }
    }

    Step start(String type, String id) { return new Step(type, id); }

    synchronized void add(String type, String id, long start, String status, String note) {
        addMillis(type, id, (System.nanoTime() - start) / 1_000_000, status, note);
    }

    synchronized void addMillis(String type, String id, long millis, String status, String note) {
        entries.add(new Entry(type, id, millis, status, note));
        Progress.get().line("  time " + type + " " + id + ": " + Progress.duration(millis)
            + " (" + millis + " ms)" + (note.isEmpty() ? "" : " - " + note));
    }

    synchronized void write(Path directory, Map<String, String> failures, Map<String, String> skipped,
                            boolean finished) throws IOException {
        long total = (System.nanoTime() - started) / 1_000_000;
        StringBuilder md = new StringBuilder("\n\n" + HEADING + "\n\n**"
            + (finished ? "Whole rule" : "Rule elapsed before packaging") + ": " + Progress.duration(total)
            + " (" + total + " ms)**\n\n"
            + "Wall-clock time includes connection setup, fetching and local processing, not just database execution. "
            + "Step times exclude scheduler waiting. Parallel steps overlap; shared query groups repeat the same elapsed time for each id. "
            + "Do not sum step times. Inputs used only by engine=db are included in their comparison time. "
            + "The final output-folder report includes ZIP creation and cleanup; ZIP reports are snapshots taken before their own packaging.\n\n"
            + "| Step | Id | Time | Time ms | Status | Note |\n|---|---|---|---:|---|---|\n");
        try (var csv = CsvFiles.writer(directory.resolve("TIMINGS.csv"), List.of("step", "id", "millis", "status", "note"))) {
            for (Entry e : entries) {
                boolean ruleStep = List.of("query", "compare", "compare-db", "funnel", "presence", "zip").contains(e.type());
                String status = ruleStep && skipped.containsKey(e.id()) ? "SKIPPED"
                    : ruleStep && failures.containsKey(e.id()) && e.status().equals("COMPLETE") ? "FAILED" : e.status();
                md.append("| ").append(e.type()).append(" | ").append(e.id()).append(" | ")
                    .append(Progress.duration(e.millis())).append(" | ").append(e.millis()).append(" | ")
                    .append(status).append(" | ").append(e.note()).append(" |\n");
                csv.printRecord(e.type(), e.id(), e.millis(), status, e.note());
            }
            csv.printRecord("rule", "total", total, finished ? "FINAL" : "BEFORE PACKAGING", "wall-clock; do not sum steps");
        }
        Path summary = directory.resolve("SUMMARY.md");
        if (Files.exists(summary)) {
            String text = Files.readString(summary);
            int old = text.indexOf("\n\n" + HEADING + "\n");
            Files.writeString(summary, (old < 0 ? text : text.substring(0, old)) + md);
        } else Files.writeString(directory.resolve("TIMINGS.md"), md.toString());
    }
}
