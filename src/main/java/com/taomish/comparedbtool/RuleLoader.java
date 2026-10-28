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

public final class RuleLoader {
    private RuleLoader() {}
    public static Rules load(Path path) {
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
                        switch (reader.getLocalName()) {
                            case "sql" -> rules.queries.add(mapper.readValue(reader, Rules.Sql.class));
                            case "compare" -> rules.comparisons.add(mapper.readValue(reader, Rules.Compare.class));
                            case "compress" -> rules.compressions.add(mapper.readValue(reader, Rules.Compress.class));
                            case "parameters" -> {
                                if (parametersSeen) throw new ToolException("Use only one <parameters> block.");
                                parametersSeen = true;
                                while (nextTag(reader) == XMLStreamConstants.START_ELEMENT) {
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
                    return rules;
                } finally { reader.close(); }
            }
        } catch (ToolException e) { throw e;
        } catch (Exception e) {
            throw new ToolException("Cannot read rules XML: " + path + " (" + e.getClass().getSimpleName()
                + "). Check XML syntax and supported elements.");
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
        if (rules.queries == null || rules.queries.isEmpty()) throw new ToolException("Rules need at least one <sql>.");
        Set<String> ids = new HashSet<>();
        Map<String, Rules.Sql> queries = new HashMap<>();
        for (Rules.Sql sql : rules.queries) {
            id(sql.id, ids);
            if (sql.db == null || !settings.getDatasources().containsKey(sql.db))
                throw new ToolException("Unknown datasource for query " + sql.id + ": " + sql.db);
            if (sql.timeout <= 0 || sql.fetchSize <= 0 || sql.maxRows < 0)
                throw new ToolException("Invalid timeout, fetchSize or maxRows for " + sql.id);
            if (!settings.getDatasources().get(sql.db).isReadOnly())
                throw new ToolException("Datasource " + sql.db + " must have read-only=true.");
            SqlGuard.validate(sql.text);
            queries.put(sql.id, sql);
        }
        if (rules.comparisons == null) rules.comparisons = new ArrayList<>();
        for (Rules.Compare compare : rules.comparisons) {
            id(compare.id, ids);
            if (!queries.containsKey(compare.source) || !queries.containsKey(compare.target))
                throw new ToolException("Comparison '" + compare.id + "' references unknown SQL id(s): source='"
                    + compare.source + "', target='" + compare.target + "'. Available SQL ids: " + new TreeSet<>(queries.keySet()));
            List<String> keys = keys(compare);
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
        Set<String> includable = new HashSet<>(queries.keySet());
        rules.comparisons.forEach(c -> includable.add(c.id));
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
        Set<String> outputs = new HashSet<>(Set.of("summary.md", "parameters.csv", "rules.xml", "error.md", ".work"));
        for (Rules.Sql query : rules.queries) for (String suffix : List.of(".csv", ".sql", ".md"))
            outputName(query.id + suffix, outputs);
        for (Rules.Compare compare : rules.comparisons) outputName(compare.id, outputs);
        for (Rules.Compress zip : rules.compressions) outputName(zip.file, outputs);
        Map<String, Object> values = parameters(rules, Map.of());
        rules.queries.forEach(q -> QueryRunner.validateParameters(q, values));
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
    private static void id(String id, Set<String> ids) {
        Set<String> reserved = Set.of("summary", "parameters", "rules", "error", "rules.xml", "parameters.csv", "summary.md", "error.md");
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
            String value = overrides.getOrDefault(p.name, p.value);
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
