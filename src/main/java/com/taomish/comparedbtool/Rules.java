package com.taomish.comparedbtool;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonMerge;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import tools.jackson.dataformat.xml.annotation.JacksonXmlText;

/** XML contract. Public fields deliberately keep the rule model easy to extend. */
@JacksonXmlRootElement(localName = "rules")
public class Rules {
    @JacksonXmlProperty(isAttribute = true) public String name = "comparison";
    @JacksonXmlElementWrapper(localName = "parameters")
    @JacksonXmlProperty(localName = "param") public List<Param> parameters = new ArrayList<>();
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "sql") public List<Sql> queries = new ArrayList<>();
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "compare") public List<Compare> comparisons = new ArrayList<>();
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "compress") public List<Compress> compressions = new ArrayList<>();
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "expect") public List<Expect> expectations = new ArrayList<>();
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "funnel") public List<Funnel> funnels = new ArrayList<>();

    public static class Param {
        @JacksonXmlProperty(isAttribute = true) public String name;
        @JacksonXmlProperty(isAttribute = true) public String value;
        /** string, integer, decimal, date, timestamp, boolean, null */
        @JacksonXmlProperty(isAttribute = true) public String type = "string";
    }
    public static class Sql {
        @JacksonXmlProperty(isAttribute = true) public String id;
        @JacksonXmlProperty(isAttribute = true) public String db;
        @JacksonXmlProperty(isAttribute = true) public int timeout = 120;
        @JacksonXmlProperty(isAttribute = true) public int fetchSize = 1000;
        @JacksonXmlProperty(isAttribute = true) public long maxRows;
        @JacksonXmlProperty(isAttribute = true) public boolean tempTables;
        /** Inputs from earlier queries: "alias=query-id, other"; the SQL refers to :alias.column or :alias.column[]. */
        @JacksonXmlProperty(isAttribute = true) public String uses;
        /** Longest list of values per statement taken from another query; longer lists run in batches. */
        @JacksonXmlProperty(isAttribute = true) public int listBatch = 1000;
        @JacksonXmlProperty(isAttribute = true) public String export;
        @JacksonXmlText public String text;
    }
    public static class Compare {
        @JacksonXmlProperty(isAttribute = true) public String engine = "stream";
        @JacksonXmlProperty(isAttribute = true) public String id;
        @JacksonXmlProperty(isAttribute = true) public String source;
        @JacksonXmlProperty(isAttribute = true) public String target;
        @JacksonXmlProperty(isAttribute = true) public String export;
        public Key key;
        @JacksonXmlElementWrapper(useWrapping = false)
        @JsonMerge @JacksonXmlProperty(localName = "column") public List<Column> columns = new ArrayList<>();
        @JacksonXmlElementWrapper(useWrapping = false)
        @JsonMerge @JacksonXmlProperty(localName = "breakdown") public List<Breakdown> breakdowns = new ArrayList<>();
    }
    public static class Breakdown {
        @JacksonXmlProperty(isAttribute = true) public String columns;
        @JacksonXmlProperty(isAttribute = true) public int top = 15;
    }
    public static class Key {
        @JacksonXmlProperty(isAttribute = true) public String columns;
    }
    public static class Column {
        @JacksonXmlProperty(isAttribute = true) public String name;
        @JacksonXmlProperty(isAttribute = true) public String source;
        @JacksonXmlProperty(isAttribute = true) public String target;
        @JacksonXmlProperty(isAttribute = true) public boolean ignore;
        @JacksonXmlProperty(isAttribute = true) public boolean trim;
        @JacksonXmlProperty(isAttribute = true) public boolean caseInsensitive;
        @JacksonXmlProperty(isAttribute = true) public boolean nullAsEmpty;
        @JacksonXmlProperty(isAttribute = true) public boolean nullAsZero;
        @JacksonXmlProperty(isAttribute = true) public boolean dateOnly;
        @JacksonXmlProperty(isAttribute = true) public boolean numeric;
        @JacksonXmlProperty(isAttribute = true) public Integer round;
        @JacksonXmlProperty(isAttribute = true) public BigDecimal absTol = BigDecimal.ZERO;
        @JacksonXmlProperty(isAttribute = true) public BigDecimal relTol = BigDecimal.ZERO;
    }
    public static class Compress {
        @JacksonXmlProperty(isAttribute = true) public String id;
        @JacksonXmlProperty(isAttribute = true) public String file;
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "include") public List<Include> includes = new ArrayList<>();
    }
    public static class Include {
        @JacksonXmlProperty(isAttribute = true) public String ref;
    }

    /**
     * What the rule expects. Query checks (rows, column + equals/min/max/between/isNull) and comparison checks
     * (matched, equal, different, only-source, only-target, duplicates) use the same element; {@code ref} says which.
     * Count attributes accept N, &lt;=N, &gt;=N, &lt;N, &gt;N or A..B.
     */
    public static class Expect {
        @JacksonXmlProperty(isAttribute = true) public String ref;
        @JacksonXmlProperty(isAttribute = true) public String reason;
        // query ref
        @JacksonXmlProperty(isAttribute = true) public String rows;
        @JacksonXmlProperty(isAttribute = true) public String column;
        @JacksonXmlProperty(isAttribute = true) public Integer row;
        @JacksonXmlProperty(isAttribute = true) public Boolean each;
        @JacksonXmlProperty(isAttribute = true) public String equals;
        @JacksonXmlProperty(isAttribute = true) public String min;
        @JacksonXmlProperty(isAttribute = true) public String max;
        @JacksonXmlProperty(isAttribute = true) public String between;
        @JacksonXmlProperty(isAttribute = true) public Boolean isNull;
        // comparison ref
        @JacksonXmlProperty(isAttribute = true) public String matched;
        @JacksonXmlProperty(isAttribute = true) public String equal;
        @JacksonXmlProperty(isAttribute = true) public String different;
        @JacksonXmlProperty(isAttribute = true, localName = "only-source") public String onlySource;
        @JacksonXmlProperty(isAttribute = true, localName = "only-target") public String onlyTarget;
        @JacksonXmlProperty(isAttribute = true) public String duplicates;
        // funnel ref: a stage id, or "none"; and the number of keys that diverge (or, in a chain, drop out)
        @JacksonXmlProperty(isAttribute = true, localName = "first-divergence") public String firstDivergence;
        @JacksonXmlProperty(isAttribute = true) public String diverging;
    }

    /** Ordered stages, each a pair of queries returning the keys that survive it. mode: compare (two sides) or chain (one side). */
    public static class Funnel {
        @JacksonXmlProperty(isAttribute = true) public String id;
        @JacksonXmlProperty(isAttribute = true) public String key;
        @JacksonXmlProperty(isAttribute = true) public String mode = "compare";
        /** chain only: a key column whose values get a side-by-side table (for example outcome = FAIL / OK). */
        @JacksonXmlProperty(isAttribute = true) public String group;
        @JacksonXmlProperty(isAttribute = true) public String export;
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "stage") public List<Stage> stages = new ArrayList<>();
    }
    public static class Stage {
        @JacksonXmlProperty(isAttribute = true) public String id;
        @JacksonXmlProperty(isAttribute = true) public String source;
        @JacksonXmlProperty(isAttribute = true) public String target;
        @JacksonXmlProperty(isAttribute = true) public String reason;
    }
}
