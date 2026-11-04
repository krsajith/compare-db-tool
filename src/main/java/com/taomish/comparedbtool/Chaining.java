package com.taomish.comparedbtool;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

/**
 * Output of one query as input of another. A query declares {@code uses="alias=query-id, other"}; its SQL then refers to
 * {@code :alias.column} (exactly one row: a bound value) or {@code :alias.column[]} (all rows: a bound list for IN).
 * Values are always bound parameters, never pasted into the SQL. Long lists run in batches.
 */
final class Chaining {
    private Chaining() {}

    static final int HARD_CAP = 100_000;
    static final Pattern ALIAS = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** A list taken from another query's column; QueryRunner splits it into batches when it is longer than listBatch. */
    static final class ListValue extends ArrayList<Object> {
        final String source;
        ListValue(String source, Collection<?> values) { super(values); this.source = source; }
    }

    record Ref(String name, String alias, String column, boolean list) {}

    /** alias -> query id, in declaration order. */
    static Map<String, String> uses(Rules.Sql sql) {
        Map<String, String> map = new LinkedHashMap<>();
        if (sql.uses == null || sql.uses.isBlank()) return map;
        for (String part : sql.uses.split(",", -1)) {
            String p = part.strip();
            if (p.isEmpty()) throw new ToolException("Query " + sql.id + ": uses=\"" + sql.uses + "\" has an empty entry.");
            int eq = p.indexOf('=');
            String alias = eq < 0 ? p : p.substring(0, eq).strip(), target = eq < 0 ? p : p.substring(eq + 1).strip();
            if (!ALIAS.matcher(alias).matches())
                throw new ToolException("Query " + sql.id + ": '" + alias + "' cannot be used as an alias in uses= (letters, digits and _, starting with a letter; write alias=query-id when the id has a hyphen).");
            if (target.isEmpty() || map.put(alias, target) != null)
                throw new ToolException("Query " + sql.id + ": uses=\"" + sql.uses + "\" repeats alias '" + alias + "' or has no query id.");
        }
        return map;
    }

    /** Every :name in the SQL in order, as Spring reads them (so quoted text and :: casts are not mistaken for parameters). */
    static List<String> parameterNames(String sql) {
        List<String> names = new ArrayList<>();
        var recorder = new org.springframework.jdbc.core.namedparam.AbstractSqlParameterSource() {
            @Override public boolean hasValue(String name) { return true; }
            @Override public Object getValue(String name) { names.add(name); return null; }
        };
        NamedParameterUtils.buildValueArray(NamedParameterUtils.parseSqlStatement(sql), recorder, null);
        return names;
    }

    /** Dotted parameter names of the SQL (alias.column or alias.column[]), in order, without duplicates. */
    static List<Ref> references(Rules.Sql sql) {
        List<Ref> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : parameterNames(sql.text)) {
            if (!name.contains(".") && !name.endsWith("[]")) continue;
            if (!seen.add(name)) continue;
            boolean list = name.endsWith("[]");
            String base = list ? name.substring(0, name.length() - 2) : name;
            int dot = base.indexOf('.');
            if (dot <= 0 || dot == base.length() - 1 || !ALIAS.matcher(base.substring(0, dot)).matches()
                || !base.substring(dot + 1).matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new ToolException("Query " + sql.id + ": ':" + name + "' is not a valid reference to another query. Use :alias.column or :alias.column[] (the alias is declared in uses=).");
            refs.add(new Ref(name, base.substring(0, dot), base.substring(dot + 1), list));
        }
        return refs;
    }

    /** What a uses= entry points at: a query ({@code part} null), or a part of a comparison's result: {@code cmp:only-source}. */
    record Target(String id, String part) {}

    static Target target(String text) {
        int colon = text.indexOf(':');
        return colon < 0 ? new Target(text.strip(), null) : new Target(text.substring(0, colon).strip(), text.substring(colon + 1).strip());
    }

    static Set<String> needs(Rules.Sql sql, Set<String> queryIds) { return needs(sql, queryIds, Set.of()); }

