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

/** Isolated in-memory H2 only. */
class ExpectationTests {
    @TempDir Path tmp;
    String url;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA live"); s.execute("CREATE SCHEMA ref"); s.execute("CREATE SCHEMA parity_work");
            s.execute("CREATE TABLE source_data(id INTEGER, amount DECIMAL(25,3), label VARCHAR(50))");
            s.execute("CREATE TABLE target_data(id INTEGER, amount DECIMAL(25,3), label VARCHAR(50))");
            s.execute("INSERT INTO source_data VALUES(1,10,'Alpha'),(2,20,'Bravo'),(3,30,'Source'),(4,40,'Dup'),(4,40,'Dup')");
            s.execute("INSERT INTO target_data VALUES(1,10.005,'alpha'),(2,-20,'Bravo'),(5,50,'Target'),(4,40,'Dup')");
            for (String schema : new String[]{"live", "ref"})
                s.execute("CREATE TABLE " + schema + ".price (uuid VARCHAR(36), bu VARCHAR(5), security_id VARCHAR(20), value_date DATE, "
                    + "settle_price DOUBLE PRECISION, updated_by VARCHAR(20))");
            s.execute("INSERT INTO ref.price VALUES ('u1','CFS','A',DATE '2026-10-02',1.5,'mig'),('u2','CFS','B',DATE '2026-10-02',2.5,'mig')");
            s.execute("INSERT INTO live.price VALUES ('u1','CFS','A',DATE '2026-10-02',1.5,'x'),('u2','CFS','B',DATE '2026-10-02',9.9,'load')");
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword("");
        d.setReadOnly(true); d.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("test", d);
    }

    private static final String BASE = """
        <sql id="left" db="test">SELECT id, amount, label FROM source_data</sql>
        <sql id="right" db="test">SELECT id, amount, label FROM target_data</sql>
        <sql id="total" db="test">SELECT count(*) AS n, 0 AS bad FROM source_data</sql>
        <compare id="cmp" source="left" target="right">
          <key columns="id"/><column name="amount" absTol="0.01"/><column name="label" caseInsensitive="true"/>
        </compare>
        """;

    private Path xml(String name, String expects) throws Exception {
        Path file = tmp.resolve(name + ".xml");
        Files.writeString(file, "<rules name=\"" + name + "\">" + BASE + expects + "</rules>");
        return file;
    }

    private Cli.Outcome run(Path xml) throws Exception {
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 4; // default export: sample
        return new Cli(settings).executeRules(o);
    }

    @Test void expectedDifferencesPassAndExitIsZero() throws Exception {
        var outcome = run(xml("ok", """
            <expect ref="cmp" different="1" only-source="1" only-target="1" matched="2" duplicates="1" reason="seeded"/>
            <expect ref="total" rows="1"/>
            <expect ref="total" column="n" equals="5" reason="five source rows"/>
            <expect ref="total" column="bad" max="0"/>
            <expect ref="total" column="n" between="4,6"/>
            """));
        assertThat(outcome.exit()).isZero();
        assertThat(outcome.report().headline()).startsWith("PASS (9 of 9");
        String summary = Files.readString(outcome.directory().resolve("SUMMARY.md"));
        assertThat(summary).contains("Verdict: PASS").contains("COMPLETE / EXPECTATIONS MET").contains("five source rows")
            .contains("## Small query results").contains("`total` (1 row)").contains("| 5 | 0 |");
        assertThat(outcome.directory().resolve("EXPECTATIONS.md")).exists();
    }

    @Test void unmetExpectationFailsWithExitOneAndShowsFailuresFirst() throws Exception {
        var outcome = run(xml("bad", """
            <expect ref="cmp" different="0" reason="should be clean"/>
            <expect ref="cmp" matched=">=2"/>
            <expect ref="total" column="n" equals="6"/>
            """));
        assertThat(outcome.exit()).isEqualTo(1);
        assertThat(outcome.report().failed()).isEqualTo(2);
        String summary = Files.readString(outcome.directory().resolve("SUMMARY.md"));
        assertThat(summary).contains("Verdict: FAIL (2 of 3").contains("EXPECTATIONS NOT MET");
        assertThat(summary.indexOf("| FAIL |")).isLessThan(summary.indexOf("| PASS |"));
        assertThat(summary).contains("should be clean");
    }

    @Test void comparisonWithoutExpectationsStillCountsAsDifferences() throws Exception {
        // only the query is judged; the comparison differs and nobody said that was expected
        var outcome = run(xml("unjudged", "<expect ref=\"total\" column=\"n\" equals=\"5\"/>"));
        assertThat(outcome.exit()).isEqualTo(1);
        assertThat(Files.readString(outcome.directory().resolve("SUMMARY.md"))).contains("DIFFERENCES");
    }

    @Test void everyRowAndRowNumberAndNullChecks() throws Exception {
        var outcome = run(xml("rows", """
            <expect ref="left" column="id" each="true" min="1"/>
            <expect ref="left" column="label" row="3" equals="Source"/>
            <expect ref="left" column="label" row="9" equals="x"/>
            <expect ref="left" column="nothere" equals="x"/>
            <expect ref="left" rows="5"/>
            <expect ref="cmp" different="1" only-source="1" only-target="1" matched="2" duplicates="1"/>
            """));
        assertThat(outcome.report().verdicts()).extracting(Expectations.Verdict::pass)
            .containsExactly(true, true, false, false, true, true, true, true, true, true);
        assertThat(outcome.report().verdicts().get(2).actual()).contains("only 5 row");
        assertThat(outcome.report().verdicts().get(3).actual()).contains("not in the result");
    }

    @Test void failedQueryFailsItsExpectationsAndExitIsTwo() throws Exception {
        Path file = tmp.resolve("broken.xml");
        Files.writeString(file, """
            <rules name="broken">
              <sql id="q" db="test">SELECT * FROM no_such_table</sql>
              <expect ref="q" rows="1"/>
            </rules>""");
        var outcome = run(file);
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(outcome.report().verdicts().getFirst().actual()).contains("did not complete");
    }

    @Test void schemaDifferenceCannotPassSilently() throws Exception {
        Path file = tmp.resolve("schema.xml");
        Files.writeString(file, """
            <rules name="schema">
              <sql id="a" db="test">SELECT id, amount FROM source_data</sql>
              <sql id="b" db="test">SELECT id, amount, label FROM source_data</sql>
              <compare id="c" source="a" target="b"><key columns="id"/></compare>
              <expect ref="c" different="0" only-source="0" only-target="0"/>
            </rules>""");
        var outcome = run(file);
        assertThat(outcome.report().verdicts()).anyMatch(v -> v.check().equals("schema") && !v.pass());
        assertThat(outcome.exit()).isEqualTo(1);
    }

    @Test void validationRejectsMistakesOffline() throws Exception {
        for (var bad : new String[][]{
            {"<expect ref=\"nope\" rows=\"1\"/>", "unknown id"},
            {"<expect ref=\"total\" different=\"0\"/>", "is a query"},
            {"<expect ref=\"cmp\" rows=\"1\"/>", "is a comparison"},
            {"<expect ref=\"total\" column=\"n\"/>", "needs equals"},
            {"<expect ref=\"total\" equals=\"1\"/>", "needs column"},
            {"<expect ref=\"cmp\" different=\"lots\"/>", "Invalid expected count"},
            {"<expect ref=\"total\" column=\"n\" between=\"9,1\"/>", "first number is larger"},
            {"<expect ref=\"total\"/>", "states nothing"},
            {"<expect ref=\"total\" column=\"n\" each=\"true\" row=\"2\" equals=\"1\"/>", "cannot be combined"},
        }) {
            Path file = xml("v" + Math.abs(bad[0].hashCode()), bad[0]);
            Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).hasMessageContaining(bad[1]);
        }
    }

    @Test void countSpecs() {
        assertThat(Expectations.Spec.parse("x", "5").test(5)).isTrue();
        assertThat(Expectations.Spec.parse("x", "<=5").test(6)).isFalse();
        assertThat(Expectations.Spec.parse("x", "<5").test(4)).isTrue();
        assertThat(Expectations.Spec.parse("x", ">5").test(5)).isFalse();
        assertThat(Expectations.Spec.parse("x", ">=5").test(5)).isTrue();
        assertThat(Expectations.Spec.parse("x", "73000..74500").test(74000)).isTrue();
        assertThat(Expectations.sameValue("2.50", "2.5")).isTrue();
        assertThat(Expectations.sameValue("abc ", "abc")).isTrue();
    }

    // ------------------------------------------------------------------ restore: step expect and verify

    private Path restore(String name, String expect, String verify) throws Exception {
        Path file = tmp.resolve(name + ".json");
        Files.writeString(file, """
            {"name": "%s", "db": "test", "backupSchema": "parity_work",
             "steps": [{"id": "prices", "table": "live.price", "from": "ref.price",
                        "where": "bu = 'CFS'", "key": ["bu", "security_id", "value_date"]%s}]%s}
            """.formatted(name, expect, verify));
        return file;
    }

    private Cli.Options restoreOptions(Path rule, boolean apply) {
        Cli.Options o = new Cli.Options(); o.restore = rule; o.apply = apply; o.out = tmp.resolve("out"); o.partitions = 2; return o;
    }

    private String livePrice() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT settle_price FROM live.price WHERE security_id = 'B'")) { rs.next(); return rs.getString(1); }
    }

    private String restoreSummary() throws Exception {
        try (var dirs = Files.list(tmp.resolve("out"))) {
            return Files.readString(dirs.filter(p -> Files.exists(p.resolve("RESTORE.md"))).findFirst().orElseThrow().resolve("RESTORE.md"));
        }
    }

    @Test void previewReportsUnmetStepCountsAndApplyRefusesToCommit() throws Exception {
        Path rule = restore("r1", ", \"expect\": {\"replaced\": \"2\", \"inserted\": \"0\"}", "");
        assertThat(new DataRestorer(settings).execute(restoreOptions(rule, false))).isEqualTo(1);
        assertThat(restoreSummary()).contains("## Expected counts").contains("FAIL (replaced expected 2 but is 1)");
        // another rule name: H2 does not roll back the backup-table DDL of the preview (PostgreSQL does)
        Path apply = restore("r1b", ", \"expect\": {\"replaced\": \"2\", \"inserted\": \"0\"}", "");
        assertThatThrownBy(() -> new DataRestorer(settings).execute(restoreOptions(apply, true))).hasMessageContaining("Nothing was committed");
        assertThat(livePrice()).isEqualTo("9.9");
    }

    @Test void metStepCountsPassAndApplyCommits() throws Exception {
        Path rule = restore("r2", ", \"expect\": {\"replaced\": \"1\", \"inserted\": \"0\", \"targetAfter\": \">=2\"}", "");
        assertThat(new DataRestorer(settings).execute(restoreOptions(rule, true))).isZero();
        assertThat(livePrice()).isEqualTo("2.5");
    }

    private void verifyRule(String name, String expects) throws Exception {
        Files.writeString(tmp.resolve(name), """
            <rules name="verify">
              <sql id="diff" db="test">
                SELECT count(*) AS different_rows FROM live.price l JOIN ref.price r ON l.security_id = r.security_id
                WHERE l.settle_price &lt;&gt; r.settle_price
              </sql>
              %s
            </rules>""".formatted(expects));
    }

    @Test void verifyRunsAfterApplyAndReportsPass() throws Exception {
        verifyRule("verify-ok.xml", "<expect ref=\"diff\" column=\"different_rows\" equals=\"0\" reason=\"live equals the reference\"/>");
        Path rule = restore("r3", "", ", \"verify\": [{\"rule\": \"verify-ok.xml\"}]");
        assertThat(new DataRestorer(settings).execute(restoreOptions(rule, true))).isZero();
        assertThat(restoreSummary()).contains("## Verification after apply").contains("`verify-ok.xml`: PASS");
    }

    @Test void verifyFailureAfterApplyGivesExitOneAndPointsAtUndo() throws Exception {
        verifyRule("verify-bad.xml", "<expect ref=\"diff\" column=\"different_rows\" equals=\"5\"/>");
        Path rule = restore("r4", "", ", \"verify\": [{\"rule\": \"verify-bad.xml\"}]");
        assertThat(new DataRestorer(settings).execute(restoreOptions(rule, true))).isEqualTo(1);
        assertThat(restoreSummary()).contains("`verify-bad.xml`: FAIL").contains("did not verify").contains("undo-r4.json");
    }

    @Test void previewDoesNotRunVerify() throws Exception {
        verifyRule("verify-ok.xml", "<expect ref=\"diff\" column=\"different_rows\" equals=\"0\"/>");
        Path rule = restore("r5", "", ", \"verify\": [{\"rule\": \"verify-ok.xml\"}]");
        assertThat(new DataRestorer(settings).execute(restoreOptions(rule, false))).isZero();
        assertThat(restoreSummary()).contains("Not run in preview");
    }

    @Test void verifyRuleWithoutExpectationsIsRejectedBeforeAnythingRuns() throws Exception {
        verifyRule("verify-none.xml", "");
        Path rule = restore("r6", "", ", \"verify\": [{\"rule\": \"verify-none.xml\"}]");
        assertThatThrownBy(() -> new DataRestorer(settings).execute(restoreOptions(rule, true))).hasMessageContaining("no <expect>");
        assertThat(livePrice()).isEqualTo("9.9");
        Path missing = restore("r7", "", ", \"verify\": [{\"rule\": \"absent.xml\"}]");
        assertThatThrownBy(() -> new DataRestorer(settings).execute(restoreOptions(missing, true))).hasMessageContaining("not found");
    }
}
