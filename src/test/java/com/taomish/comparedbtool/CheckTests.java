package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** --check: the databases (here H2) describe and EXPLAIN the queries; the rule is checked against what they say. */
class CheckTests {
    @TempDir Path tmp;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        String a = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", b = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(a, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE trades(trade_id INTEGER, client VARCHAR(10), amount DECIMAL(10,2), settle_date DATE)");
            s.execute("INSERT INTO trades VALUES (1,'A',1.5,DATE '2026-10-01')");
            // a table whose rows would make a division fail if the query were executed: the check must never execute it
            s.execute("CREATE TABLE poison(x INTEGER)"); s.execute("INSERT INTO poison VALUES (0)");
        }
        try (Connection c = DriverManager.getConnection(b, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE trades(trade_id INTEGER, client VARCHAR(10), amount DECIMAL(10,2), settle_date DATE)");
        }
        settings = new DatasourceSettings();
        for (var e : new String[][]{{"test", a}, {"second", b}, {"dead", "jdbc:h2:tcp://localhost:1/nothing"}}) {
            var d = new DatasourceSettings.Definition(); d.setUrl(e[1]); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
            settings.getDatasources().put(e[0], d);
        }
    }

    private Cli.Outcome check(String body, boolean allowProd) throws Exception {
        Path xml = tmp.resolve("r" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"chk\"><parameters><param name=\"cob\" type=\"date\" value=\"2026-10-01\"/></parameters>" + body + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.check = true; o.allowProd = allowProd;
        return new Cli(settings).executeRules(o);
    }

    private Cli.Outcome check(String body) throws Exception { return check(body, false); }

    private String report(Cli.Outcome outcome) throws IOException { return Files.readString(outcome.directory().resolve("CHECK.md")); }

    @Test void aGoodRuleIsAllOkAndNothingIsExecutedOrExported() throws Exception {
        var outcome = check("""
            <sql id="old" db="test">SELECT trade_id, client, amount FROM trades WHERE settle_date = :cob</sql>
            <sql id="new" db="second">SELECT trade_id, client, amount FROM trades WHERE settle_date = :cob</sql>
            <compare id="cmp" source="old" target="new"><key columns="trade_id"/></compare>
            <expect ref="old" column="amount" min="0"/>""");
        assertThat(outcome.exit()).isZero();
        String md = report(outcome);
        assertThat(md).contains("No problems found").contains("| OK | old (test) | 3 columns").contains("| OK | new (second) | 3 columns")
            .contains("| OK | compare cmp | key trade_id and 2 compared column(s) exist on both sides");
        try (var files = Files.list(outcome.directory())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("CHECK.md");           // no csv, sql, summary: nothing was read
        }
    }

    @Test void aQueryIsNeverExecuted() throws Exception {
        // 1/x with x = 0 would raise an error if the statement ran; EXPLAIN and column description do not run it
        var outcome = check("<sql id=\"div\" db=\"test\">SELECT 1 / x AS boom FROM poison</sql>");
        assertThat(outcome.exit()).isZero();
        assertThat(report(outcome)).contains("| OK | div (test) | 1 column");
    }

    @Test void aMissingTableAndAWrongColumnAreFoundWithTheDatabasesOwnMessage() throws Exception {
        var outcome = check("""
            <sql id="a" db="test">SELECT trade_id FROM tradez</sql>
            <sql id="b" db="test">SELECT trade_id, nope FROM trades</sql>
            <sql id="c" db="second">SELECT trade_id FROM trades</sql>""");
        assertThat(outcome.exit()).isEqualTo(2);
        String md = report(outcome);
        assertThat(md).contains("2 problems found. Fix them before the long run.").contains("| FAIL | a (test) |").contains("TRADEZ").contains("| FAIL | b (test) |").contains("NOPE")
            .contains("| OK | c (second) |");
        assertThat(md.indexOf("FAIL")).isLessThan(md.indexOf("| OK |"));                                      // problems first
    }

    @Test void keyColumnsMustExistOnBothSidesOfAComparison() throws Exception {
        var outcome = check("""
            <sql id="old" db="test">SELECT trade_id, client FROM trades</sql>
            <sql id="new" db="second">SELECT client FROM trades</sql>
            <compare id="cmp" source="old" target="new"><key columns="trade_id"/></compare>""");
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(report(outcome)).contains("| FAIL | compare cmp |").contains("TRADE_ID");
        var mapped = check("""
            <sql id="old" db="test">SELECT trade_id AS legacy_id, amount FROM trades</sql>
            <sql id="new" db="second">SELECT trade_id, amount FROM trades</sql>
            <compare id="cmp" source="old" target="new"><key columns="trade_id"/><column name="trade_id" source="legacy_id" target="trade_id"/></compare>""");
        assertThat(mapped.exit()).isZero();                                                                  // column mappings are honoured
    }

    @Test void funnelAndPresenceKeysAndQueryReferencesAreChecked() throws Exception {
        var outcome = check("""
            <sql id="s1" db="test">SELECT trade_id FROM trades</sql><sql id="s2" db="test">SELECT client FROM trades</sql>
            <funnel id="fun" key="trade_id" mode="chain"><stage id="a" source="s1"/><stage id="b" source="s2"/></funnel>
            <presence id="pres" key="trade_id" sources="s1, s2"/>
            <sql id="look" db="second" uses="m=s1">SELECT trade_id FROM trades WHERE trade_id IN (:m.trade_id[]) AND client = :m.client</sql>""");
        assertThat(outcome.exit()).isEqualTo(2);
        String md = report(outcome);
        assertThat(md).contains("| FAIL | funnel fun, stage b | query s2 has no key column(s) [trade_id]").contains("| FAIL | presence pres | query s2 has no key column(s) [trade_id]")
            .contains("| OK | funnel fun, stage a |").contains("| OK | query look :m.trade_id[] |").contains("| FAIL | query look :m.client | no column 'client' in s1");
    }

    @Test void aDrillDownColumnIsCheckedAgainstTheRightSideOfTheComparison() throws Exception {
        var outcome = check("""
            <sql id="old" db="test">SELECT trade_id, client FROM trades</sql><sql id="new" db="second">SELECT trade_id, amount FROM trades</sql>
            <compare id="cmp" source="old" target="new"><key columns="trade_id"/></compare>
            <sql id="a" db="second" uses="s=cmp:only-source">SELECT trade_id FROM trades WHERE client IN (:s.client[])</sql>
            <sql id="b" db="second" uses="t=cmp:only-target">SELECT trade_id FROM trades WHERE amount IN (:t.amount[])</sql>
            <sql id="c" db="second" uses="t=cmp:only-target">SELECT trade_id FROM trades WHERE client IN (:t.client[])</sql>""");
        String md = report(outcome);
        assertThat(md).contains("| OK | query a :s.client[] |").contains("| OK | query b :t.amount[] |").contains("| FAIL | query c :t.client[] |");        // only-target has the target's columns
    }

    @Test void expectationsOnAMissingColumnAreFound() throws Exception {
        var outcome = check("<sql id=\"q\" db=\"test\">SELECT trade_id FROM trades</sql><expect ref=\"q\" column=\"amount\" min=\"0\"/>");
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(report(outcome)).contains("| FAIL | expect on q | no column 'amount'");
    }

    @Test void anUnreachableDatabaseIsReportedOnceAndItsOtherQueriesAreSkippedFast() throws Exception {
        System.setProperty("compare.check.connect.ms", "1500");
        try { unreachable(); } finally { System.clearProperty("compare.check.connect.ms"); }
    }

    private void unreachable() throws Exception {
        long start = System.nanoTime();
        var outcome = check("""
            <sql id="d1" db="dead">SELECT 1 AS x</sql><sql id="d2" db="dead">SELECT 2 AS x</sql><sql id="ok" db="test">SELECT trade_id FROM trades</sql>
            <compare id="cmp" source="d1" target="ok"><key columns="trade_id"/></compare>""");
        assertThat(outcome.exit()).isEqualTo(2);
        String md = report(outcome);
        assertThat(md).contains("| FAIL | d1 (dead) | cannot connect to dead").contains("| SKIP | d2 (dead) | datasource dead could not be reached").contains("| OK | ok (test) |")
            .contains("| SKIP | compare cmp | not checked: query d1 could not be described");
        assertThat((System.nanoTime() - start) / 1_000_000_000).isLessThan(10);                              // one wait, not one per query
    }

    @Test void productionNeedsAllowProdAndTempTableQueriesAreSkipped() throws Exception {
        settings.getDatasources().get("second").setProd(true);
        var blocked = check("<sql id=\"p\" db=\"second\">SELECT trade_id FROM trades</sql>");
        assertThat(blocked.exit()).isEqualTo(2);
        assertThat(report(blocked)).contains("requires --allow-prod");
        var allowed = check("<sql id=\"p\" db=\"second\">SELECT trade_id FROM trades</sql>", true);
        assertThat(allowed.exit()).isZero();
        var temp = check("<sql id=\"t\" db=\"test\" tempTables=\"true\">SELECT trade_id FROM trades</sql>");
        assertThat(report(temp)).contains("| SKIP | t (test) | temp-table queries are checked only when they run");
    }

    @Test void aQueryThatUsesAnotherQueriesValuesStillGetsItsTablesChecked() throws Exception {
        var ok = check("""
            <sql id="vd" db="test">SELECT settle_date FROM trades</sql>
            <sql id="t" db="second" uses="v=vd">SELECT trade_id FROM trades WHERE settle_date = :v.settle_date</sql>""");
        assertThat(ok.exit()).isZero();
        var bad = check("""
            <sql id="vd" db="test">SELECT settle_date FROM trades</sql>
            <sql id="t" db="second" uses="v=vd">SELECT trade_id FROM tradez WHERE settle_date = :v.settle_date</sql>""");
        assertThat(bad.exit()).isEqualTo(2);
        assertThat(report(bad)).contains("| FAIL | t (second) |").contains("TRADEZ");
    }

    @Test void theOptionIsOnlyAllowedWithRulesAndNothingElse() {
        assertThat(Cli.parse(new String[]{"--rules", "r.xml", "--check"}).check).isTrue();
        for (String[] bad : new String[][]{{"--check"}, {"--rules", "r.xml", "--check", "--validate"}, {"--restore", "x.json", "--check"}, {"--rules", "r.xml", "--check", "--datasources"}})
            assertThatThrownBy(() -> Cli.parse(bad)).as(String.join(" ", bad)).hasMessageContaining("--check");
    }

    @Test void thePlannersRowEstimateIsReadFromTheFirstLine() {
        assertThat(QueryRunner.estimatedRows("Seq Scan on trades  (cost=0.00..155.00 rows=9300 width=40)\n  Filter: x")).isEqualTo(9300L);
        assertThat(QueryRunner.estimatedRows("SELECT ...")).isNull();
        assertThat(QueryRunner.estimatedRows(null)).isNull();
        assertThat(QueryRunner.estimatedRows("Aggregate (cost=1..2 rows=1 width=8)\n  -> Seq Scan (rows=5000000)")).isEqualTo(1L);        // the top node, not a child
    }

    @Test void theMenuCanSwitchARuleToCheckMode() throws Exception {
        Path rules = Files.createDirectories(tmp.resolve("rules"));
        Files.writeString(rules.resolve("r.xml"), "<rules name=\"r\"><sql id=\"q\" db=\"test\">SELECT trade_id FROM trades</sql></rules>");
        var ui = new FakeUi().say("/rules", "rule:" + rules.resolve("r.xml").toString().replace('\\', '/'), "check", "go", "/back", null);
        new Menu(settings, ui, tmp.resolve("out2"), rules).run();
        assertThat(ui.output()).contains("Command:  .\\compare.ps1 --rules").contains(" --check").contains("Result: exit 0");
        assertThat(new RunPlan(RuleCatalog.describe(rules.resolve("r.xml"))).modeLabel()).isEqualTo("run");
    }
}
