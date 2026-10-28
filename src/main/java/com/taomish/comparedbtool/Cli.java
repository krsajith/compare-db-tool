package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.csv.CSVPrinter;

public final class Cli {
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
        --allow-prod          Permit datasources marked prod=true or named with 'prod'
        --help                Show this help

        External configuration: config/datasources.properties, relative to working directory.
        Override with --spring.config.additional-location=file:/path/to/datasources.properties
        Exit codes: 0 equal/export complete/validated; 1 differences; 2 error or PARTIAL.
        """;
    public static final class Options {
        public Path rules;
        public Path out = Path.of("out");
        public Map<String, String> parameters = new LinkedHashMap<>();
        public int partitions = 64;
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
            if (argument.startsWith("--spring.")) {
                if (!argument.contains("=")) throw new ToolException("Spring options must use --spring.name=value syntax.");
                continue;
            }
            int equal = argument.indexOf('=');
            String name = equal > 0 ? argument.substring(0, equal) : argument;
            if (Set.of("--rules", "--out", "--param", "--limit", "--partitions").contains(name)) {
                String value;
                if (equal > 0) value = argument.substring(equal + 1);
                else {
                    if (++i >= args.length) throw new ToolException("Missing value for " + name);
                    value = args[i];
                }
                try {
                    switch (name) {
                        case "--rules" -> options.rules = Path.of(value);
                        case "--out" -> options.out = Path.of(value);
                        case "--limit" -> options.limit = Long.parseLong(value);
                        case "--partitions" -> options.partitions = Integer.parseInt(value);
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
                case "--datasources" -> options.datasources = true;
                case "--allow-prod" -> options.allowProd = true;
                case "--help", "-h" -> options.help = true;
                default -> {
                    if (argument.startsWith("-") || options.rules != null) throw new ToolException("Unknown argument: " + argument);
                    options.rules = Path.of(argument);
                }
            }
        }
        if (options.limit < 0 || options.partitions < 1 || options.partitions > 256)
            throw new ToolException("limit must be nonnegative and partitions must be between 1 and 256.");
        if (!options.help && !options.datasources && options.rules == null) throw new ToolException("Supply --rules FILE.");
        return options;
    }

    public int execute(Options options) throws IOException {
        if (options.datasources) {
            if (settings.getDatasources().isEmpty()) System.out.println("No datasources configured. Check config/datasources.properties.");
            settings.getDatasources().forEach((name, definition) -> System.out.println(name
                + " [read-only=" + definition.isReadOnly() + ", prod=" + QueryRunner.production(name, definition) + "]"));
            return 0;
        }
        Rules rules = RuleLoader.load(options.rules);
        RuleLoader.validate(rules, settings);
        Map<String, Object> parameters = RuleLoader.parameters(rules, options.parameters);
        if (options.validate) {
            System.out.println("VALID: " + rules.queries.size() + " queries, " + rules.comparisons.size()
                + " comparisons, " + rules.compressions.size() + " ZIP packages. No connections opened.");
            return 0;
        }
        // Check every referenced datasource before opening any connection.
        for (Rules.Sql sql : rules.queries) {
            var definition = settings.getDatasources().get(sql.db);
            if (QueryRunner.production(sql.db, definition) && !options.allowProd)
                throw new ToolException("Datasource " + sql.db + " requires --allow-prod.");
            if (definition.getUrl() == null || !definition.getUrl().startsWith("jdbc:")
                || definition.getUrl().contains("${") || (definition.getUsername() != null && definition.getUsername().contains("${"))
                || (definition.getPassword() != null && definition.getPassword().contains("${")))
                throw new ToolException("Datasource " + sql.db + " has missing connection settings/environment variables.");
            QueryRunner.validateParameters(sql, parameters);
        }
        Path base = options.out.toAbsolutePath().normalize();
        Files.createDirectories(base);
        String name = Objects.toString(rules.name, "comparison").replaceAll("[^A-Za-z0-9_.-]", "_");
        if (name.length() > 60) name = name.substring(0, 60);
        Path directory = Files.createTempDirectory(base, name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-");
        System.out.println("Output: " + directory);
        Map<String, List<Path>> artifacts = new LinkedHashMap<>();
        Map<String, QueryRunner.Result> queries = new LinkedHashMap<>();
        List<ComparisonRunner.Result> comparisons = new ArrayList<>();
        try (QueryRunner runner = new QueryRunner(settings, options.allowProd)) {
            Files.copy(options.rules, directory.resolve("RULES.xml"));
            try (CSVPrinter params = CsvFiles.writer(directory.resolve("PARAMETERS.csv"), List.of("name", "value"))) {
                for (var entry : parameters.entrySet()) params.printRecord(Arrays.asList(entry.getKey(), entry.getValue()));
            }
            for (Rules.Sql query : rules.queries) {
                QueryRunner.Result result = runner.run(query, parameters, options.limit, directory);
                queries.put(query.id, result); artifacts.put(query.id, result.files());
                System.out.println("Query " + query.id + ": " + result.rows() + " rows, " + result.millis() + " ms"
                    + (result.partial() ? " [PARTIAL]" : ""));
            }
            ComparisonRunner comparator = new ComparisonRunner(options.partitions);
            for (Rules.Compare compare : rules.comparisons) {
                var result = comparator.run(compare, queries.get(compare.source), queries.get(compare.target), directory);
                comparisons.add(result); artifacts.put(compare.id, result.files());
                System.out.println("Compare " + compare.id + ": " + result.headline());
            }
            boolean partial = queries.values().stream().anyMatch(QueryRunner.Result::partial);
            boolean differs = comparisons.stream().anyMatch(ComparisonRunner.Result::differs);
            int exit = partial ? 2 : differs ? 1 : 0;
            StringBuilder summary = new StringBuilder("# " + rules.name + "\n\nStatus: "
                + (partial ? "PARTIAL" : differs ? "DIFFERENCES" : "COMPLETE / EQUAL") + "; exit code: " + exit
                + "\n\nQuery files are UTF-8 CSV with headers. Unquoted \\N means NULL. SQL templates and supplied parameters are included; datasource credentials are excluded.\n\n"
                + "| Query | Datasource | Rows | Time ms | Status |\n|---|---|---|---|---|\n");
            for (Rules.Sql query : rules.queries) {
                var r = queries.get(query.id);
                summary.append("| ").append(query.id).append(" | ").append(query.db).append(" | ").append(r.rows())
                    .append(" | ").append(r.millis()).append(" | ").append(r.partial() ? "PARTIAL" : "COMPLETE").append(" |\n");
            }
            summary.append("\n");
            for (var result : comparisons) summary.append("- ").append(result.id()).append(": ").append(result.headline()).append("\n");
            summary.append("\nQueries run sequentially in separate read-only transactions. Results are not a synchronized cross-database snapshot.\n");
            ComparisonRunner.writeSmallSummary(directory.resolve("SUMMARY.md"), summary.toString());
            for (Rules.Compress compression : rules.compressions) {
                LinkedHashSet<Path> included = new LinkedHashSet<>(List.of(directory.resolve("SUMMARY.md"),
                    directory.resolve("RULES.xml"), directory.resolve("PARAMETERS.csv")));
                for (Rules.Include include : compression.includes) included.addAll(artifacts.get(include.ref));
                zip(directory, directory.resolve(compression.file), included);
                System.out.println("ZIP: " + compression.file);
            }
            return exit;
        } catch (Exception e) {
            String message = e instanceof ToolException ? e.getMessage() : "File processing failed. Check paths, free disk space and permissions.";
            Files.writeString(directory.resolve("ERROR.md"), "# INCOMPLETE RUN\n\n" + message
                + "\n\nDo not treat any exported or partially compared files as proof of equality.\n");
            throw new ToolException(message + " See " + directory.resolve("ERROR.md"));
        } finally { CsvFiles.deleteTree(directory.resolve(".work")); }
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
