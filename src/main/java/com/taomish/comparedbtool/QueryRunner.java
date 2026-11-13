package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.taomish.comparedbtool.logging.SecretRedactor;

public final class QueryRunner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(QueryRunner.class);
    public record Column(String name, int jdbcType) {
        public boolean numeric() {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT,
                     Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
                default -> false;
            };
        }
    }
    public record Result(String id, Path csv, List<Column> columns, long rows, boolean partial, long millis,
                         List<Path> files) {}
    public static void validateParameters(Rules.Sql rule, Map<String, Object> parameters) {
        try {
            NamedParameterUtils.buildValueArray(NamedParameterUtils.parseSqlStatement(SqlGuard.validate(rule.text)),
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(parameters), null);
        } catch (ToolException e) { throw e; }
        catch (Exception e) { throw new ToolException("Missing/invalid bind parameters for query " + rule.id + ".", e); }
    }
    private final DatasourceSettings settings;
    private final boolean allowProd;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<String, HikariDataSource> tempPools = new ConcurrentHashMap<>();
    private final long connectTimeoutMs;
    public QueryRunner(DatasourceSettings settings, boolean allowProd) { this(settings, allowProd, 30_000); }
    /** {@code connectTimeoutMs}: how long to wait for a connection before giving up (a check should not wait as long as a run). */
    public QueryRunner(DatasourceSettings settings, boolean allowProd, long connectTimeoutMs) {
        this.settings = settings; this.allowProd = allowProd; this.connectTimeoutMs = connectTimeoutMs;
        settings.getDatasources().values().forEach(d -> SecretRedactor.register(d.getPassword()));
    }

    public static boolean production(String name, DatasourceSettings.Definition definition) {
        return definition.isProd() || name.toLowerCase(Locale.ROOT).contains("prod");
    }
    @FunctionalInterface interface ConnectionWork<T> { T execute(Connection connection) throws Exception; }
    <T> T transaction(Rules.Sql rule, Map<String, Object> parameters, ConnectionWork<T> work) throws Exception {
        SqlGuard.validate(rule.text);
        TempTablePolicy.validate(rule, settings);
        validateParameters(rule, parameters);
        if (rule.tempTables) LOG.warn("Temp-table mode datasource={} query={}; transaction will be rolled back", rule.db, rule.id);
        try (Connection connection = datasource(rule.db, rule.tempTables).getConnection()) {
            try { return work.execute(connection); }
            finally { connection.rollback(); }
        }
    }
    private HikariDataSource datasource(String name, boolean tempTables) {
        return (tempTables ? tempPools : pools).computeIfAbsent(name, key -> {
            var definition = settings.getDatasources().get(key);
            if (definition == null) throw new ToolException("Unknown datasource: " + key);
            if (!tempTables && !definition.isReadOnly()) throw new ToolException("Datasource must be read-only: " + key);
            if (production(key, definition) && !allowProd)
                throw new ToolException("Datasource " + key + " requires --allow-prod.");
            if (definition.getUrl() == null || !definition.getUrl().startsWith("jdbc:"))
                throw new ToolException("Datasource " + key + " needs a JDBC URL.");
            if (definition.getPassword() != null && definition.getPassword().contains("${"))
                throw new ToolException("Set the password environment variable for datasource " + key + ".");
            HikariConfig config = new HikariConfig();
            config.setPoolName("compare-" + (tempTables ? "temp-" : "") + key);
            config.setJdbcUrl(definition.getUrl());
            config.setUsername(definition.getUsername());
            config.setPassword(definition.getPassword());
            if (definition.getDriverClassName() != null && !definition.getDriverClassName().isBlank())
                config.setDriverClassName(definition.getDriverClassName());
            config.setReadOnly(!tempTables);
            config.setAutoCommit(false);
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(connectTimeoutMs);
            config.setInitializationFailTimeout(-1);
            SessionSettings.configure(config, definition.getUrl());
            // a statement that runs for hours sends nothing over the connection: keepalive stops a VPN or firewall from dropping it as idle
            if (definition.getUrl().startsWith("jdbc:postgresql:")) config.addDataSourceProperty("tcpKeepAlive", "true");
            if (!tempTables && definition.getUrl().startsWith("jdbc:postgresql:"))
                config.setConnectionInitSql("SET default_transaction_read_only = on");
            else if (!tempTables && definition.getUrl().startsWith("jdbc:mysql:"))
                config.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
            if (definition.getUrl().startsWith("jdbc:mysql:")) {
                config.addDataSourceProperty("useCursorFetch", "true");
                config.addDataSourceProperty("useServerPrepStmts", "true");
            }
            LOG.info("Creating datasource pool name={} driver={} production={} tempTables={}", key,
                definition.getDriverClassName(), production(key, definition), tempTables);
            return new HikariDataSource(config);
        });
    }

    public Result run(Rules.Sql rule, Map<String, Object> parameters, long globalLimit, Path directory) throws IOException {
        return run(rule, parameters, globalLimit, directory, ExportMode.FULL);
    }
    public Result run(Rules.Sql rule, Map<String, Object> parameters, long globalLimit, Path directory, ExportMode defaultMode) throws IOException {
        return run(rule, parameters, globalLimit, directory, defaultMode, null);
    }
    /** {@code inputs} says what was taken from earlier queries (shown while running and written to the query's metadata). */
    public Result run(Rules.Sql rule, Map<String, Object> parameters, long globalLimit, Path directory, ExportMode defaultMode, String inputs) throws IOException {
        ExportMode mode = ExportMode.resolve(rule.export, defaultMode);
        String sql = SqlGuard.validate(rule.text);
        TempTablePolicy.validate(rule, settings);
        // Resolve all parameters before opening a database connection.
        validateParameters(rule, parameters);
        // One execution, or one per slice of a long list taken from another query (checked before any connection is opened).
        final List<Map<String, Object>> executions = Chaining.executions(rule, parameters);
        long cap = rule.maxRows;
        if (globalLimit > 0) cap = cap == 0 ? globalLimit : Math.min(cap, globalLimit);
        final long limit = cap;
        LOG.debug("Query={} datasource={} timeoutSeconds={} fetchSize={} rowLimit={} parameterNames={}",
            rule.id, rule.db, rule.timeout, rule.fetchSize, limit, parameters.keySet());
        Path csv = mode == ExportMode.FULL ? directory.resolve(rule.id + ".csv")
            : directory.resolve(".work").resolve("queries").resolve(rule.id + ".csv");
        Path sqlFile = directory.resolve(rule.id + ".sql");
        Path meta = directory.resolve(rule.id + ".md");
        if (mode != ExportMode.NONE) Files.writeString(sqlFile, rule.text.strip() + System.lineSeparator());
        long start = System.nanoTime();
        List<Column> columns = new ArrayList<>();
        long[] rows = {0};
        boolean[] partial = {false};
        final Progress.Handle progress = Progress.get().start(rule.id, rule.db + (inputs == null || inputs.isBlank() ? "" : " (" + inputs + ")"));
        final CSVPrinter[] printer = {null};
        try {
            if (rule.tempTables) LOG.warn("Temp-table mode datasource={} query={}; transaction will be rolled back", rule.db, rule.id);
            var template = new NamedParameterJdbcTemplate(datasource(rule.db, rule.tempTables));
            int batchNumber = 0;
            for (Map<String, Object> execution : executions) {
              if (partial[0]) break;                       // the row cap was reached in an earlier batch
              if (executions.size() > 1) LOG.info("Query {} batch {} of {}", rule.id, ++batchNumber, executions.size());
              template.execute(sql, execution,
                (PreparedStatementCallback<Void>) statement -> {
                    try {
                      statement.setQueryTimeout(rule.timeout);
                      statement.setFetchSize(rule.fetchSize);
                      LOG.debug("Connected for query {}; executing SELECT", rule.id);
                      try (ResultSet result = statement.executeQuery()) {
                        ResultSetMetaData metadata = result.getMetaData();
                        if (columns.isEmpty()) {
                            Set<String> labels = new HashSet<>();
                            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                                String label = metadata.getColumnLabel(index);
                                if (label == null || label.isBlank() || !labels.add(label.toLowerCase(Locale.ROOT)))
                                    throw new ToolException("Query " + rule.id + " has empty/duplicate column labels; use SQL aliases.");
                                columns.add(new Column(label, metadata.getColumnType(index)));
                            }
                            LOG.debug("Query {} returned column labels={}", rule.id, columns.stream().map(Column::name).toList());
                            try { printer[0] = CsvFiles.writer(csv, columns.stream().map(Column::name).toList()); }
                            catch (IOException e) { throw new ToolException("Cannot write CSV for query " + rule.id + ".", e); }
                        }
                        try {
                            CSVPrinter out = printer[0];
                            while (result.next()) {
                                if (limit > 0 && rows[0] >= limit) { partial[0] = true; break; }
                                List<String> values = new ArrayList<>(columns.size());
                                for (int index = 1; index <= columns.size(); index++) values.add(value(result, index, columns.get(index - 1).jdbcType()));
                                out.printRecord(values);
                                rows[0]++;
                                if ((rows[0] & 2047) == 0) progress.rows(rows[0]);
                                if (rows[0] % 100_000 == 0) LOG.info("Query {} exported {} rows so far", rule.id, rows[0]);
                            }
                        } catch (IOException e) { throw new ToolException("Cannot write CSV for query " + rule.id + ".", e); }
                      }
                    } finally {
                        // No source data is committed, even for drivers with advisory read-only flags.
                        statement.getConnection().rollback();
                    }
                    return null;
                });
            }
        } catch (ToolException e) { throw e; }
        catch (Exception e) {
            String state = "unknown";
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof SQLException se) { state = Objects.toString(se.getSQLState(), "unknown"); break; }
            throw new ToolException("Query " + rule.id + " failed on datasource " + rule.db + " (SQLState " + state
                + "). Check connection settings, permissions and SQL. Any CSV from this query is incomplete.", e);
        } finally {
            progress.done();
            if (printer[0] != null) try { printer[0].close(); } catch (IOException e) { LOG.warn("Could not close the CSV of query {}", rule.id, e); }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        if (partial[0]) LOG.warn("Query {} hit its row cap after {} rows; results are PARTIAL", rule.id, rows[0]);
        LOG.info("Query {} export complete: rows={} elapsedMs={} CSV={}", rule.id, rows[0], millis, csv);
        writeMetadata(rule, rows[0], partial[0], millis, columns, mode, meta, null);
        if (inputs != null && !inputs.isBlank())
            Files.writeString(meta, Files.readString(meta) + "\nInputs from earlier queries: " + inputs + (executions.size() > 1 ? "; run as " + executions.size() + " batches, rows joined in batch order" : "") + ".\n");
        List<Path> files = new ArrayList<>();
        if (mode == ExportMode.FULL) files.add(csv);
        if (mode != ExportMode.NONE) files.add(sqlFile);
        files.add(meta);
        return new Result(rule.id, csv, List.copyOf(columns), rows[0], partial[0], millis, List.copyOf(files));
    }

    /** What the database says about a query without running it: its columns and its plan. */
    public record Described(List<Column> columns, String plan, Long estimatedRows) {}

    private static final java.util.regex.Pattern PLAN_ROWS = java.util.regex.Pattern.compile("\\brows=(\\d+)");

    /** PostgreSQL puts the planner's row estimate on the first line of the plan ("... rows=9300 width=..."); other databases give none. */
    static Long estimatedRows(String plan) {
        if (plan == null) return null;
        String first = plan.lines().findFirst().orElse("");
        var m = PLAN_ROWS.matcher(first);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    /**
     * Asks the database to describe the statement and to EXPLAIN it, without executing it: missing tables or columns, syntax and type
     * errors come back as exceptions, exactly as they would at run time. Never EXPLAIN ANALYZE.
     */
    public Described describe(Rules.Sql rule, Map<String, Object> parameters) {
        String sql = SqlGuard.validate(rule.text);
        TempTablePolicy.validate(rule, settings);
        validateParameters(rule, parameters);
        int timeout = Math.max(1, Math.min(rule.timeout, 30));
        var template = new NamedParameterJdbcTemplate(datasource(rule.db, false));
        List<Column> columns = template.execute(sql, parameters, (PreparedStatementCallback<List<Column>>) statement -> {
            try {
                statement.setQueryTimeout(timeout);
                ResultSetMetaData metadata = statement.getMetaData();
                if (metadata == null) return null;
                List<Column> described = new ArrayList<>();
                Set<String> labels = new HashSet<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String label = metadata.getColumnLabel(index);
                    if (label == null || label.isBlank() || !labels.add(label.toLowerCase(Locale.ROOT)))
                        throw new ToolException("Query " + rule.id + " has empty/duplicate column labels; use SQL aliases.");
                    described.add(new Column(label, metadata.getColumnType(index)));
                }
                return described;
            } finally { statement.getConnection().rollback(); }
        });
        String plan = template.execute("EXPLAIN " + sql, parameters, (PreparedStatementCallback<String>) statement -> {
            try {
                statement.setQueryTimeout(timeout);
                StringBuilder text = new StringBuilder();
                try (ResultSet result = statement.executeQuery()) {
                    int count = result.getMetaData().getColumnCount();
                    while (result.next() && text.length() < 4000) {
                        List<String> cells = new ArrayList<>();
                        for (int i = 1; i <= count; i++) cells.add(Objects.toString(result.getString(i), ""));
                        text.append(String.join(" | ", cells)).append('\n');
                    }
                }
                return text.toString();
            } finally { statement.getConnection().rollback(); }
        });
        return new Described(columns, plan, estimatedRows(plan));
    }

    /**
     * The plan of a statement as PostgreSQL JSON. analyze=true runs it (EXPLAIN ANALYZE) in the read-only pool, which is always rolled back, so it
     * cannot write; analyze=false only plans it and executes nothing. Production datasources still need --allow-prod.
     */
    public record Plan(String json, List<String> notices) {}

    public Plan explain(Rules.Explain explain, Map<String, Object> parameters) {
        Rules.Sql rule = ExplainRunner.asSql(explain);
        String sql = SqlGuard.validate(rule.text);
        TempTablePolicy.validate(rule, settings);
        validateParameters(rule, parameters);
        var definition = settings.getDatasources().get(explain.db);
        if (definition == null || definition.getUrl() == null || !definition.getUrl().startsWith("jdbc:postgresql:"))
            throw new ToolException("<explain> " + explain.id + " works on PostgreSQL datasources only: " + explain.db);
        String options = explain.analyze ? "ANALYZE" + (explain.buffers ? ", BUFFERS" : "") + ", FORMAT JSON" : "FORMAT JSON";
        LOG.info("Explain {} datasource={} analyze={} timeoutSeconds={}", explain.id, explain.db, explain.analyze, explain.timeout);
        if (explain.tempTables) LOG.warn("Temp-table mode datasource={} explain={}; transaction will be rolled back", explain.db, explain.id);
        if (explain.generic) return explainGeneric(explain, sql, parameters, options);
        var template = new NamedParameterJdbcTemplate(datasource(explain.db, explain.tempTables));
        return template.execute("EXPLAIN (" + options + ") " + sql, parameters, (PreparedStatementCallback<Plan>) statement -> {
            try {
                statement.setQueryTimeout(explain.timeout);
                StringBuilder text = new StringBuilder();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) text.append(Objects.toString(result.getString(1), ""));
                }
                // what the statement printed with RAISE NOTICE (a report function reports its steps this way)
                List<String> notices = new ArrayList<>();
                for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning())
                    if (warning.getMessage() != null) notices.add(warning.getMessage().strip());
                return new Plan(text.toString(), List.copyOf(notices));
            } finally { statement.getConnection().rollback(); }
        });
    }

    /** PostgreSQL type name for a bound value, so PREPARE can declare its parameters (the extended protocol does not infer them for a PREPARE body). */
    private static String pgType(Object value) {
        if (value instanceof java.time.LocalDate || value instanceof java.sql.Date) return "date";
        if (value instanceof java.sql.Timestamp || value instanceof java.time.LocalDateTime) return "timestamp";
        if (value instanceof Integer || value instanceof Short) return "int4";
        if (value instanceof Long) return "int8";
        if (value instanceof java.math.BigDecimal) return "numeric";
        if (value instanceof Double || value instanceof Float) return "float8";
        if (value instanceof Boolean) return "boolean";
        return "varchar";
    }

    /** Plans the statement as a prepared statement with a generic plan (what the application gets after pgjdbc's prepareThreshold), then rolls back and cleans up the connection. */
    private Plan explainGeneric(Rules.Explain explain, String sql, Map<String, Object> parameters, String options) {
        var source = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(parameters);
        var parsed = NamedParameterUtils.parseSqlStatement(sql);
        String jdbcSql = NamedParameterUtils.substituteNamedParameters(parsed, source);
        Object[] values = NamedParameterUtils.buildValueArray(parsed, source, null);
        StringBuilder numbered = new StringBuilder();
        int count = 0;
        boolean quoted = false;
        for (char c : jdbcSql.toCharArray()) {
            if (c == '\'') quoted = !quoted;
            if (c == '?' && !quoted) numbered.append('$').append(++count); else numbered.append(c);
        }
        if (count != values.length) throw new ToolException("<explain> " + explain.id + " generic=true: could not number the parameters of the statement.");
        String marks = String.join(",", Collections.nCopies(count, "?"));
        var template = new NamedParameterJdbcTemplate(datasource(explain.db, explain.tempTables));
        return template.getJdbcOperations().execute((org.springframework.jdbc.core.ConnectionCallback<Plan>) connection -> {
            try {
                try (Statement setup = connection.createStatement()) {
                    setup.setQueryTimeout(explain.timeout);
                    setup.execute("SET plan_cache_mode = force_generic_plan");
                    setup.execute("PREPARE cdt_generic(" + String.join(", ", java.util.Arrays.stream(values).map(QueryRunner::pgType).toList()) + ") AS " + numbered);
                }
                try (PreparedStatement statement = connection.prepareStatement("EXPLAIN (" + options + ") EXECUTE cdt_generic(" + marks + ")")) {
                    statement.setQueryTimeout(explain.timeout);
                    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                    StringBuilder text = new StringBuilder();
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) text.append(Objects.toString(result.getString(1), ""));
                    }
                    List<String> notices = new ArrayList<>();
                    for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning())
                        if (warning.getMessage() != null) notices.add(warning.getMessage().strip());
                    return new Plan(text.toString(), List.copyOf(notices));
                }
            } finally {
                try { connection.rollback(); } catch (SQLException ignored) { }
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("DEALLOCATE cdt_generic");
                    cleanup.execute("RESET plan_cache_mode");
                } catch (SQLException ignored) { }
                try { connection.rollback(); } catch (SQLException ignored) { }
            }
        });
    }

    /** Publishes an alias without re-executing the SQL; per-rule row caps and export settings still apply. */
    public Result reuse(Rules.Sql rule, Result shared, long globalLimit, Path directory, ExportMode defaultMode) throws IOException {
        ExportMode mode = ExportMode.resolve(rule.export, defaultMode);
        long limit = effectiveLimit(rule, globalLimit);
        long rows = limit > 0 ? Math.min(limit, shared.rows()) : shared.rows();
        boolean partial = shared.partial() || rows < shared.rows();
        Path csv = shared.csv();
        if (mode == ExportMode.FULL || rows < shared.rows()) {
            csv = mode == ExportMode.FULL ? directory.resolve(rule.id + ".csv")
                : directory.resolve(".work/queries").resolve(rule.id + ".csv");
            if (rows == shared.rows()) Files.copy(shared.csv(), csv);
            else try (var input = CsvFiles.reader(shared.csv(), true);
                      var output = CsvFiles.writer(csv, shared.columns().stream().map(Column::name).toList())) {
                long copied = 0;
                for (var row : input) { if (copied++ >= rows) break; output.printRecord(CsvFiles.values(row)); }
            }
        }
        Path sql = directory.resolve(rule.id + ".sql"), metadata = directory.resolve(rule.id + ".md");
        if (mode != ExportMode.NONE) Files.writeString(sql, rule.text.strip() + System.lineSeparator());
        writeMetadata(rule, rows, partial, shared.millis(), shared.columns(), mode, metadata, shared.id());
        List<Path> files = new ArrayList<>();
        if (mode == ExportMode.FULL) files.add(csv);
        if (mode != ExportMode.NONE) files.add(sql);
        files.add(metadata);
        LOG.info("Query {} reused execution {}: rows={} partial={} export={}", rule.id, shared.id(), rows, partial, mode);
        return new Result(rule.id, csv, shared.columns(), rows, partial, shared.millis(), List.copyOf(files));
    }

    static long effectiveLimit(Rules.Sql rule, long globalLimit) {
        return globalLimit > 0 ? rule.maxRows == 0 ? globalLimit : Math.min(rule.maxRows, globalLimit) : rule.maxRows;
    }
    private static void writeMetadata(Rules.Sql rule, long rows, boolean partial, long millis, List<Column> columns,
                                      ExportMode mode, Path meta, String reusedFrom) throws IOException {
        StringBuilder description = new StringBuilder("# Query " + rule.id + "\n\nDatasource: `" + rule.db + "`\n\nRows: "
            + rows + "\n\nStatus: " + (partial ? "PARTIAL: row cap exceeded" : "COMPLETE")
            + "\n\nElapsed: " + millis + " ms\n\nExport: " + mode.name().toLowerCase(Locale.ROOT)
            + (mode == ExportMode.FULL ? "; complete query CSV retained." : "; query CSV is temporary comparison input, not exported.")
            + "\n\nParameters: see PARAMETERS.csv.\n\n| Column | JDBC type |\n|---|---|\n");
        for (Column column : columns) description.append("| ").append(column.name().replace("|", "\\|"))
            .append(" | ").append(column.jdbcType()).append(" |\n");
        description.append("\nCSV: UTF-8, header row; unquoted \\N = NULL, quoted \\\"\\N\\\" = literal text.\n");
        if (reusedFrom != null) description.append("\nReused execution: ").append(reusedFrom)
            .append(". Elapsed time is shared query execution time, not an additional database query.\n");
        Files.writeString(meta, description);
    }
    private static String value(ResultSet result, int index, int type) throws SQLException {
        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) {
            byte[] bytes = result.getBytes(index);
            return bytes == null ? null : "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
        // JDBC getString preserves DECIMAL precision; no conversion through double.
        return result.getString(index);
    }
    @Override public void close() {
        LOG.debug("Closing {} datasource pool(s)", pools.size());
        pools.values().forEach(HikariDataSource::close);
        tempPools.values().forEach(HikariDataSource::close);
    }
}
