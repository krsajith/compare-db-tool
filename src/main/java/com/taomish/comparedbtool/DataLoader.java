package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Copies rows into a table of one datasource from a SELECT on any datasource (for example legacy MySQL, or another PostgreSQL), in one
 * transaction. The write counterpart of the comparison path, built like DataRestorer: preview by default (everything runs, then the
 * transaction is rolled back, so the reported counts are real), --apply commits, production datasources also need --allow-prod. The source
 * SELECT goes through the same guard, parameter binding and read-only pool as every rule query, and its rows are kept as a CSV in the
 * output folder. Insert only: nothing existing is changed. Per step: a backup of the rows in scope, the inserts, then checks (scope grew by
 * exactly the rows inserted, no duplicate keys, expected counts). An undo rule (a restore rule) is written next to the summary.
 */
public final class DataLoader {
    private static final Logger LOG = LoggerFactory.getLogger(DataLoader.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Set<String> EXPECT_KEYS = Set.of("targetBefore", "source", "inserted", "targetAfter");
    private static final Pattern PARAMETER = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
    private static final int BATCH = 1000;

    public static class Source {
        public String db, sql;
        /** Seconds the source query may run. */
        public int timeout = 300;
    }

    public static class Param {
        /** string, integer, decimal, date, timestamp, boolean */
        public String type = "string";
        public String value;
    }

    public static class Step {
        public String id, table, where, note;
        public Source source;
        public List<String> key;
        /**
         * Columns the source does not supply: a text value, or $uuid (a new random UUID per row) or $now (the time of the load).
         * Converted to the column type like any other value.
         */
        public Map<String, String> constants;
        /** Expected counts, each N, &lt;=N, &gt;=N, &lt;N, &gt;N or A..B. Keys: targetBefore, source, inserted, targetAfter. --apply refuses to commit when one is unmet. */
        public Map<String, String> expect;
    }

    public static class Rule {
        public String name, db, backupSchema = "parity_work", description, ticket;
        public Map<String, Param> params;
        public List<Step> steps;
        public List<DataRestorer.Verify> verify;
    }

    record StepResult(String id, String table, String sourceDb, long sourceRows, long targetBefore, long inserted, long targetAfter,
                      String backup, List<String> unmet) { }

    private final DatasourceSettings settings;

    public DataLoader(DatasourceSettings settings) { this.settings = settings; }

    // ------------------------------------------------------------------ validation (offline)

    /** Reads and validates a load rule file without connecting (also used to check the examples of the guide). */
    static Rule load(Path file) {
        try {
            Rule rule = JSON.readValue(DataRestorer.readText(file), Rule.class);
            validate(rule);
            return rule;
        } catch (ToolException e) { throw new ToolException("Load rule " + file.getFileName() + ": " + e.getMessage(), e); }
        catch (Exception e) { throw new ToolException("Cannot read load rule " + file + ": " + e.getMessage(), e); }
    }

    static void validate(Rule rule) {
        if (rule == null || rule.name == null || rule.name.isBlank()) throw new ToolException("Load rule needs a name");
        if (rule.db == null || rule.db.isBlank()) throw new ToolException("Load rule needs db: the datasource to write to");
        if (!DataRestorer.IDENTIFIER.matcher(rule.backupSchema).matches()) throw new ToolException("Invalid backupSchema " + rule.backupSchema);
        if (rule.steps == null || rule.steps.isEmpty()) throw new ToolException("Load rule needs at least one step");
        Map<String, Object> typed = typedParameters(rule, Map.of());
        Set<String> ids = new HashSet<>();
        for (Step s : rule.steps) {
            if (s == null) throw new ToolException("Empty step");
            RuleLoader.id(s.id, ids);
            if (s.table == null || !DataRestorer.QUALIFIED.matcher(s.table).matches()) throw new ToolException("Step " + s.id + ": table must be schema.table");
            DataRestorer.guard(s.id, "where", s.where, true);
            if (s.key == null || s.key.isEmpty() || s.key.stream().anyMatch(k -> k == null || !DataRestorer.IDENTIFIER.matcher(k).matches()))
                throw new ToolException("Step " + s.id + ": key must list the column names that identify a row (needed for the duplicate check and the undo rule)");
            if (s.source == null || s.source.db == null || s.source.db.isBlank() || s.source.sql == null || s.source.sql.isBlank())
                throw new ToolException("Step " + s.id + ": source needs db and sql");
            SqlGuard.validate(s.source.sql);
            Matcher m = PARAMETER.matcher(s.source.sql);
            while (m.find()) if (!typed.containsKey(m.group(1)))
                throw new ToolException("Step " + s.id + ": the source SQL uses :" + m.group(1) + " but params does not declare it");
            if (s.constants != null) for (var e : s.constants.entrySet()) {
                if (e.getKey() == null || !DataRestorer.IDENTIFIER.matcher(e.getKey()).matches() || e.getValue() == null)
                    throw new ToolException("Step " + s.id + ": constants need a column name and a value");
            }
            if (s.expect != null) for (var e : s.expect.entrySet()) {
                if (!EXPECT_KEYS.contains(e.getKey()))
                    throw new ToolException("Step " + s.id + ": unknown expect key " + e.getKey() + "; use " + new TreeSet<>(EXPECT_KEYS));
                Expectations.Spec.parse(e.getKey(), e.getValue());
            }
        }
    }

    /** The typed parameter values (a --param override replaces a declared value). */
    static Map<String, Object> typedParameters(Rule rule, Map<String, String> overrides) {
        Rules holder = new Rules();
        if (rule.params != null) for (var e : rule.params.entrySet()) {
            Rules.Param p = new Rules.Param();
            p.name = e.getKey();
            p.type = e.getValue() == null || e.getValue().type == null ? "string" : e.getValue().type;
            p.value = e.getValue() == null ? null : e.getValue().value;
            holder.parameters.add(p);
        }
        return RuleLoader.parameters(holder, overrides);
    }

    // ------------------------------------------------------------------ run

    public int execute(Cli.Options options) throws Exception {
        Rule rule = JSON.readValue(DataRestorer.readText(options.load), Rule.class);
        validate(rule);
        DataRestorer.validateVerifyRules(settings, rule.verify, options.load);
        Map<String, Object> parameters = typedParameters(rule, options.parameters);
        requireDatasource(rule.db, options);
        for (Step s : rule.steps) requireDatasource(s.source.db, options);
        if (options.validate) {
            Progress.get().line("compare-db-tool: load rule " + rule.name + " is valid - " + rule.steps.size() + " steps, "
                + (rule.verify == null ? 0 : rule.verify.size()) + " verify rule(s). No connection was opened.");
            return 0;
        }
        String stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path dir = options.out.toAbsolutePath().normalize().resolve(DataRestorer.safe(rule.name) + "-" + stamp);
        Files.createDirectories(dir);

        // 1. read every source first (read-only, guarded); the target transaction stays short
        Map<String, QueryRunner.Result> sources = new LinkedHashMap<>();
        try (QueryRunner runner = new QueryRunner(settings, options.allowProd)) {
            for (Step s : rule.steps) {
                Rules.Sql q = new Rules.Sql();
                q.id = s.id; q.db = s.source.db; q.text = s.source.sql; q.timeout = s.source.timeout; q.export = "full";
                Map<String, Object> used = new LinkedHashMap<>();
                Matcher m = PARAMETER.matcher(s.source.sql);
                while (m.find()) used.put(m.group(1), parameters.get(m.group(1)));
                QueryRunner.Result result = runner.run(q, used, 0, dir, ExportMode.FULL);
                if (result.partial()) throw new ToolException("Step " + s.id + ": the source query was cut short; a partial source is never loaded");
                sources.put(s.id, result);
            }
        }

        // 2. write: one transaction for all steps
        var definition = settings.getDatasources().get(rule.db);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(definition.getUrl());
        config.setUsername(definition.getUsername());
        config.setPassword(definition.getPassword());
        if (definition.getDriverClassName() != null) config.setDriverClassName(definition.getDriverClassName());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setAutoCommit(false);
        SessionSettings.configure(config, definition.getUrl());
        config.setConnectionTimeout(30_000); config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        List<StepResult> results = new ArrayList<>();
        Changelog.Run log = null;
        if (options.apply) {
            settings.requireWritable(rule.db);
            log = new Changelog.Run(rule.backupSchema, "LOAD", rule.name, options.load, rule.ticket);
            log.runDir = dir.toString();
        }
        try (var pool = new HikariDataSource(config)) {
            if (options.apply) {
                try (Connection setup = pool.getConnection()) { setup.setAutoCommit(true); Changelog.ensure(setup, rule.backupSchema); }
            }
            try (Connection connection = pool.getConnection()) {
                try {
                    if (options.apply) Changelog.lock(connection, rule.backupSchema, Changelog.who());
                    for (Step step : rule.steps) results.add(run(connection, rule, step, sources.get(step.id), stamp));
                    List<String> unmet = results.stream().flatMap(r -> r.unmet().stream().map(u -> r.id() + ": " + u)).toList();
                    if (options.apply && !unmet.isEmpty())
                        throw new ToolException("Counts differ from the rule's expect (" + String.join("; ", unmet) + "). Nothing was committed.");
                    if (options.apply) {
                        String undoFile = dir.resolve("undo-" + DataRestorer.safe(rule.name) + ".json").toString();
                        List<Changelog.Entry> entries = new ArrayList<>();
                        for (int i = 0; i < results.size(); i++) {
                            StepResult r = results.get(i);
                            entries.add(new Changelog.Entry(r.id(), "INSERT", r.table(), rule.steps.get(i).where, null, r.targetBefore(), r.inserted(), r.targetAfter(),
                                null, null, r.backup(), undoFile));
                        }
                        Changelog.record(connection, log, entries, "APPLIED", null);
                        connection.commit();
                        LOG.info("COMMITTED load {}: {} steps", rule.name, results.size());
                        Progress.get().line("  APPLIED: " + results.size() + (results.size() == 1 ? " step" : " steps") + " committed");
                    } else {
                        connection.rollback();
                        LOG.info("PREVIEW complete for {}: nothing changed (rolled back). Add --apply to commit.", rule.name);
                        Progress.get().line("  PREVIEW: nothing was changed (rolled back). Add --apply to commit.");
                    }
                } catch (Exception e) {
                    connection.rollback();
                    LOG.error("Load {} failed; all steps rolled back: {}", rule.name, e.getMessage());
                    if (options.apply) Changelog.failure(connection, log, "FAILED", e.getMessage());
                    throw e;
                }
            }
        }
        List<DataRestorer.VerifyResult> verified = options.apply
            ? DataRestorer.verifyRules(settings, rule.name, rule.verify, options.load, options) : List.of();
        writeSummary(dir, rule, results, options.apply, verified);
        if (options.apply) writeUndo(dir, rule, results, log);
        LOG.info("Summary: {}", dir.resolve("LOAD.md"));
        Progress.get().line("  summary: " + dir.resolve("LOAD.md"));
        try {
            var zipped = Cli.zipOutput(dir, DataRestorer.safe(rule.name));
            Progress.get().line("  zip: " + zipped + " (" + Progress.size(Files.size(zipped)) + ")");
        } catch (java.io.IOException e) {
            LOG.warn("ZIP of the load result failed; the files in {} remain: {}", dir, e.getMessage());
            Progress.get().line("  zip failed (" + e.getMessage() + "); the files remain in " + dir);
        }
        if (verified.stream().anyMatch(v -> v.error() != null)) return 2;
        boolean unmet = results.stream().anyMatch(r -> !r.unmet().isEmpty());
        return unmet || verified.stream().anyMatch(v -> !v.passed()) ? 1 : 0;
    }

    private void requireDatasource(String name, Cli.Options options) {
        var definition = settings.getDatasources().get(name);
        if (definition == null) throw new ToolException(DatasourceSettings.unknownDatasourceMessage(name));
        if (QueryRunner.production(name, definition) && !options.allowProd) throw new ToolException("Datasource " + name + " requires --allow-prod");
    }

    private StepResult run(Connection c, Rule rule, Step s, QueryRunner.Result source, String stamp) throws Exception {
        long started = System.nanoTime();
        LOG.info("Step {} started: {} rows from {} into {}", s.id, source.rows(), s.source.db, s.table);
        List<String> targetColumns = DataRestorer.columns(c, s.table);
        List<String> header = source.columns().stream().map(col -> col.name().toLowerCase(Locale.ROOT)).toList();
        for (String h : header) {
            if (!DataRestorer.IDENTIFIER.matcher(h).matches()) throw new ToolException("Step " + s.id + ": source column " + h + " is not a plain name; alias it in the SQL");
            if (!targetColumns.contains(h)) throw new ToolException("Step " + s.id + ": source column " + h + " is not a column of " + s.table);
        }
        Map<String, String> constants = new LinkedHashMap<>();
        if (s.constants != null) s.constants.forEach((k, v) -> constants.put(k.toLowerCase(Locale.ROOT), v));
        for (String k : constants.keySet()) {
            if (!targetColumns.contains(k)) throw new ToolException("Step " + s.id + ": constant column " + k + " is not a column of " + s.table);
            if (header.contains(k)) throw new ToolException("Step " + s.id + ": column " + k + " is both in the source and in constants");
        }
        List<String> keys = s.key.stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
        for (String k : keys) if (!header.contains(k) && !constants.containsKey(k))
            throw new ToolException("Step " + s.id + ": key column " + k + " is neither supplied by the source nor by constants");

        List<String> columns = new ArrayList<>(header);
        columns.addAll(constants.keySet());
        String scope = "(" + s.where + ")";
        long before = DataRestorer.count(c, "SELECT count(*) FROM " + s.table + " WHERE " + scope);

        String backup = rule.backupSchema + ".load_" + DataRestorer.safe(rule.name).replace('-', '_') + "_" + s.id.replace('-', '_').replace('.', '_') + "_" + stamp;
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + backup + " AS SELECT * FROM " + s.table + " WHERE " + scope);
        }

