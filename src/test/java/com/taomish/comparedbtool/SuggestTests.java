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

/** "There is a mismatch: which restore rule should I preview?" The tool says so and never restores by itself. */
class SuggestTests {
    @TempDir Path tmp;
    String url;
    DatasourceSettings settings;
    Path rules;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA live"); s.execute("CREATE SCHEMA ref"); s.execute("CREATE SCHEMA parity_work");
            for (String schema : new String[]{"live", "ref"})
                s.execute("CREATE TABLE " + schema + ".price (uuid VARCHAR(36), bu VARCHAR(5), security_id VARCHAR(20), value_date DATE, settle_price DOUBLE PRECISION, updated_by VARCHAR(20))");
            s.execute("INSERT INTO ref.price VALUES ('u1','CFS','A',DATE '2026-10-02',1.5,'mig'),('u2','CFS','B',DATE '2026-10-02',2.5,'mig')");
            s.execute("INSERT INTO live.price VALUES ('u1','CFS','A',DATE '2026-10-02',1.5,'x'),('u2','CFS','B',DATE '2026-10-02',9.9,'load')");
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setReadOnly(true); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", d);
        rules = Files.createDirectories(tmp.resolve("rules"));
        Files.writeString(rules.resolve("fix.json"), """
            {"name": "fix-prices", "db": "test", "backupSchema": "parity_work", "description": "Puts the CFS prices back from the migration copy.",
             "steps": [{"id": "prices", "table": "live.price", "from": "ref.price", "where": "bu = 'CFS'", "key": ["bu", "security_id", "value_date"]}]}""");
    }

    private static final String PARITY = """
        <sql id="current" db="test">SELECT security_id, settle_price FROM live.price WHERE bu = 'CFS'</sql>
        <sql id="reference" db="test">SELECT security_id, settle_price FROM ref.price WHERE bu = 'CFS'</sql>
        <compare id="cmp" source="reference" target="current"><key columns="security_id"/></compare>""";

    private Path rule(String body) throws Exception {
        Path xml = rules.resolve("parity-" + System.nanoTime() + ".xml");
        Files.writeString(xml, "<rules name=\"parity\">" + body + "</rules>");
        return xml;
    }

    private Cli.Outcome run(Path xml) throws Exception {
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 2;
        return new Cli(settings).executeRules(o);
    }

    private String livePrice() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT settle_price FROM live.price WHERE security_id = 'B'")) { rs.next(); return rs.getString(1); }
    }

    private static final String SUGGEST = "<suggest restore=\"fix.json\" when=\"#cmp.onlySource &gt; 0 or #cmp.different &gt; 0\" reason=\"current differs from the migration copy\"/>";

    @Test void aMismatchSuggestsThePreviewCommandAndNothingIsRestored() throws Exception {
        var outcome = run(rule(PARITY + SUGGEST));
        assertThat(outcome.exit()).isEqualTo(1);                                                         // the comparison differs
        assertThat(outcome.suggestions()).hasSize(1);
        var s = outcome.suggestions().getFirst();
        assertThat(s.name()).isEqualTo("fix-prices");
        assertThat(s.reason()).isEqualTo("current differs from the migration copy");
        assertThat(s.steps()).containsExactly("prices: live.price <- ref.price");
        assertThat(s.command()).startsWith(".\\compare.ps1 --restore ").endsWith("fix.json");
        String summary = Files.readString(outcome.directory().resolve("SUMMARY.md"));
        assertThat(summary).contains("## Suggested next step").contains("**Restore with `fix-prices`** (preview first): current differs from the migration copy")
            .contains("Puts the CFS prices back from the migration copy.").contains("Steps: prices: live.price <- ref.price")
            .contains("for a preview that changes nothing").contains("it does not read from another database");
        assertThat(livePrice()).isEqualTo("9.9");                                                       // the tool did not restore anything
    }

    @Test void noMismatchNoSuggestion() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) { s.execute("UPDATE live.price SET settle_price = 2.5 WHERE security_id = 'B'"); }
        var outcome = run(rule(PARITY + SUGGEST));
        assertThat(outcome.exit()).isZero();
        assertThat(outcome.suggestions()).isEmpty();
        assertThat(Files.readString(outcome.directory().resolve("SUMMARY.md"))).doesNotContain("Suggested next step");
    }

    @Test void aConditionThatCannotBeDecidedIsANoteNotASilentDrop() throws Exception {
        var outcome = run(rule(PARITY + "<sql id=\"bad\" db=\"test\">SELECT * FROM nope</sql><suggest restore=\"fix.json\" when=\"#bad.rows &gt; 0\"/>"));
        assertThat(outcome.suggestions()).isEmpty();
        assertThat(Files.readString(outcome.directory().resolve("SUMMARY.md"))).contains("Note: <suggest> number 1 could not be decided").contains("query bad did not complete");
    }

    @Test void mistakesAreFoundBeforeAnythingRuns() throws Exception {
        Files.writeString(rules.resolve("broken.json"), "{ \"name\": \"x\" ");
        Files.writeString(rules.resolve("nosteps.json"), "{\"name\": \"x\", \"db\": \"test\", \"steps\": []}");
        Files.writeString(rules.resolve("elsewhere.json"), "{\"name\": \"x\", \"db\": \"nowhere\", \"steps\": [{\"id\": \"s\", \"table\": \"a.b\", \"from\": \"c.d\", \"where\": \"1=1\", \"key\": [\"id\"]}]}");
        for (var bad : new String[][]{
            {"<suggest restore=\"missing.json\" when=\"#cmp.onlySource &gt; 0\"/>", "restore rule not found"},
            {"<suggest restore=\"broken.json\" when=\"#cmp.onlySource &gt; 0\"/>", "Cannot read restore rule"},
            {"<suggest restore=\"nosteps.json\" when=\"#cmp.onlySource &gt; 0\"/>", "at least one step"},
            {"<suggest restore=\"elsewhere.json\" when=\"#cmp.onlySource &gt; 0\"/>", "uses datasource 'nowhere', which is not configured"},
            {"<suggest when=\"#cmp.onlySource &gt; 0\"/>", "needs restore="},
            {"<suggest restore=\"fix.json\"/>", "needs when="},
            {"<suggest restore=\"fix.json\" when=\"T(java.lang.Runtime).getRuntime() != null\"/>", "a type reference"},
            {"<suggest restore=\"fix.json\" when=\"#nope.rows &gt; 0\"/>", "#nope is not a parameter"},
        }) {
            Cli.Options o = new Cli.Options(); o.rules = rule(PARITY + bad[0]); o.validate = true;
            assertThatThrownBy(() -> new Cli(settings).executeRules(o)).as(bad[0]).hasMessageContaining(bad[1]);
        }
        Cli.Options ok = new Cli.Options(); ok.rules = rule(PARITY + SUGGEST); ok.validate = true;
        assertThat(new Cli(settings).executeRules(ok).exit()).isZero();
    }

    @Test void theMenuOffersThePreviewAfterTheRun() throws Exception {
        Path xml = rule(PARITY + SUGGEST);
        String key = xml.toString().replace('\\', '/'), restoreKey = rules.resolve("fix.json").toString().replace('\\', '/');
        var ui = new FakeUi().say("/rules", "rule:" + key, "go", "restore:" + restoreKey, "go", "/back", "/back", null);
        new Menu(settings, ui, tmp.resolve("out"), rules).run();
        assertThat(ui.output()).contains("Result: exit 1").contains("fix-prices").contains("Command:  .\\compare.ps1 --restore").contains("Status: PREVIEW");
        assertThat(ui.screens.stream().flatMap(java.util.List::stream).map(Ui.Item::label)).contains("Preview restore: fix-prices");
        assertThat(livePrice()).isEqualTo("9.9");                                                       // a preview changes nothing
    }
}
