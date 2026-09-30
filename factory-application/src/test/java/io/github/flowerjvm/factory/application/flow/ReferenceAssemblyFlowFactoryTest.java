package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.step.RecoveryPolicy;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyFlowFactoryTest {
    private static final BuildSessionId SESSION_ID = new BuildSessionId("reference-build-1");
    private static final FlowId FLOW_ID =
            FlowId.of(ReferenceAssemblyFlowFactory.FLOW_TYPE, SESSION_ID.value());
    private static final Instant STARTED_AT = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void pinsSecondProductLineIdentitySixFreshDurableStepsAndLedgerProjection() {
        ReferenceAssemblyFlowCoordinator coordinator =
                (phase, buildSessionId, context) -> StepResult.done();
        var factory = new ReferenceAssemblyFlowFactory(coordinator);

        Flow first = factory.create(session(), "flow-run-1", "trace-1");
        Flow second = factory.create(session(), "flow-run-2", "trace-2");

        assertEquals(ProductLineId.REFERENCE_ASSEMBLY, factory.productLineId());
        assertEquals("build-reference-assembly", factory.flowType());
        assertEquals(FLOW_ID, first.flowId());
        assertEquals("build-reference-assembly.v1", first.definitionVersion());
        assertEquals(
                List.of(
                        ReferenceAssemblyFlowFactory.ACCEPT_REFERENCE_REQUIREMENTS,
                        ReferenceAssemblyFlowFactory.RESOLVE_CERTIFIED_COMPONENT,
                        ReferenceAssemblyFlowFactory.ASSEMBLE_REFERENCE_MANIFEST,
                        ReferenceAssemblyFlowFactory.INSPECT_REFERENCE_ASSEMBLY,
                        ReferenceAssemblyFlowFactory.WAIT_REFERENCE_RELEASE_REVIEW,
                        ReferenceAssemblyFlowFactory.RELEASE_REFERENCE_ASSEMBLY),
                first.steps().stream().map(step -> step.stepId()).toList());
        first.steps().forEach(step ->
                assertEquals(RecoveryPolicy.REENTER_IDEMPOTENT, step.recoveryPolicy()));
        for (int index = 0; index < first.steps().size(); index++) {
            assertNotSame(first.steps().get(index).step(), second.steps().get(index).step());
        }
        assertEquals(fullContext("flow-run-1", "trace-1"), first.executionContext());

        assertEquals(
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                ReferenceAssemblyFlowFactory.durablePhase(
                        ReferenceAssemblyBuildPhase.ACCEPT_REFERENCE_REQUIREMENTS));
        assertEquals(
                BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                ReferenceAssemblyFlowFactory.durablePhase(
                        ReferenceAssemblyBuildPhase.RESOLVE_CERTIFIED_COMPONENT));
        assertEquals(
                BuildSessionPhase.ASSEMBLE_CANDIDATE,
                ReferenceAssemblyFlowFactory.durablePhase(
                        ReferenceAssemblyBuildPhase.ASSEMBLE_REFERENCE_MANIFEST));
        assertEquals(
                BuildSessionPhase.TEST,
                ReferenceAssemblyFlowFactory.durablePhase(
                        ReferenceAssemblyBuildPhase.INSPECT_REFERENCE_ASSEMBLY));
        assertEquals(
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                ReferenceAssemblyFlowFactory.durablePhase(
                        ReferenceAssemblyBuildPhase.WAIT_REFERENCE_RELEASE_REVIEW));
        assertEquals(
                BuildSessionPhase.PACKAGE_RELEASE,
                ReferenceAssemblyFlowFactory.durablePhase(
                        ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY));
    }

    @Test
    void eachTickDelegatesExactlyOneBoundedLedgerObservationWithTrustedSessionIdentity() {
        var observations = new ArrayList<Observation>();
        ReferenceAssemblyFlowCoordinator coordinator = (phase, buildSessionId, context) -> {
            observations.add(new Observation(phase, buildSessionId));
            return StepResult.stay();
        };
        var factory = new ReferenceAssemblyFlowFactory(coordinator);

        try (var harness = FlowTestHarness.create()) {
            harness.submit(factory.create(session(), "flow-run-1", "trace-1"));
            harness.tick();

            assertEquals(
                    List.of(new Observation(
                            ReferenceAssemblyBuildPhase.ACCEPT_REFERENCE_REQUIREMENTS, SESSION_ID)),
                    observations);
            assertEquals(
                    ReferenceAssemblyFlowFactory.ACCEPT_REFERENCE_REQUIREMENTS,
                    harness.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        }
    }

    @Test
    void completedReleaseStepLeavesCompleteProjectionToTheDurableLedgerCoordinator() {
        var visited = new ArrayList<ReferenceAssemblyBuildPhase>();
        ReferenceAssemblyFlowCoordinator coordinator = (phase, buildSessionId, context) -> {
            visited.add(phase);
            return StepResult.done();
        };
        var factory = new ReferenceAssemblyFlowFactory(coordinator);

        try (var harness = FlowTestHarness.create()) {
            harness.submit(factory.create(session(), "flow-run-1", "trace-1"));
            for (int tick = 0; tick < 10; tick++) {
                harness.tick();
                if (harness.latestSnapshot(FLOW_ID).orElseThrow().state().isTerminal()) {
                    break;
                }
            }

            assertEquals(FlowState.FINISHED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
            assertEquals(List.of(ReferenceAssemblyBuildPhase.values()), visited);
            assertEquals(
                    BuildSessionPhase.PACKAGE_RELEASE,
                    ReferenceAssemblyFlowFactory.durablePhase(
                            ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY));
        }
    }

    @Test
    void startRequiresExactLineButAdmitsDurableCatchUpStatesForPreCheckpointRecovery() {
        var factory = new ReferenceAssemblyFlowFactory(
                (phase, buildSessionId, context) -> StepResult.done());

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(
                        session(ProductLineId.AGENT_PACK, BuildSessionStatus.RUNNING,
                                BuildSessionPhase.UNDERSTAND_CUSTOMER),
                        "run",
                        "trace"));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(
                        session(ProductLineId.REFERENCE_ASSEMBLY, BuildSessionStatus.DRAFT,
                                BuildSessionPhase.UNDERSTAND_CUSTOMER),
                        "run",
                        "trace"));
        assertEquals(
                FLOW_ID,
                factory.create(
                                session(
                                        ProductLineId.REFERENCE_ASSEMBLY,
                                        BuildSessionStatus.RUNNING,
                                        BuildSessionPhase.RESOLVE_REUSE_STRATEGY),
                                "run",
                                "trace")
                        .flowId());
        assertEquals(
                FLOW_ID,
                factory.create(
                                session(
                                        ProductLineId.REFERENCE_ASSEMBLY,
                                        BuildSessionStatus.WAITING_RELEASE_REVIEW,
                                        BuildSessionPhase.HUMAN_RELEASE_REVIEW),
                                "run-review",
                                "trace-review")
                        .flowId());
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(
                        session(
                                ProductLineId.REFERENCE_ASSEMBLY,
                                BuildSessionStatus.RUNNING,
                                BuildSessionPhase.CERTIFY),
                        "run",
                        "trace"));
    }

    @Test
    void recoveryUsesFlowIdentityAndWaitsForCheckpointIdentityRestore() {
        var factory = new ReferenceAssemblyFlowFactory(
                (phase, buildSessionId, context) -> StepResult.done());

        Flow recovered = factory.create(FLOW_ID);

        assertEquals(FLOW_ID, recovered.flowId());
        assertEquals(ExecutionContext.empty(), recovered.executionContext());
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(FlowId.of("other-flow", SESSION_ID.value())));
    }

    private static ExecutionContext fullContext(String runId, String traceId) {
        return ExecutionContext.builder()
                .tenantId("tenant-a")
                .userId("principal-a")
                .sessionId(SESSION_ID.value())
                .runId(runId)
                .traceId(traceId)
                .correlationId("project-a")
                .build();
    }

    private static BuildSession session() {
        return session(
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER);
    }

    private static BuildSession session(
            ProductLineId productLineId,
            BuildSessionStatus status,
            BuildSessionPhase phase) {
        return new BuildSession(
                SESSION_ID,
                new TenantId("tenant-a"),
                new ProjectId("project-a"),
                productLineId,
                "reference-request-1",
                "principal-a",
                status,
                phase,
                new ArtifactReference("artifact:reference-assembly-requirements"),
                new ContentHash("a".repeat(64)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                STARTED_AT,
                STARTED_AT.plusSeconds(3600),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                STARTED_AT,
                STARTED_AT);
    }

    private record Observation(
            ReferenceAssemblyBuildPhase phase,
            BuildSessionId buildSessionId) {}
}
