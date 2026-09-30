package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionIntakeLauncher;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeAction;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeInput;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeInputs;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeRecipeCheck;
import io.github.flowerjvm.factory.application.production.MaintenanceRepairDemoScenario;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.*;
import io.github.flowerjvm.flower.action.runtime.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class FactoryAgentPackProductionIntakeConfigurationTest {
    @TempDir Path temporary;

    @Test
    void defaultContextHasNoIntakeAndRequiresNoWorkerSelection() {
        try (var context = configured(false, "unused", null)) {
            context.refresh();
            assertTrue(context.getBeansOfType(AgentPackProductionIntakeInputs.class).isEmpty());
            assertTrue(context.getBeansOfType(ActionBackedAgentPackProductionIntakeLauncher.class).isEmpty());
        }
    }

    @Test
    void enabledIntakeWiresAllControlsWithoutStartupProbeAndDeniesUntrustedCallerBeforeProbe() {
        var probes = new AtomicInteger();
        try (var context = configured(true, temporary.resolve("skills").toString(), failingWorker(probes))) {
            context.refresh();
            assertNotNull(context.getBean(ActionBackedAgentPackProductionIntakeLauncher.class));
            assertEquals(0, probes.get());
            var input = input();
            var proposal = ActionProposal.builder(AgentPackProductionIntakeAction.ACTION_ID)
                    .proposalId("intake-host-proposal").requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE).requesterId("untrusted")
                    .reason("deny untrusted creation target").input(input.toMap()).idempotencyKey("request").build();
            var identity = new ExecutionContext("tenant", "untrusted", "run", "trace", Map.of(
                    "actor.permissions", Set.of(AgentPackProductionIntakeAction.PERMISSION),
                    "resource.type", "build-session", "resource.id", input.buildSessionId().value(),
                    "resource.projectId", input.projectId().value()));
            var result = context.getBean(ActionRuntime.class).handle(proposal, identity);
            assertEquals(ActionExecutionStatus.DENIED, result.status());
            assertEquals(0, probes.get());
            assertTrue(context.getBean(BuildSessionRepository.class).find(new TenantId("tenant"), input.buildSessionId()).isEmpty());
        }
    }

    @Test
    void authorizedIntakeFailsClosedWhenSelectedWorkerCannotProveCapabilities() {
        var probes = new AtomicInteger();
        try (var context = configured(true, temporary.resolve("skills").toString(), failingWorker(probes))) {
            context.refresh();
            var input = input();
            var result = context.getBean(ActionBackedAgentPackProductionIntakeLauncher.class)
                    .submit(new TenantId("tenant"), input.buildSessionId(), input.projectId(), "request", input);
            assertEquals(ActionExecutionStatus.FAILED, result.status());
            assertEquals("PRODUCTION_INTAKE_UNCERTAIN", result.code());
            assertEquals(1, probes.get());
            assertTrue(context.getBean(BuildSessionRepository.class).find(new TenantId("tenant"), input.buildSessionId()).isEmpty());
        }
    }

    @Test
    void relativePluginRootIsRejectedBeforeAnyProbe() {
        var probes = new AtomicInteger();
        try (var context = configured(true, "relative/skills", failingWorker(probes))) {
            assertThrows(BeanCreationException.class, context::refresh);
            assertEquals(0, probes.get());
        }
    }

    @Test
    void demoSelectionIsExplicitStrictAndDoesNotProbeWorkerOrStartProductionAtConfigurationTime() {
        var probes = new AtomicInteger();
        for (String scenario : java.util.List.of(MaintenanceRepairDemoScenario.ID, MaintenanceRepairDemoScenario.ID_V2)) {
            try (var context = configured(true, temporary.resolve("skills").toString(), failingWorker(probes))) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("demo", Map.of(
                        "factory.production.agent-pack.demo-repair-scenario", scenario)));
                context.refresh();
                assertNotNull(context.getBean(AgentPackProductionIntakeRecipeCheck.class));
                var noRepair = new AgentPackProductionIntakeInput(new BuildSessionId("demo-no-repair"), new ProjectId("project"),
                        Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS), 0);
                assertThrows(IllegalArgumentException.class, () -> context.getBean(AgentPackProductionIntakeRecipeCheck.class)
                        .check(new TenantId("tenant"), noRepair));
                assertEquals(0, probes.get());
            }
        }
        try (var context = configured(true, temporary.resolve("skills").toString(), failingWorker(probes))) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("demo", Map.of(
                    "factory.production.agent-pack.demo-repair-scenario", "unregistered-scenario")));
            assertThrows(BeanCreationException.class, context::refresh);
            assertEquals(0, probes.get());
        }
    }

    private static AgentPackProductionIntakeInput input() {
        return new AgentPackProductionIntakeInput(new BuildSessionId("session"), new ProjectId("project"),
                Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS), 1);
    }

    private AnnotationConfigApplicationContext configured(boolean enabled, String skillRoot, CodingWorker worker) {
        var context = new AnnotationConfigApplicationContext();
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:production-intake-host-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        context.registerBean(DataSource.class, () -> ds);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "factory.production.agent-pack.intake-enabled", Boolean.toString(enabled),
                "factory.production.agent-pack.flower-skill-root", skillRoot)));
        if (worker != null) context.registerBean(AgentPackProductionWorkerSelection.class, () ->
                new AgentPackProductionWorkerSelection("workspace", "test-binding", "test-adapter", worker));
        context.register(FactoryActionRuntimeConfiguration.class, FactoryAgentPackProductionConfiguration.class,
                FactoryAgentPackProductionIntakeConfiguration.class);
        return context;
    }

    private static CodingWorker failingWorker(AtomicInteger probes) {
        return new CodingWorker() {
            public WorkerCapabilities capabilities() { probes.incrementAndGet(); throw new IllegalStateException("isolation not proven"); }
            public WorkerSubmission submit(WorkerDispatchRequest request) { throw new AssertionError("no Worker dispatch"); }
            public WorkerStatusObservation status(WorkerStatusRequest request) { throw new AssertionError("no Worker status"); }
            public WorkerCancelResult cancel(WorkerCancelRequest request) { throw new AssertionError("no Worker cancellation"); }
        };
    }
}
