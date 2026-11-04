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
 * legacy 1-100; backup 1-96; current 1-90 and 101-103.
 * all three: 1-90 (90); legacy+backup but not current: 91-96 (6); legacy only: 97-100 (4); current only: 101-103 (3). 103 keys, 13 mismatching.
 */
class PresenceTests {
    @TempDir Path tmp;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            for (String t : new String[]{"legacy", "backup", "current_t"}) s.execute("CREATE TABLE " + t + "(id INTEGER, client VARCHAR(5))");
            for (int id = 1; id <= 100; id++) s.execute("INSERT INTO legacy VALUES (" + id + ",'" + (id >= 91 && id <= 96 ? "X" : "C" + id % 7) + "')");
            for (int id = 1; id <= 96; id++) s.execute("INSERT INTO backup VALUES (" + id + ",'" + (id >= 91 ? "X" : "C" + id % 7) + "')");
            for (int id = 1; id <= 90; id++) s.execute("INSERT INTO current_t VALUES (" + id + ",'C" + id % 7 + "')");
            for (int id = 101; id <= 103; id++) s.execute("INSERT INTO current_t VALUES (" + id + ",'N')");
            s.execute("CREATE TABLE exact_ AS SELECT id, CAST(id AS DECIMAL(10,2)) AS dec_id, 'x' AS client FROM legacy");
            s.execute("CREATE TABLE many AS SELECT x AS id, 'M' AS client FROM SYSTEM_RANGE(1, 400)");
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", d);
    }

    private static final String SOURCES = """
        <sql id="legacy" db="test">SELECT id, client FROM legacy</sql>
        <sql id="backup" db="test">SELECT id, client FROM backup</sql>
        <sql id="current" db="test">SELECT id, client FROM current_t</sql>""";

    private Cli.Outcome run(String body) throws Exception { return run(body, 200); }

    private Cli.Outcome run(String body, int sampleRows) throws Exception {
        Path xml = tmp.resolve("r" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"presence\">" + body + "</rules>");
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 4; o.sampleRows = sampleRows;
        return new Cli(settings).executeRules(o);
    }

    private String read(Cli.Outcome outcome, String file) throws Exception { return Files.readString(outcome.directory().resolve(file)); }

    private static final String MATRIX = SOURCES + "<presence id=\"deals\" key=\"id\" sources=\"legacy, backup, current\"/>";

    @Test void everyCombinationIsCountedAndTheBiggestMismatchIsNamed() throws Exception {
        var outcome = run(MATRIX);
        assertThat(outcome.exit()).isEqualTo(1);                                                     // keys are missing somewhere: a difference
        String md = read(outcome, "deals/SUMMARY.md");
        assertThat(md).contains("13 of 103 keys are not in every source; the biggest group: in `legacy` and `backup`, not in `current` (6 keys)")
            .contains("`legacy` lacks 3; `backup` lacks 7; `current` lacks 10.");
        assertThat(md).contains("| legacy | 100 | 100 |").contains("| backup | 96 | 96 |").contains("| current | 93 | 93 |");
        // pattern rows: legacy | backup | current | keys | share
        assertThat(md).contains("| yes | yes | yes | 90 | 87.4% |  |")
            .contains("| yes | yes | - | 6 | 5.8% |").contains("| yes | - | - | 4 | 3.9% |").contains("| - | - | yes | 3 | 2.9% |");
        assertThat(md).contains("`91`, `92`, `93`");
        assertThat(read(outcome, "SUMMARY.md")).contains("- presence deals: 13 of 103 keys are not in every source");
    }

    @Test void theMismatchingKeysAreListedWithTheirCombinationButKeysInEverySourceAreNot() throws Exception {
        var outcome = run(MATRIX + "<presence id=\"all\" key=\"id\" sources=\"legacy, backup, current\" export=\"full\"/>");
        String csv = read(outcome, "all/presence-keys.csv");
        assertThat(csv.lines().count()).isEqualTo(1 + 13);
        assertThat(csv).contains("\"key.id\",\"legacy\",\"backup\",\"current\",\"pattern\"")
            .contains("\"91\",\"yes\",\"yes\",\"\",\"legacy+backup\"").contains("\"97\",\"yes\",\"\",\"\",\"only legacy\"").contains("\"101\",\"\",\"\",\"yes\",\"only current\"");
    }

    @Test void sampleModeCapsTheListButCountsStayExact() throws Exception {
        var outcome = run("""
            <sql id="a" db="test">SELECT id, client FROM many</sql><sql id="b" db="test">SELECT id, client FROM many WHERE id &lt;= 10</sql>
            <presence id="m" key="id" sources="a, b"/>""", 40);                                // 390 keys only in a; cap per combination = max(5, 40/4) = 10
        String md = read(outcome, "m/SUMMARY.md");
        assertThat(md).contains("390 of 400 keys are not in every source").contains("| yes | - | 390 |");
        assertThat(read(outcome, "m/presence-keys.csv").lines().count()).isEqualTo(1 + 10);
        assertThat(md).contains("showing 10 of 390 mismatching keys (at most 10 per combination)");
        var none = run("""
            <sql id="a" db="test">SELECT id, client FROM many</sql><sql id="b" db="test">SELECT id, client FROM many WHERE id &lt;= 10</sql>
            <presence id="m" key="id" sources="a, b" export="none"/>""");
        assertThat(none.directory().resolve("m/presence-keys.csv")).doesNotExist();
        assertThat(read(none, "m/SUMMARY.md")).contains("390 of 400 keys");
    }

    @Test void whatTheMismatchingKeysHaveInCommonIsReported() throws Exception {
        var outcome = run(MATRIX);
        String md = read(outcome, "deals/SUMMARY.md");
        assertThat(md).contains("## What the mismatching keys have in common")
            .contains("**Keys in `legacy` and `backup`, not in `current` (6)**, from the rows of `legacy`")
            .contains("- 6 of 6 keys in this group (100%) have CLIENT = `X`, against 6.0% of all keys of `legacy`");
        assertThat(read(outcome, "FINDINGS.md")).contains("presence deals").contains("What the keys have in common");
    }

    @Test void expectationsAndExpressionsCanJudgeThePresence() throws Exception {
        var pass = run(MATRIX + "<expect ref=\"deals\" mismatched=\"13\" in-all=\">=90\" reason=\"known: the 6 lost since the backup\"/>");
        assertThat(pass.exit()).isZero();
        assertThat(pass.report().headline()).startsWith("PASS (2 of 2");
        var fail = run(MATRIX + "<expect ref=\"deals\" mismatched=\"0\"/>");
        assertThat(fail.exit()).isEqualTo(1);
        assertThat(fail.report().verdicts().getFirst().actual()).startsWith("13 (13 of 103 keys are not in every source");
        var expression = run(MATRIX + "<expect expr=\"#deals.missingIn['current'] == 10 and #deals.inAll == 90 and #p('deals').keys == 103\" judges=\"deals\"/>"
            + "<expect expr=\"#deals.distinct['legacy'] == #deals.rows['legacy']\" judges=\"deals\"/>");
        assertThat(expression.report().verdicts()).allMatch(Expectations.Verdict::pass);
        assertThat(expression.exit()).isZero();
        var shown = run(MATRIX + "<expect expr=\"#deals.mismatched == 0\" judges=\"deals\"/>");
        assertThat(shown.report().verdicts().getFirst().actual()).isEqualTo("false [p(deals): keys=103, inAll=90, mismatched=13]");
    }

    @Test void identicalSourcesGiveNoMismatch() throws Exception {
        var outcome = run(SOURCES + "<sql id=\"legacy2\" db=\"test\">SELECT id, client FROM legacy</sql><presence id=\"same\" key=\"id\" sources=\"legacy, legacy2\"/>");
        assertThat(outcome.exit()).isZero();
        assertThat(read(outcome, "same/SUMMARY.md")).contains("All 100 keys are in all 2 sources");
        assertThat(outcome.directory().resolve("FINDINGS.md")).doesNotExist();
    }

    @Test void numbersMatchByValueWhateverTheirType() throws Exception {
        var outcome = run("""
            <sql id="ints" db="test">SELECT id, client FROM legacy</sql>
            <sql id="decs" db="test">SELECT dec_id AS id, client FROM exact_</sql>
            <presence id="numbers" key="id" sources="ints, decs"/>""");
        assertThat(read(outcome, "numbers/SUMMARY.md")).contains("All 100 keys are in all 2 sources");
    }

    @Test void aFailedSourceFailsOnlyThePresenceAndNamesTheQuery() throws Exception {
        var outcome = run(SOURCES + "<sql id=\"bad\" db=\"test\">SELECT * FROM nope</sql><presence id=\"p1\" key=\"id\" sources=\"legacy, bad\"/>" + "<presence id=\"p2\" key=\"id\" sources=\"legacy, backup\"/>");
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(read(outcome, "MANUAL_ACTIONS.md")).contains("Presence skipped: source query bad failed");
        assertThat(outcome.directory().resolve("p2/SUMMARY.md")).exists();
        var noKey = run(SOURCES + "<sql id=\"nokey\" db=\"test\">SELECT client FROM legacy</sql><presence id=\"p\" key=\"id\" sources=\"legacy, nokey\"/>");
        assertThat(read(noKey, "MANUAL_ACTIONS.md")).contains("query nokey has no key column 'id'").contains("CLIENT");
    }

    @Test void aPresenceWithASkippedSourceIsSkippedNotFailed() throws Exception {
        var outcome = run(SOURCES + "<sql id=\"cond\" db=\"test\" after=\"legacy\" when=\"#legacy.rows == 5\">SELECT id, client FROM legacy</sql>"
            + "<presence id=\"p\" key=\"id\" sources=\"legacy, cond\"/><expect ref=\"p\" mismatched=\"0\"/>");
        assertThat(outcome.exit()).isZero();
        assertThat(read(outcome, "SUMMARY.md")).contains("- p: its source query cond was skipped");
        assertThat(outcome.report().notEvaluated()).containsExactly("p (skipped)");
    }

    @Test void mistakesAreFoundWithoutConnecting() throws Exception {
        for (var bad : new String[][]{
            {"<presence id=\"p\" key=\"id\" sources=\"legacy\"/>", "between 2 and 30 different query ids"},
            {"<presence id=\"p\" key=\"id\" sources=\"legacy, legacy\"/>", "between 2 and 30 different query ids"},
            {"<presence id=\"p\" key=\"id\" sources=\"legacy, ghost\"/>", "unknown source query 'ghost'"},
            {"<presence id=\"p\" sources=\"legacy, backup\"/>", "needs key="},
            {"<presence id=\"legacy\" key=\"id\" sources=\"legacy, backup\"/>", "unique safe filenames"},
            {"<presence id=\"p\" key=\"id\" sources=\"legacy, backup\"/><expect ref=\"p\" rows=\"1\"/>", "is a presence"},
            {"<presence id=\"p\" key=\"id\" sources=\"legacy, backup\"/><expect ref=\"p\"/>", "states nothing"},
            {"<presence id=\"p\" key=\"id\" sources=\"legacy, backup\"/><expect ref=\"legacy\" mismatched=\"0\"/>", "apply to a <presence>"},
            {"<presence id=\"p\" key=\"id\" sources=\"legacy, backup\"/><expect ref=\"p\" mismatched=\"lots\"/>", "Invalid expected count"},
        }) {
            Path file = tmp.resolve("v" + Math.abs(bad[0].hashCode()) + ".xml");
            Files.writeString(file, "<rules name=\"v\">" + SOURCES + bad[0] + "</rules>");
            Cli.Options o = new Cli.Options(); o.rules = file; o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).as(bad[0]).hasMessageContaining(bad[1]);
        }
    }

    @Test void thePatternHelpers() {
        var names = java.util.List.of("legacy", "backup", "current");
        assertThat(PresenceRunner.describe(0b011, names)).isEqualTo("in `legacy` and `backup`, not in `current`");
        assertThat(PresenceRunner.describe(0b100, names)).isEqualTo("in `current`, not in `legacy` or `backup`");
        assertThat(PresenceRunner.describe(0b111, names)).isEqualTo("in `legacy` and `backup` and `current`");
    }
}
