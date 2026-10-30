package com.taomish.comparedbtool;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class TempTableTests {
    @TempDir Path temp;
    private DatasourceSettings settings() {
        var settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition();
        d.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        d.setUsername("sa"); d.setPassword(""); d.setAllowTempTables(true);
        settings.getDatasources().put("test", d);
        return settings;
    }
    private Rules rules(String text) {
        var rules = new Rules(); var q = new Rules.Sql();
        q.id = "fn"; q.db = "test"; q.text = text; q.tempTables = true;
        rules.queries.add(q); return rules;
    }
    @Test void validationRejectsMissingOptInAndProductionEvenWithAllowProd() throws Exception {
        var s = settings(); var r = rules("SELECT 1"); var d = s.getDatasources().get("test");
        d.setAllowTempTables(false);
        assertThrows(ToolException.class, () -> RuleLoader.validate(r, s));
        d.setAllowTempTables(true); d.setProd(true);
        assertThrows(ToolException.class, () -> RuleLoader.validate(r, s));
        try (var runner = new QueryRunner(s, true)) {
            assertThrows(ToolException.class, () -> runner.run(r.queries.getFirst(), Map.of(), 0, temp));
        }
        d.setProd(false); s.getDatasources().put("test-prod", d); r.queries.getFirst().db = "test-prod";
        assertThrows(ToolException.class, () -> RuleLoader.validate(r, s));
    }
    @Test void sqlGuardStillRejectsWritesAndMultipleStatements() {
        for (String text : List.of("INSERT INTO x VALUES(1)", "SELECT 1 INTO x", "SELECT 1; SELECT 2",
                "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"))
            assertThrows(ToolException.class, () -> RuleLoader.validate(rules(text), settings()));
    }
    @Test void allowListSupportsDefaultsGlobsQuotedNamesAndComments() {
        var s = settings();
        RuleLoader.validate(rules("SELECT other.any_fn(1)"), s);
        s.getDatasources().get("test").setAllowedFunctions("opera.fn_*, test.exact");
        RuleLoader.validate(rules("SELECT opera.fn_report(1), test.exact(2), 'bad.fn()'"), s);
        RuleLoader.validate(rules("SELECT \"opera\" /* gap */ . \"fn_report\" (1)"), s);
        assertThrows(ToolException.class, () -> RuleLoader.validate(rules("SELECT other.fn_report(1)"), s));
        assertThrows(ToolException.class, () -> RuleLoader.validate(rules("SELECT \"other\".\"fn_report\"(1)"), s));
        assertThrows(ToolException.class, () -> RuleLoader.validate(rules("SELECT opera.fn_ok(other.bad(1))"), s));
    }
    public static int writeRow(Connection connection, boolean fail) throws SQLException {
        assertFalse(connection.getAutoCommit());
        assertFalse(connection.isReadOnly());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO audit_rows VALUES(1)");
        }
        if (fail) throw new SQLException("test failure");
        return 1;
    }
    @Test void executionRollsBackWritesOnSuccessFailureAndRepeatedPoolUse() throws Exception {
        var s = settings(); String url = s.getDatasources().get("test").getUrl();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE audit_rows(id INT)");
            st.execute("CREATE ALIAS write_row FOR 'com.taomish.comparedbtool.TempTableTests.writeRow'");
            try (var runner = new QueryRunner(s, false)) {
                for (boolean fail : List.of(false, true, false)) {
                    var q = rules("SELECT write_row(" + fail + ") AS id").queries.getFirst();
                    if (fail) assertThrows(ToolException.class, () -> runner.run(q, Map.of(), 0, temp));
                    else assertEquals(1, runner.run(q, Map.of(), 0, temp).rows());
                    try (var rs = st.executeQuery("SELECT count(*) FROM audit_rows")) {
                        rs.next(); assertEquals(0, rs.getInt(1));
                    }
                }
            }
        }
    }
    @Test void xmlAttributeLoadsAndValidationDoesNotConnect() throws Exception {
        Path xml = temp.resolve("rules.xml");
        Files.writeString(xml, "<rules><sql id=\"q\" db=\"test\" tempTables=\"true\">SELECT opera.fn_x()</sql></rules>");
        var r = RuleLoader.load(xml); assertTrue(r.queries.getFirst().tempTables);
        var s = settings(); s.getDatasources().get("test").setUrl("jdbc:postgresql://invalid.invalid/no_connection");
        RuleLoader.validate(r, s);
    }
    @Test void reuseDoesNotMixNormalAndTempModes() throws Exception {
        var normal = rules("SELECT 1").queries.getFirst(); normal.tempTables = false;
        var tempQuery = rules("SELECT 1").queries.getFirst(); tempQuery.id = "temp";
        List<Boolean> modes = new ArrayList<>();
        QueryScheduler.run(List.of(normal, tempQuery), 1, group -> {
            assertEquals(1, group.rules().size()); modes.add(group.rules().getFirst().tempTables); return List.of();
        }, outcomes -> {});
        assertEquals(List.of(false, true), modes);
    }
}
