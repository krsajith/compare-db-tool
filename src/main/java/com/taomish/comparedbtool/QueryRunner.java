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
    public QueryRunner(DatasourceSettings settings, boolean allowProd) {
        this.settings = settings; this.allowProd = allowProd;
        settings.getDatasources().values().forEach(d -> SecretRedactor.register(d.getPassword()));
    }

    public static boolean production(String name, DatasourceSettings.Definition definition) {
        return definition.isProd() || name.toLowerCase(Locale.ROOT).contains("prod");
    }
    private HikariDataSource datasource(String name) {
        return pools.computeIfAbsent(name, key -> {
            var definition = settings.getDatasources().get(key);
            if (definition == null) throw new ToolException("Unknown datasource: " + key);
            if (!definition.isReadOnly()) throw new ToolException("Datasource must be read-only: " + key);
            if (production(key, definition) && !allowProd)
                throw new ToolException("Datasource " + key + " requires --allow-prod.");
            if (definition.getUrl() == null || !definition.getUrl().startsWith("jdbc:"))
                throw new ToolException("Datasource " + key + " needs a JDBC URL.");
            if (definition.getPassword() != null && definition.getPassword().contains("${"))
                throw new ToolException("Set the password environment variable for datasource " + key + ".");
            HikariConfig config = new HikariConfig();
            config.setPoolName("compare-" + key);
            config.setJdbcUrl(definition.getUrl());
            config.setUsername(definition.getUsername());
            config.setPassword(definition.getPassword());
            if (definition.getDriverClassName() != null && !definition.getDriverClassName().isBlank())
                config.setDriverClassName(definition.getDriverClassName());
            config.setReadOnly(true);
            config.setAutoCommit(false);
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(30_000);
            config.setInitializationFailTimeout(-1);
            if (definition.getUrl().startsWith("jdbc:postgresql:"))
                config.setConnectionInitSql("SET default_transaction_read_only = on");
            else if (definition.getUrl().startsWith("jdbc:mysql:"))
                config.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
            if (definition.getUrl().startsWith("jdbc:mysql:")) {
                config.addDataSourceProperty("useCursorFetch", "true");
                config.addDataSourceProperty("useServerPrepStmts", "true");
            }
            LOG.info("Creating read-only datasource pool name={} driver={} production={}", key,
                definition.getDriverClassName(), production(key, definition));
            return new HikariDataSource(config);
        });
    }

    public Result run(Rules.Sql rule, Map<String, Object> parameters, long globalLimit, Path directory) throws IOException {
        return run(rule, parameters, globalLimit, directory, ExportMode.FULL);
    }
    public Result run(Rules.Sql rule, Map<String, Object> parameters, long globalLimit, Path directory, ExportMode defaultMode) throws IOException {
        ExportMode mode = ExportMode.resolve(rule.export, defaultMode);
        String sql = SqlGuard.validate(rule.text);
        // Resolve all parameters before opening a database connection.
        validateParameters(rule, parameters);
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
        try {
            new NamedParameterJdbcTemplate(datasource(rule.db)).execute(sql, parameters,
                (PreparedStatementCallback<Void>) statement -> {
                    statement.setQueryTimeout(rule.timeout);
                    statement.setFetchSize(rule.fetchSize);
                    LOG.debug("Connected for query {}; executing SELECT", rule.id);
                    try (ResultSet result = statement.executeQuery()) {
                        ResultSetMetaData metadata = result.getMetaData();
                        Set<String> labels = new HashSet<>();
                        for (int index = 1; index <= metadata.getColumnCount(); index++) {
                            String label = metadata.getColumnLabel(index);
                            if (label == null || label.isBlank() || !labels.add(label.toLowerCase(Locale.ROOT)))
                                throw new ToolException("Query " + rule.id + " has empty/duplicate column labels; use SQL aliases.");
                            columns.add(new Column(label, metadata.getColumnType(index)));
                        }
                        LOG.debug("Query {} returned column labels={}", rule.id, columns.stream().map(Column::name).toList());
                        try (CSVPrinter out = CsvFiles.writer(csv, columns.stream().map(Column::name).toList())) {
                            while (result.next()) {
                                if (limit > 0 && rows[0] >= limit) { partial[0] = true; break; }
                                List<String> values = new ArrayList<>(columns.size());
                                for (int index = 1; index <= columns.size(); index++) values.add(value(result, index, columns.get(index - 1).jdbcType()));
                                out.printRecord(values);
                                rows[0]++;
                                if (rows[0] % 100_000 == 0) LOG.info("Query {} exported {} rows so far", rule.id, rows[0]);
                            }
                        } catch (IOException e) { throw new ToolException("Cannot write CSV for query " + rule.id + ".", e); }
                    } finally {
                        // No source data is committed, even for drivers with advisory read-only flags.
                        statement.getConnection().rollback();
                    }
                    return null;
                });
        } catch (ToolException e) { throw e; }
        catch (Exception e) {
            String state = "unknown";
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof SQLException se) { state = Objects.toString(se.getSQLState(), "unknown"); break; }
            throw new ToolException("Query " + rule.id + " failed on datasource " + rule.db + " (SQLState " + state
                + "). Check connection settings, permissions and SQL. Any CSV from this query is incomplete.", e);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        if (partial[0]) LOG.warn("Query {} hit its row cap after {} rows; results are PARTIAL", rule.id, rows[0]);
        LOG.info("Query {} export complete: rows={} elapsedMs={} CSV={}", rule.id, rows[0], millis, csv);
        writeMetadata(rule, rows[0], partial[0], millis, columns, mode, meta, null);
        List<Path> files = new ArrayList<>();
        if (mode == ExportMode.FULL) files.add(csv);
        if (mode != ExportMode.NONE) files.add(sqlFile);
        files.add(meta);
        return new Result(rule.id, csv, List.copyOf(columns), rows[0], partial[0], millis, List.copyOf(files));
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
    }
}
