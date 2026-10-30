package com.taomish.comparedbtool;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DbComparisonTests {
    @TempDir Path temp;
    private DatasourceSettings settings() {
        var settings = new DatasourceSettings(); var d = new DatasourceSettings.Definition();
        d.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"); d.setUsername("sa"); d.setPassword(""); d.setAllowTempTables(true);
        settings.getDatasources().put("test", d); return settings;
    }
    private void seed(DatasourceSettings s, String... statements) throws Exception {
        try (var c = DriverManager.getConnection(s.getDatasources().get("test").getUrl(), "sa", ""); var st = c.createStatement()) {
            for (String text : statements) st.execute(text);
        }
    }
    private Rules.Sql query(String id, String text) { var q = new Rules.Sql(); q.id = id; q.db = "test"; q.text = text; return q; }
    private Rules.Column column(String name) { var c = new Rules.Column(); c.name = name; return c; }
    private Rules.Compare compare() {
        var c = new Rules.Compare(); c.id = "check"; c.source = "left"; c.target = "right"; c.engine = "db";
        c.key = new Rules.Key(); c.key.columns = "id"; return c;
    }
    private DbComparisonRunner.Outcome parity(DatasourceSettings s, Rules.Sql a, Rules.Sql b, Rules.Compare c) throws Exception {
        Path stream = Files.createTempDirectory(temp, "stream"), db = Files.createTempDirectory(temp, "db");
        try (var runner = new QueryRunner(s, false)) {
            var left = runner.run(a, Map.of(), 0, stream); var right = runner.run(b, Map.of(), 0, stream);
            var expected = new ComparisonRunner(4).run(c, left, right, stream);
            var result = new DbComparisonRunner().run(c, a, b, runner, Map.of(), db, ExportMode.FULL, 200);
            var actual = result.comparison();
            assertEquals(expected.headline(), actual.headline());
            assertEquals(expected.ambiguousKeys(), actual.ambiguousKeys());
            assertEquals(expected.differentCells(), actual.differentCells()); assertEquals(expected.acceptedChanges(), actual.acceptedChanges());
            assertEquals(left.rows(), result.source().rows()); assertEquals(right.rows(), result.target().rows());
            assertEquals(groups(stream), groups(db));
            if (!c.breakdowns.isEmpty()) {
                String x = Files.readString(stream.resolve("check/SUMMARY.md")), y = Files.readString(db.resolve("check/SUMMARY.md"));
                assertEquals(x.substring(x.indexOf("## Breakdown:"), x.indexOf("Export mode:")), y.substring(y.indexOf("## Breakdown:"), y.indexOf("Export mode:")));
            }
            for (String file : List.of("differences.csv", "only-source.csv", "only-target.csv", "duplicates.csv"))
                assertEquals(csv(stream.resolve("check/" + file)), csv(db.resolve("check/" + file)), file);
            return result;
        }
    }
    private Set<List<String>> csv(Path path) throws Exception {
        try (var rows = CsvFiles.reader(path, false)) {
            var result = new HashSet<List<String>>(); for (var row : rows) result.add(CsvFiles.values(row)); return result;
        }
    }
    private String groups(Path root) throws Exception {
        String summary = Files.readString(root.resolve("check/SUMMARY.md"));
        return summary.substring(summary.indexOf("| Column | Class"), summary.indexOf("Only accepted=false"));
    }
    @Test void matchesStreamingCountsClassesDuplicatesAndBreakdowns() throws Exception {
        var s = settings();
        seed(s, "CREATE TABLE a(id INT, amount DECIMAL(25,3), label VARCHAR(50))", "CREATE TABLE b(id INT, amount DECIMAL(25,3), label VARCHAR(50))",
            "INSERT INTO a VALUES(1,10,'Alpha'),(2,20,'Bravo'),(3,30,'Source'),(4,40,'Dup'),(4,40,'Dup'),(6,NULL,NULL),(7,1,' trim ')",
            "INSERT INTO b VALUES(1,10.005,'alpha'),(2,-20,'Bravo'),(5,50,'Target'),(4,40,'Dup'),(6,0,''),(7,1,'trim')");
        var c = compare(); var amount = column("amount"); amount.absTol = new BigDecimal("0.01"); amount.nullAsZero = true;
        var label = column("label"); label.caseInsensitive = true; label.trim = true; label.nullAsEmpty = true;
        c.columns.addAll(List.of(amount, label)); var breakdown = new Rules.Breakdown(); breakdown.columns = "label"; c.breakdowns.add(breakdown);
        var r = parity(s, query("left", "SELECT * FROM a"), query("right", "SELECT * FROM b"), c);
        assertEquals(3, r.comparison().equal()); assertEquals(1, r.comparison().different());
    }
    @Test void normalizationCompositeNullKeysMappingsAndSchemaDifferencesMatch() throws Exception {
        var s = settings();
        seed(s, "CREATE TABLE a(id VARCHAR(20), tag INT, n DECIMAL(25,3), extra VARCHAR(10))", "CREATE TABLE b(code VARCHAR(20), tag INT, amount DECIMAL(25,3))",
            "INSERT INTO a VALUES(' a ',1,1.004,'x'),(NULL,2,2.005,'y'),(' dup',3,1,'z'),('DUP ',3,2,'z')",
            "INSERT INTO b VALUES('A',1,1.003),(NULL,2,2.004),('DUP',3,1)");
        var c = compare(); c.key.columns = "id,tag";
        var key = column("id"); key.target = "code"; key.trim = true; key.caseInsensitive = true;
        var amount = column("n"); amount.target = "amount"; amount.round = 2;
        c.columns.addAll(List.of(key, amount));
        var result = parity(s, query("left", "SELECT * FROM a"), query("right", "SELECT * FROM b"), c);
        assertTrue(result.comparison().schemaDifferent()); assertEquals(1, result.comparison().duplicateSourceKeys());
    }
    @Test void roundRelativeToleranceNullAndNegativeValuesMatch() throws Exception {
        var s = settings();
        seed(s, "CREATE TABLE a(id INT, n DECIMAL(30,8))", "CREATE TABLE b(id INT, n DECIMAL(30,8))",
            "INSERT INTO a VALUES(1,-1.005),(2,100),(3,NULL),(4,0),(5,-2),(6,1.004)",
            "INSERT INTO b VALUES(1,-1.004),(2,101),(3,0),(4,NULL),(5,2),(6,1.003)");
        var c = compare(); var n = column("n"); n.round = 2; n.relTol = new BigDecimal("0.01"); n.nullAsZero = true; c.columns.add(n);
        parity(s, query("left", "SELECT * FROM a"), query("right", "SELECT * FROM b"), c);
    }
    @Test void emptyInputsAndOnlyDuplicateInputsMatch() throws Exception {
        var s = settings(); seed(s, "CREATE TABLE a(id INT, n INT)", "CREATE TABLE b(id INT, n INT)");
        parity(s, query("left", "SELECT * FROM a"), query("right", "SELECT * FROM b"), compare());
        seed(s, "INSERT INTO a VALUES(NULL,1),(NULL,2),(1,3),(1,4)", "INSERT INTO b VALUES(NULL,3),(NULL,4),(2,3),(2,4)");
        parity(s, query("left", "SELECT * FROM a"), query("right", "SELECT * FROM b"), compare());
    }
    @Test void equalRowsNeverCrossTheWireAndSamplesAreBoundedEvenInFullMode() throws Exception {
        var s = settings();
        seed(s, "CREATE TABLE a AS SELECT x AS id, CAST(x AS DECIMAL(20,2)) AS n FROM SYSTEM_RANGE(1,100)",
            "CREATE TABLE b AS SELECT id, CASE WHEN id>90 THEN -n ELSE n END AS n FROM a");
        try (var runner = new QueryRunner(s, false)) {
            var c = compare(); var result = new DbComparisonRunner().run(c, query("left", "SELECT * FROM a"), query("right", "SELECT * FROM b"), runner, Map.of(), temp, ExportMode.FULL, 2);
            assertEquals(90, result.comparison().equal()); assertEquals(10, result.comparison().different());
            assertTrue(result.fetched() <= 4, "Only total, class aggregate and two sample rows should be fetched.");
            assertFalse(Files.exists(temp.resolve("left.csv"))); assertFalse(Files.exists(temp.resolve(".work")));
            assertEquals(3, csv(temp.resolve("check/differences.csv")).size());
        }
    }
    @Test void noneModeFetchesAggregatesAndBreakdownsWithoutSampleFiles() throws Exception {
        var s = settings(); seed(s, "CREATE TABLE a(id INT,n INT)", "INSERT INTO a VALUES(1,1),(2,2)");
        try (var runner = new QueryRunner(s, false)) {
            var c = compare();
            var out = new DbComparisonRunner().run(c, query("left", "SELECT * FROM a"), query("right", "SELECT id,-n AS n FROM a"), runner, Map.of(), temp, ExportMode.NONE, 1);
            assertEquals(2, out.comparison().different()); assertEquals(2, out.fetched());
            assertEquals(1, out.comparison().files().size()); assertFalse(Files.exists(temp.resolve("check/differences.csv")));
        }
    }
    @Test void rejectsCrossDatasourceDateOnlyUnsupportedEngineAndRowCapsDuringValidation() {
        var s = settings(); Rules.Sql a = query("left", "SELECT 1 AS id"), b = query("right", "SELECT 1 AS id");
        var r = new Rules(); r.queries.addAll(List.of(a,b)); r.comparisons.add(compare());
        b.db = "other"; s.getDatasources().put("other", s.getDatasources().get("test"));
        assertThrows(ToolException.class, () -> RuleLoader.validate(r,s)); b.db = "test";
        var column = column("id"); column.dateOnly = true; r.comparisons.getFirst().columns.add(column);
        assertThrows(ToolException.class, () -> RuleLoader.validate(r,s)); column.dateOnly = false;
        a.maxRows = 1; assertThrows(ToolException.class, () -> RuleLoader.validate(r,s)); a.maxRows = 0;
        r.comparisons.getFirst().engine = "bad"; assertThrows(ToolException.class, () -> RuleLoader.validate(r,s));
    }
    @Test void rejectsUnsupportedNativeTypesAndTextConversionsClearly() throws Exception {
        var s = settings();
        try (var runner = new QueryRunner(s, false)) {
            for (String expression : List.of("CAST(1 AS DOUBLE)", "DATE '2026-10-01'", "'2026-10-01'", "'123'", "'é'")) {
                Path out = Files.createTempDirectory(temp,"unsupported");
                var error = assertThrows(ToolException.class, () -> new DbComparisonRunner().run(compare(),
                    query("left", "SELECT 1 AS id," + expression + " AS n"), query("right", "SELECT 1 AS id,CAST(NULL AS VARCHAR) AS n"),
                    runner, Map.of(), out, ExportMode.NONE, 1));
                assertTrue(error.getMessage().contains("engine=db"));
            }
        }
    }
    @Test void combinedTempModeRollsBackFunctionWrites() throws Exception {
        var s = settings(); seed(s, "CREATE TABLE audit_rows(id INT)", "CREATE ALIAS write_row FOR 'com.taomish.comparedbtool.TempTableTests.writeRow'");
        Rules.Sql a = query("left", "SELECT 1 AS id, 1 AS n"), b = query("right", "SELECT 1 AS id, write_row(false) AS n"); b.tempTables = true;
        try (var runner = new QueryRunner(s, false)) {
            var result = new DbComparisonRunner().run(compare(), a, b, runner, Map.of(), temp, ExportMode.NONE, 1);
            assertEquals(1, result.comparison().equal());
            b.text = "SELECT 1 AS id, write_row(true) AS n";
            assertThrows(Exception.class, () -> new DbComparisonRunner().run(compare(), a, b, runner, Map.of(), Files.createDirectory(temp.resolve("failure")), ExportMode.NONE, 1));
        }
        try (var c = DriverManager.getConnection(s.getDatasources().get("test").getUrl(), "sa", ""); var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM audit_rows")) {
            rs.next(); assertEquals(0, rs.getInt(1));
        }
    }
    @Test void cliDbOnlyInputsAreNotIndividuallyExportedAndZipContainsArtifacts() throws Exception {
        var s = settings();
        Path xml = temp.resolve("input.xml"); Files.writeString(xml, """
            <rules name="db-cli">
              <sql id="left" db="test" export="full">SELECT 1 AS id, 2 AS n</sql>
              <sql id="right" db="test" export="full">SELECT 1 AS id, 3 AS n</sql>
              <compare id="check" source="left" target="right" engine="db"><key columns="id"/></compare>
              <compress id="zip" file="results.zip"><include ref="left"/><include ref="right"/><include ref="check"/></compress>
            </rules>
            """);
        var cli = new Cli(s); var options = Cli.parse(new String[]{"--rules",xml.toString(),"--out",temp.resolve("out").toString()});
        assertEquals(1, cli.execute(options));
        try (var paths = Files.list(temp.resolve("out"))) {
            Path root = paths.findFirst().orElseThrow();
            assertFalse(Files.exists(root.resolve("left.csv"))); assertFalse(Files.exists(root.resolve("right.csv")));
            assertTrue(Files.exists(root.resolve("results.zip"))); assertTrue(Files.readString(root.resolve("SUMMARY.md")).contains("different=1"));
        }
    }
    @Test void breakdownTopOtherMissingColumnsAndIgnoredMappingsMatch() throws Exception {
        var s = settings(); seed(s, "CREATE TABLE a(id INT,n INT,grp VARCHAR(30))", "CREATE TABLE b(id INT,n INT,grp VARCHAR(30),z INT)",
            "INSERT INTO a VALUES(1,1,'A'),(2,2,'B'),(3,3,'A'),(4,4,NULL),(5,5,'C'),(6,6,'C'),(7,7,'A')",
            "INSERT INTO b SELECT id,-n,grp,1 FROM a");
        var c = compare(); var ignore = column("grp"); ignore.ignore = true; c.columns.add(ignore);
        var group = new Rules.Breakdown(); group.columns = "grp,z"; group.top = 1; c.breakdowns.add(group);
        parity(s, query("left","SELECT * FROM a"), query("right","SELECT * FROM b"), c);
    }
    @Test void boundParametersWorkInCombinedCtesAndValidationOpensNoConnection() throws Exception {
        var s = settings(); var a = query("left", "WITH x AS (SELECT CAST(:n AS INTEGER) AS id) SELECT id, 1 AS amount FROM x");
        var b = query("right", "SELECT CAST(:n AS INTEGER) AS id, 1 AS amount"); var r = new Rules(); r.queries.addAll(List.of(a,b)); r.comparisons.add(compare());
        var param = new Rules.Param(); param.name = "n"; param.type = "integer"; param.value = "3"; r.parameters.add(param);
        RuleLoader.validate(r,s);
        try (var runner = new QueryRunner(s, false)) {
            assertEquals(1,new DbComparisonRunner().run(compare(), a,b,runner,Map.of("n",3),temp,ExportMode.NONE,1).comparison().equal());
        }
        s.getDatasources().get("test").setUrl("jdbc:postgresql://invalid.invalid/no_connection"); RuleLoader.validate(r,s);
    }
}
