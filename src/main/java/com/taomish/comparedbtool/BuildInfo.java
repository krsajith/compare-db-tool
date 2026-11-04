package com.taomish.comparedbtool;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Which build is running: shown in the menu and in SUMMARY.md, so a stale copy of the jar is obvious. */
final class BuildInfo {
    private BuildInfo() {}
    static String line() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in == null) return "compare-db-tool (development build, no build info)";
            Properties p = new Properties();
            p.load(in);
            return "compare-db-tool " + p.getProperty("build.version", "?") + ", built " + p.getProperty("build.time", "?");
        } catch (IOException e) {
            return "compare-db-tool (build info unreadable)";
        }
    }
}
