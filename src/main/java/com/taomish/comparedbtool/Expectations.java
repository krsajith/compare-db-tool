package com.taomish.comparedbtool;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Expected results stated in a rule ({@code <expect>}): validated offline, evaluated after the run, reported as one
 * PASS/FAIL verdict per check. Counts are always exact; they come from the finished query results and comparisons.
 */
final class Expectations {
    private Expectations() {}

    record Verdict(String ref, String check, String expected, String actual, boolean pass, String reason) {}

    record Report(List<Verdict> verdicts, Set<String> judgedComparisons, List<String> notEvaluated) {
        Report(List<Verdict> verdicts, Set<String> judgedComparisons) { this(verdicts, judgedComparisons, List.of()); }
        boolean any() { return !verdicts.isEmpty(); }
        long failed() { return verdicts.stream().filter(v -> !v.pass()).count(); }
        boolean passed() { return failed() == 0; }
        String headline() {
            return !any() ? "NOT CHECKED (the rule has no expectations)"
                : passed() ? "PASS (" + verdicts.size() + " of " + verdicts.size() + " expectations met)"
                : "FAIL (" + failed() + " of " + verdicts.size() + " expectations not met)";
        }
    }

    /** A count requirement: N, &lt;=N, &gt;=N, &lt;N, &gt;N or A..B. */
    static final class Spec {
        final String text;
        final long min, max;
        private Spec(String text, long min, long max) { this.text = text; this.min = min; this.max = max; }
        boolean test(long value) { return value >= min && value <= max; }
        static Spec parse(String attribute, String raw) {
            String t = raw == null ? "" : raw.strip();
            try {
                if (t.contains("..")) {
                    String[] p = t.split("\\.\\.", -1);
                    if (p.length != 2) throw new NumberFormatException();
                    long lo = Long.parseLong(p[0].strip()), hi = Long.parseLong(p[1].strip());
                    if (lo > hi) throw new NumberFormatException();
                    return new Spec(t, lo, hi);
                }
                if (t.startsWith("<=")) return new Spec(t, Long.MIN_VALUE, Long.parseLong(t.substring(2).strip()));
                if (t.startsWith(">=")) return new Spec(t, Long.parseLong(t.substring(2).strip()), Long.MAX_VALUE);
                if (t.startsWith("<")) return new Spec(t, Long.MIN_VALUE, Long.parseLong(t.substring(1).strip()) - 1);
                if (t.startsWith(">")) return new Spec(t, Long.parseLong(t.substring(1).strip()) + 1, Long.MAX_VALUE);
                long n = Long.parseLong(t);
                return new Spec(t, n, n);
            } catch (NumberFormatException e) {
                throw new ToolException("Invalid expected count " + attribute + "=\"" + raw
                    + "\"; use N, <=N, >=N, <N, >N or A..B.");
            }
        }
    }

    // ---------------------------------------------------------------- validation (offline)

