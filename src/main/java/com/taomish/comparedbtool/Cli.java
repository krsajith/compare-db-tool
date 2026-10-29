package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.taomish.comparedbtool.logging.SecretRedactor;

public final class Cli {
    private static final Logger LOG = LoggerFactory.getLogger(Cli.class);
    static final String HELP = """
        compare-db-tool: XML-driven JDBC export, comparison and ZIP packaging

        java -jar target/compare-db-tool.jar --rules rules/trades.xml [options]

        --rules FILE          XML rule file (or supply FILE as a positional argument)
        --validate            Validate rules and datasource references without connecting
        --datasources         List configured names without connecting or displaying secrets
        --out DIR             Output base directory (default: out)
        --param NAME=VALUE    Override a declared XML parameter; repeat as needed
        --limit N             Optional row cap per query; capped results are PARTIAL
        --partitions N        Disk partitions per comparison (default: 64, range 1..256)
        --export MODE         none, sample (default), or full; XML export overrides this
        --sample-rows N       Maximum records per comparison sample CSV (default: 200)
        --parallel N          Query workers (default: distinct datasource count; one active per datasource)
        --max-partition-mb N  Estimated comparison map/group budget (default: 256 MB)
        --allow-prod          Permit datasources marked prod=true or named with 'prod'
        --template-import FILE  Import one or more templates using a JSON rule (preview by default)
        --template-export FILE  Export requested template files, row metadata and an upload rule
        --template-upload FILE  Alias for --template-import; existing single-template rules still work
        --apply               Commit template replacement; otherwise roll back the preview
        --help                Show this help

        External configuration: config/datasources.properties, relative to working directory.
        Logging settings: config/logging.properties; logs/compare-db-tool.log and compare-db-tool-error.log.
        Override with --spring.config.additional-location=file:/path/to/datasources.properties
        One-run logging override: --logging.level.com.taomish.comparedbtool=DEBUG
        Exit codes: 0 equal/export complete/validated; 1 differences; 2 error or PARTIAL.
        """;
    public static final class Options {
        public Path rules;
        public Path templateUpload;
        public Path templateExport;
        public boolean apply;
        public Path out = Path.of("out");
        public Map<String, String> parameters = new LinkedHashMap<>();
        public int partitions = 64;
        public ExportMode export = ExportMode.SAMPLE;
        public int sampleRows = 200;
        public int parallel;
        public int maxPartitionMb = 256;
        public long limit;
        public boolean validate, datasources, allowProd, help;
    }
    private final DatasourceSettings settings;
    public Cli(DatasourceSettings settings) { this.settings = settings; }

