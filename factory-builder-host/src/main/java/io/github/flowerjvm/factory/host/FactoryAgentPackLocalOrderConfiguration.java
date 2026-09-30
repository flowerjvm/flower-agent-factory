package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionIntakeLauncher;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeInput;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Disabled unless a local operator explicitly opts in with a complete, fixed order identity. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.production.agent-pack.local-order", name = "enabled", havingValue = "true")
public class FactoryAgentPackLocalOrderConfiguration {
    private static final String PREFIX = "factory.production.agent-pack.local-order.";

    @Bean
    FactoryAgentPackLocalOrderRunner agentPackLocalOrderRunner(
            Environment environment, ActionBackedAgentPackProductionIntakeLauncher intake,
            BuildSessionRepository sessions, FactoryProductLineFlowLauncher flows) {
        // No implicit tenant, default deadline, auto-generated request key, or server endpoint.
        var input = new AgentPackProductionIntakeInput(
                new BuildSessionId(required(environment, "session-id")),
                new ProjectId(required(environment, "project-id")),
                Instant.parse(required(environment, "deadline-at")),
                Integer.parseInt(required(environment, "max-repair-rounds")));
        return new FactoryAgentPackLocalOrderRunner(intake, sessions, flows,
                new TenantId(required(environment, "tenant-id")), required(environment, "request-key"), input);
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(PREFIX + name + " must be explicitly configured");
        }
        return value;
    }
}
