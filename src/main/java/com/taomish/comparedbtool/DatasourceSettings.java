package com.taomish.comparedbtool;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "compare")
public class DatasourceSettings {
    private Map<String, Definition> datasources = new LinkedHashMap<>();
    public Map<String, Definition> getDatasources() { return datasources; }
    public void setDatasources(Map<String, Definition> datasources) { this.datasources = datasources; }

    public static class Definition {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private boolean readOnly = true;
        private boolean prod;
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
    }
}
