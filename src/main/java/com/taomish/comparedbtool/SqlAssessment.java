package com.taomish.comparedbtool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Position-preserving lexical findings. This is not an execution authorization or a SQL parser. */
public record SqlAssessment(Level level, List<Finding> findings) {
    public enum Level { OK, WARN, DANGER }
    public record Finding(Level level, String word, int line, int column, String context, String reason) {}
    private record Token(String text, int offset) {}
    private static final Set<String> SET_OPERATORS = Set.of("UNION", "EXCEPT", "INTERSECT", "MINUS");
    private static final Pattern DOLLAR = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$");
    private static final Set<String> EFFECT_FUNCTIONS = Set.of("NEXTVAL", "SETVAL", "PG_ADVISORY_LOCK",
        "PG_ADVISORY_XACT_LOCK", "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND", "DBLINK_EXEC",
        "LO_IMPORT", "LO_EXPORT", "GET_LOCK", "RELEASE_LOCK");

    public SqlAssessment { findings = List.copyOf(findings); }

    static SqlAssessment scan(String sql) {
        if (sql == null || sql.isBlank()) throw new ToolException("SQL cannot be empty.");
        List<Finding> findings = new ArrayList<>();
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (sql.startsWith("--", i)) {
                if (i + 2 < sql.length() && !Character.isWhitespace(sql.charAt(i + 2)))
                    add(findings, sql, i, Level.WARN, "--", "Comment without whitespace has dialect-dependent meaning.");
                int end = sql.indexOf('\n', i + 2); i = end < 0 ? sql.length() : end + 1; continue;
            }
            if (sql.startsWith("/*", i)) {
                int start = i, depth = 1;
                executable(findings, sql, i); i += 2;
                while (i < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        add(findings, sql, i, Level.WARN, "/*", "Nested comments have dialect-dependent meaning.");
                        executable(findings, sql, i); depth++; i += 2;
                    } else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) add(findings, sql, start, Level.DANGER, "/*", "Unterminated comment; statement boundaries are uncertain.");
                continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                int start = i++; char close = c == '[' ? ']' : c;
                boolean closed = false;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\\') {
                        add(findings, sql, i, Level.WARN, "\\", "Backslash escaping depends on database/session settings.");
                        i = Math.min(sql.length(), i + 2); continue;
                    }
                    if (sql.charAt(i++) == close) {
                        if (i < sql.length() && sql.charAt(i) == close) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) add(findings, sql, start, Level.DANGER, String.valueOf(c), "Unterminated quote; statement boundaries are uncertain.");
                // Preserve an expression placeholder so SELECT 'x' INTO ... remains distinguishable.
                tokens.add(new Token("<QUOTED>", start)); continue;
            }
            if (c == '$') {
                var matcher = DOLLAR.matcher(sql).region(i, sql.length());
                if (matcher.lookingAt()) {
                    String tag = matcher.group(); int end = sql.indexOf(tag, matcher.end());
                    add(findings, sql, i, end < 0 ? Level.DANGER : Level.WARN, "$",
                        end < 0 ? "Unterminated dollar quote; statement boundaries are uncertain."
                            : "Dollar quoting is database-specific; contents are treated as a literal.");
                    tokens.add(new Token("<QUOTED>", i));
                    i = end < 0 ? sql.length() : end + tag.length(); continue;
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                boolean parameter = start > 0 && sql.charAt(start - 1) == ':'
                    && (start < 2 || sql.charAt(start - 2) != ':');
                tokens.add(new Token(parameter ? "<PARAM>" : sql.substring(start, i).toUpperCase(Locale.ROOT), start));
            } else { tokens.add(new Token(String.valueOf(c), i)); i++; }
        }
        int statements = 0;
        boolean start = true;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i); String word = t.text;
            if (word.equals(";")) { start = true; continue; }
            String previous = i > 0 ? tokens.get(i - 1).text : "";
            String next = i + 1 < tokens.size() ? tokens.get(i + 1).text : "";
            boolean statementStart = start || SET_OPERATORS.contains(previous)
                || (previous.equals("ALL") && i > 1 && SET_OPERATORS.contains(tokens.get(i - 2).text));
            if (start && word.equals("(")) continue; // a parenthesised SELECT: judge the word inside
            if (start) {
                statements++; start = false;
                if (!word.equals("SELECT") && !word.equals("WITH"))
                    add(findings, sql, t.offset, Level.DANGER, word, "Statement does not start with SELECT/WITH.");
            }
            if (word.equals("FOR") && (next.equals("SHARE") || next.equals("KEY") || next.equals("NO")))
                add(findings, sql, t.offset, Level.DANGER, word, "Locking read (FOR SHARE / FOR KEY SHARE / FOR NO KEY UPDATE) takes row locks.");
            if (!SqlGuard.flagged(word)) continue;
            boolean function = next.equals("(");
            boolean alias = previous.equals("AS") || previous.equals(".");
            boolean expression = previous.equals("SELECT") || previous.equals(",") || previous.equals("(")
                || previous.equals("=") || previous.equals("+") || previous.equals("-")
                || previous.equals("*") || previous.equals("/") || previous.equals("WHEN")
                || previous.equals("THEN") || previous.equals("ELSE") || previous.equals("BY")
                || previous.equals("WHERE") || previous.equals("AND") || previous.equals("OR")
                || previous.equals("ON") || previous.equals("HAVING") || previous.equals("NOT")
                || previous.equals("|") || previous.equals("<") || previous.equals(">") || previous.equals("%")
                || previous.equals("CASE") || previous.equals("DISTINCT") || previous.equals("BETWEEN") || previous.equals("LIKE")
                || previous.equals("ILIKE") || previous.equals("IN") || previous.equals("IS");
            // A table (or its alias) named like a keyword: FROM load, JOIN copy. A statement cannot start here.
            boolean tablePosition = previous.equals("FROM") || previous.equals("JOIN");
            // INTO immediately after SELECT or a comma can be an identifier; after an expression it writes a table/file.
            boolean columnEnd = next.equals(",") || (next.equals("FROM") && !previous.equals("(")) || next.equals("AS")
                || next.equals(";") || next.isEmpty() || next.equals(")") || next.equals("=") || next.equals(".");
            boolean cteBodyStart = previous.equals("(") && i > 1 && tokens.get(i - 2).text.equals("AS");
            boolean benignPosition = alias || (expression && (function || columnEnd)) || (tablePosition && !next.equals("INTO"));
            boolean danger = statementStart || (function && EFFECT_FUNCTIONS.contains(word))
                || cteBodyStart || (!benignPosition) || (word.equals("REPLACE") && next.equals("INTO"));
            add(findings, sql, t.offset, danger ? Level.DANGER : Level.WARN, word,
                danger ? "Looks like a statement, a write, a lock, file output or a side-effecting function; only plain reads are allowed."
                    : function ? "Flagged word used as a function; verify that the function has no side effects."
                    : "Flagged word in an identifier/expression position; verify its dialect-specific meaning.");
        }
        if (statements > 1) add(findings, sql, tokens.getFirst().offset, Level.WARN, ";",
            "Multiple statements; verify each statement and driver support.");
        if (statements == 0) add(findings, sql, 0, Level.DANGER, "SQL", "No SELECT/WITH statement found.");
        findings.sort(java.util.Comparator.comparingInt(Finding::line).thenComparingInt(Finding::column));
        Level level = findings.stream().map(Finding::level).max(java.util.Comparator.naturalOrder()).orElse(Level.OK);
        return new SqlAssessment(level, findings);
    }

    private static void executable(List<Finding> findings, String sql, int i) {
        if (sql.startsWith("/*!", i) || sql.startsWith("/*M!", i) || sql.startsWith("/*m!", i))
            add(findings, sql, i, Level.DANGER, "/*!", "Executable comment may run hidden statements on MySQL/MariaDB.");
    }

    private static void add(List<Finding> findings, String sql, int offset, Level level, String word, String reason) {
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) { if (sql.charAt(i) == '\n') { line++; column = 1; } else column++; }
        String context = sql.substring(Math.max(0, offset - 24), Math.min(sql.length(), offset + 48))
            .replace('\n', ' ').replace('\r', ' ').replace('|', '/');
        findings.add(new Finding(level, word, line, column, context, reason));
    }
}