    static void validate(Rules rules, Set<String> queryIds) {
        if (rules.expectations == null) { rules.expectations = new ArrayList<>(); return; }
        Set<String> comparisonIds = new HashSet<>();
        for (Rules.Compare c : rules.comparisons) comparisonIds.add(c.id);
        Map<String, Rules.Funnel> funnels = new HashMap<>();
        for (Rules.Funnel f : rules.funnels) funnels.put(f.id, f);
        for (Rules.Expect e : rules.expectations) {
            if (e.expr != null) {
                boolean others = e.ref != null || e.rows != null || e.column != null || e.row != null || e.each != null || e.equals != null || e.min != null
                    || e.max != null || e.between != null || e.isNull != null || e.matched != null || e.equal != null || e.different != null
                    || e.onlySource != null || e.onlyTarget != null || e.duplicates != null || e.firstDivergence != null || e.diverging != null;
                if (others) throw new ToolException("<expect expr=\"...\"> cannot be combined with ref= or the other checks; give each its own <expect>.");
                if (e.judges != null) for (String id : e.judges.split(",", -1)) {
                    String name = id.strip();
                    if (!comparisonIds.contains(name) && !funnels.containsKey(name))
                        throw new ToolException("<expect expr=\"...\" judges=\"" + e.judges + "\">: '" + name + "' is not a comparison or funnel. Known: "
                            + new TreeSet<>(comparisonIds) + " and " + new TreeSet<>(funnels.keySet()) + ".");
                }
                continue;
            }
            if (e.judges != null) throw new ToolException("judges= belongs to <expect expr=\"...\">.");
            if (e.ref == null || e.ref.isBlank()) throw new ToolException("<expect> needs ref=\"query or comparison id\" or expr=\"...\".");
            boolean query = queryIds.contains(e.ref), comparison = comparisonIds.contains(e.ref), funnel = funnels.containsKey(e.ref);
            if (!query && !comparison && !funnel)
                throw new ToolException("<expect ref=\"" + e.ref + "\"> references an unknown id. Queries: "
                    + new TreeSet<>(queryIds) + "; comparisons: " + new TreeSet<>(comparisonIds) + "; funnels: " + new TreeSet<>(funnels.keySet()));
            boolean funnelAttrs = e.firstDivergence != null || e.diverging != null;
            boolean queryAttrs = e.rows != null || e.column != null || e.row != null || e.each != null || e.equals != null
                || e.min != null || e.max != null || e.between != null || e.isNull != null;
            boolean compareAttrs = e.matched != null || e.equal != null || e.different != null || e.onlySource != null
                || e.onlyTarget != null || e.duplicates != null;
            if (funnel) {
                if (queryAttrs || compareAttrs) throw new ToolException("<expect ref=\"" + e.ref + "\"> is a funnel: use first-divergence or diverging.");
                if (!funnelAttrs) throw new ToolException("<expect ref=\"" + e.ref + "\"> states nothing to check.");
                Rules.Funnel f = funnels.get(e.ref);
                if (e.firstDivergence != null) {
                    if (FunnelRunner.chain(f)) throw new ToolException("<expect ref=\"" + e.ref + "\">: first-divergence applies to comparison funnels, not chains.");
                    boolean known = e.firstDivergence.equalsIgnoreCase("none") || f.stages.stream().anyMatch(st -> st.id.equalsIgnoreCase(e.firstDivergence));
                    if (!known) throw new ToolException("<expect ref=\"" + e.ref + "\">: first-divergence must be none or one of "
                        + f.stages.stream().map(st -> st.id).toList() + ".");
                }
                if (e.diverging != null) Spec.parse("diverging", e.diverging);
            } else if (query) {
                if (funnelAttrs) throw new ToolException("<expect ref=\"" + e.ref + "\"> is a query: first-divergence and diverging apply to funnels.");
                if (compareAttrs) throw new ToolException("<expect ref=\"" + e.ref + "\"> is a query: matched/equal/different/only-source/only-target/duplicates apply to comparisons.");
                if (e.rows != null) Spec.parse("rows", e.rows);
                boolean condition = e.equals != null || e.min != null || e.max != null || e.between != null || e.isNull != null;
                if (e.column == null && (condition || e.row != null || e.each != null))
                    throw new ToolException("<expect ref=\"" + e.ref + "\"> needs column=\"...\" for equals/min/max/between/isNull/row/each.");
                if (e.column != null && !condition)
                    throw new ToolException("<expect ref=\"" + e.ref + "\" column=\"" + e.column + "\"> needs equals, min, max, between or isNull.");
                if (e.column == null && e.rows == null) throw new ToolException("<expect ref=\"" + e.ref + "\"> states nothing to check.");
                if (e.row != null && (e.row < 1 || Boolean.TRUE.equals(e.each)))
                    throw new ToolException("<expect ref=\"" + e.ref + "\">: row is 1-based and cannot be combined with each=\"true\".");
                if (e.min != null) number("min", e.min);
                if (e.max != null) number("max", e.max);
                if (e.between != null) between(e.between);
            } else {
                if (queryAttrs || funnelAttrs) throw new ToolException("<expect ref=\"" + e.ref + "\"> is a comparison: use matched, equal, different, only-source, only-target or duplicates.");
                if (!compareAttrs) throw new ToolException("<expect ref=\"" + e.ref + "\"> states nothing to check.");
                for (var entry : comparisonSpecs(e).entrySet()) Spec.parse(entry.getKey(), entry.getValue());
            }
        }
    }

