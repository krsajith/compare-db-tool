package com.taomish.comparedbtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateUploaderTests {
    @TempDir Path temp;
    Path templates, rule;
    String url;
    UUID id = UUID.randomUUID();
    DatasourceSettings settings;
    byte[] original = {0, 1, 2, (byte)255};
    byte[] replacement = {80, 75, 0, (byte)254, 13, 10};

    @BeforeEach void setup() throws Exception {
        templates = Files.createDirectory(temp.resolve("templates"));
        Files.write(templates.resolve("report.xlsx"), replacement);
        rule = temp.resolve("upload.json");
        Files.writeString(rule, """
            {"db":"test", "schema":"PUBLIC", "tenantId":"tenant", "docTemplateName":"Report",
             "businessApplication":"Opera", "businessUnit":" CFS ", "file":"report.xlsx"}
            """);
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword("");
        d.setReadOnly(false); d.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("test", d);
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("""
                CREATE TABLE oga_doctemplatemaster_doctemplatemaster (
                uuid UUID PRIMARY KEY, tenant_id VARCHAR, doc_template_name VARCHAR,
                business_application VARCHAR, business_unit VARCHAR, template_engine VARCHAR,
                template_file_name VARCHAR, template_file_type VARCHAR, allowed_output_formats VARCHAR,
                description VARCHAR, mime_type VARCHAR, document_name VARCHAR, status VARCHAR,
                document_content VARBINARY, updated_by VARCHAR, updated_timestamp TIMESTAMP)
                """);
            try (var insert = c.prepareStatement("INSERT INTO oga_doctemplatemaster_doctemplatemaster VALUES(?, 'tenant','report','opera','cfs', 'jsreport', 'old.xlsx','xlsx', '{\"selected\":[\"xlsx\"],\"default\":\"xlsx\"}', 'old description', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','old.xlsx','Active',?,'old-user',TIMESTAMP '2026-01-01 00:00:00')")) {
                insert.setObject(1, id); insert.setBytes(2, original); insert.executeUpdate();
            }
        }
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("ALTER TABLE oga_doctemplatemaster_doctemplatemaster ADD created_by VARCHAR");
            s.execute("ALTER TABLE oga_doctemplatemaster_doctemplatemaster ADD created_timestamp TIMESTAMP");
        }
    }
    Connection connect() throws Exception { return DriverManager.getConnection(url, "sa", ""); }
    int run(String... args) {
        var options = Cli.parse(args);
        return new TemplateUploader(settings, templates).execute(options);
    }
    byte[] content() throws Exception {
        try (var c = connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT document_content FROM oga_doctemplatemaster_doctemplatemaster WHERE uuid='" + id + "'")) {
            assertTrue(r.next()); return r.getBytes(1);
        }
    }
    @Test void previewDoesNotWriteAndApplyPreservesBinaryWithRestorableBackup() throws Exception {
        assertEquals(0, run("--template-upload", rule.toString()));
        assertArrayEquals(original, content()); assertFalse(Files.exists(templates.resolve(".backups")));
        assertEquals(0, run("--template-upload", rule.toString(), "--apply"));
        assertArrayEquals(replacement, content());
        Path backup;
        try (var paths = Files.list(templates.resolve(".backups"))) { backup = paths.findFirst().orElseThrow(); }
        assertArrayEquals(original, Files.readAllBytes(backup.resolve("original.bin")));
        try (var c = connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT document_name, description, allowed_output_formats FROM oga_doctemplatemaster_doctemplatemaster")) {
            r.next(); assertEquals("report.xlsx", r.getString(1)); assertEquals("old description", r.getString(2));
            assertTrue(r.getString(3).contains("xlsx"));
        }
        try (var c = connect(); var s = c.createStatement()) {
            String restore = Files.readString(backup.resolve("restore.sql")).replaceAll("(?m)^--.*$", "");
            for (String statement : restore.split(";")) {
                if (!statement.isBlank()) s.execute(statement);
            }
            c.commit();
        }
        assertArrayEquals(original, content());
    }
    @Test void rejectsAmbiguousMissingAndWrongTenantRecords() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("INSERT INTO oga_doctemplatemaster_doctemplatemaster(uuid,tenant_id,doc_template_name,business_application,business_unit) VALUES(RANDOM_UUID(),'tenant','REPORT','OPERA','CFS')");
        }
        assertThrows(ToolException.class, () -> run("--template-upload", rule.toString(), "--apply"));
        assertArrayEquals(original, content());
        Files.writeString(rule, Files.readString(rule).replace("\"tenant\"", "\"another-tenant\""));
        assertThrows(ToolException.class, () -> run("--template-upload", rule.toString(), "--apply"));
    }
    @Test void guardsDatasourceHashUuidPathAndOutputFormats() throws Exception {
        settings.getDatasources().get("test").setReadOnly(true);
        assertThrows(ToolException.class, () -> run("--template-upload", rule.toString(), "--apply"));
        settings.getDatasources().get("test").setReadOnly(false);
        settings.getDatasources().get("test").setProd(true);
        assertThrows(ToolException.class, () -> run("--template-upload", rule.toString()));
        settings.getDatasources().get("test").setProd(false);
        String valid = Files.readString(rule);
        for (String addition : List.of(
            "\"expectedSha256\":\"" + "0".repeat(64) + "\"",
            "\"uuid\":\"" + UUID.randomUUID() + "\"",
            "\"allowedOutputFormats\":{\"selected\":[\"pdf\"],\"default\":\"xlsx\"}",
            "\"unknownField\":true")) {
            Files.writeString(rule, valid.replaceFirst("\\{", "{" + addition + ","));
            assertThrows(ToolException.class, () -> run("--template-upload", rule.toString(), "--apply"));
        }
        Files.write(temp.resolve("outside.xlsx"), replacement);
        Files.writeString(rule, valid.replace("report.xlsx", "../outside.xlsx"));
        assertThrows(ToolException.class, () -> run("--template-upload", rule.toString(), "--validate"));
        assertArrayEquals(original, content());
    }
    @Test void failedUpdateRollsBackAndValidationDoesNotConnect() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("ALTER TABLE oga_doctemplatemaster_doctemplatemaster ADD CONSTRAINT reject_new_name CHECK(document_name='old.xlsx')");
        }
        assertThrows(ToolException.class, () -> run("--template-upload", rule.toString(), "--apply"));
        assertArrayEquals(original, content());
        settings.getDatasources().get("test").setUrl("jdbc:missing:never-connect");
        assertEquals(0, run("--template-upload", rule.toString(), "--validate"));
    }
    @Test void cliKeepsWriteModeSeparate() {
        assertThrows(ToolException.class, () -> Cli.parse(new String[]{"--rules", "x.xml", "--apply"}));
        assertThrows(ToolException.class, () -> Cli.parse(new String[]{"--template-upload", "x.json", "--rules", "x.xml"}));
        assertThrows(ToolException.class, () -> Cli.parse(new String[]{"--template-upload", "x.json", "--apply", "--validate"}));
    }

    void batch(String entries) throws Exception {
        Files.writeString(rule, """
            {"db":"test", "schema":"PUBLIC", "tenantId":"tenant", "businessApplication":"Opera",
             "businessUnit":"CFS", "mode":"upsert", "templates":[
            """ + entries + "]}");
    }
    String newEntry(String name) {
        return "{\"docTemplateName\":\"" + name + "\",\"file\":\"report.xlsx\","
            + "\"allowedOutputFormats\":{\"selected\":[\"xlsx\"],\"default\":\"xlsx\"}}";
    }
    int count() throws Exception {
        try (var c = connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM oga_doctemplatemaster_doctemplatemaster")) {
            r.next(); return r.getInt(1);
        }
    }
    @Test void batchPreviewsThenCreatesAndUpdatesAtomically() throws Exception {
        batch("{\"docTemplateName\":\"report\",\"file\":\"report.xlsx\"}," + newEntry("NewReport"));
        assertEquals(0, run("--template-import", rule.toString()));
        assertEquals(1, count()); assertArrayEquals(original, content());
        assertEquals(0, run("--template-import", rule.toString(), "--apply"));
        assertEquals(2, count()); assertArrayEquals(replacement, content());
        try (var c = connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT document_content,status,created_timestamp FROM oga_doctemplatemaster_doctemplatemaster WHERE doc_template_name='NewReport'")) {
            assertTrue(r.next()); assertArrayEquals(replacement, r.getBytes(1)); assertEquals("Active", r.getString(2)); assertNotNull(r.getTimestamp(3));
        }
        // Generated undo SQL removes the inserted record and restores the original record.
        try (var c = connect(); var s = c.createStatement(); var paths = Files.list(templates.resolve(".backups"))) {
            for (Path backup : paths.toList()) {
                String restore = Files.readString(backup.resolve("restore.sql")).replaceAll("(?m)^--.*$", "");
                for (String statement : restore.split(";")) if (!statement.isBlank()) s.execute(statement);
            }
            c.commit();
        }
        assertEquals(1, count()); assertArrayEquals(original, content());
    }
    @Test void failureOnLaterEntryRollsBackBothEarlierUpdatesAndInserts() throws Exception {
        batch("{\"docTemplateName\":\"report\",\"file\":\"report.xlsx\"}," + newEntry("NewReport")
            + ",{\"docTemplateName\":\"Missing\",\"file\":\"report.xlsx\",\"mode\":\"update\"}");
        assertThrows(ToolException.class, () -> run("--template-import", rule.toString(), "--apply"));
        assertEquals(1, count()); assertArrayEquals(original, content());
    }
    @Test void rejectsDuplicateEntriesEmptyBatchAndCreateOverExisting() throws Exception {
        batch(newEntry("report") + "," + newEntry("REPORT"));
        assertThrows(ToolException.class, () -> run("--template-import", rule.toString(), "--apply"));
        batch("");
        assertThrows(ToolException.class, () -> run("--template-import", rule.toString(), "--validate"));
        batch(newEntry("report").replace("\"file\"", "\"mode\":\"create\",\"file\""));
        assertThrows(ToolException.class, () -> run("--template-import", rule.toString(), "--apply"));
        assertArrayEquals(original, content());
    }
    @Test void singleTemplateCanExplicitlyCreateAndBatchValidatesAllFilesBeforeWriting() throws Exception {
        Files.writeString(rule, Files.readString(rule).replace("\"Report\"", "\"NewReport\"")
            .replace("\"file\"", "\"mode\":\"create\",\"allowedOutputFormats\":{\"selected\":[\"xlsx\"],\"default\":\"xlsx\"},\"file\""));
        assertEquals(0, run("--template-import", rule.toString(), "--apply"));
        assertEquals(2, count());
        batch("{\"docTemplateName\":\"report\",\"file\":\"report.xlsx\"}," + newEntry("MissingFile").replace("report.xlsx", "missing.xlsx"));
        assertThrows(ToolException.class, () -> run("--template-import", rule.toString(), "--apply"));
        assertArrayEquals(original, content());
    }

    Path export(String selections) throws Exception {
        Path request = temp.resolve("export.json");
        Files.writeString(request, """
            {"db":"test","schema":"PUBLIC","tenantId":"tenant","businessApplication":"Opera",
             "businessUnit":"CFS","templates":[
            """ + selections + "]}");
        new TemplateExporter(settings, templates).execute(Cli.parse(new String[]{"--template-export", request.toString()}));
        try (var paths = Files.list(templates)) {
            return paths.filter(p -> p.getFileName().toString().startsWith("export-")).findFirst().orElseThrow();
        }
    }
    @Test void exportEditUploadRoundTripPreservesEveryMetadataColumnForHtmlDocxAndXlsx() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("ALTER TABLE oga_doctemplatemaster_doctemplatemaster ADD custom_metadata VARCHAR");
            s.execute("UPDATE oga_doctemplatemaster_doctemplatemaster SET custom_metadata='extra column', description=NULL");
            for (String type : List.of("html", "docx")) {
                s.execute("INSERT INTO oga_doctemplatemaster_doctemplatemaster SELECT RANDOM_UUID(),tenant_id,'report-" + type
                    + "',business_application,business_unit,template_engine,'Original Name." + type + "','" + type
                    + "',allowed_output_formats,description,mime_type,'Download Name." + type
                    + "',status,document_content,updated_by,updated_timestamp,created_by,created_timestamp,custom_metadata FROM oga_doctemplatemaster_doctemplatemaster WHERE uuid='" + id + "'");
            }
        }
        Path directory = export("{\"docTemplateName\":\"report\"},{\"docTemplateName\":\"report-html\"},{\"docTemplateName\":\"report-docx\"}");
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var upload = json.readTree(Files.readString(directory.resolve("upload-rule.json")));
        assertEquals(3, upload.get("templates").size());
        Map<String,Map<String,String>> before = new HashMap<>();
        for (var entry : upload.get("templates")) {
            Path file = templates.resolve(entry.get("file").asText());
            assertArrayEquals(original, Files.readAllBytes(file));
            var metadata = json.readTree(Files.readString(file.getParent().resolve("row.json")));
            assertTrue(metadata.get("row").get("DESCRIPTION").isNull());
            assertEquals("extra column", metadata.get("row").get("CUSTOM_METADATA").asText());
            assertEquals("raw-bytes", metadata.get("row").get("DOCUMENT_CONTENT").get("encoding").asText());
            assertEquals(19, metadata.get("columns").size());
            String uuid = entry.get("uuid").asText();
            before.put(uuid, metadataFor(uuid));
            Files.write(file, replacement);
        }
        run("--template-import", directory.resolve("upload-rule.json").toString(), "--apply");
        for (var entry : upload.get("templates")) assertEquals(before.get(entry.get("uuid").asText()), metadataFor(entry.get("uuid").asText()));
        assertArrayEquals(replacement, content());
        assertThrows(ToolException.class, () -> run("--template-import", directory.resolve("upload-rule.json").toString(), "--apply"));
    }
    Map<String,String> metadataFor(String uuid) throws Exception {
        try (var c = connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM oga_doctemplatemaster_doctemplatemaster WHERE uuid='" + uuid + "'")) {
            r.next(); Map<String,String> values = new LinkedHashMap<>();
            for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                String column = r.getMetaData().getColumnLabel(i);
                if (!column.equals("DOCUMENT_CONTENT") && !column.equals("UPDATED_TIMESTAMP")) values.put(column, r.getString(i));
            }
            return values;
        }
    }
    @Test void uuidExportIsTenantScopedAndUploadPreservesCurrentMetadata() throws Exception {
        Path directory = export("{\"uuid\":\"" + id + "\"}");
        Files.write(directory.resolve(id.toString()).resolve("template.xlsx"), replacement);
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("UPDATE oga_doctemplatemaster_doctemplatemaster SET doc_template_name='Renamed', description='Changed while editing' WHERE uuid='" + id + "'");
        }
        run("--template-import", directory.resolve("upload-rule.json").toString(), "--apply");
        assertArrayEquals(replacement, content());
        assertEquals("Changed while editing", metadataFor(id.toString()).get("DESCRIPTION"));
        Path request = temp.resolve("export.json");
        Files.writeString(request, Files.readString(request).replace("\"tenant\"", "\"wrong-tenant\""));
        assertThrows(ToolException.class, () -> new TemplateExporter(settings, templates).execute(Cli.parse(new String[]{"--template-export", request.toString()})));
    }
    @Test void exportRejectsMissingAmbiguousAndDuplicateSelectionsWithoutPublishingRule() throws Exception {
        assertThrows(ToolException.class, () -> export("{\"docTemplateName\":\"report\"},{\"docTemplateName\":\"missing\"}"));
        assertThrows(ToolException.class, () -> export("{\"uuid\":\"" + id + "\"},{\"docTemplateName\":\"report\"}"));
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("INSERT INTO oga_doctemplatemaster_doctemplatemaster SELECT RANDOM_UUID(),tenant_id,doc_template_name,business_application,business_unit,template_engine,template_file_name,template_file_type,allowed_output_formats,description,mime_type,document_name,status,document_content,updated_by,updated_timestamp,created_by,created_timestamp FROM oga_doctemplatemaster_doctemplatemaster WHERE uuid='" + id + "'");
        }
        assertThrows(ToolException.class, () -> export("{\"docTemplateName\":\"report\"}"));
        try (var paths = Files.list(templates)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith("export-"))); }
    }
    @Test void exportValidationDoesNotConnectAndConflictingCliModesAreRejected() throws Exception {
        Path request = temp.resolve("export.json");
        Files.writeString(request, "{\"db\":\"test\",\"schema\":\"PUBLIC\",\"tenantId\":\"tenant\",\"templates\":[{\"uuid\":\"" + id + "\"}]}");
        settings.getDatasources().get("test").setUrl("jdbc:missing:never-connect");
        assertEquals(0, new TemplateExporter(settings, templates).execute(Cli.parse(new String[]{"--template-export", request.toString(), "--validate"})));
        for (String[] args : List.of(new String[]{"--template-export","x.json","--apply"},
            new String[]{"--template-export","x.json","--template-import","y.json"},
            new String[]{"--template-export","x.json","--rules","x.xml"})) {
            assertThrows(ToolException.class, () -> Cli.parse(args));
        }
    }
}
