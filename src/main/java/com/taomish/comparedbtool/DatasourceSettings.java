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
    public boolean allowsTempTables(String name) {
        var definition = datasources.get(name);
        return definition != null && !QueryRunner.production(name, definition) && definition.isAllowTempTables();
    }
    public List<String> diagnostics() {
        var lines = new java.util.ArrayList<String>();
        lines.add("Datasource files loaded: " + configurationDescription());
        datasources.forEach((name, d) -> lines.add(name + " [read-only=" + d.isReadOnly()
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
        public boolean isAllowTempTables() { return !prod && !Boolean.FALSE.equals(allowTempTables); }
        public void setAllowTempTables(boolean value) { allowTempTables = value; }
        public String getAllowedFunctions() { return allowedFunctions; }
        public void setAllowedFunctions(String value) { allowedFunctions = value; }
    }
}
