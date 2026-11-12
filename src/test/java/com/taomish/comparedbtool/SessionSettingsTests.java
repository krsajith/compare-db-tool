package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionSettingsTests {
    @AfterEach void reset() { SessionSettings.init(Map.of()); }

    private static Map<String, String> of(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test void anySettingIsPassedToPostgresqlConnections() {
        SessionSettings.init(of("work_mem", "256MB", "maintenance_work_mem", "2GB", "synchronous_commit", "off",
                "statement_timeout", "0", "search_path", "opera, public"));
        HikariConfig config = new HikariConfig();
        SessionSettings.configure(config, "jdbc:postgresql://host:5432/db");
        assertThat(config.getDataSourceProperties().getProperty("options"))
                .isEqualTo("-c work_mem=256MB -c maintenance_work_mem=2GB -c synchronous_commit=off -c statement_timeout=0 -c search_path=opera,\\ public");
    }

    @Test void otherDatabasesAreLeftAlone() {
        SessionSettings.init(of("work_mem", "256MB"));
        HikariConfig config = new HikariConfig();
        SessionSettings.configure(config, "jdbc:mysql://host:3306/db");
        assertThat(config.getDataSourceProperties().getProperty("options")).isNull();
    }

    @Test void noSettingsNoOptions() {
        SessionSettings.init(Map.of());
        HikariConfig config = new HikariConfig();
        SessionSettings.configure(config, "jdbc:postgresql://host:5432/db");
        assertThat(config.getDataSourceProperties().getProperty("options")).isNull();
    }

    @Test void nameMustBeASettingName() {
        assertThatThrownBy(() -> SessionSettings.init(of("work_mem; drop", "1")))
                .isInstanceOf(ToolException.class).hasMessageContaining("not a setting name");
    }

    @Test void commandLineParsesSessionPairs() {
        Cli.Options o = Cli.parse(new String[] {"--rules", "x.xml", "--session", "work_mem=256MB", "--session=lock_timeout=30s"});
        assertThat(o.session).containsEntry("work_mem", "256MB").containsEntry("lock_timeout", "30s");
        assertThatThrownBy(() -> Cli.parse(new String[] {"--rules", "x.xml", "--session", "work_mem"}))
                .isInstanceOf(ToolException.class);
    }
}
