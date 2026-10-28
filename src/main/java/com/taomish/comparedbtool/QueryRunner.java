package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

public final class QueryRunner implements AutoCloseable {
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
        catch (Exception e) { throw new ToolException("Missing/invalid bind parameters for query " + rule.id + "."); }
    }
    private final DatasourceSettings settings;
    private final boolean allowProd;
    private final Map<String, HikariDataSource> pools = new LinkedHashMap<>();
    public QueryRunner(DatasourceSettings settings, boolean allowProd) { this.settings = settings; this.allowProd = allowProd; }

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
            return new HikariDataSource(config);
        });
    }

    public Result run(Rules.Sql rule, Map<String, Object> parameters, long globalLimit, Path directory) throws IOException {
        String sql = SqlGuard.validate(rule.text);
        // Resolve all parameters before opening a database connection.
        validateParameters(rule, parameters);
        long cap = rule.maxRows;
        if (globalLimit > 0) cap = cap == 0 ? globalLimit : Math.min(cap, globalLimit);
        final long limit = cap;
        Path csv = directory.resolve(rule.id + ".csv");
        Path sqlFile = directory.resolve(rule.id + ".sql");
        Path meta = directory.resolve(rule.id + ".md");
        Files.writeString(sqlFile, rule.text.strip() + System.lineSeparator());
        long start = System.nanoTime();
        List<Column> columns = new ArrayList<>();
        long[] rows = {0};
        boolean[] partial = {false};
        try {
            new NamedParameterJdbcTemplate(datasource(rule.db)).execute(sql, parameters,
                (PreparedStatementCallback<Void>) statement -> {
                    statement.setQueryTimeout(rule.timeout);
                    statement.setFetchSize(rule.fetchSize);
                    try (ResultSet result = statement.executeQuery()) {
                        ResultSetMetaData metadata = result.getMetaData();
                        Set<String> labels = new HashSet<>();
                        for (int index = 1; index <= metadata.getColumnCount(); index++) {
                            String label = metadata.getColumnLabel(index);
                            if (label == null || label.isBlank() || !labels.add(label.toLowerCase(Locale.ROOT)))
                                throw new ToolException("Query " + rule.id + " has empty/duplicate column labels; use SQL aliases.");
                            columns.add(new Column(label, metadata.getColumnType(index)));
                        }
                        try (CSVPrinter out = CsvFiles.writer(csv, columns.stream().map(Column::name).toList())) {
                            while (result.next()) {
                                if (limit > 0 && rows[0] >= limit) { partial[0] = true; break; }
                                List<String> values = new ArrayList<>(columns.size());
                                for (int index = 1; index <= columns.size(); index++) values.add(value(result, index, columns.get(index - 1).jdbcType()));
                                out.printRecord(values);
                                rows[0]++;
                            }
                        } catch (IOException e) { throw new ToolException("Cannot write CSV for query " + rule.id + "."); }
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
                + "). Check connection settings, permissions and SQL. Any CSV from this query is incomplete.");
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        StringBuilder description = new StringBuilder("# Query " + rule.id + "\n\nDatasource: `" + rule.db + "`\n\nRows: "
            + rows[0] + "\n\nStatus: " + (partial[0] ? "PARTIAL: row cap exceeded" : "COMPLETE")
            + "\n\nElapsed: " + millis + " ms\n\n| Column | JDBC type |\n|---|---|\n");
        for (Column column : columns) description.append("| ").append(column.name().replace("|", "\\|"))
            .append(" | ").append(column.jdbcType()).append(" |\n");
        description.append("\nCSV: UTF-8, header row; unquoted \\N = NULL, quoted \\\"\\N\\\" = literal text.\n");
        Files.writeString(meta, description);
        return new Result(rule.id, csv, List.copyOf(columns), rows[0], partial[0], millis, List.of(csv, sqlFile, meta));
    }
    private static String value(ResultSet result, int index, int type) throws SQLException {
        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) {
            byte[] bytes = result.getBytes(index);
            return bytes == null ? null : "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
        // JDBC getString preserves DECIMAL precision; no conversion through double.
        return result.getString(index);
    }
    @Override public void close() { pools.values().forEach(HikariDataSource::close); }
}
