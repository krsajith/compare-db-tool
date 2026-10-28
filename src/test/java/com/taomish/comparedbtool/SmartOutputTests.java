package com.taomish.comparedbtool;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SmartOutputTests {
    @TempDir Path temp;
    static DatasourceSettings settings() {
        var settings = new DatasourceSettings();
        var ds = new DatasourceSettings.Definition();
        ds.setUrl("jdbc:h2:mem:" + UUID.randomUUID()); ds.setUsername("sa"); ds.setPassword("");
        ds.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("test", ds);
        return settings;
    }
    static Rules.Sql query(String id, boolean source) {
        var query = new Rules.Sql(); query.id = id; query.db = "test";
        query.text = "SELECT X AS id, CAST(" + (source ? "X" : "CASE WHEN X=1 THEN X+0.001 ELSE -X END")
            + " AS DECIMAL(18,3)) AS amount, CASE WHEN MOD(X,2)=0 THEN 'P2' ELSE 'P1' END AS product,"
            + " '2026-10' AS contract_month FROM SYSTEM_RANGE(1," + (source ? 9254 : 3) + ")";
        return query;
    }
    static Rules.Compare comparison(String id) {
        var compare = new Rules.Compare(); compare.id = id; compare.source = "left"; compare.target = "right";
        compare.key = new Rules.Key(); compare.key.columns = "id";
        var amount = new Rules.Column(); amount.name = "amount"; amount.absTol = new java.math.BigDecimal("0.01");
        compare.columns.add(amount);
        var breakdown = new Rules.Breakdown(); breakdown.columns = "product,contract_month"; breakdown.top = 1;
        compare.breakdowns.add(breakdown);
        var missing = new Rules.Breakdown(); missing.columns = "unavailable"; missing.top = 1; compare.breakdowns.add(missing);
        return compare;
    }
    static List<Long> counts(ComparisonRunner.Result r) {
        return List.of(r.matched(), r.equal(), r.different(), r.onlySource(), r.onlyTarget(), r.duplicateSourceKeys(),
            r.duplicateTargetKeys(), r.ambiguousKeys(), r.differentCells(), r.acceptedChanges());
    }
    @Test void exportModesPreserveCountsClassificationsAndBoundSamples() throws Exception {
        List<Long> expected = null;
        try (var runner = new QueryRunner(settings(), false)) {
            for (ExportMode mode : ExportMode.values()) {
                Path root = Files.createDirectory(temp.resolve(mode.name()));
                var left = runner.run(query("left", true), Map.of(), 0, root, mode);
                var right = runner.run(query("right", false), Map.of(), 0, root, mode);
                assertEquals(9254, left.rows());
                assertEquals(mode == ExportMode.FULL, Files.exists(root.resolve("left.csv")));
                assertEquals(mode != ExportMode.NONE, Files.exists(root.resolve("left.sql")));
                var result = new ComparisonRunner(8).run(comparison("check"), left, right, root, mode, 200);
                if (expected == null) expected = counts(result); else assertEquals(expected, counts(result));
                assertEquals(List.of(3L, 1L, 2L, 9251L, 0L, 0L, 0L, 0L, 2L, 1L), counts(result));
                String summary = Files.readString(root.resolve("check/SUMMARY.md"));
                assertTrue(summary.contains("| amount | SIGN_FLIP | false | 2 |"));
                assertTrue(summary.contains("| amount | ROUNDING | true | 1 |"));
                assertTrue(summary.contains("| P2 | 2026-10 | 4626 |"));
                assertTrue(summary.contains("| other | | 4625 |"));
                assertTrue(summary.contains("Missing breakdown columns:"));
                assertTrue(Files.size(root.resolve("check/SUMMARY.md")) <= 8192);
                if (mode == ExportMode.NONE) {
                    assertFalse(Files.exists(root.resolve("check/only-source.csv")));
                    assertEquals(1, result.files().size());
                } else {
                    try (var rows = CsvFiles.reader(root.resolve("check/only-source.csv"), true)) {
                        assertEquals(mode == ExportMode.FULL ? 9251 : 200, rows.getRecords().size());
                    }
                    try (var rows = CsvFiles.reader(root.resolve("check/differences.csv"), true)) {
                        assertEquals(mode == ExportMode.FULL ? 3 : 2, rows.getRecords().size());
                    }
                    if (mode == ExportMode.SAMPLE) assertTrue(summary.contains("showing 200 of 9251"));
                }
            }
        }
    }

    @Test void defaultMailBundleHasNoRawQueryCsvAndXmlOverridesCli() throws Exception {
        Path xml = temp.resolve("rules.xml");
        Files.writeString(xml, """
            <rules name="mail">
              <sql id="left" db="test"><![CDATA[SELECT X AS id FROM SYSTEM_RANGE(1,1000)]]></sql>
              <sql id="right" db="test"><![CDATA[SELECT 1 AS id]]></sql>
              <compare id="check" source="left" target="right"><key columns="id"/></compare>
              <compress id="bundle" file="mail.zip"><include ref="left"/><include ref="right"/><include ref="check"/></compress>
            </rules>
            """);
        var options = new Cli.Options(); options.rules = xml; options.out = temp.resolve("runs"); options.partitions = 2;
        assertEquals(1, new Cli(settings()).execute(options));
        Path root;
        try (var runs = Files.list(options.out)) { root = runs.findFirst().orElseThrow(); }
        assertFalse(Files.exists(root.resolve("left.csv")));
        assertFalse(Files.exists(root.resolve(".work")));
        assertTrue(Files.size(root.resolve("mail.zip")) < 1_000_000);
        try (var zip = new ZipFile(root.resolve("mail.zip").toFile())) {
            assertNull(zip.getEntry("left.csv")); assertNotNull(zip.getEntry("check/SUMMARY.md"));
            assertNotNull(zip.getEntry("check/only-source.csv"));
        }
        Files.writeString(xml, Files.readString(xml).replace("id=\"left\" db=\"test\"", "id=\"left\" db=\"test\" export=\"full\"")
            .replace("<compare id=\"check\"", "<compare export=\"sample\" id=\"check\""));
        options.out = temp.resolve("overrides"); options.export = ExportMode.NONE; options.sampleRows = 2;
        assertEquals(1, new Cli(settings()).execute(options));
        try (var runs = Files.list(options.out)) { root = runs.findFirst().orElseThrow(); }
        assertTrue(Files.exists(root.resolve("left.csv"))); assertFalse(Files.exists(root.resolve("right.csv")));
        try (var rows = CsvFiles.reader(root.resolve("check/only-source.csv"), true)) { assertEquals(2, rows.getRecords().size()); }
    }

    @Test void cliAndInterleavedBreakdownRulesAreValidated() throws Exception {
        var options = Cli.parse(new String[] {"rules.xml", "--export=none", "--sample-rows", "12"});
        assertEquals(ExportMode.NONE, options.export); assertEquals(12, options.sampleRows);
        assertThrows(ToolException.class, () -> Cli.parse(new String[] {"rules.xml", "--export=unknown"}));
        assertThrows(ToolException.class, () -> Cli.parse(new String[] {"rules.xml", "--sample-rows=-1"}));
        Path xml = temp.resolve("breakdowns.xml");
        Files.writeString(xml, """
            <rules><sql id="q" db="test">SELECT 1 AS id</sql>
              <compare id="c" source="q" target="q" export="none"><key columns="id"/>
                <breakdown columns="id"/><column name="id"/><breakdown columns="other" top="2"/>
              </compare></rules>
            """);
        var rules = RuleLoader.load(xml);
        assertEquals(2, rules.comparisons.getFirst().breakdowns.size());
        assertDoesNotThrow(() -> RuleLoader.validate(rules, settings()));
    }
}
