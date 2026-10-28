package com.taomish.comparedbtool;

import java.util.*;

/** Accumulates grouping counts while the comparison already has each row available. */
final class Breakdowns {
    private static final List<String> CATEGORIES = List.of("only-source", "only-target", "different");
    private final List<Table> tables = new ArrayList<>();
    private static final class Table {
        final Rules.Breakdown rule;
        final List<String> names;
        final int[] source, target;
        final Map<String, Map<List<String>, Long>> counts = new LinkedHashMap<>();
        final List<String> missing = new ArrayList<>();
        Table(Rules.Breakdown rule, List<QueryRunner.Column> left, List<QueryRunner.Column> right, List<Rules.Column> mappings) {
            this.rule = rule;
            names = Arrays.stream(rule.columns.split(",")).map(String::strip).toList();
            source = new int[names.size()]; target = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i), a = name, b = name;
                for (Rules.Column mapping : mappings) if (mapping.name.equalsIgnoreCase(name)) {
                    a = mapping.source == null ? name : mapping.source;
                    b = mapping.target == null ? name : mapping.target;
                }
                source[i] = index(left, a); target[i] = index(right, b);
                if (source[i] < 0) missing.add("source." + name);
                if (target[i] < 0) missing.add("target." + name);
            }
            CATEGORIES.forEach(category -> counts.put(category, new HashMap<>()));
        }
        void add(String category, List<String> row, boolean left) {
            List<String> values = new ArrayList<>();
            for (int index : left ? source : target) {
                String value = index < 0 ? "(missing column)" : row.get(index);
                values.add(value == null ? "(NULL)" : value.isEmpty() ? "(empty)" : value);
            }
            counts.get(category).merge(values, 1L, Long::sum);
        }
    }
    Breakdowns(Rules.Compare rule, QueryRunner.Result source, QueryRunner.Result target) {
        if (rule.breakdowns != null) for (Rules.Breakdown breakdown : rule.breakdowns)
            tables.add(new Table(breakdown, source.columns(), target.columns(), rule.columns));
    }
    void add(String category, List<String> row, boolean source) { tables.forEach(t -> t.add(category, row, source)); }
    void appendTo(StringBuilder markdown) {
        for (Table table : tables) {
            markdown.append("\n## Breakdown: ").append(escape(table.rule.columns)).append("\n\n");
            if (!table.missing.isEmpty()) markdown.append("Missing breakdown columns: ").append(table.missing)
                .append(". These are grouped as (missing column).\n\n");
            markdown.append("Different counts are matched rows with unaccepted changes, grouped by source values.\n\n");
            for (String category : CATEGORIES) {
                var counts = table.counts.get(category);
                if (counts.isEmpty()) continue;
                markdown.append("**").append(category).append("**\n\n| ")
                    .append(String.join(" | ", table.names.stream().map(Breakdowns::escape).toList())).append(" | Rows |\n|")
                    .append("---|".repeat(table.names.size() + 1)).append("\n");
                var sorted = counts.entrySet().stream().sorted(Map.Entry.<List<String>, Long>comparingByValue().reversed()
                    .thenComparing(e -> e.getKey().toString())).toList();
                long other = 0;
                for (int i = 0; i < sorted.size(); i++) {
                    var group = sorted.get(i);
                    if (i >= table.rule.top) { other += group.getValue(); continue; }
                    markdown.append("| ").append(String.join(" | ", group.getKey().stream().map(Breakdowns::escape).toList()))
                        .append(" | ").append(group.getValue()).append(" |\n");
                }
                if (other > 0) markdown.append("| other").append(" |".repeat(table.names.size() - 1))
                    .append(" | ").append(other).append(" |\n");
                markdown.append("\nTotal ").append(category).append(": ")
                    .append(counts.values().stream().mapToLong(Long::longValue).sum()).append(" rows.\n\n");
            }
        }
    }
    private static int index(List<QueryRunner.Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        return -1;
    }
    private static String escape(String value) { return value.replace("|", "\\|").replace("\r", " ").replace("\n", " "); }
}
