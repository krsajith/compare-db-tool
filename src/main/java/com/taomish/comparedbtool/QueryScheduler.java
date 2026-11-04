package com.taomish.comparedbtool;

import java.util.*;
import java.util.concurrent.*;

/**
 * Bounded query workers; one active group per datasource, callbacks on the coordinator. A query that uses another query's result
 * waits until that query has finished; identical SQL with identical inputs is still run once.
 */
final class QueryScheduler {
    record Group(List<Rules.Sql> rules, int order, Set<String> needs) {
        String datasource() { return rules.getFirst().db; }
    }
    record Outcome(Rules.Sql query, QueryRunner.Result result, Throwable error, boolean permitted) {}
    private record Key(String datasource, String text, boolean tempTables, String uses) {}
    private record Completed(Group group, List<Outcome> outcomes) {}
    @FunctionalInterface interface Work { List<Outcome> execute(Group group) throws Exception; }
    /** Work that needs the outcomes of the queries it depends on (by query id). */
    @FunctionalInterface interface DependentWork { List<Outcome> execute(Group group, Map<String, Outcome> finished) throws Exception; }
    @FunctionalInterface interface Consumer { void completed(List<Outcome> outcomes) throws Exception; }

    static void run(List<Rules.Sql> queries, int parallel, Work work, Consumer consumer) throws Exception {
        run(queries, Map.of(), parallel, (group, finished) -> work.execute(group), consumer);
    }

    static void run(List<Rules.Sql> queries, Map<String, Set<String>> needs, int parallel, DependentWork work, Consumer consumer) throws Exception {
        run(queries, needs, Set.of(), parallel, work, consumer);
    }

    /**
     * {@code external}: names of things that are not queries (such as {@code cmp:<id>}) which the consumer adds when they are done.
     * A group waiting for one is submitted as soon as the consumer has added it.
     */
    static void run(List<Rules.Sql> queries, Map<String, Set<String>> needs, Set<String> external, int parallel, DependentWork work, Consumer consumer) throws Exception {
        Map<Key, List<Rules.Sql>> equivalent = new LinkedHashMap<>();
        for (Rules.Sql query : queries)
            equivalent.computeIfAbsent(new Key(query.db, query.text, query.tempTables, Objects.toString(query.uses, "")), k -> new ArrayList<>()).add(query);
        Map<String, List<Group>> lanes = new LinkedHashMap<>();
        int order = 0;
        for (var group : equivalent.values()) {
            Set<String> needed = new LinkedHashSet<>();
            group.forEach(q -> needed.addAll(needs.getOrDefault(q.id, Set.of())));
            lanes.computeIfAbsent(group.getFirst().db, k -> new ArrayList<>()).add(new Group(List.copyOf(group), order++, Set.copyOf(needed)));
        }
        int threads = parallel == 0 ? lanes.size() : parallel;
        if (threads < 1) return;
        Set<String> active = new HashSet<>();
        Map<String, Outcome> finished = new ConcurrentHashMap<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            CompletionService<Completed> completions = new ExecutorCompletionService<>(executor);
            int inFlight = submit(lanes, active, finished, external, threads, completions, work);
            while (inFlight > 0) {
                Completed completion = completions.take().get();
                inFlight--; active.remove(completion.group.datasource());
                // Record before refilling: a query waiting for this one becomes runnable now.
                completion.outcomes.forEach(o -> finished.put(o.query().id, o));
                // Refill before comparing: query IO continues while the coordinator compares ready inputs.
                inFlight += submit(lanes, active, finished, external, threads - inFlight, completions, work);
                consumer.completed(completion.outcomes);
                // The consumer may have finished a comparison that a waiting query needs.
                inFlight += submit(lanes, active, finished, external, threads - inFlight, completions, work);
            }
        }
        // Whatever is still waiting depends on a query that never ran (a rule error that validation should have caught).
        List<Outcome> stuck = new ArrayList<>();
        lanes.values().forEach(groups -> groups.forEach(g -> g.rules().forEach(q ->
            stuck.add(new Outcome(q, null, new ToolException("Not run: it waits for " + g.needs() + ", which never finished."), false)))));
        if (!stuck.isEmpty()) consumer.completed(stuck);
    }

    private static int submit(Map<String, List<Group>> lanes, Set<String> active, Map<String, Outcome> finished, Set<String> external, int slots,
                              CompletionService<Completed> completions, DependentWork work) {
        int submitted = 0;
        while (submitted < slots) {
            Group selected = null;
            for (var lane : lanes.entrySet()) {
                if (active.contains(lane.getKey())) continue;
                for (Group candidate : lane.getValue()) {
                    if (!candidate.needs().stream().allMatch(n -> finished.containsKey(n) || external.contains(n))) continue;
                    if (selected == null || candidate.order < selected.order) selected = candidate;
                    break;                                  // the first runnable group of a lane is its earliest
                }
            }
            if (selected == null) break;
            Group group = selected;
            lanes.get(group.datasource()).remove(group);
            active.add(group.datasource());
            completions.submit(() -> {
                try { return new Completed(group, work.execute(group, finished)); }
                catch (Exception error) {
                    return new Completed(group, group.rules.stream().map(q -> new Outcome(q, null, error, false)).toList());
                }
            });
            submitted++;
        }
        return submitted;
    }
}