    private static LinkedHashMap<String, String> comparisonSpecs(Rules.Expect e) {
        var specs = new LinkedHashMap<String, String>();
        if (e.matched != null) specs.put("matched", e.matched);
        if (e.equal != null) specs.put("equal", e.equal);
        if (e.different != null) specs.put("different", e.different);
        if (e.onlySource != null) specs.put("only-source", e.onlySource);
        if (e.onlyTarget != null) specs.put("only-target", e.onlyTarget);
        if (e.duplicates != null) specs.put("duplicates", e.duplicates);
        return specs;
    }

    private static BigDecimal number(String attribute, String text) {
        try { return new BigDecimal(text.strip()); }
        catch (NumberFormatException e) { throw new ToolException("Invalid number " + attribute + "=\"" + text + "\" in <expect>."); }
    }

    private static BigDecimal[] between(String text) {
        String[] p = text.split(",", -1);
        if (p.length != 2) throw new ToolException("between=\"" + text + "\" must be two numbers, for example between=\"73000,74500\".");
        BigDecimal lo = number("between", p[0]), hi = number("between", p[1]);
        if (lo.compareTo(hi) > 0) throw new ToolException("between=\"" + text + "\": the first number is larger than the second.");
        return new BigDecimal[]{lo, hi};
    }

    // ---------------------------------------------------------------- evaluation

    static Report evaluate(Rules rules, Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons) {
        return evaluate(rules, queries, comparisons, List.of());
    }

    static Report evaluate(Rules rules, Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons,
                           List<FunnelRunner.Result> funnels) {
        return evaluate(rules, queries, comparisons, funnels, Map.of());
    }

    static Report evaluate(Rules rules, Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons,
                           List<FunnelRunner.Result> funnels, Map<String, Object> parameters) {
        return evaluate(rules, queries, comparisons, funnels, parameters, Set.of());
    }

    /** {@code skipped}: ids of queries, comparisons and funnels that were skipped by a when= condition; expectations on them are listed as not evaluated. */
    static Report evaluate(Rules rules, Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons,
                           List<FunnelRunner.Result> funnels, Map<String, Object> parameters, Set<String> skipped) {
        List<Verdict> verdicts = new ArrayList<>();
        List<String> notEvaluated = new ArrayList<>();
        Map<String, FunnelRunner.Result> funnelById = new HashMap<>();
        funnels.forEach(f -> funnelById.put(f.id(), f));
        Set<String> funnelIds = new HashSet<>();
        rules.funnels.forEach(f -> funnelIds.add(f.id));
        Set<String> judged = new LinkedHashSet<>();
        Map<String, ComparisonRunner.Result> byId = new HashMap<>();
        comparisons.forEach(c -> byId.put(c.id(), c));
        Set<String> comparisonIds = new HashSet<>();
        rules.comparisons.forEach(c -> comparisonIds.add(c.id));
        Set<String> schemaChecked = new HashSet<>();
        for (Rules.Expect e : rules.expectations) {
            if (e.ref != null && skipped.contains(e.ref)) { notEvaluated.add(e.ref + " (skipped)"); continue; }
            if (e.when != null && !conditionHolds(rules, e, queries, comparisons, funnels, parameters, skipped, verdicts)) continue;
            if (e.expr != null) {
                if (e.judges != null) for (String id : e.judges.split(",", -1)) judged.add(id.strip());
                evaluateExpression(rules, e, queries, comparisons, funnels, parameters, skipped, verdicts);
                continue;
            }
            if (funnelIds.contains(e.ref)) {
                judged.add(e.ref);
                evaluateFunnel(e, rules.funnels.stream().filter(f -> f.id.equals(e.ref)).findFirst().orElseThrow(), funnelById.get(e.ref), verdicts);
            } else if (comparisonIds.contains(e.ref)) {
                judged.add(e.ref);
                evaluateComparison(e, byId.get(e.ref), verdicts, schemaChecked);
            } else evaluateQuery(e, queries.get(e.ref), verdicts);
        }
        return new Report(List.copyOf(verdicts), Set.copyOf(judged), List.copyOf(notEvaluated));
    }

