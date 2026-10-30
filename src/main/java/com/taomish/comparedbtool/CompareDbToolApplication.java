package com.taomish.comparedbtool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.taomish.comparedbtool.logging.SecretRedactor;
import java.io.PrintWriter;
import java.io.StringWriter;

// No JDBC autoconfiguration: named pools are created lazily only for referenced SQL rules.
@SpringBootConfiguration
@EnableConfigurationProperties(DatasourceSettings.class)
public class CompareDbToolApplication {
    private static final Logger LOG = LoggerFactory.getLogger(CompareDbToolApplication.class);

    public static void main(String[] args) {
        int code;
        SecretRedactor.registerEnvironment();
        try {
            Cli.Options options = Cli.parse(args);
            if (options.help) { System.out.println(Cli.HELP); return; }
            System.out.println("compare-db-tool: initializing configuration"
                + (options.validate ? " [validation only; no database connections]" : ""));
            System.out.flush();
            SpringApplication application = new SpringApplication(CompareDbToolApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setLogStartupInfo(true);
            application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                SecretRedactor.register(event.getEnvironment()));
            try (var context = application.run(args)) {
                DatasourceSettings settings = context.getBean(DatasourceSettings.class);
                settings.getDatasources().values().forEach(d -> SecretRedactor.register(d.getPassword()));
                DatasourceDiagnostics.capture(context.getEnvironment(), settings);
                settings.diagnostics().forEach(line -> LOG.info("{}", line));
                LOG.info("Runtime Java={} vendor={} Spring Boot={} workingDirectory={}",
                    System.getProperty("java.version"), System.getProperty("java.vendor"),
                    SpringBootVersion.getVersion(), System.getProperty("user.dir"));
                LOG.info("Configured datasources={} validationOnly={} partitions={}",
                    settings.getDatasources().keySet(), options.validate, options.partitions);
                try {
                    code = new Cli(settings).execute(options);
                } catch (Exception e) {
                    // Log while the application/logging context is still open.
                    logFailure("Execution failed. Full cause chain follows; secrets are redacted.", e);
                    System.err.println("ERROR: " + SecretRedactor.clean(e.getMessage())
                        + ". See the configured log directory (default logs/compare-db-tool-error.log).");
                    code = 2;
                }
                LOG.info("Tool finished with exit code {}", code);
            }
            System.out.println("compare-db-tool: finished (exit " + code + ").");
        } catch (ToolException e) {
            logFailure("Invalid command or tool configuration", e);
            System.err.println("ERROR: " + SecretRedactor.clean(e.getMessage()));
            code = 2;
        } catch (Exception e) {
            logFailure("Could not initialize compare-db-tool", e);
            System.err.println("ERROR: Could not initialize the tool. See logs/compare-db-tool-error.log and console details.");
            code = 2;
        }
        System.exit(code);
    }

    private static void logFailure(String message, Throwable failure) {
        // Redact before SLF4J too: startup can fail before our Logback encoder is installed.
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        LOG.error("{}\n{}", message, SecretRedactor.clean(stack.toString()));
    }

}
