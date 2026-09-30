package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionIntakeLauncher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class FactoryAgentPackLocalOrderConfigurationTest {
    private static final String PREFIX = "factory.production.agent-pack.local-order.";

    @Test
    void absentEnabledPropertyCreatesNoRunnerAndNeedsNoProductionDependencies() {
        try (var context = context(Map.of())) {
            context.refresh();
            assertTrue(context.getBeansOfType(FactoryAgentPackLocalOrderRunner.class).isEmpty());
        }
    }

    @Test
    void explicitlyDisabledLocalOrderCreatesNoRunnerEvenWithMalformedOrderProperties() {
        try (var context = context(Map.of(PREFIX + "enabled", "false", PREFIX + "deadline-at", "not-a-date"))) {
            context.refresh();
            assertTrue(context.getBeansOfType(FactoryAgentPackLocalOrderRunner.class).isEmpty());
        }
    }

    @Test
    void enabledCompleteOrderCreatesOneRunnerButRefreshDoesNotExecuteItsIntake() {
        try (var fixture = new FactoryAgentPackLocalOrderRunnerTest.Fixture(); var context = context(completeProperties())) {
            registerDependencies(context, fixture);
            context.refresh();
            assertEquals(1, context.getBeansOfType(FactoryAgentPackLocalOrderRunner.class).size());
            assertTrue(fixture.proposals.isEmpty());
            fixture.assertNoFlow();
            fixture.sessions.create(FactoryAgentPackLocalOrderRunnerTest.pristine());
            context.getBean(FactoryAgentPackLocalOrderRunner.class).run(new DefaultApplicationArguments());
            fixture.worker.tickOnce();
            assertEquals(1, fixture.proposals.size());
            assertEquals(1, fixture.worker.snapshot().size());
        }
    }

    @Test
    void enabledOrderRequiresEachExplicitIdentityDeadlineAndRepairProperty() {
        for (String missing : List.of("tenant-id", "session-id", "project-id", "request-key", "deadline-at", "max-repair-rounds")) {
            var properties = completeProperties(); properties.remove(PREFIX + missing);
            try (var fixture = new FactoryAgentPackLocalOrderRunnerTest.Fixture(); var context = context(properties)) {
                registerDependencies(context, fixture);
                RuntimeException rejected = assertThrows(RuntimeException.class, context::refresh);
                assertTrue(causes(rejected).contains(PREFIX + missing + " must be explicitly configured"), missing);
                assertTrue(fixture.proposals.isEmpty());
                fixture.assertNoFlow();
            }
        }
    }

    @Test
    void enabledOrderRejectsMalformedDeadlineRepairLimitAndWhitespaceWithoutRuntimeEffects() {
        for (Map.Entry<String, String> invalid : List.of(Map.entry("deadline-at", "not-an-instant"),
                Map.entry("deadline-at", "2026-09-06T06:00:00.000000001Z"),
                Map.entry("max-repair-rounds", "-1"), Map.entry("max-repair-rounds", "4"),
                Map.entry("max-repair-rounds", "1.5"), Map.entry("request-key", " leading-space"))) {
            var properties = completeProperties(); properties.put(PREFIX + invalid.getKey(), invalid.getValue());
            try (var fixture = new FactoryAgentPackLocalOrderRunnerTest.Fixture(); var context = context(properties)) {
                registerDependencies(context, fixture);
                assertThrows(RuntimeException.class, context::refresh);
                assertTrue(fixture.proposals.isEmpty());
                fixture.assertNoFlow();
            }
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-order-test", properties));
        context.register(FactoryAgentPackLocalOrderConfiguration.class);
        return context;
    }

    private static void registerDependencies(AnnotationConfigApplicationContext context, FactoryAgentPackLocalOrderRunnerTest.Fixture fixture) {
        context.registerBean(ActionBackedAgentPackProductionIntakeLauncher.class, () -> fixture.intake);
        context.registerBean(BuildSessionRepository.class, () -> fixture.sessions);
        context.registerBean(FactoryProductLineFlowLauncher.class, () -> fixture.flows);
    }

    private static Map<String, Object> completeProperties() {
        var values = new LinkedHashMap<String, Object>();
        values.put(PREFIX + "enabled", "true");
        values.put(PREFIX + "tenant-id", FactoryAgentPackLocalOrderRunnerTest.TENANT.value());
        values.put(PREFIX + "session-id", FactoryAgentPackLocalOrderRunnerTest.SESSION.value());
        values.put(PREFIX + "project-id", FactoryAgentPackLocalOrderRunnerTest.PROJECT.value());
        values.put(PREFIX + "request-key", FactoryAgentPackLocalOrderRunnerTest.REQUEST);
        values.put(PREFIX + "deadline-at", FactoryAgentPackLocalOrderRunnerTest.INPUT.deadlineAt().toString());
        values.put(PREFIX + "max-repair-rounds", "2");
        return values;
    }

    private static String causes(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) messages.append(current.getMessage()).append('\n');
        return messages.toString();
    }
}
