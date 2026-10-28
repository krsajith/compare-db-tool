package com.taomish.comparedbtool;

import com.taomish.comparedbtool.logging.RedactingPatternLayoutEncoder;
import com.taomish.comparedbtool.logging.SecretRedactor;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingTests {
    @Test void redactsKnownSecretsJdbcUrlsAndNamedTokens() {
        String testCredential = "sample-only-credential-7342";
        SecretRedactor.register(testCredential);
        String rendered = SecretRedactor.clean("Connection failed: " + testCredential
            + " jdbc:postgresql://user:example@localhost/db?password=hidden password=unknown access_token=another");
        assertFalse(rendered.contains(testCredential));
        assertFalse(rendered.contains("user:example"));
        assertFalse(rendered.contains("unknown"));
        assertFalse(rendered.contains("another"));
        assertTrue(rendered.contains("Connection failed"));
    }

    @Test void retainsFullCauseStackWhileRedactingNestedExceptionMessages() {
        String testCredential = "sample-nested-credential-8641";
        SecretRedactor.register(testCredential);
        LoggerContext context = new LoggerContext();
        try {
            RedactingPatternLayoutEncoder encoder = new RedactingPatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setCharset(StandardCharsets.UTF_8);
            encoder.setPattern("%level %msg%n%ex{full}");
            encoder.start();
            Exception cause = new IllegalArgumentException("Invalid XML near line 12 " + testCredential);
            Exception failure = new IllegalStateException("Cannot load rule", cause);
            LoggingEvent event = new LoggingEvent(getClass().getName(), context.getLogger("test"), Level.ERROR,
                "Loading rules failed", failure, null);
            String rendered = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertTrue(rendered.contains("IllegalStateException"));
            assertTrue(rendered.contains("Caused by:"));
            assertTrue(rendered.contains("Invalid XML near line 12"));
            assertFalse(rendered.contains(testCredential));
            encoder.stop();
        } finally { context.stop(); }
    }

    @Test void toolExceptionPreservesTheOriginalCauseAndLoggingFlagsAreAccepted() {
        Exception cause = new IllegalArgumentException("parser detail");
        assertSame(cause, new ToolException("Cannot read rules", cause).getCause());
        assertDoesNotThrow(() -> Cli.parse(new String[] {"rules.xml", "--validate",
            "--logging.level.com.taomish.comparedbtool=DEBUG", "--logging.file.path=D:/logs"}));
    }
}
