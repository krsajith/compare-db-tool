package com.taomish.comparedbtool;

import java.util.Locale;

public enum ExportMode {
    NONE, SAMPLE, FULL;
    public static ExportMode parse(String value) {
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw new ToolException("Export mode must be none, sample or full."); }
    }
    public static ExportMode resolve(String override, ExportMode fallback) {
        return override == null || override.isBlank() ? fallback : parse(override);
    }
}
