package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic data in isolated in-memory H2 only. */
class FindingsTests {
    @TempDir Path tmp;
    String url;
    DatasourceSettings settings;
    int tables;

    @BeforeEach void setup() {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword("");
        d.setReadOnly(true); d.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("test", d);
    }

    static String row(String ref, String code, String amount, String region, String ts, String by) {
        return "('" + ref + "','" + code + "'," + amount + ",'" + region + "',TIMESTAMP '" + ts + "','" + by + "')";
    }

    static List<String> common(int n) {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            rows.add(row(String.format("C%02d", i), "code" + i, String.valueOf(100 + i), i % 2 == 0 ? "EU" : "US", String.format("2026-09-%02d 10:00:00", i), "etl"));
        return rows;
    }

    String table(List<String> rows) throws Exception {
        String name = "t" + (++tables);
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE " + name + "(ref VARCHAR(20), code VARCHAR(20), amount DECIMAL(25,3), region VARCHAR(10), created_ts TIMESTAMP, updated_by VARCHAR(20))");
            if (!rows.isEmpty()) s.execute("INSERT INTO " + name + " VALUES " + String.join(",", rows));
        }
        return name;
    }

    List<Findings.Finding> findings(List<String> source, List<String> target) throws Exception {
        return compare(source, target).findings();
    }

    ComparisonRunner.Result compare(List<String> source, List<String> target) throws Exception {
        String a = table(source), b = table(target);
        Path dir = Files.createDirectory(tmp.resolve("run" + tables));
        try (QueryRunner runner = new QueryRunner(settings, false)) {
            var qa = new Rules.Sql(); qa.id = "left"; qa.db = "test"; qa.text = "SELECT * FROM " + a;
            var qb = new Rules.Sql(); qb.id = "right"; qb.db = "test"; qb.text = "SELECT * FROM " + b;
            var left = runner.run(qa, java.util.Map.of(), 0, dir);
            var right = runner.run(qb, java.util.Map.of(), 0, dir);
            var cmp = new Rules.Compare(); cmp.id = "cmp"; cmp.source = "left"; cmp.target = "right";
            cmp.key = new Rules.Key(); cmp.key.columns = "ref";
            return new ComparisonRunner(4).run(cmp, left, right, dir);
        }
    }

    static Findings.Finding of(List<Findings.Finding> list, String type) {
        return list.stream().filter(f -> f.type().equals(type)).findFirst().orElseThrow(() -> new AssertionError("no " + type + " in " + list.stream().map(Findings.Finding::type).toList()));
    }

    @Test void rowsThatExistUnderAnotherKeyAreNotReportedAsMissing() throws Exception {
        List<String> source = common(20), target = common(20);
        for (int i = 1; i <= 5; i++) {
            source.add(row("S" + i, "own" + i, i + "00", "EU", "2026-09-20 10:00:00", "etl"));
            target.add(row("T" + i, "own" + i, i + "00", "EU", "2026-09-20 10:00:00", "etl"));
        }
        var result = compare(source, target);
        assertThat(result.onlySource()).isEqualTo(5);
        var f = of(result.findings(), "rekeyed");
        assertThat(f.explains()).isEqualTo(5);
        assertThat(f.of()).isEqualTo(5);
        assertThat(f.title()).contains("5 of 5 missing rows exist on the other side under a different key");
        assertThat(f.evidence()).contains("`S1` (source) = `T1` (target)");
        assertThat(result.findings().getFirst().type()).isEqualTo("rekeyed");     // ranked first
    }

    @Test void keysThatMatchAfterIgnoringCasePunctuationAndLeadingZeros() throws Exception {
        List<String> source = common(20), target = common(20);
        source.add(row("0007", "a", "10", "EU", "2026-09-20 10:00:00", "etl")); target.add(row("7", "a2", "11", "EU", "2026-09-20 10:00:00", "etl"));
        source.add(row("0008", "b", "10", "EU", "2026-09-20 10:00:00", "etl")); target.add(row("8", "b2", "11", "EU", "2026-09-20 10:00:00", "etl"));
        source.add(row("AB-12", "c", "10", "EU", "2026-09-20 10:00:00", "etl")); target.add(row("ab12", "c2", "11", "EU", "2026-09-20 10:00:00", "etl"));
        var list = findings(source, target);
        assertThat(of(list, "near-miss-key").explains()).isEqualTo(3);
        assertThat(list).noneMatch(f -> f.type().equals("rekeyed"));                // the other values differ
    }

    @Test void extraRowsNewerThanTheSourceAndLoadedByOneUser() throws Exception {
        List<String> target = common(20);
        for (int i = 1; i <= 6; i++) target.add(row("N" + i, "new" + i, "5" + i, "EU", "2026-10-0" + i + " 09:00:00", "batch"));
        var list = findings(common(20), target);
        var timing = of(list, "timing-newer-than-source");
        assertThat(timing.title()).contains("created_ts").contains("6 of 6 rows only in the target are newer than the newest source row (2026-09-20 10:00:00");
        var who = list.stream().filter(f -> f.type().equals("concentration") && f.title().contains("UPDATED_BY")).findFirst().orElseThrow();
        assertThat(who.title()).contains("6 of 6 only-target rows (100%) have UPDATED_BY = `batch`").contains("of all target rows");
        assertThat(list.stream().map(Findings.Finding::type).toList()).doesNotContain("rekeyed");
    }

    @Test void valuesThatAreCommonEverywhereAreNotConcentration() throws Exception {
        List<String> source = common(20), target = common(20);
        for (int i = 1; i <= 6; i++) { source.add(row("M" + i, "m" + i, "9" + i, "EU", "2026-09-25 10:00:00", "etl")); }  // 'etl' is 100% of everything
        var list = findings(source, target);
        assertThat(list).noneMatch(f -> f.type().equals("concentration") && f.title().contains("UPDATED_BY"));
    }

    @Test void constantRatioOffsetAndSignFlipAreNamed() throws Exception {
        List<String> source = common(20), ratio = common(20), offset = common(20), flip = common(20);
        for (int i = 1; i <= 4; i++) ratio.set(i - 1, row(String.format("C%02d", i), "code" + i, String.valueOf((100 + i) * 100), i % 2 == 0 ? "EU" : "US", String.format("2026-09-%02d 10:00:00", i), "etl"));
        for (int i = 1; i <= 4; i++) offset.set(i - 1, row(String.format("C%02d", i), "code" + i, String.valueOf(100 + i + 5), i % 2 == 0 ? "EU" : "US", String.format("2026-09-%02d 10:00:00", i), "etl"));
        for (int i = 1; i <= 3; i++) flip.set(i - 1, row(String.format("C%02d", i), "code" + i, String.valueOf(-(100 + i)), i % 2 == 0 ? "EU" : "US", String.format("2026-09-%02d 10:00:00", i), "etl"));
        assertThat(of(findings(source, ratio), "constant-ratio").title()).contains("AMOUNT: target = source x 100 in all 4 differing cells");
        assertThat(of(findings(source, offset), "constant-offset").title()).contains("AMOUNT: target = source + 5 in all 4 differing cells");
        assertThat(of(findings(source, flip), "sign-flip").title()).contains("AMOUNT: every differing value has the opposite sign");
    }

    @Test void aFewRowsCanDriveTheWholeGap() throws Exception {
        List<String> target = common(20);
        // C01 +1000, C02 +900, then eight rows off by 1 each
        target.set(0, row("C01", "code1", "1101", "US", "2026-09-01 10:00:00", "etl"));
        target.set(1, row("C02", "code2", "1002", "EU", "2026-09-02 10:00:00", "etl"));
        for (int i = 3; i <= 10; i++) target.set(i - 1, row(String.format("C%02d", i), "code" + i, String.valueOf(100 + i + (i % 3 + 1)), i % 2 == 0 ? "EU" : "US", String.format("2026-09-%02d 10:00:00", i), "etl"));
        var f = of(findings(common(20), target), "few-rows-drive-gap");
        assertThat(f.title()).startsWith("AMOUNT: 5 of 10 differing cells carry");
        assertThat(f.evidence()).contains("`C01` 101 -> 1101 (1000)").contains("net (target - source)");
    }

    @Test void differencesConfinedToOneColumnAndEmptySideAreReported() throws Exception {
        List<String> target = common(20);
        for (int i = 1; i <= 4; i++) target.set(i - 1, row(String.format("C%02d", i), "code" + i, String.valueOf(100 + i + 1), i % 2 == 0 ? "EU" : "US", String.format("2026-09-%02d 10:00:00", i), "etl"));
        assertThat(of(findings(common(20), target), "one-column").title()).isEqualTo("All 4 differing cells are in one column: AMOUNT");
        var empty = findings(common(20), List.of());
        assertThat(empty.getFirst().type()).isEqualTo("empty-side");
        assertThat(empty.getFirst().title()).isEqualTo("Target returned no rows");
    }

    @Test void equalDataHasNoFindingsAndWritesNoFile() throws Exception {
        var result = compare(common(20), common(20));
        assertThat(result.findings()).isEmpty();
        assertThat(tmp.resolve("run" + tables).resolve("cmp/FINDINGS.md")).doesNotExist();
    }

    @Test void findingsAreWrittenPerComparisonAndPerRunAndZipped() throws Exception {
        List<String> source = common(20), target = common(20);
        for (int i = 1; i <= 5; i++) {
            source.add(row("S" + i, "own" + i, i + "00", "EU", "2026-09-20 10:00:00", "etl"));
            target.add(row("T" + i, "own" + i, i + "00", "EU", "2026-09-20 10:00:00", "etl"));
        }
        String a = table(source), b = table(target);
        Path xml = tmp.resolve("rules.xml");
        Files.writeString(xml, """
            <rules name="f">
              <sql id="left" db="test">SELECT * FROM %s</sql><sql id="right" db="test">SELECT * FROM %s</sql>
              <compare id="cmp" source="left" target="right"><key columns="ref"/></compare>
              <compress id="bundle" file="f.zip"><include ref="cmp"/></compress>
            </rules>""".formatted(a, b));
        Cli.Options o = new Cli.Options(); o.rules = xml; o.out = tmp.resolve("out"); o.partitions = 4;
        var outcome = new Cli(settings).executeRules(o);
        Path dir = outcome.directory();
        assertThat(Files.readString(dir.resolve("FINDINGS.md"))).contains("## cmp").contains("5 of 5 missing rows exist on the other side under a different key");
        assertThat(Files.readString(dir.resolve("SUMMARY.md"))).contains("## Findings (see FINDINGS.md)").contains("**cmp**");
        assertThat(Files.readString(dir.resolve("cmp/SUMMARY.md"))).contains("## Findings");
        assertThat(Files.readString(dir.resolve("cmp/findings.json"))).contains("\"type\" : \"rekeyed\"").contains("\"explains\" : 5");
        try (ZipFile zip = new ZipFile(dir.resolve("f.zip").toFile())) {
            assertThat(zip.getEntry("FINDINGS.md")).isNotNull();
            assertThat(zip.getEntry("cmp/FINDINGS.md")).isNotNull();
            assertThat(zip.getEntry("cmp/findings.json")).isNotNull();
        }
    }
}