    /** False when the expectation's when= says to skip it; a when= that cannot be evaluated is reported as a failed check instead of being skipped silently. */
    private static Set<String> names(Rules rules, Map<String, Object> parameters) {
        Set<String> names = new TreeSet<>(parameters.keySet());
        names.addAll(Expressions.resultNames(rules));
        return names;
    }

    private static boolean conditionHolds(Rules rules, Rules.Expect e, Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons,
                                          List<FunnelRunner.Result> funnels, Map<String, Object> parameters, Set<String> skipped, List<Verdict> out) {
        String where = "<expect when=\"" + Menu.shorten(e.when, 40) + "\">";
        try {
            var expression = Expressions.parse(e.when, where, names(rules, parameters), true);
            var context = Expressions.context(parameters, new Expressions.Lookups(rules, queries, comparisons, funnels, skipped), expression);
            return Expressions.bool(Expressions.evaluate(expression, context, where), where, e.when);
        } catch (ToolException ex) {
            out.add(new Verdict(e.ref == null ? "expression" : e.ref, "when " + e.when, "true or false", ex.getMessage(), false, e.reason));
            return false;
        }
    }

    private static void evaluateExpression(Rules rules, Rules.Expect e, Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons,
                                           List<FunnelRunner.Result> funnels, Map<String, Object> parameters, Set<String> skipped, List<Verdict> out) {
        String where = "<expect expr=\"" + Menu.shorten(e.expr, 40) + "\">";
        var lookups = new Expressions.Lookups(rules, queries, comparisons, funnels, skipped);
        try {
            var expression = Expressions.parse(e.expr, where, names(rules, parameters), true);
            boolean holds = Expressions.bool(Expressions.evaluate(expression, Expressions.context(parameters, lookups, expression), where), where, e.expr);
            out.add(new Verdict("expression", e.expr, "true", holds ? "true" : "false" + Expressions.touchedSummary(lookups.touched), holds, e.reason));
        } catch (ToolException ex) {
            out.add(new Verdict("expression", e.expr, "true", ex.getMessage() + Expressions.touchedSummary(lookups.touched), false, e.reason));
        }
    }

    private static void evaluateFunnel(Rules.Expect e, Rules.Funnel funnel, FunnelRunner.Result r, List<Verdict> out) {
        if (r == null) {
            String why = "funnel did not complete (see MANUAL_ACTIONS.md)";
            if (e.firstDivergence != null) out.add(new Verdict(e.ref, "first-divergence", e.firstDivergence, why, false, e.reason));
            if (e.diverging != null) out.add(new Verdict(e.ref, "diverging", e.diverging, why, false, e.reason));
            return;
        }
        if (e.firstDivergence != null) {
            String actual = r.firstDivergence() < 0 ? "none" : funnel.stages.get(r.firstDivergence()).id;
            out.add(new Verdict(e.ref, "first-divergence", e.firstDivergence, actual, actual.equalsIgnoreCase(e.firstDivergence), e.reason));
        }
        if (e.diverging != null) {
            Spec spec = Spec.parse("diverging", e.diverging);
            out.add(new Verdict(e.ref, r.chain() ? "dropping out" : "diverging keys", spec.text, String.valueOf(r.diverging()), spec.test(r.diverging()), e.reason));
        }
    }

