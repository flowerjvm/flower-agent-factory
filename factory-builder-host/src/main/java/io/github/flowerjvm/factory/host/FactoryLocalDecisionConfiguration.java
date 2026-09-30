package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.decision.ActionBackedDecisionRecordLauncher;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Disabled by default. The binding is protected host configuration, never an AI/user command field. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.decision.local", name = "enabled", havingValue = "true")
public class FactoryLocalDecisionConfiguration {
    @Bean
    FactoryLocalDecisionRunner localDecisionRunner(Environment environment, ActionBackedDecisionRecordLauncher launcher) {
        return new FactoryLocalDecisionRunner(launcher, requiredPath(environment, "operator-binding-path"),
                requiredPath(environment, "request-path"));
    }

    private static Path requiredPath(Environment environment, String name) {
        String value = environment.getProperty("factory.decision.local." + name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("factory.decision.local." + name + " must be explicitly configured");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw FactoryLocalDecisionDocuments.invalid();
        return path;
    }
}
