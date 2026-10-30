package com.taomish.comparedbtool;

import java.util.LinkedHashSet;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/** Inspect origins, never connection values; includes additional imported configuration files. */
final class DatasourceDiagnostics {
    private DatasourceDiagnostics() {}
    static void capture(ConfigurableEnvironment environment, DatasourceSettings settings) {
        var files = new LinkedHashSet<String>();
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String key : enumerable.getPropertyNames()) {
                if (!key.startsWith("compare.datasources.")) continue;
                var origin = OriginLookup.getOrigin(source, key);
                if (origin instanceof TextResourceOrigin text && text.getResource().isFile()) {
                    try { files.add(text.getResource().getFile().toPath().toAbsolutePath().normalize().toString()); }
                    catch (java.io.IOException error) { throw new ToolException("Cannot resolve datasource configuration file path.", error); }
                }
            }
        }
        settings.datasourceFiles(java.util.List.copyOf(files));
    }
}
