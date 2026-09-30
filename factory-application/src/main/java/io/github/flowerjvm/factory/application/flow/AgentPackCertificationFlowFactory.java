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
import java.util.Objects;

/** Secondary, separately recoverable one-step continuation for Agent Pack certification. */
public final class AgentPackCertificationFlowFactory implements FactoryContinuationFlow {
    public static final String FLOW_TYPE = "agent-pack-certification";
    public static final String DEFINITION_VERSION = "agent-pack-certification.v1";
    public static final String ISSUE_CERTIFICATION = "ISSUE_CERTIFICATION";

    private final AgentPackCertificationFlowCoordinator coordinator;

    public AgentPackCertificationFlowFactory(AgentPackCertificationFlowCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public String flowType() {
        return FLOW_TYPE;
    }

    @Override
    public boolean owns(BuildSession session) {
        Objects.requireNonNull(session, "session");
        if (!ProductLineId.AGENT_PACK.equals(session.productLineId())
                || session.status().isTerminal()
                || session.status() == BuildSessionStatus.CANCELLING
                || session.status() == BuildSessionStatus.CANCELLED
                || session.cancellationRequestedAt().isPresent()
                || session.currentCandidateId().isEmpty()
                || session.currentCandidateHash().isEmpty()) {
            return false;
        }
        boolean candidateReady = session.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && session.currentCertificationId().isEmpty();
        boolean certifying = session.status() == BuildSessionStatus.CERTIFYING
                && session.currentPhase() == BuildSessionPhase.CERTIFY
                && session.currentCertificationId().isEmpty();
        boolean awaitingRelease = session.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && session.currentCertificationId().isPresent();
        return candidateReady || certifying || awaitingRelease;
    }

    @Override
    public boolean controls(BuildSession session) {
        Objects.requireNonNull(session, "session");
        if (!ProductLineId.AGENT_PACK.equals(session.productLineId())) {
            return false;
        }
        if (owns(session)) {
            return true;
        }
        boolean certificationCrashWindow = session.currentPhase() == BuildSessionPhase.CERTIFY
                && (session.status() == BuildSessionStatus.CANCELLING
                        || session.status() == BuildSessionStatus.CANCELLED
                        || session.status() == BuildSessionStatus.BLOCKED
                        || session.status() == BuildSessionStatus.MANUAL_REVIEW
                        || session.status() == BuildSessionStatus.FAILED);
        boolean boundReleaseCrashWindow = session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && session.currentCertificationId().isPresent()
                && (session.status() == BuildSessionStatus.CANCELLING
                        || session.status() == BuildSessionStatus.CANCELLED
                        || session.status() == BuildSessionStatus.BLOCKED
                        || session.status() == BuildSessionStatus.MANUAL_REVIEW
                        || session.status() == BuildSessionStatus.FAILED);
        return certificationCrashWindow || boundReleaseCrashWindow;
    }

    @Override
    public Flow create(BuildSession session, String flowRunId, String traceId) {
        Objects.requireNonNull(session, "session");
        if (!owns(session)) {
            throw new IllegalArgumentException(
                    "BuildSession is not owned by the Agent Pack certification continuation");
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

    /** Focused recovery helper; the composed host registry uses {@link FactoryFlowRegistry}. */
    public FlowFactoryRegistry registry() {
        return FlowFactoryRegistry.builder().register(FLOW_TYPE, this).build();
    }

    private Flow build(BuildSessionId sessionId, ExecutionContext context) {
        return Flow.builder(FLOW_TYPE, sessionId.value())
                .definitionVersion(DEFINITION_VERSION)
                .executionContext(context)
                .durable()
                .durableStep(
                        ISSUE_CERTIFICATION,
                        new CertificationLedgerStep(sessionId, coordinator),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .build();
    }

    private static final class CertificationLedgerStep extends Step {
        private final BuildSessionId buildSessionId;
        private final AgentPackCertificationFlowCoordinator coordinator;

        private CertificationLedgerStep(
                BuildSessionId buildSessionId,
                AgentPackCertificationFlowCoordinator coordinator) {
            this.buildSessionId = Objects.requireNonNull(buildSessionId, "buildSessionId");
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        }

        @Override
        protected StepResult onTick(StepContext context) {
            return Objects.requireNonNull(
                    coordinator.advance(buildSessionId, context), "certification coordinator result");
        }
    }
}
