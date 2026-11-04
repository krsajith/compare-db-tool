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

/** Two isolated in-memory H2 databases stand in for "legacy" (test) and "new" (second). */
class ChainingTests {
    @TempDir Path tmp;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        String a = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", b = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(a, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE valuation(value_date DATE, business_unit VARCHAR(5), factor DECIMAL(10,4), active BOOLEAN)");
            s.execute("INSERT INTO valuation VALUES (DATE '2026-10-02', 'CFS', 1.5, TRUE)");
            s.execute("CREATE TABLE two_rows(v INTEGER)"); s.execute("INSERT INTO two_rows VALUES (1),(2)");
            s.execute("CREATE TABLE wanted AS SELECT x AS id, 'k' || x AS code FROM SYSTEM_RANGE(1, 2500)");
            s.execute("CREATE TABLE few AS SELECT x AS id FROM SYSTEM_RANGE(1, 5)");
            s.execute("CREATE TABLE other AS SELECT x AS id FROM SYSTEM_RANGE(11, 13)");
            s.execute("CREATE TABLE nothing(id INTEGER)");
        }
        try (Connection c = DriverManager.getConnection(b, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE trades AS SELECT x AS id, DATE '2026-10-01' + MOD(x, 2) AS trade_date, x * 2.5 AS amount, 'k' || x AS code FROM SYSTEM_RANGE(1, 3000)");
        }
        settings = new DatasourceSettings();
        for (var e : new String[][]{{"test", a}, {"second", b}}) {
            var d = new DatasourceSettings.Definition(); d.setUrl(e[1]); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
            settings.getDatasources().put(e[0], d);
        }
    }

    private Cli.Outcome run(String body) throws Exception {
        Path xml = tmp.resolve("r" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"chain\">" + body + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 2; o.parallel = 2;
        return new Cli(settings).executeRules(o);
    }

    private String read(Cli.Outcome outcome, String file) throws Exception { return Files.readString(outcome.directory().resolve(file)); }

    @Test void aValueFromOneQueryBecomesAParameterOfTheNext() throws Exception {
        var outcome = run("""
            <sql id="vd" db="test">SELECT value_date FROM valuation WHERE business_unit = 'CFS'</sql>
            <sql id="day" db="second" uses="vd" export="full">SELECT id, trade_date FROM trades WHERE trade_date = :vd.value_date AND id &lt;= 10</sql>""");
        assertThat(outcome.exit()).isZero();
        assertThat(read(outcome, "day.csv").lines().count()).isEqualTo(1 + 5);                      // header + ids 2,4,6,8,10 (trade_date 2026-10-02)
        assertThat(read(outcome, "day.md")).contains("Inputs from earlier queries: using vd.value_date = 2026-10-02");
    }

    @Test void typesSurviveTheTripThroughTheCsv() throws Exception {
        var outcome = run("""
            <sql id="v" db="test">SELECT value_date, factor, active, business_unit FROM valuation</sql>
            <sql id="t" db="second" uses="v" export="full">
              SELECT id, amount FROM trades WHERE amount > :v.factor * 1000 AND trade_date = :v.value_date AND :v.active = TRUE AND code = 'k' || CAST(id AS VARCHAR) AND :v.business_unit = 'CFS' AND id &gt; 1000
            </sql>""");
        assertThat(outcome.exit()).isZero();
        assertThat(read(outcome, "t.csv").lines().count()).isGreaterThan(1);                         // numeric, date, boolean and text inputs all bound with their type
    }

    @Test void aListFromOneDatabaseFiltersAQueryOnAnotherAndHyphenatedIdsWorkThroughAnAlias() throws Exception {
        var outcome = run("""
            <sql id="missing-keys" db="test">SELECT id FROM few</sql>
            <sql id="lookup" db="second" uses="mk=missing-keys" export="full">SELECT id, amount FROM trades WHERE id IN (:mk.id[]) ORDER BY id</sql>""");
        assertThat(outcome.exit()).isZero();
        assertThat(read(outcome, "lookup.csv")).contains("\"1\",\"2.5\"").contains("\"5\",\"12.5\"");
        assertThat(read(outcome, "lookup.csv").lines().count()).isEqualTo(6);
        assertThat(read(outcome, "lookup.md")).contains("using 5 values from missing-keys.id");
    }

    @Test void longListsRunInBatchesAndTheRowsAreJoined() throws Exception {
        var outcome = run("""
            <sql id="w" db="test">SELECT id FROM wanted</sql>
            <sql id="lookup" db="second" uses="w" listBatch="1000" export="full">SELECT id FROM trades WHERE id IN (:w.id[])</sql>""");
        assertThat(outcome.exit()).isZero();
        assertThat(read(outcome, "lookup.csv").lines().count()).isEqualTo(1 + 2500);
        assertThat(read(outcome, "lookup.md")).contains("Rows: 2500").contains("run as 3 batches").contains("2,500 values from w.id in 3 batches of 1,000");
    }

    @Test void aQueryThatCombinesRowsIsNeverSplitIntoBatches() throws Exception {
        var outcome = run("""
            <sql id="w" db="test">SELECT id FROM wanted</sql>
            <sql id="total" db="second" uses="w" listBatch="1000">SELECT count(*) AS n FROM trades WHERE id IN (:w.id[])</sql>""");
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(read(outcome, "MANUAL_ACTIONS.md")).contains("cannot be split into batches safely").contains("GROUP BY, DISTINCT, an aggregate");
        // within the batch size the same query is fine and answers for the whole list
        var small = run("""
            <sql id="w" db="test">SELECT id FROM few</sql>
            <sql id="total" db="second" uses="w" export="full">SELECT count(*) AS n FROM trades WHERE id IN (:w.id[])</sql>""");
        assertThat(read(small, "total.csv")).contains("\"5\"");
    }

    @Test void anEmptyListMatchesNothingButNotInWithAnEmptyListIsRefused() throws Exception {
        var ok = run("""
            <sql id="none" db="test">SELECT id FROM nothing</sql>
            <sql id="lookup" db="second" uses="n=none" export="full">SELECT id FROM trades WHERE id IN (:n.id[])</sql>""");
        assertThat(ok.exit()).isZero();
        assertThat(read(ok, "lookup.csv").lines().count()).isEqualTo(1);                             // header only: no rows, and not an error
        var refused = run("""
            <sql id="none" db="test">SELECT id FROM nothing</sql>
            <sql id="lookup" db="second" uses="n=none">SELECT id FROM trades WHERE id NOT IN (:n.id[])</sql>""");
        assertThat(refused.exit()).isEqualTo(2);
        assertThat(read(refused, "MANUAL_ACTIONS.md")).contains("NOT IN").contains("empty list cannot be run safely");
    }

    @Test void aScalarNeedsExactlyOneRow() throws Exception {
        var none = run("""
            <sql id="e" db="test">SELECT id FROM nothing</sql>
            <sql id="t" db="second" uses="e">SELECT id FROM trades WHERE id = :e.id</sql>""");
        assertThat(read(none, "MANUAL_ACTIONS.md")).contains(":e.id needs exactly one row from query e, which returned none");
        var two = run("""
            <sql id="two" db="test">SELECT v FROM two_rows</sql>
            <sql id="t" db="second" uses="two">SELECT id FROM trades WHERE id = :two.v</sql>""");
        assertThat(read(two, "MANUAL_ACTIONS.md")).contains("which returned more than one").contains(":two.v[] for a list");
        var column = run("""
            <sql id="v" db="test">SELECT value_date FROM valuation</sql>
            <sql id="t" db="second" uses="v">SELECT id FROM trades WHERE trade_date = :v.nope</sql>""");
        assertThat(read(column, "MANUAL_ACTIONS.md")).contains("has no column 'nope'").contains("VALUE_DATE");
    }

    @Test void whenAnInputFailsTheQueryThatNeedsItIsNotRunAndTheRestStillRuns() throws Exception {
        var outcome = run("""
            <sql id="bad" db="test">SELECT * FROM no_such_table</sql>
            <sql id="needs" db="second" uses="bad">SELECT id FROM trades WHERE id IN (:bad.id[])</sql>
            <sql id="fine" db="second">SELECT id FROM trades WHERE id = 1</sql>""");
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(read(outcome, "MANUAL_ACTIONS.md")).contains("Not run: it uses the result of query bad, which failed");
        assertThat(outcome.directory().resolve("fine.md")).exists();
    }

    @Test void identicalSqlWithDifferentInputsIsNotMergedIntoOneExecution() throws Exception {
        var outcome = run("""
            <sql id="a" db="test">SELECT id FROM few</sql>
            <sql id="b" db="test">SELECT id FROM other</sql>
            <sql id="la" db="second" uses="w=a" export="full">SELECT id FROM trades WHERE id IN (:w.id[])</sql>
            <sql id="lb" db="second" uses="w=b" export="full">SELECT id FROM trades WHERE id IN (:w.id[])</sql>""");
        assertThat(read(outcome, "la.csv").lines().count()).isEqualTo(6);
        assertThat(read(outcome, "lb.csv").lines().count()).isEqualTo(4);
        assertThat(read(outcome, "lb.csv")).contains("\"11\"").doesNotContain("\"1\",");
    }

    @Test void aChainCanFeedAComparisonAndAFunnel() throws Exception {
        var outcome = run("""
            <sql id="few" db="test">SELECT id FROM few</sql>
            <sql id="old" db="second" uses="f=few">SELECT id, amount FROM trades WHERE id IN (:f.id[])</sql>
            <sql id="new" db="second" uses="f=few">SELECT id, amount FROM trades WHERE id IN (:f.id[]) AND id &lt;&gt; 3</sql>
            <compare id="cmp" source="old" target="new"><key columns="id"/></compare>
            <expect ref="cmp" only-source="1" different="0" only-target="0"/>""");
        assertThat(outcome.exit()).isZero();
        assertThat(outcome.report().headline()).startsWith("PASS");
    }

    @Test void mistakesAreFoundWithoutConnecting() throws Exception {
        for (var bad : new String[][]{
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS id</sql><sql id=\"b\" db=\"test\">SELECT id FROM t WHERE id IN (:a.id[])</sql>", "alias 'a' which is not declared"},
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS id</sql><sql id=\"b\" db=\"test\" uses=\"a\">SELECT 1 AS x</sql>", "never referenced"},
            {"<sql id=\"b\" db=\"test\" uses=\"x=ghost\">SELECT 1 FROM t WHERE a = :x.id</sql>", "names an unknown query"},
            {"<sql id=\"b\" db=\"test\" uses=\"b\">SELECT 1 FROM t WHERE a = :b.id</sql>", "cannot use its own result"},
            {"<sql id=\"a\" db=\"test\" uses=\"b\">SELECT 1 FROM t WHERE a = :b.id</sql><sql id=\"b\" db=\"test\" uses=\"a\">SELECT 1 FROM t WHERE a = :a.id</sql>", "in a circle"},
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS id</sql><sql id=\"b\" db=\"test\" uses=\"my-a=a\">SELECT 1 FROM t WHERE a = :my-a.id</sql>", "cannot be used as an alias"},
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS id</sql><sql id=\"b\" db=\"test\" uses=\"a\">SELECT 1 FROM t WHERE a = :a.</sql>", "not a valid reference"},
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS id</sql><sql id=\"b\" db=\"test\" uses=\"a,a\">SELECT 1 FROM t WHERE a = :a.id</sql>", "repeats alias"},
            {"<sql id=\"a\" db=\"test\" listBatch=\"0\">SELECT 1 AS id</sql>", "listBatch must be between"},
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS id</sql><sql id=\"b\" db=\"test\" uses=\"a\">SELECT id FROM t WHERE id = :a.id</sql>"
                + "<compare id=\"c\" engine=\"db\" source=\"a\" target=\"b\"><key columns=\"id\"/></compare>", "engine=db"},
        }) {
            Path file = tmp.resolve("v" + Math.abs(bad[0].hashCode()) + ".xml");
            Files.writeString(file, "<rules name=\"v\">" + bad[0] + "</rules>");
            Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).hasMessageContaining(bad[1]);
        }
    }

    @Test void aValidChainValidatesOffline() throws Exception {
        Path file = tmp.resolve("ok.xml");
        Files.writeString(file, """
            <rules name="v">
              <sql id="missing-keys" db="test">SELECT id FROM few</sql><sql id="valuation-date" db="test">SELECT value_date FROM valuation</sql>
              <sql id="lookup" db="second" uses="mk=missing-keys, vd=valuation-date">SELECT id FROM trades WHERE trade_date = :vd.value_date AND id IN (:mk.id[])</sql>
            </rules>""");
        Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
        assertThat(new Cli(settings).executeRules(o).exit()).isZero();
    }

    @Test void batchableAndHelpers() {
        assertThat(Chaining.batchable("SELECT id FROM t WHERE id IN (:a.id[]) ORDER BY id")).isTrue();
        assertThat(Chaining.batchable("SELECT 'a group by b' AS note FROM t WHERE id IN (:a.id[])")).isTrue();       // inside a string
        assertThat(Chaining.batchable("SELECT DISTINCT id FROM t")).isFalse();
        assertThat(Chaining.batchable("SELECT id FROM t GROUP BY id")).isFalse();
        assertThat(Chaining.batchable("SELECT sum(x) FROM t")).isFalse();
        assertThat(Chaining.batchable("SELECT id FROM t LIMIT 5")).isFalse();
        assertThat(Chaining.batchable("SELECT id FROM a UNION SELECT id FROM b")).isFalse();
        assertThat(Chaining.typed("42", java.sql.Types.INTEGER)).isEqualTo(42L);
        assertThat(Chaining.typed("18446744073709551615", java.sql.Types.BIGINT)).isEqualTo(new java.math.BigDecimal("18446744073709551615"));
        assertThat(Chaining.typed("2026-10-02 00:00:00", java.sql.Types.DATE)).isEqualTo(java.sql.Date.valueOf("2026-10-02"));
        assertThat(Chaining.typed("t", java.sql.Types.BOOLEAN)).isEqualTo(true);
        assertThat(Chaining.typed("abc", java.sql.Types.INTEGER)).isEqualTo("abc");                                  // unparsable stays text
        assertThat(Chaining.typed(null, java.sql.Types.INTEGER)).isNull();
    }
}