        int[] types = columnTypes(c, s.table, columns);
        Map<String, Supplier<Object>> constantValues = new LinkedHashMap<>();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (int i = header.size(); i < columns.size(); i++) {
            String column = columns.get(i); String spec = constants.get(column); int type = types[i];
            constantValues.put(column, switch (spec) {
                case "$uuid" -> () -> type == Types.OTHER ? UUID.randomUUID() : UUID.randomUUID().toString();
                case "$now" -> () -> type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE || type == Types.OTHER ? now : now.toString();
                default -> { Object fixed = convert(spec, type, column); yield () -> fixed; }
            });
        }

        String sql = "INSERT INTO " + s.table + " (" + String.join(", ", columns) + ") VALUES ("
            + columns.stream().map(x -> "?").collect(Collectors.joining(", ")) + ")";
        long inserted = 0;
        try (CSVParser parser = CsvFiles.reader(source.csv(), true); PreparedStatement ps = c.prepareStatement(sql)) {
            Map<String, String> sourceName = new LinkedHashMap<>();
            for (String name : parser.getHeaderMap().keySet()) sourceName.put(name.toLowerCase(Locale.ROOT), name);
            int pending = 0;
            for (CSVRecord record : parser) {
                for (int i = 0; i < columns.size(); i++) {
                    String column = columns.get(i);
                    Object value = i < header.size() ? convert(record.get(sourceName.get(column)), types[i], column) : constantValues.get(column).get();
                    if (value == null && keys.contains(column))
                        throw new ToolException("Step " + s.id + ": row " + (inserted + 1) + " has an empty key column " + column);
                    if (value == null) ps.setNull(i + 1, types[i]);
                    else if (types[i] == Types.OTHER) ps.setObject(i + 1, value, Types.OTHER);
                    else ps.setObject(i + 1, value);
                }
                ps.addBatch(); pending++; inserted++;
                if (pending == BATCH) { ps.executeBatch(); pending = 0; }
            }
            if (pending > 0) ps.executeBatch();
        }

