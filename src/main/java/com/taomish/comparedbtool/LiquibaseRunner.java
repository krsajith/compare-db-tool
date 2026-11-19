package com.taomish.comparedbtool;

import com.taomish.comparedbtool.logging.SecretRedactor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.StringWriter;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import liquibase.*;
import liquibase.changelog.*;
import liquibase.changelog.visitor.AbstractChangeExecListener;
import liquibase.database.*;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.executor.ExecutorService;
import liquibase.executor.LoggingExecutor;
import liquibase.resource.DirectoryResourceAccessor;

/** Runs an external changelog with resource-relative identities, independently of Spring startup. */
final class LiquibaseRunner {
    record Source(Path file, Path root, String changelog) {
        static Source resolve(Path requested) {
            Path file = requested.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) throw new ToolException("Liquibase changelog does not exist: " + file);
            Path root = file.getParent();
            for (Path p = root; p != null; p = p.getParent()) {
                if (p.endsWith(Path.of("src", "main", "resources"))) { root = p; break; }
            }
            return new Source(file, root, root.relativize(file).toString().replace('\\', '/'));
        }
    }

    private final DatasourceSettings settings;
    LiquibaseRunner(DatasourceSettings settings) { this.settings = settings; }

    int execute(Cli.Options o) throws Exception {
        return Scope.child(Map.of(Scope.Attr.logService.name(), new SafeLogService(),
            Scope.Attr.ui.name(), new liquibase.ui.LoggerUIService(), "liquibase.analytics.enabled", false), () -> executeScoped(o));
    }

    private int executeScoped(Cli.Options o) throws Exception {
        long start = System.nanoTime();
        Source source = Source.resolve(o.liquibase);
        String mode = o.validate ? "VALIDATE" : o.status ? "STATUS" : o.apply ? "APPLY" : "PREVIEW";
        String dbName = settings.targetName() != null ? settings.targetName() : o.targetEnv;
        DatasourceSettings.Definition definition = dbName == null ? null : settings.getDatasources().get(dbName);
        if (!o.validate) {
            if (dbName == null) throw new ToolException("Liquibase needs --target-env NAME (or TARGET_ENV).");
            if (definition == null) throw new ToolException(DatasourceSettings.unknownDatasourceMessage(dbName));
            if (QueryRunner.production(dbName, definition) && !o.allowProd)
                throw new ToolException("Datasource " + dbName + " requires --allow-prod.");
            if (o.apply) settings.requireWritable(dbName);
            if (definition.getUrl() == null || !definition.getUrl().startsWith("jdbc:"))
                throw new ToolException("Datasource " + dbName + " needs a JDBC URL.");
            if (definition.getPassword() != null && definition.getPassword().contains("${"))
                throw new ToolException("Set the password environment variable for datasource " + dbName + ".");
            SecretRedactor.register(definition.getPassword());
        }
        Path base = o.out.toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path dir = Files.createTempDirectory(base, "liquibase-" + mode.toLowerCase(Locale.ROOT) + "-");
        List<String> pending = new ArrayList<>();
        List<String> results = new ArrayList<>();
        String[] status = {"FAILED"};
        Progress.get().line("Liquibase " + mode + ": " + source.file());
        Progress.get().line("  resource root: " + source.root() + "; changelog: " + source.changelog());
        try (var resources = new DirectoryResourceAccessor(source.root())) {
            // Keep offline parsing and checksums scoped to this project's resources. No JDBC connection is opened here.
            Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), resources), () -> {
                var offline = new PostgresDatabase() {
                    @Override public List<RanChangeSet> getRanChangeSetList() { return List.of(); }
                };
                offline.setConnection(new OfflineConnection("offline:postgresql", resources));
                configureSchema(offline, o.changelogSchema);
                try (var lb = new Liquibase(source.changelog(), resources, offline)) {
                    var log = lb.getDatabaseChangeLog();
                    // Validate definitions offline, but leave database-dependent precondition checks to connected operations.
                    var preconditions = log.getPreconditions();
                    if (preconditions != null && preconditions.validate(offline).hasErrors())
                        throw new ToolException("Invalid changelog preconditions: " + preconditions.validate(offline));
                    var nested = preconditions == null ? List.<liquibase.precondition.Precondition>of()
                        : new ArrayList<>(preconditions.getNestedPreconditions());
                    if (preconditions != null) preconditions.getNestedPreconditions().clear();
                    try { log.validate(offline, new Contexts(), new LabelExpression()); }
                    finally { if (preconditions != null) preconditions.getNestedPreconditions().addAll(nested); }
                    for (var set : log.getChangeSets()) set.generateCheckSum(ChecksumVersion.latest());
                    Progress.get().line("  parsed " + log.getChangeSets().size() + " changesets; includes and checksums resolved offline");
                }
            });
            if (o.validate) {
                status[0] = "VALID";
                Progress.get().line("Liquibase validation passed. No connection was opened. SQL correctness and database checksums require a live operation.");
                return 0;
            }
            HikariConfig config = new HikariConfig();
            config.setPoolName("liquibase-" + mode.toLowerCase(Locale.ROOT));
            config.setJdbcUrl(definition.getUrl());
            config.setUsername(definition.getUsername());
            config.setPassword(definition.getPassword());
            if (definition.getDriverClassName() != null && !definition.getDriverClassName().isBlank())
                config.setDriverClassName(definition.getDriverClassName());
            config.setMaximumPoolSize(1); config.setMinimumIdle(0);
            config.setConnectionTimeout(30_000); config.setInitializationFailTimeout(-1);
            config.setAutoCommit(false); config.setReadOnly(!o.apply);
            SessionSettings.configure(config, definition.getUrl());
            if (definition.getUrl().startsWith("jdbc:postgresql:")) {
                config.addDataSourceProperty("tcpKeepAlive", "true");
                if (!o.apply) config.setConnectionInitSql("SET default_transaction_read_only = on");
            }
            try (var pool = new HikariDataSource(config); Connection connection = pool.getConnection()) {
                if (o.changelogSchema != null) connection.setSchema(o.changelogSchema);
                Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
                configureSchema(database, o.changelogSchema);
                if (!o.apply) Scope.getCurrentScope().getSingleton(ChangeLogHistoryServiceFactory.class)
                    .register(new ReadOnlyHistory(database));
                try (var lb = new Liquibase(source.changelog(), resources, database)) {
                    // false prevents status from creating/upgrading Liquibase's tracking tables.
                    List<ChangeSet> eligible = lb.listUnrunChangeSets(new Contexts(), new LabelExpression(), false);
                    for (ChangeSet set : eligible)
                        pending.add(identity(set));
                    Files.writeString(dir.resolve("PENDING.md"), "# Changesets eligible to run\n\n" + String.join("\n", pending.stream().map(s -> "- " + s).toList()) + "\n");
                    Progress.get().line("  " + pending.size() + " changesets eligible to run");
                    if (o.status) status[0] = "STATUS";
                    else if (o.apply) {
                        lb.setChangeExecListener(new ExecutionLog(results));
                        lb.update(new Contexts(), new LabelExpression());
                        status[0] = "APPLIED";
                    } else {
                        StringWriter sql = new StringWriter();
                        try {
                            Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), resources, Scope.Attr.database.name(), database), () -> {
                                var executor = new LoggingExecutor(Scope.getCurrentScope().getSingleton(ExecutorService.class)
                                    .getExecutor("jdbc", database), sql, database);
                                executor.setResourceAccessor(resources);
                                executor.comment("Migration SQL preview only. Preconditions are evaluated at apply time; tracking and lock SQL are omitted.");
                                for (ChangeSet set : eligible) {
                                    executor.comment(identity(set));
                                    if (set.getRunWith() != null && !set.getRunWith().equalsIgnoreCase("jdbc"))
                                        throw new ToolException("Cannot preview changeset with runWith=" + set.getRunWith() + ": " + identity(set));
                                    for (var change : set.getChanges()) executor.execute(change, set.getSqlVisitors());
                                }
                            });
                        }
                        finally {
                            Files.writeString(dir.resolve("PREVIEW.sql"), SecretRedactor.clean(sql.toString()));
                        }
                        status[0] = "PREVIEW";
                    }
                    if (!o.apply) connection.rollback();
                }
            }
            return 0;
        } catch (Exception e) {
            results.add("ERROR: " + SecretRedactor.clean(e.getMessage()));
            throw e;
        } finally {
            Scope.getCurrentScope().getSingleton(ChangeLogHistoryServiceFactory.class).resetAll();
            Scope.getCurrentScope().getSingleton(ExecutorService.class).reset();
            long millis = (System.nanoTime() - start) / 1_000_000;
            Files.writeString(dir.resolve("RESULTS.md"), "# Changeset execution\n\n" + String.join("\n", results) + "\n");
            Files.writeString(dir.resolve("SUMMARY.md"), SecretRedactor.clean("# Liquibase\n\nStatus: " + status[0]
                + "\nMode: " + mode + "\nChangelog: " + source.file() + "\nResource root: " + source.root()
                + "\nChangelog identity: " + source.changelog() + "\nDatasource: " + Objects.toString(dbName, "none (offline)")
                + "\nSchema: " + Objects.toString(o.changelogSchema, "connection default") + "\nEligible changesets: " + pending.size()
                + "\nComplete operation: " + millis + " ms\n\n"
                + "Preview generates SQL; it does not execute migrations. Apply uses Liquibase tracking and locking. "
                + "Earlier changesets may remain committed after a failure; no automatic whole-run rollback is provided.\n"));
            Progress.get().line("Liquibase " + status[0] + " in " + millis + " ms; summary: " + dir.resolve("SUMMARY.md"));
        }
    }

    private static void configureSchema(Database database, String schema) throws Exception {
        if (schema != null) { database.setDefaultSchemaName(schema); database.setLiquibaseSchemaName(schema); }
    }

    private static String identity(ChangeSet set) { return set.getFilePath() + "::" + set.getId() + "::" + set.getAuthor(); }

    /** Route library messages through the tool's redaction and logging configuration, including its UI output. */
    private static final class SafeLogService extends liquibase.logging.core.AbstractLogService {
        @Override public int getPriority() { return 1000; }
        @Override public liquibase.logging.Logger getLog(Class type) {
            var logger = org.slf4j.LoggerFactory.getLogger(type);
            return new liquibase.logging.core.AbstractLogger() {
                @Override public void log(java.util.logging.Level level, String message, Throwable failure) {
                    String safe = SecretRedactor.clean(message + (failure == null ? "" : " (" + failure.getMessage() + ")"));
                    if (level.intValue() >= java.util.logging.Level.SEVERE.intValue()) logger.error("{}", safe);
                    else if (level.intValue() >= java.util.logging.Level.WARNING.intValue()) logger.warn("{}", safe);
                    else if (level.intValue() >= java.util.logging.Level.INFO.intValue()) logger.info("{}", safe);
                    else logger.debug("{}", safe);
                }
            };
        }
    }

    /** Include parsing in Liquibase 4.31 calls history.init even when listUnrunChangeSets disables table checks. */
    private static final class ReadOnlyHistory extends StandardChangeLogHistoryService {
        private final Database owner;
        ReadOnlyHistory(Database owner) { this.owner = owner; setDatabase(owner); }
        @Override public boolean supports(Database database) { return database == owner; }
        @Override public int getPriority() { return super.getPriority() + 100; }
        @Override public void init() { /* Read existing history only; never create or upgrade tracking tables. */ }
        @Override public void replaceChecksum(ChangeSet set) throws liquibase.exception.DatabaseException {
            throw new liquibase.exception.DatabaseException("Checksum repair requires an explicit Liquibase maintenance operation.");
        }
        @Override public void replaceFilePath(ChangeSet set, String path) throws liquibase.exception.DatabaseException {
            throw new liquibase.exception.DatabaseException("Changelog path repair requires an explicit Liquibase maintenance operation.");
        }
    }

    private static final class ExecutionLog extends AbstractChangeExecListener {
        private final List<String> results;
        private final Map<String, Long> starts = new HashMap<>();
        ExecutionLog(List<String> results) { this.results = results; }
        @Override public void willRun(ChangeSet set, DatabaseChangeLog log, Database database, ChangeSet.RunStatus status) {
            starts.put(identity(set), System.nanoTime());
            Progress.get().line("  running " + identity(set));
        }
        @Override public void ran(ChangeSet set, DatabaseChangeLog log, Database database, ChangeSet.ExecType type) {
            record(set, type.toString());
        }
        @Override public void runFailed(ChangeSet set, DatabaseChangeLog log, Database database, Exception failure) {
            record(set, "FAILED: " + SecretRedactor.clean(failure.getMessage()));
        }
        private void record(ChangeSet set, String status) {
            long elapsed = (System.nanoTime() - starts.getOrDefault(identity(set), System.nanoTime())) / 1_000_000;
            String line = identity(set) + " - " + status + " - " + elapsed + " ms";
            results.add(SecretRedactor.clean(line));
            Progress.get().line("  " + SecretRedactor.clean(line));
        }
    }
}
