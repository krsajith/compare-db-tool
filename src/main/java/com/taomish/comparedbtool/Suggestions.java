package com.taomish.comparedbtool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * "There is a mismatch: should I restore, and with which rule?" A rule declares {@code <suggest restore="file.json" when="...">}; when the
 * condition holds after the run, the tool says so and prints the exact command. It never restores by itself: the command is a preview that
 * changes nothing until it is run again with --apply.
 */
final class Suggestions {
    private Suggestions() {}

    record Suggestion(Path rule, String display, String name, String description, List<String> steps, String reason, String command) {}

    /** Offline: the restore rule exists, is valid and its condition parses. {@code rulesDirectory} null skips the file checks. */
    static void validate(Rules rules, Path rulesDirectory, DatasourceSettings settings, boolean checkDatasource) {
        List<String> names = new ArrayList<>(rules.parameters.stream().map(p -> p.name).toList());
        names.addAll(Expressions.resultNames(rules));
        int n = 0;
        for (Rules.Suggest s : rules.suggestions) {
            n++;
            String where = "<suggest> number " + n;
            if (s.restore == null || s.restore.isBlank()) throw new ToolException(where + " needs restore=\"restore-rule.json\".");
            if (s.when == null || s.when.isBlank()) throw new ToolException(where + " needs when=\"...\": the condition under which the restore is worth suggesting, for example #cmp.onlySource > 0.");
            Expressions.parse(s.when, where + " when=\"" + Menu.shorten(s.when, 40) + "\"", names, true);
            if (rulesDirectory == null) continue;
            Path file = rulesDirectory.resolve(s.restore);
            if (!Files.isRegularFile(file)) throw new ToolException(where + ": restore rule not found: " + file);
            DataRestorer.Rule restore = DataRestorer.load(file);
            if (checkDatasource && !settings.getDatasources().containsKey(restore.db))
                throw new ToolException(where + ": the restore rule " + s.restore + " uses datasource '" + restore.db + "', which is not configured.");
        }
    }

    /** The suggestions whose condition holds. A condition that cannot be evaluated is reported as a warning line instead of being dropped. */
    static List<Suggestion> evaluate(Rules rules, Path rulesDirectory, Map<String, Object> parameters, Map<String, QueryRunner.Result> queries,
                                     List<ComparisonRunner.Result> comparisons, List<FunnelRunner.Result> funnels, List<PresenceRunner.Result> presences,
                                     Set<String> skipped, List<String> warnings) {
        List<Suggestion> out = new ArrayList<>();
        Set<String> names = new TreeSet<>(parameters.keySet());
        names.addAll(Expressions.resultNames(rules));
        int n = 0;
        for (Rules.Suggest s : rules.suggestions) {
            n++;
            String where = "<suggest> number " + n;
            try {
                var expression = Expressions.parse(s.when, where, names, true);
                var lookups = new Expressions.Lookups(rules, queries, comparisons, funnels, skipped).withPresences(presences);
                if (!Expressions.bool(Expressions.evaluate(expression, Expressions.context(parameters, lookups, expression), where), where, s.when)) continue;
                Path file = rulesDirectory.resolve(s.restore);
                DataRestorer.Rule restore = DataRestorer.load(file);
                List<String> steps = restore.steps.stream().map(st -> st.id + ": " + st.table + " <- " + st.from).toList();
                String display = display(file);
                out.add(new Suggestion(file, display, restore.name, restore.description == null ? "" : RuleCatalog.tidy(restore.description), steps,
                    s.reason == null ? "" : s.reason, ".\\compare.ps1 --restore " + (display.contains(" ") ? "\"" + display + "\"" : display)));
            } catch (ToolException e) {
                warnings.add(where + " could not be decided: " + e.getMessage());
            }
        }
        return out;
    }

    private static String display(Path file) {
        try { return Path.of("").toAbsolutePath().relativize(file.toAbsolutePath()).toString(); }
        catch (IllegalArgumentException e) { return file.toString(); }
    }

    static String markdown(List<Suggestion> suggestions) {
        StringBuilder md = new StringBuilder("## Suggested next step\n\n");
        for (Suggestion s : suggestions) {
            md.append("**Restore with `").append(s.name()).append("`** (preview first)").append(s.reason().isEmpty() ? "" : ": " + s.reason()).append("\n\n");
            if (!s.description().isEmpty()) md.append(s.description()).append("\n\n");
            md.append("Steps: ").append(String.join("; ", s.steps())).append("\n\n");
            md.append("Run `").append(s.command()).append("` for a preview that changes nothing. Read its RESTORE.md, then add `--apply` to commit. ")
                .append("A restore copies rows from a table in the same database (a backup or migration copy); it does not read from another database.\n\n");
        }
        return md.toString();
    }
}
