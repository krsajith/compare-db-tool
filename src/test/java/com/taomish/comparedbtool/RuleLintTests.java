package com.taomish.comparedbtool;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RuleLintTests {
    private static List<String> advice(String body, ExportMode mode) throws Exception {
        Path f = Files.createTempFile("lint", ".xml");
        Files.writeString(f, "<rules name=\"t\">" + body + "</rules>");
        return RuleLint.advice(RuleLoader.load(f), mode);
    }

    @Test void standaloneSampleQueryIsFlagged() throws Exception {
        var a = advice("<sql id=\"q\" db=\"x\" export=\"sample\"><![CDATA[SELECT 1 AS a]]></sql>", ExportMode.SAMPLE);
        assertThat(a).anyMatch(l -> l.contains("query q") && l.contains("NOT be kept"));
        assertThat(a).anyMatch(l -> l.contains("no <expect>")).anyMatch(l -> l.contains("no <compress>"));
    }

    @Test void defaultModeAlsoFlagsAndFullDoesNot() throws Exception {
        assertThat(advice("<sql id=\"q\" db=\"x\"><![CDATA[SELECT 1 AS a]]></sql>", ExportMode.SAMPLE)).anyMatch(l -> l.contains("NOT be kept"));
        assertThat(advice("<sql id=\"q\" db=\"x\" export=\"full\"><![CDATA[SELECT 1 AS a]]></sql>", ExportMode.SAMPLE)).noneMatch(l -> l.contains("NOT be kept"));
        assertThat(advice("<sql id=\"q\" db=\"x\"><![CDATA[SELECT 1 AS a]]></sql>", ExportMode.FULL)).noneMatch(l -> l.contains("NOT be kept"));
    }

    @Test void comparedQueriesAreNotFlaggedAndCompleteRuleIsClean() throws Exception {
        var a = advice("<sql id=\"s\" db=\"x\"><![CDATA[SELECT id FROM t]]></sql><sql id=\"t\" db=\"x\"><![CDATA[SELECT id FROM u]]></sql>"
            + "<compare id=\"c\" source=\"s\" target=\"t\"><key columns=\"id\"/></compare><expect ref=\"c\" matched=\"true\"/>"
            + "<compress id=\"z\" file=\"z.zip\"><include ref=\"c\"/></compress>", ExportMode.SAMPLE);
        assertThat(a).isEmpty();
    }

    @Test void selectStarIsFlagged() throws Exception {
        assertThat(advice("<sql id=\"q\" db=\"x\" export=\"full\"><![CDATA[SELECT * FROM t]]></sql>", ExportMode.SAMPLE)).anyMatch(l -> l.contains("SELECT *"));
    }
}
