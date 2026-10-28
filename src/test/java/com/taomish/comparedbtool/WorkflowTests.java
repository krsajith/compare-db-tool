package com.taomish.comparedbtool;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Only isolated in-memory H2 test databases; never reads the external datasource file. */
class WorkflowTests {
    @TempDir Path temp;
    private DatasourceSettings settings(String url) {
        DatasourceSettings settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition();
        d.setUrl(url); d.setUsername("sa"); d.setPassword(""); d.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test-ds", d);
        return settings;
    }
    private String seed() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE source_data(id INTEGER, amount DECIMAL(25,3), label VARCHAR(50))");
            sql.execute("CREATE TABLE target_data(id INTEGER, amount DECIMAL(25,3), label VARCHAR(50))");
            sql.execute("INSERT INTO source_data VALUES(1,10,'Alpha'),(2,20,'Bravo'),(3,30,'Source'),(4,40,'Dup'),(4,40,'Dup')");
            sql.execute("INSERT INTO target_data VALUES(1,10.005,'alpha'),(2,-20,'Bravo'),(5,50,'Target'),(4,40,'Dup')");
        }
        return url;
    }
    private Rules.Sql query(String id, String table) {
        Rules.Sql sql = new Rules.Sql(); sql.id = id; sql.db = "test-ds";
        sql.text = "SELECT id, amount, label FROM " + table + " ORDER BY id";
        return sql;
    }
    private Rules.Compare comparison() {
        Rules.Compare c = new Rules.Compare(); c.id = "trade-check"; c.source = "left"; c.target = "right";
        c.key = new Rules.Key(); c.key.columns = "id";
        Rules.Column amount = new Rules.Column(); amount.name = "amount"; amount.absTol = new java.math.BigDecimal("0.01");
        Rules.Column label = new Rules.Column(); label.name = "label"; label.caseInsensitive = true;
        c.columns.add(amount); c.columns.add(label);
        return c;
    }
    @Test void queryExportAndPartitionedComparisonFindExactSeededCounts() throws Exception {
        try (QueryRunner runner = new QueryRunner(settings(seed()), false)) {
            var a = runner.run(query("left", "source_data"), Map.of(), 0, temp);
            var b = runner.run(query("right", "target_data"), Map.of(), 0, temp);
            assertEquals(5, a.rows()); assertEquals(4, b.rows());
            var result = new ComparisonRunner(8).run(comparison(), a, b, temp);
            assertEquals(2, result.matched()); assertEquals(1, result.equal()); assertEquals(1, result.different());
            assertEquals(1, result.onlySource()); assertEquals(1, result.onlyTarget());
            assertEquals(1, result.duplicateSourceKeys()); assertEquals(0, result.duplicateTargetKeys());
            assertEquals(1, result.differentCells()); assertEquals(2, result.acceptedChanges());
            assertFalse(result.partial()); assertTrue(result.differs());
        }
    }

    @Test void singlePartitionAndMultiplePartitionsHaveSameCounts() throws Exception {
        try (QueryRunner runner = new QueryRunner(settings(seed()), false)) {
            var a = runner.run(query("left", "source_data"), Map.of(), 0, temp);
            var b = runner.run(query("right", "target_data"), Map.of(), 0, temp);
            Path one = Files.createDirectory(temp.resolve("one")), many = Files.createDirectory(temp.resolve("many"));
            var x = new ComparisonRunner(1).run(comparison(), a, b, one);
            var y = new ComparisonRunner(16).run(comparison(), a, b, many);
            assertEquals(x.headline(), y.headline());
            assertEquals(Files.readString(one.resolve("trade-check/SUMMARY.md")), Files.readString(many.resolve("trade-check/SUMMARY.md")));
        }
    }

    @Test void rowCapsMarkPartialOnlyWhenAnotherRowExists() throws Exception {
        try (QueryRunner runner = new QueryRunner(settings(seed()), false)) {
            assertTrue(runner.run(query("capped", "source_data"), Map.of(), 2, temp).partial());
            assertFalse(runner.run(query("exact", "source_data"), Map.of(), 5, temp).partial());
        }
    }

    @Test void productionGateAndSqlGuardRunBeforeConnecting() throws Exception {
        DatasourceSettings settings = settings("jdbc:h2:mem:should_not_connect");
        settings.getDatasources().get("test-ds").setProd(true);
        try (QueryRunner runner = new QueryRunner(settings, false)) {
            assertThrows(ToolException.class, () -> runner.run(query("left", "source_data"), Map.of(), 0, temp));
            Rules.Sql mutation = query("bad", "source_data"); mutation.text = "DROP TABLE source_data";
            assertThrows(ToolException.class, () -> runner.run(mutation, Map.of(), 0, temp));
            assertFalse(Files.exists(temp.resolve("bad.csv")));
        }
    }

    @Test void completeCliCreatesSelectedZipWithoutConnectionConfiguration() throws Exception {
        DatasourceSettings settings = settings(seed());
        Path xml = temp.resolve("rules.xml");
        Files.writeString(xml, """
            <rules name="workflow">
              <sql id="left" db="test-ds">SELECT id, amount, label FROM source_data</sql>
              <sql id="right" db="test-ds">SELECT id, amount, label FROM target_data</sql>
              <compare id="trade-check" source="left" target="right">
                <key columns="id"/><column name="amount" absTol="0.01"/><column name="label" caseInsensitive="true"/>
              </compare>
              <compress id="bundle" file="comparison.zip">
                <include ref="left"/><include ref="trade-check"/>
              </compress>
            </rules>
            """);
        Cli.Options options = new Cli.Options(); options.rules = xml; options.out = temp.resolve("out"); options.partitions = 4;
        assertEquals(1, new Cli(settings).execute(options));
        Path run;
        try (var dirs = Files.list(options.out)) { run = dirs.findFirst().orElseThrow(); }
        try (ZipFile zip = new ZipFile(run.resolve("comparison.zip").toFile())) {
            assertNotNull(zip.getEntry("left.csv")); assertNull(zip.getEntry("right.csv"));
            assertNotNull(zip.getEntry("trade-check/differences.csv")); assertNotNull(zip.getEntry("SUMMARY.md"));
            assertTrue(zip.stream().noneMatch(e -> e.getName().contains("datasources")));
        }
        assertFalse(Files.exists(run.resolve(".work")));
    }

    @Test void validateDoesNotConnectEvenWithUnreachableDatasource() throws Exception {
        Path xml = temp.resolve("validate.xml");
        Files.writeString(xml, "<rules><sql id=\"q\" db=\"test-ds\">SELECT 1 AS id</sql></rules>");
        var options = new Cli.Options(); options.rules = xml; options.validate = true;
        assertEquals(0, new Cli(settings("jdbc:postgresql://unreachable.invalid:1/missing")).execute(options));
    }

    @Test void compositeKeysMappedColumnsAndIgnoredSchemaColumns() throws Exception {
        Path left = temp.resolve("left.csv"), right = temp.resolve("right.csv");
        try (var csv = CsvFiles.writer(left, List.of("legacy_id", "book", "old_note"))) {
            csv.printRecord("a|b", "c", "old");
            csv.printRecord("a", "b|c", "old");
        }
        try (var csv = CsvFiles.writer(right, List.of("trade_id", "book", "new_note"))) {
            csv.printRecord("a", "b|c", "new");
            csv.printRecord("a|b", "c", "new");
        }
        var a = new QueryRunner.Result("left", left, List.of(new QueryRunner.Column("legacy_id", Types.VARCHAR),
            new QueryRunner.Column("book", Types.VARCHAR), new QueryRunner.Column("old_note", Types.VARCHAR)), 2, false, 0, List.of(left));
        var b = new QueryRunner.Result("right", right, List.of(new QueryRunner.Column("trade_id", Types.VARCHAR),
            new QueryRunner.Column("book", Types.VARCHAR), new QueryRunner.Column("new_note", Types.VARCHAR)), 2, false, 0, List.of(right));
        Rules.Compare compare = new Rules.Compare(); compare.id = "mapped";
        compare.source = "left"; compare.target = "right"; compare.key = new Rules.Key(); compare.key.columns = "trade_id,book";
        Rules.Column mapping = new Rules.Column(); mapping.name = "trade_id"; mapping.source = "legacy_id"; mapping.target = "trade_id";
        compare.columns.add(mapping);
        for (String name : List.of("old_note", "new_note")) {
            Rules.Column ignore = new Rules.Column(); ignore.name = name; ignore.ignore = true; compare.columns.add(ignore);
        }
        var result = new ComparisonRunner(4).run(compare, a, b, temp);
        assertEquals(2, result.matched()); assertEquals(2, result.equal());
        assertFalse(result.differs()); assertFalse(result.schemaDifferent());
        compare.id = "schema-check";
        compare.columns.removeIf(c -> c.ignore);
        assertTrue(new ComparisonRunner(4).run(compare, a, b, temp).schemaDifferent());
    }

    @Test void failedQueryDoesNotStopOtherExportsComparisonsOrZipPackaging() throws Exception {
        Path xml = temp.resolve("continue.xml");
        Files.writeString(xml, """
            <rules name="continue-on-error">
              <sql id="bad-query" db="test-ds">SELECT id FROM missing_test_table</sql>
              <sql id="good-query" db="test-ds">SELECT 1 AS id</sql>
              <compare id="skipped" source="bad-query" target="good-query"><key columns="id"/></compare>
              <compare id="completed" source="good-query" target="good-query"><key columns="id"/></compare>
              <compress id="bundle" file="results.zip">
                <include ref="bad-query"/><include ref="good-query"/><include ref="skipped"/><include ref="completed"/>
              </compress>
            </rules>
            """);
        var options = new Cli.Options(); options.rules = xml; options.out = temp.resolve("continued"); options.partitions = 2;
        assertEquals(2, new Cli(settings(seed())).execute(options));
        Path run;
        try (var dirs = Files.list(options.out)) { run = dirs.findFirst().orElseThrow(); }
        assertTrue(Files.exists(run.resolve("good-query.csv")));
        assertTrue(Files.exists(run.resolve("completed/SUMMARY.md")));
        String actions = Files.readString(run.resolve("MANUAL_ACTIONS.md"));
        assertTrue(actions.contains("bad-query.sql"));
        assertTrue(actions.contains("Comparison skipped"));
        try (ZipFile zip = new ZipFile(run.resolve("results.zip").toFile())) {
            assertNotNull(zip.getEntry("good-query.csv"));
            assertNotNull(zip.getEntry("bad-query.sql"));
            assertNotNull(zip.getEntry("MANUAL_ACTIONS.md"));
        }
    }
}
