package com.taomish.comparedbtool;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explains a stream comparison from the same pass that makes it, with bounded memory: rows that exist on the other side under a
 * different key, snapshot timing of the unmatched rows, concentration by value (who, which product, which day), and what drives a
 * numeric gap (a few rows, a constant ratio or offset). Counts are exact; examples are capped. Nothing here changes a result.
 */
final class Findings {
    record Finding(String type, String title, String evidence, long explains, long of, double score) {}

    private static final int REKEY_CAP = 50_000, DIST_CAP = 200, TS_DIST_CAP = 20_000, MAX_TS_COLUMNS = 8, MAX_DIM_COLUMNS = 40, VALUE_CHARS = 60, TOP = 10;
    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final MathContext MC = new MathContext(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Rules.Compare rule;
    private final QueryRunner.Result source, target;
    private final List<ComparisonRunner.Binding> nonKey;
    private long onlySourceRows, onlyTargetRows, differentRows, matchedRows;

    // rows under another key
    private static final class Sig { long count; final List<String> examples = new ArrayList<>(); }
    private final Map<Long, Sig> sigS = new HashMap<>(), sigT = new HashMap<>(), looseS = new HashMap<>(), looseT = new HashMap<>();
    private boolean rekeyTruncated;

    // timestamps
    private static final class Ts {
        final String name; final int s, t;
        String sMin, sMax, tMin, tMax;
        final TreeMap<String, Long> onlyS = new TreeMap<>(), onlyT = new TreeMap<>();
        boolean overflowS, overflowT;
        Ts(String name, int s, int t) { this.name = name; this.s = s; this.t = t; }
    }
    private final List<Ts> timestamps = new ArrayList<>();

    // value distributions
    static final class Dims {
        final int[] columns; final String[] names; final List<Map<String, Long>> counts = new ArrayList<>(); final boolean[] overflow;
        long rows;
        Dims(List<QueryRunner.Column> all, Set<Integer> skip) {
            List<Integer> chosen = new ArrayList<>();
            for (int i = 0; i < all.size() && chosen.size() < MAX_DIM_COLUMNS; i++) if (!skip.contains(i)) chosen.add(i);
            columns = chosen.stream().mapToInt(Integer::intValue).toArray();
            names = chosen.stream().map(i -> all.get(i).name()).toArray(String[]::new);
            overflow = new boolean[columns.length];
            for (int i = 0; i < columns.length; i++) counts.add(new HashMap<>());
        }
        void add(List<String> row) {
            rows++;
            for (int i = 0; i < columns.length; i++) {
                if (overflow[i]) continue;
                String v = row.get(columns[i]);
                Map<String, Long> map = counts.get(i);
                map.merge(v == null ? "(NULL)" : v.length() > VALUE_CHARS ? v.substring(0, VALUE_CHARS) : v, 1L, Long::sum);
                if (map.size() > DIST_CAP) { overflow[i] = true; counts.set(i, Map.of()); }
            }
        }
    }
    private final Dims baseS, baseT, catOnlyS, catOnlyT, catDiff;

    // numeric cells
    private static final class Top { final BigDecimal abs, diff; final String key, from, to; Top(BigDecimal abs, BigDecimal diff, String key, String from, String to) { this.abs = abs; this.diff = diff; this.key = key; this.from = from; this.to = to; } }
    private static final class Num {
        long count, unparsed;
        BigDecimal sumDiff = BigDecimal.ZERO, sumAbs = BigDecimal.ZERO, off0, ratio0;
        boolean offsetConst = true, ratioConst = true, flipAll = true;
        long ratioN;
        final PriorityQueue<Top> top = new PriorityQueue<>(Comparator.comparing((Top t) -> t.abs));
    }
    private final Map<String, Num> numbers = new LinkedHashMap<>();
    private final Map<String, Long> differingCellsByColumn = new LinkedHashMap<>();

    Findings(Rules.Compare rule, QueryRunner.Result source, QueryRunner.Result target,
             List<ComparisonRunner.Binding> bindings, List<ComparisonRunner.Binding> keyBindings) {
        this.rule = rule; this.source = source; this.target = target;
        Set<String> keyNames = new HashSet<>();
        keyBindings.forEach(k -> keyNames.add(k.name().toLowerCase(Locale.ROOT)));
        this.nonKey = bindings.stream().filter(b -> !keyNames.contains(b.name().toLowerCase(Locale.ROOT))).toList();
        Set<Integer> skipS = new HashSet<>(), skipT = new HashSet<>();
        keyBindings.forEach(k -> { skipS.add(k.source()); skipT.add(k.target()); });
        baseS = new Dims(source.columns(), skipS); catOnlyS = new Dims(source.columns(), skipS); catDiff = new Dims(source.columns(), skipS);
        baseT = new Dims(target.columns(), skipT); catOnlyT = new Dims(target.columns(), skipT);
        // timestamp columns, by logical name; the ones that look like audit columns first
        Map<String, int[]> byName = new LinkedHashMap<>();
        for (int i = 0; i < source.columns().size(); i++) if (temporal(source.columns().get(i))) byName.computeIfAbsent(source.columns().get(i).name().toLowerCase(Locale.ROOT), n -> new int[]{-1, -1})[0] = i;
        for (int i = 0; i < target.columns().size(); i++) if (temporal(target.columns().get(i))) byName.computeIfAbsent(target.columns().get(i).name().toLowerCase(Locale.ROOT), n -> new int[]{-1, -1})[1] = i;
        byName.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> auditName(e.getKey()) ? 0 : 1))
            .limit(MAX_TS_COLUMNS).forEach(e -> timestamps.add(new Ts(e.getKey(), e.getValue()[0], e.getValue()[1])));
    }

    private static boolean temporal(QueryRunner.Column c) {
        return c.jdbcType() == Types.TIMESTAMP || c.jdbcType() == Types.TIMESTAMP_WITH_TIMEZONE || c.jdbcType() == Types.DATE
            || (c.name().toLowerCase(Locale.ROOT).matches(".*(timestamp|_at|_date|_time)$") && !c.name().isEmpty());
    }
    private static boolean auditName(String n) { return n.matches(".*(creat|updat|modif|load|insert|timestamp).*"); }

    // ------------------------------------------------------------------ hooks, called from the comparison pass

    void onlySource(List<String> row, List<String> key) {
        onlySourceRows++; baseS.add(row); catOnlyS.add(row);
        times(true, row, true);
        register(true, row, key);
    }

    void onlyTarget(List<String> row, List<String> key) {
        onlyTargetRows++; baseT.add(row); catOnlyT.add(row);
        times(false, row, true);
        register(false, row, key);
    }

    void matched(List<String> sourceRow, List<String> targetRow, boolean different) {
        matchedRows++;
        baseS.add(sourceRow); baseT.add(targetRow);
        times(true, sourceRow, false); times(false, targetRow, false);
        if (different) { differentRows++; catDiff.add(sourceRow); }
    }

    /** One unaccepted cell difference. */
    void cell(ComparisonRunner.Binding binding, List<String> key, String from, String to) {
        differingCellsByColumn.merge(binding.name(), 1L, Long::sum);
        if (!binding.numeric()) return;
        Num n = numbers.computeIfAbsent(binding.name(), k -> new Num());
        BigDecimal a = number(from), b = number(to);
        if (a == null || b == null) { n.unparsed++; return; }
        BigDecimal d = b.subtract(a), abs = d.abs();
        n.count++; n.sumDiff = n.sumDiff.add(d); n.sumAbs = n.sumAbs.add(abs);
        if (n.top.size() < TOP || abs.compareTo(n.top.peek().abs) > 0) {
            n.top.add(new Top(abs, d, String.join("|", key.stream().map(v -> v == null ? "NULL" : v).toList()),
                a.stripTrailingZeros().toPlainString(), b.stripTrailingZeros().toPlainString()));
            if (n.top.size() > TOP) n.top.poll();
        }
        if (n.off0 == null) n.off0 = d; else if (n.offsetConst && d.subtract(n.off0).abs().compareTo(new BigDecimal("0.000000001")) > 0) n.offsetConst = false;
        if (a.signum() != 0) {
            BigDecimal r = b.divide(a, MC);
            n.ratioN++;
            if (n.ratio0 == null) n.ratio0 = r;
            else if (n.ratioConst && r.subtract(n.ratio0).abs().compareTo(n.ratio0.abs().multiply(new BigDecimal("0.000001"))) > 0) n.ratioConst = false;
        } else n.ratioConst = false;
        if (a.signum() == 0 || b.compareTo(a.negate()) != 0) n.flipAll = false;
    }

    private void times(boolean sourceSide, List<String> row, boolean unmatched) {
        for (Ts t : timestamps) {
            int index = sourceSide ? t.s : t.t;
            if (index < 0) continue;
            String v = row.get(index);
            if (v == null || !ISO.matcher(v).find()) continue;
            if (v.length() > 26) v = v.substring(0, 26);
            if (sourceSide) { if (t.sMin == null || v.compareTo(t.sMin) < 0) t.sMin = v; if (t.sMax == null || v.compareTo(t.sMax) > 0) t.sMax = v; }
            else { if (t.tMin == null || v.compareTo(t.tMin) < 0) t.tMin = v; if (t.tMax == null || v.compareTo(t.tMax) > 0) t.tMax = v; }
            if (!unmatched) continue;
            TreeMap<String, Long> dist = sourceSide ? t.onlyS : t.onlyT;
            if (sourceSide ? t.overflowS : t.overflowT) continue;
            dist.merge(v, 1L, Long::sum);
            if (dist.size() > TS_DIST_CAP) { if (sourceSide) t.overflowS = true; else t.overflowT = true; }
        }
    }

    private void register(boolean sourceSide, List<String> row, List<String> key) {
        if (nonKey.isEmpty()) return;
        StringBuilder signature = new StringBuilder();
        for (ComparisonRunner.Binding b : nonKey) {
            String v = row.get(sourceSide ? b.source() : b.target());
            signature.append(v == null ? "\u0002" : Values.normalize(v, b.rule(), b.numeric())).append('\u0001');
        }
        add(sourceSide ? sigS : sigT, hash(signature.toString()), key);
        StringBuilder loose = new StringBuilder();
        for (String k : key) loose.append(k == null ? "" : k.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").replaceFirst("^0+(?=.)", "")).append('\u0001');
        add(sourceSide ? looseS : looseT, hash(loose.toString()), key);
    }

    private void add(Map<Long, Sig> map, long hash, List<String> key) {
        Sig sig = map.get(hash);
        if (sig == null) {
            if (map.size() >= REKEY_CAP) { rekeyTruncated = true; return; }
            sig = new Sig(); map.put(hash, sig);
        }
        sig.count++;
        if (sig.examples.size() < 3) sig.examples.add(String.join("|", key.stream().map(v -> v == null ? "NULL" : v).toList()));
    }

    private static long hash(String text) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) { h ^= text.charAt(i); h *= 0x100000001b3L; }
        return h;
    }

    private static BigDecimal number(String text) {
        if (text == null) return null;
        try { return new BigDecimal(text.strip()); } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------------ evaluation

    List<Finding> evaluate(Map<ComparisonRunner.Group, Long> groups) {
        List<Finding> out = new ArrayList<>();
        if (source.rows() == 0 || target.rows() == 0)
            out.add(new Finding("empty-side", (source.rows() == 0 ? "Source" : "Target") + " returned no rows",
                "Source `" + rule.source + "` has " + source.rows() + " rows, target `" + rule.target + "` has " + target.rows()
                    + ". Check the filters, dates and parameters of that query before reading any difference.", 0, 0, 99));
        rekeyed(out);
        timing(out);
        concentration(out, "only-source", catOnlyS, baseS, onlySourceRows, "source");
        concentration(out, "only-target", catOnlyT, baseT, onlyTargetRows, "target");
        concentration(out, "different", catDiff, baseS, differentRows, "source");
        numeric(out);
        oneColumn(out, groups);
        out.sort(Comparator.comparingDouble(Finding::score).reversed().thenComparing(Finding::title));
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    private static long pairs(Map<Long, Sig> a, Map<Long, Sig> b) {
        long pairs = 0;
        for (var e : a.entrySet()) { Sig other = b.get(e.getKey()); if (other != null) pairs += Math.min(e.getValue().count, other.count); }
        return pairs;
    }

    private String example(Map<Long, Sig> a, Map<Long, Sig> b) {
        List<String> lines = new ArrayList<>();
        for (var e : a.entrySet()) {
            Sig other = b.get(e.getKey());
            if (other != null) lines.add("`" + e.getValue().examples.getFirst() + "` (source) = `" + other.examples.getFirst() + "` (target)");
            if (lines.size() >= 3) break;
        }
        return String.join("; ", lines);
    }

    private void rekeyed(List<Finding> out) {
        long denominator = Math.min(onlySourceRows, onlyTargetRows);
        if (denominator == 0) return;
        String cap = rekeyTruncated ? " Only the first " + REKEY_CAP + " unmatched rows per side were examined." : "";
        long sameValues = pairs(sigS, sigT);
        if (sameValues > 0)
            out.add(new Finding("rekeyed", sameValues + " of " + denominator + " missing rows exist on the other side under a different key",
                "The row has identical values in every compared column but another key, so it is probably not missing: the key changed (re-keyed, re-numbered, re-generated). Examples: "
                    + example(sigS, sigT) + "." + cap, sameValues, denominator, 90.0 * sameValues / denominator));
        long looseKeys = pairs(looseS, looseT);
        if (looseKeys > 0)
            out.add(new Finding("near-miss-key", looseKeys + " of " + denominator + " unmatched rows have a key that matches after ignoring case, punctuation and leading zeros",
                "A key normalisation on the key column would match them (for example `<column name=\"...\" trim=\"true\" caseInsensitive=\"true\"/>`). Examples: "
                    + example(looseS, looseT) + "." + cap, looseKeys, denominator, 88.0 * looseKeys / denominator));
    }

    private static long countAbove(TreeMap<String, Long> dist, String limit) { return dist.tailMap(limit, false).values().stream().mapToLong(Long::longValue).sum(); }
    private static long countBelow(TreeMap<String, Long> dist, String limit) { return dist.headMap(limit, false).values().stream().mapToLong(Long::longValue).sum(); }

    private void timing(List<Finding> out) {
        for (Ts t : timestamps) {
            if (t.s < 0 || t.t < 0) continue;
            // rows only on the target side
            if (onlyTargetRows >= 3 && !t.overflowT && t.sMax != null) {
                long newer = countAbove(t.onlyT, t.sMax);
                if (newer >= 0.9 * onlyTargetRows)
                    out.add(timingFinding("newer-than-source", t.name, newer, onlyTargetRows, "only in the target",
                        "are newer than the newest source row (" + t.sMax + "); the extra rows range " + t.onlyT.firstKey() + " .. " + t.onlyT.lastKey(),
                        "The source looks like an older snapshot: these rows were written after it was taken, so they are not an error in the target."));
            }
            if (onlySourceRows >= 3 && !t.overflowS && t.tMax != null) {
                long newer = countAbove(t.onlyS, t.tMax);
                if (newer >= 0.9 * onlySourceRows)
                    out.add(timingFinding("newer-than-target", t.name, newer, onlySourceRows, "only in the source",
                        "are newer than the newest target row (" + t.tMax + "); they range " + t.onlyS.firstKey() + " .. " + t.onlyS.lastKey(),
                        "The target looks like an older snapshot (or was loaded before these rows existed)."));
            }
            if (onlySourceRows >= 3 && !t.overflowS && t.tMin != null) {
                long older = countBelow(t.onlyS, t.tMin);
                if (older >= 0.9 * onlySourceRows)
                    out.add(timingFinding("older-than-target", t.name, older, onlySourceRows, "only in the source",
                        "are older than the oldest target row (" + t.tMin + "); they range " + t.onlyS.firstKey() + " .. " + t.onlyS.lastKey(),
                        "The target holds no history before that point: look at its retention or load window."));
            }
            if (onlyTargetRows >= 3 && !t.overflowT && t.sMin != null) {
                long older = countBelow(t.onlyT, t.sMin);
                if (older >= 0.9 * onlyTargetRows)
                    out.add(timingFinding("older-than-source", t.name, older, onlyTargetRows, "only in the target",
                        "are older than the oldest source row (" + t.sMin + "); they range " + t.onlyT.firstKey() + " .. " + t.onlyT.lastKey(),
                        "The source holds no history before that point."));
            }
        }
    }

    private Finding timingFinding(String type, String column, long n, long of, String which, String what, String meaning) {
        return new Finding("timing-" + type, column + ": " + n + " of " + of + " rows " + which + " " + what, meaning, n, of, 85.0 * n / of);
    }

    private void concentration(List<Finding> out, String category, Dims cat, Dims base, long total, String side) {
        out.addAll(concentrate(category + " rows", "all " + side + " rows", cat, base, total, 5, 0.8));
    }

    /**
     * Which value of which column do most of the rows in {@code cat} share, compared with how common that value is in {@code base}?
     * Used for unmatched rows of a comparison and for the keys that diverge in a funnel.
     */
    static List<Finding> concentrate(String subject, String baseName, Dims cat, Dims base, long total, int minimumRows, double minimumShare) {
        List<Finding> found = new ArrayList<>();
        if (total < minimumRows) return found;
        for (int i = 0; i < cat.columns.length; i++) {
            if (cat.overflow[i]) continue;
            Map<String, Long> counts = cat.counts.get(i);
            if (counts.size() < 1) continue;
            var best = Collections.max(counts.entrySet(), Map.Entry.comparingByValue());
            double share = (double) best.getValue() / total;
            double baseShare = -1;
            if (!base.overflow[i] && base.rows > 0) baseShare = (double) base.counts.get(i).getOrDefault(best.getKey(), 0L) / base.rows;
            boolean informative = baseShare >= 0 ? share >= minimumShare && share - baseShare >= 0.3 && share >= 2 * baseShare : share >= Math.max(0.9, minimumShare);
            if (!informative) continue;
            String name = cat.names[i];
            String sentence = best.getValue() + " of " + total + " " + subject + " (" + Math.round(share * 100) + "%) have " + name + " = `" + best.getKey() + "`"
                + (baseShare >= 0 ? ", against " + String.format(Locale.ROOT, "%.1f", baseShare * 100) + "% of " + baseName : "");
            double lift = baseShare > 0 ? Math.min(5, share / baseShare) / 5 : 1;
            found.add(new Finding("concentration", sentence,
                counts.size() == 1 ? "All of them share this value; look at what that value stands for (a product, a user, a batch, a date)."
                    : "The other values: " + counts.entrySet().stream().filter(e -> !e.getKey().equals(best.getKey())).sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(3).map(e -> "`" + e.getKey() + "` " + e.getValue()).toList() + ".",
                best.getValue(), total, 60.0 * share * (0.5 + 0.5 * lift)));
        }
        found.sort(Comparator.comparingDouble(Finding::score).reversed());
        return new ArrayList<>(found.subList(0, Math.min(3, found.size())));
    }

    private void numeric(List<Finding> out) {
        for (var entry : numbers.entrySet()) {
            Num n = entry.getValue();
            String col = entry.getKey();
            if (n.count == 0) continue;
            if (n.flipAll)
                out.add(new Finding("sign-flip", col + ": every differing value has the opposite sign", "All " + n.count + " differing cells have target = -source: a sign convention differs between the two sides (debit/credit, long/short, direction).", n.count, n.count, 96));
            else if (n.count >= 3 && n.offsetConst && n.off0.signum() != 0)
                out.add(new Finding("constant-offset", col + ": target = source " + (n.off0.signum() > 0 ? "+ " : "- ") + n.off0.abs().stripTrailingZeros().toPlainString() + " in all " + n.count + " differing cells",
                    "A fixed amount is added on one side (a fee, an adjustment, a default).", n.count, n.count, 95));
            else if (n.count >= 3 && n.ratioConst && n.ratioN == n.count)
                out.add(new Finding("constant-ratio", col + ": target = source x " + n.ratio0.round(new MathContext(8)).stripTrailingZeros().toPlainString() + " in all " + n.count + " differing cells",
                    "A fixed factor between the two sides: a unit or currency scale (x100, x1000), a percentage versus a fraction, or an FX rate.", n.count, n.count, 95));
            List<Top> top = n.top.stream().sorted(Comparator.comparing((Top t) -> t.abs).reversed()).toList();
            BigDecimal top5 = top.stream().limit(5).map(t -> t.abs).reduce(BigDecimal.ZERO, BigDecimal::add);
            double share = n.sumAbs.signum() == 0 ? 0 : top5.divide(n.sumAbs, MC).doubleValue();
            String rows = top.stream().limit(5).map(t -> "`" + t.key + "` " + t.from + " -> " + t.to + " (" + t.diff.stripTrailingZeros().toPlainString() + ")").toList().toString();
            String net = "net (target - source) = " + n.sumDiff.stripTrailingZeros().toPlainString() + ", sum of absolute differences = " + n.sumAbs.stripTrailingZeros().toPlainString();
            if (n.count > 5 && share >= 0.8)
                out.add(new Finding("few-rows-drive-gap", col + ": 5 of " + n.count + " differing cells carry " + Math.round(share * 100) + "% of the absolute difference",
                    net + ". Largest: " + rows + ".", Math.min(5, n.count), n.count, 78 * share));
            else
                out.add(new Finding("numeric-gap", col + ": " + n.count + " differing cell" + (n.count == 1 ? "" : "s") + ", " + net,
                    "Largest: " + rows + (n.unparsed > 0 ? " (" + n.unparsed + " cells were not numbers and are left out)" : "") + ".", n.count, n.count, n.count <= 5 ? 50 : 40));
        }
    }

    private void oneColumn(List<Finding> out, Map<ComparisonRunner.Group, Long> groups) {
        if (groups == null || nonKey.size() < 2) return;
        Map<String, Long> perColumn = new HashMap<>();
        long total = 0;
        for (var g : groups.entrySet()) if (!g.getKey().accepted()) { perColumn.merge(g.getKey().column(), g.getValue(), Long::sum); total += g.getValue(); }
        if (total < 3 || perColumn.size() != 1) return;
        var only = perColumn.entrySet().iterator().next();
        out.add(new Finding("one-column", "All " + total + " differing cells are in one column: " + only.getKey(),
            "The other " + (nonKey.size() - 1) + " compared columns agree wherever rows match, so the cause is specific to this column.", total, total, 55));
    }

    // ------------------------------------------------------------------ output

    static String line(Finding f, int max) {
        String text = f.title() + (f.evidence().isEmpty() ? "" : " - " + f.evidence());
        return text.length() > max ? text.substring(0, max - 3) + "..." : text;
    }

    static String markdown(String comparison, List<Finding> findings) {
        StringBuilder md = new StringBuilder("# Findings: " + comparison + "\n\nComputed from the complete comparison (counts are exact; examples are capped). Most important first. "
            + "A finding says what the data shows, not what caused it.\n");
        int i = 1;
        for (Finding f : findings)
            md.append("\n## ").append(i++).append(". ").append(f.title()).append("\n\n").append(f.evidence()).append("\n\n(")
                .append(f.type()).append(f.of() > 0 ? ", explains " + f.explains() + " of " + f.of() : "").append(")\n");
        return md.toString();
    }

    static void write(Path directory, String comparison, List<Finding> findings) throws IOException {
        if (findings.isEmpty()) return;
        Files.writeString(directory.resolve("FINDINGS.md"), markdown(comparison, findings));
        List<Map<String, Object>> json = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", f.type()); m.put("title", f.title()); m.put("evidence", f.evidence());
            m.put("explains", f.explains()); m.put("of", f.of()); m.put("score", Math.round(f.score() * 10) / 10.0);
            json.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("comparison", comparison); root.put("findings", json);
        Files.writeString(directory.resolve("findings.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
