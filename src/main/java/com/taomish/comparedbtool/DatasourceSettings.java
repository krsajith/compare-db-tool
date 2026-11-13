package com.taomish.comparedbtool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "compare")
public class DatasourceSettings {
    private Map<String, Definition> datasources = new LinkedHashMap<>();
    public Map<String, Definition> getDatasources() { return datasources; }
    public void setDatasources(Map<String, Definition> datasources) { this.datasources = datasources; }
    private List<String> datasourceFiles = List.of();
    public void datasourceFiles(List<String> files) { datasourceFiles = List.copyOf(files); }
    public List<String> datasourceFiles() { return datasourceFiles; }
    public String configurationDescription() {
        return datasourceFiles.isEmpty() ? "no datasource file loaded (defaults/environment/command line); expected default file "
            + java.nio.file.Path.of("config/datasources.properties").toAbsolutePath().normalize()
            : String.join(", ", datasourceFiles);
    }
    /** The name rules use for "the database chosen for this run": db="target" resolves to the datasource named here. */
    public static final String TARGET_ALIAS = "target";
    public void aliasTarget(String datasourceName) {
        var definition = datasources.get(datasourceName);
        if (definition == null) throw new ToolException("Unknown datasource for the target alias: " + datasourceName
            + ". Configured: " + datasources.keySet());
        // The alias must not hide production: keep the guard even if only the real name said "prod".
        if (QueryRunner.production(datasourceName, definition)) definition.setProd(true);
        datasources.put(TARGET_ALIAS, definition);
        targetName = datasourceName;
    }
    private String targetName;
    /** Which real datasource db="target" meant in this run, or null when the alias was not set. */
    public String targetName() { return targetName; }
    /** One line naming the real datasource and database location (JDBC URL without options or credentials) of each name a run used, so an output folder or zip shows its environment. */
    public String environmentLine(java.util.Collection<String> names) {
        var parts = new java.util.ArrayList<String>();
        for (String name : new java.util.LinkedHashSet<>(names)) {
            var definition = datasources.get(name);
            String url = definition == null || definition.getUrl() == null ? "" : definition.getUrl();
            int cut = url.indexOf('?');
            if (cut >= 0) url = url.substring(0, cut);
            url = url.replaceFirst("^jdbc:", "").replaceFirst("//[^/@]*@", "//");
            String label = TARGET_ALIAS.equals(name) && targetName != null ? "target = " + targetName : name;
            parts.add(label + (url.isEmpty() ? "" : " (" + url + ")"));
        }
        return String.join("; ", parts);
    }
    public static String unknownDatasourceMessage(String name) {
        return TARGET_ALIAS.equals(name)
            ? "Unknown datasource: target. Choose the database for this run with --target-env NAME (for example --target-env postgres-uat) "
                + "or the environment variable TARGET_ENV; NAME must be a datasource from config/datasources.properties."
            : "Unknown datasource: " + name;
    }
    public boolean allowsTempTables(String name) {
        var definition = datasources.get(name);
        return definition != null && !QueryRunner.production(name, definition) && definition.isAllowTempTables();
    }
    /** Writes (apply of restore, load, change) are allowed unless the datasource says allow-write=false. Previews are always fine. */
    public void requireWritable(String name) {
        var definition = datasources.get(name);
        if (definition != null && !definition.isAllowWrite())
            throw new ToolException("Datasource " + name + " has allow-write=false: an apply is refused (a preview still works).");
    }
    public List<String> diagnostics() {
        var lines = new java.util.ArrayList<String>();
        lines.add("Datasource files loaded: " + configurationDescription());
        datasources.forEach((name, d) -> lines.add(name + " [read-only=" + d.isReadOnly() + ", allow-write=" + d.isAllowWrite()
            + ", prod=" + QueryRunner.production(name, d) + ", allow-temp-tables=" + allowsTempTables(name)
            + ", allowed-functions=" + d.getAllowedFunctions() + "]"));
        return lines;
    }

    public static class Definition {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private boolean readOnly = true;
        private boolean prod;
        private boolean allowWrite = true;
        private Boolean allowTempTables;
        private String allowedFunctions = "*";
        public String getUrl() { return url; }
        public void setUrl(String value) { url = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String value) { driverClassName = value; }
        public boolean isReadOnly() { return readOnly; }
        public void setReadOnly(boolean value) { readOnly = value; }
        public boolean isProd() { return prod; }
        public void setProd(boolean value) { prod = value; }
        public boolean isAllowWrite() { return allowWrite; }
        public void setAllowWrite(boolean value) { allowWrite = value; }
        public boolean isAllowTempTables() { return !prod && !Boolean.FALSE.equals(allowTempTables); }
        public void setAllowTempTables(boolean value) { allowTempTables = value; }
        public String getAllowedFunctions() { return allowedFunctions; }
        public void setAllowedFunctions(String value) { allowedFunctions = value; }
    }
}
