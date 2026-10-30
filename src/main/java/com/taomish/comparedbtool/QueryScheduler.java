package com.taomish.comparedbtool;

import java.util.*;
import java.util.concurrent.*;

/** Bounded query workers; one active group per datasource, callbacks on the coordinator. */
final class QueryScheduler {
    record Group(List<Rules.Sql> rules, int order) {
        String datasource() { return rules.getFirst().db; }
    }
    record Outcome(Rules.Sql query, QueryRunner.Result result, Throwable error, boolean permitted) {}
    private record Key(String datasource, String text, boolean tempTables) {}
    private record Completed(Group group, List<Outcome> outcomes) {}
    @FunctionalInterface interface Work { List<Outcome> execute(Group group) throws Exception; }
    @FunctionalInterface interface Consumer { void completed(List<Outcome> outcomes) throws Exception; }

    static void run(List<Rules.Sql> queries, int parallel, Work work, Consumer consumer) throws Exception {
        Map<Key, List<Rules.Sql>> equivalent = new LinkedHashMap<>();
        for (Rules.Sql query : queries) equivalent.computeIfAbsent(new Key(query.db, query.text, query.tempTables), k -> new ArrayList<>()).add(query);
        Map<String, Deque<Group>> lanes = new LinkedHashMap<>();
        int order = 0;
        for (var group : equivalent.values()) lanes.computeIfAbsent(group.getFirst().db, k -> new ArrayDeque<>())
            .add(new Group(List.copyOf(group), order++));
        int threads = parallel == 0 ? lanes.size() : parallel;
        if (threads < 1) return;
        Set<String> active = new HashSet<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            CompletionService<Completed> completions = new ExecutorCompletionService<>(executor);
            int inFlight = submit(lanes, active, threads, completions, work);
            while (inFlight > 0) {
                Completed completion = completions.take().get();
                inFlight--; active.remove(completion.group.datasource());
                // Refill before comparing: query IO continues while the coordinator compares ready inputs.
                inFlight += submit(lanes, active, threads - inFlight, completions, work);
                consumer.completed(completion.outcomes);
            }
        }
    }
    private static int submit(Map<String, Deque<Group>> lanes, Set<String> active, int slots,
                              CompletionService<Completed> completions, Work work) {
        int submitted = 0;
        while (submitted < slots) {
            Deque<Group> selected = null;
            for (var lane : lanes.entrySet()) {
                if (lane.getValue().isEmpty() || active.contains(lane.getKey())) continue;
                if (selected == null || lane.getValue().getFirst().order < selected.getFirst().order) selected = lane.getValue();
            }
            if (selected == null) break;
            Group group = selected.removeFirst(); active.add(group.datasource());
            completions.submit(() -> {
                try { return new Completed(group, work.execute(group)); }
                catch (Exception error) {
                    return new Completed(group, group.rules.stream().map(q -> new Outcome(q, null, error, false)).toList());
                }
            });
            submitted++;
        }
        return submitted;
    }
}
