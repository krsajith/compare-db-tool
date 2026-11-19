package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LiquibaseRunnerTests {
    @TempDir Path tmp;
    DatasourceSettings settings;
    String url;
    Path yaml;

    @BeforeEach void setup() throws Exception {
        settings = new DatasourceSettings();
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var d = new DatasourceSettings.Definition();
        d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", d);
        yaml = tmp.resolve("project with spaces/src/main/resources/db/changelog/master.yaml");
        Files.createDirectories(yaml.getParent().resolve("tables"));
        Files.writeString(yaml, """
            databaseChangeLog:
              - include:
                  file: db/changelog/tables/one.xml
            """);
        Files.writeString(yaml.getParent().resolve("tables/one.xml"), """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.31.xsd">
              <changeSet id="one" author="test"><sqlFile path="one.sql" relativeToChangelogFile="true"/></changeSet>
            </databaseChangeLog>
            """);
        Files.writeString(yaml.getParent().resolve("tables/one.sql"), "CREATE TABLE example_table (id INT);");
    }

    Cli.Options options(String... flags) {
        var args = new ArrayList<>(List.of("--liquibase", yaml.toString(), "--target-env", "test", "--out", tmp.resolve("out").toString()));
        args.addAll(List.of(flags));
        return Cli.parse(args.toArray(String[]::new));
    }
    int run(String... flags) throws Exception { return new Cli(settings).execute(options(flags)); }
    String query(String sql) throws Exception {
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next(); return rs.getString(1);
        }
    }
    List<Path> outputs(String name) throws Exception {
        try (var stream = Files.walk(tmp.resolve("out"))) { return stream.filter(p -> p.getFileName().toString().equals(name)).toList(); }
    }
    @Test void fullPathResolvesResourceRootAndKeepsClasspathIdentity() {
        var source = LiquibaseRunner.Source.resolve(yaml);
        assertThat(source.changelog()).isEqualTo("db/changelog/master.yaml");
        assertThat(source.root()).isEqualTo(yaml.getParent().getParent().getParent());
    }
    @Test void validationNeverConnectsAndResolvesSqlFiles() throws Exception {
        settings.getDatasources().get("test").setUrl("jdbc:invalid:must-not-connect");
        assertThat(run("--validate")).isZero();
        assertThat(Files.readString(outputs("SUMMARY.md").getFirst())).contains("Status: VALID");
        Files.delete(yaml.getParent().resolve("tables/one.sql"));
        assertThatThrownBy(() -> run("--validate")).isInstanceOf(ToolException.class);
    }
    @Test void statusAndPreviewLeaveFreshDatabaseUntouched() throws Exception {
        assertThat(run("--status")).isZero();
        assertThat(query("SELECT COALESCE(LISTAGG(TABLE_NAME, ','), '') FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'")).isEmpty();
        assertThat(run()).isZero();
        assertThat(query("SELECT COALESCE(LISTAGG(TABLE_NAME, ','), '') FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'")).isEmpty();
        assertThat(Files.readString(outputs("PREVIEW.sql").getFirst())).containsIgnoringCase("CREATE TABLE example_table")
            .doesNotContain("-- Connection:").doesNotContain(url);
        assertThat(Files.readString(outputs("PENDING.md").getFirst())).contains("db/changelog/tables/one.xml::one::test");
    }
    @Test void applyTracksOriginalIdentityAndSecondRunDoesNotReapply() throws Exception {
        assertThat(run("--apply")).isZero();
        assertThat(query("SELECT FILENAME FROM DATABASECHANGELOG")).isEqualTo("db/changelog/tables/one.xml");
        assertThat(run("--apply")).isZero();
        assertThat(query("SELECT COUNT(*) FROM DATABASECHANGELOG")).isEqualTo("1");
        assertThat(run()).isZero();
        assertThat(Files.readString(outputs("PREVIEW.sql").getFirst())).doesNotContain("CREATE TABLE example_table");
        assertThat(outputs("RESULTS.md").stream().map(p -> {
            try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList()).anySatisfy(s -> assertThat(s).contains("EXECUTED").contains(" ms"));
    }
    @Test void schemaControlsBothTrackingAndDefaultObjects() throws Exception {
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) { s.execute("CREATE SCHEMA opera"); }
        assertThat(run("--apply", "--schema", "OPERA")).isZero();
        assertThat(query("SELECT COUNT(*) FROM OPERA.DATABASECHANGELOG")).isEqualTo("1");
        assertThat(query("SELECT COUNT(*) FROM OPERA.EXAMPLE_TABLE")).isEqualTo("0");
    }
    @Test void gatesAreCheckedBeforeAnyConnection() throws Exception {
        var d = settings.getDatasources().get("test");
        d.setUrl("jdbc:invalid:must-not-connect"); d.setProd(true);
        assertThatThrownBy(() -> run()).hasMessageContaining("--allow-prod");
        d.setProd(false); d.setAllowWrite(false);
        assertThatThrownBy(() -> run("--apply")).hasMessageContaining("allow-write=false");
    }
    @Test void missingIncludeAndInvalidXmlFailOffline() throws Exception {
        Files.writeString(yaml, "databaseChangeLog:\n  - include:\n      file: missing.xml\n");
        assertThatThrownBy(() -> run("--validate")).isInstanceOf(ToolException.class);
        Files.writeString(yaml, "databaseChangeLog:\n  - include:\n      file: db/changelog/tables/one.xml\n");
        Files.writeString(yaml.getParent().resolve("tables/one.xml"), "<broken");
        assertThatThrownBy(() -> run("--validate")).isInstanceOf(ToolException.class);
    }
    @Test void failurePreservesExecutionReportAndDoesNotClaimRollback() throws Exception {
        Files.writeString(yaml.getParent().resolve("tables/one.sql"), "SELECT * FROM missing_table;");
        assertThatThrownBy(() -> run("--apply")).isInstanceOf(ToolException.class);
        assertThat(Files.readString(outputs("SUMMARY.md").getFirst())).contains("Status: FAILED").contains("no automatic whole-run rollback");
        assertThat(Files.readString(outputs("RESULTS.md").getFirst())).contains("FAILED");
    }
    @Test void incompatibleModesAreRejected() {
        for (String[] flags : List.of(new String[]{"--apply", "--status"}, new String[]{"--validate", "--apply"},
            new String[]{"--validate", "--status"}, new String[]{"--rules", "x.xml"}, new String[]{"--change", "x.json"},
            new String[]{"--check"}, new String[]{"--menu"}, new String[]{"--db", "test"}))
            assertThatThrownBy(() -> options(flags)).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> Cli.parse(new String[]{"--status"})).hasMessageContaining("--liquibase");
    }
    @Test void fallbackUsesYamlDirectoryForNonstandardProject() throws Exception {
        Path standalone = tmp.resolve("other/master.yaml"); Files.createDirectories(standalone.getParent());
        Files.writeString(standalone, "databaseChangeLog: []\n");
        var source = LiquibaseRunner.Source.resolve(standalone);
        assertThat(source.root()).isEqualTo(standalone.getParent());
        assertThat(source.changelog()).isEqualTo("master.yaml");
    }

    @Test void statusAndPreviewDoNotMutateExistingHistoryAndChecksumMismatchFails() throws Exception {
        assertThat(run("--apply")).isZero();
        String before = query("SELECT MD5SUM FROM DATABASECHANGELOG");
        assertThat(run("--status")).isZero();
        assertThat(run()).isZero();
        assertThat(query("SELECT MD5SUM FROM DATABASECHANGELOG")).isEqualTo(before);
        Files.writeString(yaml.getParent().resolve("tables/one.sql"), "CREATE TABLE different_table (id INT);");
        assertThatThrownBy(() -> run("--status")).hasMessageContaining("check sum");
        assertThatThrownBy(() -> run()).hasMessageContaining("check sum");
        assertThat(query("SELECT MD5SUM FROM DATABASECHANGELOG")).isEqualTo(before);
    }

    @Test void preconditionsAreNotEvaluatedOfflineAndMarkRanIsRespectedAtApply() throws Exception {
        Path xml = yaml.getParent().resolve("tables/one.xml");
        String original = Files.readString(xml);
        Files.writeString(xml, original.replace("<changeSet id=\"one\" author=\"test\">", """
            <preConditions><sqlCheck expectedResult="1">SELECT 1</sqlCheck></preConditions>
            <changeSet id="one" author="test">
              <preConditions onFail="MARK_RAN"><sqlCheck expectedResult="0">SELECT 1</sqlCheck></preConditions>
            """));
        assertThat(run("--validate")).isZero();
        assertThat(run()).isZero();
        assertThat(query("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'")).isEqualTo("0");
        assertThat(run("--apply")).isZero();
        assertThat(query("SELECT EXECTYPE FROM DATABASECHANGELOG")).isEqualTo("MARK_RAN");
        assertThat(query("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='EXAMPLE_TABLE'")).isEqualTo("0");
    }

    @Test void runAlwaysRunOnChangeAndNontransactionalChangesUseLiquibaseSemantics() throws Exception {
        Path xml = yaml.getParent().resolve("tables/one.xml");
        String original = Files.readString(xml);
        String extra = """
              <changeSet id="always" author="test" runAlways="true" runInTransaction="false"><sql>INSERT INTO example_table VALUES (9)</sql></changeSet>
              <changeSet id="onchange" author="test" runOnChange="true"><sql>INSERT INTO example_table VALUES (1)</sql></changeSet>
            </databaseChangeLog>
            """;
        Files.writeString(xml, original.replace("</databaseChangeLog>", extra));
        assertThat(run("--apply")).isZero();
        assertThat(run("--apply")).isZero();
        assertThat(query("SELECT COUNT(*) FROM example_table WHERE id=9")).isEqualTo("2");
        assertThat(query("SELECT COUNT(*) FROM example_table WHERE id=1")).isEqualTo("1");
        Files.writeString(xml, Files.readString(xml).replace("VALUES (1)", "VALUES (2)"));
        assertThat(run("--status")).isZero();
        assertThat(run()).isZero();
        assertThat(query("SELECT COUNT(*) FROM example_table")).isEqualTo("3");
        assertThat(run("--apply")).isZero();
        assertThat(query("SELECT COUNT(*) FROM example_table WHERE id=2")).isEqualTo("1");
        assertThat(query("SELECT EXECTYPE FROM DATABASECHANGELOG WHERE id='onchange'")).isEqualTo("RERAN");
    }
}
