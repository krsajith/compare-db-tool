package com.taomish.comparedbtool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative lexical guard, complemented by JDBC/session read-only settings and DB permissions. */
public final class SqlGuard {
    private SqlGuard() {}
    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT",
        "REPLACE", "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE",
        "COPY", "LOAD", "INTO", "SET", "RESET", "COMMIT", "ROLLBACK", "BEGIN", "DO", "LOCK", "VACUUM",
        "ANALYZE", "ATTACH", "DETACH", "PRAGMA", "OUTFILE", "DUMPFILE", "NEXTVAL", "SETVAL",
        "PG_ADVISORY_LOCK", "PG_ADVISORY_XACT_LOCK", "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND",
        "DBLINK_EXEC", "LO_IMPORT", "LO_EXPORT", "GET_LOCK", "RELEASE_LOCK");

    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) throw new ToolException("SQL cannot be empty.");
        List<String> words = new ArrayList<>();
        boolean ended = false;
        int n = sql.length();
        for (int i = 0; i < n;) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (i + 1 < n && sql.startsWith("--", i)) {
                if (i + 2 < n && !Character.isWhitespace(sql.charAt(i + 2)))
                    throw new ToolException("Use whitespace after -- comments for cross-database safety.");
                int end = sql.indexOf('\n', i + 2); i = end < 0 ? n : end + 1; continue;
            }
            if (i + 1 < n && sql.startsWith("/*", i)) {
                if (sql.startsWith("/*!", i) || sql.startsWith("/*M!", i) || sql.startsWith("/*m!", i))
                    throw new ToolException("Executable SQL comments are unsupported.");
                int depth = 1; i += 2;
                while (i < n && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        if (sql.startsWith("/*!", i) || sql.startsWith("/*M!", i) || sql.startsWith("/*m!", i))
                            throw new ToolException("Executable SQL comments are unsupported.");
                        throw new ToolException("Nested SQL comments are unsupported across database dialects.");
                    }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw new ToolException("Unterminated SQL comment.");
                continue;
            }
            if (ended) throw new ToolException("Only a single SELECT/WITH statement is allowed.");
            if (c == ';') { ended = true; i++; continue; }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c; i++;
                boolean closed = false;
                while (i < n) {
                    if (sql.charAt(i) == '\\') {
                        // Backslash quoting differs across vendors/session modes: reject ambiguity.
                        throw new ToolException("Backslash SQL quoting is unsupported; use bound parameters.");
                    }
                    if (sql.charAt(i++) == close) {
                        if (i < n && sql.charAt(i) == close) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw new ToolException("Unterminated SQL string or identifier.");
                continue;
            }
            if (c == '$') {
                throw new ToolException("Dollar-quoted SQL is unsupported across database dialects; use bound parameters.");
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                // Names after ':' are bind parameters, except PostgreSQL '::' casts.
                boolean parameter = start > 0 && sql.charAt(start - 1) == ':'
                    && (start < 2 || sql.charAt(start - 2) != ':');
                if (!parameter) words.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else i++;
        }
        if (words.isEmpty() || !(words.getFirst().equals("SELECT") || words.getFirst().equals("WITH")))
            throw new ToolException("Only SELECT/WITH queries are supported.");
        for (String word : words) if (FORBIDDEN.contains(word))
            throw new ToolException("SQL contains a disallowed operation: " + word);
        return sql.strip().replaceFirst(";\\s*$", "");
    }
}
