package com.taomish.comparedbtool;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Everything the menu has chosen for one run; turns into the same Options the command line would build. */
final class RunPlan {
    enum Mode { RUN, VALIDATE, PREVIEW, APPLY }

    final RuleCatalog.Entry entry;
    Mode mode;
    final Map<String, String> params = new LinkedHashMap<>();
    ExportMode export = ExportMode.SAMPLE;
    int sampleRows = 200, parallel;
    long limit;
    boolean allowProd;

    RunPlan(RuleCatalog.Entry entry) {
        this.entry = entry;
        this.mode = defaultMode(entry.kind());
    }

    static Mode defaultMode(RuleCatalog.Kind kind) {
        return kind == RuleCatalog.Kind.COMPARE || kind == RuleCatalog.Kind.TEMPLATE_EXPORT ? Mode.RUN : Mode.PREVIEW;
    }

    Cli.Options toOptions(Path out) {
        Cli.Options o = new Cli.Options();
        o.out = out;
        o.allowProd = allowProd;
        o.validate = mode == Mode.VALIDATE;
        o.apply = mode == Mode.APPLY;
        switch (entry.kind()) {
            case COMPARE -> {
                o.rules = entry.path();
                o.parameters.putAll(params);
                o.export = export; o.sampleRows = sampleRows; o.parallel = parallel; o.limit = limit;
            }
            case RESTORE -> { o.restore = entry.path(); o.export = export; o.sampleRows = sampleRows; }
            case TEMPLATE_IMPORT -> o.templateUpload = entry.path();
            case TEMPLATE_EXPORT -> o.templateExport = entry.path();
        }
        return o;
    }

    /** The equivalent command line, to show before running and to paste into notes or scripts. */
    String commandLine() {
        StringBuilder c = new StringBuilder(".\\compare.ps1");
        switch (entry.kind()) {
            case COMPARE -> c.append(" --rules ").append(quote(entry.path().toString()));
            case RESTORE -> c.append(" --restore ").append(quote(entry.path().toString()));
            case TEMPLATE_IMPORT -> c.append(" --template-import ").append(quote(entry.path().toString()));
            case TEMPLATE_EXPORT -> c.append(" --template-export ").append(quote(entry.path().toString()));
        }
        if (entry.kind() == RuleCatalog.Kind.COMPARE) {
            params.forEach((k, v) -> c.append(" --param ").append(quote(k + "=" + v)));
            if (export != ExportMode.SAMPLE) c.append(" --export ").append(export.name().toLowerCase());
            if (sampleRows != 200) c.append(" --sample-rows ").append(sampleRows);
            if (parallel > 0) c.append(" --parallel ").append(parallel);
            if (limit > 0) c.append(" --limit ").append(limit);
        }
        if (mode == Mode.VALIDATE) c.append(" --validate");
        if (mode == Mode.APPLY) c.append(" --apply");
        if (allowProd) c.append(" --allow-prod");
        return c.toString();
    }

    private static String quote(String text) { return text.matches("[A-Za-z0-9_.:=\\\\/-]+") ? text : "\"" + text + "\""; }

    String modeLabel() {
        return switch (mode) {
            case RUN -> entry.kind() == RuleCatalog.Kind.TEMPLATE_EXPORT ? "export" : "run";
            case VALIDATE -> "validate only";
            case PREVIEW -> "preview";
            case APPLY -> "APPLY";
        };
    }
}
