package com.taomish.comparedbtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.taomish.comparedbtool.SqlAssessment.Level.*;

class SqlAssessmentTests {
    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1", "WITH x AS (SELECT 1) SELECT * FROM x; -- end",
        "SELECT 'DROP; DELETE', \"INSERT\", `UPDATE`, [SET], :delete::text /* DROP */",
        "SELECT dollar$column FROM t"})
    void benign(String sql) { assertEquals(OK, SqlGuard.assess(sql).level()); }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT replace('abc','a','x')", "SELECT insert('abc',1,1,'x')",
        "SELECT t.replace FROM t", "SELECT 1 AS drop", "SELECT set, into FROM t",
        "SELECT * FROM t WHERE replace = 1", "SELECT 'a' || replace('a','a','b')",
        "SELECT 1; /* ordinary */ SELECT 2", "SELECT $$delete; drop$$",
        "SELECT $tag$drop$tag$", "SELECT 'a\\b'", "SELECT 1 --comment\n",
        "SELECT 1 /* outer /* nested */ end */"})
    void warning(String sql) { assertEquals(WARN, SqlGuard.assess(sql).level()); }

    @ParameterizedTest
    @ValueSource(strings = {"REPLACE INTO t VALUES(1)", "INSERT INTO t VALUES(1)", "DROP TABLE t",
        "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "SELECT * INTO backup FROM t",
        "WITH x AS (INSERT INTO t VALUES(1) RETURNING *) SELECT * FROM x",
        "WITH x AS (UPDATE t SET a=1 RETURNING *) SELECT * FROM x",
        "SELECT 1 INTO backup", "SELECT 'x' INTO backup", "SELECT 1 AS x INTO backup",
        "SELECT * FROM t FOR UPDATE", "SELECT nextval('seq')", "SELECT setval('seq',1)",
        "SELECT pg_advisory_lock(1)", "SELECT 1; DELETE FROM t", "SELECT 1 UNION DELETE FROM t",
        "SELECT 1 /*! INTO OUTFILE '/tmp/x' */", "DO $$ BEGIN END $$", "ANALYZE t",
        "SELECT 'unterminated", "SELECT $$unterminated", "/* unterminated", "-- only a comment"})
    void danger(String sql) { assertEquals(DANGER, SqlGuard.assess(sql).level()); }

    @Test void findingsRetainLocationContextReasonAndAreImmutable() {
        var result = SqlGuard.assess("SELECT 1,\n  replace('a','a','b')");
        var f = result.findings().getFirst();
        assertEquals("REPLACE", f.word()); assertEquals(2, f.line()); assertEquals(3, f.column());
        assertTrue(f.context().contains("replace(")); assertTrue(f.reason().contains("function"));
        assertThrows(UnsupportedOperationException.class, () -> result.findings().clear());
    }

    @Test void assessmentDoesNotRelaxExecutionBeforeConfirmationIsImplemented() {
        assertEquals(WARN, SqlGuard.assess("SELECT replace('a','a','b')").level());
        assertThrows(ToolException.class, () -> SqlGuard.validate("SELECT replace('a','a','b')"));
    }

    @Test void shippedRulesValidateAndProduceOfflineFindingInventory() throws Exception {
        int count = 0;
        try (var paths = java.nio.file.Files.list(java.nio.file.Path.of("rules"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                var rules = RuleLoader.load(path);
                var settings = new DatasourceSettings();
                for (var q : rules.queries) {
                    var definition = new DatasourceSettings.Definition();
                    definition.setUrl("jdbc:h2:mem:offline-validation-only");
                    settings.getDatasources().putIfAbsent(q.db, definition);
                }
                assertDoesNotThrow(() -> RuleLoader.validate(rules, settings), path.toString());
                for (var q : rules.queries) {
                    var assessment = SqlGuard.assess(q.text);
                    if (assessment.level() != OK) System.out.println("ASSESSMENT " + path.getFileName() + " "
                        + q.id + " " + assessment.level() + " "
                        + assessment.findings().stream().map(f -> f.word() + "@" + f.line() + ":" + f.column()).toList());
                }
                count++;
            }
        }
        assertTrue(count > 0);
        System.out.println("OFFLINE RULES VALIDATED: " + count + " (synthetic datasource definitions, no connections)");
    }
}
