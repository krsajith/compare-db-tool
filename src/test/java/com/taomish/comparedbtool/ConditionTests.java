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

/** Conditions on query results: text, lists and maps; and running a query only when a condition on an earlier one holds. */
class ConditionTests {
    @TempDir Path tmp;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE people(id INTEGER, first_name VARCHAR(20), age INTEGER, amount DECIMAL(10,2))");
            s.execute("INSERT INTO people VALUES (1,'sajith',40,10.50),(2,'anna',30,20.25),(3,'raj',22,5.00)");
            s.execute("CREATE TABLE nobody(id INTEGER)");
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", d);
    }

    private static final String QUERIES = """
        <sql id="query1" db="test">SELECT first_name, age FROM people ORDER BY id</sql>
        <sql id="people" db="test">SELECT id, first_name, age, amount FROM people ORDER BY id</sql>
        <sql id="nobody" db="test">SELECT id FROM nobody</sql>
        <sql id="query-2" db="test">SELECT first_name FROM people WHERE id = 2</sql>""";

    private Cli.Outcome run(String body) throws Exception {
        Path xml = tmp.resolve("r" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"cond\">" + body + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 2; o.parallel = 1;
        return new Cli(settings).executeRules(o);
    }

    private void assertAllPass(String expressions, int count) throws Exception {
        var outcome = run(QUERIES + expressions);
        assertThat(outcome.report().verdicts()).as(expressions).hasSize(count).allMatch(Expectations.Verdict::pass);
    }

    @Test void aResultCanBeReadByItsIdLikeQueryDotFirstDotColumn() throws Exception {
        assertAllPass("""
            <expect expr="#query1.first.first_name == 'sajith'"/>
            <expect expr="#query1.first.age &gt; 35 and #query1.rows == 3"/>
            <expect expr="#q('query-2').first.first_name == 'anna'"/>
            <expect expr="#query1.first.first_name != 'anna' and not (#query1.first.age &lt; 18)"/>""", 4);
    }

    @Test void textListsAndMapsCanBeTested() throws Exception {
        assertAllPass("""
            <expect expr="#startsWith(#query1.first.first_name, 'saj') and #endsWith(#query1.first.first_name, 'ith')"/>
            <expect expr="#contains(#query1.first.first_name, 'ajit')"/>
            <expect expr="#equalsIgnoreCase(#upper(#query1.first.first_name), 'SAJITH') and #isBlank('  ') and not #isBlank(#query1.first.first_name)"/>
            <expect expr="#contains(#values('people', 'first_name'), 'anna')"/>
            <expect expr="not #contains(#values('people', 'first_name'), 'nobody')"/>
            <expect expr="#contains(#values('people', 'age'), 30)"/>
            <expect expr="#contains(#query1.first, 'first_name') and not #contains(#query1.first, 'last_name')"/>
            <expect expr="#size(#rows('people')) == 3 and #size(#values('people', 'id')) == 3"/>
            <expect expr="#size(#rows('people').?[age &gt; 25]) == 2"/>
            <expect expr="#size(#rows('people').?[first_name == 'sajith' and age == 40]) == 1"/>
            <expect expr="#contains(#rows('people').![first_name], 'raj')"/>
            <expect expr="#sum(#values('people', 'age')) == 92 and #sum(#values('people', 'amount')) == 35.75"/>
            <expect expr="#isEmpty(#rows('nobody')) and #size(#values('nobody', 'id')) == 0 and #nobody.rows == 0"/>
            <expect expr="#size(#distinct(#values('people', 'age'))) == 3"/>
            <expect expr="#size(#rows('people').?[age &gt; 100]) == 0 and #isEmpty(#rows('people').?[age &gt; 100])"/>""", 15);
    }

    @Test void aConditionThatIsFalseFailsWithWhatItRead() throws Exception {
        var outcome = run(QUERIES + "<expect expr=\"#query1.first.first_name == 'anna'\" reason=\"the first person should be anna\"/>");
        var v = outcome.report().verdicts().getFirst();
        assertThat(v.pass()).isFalse();
        assertThat(v.actual()).startsWith("false [q(query1): rows=3]");
        assertThat(v.reason()).isEqualTo("the first person should be anna");
    }

    @Test void readingFromAQueryThatFailedSaysSo() throws Exception {
        var outcome = run(QUERIES + "<sql id=\"broken\" db=\"test\">SELECT * FROM nope</sql><expect expr=\"#broken.rows &gt; 0\"/>");
        assertThat(outcome.report().verdicts().getFirst().actual()).contains("query broken did not complete");
    }

    @Test void aWrongColumnOrTypeIsExplained() throws Exception {
        var outcome = run(QUERIES + "<expect expr=\"#contains(#values('people', 'nope'), 1)\"/><expect expr=\"#size(5) == 1\"/><expect expr=\"#sum(#values('people', 'first_name')) == 1\"/>");
        assertThat(outcome.report().verdicts()).extracting(Expectations.Verdict::actual).satisfiesExactly(
            a -> assertThat(a).contains("query people has no column 'nope'"),
            a -> assertThat(a).contains("size() needs a list, a map or text, not Integer"),
            a -> assertThat(a).contains("sum() found 'sajith', which is not a number"));
    }

    @Test void tooManyRowsForRowsAreRefusedWithAWayOut() throws Exception {
        String url = settings.getDatasources().get("test").getUrl();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE big AS SELECT x AS id FROM SYSTEM_RANGE(1, " + (Expressions.Lookups.MAX_ROWS + 1) + ")");
        }
        var outcome = run(QUERIES + "<sql id=\"big\" db=\"test\">SELECT id FROM big</sql><expect expr=\"#size(#rows('big')) &gt; 0\"/><expect expr=\"#size(#values('big', 'id')) &gt; 0\"/>");
        assertThat(outcome.report().verdicts().get(0).actual()).contains("rows() reads at most 20,000").contains("values('big', 'column')");
        assertThat(outcome.report().verdicts().get(1).pass()).isTrue();
    }

    // ------------------------------------------------------------------ when= on a query

    private static final String CONDITIONAL = """
        <sql id="query1" db="test">SELECT first_name FROM people WHERE id = 1</sql>
        <sql id="yes" db="test" after="query1" when="#query1.first.first_name == 'sajith'" export="full">SELECT id FROM people</sql>
        <sql id="no" db="test" after="query1" when="#query1.first.first_name == 'anna'" export="full">SELECT id FROM people</sql>""";

    @Test void aQueryRunsOnlyWhenItsConditionHoldsAndIdenticalSqlIsNotMerged() throws Exception {
        var outcome = run(CONDITIONAL);
        assertThat(outcome.exit()).isZero();
        assertThat(Files.readString(outcome.directory().resolve("yes.csv")).lines().count()).isEqualTo(1 + 3);
        assertThat(outcome.directory().resolve("no.csv")).doesNotExist();
        String summary = Files.readString(outcome.directory().resolve("SUMMARY.md"));
        assertThat(summary).contains("| no | test | - | - | SKIPPED |").contains("Skipped, not failures").contains("- no: condition not met: #query1.first.first_name == 'anna'");
        assertThat(Files.readString(outcome.directory().resolve("MANUAL_ACTIONS.md"))).contains("No failed queries");
    }

    @Test void skippingCascadesToWhatDependsOnTheSkippedQuery() throws Exception {
        var outcome = run(CONDITIONAL + """
            <sql id="dep" db="test" uses="n=no">SELECT id FROM people WHERE id IN (:n.id[])</sql>
            <compare id="cmp" source="no" target="yes"><key columns="id"/></compare>
            <sql id="drill" db="test" uses="m=cmp:only-source">SELECT id FROM people WHERE id IN (:m.id[])</sql>
            <funnel id="fun" key="id"><stage id="s1" source="no" target="yes"/><stage id="s2" source="no" target="yes"/></funnel>
            <expect ref="cmp" different="0"/><expect ref="fun" first-divergence="none"/><expect ref="yes" rows="3"/>""");
        assertThat(outcome.exit()).isZero();                                    // skipped is not a failure
        String summary = Files.readString(outcome.directory().resolve("SUMMARY.md"));
        assertThat(summary).contains("- dep: its input query no was skipped").contains("- cmp: its input query no was skipped")
            .contains("- drill: its input comparison cmp was skipped").contains("- fun: its stage query no was skipped");
        assertThat(outcome.report().verdicts()).hasSize(1);                     // only the expectation on a query that ran
        assertThat(outcome.report().notEvaluated()).containsExactly("cmp (skipped)", "fun (skipped)");
        assertThat(Files.readString(outcome.directory().resolve("EXPECTATIONS.md"))).contains("Not evaluated: cmp (skipped), fun (skipped)");
    }

    @Test void anExpressionThatReadsASkippedResultFailsInsteadOfPassingSilently() throws Exception {
        var outcome = run(CONDITIONAL + "<expect expr=\"#no.rows == 3\"/>");
        assertThat(outcome.report().verdicts().getFirst().pass()).isFalse();
        assertThat(outcome.report().verdicts().getFirst().actual()).contains("query no was skipped");
    }

    @Test void whenCanUseParametersAndComparisons() throws Exception {
        var outcome = run("""
            <parameters><param name="bu" type="string" value="CFS"/></parameters>
            <sql id="a" db="test">SELECT id FROM people</sql><sql id="b" db="test">SELECT id FROM people WHERE id &lt; 3</sql>
            <compare id="cmp" source="a" target="b"><key columns="id"/></compare>
            <sql id="follow" db="test" after="cmp" when="#bu == 'CFS' and #cmp.onlySource == 1" export="full">SELECT id FROM people</sql>
            <sql id="never" db="test" after="cmp" when="#bu == 'ORM' or #cmp.onlySource &gt; 5" export="full">SELECT id FROM people WHERE id = 1</sql>""");
        assertThat(outcome.directory().resolve("follow.csv")).exists();
        assertThat(outcome.directory().resolve("never.csv")).doesNotExist();
    }

    @Test void aWhenThatCannotBeEvaluatedIsABlockedQueryNotASilentSkip() throws Exception {
        var outcome = run("""
            <sql id="query1" db="test">SELECT first_name FROM people WHERE id = 1</sql>
            <sql id="q" db="test" after="query1" when="#size(#values('query1', 'nope')) &gt; 0">SELECT id FROM people</sql>""");
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(Files.readString(outcome.directory().resolve("MANUAL_ACTIONS.md"))).contains("query query1 has no column 'nope'");
        var notBoolean = run("""
            <sql id="query1" db="test">SELECT first_name FROM people WHERE id = 1</sql>
            <sql id="q" db="test" after="query1" when="#query1.rows + 1">SELECT id FROM people</sql>""");
        assertThat(notBoolean.exit()).isEqualTo(2);
        assertThat(Files.readString(notBoolean.directory().resolve("MANUAL_ACTIONS.md"))).contains("must give true or false but gave 2");
    }

    @Test void mistakesInWhenAndAfterAreFoundWithoutConnecting() throws Exception {
        for (var bad : new String[][]{
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS x</sql><sql id=\"b\" db=\"test\" when=\"#a.rows == 1\">SELECT 1 AS x</sql>", "#a is not a parameter"},                   // a is not waited for
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS x</sql><sql id=\"b\" db=\"test\" after=\"a\" when=\"#a.rows == 1 and #nope == 1\">SELECT 1 AS x</sql>", "#nope is not a parameter"},
            {"<sql id=\"b\" db=\"test\" after=\"ghost\">SELECT 1 AS x</sql>", "which is not a query or comparison"},
            {"<sql id=\"b\" db=\"test\" after=\"b\">SELECT 1 AS x</sql>", "cannot run after itself"},
            {"<sql id=\"a\" db=\"test\" after=\"b\">SELECT 1 AS x</sql><sql id=\"b\" db=\"test\" after=\"a\">SELECT 1 AS x</sql>", "in a circle"},
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS x</sql><sql id=\"b\" db=\"test\" after=\"a\" when=\"T(java.lang.Runtime).getRuntime()\">SELECT 1 AS x</sql>", "a type reference"},
            {"<sql id=\"a\" db=\"test\">SELECT 1 AS x</sql><expect expr=\"#nope.rows == 1\"/>", "#nope is not a parameter"},
        }) {
            Path file = tmp.resolve("w" + Math.abs(bad[0].hashCode()) + ".xml");
            Files.writeString(file, "<rules name=\"v\">" + bad[0] + "</rules>");
            Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).as(bad[0]).hasMessageContaining(bad[1]);
        }
    }

    @Test void aValidRuleWithConditionsValidatesOffline() throws Exception {
        Path file = tmp.resolve("ok.xml");
        Files.writeString(file, "<rules name=\"v\">" + CONDITIONAL + "<sql id=\"people\" db=\"test\">SELECT first_name FROM people</sql>"
            + "<expect expr=\"#contains(#values('people', 'first_name'), 'anna') and #query1.rows == 1\"/></rules>");
        Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
        assertThat(new Cli(settings).executeRules(o).exit()).isZero();
    }

    @Test void theFunctions() {
        var f = Expressions.Functions.class;
        assertThat(Expressions.Functions.contains(java.util.List.of(1L, 2L), 2)).isTrue();                    // 2L equals 2
        assertThat(Expressions.Functions.contains(java.util.List.of(new java.math.BigDecimal("1.50")), 1.5)).isTrue();
        assertThat(Expressions.Functions.contains("abc", "b")).isTrue();
        assertThat(Expressions.Functions.contains(null, "b")).isFalse();
        assertThat(Expressions.Functions.size(null)).isZero();
        assertThat(Expressions.Functions.size("héllo")).isEqualTo(5);
        assertThat(Expressions.Functions.isEmpty(java.util.Map.of())).isTrue();
        assertThat(Expressions.Functions.sum(java.util.Arrays.asList(1L, null, new java.math.BigDecimal("2.5")))).isEqualByComparingTo("3.5");
        assertThat(Expressions.Functions.distinct(java.util.List.of(1, 1, 2))).containsExactly(1, 2);
        assertThat(Expressions.Functions.startsWith(null, "a")).isFalse();
        assertThat(Expressions.Functions.equalsIgnoreCase(null, null)).isTrue();
    }
}
