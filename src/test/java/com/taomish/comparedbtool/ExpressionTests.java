package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpressionTests {
    @TempDir Path tmp;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE a(id INTEGER, v INTEGER)"); s.execute("CREATE TABLE b(id INTEGER, v INTEGER)");
            s.execute("INSERT INTO a VALUES (1,10),(2,20),(3,30),(4,40)"); s.execute("INSERT INTO b VALUES (1,10),(2,99),(5,50)");
            s.execute("CREATE TABLE vd(value_date DATE, factor DECIMAL(10,2))"); s.execute("INSERT INTO vd VALUES (DATE '2026-10-02', 1.25)");
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", d);
    }

    private Path rules(String body) throws Exception {
        Path xml = tmp.resolve("r" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"expr\">" + body + "</rules>");
        return xml;
    }

    private Cli.Outcome run(String body, String... params) throws Exception {
        Cli.Options o = new Cli.Options(); o.rules = rules(body); o.out = tmp.resolve("out"); o.partitions = 2;
        for (String p : params) o.parameters.put(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1));
        return new Cli(settings).executeRules(o);
    }

    private String csv(Cli.Outcome outcome, String file) throws Exception { return Files.readString(outcome.directory().resolve(file)); }

    private static final String DATES = """
        <parameters>
          <param name="cob" type="date" value="2026-10-01"/>
          <param name="prevCob" type="date" expr="#prevBusinessDay(#cob)"/>
          <param name="valueDate" type="date" expr="#nextBusinessDay(#cob)"/>
        </parameters>
        <sql id="d" db="test" export="full">SELECT CAST(:cob AS DATE) AS cob, CAST(:prevCob AS DATE) AS prev, CAST(:valueDate AS DATE) AS vdate FROM a WHERE id = 1</sql>""";

    @Test void computedParametersAreBoundWithTheirDeclaredType() throws Exception {
        var outcome = run(DATES);
        assertThat(csv(outcome, "d.csv")).contains("\"2026-10-01\",\"2026-09-30\",\"2026-10-02\"");           // Thursday: Wednesday before, Friday after
        assertThat(csv(outcome, "PARAMETERS.csv")).contains("prevCob").contains("2026-09-30");
        var monday = run(DATES, "cob=2026-10-05");
        assertThat(csv(monday, "d.csv")).contains("\"2026-10-05\",\"2026-10-02\",\"2026-10-06\"");            // Monday: the Friday before
        var friday = run(DATES, "cob=2026-10-02");
        assertThat(csv(friday, "d.csv")).contains("\"2026-10-02\",\"2026-10-01\",\"2026-10-05\"");            // Friday: the Monday after
    }

    @Test void aComputedParameterCanBeOverriddenAndCanUseNumbersAndText() throws Exception {
        var overridden = run(DATES, "prevCob=2020-01-01");
        assertThat(csv(overridden, "d.csv")).contains("\"2026-10-01\",\"2020-01-01\",\"2026-10-02\"");
        var mixed = run("""
            <parameters>
              <param name="n" type="integer" value="-5"/>
              <param name="m" type="integer" expr="#abs(#n) + 1"/>
              <param name="label" type="string" expr="#upper('bu') + '-' + #m"/>
              <param name="big" type="boolean" expr="#m > 5"/>
            </parameters>
            <sql id="x" db="test" export="full">SELECT :m AS m, :label AS label, :big AS big FROM a WHERE id = 1</sql>""");
        assertThat(csv(mixed, "x.csv")).contains("\"6\",\"BU-6\",\"TRUE\"");
    }

    @Test void aFailingCheckStopsTheRunBeforeAnyConnectionAndSaysWhy() throws Exception {
        String body = """
            <parameters>
              <param name="valueDate" type="date" value="2026-10-02"/><param name="cob" type="date" value="2026-10-01"/><param name="prevCob" type="date" value="2026-09-30"/>
            </parameters>
            <check expr="#prevCob &lt; #cob and #cob &lt;= #valueDate" message="Expected PrevCob &lt; Cob &lt;= ValueDate. Check the incident dates."/>
            <sql id="x" db="test">SELECT 1 AS one FROM a WHERE id = 1</sql>""";
        assertThat(run(body).exit()).isZero();
        assertThatThrownBy(() -> run(body, "cob=2026-10-05")).hasMessageContaining("Check failed: Expected PrevCob < Cob <= ValueDate").hasMessageContaining("cob=2026-10-05");
        // --validate evaluates checks too (with the overrides), still without connecting
        Cli.Options o = new Cli.Options(); o.rules = rules(body); o.validate = true; o.parameters.put("prevCob", "2026-10-09");
        assertThatThrownBy(() -> new Cli(settings).executeRules(o)).hasMessageContaining("Check failed");
    }

    @Test void whenSkipsAnExpectationAndAnUnevaluableWhenIsAFailureNotASilentSkip() throws Exception {
        String queries = """
            <parameters><param name="bu" type="string" value="CFS"/></parameters>
            <sql id="l" db="test">SELECT id FROM a</sql><sql id="n" db="test">SELECT id FROM b</sql>
            <compare id="cmp" source="l" target="n"><key columns="id"/></compare>""";
        var skipped = run(queries + "<expect ref=\"cmp\" different=\"0\" only-source=\"0\" when=\"#bu == 'ORM'\"/><expect ref=\"l\" rows=\"4\"/>");
        assertThat(skipped.report().verdicts()).hasSize(1);                                             // only the rows= check ran
        var taken = run(queries + "<expect ref=\"cmp\" only-source=\"2\" when=\"#bu == 'CFS'\"/>");
        assertThat(taken.report().verdicts()).extracting(Expectations.Verdict::pass).containsExactly(true);
        var broken = run(queries + "<expect ref=\"l\" rows=\"4\" when=\"#q('nope').rows &gt; 1\"/>");
        assertThat(broken.report().verdicts().getFirst().pass()).isFalse();
        assertThat(broken.report().verdicts().getFirst().actual()).contains("query nope did not complete");
    }

    @Test void expectationsCanRelateResultsToEachOtherAndShowWhatTheyRead() throws Exception {
        String queries = """
            <sql id="l" db="test">SELECT id, v FROM a</sql><sql id="n" db="test">SELECT id, v FROM b</sql>
            <compare id="cmp" source="l" target="n"><key columns="id"/></compare>""";
        // legacy 4 rows, new 3 rows: 2 only in legacy, 1 only in new, 1 differs; rows(l) - rows(n) == onlySource - onlyTarget (4 - 3 == 2 - 1)
        var ok = run(queries + """
            <expect expr="#q('l').rows - #q('n').rows == #c('cmp').onlySource - #c('cmp').onlyTarget" judges="cmp" reason="the row counts reconcile with the unmatched rows"/>
            <expect expr="#c('cmp').different &lt;= 1 and #c('cmp').onlySource == 2"/>""");
        assertThat(ok.report().verdicts()).extracting(Expectations.Verdict::pass).containsExactly(true, true);
        assertThat(ok.exit()).isZero();                    // the first expression accounts for the comparison's differences
        var unjudged = run(queries + "<expect expr=\"#q('l').rows - #q('n').rows == #c('cmp').onlySource - #c('cmp').onlyTarget\"/>");
        assertThat(unjudged.report().headline()).startsWith("PASS");
        assertThat(unjudged.exit()).isEqualTo(1);          // true, but without judges= the comparison's differences still count
        var judged = run(queries + "<expect expr=\"#c('cmp').onlySource == 2 and #c('cmp').onlyTarget == 1\" judges=\"cmp\"/>");
        assertThat(judged.exit()).isZero();
        var bad = run(queries + "<expect expr=\"#q('l').rows == #q('n').rows\" reason=\"same row count\"/>");
        var verdict = bad.report().verdicts().getFirst();
        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.actual()).startsWith("false [q(l): rows=4; q(n): rows=3]");
        assertThat(verdict.reason()).isEqualTo("same row count");
    }

    @Test void theFirstRowOfAQueryIsReadableWithItsTypes() throws Exception {
        var outcome = run("""
            <sql id="vd" db="test">SELECT value_date, factor FROM vd</sql>
            <expect expr="#q('vd').first.value_date == #date('2026-10-02') and #q('vd').first.factor &gt; 1 and #q('vd').columns[0] == 'value_date'"/>
            <expect expr="#daysBetween(#q('vd').first.value_date, #date('2026-10-09')) == 7"/>""");
        assertThat(outcome.report().verdicts()).extracting(Expectations.Verdict::pass).containsExactly(true, true);
    }

    @Test void whatAnExpressionMayNotDoIsRefusedWhenTheRuleIsChecked() throws Exception {
        for (var bad : new String[][]{
            {"<check expr=\"T(java.lang.Runtime).getRuntime().exec('calc') != null\"/>", "a type reference T(...)"},
            {"<check expr=\"new java.io.File('x').exists()\"/>", "creating objects with new"},
            {"<check expr=\"'abc'.toString() == 'abc'\"/>", "calling a method on a value"},
            {"<check expr=\"#cob.plusDays(1) &gt; #cob\"/>", "calling a method on a value"},
            {"<check expr=\"'a' matches '(a+)+$'\"/>", "matches (regular expressions)"},
            {"<check expr=\"#cob instanceof T(java.lang.String)\"/>", "instanceof"},
            {"<check expr=\"#cob = #cob\"/>", "assignment"},
            {"<check expr=\"@someBean.run()\"/>", "a bean reference"},
            {"<check expr=\"#nope == 1\"/>", "#nope is not a parameter"},
            {"<check expr=\"#today == 1\"/>", "functions are called with brackets"},
            {"<check expr=\"#sleep(5) == 1\"/>", "unknown function sleep()"},
            {"<check expr=\"#q('x').rows == 1\"/>", "work only in <expect>"},
            {"<check expr=\"#cob &lt;\"/>", "cannot parse the expression"},
            {"<check expr=\"" + "1 + ".repeat(200) + "1 == 1\"/>", "longer than 500 characters"},
            {"<check expr=\"\"/>", "the expression is empty"},
            {"<check expr=\"#cob == #date('2026-10-01')\"/>", null},                // allowed, and true
            {"<expect expr=\"#q('l').rows == 1\" ref=\"l\"/>", "cannot be combined"},
            {"<expect expr=\"#q('l').rows == 1\" judges=\"ghost\"/>", "is not a comparison or funnel"},
            {"<expect ref=\"l\" rows=\"1\" judges=\"l\"/>", "judges= belongs to"},
            {"<expect expr=\"T(java.lang.Runtime).getRuntime()\"/>", "a type reference"},
            {"<expect ref=\"l\" rows=\"1\" when=\"new java.util.Date()\"/>", "creating objects with new"},
        }) {
            Path file = rules("""
                <parameters><param name="cob" type="date" value="2026-10-01"/></parameters>
                <sql id="l" db="test">SELECT id FROM a</sql>""" + bad[0]);
            Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
            if (bad[1] == null) { new Cli(settings).executeRules(o); continue; }
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).as(bad[0]).hasMessageContaining(bad[1]);
        }
    }

    @Test void parametersMustNotMixValueAndExprOrUseALaterParameter() throws Exception {
        for (var bad : new String[][]{
            {"<parameters><param name=\"a\" type=\"integer\" value=\"1\" expr=\"2\"/></parameters>", "both value= and expr="},
            {"<parameters><param name=\"a\" type=\"integer\" expr=\"#b + 1\"/><param name=\"b\" type=\"integer\" value=\"1\"/></parameters>", "#b is not a parameter"},
            {"<parameters><param name=\"a\" type=\"integer\" expr=\"#a + 1\"/></parameters>", "#a is not a parameter"},
            {"<parameters><param name=\"min\" type=\"integer\" value=\"1\"/></parameters><check expr=\"#min(#min, 2) == 1\"/>", "'min' is a parameter, not a function"},
        }) {
            Cli.Options o = new Cli.Options(); o.rules = rules(bad[0] + "<sql id=\"l\" db=\"test\">SELECT id FROM a</sql>"); o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).as(bad[0]).hasMessageContaining(bad[1]);
        }
    }

    @Test void aWronglyTypedExpressionResultIsExplained() throws Exception {
        assertThatThrownBy(() -> run("<parameters><param name=\"d\" type=\"date\" expr=\"'not a date'\"/></parameters><sql id=\"l\" db=\"test\">SELECT 1 AS x FROM a</sql>"))
            .hasMessageContaining("Invalid value/type for parameter d");
        assertThatThrownBy(() -> run("<check expr=\"1 + 1\"/><sql id=\"l\" db=\"test\">SELECT 1 AS x FROM a</sql>")).hasMessageContaining("must give true or false but gave 2");
        assertThatThrownBy(() -> run("<parameters><param name=\"d\" type=\"date\" expr=\"#date('2026-13-45')\"/></parameters><sql id=\"l\" db=\"test\">SELECT 1 AS x FROM a</sql>"))
            .hasMessageContaining("Parameter d:").hasMessageContaining("2026-13-45");
    }

    @Test void theFunctions() {
        var f = Expressions.Functions.class;
        assertThat(Expressions.Functions.addBusinessDays(LocalDate.of(2026, 10, 2), 1)).isEqualTo(LocalDate.of(2026, 10, 5));      // Friday -> Monday
        assertThat(Expressions.Functions.addBusinessDays(LocalDate.of(2026, 10, 5), -1)).isEqualTo(LocalDate.of(2026, 10, 2));     // Monday -> Friday
        assertThat(Expressions.Functions.addBusinessDays(LocalDate.of(2026, 10, 3), 0)).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(Expressions.Functions.addBusinessDays(LocalDate.of(2026, 10, 1), 5)).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(Expressions.Functions.endOfMonth(LocalDate.of(2026, 2, 10))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Expressions.Functions.startOfMonth(LocalDate.of(2026, 2, 10))).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(Expressions.Functions.plusMonths(LocalDate.of(2026, 1, 31), 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Expressions.Functions.dayOfWeek(LocalDate.of(2026, 10, 5))).isEqualTo(1);
        assertThat(Expressions.Functions.isBusinessDay(LocalDate.of(2026, 10, 4))).isFalse();
        assertThat(Expressions.Functions.round(new java.math.BigDecimal("2.345"), 2)).isEqualByComparingTo("2.35");
        assertThat(Expressions.functionNames(false)).contains("prevBusinessDay", "plusDays", "abs").doesNotContain("q", "c", "f");
        assertThat(Expressions.functionNames(true)).contains("q", "c", "f");
    }
}
