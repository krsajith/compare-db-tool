package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guarded UPDATE, DELETE and free SQL statements (--change). Same shape as --restore: preview by default (everything runs in a transaction
 * that is rolled back), --apply backs up the rows in scope, runs the statements, checks the expected counts and the preconditions, shows the
 * result and asks for a typed confirmation before it commits. Nothing is blocked; what is risky needs a longer, generated phrase (or the
 * phrase the rule names). Every committed step is written to the changelog table in the same transaction.
 */
public final class DataChanger {
    private static final Logger LOG = LoggerFactory.getLogger(DataChanger.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Set<String> ACTIONS = Set.of("update", "delete", "sql");
    private static final Set<String> EXPECT_KEYS = Set.of("targetBefore", "affected", "targetAfter");
    private static final Pattern LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern DDL = Pattern.compile("\\b(drop|alter|create|truncate|grant|revoke|copy|call|do|execute|merge|vacuum|lock)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_WORD = Pattern.compile("\\b(insert|update|delete)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_WORD = Pattern.compile("^\\s*([A-Za-z]+)");

    public static class Precondition {
        public String sql, expect, reason;
    }

    public static class Step {
        public String id, action, table, where, sql, confirm, note;
        public List<String> key;
        /** update: column to SQL expression */
        public Map<String, String> set;
        public List<Precondition> preconditions;
        /** Keys: targetBefore, affected, targetAfter; each N, &lt;=N, &gt;=N, &lt;N, &gt;N or A..B. */
        public Map<String, String> expect;
    }

    public static class Rule {
        public String name, db, backupSchema = "parity_work", ticket, description, confirm;
        public List<Step> steps;
        public List<DataRestorer.Verify> verify;
    }

    record PreResult(String sql, String expected, String actual, String reason, boolean ok) { }

    record StepResult(Step step, String statement, long before, long affected, long after, String backup, boolean undoable,
                      List<PreResult> pre, List<String> unmet) {
        boolean scoped() { return backup != null; }
    }

    private final DatasourceSettings settings;

    public DataChanger(DatasourceSettings settings) { this.settings = settings; }

    // ------------------------------------------------------------------ validation (offline)

    static Rule load(Path file) {
        try {
            Rule rule = JSON.readValue(DataRestorer.readText(file), Rule.class);
            validate(rule);
            return rule;
        } catch (ToolException e) { throw new ToolException("Change rule " + file.getFileName() + ": " + e.getMessage(), e); }
        catch (Exception e) { throw new ToolException("Cannot read change rule " + file + ": " + e.getMessage(), e); }
    }

    static void validate(Rule rule) {
        if (rule == null || rule.name == null || rule.name.isBlank()) throw new ToolException("Change rule needs a name");
        if (rule.db == null || rule.db.isBlank()) throw new ToolException("Change rule needs db");
        if (!DataRestorer.IDENTIFIER.matcher(rule.backupSchema).matches()) throw new ToolException("Invalid backupSchema " + rule.backupSchema);
        if (rule.steps == null || rule.steps.isEmpty()) throw new ToolException("Change rule needs at least one step");
        Set<String> ids = new LinkedHashSet<>();
        for (Step s : rule.steps) {
            if (s.id == null || !DataRestorer.IDENTIFIER.matcher(s.id.replace('-', '_')).matches() || !ids.add(s.id))
                throw new ToolException("Each step needs a unique id of letters, digits, _ or -");
            if (s.action == null || !ACTIONS.contains(s.action)) throw new ToolException("Step " + s.id + ": action must be one of " + new java.util.TreeSet<>(ACTIONS));
            boolean raw = s.action.equals("sql");
            if (s.table != null && !DataRestorer.QUALIFIED.matcher(s.table).matches()) throw new ToolException("Step " + s.id + ": table must be schema.table");
            if (s.key != null && (s.key.isEmpty() || s.key.stream().anyMatch(k -> !DataRestorer.IDENTIFIER.matcher(k).matches())))
                throw new ToolException("Step " + s.id + ": key must list column names");
            if (raw) {
                if (s.sql == null || s.sql.isBlank()) throw new ToolException("Step " + s.id + ": a sql step needs sql (the statement)");
                if (s.set != null) throw new ToolException("Step " + s.id + ": set belongs to update steps");
                if ((s.table == null) != (s.where == null || s.where.isBlank()))
                    throw new ToolException("Step " + s.id + ": table and where go together; they are the scope that is backed up (leave both out for no backup)");
                if (s.key != null && s.table == null) throw new ToolException("Step " + s.id + ": key needs table and where");
            } else {
                if (s.sql != null) throw new ToolException("Step " + s.id + ": sql belongs to sql steps");
                if (s.table == null) throw new ToolException("Step " + s.id + ": table must be schema.table");
                if (s.where == null || s.where.isBlank()) throw new ToolException("Step " + s.id + ": where is required (the rows the step may change)");
                if (s.action.equals("update")) {
                    if (s.set == null || s.set.isEmpty()) throw new ToolException("Step " + s.id + ": an update step needs set");
                    for (var e : s.set.entrySet()) {
                        if (!DataRestorer.IDENTIFIER.matcher(e.getKey()).matches()) throw new ToolException("Step " + s.id + ": set column " + e.getKey() + " is not a column name");
                        if (e.getValue() == null || e.getValue().isBlank()) throw new ToolException("Step " + s.id + ": set " + e.getKey() + " needs a SQL expression");
                    }
                } else if (s.set != null) throw new ToolException("Step " + s.id + ": a delete step has no set");
            }
            boolean scoped = s.table != null;
            if (s.expect != null) for (var e : s.expect.entrySet()) {
                if (!EXPECT_KEYS.contains(e.getKey()))
                    throw new ToolException("Step " + s.id + ": unknown expect key " + e.getKey() + "; use " + new java.util.TreeSet<>(EXPECT_KEYS));
                if (!scoped && !e.getKey().equals("affected")) throw new ToolException("Step " + s.id + ": expect " + e.getKey() + " needs table and where (the scope)");
                Expectations.Spec.parse(e.getKey(), e.getValue());
            }
            if (s.preconditions != null) for (Precondition p : s.preconditions) {
                if (p == null || p.sql == null || p.expect == null) throw new ToolException("Step " + s.id + ": each precondition needs sql and expect");
                SqlGuard.validate(p.sql.replace("{{where}}", s.where == null ? "1 = 1" : s.where));
                Expectations.Spec.parse("precondition", p.expect);
            }
        }
    }

    // ------------------------------------------------------------------ risk

    private static String bare(String text) { return LITERAL.matcher(text).replaceAll("''"); }

    /** Why a step needs the long typed phrase. Empty = a normal change that needs only APPLY. */
    static List<String> riskReasons(Step s) {
        List<String> out = new ArrayList<>();
        boolean raw = s.action.equals("sql");
        String sql = raw ? s.sql.strip().replaceFirst(";\\s*$", "") : null;
        Map<String, String> parts = new LinkedHashMap<>();
        if (s.where != null) parts.put("where", s.where);
        if (s.set != null) parts.put("set", String.join(" ", s.set.values()));
        if (sql != null) parts.put("sql", sql);
        parts.forEach((label, text) -> {
            String b = bare(text);
            if (b.contains(";")) out.add(label + " has a semicolon (a second statement could follow)");
            if (b.contains("--") || b.contains("/*")) out.add(label + " has a comment");
            if (DDL.matcher(b).find()) out.add(label + " contains DDL or another statement word (" + DDL.matcher(b).results().map(m -> m.group().toLowerCase(Locale.ROOT)).distinct().collect(Collectors.joining(", ")) + ")");
            else if (!label.equals("sql") && WRITE_WORD.matcher(b).find()) out.add(label + " contains a write word (" + WRITE_WORD.matcher(b).results().map(m -> m.group().toLowerCase(Locale.ROOT)).distinct().collect(Collectors.joining(", ")) + ")");
        });
        if (s.where != null) {
            String n = bare(s.where).toLowerCase(Locale.ROOT).replaceAll("[\\s()]", "");
            if (n.equals("1=1") || n.equals("true") || n.contains("or1=1") || n.contains("ortrue")) out.add("where is always true: the step touches every row");
        }
        if (raw) {
            var m = FIRST_WORD.matcher(sql);
            String first = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
            if (!Set.of("update", "delete", "insert").contains(first)) out.add("the statement is not a plain UPDATE, DELETE or INSERT (it starts with " + (first.isEmpty() ? "?" : first.toUpperCase(Locale.ROOT)) + ")");
            if (s.table == null) out.add("no table and where: nothing can be backed up, there will be NO BACKUP and no automatic undo");
        }
        return out;
    }

    // ------------------------------------------------------------------ run

    public int execute(Cli.Options options) throws Exception {
        Rule rule = load(options.change);
        DataRestorer.validateVerifyRules(settings, rule.verify, options.change);
        if (options.validate) {
            LOG.info("VALID: change rule {} with {} steps; no database connection opened", rule.name, rule.steps.size());
            Progress.get().line("compare-db-tool: change rule " + rule.name + " is valid - " + rule.steps.size() + " steps, "
                + (rule.verify == null ? 0 : rule.verify.size()) + " verify rule(s). No connection was opened.");
            return 0;
        }
        var definition = settings.getDatasources().get(rule.db);
        if (definition == null) throw new ToolException("Unknown datasource " + rule.db);
        boolean prod = QueryRunner.production(rule.db, definition);
        if (prod && !options.allowProd) throw new ToolException("Datasource " + rule.db + " requires --allow-prod");
        if (options.apply) settings.requireWritable(rule.db);

        // risk is known from the rule alone, so a refused shortcut fails before anything connects
        Map<String, List<String>> stepRisk = new LinkedHashMap<>();
        for (Step s : rule.steps) { var r = riskReasons(s); if (!r.isEmpty()) stepRisk.put(s.id, r); }
        List<String> required = requiredPhrases(rule);
        boolean risky = !stepRisk.isEmpty() || prod;
        if (options.apply && options.yes && (risky || !required.isEmpty()))
            throw new ToolException("--yes is only for a normal change; this one is " + (risky ? "risky" : "protected by a confirm text in the rule")
                + ". Type the phrase, or pass --confirm \"<the phrase>\".");

        String stamp = Changelog.stamp();
        Path dir = options.out.toAbsolutePath().normalize().resolve(DataRestorer.safe(rule.name) + "-" + stamp);
        Files.createDirectories(dir);
        Changelog.Run run = new Changelog.Run(rule.backupSchema, "CHANGE", rule.name, options.change, rule.ticket);
        run.runDir = dir.toString();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(definition.getUrl());
        config.setUsername(definition.getUsername());
        config.setPassword(definition.getPassword());
        if (definition.getDriverClassName() != null) config.setDriverClassName(definition.getDriverClassName());
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setAutoCommit(false);
        SessionSettings.configure(config, definition.getUrl());
        config.setConnectionTimeout(30_000); config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        List<StepResult> results = new ArrayList<>();
        List<String> riskLines = new ArrayList<>();
        String status = options.apply ? "FAILED" : "PREVIEW";
        Exception failure = null;
        List<DataRestorer.VerifyResult> verified = List.of();
        boolean committed = false;
        try (var pool = new HikariDataSource(config)) {
            if (options.apply) {
                try (Connection setup = pool.getConnection()) {
                    setup.setAutoCommit(true);
                    Changelog.ensure(setup, rule.backupSchema);
                    guardReapply(setup, run, options);
                }
            }
            try (Connection connection = pool.getConnection()) {
                try {
                    if (options.apply) Changelog.lock(connection, rule.backupSchema, Changelog.who());
                    for (Step step : rule.steps) results.add(run(connection, rule, step, stamp, options.apply));
                    List<String> unmet = new ArrayList<>();
                    for (StepResult r : results) for (String u : r.unmet()) unmet.add("step " + r.step().id + ": " + u);
                    for (var e : stepRisk.entrySet()) e.getValue().forEach(x -> riskLines.add("step " + e.getKey() + ": " + x));
                    if (prod) riskLines.add("datasource " + rule.db + " is production");
                    if (!options.apply) {
                        connection.rollback();
                        status = "PREVIEW";
                        LOG.info("PREVIEW complete for {}: nothing changed (rolled back). Add --apply to commit.", rule.name);
                        Progress.get().line("  PREVIEW: nothing was changed (rolled back). Add --apply to commit.");
                        if (!riskLines.isEmpty()) Progress.get().line("  RISKY (a typed phrase will be needed on --apply): " + String.join("; ", riskLines));
                    } else {
                        if (!unmet.isEmpty()) throw new ToolException("Counts or preconditions differ from the rule (" + String.join("; ", unmet) + "). Nothing was committed.");
                        String phrase = required.isEmpty() ? (risky ? generatedPhrase(rule, results) : "APPLY") : null;
                        if (!confirmed(options, rule, results, riskLines, required, phrase)) {
                            connection.rollback();
                            status = "CANCELLED";
                            Changelog.failure(connection, run, "ROLLED_BACK", "not confirmed by the user");
                            Progress.get().line("  CANCELLED: the confirmation was not given; nothing was committed.");
                        } else {
                            String undoFile = dir.resolve("undo-" + DataRestorer.safe(rule.name) + ".json").toString();
                            String riskText = riskLines.isEmpty() ? "NORMAL" : "RISKY: " + String.join("; ", riskLines);
                            Changelog.record(connection, run, entries(results, riskText, undoFile), "APPLIED", null);
                            connection.commit();
                            committed = true;
                            status = "APPLIED";
                            LOG.info("COMMITTED change {}: {} steps", rule.name, results.size());
                            Progress.get().line("  APPLIED: " + results.size() + (results.size() == 1 ? " step" : " steps") + " committed and written to the changelog");
                        }
                    }
                } catch (Exception e) {
                    failure = e;
                    LOG.error("Change {} failed; all steps rolled back: {}", rule.name, e.getMessage());
                    if (options.apply) Changelog.failure(connection, run, "FAILED", e.getMessage());
                    else connection.rollback();
                }
            }
        }
        if (committed) verified = DataRestorer.verifyRules(settings, rule.name, rule.verify, options.change, options);
        writeSummary(dir, rule, results, status, failure, riskLines, verified);
        if (committed) writeUndo(dir, rule, results, run);
        Progress.get().line("  summary: " + dir.resolve("CHANGE.md"));
        try {
            var zipped = Cli.zipOutput(dir, DataRestorer.safe(rule.name));
            Progress.get().line("  zip: " + zipped + " (" + Progress.size(Files.size(zipped)) + ")");
        } catch (java.io.IOException e) {
            LOG.warn("ZIP of the change result failed; the files in {} remain: {}", dir, e.getMessage());
        }
        if (failure != null) throw failure;
        if (status.equals("CANCELLED")) return 1;
        if (verified.stream().anyMatch(v -> v.error() != null)) return 2;
        boolean unmet = results.stream().anyMatch(r -> !r.unmet().isEmpty());
        return unmet || verified.stream().anyMatch(v -> !v.passed()) ? 1 : 0;
    }

    private void guardReapply(Connection c, Changelog.Run run, Cli.Options options) throws Exception {
        List<String[]> earlier = Changelog.earlierApplied(c, run);
        for (String[] e : earlier) {
            if (e[2].equals("same") && !options.rerun)
                throw new ToolException("This rule (same content) was already applied here on " + e[1] + " (run " + e[0].substring(0, 8)
                    + "). Use --rerun to apply it again, or run its undo rule first.");
        }
        if (!earlier.isEmpty() && earlier.stream().noneMatch(e -> e[2].equals("same")))
            Progress.get().line("  note: a rule named " + run.ruleName + " was applied here before with different content (" + earlier.get(0)[1] + "); this is a new version.");
    }

    // ------------------------------------------------------------------ steps

    private StepResult run(Connection c, Rule rule, Step s, String stamp, boolean apply) throws Exception {
        long started = System.nanoTime();
        boolean raw = s.action.equals("sql");
        boolean scoped = s.table != null;
        String target = scoped ? "(SELECT * FROM " + s.table + " WHERE " + s.where + ")" : null;
        long before = 0, after = 0;
        if (scoped) {
            List<String> columns = DataRestorer.columns(c, s.table);
            if (s.key != null) for (String k : s.key) if (!columns.contains(k.toLowerCase(Locale.ROOT))) throw new ToolException("Step " + s.id + ": key column " + k + " is not in " + s.table);
            if (s.set != null) for (String col : s.set.keySet()) if (!columns.contains(col.toLowerCase(Locale.ROOT))) throw new ToolException("Step " + s.id + ": set column " + col + " is not in " + s.table);
            before = DataRestorer.count(c, "SELECT count(*) FROM " + target + " t");
        }
        List<PreResult> pre = new ArrayList<>();
        List<String> unmet = new ArrayList<>();
        if (s.preconditions != null) for (Precondition p : s.preconditions) {
            String sql = SqlGuard.validate(p.sql.replace("{{where}}", s.where == null ? "1 = 1" : s.where));
            long actual;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next()) throw new ToolException("Step " + s.id + ": a precondition returned no row: " + p.sql);
                actual = rs.getLong(1);
            }
            boolean ok = Expectations.Spec.parse("precondition", p.expect).test(actual);
            pre.add(new PreResult(p.sql, p.expect, String.valueOf(actual), p.reason, ok));
            if (!ok) unmet.add("precondition failed: " + (p.reason == null ? p.sql : p.reason) + " (expected " + p.expect + ", got " + actual + ")");
        }
        if (apply && !unmet.isEmpty()) throw new ToolException("Step " + s.id + ": " + String.join("; ", unmet) + ". Nothing was committed.");
        if (apply) {
            boolean hasExpect = s.expect != null && !s.expect.isEmpty() && (!raw || s.expect.containsKey("affected"));
            if (!hasExpect) throw new ToolException("Step " + s.id + ": --apply needs expect" + (raw ? " with at least affected" : "")
                + " (run the preview and copy its counts into the rule).");
        }
        String backup = null;
        if (scoped) {
            backup = backupName(rule, s, stamp);
            try (Statement st = c.createStatement()) { st.execute("CREATE TABLE " + backup + " AS SELECT * FROM " + s.table + " WHERE " + s.where); }
        }
        String statement = switch (s.action) {
            case "update" -> "UPDATE " + s.table + " SET " + s.set.entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).collect(Collectors.joining(", "))
                + " WHERE " + s.where;
            case "delete" -> "DELETE FROM " + s.table + " WHERE " + s.where;
            default -> s.sql.strip().replaceFirst(";\\s*$", "");
        };
        long affected;
        try (Statement st = c.createStatement()) { affected = st.executeUpdate(statement); }
        if (scoped) after = DataRestorer.count(c, "SELECT count(*) FROM " + target + " t");
        Progress.get().line(String.format(Locale.ROOT, "  step %s (%s): %saffected %s%s%s", s.id, s.action, scoped ? s.table + ", " + Progress.count(before) + " rows in scope, " : "no scope, ",
            Progress.count(affected), scoped ? ", " + Progress.count(after) + " in scope after" : "", scoped ? "" : " (NO BACKUP)"));
        LOG.info("Step {}: {} affected {} (scope {} -> {}); backup {}; {} s", s.id, s.action, affected, before, after, backup, (System.nanoTime() - started) / 1_000_000_000);
        if (s.expect != null) {
            Map<String, Long> actual = new LinkedHashMap<>();
            actual.put("affected", affected);
            if (scoped) { actual.put("targetBefore", before); actual.put("targetAfter", after); }
            for (var e : new TreeMap<>(s.expect).entrySet())
                if (!Expectations.Spec.parse(e.getKey(), e.getValue()).test(actual.get(e.getKey())))
                    unmet.add(e.getKey() + " expected " + e.getValue() + " but is " + actual.get(e.getKey()));
        }
        if (apply && !unmet.isEmpty()) throw new ToolException("Step " + s.id + ": " + String.join("; ", unmet) + ". Nothing was committed.");
        if (!unmet.isEmpty()) LOG.warn("Step {}: differs from expect: {}", s.id, unmet);
        return new StepResult(s, statement, before, affected, after, backup, scoped && s.key != null, pre, unmet);
    }

    /** change_&lt;rule&gt;_&lt;step&gt;_&lt;stamp&gt;, cut so the whole name stays within PostgreSQL's 63 characters and the stamp survives. */
    static String backupName(Rule rule, Step s, String stamp) {
        String middle = (DataRestorer.safe(rule.name) + "_" + s.id).replaceAll("[^A-Za-z0-9_]", "_");
        int room = 63 - "change_".length() - 1 - stamp.length();
        // a cut name ends with a hash of the full name, so steps whose ids only differ at the end stay unique
        if (middle.length() > room) middle = middle.substring(0, room - 9) + "_" + Integer.toHexString(middle.hashCode() | 0x10000000);
        return rule.backupSchema + ".change_" + middle + "_" + stamp;
    }

    // ------------------------------------------------------------------ confirmation

    /** What must be typed: the texts the rule names (rule level, then step level), or nothing when the tool generates the phrase. */
    static List<String> requiredPhrases(Rule rule) {
        List<String> out = new ArrayList<>();
        if (rule.confirm != null && !rule.confirm.isBlank()) out.add(rule.confirm.strip());
        for (Step s : rule.steps) if (s.confirm != null && !s.confirm.isBlank()) out.add(s.confirm.strip());
        return out;
    }

    static String generatedPhrase(Rule rule, List<StepResult> results) {
        long total = results.stream().mapToLong(StepResult::affected).sum();
        String actions = results.stream().map(r -> r.step().action.toUpperCase(Locale.ROOT)).distinct().collect(Collectors.joining("+"));
        String table = results.stream().map(r -> r.step().table).filter(t -> t != null).findFirst().orElse("-");
        return "APPLY " + total + " " + actions + " " + rule.db + " " + table;
    }

    private boolean confirmed(Cli.Options options, Rule rule, List<StepResult> results, List<String> risk, List<String> required, String generated) {
        List<String> phrases = required.isEmpty() ? List.of(generated) : required;
        StringBuilder message = new StringBuilder();
        message.append("\n  Step table (before / affected / after):\n");
        for (StepResult r : results)
            message.append(String.format(Locale.ROOT, "    %-24s %-7s %-34s %s / %s / %s   backup %s%n", r.step().id, r.step().action, r.step().table == null ? "-" : r.step().table,
                r.scoped() ? r.before() : "-", r.affected(), r.scoped() ? r.after() : "-", r.backup() == null ? "NONE" : r.backup()));
        for (StepResult r : results) for (PreResult p : r.pre())
            message.append("    precondition ").append(r.step().id).append(": ").append(p.ok() ? "ok" : "FAILED").append(" - ").append(p.reason() == null ? p.sql() : p.reason())
                .append(" (expected ").append(p.expected()).append(", got ").append(p.actual()).append(")\n");
        if (!risk.isEmpty()) {
            message.append("\n  WARNING, this change is risky:\n");
            risk.forEach(x -> message.append("    - ").append(x).append('\n'));
            message.append("  Statements that will be committed:\n");
            results.forEach(r -> message.append("    ").append(r.statement()).append('\n'));
        }
        message.append("\n  Datasource: ").append(rule.db).append("\n");
        if (options.confirm != null) {
            String given = options.confirm.strip();
            boolean ok = given.equals(String.join("|", phrases));
            Progress.get().line(message + (ok ? "  --confirm matches the required phrase." : "  --confirm does not match the required phrase: " + String.join(" | ", phrases)));
            return ok;
        }
        if (options.yes) { Progress.get().line(message + "  --yes: normal change, every expected count matched."); return true; }
        java.util.function.BiPredicate<String, String> asker = options.confirmer != null ? options.confirmer : DataChanger::askOnConsole;
        for (String phrase : phrases) {
            if (!asker.test(message.toString(), phrase)) return false;
            message.setLength(0);
        }
        return true;
    }

    private static boolean askOnConsole(String message, String phrase) {
        System.out.println(message);
        System.out.print("  Type " + phrase + " to commit (anything else cancels): ");
        System.out.flush();
        try {
            String line = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.Charset.defaultCharset())).readLine();
            return line != null && line.strip().equals(phrase);
        } catch (java.io.IOException e) { return false; }
    }

    // ------------------------------------------------------------------ outputs

    private static List<Changelog.Entry> entries(List<StepResult> results, String risk, String undoFile) throws Exception {
        List<Changelog.Entry> out = new ArrayList<>();
        for (StepResult r : results) {
            List<Map<String, Object>> pre = new ArrayList<>();
            for (PreResult p : r.pre()) pre.add(Map.of("sql", p.sql(), "expected", p.expected(), "actual", p.actual()));
            out.add(new Changelog.Entry(r.step().id, r.step().action.toUpperCase(Locale.ROOT), r.step().table, r.step().where, r.statement(),
                r.scoped() ? r.before() : null, r.affected(), r.scoped() ? r.after() : null, pre.isEmpty() ? null : JSON.writeValueAsString(pre), risk,
                r.backup(), r.undoable() ? undoFile : null));
        }
        return out;
    }

    private static void writeSummary(Path dir, Rule rule, List<StepResult> results, String status, Exception failure, List<String> risk,
                                     List<DataRestorer.VerifyResult> verified) throws Exception {
        StringBuilder md = new StringBuilder("# Change " + rule.name + "\n\n");
        md.append(switch (status) {
            case "APPLIED" -> "Status: APPLIED (committed).\n\n";
            case "CANCELLED" -> "Status: CANCELLED (not confirmed; rolled back, nothing changed).\n\n";
            case "FAILED" -> "Status: FAILED (rolled back, nothing changed): " + Expectations.cell(String.valueOf(failure == null ? "" : failure.getMessage())) + "\n\n";
            default -> failure == null ? "Status: PREVIEW (rolled back, nothing changed). Run again with --apply to commit.\n\n"
                : "Status: PREVIEW FAILED (rolled back): " + Expectations.cell(String.valueOf(failure.getMessage())) + "\n\n";
        });
        md.append("Tool: ").append(BuildInfo.line()).append("\n\n");
        if (rule.ticket != null) md.append("Ticket: ").append(rule.ticket).append("\n\n");
        if (rule.description != null) md.append(rule.description).append("\n\n");
        if (!risk.isEmpty()) {
            md.append("## Risky: a typed phrase is required to apply\n\n");
            risk.forEach(x -> md.append("- ").append(x).append('\n'));
            md.append('\n');
        }
        md.append("| Step | Action | Table | Rows before | Affected | Rows after | Backup |\n|---|---|---|---|---|---|---|\n");
        boolean applied = status.equals("APPLIED");
        for (StepResult r : results)
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d | %s | %s |%n", r.step().id, r.step().action, r.step().table == null ? "-" : r.step().table,
                r.scoped() ? String.valueOf(r.before()) : "-", r.affected(), r.scoped() ? String.valueOf(r.after()) : "-",
                r.backup() == null ? "NONE" : applied ? r.backup() : "(rolled back)"));
        md.append("\n## Statements\n\n");
        for (StepResult r : results) md.append("- ").append(r.step().id).append(": `").append(r.statement().replace("`", "'")).append("`\n");
        boolean anyPre = results.stream().anyMatch(r -> !r.pre().isEmpty());
        if (anyPre) {
            md.append("\n## Preconditions\n\n");
            for (StepResult r : results) for (PreResult p : r.pre())
                md.append("- ").append(r.step().id).append(": ").append(p.ok() ? "PASS" : "FAIL").append(" - ").append(p.reason() == null ? p.sql() : p.reason())
                    .append(" (expected ").append(p.expected()).append(", got ").append(p.actual()).append(")\n");
        }
        boolean anyExpect = results.stream().anyMatch(r -> r.step().expect != null && !r.step().expect.isEmpty());
        if (anyExpect) {
            md.append("\n## Expected counts\n\n");
            for (StepResult r : results) if (r.step().expect != null && !r.step().expect.isEmpty())
                md.append("- ").append(r.step().id).append(": ").append(r.unmet().stream().noneMatch(u -> !u.startsWith("precondition")) ? "PASS" : "FAIL ("
                    + r.unmet().stream().filter(u -> !u.startsWith("precondition")).collect(Collectors.joining("; ")) + ")").append('\n');
        }
        long noUndo = results.stream().filter(r -> !r.undoable()).count();
        if (noUndo > 0) md.append("\nNo automatic undo for step(s): ").append(results.stream().filter(r -> !r.undoable()).map(r -> r.step().id).collect(Collectors.joining(", "))
            .concat(" (no scope or no key, or a statement that adds rows the backup cannot name).\n"));
        if (rule.verify != null && !rule.verify.isEmpty()) {
            md.append("\n## Verification after apply\n\n");
            if (!applied) md.append("Not run: it runs after --apply commits: ").append(rule.verify.stream().map(v -> v.rule).toList()).append("\n");
            for (var v : verified) {
                md.append("- `").append(v.rule()).append("`: ");
                if (v.error() != null) md.append("COULD NOT RUN (").append(Expectations.cell(v.error())).append(")\n");
                else md.append(v.passed() ? "PASS" : "FAIL").append(" - ").append(v.report().headline()).append(", exit ").append(v.exit()).append(", output `")
                    .append(v.directory().getFileName()).append("`\n");
            }
            if (applied && verified.stream().anyMatch(v -> !v.passed()))
                md.append("\nThe change is committed but did not verify. The undo rule `undo-").append(DataRestorer.safe(rule.name)).append(".json` in this folder puts the rows back.\n");
        }
        Files.writeString(dir.resolve("CHANGE.md"), md.toString(), StandardCharsets.UTF_8);
    }

    /** Undo = a restore rule: every row the backup holds goes back, found by key (the change may have moved it out of the original where). */
    private static void writeUndo(Path dir, Rule rule, List<StepResult> results, Changelog.Run run) throws Exception {
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("name", rule.name + "-undo");
        undo.put("db", rule.db);
        undo.put("backupSchema", rule.backupSchema);
        if (rule.ticket != null) undo.put("ticket", rule.ticket);
        undo.put("undoOfRunId", run.id.toString());
        undo.put("description", "Undo of change " + rule.name + ": puts back the rows as they were before the change (from its backup tables). Run with --restore.");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = results.size() - 1; i >= 0; i--) {
            StepResult r = results.get(i);
            if (!r.undoable()) continue;
            String keys = String.join(", ", r.step().key);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", r.step().id + "-undo"); step.put("table", r.step().table); step.put("from", r.backup());
            step.put("where", "(" + keys + ") IN (SELECT " + keys + " FROM " + r.backup() + ")");
            step.put("key", r.step().key); step.put("deleteExtra", false);
            steps.add(step);
        }
        if (steps.isEmpty()) return;
        undo.put("steps", steps);
        Files.writeString(dir.resolve("undo-" + DataRestorer.safe(rule.name) + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(undo), StandardCharsets.UTF_8);
    }
}
