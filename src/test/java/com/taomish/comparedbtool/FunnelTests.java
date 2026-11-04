package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Legacy keeps a=1 for ids 1-8, b=1 for ids 1-7. New: a=0 for 3,4,5 and 10; a=1 for 9; b=1 only for 1,2,6.
 * s2 (a=1): legacy {1..8}, new {1,2,6,7,8,9} -> 3,4,5 source only, 9 target only.
 * s3 (a=1 and b=1): legacy {1..7}, new {1,2,6} -> 7 is new at s3; 3,4,5 already diverged at s2.
 */
class FunnelTests {
    @TempDir Path tmp;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE legacy(id INTEGER, a INTEGER, b INTEGER)");
            s.execute("CREATE TABLE fresh(id INTEGER, a INTEGER, b INTEGER)");
            for (int id = 1; id <= 10; id++) {
                int la = id <= 8 ? 1 : 0, lb = id <= 7 ? 1 : 0;
                int na = (id == 3 || id == 4 || id == 5 || id == 10) ? 0 : id == 9 ? 1 : la;
                int nb = (id == 1 || id == 2 || id == 6) ? 1 : 0;
                s.execute("INSERT INTO legacy VALUES (" + id + "," + la + "," + lb + ")");
                s.execute("INSERT INTO fresh VALUES (" + id + "," + na + "," + nb + ")");
            }
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword("");
        d.setReadOnly(true); d.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("test", d);
    }

    private static final String QUERIES = """
        <sql id="l1" db="test">SELECT id FROM legacy</sql>
        <sql id="n1" db="test">SELECT id FROM fresh</sql>
        <sql id="l2" db="test">SELECT id FROM legacy WHERE a = 1</sql>
        <sql id="n2" db="test">SELECT id FROM fresh WHERE a = 1</sql>
        <sql id="l3" db="test">SELECT id FROM legacy WHERE a = 1 AND b = 1</sql>
        <sql id="n3" db="test">SELECT id FROM fresh WHERE a = 1 AND b = 1</sql>
        """;

    private static final String COMPARE_FUNNEL = """
        <funnel id="deals" key="id">
          <stage id="s1-all" source="l1" target="n1" reason="every deal"/>
          <stage id="s2-active" source="l2" target="n2" reason="active deals only"/>
          <stage id="s3-eligible" source="l3" target="n3" reason="active and eligible"/>
        </funnel>""";

    private Cli.Outcome run(String body) throws Exception {
        Path xml = tmp.resolve("r" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"funnel\">" + QUERIES + body + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 4;
        return new Cli(settings).executeRules(o);
    }

    @Test void firstDivergenceIsBlamedOnTheStageThatIntroducesIt() throws Exception {
        var outcome = run(COMPARE_FUNNEL);
        assertThat(outcome.exit()).isEqualTo(1);                                  // a diverging funnel is a difference
        String md = Files.readString(outcome.directory().resolve("deals/SUMMARY.md"));
        assertThat(md).contains("First divergence at `s2-active`: 4 keys agree in every earlier stage, then are kept only by the source (3) or only by the target (1); 5 keys diverge in total");
        assertThat(md).contains("What this stage does: active deals only");
        // stage | source | target | only source | only target | diverges here
        assertThat(md).contains("| 1 | s1-all | 10 | 10 | 0 | 0 | 0 / 0 |");
        assertThat(md).contains("| 2 | s2-active | 8 | 6 | 3 | 1 | 3 / 1 |");
        assertThat(md).contains("| 3 | s3-eligible | 7 | 3 | 4 | 0 | 1 / 0 |");     // only-source 4 = 3 inherited + key 7 new here
        String csv = Files.readString(outcome.directory().resolve("deals/diverging-keys.csv"));
        assertThat(csv).contains("\"key.id\",\"first_divergence\",\"kept_only_by\",\"source_stages\",\"target_stages\"");
        assertThat(csv).contains("\"3\",\"s2-active\",\"source\",\"s1-all..s3-eligible\",\"s1-all\"");
        assertThat(csv).contains("\"9\",\"s2-active\",\"target\",\"s1-all\",\"s1-all..s2-active\"");
        assertThat(csv).contains("\"7\",\"s3-eligible\",\"source\",\"s1-all..s3-eligible\",\"s1-all..s2-active\"");
        assertThat(csv.lines().count()).isEqualTo(6);                              // header + 5 keys
        String summary = Files.readString(outcome.directory().resolve("SUMMARY.md"));
        assertThat(summary).contains("funnel deals: first divergence at `s2-active`").contains("## Findings");
        assertThat(Files.readString(outcome.directory().resolve("FINDINGS.md"))).contains("funnel deals");
    }

    @Test void expectationsOnAFunnelTurnTheDivergenceIntoPassOrFail() throws Exception {
        var pass = run(COMPARE_FUNNEL + "<expect ref=\"deals\" first-divergence=\"s2-active\" diverging=\"5\" reason=\"known rule change\"/>");
        assertThat(pass.exit()).isZero();
        assertThat(pass.report().headline()).startsWith("PASS (2 of 2");
        var fail = run(COMPARE_FUNNEL + "<expect ref=\"deals\" first-divergence=\"none\"/>");
        assertThat(fail.exit()).isEqualTo(1);
        assertThat(fail.report().verdicts().getFirst().actual()).isEqualTo("s2-active");
    }

    @Test void identicalSidesGiveNoDivergence() throws Exception {
        var outcome = run("""
            <funnel id="same" key="id">
              <stage id="a" source="l1" target="l1"/><stage id="b" source="l2" target="l2"/>
            </funnel>
            <expect ref="same" first-divergence="none" diverging="0"/>""");
        assertThat(outcome.exit()).isZero();
        assertThat(Files.readString(outcome.directory().resolve("same/SUMMARY.md"))).contains("No divergence: both sides keep the same keys at all 2 stages");
        assertThat(outcome.directory().resolve("FINDINGS.md")).doesNotExist();
    }

    @Test void chainShowsWhereKeysDropOutAndNeverCountsAsADifference() throws Exception {
        var outcome = run("""
            <funnel id="chain" key="id" mode="chain">
              <stage id="s1-all" source="l1"/><stage id="s2-active" source="l2" reason="active"/><stage id="s3-eligible" source="l3"/>
            </funnel>""");
        assertThat(outcome.exit()).isZero();
        String md = Files.readString(outcome.directory().resolve("chain/SUMMARY.md"));
        assertThat(md).contains("10 keys enter, 7 reach the last stage; 3 drop out, most at `s2-active` (2)");
        assertThat(md).contains("| 1 | s1-all | 10 | - |").contains("| 2 | s2-active | 8 | 2 |").contains("| 3 | s3-eligible | 7 | 1 |");
        String csv = Files.readString(outcome.directory().resolve("chain/dropped-keys.csv"));
        assertThat(csv).contains("\"9\",\"s2-active\",\"s1-all\"").contains("\"8\",\"s3-eligible\",\"s1-all..s2-active\"");
    }

    @Test void keysCanBeCompositeAndNumbersMatchByValue() throws Exception {
        var outcome = run("""
            <sql id="k1" db="test">SELECT id, a FROM legacy</sql>
            <sql id="k2" db="test">SELECT id, CAST(a AS DECIMAL(10,2)) AS a FROM legacy</sql>
            <funnel id="comp" key="id,a"><stage id="x" source="k1" target="k2"/><stage id="y" source="k1" target="k2"/></funnel>""");
        assertThat(Files.readString(outcome.directory().resolve("comp/SUMMARY.md"))).contains("No divergence");
    }

    @Test void sampleModeCapsTheKeyListButCountsStayExact() throws Exception {
        Path xml = tmp.resolve("cap.xml");
        Files.writeString(xml, "<rules name=\"cap\">" + QUERIES + COMPARE_FUNNEL + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 4; o.sampleRows = 6;   // 6 / (3 stages x 2 sides) = 1 per stage and side
        var outcome = new Cli(settings).executeRules(o);
        String md = Files.readString(outcome.directory().resolve("deals/SUMMARY.md"));
        assertThat(md).contains("5 keys diverge in total").contains("showing 3 of 5 (at most 1 per stage and side)");
        o.export = ExportMode.NONE;
        var none = new Cli(settings).executeRules(o);
        assertThat(none.directory().resolve("deals/diverging-keys.csv")).doesNotExist();
        assertThat(Files.readString(none.directory().resolve("deals/SUMMARY.md"))).contains("| 2 | s2-active | 8 | 6 | 3 | 1 | 3 / 1 |");
    }

    @Test void aStageQueryWithoutTheKeyColumnFailsTheFunnelOnlyAndSaysWhich() throws Exception {
        var outcome = run("""
            <sql id="bad" db="test">SELECT a FROM legacy</sql>
            <funnel id="broken" key="id"><stage id="x" source="l1" target="n1"/><stage id="y" source="bad" target="n2"/></funnel>
            <funnel id="fine" key="id"><stage id="x" source="l1" target="l1"/><stage id="y" source="l2" target="l2"/></funnel>""");
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(Files.readString(outcome.directory().resolve("MANUAL_ACTIONS.md"))).contains("query bad has no key column 'id'");
        assertThat(outcome.directory().resolve("fine/SUMMARY.md")).exists();
    }

    @Test void validationRejectsMistakesOffline() throws Exception {
        for (var bad : new String[][]{
            {"<funnel id=\"f\" key=\"id\"><stage id=\"a\" source=\"l1\" target=\"n1\"/></funnel>", "between 2 and 30"},
            {"<funnel id=\"f\" key=\"id\"><stage id=\"a\" source=\"l1\" target=\"n1\"/><stage id=\"a\" source=\"l2\" target=\"n2\"/></funnel>", "unique"},
            {"<funnel id=\"f\" key=\"id\"><stage id=\"a\" source=\"l1\" target=\"n1\"/><stage id=\"b\" source=\"nope\" target=\"n2\"/></funnel>", "unknown source query 'nope'"},
            {"<funnel id=\"f\" key=\"id\"><stage id=\"a\" source=\"l1\" target=\"n1\"/><stage id=\"b\" source=\"l2\"/></funnel>", "needs target"},
            {"<funnel id=\"f\" key=\"id\" mode=\"chain\"><stage id=\"a\" source=\"l1\"/><stage id=\"b\" source=\"l2\" target=\"n2\"/></funnel>", "remove target"},
            {"<funnel id=\"f\" mode=\"compare\"><stage id=\"a\" source=\"l1\" target=\"n1\"/><stage id=\"b\" source=\"l2\" target=\"n2\"/></funnel>", "needs key"},
            {"<funnel id=\"f\" key=\"id\" mode=\"sideways\"><stage id=\"a\" source=\"l1\"/><stage id=\"b\" source=\"l2\"/></funnel>", "mode must be"},
            {COMPARE_FUNNEL + "<expect ref=\"deals\" first-divergence=\"s9\"/>", "must be none or one of"},
            {COMPARE_FUNNEL + "<expect ref=\"deals\" rows=\"1\"/>", "is a funnel"},
            {COMPARE_FUNNEL + "<expect ref=\"l1\" diverging=\"0\"/>", "is a query"},
            {"<funnel id=\"c\" key=\"id\" mode=\"chain\"><stage id=\"a\" source=\"l1\"/><stage id=\"b\" source=\"l2\"/></funnel><expect ref=\"c\" first-divergence=\"none\"/>", "not chains"},
        }) {
            Path file = tmp.resolve("v" + Math.abs(bad[0].hashCode()) + ".xml");
            Files.writeString(file, "<rules name=\"v\">" + QUERIES + bad[0] + "</rules>");
            Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).hasMessageContaining(bad[1]);
        }
    }

    @Test void validFunnelValidatesWithoutConnecting() throws Exception {
        Path file = tmp.resolve("ok.xml");
        Files.writeString(file, "<rules name=\"v\">" + QUERIES + COMPARE_FUNNEL + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
        assertThat(new Cli(settings).executeRules(o).exit()).isZero();
    }

    @Test void groupedChainPutsGroupsSideBySide() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE pairs(id INTEGER, outcome VARCHAR(10), a INTEGER, b INTEGER)");
            for (int id = 1; id <= 4; id++) s.execute("INSERT INTO pairs VALUES (" + id + ",'FAIL',1," + (id == 1 ? 1 : 0) + ")");   // FAIL: 4 enter, 4 pass a, 1 passes b
            for (int id = 5; id <= 8; id++) s.execute("INSERT INTO pairs VALUES (" + id + ",'OK',1,1)");                                // OK: all 4 reach the end
        }
        settings.getDatasources().get("test").setUrl(url);
        var outcome = run("""
            <sql id="g1" db="test">SELECT outcome, id FROM pairs</sql>
            <sql id="g2" db="test">SELECT outcome, id FROM pairs WHERE a = 1</sql>
            <sql id="g3" db="test">SELECT outcome, id FROM pairs WHERE a = 1 AND b = 1</sql>
            <funnel id="grp" key="outcome,id" mode="chain" group="outcome">
              <stage id="s1" source="g1"/><stage id="s2" source="g2"/><stage id="s3" source="g3" reason="b is set"/>
            </funnel>""");
        String md = Files.readString(outcome.directory().resolve("grp/SUMMARY.md"));
        assertThat(md).contains("FAIL: 4 enter, 1 reach the last stage, most drops at `s3` (3); OK: 4 enter, 4 reach the last stage");
        assertThat(md).contains("| # | Stage | FAIL reaching | FAIL dropped | OK reaching | OK dropped | What it does |");
        assertThat(md).contains("| 1 | s1 | 4 | - | 4 | - |").contains("| 3 | s3 | 1 | 3 | 4 | 0 | b is set |");
    }

    @Test void groupMustBeAKeyColumnAndOnlyForChains() throws Exception {
        for (var bad : new String[][]{
            {"<funnel id=\"f\" key=\"id\" mode=\"chain\" group=\"outcome\"><stage id=\"a\" source=\"l1\"/><stage id=\"b\" source=\"l2\"/></funnel>", "must be one of the key columns"},
            {"<funnel id=\"f\" key=\"id\" group=\"id\"><stage id=\"a\" source=\"l1\" target=\"n1\"/><stage id=\"b\" source=\"l2\" target=\"n2\"/></funnel>", "applies to chain funnels"},
        }) {
            Path file = tmp.resolve("g" + Math.abs(bad[0].hashCode()) + ".xml");
            Files.writeString(file, "<rules name=\"v\">" + QUERIES + bad[0] + "</rules>");
            Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).hasMessageContaining(bad[1]);
        }
    }

    @Test void shippedGrasaFunnelV2ValidatesThroughTheToolAndKeepsAll130Pairs() {
        var datasources = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl("jdbc:postgresql://unreachable.invalid:1/x"); d.setUsername("u"); d.setPassword("p"); d.setReadOnly(true);
        datasources.getDatasources().put("postgres-dev", d);
        Rules rules = RuleLoader.load(Path.of("rules/grasa-funnel-cfs-v2.xml"));
        RuleLoader.validate(rules, datasources, true);          // SQL guard, parameters and funnel references; opens no connection
        assertThat(rules.queries).hasSize(7);
        assertThat(rules.funnels).hasSize(1);
        assertThat(rules.funnels.getFirst().stages).extracting(st -> st.id)
            .containsExactly("s1_cache", "s2_deal", "s3_eligible", "s4_master", "s5_active", "s6_vol1", "s7_surface");
        assertThat(rules.funnels.getFirst().group).isEqualTo("outcome");
        String pairs = rules.parameters.stream().filter(p -> p.name.equals("pairs")).findFirst().orElseThrow().value;
        assertThat(pairs.split(",")).hasSize(130).allMatch(p -> p.split("\\|").length == 3);
    }
}
