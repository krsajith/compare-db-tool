package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ordered stages, each a query returning the keys that survive it. For every key the tool records, per side, which stages it
 * survived (one bit per stage); the lowest bit where the two sides differ is the stage where that key first diverges. A key that
 * diverges at stage 2 is blamed on stage 2 only, not again on every later stage. In chain mode (one side) the lowest missing
 * bit is the stage the key drops out at. Keys are matched on disk partitions like a comparison, so memory stays bounded.
 */
final class FunnelRunner {
    private static final Logger LOG = LoggerFactory.getLogger(FunnelRunner.class);
    static final int MAX_STAGES = 30;

    record StageResult(String id, String reason, long source, long target, long onlySource, long onlyTarget, long divergesSourceOnly, long divergesTargetOnly) {}

    record Result(String id, boolean chain, List<StageResult> stages, int firstDivergence, long diverging, long anomalies,
                  List<Path> files, String headline, String detail) {
        /** A comparison funnel that diverges is a difference; a chain only reports. */
        boolean differs() { return !chain && diverging > 0; }
        Findings.Finding finding() {
            if (diverging == 0) return null;
            return new Findings.Finding(chain ? "funnel-drop-off" : "funnel-first-divergence", headline, detail, diverging, 0, chain ? 70 : 97);
        }
    }

    private final int partitions, maxPartitionMb;
    FunnelRunner(int partitions, int maxPartitionMb) { this.partitions = partitions; this.maxPartitionMb = maxPartitionMb; }

    static List<String> keys(Rules.Funnel funnel) {
        return funnel.key == null ? List.of() : Arrays.stream(funnel.key.split(",", -1)).map(String::strip).toList();
    }

    static boolean chain(Rules.Funnel funnel) { return "chain".equalsIgnoreCase(funnel.mode); }

    static void validate(Rules.Funnel funnel, Map<String, Rules.Sql> queries, Set<String> ids) {
        RuleLoader.id(funnel.id, ids);
        ExportMode.resolve(funnel.export, ExportMode.SAMPLE);
        String mode = funnel.mode == null ? "" : funnel.mode.toLowerCase(Locale.ROOT);
        if (!mode.equals("compare") && !mode.equals("chain")) throw new ToolException("Funnel " + funnel.id + ": mode must be compare or chain.");
        List<String> keys = keys(funnel);
        if (keys.isEmpty() || keys.stream().anyMatch(String::isBlank) || keys.stream().map(k -> k.toLowerCase(Locale.ROOT)).distinct().count() != keys.size())
            throw new ToolException("Funnel " + funnel.id + " needs key=\"col1,col2\" with distinct, nonempty column names.");
        if (funnel.group != null) {
            if (!mode.equals("chain")) throw new ToolException("Funnel " + funnel.id + ": group= applies to chain funnels.");
            if (keys.stream().noneMatch(k -> k.equalsIgnoreCase(funnel.group.strip())))
                throw new ToolException("Funnel " + funnel.id + ": group=\"" + funnel.group + "\" must be one of the key columns " + keys + ".");
        }
        if (funnel.stages == null || funnel.stages.size() < 2 || funnel.stages.size() > MAX_STAGES)
            throw new ToolException("Funnel " + funnel.id + " needs between 2 and " + MAX_STAGES + " <stage> elements.");
        Set<String> stageIds = new HashSet<>();
        for (Rules.Stage stage : funnel.stages) {
            if (stage.id == null || !stage.id.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}") || !stageIds.add(stage.id.toLowerCase(Locale.ROOT)))
                throw new ToolException("Funnel " + funnel.id + ": stage ids must be unique and start with a letter: " + stage.id);
            if (!queries.containsKey(stage.source))
                throw new ToolException("Funnel " + funnel.id + ", stage " + stage.id + ": unknown source query '" + stage.source + "'. Available: " + new TreeSet<>(queries.keySet()));
            if (mode.equals("compare") && !queries.containsKey(stage.target))
                throw new ToolException("Funnel " + funnel.id + ", stage " + stage.id + ": a comparison funnel needs target=\"query id\" (known: " + new TreeSet<>(queries.keySet()) + ").");
            if (mode.equals("chain") && stage.target != null)
                throw new ToolException("Funnel " + funnel.id + ", stage " + stage.id + ": a chain funnel has one side; remove target=.");
        }
    }

