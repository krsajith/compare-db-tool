package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressTests {
    @TempDir Path tmp;

    static String captured(Callable<?> work) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        try { work.call(); } finally { System.setOut(original); }
        return buffer.toString();
    }

    @Test void wording() {
        assertThat(Progress.count(1234567)).isEqualTo("1,234,567");
        assertThat(Progress.duration(850)).isEqualTo("850 ms");
        assertThat(Progress.duration(4200)).isEqualTo("4.2 s");
        assertThat(Progress.duration(125_000)).isEqualTo("2 min 5 s");
        assertThat(Progress.duration(3_900_000)).isEqualTo("1 h 5 min");
        assertThat(Progress.size(900)).isEqualTo("900 bytes");
        assertThat(Progress.size(1536)).isEqualTo("1.5 KB");
        assertThat(Progress.size(3 * 1048576)).isEqualTo("3.0 MB");
        assertThat(Progress.queryFound("legacy", "mysql-oprdb02", 9251, 4200, false)).isEqualTo("  OK  found 9,251 rows in legacy (mysql-oprdb02) in 4.2 s");
        assertThat(Progress.queryFound("one", "pg", 1, 20, false)).contains("found 1 row in one");
        assertThat(Progress.queryFound("cap", "pg", 10, 20, true)).startsWith("  PARTIAL").contains("do not rely");
        assertThat(Progress.queryFailed("q", "pg", "boom\n  with   spaces")).isEqualTo("  FAILED q on pg: boom with spaces (details: MANUAL_ACTIONS.md)");
        assertThat(Progress.queryFailed("q", "pg", "x".repeat(400))).hasSizeLessThan(330).contains("...");
    }

    @Test void longRunningStepsAnnounceThemselvesAndReportEveryHeartbeat() throws Exception {
        Progress progress = new Progress();
        progress.heartbeatSeconds(1);
        String text = captured(() -> {
            var handle = progress.start("slow-query", "postgres-dev");
            handle.rows(340_000);
            Thread.sleep(1700);
            handle.done();
            Thread.sleep(1200);                        // no more lines after done()
            return null;
        });
        assertThat(text).contains("  > slow-query on postgres-dev ...").contains("  ... slow-query on postgres-dev still running (1.0 s, 340,000 rows so far)");
        assertThat(text.split("still running", -1).length - 1).isEqualTo(1);
    }

    @Test void quietSilencesEverything() throws Exception {
        Progress progress = new Progress();
        progress.quiet(true);
        assertThat(captured(() -> { progress.line("hello"); progress.start("q", "db").done(); return null; })).isEmpty();
    }

    private Cli.Options options(Path xml, boolean quiet) {
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 4; o.quiet = quiet; return o;
    }

    @Test void aRunTellsWhatItFoundAndHowItWent() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE a(id INTEGER, v INTEGER)"); s.execute("CREATE TABLE b(id INTEGER, v INTEGER)");
            s.execute("INSERT INTO a VALUES (1,10),(2,20),(3,30)"); s.execute("INSERT INTO b VALUES (1,10),(2,99),(4,40)");
        }
        var settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", d);
        Path xml = tmp.resolve("r.xml");
        Files.writeString(xml, """
            <rules name="demo">
              <sql id="legacy" db="test">SELECT id, v FROM a</sql><sql id="fresh" db="test">SELECT id, v FROM b</sql><sql id="broken" db="test">SELECT * FROM nope</sql>
              <compare id="cmp" source="legacy" target="fresh"><key columns="id"/></compare>
              <expect ref="cmp" different="0" reason="should be clean"/>
              <compress id="z" file="demo.zip"><include ref="cmp"/></compress>
            </rules>""");
        String text = captured(() -> new Cli(settings).executeRules(options(xml, false)));
        assertThat(text).contains("compare-db-tool: demo - 3 queries on test, 1 comparison; export sample")
            .contains("  output: ")
            .contains("  > legacy on test ...")
            .contains("found 3 rows in legacy (test) in")
            .contains("  FAILED broken on test:")
            .contains("  compare cmp: 2 matched (1 equal, 1 differ), 1 only in legacy, 1 only in fresh")
            .contains("  expectations: FAIL (1 of 1 expectations not met)")
            .contains("    FAIL cmp: different expected 0, got 1 (should be clean)")
            .contains("  zip: demo.zip (")
            .contains("1 item needs attention:")
            .containsPattern("compare-db-tool: done in .* - exit 2 \\(error or PARTIAL result\\), expectations FAIL")
            .contains("  summary: ");
        String quiet = captured(() -> new Cli(settings).executeRules(options(xml, true)));
        assertThat(quiet).doesNotContain("compare-db-tool:").doesNotContain("found ").doesNotContain("  compare cmp").doesNotContain("  summary:");
        assertThat(quiet).doesNotContain("\tat ");                      // no stack trace on the console, the log files have it
        Cli.Options validate = options(xml, false); validate.validate = true;
        assertThat(captured(() -> new Cli(settings).executeRules(validate))).contains("rules are valid - 3 queries, 1 comparisons, 0 funnels, 1 expectations, 1 ZIP. No connection was opened.");
    }
}
