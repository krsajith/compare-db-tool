package com.taomish.comparedbtool.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.Set;

/**
 * Console threshold. Everything stays in the log files; the console shows this level and above, plus INFO from the classes whose log
 * lines are their user-facing output (template import/export), because those modes print through the logger.
 */
public class ConsoleLevelFilter extends Filter<ILoggingEvent> {
    private static final Set<String> TALKATIVE = Set.of("com.taomish.comparedbtool.TemplateUploader", "com.taomish.comparedbtool.TemplateExporter");
    private Level level = Level.WARN;

    public void setLevel(String text) { this.level = Level.toLevel(text, Level.WARN); }

    @Override public FilterReply decide(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(level)) return FilterReply.NEUTRAL;
        if (event.getLevel().isGreaterOrEqual(Level.INFO) && TALKATIVE.contains(event.getLoggerName())) return FilterReply.NEUTRAL;
        return FilterReply.DENY;
    }
}