    private static void evaluateComparison(Rules.Expect e, ComparisonRunner.Result r, List<Verdict> out, Set<String> schemaChecked) {
        var specs = comparisonSpecs(e);
        if (r == null) {
            specs.forEach((name, text) -> out.add(new Verdict(e.ref, name, text, "comparison did not complete (see MANUAL_ACTIONS.md)", false, e.reason)));
            return;
        }
        for (var entry : specs.entrySet()) {
            long actual = switch (entry.getKey()) {
                case "matched" -> r.matched();
                case "equal" -> r.equal();
                case "different" -> r.different();
                case "only-source" -> r.onlySource();
                case "only-target" -> r.onlyTarget();
                default -> r.duplicateSourceKeys() + r.duplicateTargetKeys();
            };
            Spec spec = Spec.parse(entry.getKey(), entry.getValue());
            boolean ok = spec.test(actual) && !r.partial();
            out.add(new Verdict(e.ref, entry.getKey(), spec.text, r.partial() ? actual + " (PARTIAL comparison)" : String.valueOf(actual), ok, e.reason));
        }
        // A column that exists on one side only is not covered by any count: never let it pass silently.
        if (r.schemaDifferent() && schemaChecked.add(e.ref))
            out.add(new Verdict(e.ref, "schema", "same columns on both sides", "columns differ (see the comparison SUMMARY.md)", false, e.reason));
    }

    private static void evaluateQuery(Rules.Expect e, QueryRunner.Result r, List<Verdict> out) {
        List<String[]> checks = new ArrayList<>(); // {name, expectedText}
        if (e.rows != null) checks.add(new String[]{"rows", e.rows});
        String where = e.column == null ? "" : e.column + (Boolean.TRUE.equals(e.each) ? " (every row)" : " (row " + (e.row == null ? 1 : e.row) + ")");
        if (e.equals != null) checks.add(new String[]{where + " equals", e.equals});
        if (e.min != null) checks.add(new String[]{where + " min", e.min});
        if (e.max != null) checks.add(new String[]{where + " max", e.max});
        if (e.between != null) checks.add(new String[]{where + " between", e.between});
        if (e.isNull != null) checks.add(new String[]{where + " isNull", String.valueOf(e.isNull)});
        if (r == null || r.partial()) {
            String why = r == null ? "query did not complete (see MANUAL_ACTIONS.md)" : "query result is PARTIAL (row cap)";
            for (String[] c : checks) out.add(new Verdict(e.ref, c[0], c[1], why, false, e.reason));
            return;
        }
        if (e.rows != null) {
            Spec spec = Spec.parse("rows", e.rows);
            out.add(new Verdict(e.ref, "rows", spec.text, String.valueOf(r.rows()), spec.test(r.rows()), e.reason));
        }
        if (e.column == null) return;
        boolean each = Boolean.TRUE.equals(e.each);
        int row = e.row == null ? 1 : e.row;
        if (e.equals != null) out.add(columnCheck(e, r, where + " equals", e.equals, each, row, v -> v != null && sameValue(v, e.equals)));
        if (e.min != null) {
            BigDecimal lo = number("min", e.min);
            out.add(columnCheck(e, r, where + " min", e.min, each, row, v -> numeric(v) != null && numeric(v).compareTo(lo) >= 0));
        }
        if (e.max != null) {
            BigDecimal hi = number("max", e.max);
            out.add(columnCheck(e, r, where + " max", e.max, each, row, v -> numeric(v) != null && numeric(v).compareTo(hi) <= 0));
        }
        if (e.between != null) {
            BigDecimal[] range = between(e.between);
            out.add(columnCheck(e, r, where + " between", e.between, each, row,
                v -> numeric(v) != null && numeric(v).compareTo(range[0]) >= 0 && numeric(v).compareTo(range[1]) <= 0));
        }
        if (e.isNull != null) out.add(columnCheck(e, r, where + " isNull", String.valueOf(e.isNull), each, row, v -> (v == null) == e.isNull));
    }

    private static Verdict columnCheck(Rules.Expect e, QueryRunner.Result result, String check, String expected,
                                       boolean each, int row, Predicate<String> accept) {
        if (result.csv() == null || !Files.exists(result.csv()))
            return new Verdict(e.ref, check, expected, "rows are not retained for this query (input of an engine=db comparison): use rows= or compare in SQL", false, e.reason);
        try (var parser = CsvFiles.reader(result.csv(), true)) {
            Integer index = null;
            for (var header : parser.getHeaderMap().entrySet())
                if (header.getKey().equalsIgnoreCase(e.column)) index = header.getValue();
            if (index == null) return new Verdict(e.ref, check, expected, "column " + e.column + " is not in the result (" + parser.getHeaderNames() + ")", false, e.reason);
            long number = 0, checked = 0;
            for (var record : parser) {
                number++;
                if (!each && number < row) continue;
                String value = record.get(index);
                checked++;
                if (!accept.test(value))
                    return new Verdict(e.ref, check, expected, "row " + number + ": " + (value == null ? "NULL" : value), false, e.reason);
                if (!each) return new Verdict(e.ref, check, expected, value == null ? "NULL" : value, true, e.reason);
            }
            if (each) return new Verdict(e.ref, check, expected, checked + " rows checked, all ok", true, e.reason);
            return new Verdict(e.ref, check, expected, "result has only " + number + " row(s)", false, e.reason);
        } catch (IOException | RuntimeException ex) {
            return new Verdict(e.ref, check, expected, "could not read the result: " + ex.getMessage(), false, e.reason);
        }
    }