    /**
     * Offline checks: aliases declared, targets exist, no self reference, no unused alias. Returns the nodes this query waits for:
     * query ids, and {@code cmp:<id>} for the comparisons whose unmatched rows it uses.
     */
    static Set<String> needs(Rules.Sql sql, Set<String> queryIds, Set<String> comparisonIds) {
        Map<String, String> uses = uses(sql);
        Set<String> needs = new LinkedHashSet<>();
        for (var e : uses.entrySet()) {
            Target t = target(e.getValue());
            if (t.part() == null) {
                if (!queryIds.contains(t.id()))
                    throw new ToolException("Query " + sql.id + ": uses=\"" + e.getKey() + "=" + e.getValue() + "\" names an unknown query. Available: " + new TreeSet<>(queryIds)
                        + (comparisonIds.isEmpty() ? "" : "; or use a comparison's rows as <comparison id>:only-source, :only-target or :different (comparisons: " + new TreeSet<>(comparisonIds) + ")"));
                if (t.id().equals(sql.id)) throw new ToolException("Query " + sql.id + " cannot use its own result.");
                needs.add(t.id());
            } else {
                if (!comparisonIds.contains(t.id()))
                    throw new ToolException("Query " + sql.id + ": uses=\"" + e.getKey() + "=" + e.getValue() + "\" names an unknown comparison. Available: " + new TreeSet<>(comparisonIds));
                if (!ComparisonRunner.PARTS.contains(t.part()))
                    throw new ToolException("Query " + sql.id + ": uses=\"" + e.getKey() + "=" + e.getValue() + "\": after the comparison id write :only-source, :only-target or :different.");
                needs.add("cmp:" + t.id());
            }
        }
        Set<String> referenced = new HashSet<>();
        for (Ref ref : references(sql)) {
            if (!uses.containsKey(ref.alias()))
                throw new ToolException("Query " + sql.id + ": ':" + ref.name() + "' refers to alias '" + ref.alias() + "' which is not declared. Add uses=\"" + ref.alias() + "=<query id>\" to this <sql>.");
            referenced.add(ref.alias());
        }
        for (String alias : uses.keySet()) if (!referenced.contains(alias))
            throw new ToolException("Query " + sql.id + ": alias '" + alias + "' is declared in uses= but never referenced as :" + alias + ".column in the SQL.");
        return needs;
    }

    /** Placeholders so offline parameter checks accept the dotted names. */
    static Map<String, Object> placeholders(Rules.Sql sql) {
        Map<String, Object> map = new HashMap<>();
        for (Ref ref : references(sql)) map.put(ref.name(), ref.list() ? new ArrayList<>(Collections.singletonList(null)) : null);
        return map;
    }

