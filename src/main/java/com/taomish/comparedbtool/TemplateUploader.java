package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;

/** Deliberately separate from the read-only comparison/query execution path. */
public final class TemplateUploader {
    private static final Logger LOG = LoggerFactory.getLogger(TemplateUploader.class);
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Map<String,String> MIME = Map.of(
        "html", "text/html", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final Set<String> FORMATS = Set.of("pdf", "html", "docx", "xlsx");
    private static final List<String> FIELDS = List.of("doc_template_name", "business_application", "business_unit",
        "template_engine", "template_file_name", "template_file_type", "allowed_output_formats", "description",
        "mime_type", "document_name", "status");

    public static class Rule {
        public String db, schema, tenantId, uuid, file, docTemplateName, businessApplication, businessUnit;
        public String templateEngine, templateFileName, templateFileType, description, mimeType, documentName, status;
        public Map<String,Object> allowedOutputFormats;
        public String updatedBy, expectedSha256;
        public String mode = "update";
        public boolean contentOnly;
    }
    public static class ImportRule {
        public String db, schema, tenantId, businessApplication, businessUnit, updatedBy;
        public String mode = "update";
        public List<Rule> templates;
    }
    private record Prepared(Rule rule, byte[] bytes, String filename) {}
    private final DatasourceSettings settings;
    private final Path templateRoot;
    public TemplateUploader(DatasourceSettings settings) { this(settings, Path.of("templates")); }
    TemplateUploader(DatasourceSettings settings, Path templateRoot) { this.settings = settings; this.templateRoot = templateRoot; }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new ToolException("Template rule requires " + name);
        return value;
    }
    private static void validateFormats(String value) {
        var node = JSON.readTree(value);
        var selected = node.get("selected");
        if (selected == null || !selected.isArray() || selected.isEmpty())
            throw new ToolException("allowedOutputFormats.selected must be a nonempty array");
        Set<String> formats = new HashSet<>();
        for (var item : selected) {
            String text = item.asText().trim().toLowerCase(Locale.ROOT);
            if (!FORMATS.contains(text)) throw new ToolException("Unsupported output format: " + text);
            formats.add(text);
        }
        var defaultNode = node.get("default");
        if (defaultNode == null || !formats.contains(defaultNode.asText().trim().toLowerCase(Locale.ROOT)))
            throw new ToolException("allowedOutputFormats.default must be selected");
    }
    public int execute(Cli.Options options) {
        try {
            String source = Files.readString(options.templateUpload);
            List<Rule> rules;
            if (JSON.readTree(source).has("templates")) {
                ImportRule batch = JSON.readValue(source, ImportRule.class);
                if (batch.templates == null || batch.templates.isEmpty()) throw new ToolException("templates must be a nonempty array");
                rules = batch.templates;
                for (Rule rule : rules) {
                    if (rule == null) throw new ToolException("Null template entry");
                    if (rule.db == null) rule.db = batch.db;
                    if (rule.schema == null) rule.schema = batch.schema;
                    if (rule.tenantId == null) rule.tenantId = batch.tenantId;
                    if (rule.businessApplication == null) rule.businessApplication = batch.businessApplication;
                    if (rule.businessUnit == null) rule.businessUnit = batch.businessUnit;
                    if (rule.updatedBy == null) rule.updatedBy = batch.updatedBy;
                }
                // Entry mode overrides the shared default only when explicitly present.
                var entries = JSON.readTree(source).get("templates");
                for (int i = 0; i < rules.size(); i++) if (!entries.get(i).has("mode")) rules.get(i).mode = batch.mode;
            } else rules = List.of(JSON.readValue(source, Rule.class));
            List<Prepared> prepared = new ArrayList<>();
            Set<List<String>> identities = new HashSet<>();
            for (Rule rule : rules) {
                Prepared item = prepare(rule, options);
                if (!rule.db.equals(rules.getFirst().db) || !rule.schema.equals(rules.getFirst().schema))
                    throw new ToolException("All templates in one import must use the same datasource and schema");
                if (!identities.add(List.of(rule.tenantId, rule.docTemplateName.toLowerCase(Locale.ROOT),
                    rule.businessApplication.toLowerCase(Locale.ROOT), rule.businessUnit.toLowerCase(Locale.ROOT))))
                    throw new ToolException("Duplicate template identity in import rule: " + rule.docTemplateName);
                prepared.add(item);
            }
            if (options.validate) { LOG.info("VALID: {} template files and rules checked; no database connection opened", prepared.size()); return 0; }
            Rule first = rules.getFirst();
            var definition = settings.getDatasources().get(first.db);
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(required(definition.getUrl(), "datasource JDBC URL"));
            config.setUsername(definition.getUsername()); config.setPassword(definition.getPassword());
            if (definition.getDriverClassName() != null) config.setDriverClassName(definition.getDriverClassName());
            config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setAutoCommit(false);
            config.setReadOnly(!options.apply);
            try (var pool = new HikariDataSource(config); Connection connection = pool.getConnection()) {
                try {
                    for (Prepared item : prepared) replace(connection, item.rule(), item.bytes(), item.filename(), options);
                    if (options.apply) {
                        connection.commit();
                        LOG.info("COMMITTED import: {} templates. Service cache expires per jsreportTemplateCacheSeconds (default 60s).", prepared.size());
                    } else LOG.info("PREVIEW complete: {} templates; no rows changed", prepared.size());
                } catch (Exception e) {
                    LOG.error("Import failed; all changes in this import are rolled back. Backup files may remain for attempted operations.");
                    throw e;
                } finally { connection.rollback(); }
            }
            return 0;
        } catch (ToolException e) { throw e; }
        catch (Exception e) { throw new ToolException("Template upload failed", e); }
    }

