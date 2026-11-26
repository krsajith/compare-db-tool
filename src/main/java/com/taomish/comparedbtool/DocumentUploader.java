package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces stored files (for example the Web XTR master Excel files) in opera.oga_document_repository with files from disk.
 * Reached through --template-upload / --template-import when the rule JSON has a "documents" list (see Cli.execute).
 * The bytes never travel inside the rule: the rule names a file under the project's documents folder.
 * Per document: the row is found by tenant, business application and document name; it must be stored in the database
 * (storage_type DB); its current MD5 must equal expectedOldMd5 (refuses otherwise); on --apply the old bytes are saved to
 * documents/.backups/doc-... with an undo rule (undo.json, run it the same way); the new bytes are written with a bound
 * parameter, then read back and compared. Preview by default (nothing is written), --apply commits, production datasources
 * also need --allow-prod. All documents of one rule are one transaction.
 */
public final class DocumentUploader {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentUploader.class);
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public static class Doc {
        /** document_name as stored. */
        public String documentName;
        /** File under the documents folder, for example xoga750/XTRFills_Broker_CurrentUser_Master.xlsx. */
        public String file;
        /** MD5 (32 hex) the stored document must have now; the replacement is refused otherwise. */
        public String expectedOldMd5;
    }

    public static class Rule {
        public String db, tenantId, businessApplication;
        public String schema = "opera";
        public List<Doc> documents;
    }

    private record Prepared(Doc doc, byte[] bytes) {}

    private final DatasourceSettings settings;
    private final Path root;

    public DocumentUploader(DatasourceSettings settings) { this(settings, Path.of("documents")); }
    DocumentUploader(DatasourceSettings settings, Path root) { this.settings = settings; this.root = root; }

    /** True when the rule file is a document rule (has a "documents" list). */
    static boolean isDocumentRule(String json) {
        return JSON.readTree(json).has("documents");
    }

    static String md5(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new ToolException("Document rule requires " + name);
        return value;
    }

    public int execute(Cli.Options options) {
        try {
            Rule rule = JSON.readValue(Files.readString(options.templateUpload), Rule.class);
            required(rule.db, "db"); required(rule.tenantId, "tenantId"); required(rule.businessApplication, "businessApplication");
            if (rule.schema == null || !rule.schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ToolException("Invalid schema identifier");
            if (rule.documents == null || rule.documents.isEmpty()) throw new ToolException("documents must be a nonempty list");
            Files.createDirectories(root);
            Path realRoot = root.toRealPath();
            List<Prepared> prepared = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Doc doc : rule.documents) {
                if (doc == null) throw new ToolException("Null document entry");
                required(doc.documentName, "documentName"); required(doc.file, "file"); required(doc.expectedOldMd5, "expectedOldMd5");
                if (!doc.expectedOldMd5.matches("(?i)[0-9a-f]{32}")) throw new ToolException("expectedOldMd5 must be 32 hexadecimal characters");
                if (!names.add(doc.documentName.toLowerCase())) throw new ToolException("Duplicate document in rule: " + doc.documentName);
                Path input = realRoot.resolve(doc.file).normalize().toRealPath();
                if (!input.startsWith(realRoot) || !Files.isRegularFile(input))
                    throw new ToolException("File must be inside the project's documents folder: " + doc.file);
                byte[] bytes = Files.readAllBytes(input);
                if (bytes.length == 0) throw new ToolException("File is empty: " + doc.file);
                LOG.info("Document {} file={} bytes={} MD5={} expected current MD5={}", doc.documentName, doc.file, bytes.length, md5(bytes), doc.expectedOldMd5);
                prepared.add(new Prepared(doc, bytes));
            }
            if (options.validate) { LOG.info("VALID: {} document files and rule checked; no database connection opened", prepared.size()); return 0; }

            var definition = settings.getDatasources().get(rule.db);
            if (definition == null) throw new ToolException("Unknown datasource: " + rule.db);
            if (QueryRunner.production(rule.db, definition) && !options.allowProd)
                throw new ToolException("Datasource " + rule.db + " requires --allow-prod");
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(required(definition.getUrl(), "datasource JDBC URL"));
            config.setUsername(definition.getUsername()); config.setPassword(definition.getPassword());
            if (definition.getDriverClassName() != null) config.setDriverClassName(definition.getDriverClassName());
            config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setAutoCommit(false);
            config.setConnectionTimeout(30_000); config.setInitializationFailTimeout(-1);
            config.setReadOnly(!options.apply);
            try (var pool = new HikariDataSource(config); Connection connection = pool.getConnection()) {
                try {
                    for (Prepared item : prepared) replace(connection, rule, item, options.apply, realRoot);
                    if (options.apply) {
                        connection.commit();
                        LOG.info("COMMITTED: {} document(s) replaced. Undo rule(s) are under {}", prepared.size(), realRoot.resolve(".backups"));
                    } else LOG.info("PREVIEW complete: {} document(s) checked; nothing was written. Use --apply to replace them.", prepared.size());
                } catch (Exception e) {
                    LOG.error("Document upload failed; every change of this run is rolled back.");
                    throw e;
                } finally { connection.rollback(); }
            }
            return 0;
        } catch (ToolException e) { throw e; }
        catch (Exception e) { throw new ToolException("Document upload failed", e); }
    }

    private void replace(Connection connection, Rule rule, Prepared item, boolean apply, Path realRoot) throws Exception {
        String table = rule.schema + ".oga_document_repository";
        String predicate = "tenant_id=? AND lower(business_application_name)=lower(?) AND document_name=?";
        Doc doc = item.doc();
        byte[] oldBytes;
        String storageType;
        try (var sql = connection.prepareStatement("SELECT document, storage_type FROM " + table + " WHERE " + predicate + (apply ? " FOR UPDATE" : ""))) {
            sql.setQueryTimeout(60);
            sql.setString(1, rule.tenantId); sql.setString(2, rule.businessApplication); sql.setString(3, doc.documentName);
            try (var result = sql.executeQuery()) {
                if (!result.next()) throw new ToolException("Document not found: " + doc.documentName + " (tenant, business application and name must match)");
                oldBytes = result.getBytes(1);
                storageType = result.getString(2);
                if (result.next()) throw new ToolException("Ambiguous: more than one stored document matches " + doc.documentName);
            }
        }
        if (storageType == null || !storageType.equalsIgnoreCase("DB"))
            throw new ToolException("Document " + doc.documentName + " is stored as " + storageType + ", not in the database; this tool only replaces DB-stored documents");
        if (oldBytes == null) throw new ToolException("Stored document has no content: " + doc.documentName);
        String oldMd5 = md5(oldBytes);
        if (!oldMd5.equalsIgnoreCase(doc.expectedOldMd5))
            throw new ToolException("Stored " + doc.documentName + " has MD5 " + oldMd5 + ", expected " + doc.expectedOldMd5 + "; replacement refused");
        String newMd5 = md5(item.bytes());
        LOG.info("{} stored MD5 {} ({} bytes) -> new MD5 {} ({} bytes){}", doc.documentName, oldMd5, oldBytes.length, newMd5, item.bytes().length,
            oldMd5.equalsIgnoreCase(newMd5) ? "  (identical content)" : "");
        if (!apply) return;

        Path backups = realRoot.resolve(".backups");
        Files.createDirectories(backups);
        Path backup = Files.createTempDirectory(backups, "doc-");
        Files.write(backup.resolve("original.bin"), oldBytes);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("documentName", doc.documentName);
        entry.put("file", ".backups/" + backup.getFileName() + "/original.bin");
        entry.put("expectedOldMd5", newMd5);
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("db", rule.db); undo.put("schema", rule.schema); undo.put("tenantId", rule.tenantId);
        undo.put("businessApplication", rule.businessApplication); undo.put("documents", List.of(entry));
        Files.writeString(backup.resolve("undo.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(undo));
        LOG.info("Backup of the old file and undo rule: {}", backup);

        try (var sql = connection.prepareStatement("UPDATE " + table + " SET document=?, size_bytes=?, updated_timestamp=CURRENT_TIMESTAMP WHERE " + predicate)) {
            sql.setQueryTimeout(60);
            sql.setBytes(1, item.bytes());
            sql.setLong(2, item.bytes().length);
            sql.setString(3, rule.tenantId); sql.setString(4, rule.businessApplication); sql.setString(5, doc.documentName);
            if (sql.executeUpdate() != 1) throw new ToolException("Replacement did not affect exactly one row: " + doc.documentName);
        }
        try (var sql = connection.prepareStatement("SELECT document FROM " + table + " WHERE " + predicate)) {
            sql.setQueryTimeout(60);
            sql.setString(1, rule.tenantId); sql.setString(2, rule.businessApplication); sql.setString(3, doc.documentName);
            try (var result = sql.executeQuery()) {
                if (!result.next() || !Arrays.equals(item.bytes(), result.getBytes(1))) throw new ToolException("Stored bytes failed verification: " + doc.documentName);
            }
        }
        LOG.info("STAGED {} new MD5 {} verified by read-back", doc.documentName, newMd5);
    }
}
