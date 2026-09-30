package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.production.AgentPackProductionAction;
import io.github.flowerjvm.factory.application.production.AgentPackProductionCodec;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPhasePreparation;
import io.github.flowerjvm.factory.application.production.AgentPackProductionService;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class FactoryAgentPackProductionConfigurationTest {
    @Test
    void registersProductionWithAllControlsWithoutInvokingAWorker() {
        try (var context = configured()) {
            context.refresh();
            assertNotNull(context.getBean(AgentPackProductionCodec.class));
            assertNotNull(context.getBean(AgentPackProductionService.class));
            assertNotNull(context.getBean(AgentPackProductionPhasePreparation.class));
            var proposal = ActionProposal.builder(AgentPackProductionAction.ACTION_ID)
                    .proposalId("production-test-proposal").requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE).requesterId("factory-builder")
                    .reason("Test denial for a missing durable session")
                    .input(Map.of("buildSessionId", "missing", "expectedSessionVersion", 0L))
                    .idempotencyKey("missing-session-test").build();
            var identity = new ExecutionContext("tenant", "factory-builder", "test-run", "test-trace",
                    Map.of("actor.permissions", Set.of(AgentPackProductionAction.PERMISSION),
                            "resource.type", "build-session", "resource.id", "missing"));
            // A resolved registered definition reaches the policy gate, not UNKNOWN_ACTION.
            assertEquals(ActionExecutionStatus.DENIED, context.getBean(ActionRuntime.class).handle(proposal, identity).status());
        }
    }

    @Test
    void rejectsUnboundedProductionWorkWindowAtStartup() {
        try (var context = configured()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "factory.production.agent-pack.work-window", "PT2H")));
            assertThrows(BeanCreationException.class, context::refresh);
        }
    }

    private static AnnotationConfigApplicationContext configured() {
        var context = new AnnotationConfigApplicationContext();
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:production-host-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(FactoryActionRuntimeConfiguration.class, FactoryAgentPackProductionConfiguration.class);
        return context;
    }
}