    /** Cycle check over query ids; returns an offending chain or null. */
    static String cycle(Map<String, Set<String>> needs) {
        Map<String, Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : needs.keySet()) {
            String found = visit(id, needs, state, path);
            if (found != null) return found;
        }
        return null;
    }

    private static String visit(String id, Map<String, Set<String>> needs, Map<String, Integer> state, Deque<String> path) {
        Integer s = state.get(id);
        if (s != null && s == 2) return null;
        path.addLast(id);
        if (s != null && s == 1) return String.join(" -> ", path);
        state.put(id, 1);
        for (String next : needs.getOrDefault(id, Set.of())) {
            String found = visit(next, needs, state, path);
            if (found != null) return found;
        }
        state.put(id, 2);
        path.removeLast();
        return null;
    }

    // ------------------------------------------------------------------ binding at run time

    /** The bound values for every reference of {@code sql}, read from the finished dependency results. */
    static Map<String, Object> bind(Rules.Sql sql, Map<String, QueryRunner.Result> results) throws IOException {
        Map<String, String> uses = uses(sql);
        Map<String, Object> bound = new LinkedHashMap<>();
        String stripped = strip(sql.text);
        for (Ref ref : references(sql)) {
            String queryId = uses.get(ref.alias());
            QueryRunner.Result r = results.get(queryId);
            if (r == null || r.csv() == null || !java.nio.file.Files.exists(r.csv()))
                throw new ToolException("Query " + sql.id + ": the rows of query " + queryId + " are not available (it is an engine=db input, or did not run).");
            int index = -1;
            for (int i = 0; i < r.columns().size(); i++) if (r.columns().get(i).name().equalsIgnoreCase(ref.column())) index = i;
            if (index < 0)
                throw new ToolException("Query " + sql.id + ": query " + queryId + " has no column '" + ref.column() + "' for :" + ref.name() + ". Its columns: "
                    + r.columns().stream().map(QueryRunner.Column::name).toList() + ".");
            int type = r.columns().get(index).jdbcType();
            if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB)
                throw new ToolException("Query " + sql.id + ": binary column " + ref.column() + " of " + queryId + " cannot be used as an input.");
            List<Object> values = new ArrayList<>();
            long rows = 0;
            try (var parser = CsvFiles.reader(r.csv(), true)) {
                for (var record : parser) {
                    rows++;
                    if (!ref.list() && rows > 1) break;
                    values.add(typed(record.get(index), type));
                    if (values.size() > HARD_CAP)
                        throw new ToolException("Query " + sql.id + ": :" + ref.name() + " would have more than " + Progress.count(HARD_CAP) + " values. Narrow query " + queryId + " or compare inside one database.");
                }
            }
            if (ref.list()) {
                Collection<Object> distinct = new LinkedHashSet<>(values);
                distinct.remove(null);
                if (distinct.isEmpty() && java.util.regex.Pattern.compile("(?i)\\bnot\\s+in\\s*\\(\\s*:" + Pattern.quote(ref.name())).matcher(stripped).find())
                    throw new ToolException("Query " + sql.id + ": query " + queryId + " returned no values, and NOT IN (:" + ref.name() + ") with an empty list cannot be run safely.");
                // an empty list becomes IN (NULL), which matches nothing, so the follow-up query correctly returns no rows
                List<Object> list = distinct.isEmpty() ? new ArrayList<>(Collections.singletonList(null)) : new ArrayList<>(distinct);
                bound.put(ref.name(), new ListValue(queryId + "." + ref.column() + (distinct.isEmpty() ? " (no values)" : ""), list));
            } else {
                if (rows != 1) throw new ToolException("Query " + sql.id + ": :" + ref.name() + " needs exactly one row from query " + queryId + ", which returned "
                    + (rows == 0 ? "none" : "more than one") + ". " + (rows == 0 ? "" : "Use :" + ref.alias() + "." + ref.column() + "[] for a list, or make that query return one row."));
                bound.put(ref.name(), values.getFirst());
            }
        }
        return bound;
    }

    /** One line saying what was taken from where, for the progress message and the query's metadata. */
    static String describe(Rules.Sql sql, Map<String, Object> bound, int listBatch) {
        List<String> parts = new ArrayList<>();
        for (var e : bound.entrySet()) {
            if (e.getValue() instanceof ListValue list) {
                long real = list.stream().filter(Objects::nonNull).count();
                parts.add(Progress.count(real) + " value" + (real == 1 ? "" : "s") + " from " + list.source
                    + (list.size() > listBatch ? " in " + ((list.size() + listBatch - 1) / listBatch) + " batches of " + Progress.count(listBatch) : ""));
            } else parts.add(e.getKey() + " = " + e.getValue());
        }
        return parts.isEmpty() ? "" : "using " + String.join("; ", parts);
    }

    // ------------------------------------------------------------------ batching

    /** The parameter maps to execute: one, or one per slice of the single list that is longer than the batch size. */
    static List<Map<String, Object>> executions(Rules.Sql sql, Map<String, Object> parameters) {
        int batch = Math.max(1, sql.listBatch);
        String longName = null;
        for (var e : parameters.entrySet()) if (e.getValue() instanceof ListValue list && list.size() > batch) {
            if (longName != null) throw new ToolException("Query " + sql.id + ": two lists (:" + longName + " and :" + e.getKey() + ") are longer than listBatch=" + batch
                + "; they cannot be batched together. Narrow one of them.");
            longName = e.getKey();
        }
        if (longName == null) return List.of(parameters);
        if (!batchable(sql.text)) throw new ToolException("Query " + sql.id + ": :" + longName + " has " + Progress.count(((List<?>) parameters.get(longName)).size())
            + " values, more than listBatch=" + batch + ", and the query cannot be split into batches safely because it uses GROUP BY, DISTINCT, an aggregate, LIMIT/OFFSET or a set operation. "
            + "Raise listBatch (the database limit is about 30,000 bound values), or select the rows and aggregate in the tool.");
        List<Object> all = (List<Object>) parameters.get(longName);
        List<Map<String, Object>> executions = new ArrayList<>();
        for (int from = 0; from < all.size(); from += batch) {
            Map<String, Object> copy = new HashMap<>(parameters);
            copy.put(longName, new ArrayList<>(all.subList(from, Math.min(all.size(), from + batch))));
            executions.add(copy);
        }
        return executions;
    }

    private static final Pattern NOT_BATCHABLE = Pattern.compile(
        "\\b(GROUP\\s+BY|DISTINCT|HAVING|LIMIT|OFFSET|FETCH|UNION|INTERSECT|EXCEPT|MINUS|OVER\\s*\\(|COUNT\\s*\\(|SUM\\s*\\(|AVG\\s*\\(|MIN\\s*\\(|MAX\\s*\\(|ARRAY_AGG|STRING_AGG|GROUP_CONCAT|LISTAGG)");

    /** A row-wise filter can be run slice by slice; anything that combines rows cannot. */
    static boolean batchable(String sql) { return !NOT_BATCHABLE.matcher(strip(sql).toUpperCase(Locale.ROOT)).find(); }

    /** The SQL without string literals, quoted identifiers and comments. */
    static String strip(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ").replaceAll("'(?:[^']|'')*'", "''").replaceAll("\"(?:[^\"]|\"\")*\"", "\"\"");
    }

    // ------------------------------------------------------------------ values from CSV text

    /** The JDBC type decides how the exported text becomes a bound value; anything unrecognised stays text. */
    static Object typed(String text, int jdbcType) {
        if (text == null) return null;
        String t = text.strip();
        try {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                    try { yield Long.valueOf(t); } catch (NumberFormatException e) { yield new BigDecimal(t); }
                }
                case Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> new BigDecimal(t);
                case Types.BOOLEAN, Types.BIT -> t.equalsIgnoreCase("t") || t.equalsIgnoreCase("true") || t.equals("1");
                case Types.DATE -> Date.valueOf(t.length() > 10 ? t.substring(0, 10) : t);
                case Types.TIMESTAMP -> Timestamp.valueOf(t);
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(t.replace(' ', 'T'));
                default -> text;
            };
        } catch (RuntimeException e) {
            return text;
        }
    }
}
