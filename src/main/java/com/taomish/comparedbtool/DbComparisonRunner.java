package com.taomish.comparedbtool;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** One database statement: materialized inputs, row/cell aggregates and bounded samples. */
final class DbComparisonRunner {
    record Outcome(ComparisonRunner.Result comparison, QueryRunner.Result source, QueryRunner.Result target, long fetched) {}

    static void validate(Rules.Compare rule, Rules.Sql source, Rules.Sql target, DatasourceSettings settings, boolean checkQueries) {
        if (!Set.of("stream", "db").contains(rule.engine)) throw new ToolException("Unknown comparison engine: " + rule.engine);
        if (!"db".equals(rule.engine)) return;
        if (!source.db.equals(target.db)) throw new ToolException("engine=db requires the same datasource: " + rule.id);
        if (source.maxRows != 0 || target.maxRows != 0) throw new ToolException("engine=db does not support maxRows: " + rule.id);
        for (Rules.Column c : rule.columns) {
            if (c.dateOnly) throw new ToolException("engine=db cannot translate dateOnly exactly: " + rule.id);
            if (!c.ignore && c.nullAsEmpty && c.nullAsZero)
                throw new ToolException("engine=db cannot combine nullAsEmpty and nullAsZero: " + c.name);
        }
        if (checkQueries) {
            String url = settings.getDatasources().get(source.db).getUrl();
            if (url == null || !(url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:h2:")))
                throw new ToolException("engine=db supports PostgreSQL (and H2 tests) only: " + rule.id);
        }
    }

    Outcome run(Rules.Compare rule, Rules.Sql source, Rules.Sql target, QueryRunner runner,
                Map<String, Object> parameters, Path root, ExportMode defaultMode, int sampleRows) throws Exception {
        validate(rule, source, target, null, false);
        QueryRunner.validateParameters(target, parameters);
        Rules.Sql combined = new Rules.Sql();
        combined.id = rule.id; combined.db = source.db;
        combined.text = "WITH s AS (\n" + SqlGuard.validate(source.text) + "\n), t AS (\n" + SqlGuard.validate(target.text) + "\n) SELECT 1";
        combined.tempTables = source.tempTables || target.tempTables;
        combined.timeout = Math.min(source.timeout, target.timeout);
        combined.fetchSize = Math.min(source.fetchSize, target.fetchSize);
        long start = System.nanoTime();
        return runner.transaction(combined, parameters, connection -> {
            var jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
            List<QueryRunner.Column> left = metadata(jdbc, source, parameters), right = metadata(jdbc, target, parameters);
            var a = new QueryRunner.Result(source.id, null, left, 0, false, 0, List.of());
            var b = new QueryRunner.Result(target.id, null, right, 0, false, 0, List.of());
            var plan = new Plan(rule, source, target, a, b, connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"),
                ExportMode.resolve(rule.export, defaultMode), sampleRows);
            Path directory = Files.createDirectory(root.resolve(rule.id));
            ExportMode mode = ExportMode.resolve(rule.export, defaultMode);
            var groups = new TreeMap<ComparisonRunner.Group, Long>(Comparator.comparing(ComparisonRunner.Group::column)
                .thenComparing(ComparisonRunner.Group::classification).thenComparing(ComparisonRunner.Group::accepted));
            Breakdowns breakdowns = new Breakdowns(rule, a, b, new MemoryBudget(256));
            long[] totals = new long[10], counts = new long[2], fetched = {0};
            var exports = new LinkedHashMap<String, String>();
            List<String> keyHeader = RuleLoader.keys(rule).stream().map(k -> "key." + k).toList();
            try (CSVPrinter diff = printer(directory.resolve("differences.csv"), concat(keyHeader,
                     List.of("column", "source", "target", "classification", "accepted", "absolute_difference", "relative_difference")), mode);
                 CSVPrinter ls = printer(directory.resolve("only-source.csv"), left.stream().map(QueryRunner.Column::name).toList(), mode);
                 CSVPrinter rt = printer(directory.resolve("only-target.csv"), right.stream().map(QueryRunner.Column::name).toList(), mode);
                 CSVPrinter dup = printer(directory.resolve("duplicates.csv"), concat(keyHeader, List.of("side", "row_count")), mode)) {
                long[] written = new long[4];
                jdbc.execute(plan.sql(), parameters, (PreparedStatementCallback<Void>) statement -> {
                    statement.setQueryTimeout(combined.timeout); statement.setFetchSize(combined.fetchSize);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            fetched[0]++;
                            String kind = rs.getString(1);
                            switch (kind) {
                                case "totals" -> {
                                    for (int i = 0; i < 8; i++) totals[i] = rs.getLong(i + 2);
                                    counts[0] = rs.getLong(10); counts[1] = rs.getLong(11);
                                    if (rs.getLong(12) != 0) throw new ToolException("engine=db encountered text needing numeric/date/Unicode conversion; use engine=stream or project native numeric columns.");
                                }
                                case "group" -> {
                                    var group = new ComparisonRunner.Group(rs.getString(2), rs.getString(3), rs.getBoolean(4));
                                    long n = rs.getLong(5); groups.put(group, n); totals[group.accepted() ? 9 : 8] += n;
                                }
                                case "cell" -> {
                                    int index = rs.getInt(2); var binding = plan.bindings.get(index);
                                    String lv = rs.getString(3), rv = rs.getString(4);
                                    var change = Values.compare(lv, rv, binding.rule(), binding.numeric());
                                    if (change == null) throw new ToolException("Database returned an unchanged sample cell.");
                                    List<String> keys = plan.readKeys(rs, 5);
                                    if (diff != null) diff.printRecord(concat(keys, Arrays.asList(binding.name(), lv, rv,
                                        change.classification(), String.valueOf(change.accepted()), change.absoluteDifference(), change.relativeDifference())));
                                    written[0]++;
                                }
                                case "left", "right" -> {
                                    boolean isLeft = kind.equals("left"); int n = isLeft ? left.size() : right.size();
                                    List<String> row = new ArrayList<>(); for (int i = 0; i < n; i++) row.add(rs.getString(i + 2));
                                    CSVPrinter out = isLeft ? ls : rt; if (out != null) out.printRecord(row); written[isLeft ? 1 : 2]++;
                                }
                                case "dup" -> {
                                    List<String> row = plan.readKeys(rs, 4); row.add(rs.getString(2)); row.add(rs.getString(3));
                                    if (dup != null) dup.printRecord(row); written[3]++;
                                }
                                case "break-total" -> breakdowns.total(rs.getInt(2), rs.getString(3), rs.getLong(4));
                                case "break" -> {
                                    int table = rs.getInt(2); String category = rs.getString(3); boolean isLeft = !category.equals("only-target");
                                    List<String> row = new ArrayList<>(Collections.nCopies(isLeft ? left.size() : right.size(), null));
                                    var names = rule.breakdowns.get(table).columns.split(",");
                                    for (int i = 0; i < names.length; i++) {
                                        int column = plan.breakIndex(names[i].strip(), isLeft);
                                        if (column >= 0) row.set(column, rs.getString(i + 5));
                                    }
                                    breakdowns.addCount(table, category, row, isLeft, rs.getLong(4));
                                }
                                default -> throw new ToolException("Unknown database comparison event: " + kind);
                            }
                        }
                    } catch (java.io.IOException error) { throw new ToolException("Cannot write database comparison samples.", error); }
                    return null;
                });
                long diffCount = groups.entrySet().stream().filter(e -> mode == ExportMode.FULL || !e.getKey().accepted()).mapToLong(Map.Entry::getValue).sum();
                exports.put("differences.csv", showing(written[0], diffCount));
                exports.put("only-source.csv", showing(written[1], totals[3]));
                exports.put("only-target.csv", showing(written[2], totals[4]));
                exports.put("duplicates.csv", showing(written[3], totals[5] + totals[6]));
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            a = new QueryRunner.Result(source.id, null, left, counts[0], false, millis, List.of());
            b = new QueryRunner.Result(target.id, null, right, counts[1], false, millis, List.of());
            var result = ComparisonRunner.finish(rule, a, b, directory, mode, totals, groups, breakdowns, exports,
                plan.extras(true), plan.extras(false));
            return new Outcome(result, a, b, fetched[0]);
        });
    }

    private static String showing(long n, long total) { return "showing " + n + " of " + total; }
    private static CSVPrinter printer(Path path, List<String> header, ExportMode mode) throws Exception {
        return mode == ExportMode.NONE ? null : CsvFiles.writer(path, header);
    }
    private static List<QueryRunner.Column> metadata(NamedParameterJdbcTemplate jdbc, Rules.Sql query, Map<String, Object> parameters) {
        return jdbc.execute(SqlGuard.validate(query.text), parameters, (PreparedStatementCallback<List<QueryRunner.Column>>) statement -> {
            statement.setQueryTimeout(query.timeout);
            var meta = statement.getMetaData();
            if (meta == null) throw new ToolException("engine=db requires JDBC result metadata before execution.");
            var columns = new ArrayList<QueryRunner.Column>(); var labels = new HashSet<String>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String name = meta.getColumnLabel(i);
                if (name == null || name.isBlank() || !labels.add(name.toLowerCase(Locale.ROOT)))
                    throw new ToolException("engine=db needs distinct nonempty column aliases: " + query.id);
                if (!Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL,
                        Types.VARCHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(meta.getColumnType(i)))
                    throw new ToolException("engine=db requires exact numeric or variable text columns; unsupported type for " + name);
                columns.add(new QueryRunner.Column(name, meta.getColumnType(i)));
            }
            return columns;
        });
    }
    private static <T> List<T> concat(List<T> a, List<T> b) { var result = new ArrayList<>(a); result.addAll(b); return result; }

