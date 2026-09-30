package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Disabled by default, with no ambient/default tenant, component or new production request. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.production.incident-application.local-order", name = "enabled", havingValue = "true")
public class FactoryIncidentApplicationLocalOrderConfiguration {
    private static final String PREFIX = "factory.production.incident-application.local-order.";

    @Bean FactoryIncidentApplicationLocalOrderRunner incidentApplicationLocalOrderRunner(Environment environment,
            ActionRuntime runtime, IncidentApplicationIntake intake, IncidentApplicationLedger ledger,
            FactoryProductLineFlowLauncher flows) {
        if (!Boolean.parseBoolean(environment.getProperty("factory.production.incident-application.enabled", "false"))) {
            throw new IllegalArgumentException("Incident application production line must be explicitly enabled");
        }
        var input = Map.<String,Object>of("buildSessionId", required(environment,"session-id"),
                "projectId", required(environment,"project-id"), "variant", required(environment,"variant"),
                "certificationId", required(environment,"certification-id"), "candidateHash", required(environment,"source-hash"),
                "certificationManifestRef", required(environment,"certification-manifest-ref"),
                "certificationManifestHash", required(environment,"certification-manifest-hash"),
                "deadlineAt", required(environment,"deadline-at"));
        return new FactoryIncidentApplicationLocalOrderRunner(runtime, intake, ledger, flows,
                new TenantId(required(environment,"tenant-id")), required(environment,"request-key"), input);
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(PREFIX + name + " must be explicitly configured");
        }
        return value;
    }
}
