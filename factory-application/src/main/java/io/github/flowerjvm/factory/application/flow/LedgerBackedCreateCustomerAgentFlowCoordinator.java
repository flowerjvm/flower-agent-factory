package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchIdempotencyKeys;
import io.github.flowerjvm.factory.application.build.BuildPhase;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPhasePreparation;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner.Status;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerActionRunRecovery;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Production coordinator that derives every tick from durable Factory ledgers.
 *
 * <p>It proposes registered Actions for REQUESTED worker dispatch and independent verification,
 * but never performs blocking worker, Maven, or Docker work on the Flower tick. A new instance
 * after restart observes the same durable WorkOrder, WorkerRun, candidate, verification and
 * decision truth.
 */
public final class LedgerBackedCreateCustomerAgentFlowCoordinator
        implements CreateCustomerAgentFlowCoordinator {
    public static final String DESIGN_WORK_ORDER_PHASE = BuildSessionPhase.DESIGN_AGENT.id();
    public static final String GENERATE_WORK_ORDER_PHASE = BuildSessionPhase.GENERATE_CANDIDATE.id();
    public static final String CANDIDATE_SUBJECT_TYPE = "CANDIDATE";
    public static final String PR4_GATE_PROFILE = ActionBackedVerificationRunLauncher.GATE_PROFILE;
    public static final String BUILD_SESSION_DEADLINE_EXCEEDED = "BUILD_SESSION_DEADLINE_EXCEEDED";
    public static final String BUILD_SESSION_REPAIR_LIMIT_EXCEEDED = "BUILD_SESSION_REPAIR_LIMIT_EXCEEDED";
    public static final String BUILD_SESSION_CANCELLATION_REQUESTED = "BUILD_SESSION_CANCELLATION_REQUESTED";
    public static final String WORK_ORDER_DEADLINE_EXCEEDED = "WORK_ORDER_DEADLINE_EXCEEDED";
    public static final String WORKER_RUN_CLOCK_SKEW = "WORKER_RUN_CLOCK_SKEW";
    public static final String WORKER_RUN_FAILED = "WORKER_RUN_FAILED";
    public static final String WORKER_RUN_CANCELLED = "WORKER_RUN_CANCELLED";
    public static final String WORKER_RUN_MANUAL_REVIEW = "WORKER_RUN_MANUAL_REVIEW";
    public static final String CANDIDATE_HASH_MISMATCH = "CANDIDATE_HASH_MISMATCH";
    public static final String VERIFICATION_REQUIRED = "VERIFICATION_REQUIRED";
    public static final String VERIFICATION_ACTION_ORPHANED = "VERIFICATION_ACTION_ORPHANED";
    public static final String VERIFICATION_ACTION_TERMINAL_MISMATCH = "VERIFICATION_ACTION_TERMINAL_MISMATCH";
    public static final String DECISION_EXPIRED = "DECISION_EXPIRED";
    public static final String DECISION_REJECTED = "DECISION_REJECTED";
    public static final String DECISION_SUBJECT_CHANGED = "DECISION_SUBJECT_CHANGED";
    public static final String DECISION_CANCELLED = "DECISION_CANCELLED";

    private final BuildSessionRepository buildSessions;
    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final CandidateVersionRepository candidates;
    private final VerificationRunRepository verifications;
    private final VerificationRunLauncher verificationLauncher;
    private final VerificationEvidenceValidator verificationEvidenceValidator;
    private final VerificationActionEvidenceOwner verificationActionEvidenceOwner;
    private final ContentHash requiredVerificationFixtureSetHash;
    private final DecisionPointRepository decisions;
    private final WorkerActionRunRecovery actionRunRecovery;
    private final ActionRuntime actionRuntime;
    private final Clock clock;
    private final Optional<AgentPackProductionPhasePreparation> productionPreparation;

    public LedgerBackedCreateCustomerAgentFlowCoordinator(
            BuildSessionRepository buildSessions,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            VerificationRunLauncher verificationLauncher,
            VerificationEvidenceValidator verificationEvidenceValidator,
            VerificationActionEvidenceOwner verificationActionEvidenceOwner,
            ContentHash requiredVerificationFixtureSetHash,
            DecisionPointRepository decisions,
            WorkerActionRunRecovery actionRunRecovery,
            ActionRuntime actionRuntime,
            Clock clock) {
        this(buildSessions, workOrders, workerRuns, candidates, verifications, verificationLauncher,
                verificationEvidenceValidator, verificationActionEvidenceOwner, requiredVerificationFixtureSetHash,
                decisions, actionRunRecovery, actionRuntime, clock, Optional.empty());
    }

    public LedgerBackedCreateCustomerAgentFlowCoordinator(
            BuildSessionRepository buildSessions, WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns, CandidateVersionRepository candidates,
            VerificationRunRepository verifications, VerificationRunLauncher verificationLauncher,
            VerificationEvidenceValidator verificationEvidenceValidator,
            VerificationActionEvidenceOwner verificationActionEvidenceOwner,
            ContentHash requiredVerificationFixtureSetHash, DecisionPointRepository decisions,
            WorkerActionRunRecovery actionRunRecovery, ActionRuntime actionRuntime, Clock clock,
            Optional<AgentPackProductionPhasePreparation> productionPreparation) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.verifications = Objects.requireNonNull(verifications, "verifications");
        this.verificationLauncher = Objects.requireNonNull(verificationLauncher, "verificationLauncher");
        this.verificationEvidenceValidator = Objects.requireNonNull(
                verificationEvidenceValidator, "verificationEvidenceValidator");
        this.verificationActionEvidenceOwner = Objects.requireNonNull(
                verificationActionEvidenceOwner, "verificationActionEvidenceOwner");
        this.requiredVerificationFixtureSetHash = Objects.requireNonNull(
                requiredVerificationFixtureSetHash, "requiredVerificationFixtureSetHash");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.actionRunRecovery = Objects.requireNonNull(actionRunRecovery, "actionRunRecovery");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.productionPreparation = Objects.requireNonNull(productionPreparation, "productionPreparation");
    }

    @Override
    public StepResult advance(BuildPhase phase, StepContext context) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(context, "context");
        TenantId tenantId = new TenantId(requireText(
                context.executionContext().tenantIdOrNull(), "Flower tenantId"));
        BuildSessionId sessionId = new BuildSessionId(requireText(
                context.executionContext().sessionIdOrNull(), "Flower sessionId"));
        BuildSession session = buildSessions.find(tenantId, sessionId)
                .orElseThrow(() -> new IllegalStateException("BuildSession not found in Flower tenant scope"));
        validateIdentity(session, context);
        if (!CreateCustomerAgentFlowFactory.PRODUCT_LINE_ID.equals(session.productLineId())) {
            throw new IllegalStateException("Agent Pack Flow cannot advance a different product line");
        }

        Instant now = clock.instant();
        if (session.status().isTerminal()) {
            return failFromPersistedOutcome(session, "BuildSession is already terminal");
        }
        if (session.status() == BuildSessionStatus.BLOCKED
                || session.status() == BuildSessionStatus.MANUAL_REVIEW) {
            return failFromPersistedOutcome(session, "BuildSession is not runnable");
        }
        if (session.status() == BuildSessionStatus.CANCELLING
                && session.terminalCode().isPresent()) {
            // A crash after a branch-specific cancellation CAS must not replace its stable cause
            // with the generic external-cancellation code on Flow re-entry.
            return failFromPersistedOutcome(session, "BuildSession cancellation is already durable");
        }
        if (session.cancellationRequestedAt().isPresent()
                || session.status() == BuildSessionStatus.CANCELLING) {
            return persistCancellationAndFail(
                    session,
                    BUILD_SESSION_CANCELLATION_REQUESTED,
                    "durable BuildSession cancellation requested",
                    now);
        }
        Optional<StepResult> recovered = recoverPersistedStepResult(phase, session);
        if (recovered.isPresent()) {
            // Domain CAS is authoritative. If the process died before Flower checkpointed the
            // returned DONE/GOTO, project that already-committed transition idempotently.
            return recovered.orElseThrow();
        }
        boolean activeLongRunningObservation = (phase == BuildPhase.DESIGN_CANDIDATE
                        && session.currentPhase() == BuildSessionPhase.DESIGN_AGENT)
                || (phase == BuildPhase.GENERATE_CANDIDATE
                        && session.currentPhase() == BuildSessionPhase.GENERATE_CANDIDATE)
                || (phase == BuildPhase.VERIFY_CANDIDATE
                        && session.currentPhase() == BuildSessionPhase.TEST);
        if (!activeLongRunningObservation && !now.isBefore(session.deadlineAt())) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.FAILED,
                    BUILD_SESSION_DEADLINE_EXCEEDED,
                    "persisted BuildSession deadline exceeded",
                    now);
        }

        return switch (phase) {
            case ACCEPT_REQUIREMENTS -> acceptRequirements(session, now);
            case DESIGN_CANDIDATE -> observeWorkerPhase(
                    session,
                    DESIGN_WORK_ORDER_PHASE,
                    BuildSessionPhase.DESIGN_AGENT,
                    BuildSessionStatus.RUNNING,
                    now,
                    context);
            case GENERATE_CANDIDATE -> observeWorkerPhase(
                    session,
                    GENERATE_WORK_ORDER_PHASE,
                    BuildSessionPhase.GENERATE_CANDIDATE,
                    BuildSessionStatus.RUNNING,
                    now,
                    context);
            case VERIFY_CANDIDATE -> observeVerification(session, now);
            case HUMAN_REVIEW -> observeHumanReview(session, now);
            case MARK_CANDIDATE_READY -> markCandidateReady(session, now);
        };
    }

    private StepResult acceptRequirements(BuildSession session, Instant now) {
        if (session.status() == BuildSessionStatus.DRAFT) {
            return StepResult.stay();
        }
        if (session.currentPhase() != BuildSessionPhase.UNDERSTAND_CUSTOMER) {
            return StepResult.stay();
        }
        return transition(session, BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, now)
                ? StepResult.done()
                : StepResult.stay();
    }

    private StepResult observeWorkerPhase(
            BuildSession session,
            String workOrderPhase,
            BuildSessionPhase phase,
            BuildSessionStatus status,
            Instant now,
            StepContext stepContext) {
        if (session.currentPhase() != phase) {
            return StepResult.stay();
        }
        WorkOrder workOrder = workOrders
                .findLatestByBuildSessionAndPhase(session.tenantId(), session.buildSessionId(), workOrderPhase)
                .orElse(null);
        if (workOrder == null) {
            return !now.isBefore(session.deadlineAt())
                    ? persistCutoffOutcome(session, session.deadlineAt(), now)
                    : prepareProductionPhase(session, now);
        }
        // Redesign completes as RUNNING/GENERATE while an older successful generation still exists.
        // The immutable production plan's round fence prevents replaying that old candidate.
        WorkOrder observedOrder = workOrder;
        if (productionPreparation.filter(value -> value.requiresCurrentRevision(session, observedOrder)).isPresent()) {
            return !now.isBefore(session.deadlineAt())
                    ? persistCutoffOutcome(session, session.deadlineAt(), now)
                    : prepareProductionPhase(session, now);
        }
        if (session.status() == BuildSessionStatus.REPAIRING
                && workOrder.createdAt().isBefore(session.updatedAt())) {
            // A repair branch cannot reuse the earlier terminal order/candidate it is replacing.
            return !now.isBefore(session.deadlineAt())
                    ? persistCutoffOutcome(session, session.deadlineAt(), now)
                    : prepareProductionPhase(session, now);
        }
        Instant workCutoff = earliest(session.deadlineAt(), workOrder.deadlineAt());
        var workerRun = workerRuns
                .findLatestByWorkOrder(session.tenantId(), workOrder.workOrderId())
                .orElse(null);
        if (workerRun == null) {
            return !now.isBefore(workCutoff)
                    ? persistCutoffOutcome(session, workCutoff, now)
                    : StepResult.stay();
        }
        Instant effectiveCutoff = earliest(workCutoff, workerRun.deadlineAt());
        if (workerRun.status() == WorkerRunStatus.REQUESTED) {
            var unclaimedOwner = actionRunRecovery.findUnclaimedOwner(workOrder, workerRun);
            if (unclaimedOwner.isPresent()) {
                var owner = unclaimedOwner.orElseThrow();
                BuildSessionStatus outcomeStatus = owner.disposition()
                                == WorkerActionRunRecovery.UnclaimedOwnerDisposition.TERMINAL_NO_EFFECT
                        ? BuildSessionStatus.BLOCKED
                        : BuildSessionStatus.MANUAL_REVIEW;
                return persistOutcomeAndFail(
                        session,
                        outcomeStatus,
                        owner.code(),
                        owner.message(),
                        now);
            }
        }
        if (workerRun.status() == WorkerRunStatus.WAITING_EXTERNAL) {
            workerRun = actionRunRecovery.reconcileTerminalCompletion(workOrder, workerRun);
        }
        if (now.isBefore(workerRun.createdAt())) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.FAILED,
                    WORKER_RUN_CLOCK_SKEW,
                    "WorkerRun timestamp is ahead of the trusted clock",
                    now);
        }
        if (workerRun.status() == WorkerRunStatus.WAITING_EXTERNAL
                && !now.isBefore(effectiveCutoff)) {
            workerRun = actionRunRecovery.reconcileDeadline(
                    workOrder, workerRun, effectiveCutoff, now);
        }
        if (!workerRun.status().isTerminal() && !now.isBefore(effectiveCutoff)) {
            return persistCutoffOutcome(session, effectiveCutoff, now);
        }
        if (workerRun.status().isTerminal()
                && (workerRun.completedAt().isEmpty()
                        || !workerRun.completedAt().orElseThrow().isBefore(effectiveCutoff))) {
            return persistCutoffOutcome(session, effectiveCutoff, now);
        }
        if (workerRun.status() == WorkerRunStatus.REQUESTED) {
            dispatch(workOrder, workerRun, stepContext);
            return StepResult.stay();
        }
        if (!workerRun.status().isTerminal()) {
            return StepResult.stay();
        }
        if (!actionRunRecovery.terminalOwnerMatches(workOrder, workerRun)) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.MANUAL_REVIEW,
                    WORKER_RUN_MANUAL_REVIEW,
                    "terminal WorkerRun is not backed by its canonical ActionRun result",
                    now);
        }
        if (workerRun.status() != WorkerRunStatus.SUCCEEDED) {
            if (workerRun.status() == WorkerRunStatus.FAILED
                    && workerRun.retryDisposition()
                            .filter(value -> value == WorkerRetryDisposition.AFTER_CORRECTION)
                            .isPresent()) {
                if (session.repairRound() >= session.maxRepairRounds()) {
                    return persistOutcomeAndFail(
                            session,
                            BuildSessionStatus.BLOCKED,
                            BUILD_SESSION_REPAIR_LIMIT_EXCEEDED,
                            "BuildSession repair limit exhausted",
                            now);
                }
                String target = phase == BuildSessionPhase.DESIGN_AGENT
                        ? CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE
                        : CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE;
                return repair(session, phase, session.repairRound() + 1, now)
                        ? StepResult.goTo(target)
                        : StepResult.stay();
            }
            return persistWorkerFailureAndFail(session, workerRun, now);
        }
        if (phase == BuildSessionPhase.DESIGN_AGENT) {
            if (workerRun.resultArtifactManifestRef().isEmpty() || workerRun.resultHash().isEmpty()) {
                return StepResult.stay();
            }
            BuildSessionStatus nextStatus = session.status() == BuildSessionStatus.REPAIRING
                    ? BuildSessionStatus.REPAIRING
                    : status;
            return bindBlueprintAndAdvance(
                            session,
                            nextStatus,
                            workerRun.resultArtifactManifestRef().orElseThrow(),
                            now)
                    ? StepResult.done()
                    : StepResult.stay();
        }
        var candidate = candidates.findByBuildSessionAndWorkOrder(
                session.tenantId(), session.buildSessionId(), workOrder.workOrderId());
        if (candidate.isEmpty()
                || workerRun.resultHash().isEmpty()
                || workerRun.resultArtifactManifestRef().isEmpty()
                || !candidate.orElseThrow().sourceManifestRef()
                        .equals(workerRun.resultArtifactManifestRef().orElseThrow())
                || !candidate.orElseThrow().sourceHash().equals(workerRun.resultHash().orElseThrow())) {
            return StepResult.stay();
        }
        return bindCandidateAndAdvance(session, candidate.orElseThrow(), now)
                ? StepResult.done()
                : StepResult.stay();
    }

    private StepResult observeVerification(BuildSession session, Instant now) {
        if (session.currentPhase() != BuildSessionPhase.TEST) {
            return StepResult.stay();
        }
        if (session.currentCandidateId().isEmpty() || session.currentCandidateHash().isEmpty()) {
            return StepResult.stay();
        }
        var candidate = candidates.find(
                session.tenantId(), session.currentCandidateId().orElseThrow());
        if (candidate.isEmpty()) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    VERIFICATION_REQUIRED,
                    "current CandidateVersion is missing",
                    now);
        }
        if (!candidate.orElseThrow().buildSessionId().equals(session.buildSessionId())
                || !candidate.orElseThrow().sourceHash().equals(session.currentCandidateHash().orElseThrow())) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    CANDIDATE_HASH_MISMATCH,
                    "current CandidateVersion does not match the BuildSession candidate",
                    now);
        }
        final String requiredProfile;
        try {
            requiredProfile = verificationLauncher.profileFor(candidate.orElseThrow());
        } catch (RuntimeException invalidProfile) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    VERIFICATION_REQUIRED,
                    "generation WorkOrder does not establish an authorized verification profile",
                    now);
        }
        var verification = verifications.findLatestForCandidate(
                session.tenantId(),
                session.buildSessionId(),
                session.currentCandidateId().orElseThrow(),
                session.currentCandidateHash().orElseThrow(),
                requiredProfile);
        if (verification.isPresent()) {
            var owner = verificationActionEvidenceOwner.assess(verification.orElseThrow());
            if (owner.status() == Status.ORPHANED) {
                return persistOutcomeAndFail(
                        session,
                        BuildSessionStatus.MANUAL_REVIEW,
                        owner.code(),
                        "verification Action remained RUNNING or lost its durable park transition; "
                                + "Action Runtime 0.3.3 cannot safely recover that attempt",
                        now);
            }
            if (owner.status() == Status.INVALID_TERMINAL) {
                return persistOutcomeAndFail(
                        session,
                        BuildSessionStatus.MANUAL_REVIEW,
                        VERIFICATION_ACTION_TERMINAL_MISMATCH,
                        "terminal VerificationRun is not owned by an exact canonical ActionRun result",
                        now);
            }
            if (verification.orElseThrow().status().isTerminal()
                    && owner.status() == Status.PENDING) {
                return now.isBefore(session.deadlineAt())
                        ? StepResult.stay()
                        : persistCutoffOutcome(session, session.deadlineAt(), now);
            }
        }
        if (verification.isEmpty()
                || verification.orElseThrow().status() == VerificationRunStatus.REQUESTED
                || verification.orElseThrow().status() == VerificationRunStatus.RUNNING) {
            if (!now.isBefore(session.deadlineAt())) {
                return persistCutoffOutcome(session, session.deadlineAt(), now);
            }
            try {
                // The launcher performs only repository reads/CAS and a deferred Action submission.
                // Durable verifier work never executes on this Flower tick.
                verificationLauncher.ensureRequested(session, candidate.orElseThrow(), now);
            } catch (RuntimeException launchFailure) {
                // No blocking fallback is allowed on a Flower tick. A later tick can retry the
                // same durable request; the enclosing BuildSession deadline remains authoritative.
            }
            return StepResult.stay();
        }
        if (!verification.orElseThrow().status().isTerminal()) {
            return StepResult.stay();
        }
        var terminal = verification.orElseThrow();
        if (terminal.completedAt().isEmpty()
                || !terminal.completedAt().orElseThrow().isBefore(session.deadlineAt())) {
            return persistCutoffOutcome(session, session.deadlineAt(), now);
        }
        if (!requiredProfile.equals(terminal.gateProfile())
                || !terminal.toolchainLockHash().equals(candidate.orElseThrow().toolchainLockHash())
                || !terminal.fixtureSetHash().equals(requiredVerificationFixtureSetHash)
                || terminal.resultManifestRef().isEmpty()
                || terminal.resultManifestHash().isEmpty()
                || terminal.resultManifestHash().filter(VerificationRun.LEGACY_RESULT_MANIFEST_HASH::equals).isPresent()
                || terminal.terminalCode().isEmpty()
                || terminal.disposition().isEmpty()) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    VERIFICATION_REQUIRED,
                    "VerificationRun is not bound to the required generation evidence profile",
                    now);
        }
        VerificationDisposition disposition = terminal.disposition().orElseThrow();
        if (terminal.status() == VerificationRunStatus.FAILED
                && disposition == VerificationDisposition.BLOCKED) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    terminal.terminalCode().orElseThrow(),
                    "independent verification infrastructure did not establish review evidence",
                    now);
        }
        if (terminal.status() == VerificationRunStatus.FAILED) {
            if (disposition != VerificationDisposition.REPAIR_REQUIRED) {
                return persistOutcomeAndFail(
                        session,
                        BuildSessionStatus.BLOCKED,
                        VERIFICATION_REQUIRED,
                        "failed VerificationRun has an unsupported disposition",
                        now);
            }
            if (session.repairRound() >= session.maxRepairRounds()) {
                return persistOutcomeAndFail(
                        session,
                        BuildSessionStatus.BLOCKED,
                        BUILD_SESSION_REPAIR_LIMIT_EXCEEDED,
                        "BuildSession repair limit exhausted",
                        now);
            }
            return repair(
                            session,
                            BuildSessionPhase.GENERATE_CANDIDATE,
                            session.repairRound() + 1,
                            now)
                    ? StepResult.goTo(CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE)
                    : StepResult.stay();
        }
        if (disposition != VerificationDisposition.REVIEW_ELIGIBLE) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    VERIFICATION_REQUIRED,
                    "only evidence-bound review-eligible verification may enter human review",
                    now);
        }
        if (!verificationEvidenceValidator.isReviewEligible(terminal)) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    VERIFICATION_REQUIRED,
                    "verification result manifest is missing, corrupt, or not bound to the durable run",
                    now);
        }
        return transition(
                        session,
                        BuildSessionStatus.WAITING_RELEASE_REVIEW,
                        BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                        now)
                ? StepResult.done()
                : StepResult.stay();
    }

    private StepResult observeHumanReview(BuildSession session, Instant now) {
        if (session.currentPhase() != BuildSessionPhase.HUMAN_RELEASE_REVIEW
                || session.currentCandidateId().isEmpty()
                || session.currentCandidateHash().isEmpty()) {
            return StepResult.stay();
        }
        var decision = decisions.findLatestByBuildSessionAndSubject(
                session.tenantId(),
                session.buildSessionId(),
                DecisionPoint.RELEASE_REVIEW_TYPE,
                CANDIDATE_SUBJECT_TYPE,
                session.currentCandidateId().orElseThrow().value(),
                session.currentCandidateHash().orElseThrow());
        if (decision.isEmpty()) {
            return prepareProductionPhase(session, now);
        }
        DecisionPoint point = decision.orElseThrow();
        if (point.status() == DecisionPointStatus.OPEN) {
            if (now.isBefore(point.dueAt())) {
                return StepResult.stay();
            }
            DecisionPoint expired = point.expire(now);
            if (!decisions.compareAndSet(point, expired)) {
                return StepResult.stay();
            }
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    DECISION_EXPIRED,
                    "human release review expired at its persisted deadline",
                    now);
        }
        return switch (point.status()) {
            case APPROVED -> StepResult.done();
            case CHANGES_REQUESTED -> {
                if (session.repairRound() >= session.maxRepairRounds()) {
                    yield persistOutcomeAndFail(
                            session,
                            BuildSessionStatus.BLOCKED,
                            BUILD_SESSION_REPAIR_LIMIT_EXCEEDED,
                            "BuildSession repair limit exhausted",
                            now);
                }
                yield repair(
                                session,
                                BuildSessionPhase.DESIGN_AGENT,
                                session.repairRound() + 1,
                                now)
                        ? StepResult.goTo(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE)
                        : StepResult.stay();
            }
            case CANCELLED -> persistCancellationAndFail(
                    session,
                    DECISION_CANCELLED,
                    "human release review cancelled the BuildSession",
                    now);
            case REJECTED -> persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    DECISION_REJECTED,
                    "human release review rejected the candidate",
                    now);
            case EXPIRED -> persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    DECISION_EXPIRED,
                    "human release review expired",
                    now);
            case STALE -> persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.BLOCKED,
                    DECISION_SUBJECT_CHANGED,
                    "human release review no longer matches the current candidate",
                    now);
            case OPEN -> StepResult.stay();
        };
    }

    private StepResult prepareProductionPhase(BuildSession session, Instant now) {
        if (productionPreparation.isEmpty()) return StepResult.stay();
        try {
            var prepared = productionPreparation.orElseThrow().prepare(session);
            if (prepared.isEmpty()) return StepResult.stay();
            var result = prepared.orElseThrow();
            if (result.status() == ActionExecutionStatus.SUCCEEDED
                    || (result.status() == ActionExecutionStatus.DENIED && "DUPLICATE_ACTION".equals(result.code()))) {
                // Observe committed WorkOrder/WorkerRun or DecisionPoint on the next tick. A stale
                // in-progress duplicate is never stolen; the persisted session deadline bounds it.
                return StepResult.stay();
            }
            if (buildSessions.find(session.tenantId(), session.buildSessionId())
                    .filter(session::equals).isEmpty()) return StepResult.stay();
            return persistOutcomeAndFail(session, BuildSessionStatus.MANUAL_REVIEW,
                    "AGENT_PACK_PRODUCTION_PREPARATION_FAILED",
                    "registered production preparation did not establish durable phase intent", clock.instant());
        } catch (RuntimeException uncertain) {
            return persistOutcomeAndFail(session, BuildSessionStatus.MANUAL_REVIEW,
                    "AGENT_PACK_PRODUCTION_PREPARATION_UNCERTAIN",
                    "production preparation requires reconciliation; no direct Worker fallback is permitted", clock.instant());
        }
    }

    private StepResult markCandidateReady(BuildSession session, Instant now) {
        if (session.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE) {
            return StepResult.done();
        }
        if (session.currentPhase() != BuildSessionPhase.HUMAN_RELEASE_REVIEW
                || session.currentCandidateId().isEmpty()
                || session.currentCandidateHash().isEmpty()) {
            return StepResult.stay();
        }
        var decision = decisions.findLatestByBuildSessionAndSubject(
                session.tenantId(),
                session.buildSessionId(),
                DecisionPoint.RELEASE_REVIEW_TYPE,
                CANDIDATE_SUBJECT_TYPE,
                session.currentCandidateId().orElseThrow().value(),
                session.currentCandidateHash().orElseThrow());
        if (decision.filter(value -> value.status() == DecisionPointStatus.APPROVED).isEmpty()) {
            return StepResult.stay();
        }
        // PR3's lossy projection must not move the durable phase backwards to ASSEMBLE_CANDIDATE.
        return transition(
                        session,
                        BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                        BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                        now)
                ? StepResult.done()
                : StepResult.stay();
    }

    private void dispatch(WorkOrder workOrder, io.github.flowerjvm.factory.application.work.WorkerRunRecord run,
            StepContext stepContext) {
        ActionProposal proposal = ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                .proposalId("flow-dispatch:" + run.operationId())
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-builder")
                .reason("Advance durable CreateCustomerAgentFlow worker phase")
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, workOrder.workOrderId().value(),
                        WorkerDispatchAction.WORKER_RUN_ID, run.workerRunId().value(),
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, run.version()))
                .idempotencyKey(WorkerDispatchIdempotencyKeys.derive(workOrder, run))
                .build();
        var identity = stepContext.executionContext();
        var actionContext = new io.github.flowerjvm.flower.action.runtime.ExecutionContext(
                identity.tenantIdOrNull(),
                identity.userIdOrNull(),
                actionRunId(),
                identity.traceIdOrNull(),
                Map.of(
                        "actor.permissions", Set.of(WorkerDispatchAction.PERMISSION),
                        "resource.type", WorkerDispatchAction.RESOURCE_TYPE,
                        "resource.id", workOrder.workOrderId().value()));
        var result = actionRuntime.handle(proposal, actionContext);
        if (result.status() != ActionExecutionStatus.ACCEPTED
                && result.status() != ActionExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("worker dispatch Action rejected: " + result.code());
        }
    }

    /** Every governed request gets a fresh bounded lifecycle ID; idempotency groups retries. */
    private static String actionRunId() {
        return UUID.randomUUID().toString();
    }

    private boolean transition(
            BuildSession expected,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Instant updatedAt) {
        return buildSessions.compareAndSet(expected, copy(expected, status, phase, updatedAt));
    }

    private StepResult failFromPersistedOutcome(BuildSession session, String fallbackMessage) {
        String code = session.terminalCode().orElse("BUILD_SESSION_NOT_RUNNABLE");
        String message = session.terminalMessage().orElse(fallbackMessage);
        return StepResult.fail(new IllegalStateException(code + ": " + message));
    }

    private StepResult persistCancellationAndFail(
            BuildSession expected,
            String code,
            String message,
            Instant updatedAt) {
        BuildSession cancelled = outcome(
                expected,
                BuildSessionStatus.CANCELLING,
                code,
                message,
                expected.cancellationRequestedAt().isPresent()
                        ? expected.cancellationRequestedAt()
                        : Optional.of(updatedAt),
                updatedAt);
        return buildSessions.compareAndSet(expected, cancelled)
                ? StepResult.fail(new IllegalStateException(code + ": " + message))
                : StepResult.stay();
    }

    private StepResult persistOutcomeAndFail(
            BuildSession expected,
            BuildSessionStatus status,
            String code,
            String message,
            Instant updatedAt) {
        BuildSession next = outcome(
                expected,
                status,
                code,
                message,
                expected.cancellationRequestedAt(),
                updatedAt);
        return buildSessions.compareAndSet(expected, next)
                ? StepResult.fail(new IllegalStateException(code + ": " + message))
                : StepResult.stay();
    }

    private StepResult persistCutoffOutcome(
            BuildSession session,
            Instant effectiveCutoff,
            Instant observedAt) {
        if (effectiveCutoff.equals(session.deadlineAt())) {
            return persistOutcomeAndFail(
                    session,
                    BuildSessionStatus.FAILED,
                    BUILD_SESSION_DEADLINE_EXCEEDED,
                    "persisted BuildSession deadline exceeded",
                    observedAt);
        }
        return persistOutcomeAndFail(
                session,
                BuildSessionStatus.BLOCKED,
                WORK_ORDER_DEADLINE_EXCEEDED,
                "persisted WorkOrder or WorkerRun deadline exceeded",
                observedAt);
    }

    private static Instant earliest(Instant first, Instant second) {
        return first.isAfter(second) ? second : first;
    }

    private StepResult persistWorkerFailureAndFail(
            BuildSession session,
            io.github.flowerjvm.factory.application.work.WorkerRunRecord workerRun,
            Instant updatedAt) {
        if (workerRun.status() == WorkerRunStatus.CANCELLED) {
            return persistCancellationAndFail(
                    session,
                    WORKER_RUN_CANCELLED,
                    workerRun.message().orElse("WorkerRun cancellation was confirmed"),
                    updatedAt);
        }
        WorkerRetryDisposition disposition = workerRun.retryDisposition()
                .orElse(WorkerRetryDisposition.MANUAL_REVIEW);
        BuildSessionStatus status;
        String code;
        if (workerRun.status() == WorkerRunStatus.MANUAL_REVIEW
                || workerRun.status() == WorkerRunStatus.RECONCILING
                || disposition == WorkerRetryDisposition.MANUAL_REVIEW) {
            status = BuildSessionStatus.MANUAL_REVIEW;
            code = WORKER_RUN_MANUAL_REVIEW;
        } else if (disposition == WorkerRetryDisposition.NEVER) {
            status = BuildSessionStatus.FAILED;
            code = WORKER_RUN_FAILED;
        } else {
            // AFTER_BACKOFF requires a separately governed new WorkerRun; this Flow cannot
            // silently reuse the terminal Action key, so it remains a recoverable BLOCKED state.
            status = BuildSessionStatus.BLOCKED;
            code = WORKER_RUN_FAILED;
        }
        String message = workerRun.message().orElse("WorkerRun ended without success: " + workerRun.status());
        return persistOutcomeAndFail(session, status, code, message, updatedAt);
    }

    private static Optional<StepResult> recoverPersistedStepResult(
            BuildPhase flowPhase,
            BuildSession session) {
        return switch (flowPhase) {
            case ACCEPT_REQUIREMENTS -> session.status() == BuildSessionStatus.RUNNING
                            && session.currentPhase() == BuildSessionPhase.DESIGN_AGENT
                    ? Optional.of(StepResult.done())
                    : Optional.empty();
            case DESIGN_CANDIDATE -> (session.status() == BuildSessionStatus.RUNNING
                                    || session.status() == BuildSessionStatus.REPAIRING)
                            && session.currentPhase() == BuildSessionPhase.GENERATE_CANDIDATE
                            && session.currentBlueprintRef().isPresent()
                    ? Optional.of(StepResult.done())
                    : Optional.empty();
            case GENERATE_CANDIDATE -> session.status() == BuildSessionStatus.VERIFYING
                            && session.currentPhase() == BuildSessionPhase.TEST
                            && session.currentCandidateId().isPresent()
                            && session.currentCandidateHash().isPresent()
                    ? Optional.of(StepResult.done())
                    : Optional.empty();
            case VERIFY_CANDIDATE -> {
                if (session.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                        && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                        && session.currentCandidateId().isPresent()
                        && session.currentCandidateHash().isPresent()) {
                    yield Optional.of(StepResult.done());
                }
                if (session.status() == BuildSessionStatus.REPAIRING
                        && session.currentPhase() == BuildSessionPhase.GENERATE_CANDIDATE) {
                    yield Optional.of(StepResult.goTo(CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE));
                }
                yield Optional.empty();
            }
            case HUMAN_REVIEW -> session.status() == BuildSessionStatus.REPAIRING
                            && session.currentPhase() == BuildSessionPhase.DESIGN_AGENT
                    ? Optional.of(StepResult.goTo(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE))
                    : Optional.empty();
            case MARK_CANDIDATE_READY -> session.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                            && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                    ? Optional.of(StepResult.done())
                    : Optional.empty();
        };
    }

    private static BuildSession outcome(
            BuildSession expected,
            BuildSessionStatus status,
            String code,
            String message,
            Optional<Instant> cancellationRequestedAt,
            Instant updatedAt) {
        return new BuildSession(
                expected.buildSessionId(), expected.tenantId(), expected.projectId(),
                expected.productLineId(),
                expected.requestIdempotencyKey(), expected.createdBy(), status, expected.currentPhase(),
                expected.requirementsArtifactRef(), expected.requirementsHash(),
                expected.selectedManagerWorkerBinding(), expected.selectedCodingWorkerBinding(),
                expected.currentBlueprintRef(), expected.currentCandidateId(), expected.currentCandidateHash(),
                expected.currentCertificationId(), expected.repairRound(), expected.maxRepairRounds(),
                expected.startedAt(), expected.deadlineAt(), cancellationRequestedAt,
                Optional.of(code), Optional.of(message), expected.version() + 1,
                expected.createdAt(), updatedAt);
    }

    private boolean bindCandidateAndAdvance(
            BuildSession expected,
            io.github.flowerjvm.factory.application.candidate.CandidateVersion candidate,
            Instant updatedAt) {
        return buildSessions.compareAndSet(expected, new BuildSession(
                expected.buildSessionId(), expected.tenantId(), expected.projectId(),
                expected.productLineId(),
                expected.requestIdempotencyKey(), expected.createdBy(), BuildSessionStatus.VERIFYING,
                BuildSessionPhase.TEST, expected.requirementsArtifactRef(), expected.requirementsHash(),
                expected.selectedManagerWorkerBinding(), expected.selectedCodingWorkerBinding(),
                expected.currentBlueprintRef(), Optional.of(candidate.candidateId()),
                Optional.of(candidate.sourceHash()), expected.currentCertificationId(), expected.repairRound(),
                expected.maxRepairRounds(), expected.startedAt(), expected.deadlineAt(),
                expected.cancellationRequestedAt(), expected.terminalCode(), expected.terminalMessage(),
                expected.version() + 1, expected.createdAt(), updatedAt));
    }

    private boolean bindBlueprintAndAdvance(
            BuildSession expected,
            BuildSessionStatus status,
            io.github.flowerjvm.factory.contracts.artifact.ArtifactReference blueprintRef,
            Instant updatedAt) {
        return buildSessions.compareAndSet(expected, new BuildSession(
                expected.buildSessionId(), expected.tenantId(), expected.projectId(),
                expected.productLineId(),
                expected.requestIdempotencyKey(), expected.createdBy(), status,
                BuildSessionPhase.GENERATE_CANDIDATE, expected.requirementsArtifactRef(),
                expected.requirementsHash(), expected.selectedManagerWorkerBinding(),
                expected.selectedCodingWorkerBinding(), Optional.of(blueprintRef),
                expected.currentCandidateId(), expected.currentCandidateHash(),
                expected.currentCertificationId(), expected.repairRound(), expected.maxRepairRounds(),
                expected.startedAt(), expected.deadlineAt(), expected.cancellationRequestedAt(),
                expected.terminalCode(), expected.terminalMessage(), expected.version() + 1,
                expected.createdAt(), updatedAt));
    }

    private boolean repair(
            BuildSession expected,
            BuildSessionPhase phase,
            int repairRound,
            Instant updatedAt) {
        return buildSessions.compareAndSet(expected, new BuildSession(
                expected.buildSessionId(), expected.tenantId(), expected.projectId(),
                expected.productLineId(),
                expected.requestIdempotencyKey(), expected.createdBy(), BuildSessionStatus.REPAIRING,
                phase, expected.requirementsArtifactRef(), expected.requirementsHash(),
                expected.selectedManagerWorkerBinding(), expected.selectedCodingWorkerBinding(),
                expected.currentBlueprintRef(), expected.currentCandidateId(), expected.currentCandidateHash(),
                expected.currentCertificationId(), repairRound, expected.maxRepairRounds(),
                expected.startedAt(), expected.deadlineAt(), expected.cancellationRequestedAt(),
                expected.terminalCode(), expected.terminalMessage(), expected.version() + 1,
                expected.createdAt(), updatedAt));
    }

    private static BuildSession copy(
            BuildSession expected,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Instant updatedAt) {
        return new BuildSession(
                expected.buildSessionId(), expected.tenantId(), expected.projectId(),
                expected.productLineId(),
                expected.requestIdempotencyKey(), expected.createdBy(), status, phase,
                expected.requirementsArtifactRef(), expected.requirementsHash(),
                expected.selectedManagerWorkerBinding(), expected.selectedCodingWorkerBinding(),
                expected.currentBlueprintRef(), expected.currentCandidateId(), expected.currentCandidateHash(),
                expected.currentCertificationId(), expected.repairRound(), expected.maxRepairRounds(),
                expected.startedAt(), expected.deadlineAt(), expected.cancellationRequestedAt(),
                expected.terminalCode(), expected.terminalMessage(), expected.version() + 1,
                expected.createdAt(), updatedAt);
    }

    private static void validateIdentity(BuildSession session, StepContext context) {
        if (!session.projectId().value().equals(context.executionContext().correlationIdOrNull())) {
            throw new IllegalStateException("Flower correlationId does not match durable projectId");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