    private Prepared prepare(Rule rule, Cli.Options options) throws Exception {
            if (rule.mode == null || !Set.of("update", "create", "upsert").contains(rule.mode))
                throw new ToolException("Template mode must be update, create or upsert");
            required(rule.db, "db"); required(rule.schema, "schema"); required(rule.tenantId, "tenantId");
            required(rule.docTemplateName, "docTemplateName"); required(rule.businessApplication, "businessApplication");
            rule.businessUnit = required(rule.businessUnit, "businessUnit").trim();
            if (!rule.schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ToolException("Invalid schema identifier");
            if (rule.uuid != null) UUID.fromString(rule.uuid);
            if (rule.expectedSha256 != null && !rule.expectedSha256.matches("(?i)[0-9a-f]{64}"))
                throw new ToolException("expectedSha256 must contain 64 hexadecimal characters");
            var definition = settings.getDatasources().get(rule.db);
            if (definition == null) throw new ToolException("Unknown datasource: " + rule.db);
            if (QueryRunner.production(rule.db, definition) && !options.allowProd)
                throw new ToolException("Datasource " + rule.db + " requires --allow-prod");
            if (options.apply && definition.isReadOnly())
                throw new ToolException("Template writes require a dedicated datasource with read-only=false");
            Path root = templateRoot.toRealPath();
            Path input = root.resolve(required(rule.file, "file")).normalize().toRealPath();
            if (!input.startsWith(root) || !Files.isRegularFile(input))
                throw new ToolException("Template file must be inside the project's templates directory");
            byte[] bytes = Files.readAllBytes(input);
            if (bytes.length == 0) throw new ToolException("Template file is empty");
            String filename = input.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            String type = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!MIME.containsKey(type)) throw new ToolException("Supported template files: HTML, DOCX, XLSX");
            if (rule.templateFileType != null && !type.equalsIgnoreCase(rule.templateFileType.trim()))
                throw new ToolException("templateFileType must match the file extension");
            rule.templateFileType = type;
            rule.templateEngine = rule.templateEngine == null ? "jsreport" : rule.templateEngine;
            if (!"jsreport".equalsIgnoreCase(rule.templateEngine.trim())) throw new ToolException("Only jsreport is supported");
            if (rule.mimeType == null || rule.mimeType.isBlank() || rule.mimeType.equalsIgnoreCase(type)
                || rule.mimeType.equalsIgnoreCase("application/octet-stream")) rule.mimeType = MIME.get(type);
            if (!MIME.get(type).equalsIgnoreCase(rule.mimeType.trim())) throw new ToolException("MIME type does not match file type");
            // Persist canonical MIME even if a legacy alias or mixed casing was supplied.
            rule.mimeType = MIME.get(type);
            if (rule.allowedOutputFormats != null) validateFormats(JSON.writeValueAsString(rule.allowedOutputFormats));
            LOG.info("Template {} datasource={} schema={} tenant={} BU={} file={} bytes={} SHA-256={} mode={}",
                rule.docTemplateName, rule.db, rule.schema, rule.tenantId, rule.businessUnit, filename,
                bytes.length, hash(bytes), options.validate ? "VALIDATE" : options.apply ? "APPLY" : "PREVIEW");
            return new Prepared(rule, bytes, filename);
    }

