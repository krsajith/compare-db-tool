package com.taomish.comparedbtool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import com.taomish.comparedbtool.logging.SecretRedactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Interactive front end: pick a rule (recent, favourite, all), choose options, confirm what is risky, run, read the verdict.
 * It builds the same Options the command line builds and calls the same Cli, so a menu run and a typed command are identical.
 */
final class Menu {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DatasourceSettings settings;
    private final Ui ui;
    private final Path out, rulesDir;
    private final Cli cli;
    private final History history;

    Menu(DatasourceSettings settings, Ui ui, Path out, Path rulesDir) {
        this.settings = settings; this.ui = ui; this.out = out; this.rulesDir = rulesDir;
        this.cli = new Cli(settings);
        this.history = new History(out.resolve(".history.json"));
    }

    /** Terminal if there is one; plain numbered prompts when --menu is given without one; help text otherwise (old behaviour). */
    static int launch(DatasourceSettings settings, Cli.Options options) {
        JLineUi terminal = JLineUi.tryCreate();
        Ui ui;
        if (terminal != null) ui = terminal;
        else if (options.menuRequested) ui = new PlainUi(new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset())), System.out);
        else { System.out.println(Cli.HELP); return 0; }
        try { return new Menu(settings, ui, options.out.toAbsolutePath().normalize(), Path.of("rules")).run(); }
        finally { if (terminal != null) terminal.close(); }
    }

    // ------------------------------------------------------------------ main screen

    int run() {
        ui.print(BuildInfo.line());
        ui.print(datasourceLine());
        while (true) {
            String choice = ui.pick("compare-db-tool", mainItems(), "Type to filter, / for commands, Enter selects, Esc quits");
            if (choice == null || choice.equals("/quit")) return 0;
            try { handle(choice); }
            catch (ToolException e) { ui.print("ERROR: " + SecretRedactor.clean(e.getMessage())); }
        }
    }

    private void handle(String choice) {
        switch (choice) {
            case "/rules" -> rulesScreen("Compare rules", RuleCatalog.Kind.COMPARE);
            case "/restore" -> rulesScreen("Restore and template rules (these change data)", RuleCatalog.Kind.RESTORE,
                RuleCatalog.Kind.TEMPLATE_IMPORT, RuleCatalog.Kind.TEMPLATE_EXPORT);
            case "/open" -> {
                String text = ui.ask("Path of a rule file: ", "");
                if (text == null || text.isBlank()) return;
                RuleCatalog.Entry entry = RuleCatalog.describe(Path.of(text.strip()));
                if (entry == null) ui.print("Not a rule this tool can run: " + text);
                else ruleScreen(entry, null);
            }
            case "/history" -> historyScreen();
            case "/datasources" -> datasourcesScreen();
            case "/help" -> ui.print(HELP);
            default -> {
                if (choice.startsWith("rule:")) {
                    RuleCatalog.Entry entry = RuleCatalog.describe(Path.of(choice.substring(5)));
                    if (entry == null) ui.print("Rule file not found: " + choice.substring(5));
                    else ruleScreen(entry, null);
                } else if (choice.startsWith("run:")) rerun(history.runs().get(Integer.parseInt(choice.substring(4))));
            }
        }
    }

    private List<Ui.Item> mainItems() {
        List<Ui.Item> items = new ArrayList<>();
        List<History.Run> runs = history.runs();
        Instant now = Instant.now();
        Set<String> shown = new LinkedHashSet<>();
        for (History.Run r : history.recent(8)) {
            shown.add(r.rule);
            items.add(new Ui.Item("run:" + runs.indexOf(r), "again: " + name(r.rule) + " (" + label(r.mode) + ")", runDetail(r, now)));
        }
        for (String favorite : history.favorites())
            if (!shown.contains(favorite) && java.nio.file.Files.exists(Path.of(favorite))) {
                RuleCatalog.Entry e = RuleCatalog.describe(Path.of(favorite));
                if (e != null) items.add(new Ui.Item("rule:" + favorite, "* " + e.name(), e.description()));
            }
        items.add(new Ui.Item("/rules", "/rules", "all compare rules in rules\\"));
        items.add(new Ui.Item("/restore", "/restore", "restore and template rules (change data, preview first)"));
        items.add(new Ui.Item("/open", "/open", "a rule file by path"));
        items.add(new Ui.Item("/history", "/history", "the last 30 runs"));
        items.add(new Ui.Item("/datasources", "/datasources", "configured connections, production ones marked"));
        items.add(new Ui.Item("/help", "/help", "keys and what each screen does"));
        items.add(new Ui.Item("/quit", "/quit", "leave (Esc also works)"));
        return items;
    }

    private static String name(String rule) { return Path.of(rule).getFileName().toString().replaceFirst("\\.[^.]+$", ""); }
    private static String label(String mode) { return "APPLY".equals(mode) ? "preview of apply" : mode == null ? "run" : mode.toLowerCase().replace('_', ' '); }

    private static String runDetail(History.Run r, Instant now) {
        String params = r.params == null || r.params.isEmpty() ? "" : "  " + shorten(String.join(", ", r.params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()), 60);
        return ago(Instant.parse(r.started), now) + "  exit " + r.exit + "  " + shorten(r.verdict == null ? "" : r.verdict, 60) + params;
    }

    static String ago(Instant then, Instant now) {
        long minutes = Duration.between(then, now).toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        if (minutes < 60 * 48) return (minutes / 60) + " h ago";
        return (minutes / 1440) + " d ago";
    }

    static String shorten(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 3) + "..."; }

    private String datasourceLine() {
        List<String> normal = new ArrayList<>(), prod = new ArrayList<>();
        settings.getDatasources().forEach((n, d) -> (QueryRunner.production(n, d) ? prod : normal).add(n));
        if (normal.isEmpty() && prod.isEmpty()) return "Datasources: none configured (config/datasources.properties)";
        return "Datasources: " + String.join(", ", normal) + (prod.isEmpty() ? "" : (normal.isEmpty() ? "" : "  |  ") + "PRODUCTION: " + String.join(", ", prod));
    }

    private void datasourcesScreen() {
        if (settings.getDatasources().isEmpty()) { ui.print("No datasources configured. Check config/datasources.properties."); return; }
        settings.getDatasources().forEach((n, d) -> ui.print(String.format("  %-24s %s%s", n, QueryRunner.production(n, d) ? "PRODUCTION  " : "", d.isReadOnly() ? "read-only" : "writable")));
    }

    private static final String HELP = """
        Keys: Up/Down move, type to filter, Enter selects, Esc goes back (quits on the main screen), PgUp/PgDn, Ctrl-U clears the filter.
        Commands: type "/" to list them (/rules, /restore, /history, ...).
        A run is built as the same options the command line takes; the equivalent command is shown before anything runs.
        Production datasources and --apply need the name or APPLY typed. Changed parameter values are listed and confirmed.
        Recent runs, favourites and the last parameter values are kept in out\\.history.json (no passwords).""";

    // ------------------------------------------------------------------ lists

    private void rulesScreen(String title, RuleCatalog.Kind... kinds) {
        Set<RuleCatalog.Kind> wanted = Set.of(kinds);
        if (!java.nio.file.Files.isDirectory(rulesDir)) { ui.print("No rules folder at " + rulesDir.toAbsolutePath() + ". Use /open to give a path."); return; }
        List<RuleCatalog.Entry> entries = RuleCatalog.scan(rulesDir).stream().filter(e -> wanted.contains(e.kind())).toList();
        if (entries.isEmpty()) { ui.print("No matching rules in " + rulesDir + "."); return; }
        Set<String> favorites = history.favorites();
        List<Ui.Item> items = new ArrayList<>();
        for (RuleCatalog.Entry e : entries) if (favorites.contains(e.key())) items.add(item(e, true));
        for (RuleCatalog.Entry e : entries) if (!favorites.contains(e.key())) items.add(item(e, false));
        String choice = ui.pick(title + " (" + entries.size() + ")", items, "Type to filter by name or description, Enter selects, Esc goes back");
        if (choice != null) handle(choice);
    }

    private Ui.Item item(RuleCatalog.Entry e, boolean favorite) {
        String kind = e.kind() == RuleCatalog.Kind.COMPARE ? "" : "[" + e.kind().label + "] ";
        return new Ui.Item("rule:" + e.key(), (favorite ? "* " : "") + e.name(), kind + e.description());
    }

    private void historyScreen() {
        List<History.Run> runs = history.runs();
        if (runs.isEmpty()) { ui.print("No runs recorded yet."); return; }
        Instant now = Instant.now();
        List<Ui.Item> items = new ArrayList<>();
        for (int i = runs.size() - 1; i >= 0 && items.size() < 30; i--) {
            History.Run r = runs.get(i);
            items.add(new Ui.Item("run:" + i, name(r.rule) + " (" + label(r.mode) + ")", runDetail(r, now)));
        }
        String choice = ui.pick("Last runs", items, "Enter prepares the same run again (you confirm before it starts)");
        if (choice != null) handle(choice);
    }

    /** Never re-applies a write straight from history: an APPLY comes back as a preview. */
    private void rerun(History.Run r) {
        RuleCatalog.Entry entry = RuleCatalog.describe(Path.of(r.rule));
        if (entry == null) { ui.print("Rule file not found any more: " + r.rule); return; }
        RunPlan plan = new RunPlan(entry);
        try { plan.mode = RunPlan.Mode.valueOf(r.mode); } catch (Exception ignored) { /* default */ }
        if (plan.mode == RunPlan.Mode.APPLY) plan.mode = RunPlan.Mode.PREVIEW;
        if (r.params != null) plan.params.putAll(r.params);
        try { plan.export = ExportMode.parse(r.export); } catch (Exception ignored) { /* default */ }
        plan.limit = r.limit; plan.parallel = r.parallel;
        ruleScreen(entry, plan);
    }

    // ------------------------------------------------------------------ one rule

    private record Db(String name, Boolean prod) {}
    private record Param(String name, String type, String declared) {}
    private record Info(List<Param> params, List<Db> dbs, String facts, String error, Rules rules) {}

    private Db db(String name) {
        var def = settings.getDatasources().get(name);
        return new Db(name, def == null ? null : QueryRunner.production(name, def));
    }

    /** Loading a rule for its details logs at DEBUG; keep that out of the menu (a real run still logs everything). */
    private Info info(RuleCatalog.Entry entry) {
        var logger = org.slf4j.LoggerFactory.getLogger(RuleLoader.class);
        ch.qos.logback.classic.Level previous = null;
        if (logger instanceof ch.qos.logback.classic.Logger logback) { previous = logback.getLevel(); logback.setLevel(ch.qos.logback.classic.Level.WARN); }
        try { return loadInfo(entry); }
        finally { if (logger instanceof ch.qos.logback.classic.Logger logback) logback.setLevel(previous); }
    }

    private Info loadInfo(RuleCatalog.Entry entry) {
        try {
            if (entry.kind() == RuleCatalog.Kind.COMPARE) {
                Rules rules = RuleLoader.load(entry.path());
                List<Param> params = rules.parameters.stream().filter(p -> p.expr == null).map(p -> new Param(p.name, p.type, p.value)).toList();
                List<Db> dbs = rules.queries.stream().map(q -> q.db).distinct().map(this::db).toList();
                String facts = rules.queries.size() + " queries, " + rules.comparisons.size() + " comparisons, "
                    + (rules.expectations.isEmpty() ? "no expectations" : rules.expectations.size() + " expectations")
                    + (rules.funnels.isEmpty() ? "" : ", " + rules.funnels.size() + " funnels")
                    + (rules.presences.isEmpty() ? "" : ", " + rules.presences.size() + " presence matrices")
                    + ", " + rules.compressions.size() + " ZIP";
                return new Info(params, dbs, facts, null, rules);
            }
            JsonNode node = JSON.readTree(Files.readString(entry.path()));
            List<Db> dbs = node.has("db") ? List.of(db(node.get("db").asString())) : List.of();
            String facts = switch (entry.kind()) {
                case RESTORE -> node.path("steps").size() + " steps" + (node.has("verify") ? ", " + node.get("verify").size() + " verify rule(s) after apply" : ", no verify rule");
                case TEMPLATE_IMPORT -> "template import" + (node.has("mode") ? ", mode " + node.get("mode").asString() : "");
                default -> "template export";
            };
            return new Info(List.of(), dbs, facts, null, null);
        } catch (Exception e) {
            return new Info(List.of(), List.of(), "", e.getMessage(), null);
        }
    }

    private void ruleScreen(RuleCatalog.Entry entry, RunPlan preset) {
        Info info = info(entry);
        ui.print("");
        ui.print(entry.path() + "   [" + entry.kind().label + "]");
        if (!entry.description().isEmpty()) ui.print("  " + entry.description());
        if (info.error() != null) { ui.print("  This rule cannot be loaded: " + SecretRedactor.clean(info.error())); return; }
        ui.print("  " + info.facts());
        ui.print("  Datasources: " + (info.dbs().isEmpty() ? "none" : String.join(", ", info.dbs().stream().map(d ->
            d.name() + (d.prod() == null ? " (NOT CONFIGURED)" : d.prod() ? " (PRODUCTION)" : "")).toList())));
        RunPlan plan = preset;
        if (plan == null) {
            plan = new RunPlan(entry);
            Map<String, String> last = history.lastParams(entry.key());
            for (Param p : info.params()) plan.params.put(p.name(), last.getOrDefault(p.name(), p.declared()));
        } else {
            for (Param p : info.params()) plan.params.putIfAbsent(p.name(), p.declared());
            plan.params.keySet().removeIf(k -> info.params().stream().noneMatch(p -> p.name().equals(k)));
        }
        optionsScreen(info, plan);
    }

    private void optionsScreen(Info info, RunPlan plan) {
        RuleCatalog.Kind kind = plan.entry.kind();
        while (true) {
            List<Ui.Item> items = new ArrayList<>();
            items.add(new Ui.Item("go", goLabel(plan), ""));
            if (kind == RuleCatalog.Kind.COMPARE || kind == RuleCatalog.Kind.RESTORE)
                items.add(new Ui.Item("validate", plan.mode == RunPlan.Mode.VALIDATE ? "Mode: validate only" : "Mode: " + plan.modeLabel(),
                    plan.mode == RunPlan.Mode.VALIDATE ? "checks the rule, opens no connection (select to switch back)" : "select to validate only, without connecting"));
            if (!info.params().isEmpty()) items.add(new Ui.Item("params", "Parameters", paramSummary(info, plan)));
            if (kind == RuleCatalog.Kind.COMPARE) {
                items.add(new Ui.Item("export", "Export: " + plan.export.name().toLowerCase(), "none = counts only, sample = capped CSVs, full = everything"));
                items.add(new Ui.Item("limit", "Row limit: " + (plan.limit > 0 ? plan.limit : "none"), plan.limit > 0 ? "results become PARTIAL" : ""));
                items.add(new Ui.Item("parallel", "Parallel queries: " + (plan.parallel > 0 ? plan.parallel : "auto"), ""));
            }
            items.add(new Ui.Item("pin", history.isFavorite(plan.entry.key()) ? "Unpin from favourites" : "Pin to favourites", ""));
            items.add(new Ui.Item("/back", "/back", "to the main screen"));
            String choice = ui.pick(plan.entry.name() + ": " + plan.modeLabel(), items, "Enter selects, Esc goes back");
            if (choice == null || choice.equals("/back")) return;
            switch (choice) {
                case "go" -> { if (execute(info, plan)) return; }
                case "validate" -> plan.mode = plan.mode == RunPlan.Mode.VALIDATE ? RunPlan.defaultMode(kind) : RunPlan.Mode.VALIDATE;
                case "params" -> editParams(info, plan);
                case "export" -> {
                    String m = ui.pick("Export mode", List.of(new Ui.Item("none", "none", "counts and summaries only"),
                        new Ui.Item("sample", "sample", "at most " + plan.sampleRows + " records per CSV (default)"),
                        new Ui.Item("full", "full", "every row and every changed cell")), null);
                    if (m != null) plan.export = ExportMode.parse(m);
                }
                case "limit" -> {
                    String v = ui.ask("Row limit per query (0 = none): ", String.valueOf(plan.limit));
                    if (v != null) try { plan.limit = Math.max(0, Long.parseLong(v.strip())); } catch (NumberFormatException e) { ui.print("Not a number: " + v); }
                }
                case "parallel" -> {
                    String v = ui.ask("Parallel queries, 1 to 256 (0 = auto): ", String.valueOf(plan.parallel));
                    if (v != null) try {
                        int n = Integer.parseInt(v.strip());
                        if (n < 0 || n > 256) throw new NumberFormatException();
                        plan.parallel = n;
                    } catch (NumberFormatException e) { ui.print("Use a whole number from 0 to 256: " + v); }
                }
                case "pin" -> ui.print(history.toggleFavorite(plan.entry.key()) ? "Pinned." : "Unpinned.");
                default -> { }
            }
        }
    }

    private String goLabel(RunPlan plan) {
        return switch (plan.mode) {
            case RUN -> plan.entry.kind() == RuleCatalog.Kind.TEMPLATE_EXPORT ? "Export now" : "Run now";
            case VALIDATE -> "Validate now";
            case PREVIEW -> "Preview now (changes nothing)";
            case APPLY -> "APPLY now";
        };
    }

    private String paramSummary(Info info, RunPlan plan) {
        List<String> parts = new ArrayList<>();
        for (Param p : info.params()) parts.add(p.name() + "=" + plan.params.getOrDefault(p.name(), p.declared()));
        return shorten(String.join(", ", parts), 90) + (changed(info, plan).isEmpty() ? "" : "   (" + changed(info, plan).size() + " changed)");
    }

    private void editParams(Info info, RunPlan plan) {
        for (Param p : info.params()) {
            while (true) {
                String value = ui.ask(p.name() + " (" + p.type() + ", declared " + p.declared() + "): ", plan.params.getOrDefault(p.name(), p.declared()));
                if (value == null) return;
                String problem = problem(info.rules(), p.name(), value);
                if (problem == null) { plan.params.put(p.name(), value); break; }
                ui.print("  " + problem);
            }
        }
    }

    static String problem(Rules rules, String name, String value) {
        try { RuleLoader.parameters(rules, Map.of(name, value)); return null; }
        catch (ToolException e) { return e.getMessage(); }
    }

    private List<String> changed(Info info, RunPlan plan) {
        List<String> changes = new ArrayList<>();
        for (Param p : info.params()) {
            String now = plan.params.getOrDefault(p.name(), p.declared());
            if (!java.util.Objects.equals(now, p.declared())) changes.add(p.name() + ": " + p.declared() + " -> " + now);
        }
        return changes;
    }

    // ------------------------------------------------------------------ the gate, the run, the result

    /** Returns false when the user cancelled or the plan is blocked. Validation still needs the datasource names (it only skips connecting). */
    private boolean gate(Info info, RunPlan plan) {
        List<String> missing = info.dbs().stream().filter(d -> d.prod() == null).map(Db::name).toList();
        if (!missing.isEmpty()) {
            ui.print("Blocked: datasource " + String.join(", ", missing) + " is not in config/datasources.properties (see /datasources).");
            return false;
        }
        plan.allowProd = false;
        if (plan.mode == RunPlan.Mode.VALIDATE) return true;       // no connection is opened: nothing risky to confirm
        List<String> prod = info.dbs().stream().filter(d -> Boolean.TRUE.equals(d.prod())).map(Db::name).toList();
        if (!prod.isEmpty()) {
            String phrase = prod.size() == 1 ? prod.getFirst() : "PROD";
            if (!ui.typed("PRODUCTION datasource: " + String.join(", ", prod) + ". This will read production data (--allow-prod).", phrase)) return false;
            plan.allowProd = true;
        }
        if (plan.mode == RunPlan.Mode.APPLY && !ui.typed("APPLY commits the changes shown by the preview.", "APPLY")) return false;
        if (plan.limit > 0 && !ui.confirm("A row limit of " + plan.limit + " makes the results PARTIAL (exit 2). Run anyway?", false)) return false;
        List<String> changes = changed(info, plan);
        if (!changes.isEmpty()) {
            ui.print("Parameter values that differ from the rule's declared ones:");
            changes.forEach(c -> ui.print("  " + c));
            if (!ui.confirm("Run with these values?", true)) return false;
        }
        return true;
    }

    /** Returns true to leave the rule screen (after the user chose to go back from the result). */
    private boolean execute(Info info, RunPlan plan) {
        if (!gate(info, plan)) return false;
        while (true) {
            ui.print("");
            ui.print("Command:  " + plan.commandLine());
            Result result = runNow(plan);
            history.add(result.record);
            showResult(plan, result);
            List<Ui.Item> next = new ArrayList<>();
            if (plan.mode == RunPlan.Mode.PREVIEW && plan.entry.kind().writes() && result.exit == 0)
                next.add(new Ui.Item("apply", "Apply this change (commits)", "you will type APPLY to confirm"));
            if (plan.mode != RunPlan.Mode.APPLY) next.add(new Ui.Item("again", "Run again with the same settings", ""));
            next.add(new Ui.Item("change", "Change options", "back to the options screen"));
            if (result.dir != null) next.add(new Ui.Item("view", "Show the whole summary", result.dir.toString()));
            next.add(new Ui.Item("/back", "/back", "to the main screen"));
            String choice = ui.pick("Done: " + plan.entry.name() + " (" + plan.modeLabel() + "), exit " + result.exit, next, "Enter selects, Esc goes back");
            if (choice == null || choice.equals("/back")) return true;
            switch (choice) {
                case "again" -> { if (!gate(info, plan)) return false; }
                case "change" -> { return false; }
                case "view" -> { printFile(result.summary, 400); continue; }
                case "apply" -> {
                    plan.mode = RunPlan.Mode.APPLY;
                    if (!gate(info, plan)) { plan.mode = RunPlan.Mode.PREVIEW; return false; }
                }
                default -> { }
            }
        }
    }

    private record Result(int exit, Path dir, Path summary, String verdict, History.Run record) {}

    private Result runNow(RunPlan plan) {
        Instant start = Instant.now();
        Cli.Options options = plan.toOptions(out);
        int exit;
        Path dir = null;
        String verdict = null;
        try {
            if (plan.entry.kind() == RuleCatalog.Kind.COMPARE) {
                Cli.Outcome outcome = cli.executeRules(options);
                exit = outcome.exit(); dir = outcome.directory();
                if (outcome.report().any()) verdict = outcome.report().headline();
            } else exit = cli.execute(options);
        } catch (ToolException e) {
            exit = 2; verdict = "ERROR: " + SecretRedactor.clean(e.getMessage()); ui.print(verdict);
        } catch (Exception e) {
            exit = 2; verdict = "ERROR: " + SecretRedactor.clean(String.valueOf(e.getMessage())); ui.print(verdict);
        }
        if (dir == null && plan.mode != RunPlan.Mode.VALIDATE) dir = newestFolder(start);
        Path summary = dir == null ? null : firstExisting(dir, "SUMMARY.md", "RESTORE.md");
        if (verdict == null && summary != null) verdict = statusLine(summary);
        if (verdict == null) verdict = exit == 0 ? "OK" : exitText(exit);
        History.Run r = new History.Run();
        r.rule = plan.entry.key(); r.kind = plan.entry.kind().name(); r.mode = plan.mode.name();
        r.params = plan.entry.kind() == RuleCatalog.Kind.COMPARE ? new LinkedHashMap<>(plan.params) : new LinkedHashMap<>();
        r.export = plan.export.name(); r.limit = plan.limit; r.parallel = plan.parallel;
        r.started = start.toString(); r.exit = exit; r.verdict = verdict; r.dir = dir == null ? null : dir.toString();
        return new Result(exit, dir, summary, verdict, r);
    }

    static String exitText(int exit) {
        return switch (exit) {
            case 0 -> "success";
            case 1 -> "differences, or an expectation was not met";
            default -> "error or PARTIAL result";
        };
    }

    private void showResult(RunPlan plan, Result result) {
        ui.print("");
        ui.print("Result: exit " + result.exit + " (" + exitText(result.exit) + ")");
        if (result.verdict != null) ui.print("Verdict: " + result.verdict);
        if (result.dir != null) ui.print("Output: " + result.dir);
        if (result.summary != null) printFile(result.summary, 45);
    }

    private void printFile(Path file, int maxLines) {
        if (file == null) return;
        try (Stream<String> lines = Files.lines(file)) {
            List<String> all = lines.toList();
            all.stream().limit(maxLines).forEach(ui::print);
            if (all.size() > maxLines) ui.print("... " + (all.size() - maxLines) + " more lines in " + file);
        } catch (IOException e) { ui.print("Cannot read " + file); }
    }

    private Path newestFolder(Instant since) {
        if (!Files.isDirectory(out)) return null;
        try (Stream<Path> dirs = Files.list(out)) {
            return dirs.filter(Files::isDirectory).filter(d -> {
                try { return Files.getLastModifiedTime(d).toInstant().isAfter(since.minusSeconds(2)); } catch (IOException e) { return false; }
            }).max((a, b) -> {
                try { return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)); } catch (IOException e) { return 0; }
            }).orElse(null);
        } catch (IOException e) { return null; }
    }

    private static Path firstExisting(Path dir, String... names) {
        for (String n : names) if (Files.isRegularFile(dir.resolve(n))) return dir.resolve(n);
        return null;
    }

    private static String statusLine(Path summary) {
        try (Stream<String> lines = Files.lines(summary)) {
            return lines.filter(l -> l.startsWith("Status:")).findFirst().map(l -> l.substring(7).strip()).orElse(null);
        } catch (IOException e) { return null; }
    }
}
