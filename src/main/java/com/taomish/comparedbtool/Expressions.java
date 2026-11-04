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
    static final Set<String> RESULT_FUNCTIONS = Set.of("q", "c", "f");
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

    /** What q(), c() and f() can see: the results of this run, as plain maps. Each call is remembered so a failing expectation can show what it read. */
    static final class Lookups {
        private final Map<String, QueryRunner.Result> queries;
        private final List<ComparisonRunner.Result> comparisons;
        private final List<FunnelRunner.Result> funnels;
        final Map<String, Map<String, Object>> touched = new LinkedHashMap<>();

        Lookups(Map<String, QueryRunner.Result> queries, List<ComparisonRunner.Result> comparisons, List<FunnelRunner.Result> funnels) {
            this.queries = queries; this.comparisons = comparisons; this.funnels = funnels;
        }

        public Map<String, Object> q(String id) {
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

        public Map<String, Object> c(String id) {
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
            var r = funnels.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("funnel " + id + " did not complete (see MANUAL_ACTIONS.md)"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("diverging", r.diverging());
            m.put("firstDivergence", r.firstDivergence() < 0 ? "none" : r.stages().get(r.firstDivergence()).id());
            touched.put("f(" + id + ")", m);
            return m;
        }
    }

    /** A read-only context holding the functions, the parameters (as java.time / numbers / text) and, after the run, the result lookups. */
    static EvaluationContext context(Map<String, Object> parameters, Lookups lookups) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forPropertyAccessors(new MapAccessor(), DataBindingPropertyAccessor.forReadOnlyAccess())
            .withAssignmentDisabled().build();
        FUNCTIONS.forEach(context::setVariable);
        if (lookups != null) {
            try {
                var lookup = MethodHandles.lookup();
                for (String name : RESULT_FUNCTIONS)
                    context.setVariable(name, lookup.findVirtual(Lookups.class, name, MethodType.methodType(Map.class, String.class)).bindTo(lookups));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        parameters.forEach((name, value) -> context.setVariable(name, friendly(value)));
        return context;
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
