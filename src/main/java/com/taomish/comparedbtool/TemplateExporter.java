package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only extraction of explicitly requested rows into editable files and an upload rule. */
public final class TemplateExporter {
    private static final Logger LOG = LoggerFactory.getLogger(TemplateExporter.class);
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public static class Request {
        public String db, schema, tenantId, businessApplication, businessUnit, uploadDb;
        public List<Selection> templates;
    }
    public static class Selection {
        public String uuid, tenantId, docTemplateName, businessApplication, businessUnit;
    }
    private record Exported(Map<String,Object> row, List<Map<String,Object>> columns,
                            byte[] bytes, TemplateUploader.Rule upload) {}
    private final DatasourceSettings settings;
    private final Path root;
    public TemplateExporter(DatasourceSettings settings) { this(settings, Path.of("templates")); }
    TemplateExporter(DatasourceSettings settings, Path root) { this.settings = settings; this.root = root; }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new ToolException("Template export requires " + field);
        return value;
    }
    private DatasourceSettings.Definition datasource(String name, boolean allowProd) {
        var definition = settings.getDatasources().get(required(name, "db"));
        if (definition == null) throw new ToolException("Unknown datasource: " + name);
        if (QueryRunner.production(name, definition) && !allowProd)
            throw new ToolException("Datasource " + name + " requires --allow-prod");
        return definition;
    }
    public int execute(Cli.Options options) {
        try {
            Request request = JSON.readValue(Files.readString(options.templateExport), Request.class);
            required(request.schema, "schema");
            if (!request.schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ToolException("Invalid schema identifier");
            var definition = datasource(request.db, options.allowProd);
            if (request.uploadDb == null) request.uploadDb = request.db;
            datasource(request.uploadDb, options.allowProd);
            if (request.templates == null || request.templates.isEmpty())
                throw new ToolException("Template export needs a nonempty templates array");
            for (Selection selection : request.templates) {
                if (selection == null) throw new ToolException("Null template selection");
                if (selection.tenantId == null) selection.tenantId = request.tenantId;
                required(selection.tenantId, "tenantId");
                if (selection.uuid != null) UUID.fromString(selection.uuid);
                else {
                    required(selection.docTemplateName, "docTemplateName (or uuid)");
                    if (selection.businessApplication == null) selection.businessApplication = request.businessApplication;
                    if (selection.businessUnit == null) selection.businessUnit = request.businessUnit;
                    required(selection.businessApplication, "businessApplication");
                    selection.businessUnit = required(selection.businessUnit, "businessUnit").trim();
                }
            }
            required(definition.getUrl(), "datasource JDBC URL");
            if (options.validate) {
                LOG.info("VALID: {} template selections checked; no database connection or export directory created", request.templates.size());
                return 0;
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(definition.getUrl()); config.setUsername(definition.getUsername()); config.setPassword(definition.getPassword());
            if (definition.getDriverClassName() != null) config.setDriverClassName(definition.getDriverClassName());
            config.setReadOnly(true); config.setAutoCommit(false); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
            // All requested rows are read from one consistent transaction snapshot.
            config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
            if (definition.getUrl().startsWith("jdbc:postgresql:")) config.setConnectionInitSql("SET default_transaction_read_only = on");
            else if (definition.getUrl().startsWith("jdbc:mysql:")) config.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
            List<Exported> rows = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            try (var pool = new HikariDataSource(config); var connection = pool.getConnection()) {
                try {
                    for (Selection selection : request.templates) {
                        Exported row = read(connection, request, selection);
                        if (!identities.add(row.upload().uuid)) throw new ToolException("Same template requested more than once: " + row.upload().uuid);
                        rows.add(row);
                    }
                } finally { connection.rollback(); }
            }
            // Resolve every requested row before writing any export files. Unique folders protect local edits.
            Files.createDirectories(root);
            Path directory = Files.createTempDirectory(root, "export-");
            List<TemplateUploader.Rule> uploads = new ArrayList<>();
            for (Exported row : rows) {
                var upload = row.upload();
                Path templateDirectory = Files.createDirectory(directory.resolve(upload.uuid));
                Path file = templateDirectory.resolve("template." + upload.templateFileType);
                Files.write(file, row.bytes());
                if (!Arrays.equals(Files.readAllBytes(file), row.bytes())) throw new ToolException("Exported file verification failed");
                upload.file = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                Map<String,Object> metadata = new LinkedHashMap<>();
                metadata.put("sourceDb", request.db); metadata.put("schema", request.schema);
                metadata.put("columns", row.columns()); metadata.put("row", row.row());
                metadata.put("documentContent", Map.of("file", upload.file, "encoding", "raw-bytes",
                    "bytes", row.bytes().length, "sha256", upload.expectedSha256));
                Files.writeString(templateDirectory.resolve("row.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
                uploads.add(upload);
                LOG.info("EXPORTED name={} tenant={} BU={} UUID={} bytes={} SHA-256={} file={}", upload.docTemplateName,
                    upload.tenantId, upload.businessUnit, upload.uuid, row.bytes().length, upload.expectedSha256, file.toAbsolutePath());
            }
            var generated = new TemplateUploader.ImportRule();
            generated.db = request.uploadDb; generated.schema = request.schema; generated.templates = uploads;
            // Write the runnable rule last: an incomplete export never has an upload rule.
            Path rule = directory.resolve("upload-rule.json");
            Files.writeString(rule, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(generated));
            Files.writeString(directory.resolve("README.txt"), "Edit the template files in the UUID subdirectories.\n"
                + "row.json records every original database column (binary document_content is stored in the template file).\n"
                + "The generated upload rule updates content only, preserving row metadata except the update timestamp.\n"
                + "Preview from the project directory: .\\compare.ps1 --template-import \"" + rule.toAbsolutePath() + "\"\n"
                + "Add --apply to commit (and --allow-prod if required). Upload needs a writable datasource.\n"
                + "expectedSha256 checks the original database bytes; re-export after a successful upload before editing again.\n");
            LOG.info("EXPORTED {} templates. Edit files, then preview/apply with generated rule: {}", rows.size(), rule.toAbsolutePath());
            return 0;
        } catch (ToolException e) { throw e; }
        catch (Exception e) { throw new ToolException("Template export failed", e); }
    }
    private Exported read(Connection connection, Request request, Selection selection) throws Exception {
        String predicate = selection.uuid == null
            ? "tenant_id=? AND lower(doc_template_name)=lower(?) AND lower(business_application)=lower(?) AND lower(business_unit)=lower(?)"
            : "tenant_id=? AND uuid=?";
        try (var sql = connection.prepareStatement("SELECT * FROM " + request.schema
            + ".oga_doctemplatemaster_doctemplatemaster WHERE " + predicate)) {
            sql.setQueryTimeout(30); sql.setMaxRows(2); sql.setString(1, selection.tenantId);
            if (selection.uuid != null) sql.setObject(2, UUID.fromString(selection.uuid));
            else {
                sql.setString(2, selection.docTemplateName); sql.setString(3, selection.businessApplication); sql.setString(4, selection.businessUnit);
            }
            try (var result = sql.executeQuery()) {
                if (!result.next()) throw new ToolException("Requested template not found for tenant " + selection.tenantId + ": "
                    + Objects.toString(selection.uuid, selection.docTemplateName));
                byte[] bytes = result.getBytes("document_content");
                if (bytes == null || bytes.length == 0) throw new ToolException("Requested template has empty/null content");
                var upload = new TemplateUploader.Rule();
                upload.db = request.uploadDb; upload.schema = request.schema; upload.tenantId = result.getString("tenant_id");
                upload.uuid = UUID.fromString(result.getString("uuid")).toString();
                upload.docTemplateName = required(result.getString("doc_template_name"), "stored docTemplateName");
                upload.businessApplication = required(result.getString("business_application"), "stored businessApplication");
                upload.businessUnit = required(result.getString("business_unit"), "stored businessUnit");
                String type = required(result.getString("template_file_type"), "stored templateFileType").trim().toLowerCase(Locale.ROOT);
                if (!Set.of("html", "docx", "xlsx").contains(type)) throw new ToolException("Unsupported stored template file type: " + type);
                upload.templateFileType = type; upload.contentOnly = true; upload.expectedSha256 = TemplateUploader.hash(bytes);
                Map<String,Object> row = new LinkedHashMap<>();
                List<Map<String,Object>> columns = new ArrayList<>();
                var metadata = result.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    String name = metadata.getColumnLabel(i);
                    columns.add(Map.of("name", name, "jdbcType", metadata.getColumnType(i), "typeName", metadata.getColumnTypeName(i)));
                    if (name.equalsIgnoreCase("document_content")) {
                        row.put(name, Map.of("file", "template." + type, "encoding", "raw-bytes", "bytes", bytes.length, "sha256", upload.expectedSha256));
                    } else {
                        Object value = switch (metadata.getColumnType(i)) {
                            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                                byte[] binary = result.getBytes(i);
                                yield binary == null ? null : Map.of("encoding", "base64", "value", Base64.getEncoder().encodeToString(binary));
                            }
                            case Types.NUMERIC, Types.DECIMAL -> result.getBigDecimal(i);
                            case Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT, Types.FLOAT, Types.DOUBLE, Types.REAL,
                                 Types.BOOLEAN, Types.BIT -> result.getObject(i);
                            default -> result.getString(i);
                        };
                        row.put(name, value);
                    }
                }
                if (result.next()) throw new ToolException("Ambiguous requested template; select its UUID explicitly");
                return new Exported(row, columns, bytes, upload);
            }
        }
    }
}
