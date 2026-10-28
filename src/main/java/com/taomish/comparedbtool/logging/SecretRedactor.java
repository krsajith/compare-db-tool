package com.taomish.comparedbtool.logging;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.springframework.core.env.*;

/** Redacts rendered log output, including exception messages and nested causes. */
public final class SecretRedactor {
    private SecretRedactor() {}
    private static final List<String> SECRETS = new CopyOnWriteArrayList<>();
    private static final Pattern KEY = Pattern.compile("(?i).*(password|passwd|pwd|token|secret|credential|api[-_.]?key).*");
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[^\\s<>\"']+");
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)((?:password|passwd|pwd|access[-_]?token|refresh[-_]?token|secret|api[-_]?key)\\s*[=:]\\s*)"
        + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");

    public static synchronized void register(String value) {
        if (value == null || value.isEmpty() || value.contains("${") || SECRETS.contains(value)) return;
        SECRETS.add(value);
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        if (!encoded.equals(value) && !SECRETS.contains(encoded)) SECRETS.add(encoded);
        SECRETS.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
    }
    public static void registerEnvironment() {
        System.getenv().forEach((name, value) -> { if (KEY.matcher(name).matches()) register(value); });
        System.getProperties().forEach((name, value) -> {
            if (KEY.matcher(name.toString()).matches()) register(value.toString());
        });
    }
    public static void register(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) registerSource(environment, source);
    }
    private static void registerSource(ConfigurableEnvironment environment, PropertySource<?> source) {
        if (source instanceof CompositePropertySource composite) {
            composite.getPropertySources().forEach(s -> registerSource(environment, s));
        } else if (source instanceof EnumerablePropertySource<?> enumerable) {
            for (String name : enumerable.getPropertyNames()) if (KEY.matcher(name).matches()) {
                try { register(environment.getProperty(name)); }
                catch (IllegalArgumentException ignored) { /* unresolved placeholders are reported by binding */ }
            }
        }
    }
    public static String clean(String text) {
        if (text == null) return null;
        String result = text;
        for (String secret : SECRETS) result = result.replace(secret, "[REDACTED]");
        result = JDBC_URL.matcher(result).replaceAll("jdbc:[REDACTED]");
        result = ASSIGNMENT.matcher(result).replaceAll("$1[REDACTED]");
        return BEARER.matcher(result).replaceAll("$1[REDACTED]");
    }
}
