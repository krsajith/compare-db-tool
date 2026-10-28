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
        @JacksonXmlProperty(isAttribute = true) public String export;
        @JacksonXmlText public String text;
    }
    public static class Compare {
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
}
