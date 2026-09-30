package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAssembler;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyInspector;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchOperationIds;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchRunner;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseOutcome;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewResult;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRequestService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Drives the concrete six-stage Reference Assembly line from durable truth.
 *
 * <p>Each tick performs bounded local reads, deterministic artifact work, at most one ordinary
 * ledger projection, or one governed Action proposal. It never runs the deferred release runner
 * and never waits on a thread or an external system.
 */
public final class LedgerBackedReferenceAssemblyFlowCoordinator
        implements ReferenceAssemblyFlowCoordinator {
    public static final String SESSION_NOT_FOUND =
            "REFERENCE_ASSEMBLY_SESSION_NOT_FOUND";
    public static final String FLOW_IDENTITY_MISMATCH =
            "REFERENCE_ASSEMBLY_FLOW_IDENTITY_MISMATCH";
    public static final String SESSION_STATE_INVALID =
            "REFERENCE_ASSEMBLY_SESSION_STATE_INVALID";
    public static final String SESSION_CANCELLED =
            "REFERENCE_ASSEMBLY_SESSION_CANCELLED";
    public static final String DEADLINE_EXCEEDED =
            "REFERENCE_ASSEMBLY_DEADLINE_EXCEEDED";
    public static final String REQUEST_FAILED =
            "REFERENCE_ASSEMBLY_REQUEST_FAILED";
    public static final String COMPONENT_REJECTED =
            "REFERENCE_ASSEMBLY_COMPONENT_REJECTED";
    public static final String ASSEMBLY_FAILED =
            "REFERENCE_ASSEMBLY_ASSEMBLY_FAILED";
    public static final String INSPECTION_REJECTED =
            "REFERENCE_ASSEMBLY_INSPECTION_REJECTED";
    public static final String REVIEW_REJECTED =
            "REFERENCE_ASSEMBLY_REVIEW_REJECTED";
    public static final String RELEASE_OWNER_INVALID =
            "REFERENCE_ASSEMBLY_RELEASE_OWNER_INVALID";

    private final BuildSessionRepository buildSessions;
    private final ReferenceAssemblyRepository assemblies;
    private final DecisionPointRepository decisionPoints;
    private final RequestOperation requests;
    private final ResolveOperation resolver;
    private final AssembleOperation assembler;
    private final InspectOperation inspector;
    private final ReviewOperation reviews;
    private final ReferenceAssemblyReleaseLauncher releaseLauncher;
    private final ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents;
    private final ReleaseObservationOperation releaseObserver;
    private final RunStore actionRuns;
    private final Clock clock;

    public LedgerBackedReferenceAssemblyFlowCoordinator(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository assemblies,
            DecisionPointRepository decisionPoints,
            ReferenceAssemblyRequestService requests,
            ReferenceAssemblyAssembler assembler,
            ReferenceAssemblyInspector inspector,
            ReferenceAssemblyReleaseReviewService reviews,
            ReferenceAssemblyReleaseLauncher releaseLauncher,
            ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents,
            ReferenceAssemblyReleaseService releaseService,
            RunStore actionRuns,
            Clock clock) {
        this(
                buildSessions,
                assemblies,
                decisionPoints,
                requestOperation(requests),
                resolveOperation(assembler),
                assembleOperation(assembler),
                inspectOperation(inspector),
                reviewOperation(reviews),
                releaseLauncher,
                releaseIntents,
                releaseObservationOperation(releaseService),
                actionRuns,
                clock);
    }

    LedgerBackedReferenceAssemblyFlowCoordinator(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository assemblies,
            DecisionPointRepository decisionPoints,
            RequestOperation requests,
            ResolveOperation resolver,
            AssembleOperation assembler,
            InspectOperation inspector,
            ReviewOperation reviews,
            ReferenceAssemblyReleaseLauncher releaseLauncher,
            ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents,
            ReleaseObservationOperation releaseObserver,
            RunStore actionRuns,
            Clock clock) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.assemblies = Objects.requireNonNull(assemblies, "assemblies");
        this.decisionPoints = Objects.requireNonNull(decisionPoints, "decisionPoints");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.releaseLauncher = Objects.requireNonNull(releaseLauncher, "releaseLauncher");
        this.releaseIntents = Objects.requireNonNull(releaseIntents, "releaseIntents");
        this.releaseObserver = Objects.requireNonNull(releaseObserver, "releaseObserver");
        this.actionRuns = Objects.requireNonNull(actionRuns, "actionRuns");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StepResult advance(
            ReferenceAssemblyBuildPhase phase,
            BuildSessionId buildSessionId,
            StepContext context) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(context, "context");
        TenantId tenantId;
        try {
            tenantId = new TenantId(context.executionContext().tenantIdOrNull());
        } catch (RuntimeException invalidTenant) {
            return fail(FLOW_IDENTITY_MISMATCH, "Flower tenant identity is missing or invalid");
        }
        BuildSession session = buildSessions.find(tenantId, buildSessionId).orElse(null);
        if (session == null) {
            return fail(SESSION_NOT_FOUND, "trusted BuildSession is missing");
        }
        if (!hasExactFlowIdentity(session, context)) {
            return fail(
                    FLOW_IDENTITY_MISMATCH,
                    "Flower identity does not match the BuildSession ledger");
        }
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())) {
            return fail(SESSION_STATE_INVALID, "BuildSession belongs to another ProductLine");
        }
        if (session.status() == BuildSessionStatus.SUCCEEDED
                && session.currentPhase() == BuildSessionPhase.COMPLETE) {
            return phase == ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY
                    ? StepResult.done()
                    : recoveryDone(phase, session, tenantId);
        }
        if (session.status() == BuildSessionStatus.MANUAL_REVIEW
                || session.status() == BuildSessionStatus.BLOCKED
                || session.status() == BuildSessionStatus.FAILED) {
            return failFromSession(session);
        }
        if (session.cancellationRequestedAt().isPresent()
                || session.status() == BuildSessionStatus.CANCELLING
                || session.status() == BuildSessionStatus.CANCELLED) {
            return fail(SESSION_CANCELLED, "BuildSession cancellation authority stops assembly");
        }
        Instant observedAt = now();
        if (observedAt.isBefore(session.updatedAt())) {
            return StepResult.stay();
        }
        try {
            return switch (phase) {
                case ACCEPT_REFERENCE_REQUIREMENTS ->
                        accept(session, tenantId, observedAt);
                case RESOLVE_CERTIFIED_COMPONENT ->
                        resolve(session, tenantId, observedAt);
                case ASSEMBLE_REFERENCE_MANIFEST ->
                        assemble(session, tenantId, observedAt);
                case INSPECT_REFERENCE_ASSEMBLY ->
                        inspect(session, tenantId, observedAt);
                case WAIT_REFERENCE_RELEASE_REVIEW ->
                        review(session, tenantId, observedAt);
                case RELEASE_REFERENCE_ASSEMBLY ->
                        release(session, tenantId, context, observedAt);
            };
        } catch (RuntimeException boundedFailure) {
            return handleBoundedFailure(phase, session, boundedFailure, observedAt);
        }
    }

    private StepResult accept(
            BuildSession session, TenantId tenantId, Instant observedAt) {
        ReferenceAssembly existing = findAssembly(tenantId, session.buildSessionId());
        if (session.currentPhase() != BuildSessionPhase.UNDERSTAND_CUSTOMER) {
            return existing != null && phaseRank(session.currentPhase()) >= 1
                    ? StepResult.done()
                    : fail(SESSION_STATE_INVALID, "request phase differs from durable ledgers");
        }
        if (existing == null) {
            requests.ensureRequested(tenantId, session.buildSessionId());
            return StepResult.stay();
        }
        ReferenceAssembly assembly = existing;
        if (assembly.status() != ReferenceAssemblyStatus.REQUESTED) {
            return StepResult.stay();
        }
        BuildSession next = session.advanceReferenceAssemblyPhase(
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                monotonic(observedAt, assembly.updatedAt()));
        return buildSessions.compareAndSet(session, next)
                ? StepResult.done()
                : StepResult.stay();
    }

    private StepResult resolve(
            BuildSession session, TenantId tenantId, Instant observedAt) {
        ReferenceAssembly assembly = requireAssembly(tenantId, session.buildSessionId());
        if (phaseRank(session.currentPhase()) > 1
                && statusRank(assembly.status()) >= 1) {
            return StepResult.done();
        }
        requireSessionPhase(session, BuildSessionPhase.RESOLVE_REUSE_STRATEGY);
        if (assembly.status() == ReferenceAssemblyStatus.REQUESTED) {
            resolver.resolveComponent(tenantId, assembly.requirement());
            ReferenceAssembly resolved = assembly.resolveComponent(
                    monotonic(observedAt, assembly.updatedAt()));
            assemblies.compareAndSet(assembly, resolved);
            return StepResult.stay();
        }
        if (assembly.status() != ReferenceAssemblyStatus.COMPONENT_RESOLVED) {
            return invalidAggregate(assembly, COMPONENT_REJECTED);
        }
        BuildSession next = session.advanceReferenceAssemblyPhase(
                BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                BuildSessionPhase.ASSEMBLE_CANDIDATE,
                monotonic(observedAt, assembly.updatedAt()));
        return buildSessions.compareAndSet(session, next)
                ? StepResult.done()
                : StepResult.stay();
    }

    private StepResult assemble(
            BuildSession session, TenantId tenantId, Instant observedAt) {
        ReferenceAssembly assembly = requireAssembly(tenantId, session.buildSessionId());
        if (phaseRank(session.currentPhase()) > 2
                && statusRank(assembly.status()) >= 2) {
            return StepResult.done();
        }
        requireSessionPhase(session, BuildSessionPhase.ASSEMBLE_CANDIDATE);
        if (assembly.status() == ReferenceAssemblyStatus.COMPONENT_RESOLVED) {
            CertificationArtifactLock manifest =
                    assembler.assemble(tenantId, assembly.requirement());
            ReferenceAssembly assembled = assembly.assemble(
                    manifest, monotonic(observedAt, assembly.updatedAt()));
            assemblies.compareAndSet(assembly, assembled);
            return StepResult.stay();
        }
        if (assembly.status() != ReferenceAssemblyStatus.ASSEMBLED) {
            return invalidAggregate(assembly, ASSEMBLY_FAILED);
        }
        BuildSession next = session.advanceReferenceAssemblyPhase(
                BuildSessionPhase.ASSEMBLE_CANDIDATE,
                BuildSessionPhase.TEST,
                monotonic(observedAt, assembly.updatedAt()));
        return buildSessions.compareAndSet(session, next)
                ? StepResult.done()
                : StepResult.stay();
    }

    private StepResult inspect(
            BuildSession session, TenantId tenantId, Instant observedAt) {
        ReferenceAssembly assembly = requireAssembly(tenantId, session.buildSessionId());
        if (session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && assembly.status() == ReferenceAssemblyStatus.INSPECTED) {
            return StepResult.done();
        }
        requireSessionPhase(session, BuildSessionPhase.TEST);
        if (assembly.status() == ReferenceAssemblyStatus.ASSEMBLED) {
            InspectionProjection result = inspector.inspect(
                    tenantId, assembly.assemblyManifest().orElseThrow());
            ReferenceAssembly next = result.passed()
                    ? assembly.inspect(
                            result.reportLock(), monotonic(observedAt, assembly.updatedAt()))
                    : assembly.rejectAfterInspection(
                            result.reportLock(),
                            INSPECTION_REJECTED,
                            monotonic(observedAt, assembly.updatedAt()));
            assemblies.compareAndSet(assembly, next);
            return StepResult.stay();
        }
        if (assembly.status() == ReferenceAssemblyStatus.REJECTED) {
            return failAssemblySession(
                    session,
                    assembly.stableCode().orElse(INSPECTION_REJECTED),
                    "independent Reference Assembly inspection rejected the product",
                    observedAt);
        }
        if (assembly.status() != ReferenceAssemblyStatus.INSPECTED) {
            return invalidAggregate(assembly, INSPECTION_REJECTED);
        }
        BuildSession waiting = session.awaitReferenceAssemblyReleaseReview(
                monotonic(observedAt, assembly.updatedAt()));
        return buildSessions.compareAndSet(session, waiting)
                ? StepResult.done()
                : StepResult.stay();
    }

    private StepResult review(
            BuildSession session, TenantId tenantId, Instant observedAt) {
        ReferenceAssembly assembly = requireAssembly(tenantId, session.buildSessionId());
        if (session.currentPhase() == BuildSessionPhase.PACKAGE_RELEASE
                && session.status() == BuildSessionStatus.RUNNING) {
            return approvedReview(tenantId, assembly) ? StepResult.done() : fail(
                    SESSION_STATE_INVALID,
                    "release phase is not backed by the exact approved DecisionPoint");
        }
        if (assembly.status() == ReferenceAssemblyStatus.REJECTED) {
            return failAssemblySession(
                    session,
                    assembly.stableCode().orElse(REVIEW_REJECTED),
                    "Reference Assembly release review rejected the product",
                    observedAt);
        }
        if (session.status() != BuildSessionStatus.WAITING_RELEASE_REVIEW
                || session.currentPhase() != BuildSessionPhase.HUMAN_RELEASE_REVIEW
                || assembly.status() != ReferenceAssemblyStatus.INSPECTED) {
            return fail(SESSION_STATE_INVALID, "release-review phase differs from durable ledgers");
        }
        ReferenceAssemblyReleaseReviewResult result = reviews.ensureReleaseReview(
                tenantId, assembly.referenceAssemblyId());
        if (result.disposition()
                != ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT) {
            return StepResult.stay();
        }
        return switch (result.decisionPoint().status()) {
            case OPEN -> StepResult.stay();
            case APPROVED -> {
                BuildSession packaging = session.resumeReferenceAssemblyRelease(
                        monotonic(observedAt, result.referenceAssembly().updatedAt()));
                yield buildSessions.compareAndSet(session, packaging)
                        ? StepResult.done()
                        : StepResult.stay();
            }
            case CHANGES_REQUESTED, REJECTED, EXPIRED, STALE, CANCELLED -> {
                ReferenceAssembly rejected = result.referenceAssembly().reject(
                        reviewCode(result.decisionPoint().status()),
                        monotonic(observedAt, result.referenceAssembly().updatedAt()));
                assemblies.compareAndSet(result.referenceAssembly(), rejected);
                yield StepResult.stay();
            }
        };
    }

    private StepResult release(
            BuildSession session,
            TenantId tenantId,
            StepContext context,
            Instant observedAt) {
        ReferenceAssembly assembly = requireAssembly(tenantId, session.buildSessionId());
        if (assembly.status() == ReferenceAssemblyStatus.RELEASED) {
            return completeReleased(session, assembly, observedAt);
        }
        if (session.status() != BuildSessionStatus.RUNNING
                || session.currentPhase() != BuildSessionPhase.PACKAGE_RELEASE
                || assembly.status() != ReferenceAssemblyStatus.INSPECTED
                || !approvedReview(tenantId, assembly)) {
            return fail(SESSION_STATE_INVALID, "release phase differs from approved durable ledgers");
        }
        ReferenceAssemblyReleaseDispatchIntent intent = releaseIntents
                .findLatest(tenantId, assembly.referenceAssemblyId())
                .orElse(null);
        if (assembly.releaseActionRunId().isEmpty()) {
            if (intent != null || !observedAt.isBefore(session.deadlineAt())) {
                return intent != null
                        ? manual(session, RELEASE_OWNER_INVALID, "unbound release has an intent", observedAt)
                        : failAssemblySession(
                                session,
                                DEADLINE_EXCEEDED,
                                "Reference Assembly release deadline exceeded",
                                observedAt);
            }
            try {
                var result = releaseLauncher.ensureProposed(
                        session, assembly, context.executionContext(), observedAt);
                if (result.status() == ActionExecutionStatus.ACCEPTED
                        || result.status() == ActionExecutionStatus.SUCCEEDED
                        || (result.status() == ActionExecutionStatus.DENIED
                                && "DUPLICATE_ACTION".equals(result.code()))) {
                    return StepResult.stay();
                }
            } catch (RuntimeException proposalFailure) {
                // The dispatch transaction may have committed its owner binding and intent
                // before the caller lost the result. Durable ledgers, not the transport
                // outcome, decide whether this tick can safely continue.
            }
            ReferenceAssembly refreshed = requireAssembly(tenantId, session.buildSessionId());
            return releaseIntents
                            .findLatest(tenantId, assembly.referenceAssemblyId())
                            .filter(candidate -> matchesExactIntent(session, refreshed, candidate))
                            .isPresent()
                    ? StepResult.stay()
                    : manual(
                            session,
                            RELEASE_OWNER_INVALID,
                            "release proposal failed before an exact intent existed",
                            observedAt);
        }
        if (intent == null || !matchesExactIntent(session, assembly, intent)) {
            return manual(
                    session,
                    RELEASE_OWNER_INVALID,
                    "release Action owner or durable intent is missing or mismatched",
                    observedAt);
        }
        if (!intent.status().isTerminal()) {
            return StepResult.stay();
        }
        return manual(
                session,
                RELEASE_OWNER_INVALID,
                "release intent terminalized without a canonical shipment: " + intent.status(),
                observedAt);
    }

    private StepResult completeReleased(
            BuildSession session, ReferenceAssembly assembly, Instant observedAt) {
        ReferenceAssemblyReleaseDispatchIntent intent = releaseIntents
                .findLatest(assembly.tenantId(), assembly.referenceAssemblyId())
                .orElse(null);
        if (intent == null) {
            return manual(
                    session,
                    RELEASE_OWNER_INVALID,
                    "released ledger lacks its durable V14 release intent",
                    observedAt);
        }
        if (!matchesExactIntent(session, assembly, intent)) {
            return manual(
                    session,
                    RELEASE_OWNER_INVALID,
                    "released ledger has a mismatched durable V14 release intent",
                    observedAt);
        }
        if (!intent.status().isTerminal()) {
            return StepResult.stay();
        }
        if (intent.status() != ReferenceAssemblyReleaseDispatchIntentStatus.COMPLETED) {
            return manual(
                    session,
                    RELEASE_OWNER_INVALID,
                    "released ledger has a terminal non-completed exact intent: "
                            + intent.status(),
                    observedAt);
        }
        if (intent.lastCode()
                .filter(ReferenceAssemblyReleaseDispatchRunner.DISPATCH_COMPLETED::equals)
                .isEmpty()) {
            return manual(
                    session,
                    RELEASE_OWNER_INVALID,
                    "released ledger has COMPLETED intent without the exact dispatch success code",
                    observedAt);
        }
        ReferenceAssemblyReleaseOutcome outcome = releaseObserver.observeReleased(intent)
                .orElse(null);
        ActionRun actionRun = actionRuns.find(intent.actionRunId()).orElse(null);
        if (outcome == null
                || actionRun == null
                || !ReferenceAssemblyReleaseDispatchRunner.hasExactTerminalOwnerBinding(
                        intent, actionRun, assembly)
                || !ReferenceAssemblyReleaseDispatchRunner.exactTerminalResult(
                        outcome, actionRun.result())) {
            return manual(
                    session,
                    RELEASE_OWNER_INVALID,
                    "released ledger lacks its exact successful Action evidence",
                    observedAt);
        }
        if (session.status() == BuildSessionStatus.SUCCEEDED
                && session.currentPhase() == BuildSessionPhase.COMPLETE) {
            return StepResult.done();
        }
        BuildSession completed = session.completeReferenceAssemblyRelease(
                monotonic(observedAt, assembly.updatedAt()));
        return buildSessions.compareAndSet(session, completed)
                ? StepResult.done()
                : StepResult.stay();
    }

    private boolean approvedReview(TenantId tenantId, ReferenceAssembly assembly) {
        if (assembly.releaseDecisionPointId().isEmpty()
                || assembly.releaseSubjectHash().isEmpty()) {
            return false;
        }
        DecisionPoint point = decisionPoints
                .find(tenantId, assembly.releaseDecisionPointId().orElseThrow())
                .orElse(null);
        return point != null
                && point.status() == DecisionPointStatus.APPROVED
                && point.tenantId().equals(tenantId)
                && point.buildSessionId().equals(assembly.buildSessionId())
                && ReferenceAssemblyReleaseReviewService.DECISION_TYPE.equals(point.type())
                && ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE.equals(point.subjectType())
                && point.subjectId().equals(assembly.referenceAssemblyId().value())
                && point.subjectHash().equals(assembly.releaseSubjectHash().orElseThrow());
    }

    private static boolean matchesExactIntent(
            BuildSession session,
            ReferenceAssembly assembly,
            ReferenceAssemblyReleaseDispatchIntent intent) {
        if (!intent.tenantId().equals(assembly.tenantId())
                || !intent.referenceAssemblyId().equals(assembly.referenceAssemblyId())
                || !intent.deadlineAt().equals(session.deadlineAt())
                || assembly.assemblyManifest()
                        .map(lock -> !lock.hash().equals(intent.assemblyManifestHash()))
                        .orElse(true)
                || assembly.inspectionReport()
                        .map(lock -> !lock.hash().equals(intent.inspectionReportHash()))
                        .orElse(true)
                || assembly.releaseDecisionPointId()
                        .filter(intent.releaseDecisionPointId()::equals)
                        .isEmpty()
                || assembly.releaseSubjectHash()
                        .filter(intent.releaseSubjectHash()::equals)
                        .isEmpty()
                || assembly.releaseActionRunId().filter(intent.actionRunId()::equals).isEmpty()) {
            return false;
        }
        ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                intent.referenceAssemblyId(),
                intent.assemblyManifestHash(),
                intent.inspectionReportHash(),
                intent.releaseDecisionPointId(),
                intent.releaseSubjectHash(),
                intent.expectedReferenceAssemblyVersion());
        return intent.operationId().equals(
                ReferenceAssemblyReleaseDispatchOperationIds.derive(intent.tenantId(), input));
    }

    private StepResult recoveryDone(
            ReferenceAssemblyBuildPhase phase, BuildSession session, TenantId tenantId) {
        ReferenceAssembly assembly = findAssembly(tenantId, session.buildSessionId());
        if (assembly == null || assembly.status() != ReferenceAssemblyStatus.RELEASED) {
            return fail(SESSION_STATE_INVALID, "completed session lacks its released product ledger");
        }
        return StepResult.done();
    }

    private StepResult handleBoundedFailure(
            ReferenceAssemblyBuildPhase phase,
            BuildSession session,
            RuntimeException failure,
            Instant observedAt) {
        String code = switch (phase) {
            case ACCEPT_REFERENCE_REQUIREMENTS -> REQUEST_FAILED;
            case RESOLVE_CERTIFIED_COMPONENT -> COMPONENT_REJECTED;
            case ASSEMBLE_REFERENCE_MANIFEST -> ASSEMBLY_FAILED;
            case INSPECT_REFERENCE_ASSEMBLY -> INSPECTION_REJECTED;
            case WAIT_REFERENCE_RELEASE_REVIEW -> REVIEW_REJECTED;
            case RELEASE_REFERENCE_ASSEMBLY -> RELEASE_OWNER_INVALID;
        };
        return manual(
                session,
                code,
                "bounded Reference Assembly operation failed closed",
                observedAt);
    }

    private StepResult failAssemblySession(
            BuildSession session, String code, String message, Instant observedAt) {
        try {
            BuildSession failed = session.failReferenceAssembly(code, message, observedAt);
            return buildSessions.compareAndSet(session, failed)
                    ? fail(code, message)
                    : StepResult.stay();
        } catch (RuntimeException invalidProjection) {
            return fail(SESSION_STATE_INVALID, "Reference Assembly failure cannot be projected");
        }
    }

    private StepResult manual(
            BuildSession session, String code, String message, Instant observedAt) {
        try {
            BuildSession review = session.manualReviewReferenceAssembly(
                    code, message, observedAt);
            return buildSessions.compareAndSet(session, review)
                    ? fail(code, message)
                    : StepResult.stay();
        } catch (RuntimeException invalidProjection) {
            return fail(SESSION_STATE_INVALID, "Reference Assembly manual review cannot be projected");
        }
    }

    private static StepResult invalidAggregate(
            ReferenceAssembly assembly, String code) {
        return fail(code, "Reference Assembly ledger is at unexpected status " + assembly.status());
    }

    private ReferenceAssembly requireAssembly(
            TenantId tenantId, BuildSessionId buildSessionId) {
        ReferenceAssembly assembly = findAssembly(tenantId, buildSessionId);
        if (assembly == null) {
            throw new IllegalStateException("Reference Assembly ledger is missing");
        }
        return assembly;
    }

    private ReferenceAssembly findAssembly(
            TenantId tenantId, BuildSessionId buildSessionId) {
        return assemblies.findByBuildSession(tenantId, buildSessionId).orElse(null);
    }

    private static void requireSessionPhase(
            BuildSession session, BuildSessionPhase expected) {
        if (session.status() != BuildSessionStatus.RUNNING
                || session.currentPhase() != expected) {
            throw new IllegalStateException("BuildSession is outside " + expected);
        }
    }

    private static int statusRank(ReferenceAssemblyStatus status) {
        return switch (status) {
            case REQUESTED -> 0;
            case COMPONENT_RESOLVED -> 1;
            case ASSEMBLED -> 2;
            case INSPECTED -> 3;
            case RELEASED -> 4;
            case REJECTED -> -1;
        };
    }

    private static int phaseRank(BuildSessionPhase phase) {
        return switch (phase) {
            case UNDERSTAND_CUSTOMER -> 0;
            case RESOLVE_REUSE_STRATEGY -> 1;
            case ASSEMBLE_CANDIDATE -> 2;
            case TEST -> 3;
            case HUMAN_RELEASE_REVIEW -> 4;
            case PACKAGE_RELEASE -> 5;
            case COMPLETE -> 6;
            default -> -1;
        };
    }

    private static String reviewCode(DecisionPointStatus status) {
        return "REFERENCE_ASSEMBLY_REVIEW_" + status.name();
    }

    private static RequestOperation requestOperation(ReferenceAssemblyRequestService service) {
        Objects.requireNonNull(service, "requests");
        return (tenantId, buildSessionId) -> service
                .ensureRequested(tenantId, buildSessionId)
                .referenceAssembly();
    }

    private static ResolveOperation resolveOperation(ReferenceAssemblyAssembler service) {
        Objects.requireNonNull(service, "assembler");
        return service::resolveComponent;
    }

    private static AssembleOperation assembleOperation(ReferenceAssemblyAssembler service) {
        Objects.requireNonNull(service, "assembler");
        return (tenantId, requirement) ->
                service.assemble(tenantId, requirement).manifestLock();
    }

    private static InspectOperation inspectOperation(ReferenceAssemblyInspector service) {
        Objects.requireNonNull(service, "inspector");
        return (tenantId, manifest) -> {
            ReferenceAssemblyInspector.InspectionResult result =
                    service.inspect(tenantId, manifest);
            return new InspectionProjection(result.passed(), result.reportLock());
        };
    }

    private static ReviewOperation reviewOperation(
            ReferenceAssemblyReleaseReviewService service) {
        Objects.requireNonNull(service, "reviews");
        return service::ensureReleaseReview;
    }

    private static ReleaseObservationOperation releaseObservationOperation(
            ReferenceAssemblyReleaseService service) {
        Objects.requireNonNull(service, "releaseService");
        return service::observeReleased;
    }

    private static Instant monotonic(Instant observedAt, Instant other) {
        return observedAt.isBefore(other) ? other : observedAt;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean hasExactFlowIdentity(
            BuildSession session, StepContext context) {
        var identity = context.executionContext();
        return session.tenantId().value().equals(identity.tenantIdOrNull())
                && session.createdBy().equals(identity.userIdOrNull())
                && session.buildSessionId().value().equals(identity.sessionIdOrNull())
                && session.projectId().value().equals(identity.correlationIdOrNull())
                && identity.runIdOrNull() != null
                && !identity.runIdOrNull().isBlank()
                && identity.traceIdOrNull() != null
                && !identity.traceIdOrNull().isBlank();
    }

    private static StepResult failFromSession(BuildSession session) {
        return fail(
                session.terminalCode().orElse(SESSION_STATE_INVALID),
                session.terminalMessage().orElse("BuildSession is no longer runnable"));
    }

    private static StepResult fail(String code, String message) {
        return StepResult.fail(new IllegalStateException(code + ": " + message));
    }

    @FunctionalInterface
    interface RequestOperation {
        ReferenceAssembly ensureRequested(TenantId tenantId, BuildSessionId buildSessionId);
    }

    @FunctionalInterface
    interface ResolveOperation {
        void resolveComponent(TenantId tenantId, CertificationArtifactLock requirement);
    }

    @FunctionalInterface
    interface AssembleOperation {
        CertificationArtifactLock assemble(
                TenantId tenantId, CertificationArtifactLock requirement);
    }

    @FunctionalInterface
    interface InspectOperation {
        InspectionProjection inspect(
                TenantId tenantId, CertificationArtifactLock assemblyManifest);
    }

    @FunctionalInterface
    interface ReviewOperation {
        ReferenceAssemblyReleaseReviewResult ensureReleaseReview(
                TenantId tenantId,
                io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId referenceAssemblyId);
    }

    @FunctionalInterface
    interface ReleaseObservationOperation {
        Optional<ReferenceAssemblyReleaseOutcome> observeReleased(
                ReferenceAssemblyReleaseDispatchIntent intent);
    }

    record InspectionProjection(boolean passed, CertificationArtifactLock reportLock) {
        InspectionProjection {
            Objects.requireNonNull(reportLock, "reportLock");
        }
    }
}
