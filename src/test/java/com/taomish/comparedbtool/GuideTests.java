package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HOW-TO-USE.md is for agents that will copy its examples: they must work, and the flags and functions it names must exist. */
class GuideTests {
    @TempDir Path tmp;
    private static final Path GUIDE = Path.of("HOW-TO-USE.md");

    private String guide() throws Exception { return Files.readString(GUIDE); }

    private DatasourceSettings settings() {
        var settings = new DatasourceSettings();
        for (String name : new String[]{"mysql-oprdb02", "postgres-dev"}) {
            var d = new DatasourceSettings.Definition();
            d.setUrl("jdbc:postgresql://unreachable.invalid:1/x"); d.setUsername("u"); d.setPassword("p"); d.setReadOnly(true);
            settings.getDatasources().put(name, d);
        }
        return settings;
    }

    private List<String> blocks(String language) throws Exception {
        Matcher m = Pattern.compile("```" + language + "\\R(.*?)```", Pattern.DOTALL).matcher(guide());
        List<String> found = new ArrayList<>();
        while (m.find()) found.add(m.group(1));
        return found;
    }

    @Test void everyCompleteRuleInTheGuideIsValidAndItsChecksHold() throws Exception {
        List<String> rules = blocks("xml rule");
        assertThat(rules).as("the guide should contain complete example rules").hasSizeGreaterThanOrEqualTo(6);
        int n = 0;
        for (String xml : rules) {
            Path file = tmp.resolve("example-" + (++n) + ".xml");
            Files.writeString(file, xml);
            Rules loaded = RuleLoader.load(file);
            RuleLoader.validate(loaded, settings(), true);                        // SQL guard, ids, parameters, aliases, cycles, expressions, suggestions
            RuleLoader.checks(loaded, RuleLoader.parameters(loaded, Map.of()));   // the <check> examples hold for their own defaults
        }
    }

    @Test void everyRestoreRuleInTheGuideIsValid() throws Exception {
        List<String> restores = blocks("json restore");
        assertThat(restores).isNotEmpty();
        int n = 0;
        for (String json : restores) {
            Path file = tmp.resolve("restore-" + (++n) + ".json");
            Files.writeString(file, json);
            DataRestorer.Rule rule = DataRestorer.load(file);
            assertThat(rule.steps).isNotEmpty();
        }
    }

    @Test void everyFlagTheGuideNamesExists() throws Exception {
        Pattern flag = Pattern.compile("(?<![\\w-])--[a-z][a-z-]*");
        Set<String> known = new HashSet<>();
        Matcher help = flag.matcher(Cli.HELP);
        while (help.find()) known.add(help.group());
        Matcher doc = flag.matcher(guide());
        Set<String> unknown = new TreeSet<>();
        while (doc.find()) {
            String f = doc.group();
            if (!known.contains(f) && !f.equals("--logging") && !f.equals("--spring")) unknown.add(f);
        }
        assertThat(unknown).as("flags in HOW-TO-USE.md that the tool does not have").isEmpty();
        // and the other way round: every real flag is documented
        String text = guide();
        Set<String> undocumented = new TreeSet<>(known);
        undocumented.removeIf(f -> text.contains("`" + f) || text.contains(f + " ") || text.contains(f + "`"));
        assertThat(undocumented).as("flags missing from HOW-TO-USE.md").isEmpty();
    }

    @Test void everyExpressionFunctionIsListed() throws Exception {
        String text = guide();
        for (String name : Expressions.functionNames(true))
            assertThat(Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "(?![\\w])").matcher(text).find()).as("function " + name + " is documented").isTrue();
    }

    @Test void shippedFilesTheGuideMentionsExist() throws Exception {
        Matcher m = Pattern.compile("`(rules/[\\w.-]+\\.(?:xml|json))`").matcher(guide());
        int found = 0;
        while (m.find()) { found++; assertThat(Path.of(m.group(1))).as(m.group(1)).exists(); }
        assertThat(found).isGreaterThan(0);
    }

    @Test void theAgentFilesPointAtTheGuide() throws Exception {
        assertThat(Files.readString(Path.of("AGENTS.md"))).contains("HOW-TO-USE.md");
        assertThat(Files.readString(Path.of("CLAUDE.md"))).contains("AGENTS.md").contains("HOW-TO-USE.md");
        assertThat(Files.readString(Path.of("README.md"))).contains("HOW-TO-USE.md");
    }
}
