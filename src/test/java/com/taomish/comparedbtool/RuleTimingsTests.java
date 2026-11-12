package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.zip.ZipFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class RuleTimingsTests {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void savesEveryStepAndFinalWallTimeEvenWhenQuietAndSomeStepsFail(boolean databaseEngine) throws Exception {
        var settings = new DatasourceSettings();
        var db = new DatasourceSettings.Definition();
        db.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db.setUsername("sa"); db.setPassword(""); db.setReadOnly(true); db.setAllowTempTables(true);
        db.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", db);
        Path xml = tmp.resolve("timed.xml");
        Files.writeString(xml, """
            <rules name="timed">
              <sql id="a" db="test">SELECT 1 AS id</sql>
              <sql id="alias" db="test">SELECT 1 AS id</sql>
              <sql id="b" db="test">SELECT 2 AS id</sql>
              <sql id="broken" db="test">SELECT id FROM nonexistent</sql>
              <sql id="skip" db="test" when="false">SELECT 3 AS id</sql>
              <sql id="db-a" db="test">SELECT 1 AS id</sql>
              <sql id="db-b" db="test">SELECT 2 AS id</sql>
              <compare id="cmp" source="db-a" target="db-b" engine="%s"><key columns="id"/></compare>
              <compare id="blocked" source="broken" target="b"><key columns="id"/></compare>
              <funnel id="flow" key="id"><stage id="first" source="a" target="b"/><stage id="second" source="a" target="b"/></funnel>
              <funnel id="skip-flow" key="id"><stage id="first" source="skip" target="b"/><stage id="second" source="a" target="b"/></funnel>
              <presence id="matrix" key="id" sources="a,b"/>
              <presence id="skip-matrix" key="id" sources="skip,b"/>
              <expect ref="a" rows="1"/>
              <compress id="bundle" file="timed.zip"><include ref="cmp"/></compress>
            </rules>
            """.formatted(databaseEngine ? "db" : "stream"));
        var options = new Cli.Options();
        options.rules = xml; options.out = tmp.resolve("out"); options.quiet = true;
        options.export = ExportMode.NONE; options.partitions = 4;
        Cli.Outcome[] result = new Cli.Outcome[1];
        String console = ProgressTests.captured(() -> result[0] = new Cli(settings).executeRules(options));
        assertThat(console).doesNotContain("  time ", "  > ", "compare-db-tool: done");
        var outcome = result[0];
        assertThat(outcome.exit()).isEqualTo(2);
        String md = Files.readString(outcome.directory().resolve("SUMMARY.md"));
        assertThat(md).contains("## Rule timing", "**Whole rule:", "Do not sum step times",
            "| zip | bundle |", "| cleanup | temporary-files |", "| expectations | evaluate |",
            "| funnel | flow |", "| presence | matrix |");
        List<List<String>> rows = new ArrayList<>();
        try (var csv = CsvFiles.reader(outcome.directory().resolve("TIMINGS.csv"), true)) {
            for (var row : csv) rows.add(CsvFiles.values(row));
        }
        var total = rows.getLast();
        assertThat(total.subList(0, 2)).containsExactly("rule", "total");
        assertThat(total.get(3)).isEqualTo("FINAL");
        long wall = Long.parseLong(total.get(2));
        for (var row : rows) assertThat(Long.parseLong(row.get(2))).isBetween(0L, wall);
        assertThat(row(rows, "broken").get(3)).isEqualTo("FAILED / BLOCKED");
        assertThat(row(rows, "blocked").get(3)).isEqualTo("FAILED");
        for (String id : List.of("skip", "skip-flow", "skip-matrix"))
            assertThat(row(rows, id).get(3)).isEqualTo("SKIPPED");
        assertThat(row(rows, "a").get(2)).isEqualTo(row(rows, "alias").get(2));
        assertThat(row(rows, "alias").get(4)).startsWith("shared group:");
        assertThat(row(rows, "cmp").getFirst()).isEqualTo(databaseEngine ? "compare-db" : "compare");
        if (databaseEngine) assertThat(rows).noneMatch(r -> List.of("db-a", "db-b").contains(r.get(1)));
        try (var zip = new ZipFile(outcome.directory().resolve("timed.zip").toFile())) {
            assertThat(zip.getEntry("TIMINGS.csv")).isNotNull();
            String snapshot = new String(zip.getInputStream(zip.getEntry("SUMMARY.md")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(snapshot).contains("Rule elapsed before packaging").doesNotContain("**Whole rule:");
        }
    }

    private static List<String> row(List<List<String>> rows, String id) {
        return rows.stream().filter(r -> r.get(1).equals(id)).findFirst().orElseThrow();
    }

    @Test void partialRunWithoutZipStillSavesFinalTiming() throws Exception {
        var settings = new DatasourceSettings();
        var db = new DatasourceSettings.Definition();
        db.setUrl("jdbc:h2:mem:" + UUID.randomUUID()); db.setUsername("sa"); db.setPassword("");
        db.setReadOnly(true); db.setDriverClassName("org.h2.Driver");
        settings.getDatasources().put("test", db);
        var options = new Cli.Options();
        options.rules = tmp.resolve("partial.xml"); options.out = tmp.resolve("out");
        options.quiet = true; options.limit = 1; options.export = ExportMode.NONE;
        Files.writeString(options.rules, """
            <rules name="partial"><sql id="limited" db="test">SELECT X AS id FROM SYSTEM_RANGE(1,3)</sql></rules>
            """);
        var outcome = new Cli(settings).executeRules(options);
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(Files.readString(outcome.directory().resolve("SUMMARY.md")))
            .contains("**Whole rule:", "| PARTIAL |").doesNotContain("Rule elapsed before packaging");
        assertThat(Files.readString(outcome.directory().resolve("TIMINGS.csv")))
            .contains("\"query\",\"limited\",", "\"rule\",\"total\",");
    }

    @Test void timingOutputNameCannotBeUsedAsQueryId() {
        assertThatThrownBy(() -> RuleLoader.id("TIMINGS", new HashSet<>())).isInstanceOf(ToolException.class);
    }
}
