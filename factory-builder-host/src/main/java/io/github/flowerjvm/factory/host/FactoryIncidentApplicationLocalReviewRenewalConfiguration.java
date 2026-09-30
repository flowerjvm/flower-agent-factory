package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** No ambient actor, deadline extension, approval or all-expired selection. Disabled by default. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.production.incident-application.local-review-renewal", name = "enabled", havingValue = "true")
public class FactoryIncidentApplicationLocalReviewRenewalConfiguration {
    private static final String PREFIX = "factory.production.incident-application.local-review-renewal.";

    @Bean FactoryIncidentApplicationLocalReviewRenewal localIncidentApplicationReviewRenewal(Environment environment, ActionRuntime runtime) {
        if (!"true".equals(environment.getProperty("factory.production.incident-application.enabled"))) {
            throw new IllegalArgumentException("Incident application line must be explicitly enabled for exact review renewal");
        }
        return new FactoryIncidentApplicationLocalReviewRenewal(runtime, path(environment, "operator-binding-path"), path(environment, "request-path"));
    }

    private static Path path(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) throw new IllegalArgumentException(PREFIX + name + " must be explicitly configured");
        var path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw FactoryLocalDecisionDocuments.invalid();
        return path;
    }
}
