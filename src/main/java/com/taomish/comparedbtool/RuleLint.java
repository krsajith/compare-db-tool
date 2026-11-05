package com.taomish.comparedbtool;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Advice about a rule that is valid but probably not what its author wants: results that will not be kept, no PASS/FAIL,
 * nothing bundled. Printed by --validate and --check; never stops a run.
 */
final class RuleLint {
    private RuleLint() {}

    private static final Pattern SELECT_STAR = Pattern.compile("(?i)\\bselect\\s+(distinct\\s+)?(\\w+\\.)?\\*\\s*(,|\\bfrom\\b)");

    static List<String> advice(Rules rules, ExportMode global) {
        List<String> out = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (Rules.Compare c : rules.comparisons) { consumed.add(c.source); consumed.add(c.target); }
        for (Rules.Funnel f : rules.funnels) for (Rules.Stage s : f.stages) { consumed.add(s.source); consumed.add(s.target); }
        for (Rules.Presence p : rules.presences) consumed.addAll(PresenceRunner.sources(p));
        for (Rules.Sql q : rules.queries) {
            try { for (String target : Chaining.uses(q).values()) consumed.add(Chaining.target(target).id()); } catch (RuntimeException ignored) { }
        }
        for (Rules.Sql q : rules.queries) {
            ExportMode mode;
            try { mode = ExportMode.resolve(q.export, global); } catch (ToolException e) { continue; }
            if (!consumed.contains(q.id) && mode != ExportMode.FULL)
                out.add("query " + q.id + " is not compared or reused and its export is " + mode.name().toLowerCase(Locale.ROOT)
                    + ": its rows will NOT be kept (only the .sql and .md files). Add export=\"full\" if you want to read them.");
            if (q.text != null && SELECT_STAR.matcher(q.text).find())
                out.add("query " + q.id + " uses SELECT *: list the columns you need.");
        }
        if (rules.expectations.isEmpty() && rules.checks.isEmpty())
            out.add("no <expect>: the run will not say PASS or FAIL. State what you expect.");
        if (rules.compressions.isEmpty() && !rules.queries.isEmpty())
            out.add("no <compress>: nothing is bundled into a ZIP. Add one if the result is to be sent or attached.");
        return out;
    }

    static void print(Rules rules, ExportMode global) {
        for (String line : advice(rules, global)) Progress.get().line("  advice: " + line);
    }
}
