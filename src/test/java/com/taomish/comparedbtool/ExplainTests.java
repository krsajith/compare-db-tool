package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The plan reading, the expectations on it and the rule element. In-memory H2 only; PostgreSQL's plan is given as JSON text. */
class ExplainTests {
    @TempDir Path tmp;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", d);
    }

    /** A nested loop that probes latest_active_lot by a sequential scan, 5000 times; the planner expected 1 row from it, it returns 700. */
    static final String ANALYZED = """
        [{"Plan": {"Node Type": "Nested Loop", "Plan Rows": 1, "Actual Rows": 700, "Actual Loops": 1, "Actual Total Time": 9100.0, "Total Cost": 500.5,
           "Shared Hit Blocks": 12000, "Shared Read Blocks": 300,
           "Plans": [
             {"Node Type": "Index Scan", "Relation Name": "cloned_deal_ticket_table", "Index Name": "cdt_idx", "Plan Rows": 1, "Actual Rows": 1, "Actual Loops": 5000, "Actual Total Time": 0.04},
             {"Node Type": "Seq Scan", "Relation Name": "latest_active_lot", "Plan Rows": 1, "Actual Rows": 700, "Actual Loops": 5000, "Actual Total Time": 1.7, "Rows Removed by Filter": 250000}
           ]},
          "Planning Time": 1.5, "Execution Time": 9100.0}]""";

    static final String PLAN_ONLY = """
        [{"Plan": {"Node Type": "Hash Join", "Plan Rows": 5000, "Total Cost": 321.0,
           "Plans": [{"Node Type": "Seq Scan", "Relation Name": "premium_fy_table", "Plan Rows": 5000},
                     {"Node Type": "Index Only Scan", "Relation Name": "fixing_table", "Index Name": "fx_idx", "Plan Rows": 10}]}}]""";

    @Test void readsTheTimeTheSequentialScanAndTheEstimateError() {
        var r = ExplainRunner.parse("plan", true, ANALYZED);
        assertThat(r.executionMs()).isEqualTo(9100.0);
        assertThat(r.planningMs()).isEqualTo(1.5);
        assertThat(r.nodes()).hasSize(3);
        assertThat(r.seqScans()).extracting(ExplainRunner.Node::relation).containsExactly("latest_active_lot");
        // the probe runs 5000 loops of 1.7 ms: 8500 ms in the node itself, the largest part of the join
        assertThat(r.slowest().getFirst().label()).isEqualTo("Seq Scan on latest_active_lot");
        assertThat(r.slowest().getFirst().exclusiveMs()).isEqualTo(8500.0);
        // expected 1 row, actual 700 per loop: off by 700x (the index scan expected 1 and got 1, so it is not counted)
        assertThat(r.worstError()).isEqualTo(700.0);
        assertThat(r.misestimated()).extracting(ExplainRunner.Node::type).containsExactly("Nested Loop", "Seq Scan");
        assertThat(r.sharedHit()).isEqualTo(12000);
        assertThat(r.headline()).contains("executed in 9100 ms").contains("1 sequential scan (latest_active_lot)")
            .contains("slowest: Seq Scan on latest_active_lot 8500 ms").contains("worst row estimate off by 700x");
        assertThat(ExplainRunner.render(r)).contains("Seq Scan on latest_active_lot  (rows est 1, actual 700, loops 5000, own time 8500 ms, removed by filter 250000)")
            .contains("-> Index Scan on cloned_deal_ticket_table using cdt_idx");
    }

    @Test void aPlanWithoutAnalyzeHasNoTimesAndSaysNothingWasExecuted() {
        var r = ExplainRunner.parse("plan", false, PLAN_ONLY);
        assertThat(r.analyze()).isFalse();
        assertThat(r.seqScans()).extracting(ExplainRunner.Node::relation).containsExactly("premium_fy_table");
        assertThat(r.slowest()).isEmpty();
        assertThat(r.misestimated()).isEmpty();
        assertThat(r.worstError()).isNaN();
        assertThat(r.headline()).startsWith("planned only, nothing was executed: 3 nodes, estimated cost 321").contains("premium_fy_table");
    }

    @Test void aBrokenPlanIsReportedNotIgnored() {
        assertThatThrownBy(() -> ExplainRunner.parse("plan", true, "not json")).isInstanceOf(ToolException.class).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> ExplainRunner.parse("plan", true, "[{\"Other\": 1}]")).isInstanceOf(ToolException.class).hasMessageContaining("unexpected plan format");
    }

    private Rules rules(String body) throws Exception {
        Path xml = tmp.resolve("r" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"x\">" + body + "</rules>");
        Rules rules = RuleLoader.load(xml);
        RuleLoader.validate(rules, settings);
        return rules;
    }

    private static final String EXPLAIN = "<explain id=\"plan\" db=\"test\" timeout=\"60\"><![CDATA[SELECT 1 AS one WHERE 1 = :n]]></explain>";
    private static final String PARAMS = "<parameters><param name=\"n\" type=\"integer\" value=\"1\"/></parameters>";

    private Expectations.Report evaluate(Rules rules, String json, boolean analyze) {
        var result = ExplainRunner.parse("plan", analyze, json);
        return Expectations.evaluate(rules, Map.of(), List.of(), List.of(), List.of(), List.of(result), Map.of(), Set.of());
    }

    @Test void anExplainNeedsNoQueryAndIsChecked() throws Exception {
        Rules rules = rules(PARAMS + EXPLAIN
            + "<expect ref=\"plan\" max-ms=\"500\" reason=\"fast\"/>"
            + "<expect ref=\"plan\" no-seq-scan=\"latest_active_lot\" reason=\"index used\"/>"
            + "<expect ref=\"plan\" no-seq-scan=\"other_table\"/>"
            + "<expect ref=\"plan\" max-estimate-error=\"50\"/>"
            + "<compress id=\"z\" file=\"z.zip\"><include ref=\"plan\"/></compress>");
        assertThat(rules.queries).isEmpty();
        assertThat(rules.explains).hasSize(1);
        var report = evaluate(rules, ANALYZED, true);
        assertThat(report.verdicts()).extracting(Expectations.Verdict::check, Expectations.Verdict::pass).containsExactly(
            org.assertj.core.groups.Tuple.tuple("execution time ms", false),            // 9100 ms against 500
            org.assertj.core.groups.Tuple.tuple("sequential scan of latest_active_lot", false),
            org.assertj.core.groups.Tuple.tuple("sequential scan of other_table", true),
            org.assertj.core.groups.Tuple.tuple("row estimate error", false));          // 700x against 50
        assertThat(report.verdicts().get(1).actual()).isEqualTo("latest_active_lot (700 rows x 5000 loops)");
        assertThat(report.passed()).isFalse();
    }

    @Test void aGoodPlanPasses() throws Exception {
        Rules rules = rules(PARAMS + EXPLAIN + "<expect ref=\"plan\" max-ms=\"20000\"/><expect ref=\"plan\" no-seq-scan=\"fixing_table, premium_fy_table_x\"/>");
        assertThat(evaluate(rules, ANALYZED, true).passed()).isTrue();
        // the star means any table
        Rules star = rules(PARAMS + EXPLAIN + "<expect ref=\"plan\" no-seq-scan=\"*\"/>");
        assertThat(evaluate(star, ANALYZED, true).passed()).isFalse();
    }

    @Test void timeAndEstimateChecksAreNotMetWhenNothingWasExecuted() throws Exception {
        Rules rules = rules(PARAMS + "<explain id=\"plan\" db=\"test\" analyze=\"false\"><![CDATA[SELECT 1 AS one WHERE 1 = :n]]></explain>"
            + "<expect ref=\"plan\" max-ms=\"500\"/><expect ref=\"plan\" no-seq-scan=\"premium_fy_table\"/>");
        var report = evaluate(rules, PLAN_ONLY, false);
        assertThat(report.verdicts().get(0).pass()).isFalse();
        assertThat(report.verdicts().get(0).actual()).contains("nothing was executed");
        assertThat(report.verdicts().get(1).pass()).isFalse();                              // the plan does scan premium_fy_table sequentially
    }

    @Test void mistakesInTheRuleAreCaughtBeforeAnyConnection() {
        assertThatThrownBy(() -> rules(PARAMS + EXPLAIN + "<expect ref=\"plan\" rows=\"1\"/>")).hasMessageContaining("is an explain");
        assertThatThrownBy(() -> rules(PARAMS + EXPLAIN + "<expect ref=\"plan\"/>")).hasMessageContaining("states nothing to check");
        assertThatThrownBy(() -> rules(PARAMS + EXPLAIN + "<expect ref=\"plan\" max-ms=\"slow\"/>")).hasMessageContaining("Invalid number max-ms");
        assertThatThrownBy(() -> rules(PARAMS + "<sql id=\"q\" db=\"test\">SELECT 1 AS a</sql>" + EXPLAIN + "<expect ref=\"q\" max-ms=\"5\"/>"))
            .hasMessageContaining("apply to an <explain>");
        assertThatThrownBy(() -> rules(PARAMS + "<explain id=\"p\" db=\"test\" timeout=\"0\">SELECT 1 AS a</explain>")).hasMessageContaining("timeout must be between");
        assertThatThrownBy(() -> rules(PARAMS + "<explain id=\"p\" db=\"nope\">SELECT 1 AS a</explain>")).hasMessageContaining("Unknown datasource");
        assertThatThrownBy(() -> rules(PARAMS + "<explain id=\"p\" db=\"test\">DELETE FROM t</explain>")).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> rules(PARAMS + "<explain id=\"p\" db=\"test\">SELECT :missing AS a</explain>")).hasMessageContaining("bind parameters");
        assertThatThrownBy(() -> rules(PARAMS + EXPLAIN + EXPLAIN)).hasMessageContaining("unique");
    }

    @Test void anExplainCanTakeAValueFromAnEarlierQuery() throws Exception {
        String pick = "<sql id=\"pick\" db=\"test\">SELECT 7 AS n</sql>";
        String uses = "<explain id=\"plan\" db=\"test\" uses=\"t=pick\"><![CDATA[SELECT 1 AS one WHERE 1 = :t.n]]></explain>";
        assertThat(rules(pick + uses).explains.getFirst().uses).isEqualTo("t=pick");
        assertThatThrownBy(() -> rules(uses)).hasMessageContaining("unknown query");
        assertThatThrownBy(() -> rules(pick + "<explain id=\"plan\" db=\"test\" uses=\"t=pick\">SELECT 1 AS one</explain>")).hasMessageContaining("never referenced");
        assertThatThrownBy(() -> rules(pick + "<explain id=\"plan\" db=\"test\">SELECT 1 AS one WHERE 1 = :t.n</explain>")).hasMessageContaining("not declared");
        // at run time the value is bound first; the H2 stand-in then stops at the PostgreSQL-only rule, which proves the binding itself worked
        Path xml = tmp.resolve("uses.xml");
        Files.writeString(xml, "<rules name=\"x\">" + pick + uses + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out2"); o.partitions = 4; o.sampleRows = 200;
        var outcome = new Cli(settings).executeRules(o);
        assertThat(Files.readString(outcome.directory().resolve("MANUAL_ACTIONS.md"))).contains("works on PostgreSQL datasources only")
            .doesNotContain("are not available");
    }

    @Test void aRuleWithoutQueriesOrExplainsStillNeedsOne() {
        assertThatThrownBy(() -> rules(PARAMS)).hasMessageContaining("at least one <sql> or <explain>");
    }

    @Test void anExplainOnADatabaseThatIsNotPostgreSqlFailsAsAManualAction() throws Exception {
        Path xml = tmp.resolve("run.xml");
        Files.writeString(xml, "<rules name=\"x\">" + PARAMS + EXPLAIN + "<expect ref=\"plan\" max-ms=\"500\"/></rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 4; o.sampleRows = 200;
        var outcome = new Cli(settings).executeRules(o);
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(Files.readString(outcome.directory().resolve("MANUAL_ACTIONS.md"))).contains("works on PostgreSQL datasources only");
        assertThat(Files.readString(outcome.directory().resolve("EXPECTATIONS.md"))).contains("explain did not complete");
    }
}
