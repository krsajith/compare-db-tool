package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

/**
 * --check: ask the databases about every query (EXPLAIN and a description of its columns, never executing it), then check the rule
 * against what they said: key columns of comparisons, funnels and presence matrices, columns used by {@code :alias.column}, columns named
 * in expectations. A mistake that would otherwise show up at the end of a long run shows up in seconds. Nothing is read or written.
 */
final class LiveCheck {
    private LiveCheck() {}

    enum Status { OK, WARN, FAIL, SKIP }

    record Line(Status status, String subject, String text) {
        String render() { return String.format(Locale.ROOT, "  %-5s %s%s", status, subject, text.isEmpty() ? "" : ": " + text); }
    }

    static final long LARGE_ESTIMATE = 5_000_000;

    /** Returns the exit code: 2 when something would fail, 0 otherwise. */
    static int run(Rules rules, Map<String, Object> parameters, DatasourceSettings settings, boolean allowProd, Path directory) throws IOException {
        List<Line> lines = new ArrayList<>();
        Progress.get().line("compare-db-tool: checking " + rules.name + " against the databases (EXPLAIN and column descriptions only; no data is read)");
        Map<String, QueryRunner.Result> stubs = new LinkedHashMap<>();
        Set<String> unreachable = new HashSet<>();
        long connectMs = Long.getLong("compare.check.connect.ms", 10_000);
        try (QueryRunner runner = new QueryRunner(settings, allowProd, connectMs)) {
            for (Rules.Sql query : rules.queries) {
                String subject = query.id + " (" + query.db + ")";
                if (query.tempTables) { add(lines, Status.SKIP, subject, "temp-table queries are checked only when they run"); continue; }
                if (unreachable.contains(query.db)) { add(lines, Status.SKIP, subject, "datasource " + query.db + " could not be reached (see above)"); continue; }
                Map<String, Object> bound = new HashMap<>(parameters);
                bound.putAll(Chaining.placeholders(query));
                boolean usesOtherQueries = !Chaining.references(query).isEmpty();
                try {
                    QueryRunner.Described d = runner.describe(query, bound);
                    if (d.columns() != null) stubs.put(query.id, new QueryRunner.Result(query.id, null, d.columns(), 0, false, 0, List.of()));
                    StringBuilder text = new StringBuilder(d.columns() == null ? "described" : d.columns().size() + (d.columns().size() == 1 ? " column" : " columns"));
                    boolean large = d.estimatedRows() != null && d.estimatedRows() >= LARGE_ESTIMATE;
                    if (d.estimatedRows() != null) text.append(", the planner expects about ").append(Progress.count(d.estimatedRows())).append(" rows");
                    if (large) text.append(" - a large export; consider a narrower filter or a row limit (estimates can be off)");
                    add(lines, large ? Status.WARN : Status.OK, subject, text.toString());
                } catch (Exception error) {
                    Throwable root = error;
                    String sqlState = null;
                    boolean connection = false;
                    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                        root = cause;
                        if (cause instanceof SQLException se && sqlState == null && se.getSQLState() != null) sqlState = se.getSQLState();
                        String name = cause.getClass().getSimpleName();
                        if (name.contains("CannotGetJdbcConnection") || name.contains("TransientConnection") || name.contains("ConnectException") || (sqlState != null && sqlState.startsWith("08"))) connection = true;
                    }
                    String message = SecretRedactorHolder.clean(Objects.toString(root.getMessage(), root.getClass().getSimpleName())).replaceAll("\\s+", " ");
                    if (message.length() > 260) message = message.substring(0, 257) + "...";
                    if (connection) {
                        unreachable.add(query.db);
                        add(lines, Status.FAIL, subject, "cannot connect to " + query.db + ": " + message);
                    } else if (error instanceof ToolException && root == error) {
                        add(lines, Status.FAIL, subject, message);
                    } else if (usesOtherQueries && (sqlState == null || !sqlState.startsWith("42"))) {
                        // untyped placeholders for the other queries' values can upset a database that needs the type; the structure is still checked below
                        add(lines, Status.WARN, subject, "could not be checked completely because it uses values from another query: " + message);
                    } else {
                        add(lines, Status.FAIL, subject, message);
                    }
                }
            }
            for (Rules.Explain explain : rules.explains) {
                String subject = "explain " + explain.id + " (" + explain.db + ")";
                if (explain.tempTables) { add(lines, Status.SKIP, subject, "temp-table statements are checked only when they run"); continue; }
                if (unreachable.contains(explain.db)) { add(lines, Status.SKIP, subject, "datasource " + explain.db + " could not be reached (see above)"); continue; }
                try {
                    Map<String, Object> bound = new HashMap<>(parameters);
                    bound.putAll(Chaining.placeholders(ExplainRunner.asSql(explain)));
                    QueryRunner.Described d = runner.describe(ExplainRunner.asSql(explain), bound);
                    add(lines, Status.OK, subject, "the statement plans" + (d.estimatedRows() != null ? ", the planner expects about " + Progress.count(d.estimatedRows()) + " rows" : "")
                        + (explain.analyze ? "; the run executes it (read-only, rolled back)" : "; plan only, nothing is executed"));
                } catch (Exception error) {
                    Throwable root = error;
                    for (Throwable cause = error; cause != null; cause = cause.getCause()) root = cause;
                    String message = SecretRedactorHolder.clean(Objects.toString(root.getMessage(), root.getClass().getSimpleName())).replaceAll("\\s+", " ");
                    if (message.length() > 260) message = message.substring(0, 257) + "...";
                    boolean usesOtherQueries = explain.uses != null && !explain.uses.isBlank();
                    if (usesOtherQueries && !(error instanceof ToolException))
                        add(lines, Status.WARN, subject, "could not be checked completely because it uses values from another query: " + message);
                    else add(lines, Status.FAIL, subject, message);
                }
            }
        }
        structure(rules, stubs, lines);
        long failed = lines.stream().filter(l -> l.status() == Status.FAIL).count(), warned = lines.stream().filter(l -> l.status() == Status.WARN).count();
        int exit = failed > 0 ? 2 : 0;
        StringBuilder md = new StringBuilder("# Check of " + rules.name + "\n\nTool: " + BuildInfo.line() + "\n\nThe databases were asked to EXPLAIN each query and describe its columns; no query was executed and no data was read.\n\n");
        md.append(failed == 0 ? "**No problems found" + (warned > 0 ? " (" + warned + " warning" + (warned == 1 ? "" : "s") + ")" : "") + ".**\n\n"
            : "**" + failed + " problem" + (failed == 1 ? "" : "s") + " found. Fix them before the long run.**\n\n");
        md.append("| Result | What | Detail |\n|---|---|---|\n");
        lines.stream().sorted(Comparator.comparing((Line l) -> l.status() == Status.FAIL ? 0 : l.status() == Status.WARN ? 1 : 2)).forEach(l ->
            md.append("| ").append(l.status()).append(" | ").append(Expectations.cell(l.subject())).append(" | ").append(Expectations.cell(l.text())).append(" |\n"));
        Files.writeString(directory.resolve("CHECK.md"), md.toString());
        Progress.get().line("compare-db-tool: check done - " + (failed == 0 ? "no problems found" + (warned > 0 ? ", " + warned + " warning(s)" : "")
            : failed + " problem(s) found; fix them before the long run") + ". Nothing was read.");
        Progress.get().line("  report: " + directory.resolve("CHECK.md"));
        return exit;
    }

    private static void add(List<Line> lines, Status status, String subject, String text) {
        Line line = new Line(status, subject, text);
        lines.add(line);
        Progress.get().line(line.render());
    }

    private static boolean has(QueryRunner.Result r, String column) {
        return r.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(column));
    }

    private static String columns(QueryRunner.Result r) { return r.columns().stream().map(QueryRunner.Column::name).toList().toString(); }

    /** Checks that need the real column lists of the queries. */
    private static void structure(Rules rules, Map<String, QueryRunner.Result> stubs, List<Line> lines) {
        for (Rules.Compare compare : rules.comparisons) {
            String subject = "compare " + compare.id;
            var a = stubs.get(compare.source);
            var b = stubs.get(compare.target);
            if (a == null || b == null) { add(lines, Status.SKIP, subject, "not checked: query " + (a == null ? compare.source : compare.target) + " could not be described"); continue; }
            if ("db".equals(compare.engine)) { add(lines, Status.OK, subject, "engine=db: the columns exist; types are checked when it runs"); continue; }
            try {
                var bindings = ComparisonRunner.bindings(compare, a, b);
                List<String> missing = RuleLoader.keys(compare).stream().filter(k -> bindings.stream().noneMatch(x -> x.name().equalsIgnoreCase(k))).toList();
                if (!missing.isEmpty()) add(lines, Status.FAIL, subject, "key column(s) " + missing + " are not in both queries. " + compare.source + ": " + columns(a) + "; " + compare.target + ": " + columns(b));
                else add(lines, Status.OK, subject, "key " + String.join(", ", RuleLoader.keys(compare)) + " and " + Math.max(0, bindings.size() - RuleLoader.keys(compare).size()) + " compared column(s) exist on both sides");
            } catch (ToolException e) {
                add(lines, Status.FAIL, subject, e.getMessage());
            }
        }
        for (Rules.Funnel funnel : rules.funnels) {
            List<String> keys = FunnelRunner.keys(funnel);
            for (Rules.Stage stage : funnel.stages) for (String queryId : new String[]{stage.source, stage.target}) {
                if (queryId == null) continue;
                var r = stubs.get(queryId);
                String subject = "funnel " + funnel.id + ", stage " + stage.id;
                if (r == null) { add(lines, Status.SKIP, subject, "not checked: query " + queryId + " could not be described"); continue; }
                List<String> missing = keys.stream().filter(k -> !has(r, k)).toList();
                add(lines, missing.isEmpty() ? Status.OK : Status.FAIL, subject, missing.isEmpty() ? "query " + queryId + " returns the key" : "query " + queryId + " has no key column(s) " + missing + "; its columns: " + columns(r));
            }
        }
        for (Rules.Presence presence : rules.presences) {
            List<String> keys = FunnelRunner.keysOf(presence.key);
            for (String queryId : PresenceRunner.sources(presence)) {
                var r = stubs.get(queryId);
                String subject = "presence " + presence.id;
                if (r == null) { add(lines, Status.SKIP, subject, "not checked: query " + queryId + " could not be described"); continue; }
                List<String> missing = keys.stream().filter(k -> !has(r, k)).toList();
                add(lines, missing.isEmpty() ? Status.OK : Status.FAIL, subject, missing.isEmpty() ? "query " + queryId + " returns the key" : "query " + queryId + " has no key column(s) " + missing + "; its columns: " + columns(r));
            }
        }
        for (Rules.Sql query : rules.queries) {
            Map<String, String> uses;
            List<Chaining.Ref> refs;
            try { uses = Chaining.uses(query); refs = Chaining.references(query); } catch (ToolException e) { continue; }
            for (Chaining.Ref ref : refs) {
                Chaining.Target target = Chaining.target(uses.get(ref.alias()));
                String producer = target.part() == null ? target.id()
                    : rules.comparisons.stream().filter(c -> c.id.equals(target.id())).findFirst().map(c -> target.part().equals("only-target") ? c.target : c.source).orElse(null);
                var r = producer == null ? null : stubs.get(producer);
                String subject = "query " + query.id + " :" + ref.name();
                if (r == null) { add(lines, Status.SKIP, subject, "not checked: " + (producer == null ? "its source" : "query " + producer) + " could not be described"); continue; }
                add(lines, has(r, ref.column()) ? Status.OK : Status.FAIL, subject, has(r, ref.column()) ? "column " + ref.column() + " exists in " + uses.get(ref.alias())
                    : "no column '" + ref.column() + "' in " + uses.get(ref.alias()) + "; its columns: " + columns(r));
            }
        }
        for (Rules.Expect e : rules.expectations) {
            if (e.ref == null || e.column == null) continue;
            var r = stubs.get(e.ref);
            if (r == null) continue;
            if (!has(r, e.column)) add(lines, Status.FAIL, "expect on " + e.ref, "no column '" + e.column + "'; its columns: " + columns(r));
        }
    }

    /** SecretRedactor is registered at startup; this keeps the call site short. */
    private static final class SecretRedactorHolder {
        static String clean(String text) { return com.taomish.comparedbtool.logging.SecretRedactor.clean(text); }
    }
}
