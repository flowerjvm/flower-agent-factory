package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.recovery.FlowFactoryRegistry;
import io.github.flowerjvm.flower.core.step.RecoveryPolicy;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the durable six-step Flow of the concrete Reference Assembly product line. */
public final class ReferenceAssemblyFlowFactory implements FactoryProductLine {
    public static final ProductLineId PRODUCT_LINE_ID = ProductLineId.REFERENCE_ASSEMBLY;
    public static final String FLOW_TYPE = "build-reference-assembly";
    public static final String DEFINITION_VERSION = "build-reference-assembly.v1";

    public static final String ACCEPT_REFERENCE_REQUIREMENTS = "ACCEPT_REFERENCE_REQUIREMENTS";
    public static final String RESOLVE_CERTIFIED_COMPONENT = "RESOLVE_CERTIFIED_COMPONENT";
    public static final String ASSEMBLE_REFERENCE_MANIFEST = "ASSEMBLE_REFERENCE_MANIFEST";
    public static final String INSPECT_REFERENCE_ASSEMBLY = "INSPECT_REFERENCE_ASSEMBLY";
    public static final String WAIT_REFERENCE_RELEASE_REVIEW = "WAIT_REFERENCE_RELEASE_REVIEW";
    public static final String RELEASE_REFERENCE_ASSEMBLY = "RELEASE_REFERENCE_ASSEMBLY";

    private static final Map<ReferenceAssemblyBuildPhase, BuildSessionPhase> DURABLE_PHASES = Map.of(
            ReferenceAssemblyBuildPhase.ACCEPT_REFERENCE_REQUIREMENTS,
                    BuildSessionPhase.UNDERSTAND_CUSTOMER,
            ReferenceAssemblyBuildPhase.RESOLVE_CERTIFIED_COMPONENT,
                    BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
            ReferenceAssemblyBuildPhase.ASSEMBLE_REFERENCE_MANIFEST,
                    BuildSessionPhase.ASSEMBLE_CANDIDATE,
            ReferenceAssemblyBuildPhase.INSPECT_REFERENCE_ASSEMBLY,
                    BuildSessionPhase.TEST,
            ReferenceAssemblyBuildPhase.WAIT_REFERENCE_RELEASE_REVIEW,
                    BuildSessionPhase.HUMAN_RELEASE_REVIEW,
            ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                    BuildSessionPhase.PACKAGE_RELEASE);

    private final ReferenceAssemblyFlowCoordinator coordinator;

    public ReferenceAssemblyFlowFactory(ReferenceAssemblyFlowCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public boolean requiresProductLineCancellationRequester() {
        return true;
    }

    /**
     * Starts or reconstructs the primary line from its durable ledger. The Flow always begins at
     * its first step; coordinator catch-up makes domain-before-checkpoint recovery idempotent.
     */
    @Override
    public Flow create(BuildSession session, String flowRunId, String traceId) {
        Objects.requireNonNull(session, "session");
        if (!PRODUCT_LINE_ID.equals(session.productLineId())) {
            throw new IllegalArgumentException(
                    "Reference Assembly Flow requires the reference-assembly product line");
        }
        boolean liveRunning = session.status() == BuildSessionStatus.RUNNING
                && (session.currentPhase() == BuildSessionPhase.UNDERSTAND_CUSTOMER
                        || session.currentPhase() == BuildSessionPhase.RESOLVE_REUSE_STRATEGY
                        || session.currentPhase() == BuildSessionPhase.ASSEMBLE_CANDIDATE
                        || session.currentPhase() == BuildSessionPhase.TEST
                        || session.currentPhase() == BuildSessionPhase.PACKAGE_RELEASE);
        boolean waitingReview = session.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW;
        if (!liveRunning && !waitingReview) {
            throw new IllegalArgumentException(
                    "Reference Assembly Flow requires an active durable product-line state");
        }
        ExecutionContext context = FactoryExecutionContexts.create(
                session.buildSessionId(),
                session.tenantId().value(),
                session.createdBy(),
                flowRunId,
                traceId,
                session.projectId().value());
        return build(session.buildSessionId(), context);
    }

    /** Flower recovery entry point; the durable checkpoint restores the original identity. */
    @Override
    public Flow create(FlowId flowId) {
        Objects.requireNonNull(flowId, "flowId");
        if (!FLOW_TYPE.equals(flowId.flowType())) {
            throw new IllegalArgumentException("unexpected Flow type: " + flowId.flowType());
        }
        return build(new BuildSessionId(flowId.flowKey()), ExecutionContext.empty());
    }

    @Override
    public ProductLineId productLineId() {
        return PRODUCT_LINE_ID;
    }

    @Override
    public String flowType() {
        return FLOW_TYPE;
    }

    /** Focused recovery helper; the composed host registry owns multi-line registration. */
    public FlowFactoryRegistry registry() {
        return new FactoryProductLineRegistry(List.of(this)).flowFactoryRegistry();
    }

    public static BuildSessionPhase durablePhase(ReferenceAssemblyBuildPhase phase) {
        return DURABLE_PHASES.get(Objects.requireNonNull(phase, "phase"));
    }

    private Flow build(BuildSessionId buildSessionId, ExecutionContext context) {
        return Flow.builder(FLOW_TYPE, buildSessionId.value())
                .definitionVersion(DEFINITION_VERSION)
                .executionContext(context)
                .durable()
                .durableStep(
                        ACCEPT_REFERENCE_REQUIREMENTS,
                        step(ReferenceAssemblyBuildPhase.ACCEPT_REFERENCE_REQUIREMENTS, buildSessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        RESOLVE_CERTIFIED_COMPONENT,
                        step(ReferenceAssemblyBuildPhase.RESOLVE_CERTIFIED_COMPONENT, buildSessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        ASSEMBLE_REFERENCE_MANIFEST,
                        step(ReferenceAssemblyBuildPhase.ASSEMBLE_REFERENCE_MANIFEST, buildSessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        INSPECT_REFERENCE_ASSEMBLY,
                        step(ReferenceAssemblyBuildPhase.INSPECT_REFERENCE_ASSEMBLY, buildSessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        WAIT_REFERENCE_RELEASE_REVIEW,
                        step(ReferenceAssemblyBuildPhase.WAIT_REFERENCE_RELEASE_REVIEW, buildSessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        RELEASE_REFERENCE_ASSEMBLY,
                        step(ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY, buildSessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .build();
    }

    private Step step(ReferenceAssemblyBuildPhase phase, BuildSessionId buildSessionId) {
        return new ReferenceAssemblyLedgerStep(phase, buildSessionId, coordinator);
    }

    private static final class ReferenceAssemblyLedgerStep extends Step {
        private final ReferenceAssemblyBuildPhase phase;
        private final BuildSessionId buildSessionId;
        private final ReferenceAssemblyFlowCoordinator coordinator;

        private ReferenceAssemblyLedgerStep(
                ReferenceAssemblyBuildPhase phase,
                BuildSessionId buildSessionId,
                ReferenceAssemblyFlowCoordinator coordinator) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.buildSessionId = Objects.requireNonNull(buildSessionId, "buildSessionId");
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        }

        @Override
        protected StepResult onTick(StepContext context) {
            return Objects.requireNonNull(
                    coordinator.advance(phase, buildSessionId, context),
                    "Reference Assembly coordinator result");
        }
    }
}