    private static BigDecimal numeric(String value) {
        if (value == null) return null;
        try { return new BigDecimal(value.strip()); } catch (NumberFormatException e) { return null; }
    }

    static boolean sameValue(String actual, String expected) {
        BigDecimal a = numeric(actual), b = numeric(expected);
        return a != null && b != null ? a.compareTo(b) == 0 : actual.strip().equals(expected.strip());
    }

    // ---------------------------------------------------------------- reporting

    static String cell(String text) {
        if (text == null) return "";
        String flat = text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
        return flat.length() > 160 ? flat.substring(0, 157) + "..." : flat;
    }

    /** Failing checks first, so a truncated summary still shows what is wrong. */
    static String table(Report report) {
        StringBuilder md = new StringBuilder("| Verdict | Check | Expected | Actual | Why this is expected |\n|---|---|---|---|---|\n");
        List<Verdict> ordered = new ArrayList<>(report.verdicts());
        ordered.sort(Comparator.comparing(Verdict::pass));
        for (Verdict v : ordered)
            md.append("| ").append(v.pass() ? "PASS" : "FAIL").append(" | ").append(cell(v.ref() + ": " + v.check()))
                .append(" | ").append(cell(v.expected())).append(" | ").append(cell(v.actual()))
                .append(" | ").append(cell(v.reason())).append(" |\n");
        return md.toString();
    }

    static void write(Path directory, Report report) throws IOException {
        if (!report.any()) return;
        Files.writeString(directory.resolve("EXPECTATIONS.md"), "# Expectations: " + report.headline() + "\n\n" + table(report)
            + (report.notEvaluated().isEmpty() ? "" : "\nNot evaluated: " + String.join(", ", report.notEvaluated()) + "\n"));
    }

    /** One-row and other tiny results printed in the summary, so counts are visible without --export full. */
    static String smallResults(Rules rules, Map<String, QueryRunner.Result> queries) {
        StringBuilder md = new StringBuilder();
        for (Rules.Sql query : rules.queries) {
            QueryRunner.Result r = queries.get(query.id);
            if (r == null || r.partial() || r.csv() == null || !Files.exists(r.csv()) || r.rows() == 0
                || r.rows() > 5 || r.columns().size() > 12) continue;
            try (var parser = CsvFiles.reader(r.csv(), true)) {
                StringBuilder block = new StringBuilder("\n`" + query.id + "` (" + r.rows() + (r.rows() == 1 ? " row" : " rows") + ")\n\n|");
                for (var column : r.columns()) block.append(' ').append(cell(column.name())).append(" |");
                block.append("\n|").append("---|".repeat(r.columns().size())).append('\n');
                for (var record : parser) {
                    block.append('|');
                    for (int i = 0; i < r.columns().size(); i++) {
                        String v = record.get(i);
                        block.append(' ').append(v == null ? "NULL" : cell(v.length() > 60 ? v.substring(0, 57) + "..." : v)).append(" |");
                    }
                    block.append('\n');
                }
                if (md.length() + block.length() > 3000) { md.append("\n(further small results omitted; see the query metadata)\n"); break; }
                md.append(block);
            } catch (IOException | RuntimeException ignored) { /* the summary is a convenience; the CSV stays authoritative */ }
        }
        return md.length() == 0 ? "" : "## Small query results\n" + md + "\n";
    }
}
