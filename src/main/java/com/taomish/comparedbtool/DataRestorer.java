package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Restores rows of target tables from reference tables (a backup schema, a migration copy, a snapshot) in one transaction.
 * Deliberately separate from the read-only comparison path, like TemplateUploader. Preview by default: every statement runs and
 * the transaction is rolled back, so the reported counts are the real ones. --apply commits; production datasources also need
 * --allow-prod. Per step: backup of the target rows in scope, rows whose key exists in the reference with other content are
 * replaced, missing rows are inserted, optionally rows the reference does not have are removed, then the scope must equal the
 * reference. An undo rule (restore from the backup tables) is written next to the summary.
 */
public final class DataRestorer {
    private static final Logger LOG = LoggerFactory.getLogger(DataRestorer.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern QUALIFIED = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern FORBIDDEN = Pattern.compile(
        "(;|--|/\\*|\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|call|do|execute|merge|vacuum|lock|set)\\b)",
        Pattern.CASE_INSENSITIVE);
    /** Not compared as content: identity and audit columns (still copied from the reference on insert). */
    private static final Set<String> AUDIT = Set.of("uuid", "created_by", "created_timestamp", "updated_by", "updated_timestamp");

    public static class Step {
        public String id, table, from, where, referenceWhere;
        public List<String> key;
        public boolean deleteExtra;
        public String note;
    }

    public static class Rule {
        public String name, db, backupSchema = "parity_work", description;
        public List<Step> steps;
    }

    record StepResult(String id, String table, String from, long targetBefore, long reference, long changed, long missing, long extra,
                      long removedExtra, long targetAfter, String backup) { }

    private final DatasourceSettings settings;

    public DataRestorer(DatasourceSettings settings) { this.settings = settings; }

    public int execute(Cli.Options options) throws Exception {
        Rule rule = JSON.readValue(Files.readString(options.restore), Rule.class);
        validate(rule);
        if (options.validate) {
            LOG.info("VALID: restore rule {} with {} steps; no database connection opened", rule.name, rule.steps.size());
            return 0;
        }
        var definition = settings.getDatasources().get(rule.db);
        if (definition == null) throw new ToolException("Unknown datasource " + rule.db);
        if (QueryRunner.production(rule.db, definition) && !options.allowProd)
            throw new ToolException("Datasource " + rule.db + " requires --allow-prod");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path dir = options.out.toAbsolutePath().normalize().resolve(safe(rule.name) + "-" + stamp);
        Files.createDirectories(dir);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(definition.getUrl());
        config.setUsername(definition.getUsername());
        config.setPassword(definition.getPassword());
        if (definition.getDriverClassName() != null) config.setDriverClassName(definition.getDriverClassName());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setAutoCommit(false);
        config.setConnectionTimeout(30_000); config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        List<StepResult> results = new ArrayList<>();
        try (var pool = new HikariDataSource(config); Connection connection = pool.getConnection()) {
            try {
                for (Step step : rule.steps) results.add(run(connection, rule, step, stamp));
                if (options.apply) {
                    connection.commit();
                    LOG.info("COMMITTED restore {}: {} steps", rule.name, results.size());
                } else {
                    connection.rollback();
                    LOG.info("PREVIEW complete for {}: nothing changed (rolled back). Add --apply to commit.", rule.name);
                }
            } catch (Exception e) {
                connection.rollback();
                LOG.error("Restore {} failed; all steps rolled back: {}", rule.name, e.getMessage());
                throw e;
            }
        }
        writeSummary(dir, rule, results, options.apply);
        if (options.apply) writeUndo(dir, rule, results);
        LOG.info("Summary: {}", dir.resolve("RESTORE.md"));
        return 0;
    }

    static void validate(Rule rule) {
        if (rule == null || rule.name == null || rule.name.isBlank()) throw new ToolException("Restore rule needs a name");
        if (rule.db == null || rule.db.isBlank()) throw new ToolException("Restore rule needs db");
        if (!IDENTIFIER.matcher(rule.backupSchema).matches()) throw new ToolException("Invalid backupSchema " + rule.backupSchema);
        if (rule.steps == null || rule.steps.isEmpty()) throw new ToolException("Restore rule needs at least one step");
        Set<String> ids = new java.util.HashSet<>();
        for (Step s : rule.steps) {
            if (s.id == null || !IDENTIFIER.matcher(s.id.replace('-', '_')).matches() || !ids.add(s.id))
                throw new ToolException("Each step needs a unique id of letters, digits, _ or -");
            if (s.table == null || !QUALIFIED.matcher(s.table).matches()) throw new ToolException("Step " + s.id + ": table must be schema.table");
            if (s.from == null || !QUALIFIED.matcher(s.from).matches()) throw new ToolException("Step " + s.id + ": from must be schema.table");
            if (s.table.equalsIgnoreCase(s.from)) throw new ToolException("Step " + s.id + ": from and table are the same");
            if (s.key == null || s.key.isEmpty() || s.key.stream().anyMatch(k -> !IDENTIFIER.matcher(k).matches()))
                throw new ToolException("Step " + s.id + ": key must list column names");
            guard(s.id, "where", s.where, true);
            guard(s.id, "referenceWhere", s.referenceWhere, false);
        }
    }

    static void guard(String id, String name, String predicate, boolean required) {
        if (predicate == null || predicate.isBlank()) {
            if (required) throw new ToolException("Step " + id + ": " + name + " is required (the scope of the restore)");
            return;
        }
        if (FORBIDDEN.matcher(predicate).find())
            throw new ToolException("Step " + id + ": " + name + " may only be a condition (no statements, comments or semicolons)");
    }

    private StepResult run(Connection c, Rule rule, Step s, String stamp) throws Exception {
        long started = System.nanoTime();
        LOG.info("Step {} started: {} from {}", s.id, s.table, s.from);
        List<String> targetColumns = columns(c, s.table);
        List<String> referenceColumns = columns(c, s.from);
        List<String> common = targetColumns.stream().filter(referenceColumns::contains).toList();
        for (String k : s.key) if (!common.contains(k.toLowerCase(Locale.ROOT))) throw new ToolException("Step " + s.id + ": key column " + k + " not in both tables");
        List<String> content = common.stream().filter(col -> !AUDIT.contains(col) && !s.key.contains(col)).toList();
        String refScope = "(" + s.where + ")" + (s.referenceWhere == null || s.referenceWhere.isBlank() ? "" : " AND (" + s.referenceWhere + ")");
        // plain equality on the key: PostgreSQL can hash-join it (IS NOT DISTINCT FROM forces a nested loop, minutes to hours on 300k rows);
        // key columns must therefore be non-null, checked below
        String keyMatch = s.key.stream().map(k -> "t." + k + " = r." + k).collect(Collectors.joining(" AND "));
        String contentDiff = content.isEmpty() ? "(1 = 0)"
            : "(" + content.stream().map(col -> "t." + col + " IS DISTINCT FROM r." + col).collect(Collectors.joining(" OR ")) + ")";
        String target = "(SELECT * FROM " + s.table + " WHERE " + s.where + ")";
        String reference = "(SELECT * FROM " + s.from + " WHERE " + refScope + ")";
        String anyKeyNull = s.key.stream().map(k -> "x." + k + " IS NULL").collect(Collectors.joining(" OR "));
        long nullKeys = count(c, "SELECT count(*) FROM " + reference + " x WHERE " + anyKeyNull)
            + count(c, "SELECT count(*) FROM " + target + " x WHERE " + anyKeyNull);
        if (nullKeys > 0) throw new ToolException("Step " + s.id + ": " + nullKeys + " rows have an empty key column; choose a key without nulls");

        long before = count(c, "SELECT count(*) FROM " + target + " t");
        long refRows = count(c, "SELECT count(*) FROM " + reference + " r");
        long changed = count(c, "SELECT count(*) FROM " + reference + " r JOIN " + target + " t ON " + keyMatch + " WHERE " + contentDiff);
        long missing = count(c, "SELECT count(*) FROM " + reference + " r WHERE NOT EXISTS (SELECT 1 FROM " + target + " t WHERE " + keyMatch + ")");
        long extra = count(c, "SELECT count(*) FROM " + target + " t WHERE NOT EXISTS (SELECT 1 FROM " + reference + " r WHERE " + keyMatch + ")");

        String backup = rule.backupSchema + ".restore_" + safe(rule.name).replace('-', '_') + "_" + s.id.replace('-', '_') + "_" + stamp;
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + backup + " AS SELECT * FROM " + s.table + " WHERE " + s.where);
            // replace rows whose key exists with other content
            st.executeUpdate("DELETE FROM " + s.table + " t WHERE (" + s.where + ") AND EXISTS (SELECT 1 FROM " + reference + " r WHERE "
                + keyMatch + " AND " + contentDiff + ")");
        }
        long removedExtra = 0;
        try (Statement st = c.createStatement()) {
            if (s.deleteExtra)
                removedExtra = st.executeUpdate("DELETE FROM " + s.table + " t WHERE (" + s.where + ") AND NOT EXISTS (SELECT 1 FROM "
                    + reference + " r WHERE " + keyMatch + ")");
            String cols = String.join(", ", common);
            st.executeUpdate("INSERT INTO " + s.table + " (" + cols + ") SELECT " + common.stream().map(col -> "r." + col).collect(Collectors.joining(", "))
                + " FROM " + reference + " r WHERE NOT EXISTS (SELECT 1 FROM " + target + " t WHERE " + keyMatch + ")");
        }
        long after = count(c, "SELECT count(*) FROM " + target + " t");
        long stillDifferent = count(c, "SELECT count(*) FROM " + reference + " r WHERE NOT EXISTS (SELECT 1 FROM " + target + " t WHERE "
            + keyMatch + " AND NOT " + contentDiff + ")");
        if (stillDifferent != 0) throw new ToolException("Step " + s.id + ": " + stillDifferent + " reference rows still differ after the restore");
        LOG.info("Step {}: target {} rows, reference {}; replaced {}, inserted {}, extra {} (removed {}); target now {}; backup {}; {} s",
            s.id, before, refRows, changed, missing, extra, removedExtra, after, backup, (System.nanoTime() - started) / 1_000_000_000);
        return new StepResult(s.id, s.table, s.from, before, refRows, changed, missing, extra, removedExtra, after, backup);
    }

    private static List<String> columns(Connection c, String qualified) throws Exception {
        String[] parts = qualified.split("\\.");
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE lower(table_schema) = lower(?) AND lower(table_name) = lower(?) ORDER BY ordinal_position")) {
            ps.setString(1, parts[0]); ps.setString(2, parts[1]);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1).toLowerCase(Locale.ROOT)); }
        }
        if (out.isEmpty()) throw new ToolException("Table not found: " + qualified);
        return out;
    }

    private static long count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static String safe(String name) { return name.replaceAll("[^A-Za-z0-9_-]", "_"); }

    private static void writeSummary(Path dir, Rule rule, List<StepResult> results, boolean applied) throws Exception {
        StringBuilder md = new StringBuilder("# Restore " + rule.name + "\n\n")
            .append(applied ? "Status: APPLIED (committed).\n\n" : "Status: PREVIEW (rolled back, nothing changed). Run again with --apply to commit.\n\n")
            .append(rule.description == null ? "" : rule.description + "\n\n")
            .append("| Step | Table | From | Rows before | Reference rows | Replaced | Inserted | Extra in target | Extra removed | Rows after | Backup |\n")
            .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (StepResult r : results)
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %d | %d | %d | %d | %d | %d | %s |%n", r.id, r.table, r.from, r.targetBefore,
                r.reference, r.changed, r.missing, r.extra, r.removedExtra, r.targetAfter, applied ? r.backup : "(preview: rolled back)"));
        Files.writeString(dir.resolve("RESTORE.md"), md.toString(), StandardCharsets.UTF_8);
    }

    /** Undo = the same steps, restoring the scope from the backup tables and removing what the restore inserted. */
    private static void writeUndo(Path dir, Rule rule, List<StepResult> results) throws Exception {
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("name", rule.name + "-undo");
        undo.put("db", rule.db);
        undo.put("backupSchema", rule.backupSchema);
        undo.put("description", "Undo of " + rule.name + ": puts back the rows as they were before the restore (from its backup tables).");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = results.size() - 1; i >= 0; i--) {
            StepResult r = results.get(i);
            Step s = rule.steps.get(i);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", s.id + "-undo"); step.put("table", s.table); step.put("from", r.backup);
            step.put("where", s.where); step.put("key", s.key); step.put("deleteExtra", true);
            steps.add(step);
        }
        undo.put("steps", steps);
        Files.writeString(dir.resolve("undo-" + safe(rule.name) + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(undo), StandardCharsets.UTF_8);
    }
}
