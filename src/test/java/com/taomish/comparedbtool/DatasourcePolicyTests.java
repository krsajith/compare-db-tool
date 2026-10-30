package com.taomish.comparedbtool;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import static org.junit.jupiter.api.Assertions.*;

class DatasourcePolicyTests {
    @TempDir Path temp;
    private DatasourceSettings settings(String name) {
        var settings = new DatasourceSettings(); var d = new DatasourceSettings.Definition();
        d.setUrl("jdbc:h2:mem:" + UUID.randomUUID()); d.setUsername("sa"); d.setPassword("");
        settings.getDatasources().put(name, d); return settings;
    }
    private Rules rules(String name) {
        var r = new Rules(); var q = new Rules.Sql(); q.id = "fn"; q.db = name; q.tempTables = true; q.text = "SELECT 1 AS id";
        r.queries.add(q); return r;
    }
    @Test void defaultsPermitDevAndUatWithoutAdditionalProperties() throws Exception {
        for (String name : List.of("postgres-dev", "postgres-uat")) {
            var s = settings(name); var r = rules(name);
            assertTrue(s.allowsTempTables(name)); assertEquals("*", s.getDatasources().get(name).getAllowedFunctions());
            RuleLoader.validate(r, s);
            try (var runner = new QueryRunner(s, false)) {
                assertEquals(1, runner.run(r.queries.getFirst(), Map.of(), 0, Files.createDirectory(temp.resolve(name))).rows());
            }
        }
    }
    @Test void productionAlwaysOffEvenWithExplicitEnableAndAllowProd() throws Exception {
        for (String name : List.of("postgres-PROD", "flagged")) {
            var s = settings(name); var d = s.getDatasources().get(name); d.setProd(name.equals("flagged"));
            assertFalse(s.allowsTempTables(name)); d.setAllowTempTables(true); assertFalse(s.allowsTempTables(name));
            var error = assertThrows(ToolException.class, () -> RuleLoader.validate(rules(name),s));
            assertTrue(error.getMessage().contains("production"));
            try (var runner = new QueryRunner(s,true)) {
                assertThrows(ToolException.class, () -> runner.run(rules(name).queries.getFirst(),Map.of(),0,temp));
            }
        }
    }
    @Test void explicitDisableNamesPropertyAndLoadedFile() {
        var s = settings("dev"); s.getDatasources().get("dev").setAllowTempTables(false);
        Path file = temp.resolve("actual.properties").toAbsolutePath(); s.datasourceFiles(List.of(file.toString()));
        var error = assertThrows(ToolException.class, () -> RuleLoader.validate(rules("dev"),s));
        assertTrue(error.getMessage().contains("explicitly disabled"));
        assertTrue(error.getMessage().contains("compare.datasources.dev.allow-temp-tables=true"));
        assertTrue(error.getMessage().contains(file.toString()));
    }
    @Test void tempModeIgnoresDatasourceReadOnlyFlagWhileOrdinaryRulesKeepTheirChecks() throws Exception {
        for (boolean readOnly : List.of(true,false)) {
            var s = settings("dev"); s.getDatasources().get("dev").setReadOnly(readOnly); var r = rules("dev");
            RuleLoader.validate(r,s);
            try (var runner = new QueryRunner(s,false)) {
                assertEquals(1,runner.run(r.queries.getFirst(),Map.of(),0,temp).rows());
                r.queries.getFirst().tempTables = false;
                if (readOnly) { RuleLoader.validate(r,s); assertEquals(1,runner.run(r.queries.getFirst(),Map.of(),0,temp).rows()); }
                else {
                    assertThrows(ToolException.class, () -> RuleLoader.validate(r,s));
                    assertThrows(ToolException.class, () -> runner.run(r.queries.getFirst(),Map.of(),0,temp));
                }
            }
        }
    }
    @Test void bootTracksImportedAndAdditionalFilesAndOutputOmitsConnectionSecrets() throws Exception {
        Path base = temp.resolve("base.properties"), override = temp.resolve("override.properties");
        Files.writeString(base, """
            compare.datasources.dev.url=jdbc:h2:mem:test_diagnostics
            compare.datasources.dev.username=private-user-4908
            compare.datasources.dev.password=private-password-4908
            compare.datasources.dev.read-only=true
            """);
        Files.writeString(override, "spring.config.import=" + base.toUri() + "\ncompare.datasources.dev.allow-temp-tables=false\ncompare.datasources.dev.allowed-functions=opera.fn_*\n");
        var application = new SpringApplication(CompareDbToolApplication.class); application.setLogStartupInfo(false);
        try (var context = application.run("--spring.config.location=" + base.toUri(),
                "--spring.config.additional-location=" + override.toUri(), "--spring.main.banner-mode=off")) {
            var s = context.getBean(DatasourceSettings.class); DatasourceDiagnostics.capture(context.getEnvironment(),s);
            assertEquals(Set.of(base.toString(),override.toString()), new HashSet<>(s.datasourceFiles()));
            assertFalse(s.allowsTempTables("dev"));
            String startup = String.join("\n",s.diagnostics());
            var captured = new ByteArrayOutputStream(); var previous = System.out;
            try {
                System.setOut(new PrintStream(captured));
                assertEquals(0,new Cli(s).execute(Cli.parse(new String[]{"--datasources"})));
            } finally { System.setOut(previous); }
            String output = startup + captured;
            for (String secret : List.of("jdbc:h2:","private-user-4908","private-password-4908")) assertFalse(output.contains(secret));
            for (String text : List.of(base.toString(),override.toString(),"read-only=true","prod=false","allow-temp-tables=false","allowed-functions=opera.fn_*"))
                assertTrue(output.contains(text),text);
        }
    }
}
