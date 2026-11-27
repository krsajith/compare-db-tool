package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Copies stored documents (for example the Web XTR / GPC master Excel files) from one datasource to another, insert only,
 * in opera.oga_document_repository. Reached through --template-import / --template-upload when the rule JSON has a
 * "copyFromDb" key (see Cli.execute). Bytes travel through JDBC byte arrays; nothing is put in the rule.
 * Per source document (tenant + business application in the list, storage_type DB): when the target has no row with the same
 * tenant, business application and name the whole row is inserted with every column as in the source. When the target already
 * has that document with the same MD5 it is skipped; with a different MD5 the run is refused (use the replace rule instead).
 * expectedMd5 maps document_name to the MD5 the source bytes must have (guards that the source holds the intended version).
 * Preview by default (read-only target, nothing written); --apply inserts in one transaction, reads the bytes back and compares,
 * and writes documents/.backups/copy-.../undo.sql (DELETE by uuid). Production targets also need --allow-prod.
 */
public final class DocumentCopier {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentCopier.class);
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public static class Rule {
        public String copyFromDb, db, tenantId;
        public String schema = "opera";
        public List<String> businessApplications;
        public Map<String, String> expectedMd5;
    }

    private final DatasourceSettings settings;
    public DocumentCopier(DatasourceSettings settings) { this.settings = settings; }

    static boolean isCopyRule(String json) { return JSON.readTree(json).has("copyFromDb"); }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new ToolException("Document copy rule requires " + name);
        return value;
    }

    private HikariDataSource pool(String name, boolean readOnly, boolean allowProd, boolean target) {
        var definition = settings.getDatasources().get(name);
        if (definition == null) throw new ToolException("Unknown datasource: " + name);
        if (target && QueryRunner.production(name, definition) && !allowProd) throw new ToolException("Datasource " + name + " requires --allow-prod");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required(definition.getUrl(), "datasource JDBC URL"));
        config.setUsername(definition.getUsername()); config.setPassword(definition.getPassword());
        if (definition.getDriverClassName() != null) config.setDriverClassName(definition.getDriverClassName());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setAutoCommit(false);
        config.setConnectionTimeout(30_000); config.setInitializationFailTimeout(-1);
        config.setReadOnly(readOnly);
        return new HikariDataSource(config);
    }

    private record Row(String app, String name, List<Object> values, byte[] bytes, String md5, Object uuid) {}

    public int execute(Cli.Options options) {
        try {
            Rule rule = JSON.readValue(Files.readString(options.templateUpload), Rule.class);
            required(rule.copyFromDb, "copyFromDb"); required(rule.db, "db"); required(rule.tenantId, "tenantId");
            if (rule.schema == null || !rule.schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ToolException("Invalid schema identifier");
            if (rule.businessApplications == null || rule.businessApplications.isEmpty()) throw new ToolException("businessApplications must be a nonempty list");
            if (rule.copyFromDb.equals(rule.db)) throw new ToolException("copyFromDb and db must differ");
            if (options.validate) { LOG.info("VALID: document copy rule checked; no database connection opened"); return 0; }
            String table = rule.schema + ".oga_document_repository";

            List<String> columns = new ArrayList<>();
            List<Row> rows = new ArrayList<>();
            try (var source = pool(rule.copyFromDb, true, true, false); Connection sc = source.getConnection()) {
                String in = rule.businessApplications.stream().map(a -> "?").collect(Collectors.joining(","));
                try (var sql = sc.prepareStatement("SELECT * FROM " + table + " WHERE tenant_id=? AND lower(business_application_name) IN (" + in + ") ORDER BY business_application_name, document_name")) {
                    sql.setQueryTimeout(120);
                    sql.setString(1, rule.tenantId);
                    for (int i = 0; i < rule.businessApplications.size(); i++) sql.setString(i + 2, rule.businessApplications.get(i).toLowerCase());
                    try (var rs = sql.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int n = meta.getColumnCount();
                        int docCol = -1, appCol = -1, nameCol = -1, uuidCol = -1, storageCol = -1;
                        for (int i = 1; i <= n; i++) {
                            String c = meta.getColumnName(i).toLowerCase();
                            columns.add(c);
                            switch (c) {
                                case "document" -> docCol = i;
                                case "business_application_name" -> appCol = i;
                                case "document_name" -> nameCol = i;
                                case "uuid" -> uuidCol = i;
                                case "storage_type" -> storageCol = i;
                                default -> { }
                            }
                        }
                        if (docCol < 0 || appCol < 0 || nameCol < 0 || uuidCol < 0 || storageCol < 0) throw new ToolException("Source table lacks an expected column");
                        while (rs.next()) {
                            String storage = rs.getString(storageCol);
                            String name = rs.getString(nameCol);
                            if (storage == null || !storage.equalsIgnoreCase("DB")) throw new ToolException("Source document " + name + " is stored as " + storage + ", not DB; refused");
                            byte[] bytes = rs.getBytes(docCol);
                            if (bytes == null || bytes.length == 0) throw new ToolException("Source document has no content: " + name);
                            List<Object> values = new ArrayList<>();
                            for (int i = 1; i <= n; i++) {
                                int type = meta.getColumnType(i);
                                boolean binary = type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB;
                                values.add(binary ? rs.getBytes(i) : rs.getObject(i));
                            }
                            rows.add(new Row(rs.getString(appCol), name, values, bytes, DocumentUploader.md5(bytes), rs.getObject(uuidCol)));
                        }
                    }
                } finally { sc.rollback(); }
            }
            if (rows.isEmpty()) throw new ToolException("Source " + rule.copyFromDb + " has no document for the listed business applications");
            if (rule.expectedMd5 != null) for (var e : rule.expectedMd5.entrySet()) {
                if (!e.getValue().matches("(?i)[0-9a-f]{32}")) throw new ToolException("expectedMd5 for " + e.getKey() + " must be 32 hexadecimal characters");
                Row hit = rows.stream().filter(r -> r.name().equalsIgnoreCase(e.getKey())).findFirst()
                    .orElseThrow(() -> new ToolException("expectedMd5 names " + e.getKey() + " but the source has no such document"));
                if (!hit.md5().equalsIgnoreCase(e.getValue()))
                    throw new ToolException("Source " + e.getKey() + " has MD5 " + hit.md5() + ", expected " + e.getValue() + "; copy refused (is the source fix applied?)");
            }

            int copied = 0, skipped = 0;
            List<Object> uuids = new ArrayList<>();
            String existsSql = "SELECT document FROM " + table + " WHERE tenant_id=? AND lower(business_application_name)=lower(?) AND document_name=?";
            try (var target = pool(rule.db, !options.apply, options.allowProd, true); Connection tc = target.getConnection()) {
                try {
                    String cols = String.join(",", columns);
                    String marks = columns.stream().map(c -> "?").collect(Collectors.joining(","));
                    for (Row row : rows) {
                        byte[] existing = null;
                        boolean exists = false;
                        try (var sql = tc.prepareStatement(existsSql)) {
                            sql.setQueryTimeout(60);
                            sql.setString(1, rule.tenantId); sql.setString(2, row.app()); sql.setString(3, row.name());
                            try (var rs = sql.executeQuery()) {
                                if (rs.next()) { exists = true; existing = rs.getBytes(1); }
                                if (rs.next()) throw new ToolException("Ambiguous: more than one target document matches " + row.name());
                            }
                        }
                        if (exists) {
                            String old = existing == null ? "none" : DocumentUploader.md5(existing);
                            if (old.equalsIgnoreCase(row.md5())) { LOG.info("SKIP {} / {}: target already has the same content", row.app(), row.name()); skipped++; continue; }
                            throw new ToolException("Target already has " + row.app() + " / " + row.name() + " with MD5 " + old + " (source " + row.md5() + "); use the replace rule, not the copy rule");
                        }
                        LOG.info("COPY {} / {} ({} bytes, MD5 {})", row.app(), row.name(), row.bytes().length, row.md5());
                        if (!options.apply) { copied++; continue; }
                        try (var sql = tc.prepareStatement("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")")) {
                            sql.setQueryTimeout(60);
                            for (int i = 0; i < columns.size(); i++) {
                                Object v = row.values().get(i);
                                if (v instanceof byte[] b) sql.setBytes(i + 1, b); else sql.setObject(i + 1, v);
                            }
                            if (sql.executeUpdate() != 1) throw new ToolException("Insert did not affect exactly one row: " + row.name());
                        }
                        try (var sql = tc.prepareStatement(existsSql)) {
                            sql.setQueryTimeout(60);
                            sql.setString(1, rule.tenantId); sql.setString(2, row.app()); sql.setString(3, row.name());
                            try (var rs = sql.executeQuery()) {
                                if (!rs.next() || !Arrays.equals(row.bytes(), rs.getBytes(1))) throw new ToolException("Inserted bytes failed verification: " + row.name());
                            }
                        }
                        uuids.add(row.uuid());
                        copied++;
                    }
                    if (options.apply) {
                        if (!uuids.isEmpty()) {
                            Path backups = Path.of("documents").toAbsolutePath().resolve(".backups");
                            Files.createDirectories(backups);
                            Path dir = Files.createTempDirectory(backups, "copy-");
                            String list = uuids.stream().map(u -> "'" + u + "'").collect(Collectors.joining(","));
                            Files.writeString(dir.resolve("undo.sql"), "-- Undo of " + options.templateUpload.getFileName() + " on " + rule.db + "\nBEGIN;\nDELETE FROM " + table
                                + " WHERE tenant_id='" + rule.tenantId + "' AND uuid IN (" + list + ");\n-- Check the affected row count = " + uuids.size() + ", then COMMIT; otherwise ROLLBACK.\n");
                            LOG.info("Undo script: {}", dir.resolve("undo.sql"));
                        }
                        tc.commit();
                        LOG.info("COMMITTED: {} document(s) inserted, {} already present and identical", copied, skipped);
                    } else LOG.info("PREVIEW complete: {} document(s) would be inserted, {} already present; nothing was written. Use --apply.", copied, skipped);
                } catch (Exception e) {
                    LOG.error("Document copy failed; every change of this run is rolled back.");
                    throw e;
                } finally { tc.rollback(); }
            }
            return 0;
        } catch (ToolException e) { throw e; }
        catch (Exception e) { throw new ToolException("Document copy failed", e); }
    }
}
