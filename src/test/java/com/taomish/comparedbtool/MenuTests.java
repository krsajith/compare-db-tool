package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Isolated in-memory H2 only; the terminal itself is replaced by FakeUi. */
class MenuTests {
    @TempDir Path tmp;
    String url;
    DatasourceSettings settings;
    Path rules, out;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA live"); s.execute("CREATE SCHEMA ref"); s.execute("CREATE SCHEMA parity_work");
            s.execute("CREATE TABLE source_data(id INTEGER, amount DECIMAL(25,3))");
            s.execute("CREATE TABLE target_data(id INTEGER, amount DECIMAL(25,3))");
            s.execute("INSERT INTO source_data VALUES(1,10),(2,20),(3,30)");
            s.execute("INSERT INTO target_data VALUES(1,10),(2,20),(4,40)");
            for (String schema : new String[]{"live", "ref"})
                s.execute("CREATE TABLE " + schema + ".price (uuid VARCHAR(36), bu VARCHAR(5), security_id VARCHAR(20), value_date DATE, settle_price DOUBLE PRECISION, updated_by VARCHAR(20))");
            s.execute("INSERT INTO ref.price VALUES ('u2','CFS','B',DATE '2026-10-02',2.5,'mig')");
            s.execute("INSERT INTO live.price VALUES ('u2','CFS','B',DATE '2026-10-02',9.9,'load')");
        }
        settings = settings("test", false);
        rules = Files.createDirectories(tmp.resolve("rules"));
        out = tmp.resolve("out");
        Files.writeString(rules.resolve("trades.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- Trades parity. Second sentence is not shown in the list. -->
            <rules name="trades">
              <parameters><param name="minId" type="integer" value="1"/></parameters>
              <sql id="left" db="test">SELECT id, amount FROM source_data WHERE id >= :minId</sql>
              <sql id="right" db="test">SELECT id, amount FROM target_data WHERE id >= :minId</sql>
              <compare id="cmp" source="left" target="right"><key columns="id"/></compare>
              <expect ref="cmp" only-source="1" only-target="1" reason="seeded"/>
            </rules>""");
        Files.writeString(rules.resolve("fix.json"), """
            {"name": "fix", "db": "test", "backupSchema": "parity_work", "description": "Puts the price back.",
             "steps": [{"id": "prices", "table": "live.price", "from": "ref.price", "where": "bu = 'CFS'", "key": ["bu", "security_id", "value_date"]}]}""");
    }

    private DatasourceSettings settings(String name, boolean prod) {
        var s = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword("");
        d.setReadOnly(true); d.setDriverClassName("org.h2.Driver"); d.setProd(prod);
        s.getDatasources().put(name, d);
        return s;
    }

    private String key(String file) { return rules.resolve(file).toString().replace('\\', '/'); }
    private Menu menu(FakeUi ui) { return new Menu(settings, ui, out, rules); }

    private String price() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT settle_price FROM live.price")) { rs.next(); return rs.getString(1); }
    }

    // ------------------------------------------------------------------ pure pieces

    @Test void filterMatchesWordsAndSlashCommands() {
        var items = List.of(new Ui.Item("rule:a", "rrr-parity-cfs", "RRR parity for CFS"), new Ui.Item("rule:b", "grasa-funnel", "GRASA"),
            new Ui.Item("/rules", "/rules", "all rules"), new Ui.Item("/restore", "/restore", "restore"), new Ui.Item("/quit", "/quit", "leave"));
        assertThat(Ui.filter(items, "")).hasSize(5);
        assertThat(Ui.filter(items, "parity cfs")).extracting(Ui.Item::id).containsExactly("rule:a");
        assertThat(Ui.filter(items, "GRASA")).extracting(Ui.Item::id).containsExactly("rule:b");
        assertThat(Ui.filter(items, "/")).extracting(Ui.Item::id).containsExactly("/rules", "/restore", "/quit");
        assertThat(Ui.filter(items, "/r")).extracting(Ui.Item::id).containsExactly("/rules", "/restore");
        assertThat(Ui.filter(items, "/res")).extracting(Ui.Item::id).containsExactly("/restore");
        assertThat(Ui.filter(items, "/est")).extracting(Ui.Item::id).containsExactly("/restore");   // not a prefix: falls back to contains
        assertThat(Ui.filter(items, "nothing")).isEmpty();
    }

    @Test void catalogTellsKindsAndShowsFirstSentence() throws Exception {
        Files.writeString(rules.resolve("tpl-in.json"), "{\"db\":\"test\",\"mode\":\"replace\",\"templates\":[]}");
        Files.writeString(rules.resolve("tpl-out.json"), "{\"db\":\"test\",\"templates\":[]}");
        Files.writeString(rules.resolve("notes.json"), "[1,2]");
        Files.writeString(rules.resolve("readme.xml"), "<other/>");
        var entries = RuleCatalog.scan(rules);
        assertThat(entries).extracting(RuleCatalog.Entry::name).containsExactly("fix", "tpl-in", "tpl-out", "trades");
        assertThat(entries).extracting(RuleCatalog.Entry::kind).containsExactly(RuleCatalog.Kind.RESTORE,
            RuleCatalog.Kind.TEMPLATE_IMPORT, RuleCatalog.Kind.TEMPLATE_EXPORT, RuleCatalog.Kind.COMPARE);
        assertThat(entries.getLast().description()).isEqualTo("Trades parity.");
        assertThat(entries.getFirst().description()).isEqualTo("Puts the price back.");
        assertThat(RuleCatalog.tidy("Generated by D:/oga/x/generate_rules.py. Cash refresh 1: preflight. More.")).isEqualTo("Cash refresh 1: preflight.");
    }

    @Test void commandLineShowsOnlyWhatDiffersFromDefaults() {
        var entry = RuleCatalog.describe(rules.resolve("trades.xml"));
        var plan = new RunPlan(entry);
        assertThat(plan.commandLine()).endsWith("trades.xml").startsWith(".\\compare.ps1 --rules ");
        plan.params.put("minId", "2"); plan.params.put("note", "a b");
        plan.export = ExportMode.FULL; plan.parallel = 2; plan.limit = 50; plan.allowProd = true;
        assertThat(plan.commandLine()).contains("--param minId=2").contains("--param \"note=a b\"").contains("--export full")
            .contains("--parallel 2").contains("--limit 50").endsWith("--allow-prod");
        var options = plan.toOptions(out);
        assertThat(options.rules).isEqualTo(entry.path());
        assertThat(options.parameters).containsEntry("minId", "2");
        assertThat(options.export).isEqualTo(ExportMode.FULL);
        plan.mode = RunPlan.Mode.VALIDATE;
        assertThat(plan.toOptions(out).validate).isTrue();
        var restore = new RunPlan(RuleCatalog.describe(rules.resolve("fix.json")));
        assertThat(restore.mode).isEqualTo(RunPlan.Mode.PREVIEW);
        restore.mode = RunPlan.Mode.APPLY;
        assertThat(restore.toOptions(out).restore).isEqualTo(rules.resolve("fix.json"));
        assertThat(restore.toOptions(out).apply).isTrue();
        assertThat(restore.commandLine()).contains("--restore").endsWith("--apply");
    }

    @Test void historyKeepsRecentDistinctRunsFavouritesAndSurvivesCorruption() throws Exception {
        Path file = tmp.resolve("h/.history.json");
        var h = new History(file);
        for (int i = 0; i < 3; i++) h.add(run("r1", Map.of("a", "1"), i));
        h.add(run("r2", Map.of(), 0));
        h.add(run("r1", Map.of("a", "2"), 1));
        assertThat(h.recent(10)).hasSize(3);                                  // r1 a=2, r2, r1 a=1
        assertThat(h.lastParams("r1")).containsEntry("a", "2");
        assertThat(h.toggleFavorite("r2")).isTrue();
        var reloaded = new History(file);
        assertThat(reloaded.runs()).hasSize(5);
        assertThat(reloaded.isFavorite("r2")).isTrue();
        assertThat(reloaded.toggleFavorite("r2")).isFalse();
        for (int i = 0; i < History.KEEP + 20; i++) reloaded.add(run("bulk", Map.of(), 0));
        assertThat(new History(file).runs()).hasSize(History.KEEP);
        Files.writeString(file, "{ not json");
        assertThat(new History(file).runs()).isEmpty();
        assertThat(file.resolveSibling(".history.json.bad")).exists();
    }

    private static History.Run run(String rule, Map<String, String> params, int exit) {
        var r = new History.Run();
        r.rule = rule; r.kind = "COMPARE"; r.mode = "RUN"; r.params.putAll(params); r.exit = exit; r.started = Instant.now().toString();
        return r;
    }

    @Test void agoReadsNaturally() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        assertThat(Menu.ago(now.minusSeconds(20), now)).isEqualTo("just now");
        assertThat(Menu.ago(now.minusSeconds(5 * 60), now)).isEqualTo("5 min ago");
        assertThat(Menu.ago(now.minusSeconds(3 * 3600), now)).isEqualTo("3 h ago");
        assertThat(Menu.ago(now.minusSeconds(3 * 86400), now)).isEqualTo("3 d ago");
    }

    @Test void plainUiPicksByNumberTextOrCommandAndEnterCancels() {
        var items = List.of(new Ui.Item("a", "alpha", ""), new Ui.Item("b", "beta", "second"), new Ui.Item("/quit", "/quit", "leave"));
        assertThat(plain("2\n").pick("t", items, null)).isEqualTo("b");
        assertThat(plain("alp\n").pick("t", items, null)).isEqualTo("a");
        assertThat(plain("/q\n").pick("t", items, null)).isEqualTo("/quit");
        assertThat(plain("\n").pick("t", items, null)).isNull();
        assertThat(plain("9\n1\n").pick("t", items, null)).isEqualTo("a");           // out of range asks again
        assertThat(plain("zzz\n2\n").pick("t", items, null)).isEqualTo("b");         // no match asks again
        assertThat(plain("\n").ask("Value: ", "7")).isEqualTo("7");                   // Enter keeps the suggestion
        assertThat(plain("9\n").ask("Value: ", "7")).isEqualTo("9");
    }

    private PlainUi plain(String input) {
        return new PlainUi(new BufferedReader(new StringReader(input)), new PrintStream(new ByteArrayOutputStream()));
    }

    // ------------------------------------------------------------------ the flow

    @Test void runWithChangedParameterAsksToConfirmRunsAndRemembers() throws Exception {
        var ui = new FakeUi().say("/rules", "rule:" + key("trades.xml"), "params", "2", "go", "y", "/back", null);
        // minId=2: source {2,3} vs target {2,4}: one only-source, one only-target, as the rule expects
        assertThat(menu(ui).run()).isZero();
        assertThat(ui.output()).contains("minId: 1 -> 2").contains("Command:  .\\compare.ps1 --rules").contains("--param minId=2")
            .contains("Result: exit 0").contains("Verdict: PASS (2 of 2 expectations met)").contains("Tool: compare-db-tool");
        var h = new History(out.resolve(".history.json"));
        assertThat(h.runs()).hasSize(1);
        assertThat(h.runs().getFirst().params).containsEntry("minId", "2");
        assertThat(h.runs().getFirst().exit).isZero();
        assertThat(ui.prompts).anyMatch(p -> p.startsWith("minId (integer, declared 1)") && p.endsWith("[1]"));
        // next time the last value is offered, and the main screen lists the run
        var again = new FakeUi().say(null);
        menu(again).run();
        assertThat(again.screens.getFirst()).extracting(Ui.Item::id).first().isEqualTo("run:0");
    }

    @Test void invalidParameterIsRejectedAndAskedAgain() throws Exception {
        var ui = new FakeUi().say("/rules", "rule:" + key("trades.xml"), "params", "abc", "3", "/back", null, null);
        menu(ui).run();
        assertThat(ui.output()).contains("Invalid value/type for parameter minId");
        assertThat(ui.prompts).hasSize(2);
    }

    @Test void failingExpectationShowsFailAndExitOne() throws Exception {
        // default minId=1: source {1,2,3}, target {1,2,4} still one/one; make it fail with a different expectation
        Files.writeString(rules.resolve("strict.xml"), Files.readString(rules.resolve("trades.xml")).replace("only-source=\"1\"", "only-source=\"0\""));
        var ui = new FakeUi().say("/rules", "rule:" + key("strict.xml"), "go", "/back", null);
        menu(ui).run();
        assertThat(ui.output()).contains("Result: exit 1").contains("Verdict: FAIL (1 of 2");
    }

    @Test void productionNeedsItsNameTypedAndWrongTextCancels() throws Exception {
        settings = settings("test", true);
        var wrong = new FakeUi().say("/rules", "rule:" + key("trades.xml"), "go", "nope", "/back", null);
        menu(wrong).run();
        assertThat(wrong.output()).contains("PRODUCTION datasource: test");
        assertThat(new History(out.resolve(".history.json")).runs()).isEmpty();
        var right = new FakeUi().say("/rules", "rule:" + key("trades.xml"), "go", "test", "/back", null);
        menu(right).run();
        assertThat(new History(out.resolve(".history.json")).runs()).hasSize(1);
        assertThat(right.output()).contains("--allow-prod");
    }

    @Test void unconfiguredDatasourceBlocksRunAndValidateButConfiguredValidateNeedsNoConfirmation() throws Exception {
        settings = settings("other", false);
        var ui = new FakeUi().say("/rules", "rule:" + key("trades.xml"), "go", "validate", "go", "/back", null);
        menu(ui).run();
        assertThat(ui.output()).contains("Blocked: datasource test is not in config/datasources.properties").contains("(NOT CONFIGURED)");
        assertThat(new History(out.resolve(".history.json")).runs()).isEmpty();
        // configured and marked production: validating opens no connection, so no typed confirmation is asked
        settings = settings("test", true);
        var ok = new FakeUi().say("/rules", "rule:" + key("trades.xml"), "validate", "go", "/back", null);
        menu(ok).run();
        var runs = new History(out.resolve(".history.json")).runs();
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().mode).isEqualTo("VALIDATE");
        assertThat(runs.getFirst().exit).isZero();
        assertThat(ok.prompts).isEmpty();
    }

    @Test void rowLimitMustBeConfirmedBecauseResultsArePartial() throws Exception {
        var ui = new FakeUi().say("/rules", "rule:" + key("trades.xml"), "limit", "2", "go", "n", "/back", null);
        menu(ui).run();
        assertThat(ui.titles).anyMatch(t -> t.contains("PARTIAL"));
        assertThat(new History(out.resolve(".history.json")).runs()).isEmpty();
    }

    @Test void restorePreviewChangesNothingAndApplyNeedsTypedApply() throws Exception {
        var ui = new FakeUi().say("/restore", "rule:" + key("fix.json"), "go", "apply", "APPLY", "/back", null);
        ui.slowOnApply = true;
        menu(ui).run();
        assertThat(ui.output()).contains("Status: APPLIED");
        assertThat(price()).isEqualTo("2.5");
        var runs = new History(out.resolve(".history.json")).runs();
        assertThat(runs).extracting(r -> r.mode).containsExactly("PREVIEW", "APPLY");
    }

    @Test void wrongTextDoesNotApplyAndHistoryNeverReappliesDirectly() throws Exception {
        var ui = new FakeUi().say("/restore", "rule:" + key("fix.json"), "go", "apply", "yes please", "/back", null);
        menu(ui).run();
        assertThat(price()).isEqualTo("9.9");
        // a recorded APPLY comes back as a preview
        var r = run(rules.resolve("fix.json").toString().replace('\\', '/'), Map.of(), 0);
        r.kind = "RESTORE"; r.mode = "APPLY";
        new History(out.resolve(".history.json")).add(r);
        var again = new FakeUi().say("run:" + (new History(out.resolve(".history.json")).runs().size() - 1), null, null);
        menu(again).run();
        assertThat(again.titles).anyMatch(t -> t.endsWith(": preview"));
    }

    @Test void brokenRuleIsExplainedNotCrashed() throws Exception {
        Files.writeString(rules.resolve("broken.xml"), "<rules><sql id=\"x\" db=\"test\">SELECT 1</sql><bogus/></rules>");
        var ui = new FakeUi().say("/rules", "rule:" + key("broken.xml"), "/rules", null, null);
        menu(ui).run();
        assertThat(ui.output()).contains("This rule cannot be loaded");
    }
}
