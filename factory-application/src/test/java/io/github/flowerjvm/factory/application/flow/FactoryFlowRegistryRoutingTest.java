package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FactoryFlowRegistryRoutingTest {
    private static final BuildSessionId SESSION_ID = new BuildSessionId("routing-session");

    @Test
    void agentPackOwnsEveryHistoricalContinuationConditionAndMissingOwnerStillFailsClosed() {
        var agent = agentLine();
        var registry = registry(List.of());
        var boundaries = List.of(
                new Boundary(BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                        BuildSessionPhase.HUMAN_RELEASE_REVIEW, false),
                new Boundary(BuildSessionStatus.CERTIFYING, BuildSessionPhase.UNDERSTAND_CUSTOMER, false),
                new Boundary(BuildSessionStatus.CERTIFIED_BUNDLE_READY,
                        BuildSessionPhase.UNDERSTAND_CUSTOMER, false),
                new Boundary(BuildSessionStatus.RUNNING, BuildSessionPhase.CERTIFY, false),
                new Boundary(BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE, false),
                new Boundary(BuildSessionStatus.RUNNING, BuildSessionPhase.COMPLETE, false),
                new Boundary(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, true));

        for (Boundary boundary : boundaries) {
            BuildSession session = session(ProductLineId.AGENT_PACK, boundary);
            assertTrue(agent.requiresContinuation(session), boundary.toString());
            assertThrows(IllegalStateException.class, () -> registry.flowId(session), boundary.toString());
        }

        BuildSession primary = session(ProductLineId.AGENT_PACK,
                new Boundary(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, false));
        assertFalse(agent.requiresContinuation(primary));
        assertEquals(FlowId.of(CreateCustomerAgentFlowFactory.FLOW_TYPE, SESSION_ID.value()),
                registry.flowId(primary));
    }

    @Test
    void referenceAssemblyKeepsPrimaryOwnershipAtItsOwnPackageReleasePhase() {
        var reference = referenceLine();
        BuildSession session = session(ProductLineId.REFERENCE_ASSEMBLY,
                new Boundary(BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE, false));

        assertFalse(reference.requiresContinuation(session));
        assertFalse(agentLine().requiresContinuation(session));
        assertEquals(FlowId.of(ReferenceAssemblyFlowFactory.FLOW_TYPE, SESSION_ID.value()),
                registry(List.of()).flowId(session));
    }

    @Test
    void installedContinuationRetainsItsFlowAndAllSixExecutionIdentityValues() {
        var continuation = new AgentPackCertificationFlowFactory((id, context) -> StepResult.stay());
        var registry = registry(List.of(continuation));
        BuildSession session = session(ProductLineId.AGENT_PACK,
                new Boundary(BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                        BuildSessionPhase.HUMAN_RELEASE_REVIEW, false));

        Flow flow = registry.createContinuation(session, "routing-run", "routing-trace");

        assertEquals(FlowId.of(AgentPackCertificationFlowFactory.FLOW_TYPE, SESSION_ID.value()),
                registry.flowId(session));
        assertEquals(registry.flowId(session), flow.flowId());
        assertEquals(ExecutionContext.builder()
                .tenantId("routing-tenant")
                .userId("routing-principal")
                .sessionId(SESSION_ID.value())
                .runId("routing-run")
                .traceId("routing-trace")
                .correlationId("routing-project")
                .build(), flow.executionContext());
    }

    @Test
    void aContinuationCannotClaimAnUnregisteredProductLine() {
        var registry = registry(List.of(claimingContinuation("test-continuation")));
        BuildSession unknown = session(new ProductLineId("unknown-line"),
                new Boundary(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, false));

        assertThrows(IllegalArgumentException.class, () -> registry.flowId(unknown));
        assertThrows(IllegalArgumentException.class,
                () -> registry.createContinuation(unknown, "run", "trace"));
    }

    @Test
    void ambiguousContinuationOwnersStillFailClosedBeforeFlowCreation() {
        var registry = registry(List.of(
                claimingContinuation("test-continuation-a"), claimingContinuation("test-continuation-b")));
        BuildSession session = session(ProductLineId.AGENT_PACK,
                new Boundary(BuildSessionStatus.CERTIFYING, BuildSessionPhase.CERTIFY, false));

        assertThrows(IllegalStateException.class, () -> registry.flowId(session));
        assertThrows(IllegalStateException.class,
                () -> registry.createContinuation(session, "run", "trace"));
    }

    private static FactoryFlowRegistry registry(List<FactoryContinuationFlow> continuations) {
        return new FactoryFlowRegistry(
                new FactoryProductLineRegistry(List.of(agentLine(), referenceLine())), continuations);
    }

    private static CreateCustomerAgentFlowFactory agentLine() {
        return new CreateCustomerAgentFlowFactory((phase, context) -> StepResult.stay());
    }

    private static ReferenceAssemblyFlowFactory referenceLine() {
        return new ReferenceAssemblyFlowFactory((phase, sessionId, context) -> StepResult.stay());
    }

    private static FactoryContinuationFlow claimingContinuation(String flowType) {
        return new FactoryContinuationFlow() {
            @Override
            public String flowType() {
                return flowType;
            }

            @Override
            public boolean owns(BuildSession session) {
                return true;
            }

            @Override
            public Flow create(BuildSession session, String flowRunId, String traceId) {
                throw new AssertionError("invalid continuation ownership must fail before creation");
            }

            @Override
            public Flow create(FlowId flowId) {
                throw new AssertionError("test-only continuation has no recovery path");
            }
        };
    }

    private static BuildSession session(ProductLineId productLineId, Boundary boundary) {
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        boolean agent = ProductLineId.AGENT_PACK.equals(productLineId);
        return new BuildSession(
                SESSION_ID,
                new TenantId("routing-tenant"),
                new ProjectId("routing-project"),
                productLineId,
                "routing-request",
                "routing-principal",
                boundary.status(),
                boundary.phase(),
                new ArtifactReference("artifact:routing-requirements"),
                new ContentHash("a".repeat(64)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                agent ? Optional.of(new CandidateId("routing-candidate")) : Optional.empty(),
                agent ? Optional.of(new ContentHash("b".repeat(64))) : Optional.empty(),
                boundary.hasCertification()
                        ? Optional.of(new CertificationId("routing-certification")) : Optional.empty(),
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

    private record Boundary(
            BuildSessionStatus status, BuildSessionPhase phase, boolean hasCertification) {}
}
