package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
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
        --restore FILE        Restore table rows from reference tables using a JSON rule (preview by default);
                              step "expect" counts are checked, "verify" rules run after --apply
        --apply               Commit template replacement or data restore; otherwise roll back the preview
        --quiet               No progress messages on the console (the log files and outputs are unchanged)
        --menu                Interactive menu (also the default when started without arguments in a terminal)
        --help                Show this help

        External configuration: config/datasources.properties, relative to working directory.
        Logging settings: config/logging.properties; logs/compare-db-tool.log and compare-db-tool-error.log.
        Override with --spring.config.additional-location=file:/path/to/datasources.properties
        One-run logging override: --logging.level.com.taomish.comparedbtool=DEBUG
        Exit codes: 0 equal/export complete/validated/all <expect> met; 1 differences or an <expect> not met; 2 error or PARTIAL.
        """;
    public static final class Options {
        public Path rules;
        public Path templateUpload;
        public Path templateExport;
        public Path restore;
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
        /** Interactive menu; menuRequested = asked for with --menu (plain prompts are allowed without a terminal). */
        public boolean menu, menuRequested, quiet;
    }
    private final DatasourceSettings settings;
    public Cli(DatasourceSettings settings) { this.settings = settings; }

    public static Options parse(String[] args) {
        Options options = new Options();
        if (args.length == 0) { options.menu = true; return options; }
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.startsWith("--spring.") || argument.startsWith("--logging.")) {
                if (!argument.contains("=")) throw new ToolException("Spring/logging options must use --name=value syntax.");
                continue;
            }
            int equal = argument.indexOf('=');
            String name = equal > 0 ? argument.substring(0, equal) : argument;
            if (Set.of("--template-export", "--template-import", "--template-upload", "--restore", "--rules", "--out", "--param", "--limit", "--partitions", "--export", "--sample-rows", "--parallel", "--max-partition-mb").contains(name)) {
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
                        case "--restore" -> {
                            if (options.restore != null) throw new ToolException("Supply only one restore rule file");
                            options.restore = Path.of(value);
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
                case "--quiet" -> options.quiet = true;
                case "--menu" -> { options.menu = true; options.menuRequested = true; }
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
        if (options.restore != null && (options.rules != null || options.templateUpload != null || options.templateExport != null
            || options.datasources || !options.parameters.isEmpty()))
            throw new ToolException("Restore cannot be combined with comparison rules, templates, datasource listing or parameters.");
        if (options.apply && ((options.templateUpload == null && options.restore == null) || options.validate))
            throw new ToolException("--apply requires --template-upload or --restore and cannot be combined with --validate.");
        if (!options.help && !options.menu && !options.datasources && options.rules == null && options.templateUpload == null && options.templateExport == null
            && options.restore == null)
            throw new ToolException("Supply --rules FILE, --template-import FILE, --template-export FILE or --restore FILE.");
        return options;
    }

    /** Result of one rule run: exit code, the output folder (null for --validate) and the expectation verdicts. */
    public record Outcome(int exit, Path directory, Expectations.Report report) {}

    public int execute(Options options) throws IOException {
        Progress.get().quiet(options.quiet);
        if (options.menu) return Menu.launch(settings, options);
        if (options.templateExport != null) return new TemplateExporter(settings).execute(options);
        if (options.templateUpload != null) return new TemplateUploader(settings).execute(options);
        if (options.restore != null) {
            try { return new DataRestorer(settings).execute(options); }
            catch (ToolException e) { throw e; }
            catch (Exception e) { throw new ToolException("Restore failed", e); }
        }
        if (options.datasources) {
            if (settings.getDatasources().isEmpty()) System.out.println("No datasources configured. Check config/datasources.properties.");
            settings.diagnostics().forEach(System.out::println);
            return 0;
        }
        return executeRules(options).exit();
    }

    /** Runs one XML rule file. Also used by a restore rule's verify step. */
    public Outcome executeRules(Options options) throws IOException {
        LOG.info("Loading rules: {}", options.rules.toAbsolutePath().normalize());
        Rules rules = RuleLoader.load(options.rules);
        RuleLoader.validate(rules, settings, options.validate);
        Map<String, Object> parameters = RuleLoader.parameters(rules, options.parameters);
        RuleLoader.checks(rules, parameters);                                   // before anything connects, also with --validate
        if (options.validate) {
            LOG.info("VALID: {} queries, {} comparisons, {} ZIP packages. No connections opened.",
                rules.queries.size(), rules.comparisons.size(), rules.compressions.size());
            Progress.get().quiet(options.quiet);
            Progress.get().line("compare-db-tool: rules are valid - " + rules.queries.size() + " queries, " + rules.comparisons.size() + " comparisons, "
                + rules.funnels.size() + " funnels, " + rules.expectations.size() + " expectations, " + rules.compressions.size() + " ZIP. No connection was opened.");
            return new Outcome(0, null, new Expectations.Report(List.of(), Set.of()));
        }
        Path base = options.out.toAbsolutePath().normalize();
        Files.createDirectories(base);
        String name = Objects.toString(rules.name, "comparison").replaceAll("[^A-Za-z0-9_.-]", "_");
        if (name.length() > 60) name = name.substring(0, 60);
        Path directory = Files.createTempDirectory(base, name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-");
        LOG.info("Output: {}", directory);
        Progress.get().quiet(options.quiet);
        final long startedNanos = System.nanoTime();
        Progress.get().line("compare-db-tool: " + rules.name + " - " + rules.queries.size() + (rules.queries.size() == 1 ? " query" : " queries") + " on "
            + String.join(", ", rules.queries.stream().map(q -> q.db).distinct().toList())
            + (rules.comparisons.isEmpty() ? "" : ", " + rules.comparisons.size() + (rules.comparisons.size() == 1 ? " comparison" : " comparisons"))
            + (rules.funnels.isEmpty() ? "" : ", " + rules.funnels.size() + (rules.funnels.size() == 1 ? " funnel" : " funnels"))
            + "; export " + options.export.name().toLowerCase(Locale.ROOT) + (options.limit > 0 ? ", row limit " + Progress.count(options.limit) : ""));
        Progress.get().line("  output: " + directory);
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
            Set<String> dbOnly = new HashSet<>();
            for (var compare : rules.comparisons) if ("db".equals(compare.engine)) {
                dbOnly.add(compare.source); dbOnly.add(compare.target);
            }
            for (var compare : rules.comparisons) if (!"db".equals(compare.engine)) {
                dbOnly.remove(compare.source); dbOnly.remove(compare.target);
            }
            Map<String, Set<String>> needs = new HashMap<>();
            Set<String> queryIds = new HashSet<>(), comparisonIds = new HashSet<>();
            rules.queries.forEach(q -> queryIds.add(q.id));
            rules.comparisons.forEach(c -> comparisonIds.add(c.id));
            rules.queries.forEach(q -> needs.put(q.id, Chaining.needs(q, queryIds, comparisonIds)));
            // Comparisons whose unmatched rows a later query uses keep them complete; the scheduler learns when each comparison is done.
            Set<String> drilled = new HashSet<>();
            needs.values().forEach(n -> n.stream().filter(x -> x.startsWith("cmp:")).forEach(x -> drilled.add(x.substring(4))));
            Set<String> comparisonDone = ConcurrentHashMap.newKeySet();
            Set<String> skipped = ConcurrentHashMap.newKeySet();                    // ids skipped by a when= condition (queries, comparisons, funnels)
            Map<String, String> skippedReasons = new LinkedHashMap<>();
            Map<String, ComparisonRunner.Result> comparisonById = new ConcurrentHashMap<>();
            QueryScheduler.run(rules.queries.stream().filter(q -> !dbOnly.contains(q.id)).toList(), needs, comparisonDone, options.parallel,
                (group, finished) -> executeGroup(group, finished, comparisonById, rules, skipped, runner, parameters, directory, options),
                outcomes -> {
                    for (var outcome : outcomes) {
                        completedQueries.add(outcome.query().id);
                        if (outcome.error() instanceof QueryScheduler.Skipped) skipped.add(outcome.query().id);
                        recordQuery(outcome, directory, manualDirectory, queries, artifacts, failures, skippedReasons);
                    }
                    for (Rules.Compare compare : rules.comparisons) {
                        if ("db".equals(compare.engine)) continue;
                        if (attemptedComparisons.contains(compare.id) || !completedQueries.contains(compare.source)
                            || !completedQueries.contains(compare.target)) continue;
                        attemptedComparisons.add(compare.id);
                        Path retain = drilled.contains(compare.id) ? directory.resolve(".work/drill").resolve(compare.id) : null;
                        if (retain != null) Files.createDirectories(retain);
                        compareReady(compare, comparator, directory, manualDirectory, queries, artifacts, failures, comparisons, options, retain, skipped, skippedReasons);
                        comparisons.stream().filter(r -> r.id().equals(compare.id)).findFirst().ifPresent(r -> comparisonById.put(compare.id, r));
                        comparisonDone.add("cmp:" + compare.id);       // also when it failed: what waits for it is then blocked with a reason
                    }
                });
            // After query workers finish, database comparisons cannot overlap a datasource's scheduler lane.
            for (var compare : rules.comparisons) if ("db".equals(compare.engine)) {
                var source = rules.queries.stream().filter(q -> q.id.equals(compare.source)).findFirst().orElseThrow();
                var target = rules.queries.stream().filter(q -> q.id.equals(compare.target)).findFirst().orElseThrow();
                for (var query : List.of(source, target)) if (dbOnly.contains(query.id)) {
                    Path sql = directory.resolve(query.id + ".sql"), note = directory.resolve(query.id + ".md");
                    Files.writeString(sql, query.text);
                    Files.writeString(note, "# Query " + query.id + "\n\nInput to engine=db comparison; no standalone CSV is fetched. See comparison SUMMARY.md for execution status, counts and capped samples.\n");
                    artifacts.put(query.id, List.of(sql, note));
                }
                try {
                    if (options.limit > 0) throw new ToolException("engine=db cannot be combined with --limit; remove the row cap.");
                    checkQuery(source, parameters, options.allowProd); checkQuery(target, parameters, options.allowProd);
                    var outcome = new DbComparisonRunner().run(compare, source, target, runner, parameters, directory, options.export, options.sampleRows);
                    queries.putIfAbsent(source.id, outcome.source()); queries.putIfAbsent(target.id, outcome.target());
                    comparisons.add(outcome.comparison()); artifacts.put(compare.id, outcome.comparison().files());
                    LOG.info("Compare {} engine=db: {}; fetched aggregate/sample rows={}", compare.id, outcome.comparison().headline(), outcome.fetched());
                    Progress.get().line(Progress.compared(compare.id, compare.source, compare.target, outcome.comparison()));
                } catch (Exception error) {
                    LOG.error("Database comparison {} did not complete; continuing", compare.id, error);
                    String detail = failureReason(error); failures.put(compare.id, "Comparison " + compare.id + ": " + detail);
                    Progress.get().line("  FAILED compare " + compare.id + ": " + Menu.shorten(detail.replaceAll("\\s+", " "), 200));
                    Path note = manualDirectory.resolve(compare.id + ".md");
                    Files.writeString(note, "# Comparison " + compare.id + " not completed\n\n" + detail + "\n\nAny comparison files are incomplete.\n");
                    artifacts.put(compare.id, List.of(note));
                }
            }
            // Funnels read the finished stage queries, like comparisons do.
            List<FunnelRunner.Result> funnels = new ArrayList<>();
            for (Rules.Funnel funnel : rules.funnels) {
                var skippedStage = funnel.stages.stream().flatMap(st -> Stream.of(st.source, st.target)).filter(Objects::nonNull).filter(skipped::contains).findFirst();
                if (skippedStage.isPresent()) {
                    skipped.add(funnel.id);
                    skippedReasons.put(funnel.id, "its stage query " + skippedStage.get() + " was skipped");
                    artifacts.put(funnel.id, List.of());
                    Progress.get().line("  - skipped funnel " + funnel.id + ": its stage query " + skippedStage.get() + " was skipped");
                    continue;
                }
                LOG.info("Starting funnel {} ({} stages)...", funnel.id, funnel.stages.size());
                try {
                    var result = new FunnelRunner(options.partitions, options.maxPartitionMb).run(funnel, queries, directory, options.export, options.sampleRows);
                    funnels.add(result); artifacts.put(funnel.id, result.files());
                    LOG.info("Funnel {}: {}", funnel.id, result.headline());
                    Progress.get().line("  funnel " + funnel.id + ": " + result.headline());
                } catch (Exception error) {
                    LOG.error("Funnel {} did not complete; continuing", funnel.id, error);
                    String detail = failureReason(error);
                    Progress.get().line("  FAILED funnel " + funnel.id + ": " + Menu.shorten(detail.replaceAll("\\s+", " "), 200));
                    failures.put(funnel.id, "Funnel " + funnel.id + ": " + detail);
                    Path note = manualDirectory.resolve(funnel.id + ".md");
                    Files.writeString(note, "# Funnel " + funnel.id + " not completed\n\n" + detail + "\n\nAny files in the funnel directory are incomplete.\n");
                    artifacts.put(funnel.id, List.of(note));
                }
            }
            boolean partial = queries.values().stream().anyMatch(QueryRunner.Result::partial);
            // Query CSVs still exist here (they live under .work until the run ends), so expectations can read them.
            Expectations.Report report = Expectations.evaluate(rules, queries, comparisons, funnels, parameters, skipped);
            Expectations.write(directory, report);
            // A comparison the rule has stated expectations for is judged by them; the rest still count as differences.
            boolean differs = comparisons.stream().filter(c -> !report.judgedComparisons().contains(c.id()))
                .anyMatch(ComparisonRunner.Result::differs)
                || funnels.stream().filter(f -> !report.judgedComparisons().contains(f.id())).anyMatch(FunnelRunner.Result::differs);
            int exit = partial || !failures.isEmpty() ? 2 : differs || !report.passed() ? 1 : 0;
            if (report.any()) {
                LOG.info("Expectations: {}", report.headline());
                Progress.get().line("  expectations: " + report.headline());
                report.verdicts().stream().filter(v -> !v.pass()).limit(10).forEach(v -> Progress.get().line("    FAIL " + v.ref() + ": " + v.check()
                    + " expected " + v.expected() + ", got " + Menu.shorten(String.valueOf(v.actual()), 120) + (v.reason() == null || v.reason().isBlank() ? "" : " (" + v.reason() + ")")));
            }
            List<String> topFindings = writeFindings(directory, comparisons, funnels);
            StringBuilder summary = new StringBuilder("# " + rules.name + "\n\nStatus: "
                + (!failures.isEmpty() ? "INCOMPLETE / MANUAL ACTIONS REQUIRED" : partial ? "PARTIAL"
                    : !report.passed() ? "EXPECTATIONS NOT MET" : differs ? "DIFFERENCES"
                    : report.any() ? "COMPLETE / EXPECTATIONS MET" : "COMPLETE / EQUAL") + "; exit code: " + exit
                + (report.any() ? "\n\nVerdict: " + report.headline() + "\n\n" + Expectations.table(report) : "")
                + "\n\nTool: " + BuildInfo.line()
                + "\n\nExport default: " + options.export.name().toLowerCase(Locale.ROOT) + "; sample cap: " + options.sampleRows
                + ". Counts cover complete inputs; sampling never limits comparison accuracy. Query CSVs are exported only in full mode; inputs used only by engine=db stay inside the database.\n\n"
                + "| Query | Datasource | Rows | Time ms | Status |\n|---|---|---|---|---|\n");
            for (Rules.Sql query : rules.queries) {
                var r = queries.get(query.id);
                if (r == null) {
                    summary.append("| ").append(query.id).append(" | ").append(query.db).append(" | - | - | ").append(skipped.contains(query.id) ? "SKIPPED" : "FAILED / BLOCKED").append(" |\n");
                    continue;
                }
                summary.append("| ").append(query.id).append(" | ").append(query.db).append(" | ").append(r.rows())
                    .append(" | ").append(r.millis()).append(" | ").append(r.partial() ? "PARTIAL" : "COMPLETE").append(" |\n");
            }
            summary.append("\n");
            summary.append(Expectations.smallResults(rules, queries));
            if (!topFindings.isEmpty()) {
                summary.append("## Findings (see FINDINGS.md)\n\n");
                topFindings.forEach(l -> summary.append("- ").append(l).append("\n"));
                summary.append("\n");
            }
            if (!skippedReasons.isEmpty()) {
                summary.append("Skipped, not failures (a when= condition was false):\n\n");
                skippedReasons.forEach((id, why) -> summary.append("- ").append(id).append(": ").append(why).append("\n"));
                summary.append("\n");
            }
            if (!report.notEvaluated().isEmpty()) summary.append("Expectations not evaluated: ").append(String.join(", ", report.notEvaluated())).append("\n\n");
            summary.append("Manual actions: ").append(failures.size()).append(". See MANUAL_ACTIONS.md for failed queries and skipped comparisons.\n\n");
            for (var result : comparisons) summary.append("- ").append(result.id()).append(": ").append(result.headline()).append("\n");
            for (var result : funnels) summary.append("- funnel ").append(result.id()).append(": ").append(result.headline()).append("\n");
            summary.append("\nQueries run with bounded concurrency and one active query per datasource; identical SQL is reused. "
                + "Ready comparisons run while other queries continue. Results are not a synchronized cross-database snapshot.\n");
            ComparisonRunner.writeSmallSummary(directory.resolve("SUMMARY.md"), summary.toString());
            writeManualActions(directory, failures);
            for (Rules.Compress compression : rules.compressions) {
                LinkedHashSet<Path> included = new LinkedHashSet<>(List.of(directory.resolve("SUMMARY.md"),
                    directory.resolve("RULES.xml"), directory.resolve("PARAMETERS.csv"), directory.resolve("MANUAL_ACTIONS.md")));
                if (report.any()) included.add(directory.resolve("EXPECTATIONS.md"));
                if (Files.exists(directory.resolve("FINDINGS.md"))) included.add(directory.resolve("FINDINGS.md"));
                for (Rules.Sql query : rules.queries) if (failures.containsKey(query.id))
                    included.add(directory.resolve(query.id + ".sql"));
                for (Rules.Include include : compression.includes) included.addAll(artifacts.get(include.ref));
                LOG.info("Creating ZIP {} with {} files", compression.file, included.size());
                try {
                    zip(directory, directory.resolve(compression.file), included);
                LOG.info("ZIP: {} ({} bytes)", directory.resolve(compression.file), Files.size(directory.resolve(compression.file)));
                Progress.get().line("  zip: " + compression.file + " (" + Progress.size(Files.size(directory.resolve(compression.file))) + ")");
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
            if (!failures.isEmpty()) Progress.get().line("  " + failures.size() + (failures.size() == 1 ? " item needs" : " items need") + " attention: " + directory.resolve("MANUAL_ACTIONS.md"));
            Progress.get().line("compare-db-tool: done in " + Progress.duration((System.nanoTime() - startedNanos) / 1_000_000) + " - exit " + exit + " (" + Menu.exitText(exit) + ")"
                + (report.any() ? ", expectations " + (report.passed() ? "PASS" : "FAIL") : ""));
            Progress.get().line("  summary: " + directory.resolve("SUMMARY.md"));
            return new Outcome(exit, directory, report);
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

    /** One FINDINGS.md for the run, comparisons in rule order, and the few lines worth putting in SUMMARY.md. */
    private static List<String> writeFindings(Path directory, List<ComparisonRunner.Result> comparisons, List<FunnelRunner.Result> funnels) throws IOException {
        List<String> top = new ArrayList<>();
        StringBuilder md = new StringBuilder("# Findings\n\nComputed from the complete comparisons; counts are exact, examples are capped. "
            + "Most important first within each comparison. A finding says what the data shows, not what caused it. Details: <comparison>/FINDINGS.md.\n");
        boolean any = false;
        record Ranked(String comparison, Findings.Finding finding) {}
        List<Ranked> all = new ArrayList<>();
        for (var result : comparisons) {
            if (result.findings().isEmpty()) continue;
            any = true;
            md.append("\n## ").append(result.id()).append(": ").append(result.headline()).append("\n\n");
            int n = 1;
            for (var f : result.findings().stream().limit(6).toList())
                md.append(n++).append(". ").append(Findings.line(f, 700)).append("\n");
            result.findings().stream().limit(2).forEach(f -> all.add(new Ranked(result.id(), f)));
        }
        for (var funnel : funnels) {
            var finding = funnel.finding();
            if (finding == null) continue;
            any = true;
            md.append("\n## funnel ").append(funnel.id()).append("\n\n").append(Findings.line(finding, 900)).append("\n");
            if (!funnel.profile().isEmpty()) { md.append("\nWhat the keys have in common:\n\n"); funnel.profile().forEach(l -> md.append(l).append("\n")); }
            all.add(new Ranked("funnel " + funnel.id(), finding));
        }
        if (!any) return top;
        Files.writeString(directory.resolve("FINDINGS.md"), md.toString());
        all.stream().sorted(Comparator.comparingDouble((Ranked r) -> r.finding().score()).reversed()).limit(5)
            .forEach(r -> top.add("**" + r.comparison() + "**: " + Findings.line(r.finding(), 210)));
        return top;
    }

    private List<QueryScheduler.Outcome> executeGroup(QueryScheduler.Group group, Map<String, QueryScheduler.Outcome> finished,
            Map<String, ComparisonRunner.Result> comparisonById, Rules rules, Set<String> skipped, QueryRunner runner,
            Map<String, Object> baseParameters, Path directory, Options options) throws IOException {
        List<QueryScheduler.Outcome> outcomes = new ArrayList<>();
        Map<String, Object> parameters = baseParameters;
        String inputs = "";
        Rules.Sql probe = group.rules().getFirst();
        // Skipped upstream => skipped here. Then this query's own when= condition, read from what has finished.
        for (String need : group.needs()) {
            boolean upstreamSkipped = need.startsWith("cmp:") ? skipped.contains(need.substring(4)) : finished.get(need) != null && finished.get(need).error() instanceof QueryScheduler.Skipped;
            if (upstreamSkipped) {
                String why = "its input " + (need.startsWith("cmp:") ? "comparison " + need.substring(4) : "query " + need) + " was skipped";
                group.rules().forEach(q -> outcomes.add(new QueryScheduler.Outcome(q, null, new QueryScheduler.Skipped(why), false)));
                return outcomes;
            }
        }
        if (probe.when != null) {
            Map<String, QueryRunner.Result> available = new HashMap<>();
            finished.forEach((id, o) -> { if (o.result() != null) available.put(id, o.result()); });
            String where = "Query " + probe.id + " when=\"" + Menu.shorten(probe.when, 40) + "\"";
            try {
                Set<String> names = new TreeSet<>(baseParameters.keySet());
                names.addAll(Expressions.resultNames(rules));
                var expression = Expressions.parse(probe.when, where, names, true);
                var lookups = new Expressions.Lookups(rules, available, new ArrayList<>(comparisonById.values()), List.of(), skipped);
                boolean runIt = Expressions.bool(Expressions.evaluate(expression, Expressions.context(baseParameters, lookups, expression), where), where, probe.when);
                if (!runIt) {
                    String why = "condition not met: " + probe.when;
                    group.rules().forEach(q -> outcomes.add(new QueryScheduler.Outcome(q, null, new QueryScheduler.Skipped(why), false)));
                    return outcomes;
                }
            } catch (ToolException error) {
                group.rules().forEach(q -> outcomes.add(new QueryScheduler.Outcome(q, null, error, false)));
                return outcomes;
            }
        }
        // Inputs from earlier queries: only when every query this one uses finished completely.
        if (!Chaining.uses(probe).isEmpty()) {
            Map<String, QueryRunner.Result> results = new HashMap<>();
            for (String target : Chaining.uses(probe).values()) {
                Chaining.Target t = Chaining.target(target);
                String why = null;
                if (t.part() == null) {
                    var done = finished.get(t.id());
                    if (done == null || done.error() != null || done.result() == null || done.result().partial())
                        why = "Not run: it uses the result of query " + t.id() + ", which failed, was blocked or is PARTIAL.";
                    else results.put(target, done.result());
                } else {
                    var comparison = comparisonById.get(t.id());
                    if (comparison == null || comparison.retained() == null)
                        why = "Not run: it uses the " + t.part() + " rows of comparison " + t.id() + ", which did not complete.";
                    else results.put(target, comparison.retained().table(t.part()));
                }
                if (why != null) {
                    final String reason = why;
                    group.rules().forEach(q -> outcomes.add(new QueryScheduler.Outcome(q, null, new ToolException(reason), false)));
                    return outcomes;
                }
            }
            try {
                Map<String, Object> bound = Chaining.bind(probe, results);
                parameters = new HashMap<>(baseParameters);
                parameters.putAll(bound);
                inputs = Chaining.describe(probe, bound, group.rules().stream().mapToInt(q -> q.listBatch).min().orElse(1000));
                if (!inputs.isEmpty()) LOG.info("Query {} {}", probe.id, inputs);
            } catch (Exception error) {
                group.rules().forEach(q -> outcomes.add(new QueryScheduler.Outcome(q, null, error, false)));
                return outcomes;
            }
        }
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
            try { outcomes.add(new QueryScheduler.Outcome(query, runner.run(query, parameters, options.limit, directory, options.export, inputs), null, true)); }
            catch (Exception error) { outcomes.add(new QueryScheduler.Outcome(query, null, error, true)); }
            return outcomes;
        }
        Rules.Sql first = eligible.getFirst(), canonical = new Rules.Sql();
        canonical.id = first.id; canonical.db = first.db; canonical.text = first.text; canonical.export = "none";
        canonical.tempTables = first.tempTables;
        canonical.timeout = eligible.stream().mapToInt(q -> q.timeout).min().orElseThrow();
        canonical.fetchSize = eligible.stream().mapToInt(q -> q.fetchSize).min().orElseThrow();
        canonical.listBatch = eligible.stream().mapToInt(q -> q.listBatch).min().orElseThrow();
        List<Long> limits = eligible.stream().map(q -> QueryRunner.effectiveLimit(q, options.limit)).toList();
        canonical.maxRows = limits.contains(0L) ? 0 : limits.stream().mapToLong(Long::longValue).max().orElseThrow();
        Path sharedDirectory = directory.resolve(".work/reuse").resolve(first.id);
        Files.createDirectories(sharedDirectory);
        LOG.info("Executing identical SQL once on {} for ids={}; strictest timeout={} s", first.db,
            eligible.stream().map(q -> q.id).toList(), canonical.timeout);
        QueryRunner.Result shared;
        try { shared = runner.run(canonical, parameters, 0, sharedDirectory, ExportMode.NONE, inputs); }
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
            Map<String, QueryRunner.Result> queries, Map<String, List<Path>> artifacts, Map<String, String> failures, Map<String, String> skippedReasons) throws IOException {
        Rules.Sql query = outcome.query();
        if (outcome.error() instanceof QueryScheduler.Skipped) {
            skippedReasons.put(query.id, outcome.error().getMessage());
            artifacts.put(query.id, List.of());
            Progress.get().line("  - skipped " + query.id + ": " + outcome.error().getMessage());
            return;
        }
        if (outcome.error() == null) {
            QueryRunner.Result result = outcome.result();
            Progress.get().line(Progress.queryFound(query.id, query.db, result.rows(), result.millis(), result.partial()));
            queries.put(query.id, result); artifacts.put(query.id, result.files());
            if (result.partial()) failures.put(query.id, "PARTIAL query on " + query.db
                + ". Rerun without the row cap; do not compare this incomplete extract.");
            return;
        }
        LOG.error("Query {} on {} failed; continuing with other queries", query.id, query.db, outcome.error());
        Progress.get().line(Progress.queryFailed(query.id, query.db, failureReason(outcome.error())));
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
            List<ComparisonRunner.Result> comparisons, Options options, Path retain, Set<String> skipped, Map<String, String> skippedReasons) throws IOException {
        String skippedInput = skipped.contains(compare.source) ? compare.source : skipped.contains(compare.target) ? compare.target : null;
        if (skippedInput != null) {
            skipped.add(compare.id);
            skippedReasons.put(compare.id, "its input query " + skippedInput + " was skipped");
            artifacts.put(compare.id, List.of());
            Progress.get().line("  - skipped compare " + compare.id + ": its input query " + skippedInput + " was skipped");
            return;
        }
        LOG.info("Starting ready comparison {}...", compare.id);
        try {
            var source = queries.get(compare.source); var target = queries.get(compare.target);
            if (source == null || target == null || source.partial() || target.partial())
                throw new ToolException("Comparison skipped: source " + compare.source + " or target " + compare.target
                    + " failed, was blocked, or is PARTIAL. Resolve those queries and rerun this comparison.");
            var result = comparator.run(compare, source, target, directory, options.export, options.sampleRows, retain);
            comparisons.add(result); artifacts.put(compare.id, result.files());
            LOG.info("Compare {}: {}", compare.id, result.headline());
            Progress.get().line(Progress.compared(compare.id, compare.source, compare.target, result));
        } catch (Exception error) {
            LOG.error("Comparison {} did not complete; continuing", compare.id, error);
            String detail = failureReason(error);
            Progress.get().line("  FAILED compare " + compare.id + ": " + Menu.shorten(detail.replaceAll("\\s+", " "), 200));
            failures.put(compare.id, "Comparison " + compare.id + ": " + detail);
            Path note = manualDirectory.resolve(compare.id + ".md");
            Files.writeString(note, "# Comparison " + compare.id + " not completed\n\n" + detail
                + "\n\nAny files in the comparison directory are incomplete and must not be treated as parity results.\n");
            artifacts.put(compare.id, List.of(note));
        }
    }

    private void checkQuery(Rules.Sql query, Map<String, Object> parameters, boolean allowProd) {
        SqlGuard.validate(query.text);
        TempTablePolicy.validate(query, settings);
        QueryRunner.validateParameters(query, parameters);
        var definition = settings.getDatasources().get(query.db);
        if (definition == null) throw new ToolException("Unknown datasource: " + query.db);
        if (!query.tempTables && !definition.isReadOnly()) throw new ToolException("Datasource " + query.db + " must have read-only=true.");
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