    private static final class Plan {
        final Rules.Compare rule;
        final QueryRunner.Result source, target;
        final List<ComparisonRunner.Binding> bindings, keys;
        final List<String> ctes = new ArrayList<>(), events = new ArrayList<>();
        final int width;
        final boolean postgres;
        final ExportMode mode;
        final int samples;

        Plan(Rules.Compare rule, Rules.Sql a, Rules.Sql b, QueryRunner.Result source, QueryRunner.Result target,
             boolean postgres, ExportMode mode, int samples) {
            this.rule = rule; this.source = source; this.target = target; this.postgres = postgres; this.mode = mode; this.samples = samples;
            bindings = ComparisonRunner.bindings(rule, source, target);
            keys = RuleLoader.keys(rule).stream().map(k -> bindings.stream().filter(x -> x.name().equalsIgnoreCase(k)).findFirst()
                .orElseThrow(() -> new ToolException("Missing database comparison key: " + k))).toList();
            width = Math.max(12, Math.max(Math.max(source.columns().size(), target.columns().size()) + 1,
                Math.max(keys.size() + 4, rule.breakdowns.stream().mapToInt(x -> x.columns.split(",").length + 4).max().orElse(0))));
            for (var binding : bindings) checkType(binding);
            ctes.add("s AS " + (postgres ? "MATERIALIZED " : "") + "(\n" + SqlGuard.validate(a.text) + "\n)");
            ctes.add("t AS " + (postgres ? "MATERIALIZED " : "") + "(\n" + SqlGuard.validate(b.text) + "\n)");
            build();
        }
        private void checkType(ComparisonRunner.Binding b) {
            int x = source.columns().get(b.source()).jdbcType(), y = target.columns().get(b.target()).jdbcType();
            Set<Integer> numbers = Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL);
            Set<Integer> strings = Set.of(Types.VARCHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.LONGNVARCHAR);
            if (!(numbers.contains(x) && numbers.contains(y)) && !(strings.contains(x) && strings.contains(y)))
                throw new ToolException("engine=db requires native exact numeric or variable text columns; unsupported types for " + b.name());
            if (!b.numeric() && (b.rule().numeric || b.rule().round != null || b.rule().nullAsZero || b.rule().absTol.signum() > 0 || b.rule().relTol.signum() > 0))
                throw new ToolException("engine=db numeric rules require native numeric columns: " + b.name());
        }
        private static String q(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
        private static String lit(String text) { return "'" + text.replace("'", "''") + "'"; }
        private static String text(String expression) { return "CAST(" + expression + " AS VARCHAR)"; }
        private static String same(String a, String b) { return "(" + a + " IS NOT DISTINCT FROM " + b + ")"; }
        private static String raw(ComparisonRunner.Binding b, boolean left) { return (left ? "a" + b.source() : "b" + b.target()); }
        private static String strip(String expression) {
            String chars = "[\t\n\u000B\f\r\u001C-\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]";
            return "REGEXP_REPLACE(REGEXP_REPLACE(" + expression + ", " + lit("^" + chars + "+") + ", ''), " + lit(chars + "+$") + ", '')";
        }
        private String norm(String expression, ComparisonRunner.Binding b) {
            var r = b.rule(); String result = expression;
            if (b.numeric()) {
                if (r.round != null) result = "ROUND(" + result + ", " + r.round + ")";
                if (r.nullAsZero) result = "COALESCE(" + result + ", 0)";
                // nullAsEmpty has no effect on numeric equality except NULL vs a text empty string, which native numerics cannot contain.
            } else {
                if (postgres) result = "(" + result + " COLLATE \"C\")";
                if (r.trim) result = strip(result);
                if (r.caseInsensitive) result = "UPPER(" + result + ")";
                if (r.nullAsEmpty) result = "COALESCE(" + result + ", '')";
            }
            return result;
        }
        private String accepted(ComparisonRunner.Binding b) {
            String x = raw(b, true), y = raw(b, false);
            String result = same(norm(x, b), norm(y, b));
            if (b.numeric()) result += " OR (" + x + " IS NOT NULL AND " + y + " IS NOT NULL AND (ABS(" + x + "-" + y + ") <= "
                + b.rule().absTol.toPlainString() + " OR ABS(" + x + "-" + y + ") <= " + b.rule().relTol.toPlainString()
                + " * GREATEST(ABS(" + x + "), ABS(" + y + "))))";
            return "(" + result + ")";
        }
        private String changed(ComparisonRunner.Binding b) { return "NOT " + same(text(raw(b, true)), text(raw(b, false))); }
        private String classification(ComparisonRunner.Binding b) {
            String x = raw(b, true), y = raw(b, false), a = text(x), z = text(y);
            String result = "CASE WHEN " + x + " IS NOT NULL AND " + y + " IS NOT NULL AND UPPER(" + a + ") = UPPER(" + z + ") THEN 'CASE_ONLY'"
                + " WHEN " + x + " IS NOT NULL AND " + y + " IS NOT NULL AND " + strip(a) + " = " + strip(z) + " THEN 'WHITESPACE_ONLY'"
                + " WHEN (" + x + " IS NULL AND " + z + " = '') OR (" + y + " IS NULL AND " + a + " = '') THEN 'NULL_VS_EMPTY'";
            if (b.numeric()) result += " WHEN (" + x + " IS NULL AND " + y + " = 0) OR (" + y + " IS NULL AND " + x + " = 0) THEN 'NULL_VS_ZERO'"
                + " WHEN " + x + " IS NOT NULL AND " + y + " IS NOT NULL THEN CASE WHEN " + x + " = " + y + " THEN 'FORMAT_ONLY' WHEN "
                + accepted(b) + " THEN 'ROUNDING' WHEN " + x + " <> 0 AND " + x + " = -" + y + " THEN 'SIGN_FLIP' ELSE 'OTHER' END";
            return result + " ELSE 'OTHER' END";
        }
        private String unsupported(ComparisonRunner.Binding b) {
            if (b.numeric()) return "(" + text(raw(b, true)) + " IN ('NaN','Infinity','-Infinity') OR " + text(raw(b, false)) + " IN ('NaN','Infinity','-Infinity'))";
            String checks = "";
            for (boolean left : List.of(true, false)) {
                String x = raw(b, left);
                // Java parses numeric/date-looking text and uses Unicode case rules; do not approximate those dialect-dependent conversions.
                String numeric = regex(strip(x), "^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$");
                String dates = regex(x, "^[+-]?[0-9]{4,}-[0-9]{2}-[0-9]{2}($|[T ])");
                String unicode = regex(x, "[^\u0001-\u007F]");
                checks += (checks.isEmpty() ? "" : " OR ") + "(" + numeric + " OR " + dates + " OR " + unicode + ")";
            }
            boolean key = keys.contains(b);
            return "(" + (key ? "TRUE" : changed(b)) + " AND (" + checks + "))";
        }
        private String regex(String expression, String pattern) {
            return postgres ? "(" + expression + " ~ " + lit(pattern) + ")" : "REGEXP_LIKE(" + expression + ", " + lit(pattern) + ")";
        }
        private void side(boolean left) {
            var columns = (left ? source : target).columns(); String prefix = left ? "a" : "b", name = left ? "s" : "t";
            List<String> fields = new ArrayList<>(), partition = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                String value = q(columns.get(i).name());
                if (postgres && Set.of(Types.VARCHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(columns.get(i).jdbcType()))
                    value += " COLLATE \"C\"";
                fields.add(value + " AS " + prefix + i);
            }
            for (int i = 0; i < keys.size(); i++) {
                var k = keys.get(i); String value = norm(q(columns.get(left ? k.source() : k.target()).name()), k);
                fields.add(value + " AS k" + i); partition.add(value);
            }
            String over = " OVER (PARTITION BY " + String.join(", ", partition) + ")";
            fields.add("COUNT(*)" + over + " AS " + (left ? "sn" : "tn")); fields.add("ROW_NUMBER()" + over + " AS rn");
            ctes.add(name + "r AS (SELECT " + String.join(", ", fields) + " FROM " + name + ")");
            ctes.add(name + "u AS (SELECT * FROM " + name + "r WHERE rn=1)");
        }
        private void event(String from, List<String> expressions) {
            List<String> fields = new ArrayList<>();
            for (int i = 0; i < width; i++) fields.add(text(i < expressions.size() ? expressions.get(i) : "NULL") + " AS e" + i);
            events.add("SELECT " + String.join(", ", fields) + " " + from);
        }
        private void build() {
            side(true); side(false);
            List<String> projection = new ArrayList<>(), on = new ArrayList<>();
            for (int i = 0; i < source.columns().size(); i++) projection.add("su.a" + i);
            for (int i = 0; i < target.columns().size(); i++) projection.add("tu.b" + i);
            projection.add("su.sn"); projection.add("tu.tn");
            for (int i = 0; i < keys.size(); i++) {
                projection.add("COALESCE(su.k" + i + ", tu.k" + i + ") AS k" + i);
                // Equijoins keep PostgreSQL FULL JOIN hash/merge compatible, including NULL keys.
                String empty = keys.get(i).numeric() ? "0" : "''";
                on.add("(COALESCE(su.k" + i + ", " + empty + ") = COALESCE(tu.k" + i + ", " + empty
                    + ") AND (su.k" + i + " IS NULL) = (tu.k" + i + " IS NULL))");
            }
            String select = "SELECT " + String.join(", ", projection), join = String.join(" AND ", on);
            ctes.add("j AS (" + (postgres ? select + " FROM su FULL OUTER JOIN tu ON " + join
                : select + " FROM su LEFT JOIN tu ON " + join + " UNION ALL " + select + " FROM tu LEFT JOIN su ON " + join + " WHERE su.sn IS NULL") + ")");
            String bad = bindings.stream().map(b -> "(" + changed(b) + " AND NOT " + accepted(b) + ")").collect(Collectors.joining(" OR "));
            ctes.add("r AS (SELECT j.*, CASE WHEN sn>1 OR tn>1 THEN 'ambiguous' WHEN sn IS NULL THEN 'only-target' WHEN tn IS NULL THEN 'only-source'"
                + " WHEN " + bad + " THEN 'different' ELSE 'equal' END AS category FROM j)");
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < bindings.size(); i++) {
                var b = bindings.get(i);
                cells.add("SELECT " + i + " AS ci, " + lit(b.name()) + " AS col, " + text(raw(b, true)) + " AS lv, " + text(raw(b, false))
                    + " AS rv, " + classification(b) + " AS cls, " + accepted(b) + " AS accepted, " + keyFields("")
                    + " FROM r WHERE sn=1 AND tn=1 AND " + changed(b));
            }
            ctes.add("cells AS (" + String.join(" UNION ALL ", cells) + ")");
            List<String> totals = new ArrayList<>(List.of("'totals'"));
            for (String condition : List.of("sn=1 AND tn=1", "category='equal'", "category='different'", "category='only-source'",
                    "category='only-target'", "sn>1", "tn>1", "category='ambiguous'"))
                totals.add("COALESCE(SUM(CASE WHEN " + condition + " THEN 1 ELSE 0 END),0)");
            totals.add("COALESCE(SUM(sn),0)"); totals.add("COALESCE(SUM(tn),0)");
            totals.add("COALESCE(SUM(CASE WHEN " + bindings.stream().map(this::unsupported).collect(Collectors.joining(" OR ")) + " THEN 1 ELSE 0 END),0)");
            event("FROM r", totals);
            event("FROM cells GROUP BY col, cls, accepted", List.of("'group'", "col", "cls", "accepted", "COUNT(*)"));
            if (mode != ExportMode.NONE) {
                ctes.add("sample_cells AS (SELECT cells.*, ROW_NUMBER() OVER (PARTITION BY cls, accepted ORDER BY " + keyFields("") + ", ci) AS sample_n FROM cells"
                    + (mode == ExportMode.FULL ? "" : " WHERE NOT accepted") + ")");
                event("FROM sample_cells WHERE sample_n <= " + samples, concat(List.of("'cell'", "ci", "lv", "rv"), keyExpressions()));
                for (boolean left : List.of(true, false)) {
                    String name = left ? "left" : "right", category = left ? "only-source" : "only-target";
                    ctes.add("sample_" + name + " AS (SELECT r.*, ROW_NUMBER() OVER (ORDER BY " + keyFields("") + ") AS sample_n FROM r WHERE category=" + lit(category) + ")");
                    var fields = new ArrayList<>(List.of(lit(name)));
                    for (int i = 0; i < (left ? source : target).columns().size(); i++) fields.add((left ? "a" : "b") + i);
                    event("FROM sample_" + name + " WHERE sample_n <= " + samples, fields);
                    String side = left ? "source" : "target", count = left ? "sn" : "tn";
                    ctes.add("dup_" + name + " AS (SELECT r.*, ROW_NUMBER() OVER (ORDER BY " + keyFields("") + ") AS sample_n FROM r WHERE " + count + ">1)");
                    event("FROM dup_" + name + " WHERE sample_n <= " + samples, concat(List.of("'dup'", lit(side), count), keyExpressions()));
                }
            }
            for (int i = 0; i < rule.breakdowns.size(); i++) breakdown(i);
        }
        private String keyFields(String prefix) { return keyExpressions().stream().map(k -> prefix + k).collect(Collectors.joining(", ")); }
        private List<String> keyExpressions() { var result = new ArrayList<String>(); for (int i = 0; i < keys.size(); i++) result.add("k" + i); return result; }
        private void breakdown(int index) {
            var b = rule.breakdowns.get(index); var names = Arrays.stream(b.columns.split(",")).map(String::strip).toList();
            List<String> fields = new ArrayList<>();
            for (String name : names) {
                int a = breakIndex(name, true), z = breakIndex(name, false);
                fields.add("CASE WHEN category='only-target' THEN " + (z < 0 ? "NULL" : text("b" + z)) + " ELSE " + (a < 0 ? "NULL" : text("a" + a)) + " END");
            }
            String name = "break" + index;
            var aliased = new ArrayList<String>(); for (int i = 0; i < fields.size(); i++) aliased.add(fields.get(i) + " AS g" + i);
            ctes.add(name + " AS (SELECT category, " + String.join(", ", aliased) + ", COUNT(*) AS n FROM r WHERE category IN ('different','only-source','only-target') GROUP BY category, " + String.join(", ", fields) + ")");
            var groupNames = new ArrayList<String>(); for (int i = 0; i < fields.size(); i++) groupNames.add("g" + i);
            var tie = new ArrayList<String>(List.of("'['"));
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) tie.add("', '");
                tie.add("'Cell[missing='");
                tie.add("CASE WHEN category='only-target' THEN '" + (breakIndex(names.get(i), false) < 0) + "' ELSE '" + (breakIndex(names.get(i), true) < 0) + "' END");
                tie.add("', value='"); tie.add("COALESCE(g" + i + ", 'null')"); tie.add("']'");
            }
            tie.add("']'");
            String order = "CONCAT(" + String.join(", ", tie) + ")" + (postgres ? " COLLATE \"C\"" : "");
            ctes.add(name + "rank AS (SELECT " + name + ".*, ROW_NUMBER() OVER (PARTITION BY category ORDER BY n DESC, " + order + ") AS rank_n FROM " + name + ")");
            event("FROM " + name + " GROUP BY category", List.of("'break-total'", String.valueOf(index), "category", "SUM(n)"));
            event("FROM " + name + "rank WHERE rank_n <= " + b.top, concat(List.of("'break'", String.valueOf(index), "category", "n"), groupNames));
        }
        int breakIndex(String name, boolean left) {
            String mapped = name;
            for (var c : rule.columns) if (c.name.equalsIgnoreCase(name)) mapped = left ? (c.source == null ? name : c.source) : (c.target == null ? name : c.target);
            var cols = (left ? source : target).columns();
            for (int i = 0; i < cols.size(); i++) if (cols.get(i).name().equalsIgnoreCase(mapped)) return i;
            return -1;
        }
        List<String> extras(boolean left) {
            Set<Integer> used = new HashSet<>(); bindings.forEach(b -> used.add(left ? b.source() : b.target()));
            var columns = (left ? source : target).columns();
            for (var c : rule.columns) if (c.ignore) for (int i = 0; i < columns.size(); i++)
                if (columns.get(i).name().equalsIgnoreCase(left ? (c.source == null ? c.name : c.source) : (c.target == null ? c.name : c.target))) used.add(i);
            var extra = new ArrayList<String>(); for (int i = 0; i < columns.size(); i++) if (!used.contains(i)) extra.add(columns.get(i).name()); return extra;
        }
        List<String> readKeys(ResultSet rs, int start) throws SQLException {
            var result = new ArrayList<String>();
            for (int i = 0; i < keys.size(); i++) result.add(Values.normalize(rs.getString(start + i), keys.get(i).rule(), keys.get(i).numeric()));
            return result;
        }
        String sql() { return "WITH " + String.join(",\n", ctes) + "\n" + String.join("\nUNION ALL\n", events); }
    }
}