    public static Options parse(String[] args) {
        Options options = new Options();
        if (args.length == 0) { options.help = true; return options; }
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.startsWith("--spring.") || argument.startsWith("--logging.")) {
                if (!argument.contains("=")) throw new ToolException("Spring/logging options must use --name=value syntax.");
                continue;
            }
            int equal = argument.indexOf('=');
            String name = equal > 0 ? argument.substring(0, equal) : argument;
            if (Set.of("--template-export", "--template-import", "--template-upload", "--rules", "--out", "--param", "--limit", "--partitions", "--export", "--sample-rows", "--parallel", "--max-partition-mb").contains(name)) {
                String value;
                if (equal > 0) value = argument.substring(equal + 1);
                else {
                    if (++i >= args.length) throw new ToolException("Missing value for " + name);
                    value = args[i];
                }
                try {
                    switch (name) {
                        case "--template-export" -> {
                            if (options.templateExport != null) throw new ToolException("Supply only one template export rule");
                            options.templateExport = Path.of(value);
                        }
                        case "--template-import", "--template-upload" -> {
                            if (options.templateUpload != null) throw new ToolException("Supply only one template import rule file");
                            options.templateUpload = Path.of(value);
                        }
                        case "--rules" -> options.rules = Path.of(value);
                        case "--out" -> options.out = Path.of(value);
                        case "--limit" -> options.limit = Long.parseLong(value);
                        case "--partitions" -> options.partitions = Integer.parseInt(value);
                        case "--export" -> options.export = ExportMode.parse(value);
                        case "--sample-rows" -> options.sampleRows = Integer.parseInt(value);
                        case "--max-partition-mb" -> options.maxPartitionMb = Integer.parseInt(value);
                        case "--parallel" -> {
                            options.parallel = Integer.parseInt(value);
                            if (options.parallel < 1 || options.parallel > 256) throw new IllegalArgumentException();
                        }
                        case "--param" -> {
                            int separator = value.indexOf('=');
                            if (separator <= 0) throw new ToolException("Use --param name=value.");
                            options.parameters.put(value.substring(0, separator), value.substring(separator + 1));
                        }
                        default -> throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) { throw new ToolException("Invalid value for " + name); }
            } else switch (argument) {
                case "--validate" -> options.validate = true;
                case "--apply" -> options.apply = true;
                case "--datasources" -> options.datasources = true;
                case "--allow-prod" -> options.allowProd = true;
                case "--help", "-h" -> options.help = true;
                default -> {
                    if (argument.startsWith("-") || options.rules != null) throw new ToolException("Unknown argument: " + argument);
                    options.rules = Path.of(argument);
                }
            }
        }
        if (options.limit < 0 || options.partitions < 1 || options.partitions > 256 || options.sampleRows < 0 || options.maxPartitionMb < 1)
            throw new ToolException("limit/sample-rows must be nonnegative, max-partition-mb positive, and partitions between 1 and 256.");
        if (options.templateUpload != null && (options.rules != null || options.datasources || !options.parameters.isEmpty()))
            throw new ToolException("Template upload cannot be combined with comparison rules, datasource listing or parameters.");
        if (options.templateExport != null && (options.templateUpload != null || options.rules != null
            || options.datasources || !options.parameters.isEmpty() || options.apply))
            throw new ToolException("Template export cannot be combined with import, comparison, datasource listing, parameters or --apply.");
        if (options.apply && (options.templateUpload == null || options.validate))
            throw new ToolException("--apply requires --template-upload and cannot be combined with --validate.");
        if (!options.help && !options.datasources && options.rules == null && options.templateUpload == null && options.templateExport == null)
            throw new ToolException("Supply --rules FILE, --template-import FILE or --template-export FILE.");
        return options;
    }

    public int execute(Options options) throws IOException {
        if (options.templateExport != null) return new TemplateExporter(settings).execute(options);
        if (options.templateUpload != null) return new TemplateUploader(settings).execute(options);
        if (options.datasources) {
            if (settings.getDatasources().isEmpty()) System.out.println("No datasources configured. Check config/datasources.properties.");
            settings.getDatasources().forEach((name, definition) -> System.out.println(name
                + " [read-only=" + definition.isReadOnly() + ", prod=" + QueryRunner.production(name, definition) + "]"));
            return 0;
        }
        LOG.info("Loading rules: {}", options.rules.toAbsolutePath().normalize());
        Rules rules = RuleLoader.load(options.rules);
        RuleLoader.validate(rules, settings, options.validate);
        Map<String, Object> parameters = RuleLoader.parameters(rules, options.parameters);
        if (options.validate) {
            LOG.info("VALID: {} queries, {} comparisons, {} ZIP packages. No connections opened.",
                rules.queries.size(), rules.comparisons.size(), rules.compressions.size());
            return 0;
        }
        Path base = options.out.toAbsolutePath().normalize();
        Files.createDirectories(base);
        String name = Objects.toString(rules.name, "comparison").replaceAll("[^A-Za-z0-9_.-]", "_");
        if (name.length() > 60) name = name.substring(0, 60);
        Path directory = Files.createTempDirectory(base, name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-");
        LOG.info("Output: {}", directory);
        Map<String, List<Path>> artifacts = new LinkedHashMap<>();
        Map<String, QueryRunner.Result> queries = new LinkedHashMap<>();
        List<ComparisonRunner.Result> comparisons = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        Path manualDirectory = directory.resolve("manual");
        Files.createDirectory(manualDirectory);
        try (QueryRunner runner = new QueryRunner(settings, options.allowProd)) {
            Files.copy(options.rules, directory.resolve("RULES.xml"));
            try (CSVPrinter params = CsvFiles.writer(directory.resolve("PARAMETERS.csv"), List.of("name", "value"))) {
                for (var entry : parameters.entrySet()) params.printRecord(Arrays.asList(entry.getKey(), entry.getValue()));
            }
            Set<String> completedQueries = new HashSet<>(), attemptedComparisons = new HashSet<>();
            ComparisonRunner comparator = new ComparisonRunner(options.partitions, options.maxPartitionMb);
            LOG.info("Scheduling queries: workers={} one query per datasource; identical SQL is reused",
                options.parallel == 0 ? rules.queries.stream().map(q -> q.db).distinct().count() : options.parallel);
            QueryScheduler.run(rules.queries, options.parallel,
                group -> executeGroup(group, runner, parameters, directory, options),
                outcomes -> {
                    for (var outcome : outcomes) {
                        completedQueries.add(outcome.query().id);
                        recordQuery(outcome, directory, manualDirectory, queries, artifacts, failures);
                    }
                    for (Rules.Compare compare : rules.comparisons) {
                        if (attemptedComparisons.contains(compare.id) || !completedQueries.contains(compare.source)
                            || !completedQueries.contains(compare.target)) continue;
                        attemptedComparisons.add(compare.id);
                        compareReady(compare, comparator, directory, manualDirectory, queries, artifacts, failures, comparisons, options);
                    }
                });
            boolean partial = queries.values().stream().anyMatch(QueryRunner.Result::partial);
            boolean differs = comparisons.stream().anyMatch(ComparisonRunner.Result::differs);
            int exit = partial || !failures.isEmpty() ? 2 : differs ? 1 : 0;
            StringBuilder summary = new StringBuilder("# " + rules.name + "\n\nStatus: "
                + (!failures.isEmpty() ? "INCOMPLETE / MANUAL ACTIONS REQUIRED" : partial ? "PARTIAL" : differs ? "DIFFERENCES" : "COMPLETE / EQUAL") + "; exit code: " + exit
                + "\n\nExport default: " + options.export.name().toLowerCase(Locale.ROOT) + "; sample cap: " + options.sampleRows
                + ". Full counts always include all fetched rows; sampling never limits comparison accuracy. Query CSVs are exported only in full mode.\n\n"
                + "| Query | Datasource | Rows | Time ms | Status |\n|---|---|---|---|---|\n");
            for (Rules.Sql query : rules.queries) {
                var r = queries.get(query.id);
                if (r == null) {
                    summary.append("| ").append(query.id).append(" | ").append(query.db).append(" | - | - | FAILED / BLOCKED |\n");
                    continue;
                }
                summary.append("| ").append(query.id).append(" | ").append(query.db).append(" | ").append(r.rows())
                    .append(" | ").append(r.millis()).append(" | ").append(r.partial() ? "PARTIAL" : "COMPLETE").append(" |\n");
            }
            summary.append("\n");
            summary.append("Manual actions: ").append(failures.size()).append(". See MANUAL_ACTIONS.md for failed queries and skipped comparisons.\n\n");
            for (var result : comparisons) summary.append("- ").append(result.id()).append(": ").append(result.headline()).append("\n");
            summary.append("\nQueries run with bounded concurrency and one active query per datasource; identical SQL is reused. "
                + "Ready comparisons run while other queries continue. Results are not a synchronized cross-database snapshot.\n");
            ComparisonRunner.writeSmallSummary(directory.resolve("SUMMARY.md"), summary.toString());
            writeManualActions(directory, failures);
            for (Rules.Compress compression : rules.compressions) {
                LinkedHashSet<Path> included = new LinkedHashSet<>(List.of(directory.resolve("SUMMARY.md"),
                    directory.resolve("RULES.xml"), directory.resolve("PARAMETERS.csv"), directory.resolve("MANUAL_ACTIONS.md")));
                for (Rules.Sql query : rules.queries) if (failures.containsKey(query.id))
                    included.add(directory.resolve(query.id + ".sql"));
                for (Rules.Include include : compression.includes) included.addAll(artifacts.get(include.ref));
                LOG.info("Creating ZIP {} with {} files", compression.file, included.size());
                try {
                    zip(directory, directory.resolve(compression.file), included);
                LOG.info("ZIP: {} ({} bytes)", directory.resolve(compression.file), Files.size(directory.resolve(compression.file)));
                } catch (IOException e) {
                    LOG.error("ZIP {} failed; exported files remain available", compression.file, e);
                    failures.put(compression.id, "ZIP " + compression.file + " failed: " + failureReason(e)
                        + ". Check disk space/permissions or compress the saved files manually; successful queries need not be rerun.");
                    exit = 2;
                    writeManualActions(directory, failures);
                    ComparisonRunner.writeSmallSummary(directory.resolve("SUMMARY.md"), summary
                        + "\nZIP creation failed; final exit code 2. See MANUAL_ACTIONS.md.\n");
                }
            }
            LOG.info("Summary: {}", directory.resolve("SUMMARY.md"));
            LOG.info("Manual actions: {} ({} issue(s))", directory.resolve("MANUAL_ACTIONS.md"), failures.size());
            return exit;
        } catch (Exception e) {
            String message = e instanceof ToolException ? e.getMessage() : "File processing failed. Check paths, free disk space and permissions.";
            try {
                Files.writeString(directory.resolve("ERROR.md"), "# INCOMPLETE RUN\n\n" + message
                    + "\n\nDo not treat any exported or partially compared files as proof of equality.\n");
            } catch (IOException reportingFailure) { e.addSuppressed(reportingFailure); }
            throw new ToolException(message + " See " + directory.resolve("ERROR.md"), e);
        } finally {
            try { CsvFiles.deleteTree(directory.resolve(".work")); }
            catch (IOException cleanup) { LOG.warn("Could not remove temporary files under {}", directory.resolve(".work"), cleanup); }
        }
    }

    private List<QueryScheduler.Outcome> executeGroup(QueryScheduler.Group group, QueryRunner runner,
            Map<String, Object> parameters, Path directory, Options options) throws IOException {
        List<QueryScheduler.Outcome> outcomes = new ArrayList<>();
        List<Rules.Sql> eligible = new ArrayList<>();
        for (Rules.Sql query : group.rules()) {
            try {
                if (ExportMode.resolve(query.export, options.export) != ExportMode.NONE)
                    Files.writeString(directory.resolve(query.id + ".sql"), Objects.toString(query.text, ""));
                checkQuery(query, parameters, options.allowProd);
                eligible.add(query);
            } catch (Exception error) { outcomes.add(new QueryScheduler.Outcome(query, null, error, false)); }
        }
        if (eligible.isEmpty()) return outcomes;
        if (eligible.size() == 1) {
            Rules.Sql query = eligible.getFirst();
            LOG.info("Starting query {} on {} (timeout {} s)...", query.id, query.db, query.timeout);
            try { outcomes.add(new QueryScheduler.Outcome(query, runner.run(query, parameters, options.limit, directory, options.export), null, true)); }
            catch (Exception error) { outcomes.add(new QueryScheduler.Outcome(query, null, error, true)); }
            return outcomes;
        }
        Rules.Sql first = eligible.getFirst(), canonical = new Rules.Sql();
        canonical.id = first.id; canonical.db = first.db; canonical.text = first.text; canonical.export = "none";
        canonical.timeout = eligible.stream().mapToInt(q -> q.timeout).min().orElseThrow();
        canonical.fetchSize = eligible.stream().mapToInt(q -> q.fetchSize).min().orElseThrow();
        List<Long> limits = eligible.stream().map(q -> QueryRunner.effectiveLimit(q, options.limit)).toList();
        canonical.maxRows = limits.contains(0L) ? 0 : limits.stream().mapToLong(Long::longValue).max().orElseThrow();
        Path sharedDirectory = directory.resolve(".work/reuse").resolve(first.id);
        Files.createDirectories(sharedDirectory);
        LOG.info("Executing identical SQL once on {} for ids={}; strictest timeout={} s", first.db,
            eligible.stream().map(q -> q.id).toList(), canonical.timeout);
        QueryRunner.Result shared;
        try { shared = runner.run(canonical, parameters, 0, sharedDirectory, ExportMode.NONE); }
        catch (Exception error) {
            eligible.forEach(q -> outcomes.add(new QueryScheduler.Outcome(q, null, error, true)));
            return outcomes;
        }
        for (Rules.Sql query : eligible) {
            try { outcomes.add(new QueryScheduler.Outcome(query, runner.reuse(query, shared, options.limit, directory, options.export), null, true)); }
            catch (Exception error) { outcomes.add(new QueryScheduler.Outcome(query, null, error, true)); }
        }
        return outcomes;
    }

    private static void recordQuery(QueryScheduler.Outcome outcome, Path directory, Path manualDirectory,
            Map<String, QueryRunner.Result> queries, Map<String, List<Path>> artifacts, Map<String, String> failures) throws IOException {
        Rules.Sql query = outcome.query();
        if (outcome.error() == null) {
            QueryRunner.Result result = outcome.result();
            queries.put(query.id, result); artifacts.put(query.id, result.files());
            if (result.partial()) failures.put(query.id, "PARTIAL query on " + query.db
                + ". Rerun without the row cap; do not compare this incomplete extract.");
            return;
        }
        LOG.error("Query {} on {} failed; continuing with other queries", query.id, query.db, outcome.error());
        String action = outcome.permitted() ? "Open " + query.id + ".sql in your database client connected to " + query.db
            + ", supply the parameters from PARAMETERS.csv, and check the SELECT manually."
            : "Correct the rule, datasource settings or authorization reported below, then rerun; do not bypass safety checks manually.";
        String detail = "Query " + query.id + " on " + query.db + " " + (outcome.permitted() ? "FAILED" : "BLOCKED")
            + ".\n\n" + failureReason(outcome.error()) + "\n\n" + action;
        failures.put(query.id, detail);
        Path sqlFile = directory.resolve(query.id + ".sql"), note = manualDirectory.resolve(query.id + ".md");
        if (!Files.exists(sqlFile)) Files.writeString(sqlFile, Objects.toString(query.text, ""));
        Files.writeString(note, "# " + query.id + "\n\n" + detail + "\n");
        List<Path> saved = new ArrayList<>(List.of(sqlFile, note));
        Path csv = directory.resolve(query.id + ".csv");
        if (Files.exists(csv)) {
            Path incomplete = manualDirectory.resolve(query.id + ".incomplete.csv");
            Files.move(csv, incomplete); saved.add(incomplete);
        }
        artifacts.put(query.id, saved);
    }

    private static void compareReady(Rules.Compare compare, ComparisonRunner comparator, Path directory, Path manualDirectory,
            Map<String, QueryRunner.Result> queries, Map<String, List<Path>> artifacts, Map<String, String> failures,
            List<ComparisonRunner.Result> comparisons, Options options) throws IOException {
        LOG.info("Starting ready comparison {}...", compare.id);
        try {
            var source = queries.get(compare.source); var target = queries.get(compare.target);
            if (source == null || target == null || source.partial() || target.partial())
                throw new ToolException("Comparison skipped: source " + compare.source + " or target " + compare.target
                    + " failed, was blocked, or is PARTIAL. Resolve those queries and rerun this comparison.");
            var result = comparator.run(compare, source, target, directory, options.export, options.sampleRows);
            comparisons.add(result); artifacts.put(compare.id, result.files());
            LOG.info("Compare {}: {}", compare.id, result.headline());
        } catch (Exception error) {
            LOG.error("Comparison {} did not complete; continuing", compare.id, error);
            String detail = failureReason(error);
            failures.put(compare.id, "Comparison " + compare.id + ": " + detail);
            Path note = manualDirectory.resolve(compare.id + ".md");
            Files.writeString(note, "# Comparison " + compare.id + " not completed\n\n" + detail
                + "\n\nAny files in the comparison directory are incomplete and must not be treated as parity results.\n");
            artifacts.put(compare.id, List.of(note));
        }
    }

    private void checkQuery(Rules.Sql query, Map<String, Object> parameters, boolean allowProd) {
        SqlGuard.validate(query.text);
        QueryRunner.validateParameters(query, parameters);
        var definition = settings.getDatasources().get(query.db);
        if (definition == null) throw new ToolException("Unknown datasource: " + query.db);
        if (!definition.isReadOnly()) throw new ToolException("Datasource " + query.db + " must have read-only=true.");
        if (QueryRunner.production(query.db, definition) && !allowProd)
            throw new ToolException("Datasource " + query.db + " requires --allow-prod if production access is intended.");
        if (definition.getUrl() == null || !definition.getUrl().startsWith("jdbc:") || definition.getUrl().contains("${")
            || (definition.getUsername() != null && definition.getUsername().contains("${"))
            || (definition.getPassword() != null && definition.getPassword().contains("${")))
            throw new ToolException("Datasource " + query.db + " has missing connection settings/environment variables.");
    }

    private static String failureReason(Throwable error) {
        Throwable root = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (root.getCause() != null && seen.add(root)) root = root.getCause();
        String detail = SecretRedactor.clean(root.getClass().getSimpleName() + ": " + Objects.toString(root.getMessage(), "No message"));
        return detail.length() > 4000 ? detail.substring(0, 4000) + " (see log for full details)" : detail;
    }

    private static void writeManualActions(Path directory, Map<String, String> failures) throws IOException {
        StringBuilder report = new StringBuilder("# Manual actions\n\nSuccessful query CSVs are retained. Failed-query partial CSVs, if any, are under manual/ and must not be used as complete extracts.\n\n");
        if (failures.isEmpty()) report.append("No failed queries or skipped comparisons. No manual SQL execution required.\n");
        else for (var failure : failures.entrySet()) report.append("## ").append(failure.getKey())
            .append("\n\n").append(failure.getValue()).append("\n\n");
        report.append("\nSQL templates use named parameters; see PARAMETERS.csv for this run's values. Use the configured datasource in your database client. "
            + "After corrections, rerun the XML rule file. Final reports in the output folder include any ZIP failures discovered after earlier ZIPs were created.\n");
        Files.writeString(directory.resolve("MANUAL_ACTIONS.md"), SecretRedactor.clean(report.toString()));
    }

    private static void zip(Path directory, Path target, Set<Path> included) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".partial");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (Path file : included) {
                    zip.putNextEntry(new ZipEntry(directory.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zip); zip.closeEntry();
                }
            }
            Files.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
}
