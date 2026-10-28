package com.taomish.comparedbtool;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class SchedulerTests {
    @TempDir Path temp;
    private static final AtomicInteger EXECUTIONS = new AtomicInteger();
    public static int countedValue() { EXECUTIONS.incrementAndGet(); return 1; }

    private Rules.Sql query(String id, String ds, String text) {
        var rule = new Rules.Sql(); rule.id = id; rule.db = ds; rule.text = text; return rule;
    }
    private DatasourceSettings twoDatasources() {
        var settings = SmartOutputTests.settings();
        var second = new DatasourceSettings.Definition();
        second.setUrl("jdbc:h2:mem:" + UUID.randomUUID()); second.setUsername("sa"); second.setPassword("");
        second.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("second", second);
        return settings;
    }
    @Test void parallelH2QueriesAreBoundedPerDatasourceAndFailureDoesNotStopOthers() throws Exception {
        var queries = List.of(query("a1", "test", "SELECT 1 AS id"), query("a2", "test", "SELECT 2 AS id"),
            query("bad", "test", "SELECT * FROM missing_table"), query("b1", "second", "SELECT 3 AS id"));
        var rendezvous = new CountDownLatch(2);
        var active = new ConcurrentHashMap<String, AtomicInteger>();
        AtomicInteger running = new AtomicInteger(), maximum = new AtomicInteger();
        List<QueryScheduler.Outcome> outcomes = new ArrayList<>();
        try (var runner = new QueryRunner(twoDatasources(), false)) {
            QueryScheduler.run(queries, 0, group -> {
                var first = group.rules().getFirst();
                int perDatasource = active.computeIfAbsent(first.db, k -> new AtomicInteger()).incrementAndGet();
                assertEquals(1, perDatasource);
                maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    if (first.id.equals("a1") || first.id.equals("b1")) {
                        rendezvous.countDown(); assertTrue(rendezvous.await(10, TimeUnit.SECONDS));
                    }
                    return List.of(new QueryScheduler.Outcome(first, runner.run(first, Map.of(), 0, temp, ExportMode.NONE), null, true));
                } finally { running.decrementAndGet(); active.get(first.db).decrementAndGet(); }
            }, outcomes::addAll);
        }
        assertEquals(2, maximum.get());
        assertEquals(4, outcomes.size());
        assertEquals(1, outcomes.stream().filter(o -> o.error() != null).count());
        assertEquals(3, outcomes.stream().filter(o -> o.result() != null).count());
    }

    @Test void readyInputCanBeComparedBeforeAnotherDatasourceFinishes() throws Exception {
        var queries = List.of(query("ready", "test", "SELECT 1 AS id"), query("later", "second", "SELECT 2 AS id"));
        var compared = new CountDownLatch(1);
        var laterStarted = new CountDownLatch(1);
        try (var runner = new QueryRunner(twoDatasources(), false)) {
            QueryScheduler.run(queries, 2, group -> {
                var first = group.rules().getFirst();
                if (first.id.equals("later")) {
                    laterStarted.countDown(); assertTrue(compared.await(10, TimeUnit.SECONDS));
                } else assertTrue(laterStarted.await(10, TimeUnit.SECONDS));
                return List.of(new QueryScheduler.Outcome(first, runner.run(first, Map.of(), 0, temp, ExportMode.NONE), null, true));
            }, results -> {
                var outcome = results.getFirst();
                if (outcome.query().id.equals("ready")) {
                    var compare = new Rules.Compare(); compare.id = "early"; compare.source = "ready"; compare.target = "ready";
                    compare.key = new Rules.Key(); compare.key.columns = "id";
                    assertEquals(1, new ComparisonRunner(2).run(compare, outcome.result(), outcome.result(), temp, ExportMode.NONE, 0).equal());
                    compared.countDown();
                }
            });
        }
        assertEquals(0, compared.getCount());
    }

    @Test void identicalSqlExecutesOnceWithMixedExportModes() throws Exception {
        var settings = SmartOutputTests.settings();
        String url = settings.getDatasources().get("test").getUrl() + ";DB_CLOSE_DELAY=-1";
        settings.getDatasources().get("test").setUrl(url);
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE ALIAS COUNTED_VALUE FOR 'com.taomish.comparedbtool.SchedulerTests.countedValue'");
        }
        EXECUTIONS.set(0);
        Path xml = temp.resolve("reuse.xml");
        Files.writeString(xml, """
            <rules name="reuse">
              <sql id="left" db="test" export="full">SELECT COUNTED_VALUE() AS id</sql>
              <sql id="right" db="test" export="none">SELECT COUNTED_VALUE() AS id</sql>
              <compare id="same" source="left" target="right"><key columns="id"/></compare>
            </rules>
            """);
        var options = new Cli.Options(); options.rules = xml; options.out = temp.resolve("reuse"); options.partitions = 2;
        assertEquals(0, new Cli(settings).execute(options));
        assertEquals(1, EXECUTIONS.get());
        Path root;
        try (var runs = Files.list(options.out)) { root = runs.findFirst().orElseThrow(); }
        assertTrue(Files.exists(root.resolve("left.csv"))); assertFalse(Files.exists(root.resolve("right.csv")));
        assertTrue(Files.readString(root.resolve("right.md")).contains("Reused execution: left"));
        assertFalse(Files.exists(root.resolve(".work")));
    }

    @Test void reusedQueryPreservesDifferentRowCaps() throws Exception {
        Path xml = temp.resolve("caps.xml");
        Files.writeString(xml, """
            <rules name="caps">
              <sql id="limited" db="test" maxRows="2" export="full">SELECT X AS id FROM SYSTEM_RANGE(1,5)</sql>
              <sql id="complete" db="test" export="full">SELECT X AS id FROM SYSTEM_RANGE(1,5)</sql>
            </rules>
            """);
        var options = new Cli.Options(); options.rules = xml; options.out = temp.resolve("caps");
        assertEquals(2, new Cli(SmartOutputTests.settings()).execute(options));
        Path root;
        try (var runs = Files.list(options.out)) { root = runs.findFirst().orElseThrow(); }
        try (var rows = CsvFiles.reader(root.resolve("limited.csv"), true)) { assertEquals(2, rows.getRecords().size()); }
        try (var rows = CsvFiles.reader(root.resolve("complete.csv"), true)) { assertEquals(5, rows.getRecords().size()); }
        assertTrue(Files.readString(root.resolve("limited.md")).contains("PARTIAL"));
        assertTrue(Files.readString(root.resolve("complete.md")).contains("COMPLETE"));
    }

    @Test void sequentialFlagAndMemoryGuardAreExplicit() throws Exception {
        var options = Cli.parse(new String[] {"rules.xml", "--parallel=1", "--max-partition-mb=1"});
        assertEquals(1, options.parallel); assertEquals(1, options.maxPartitionMb);
        assertThrows(ToolException.class, () -> Cli.parse(new String[] {"rules.xml", "--parallel=0"}));
        var queries = List.of(query("a", "test", "SELECT 1"), query("b", "second", "SELECT 2"), query("c", "test", "SELECT 3"));
        AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
        List<String> order = new ArrayList<>();
        QueryScheduler.run(queries, 1, group -> {
            order.add(group.rules().getFirst().id);
            max.accumulateAndGet(active.incrementAndGet(), Math::max);
            active.decrementAndGet(); return List.of();
        }, outcomes -> {});
        assertEquals(1, max.get());
        assertEquals(List.of("a", "b", "c"), order);
        try (var runner = new QueryRunner(SmartOutputTests.settings(), false)) {
            var left = runner.run(SmartOutputTests.query("left", true), Map.of(), 0, temp, ExportMode.NONE);
            var right = runner.run(SmartOutputTests.query("right", false), Map.of(), 0, temp, ExportMode.NONE);
            ToolException error = assertThrows(ToolException.class, () -> new ComparisonRunner(1, 1)
                .run(SmartOutputTests.comparison("budget"), left, right, temp, ExportMode.NONE, 0));
            assertTrue(error.getMessage().contains("Comparison memory budget exceeded"));
        }
    }
}
