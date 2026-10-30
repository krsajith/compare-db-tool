package com.taomish.comparedbtool;

import java.util.*;
import java.util.regex.Pattern;

/** Opt-in policy checked both during validation and immediately before execution. */
final class TempTablePolicy {
    private TempTablePolicy() {}
    private static final Pattern TOKENS = Pattern.compile(
        "--[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`(?:``|[^`])*`|\\[(?:]]|[^]])*]|[A-Za-z_][A-Za-z_0-9$]*|[^\\s]");

    static void validate(Rules.Sql rule, DatasourceSettings settings) {
        if (!rule.tempTables) return;
        SqlGuard.validate(rule.text);
        var definition = settings.getDatasources().get(rule.db);
        if (definition == null) throw new ToolException("Unknown datasource: " + rule.db);
        if (QueryRunner.production(rule.db, definition))
            throw new ToolException("Temp-table queries are forbidden on production datasource " + rule.db + ".");
        if (!definition.isAllowTempTables())
            throw new ToolException("Datasource " + rule.db + " requires allow-temp-tables=true for query " + rule.id + ".");
        if (definition.getUrl() == null || !(definition.getUrl().startsWith("jdbc:postgresql:") || definition.getUrl().startsWith("jdbc:h2:")))
            throw new ToolException("Temp-table mode supports PostgreSQL (and H2 tests) only: " + rule.db);
        String allowed = definition.getAllowedFunctions();
        if (allowed == null) allowed = "*";
        List<String> patterns = Arrays.stream(allowed.split(",")).map(String::strip).toList();
        if (patterns.contains("*")) return;
        List<String> tokens = new ArrayList<>();
        var matcher = TOKENS.matcher(rule.text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("--") || token.startsWith("/*")) continue;
            tokens.add(token);
        }
        for (int i = 0; i + 3 < tokens.size(); i++) {
            if (!identifier(tokens.get(i)) || !tokens.get(i + 1).equals(".") || !identifier(tokens.get(i + 2))) continue;
            int end = i + 2;
            StringBuilder name = new StringBuilder(unquote(tokens.get(i))).append('.').append(unquote(tokens.get(end)));
            while (end + 2 < tokens.size() && tokens.get(end + 1).equals(".") && identifier(tokens.get(end + 2))) {
                end += 2; name.append('.').append(unquote(tokens.get(end)));
            }
            if (end + 1 < tokens.size() && tokens.get(end + 1).equals("(")) {
                String function = name.toString();
                if (patterns.stream().noneMatch(p -> matches(p, function)))
                    throw new ToolException("Function " + function + " is not in allowed-functions for datasource " + rule.db + ".");
            }
            i = end;
        }
    }

    private static boolean identifier(String token) {
        return token.matches("[A-Za-z_][A-Za-z_0-9$]*") || token.startsWith("\"") || token.startsWith("`") || token.startsWith("[");
    }
    private static String unquote(String token) {
        if (token.startsWith("\"") || token.startsWith("`") || token.startsWith("[")) {
            String close = token.substring(token.length() - 1);
            return token.substring(1, token.length() - 1).replace(close + close, close);
        }
        return token.toLowerCase(Locale.ROOT);
    }
    private static boolean matches(String glob, String name) {
        String regex = Arrays.stream(glob.split("\\*", -1)).map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
        return name.matches(regex);
    }
}