        long after = DataRestorer.count(c, "SELECT count(*) FROM " + s.table + " WHERE " + scope);
        if (after != before + inserted)
            throw new ToolException("Step " + s.id + ": the scope (where) holds " + after + " rows after the load, expected " + (before + inserted)
                + ". The inserted rows must fall inside the scope so that the undo rule can remove exactly them. Nothing was committed.");
        String keyList = String.join(", ", keys);
        long duplicates = DataRestorer.count(c, "SELECT count(*) FROM (SELECT 1 FROM " + s.table + " WHERE " + scope + " GROUP BY " + keyList
            + " HAVING count(*) > 1) d");
        if (duplicates != 0)
            throw new ToolException("Step " + s.id + ": " + duplicates + " key value(s) (" + keyList + ") would exist more than once in the scope after the load"
                + " (a duplicate in the source, or the rows are already there). Nothing was committed.");
        Progress.get().line(String.format(Locale.ROOT, "  step %s: %s rows read from %s, %s inserted into %s (scope had %s rows, now %s)",
            s.id, Progress.count(source.rows()), s.source.db, Progress.count(inserted), s.table, Progress.count(before), Progress.count(after)));
        LOG.info("Step {}: source {} rows, inserted {}, scope {} -> {}; backup {}; {} s", s.id, source.rows(), inserted, before, after, backup,
            (System.nanoTime() - started) / 1_000_000_000);