    private void replace(Connection connection, Rule rule, byte[] bytes, String filename, Cli.Options options) throws Exception {
        String table = rule.schema + ".oga_doctemplatemaster_doctemplatemaster";
        boolean byUuid = rule.contentOnly && rule.uuid != null;
        String predicate = byUuid ? "tenant_id=? AND uuid=?"
            : "tenant_id=? AND lower(doc_template_name)=lower(?) AND lower(business_application)=lower(?) AND lower(business_unit)=lower(?)";
        Object uuid;
        String oldUpdatedBy, oldUpdatedTimestamp;
        byte[] oldBytes;
        Map<String,String> old = new LinkedHashMap<>();
        try (var sql = connection.prepareStatement("SELECT * FROM " + table + " WHERE " + predicate + (options.apply ? " FOR UPDATE" : ""))) {
            sql.setQueryTimeout(30);
            sql.setString(1, rule.tenantId);
            if (byUuid) sql.setObject(2, UUID.fromString(rule.uuid));
            else {
                sql.setString(2, rule.docTemplateName);
                sql.setString(3, rule.businessApplication); sql.setString(4, rule.businessUnit);
            }
            try (var result = sql.executeQuery()) {
                if (!result.next()) {
                    if (rule.mode.equals("update")) throw new ToolException("Template does not exist; use mode=create or upsert to import a new template");
                    create(connection, rule, bytes, filename, options);
                    return;
                }
                if (rule.mode.equals("create")) throw new ToolException("Template already exists; mode=create refuses replacement");
                uuid = result.getObject("uuid"); oldBytes = result.getBytes("document_content");
                oldUpdatedBy = result.getString("updated_by"); oldUpdatedTimestamp = result.getString("updated_timestamp");
                for (String field : FIELDS) old.put(field, result.getString(field));
                if (result.next()) throw new ToolException("Ambiguous template identity: multiple records match (including inactive records)");
            }
        }
        if (rule.uuid != null && !rule.uuid.equalsIgnoreCase(uuid.toString())) throw new ToolException("Template UUID does not match rule");
        if (oldBytes == null) throw new ToolException("Existing template has null content; cannot create a restorable backup");
        if (rule.expectedSha256 != null && !rule.expectedSha256.equalsIgnoreCase(hash(oldBytes)))
            throw new ToolException("Existing template SHA-256 differs from expectedSha256; replacement refused");
        Map<String,String> next = new LinkedHashMap<>(old);
        if (rule.contentOnly && !rule.templateFileType.equalsIgnoreCase(Objects.toString(old.get("template_file_type"), "").trim()))
            throw new ToolException("Content-only upload must keep the stored template file type");
        if (!rule.contentOnly) {
        next.put("template_engine", rule.templateEngine); next.put("template_file_type", rule.templateFileType);
        next.put("mime_type", rule.mimeType);
        next.put("document_name", rule.documentName == null ? filename : rule.documentName);
        next.put("template_file_name", rule.templateFileName == null ? filename : rule.templateFileName);
        if (rule.description != null) next.put("description", rule.description);
        if (rule.status != null) next.put("status", rule.status);
        if (rule.allowedOutputFormats != null) next.put("allowed_output_formats", JSON.writeValueAsString(rule.allowedOutputFormats));
        validateFormats(required(next.get("allowed_output_formats"), "allowedOutputFormats (existing or rule)"));
        }
        LOG.info("Matched UUID={} existing SHA-256={} new SHA-256={} type={} status={}",
            uuid, hash(oldBytes), hash(bytes), rule.templateFileType, next.get("status"));
        if (!options.apply) { LOG.info("PREVIEW complete: no rows changed. Use --apply to commit this replacement."); return; }
        Path backups = templateRoot.resolve(".backups");
        Files.createDirectories(backups);
        Path backup = Files.createTempDirectory(backups, "template-" + uuid + "-");
        Files.write(backup.resolve("original.bin"), oldBytes);
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("db", rule.db); snapshot.put("schema", rule.schema); snapshot.put("tenantId", rule.tenantId);
        snapshot.put("uuid", uuid.toString()); snapshot.put("metadata", old);
        snapshot.put("originalSha256", hash(oldBytes)); snapshot.put("replacementSha256", hash(bytes));
        snapshot.put("updatedBy", oldUpdatedBy); snapshot.put("updatedTimestamp", oldUpdatedTimestamp);
        Files.writeString(backup.resolve("backup.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
        // Parameterized SQL rollback restores nullable metadata and original bytes exactly.
        // A portable SQL file avoids text/base64 conversion of the binary payload.
        String product = connection.getMetaData().getDatabaseProductName();
        StringBuilder rollback = new StringBuilder("-- Run in the same database. Verify exactly one row updated before COMMIT.\nBEGIN;\nUPDATE " + table + " SET ");
        for (String field : FIELDS) rollback.append(field).append("=").append(literal(old.get(field), product)).append(", ");
        rollback.append("updated_by=").append(literal(oldUpdatedBy, product)).append(", updated_timestamp=")
            .append(literal(oldUpdatedTimestamp, product)).append(", ");
        String hex = HexFormat.of().formatHex(oldBytes);
        rollback.append("document_content=").append(product.equals("PostgreSQL") ? "decode('" + hex + "','hex')" : "X'" + hex + "'")
            .append(" WHERE uuid=").append(literal(uuid.toString(), product)).append(" AND tenant_id=").append(literal(rule.tenantId, product))
            .append(" AND document_content=").append(product.equals("PostgreSQL") ? "decode('" + HexFormat.of().formatHex(bytes) + "','hex')" : "X'" + HexFormat.of().formatHex(bytes) + "'")
            .append(";\n-- Check affected row count = 1, then execute COMMIT; otherwise ROLLBACK.\n");
        Files.writeString(backup.resolve("restore.sql"), rollback.toString());
        LOG.info("Backup and rollback SQL: {}", backup.toAbsolutePath());
        List<String> updatedFields = rule.contentOnly ? List.of() : FIELDS;
        String assignments = updatedFields.isEmpty() ? "" : String.join(",", updatedFields.stream().map(f -> f + "=?").toList()) + ",";
        try (var sql = connection.prepareStatement("UPDATE " + table + " SET " + assignments
            + "document_content=?,updated_timestamp=CURRENT_TIMESTAMP" + (rule.updatedBy == null ? "" : ",updated_by=?")
            + " WHERE uuid=? AND tenant_id=?")) {
            sql.setQueryTimeout(30);
            int i = 1;
            for (String field : updatedFields) sql.setString(i++, next.get(field));
            sql.setBytes(i++, bytes);
            if (rule.updatedBy != null) sql.setString(i++, rule.updatedBy);
            sql.setObject(i++, uuid); sql.setString(i, rule.tenantId);
            if (sql.executeUpdate() != 1) throw new ToolException("Replacement did not affect exactly one row");
        }
        try (var sql = connection.prepareStatement("SELECT document_content FROM " + table + " WHERE uuid=? AND tenant_id=?")) {
            sql.setQueryTimeout(30); sql.setObject(1, uuid); sql.setString(2, rule.tenantId);
            try (var result = sql.executeQuery()) {
                if (!result.next() || !Arrays.equals(bytes, result.getBytes(1))) throw new ToolException("Stored template bytes failed verification");
            }
        }
        LOG.info("STAGED update UUID={} bytes={} SHA-256={}", uuid, bytes.length, hash(bytes));
    }
    private void create(Connection connection, Rule rule, byte[] bytes, String filename, Cli.Options options) throws Exception {
        if (rule.contentOnly) throw new ToolException("contentOnly requires an existing template");
        if (rule.uuid != null || rule.expectedSha256 != null)
            throw new ToolException("uuid and expectedSha256 require an existing template; cannot create this entry");
        validateFormats(required(rule.allowedOutputFormats == null ? null : JSON.writeValueAsString(rule.allowedOutputFormats),
            "allowedOutputFormats for a new template"));
        UUID uuid = UUID.randomUUID();
        String table = rule.schema + ".oga_doctemplatemaster_doctemplatemaster";
        LOG.info("{} new template={} tenant={} BU={} UUID={}", options.apply ? "STAGING" : "PREVIEW CREATE",
            rule.docTemplateName, rule.tenantId, rule.businessUnit, uuid);
        if (!options.apply) return;
        Path backups = templateRoot.resolve(".backups");
        Files.createDirectories(backups);
        Path backup = Files.createTempDirectory(backups, "create-" + uuid + "-");
        Files.writeString(backup.resolve("import.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rule));
        String product = connection.getMetaData().getDatabaseProductName();
        String hex = HexFormat.of().formatHex(bytes);
        String binary = product.equals("PostgreSQL") ? "decode('" + hex + "','hex')" : "X'" + hex + "'";
        Files.writeString(backup.resolve("restore.sql"), "-- Undo this insert only if its content is unchanged. Verify one affected row.\nBEGIN;\nDELETE FROM "
            + table + " WHERE uuid=" + literal(uuid.toString(), product) + " AND tenant_id=" + literal(rule.tenantId, product)
            + " AND document_content=" + binary + ";\n-- Check affected row count = 1, then execute COMMIT; otherwise ROLLBACK.\n");
        LOG.info("Insert rollback SQL: {}", backup.toAbsolutePath());
        String columns = String.join(",", FIELDS);
        try (var sql = connection.prepareStatement("INSERT INTO " + table + " (" + columns
            + ",document_content,uuid,tenant_id,created_by,created_timestamp,updated_by,updated_timestamp) VALUES ("
            + String.join(",", Collections.nCopies(FIELDS.size(), "?")) + ",?,?,?,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP)")) {
            sql.setQueryTimeout(30);
            List<String> values = Arrays.asList(rule.docTemplateName, rule.businessApplication, rule.businessUnit,
                rule.templateEngine, rule.templateFileName == null ? filename : rule.templateFileName,
                rule.templateFileType, JSON.writeValueAsString(rule.allowedOutputFormats), rule.description,
                rule.mimeType, rule.documentName == null ? filename : rule.documentName, rule.status == null ? "Active" : rule.status);
            int i = 1;
            for (String value : values) sql.setString(i++, value);
            sql.setBytes(i++, bytes); sql.setObject(i++, uuid); sql.setString(i++, rule.tenantId);
            sql.setString(i++, rule.updatedBy); sql.setString(i, rule.updatedBy);
            if (sql.executeUpdate() != 1) throw new ToolException("Import did not insert exactly one row");
        }
        try (var sql = connection.prepareStatement("SELECT document_content FROM " + table + " WHERE uuid=? AND tenant_id=?")) {
            sql.setQueryTimeout(30); sql.setObject(1, uuid); sql.setString(2, rule.tenantId);
            try (var result = sql.executeQuery()) {
                if (!result.next() || !Arrays.equals(bytes, result.getBytes(1))) throw new ToolException("Inserted template bytes failed verification");
            }
        }
        LOG.info("STAGED insert UUID={} bytes={} SHA-256={}", uuid, bytes.length, hash(bytes));
    }
    private static String literal(String value, String product) {
        if (value == null) return "NULL";
        String quoted = "'" + value.replace("'", "''") + "'";
        if (product.equals("MySQL") || product.equals("MariaDB"))
            return "CONVERT(X'" + HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "' USING utf8mb4)";
        if (product.equals("PostgreSQL")) return "E" + quoted.replace("\\", "\\\\");
        return quoted;
    }
}
