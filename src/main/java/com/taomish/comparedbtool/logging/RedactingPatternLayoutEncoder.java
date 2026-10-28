package com.taomish.comparedbtool.logging;

import java.nio.charset.Charset;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Preserve Boot's patterns but redact the entire rendered event, stack trace included. */
public class RedactingPatternLayoutEncoder extends PatternLayoutEncoder {
    @Override public byte[] encode(ILoggingEvent event) {
        byte[] rendered = super.encode(event);
        if (rendered == null) return null;
        Charset charset = getCharset() == null ? Charset.defaultCharset() : getCharset();
        return SecretRedactor.clean(new String(rendered, charset)).getBytes(charset);
    }
}
