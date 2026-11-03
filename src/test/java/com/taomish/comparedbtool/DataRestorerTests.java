package com.taomish.comparedbtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataRestorerTests {
    @TempDir Path tmp;
    String url;
    DatasourceSettings settings;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA live"); s.execute("CREATE SCHEMA ref"); s.execute("CREATE SCHEMA parity_work");
            for (String schema : new String[]{"live", "ref"})
                s.execute("CREATE TABLE " + schema + ".price (uuid VARCHAR(36), bu VARCHAR(5), security_id VARCHAR(20), value_date DATE, "
                    + "settle_price DOUBLE PRECISION, updated_by VARCHAR(20))");
            // reference: A=1.5, B=2.5, C=3.5 ; live: A=1.5 (equal), B=9.9 (changed), D=4.0 (extra), C missing; other BU untouched
            s.execute("INSERT INTO ref.price VALUES ('u1','CFS','A',DATE '2026-10-02',1.5,'mig'),('u2','CFS','B',DATE '2026-10-02',2.5,'mig'),"
                + "('u3','CFS','C',DATE '2026-10-02',3.5,'mig')");
            s.execute("INSERT INTO live.price VALUES ('u1','CFS','A',DATE '2026-10-02',1.5,'x'),('u2','CFS','B',DATE '2026-10-02',9.9,'load'),"
                + "('u4','CFS','D',DATE '2026-10-02',4.0,'load'),('u9','GRN','A',DATE '2026-10-02',7.0,'other')");
        }
        settings = new DatasourceSettings();
        var d = new DatasourceSettings.Definition(); d.setUrl(url); d.setUsername("sa"); d.setPassword("");
        d.setReadOnly(true); d.setDriverClassName("org.h2.Driver"); settings.getDatasources().put("test", d);
    }

    private Path rule(String name, boolean deleteExtra) throws Exception {
        Path file = tmp.resolve(name + ".json");
        Files.writeString(file, """
            {"name": "%s", "db": "test", "backupSchema": "parity_work",
             "steps": [{"id": "prices", "table": "live.price", "from": "ref.price",
                        "where": "bu = 'CFS' AND value_date = DATE '2026-10-02'",
                        "key": ["bu", "security_id", "value_date"], "deleteExtra": %s}]}
            """.formatted(name, deleteExtra));
        return file;
    }

    private Cli.Options options(Path rule, boolean apply) {
        Cli.Options o = new Cli.Options(); o.restore = rule; o.apply = apply; o.out = tmp.resolve("out"); return o;
    }

    private String prices() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT bu, security_id, settle_price FROM live.price ORDER BY bu, security_id")) {
            while (rs.next()) sb.append(rs.getString(1)).append(':').append(rs.getString(2)).append('=').append(rs.getDouble(3)).append(' ');
        }
        return sb.toString().trim();
    }

    @Test void previewChangesNothing() throws Exception {
        String before = prices();
        assertThat(new DataRestorer(settings).execute(options(rule("r1", false), false))).isZero();
        assertThat(prices()).isEqualTo(before);
        Path summary = Files.list(tmp.resolve("out")).findFirst().orElseThrow().resolve("RESTORE.md");
        assertThat(Files.readString(summary)).contains("PREVIEW").contains("| prices | live.price | ref.price | 3 | 3 | 1 | 1 | 1 | 0 | 4 |");
    }

    @Test void applyRestoresKeepsExtraAndOtherScopeAndUndoPutsItBack() throws Exception {
        String before = prices();
        new DataRestorer(settings).execute(options(rule("r2", false), true));
        assertThat(prices()).isEqualTo("CFS:A=1.5 CFS:B=2.5 CFS:C=3.5 CFS:D=4.0 GRN:A=7.0");
        Path dir = Files.list(tmp.resolve("out")).findFirst().orElseThrow();
        Path undo = dir.resolve("undo-r2.json");
        assertThat(undo).exists();
        new DataRestorer(settings).execute(options(undo, true));
        assertThat(prices()).isEqualTo(before);
    }

    @Test void deleteExtraRemovesRowsTheReferenceDoesNotHave() throws Exception {
        new DataRestorer(settings).execute(options(rule("r3", true), true));
        assertThat(prices()).isEqualTo("CFS:A=1.5 CFS:B=2.5 CFS:C=3.5 GRN:A=7.0");
    }

    @Test void scopeMustBeAConditionOnly() {
        DataRestorer.Step step = new DataRestorer.Step();
        step.id = "x"; step.table = "live.price"; step.from = "ref.price"; step.key = java.util.List.of("bu");
        DataRestorer.Rule r = new DataRestorer.Rule(); r.name = "bad"; r.db = "test"; r.steps = java.util.List.of(step);
        step.where = "bu = 'CFS'; DELETE FROM live.price";
        assertThatThrownBy(() -> DataRestorer.validate(r)).hasMessageContaining("only be a condition");
        step.where = "bu = 'CFS' -- comment";
        assertThatThrownBy(() -> DataRestorer.validate(r)).hasMessageContaining("only be a condition");
        step.where = null;
        assertThatThrownBy(() -> DataRestorer.validate(r)).hasMessageContaining("where is required");
    }

    @Test void productionNeedsAllowProd() throws Exception {
        settings.getDatasources().get("test").setProd(true);
        assertThatThrownBy(() -> new DataRestorer(settings).execute(options(rule("r4", false), false))).hasMessageContaining("--allow-prod");
    }
}
