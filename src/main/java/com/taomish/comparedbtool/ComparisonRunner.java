package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disk-partitioned matching. Only one partition's distinct keys is held in memory. */
public final class ComparisonRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ComparisonRunner.class);
    record Binding(String name, int source, int target, Rules.Column rule, boolean numeric) {}
    record Group(String column, String classification, boolean accepted) {}
    record Key(List<String> values) {
        Key { values = Collections.unmodifiableList(new ArrayList<>(values)); }
    }
    static final class Bucket {
        final List<String> first;
        long count = 1;
        Bucket(List<String> row) { first = row; }
    }
    public record Result(String id, long matched, long equal, long different, long onlySource, long onlyTarget,
                         long duplicateSourceKeys, long duplicateTargetKeys, long ambiguousKeys,
                         long differentCells, long acceptedChanges, boolean schemaDifferent,
                         boolean partial, List<Path> files, String headline) {
        public boolean differs() {
            return different > 0 || onlySource > 0 || onlyTarget > 0 || duplicateSourceKeys > 0
                || duplicateTargetKeys > 0 || schemaDifferent;
        }
    }
    private final int partitions;
    public ComparisonRunner(int partitions) { this.partitions = partitions; }

    public Result run(Rules.Compare rule, QueryRunner.Result source, QueryRunner.Result target, Path root) throws IOException {
        LOG.debug("Comparison={} sourceRows={} targetRows={} partitions={}", rule.id, source.rows(), target.rows(), partitions);
        List<String> keyNames = RuleLoader.keys(rule);
        List<Binding> bindings = bindings(rule, source, target);
        List<Binding> keyBindings = keyNames.stream().map(name -> bindings.stream()
            .filter(b -> b.name().equalsIgnoreCase(name)).findFirst()
            .orElseThrow(() -> new ToolException("Key column not available in " + rule.id + ": " + name))).toList();
        Set<Integer> usedSource = new HashSet<>(), usedTarget = new HashSet<>();
        bindings.forEach(b -> { usedSource.add(b.source()); usedTarget.add(b.target()); });
        for (Rules.Column c : rule.columns) if (c.ignore) {
            int s = index(source.columns(), c.source == null ? c.name : c.source);
            int t = index(target.columns(), c.target == null ? c.name : c.target);
            if (s >= 0) usedSource.add(s);
            if (t >= 0) usedTarget.add(t);
        }
        List<String> sourceExtra = extras(source, usedSource), targetExtra = extras(target, usedTarget);
        boolean schemaDifferent = !sourceExtra.isEmpty() || !targetExtra.isEmpty();
        Path directory = root.resolve(rule.id);
        Files.createDirectory(directory);
        Path work = root.resolve(".work").resolve(rule.id);
        Files.createDirectories(work);
        List<String> keyHeader = keyNames.stream().map(k -> "key." + k).toList();
        Path cells = directory.resolve("differences.csv"), onlySource = directory.resolve("only-source.csv"),
            onlyTarget = directory.resolve("only-target.csv"), duplicates = directory.resolve("duplicates.csv"),
            summary = directory.resolve("SUMMARY.md");
        long[] totals = new long[10]; // matched,equal,different,onlySource,onlyTarget,dupSource,dupTarget,ambiguous,badCells,accepted
        Map<Group, Long> groups = new TreeMap<>(Comparator.comparing(Group::column)
            .thenComparing(Group::classification).thenComparing(Group::accepted));
        try {
            LOG.debug("Partitioning source CSV for comparison {}", rule.id);
            partition(source, keyBindings, true, work.resolve("source"));
            LOG.debug("Partitioning target CSV for comparison {}", rule.id);
            partition(target, keyBindings, false, work.resolve("target"));
            try (CSVPrinter diff = CsvFiles.writer(cells, concat(keyHeader, List.of("column", "source", "target", "classification", "accepted", "absolute_difference", "relative_difference")));
                 CSVPrinter left = CsvFiles.writer(onlySource, source.columns().stream().map(QueryRunner.Column::name).toList());
                 CSVPrinter right = CsvFiles.writer(onlyTarget, target.columns().stream().map(QueryRunner.Column::name).toList());
                 CSVPrinter dup = CsvFiles.writer(duplicates, concat(keyHeader, List.of("side", "row_count")))) {
                for (int part = 0; part < partitions; part++) {
                    Map<Key, Bucket> a = load(work.resolve("source").resolve(part + ".csv"), keyBindings, true);
                    Map<Key, Bucket> b = load(work.resolve("target").resolve(part + ".csv"), keyBindings, false);
                    LOG.debug("Comparison {} partition {}/{} sourceKeys={} targetKeys={}",
                        rule.id, part + 1, partitions, a.size(), b.size());
                    Set<Key> keys = new LinkedHashSet<>(a.keySet()); keys.addAll(b.keySet());
                    for (Key key : keys) {
                        Bucket x = a.get(key), y = b.get(key);
                        boolean duplicate = false;
                        if (x != null && x.count > 1) {
                            totals[5]++; duplicate = true; dup.printRecord(concat(key.values(), List.of("source", String.valueOf(x.count))));
                        }
                        if (y != null && y.count > 1) {
                            totals[6]++; duplicate = true; dup.printRecord(concat(key.values(), List.of("target", String.valueOf(y.count))));
                        }
                        // Duplicate keys are ambiguous, never silently paired or counted as equal.
                        if (duplicate) { totals[7]++; continue; }
                        if (x == null) { totals[4]++; right.printRecord(y.first); continue; }
                        if (y == null) { totals[3]++; left.printRecord(x.first); continue; }
                        totals[0]++;
                        boolean different = false;
                        for (Binding binding : bindings) {
                            String lv = x.first.get(binding.source()), rv = y.first.get(binding.target());
                            Values.Change change = Values.compare(lv, rv, binding.rule(), binding.numeric());
                            if (change == null) continue;
                            if (change.accepted()) totals[9]++; else { totals[8]++; different = true; }
                            groups.merge(new Group(binding.name(), change.classification(), change.accepted()), 1L, Long::sum);
                            diff.printRecord(concat(key.values(), Arrays.asList(binding.name(), lv, rv, change.classification(),
                                String.valueOf(change.accepted()), change.absoluteDifference(), change.relativeDifference())));
                        }
                        if (different) totals[2]++; else totals[1]++;
                    }
                }
            }
        } finally {
            try { CsvFiles.deleteTree(work); }
            catch (IOException cleanup) { LOG.warn("Could not remove comparison temporary files at {}", work, cleanup); }
        }
        boolean partial = source.partial() || target.partial();
        String headline = (partial ? "PARTIAL; " : "") + "matched=" + totals[0] + ", equal=" + totals[1]
            + ", different=" + totals[2] + ", only-source=" + totals[3] + ", only-target=" + totals[4]
            + ", duplicate-keys=" + totals[5] + "/" + totals[6] + (schemaDifferent ? ", schema differs" : "");
        StringBuilder markdown = new StringBuilder("# " + rule.id + "\n\n" + headline + "\n\nSource: `" + rule.source
            + "` (" + source.rows() + " rows); target: `" + rule.target + "` (" + target.rows() + " rows).\n\n"
            + "Keys: " + String.join(", ", keyNames) + ". Ambiguous keys excluded from row matching: " + totals[7]
            + ".\n\nDifferent cells: " + totals[8] + "; accepted representation/tolerance changes: " + totals[9] + ".\n\n");
        if (schemaDifferent) markdown.append("Columns only in source: ").append(sourceExtra).append("\n\nColumns only in target: ")
            .append(targetExtra).append("\n\n");
        markdown.append("| Column | Class | Accepted | Cells |\n|---|---|---|---|\n");
        int shown = 0;
        for (var group : groups.entrySet()) {
            if (shown++ >= 60) { markdown.append("\nAdditional groups are available in differences.csv.\n"); break; }
            Group label = group.getKey();
            markdown.append("| ").append(label.column().replace("|", "\\|")).append(" | ").append(label.classification())
                .append(" | ").append(label.accepted()).append(" | ").append(group.getValue()).append(" |\n");
        }
        markdown.append("\n`differences.csv` includes accepted changes; only accepted=false cells count as failures. "
            + "Relative difference = abs(source-target) / max(abs(source),abs(target)). "
            + "Duplicate keys are listed separately; their complete rows remain in the original query CSVs.\n");
        writeSmallSummary(summary, markdown.toString());
        return new Result(rule.id, totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6], totals[7],
            totals[8], totals[9], schemaDifferent, partial, List.of(cells, onlySource, onlyTarget, duplicates, summary), headline);
    }

    private List<Binding> bindings(Rules.Compare compare, QueryRunner.Result a, QueryRunner.Result b) {
        LinkedHashMap<String, Rules.Column> columns = new LinkedHashMap<>();
        for (QueryRunner.Column column : a.columns()) if (index(b.columns(), column.name()) >= 0) {
            Rules.Column rule = new Rules.Column(); rule.name = column.name();
            columns.put(column.name().toLowerCase(Locale.ROOT), rule);
        }
        // Explicit rules override defaults; source/target permit different aliases across DBs.
        for (Rules.Column rule : compare.columns) {
            if (rule.source != null) columns.remove(rule.source.toLowerCase(Locale.ROOT));
            if (rule.target != null) columns.remove(rule.target.toLowerCase(Locale.ROOT));
            columns.put(rule.name.toLowerCase(Locale.ROOT), rule);
        }
        List<Binding> result = new ArrayList<>();
        Set<Integer> sourceIndices = new HashSet<>(), targetIndices = new HashSet<>();
        for (Rules.Column rule : columns.values()) {
            if (rule.ignore) continue;
            int source = index(a.columns(), rule.source == null ? rule.name : rule.source);
            int target = index(b.columns(), rule.target == null ? rule.name : rule.target);
            if (source < 0 || target < 0) throw new ToolException("Column " + rule.name + " is missing in comparison " + compare.id + ".");
            if (!sourceIndices.add(source) || !targetIndices.add(target))
                throw new ToolException("A column is mapped more than once in " + compare.id + ".");
            result.add(new Binding(rule.name, source, target, rule, a.columns().get(source).numeric() || b.columns().get(target).numeric()));
        }
        return result;
    }
    private void partition(QueryRunner.Result data, List<Binding> keys, boolean source, Path directory) throws IOException {
        Files.createDirectories(directory);
        List<CSVPrinter> writers = new ArrayList<>();
        try {
            for (int i = 0; i < partitions; i++) writers.add(CsvFiles.writer(directory.resolve(i + ".csv"), null));
            try (CSVParser rows = CsvFiles.reader(data.csv(), true)) {
                for (CSVRecord row : rows) {
                    List<String> values = CsvFiles.values(row);
                    writers.get(Math.floorMod(key(values, keys, source).hashCode(), partitions)).printRecord(values);
                }
            }
        } finally {
            IOException failure = null;
            for (CSVPrinter writer : writers) try { writer.close(); } catch (IOException e) { failure = e; }
            if (failure != null) throw failure;
        }
    }
    private Map<Key, Bucket> load(Path path, List<Binding> keys, boolean source) throws IOException {
        Map<Key, Bucket> result = new LinkedHashMap<>();
        try (CSVParser rows = CsvFiles.reader(path, false)) {
            for (CSVRecord row : rows) {
                List<String> values = CsvFiles.values(row);
                Key key = key(values, keys, source);
                Bucket bucket = result.get(key);
                if (bucket == null) result.put(key, new Bucket(values)); else bucket.count++;
            }
        }
        return result;
    }
    private Key key(List<String> row, List<Binding> keys, boolean source) {
        List<String> values = new ArrayList<>();
        for (Binding binding : keys) values.add(Values.normalize(row.get(source ? binding.source() : binding.target()), binding.rule(), binding.numeric()));
        return new Key(values);
    }
    private static int index(List<QueryRunner.Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        return -1;
    }
    private static List<String> extras(QueryRunner.Result data, Set<Integer> used) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < data.columns().size(); i++) if (!used.contains(i)) result.add(data.columns().get(i).name());
        return result;
    }
    private static List<String> concat(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left); result.addAll(right); return result;
    }
    static void writeSmallSummary(Path path, String content) throws IOException {
        // Keep summaries bounded in bytes, including Unicode column names.
        String suffix = "\n\nSummary truncated; see CSV files for complete details.\n";
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) {
            int end = Math.min(content.length(), 7800);
            while ((content.substring(0, end) + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) end--;
            if (end > 0 && Character.isHighSurrogate(content.charAt(end - 1))) end--;
            content = content.substring(0, end) + suffix;
        }
        Files.writeString(path, content);
    }
}