        List<String> unmet = new ArrayList<>();
        if (s.expect != null) {
            Map<String, Long> actual = Map.of("targetBefore", before, "source", source.rows(), "inserted", inserted, "targetAfter", after);
            for (var e : new TreeMap<>(s.expect).entrySet())
                if (!Expectations.Spec.parse(e.getKey(), e.getValue()).test(actual.get(e.getKey())))
                    unmet.add(e.getKey() + " expected " + e.getValue() + " but is " + actual.get(e.getKey()));
        }
        if (!unmet.isEmpty()) LOG.warn("Step {}: counts differ from expect: {}", s.id, unmet);
        return new StepResult(s.id, s.table, s.source.db, source.rows(), before, inserted, after, backup, unmet);
    }

    /** java.sql.Types of the given columns of the target table, in the given order. */
    private static int[] columnTypes(Connection c, String table, List<String> columns) throws Exception {
        try (Statement st = c.createStatement(); var rs = st.executeQuery("SELECT " + String.join(", ", columns) + " FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            int[] types = new int[columns.size()];
            for (int i = 0; i < types.length; i++) types[i] = md.getColumnType(i + 1);
            return types;
        }
    }

    /** Text from the CSV (null = empty value) converted to the type of the target column. */
    static Object convert(String value, int sqlType, String column) {
        if (value == null) return null;
        try {
            return switch (sqlType) {
                case Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT -> Long.valueOf(value.trim());
                case Types.DOUBLE, Types.FLOAT, Types.REAL -> Double.valueOf(value.trim());
                case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(value.trim());
                case Types.BOOLEAN, Types.BIT -> bool(value.trim());
                case Types.DATE -> java.sql.Date.valueOf(LocalDate.parse(value.trim().substring(0, 10)));
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> timestamp(value.trim());
                case Types.OTHER -> isUuid(value) ? UUID.fromString(value.trim()) : value;
                default -> value;
            };
        } catch (RuntimeException e) {
            throw new ToolException("Value '" + value + "' does not fit column " + column + " (type " + sqlType + ")");
        }
    }

    private static boolean isUuid(String value) {
        return value.trim().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static Boolean bool(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "t", "1", "yes" -> Boolean.TRUE;
            case "false", "f", "0", "no" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(v);
        };
    }

    private static Timestamp timestamp(String v) {
        String iso = v.replace(' ', 'T');
        try { return Timestamp.valueOf(LocalDateTime.parse(iso)); }
        catch (DateTimeParseException e) { return Timestamp.from(OffsetDateTime.parse(iso).toInstant()); }
    }

    // ------------------------------------------------------------------ output

    private static void writeSummary(Path dir, Rule rule, List<StepResult> results, boolean applied, List<DataRestorer.VerifyResult> verified) throws Exception {
        StringBuilder md = new StringBuilder("# Load " + rule.name + "\n\n")
            .append(applied ? "Status: APPLIED (committed).\n\n" : "Status: PREVIEW (rolled back, nothing changed). Run again with --apply to commit.\n\n")
            .append("Tool: ").append(BuildInfo.line()).append("\n\n")
            .append(rule.description == null ? "" : rule.description + "\n\n")
            .append("Target datasource: ").append(rule.db).append("\n\n")
            .append("| Step | Table | Source | Rows read | Scope before | Inserted | Scope after | Backup |\n")
            .append("|---|---|---|---|---|---|---|---|\n");
        for (StepResult r : results)
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %d | %d | %d | %s |%n", r.id(), r.table(), r.sourceDb(), r.sourceRows(),
                r.targetBefore(), r.inserted(), r.targetAfter(), applied ? r.backup() : "(preview: rolled back)"));
        boolean anyExpect = rule.steps.stream().anyMatch(s -> s.expect != null && !s.expect.isEmpty());
        if (anyExpect) {
            md.append("\n## Expected counts\n\n");
            for (StepResult r : results) {
                Step s = rule.steps.stream().filter(x -> x.id.equals(r.id())).findFirst().orElseThrow();
                if (s.expect == null || s.expect.isEmpty()) continue;
                md.append("- ").append(r.id()).append(": ").append(r.unmet().isEmpty() ? "PASS" : "FAIL (" + String.join("; ", r.unmet()) + ")").append('\n');
            }
        }
        md.append("\nThe rows read from each source are kept next to this file as `<step id>.csv` (the exact data that was loaded).\n");
        if (rule.verify != null && !rule.verify.isEmpty()) {
            md.append("\n## Verification after apply\n\n");
            if (!applied) md.append("Not run in preview: the changes are rolled back, so there is nothing to verify. It runs after --apply: ")
                .append(rule.verify.stream().map(v -> v.rule).toList()).append("\n");
            for (DataRestorer.VerifyResult v : verified) {
                md.append("- `").append(v.rule()).append("`: ");
                if (v.error() != null) md.append("COULD NOT RUN (").append(Expectations.cell(v.error())).append(")\n");
                else md.append(v.passed() ? "PASS" : "FAIL").append(" - ").append(v.report().headline()).append(", exit ").append(v.exit())
                    .append(", output `").append(v.directory().getFileName()).append("`\n");
            }
            if (applied && verified.stream().anyMatch(v -> !v.passed()))
                md.append("\nThe load is committed but did not verify. Review the verify output; the undo rule `undo-")
                    .append(DataRestorer.safe(rule.name)).append(".json` in this folder removes the inserted rows (run it with --restore).\n");
        }
        Files.writeString(dir.resolve("LOAD.md"), md.toString(), StandardCharsets.UTF_8);
    }

    /** Undo = a restore rule: each scope goes back to its backup table, which removes exactly the rows this load inserted. */
    private static void writeUndo(Path dir, Rule rule, List<StepResult> results, Changelog.Run log) throws Exception {
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("name", rule.name + "-undo");
        undo.put("db", rule.db);
        undo.put("backupSchema", rule.backupSchema);
        if (rule.ticket != null) undo.put("ticket", rule.ticket);
        undo.put("undoOfRunId", log.id.toString());
        undo.put("description", "Undo of load " + rule.name + ": puts each scope back as it was before the load (from its backup table). Run with --restore.");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = results.size() - 1; i >= 0; i--) {
            StepResult r = results.get(i);
            Step s = rule.steps.get(i);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", s.id + "-undo"); step.put("table", s.table); step.put("from", r.backup());
            step.put("where", s.where); step.put("key", s.key); step.put("deleteExtra", true);
            steps.add(step);
        }
        undo.put("steps", steps);
        Files.writeString(dir.resolve("undo-" + DataRestorer.safe(rule.name) + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(undo), StandardCharsets.UTF_8);
    }
}
