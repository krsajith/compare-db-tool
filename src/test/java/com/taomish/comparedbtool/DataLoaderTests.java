package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataLoaderTests {
    @TempDir Path tmp;
    String sourceUrl, targetUrl;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        sourceUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        targetUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(sourceUrl, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA legacy");
            s.execute("CREATE TABLE legacy.mult (code VARCHAR(10), value_date DATE, curr_mult DOUBLE PRECISION, lot_mult DOUBLE PRECISION)");
            // two rows for 2026-11-03 (one with an empty lot_mult) and one for 2026-11-05
            s.execute("INSERT INTO legacy.mult VALUES ('CM AC', DATE '2026-11-03', 0.15045286311798514, 10.0),"
                + "('CM AE', DATE '2026-11-03', 0.25, NULL), ('CM AC', DATE '2026-11-05', 0.3, 10.0)");
        }
        try (Connection c = DriverManager.getConnection(targetUrl, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA live"); s.execute("CREATE SCHEMA parity_work");
            s.execute("CREATE TABLE live.mult (uuid VARCHAR(36), tenant_id VARCHAR(40), created_by VARCHAR(40), created_timestamp TIMESTAMP, "
                + "code VARCHAR(10), value_date DATE, curr_mult DOUBLE PRECISION, lot_mult DOUBLE PRECISION)");
            s.execute("INSERT INTO live.mult VALUES ('u0', 't1', 'other', TIMESTAMP '2026-01-01 00:00:00', 'CM ZZ', DATE '2026-01-01', 1.0, 1.0)");
        }
        settings = new DatasourceSettings();
        for (var e : java.util.Map.of("src", sourceUrl, "dst", targetUrl).entrySet()) {
            var d = new DatasourceSettings.Definition(); d.setUrl(e.getValue()); d.setUsername("sa"); d.setPassword("");
            d.setReadOnly(true); d.setDriverClassName("org.h2.Driver"); settings.getDatasources().put(e.getKey(), d);
        }
    }

    private Path rule(String name, String where, String expect, String sql) throws Exception {
        Path file = tmp.resolve(name + ".json");
        Files.writeString(file, """
            {"name": "%s", "db": "dst", "backupSchema": "parity_work",
             "params": {"d1": {"type": "date", "value": "2026-11-03"}, "d2": {"type": "date", "value": "2026-11-03"}},
             "steps": [{"id": "mult", "table": "live.mult", "where": "%s", "key": ["code", "value_date"],
                        "source": {"db": "src", "sql": "%s"},
                        "constants": {"uuid": "$uuid", "tenant_id": "t1", "created_by": "test-load", "created_timestamp": "$now"},
                        "expect": %s}]}
            """.formatted(name, where, sql, expect));
        return file;
    }

    private Path rule(String name) throws Exception {
        return rule(name, "created_by = 'test-load'", "{\"targetBefore\": \"0\", \"source\": \"2\", \"inserted\": \"2\", \"targetAfter\": \"2\"}",
            "SELECT code, value_date, curr_mult, lot_mult FROM legacy.mult WHERE value_date IN (:d1, :d2)");
    }

    private Cli.Options options(Path rule, boolean apply) {
        Cli.Options o = new Cli.Options(); o.load = rule; o.apply = apply; o.out = tmp.resolve("out"); return o;
    }

    private long count() throws Exception {
        try (Connection c = DriverManager.getConnection(targetUrl, "sa", ""); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM live.mult")) { rs.next(); return rs.getLong(1); }
    }

    private Path outputDir() throws Exception {
        try (var list = Files.list(tmp.resolve("out"))) { return list.filter(Files::isDirectory).findFirst().orElseThrow(); }
    }

    @Test void previewInsertsNothingButReportsRealCounts() throws Exception {
        assertThat(new DataLoader(settings).execute(options(rule("l1"), false))).isZero();
        assertThat(count()).isEqualTo(1);
        String summary = Files.readString(outputDir().resolve("LOAD.md"));
        assertThat(summary).contains("PREVIEW").contains("| mult | live.mult | src | 2 | 0 | 2 | 2 |");
        assertThat(outputDir().resolve("mult.csv")).exists();   // the exact rows that were read are kept
    }

    @Test void applyInsertsTypedRowsWithConstantsAndUndoRemovesThem() throws Exception {
        assertThat(new DataLoader(settings).execute(options(rule("l2"), true))).isZero();
        assertThat(count()).isEqualTo(3);
        try (Connection c = DriverManager.getConnection(targetUrl, "sa", ""); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT uuid, tenant_id, created_by, created_timestamp, code, value_date, curr_mult, lot_mult "
                 + "FROM live.mult WHERE created_by = 'test-load' ORDER BY code")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("uuid")).hasSize(36).isNotEqualTo("u0");
            assertThat(rs.getString("tenant_id")).isEqualTo("t1");
            assertThat(rs.getTimestamp("created_timestamp")).isNotNull();
            assertThat(rs.getString("code")).isEqualTo("CM AC");
            assertThat(rs.getDate("value_date").toString()).isEqualTo("2026-11-03");
            assertThat(rs.getDouble("curr_mult")).isEqualTo(0.15045286311798514);   // no precision lost through the CSV
            assertThat(rs.getDouble("lot_mult")).isEqualTo(10.0);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("code")).isEqualTo("CM AE");
            rs.getDouble("lot_mult");
            assertThat(rs.wasNull()).as("an empty source value stays NULL").isTrue();
        }
        Path undo = outputDir().resolve("undo-l2.json");
        assertThat(undo).exists();
        Cli.Options restore = new Cli.Options(); restore.restore = undo; restore.apply = true; restore.out = tmp.resolve("out-undo");
        new DataRestorer(settings).execute(restore);
        assertThat(count()).as("only the inserted rows are removed; the other row is untouched").isEqualTo(1);
    }

    @Test void loadingTheSameRowsAgainIsRefusedAndNothingIsCommitted() throws Exception {
        new DataLoader(settings).execute(options(rule("l3"), true));
        assertThat(count()).isEqualTo(3);
        assertThatThrownBy(() -> new DataLoader(settings).execute(options(rule("l3b"), true)))
            .isInstanceOf(ToolException.class).hasMessageContaining("more than once");
        assertThat(count()).isEqualTo(3);
    }

    @Test void unmetExpectRefusesApplyButPreviewOnlyReportsIt() throws Exception {
        String sql = "SELECT code, value_date, curr_mult, lot_mult FROM legacy.mult WHERE value_date IN (:d1, :d2)";
        // two rule names: H2 commits DDL at once, so a backup table made by the preview would collide with the apply of the same name in the same second
        Path wrong = rule("l4a", "created_by = 'test-load'", "{\"inserted\": \"5\"}", sql);
        Path wrongAgain = rule("l4b", "created_by = 'test-load'", "{\"inserted\": \"5\"}", sql);
        assertThat(new DataLoader(settings).execute(options(wrong, false))).isEqualTo(1);
        assertThatThrownBy(() -> new DataLoader(settings).execute(options(wrongAgain, true)))
            .isInstanceOf(ToolException.class).hasMessageContaining("Nothing was committed");
        assertThat(count()).isEqualTo(1);
    }

    @Test void insertedRowsMustFallInsideTheScope() throws Exception {
        Path outside = rule("l5", "created_by = 'nobody'", "{}",
            "SELECT code, value_date, curr_mult, lot_mult FROM legacy.mult WHERE value_date IN (:d1, :d2)");
        assertThatThrownBy(() -> new DataLoader(settings).execute(options(outside, true)))
            .isInstanceOf(ToolException.class).hasMessageContaining("scope");
        assertThat(count()).isEqualTo(1);
    }

    @Test void parameterOverrideChangesWhatIsRead() throws Exception {
        Cli.Options o = options(rule("l6", "created_by = 'test-load'", "{\"source\": \"1\"}",
            "SELECT code, value_date, curr_mult, lot_mult FROM legacy.mult WHERE value_date IN (:d1, :d2)"), true);
        o.parameters.put("d1", "2026-11-05"); o.parameters.put("d2", "2026-11-05");
        assertThat(new DataLoader(settings).execute(o)).isZero();
        assertThat(count()).isEqualTo(2);
    }

    @Test void verifyRuleRunsAfterApply() throws Exception {
        Files.writeString(tmp.resolve("verify.xml"), """
            <rules name="v"><sql id="n" db="dst"><![CDATA[SELECT count(*) AS n FROM live.mult WHERE created_by = 'test-load']]></sql>
            <expect ref="n" column="n" equals="2"/></rules>""");
        // "verify" belongs to the rule (top level), next to name and db
        Path file = tmp.resolve("l7-with-verify.json");
        Files.writeString(file, Files.readString(rule("l7")).replaceFirst("\\{\"name\"", "{\"verify\": [{\"rule\": \"verify.xml\"}], \"name\""));
        assertThat(new DataLoader(settings).execute(options(file, true))).isZero();
        assertThat(Files.readString(outputDir().resolve("LOAD.md"))).contains("verify.xml").contains("PASS");
    }

    @Test void badRulesAreRejectedOffline() throws Exception {
        String ok = "SELECT code, value_date, curr_mult, lot_mult FROM legacy.mult WHERE value_date IN (:d1, :d2)";
        assertThatThrownBy(() -> DataLoader.load(rule("b1", "created_by = 'x'", "{}", "INSERT INTO legacy.mult VALUES (1)")))
            .hasMessageContaining("Only SELECT");
        assertThatThrownBy(() -> DataLoader.load(rule("b2", "created_by = 'x'", "{}", ok.replace(":d2", ":nope"))))
            .hasMessageContaining(":nope");
        assertThatThrownBy(() -> DataLoader.load(rule("b3", "created_by = 'x'; drop table t", "{}", ok)))
            .hasMessageContaining("condition");
        assertThatThrownBy(() -> DataLoader.load(rule("b4", "created_by = 'x'", "{\"rows\": \"1\"}", ok)))
            .hasMessageContaining("unknown expect key");
        assertThatThrownBy(() -> DataLoader.load(rule("b5", "created_by = 'x'", "{}", ok).resolveSibling("missing.json")))
            .hasMessageContaining("Cannot read");
        Path noKey = tmp.resolve("b6.json");
        Files.writeString(noKey, Files.readString(rule("b6x")).replace("\"key\": [\"code\", \"value_date\"]", "\"key\": []"));
        assertThatThrownBy(() -> DataLoader.load(noKey)).hasMessageContaining("key must list");
    }

    @Test void productionTargetNeedsAllowProd() throws Exception {
        settings.getDatasources().get("dst").setProd(true);
        assertThatThrownBy(() -> new DataLoader(settings).execute(options(rule("p1"), false))).hasMessageContaining("--allow-prod");
        assertThat(count()).isEqualTo(1);
    }

    @Test void valuesAreConvertedToTheColumnType() {
        assertThat(DataLoader.convert("2026-11-03 20:04:09.0", Types.TIMESTAMP, "c")).isEqualTo(java.sql.Timestamp.valueOf("2026-11-03 20:04:09"));
        assertThat(DataLoader.convert("2026-11-03 20:04:09.5+05:30", Types.TIMESTAMP_WITH_TIMEZONE, "c")).isInstanceOf(java.sql.Timestamp.class);
        assertThat(DataLoader.convert("2026-11-03", Types.DATE, "c")).isEqualTo(java.sql.Date.valueOf("2026-11-03"));
        assertThat(DataLoader.convert(" 12 ", Types.BIGINT, "c")).isEqualTo(12L);
        assertThat(DataLoader.convert("1.5E-4", Types.DOUBLE, "c")).isEqualTo(1.5E-4);
        assertThat(DataLoader.convert("t", Types.BOOLEAN, "c")).isEqualTo(true);
        assertThat(DataLoader.convert(null, Types.DATE, "c")).isNull();
        assertThat(DataLoader.convert("abc", Types.VARCHAR, "c")).isEqualTo("abc");
        assertThatThrownBy(() -> DataLoader.convert("x", Types.BIGINT, "price")).hasMessageContaining("price");
    }

    @Test void cliAcceptsLoadAndRejectsBadCombinations() {
        Cli.Options o = Cli.parse(new String[]{"--load", "r.json", "--apply", "--param", "d1=2026-11-03"});
        assertThat(o.load).isEqualTo(Path.of("r.json"));
        assertThat(o.apply).isTrue();
        assertThat(o.parameters).containsEntry("d1", "2026-11-03");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--load", "r.json", "--rules", "x.xml"})).hasMessageContaining("Load cannot");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--load", "r.json", "--restore", "x.json"})).hasMessageContaining("Load cannot");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--load", "a.json", "--load", "b.json"})).hasMessageContaining("only one");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--rules", "x.xml", "--check", "--load", "r.json"})).hasMessageContaining("Load cannot");
    }
}
