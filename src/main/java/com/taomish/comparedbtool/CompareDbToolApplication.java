package com.taomish.comparedbtool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// No JDBC autoconfiguration: named pools are created lazily only for referenced SQL rules.
@SpringBootConfiguration
@EnableConfigurationProperties(DatasourceSettings.class)
public class CompareDbToolApplication {

    public static void main(String[] args) {
        int code;
        try {
            Cli.Options options = Cli.parse(args);
            if (options.help) { System.out.println(Cli.HELP); return; }
            SpringApplication application = new SpringApplication(CompareDbToolApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setLogStartupInfo(false);
            try (var context = application.run(args)) {
                code = new Cli(context.getBean(DatasourceSettings.class)).execute(options);
            }
        } catch (ToolException e) {
            System.err.println("ERROR: " + e.getMessage());
            code = 2;
        } catch (Exception e) {
            // Never dump JDBC/configuration exceptions, which can include secrets.
            System.err.println("ERROR: Could not initialize the tool. Check configuration and file permissions.");
            code = 2;
        }
        System.exit(code);
    }

}