    Result run(Rules.Funnel funnel, Map<String, QueryRunner.Result> queries, Path root, ExportMode defaultMode, int sampleRows) throws IOException {
        boolean chain = chain(funnel);
        ExportMode mode = ExportMode.resolve(funnel.export, defaultMode);
        List<String> keyNames = keys(funnel);
        int n = funnel.stages.size(), sides = chain ? 1 : 2;
        Path directory = root.resolve(funnel.id);
        Files.createDirectory(directory);
        Path work = root.resolve(".work/funnels").resolve(funnel.id);
        Files.createDirectories(work);
        long[] stageReach = new long[n], stageReachTarget = new long[n], onlyS = new long[n], onlyT = new long[n], divS = new long[n], divT = new long[n];
        long[] dropped = new long[n];
        long anomalies = 0;
        long[] exportedByBucket = new long[n * 2];
        int cap = Math.max(1, sampleRows / Math.max(1, n * (chain ? 1 : 2)));
        List<List<String>> examples = new ArrayList<>(); // [stage index, side, key...] for the first divergence stage, kept for the summary
        Path csv = directory.resolve(chain ? "dropped-keys.csv" : "diverging-keys.csv");
        List<String> header = new ArrayList<>(keyNames.stream().map(k -> "key." + k).toList());
        header.addAll(chain ? List.of("dropped_at", "stages_reached") : List.of("first_divergence", "kept_only_by", "source_stages", "target_stages"));
        long[] totalDiverging = {0}, written = {0};
        int groupIndex = -1;
        if (chain && funnel.group != null) for (int k = 0; k < keyNames.size(); k++) if (keyNames.get(k).equalsIgnoreCase(funnel.group.strip())) groupIndex = k;
        Map<String, long[]> groupReach = new TreeMap<>(), groupDropped = new TreeMap<>();
        try {
            partition(funnel, queries, keyNames, sides, work);
            MemoryBudget budget = new MemoryBudget(maxPartitionMb);
            try (CSVPrinter out = mode == ExportMode.NONE ? null : CsvFiles.writer(csv, header)) {
                for (int p = 0; p < partitions; p++) {
                    budget.nextPartition();
                    Map<ComparisonRunner.Key, long[]> masks = new LinkedHashMap<>();
                    try (var rows = CsvFiles.reader(work.resolve("part-" + p + ".csv"), false)) {
                        for (var record : rows) {
                            List<String> values = CsvFiles.values(record);
                            ComparisonRunner.Key key = new ComparisonRunner.Key(values.subList(2, values.size()));
                            long[] m = masks.get(key);
                            if (m == null) { budget.row(values, key.values()); m = new long[2]; masks.put(key, m); }
                            m[Integer.parseInt(values.get(1))] |= 1L << Integer.parseInt(values.get(0));
                        }
                    }
                    for (var entry : masks.entrySet()) {
                        long a = entry.getValue()[0], b = entry.getValue()[1];
                        for (int i = 0; i < n; i++) {
                            boolean ai = (a >> i & 1) == 1, bi = (b >> i & 1) == 1;
                            if (ai) stageReach[i]++;
                            if (bi) stageReachTarget[i]++;
                            if (ai && !bi) onlyS[i]++;
                            if (bi && !ai) onlyT[i]++;
                        }
                        if (chain) {
                            if ((a & 1L) == 0) { if (a != 0) anomalies++; continue; }
                            int dropAt = Long.numberOfTrailingZeros(~a);
                            if (groupIndex >= 0) {
                                String g = String.valueOf(entry.getKey().values().get(groupIndex));
                                long[] reach = groupReach.computeIfAbsent(g, x -> new long[n]), drop = groupDropped.computeIfAbsent(g, x -> new long[n]);
                                for (int i = 0; i < n; i++) if ((a >> i & 1) == 1) reach[i]++;
                                if (dropAt < n) drop[dropAt]++;
                            }
                            if (dropAt >= n) continue;
                            if ((a >> (dropAt + 1)) != 0) anomalies++;
                            dropped[dropAt]++; totalDiverging[0]++;
                            emit(out, entry.getKey(), funnel, List.of(funnel.stages.get(dropAt).id, stages(a, n, funnel)), dropAt, 0, cap, mode, exportedByBucket, written, examples);
                        } else {
                            long diff = a ^ b;
                            if (diff == 0) continue;
                            int first = Long.numberOfTrailingZeros(diff);
                            boolean sourceOnly = (a >> first & 1) == 1;
                            if (sourceOnly) divS[first]++; else divT[first]++;
                            totalDiverging[0]++;
                            emit(out, entry.getKey(), funnel, List.of(funnel.stages.get(first).id, sourceOnly ? "source" : "target", stages(a, n, funnel), stages(b, n, funnel)),
                                first, sourceOnly ? 0 : 1, cap, mode, exportedByBucket, written, examples);
                        }
                    }
                }
            }
        } finally {
            try { CsvFiles.deleteTree(work); } catch (IOException cleanup) { LOG.warn("Could not remove funnel temporary files at {}", work, cleanup); }
        }
        List<StageResult> stages = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Rules.Stage s = funnel.stages.get(i);
            stages.add(new StageResult(s.id, s.reason == null ? "" : s.reason, stageReach[i], chain ? 0 : stageReachTarget[i], onlyS[i], onlyT[i], divS[i], divT[i]));
        }
        int first = -1;
        for (int i = 0; i < n && first < 0; i++) if (chain ? dropped[i] > 0 : divS[i] + divT[i] > 0) first = i;
        final int firstStage = first;
        String headline, detail;
        if (chain) {
            int worst = 0;
            for (int i = 1; i < n; i++) if (dropped[i] > dropped[worst]) worst = i;
            headline = totalDiverging[0] == 0 ? "all " + stageReach[0] + " keys of the first stage reach the last stage"
                : stageReach[0] + " keys enter, " + stageReach[n - 1] + " reach the last stage; " + totalDiverging[0] + " drop out, most at `" + funnel.stages.get(worst).id + "` (" + dropped[worst] + ")";
            if (groupIndex >= 0 && totalDiverging[0] > 0) {
                List<String> parts = new ArrayList<>();
                for (var g : groupReach.entrySet()) {
                    long[] drop = groupDropped.get(g.getKey());
                    int w = 0;
                    for (int i = 1; i < n; i++) if (drop[i] > drop[w]) w = i;
                    long lost = Arrays.stream(drop).sum();
                    parts.add(g.getKey() + ": " + g.getValue()[0] + " enter, " + g.getValue()[n - 1] + " reach the last stage"
                        + (lost == 0 ? "" : ", most drops at `" + funnel.stages.get(w).id + "` (" + drop[w] + ")"));
                }
                headline = String.join("; ", parts);
            }
            detail = totalDiverging[0] == 0 ? "" : "First stage that drops keys: `" + funnel.stages.get(first).id + "` (" + dropped[first] + ")"
                + (funnel.stages.get(worst).reason == null ? "" : "; the stage with most drops is: " + funnel.stages.get(worst).reason) + ".";
        } else if (first < 0) {
            headline = "no divergence: both sides keep the same keys at all " + n + " stages";
            detail = "";
        } else {
            Rules.Stage s = funnel.stages.get(first);
            long k = divS[first] + divT[first];
            headline = "first divergence at `" + s.id + "`: " + k + " keys agree in every earlier stage, then are kept only by the "
                + (divS[first] > 0 && divT[first] > 0 ? "source (" + divS[first] + ") or only by the target (" + divT[first] + ")" : divS[first] > 0 ? "source" : "target")
                + "; " + totalDiverging[0] + " keys diverge in total";
            detail = (s.reason == null || s.reason.isBlank() ? "" : "What this stage does: " + s.reason + ". ")
                + "Example keys: " + examples.stream().filter(e -> e.getFirst().equals(String.valueOf(firstStage))).limit(5).map(e -> "`" + String.join("|", e.subList(2, e.size())) + "`"
                    + (e.get(1).equals("0") ? " (source only)" : " (target only)")).toList() + ".";
        }
        StringBuilder md = new StringBuilder("# Funnel " + funnel.id + "\n\n" + (chain ? "Chain (one side). " : "Comparison of two sides. ") + "Key: " + String.join(", ", keyNames)
            + ".\n\n**" + headline.substring(0, 1).toUpperCase(Locale.ROOT) + headline.substring(1) + ".**\n\n" + (detail.isEmpty() ? "" : detail + "\n\n"));
        if (chain && groupIndex >= 0) {
            md.append("| # | Stage |");
            for (String g : groupReach.keySet()) md.append(' ').append(Expectations.cell(g)).append(" reaching | ").append(Expectations.cell(g)).append(" dropped |");
            md.append(" What it does |\n|---|---|").append("---|---|".repeat(groupReach.size())).append("---|\n");
            for (int i = 0; i < n; i++) {
                md.append("| ").append(i + 1).append(" | ").append(stages.get(i).id()).append(" |");
                for (String g : groupReach.keySet()) md.append(' ').append(groupReach.get(g)[i]).append(" | ").append(i == 0 ? "-" : String.valueOf(groupDropped.get(g)[i])).append(" |");
                md.append(' ').append(Expectations.cell(stages.get(i).reason())).append(" |\n");
            }
            md.append("\nRead each group left to right: the first stage with a large \"dropped\" is where that group breaks. Keys are grouped by `").append(funnel.group).append("`.\n");
            if (anomalies > 0) md.append("\n").append(anomalies).append(" keys appear in a later stage without passing the earlier ones (or reappear after dropping out); they are not counted above.\n");
        } else if (chain) {
            md.append("| # | Stage | Keys reaching it | Dropped here | What it does |\n|---|---|---|---|---|\n");
            for (int i = 0; i < n; i++) md.append("| ").append(i + 1).append(" | ").append(stages.get(i).id()).append(" | ").append(stageReach[i]).append(" | ")
                .append(i == 0 ? "-" : String.valueOf(dropped[i])).append(" | ").append(Expectations.cell(stages.get(i).reason())).append(" |\n");
            if (anomalies > 0) md.append("\n").append(anomalies).append(" keys appear in a later stage without passing the earlier ones (or reappear after dropping out); they are not counted above.\n");
        } else {
            md.append("| # | Stage | Source | Target | Only source | Only target | Diverges here (source only / target only) | What it does |\n|---|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < n; i++) md.append("| ").append(i + 1).append(" | ").append(stages.get(i).id()).append(" | ").append(stageReach[i]).append(" | ")
                .append(stageReachTarget[i]).append(" | ").append(onlyS[i]).append(" | ").append(onlyT[i]).append(" | ").append(divS[i]).append(" / ").append(divT[i])
                .append(" | ").append(Expectations.cell(stages.get(i).reason())).append(" |\n");
            md.append("\n\"Diverges here\" counts a key once, at the first stage where the two sides treat it differently. Only source / only target are the state at that stage and include keys that diverged earlier.\n");
        }
        if (mode != ExportMode.NONE) md.append("\n`").append(csv.getFileName()).append("`: showing ").append(written[0]).append(" of ").append(totalDiverging[0])
            .append(mode == ExportMode.FULL ? " (all)" : " (at most " + cap + " per stage and side)").append(".\n");
        ComparisonRunner.writeSmallSummary(directory.resolve("SUMMARY.md"), md.toString());
        List<Path> files = new ArrayList<>(List.of(directory.resolve("SUMMARY.md")));
        if (mode != ExportMode.NONE) files.add(csv);
        return new Result(funnel.id, chain, List.copyOf(stages), first, totalDiverging[0], anomalies, List.copyOf(files), headline, detail);
    }

