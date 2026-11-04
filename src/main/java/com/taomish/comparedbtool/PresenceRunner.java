package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which of several sources has each key. One bit per source is set for every key; the keys with the same bits form a pattern
 * (in legacy and backup but not in current, only in current, ...). Counts are exact. The keys of the patterns that are not "in every source" are
 * listed (capped in sample mode) and profiled: what do the keys that only one source has have in common?
 */
final class PresenceRunner {
    private static final Logger LOG = LoggerFactory.getLogger(PresenceRunner.class);
    static final int MAX_SOURCES = 30;

    record Pattern(long mask, long keys, List<String> examples) {}

    record Result(String id, List<String> sources, long[] rows, long[] distinct, long keys, long inAll, long mismatched, List<Pattern> patterns,
                  List<Path> files, String headline, String detail, List<String> profile) {
        boolean differs() { return mismatched > 0; }
        Findings.Finding finding() {
            return mismatched == 0 ? null : new Findings.Finding("presence-mismatch", headline, detail, mismatched, keys, 94);
        }
        /** For expressions: #id.keys, .inAll, .mismatched, .missingIn['source'], .distinct['source'], .rows['source']. */
        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>(), missing = new LinkedHashMap<>(), distinctMap = new LinkedHashMap<>(), rowsMap = new LinkedHashMap<>();
            for (int i = 0; i < sources.size(); i++) { missing.put(sources.get(i), keys - distinct[i]); distinctMap.put(sources.get(i), distinct[i]); rowsMap.put(sources.get(i), rows[i]); }
            m.put("keys", keys); m.put("inAll", inAll); m.put("mismatched", mismatched);
            m.put("missingIn", missing); m.put("distinct", distinctMap); m.put("rows", rowsMap);
            return m;
        }
    }

    private final int partitions, maxPartitionMb;
    PresenceRunner(int partitions, int maxPartitionMb) { this.partitions = partitions; this.maxPartitionMb = maxPartitionMb; }

    /** Numbers by value, everything else as text. */
    private static final Comparator<String> KEY_ORDER = (a, b) -> {
        try { return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)); }
        catch (NumberFormatException e) { return a.compareTo(b); }
    };

    static List<String> sources(Rules.Presence presence) {
        return presence.sources == null ? List.of() : Arrays.stream(presence.sources.split(",", -1)).map(String::strip).toList();
    }

    static void validate(Rules.Presence presence, Map<String, Rules.Sql> queries, Set<String> ids) {
        RuleLoader.id(presence.id, ids);
        ExportMode.resolve(presence.export, ExportMode.SAMPLE);
        List<String> keys = FunnelRunner.keysOf(presence.key);
        if (keys.isEmpty() || keys.stream().anyMatch(String::isBlank) || keys.stream().map(k -> k.toLowerCase(Locale.ROOT)).distinct().count() != keys.size())
            throw new ToolException("Presence " + presence.id + " needs key=\"col1,col2\" with distinct, nonempty column names.");
        List<String> sources = sources(presence);
        if (sources.size() < 2 || sources.size() > MAX_SOURCES || sources.stream().anyMatch(String::isBlank) || new HashSet<>(sources).size() != sources.size())
            throw new ToolException("Presence " + presence.id + " needs sources=\"query-a, query-b, ...\": between 2 and " + MAX_SOURCES + " different query ids.");
        for (String source : sources) if (!queries.containsKey(source))
            throw new ToolException("Presence " + presence.id + ": unknown source query '" + source + "'. Available: " + new TreeSet<>(queries.keySet()));
    }

    Result run(Rules.Presence presence, Map<String, QueryRunner.Result> queries, Path root, ExportMode defaultMode, int sampleRows) throws IOException {
        ExportMode mode = ExportMode.resolve(presence.export, defaultMode);
        List<String> keyNames = FunnelRunner.keysOf(presence.key), sources = sources(presence);
        int n = sources.size();
        Path directory = root.resolve(presence.id);
        Files.createDirectory(directory);
        Path work = root.resolve(".work/presence").resolve(presence.id);
        Files.createDirectories(work);
        long[] distinct = new long[n], rows = new long[n];
        Map<Long, Long> counts = new TreeMap<>();
        Map<Long, TreeSet<String>> examples = new HashMap<>();
        long all = (1L << n) - 1, keysTotal = 0;
        int cap = Math.max(5, sampleRows / 4);                       // listed keys per pattern in sample mode
        Map<Long, Long> listed = new HashMap<>();
        long[] written = {0};
        Path csv = directory.resolve("presence-keys.csv");
        List<String> header = new ArrayList<>(keyNames.stream().map(k -> "key." + k).toList());
        header.addAll(sources);
        header.add("pattern");
        List<Pattern> patterns = new ArrayList<>();
        List<String> profile = List.of();
        try {
            partition(presence, queries, keyNames, sources, work);
            MemoryBudget budget = new MemoryBudget(maxPartitionMb);
            try (CSVPrinter out = mode == ExportMode.NONE ? null : CsvFiles.writer(csv, header)) {
                for (int p = 0; p < partitions; p++) {
                    budget.nextPartition();
                    for (var entry : FunnelRunner.loadMasks(work, p, budget).entrySet()) {
                        long mask = entry.getValue()[0];
                        keysTotal++;
                        for (int i = 0; i < n; i++) if ((mask >> i & 1) == 1) distinct[i]++;
                        counts.merge(mask, 1L, Long::sum);
                        // the five smallest keys, so the examples do not depend on how the keys were partitioned
                        TreeSet<String> sample = examples.computeIfAbsent(mask, k -> new TreeSet<>(KEY_ORDER));
                        sample.add(String.join("|", entry.getKey().values().stream().map(v -> v == null ? "NULL" : v).toList()));
                        if (sample.size() > 5) sample.pollLast();
                        if (out != null && mask != all && (mode == ExportMode.FULL || listed.merge(mask, 1L, Long::sum) <= cap)) {
                            List<String> row = new ArrayList<>(entry.getKey().values());
                            for (int i = 0; i < n; i++) row.add((mask >> i & 1) == 1 ? "yes" : "");
                            row.add(label(mask, sources));
                            out.printRecord(row);
                            written[0]++;
                        }
                    }
                }
            }
            for (var e : counts.entrySet()) patterns.add(new Pattern(e.getKey(), e.getValue(), List.copyOf(examples.get(e.getKey()))));
            patterns.sort(Comparator.comparing((Pattern x) -> x.mask() == all ? 0 : 1).thenComparing(Pattern::keys, Comparator.reverseOrder()));
            long mismatchedNow = keysTotal - counts.getOrDefault(all, 0L);
            if (mismatchedNow > 0) {
                try { profile = profile(presence, queries, keyNames, sources, patterns, all, work); }
                catch (IOException | RuntimeException e) { LOG.warn("Profile of the mismatching keys of presence {} could not be computed; the result is unaffected", presence.id, e); }
            }
        } finally {
            try { CsvFiles.deleteTree(work); } catch (IOException cleanup) { LOG.warn("Could not remove presence temporary files at {}", work, cleanup); }
        }
        for (int i = 0; i < n; i++) rows[i] = queries.get(sources.get(i)).rows();
        long inAll = counts.getOrDefault(all, 0L), mismatched = keysTotal - inAll;
        String headline, detail;
        if (mismatched == 0) {
            headline = "all " + Progress.count(keysTotal) + " keys are in all " + n + " sources";
            detail = "";
        } else {
            Pattern biggest = patterns.stream().filter(x -> x.mask() != all).findFirst().orElseThrow();
            headline = Progress.count(mismatched) + " of " + Progress.count(keysTotal) + " keys are not in every source; the biggest group: " + describe(biggest.mask(), sources) + " ("
                + Progress.count(biggest.keys()) + " keys)";
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < n; i++) if (keysTotal - distinct[i] > 0) missing.add("`" + sources.get(i) + "` lacks " + Progress.count(keysTotal - distinct[i]));
            detail = String.join("; ", missing) + ".";
        }
        StringBuilder md = new StringBuilder("# Presence " + presence.id + "\n\nKey: " + String.join(", ", keyNames) + ". Which source has each key.\n\n");
        md.append("| Source | Rows | Distinct keys |\n|---|---|---|\n");
        for (int i = 0; i < n; i++) md.append("| ").append(sources.get(i)).append(" | ").append(Progress.count(rows[i])).append(" | ").append(Progress.count(distinct[i]))
            .append(rows[i] != distinct[i] ? " (duplicate or empty keys)" : "").append(" |\n");
        md.append("\n**").append(headline.substring(0, 1).toUpperCase(Locale.ROOT)).append(headline.substring(1)).append(".**\n\n").append(detail.isEmpty() ? "" : detail + "\n\n");
        md.append("| ").append(String.join(" | ", sources)).append(" | Keys | Share | Examples |\n|").append("---|".repeat(n + 3)).append("\n");
        int shown = 0;
        for (Pattern p : patterns) {
            if (shown++ >= 24) { md.append("\n").append(patterns.size() - 24).append(" more combinations are in presence-keys.csv.\n"); break; }
            md.append("| ");
            for (int i = 0; i < n; i++) md.append((p.mask() >> i & 1) == 1 ? "yes" : "-").append(" | ");
            md.append(Progress.count(p.keys())).append(" | ").append(String.format(Locale.ROOT, "%.1f%%", 100.0 * p.keys() / Math.max(1, keysTotal))).append(" | ")
                .append(p.mask() == all ? "" : Expectations.cell(String.join(", ", p.examples().stream().limit(3).map(x -> "`" + x + "`").toList()))).append(" |\n");
        }
        if (!profile.isEmpty()) {
            md.append("\n## What the mismatching keys have in common\n\n");
            profile.forEach(line -> md.append(line).append('\n'));
        }
        if (mode != ExportMode.NONE) md.append("\n`").append(csv.getFileName()).append("`: showing ").append(written[0]).append(" of ").append(Progress.count(mismatched))
            .append(mode == ExportMode.FULL ? " mismatching keys (all)" : " mismatching keys (at most " + cap + " per combination)").append("; keys in every source are counted, not listed.\n");
        ComparisonRunner.writeSmallSummary(directory.resolve("SUMMARY.md"), md.toString());
        List<Path> files = new ArrayList<>(List.of(directory.resolve("SUMMARY.md")));
        if (mode != ExportMode.NONE) files.add(csv);
        return new Result(presence.id, sources, rows, distinct, keysTotal, inAll, mismatched, List.copyOf(patterns), List.copyOf(files), headline, detail, List.copyOf(profile));
    }

    /** "in legacy and backup, not in current" */
    static String describe(long mask, List<String> sources) {
        List<String> in = new ArrayList<>(), out = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) ((mask >> i & 1) == 1 ? in : out).add("`" + sources.get(i) + "`");
        return (in.isEmpty() ? "in none" : "in " + String.join(" and ", in)) + (out.isEmpty() ? "" : ", not in " + String.join(" or ", out));
    }

    private static String label(long mask, List<String> sources) {
        List<String> in = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) if ((mask >> i & 1) == 1) in.add(sources.get(i));
        return (in.size() == 1 ? "only " : "") + String.join("+", in);
    }

    private List<String> profile(Rules.Presence presence, Map<String, QueryRunner.Result> queries, List<String> keyNames, List<String> sources,
                                 List<Pattern> patterns, long all, Path work) throws IOException {
        List<Pattern> chosen = patterns.stream().filter(p -> p.mask() != all && p.keys() >= FunnelRunner.PROFILE_MIN_KEYS).limit(FunnelRunner.PROFILE_BUCKETS).toList();
        if (chosen.isEmpty()) return List.of();
        Map<Long, Set<ComparisonRunner.Key>> keys = new LinkedHashMap<>();
        chosen.forEach(p -> keys.put(p.mask(), new HashSet<>()));
        MemoryBudget budget = new MemoryBudget(maxPartitionMb);
        for (int part = 0; part < partitions; part++) {
            budget.nextPartition();
            for (var entry : FunnelRunner.loadMasks(work, part, budget).entrySet()) {
                Set<ComparisonRunner.Key> set = keys.get(entry.getValue()[0]);
                if (set != null && set.size() < FunnelRunner.PROFILE_KEY_CAP) set.add(entry.getKey());
            }
        }
        List<String> lines = new ArrayList<>();
        for (Pattern p : chosen) {
            int first = Long.numberOfTrailingZeros(p.mask());                      // the first source that has these keys
            String queryId = sources.get(first);
            lines.add("**Keys " + describe(p.mask(), sources) + " (" + Progress.count(p.keys()) + ")**, from the rows of `" + queryId + "`");
            FunnelRunner.profileRows(queries.get(queryId), keyNames, keys.get(p.mask()), p.keys(), "keys in this group", "all keys of `" + queryId + "`", lines);
        }
        return lines;
    }

    private void partition(Rules.Presence presence, Map<String, QueryRunner.Result> queries, List<String> keyNames, List<String> sources, Path work) throws IOException {
        List<CSVPrinter> writers = new ArrayList<>();
        try {
            for (int p = 0; p < partitions; p++) writers.add(CsvFiles.writer(work.resolve("part-" + p + ".csv"), null));
            for (int i = 0; i < sources.size(); i++) {
                String queryId = sources.get(i);
                QueryRunner.Result r = queries.get(queryId);
                if (r == null || r.partial() || r.csv() == null || !Files.exists(r.csv()))
                    throw new ToolException("Presence skipped: source query " + queryId + " failed, was blocked, is PARTIAL, or is an engine=db input.");
                int[] index = FunnelRunner.keyIndexes(r, keyNames);
                for (int k = 0; k < index.length; k++) if (index[k] < 0)
                    throw new ToolException("Presence " + presence.id + ": query " + queryId + " has no key column '" + keyNames.get(k) + "'. Its columns: "
                        + r.columns().stream().map(QueryRunner.Column::name).toList() + ".");
                boolean[] numeric = FunnelRunner.keyNumeric(r, index);
                Rules.Column plain = new Rules.Column();
                try (var rows = CsvFiles.reader(r.csv(), true)) {
                    for (var row : rows) {
                        List<String> values = CsvFiles.values(row);
                        List<String> key = new ArrayList<>(index.length);
                        for (int k = 0; k < index.length; k++) key.add(Values.normalize(values.get(index[k]), plain, numeric[k]));
                        List<String> record = new ArrayList<>(2 + index.length);
                        record.add(String.valueOf(i)); record.add("0");
                        record.addAll(key);
                        writers.get(Math.floorMod(key.hashCode(), partitions)).printRecord(record);
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
