package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataChangerTests {
    @TempDir Path tmp;
    String url;
    DatasourceSettings settings;
    final List<String> phrases = new ArrayList<>();

    static final String KEY = "[\"bu\", \"security_id\", \"value_date\"]";

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA live"); s.execute("CREATE SCHEMA parity_work");
            s.execute("CREATE TABLE live.price (uuid VARCHAR(36), bu VARCHAR(5), security_id VARCHAR(20), value_date DATE, settle_price DOUBLE PRECISION, updated_by VARCHAR(20))");
            s.execute("INSERT INTO live.price VALUES ('u1','CFS','A',DATE '2026-10-02',1.5,'x'),('u2','CFS','B',DATE '2026-10-02',9.9,'load'),"
                + "('u4','CFS','D',DATE '2026-10-02',4.0,'load'),('u9','GRN','A',DATE '2026-10-02',7.0,'other')");
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword("");
        d.setReadOnly(true); d.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("test", d);
    }

    private Path write(String name, String json) throws Exception {
        Path file = tmp.resolve(name + ".json");
        Files.writeString(file, json);
        return file;
    }

    private Path updateB(String name, String extra) throws Exception {
        return write(name, """
            {"name": "%s", "db": "test", "ticket": "T-1", %s
             "steps": [{"id": "fix-b", "action": "update", "table": "live.price",
                        "where": "bu = 'CFS' AND security_id = 'B'", "key": %s,
                        "set": {"settle_price": "2.5", "updated_by": "'chg'"},
                        "expect": {"targetBefore": "1", "affected": "1", "targetAfter": "1"}}]}
            """.formatted(name, extra, KEY));
    }

    private Path deleteD(String name, String extra) throws Exception {
        return write(name, """
            {"name": "%s", "db": "test", %s
             "steps": [{"id": "drop-d", "action": "delete", "table": "live.price",
                        "where": "bu = 'CFS' AND security_id = 'D'", "key": %s,
                        "expect": {"targetBefore": "1", "affected": "1", "targetAfter": "0"}}]}
            """.formatted(name, extra, KEY));
    }

    private Cli.Options options(Path rule, boolean apply) {
        Cli.Options o = new Cli.Options(); o.change = rule; o.apply = apply; o.out = tmp.resolve("out");
        o.confirmer = (message, phrase) -> { phrases.add(phrase); return true; };
        return o;
    }

    private int run(Path rule, boolean apply) throws Exception { return new DataChanger(settings).execute(options(rule, apply)); }

    private String query(String sql) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) { for (int i = 1; i <= n; i++) sb.append(rs.getString(i)).append(i < n ? "|" : ";"); }
        }
        return sb.toString();
    }

    private String prices() throws Exception { return query("SELECT bu, security_id, settle_price FROM live.price ORDER BY bu, security_id, value_date").replace("|", ":"); }

    private long changelogRows() throws Exception {
        return Long.parseLong(query("SELECT count(*) FROM parity_work.cdt_changelog").replace(";", ""));
    }

    private Path newestRunDir() throws Exception {
        try (var s = Files.list(tmp.resolve("out"))) { return s.filter(Files::isDirectory).max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow(); }
    }

    private String summary() throws Exception { return Files.readString(newestRunDir().resolve("CHANGE.md")); }

    // 1
    @Test void previewChangesNothingAndWritesNoChangelog() throws Exception {
        String before = prices();
        assertThat(run(updateB("c1", ""), false)).isZero();
        assertThat(prices()).isEqualTo(before);
        assertThat(query("SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = 'cdt_changelog'")).isEqualTo("0;");
        assertThat(summary()).contains("PREVIEW").contains("| fix-b | update | live.price | 1 | 1 | 1 | (rolled back) |");
        assertThat(phrases).isEmpty();
    }

    // 2
    @Test void applyUpdateBacksUpWritesUndoAndOneChangelogRow() throws Exception {
        assertThat(run(updateB("c2", ""), true)).isZero();
        assertThat(prices()).contains("CFS:B:2.5");
        assertThat(phrases).containsExactly("APPLY");
        String backup = query("SELECT backup_table FROM parity_work.cdt_changelog").replace(";", "");
        assertThat(backup).startsWith("parity_work.change_c2_fix_b_");
        assertThat(query("SELECT settle_price FROM " + backup)).isEqualTo("9.9;");
        assertThat(query("SELECT status || '|' || mode || '|' || risk || '|' || ticket || '|' || rows_before || '|' || rows_affected || '|' || rows_after FROM parity_work.cdt_changelog"))
            .isEqualTo("APPLIED|CHANGE|NORMAL|T-1|1|1|1;");
        assertThat(newestRunDir().resolve("undo-c2.json")).exists();
        assertThat(query("SELECT undo_rule FROM parity_work.cdt_changelog")).contains("undo-c2.json");
    }

    // 3
    @Test void failedPreconditionRollsBackAndLogsFailure() throws Exception {
        Path rule = write("c3", """
            {"name": "c3", "db": "test",
             "steps": [{"id": "s", "action": "delete", "table": "live.price", "where": "security_id = 'D'", "key": %s,
                        "preconditions": [{"sql": "SELECT count(*) FROM live.price WHERE {{where}}", "expect": "0", "reason": "D must not exist"}],
                        "expect": {"affected": "1"}}]}
            """.formatted(KEY));
        String before = prices();
        assertThatThrownBy(() -> run(rule, true)).hasMessageContaining("D must not exist");
        assertThat(prices()).isEqualTo(before);
        assertThat(query("SELECT status FROM parity_work.cdt_changelog")).isEqualTo("FAILED;");
        assertThat(summary()).contains("FAILED").contains("D must not exist");
    }

    // 3b: a later step's precondition sees the effect of an earlier step
    @Test void preconditionOfLaterStepSeesEarlierStep() throws Exception {
        Path rule = write("c3b", """
            {"name": "c3b", "db": "test",
             "steps": [{"id": "s1", "action": "delete", "table": "live.price", "where": "security_id = 'D'", "key": %s, "expect": {"affected": "1"}},
                       {"id": "s2", "action": "delete", "table": "live.price", "where": "security_id = 'B'", "key": %s,
                        "preconditions": [{"sql": "SELECT count(*) FROM live.price WHERE security_id = 'D'", "expect": "0", "reason": "D already gone"}],
                        "expect": {"affected": "1"}}]}
            """.formatted(KEY, KEY));
        assertThat(run(rule, true)).isZero();
        assertThat(prices()).isEqualTo("CFS:A:1.5;GRN:A:7.0;");
    }

    // 4
    @Test void affectedMismatchRollsBack() throws Exception {
        Path rule = write("c4", """
            {"name": "c4", "db": "test",
             "steps": [{"id": "s", "action": "update", "table": "live.price", "where": "bu = 'CFS'", "key": %s,
                        "set": {"settle_price": "0"}, "expect": {"affected": "1"}}]}
            """.formatted(KEY));
        String before = prices();
        assertThatThrownBy(() -> run(rule, true)).hasMessageContaining("affected expected 1 but is 3");
        assertThat(prices()).isEqualTo(before);
        // (the backup table is not asserted away: H2 commits DDL at once, PostgreSQL rolls it back with the transaction)
    }

    @Test void applyWithoutExpectIsRefusedButPreviewShowsCounts() throws Exception {
        Path rule = write("c4b", """
            {"name": "c4b", "db": "test",
             "steps": [{"id": "s", "action": "delete", "table": "live.price", "where": "security_id = 'D'", "key": %s}]}
            """.formatted(KEY));
        assertThat(run(rule, false)).isZero();
        assertThat(summary()).contains("| s | delete | live.price | 1 | 1 | 0 |");
        assertThatThrownBy(() -> run(rule, true)).hasMessageContaining("needs expect");
    }

    // 5
    @Test void deleteThenUndoPutsRowsBackAndMarksOriginalUndone() throws Exception {
        String before = prices();
        run(deleteD("c5", ""), true);
        assertThat(prices()).doesNotContain("CFS:D");
        Path undo = newestRunDir().resolve("undo-c5.json");
        assertThat(undo).exists();
        Cli.Options o = new Cli.Options(); o.restore = undo; o.apply = true; o.out = tmp.resolve("out2");
        new DataRestorer(settings).execute(o);
        assertThat(prices()).isEqualTo(before);
        assertThat(query("SELECT mode || '|' || status FROM parity_work.cdt_changelog ORDER BY id")).isEqualTo("CHANGE|UNDONE;UNDO|APPLIED;");
        assertThat(query("SELECT count(*) FROM parity_work.cdt_changelog WHERE undo_of_run_id IS NOT NULL AND mode = 'UNDO'")).isEqualTo("1;");
    }

    @Test void updateUndoFindsRowsByKeyEvenThoughTheWhereNoLongerMatches() throws Exception {
        Path rule = write("c5b", """
            {"name": "c5b", "db": "test",
             "steps": [{"id": "s", "action": "update", "table": "live.price", "where": "bu = 'CFS' AND updated_by = 'load'", "key": %s,
                        "set": {"settle_price": "0", "updated_by": "'moved'"}, "expect": {"targetBefore": "2", "affected": "2", "targetAfter": "0"}}]}
            """.formatted(KEY));
        String before = query("SELECT security_id, settle_price FROM live.price ORDER BY security_id, bu");
        run(rule, true);
        assertThat(query("SELECT count(*) FROM live.price WHERE updated_by = 'moved'")).isEqualTo("2;");
        Cli.Options o = new Cli.Options(); o.restore = newestRunDir().resolve("undo-c5b.json"); o.apply = true; o.out = tmp.resolve("out2");
        new DataRestorer(settings).execute(o);
        assertThat(query("SELECT security_id, settle_price FROM live.price ORDER BY security_id, bu")).isEqualTo(before);
    }

    // 6
    @Test void sameRuleAgainIsRefusedUnlessRerun() throws Exception {
        Path rule = write("c6", """
            {"name": "c6", "db": "test",
             "steps": [{"id": "s", "action": "update", "table": "live.price", "where": "security_id = 'A' AND bu = 'CFS'", "key": %s,
                        "set": {"updated_by": "'again'"}, "expect": {"affected": "1"}}]}
            """.formatted(KEY));
        run(rule, true);
        assertThatThrownBy(() -> run(rule, true)).hasMessageContaining("already applied").hasMessageContaining("--rerun");
        Thread.sleep(1100);   // the backup name carries the time to the second
        Cli.Options o = options(rule, true); o.rerun = true;
        assertThat(new DataChanger(settings).execute(o)).isZero();
        assertThat(query("SELECT count(*) FROM parity_work.cdt_changelog WHERE status = 'APPLIED'")).isEqualTo("2;");
    }

    // 9
    @Test void riskIsDetected() throws Exception {
        DataChanger.Rule r = DataChanger.load(write("c9", """
            {"name": "c9", "db": "test", "steps": [
              {"id": "a", "action": "delete", "table": "live.price", "where": "security_id = 'D'", "key": ["bu"]},
              {"id": "b", "action": "delete", "table": "live.price", "where": "1 = 1", "key": ["bu"]},
              {"id": "c", "action": "delete", "table": "live.price", "where": "security_id = 'D'; DROP TABLE x", "key": ["bu"]},
              {"id": "d", "action": "sql", "sql": "CREATE TABLE zz (a int)"},
              {"id": "e", "action": "sql", "sql": "DELETE FROM live.price WHERE security_id = 'D'"},
              {"id": "f", "action": "sql", "sql": "DELETE FROM live.price WHERE security_id = 'D'", "table": "live.price", "where": "security_id = 'D'", "key": ["bu"]},
              {"id": "g", "action": "update", "table": "live.price", "where": "created_by = 'update; drop'", "key": ["bu"], "set": {"updated_by": "'x'"}}
            ]}"""));
        var risk = r.steps.stream().collect(java.util.stream.Collectors.toMap(s -> s.id, DataChanger::riskReasons));
        assertThat(risk.get("a")).isEmpty();
        assertThat(risk.get("b")).anyMatch(x -> x.contains("always true"));
        assertThat(risk.get("c")).anyMatch(x -> x.contains("semicolon")).anyMatch(x -> x.contains("drop"));
        assertThat(risk.get("d")).anyMatch(x -> x.contains("not a plain UPDATE")).anyMatch(x -> x.contains("NO BACKUP"));
        assertThat(risk.get("e")).anyMatch(x -> x.contains("NO BACKUP"));
        assertThat(risk.get("f")).isEmpty();
        assertThat(risk.get("g")).as("words inside a string literal are not statements").isEmpty();
    }

    private Path rawNoScope(String name, String extra) throws Exception {
        return write(name, """
            {"name": "%s", "db": "test", %s
             "steps": [{"id": "raw", "action": "sql", "sql": "DELETE FROM live.price WHERE security_id = 'D'", "expect": {"affected": "1"}}]}
            """.formatted(name, extra));
    }

    @Test void riskyChangeNeedsTheGeneratedPhrase() throws Exception {
        Path rule = rawNoScope("c9b", "");
        run(rule, true);
        assertThat(phrases).containsExactly("APPLY 1 SQL test -");
        assertThat(prices()).doesNotContain("CFS:D");
        assertThat(query("SELECT backup_table IS NULL, risk FROM parity_work.cdt_changelog")).startsWith("TRUE|RISKY: ").contains("NO BACKUP");
        assertThat(summary()).contains("NO BACKUP").contains("No automatic undo");
    }

    @Test void wrongPhraseCommitsNothingAndLogsRolledBack() throws Exception {
        Path rule = rawNoScope("c9c", "");
        String before = prices();
        Cli.Options o = options(rule, true);
        o.confirmer = (message, phrase) -> false;
        assertThat(new DataChanger(settings).execute(o)).isEqualTo(1);
        assertThat(prices()).isEqualTo(before);
        assertThat(query("SELECT status FROM parity_work.cdt_changelog")).isEqualTo("ROLLED_BACK;");
        assertThat(summary()).contains("CANCELLED");
    }

    @Test void confirmOptionMustEqualThePhrase() throws Exception {
        Path rule = rawNoScope("c9d", "");
        String before = prices();
        Cli.Options o = options(rule, true); o.confirmer = null; o.confirm = "APPLY 1 SQL test";
        assertThat(new DataChanger(settings).execute(o)).isEqualTo(1);
        assertThat(prices()).isEqualTo(before);
        Cli.Options ok = options(rule, true); ok.confirmer = null; ok.confirm = "APPLY 1 SQL test -";
        assertThat(new DataChanger(settings).execute(ok)).isZero();
        assertThat(prices()).doesNotContain("CFS:D");
    }

    @Test void yesIsRefusedForRiskyAndAcceptedForNormal() throws Exception {
        Cli.Options risky = options(rawNoScope("c9e", ""), true); risky.yes = true;
        assertThatThrownBy(() -> new DataChanger(settings).execute(risky)).hasMessageContaining("--yes is only for a normal change");
        Cli.Options normal = options(updateB("c9f", ""), true); normal.yes = true; normal.confirmer = null;
        assertThat(new DataChanger(settings).execute(normal)).isZero();
        assertThat(prices()).contains("CFS:B:2.5");
    }

    // 10
    @Test void ruleConfirmTextReplacesTheGeneratedPhrase() throws Exception {
        Path rule = updateB("c10", "\"confirm\": \"FIX-B-NOW\",");
        run(rule, true);
        assertThat(phrases).containsExactly("FIX-B-NOW");
        Cli.Options y = options(updateB("c10b", "\"confirm\": \"FIX-B-NOW\","), true); y.yes = true;
        assertThatThrownBy(() -> new DataChanger(settings).execute(y)).hasMessageContaining("confirm text");
    }

    // 11
    @Test void sqlStepWithScopeIsBackedUpAndUndoable() throws Exception {
        Path rule = write("c11", """
            {"name": "c11", "db": "test",
             "steps": [{"id": "raw", "action": "sql", "sql": "UPDATE live.price SET settle_price = 0 WHERE security_id = 'B'",
                        "table": "live.price", "where": "security_id = 'B'", "key": %s, "expect": {"targetBefore": "1", "affected": "1", "targetAfter": "1"}}]}
            """.formatted(KEY));
        run(rule, true);
        assertThat(phrases).as("a plain statement with a scope is a normal change").containsExactly("APPLY");
        assertThat(query("SELECT backup_table IS NOT NULL FROM parity_work.cdt_changelog")).isEqualTo("TRUE;");
        assertThat(newestRunDir().resolve("undo-c11.json")).exists();
    }

    @Test void productionNeedsAllowProdAndAlwaysTheLongPhrase() throws Exception {
        settings.getDatasources().get("test").setProd(true);
        assertThatThrownBy(() -> run(updateB("c11b", ""), false)).hasMessageContaining("--allow-prod");
        Cli.Options o = options(updateB("c11c", ""), true); o.allowProd = true;
        new DataChanger(settings).execute(o);
        assertThat(phrases).containsExactly("APPLY 1 UPDATE test live.price");
        Cli.Options y = options(updateB("c11d", ""), true); y.allowProd = true; y.yes = true;
        assertThatThrownBy(() -> new DataChanger(settings).execute(y)).hasMessageContaining("--yes is only for a normal change");
    }

    // 12
    @Test void backfillAddsBackupTablesWithoutRowsOnce() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE parity_work.restore_old_prices_step_20261101120000 AS SELECT * FROM live.price");
            Changelog.ensure(c, "parity_work");
            assertThat(changelogRows()).isEqualTo(1);
            Changelog.ensure(c, "parity_work");
            assertThat(changelogRows()).isEqualTo(1);
        }
        assertThat(query("SELECT mode || '|' || status || '|' || backfilled || '|' || rows_before || '|' || rule_name FROM parity_work.cdt_changelog"))
            .isEqualTo("RESTORE|APPLIED|TRUE|4|old_prices_step;");
    }

    @Test void changelogReportShowsBackupAndDropped() throws Exception {
        run(deleteD("c12", ""), true);
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            List<String> lines = Changelog.report(c, "parity_work", null, null, 10, true);
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst()).contains("CHANGE c12 step drop-d DELETE live.price").contains("[APPLIED").contains("backup parity_work.change_c12_drop_d_").contains("(1 rows)");
            String backup = query("SELECT backup_table FROM parity_work.cdt_changelog").replace(";", "");
            try (Statement s = c.createStatement()) { s.execute("DROP TABLE " + backup); }
            assertThat(Changelog.report(c, "parity_work", "c12", null, 10, true).getFirst()).contains("(dropped)");
            assertThat(Changelog.report(c, "parity_work", "other", null, 10, true).getFirst()).contains("no rows");
        }
    }

    // 13
    @Test void allowWriteFalseRefusesApplyButNotPreview() throws Exception {
        settings.getDatasources().get("test").setAllowWrite(false);
        assertThat(run(updateB("c13", ""), false)).isZero();
        assertThatThrownBy(() -> run(updateB("c13", ""), true)).hasMessageContaining("allow-write=false");
        Cli.Options r = new Cli.Options(); r.restore = tmp.resolve("none.json");
        assertThat(prices()).contains("CFS:B:9.9");
    }

    @Test void validationRejectsBadRules() throws Exception {
        assertThatThrownBy(() -> DataChanger.load(write("v1", "{\"name\":\"v\",\"db\":\"test\",\"steps\":[{\"id\":\"s\",\"action\":\"update\",\"table\":\"live.price\",\"where\":\"a=1\"}]}")))
            .hasMessageContaining("needs set");
        assertThatThrownBy(() -> DataChanger.load(write("v2", "{\"name\":\"v\",\"db\":\"test\",\"steps\":[{\"id\":\"s\",\"action\":\"delete\",\"table\":\"live.price\"}]}")))
            .hasMessageContaining("where is required");
        assertThatThrownBy(() -> DataChanger.load(write("v3", "{\"name\":\"v\",\"db\":\"test\",\"steps\":[{\"id\":\"s\",\"action\":\"merge\",\"table\":\"live.price\"}]}")))
            .hasMessageContaining("action must be one of");
        assertThatThrownBy(() -> DataChanger.load(write("v4", "{\"name\":\"v\",\"db\":\"test\",\"steps\":[{\"id\":\"s\",\"action\":\"sql\",\"sql\":\"DELETE FROM a\",\"expect\":{\"targetAfter\":\"0\"}}]}")))
            .hasMessageContaining("needs table and where");
        assertThatThrownBy(() -> DataChanger.load(write("v5", "{\"name\":\"v\",\"db\":\"test\",\"extra\":1,\"steps\":[{\"id\":\"s\",\"action\":\"delete\",\"table\":\"live.price\",\"where\":\"a=1\"}]}")))
            .hasMessageContaining("extra");
        assertThatThrownBy(() -> DataChanger.load(write("v6", "{\"name\":\"v\",\"db\":\"test\",\"steps\":[{\"id\":\"s\",\"action\":\"delete\",\"table\":\"live.price\",\"where\":\"a=1\","
            + "\"preconditions\":[{\"sql\":\"DELETE FROM live.price\",\"expect\":\"0\"}]}]}"))).hasMessageContaining("SELECT");
    }

    @Test void ruleFileWithAByteOrderMarkLoads() throws Exception {
        Path rule = write("bom", "﻿" + Files.readString(updateB("bom0", "")).replace("bom0", "bom"));
        assertThat(DataChanger.load(rule).name).isEqualTo("bom");
        assertThat(run(rule, true)).isZero();
    }

    @Test void unknownColumnsAreRejectedAtRunTime() throws Exception {
        Path rule = write("c14", """
            {"name": "c14", "db": "test",
             "steps": [{"id": "s", "action": "update", "table": "live.price", "where": "bu = 'CFS'", "set": {"nope": "1"}}]}
            """);
        assertThatThrownBy(() -> run(rule, false)).hasMessageContaining("set column nope is not in live.price");
    }

    @Test void parserAcceptsTheNewFlagsAndRejectsMisuse() {
        Cli.Options o = Cli.parse(new String[]{"--change", "r.json", "--apply", "--confirm", "APPLY 1 X", "--rerun"});
        assertThat(o.change).isEqualTo(Path.of("r.json")); assertThat(o.apply).isTrue(); assertThat(o.confirm).isEqualTo("APPLY 1 X"); assertThat(o.rerun).isTrue();
        Cli.Options l = Cli.parse(new String[]{"--changelog", "--db", "postgres-uat", "--rule", "x", "--ticket", "T-1", "--last", "5", "--schema", "parity_work"});
        assertThat(l.changelog).isTrue(); assertThat(l.db).isEqualTo("postgres-uat"); assertThat(l.last).isEqualTo(5);
        assertThatThrownBy(() -> Cli.parse(new String[]{"--changelog"})).hasMessageContaining("--db");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--change", "r.json", "--yes"})).hasMessageContaining("--apply");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--rules", "x.xml", "--yes", "--apply"})).hasMessageContaining("--change");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--change", "r.json", "--apply", "--yes", "--confirm", "x"})).hasMessageContaining("not both");
        assertThatThrownBy(() -> Cli.parse(new String[]{"--change", "r.json", "--restore", "q.json"})).hasMessageContaining("cannot be combined");
    }

    @Test void restoreAndLoadWritesAlsoGoToTheChangelog() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA ref");
            s.execute("CREATE TABLE ref.price AS SELECT * FROM live.price");
            s.execute("UPDATE live.price SET settle_price = 0 WHERE security_id = 'B'");
        }
        Path rule = write("r1", """
            {"name": "r1", "db": "test", "backupSchema": "parity_work",
             "steps": [{"id": "p", "table": "live.price", "from": "ref.price", "where": "bu = 'CFS'", "key": %s}]}
            """.formatted(KEY));
        Cli.Options o = new Cli.Options(); o.restore = rule; o.apply = true; o.out = tmp.resolve("out3");
        new DataRestorer(settings).execute(o);
        assertThat(query("SELECT mode || '|' || action || '|' || status || '|' || rows_affected FROM parity_work.cdt_changelog")).isEqualTo("RESTORE|RESTORE|APPLIED|1;");
        assertThat(query("SELECT backup_table FROM parity_work.cdt_changelog")).startsWith("parity_work.restore_r1_p_");
    }
}
