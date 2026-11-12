package com.taomish.comparedbtool;

import com.zaxxer.hikari.HikariConfig;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Global PostgreSQL session settings for every connection the tool opens (comparison reads, restore, load, change):
 * config/session.properties (optional, name=value per line, e.g. work_mem=256MB) plus --session name=value on the
 * command line, which adds to or overrides the file for one run. Any server setting can be given; nothing is filtered.
 * They are passed as the PostgreSQL JDBC "options" property (-c name=value), so they hold for the whole connection,
 * and they are printed once at the start of a run. MySQL datasources are not affected.
 */
final class SessionSettings {
    static final Path FILE = Path.of("config", "session.properties");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static volatile Map<String, String> global = Map.of();
    private static volatile boolean printed;

    private SessionSettings() { }

    /** Reads config/session.properties (if present) and applies the --session overrides; call once per run. */
    static void init(Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (Files.isRegularFile(FILE)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { p.load(r); }
            catch (Exception e) { throw new ToolException("Cannot read " + FILE + ": " + e.getMessage(), e); }
            p.stringPropertyNames().stream().sorted().forEach(k -> merged.put(k.trim(), p.getProperty(k).trim()));
        }
        if (overrides != null) merged.putAll(overrides);
        for (var e : merged.entrySet())
            if (!NAME.matcher(e.getKey()).matches()) throw new ToolException("Session setting name " + e.getKey() + " is not a setting name");
        global = Collections.unmodifiableMap(merged);
        printed = false;
    }

    static Map<String, String> current() { return global; }

    /** Adds the settings to a PostgreSQL pool configuration (JDBC "options"); other databases are left alone. */
    static void configure(HikariConfig config, String jdbcUrl) {
        Map<String, String> s = global;
        if (s.isEmpty() || jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) return;
        config.addDataSourceProperty("options", options(s));
        if (!printed) { printed = true; Progress.get().line("  PostgreSQL session settings: " + s); }
    }

    /** -c name=value pairs; spaces and backslashes in values are escaped as the PostgreSQL driver expects. */
    static String options(Map<String, String> s) {
        StringBuilder b = new StringBuilder();
        for (var e : s.entrySet()) {
            if (b.length() > 0) b.append(' ');
            b.append("-c ").append(e.getKey()).append('=').append(e.getValue().replace("\\", "\\\\").replace(" ", "\\ "));
        }
        return b.toString();
    }
}
