package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactoryProductLineRegistryTest {
    @Test
    void registersAgentPackAsTheFirstRecoverableProductLine() {
        var agentPack = new CreateCustomerAgentFlowFactory((phase, context) -> StepResult.stay());
        var registry = new FactoryProductLineRegistry(List.of(agentPack));

        assertSame(agentPack, registry.require(ProductLineId.AGENT_PACK));
        assertEquals(Set.of(ProductLineId.AGENT_PACK), registry.productLineIds());
        assertTrue(registry.flowFactoryRegistry().contains(CreateCustomerAgentFlowFactory.FLOW_TYPE));
        assertEquals(ProductLineId.AGENT_PACK, agentPack.productLineId());
        assertEquals(CreateCustomerAgentFlowFactory.FLOW_TYPE, agentPack.flowType());
    }

    @Test
    void rejectsEmptyDuplicateAndUnknownRegistrations() {
        assertThrows(IllegalArgumentException.class, () -> new FactoryProductLineRegistry(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FactoryProductLineRegistry(List.of(
                line("line-a", "flow-a"), line("line-a", "flow-b"))));
        assertThrows(IllegalArgumentException.class, () -> new FactoryProductLineRegistry(List.of(
                line("line-a", "same-flow"), line("line-b", "same-flow"))));

        var registry = new FactoryProductLineRegistry(List.of(line("line-a", "flow-a")));
        assertThrows(IllegalArgumentException.class, () -> registry.require(new ProductLineId("unknown-line")));
    }

    @Test
    void routesNewFlowCreationByTheDurableSessionProductLine() {
        var registry = new FactoryProductLineRegistry(List.of(
                line("line-a", "flow-a"), line("line-b", "flow-b")));

        Flow routed = registry.create(session(new ProductLineId("line-b")), "run-b", "trace-b");

        assertEquals(FlowId.of("flow-b", "session-line-b"), routed.flowId());
    }

    private static FactoryProductLine line(String productLineId, String flowType) {
        return new FactoryProductLine() {
            @Override
            public ProductLineId productLineId() {
                return new ProductLineId(productLineId);
            }

            @Override
            public String flowType() {
                return flowType;
            }

            @Override
            public Flow create(BuildSession session, String flowRunId, String traceId) {
                return Flow.builder(flowType, session.buildSessionId().value())
                        .step("test-only", new Step() {
                            @Override
                            protected StepResult onTick(StepContext context) {
                                return StepResult.done();
                            }
                        })
                        .build();
            }

            @Override
            public Flow create(FlowId flowId) {
                throw new UnsupportedOperationException("test-only line");
            }
        };
    }

    private static BuildSession session(ProductLineId productLineId) {
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        return new BuildSession(
                new BuildSessionId("session-" + productLineId.value()),
                new TenantId("tenant-a"),
                new ProjectId("project-a"),
                productLineId,
                "request-" + productLineId.value(),
                "test",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                new ArtifactReference("artifact:requirements"),
                new ContentHash("a".repeat(64)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                1,
                now,
                now.plusSeconds(300),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
    }
}