    /** Stage membership as the stage ids the key survived, compressed to a range when it is a prefix. */
    private static String stages(long mask, int n, Rules.Funnel funnel) {
        if (mask == 0) return "(none)";
        int prefix = Long.numberOfTrailingZeros(~mask);
        if (prefix > 0 && mask >> prefix == 0) return prefix == 1 ? funnel.stages.getFirst().id : funnel.stages.getFirst().id + ".." + funnel.stages.get(prefix - 1).id;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) if ((mask >> i & 1) == 1) ids.add(funnel.stages.get(i).id);
        return String.join(",", ids);
    }

    private void emit(CSVPrinter out, ComparisonRunner.Key key, Rules.Funnel funnel, List<String> tail, int stage, int side, int cap, ExportMode mode,
                      long[] byBucket, long[] written, List<List<String>> examples) throws IOException {
        int bucket = stage * 2 + side;
        if (examples.size() < 2000 && byBucket[bucket] < 5) {
            List<String> example = new ArrayList<>(List.of(String.valueOf(stage), String.valueOf(side)));
            example.addAll(key.values());
            examples.add(example);
        }
        if (out == null) return;
        if (mode != ExportMode.FULL && byBucket[bucket] >= cap) { byBucket[bucket]++; return; }
        byBucket[bucket]++;
        List<String> row = new ArrayList<>(key.values());
        row.addAll(tail);
        out.printRecord(row);
        written[0]++;
    }

    private void partition(Rules.Funnel funnel, Map<String, QueryRunner.Result> queries, List<String> keyNames, int sides, Path work) throws IOException {
        List<CSVPrinter> writers = new ArrayList<>();
        try {
            for (int p = 0; p < partitions; p++) writers.add(CsvFiles.writer(work.resolve("part-" + p + ".csv"), null));
            for (int i = 0; i < funnel.stages.size(); i++) {
                Rules.Stage stage = funnel.stages.get(i);
                for (int side = 0; side < sides; side++) {
                    String queryId = side == 0 ? stage.source : stage.target;
                    QueryRunner.Result r = queries.get(queryId);
                    if (r == null || r.partial() || r.csv() == null || !Files.exists(r.csv()))
                        throw new ToolException("Funnel skipped: stage " + stage.id + " query " + queryId + " failed, was blocked, is PARTIAL, or is an engine=db input.");
                    int[] index = new int[keyNames.size()];
                    boolean[] numeric = new boolean[keyNames.size()];
                    for (int k = 0; k < index.length; k++) {
                        index[k] = -1;
                        for (int c = 0; c < r.columns().size(); c++) if (r.columns().get(c).name().equalsIgnoreCase(keyNames.get(k))) { index[k] = c; numeric[k] = r.columns().get(c).numeric(); }
                        if (index[k] < 0) throw new ToolException("Funnel " + funnel.id + ", stage " + stage.id + ": query " + queryId + " has no key column '" + keyNames.get(k) + "'.");
                    }
                    Rules.Column plain = new Rules.Column();
                    try (var rows = CsvFiles.reader(r.csv(), true)) {
                        for (var row : rows) {
                            List<String> values = CsvFiles.values(row);
                            List<String> record = new ArrayList<>(2 + index.length);
                            record.add(String.valueOf(i)); record.add(String.valueOf(side));
                            List<String> key = new ArrayList<>(index.length);
                            for (int k = 0; k < index.length; k++) key.add(Values.normalize(values.get(index[k]), plain, numeric[k]));
                            record.addAll(key);
                            writers.get(Math.floorMod(key.hashCode(), partitions)).printRecord(record);
                        }
                    }
                }
            }
        } finally {
            IOException failure = null;
            for (CSVPrinter writer : writers) try { writer.close(); } catch (IOException e) { failure = e; }
            if (failure != null) throw failure;
        }
    }
}
