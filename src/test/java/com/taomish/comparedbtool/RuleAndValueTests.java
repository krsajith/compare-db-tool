package com.taomish.comparedbtool;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RuleAndValueTests {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {
        "DELETE FROM trades", "SELECT 1; DROP TABLE trades", "WITH x AS (DELETE FROM trades RETURNING *) SELECT * FROM x",
        "SELECT * INTO backup FROM trades", "SELECT * FROM trades FOR UPDATE", "SELECT nextval('seq')",
        "SELECT 1 /*! INTO OUTFILE '/tmp/export' */", "SELECT 1; /* harmless */ SELECT 2"
    })
    void rejectsMutationsAndMultipleStatements(String sql) {
        assertThrows(ToolException.class, () -> SqlGuard.validate(sql));
    }

    @Test void permitsQuotedTextCommentsParametersAndCasts() {
        assertDoesNotThrow(() -> SqlGuard.validate("/* report */ WITH x AS (SELECT 'delete; update' AS label) SELECT :date::date, label FROM x; -- end"));
    }

    @Test void xmlAttributesCdataRepeatedElementsAndTypedParameters() throws Exception {
        Path xml = temp.resolve("rules.xml");
        Files.writeString(xml, """
            <rules name="test">
              <parameters><param name="date" type="date" value="2026-10-28"/></parameters>
              <sql id="Left" db="ds"><![CDATA[SELECT :date AS d]]></sql>
              <sql id="Right" db="ds"><![CDATA[SELECT :date AS d]]></sql>
              <compare id="date-check" source="Left" target="Right"><key columns="d"/></compare>
              <compress id="package" file="test.zip"><include ref="Left"/><include ref="date-check"/></compress>
            </rules>
            """);
        Rules rules = RuleLoader.load(xml);
        assertEquals(2, rules.queries.size());
        assertEquals("Left", rules.queries.getFirst().id);
        assertEquals("SELECT :date AS d", rules.queries.getFirst().text.strip());
        DatasourceSettings settings = new DatasourceSettings();
        settings.getDatasources().put("ds", new DatasourceSettings.Definition());
        assertDoesNotThrow(() -> RuleLoader.validate(rules, settings));
        assertEquals(java.sql.Date.valueOf("2026-10-29"), RuleLoader.parameters(rules, Map.of("date", "2026-10-29")).get("date"));
    }

    @Test void externalEntitiesCannotReadFiles() throws Exception {
        Path secret = temp.resolve("secret.txt"); Files.writeString(secret, "SHOULD_NOT_BE_READ");
        Path xml = temp.resolve("external.xml");
        Files.writeString(xml, "<!DOCTYPE rules [<!ENTITY leak SYSTEM '" + secret.toUri()
            + "'>]><rules><sql id=\"x\" db=\"ds\">&leak;</sql></rules>");
        assertThrows(ToolException.class, () -> RuleLoader.load(xml));
    }

    @Test void interleavedSqlAndComparisonGroupsPreserveEveryReferencedId() throws Exception {
        Path xml = temp.resolve("interleaved.xml");
        Files.writeString(xml, """
            <rules name="interleaved">
              <sql id="old-sec" db="ds">SELECT 1 AS id</sql>
              <sql id="new-sec" db="ds">SELECT 1 AS id</sql>
              <compare id="sec" source="old-sec" target="new-sec"><key columns="id"/></compare>
              <sql id="old-vol" db="ds">SELECT 1 AS id</sql>
              <sql id="new-vol" db="ds">SELECT 1 AS id</sql>
              <compare id="vol" source="old-vol" target="new-vol"><key columns="id"/></compare>
              <sql id="old-px" db="ds">SELECT 1 AS id</sql>
              <sql id="new-px" db="ds">SELECT 1 AS id</sql>
              <compare id="px" source="old-px" target="new-px"><key columns="id"/></compare>
              <compress id="bundle" file="comparison.zip">
                <include ref="sec"/><include ref="vol"/><include ref="px"/>
              </compress>
            </rules>
            """);
        Rules rules = RuleLoader.load(xml);
        assertEquals(List.of("old-sec", "new-sec", "old-vol", "new-vol", "old-px", "new-px"),
            rules.queries.stream().map(q -> q.id).toList());
        assertEquals(List.of("sec", "vol", "px"), rules.comparisons.stream().map(c -> c.id).toList());
        DatasourceSettings settings = new DatasourceSettings();
        settings.getDatasources().put("ds", new DatasourceSettings.Definition());
        assertDoesNotThrow(() -> RuleLoader.validate(rules, settings));
        rules.compressions.getFirst().includes.getFirst().ref = "missing-sec";
        ToolException error = assertThrows(ToolException.class, () -> RuleLoader.validate(rules, settings));
        assertTrue(error.getMessage().contains("missing-sec"));
        assertTrue(error.getMessage().contains("Available ids:"));
    }

    @Test void missingParametersFailBeforeDatabaseAccess() {
        Rules.Sql sql = new Rules.Sql(); sql.id = "q"; sql.text = "SELECT :missing";
        assertThrows(ToolException.class, () -> QueryRunner.validateParameters(sql, Map.of()));
    }

    @Test void shippedGrasaRuleLoadsAndValidatesEveryBlockWithoutConnecting() {
        Rules rules = RuleLoader.load(Path.of("rules/grasa-inputs-cfs.xml"));
        assertEquals(6, rules.queries.size());
        assertEquals(3, rules.comparisons.size());
        assertEquals(1, rules.compressions.size());
        assertEquals(2, rules.parameters.size());
        assertEquals(List.of("sec", "vol", "px"), rules.comparisons.stream().map(c -> c.id).toList());
        assertEquals(6, rules.comparisons.getFirst().columns.size());
        DatasourceSettings settings = new DatasourceSettings();
        settings.getDatasources().put("mysql-oprdb02", new DatasourceSettings.Definition());
        settings.getDatasources().put("postgres-dev", new DatasourceSettings.Definition());
        assertDoesNotThrow(() -> RuleLoader.validate(rules, settings));
    }

    @Test void unknownRuleAttributesAndTrailingRootContentAreRejected() throws Exception {
        Path unknown = temp.resolve("unknown.xml");
        Files.writeString(unknown, "<rules><sql id=\"q\" db=\"ds\" unsupported=\"true\">SELECT 1</sql></rules>");
        assertThrows(ToolException.class, () -> RuleLoader.load(unknown));
        Path trailing = temp.resolve("trailing.xml");
        Files.writeString(trailing, "<rules><sql id=\"q\" db=\"ds\">SELECT 1</sql></rules><extra/>");
        assertThrows(ToolException.class, () -> RuleLoader.load(trailing));
    }

    @Test void numericPrecisionTolerancesAndSignFlip() {
        Rules.Column rule = new Rules.Column();
        rule.absTol = new BigDecimal("0.01");
        assertTrue(Values.compare("999999999999999999.00", "999999999999999999.005", rule, true).accepted());
        assertFalse(Values.compare("999999999999999999.00", "999999999999999999.02", rule, true).accepted());
        assertEquals("SIGN_FLIP", Values.compare("20", "-20", rule, true).classification());
        assertEquals("FORMAT_ONLY", Values.compare("1.00", "1", rule, true).classification());
    }

    @Test void nullEmptyZeroAndStringRulesRemainExplicit() {
        Rules.Column rule = new Rules.Column();
        assertFalse(Values.compare(null, "", rule, false).accepted());
        assertEquals("NULL_VS_EMPTY", Values.compare(null, "", rule, false).classification());
        rule.nullAsEmpty = true;
        assertTrue(Values.compare(null, "", rule, false).accepted());
        rule.nullAsZero = true;
        assertTrue(Values.compare(null, "0.00", rule, true).accepted());
        rule.trim = true; rule.caseInsensitive = true;
        assertTrue(Values.compare(" alpha ", "ALPHA", rule, false).accepted());
        assertFalse(Values.compare("001", "1", new Rules.Column(), false).accepted());
    }

    @Test void datesAreNotTruncatedWithoutPermission() {
        Rules.Column rule = new Rules.Column();
        assertFalse(Values.compare("2026-10-28 12:30:00", "2026-10-28", rule, false).accepted());
        rule.dateOnly = true;
        assertTrue(Values.compare("2026-10-28 12:30:00", "2026-10-28", rule, false).accepted());
        assertEquals("DATE_SHIFT", Values.compare("2026-10-28", "2026-10-29", rule, false).classification());
    }

    @Test void csvRoundTripsNullEmptyLiteralMarkerAndMultiline() throws Exception {
        Path csv = temp.resolve("roundtrip.csv");
        List<String> values = Arrays.asList(null, "", "\\N", "comma,quote\"\nnext line", "தமிழ்");
        try (var out = CsvFiles.writer(csv, List.of("a", "b", "c", "d", "e"))) { out.printRecord(values); }
        try (var in = CsvFiles.reader(csv, true)) { assertEquals(values, CsvFiles.values(in.iterator().next())); }
    }

    @Test void cliRejectsInvalidOptionsAndKeepsParameterEquals() {
        Cli.Options options = Cli.parse(new String[] {"rules.xml", "--param", "label=a=b", "--partitions=8"});
        assertEquals("a=b", options.parameters.get("label"));
        assertEquals(8, options.partitions);
        assertThrows(ToolException.class, () -> Cli.parse(new String[] {"rules.xml", "--partitions=0"}));
        assertThrows(ToolException.class, () -> Cli.parse(new String[] {"rules.xml", "--unknown"}));
    }

    @Test void summaryIsBoundedInUtf8Bytes() throws Exception {
        Path summary = temp.resolve("SUMMARY.md");
        ComparisonRunner.writeSmallSummary(summary, "தமிழ்".repeat(5000));
        assertTrue(Files.size(summary) <= 8192);
    }
}
