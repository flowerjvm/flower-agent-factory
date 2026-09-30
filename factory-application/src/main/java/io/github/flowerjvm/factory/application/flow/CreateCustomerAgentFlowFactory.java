package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildPhase;
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
import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Builds the ordinary durable six-step PR3 {@code CreateCustomerAgentFlow}. */
public final class CreateCustomerAgentFlowFactory implements FactoryProductLine {
    public static final ProductLineId PRODUCT_LINE_ID = ProductLineId.AGENT_PACK;
    public static final String FLOW_TYPE = "create-customer-agent";
    public static final String DEFINITION_VERSION = "create-customer-agent.v1";

    public static final String ACCEPT_REQUIREMENTS = "ACCEPT_REQUIREMENTS";
    public static final String DESIGN_CANDIDATE = "DESIGN_CANDIDATE";
    public static final String GENERATE_CANDIDATE = "GENERATE_CANDIDATE";
    public static final String VERIFY_CANDIDATE = "VERIFY_CANDIDATE";
    public static final String HUMAN_REVIEW = "HUMAN_REVIEW";
    public static final String MARK_CANDIDATE_READY = "MARK_CANDIDATE_READY";

    /*
     * The PR3 six-step shape is deliberately smaller than the complete BuildSession phase model.
     * Candidate-ready finishes this Flow but remains a non-terminal BuildSession state for PR4-6.
     */
    private static final Map<BuildPhase, BuildSessionPhase> DURABLE_PHASES = Map.of(
            BuildPhase.ACCEPT_REQUIREMENTS, BuildSessionPhase.UNDERSTAND_CUSTOMER,
            BuildPhase.DESIGN_CANDIDATE, BuildSessionPhase.DESIGN_AGENT,
            BuildPhase.GENERATE_CANDIDATE, BuildSessionPhase.GENERATE_CANDIDATE,
            BuildPhase.VERIFY_CANDIDATE, BuildSessionPhase.TEST,
            BuildPhase.HUMAN_REVIEW, BuildSessionPhase.HUMAN_RELEASE_REVIEW,
            // MARK is a loss projection of the reduced PR3 Flow. It must not move the durable
            // phase backwards from human review to the full model's earlier assembly phase.
            BuildPhase.MARK_CANDIDATE_READY, BuildSessionPhase.HUMAN_RELEASE_REVIEW);

    private final CreateCustomerAgentFlowCoordinator coordinator;

    public CreateCustomerAgentFlowFactory(CreateCustomerAgentFlowCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Starts a Flow only for a BuildSession that has already been durably created. */
    @Override
    public Flow create(BuildSession session, String flowRunId, String traceId) {
        Objects.requireNonNull(session, "session");
        if (!PRODUCT_LINE_ID.equals(session.productLineId())) {
            throw new IllegalArgumentException("Agent Pack Flow requires the agent-pack product line");
        }
        if (session.status() != io.github.flowerjvm.factory.application.build.BuildSessionStatus.RUNNING
                || session.currentPhase() != BuildSessionPhase.UNDERSTAND_CUSTOMER) {
            throw new IllegalArgumentException(
                    "new creation Flow requires a RUNNING BuildSession at UNDERSTAND_CUSTOMER");
        }
        return build(
                session.buildSessionId(),
                FactoryExecutionContexts.create(
                        session.buildSessionId(),
                        session.tenantId().value(),
                        session.createdBy(),
                        flowRunId,
                        traceId,
                        session.projectId().value()));
    }

    /** Flower recovery entry point; the checkpoint restores the complete original context. */
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

    /** Keeps the historical Agent Pack certification/release boundary owned by this ProductLine. */
    @Override
    public boolean requiresContinuation(BuildSession session) {
        Objects.requireNonNull(session, "session");
        return PRODUCT_LINE_ID.equals(session.productLineId())
                && (session.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                || session.status() == BuildSessionStatus.CERTIFYING
                || session.status() == BuildSessionStatus.CERTIFIED_BUNDLE_READY
                || session.currentPhase() == BuildSessionPhase.CERTIFY
                || session.currentPhase() == BuildSessionPhase.PACKAGE_RELEASE
                || session.currentPhase() == BuildSessionPhase.COMPLETE
                || session.currentCertificationId().isPresent());
    }

    /** Compatibility helper for focused single-line tests; the Host uses the collection registry. */
    public FlowFactoryRegistry registry() {
        return new FactoryProductLineRegistry(List.of(this)).flowFactoryRegistry();
    }

    /** Compatibility entry point for the shared Factory identity-only mapping. */
    public static ExecutionContext executionContext(
            BuildSessionId buildSessionId,
            String tenantId,
            String initiator,
            String flowRunId,
            String traceId,
            String projectId) {
        return FactoryExecutionContexts.create(
                buildSessionId, tenantId, initiator, flowRunId, traceId, projectId);
    }

    public static BuildSessionPhase durablePhase(BuildPhase phase) {
        return DURABLE_PHASES.get(Objects.requireNonNull(phase, "phase"));
    }

    private Flow build(BuildSessionId sessionId, ExecutionContext context) {
        return Flow.builder(FLOW_TYPE, sessionId.value())
                .definitionVersion(DEFINITION_VERSION)
                .executionContext(context)
                .durable()
                .durableStep(
                        ACCEPT_REQUIREMENTS,
                        step(BuildPhase.ACCEPT_REQUIREMENTS, sessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        DESIGN_CANDIDATE,
                        step(BuildPhase.DESIGN_CANDIDATE, sessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        GENERATE_CANDIDATE,
                        step(BuildPhase.GENERATE_CANDIDATE, sessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        VERIFY_CANDIDATE,
                        step(BuildPhase.VERIFY_CANDIDATE, sessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        HUMAN_REVIEW,
                        step(BuildPhase.HUMAN_REVIEW, sessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep(
                        MARK_CANDIDATE_READY,
                        step(BuildPhase.MARK_CANDIDATE_READY, sessionId),
                        RecoveryPolicy.REENTER_IDEMPOTENT)
                .build();
    }

    private Step step(BuildPhase phase, BuildSessionId sessionId) {
        return new LedgerObservingStep(phase, sessionId, coordinator);
    }

    private static final class LedgerObservingStep extends Step {
        private static final String WAKE_SIGNAL = "durable-ledger-changed";

        private final BuildPhase phase;
        private final BuildSessionId sessionId;
        private final CreateCustomerAgentFlowCoordinator coordinator;

        private LedgerObservingStep(
                BuildPhase phase,
                BuildSessionId sessionId,
                CreateCustomerAgentFlowCoordinator coordinator) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        }

        @Override
        protected void onEnter(StepContext context) {
            context.subscribe(CreateCustomerAgentFlowWakeup.class, wakeup -> {
                if (sessionId.equals(wakeup.buildSessionId())) {
                    context.signal(WAKE_SIGNAL);
                }
            });
        }

        @Override
        protected StepResult onTick(StepContext context) {
            // A signal only shortens observation latency; persisted ledgers remain the truth.
            if (context.hasSignal(WAKE_SIGNAL)) {
                context.clearSignal(WAKE_SIGNAL);
            }
            StepResult result = coordinator.advance(phase, context);
            return Objects.requireNonNull(result, "coordinator result");
        }
    }
}
