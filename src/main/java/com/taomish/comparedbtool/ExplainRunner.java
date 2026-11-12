package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code <explain>}: the PostgreSQL plan of one SELECT or WITH statement, with what matters read out of it: the time, the slowest
 * nodes, sequential scans, and where the planner's row estimate was far from the real count. analyze=true (default) runs the statement
 * with EXPLAIN (ANALYZE, BUFFERS) inside the read-only, always rolled back pool; analyze=false only plans it and executes nothing.
 */
final class ExplainRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ExplainRunner.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Nodes that expect and return fewer rows than this are ignored when the estimate error is measured (a 1 against a 3 is noise). */
    static final double ERROR_FLOOR_ROWS = 100;

    /** One plan node. Times are in ms; totalMs and exclusiveMs already include the loops. actualRows is per loop; NaN when nothing was executed. */
    record Node(int depth, String type, String relation, String index, double estRows, double actualRows, double loops,
                double totalMs, double exclusiveMs, long removedByFilter) {
        boolean seqScan() { return type.endsWith("Seq Scan"); }
        double error() {
            if (Double.isNaN(actualRows) || Math.max(estRows, actualRows) < ERROR_FLOOR_ROWS) return 1;
            double e = Math.max(estRows, 1), a = Math.max(actualRows, 1);
            return Math.max(e / a, a / e);
        }
        String label() { return type + (relation == null ? "" : " on " + relation) + (index == null ? "" : " using " + index); }
    }

    /** One line a function printed, such as "cash_balance T CFS S4 eligible trades : 120000 rows, 41000 ms". */
    record Step(String name, long rows, long ms) {}

    record Result(String id, boolean analyze, double planningMs, double executionMs, double totalCost, List<Node> nodes, long sharedHit, long sharedRead,
                  List<Path> files, String headline, List<String> notices) {
        List<Step> steps() { return ExplainRunner.steps(notices); }
        List<Node> seqScans() { return nodes.stream().filter(Node::seqScan).toList(); }
        List<Node> slowest() {
            if (!analyze) return List.of();
            return nodes.stream().sorted(Comparator.comparingDouble(Node::exclusiveMs).reversed()).limit(5).toList();
        }
        List<Node> misestimated() {
            if (!analyze) return List.of();
            return nodes.stream().filter(n -> n.error() >= 10).sorted(Comparator.comparingDouble(Node::error).reversed()).limit(5).toList();
        }
        double worstError() { return analyze ? nodes.stream().mapToDouble(Node::error).max().orElse(1) : Double.NaN; }
    }

    // ---------------------------------------------------------------- rule

    static Rules.Sql asSql(Rules.Explain explain) {
        Rules.Sql sql = new Rules.Sql();
        sql.id = explain.id; sql.db = explain.db; sql.timeout = explain.timeout; sql.text = explain.text; sql.uses = explain.uses; sql.tempTables = explain.tempTables;
        return sql;
    }

    static void validate(Rules.Explain explain, DatasourceSettings settings, boolean checkQueries, Set<String> ids, Set<String> queryIds) {
        RuleLoader.id(explain.id, ids);
        if (explain.db == null || explain.db.isBlank()) throw new ToolException("<explain> " + explain.id + " needs a datasource name (db=\"...\").");
        if (explain.timeout < 1 || explain.timeout > 86400) throw new ToolException("<explain> " + explain.id + ": timeout must be between 1 and 86400 seconds (24 hours).");
        if (explain.text == null || explain.text.isBlank()) throw new ToolException("<explain> " + explain.id + " needs the SELECT or WITH statement as its text.");
        SqlGuard.validate(explain.text);
        // inputs from earlier queries: aliases declared, queries exist; the values arrive when it runs (after all queries)
        Chaining.needs(asSql(explain), queryIds, Set.of());
        if (checkQueries) {
            var definition = settings.getDatasources().get(explain.db);
            if (definition == null) throw new ToolException("Unknown datasource for <explain> " + explain.id + ": " + explain.db);
            if (!explain.tempTables && !definition.isReadOnly()) throw new ToolException("Datasource " + explain.db + " must have read-only=true.");
            TempTablePolicy.validate(asSql(explain), settings);
        }
    }

    // ---------------------------------------------------------------- run

    Result run(Rules.Explain explain, Map<String, Object> parameters, QueryRunner runner, Path directory) throws IOException {
        long start = System.nanoTime();
        QueryRunner.Plan plan = runner.explain(explain, parameters);
        String json = plan.json();
        List<String> notices = plan.notices();
        Result parsed = parse(explain.id, explain.analyze, json);
        Path folder = directory.resolve(explain.id);
        Files.createDirectories(folder);
        Path text = folder.resolve("plan.txt"), raw = folder.resolve("plan.json"), summary = folder.resolve("SUMMARY.md"), notes = folder.resolve("notices.txt");
        Files.writeString(text, render(parsed));
        Files.writeString(raw, json + System.lineSeparator());
        List<Path> files = new ArrayList<>(List.of(summary, text, raw));
        if (!notices.isEmpty()) { Files.writeString(notes, String.join(System.lineSeparator(), notices) + System.lineSeparator()); files.add(notes); }
        List<Step> steps = steps(notices);
        String headline = parsed.headline + (steps.isEmpty() ? (notices.isEmpty() ? "" : "; the statement printed " + notices.size() + " messages")
            : "; " + steps.size() + " function steps, slowest: " + steps.stream().max(Comparator.comparingLong(Step::ms)).map(s -> s.name() + " " + s.ms() + " ms").orElse(""));
        Result r = new Result(parsed.id, parsed.analyze, parsed.planningMs, parsed.executionMs, parsed.totalCost, parsed.nodes, parsed.sharedHit, parsed.sharedRead,
            files, headline, List.copyOf(notices));
        Files.writeString(summary, markdown(explain, r, (System.nanoTime() - start) / 1_000_000));
        LOG.info("Explain {}: {}", explain.id, headline);
        return r;
    }

    // ---------------------------------------------------------------- parsing

    @SuppressWarnings("unchecked")
    static Result parse(String id, boolean analyze, String json) {
        Object root;
        try { root = JSON.readValue(json, Object.class); }
        catch (Exception e) { throw new ToolException("<explain> " + id + ": the database returned a plan that is not valid JSON.", e); }
        if (!(root instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> top) || !(top.get("Plan") instanceof Map<?, ?> plan))
            throw new ToolException("<explain> " + id + ": unexpected plan format from the database.");
        List<Node> nodes = new ArrayList<>();
        collect((Map<String, Object>) plan, 0, analyze, nodes);
        double planning = number(top.get("Planning Time"), 0), execution = number(top.get("Execution Time"), 0);
        double cost = number(plan.get("Total Cost"), 0);
        long hit = (long) number(plan.get("Shared Hit Blocks"), 0), read = (long) number(plan.get("Shared Read Blocks"), 0);
        Result r = new Result(id, analyze, planning, execution, cost, List.copyOf(nodes), hit, read, List.of(), "", List.of());
        return new Result(id, analyze, planning, execution, cost, List.copyOf(nodes), hit, read, List.of(), headline(r), List.of());
    }

    /** Fills {@code out} with the node and its children, parents first. Returns the node's total time in ms (all loops). */
    @SuppressWarnings("unchecked")
    private static double collect(Map<String, Object> plan, int depth, boolean analyze, List<Node> out) {
        String type = Objects.toString(plan.get("Node Type"), "?");
        double loops = analyze ? number(plan.get("Actual Loops"), 1) : 1;
        double total = analyze ? number(plan.get("Actual Total Time"), 0) * loops : 0;
        int slot = out.size();
        out.add(null);                                                       // keeps the parent before its children
        double children = 0;
        if (plan.get("Plans") instanceof List<?> kids)
            for (Object kid : kids) if (kid instanceof Map<?, ?> m) children += collect((Map<String, Object>) m, depth + 1, analyze, out);
        double exclusive = analyze ? Math.max(0, total - children) : 0;
        out.set(slot, new Node(depth, type, text(plan.get("Relation Name")), text(plan.get("Index Name")), number(plan.get("Plan Rows"), 0),
            analyze ? number(plan.get("Actual Rows"), 0) : Double.NaN, loops, total, exclusive, (long) number(plan.get("Rows Removed by Filter"), 0)));
        return total;
    }

    private static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    private static String text(Object value) { return value == null ? null : value.toString(); }

    private static final java.util.regex.Pattern STEP_NUMBERED = java.util.regex.Pattern.compile("^(?:\\S+\\s+){0,3}?(S\\d+[^:]*?)\\s*:\\s*(\\d+)\\s+rows,\\s*(\\d+)\\s+ms\\s*$");
    private static final java.util.regex.Pattern STEP_PLAIN = java.util.regex.Pattern.compile("^([^:]+?)\\s*:\\s*(\\d+)\\s+rows,\\s*(\\d+)\\s+ms\\s*$");

    /** The lines of the form "name : N rows, M ms" that a function printed; the leading words of a line such as "cash_balance T CFS S4 ..." are dropped when the name starts with S and a number. */
    static List<Step> steps(List<String> notices) {
        List<Step> out = new ArrayList<>();
        for (String line : notices) {
            String text = line.strip();
            var m = STEP_NUMBERED.matcher(text);
            if (!m.matches()) m = STEP_PLAIN.matcher(text);
            if (m.matches()) out.add(new Step(m.group(1).strip(), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))));
        }
        return out;
    }

    // ---------------------------------------------------------------- reporting

    static String headline(Result r) {
        List<Node> seq = r.seqScans();
        String scans = seq.isEmpty() ? "no sequential scan"
            : seq.size() + (seq.size() == 1 ? " sequential scan" : " sequential scans") + " (" + String.join(", ", seq.stream().map(n -> n.relation() == null ? "?" : n.relation()).distinct().toList()) + ")";
        if (!r.analyze()) return "planned only, nothing was executed: " + r.nodes().size() + " nodes, estimated cost " + fmt(r.totalCost()) + ", " + scans;
        String slow = r.slowest().isEmpty() ? "" : "; slowest: " + r.slowest().getFirst().label() + " " + fmt(r.slowest().getFirst().exclusiveMs()) + " ms";
        double worst = r.worstError();
        return "executed in " + fmt(r.executionMs()) + " ms (planning " + fmt(r.planningMs()) + " ms)" + slow + "; " + scans
            + (worst >= 10 ? "; worst row estimate off by " + fmt(worst) + "x" : "");
    }

    static String fmt(double value) {
        if (Double.isNaN(value)) return "-";
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return String.format(Locale.ROOT, "%.0f", value);
        return value >= 100 ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.2f", value);
    }

    /** The plan as an indented tree: type, relation, index, estimated against actual rows, loops, time of the node itself. */
    static String render(Result r) {
        StringBuilder out = new StringBuilder();
        for (Node n : r.nodes()) {
            out.append("  ".repeat(n.depth())).append(n.depth() == 0 ? "" : "-> ").append(n.label()).append("  (rows est ").append(fmt(n.estRows()));
            if (r.analyze()) {
                out.append(", actual ").append(fmt(n.actualRows())).append(", loops ").append(fmt(n.loops())).append(", own time ").append(fmt(n.exclusiveMs()))
                    .append(" ms");
                if (n.removedByFilter() > 0) out.append(", removed by filter ").append(n.removedByFilter());
            }
            out.append(")\n");
        }
        return out.toString();
    }

    private static String cell(String text) { return text == null ? "" : text.replace("|", "\\|"); }

    static String markdown(Rules.Explain explain, Result r, long wallMs) {
        StringBuilder md = new StringBuilder("# Explain " + explain.id + "\n\nDatasource: `" + explain.db + "`\n\n"
            + (r.analyze() ? "Mode: EXPLAIN ANALYZE" + (explain.buffers ? ", BUFFERS" : "") + (explain.tempTables ? " in temp-table mode (non-production only); the transaction was rolled back."
                : " in a read-only transaction that was rolled back.") + " The statement was executed.\n\n"
                : "Mode: plan only. The statement was not executed, so there are no actual rows or times.\n\n")
            + "Result: " + r.headline() + "\n\n");
        if (r.analyze() && explain.buffers) md.append("Shared blocks of the whole plan: ").append(r.sharedHit()).append(" hit, ").append(r.sharedRead()).append(" read from disk or the OS cache.\n\n");
        if (!r.seqScans().isEmpty()) {
            md.append("## Sequential scans\n\n| Table | Rows expected | Rows actual (per loop) | Loops | Removed by filter |\n|---|---:|---:|---:|---:|\n");
            for (Node n : r.seqScans()) md.append("| ").append(cell(n.relation())).append(" | ").append(fmt(n.estRows())).append(" | ").append(fmt(n.actualRows()))
                .append(" | ").append(fmt(n.loops())).append(" | ").append(n.removedByFilter()).append(" |\n");
            md.append("\nA sequential scan that runs many loops reads the whole table each time: look for an index that the filter or join can use.\n\n");
        }
        if (!r.slowest().isEmpty()) {
            md.append("## Slowest nodes (time of the node itself, all loops)\n\n| Node | Own time ms | Rows expected | Rows actual (per loop) | Loops |\n|---|---:|---:|---:|---:|\n");
            for (Node n : r.slowest()) md.append("| ").append(cell(n.label())).append(" | ").append(fmt(n.exclusiveMs())).append(" | ").append(fmt(n.estRows())).append(" | ")
                .append(fmt(n.actualRows())).append(" | ").append(fmt(n.loops())).append(" |\n");
            md.append("\n");
        }
        if (!r.misestimated().isEmpty()) {
            md.append("## Row estimates far from the real count (planner statistics may be stale or missing)\n\n| Node | Expected | Actual (per loop) | Off by |\n|---|---:|---:|---:|\n");
            for (Node n : r.misestimated()) md.append("| ").append(cell(n.label())).append(" | ").append(fmt(n.estRows())).append(" | ").append(fmt(n.actualRows()))
                .append(" | ").append(fmt(n.error())).append("x |\n");
            md.append("\nNodes that expect and return fewer than ").append((int) ERROR_FLOOR_ROWS).append(" rows are not counted.\n\n");
        }
        if (!r.notices().isEmpty()) {
            List<Step> steps = r.steps();
            long total = steps.stream().mapToLong(Step::ms).sum();
            if (!steps.isEmpty()) {
                md.append("## Steps the function reported (RAISE NOTICE)\n\n| Step | Rows | Time ms | Share |\n|---|---:|---:|---:|\n");
                for (Step s : steps) md.append("| ").append(cell(s.name())).append(" | ").append(s.rows()).append(" | ").append(s.ms()).append(" | ")
                    .append(total == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * s.ms() / total)).append(" |\n");
                md.append("\nThe plan of a function call is one node; these steps are where its time goes. A step whose row count jumps while the next one shrinks is a join that multiplies rows.\n\n");
            }
            md.append("All messages the statement printed are in `notices.txt`.\n\n");
        }
        md.append("Files: `plan.txt` (the tree), `plan.json` (the raw plan). Tool wall-clock time for this step: ").append(wallMs).append(" ms.\n");
        return md.toString();
    }
}
