package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.ActionBackedAgentPackCertificationLauncher;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestOutcome;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequester;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentStatus;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertificationRequestDisposition;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Reconciles the durable request, deferred Action owner and BuildSession projection.
 *
 * <p>One tick performs only bounded ledger reads, at most one governed Action proposal and at
 * most one BuildSession CAS. It never calls the issuance runner or waits for external work.
 */
public final class LedgerBackedAgentPackCertificationFlowCoordinator
        implements AgentPackCertificationFlowCoordinator {
    public static final String SESSION_NOT_FOUND = "CERTIFICATION_SESSION_NOT_FOUND";
    public static final String FLOW_IDENTITY_MISMATCH = "CERTIFICATION_FLOW_IDENTITY_MISMATCH";
    public static final String SESSION_CANCELLED = "CERTIFICATION_SESSION_CANCELLED";
    public static final String DEADLINE_EXCEEDED = "CERTIFICATION_DEADLINE_EXCEEDED";
    public static final String REQUEST_CONFLICT = "CERTIFICATION_REQUEST_CONFLICT";
    public static final String REQUEST_FAILED = "CERTIFICATION_REQUEST_FAILED";
    public static final String SUBJECT_MISMATCH = "CERTIFICATION_SUBJECT_MISMATCH";
    public static final String ACTION_OWNER_INVALID = "CERTIFICATION_ACTION_OWNER_INVALID";
    public static final String NOT_CERTIFIED = "CERTIFICATION_NOT_CERTIFIED";
    public static final String REVOKED = "CERTIFICATION_REVOKED";
    public static final String SESSION_STATE_INVALID = "CERTIFICATION_SESSION_STATE_INVALID";

    private final BuildSessionRepository buildSessions;
    private final CertificationRepository certifications;
    private final CertificationDispatchIntentRepository intents;
    private final AgentPackCertificationRequester requester;
    private final ActionBackedAgentPackCertificationLauncher launcher;
    private final CertificationActionEvidenceOwner evidenceOwner;
    private final Clock clock;

    public LedgerBackedAgentPackCertificationFlowCoordinator(
            BuildSessionRepository buildSessions,
            CertificationRepository certifications,
            CertificationDispatchIntentRepository intents,
            AgentPackCertificationRequester requester,
            ActionBackedAgentPackCertificationLauncher launcher,
            CertificationActionEvidenceOwner evidenceOwner,
            Clock clock) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.certifications = Objects.requireNonNull(certifications, "certifications");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.evidenceOwner = Objects.requireNonNull(evidenceOwner, "evidenceOwner");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StepResult advance(BuildSessionId buildSessionId, StepContext context) {
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
            return fail(FLOW_IDENTITY_MISMATCH, "Flower identity does not match the BuildSession ledger");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(session.updatedAt())) {
            return StepResult.stay();
        }
        if (session.cancellationRequestedAt().isPresent()
                || session.status() == BuildSessionStatus.CANCELLING
                || session.status() == BuildSessionStatus.CANCELLED) {
            return fail(SESSION_CANCELLED, "BuildSession cancellation authority stops certification");
        }
        if (session.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && session.currentCertificationId().isPresent()) {
            return recoverBoundCertification(session);
        }
        if (session.status() == BuildSessionStatus.BLOCKED
                || session.status() == BuildSessionStatus.MANUAL_REVIEW
                || session.status().isTerminal()) {
            return failFromSession(session);
        }
        if (!ProductLineId.AGENT_PACK.equals(session.productLineId())
                || session.currentCandidateId().isEmpty()
                || session.currentCandidateHash().isEmpty()) {
            return fail(SESSION_STATE_INVALID, "BuildSession is not an exact Agent Pack candidate authority");
        }
        if (session.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW) {
            return requestAndPropose(session, context, now);
        }
        if (session.status() == BuildSessionStatus.CERTIFYING
                && session.currentPhase() == BuildSessionPhase.CERTIFY) {
            return reconcileCertifying(session, context, now);
        }
        return fail(SESSION_STATE_INVALID, "BuildSession is outside the certification continuation");
    }

    private StepResult requestAndPropose(BuildSession session, StepContext context, Instant now) {
        if (!now.isBefore(session.deadlineAt())) {
            return block(session, DEADLINE_EXCEEDED, "Certification request deadline exceeded", now);
        }
        AgentPackCertificationRequestOutcome outcome;
        try {
            outcome = requester.ensureRequested(session.tenantId(), session.buildSessionId());
        } catch (RuntimeException requestFailure) {
            return manual(
                    session,
                    REQUEST_FAILED,
                    "trusted Certification request construction failed closed",
                    now);
        }
        if (outcome.disposition() == CertificationRequestDisposition.CONFLICT) {
            return manual(
                    session,
                    REQUEST_CONFLICT,
                    "Certification request transaction reported an exact-state conflict",
                    now);
        }
        Certification certification = outcome.certification();
        BuildSession certifyingSession = outcome.buildSession();
        if (!matchesExactSubject(certifyingSession, certification)
                || certifyingSession.status() != BuildSessionStatus.CERTIFYING
                || certifyingSession.currentPhase() != BuildSessionPhase.CERTIFY
                || certification.status() != CertificationStatus.REQUESTED) {
            return fail(REQUEST_CONFLICT, "Certification request returned a different durable authority");
        }
        return proposeOnce(certifyingSession, certification, context);
    }

    private StepResult reconcileCertifying(BuildSession session, StepContext context, Instant now) {
        Certification certification = certifications.findLatestForCandidate(
                        session.tenantId(),
                        session.buildSessionId(),
                        session.currentCandidateId().orElseThrow(),
                        session.currentCandidateHash().orElseThrow())
                .orElse(null);
        if (certification == null) {
            return StepResult.stay();
        }
        if (!matchesExactSubject(session, certification)) {
            return manual(session, SUBJECT_MISMATCH, "latest Certification has a different exact subject", now);
        }
        return switch (certification.status()) {
            case REQUESTED -> reconcileRequested(session, certification, context, now);
            case CERTIFIED -> reconcileCertified(session, certification, now);
            case NOT_CERTIFIED -> block(
                    session,
                    NOT_CERTIFIED,
                    "Certification was rejected: "
                            + certification.stableCode().orElse(NOT_CERTIFIED),
                    now);
            case REVOKED -> manual(
                    session,
                    REVOKED,
                    "Certification was revoked before release review",
                    now);
        };
    }

    private StepResult reconcileRequested(
            BuildSession session,
            Certification certification,
            StepContext context,
            Instant now) {
        CertificationDispatchIntent intent = intents
                .findLatest(session.tenantId(), certification.certificationId())
                .orElse(null);
        if (intent == null) {
            if (!now.isBefore(session.deadlineAt())) {
                return block(session, DEADLINE_EXCEEDED, "Certification issuance deadline exceeded", now);
            }
            return proposeOnce(session, certification, context);
        }
        if (!matchesExactIntent(certification, intent)) {
            return manual(session, ACTION_OWNER_INVALID, "Certification intent identity is invalid", now);
        }
        if (intent.status() == CertificationDispatchIntentStatus.PENDING
                || intent.status() == CertificationDispatchIntentStatus.UNCERTAIN
                || intent.status() == CertificationDispatchIntentStatus.RUNNING) {
            CertificationActionEvidenceOwner.Assessment assessment = evidenceOwner.assess(certification);
            return switch (assessment.status()) {
                case PENDING -> StepResult.stay();
                case ORPHANED, INVALID_TERMINAL, CANONICAL_SUCCEEDED -> manual(
                        session,
                        ACTION_OWNER_INVALID,
                        "Certification Action owner is invalid: " + assessment.code(),
                        now);
            };
        }
        return manual(
                session,
                ACTION_OWNER_INVALID,
                "Certification Action intent is terminal without a certified ledger: "
                        + intent.status(),
                now);
    }

    private StepResult reconcileCertified(
            BuildSession session, Certification certification, Instant now) {
        CertificationActionEvidenceOwner.Assessment assessment = evidenceOwner.assess(certification);
        return switch (assessment.status()) {
            case PENDING -> StepResult.stay();
            case CANONICAL_SUCCEEDED -> {
                BuildSession waiting;
                try {
                    // Issuance itself enforced the deadline. This projection remains recoverable
                    // after that deadline if a crash occurred after Certification CAS.
                    waiting = session.awaitAgentPackReleaseReview(certification.certificationId(), now);
                } catch (RuntimeException invalidProjection) {
                    yield fail(SESSION_STATE_INVALID, "canonical Certification cannot be bound");
                }
                yield buildSessions.compareAndSet(session, waiting)
                        ? StepResult.done()
                        : StepResult.stay();
            }
            case ORPHANED, INVALID_TERMINAL -> manual(
                    session,
                    ACTION_OWNER_INVALID,
                    "Certification Action owner is invalid: " + assessment.code(),
                    now);
        };
    }

    private StepResult recoverBoundCertification(BuildSession session) {
        Certification certification = certifications
                .find(session.tenantId(), session.currentCertificationId().orElseThrow())
                .orElse(null);
        if (certification == null
                || certification.status() != CertificationStatus.CERTIFIED
                || !matchesExactSubject(session, certification)) {
            return fail(SUBJECT_MISMATCH, "bound Certification is missing or no longer exact");
        }
        CertificationActionEvidenceOwner.Assessment assessment = evidenceOwner.assess(certification);
        return assessment.status() == CertificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED
                ? StepResult.done()
                : fail(ACTION_OWNER_INVALID, "bound Certification Action owner is not canonical");
    }

    private StepResult proposeOnce(
            BuildSession session,
            Certification certification,
            StepContext context) {
        Instant proposedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (proposedAt.isBefore(session.updatedAt())) {
            proposedAt = session.updatedAt();
        }
        if (!proposedAt.isBefore(session.deadlineAt())) {
            return block(
                    session,
                    DEADLINE_EXCEEDED,
                    "Certification Action proposal deadline exceeded",
                    proposedAt);
        }
        try {
            var result = launcher.ensureProposed(
                    session, certification, context.executionContext(), proposedAt);
            if (result.status() == ActionExecutionStatus.ACCEPTED
                    || result.status() == ActionExecutionStatus.SUCCEEDED
                    || (result.status() == ActionExecutionStatus.DENIED
                            && "DUPLICATE_ACTION".equals(result.code()))) {
                return StepResult.stay();
            }
        } catch (RuntimeException proposalFailure) {
            // Action/intent ledgers decide the next tick. A transport or policy failure must not
            // be mistaken for Certification issuance or trigger an ungoverned fallback.
        }
        CertificationDispatchIntent exactIntent = intents
                .findLatest(session.tenantId(), certification.certificationId())
                .filter(value -> matchesExactIntent(certification, value))
                .orElse(null);
        if (exactIntent != null) {
            return StepResult.stay();
        }
        return manual(
                session,
                REQUEST_FAILED,
                "Certification Action proposal failed before an exact durable intent existed",
                proposedAt);
    }

    private StepResult block(BuildSession session, String code, String message, Instant now) {
        BuildSession blocked;
        try {
            blocked = session.blockAgentPackCertification(code, message, now);
        } catch (RuntimeException invalidTransition) {
            return fail(SESSION_STATE_INVALID, "BuildSession cannot persist a blocked certification outcome");
        }
        return buildSessions.compareAndSet(session, blocked)
                ? fail(code, message)
                : StepResult.stay();
    }

    private StepResult manual(BuildSession session, String code, String message, Instant now) {
        BuildSession review;
        try {
            review = session.manualReviewAgentPackCertification(code, message, now);
        } catch (RuntimeException invalidTransition) {
            return fail(SESSION_STATE_INVALID, "BuildSession cannot persist certification manual review");
        }
        return buildSessions.compareAndSet(session, review)
                ? fail(code, message)
                : StepResult.stay();
    }

    private static boolean matchesExactSubject(BuildSession session, Certification certification) {
        if (session == null || certification == null) {
            return false;
        }
        var lock = certification.inputLock();
        return ProductLineId.AGENT_PACK.equals(session.productLineId())
                && session.tenantId().equals(lock.tenantId())
                && session.buildSessionId().equals(lock.buildSessionId())
                && ProductLineId.AGENT_PACK.equals(lock.productLineId())
                && lock.artifactType() == CertifiedArtifactType.AGENT_PACK
                && session.currentCandidateId().filter(lock.candidateId()::equals).isPresent()
                && session.currentCandidateHash().filter(lock.candidateHash()::equals).isPresent();
    }

    private static boolean matchesExactIntent(
            Certification certification, CertificationDispatchIntent intent) {
        CertificationIssueInput input = new CertificationIssueInput(
                certification.certificationId(),
                certification.inputLockArtifact().hash(),
                certification.version());
        return intent.tenantId().equals(certification.inputLock().tenantId())
                && intent.certificationId().equals(certification.certificationId())
                && intent.inputLockManifestHash().equals(certification.inputLockArtifact().hash())
                && intent.expectedCertificationVersion() == certification.version()
                && intent.operationId().equals(
                        CertificationDispatchOperationIds.derive(intent.tenantId(), input));
    }

    private static boolean hasExactFlowIdentity(BuildSession session, StepContext context) {
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
}
