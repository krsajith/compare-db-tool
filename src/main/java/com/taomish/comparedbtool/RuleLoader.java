package com.taomish.comparedbtool;

import java.nio.file.Path;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Date;
import java.util.*;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;
import tools.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuleLoader {
    private static final Logger LOG = LoggerFactory.getLogger(RuleLoader.class);
    private RuleLoader() {}
    public static Rules load(Path path) {
        String section = "document";
        int line = -1, column = -1;
        LOG.info("Reading XML rules from {}", path.toAbsolutePath().normalize());
        try {
            XMLInputFactory input = XMLInputFactory.newFactory();
            input.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            input.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            input.setXMLResolver((publicId, systemId, base, namespace) -> {
                throw new javax.xml.stream.XMLStreamException("External entities disabled");
            });
            XmlFactory factory = XmlFactory.builder().xmlInputFactory(input).build();
            XmlMapper mapper = XmlMapper.builder(factory)
                // Jackson 3 enables this by default. A subtree intentionally has siblings
                // after it; the outer StAX loop validates the complete document instead.
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
            LOG.debug("XML provider={} Jackson XML version={}", input.getClass().getName(),
                XmlMapper.class.getPackage().getImplementationVersion());
            // Bind one subtree at a time. Binding the entire root to unwrapped Lists can
            // replace earlier entries when sql/compare groups are interleaved.
            try (var stream = Files.newInputStream(path)) {
                XMLStreamReader reader = input.createXMLStreamReader(stream);
                try {
                    if (nextTag(reader) != XMLStreamConstants.START_ELEMENT || !reader.getLocalName().equals("rules"))
                        throw new ToolException("Rules XML must have a <rules> root.");
                    Rules rules = new Rules();
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        if (!reader.getAttributeLocalName(i).equals("name"))
                            throw new ToolException("Unknown <rules> attribute: " + reader.getAttributeLocalName(i));
                        rules.name = reader.getAttributeValue(i);
                    }
                    boolean parametersSeen = false;
                    while (nextTag(reader) == XMLStreamConstants.START_ELEMENT) {
                        section = reader.getLocalName();
                        line = reader.getLocation().getLineNumber();
                        column = reader.getLocation().getColumnNumber();
                        LOG.debug("Reading XML element={} id={} line={} column={}", section,
                            reader.getAttributeValue(null, "id"), line, column);
                        switch (reader.getLocalName()) {
                            case "sql" -> rules.queries.add(mapper.readValue(reader, Rules.Sql.class));
                            case "compare" -> rules.comparisons.add(mapper.readValue(reader, Rules.Compare.class));
                            case "compress" -> rules.compressions.add(mapper.readValue(reader, Rules.Compress.class));
                            case "expect" -> rules.expectations.add(mapper.readValue(reader, Rules.Expect.class));
                            case "funnel" -> rules.funnels.add(mapper.readValue(reader, Rules.Funnel.class));
                            case "check" -> rules.checks.add(mapper.readValue(reader, Rules.Check.class));
                            case "parameters" -> {
                                if (parametersSeen) throw new ToolException("Use only one <parameters> block.");
                                parametersSeen = true;
                                while (nextTag(reader) == XMLStreamConstants.START_ELEMENT) {
                                    section = reader.getLocalName();
                                    line = reader.getLocation().getLineNumber();
                                    column = reader.getLocation().getColumnNumber();
                                    LOG.debug("Reading XML parameter name={} line={} column={}",
                                        reader.getAttributeValue(null, "name"), line, column);
                                    if (!reader.getLocalName().equals("param"))
                                        throw new ToolException("Only <param> elements are allowed inside <parameters>.");
                                    rules.parameters.add(mapper.readValue(reader, Rules.Param.class));
                                }
                            }
                            default -> throw new ToolException("Unknown rule element: " + reader.getLocalName());
                        }
                    }
                    // Consume the document end too, so trailing malformed content is rejected.
                    while (reader.hasNext()) {
                        int event = reader.next();
                        if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.DTD
                            || event == XMLStreamConstants.ENTITY_REFERENCE
                            || (event == XMLStreamConstants.CHARACTERS && !reader.isWhiteSpace()))
                            throw new ToolException("Unexpected content after </rules>.");
                    }
                    LOG.info("Loaded XML: queries={} comparisons={} funnels={} ZIPs={} parameters={}", rules.queries.size(),
                        rules.comparisons.size(), rules.funnels.size(), rules.compressions.size(), rules.parameters.size());
                    LOG.debug("SQL ids={} comparison ids={}", rules.queries.stream().map(q -> q.id).toList(),
                        rules.comparisons.stream().map(c -> c.id).toList());
                    return rules;
                } finally { reader.close(); }
            }
        } catch (ToolException e) { throw e;
        } catch (Exception e) {
            throw new ToolException("Cannot read rules XML: " + path + " (" + e.getClass().getSimpleName()
                + ") while reading <" + section + "> near line " + line + ", column " + column
                + ". See the log for the complete parser error.", e);
        }
    }

    private static int nextTag(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                throw new ToolException("DTD and entity references are not allowed in rules XML.");
            if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT) return event;
            if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && !reader.isWhiteSpace())
                throw new ToolException("Unexpected text between XML rule elements.");
        }
        throw new ToolException("Unexpected end of rules XML.");
    }

    public static void validate(Rules rules, DatasourceSettings settings) {
        validate(rules, settings, true);
    }

    public static void validate(Rules rules, DatasourceSettings settings, boolean checkQueries) {
        if (rules.queries == null || rules.queries.isEmpty()) throw new ToolException("Rules need at least one <sql>.");
        Set<String> ids = new HashSet<>();
        Map<String, Rules.Sql> queries = new HashMap<>();
        for (Rules.Sql sql : rules.queries) {
            id(sql.id, ids);
            ExportMode.resolve(sql.export, ExportMode.SAMPLE);
            if (sql.db == null || sql.db.isBlank()) throw new ToolException("Query " + sql.id + " needs a datasource name.");
            if (checkQueries && !settings.getDatasources().containsKey(sql.db))
                throw new ToolException("Unknown datasource for query " + sql.id + ": " + sql.db);
            if (sql.timeout <= 0 || sql.fetchSize <= 0 || sql.maxRows < 0)
                throw new ToolException("Invalid timeout, fetchSize or maxRows for " + sql.id);
            if (checkQueries && !sql.tempTables && !settings.getDatasources().get(sql.db).isReadOnly())
                throw new ToolException("Datasource " + sql.db + " must have read-only=true.");
            if (checkQueries) SqlGuard.validate(sql.text);
            if (checkQueries) TempTablePolicy.validate(sql, settings);
            queries.put(sql.id, sql);
        }
        // Inputs from earlier queries: declared aliases, existing targets, no cycles. Structure only; the values arrive at run time.
        Map<String, Set<String>> needs = new LinkedHashMap<>();
        Set<String> comparisonIds = new LinkedHashSet<>();
        if (rules.comparisons == null) rules.comparisons = new ArrayList<>();
        rules.comparisons.forEach(c -> comparisonIds.add(c.id));
        for (Rules.Sql sql : rules.queries) needs.put(sql.id, Chaining.needs(sql, queries.keySet(), comparisonIds));
        // a comparison waits for its two queries, so a query that uses its rows must not (indirectly) feed it
        for (Rules.Compare c : rules.comparisons) needs.put("cmp:" + c.id, new LinkedHashSet<>(Arrays.asList(c.source, c.target)));
        String cycle = Chaining.cycle(needs);
        if (cycle != null) throw new ToolException("Queries use each other's results in a circle: " + cycle + ".");
        for (Rules.Sql sql : rules.queries) if (sql.listBatch < 1 || sql.listBatch > 30_000)
            throw new ToolException("Query " + sql.id + ": listBatch must be between 1 and 30000.");
        if (rules.comparisons == null) rules.comparisons = new ArrayList<>();
        for (Rules.Compare compare : rules.comparisons) if ("db".equals(compare.engine)) {
            if (rules.queries.stream().anyMatch(q -> needs.getOrDefault(q.id, Set.of()).contains("cmp:" + compare.id)))
                throw new ToolException("Comparison " + compare.id + " (engine=db) keeps only counts and samples, so a query cannot use its unmatched rows. Use the stream engine for it.");
            for (String inputId : new String[]{compare.source, compare.target}) {
                if (needs.containsKey(inputId) && !needs.get(inputId).isEmpty())
                    throw new ToolException("Comparison " + compare.id + " (engine=db): query " + inputId + " uses the result of another query, which engine=db cannot do.");
                if (needs.entrySet().stream().filter(e -> !e.getKey().startsWith("cmp:")).anyMatch(e -> e.getValue().contains(inputId)))
                    throw new ToolException("Comparison " + compare.id + " (engine=db): query " + inputId + " is an input of the database comparison and cannot also feed another query.");
            }
        }
        for (Rules.Compare compare : rules.comparisons) {
            id(compare.id, ids);
            ExportMode.resolve(compare.export, ExportMode.SAMPLE);
            if (compare.breakdowns == null) compare.breakdowns = new ArrayList<>();
            for (Rules.Breakdown breakdown : compare.breakdowns) {
                if (breakdown.columns == null || breakdown.columns.isBlank() || breakdown.top < 1 || breakdown.top > 100
                    || Arrays.stream(breakdown.columns.split(",", -1)).anyMatch(String::isBlank))
                    throw new ToolException("Breakdown needs nonempty columns and top between 1 and 100: " + compare.id);
            }
            if (!queries.containsKey(compare.source) || !queries.containsKey(compare.target))
                throw new ToolException("Comparison '" + compare.id + "' references unknown SQL id(s): source='"
                    + compare.source + "', target='" + compare.target + "'. Available SQL ids: " + new TreeSet<>(queries.keySet()));
            List<String> keys = keys(compare);
            DbComparisonRunner.validate(compare, queries.get(compare.source), queries.get(compare.target), settings, checkQueries);
            if (keys.isEmpty() || keys.stream().anyMatch(String::isBlank)
                || keys.stream().map(k -> k.toLowerCase(Locale.ROOT)).distinct().count() != keys.size())
                throw new ToolException("Comparison " + compare.id + " needs distinct, nonempty key columns.");
            Set<String> columns = new HashSet<>();
            if (compare.columns == null) compare.columns = new ArrayList<>();
            for (Rules.Column column : compare.columns) {
                if (column.name == null || column.name.isBlank() || !columns.add(column.name.toLowerCase(Locale.ROOT)))
                    throw new ToolException("Comparison " + compare.id + " has missing/duplicate column names.");
                if (column.absTol == null || column.relTol == null || column.absTol.signum() < 0 || column.relTol.signum() < 0
                    || (column.round != null && (column.round < 0 || column.round > 30)))
                    throw new ToolException("Invalid numeric rules in " + compare.id + ".");
                if (column.ignore && keys.stream().anyMatch(k -> k.equalsIgnoreCase(column.name)))
                    throw new ToolException("Key column cannot be ignored: " + column.name);
            }
        }
        if (rules.funnels == null) rules.funnels = new ArrayList<>();
        for (Rules.Funnel funnel : rules.funnels) FunnelRunner.validate(funnel, queries, ids);
        Set<String> includable = new HashSet<>(queries.keySet());
        rules.comparisons.forEach(c -> includable.add(c.id));
        rules.funnels.forEach(f -> includable.add(f.id));
        Set<String> zipNames = new HashSet<>();
        if (rules.compressions == null) rules.compressions = new ArrayList<>();
        for (Rules.Compress zip : rules.compressions) {
            id(zip.id, ids);
            if (zip.file == null || !zip.file.matches("[A-Za-z0-9][A-Za-z0-9_.-]*\\.zip") || !zipNames.add(zip.file.toLowerCase(Locale.ROOT)))
                throw new ToolException("Compression needs a unique simple .zip filename: " + zip.id);
            if (zip.includes == null || zip.includes.isEmpty()) throw new ToolException("Compression needs <include ref=\"...\"/>.");
            for (Rules.Include include : zip.includes) if (!includable.contains(include.ref))
                throw new ToolException("Compression '" + zip.id + "' references unknown SQL/comparison id '"
                    + include.ref + "'. Available ids: " + new TreeSet<>(includable));
        }
        validateExpressions(rules);
        Expectations.validate(rules, queries.keySet());
        Set<String> outputs = new HashSet<>(Set.of("findings.md", "expectations.md", "summary.md", "parameters.csv", "rules.xml", "error.md", "manual_actions.md", "manual", ".work"));
        for (Rules.Sql query : rules.queries) for (String suffix : List.of(".csv", ".sql", ".md"))
            outputName(query.id + suffix, outputs);
        for (Rules.Compare compare : rules.comparisons) outputName(compare.id, outputs);
        for (Rules.Funnel funnel : rules.funnels) outputName(funnel.id, outputs);
        for (Rules.Compress zip : rules.compressions) outputName(zip.file, outputs);
        Map<String, Object> values = parameters(rules, Map.of());
        if (checkQueries) rules.queries.forEach(q -> {
            Map<String, Object> withInputs = new HashMap<>(values);
            withInputs.putAll(Chaining.placeholders(q));
            QueryRunner.validateParameters(q, withInputs);
        });
    }

    /** Syntax and allowed constructs of every expression, without evaluating anything. */
    static void validateExpressions(Rules rules) {
        List<String> seen = new ArrayList<>();
        for (Rules.Param p : rules.parameters) {
            if (p.expr != null) {
                if (p.value != null) throw new ToolException("Parameter " + p.name + " has both value= and expr=; use one.");
                Expressions.parse(p.expr, "Parameter " + p.name, seen, false);
            }
            seen.add(p.name);
        }
        int n = 0;
        for (Rules.Check check : rules.checks) {
            n++;
            Expressions.parse(check.expr, "<check> number " + n, seen, false);
        }
        for (Rules.Expect e : rules.expectations) {
            if (e.expr != null) Expressions.parse(e.expr, "<expect expr=\"" + Menu.shorten(e.expr, 40) + "\">", seen, true);
            if (e.when != null) Expressions.parse(e.when, "<expect when=\"" + Menu.shorten(e.when, 40) + "\">", seen, true);
        }
    }

    /** Evaluates the checks for the parameter values of this run; the first one that does not hold stops the run. */
    public static void checks(Rules rules, Map<String, Object> values) {
        int n = 0;
        for (Rules.Check check : rules.checks) {
            n++;
            String where = "<check> number " + n;
            var expression = Expressions.parse(check.expr, where, values.keySet(), false);
            boolean holds = Expressions.bool(Expressions.evaluate(expression, Expressions.context(values, null), where), where, check.expr);
            if (!holds) throw new ToolException("Check failed: " + (check.message == null || check.message.isBlank() ? check.expr : check.message)
                + "  [" + check.expr + "; " + values.entrySet().stream().filter(e -> check.expr.contains("#" + e.getKey())).map(e -> e.getKey() + "=" + Expressions.friendly(e.getValue())).toList() + "]");
        }
    }

    public static List<String> keys(Rules.Compare compare) {
        if (compare.key == null || compare.key.columns == null) return List.of();
        return Arrays.stream(compare.key.columns.split(",", -1)).map(String::strip).toList();
    }
    private static void outputName(String name, Set<String> names) {
        if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
            || !names.add(name.toLowerCase(Locale.ROOT)))
            throw new ToolException("Conflicting/reserved output filename: " + name);
    }
    static void id(String id, Set<String> ids) {
        Set<String> reserved = Set.of("summary", "parameters", "rules", "error", "expectations", "expectations.md", "findings", "findings.md", "rules.xml", "parameters.csv", "summary.md", "error.md");
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")
            || id.endsWith(".") || id.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
            || reserved.contains(id.toLowerCase(Locale.ROOT)) || !ids.add(id.toLowerCase(Locale.ROOT)))
            throw new ToolException("Rule ids must be unique safe filenames (case-insensitive): " + id);
    }

    public static Map<String, Object> parameters(Rules rules, Map<String, String> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> declared = new HashSet<>();
        if (rules.parameters != null) for (Rules.Param p : rules.parameters) {
            if (p.name == null || !p.name.matches("[A-Za-z_][A-Za-z0-9_]*") || !declared.add(p.name))
                throw new ToolException("Parameter names must be valid and unique.");
            String value;
            if (overrides.containsKey(p.name)) value = overrides.get(p.name);
            else if (p.expr != null) {
                if (p.value != null) throw new ToolException("Parameter " + p.name + " has both value= and expr=; use one.");
                String where = "Parameter " + p.name;
                var expression = Expressions.parse(p.expr, where, values.keySet(), false);
                value = Expressions.text(Expressions.evaluate(expression, Expressions.context(values, null), where));
                if (value == null) throw new ToolException(where + ": the expression gave nothing (null): " + p.expr);
            } else value = p.value;
            try {
                values.put(p.name, switch (p.type.toLowerCase(Locale.ROOT)) {
                    case "string" -> value;
                    case "integer" -> Long.valueOf(value);
                    case "decimal" -> new BigDecimal(value);
                    case "date" -> Date.valueOf(value);
                    case "timestamp" -> Timestamp.valueOf(value);
                    case "boolean" -> {
                        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) throw new IllegalArgumentException();
                        yield Boolean.valueOf(value);
                    }
                    case "null" -> null;
                    default -> throw new IllegalArgumentException();
                });
            } catch (Exception e) { throw new ToolException("Invalid value/type for parameter " + p.name + "."); }
        }
        for (String name : overrides.keySet()) if (!declared.contains(name))
            throw new ToolException("Undeclared parameter override: " + name);
        return values;
    }
}
