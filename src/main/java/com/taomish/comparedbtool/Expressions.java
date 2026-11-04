package com.taomish.comparedbtool;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Restricted SpEL for rules: computed parameters, checks, conditions and expectations that relate results to each other.
 * What an expression can do is deliberately small. It runs in Spring's read-only data-binding context (no type references, no constructors, no
 * bean references, no instance method calls, no assignment), the parser rejects regex matching and instanceof, every function is one of the static
 * functions below, and every variable must be a declared parameter. An expression cannot reach the file system, the network or the JVM.
 */
final class Expressions {
    private Expressions() {}

    static final int MAX_LENGTH = 500;
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final Set<String> FORBIDDEN = Set.of("TypeReference", "ConstructorReference", "BeanReference", "MethodReference", "Assign",
        "OperatorInstanceof", "OperatorMatches", "OpInc", "OpDec");
    static final Set<String> RESULT_FUNCTIONS = Set.of("q", "c", "f", "rows", "values");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Map<String, Method> FUNCTIONS = new TreeMap<>();
    static {
        for (Method m : Functions.class.getDeclaredMethods()) if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && java.lang.reflect.Modifier.isStatic(m.getModifiers()))
            if (FUNCTIONS.put(m.getName(), m) != null) throw new IllegalStateException("overloaded expression function " + m.getName());
    }

    /** The only things an expression may call. Dates are java.time.LocalDate; business days are Monday to Friday (no holiday calendar). */
    public static final class Functions {
        private Functions() {}
        public static LocalDate plusDays(LocalDate d, int n) { return d.plusDays(n); }
        public static LocalDate minusDays(LocalDate d, int n) { return d.minusDays(n); }
        public static LocalDate plusMonths(LocalDate d, int n) { return d.plusMonths(n); }
        public static LocalDate minusMonths(LocalDate d, int n) { return d.minusMonths(n); }
        public static LocalDate startOfMonth(LocalDate d) { return d.with(TemporalAdjusters.firstDayOfMonth()); }
        public static LocalDate endOfMonth(LocalDate d) { return d.with(TemporalAdjusters.lastDayOfMonth()); }
        public static LocalDate nextBusinessDay(LocalDate d) { return addBusinessDays(d, 1); }
        public static LocalDate prevBusinessDay(LocalDate d) { return addBusinessDays(d, -1); }
        public static LocalDate addBusinessDays(LocalDate d, int n) {
            int step = n < 0 ? -1 : 1;
            for (int left = Math.abs(n); left > 0; ) {
                d = d.plusDays(step);
                if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) left--;
            }
            return d;
        }
        public static boolean isBusinessDay(LocalDate d) { return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY; }
        public static long daysBetween(LocalDate from, LocalDate to) { return ChronoUnit.DAYS.between(from, to); }
        public static int dayOfWeek(LocalDate d) { return d.getDayOfWeek().getValue(); }
        public static LocalDate date(String text) {
            try { return LocalDate.parse(text.strip()); }
            catch (RuntimeException e) { throw new IllegalArgumentException("date('" + text + "') is not a date; write it as yyyy-MM-dd"); }
        }
        public static LocalDate today() { return LocalDate.now(); }
        public static BigDecimal abs(BigDecimal n) { return n.abs(); }
        public static BigDecimal min(BigDecimal a, BigDecimal b) { return a.min(b); }
        public static BigDecimal max(BigDecimal a, BigDecimal b) { return a.max(b); }
        public static BigDecimal round(BigDecimal n, int scale) { return n.setScale(scale, RoundingMode.HALF_UP); }
        public static String upper(String s) { return s == null ? null : s.toUpperCase(Locale.ROOT); }
        public static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }
        public static String trim(String s) { return s == null ? null : s.strip(); }
        public static boolean isBlank(String s) { return s == null || s.isBlank(); }
        public static boolean startsWith(String s, String prefix) { return s != null && prefix != null && s.startsWith(prefix); }
        public static boolean endsWith(String s, String suffix) { return s != null && suffix != null && s.endsWith(suffix); }
        public static boolean equalsIgnoreCase(String a, String b) { return a == null ? b == null : a.equalsIgnoreCase(b); }
        /** Number of elements of a list or map, or characters of a text; 0 for nothing. */
        public static long size(Object x) {
            return switch (x) {
                case null -> 0L;
                case Collection<?> c -> c.size();
                case Map<?, ?> m -> m.size();
                case String t -> t.length();
                default -> throw new IllegalArgumentException("size() needs a list, a map or text, not " + x.getClass().getSimpleName());
            };
        }
        public static boolean isEmpty(Object x) { return size(x) == 0; }
        /** A list holds the value, a map has the key, a text contains the text. Numbers compare by value (1 equals 1.0). */
        public static boolean contains(Object container, Object value) {
            return switch (container) {
                case null -> false;
                case String t -> value != null && t.contains(String.valueOf(value));
                case Map<?, ?> m -> m.keySet().stream().anyMatch(k -> same(k, value));
                case Collection<?> c -> c.stream().anyMatch(v -> same(v, value));
                default -> throw new IllegalArgumentException("contains() needs a list, a map or text, not " + container.getClass().getSimpleName());
            };
        }
        /** Sum of the numbers in a list (empty or null values are ignored). */
        public static BigDecimal sum(Object list) {
            if (!(list instanceof Collection<?> c)) throw new IllegalArgumentException("sum() needs a list");
            BigDecimal total = BigDecimal.ZERO;
            for (Object v : c) {
                if (v == null) continue;
                if (!(v instanceof Number n)) throw new IllegalArgumentException("sum() found '" + v + "', which is not a number");
                total = total.add(v instanceof BigDecimal b ? b : new BigDecimal(n.toString()));
            }
            return total;
        }
        public static List<Object> distinct(Object list) {
            if (!(list instanceof Collection<?> c)) throw new IllegalArgumentException("distinct() needs a list");
            return new ArrayList<>(new LinkedHashSet<>(c));
        }
        static boolean same(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
            return Objects.equals(a, b);
        }
    }

    static Set<String> functionNames(boolean results) {
        Set<String> names = new TreeSet<>(FUNCTIONS.keySet());
        if (results) names.addAll(RESULT_FUNCTIONS);
        return names;
    }

    // ------------------------------------------------------------------ parsing and offline checks

    /** Parses and checks an expression without evaluating it. {@code variables} are the parameter names it may use; {@code results} allows q(), c() and f(). */
    static Expression parse(String text, String where, Collection<String> variables, boolean results) {
        if (text == null || text.isBlank()) throw new ToolException(where + ": the expression is empty.");
        if (text.length() > MAX_LENGTH) throw new ToolException(where + ": the expression is longer than " + MAX_LENGTH + " characters.");
        Expression expression;
        try { expression = PARSER.parseExpression(text); }
        catch (ParseException e) { throw new ToolException(where + ": cannot parse the expression (" + e.getSimpleMessage() + "): " + text); }
        Set<String> functions = functionNames(results);
        walk(((SpelExpression) expression).getAST(), where, text, new HashSet<>(variables), functions, results);
        return expression;
    }

    private static void walk(SpelNode node, String where, String text, Set<String> variables, Set<String> functions, boolean results) {
        String kind = node.getClass().getSimpleName();
        if (FORBIDDEN.contains(kind))
            throw new ToolException(where + ": " + describe(kind) + " is not allowed in expressions (use the functions " + functionNames(results) + "): " + text);
        if (kind.equals("FunctionReference")) {
            Matcher m = Pattern.compile("^#([A-Za-z_]\\w*)\\(").matcher(node.toStringAST());
            String name = m.find() ? m.group(1) : "?";
            if (variables.contains(name)) throw new ToolException(where + ": '" + name + "' is a parameter, not a function: " + text);
            if (!functions.contains(name)) throw new ToolException(where + ": unknown function " + name + "(). "
                + (RESULT_FUNCTIONS.contains(name) ? "q(), c() and f() read results, so they work only in <expect>. " : "") + "Available: " + functions + ".");
        } else if (kind.equals("VariableReference")) {
            String name = node.toStringAST().replaceFirst("^#", "");
            if (!name.equals("this") && !name.equals("root") && !variables.contains(name))
                throw new ToolException(where + ": #" + name + " is not a parameter" + (functions.contains(name) ? " (functions are called with brackets: #" + name + "(...))" : "")
                    + ". Parameters that can be used here: " + new TreeSet<>(variables) + ".");
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), where, text, variables, functions, results);
    }

    private static String describe(String kind) {
        return switch (kind) {
            case "TypeReference" -> "a type reference T(...)";
            case "ConstructorReference" -> "creating objects with new";
            case "BeanReference" -> "a bean reference @...";
            case "MethodReference" -> "calling a method on a value (only the functions can be called)";
            case "Assign" -> "assignment";
            case "OperatorMatches" -> "matches (regular expressions)";
            case "OperatorInstanceof" -> "instanceof";
            default -> kind;
        };
    }

    // ------------------------------------------------------------------ evaluation

    /** Stands in for a result that is not there (failed or skipped): reading anything from it says why. */
    static final class Missing extends AbstractMap<String, Object> {
        private final String message;
        Missing(String message) { this.message = message; }
        @Override public Set<Map.Entry<String, Object>> entrySet() { throw new IllegalStateException(message); }
        @Override public boolean containsKey(Object key) { throw new IllegalStateException(message); }
        @Override public Object get(Object key) { throw new IllegalStateException(message); }
    }

    /** Variable names a rule's results can be read by (ids that are valid identifiers). Longer or hyphenated ids use q('id'), c('id'), f('id'). */
    static Set<String> resultNames(Rules rules) {
        Set<String> names = new TreeSet<>();
        rules.queries.forEach(q -> { if (IDENTIFIER.matcher(q.id).matches()) names.add(q.id); });
        rules.comparisons.forEach(c -> { if (IDENTIFIER.matcher(c.id).matches()) names.add(c.id); });
        rules.funnels.forEach(f -> { if (IDENTIFIER.matcher(f.id).matches()) names.add(f.id); });
        return names;
    }

    /**
     * What q(), c(), f(), rows() and values() and the result variables can see: the results of this run, as plain maps and lists.
     * Each result read is remembered so a failing expectation can show what it read.
     */
    static final class Lookups {
        static final int MAX_ROWS = 20_000, MAX_VALUES = 100_000;
        private final Rules rules;
        private final Map<String, QueryRunner.Result> queries;
        private final List<ComparisonRunner.Result> comparisons;
        private final List<FunnelRunner.Result> funnels;
        private final Set<String> skipped;
        private final Map<String, List<Map<String, Object>>> rowCache = new HashMap<>();
        final Map<String, Map<String, Object>> touched = new LinkedHashMap<>();

        Lookups(Rules rules, Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons, List<FunnelRunner.Result> funnels, Set<String> skipped) {
            this.rules = rules; this.queries = queries; this.comparisons = comparisons; this.funnels = funnels; this.skipped = skipped;
        }

        private void notSkipped(String kind, String id) {
            if (skipped.contains(id)) throw new IllegalStateException(kind + " " + id + " was skipped (its when= condition was false), so there is nothing to read");
        }

        public Map<String, Object> q(String id) {
            notSkipped("query", id);
            QueryRunner.Result r = queries.get(id);
            if (r == null) throw new IllegalStateException("query " + id + " did not complete (see MANUAL_ACTIONS.md)");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", r.rows());
            m.put("partial", r.partial());
            m.put("columns", r.columns().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).toList());
            Map<String, Object> first = new LinkedHashMap<>();
            if (r.rows() > 0 && r.csv() != null && java.nio.file.Files.exists(r.csv())) {
                try (var parser = CsvFiles.reader(r.csv(), true)) {
                    var it = parser.iterator();
                    if (it.hasNext()) {
                        var record = it.next();
                        for (int i = 0; i < r.columns().size(); i++) first.put(r.columns().get(i).name().toLowerCase(Locale.ROOT), friendly(Chaining.typed(record.get(i), r.columns().get(i).jdbcType())));
                    }
                } catch (IOException e) { throw new IllegalStateException("cannot read the rows of query " + id, e); }
            }
            m.put("first", first);
            touched.put("q(" + id + ")", m);
            return m;
        }

        /** Every row of a query as a map by lower-case column name, for conditions such as #rows('q').?[amount &gt; 100]. */
        public List<Map<String, Object>> rows(String id) {
            notSkipped("query", id);
            QueryRunner.Result r = queries.get(id);
            if (r == null) throw new IllegalStateException("query " + id + " did not complete (see MANUAL_ACTIONS.md)");
            if (r.rows() > MAX_ROWS) throw new IllegalStateException("query " + id + " has " + Progress.count(r.rows()) + " rows; rows() reads at most " + Progress.count(MAX_ROWS)
                + ". Use values('" + id + "', 'column') for one column, or reduce the rows in SQL.");
            return rowCache.computeIfAbsent(id, k -> {
                List<Map<String, Object>> all = new ArrayList<>();
                if (r.csv() == null || !java.nio.file.Files.exists(r.csv())) return all;
                try (var parser = CsvFiles.reader(r.csv(), true)) {
                    for (var record : parser) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < r.columns().size(); i++) row.put(r.columns().get(i).name().toLowerCase(Locale.ROOT), friendly(Chaining.typed(record.get(i), r.columns().get(i).jdbcType())));
                        all.add(row);
                    }
                } catch (IOException e) { throw new IllegalStateException("cannot read the rows of query " + id, e); }
                return all;
            });
        }

        /** One column of a query as a list, in row order. */
        public List<Object> values(String id, String column) {
            notSkipped("query", id);
            QueryRunner.Result r = queries.get(id);
            if (r == null) throw new IllegalStateException("query " + id + " did not complete (see MANUAL_ACTIONS.md)");
            int index = -1;
            for (int i = 0; i < r.columns().size(); i++) if (r.columns().get(i).name().equalsIgnoreCase(column)) index = i;
            if (index < 0) throw new IllegalStateException("query " + id + " has no column '" + column + "'; its columns: " + r.columns().stream().map(QueryRunner.Column::name).toList());
            if (r.rows() > MAX_VALUES) throw new IllegalStateException("query " + id + " has " + Progress.count(r.rows()) + " rows; values() reads at most " + Progress.count(MAX_VALUES) + ".");
            List<Object> values = new ArrayList<>();
            if (r.csv() != null && java.nio.file.Files.exists(r.csv())) {
                try (var parser = CsvFiles.reader(r.csv(), true)) {
                    for (var record : parser) values.add(friendly(Chaining.typed(record.get(index), r.columns().get(index).jdbcType())));
                } catch (IOException e) { throw new IllegalStateException("cannot read the rows of query " + id, e); }
            }
            return values;
        }

        public Map<String, Object> c(String id) {
            notSkipped("comparison", id);
            var r = comparisons.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("comparison " + id + " did not complete (see MANUAL_ACTIONS.md)"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matched", r.matched()); m.put("equal", r.equal()); m.put("different", r.different());
            m.put("onlySource", r.onlySource()); m.put("onlyTarget", r.onlyTarget());
            m.put("duplicates", r.duplicateSourceKeys() + r.duplicateTargetKeys());
            m.put("differentCells", r.differentCells()); m.put("partial", r.partial());
            touched.put("c(" + id + ")", m);
            return m;
        }

        public Map<String, Object> f(String id) {
            notSkipped("funnel", id);
            var r = funnels.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("funnel " + id + " did not complete (see MANUAL_ACTIONS.md)"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("diverging", r.diverging());
            m.put("firstDivergence", r.firstDivergence() < 0 ? "none" : r.stages().get(r.firstDivergence()).id());
            touched.put("f(" + id + ")", m);
            return m;
        }

        /** The value of #id for a query, comparison or funnel of the rule; a Missing stand-in explains when it is not there. */
        Object variable(String id) {
            try {
                if (rules.queries.stream().anyMatch(x -> x.id.equals(id))) return q(id);
                if (rules.comparisons.stream().anyMatch(x -> x.id.equals(id))) return c(id);
                if (rules.funnels.stream().anyMatch(x -> x.id.equals(id))) return f(id);
            } catch (IllegalStateException e) { return new Missing(e.getMessage()); }
            return null;
        }
    }

    /** A read-only context holding the functions, the parameters (as java.time / numbers / text) and, after the run, the result lookups. */
    static EvaluationContext context(Map<String, Object> parameters, Lookups lookups) { return context(parameters, lookups, null); }

    static EvaluationContext context(Map<String, Object> parameters, Lookups lookups, Expression expression) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forPropertyAccessors(new MapAccessor(), DataBindingPropertyAccessor.forReadOnlyAccess())
            .withAssignmentDisabled().build();
        FUNCTIONS.forEach(context::setVariable);
        if (lookups != null) {
            try {
                var lookup = MethodHandles.lookup();
                context.setVariable("q", lookup.findVirtual(Lookups.class, "q", MethodType.methodType(Map.class, String.class)).bindTo(lookups));
                context.setVariable("c", lookup.findVirtual(Lookups.class, "c", MethodType.methodType(Map.class, String.class)).bindTo(lookups));
                context.setVariable("f", lookup.findVirtual(Lookups.class, "f", MethodType.methodType(Map.class, String.class)).bindTo(lookups));
                context.setVariable("rows", lookup.findVirtual(Lookups.class, "rows", MethodType.methodType(List.class, String.class)).bindTo(lookups));
                context.setVariable("values", lookup.findVirtual(Lookups.class, "values", MethodType.methodType(List.class, String.class, String.class)).bindTo(lookups));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            // #name for the queries, comparisons and funnels the expression mentions (a parameter of the same name wins)
            if (expression != null) for (String name : referencedVariables(expression))
                if (!parameters.containsKey(name)) { Object v = lookups.variable(name); if (v != null) context.setVariable(name, v); }
        }
        parameters.forEach((name, value) -> context.setVariable(name, friendly(value)));
        return context;
    }

    static Set<String> referencedVariables(Expression expression) {
        Set<String> names = new LinkedHashSet<>();
        collect(((SpelExpression) expression).getAST(), names);
        return names;
    }

    private static void collect(SpelNode node, Set<String> names) {
        if (node.getClass().getSimpleName().equals("VariableReference")) names.add(node.toStringAST().replaceFirst("^#", ""));
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), names);
    }

    static Object friendly(Object value) {
        if (value instanceof Date d) return d.toLocalDate();
        if (value instanceof Timestamp t) return t.toLocalDateTime();
        return value;
    }

    static Object evaluate(Expression expression, EvaluationContext context, String where) {
        try { return expression.getValue(context); }
        catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String message = root instanceof org.springframework.expression.spel.SpelEvaluationException s ? s.getSimpleMessage() : String.valueOf(root.getMessage());
            throw new ToolException(where + ": " + message, e);
        }
    }

    static boolean bool(Object value, String where, String text) {
        if (value instanceof Boolean b) return b;
        throw new ToolException(where + ": the expression must give true or false but gave " + (value == null ? "nothing (null)" : value + " (" + value.getClass().getSimpleName() + ")") + ": " + text);
    }

    /** A computed parameter's value as the text the declared type is parsed from. */
    static String text(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate d -> d.toString();
            case LocalDateTime t -> t.toString().replace('T', ' ');
            case BigDecimal n -> n.toPlainString();
            default -> String.valueOf(value);
        };
    }

    /** One line of what an expectation's expression read, for the verdict. */
    static String touchedSummary(Map<String, Map<String, Object>> touched) {
        if (touched.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var e : touched.entrySet()) {
            List<String> fields = new ArrayList<>();
            for (var f : e.getValue().entrySet()) {
                if (f.getKey().equals("columns") || f.getKey().equals("first") || f.getKey().equals("partial")) continue;
                fields.add(f.getKey() + "=" + f.getValue());
            }
            parts.add(e.getKey() + ": " + String.join(", ", fields));
        }
        return " [" + String.join("; ", parts.subList(0, Math.min(6, parts.size()))) + "]";
    }
}
