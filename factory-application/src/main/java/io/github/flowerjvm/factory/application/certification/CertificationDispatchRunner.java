package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded Action-owned consumer for deterministic Certification issuance intents. */
public final class CertificationDispatchRunner {
    public static final String PARK_UNCERTAIN = "CERTIFICATION_ACTION_PARK_UNCERTAIN";
    public static final String ORPHANED_BEFORE_WAITING =
            "CERTIFICATION_ACTION_ORPHANED_BEFORE_WAITING";
    public static final String ACTION_OWNER_INVALID = "CERTIFICATION_ACTION_OWNER_INVALID";
    public static final String ACTION_TERMINAL_CONFLICT = "CERTIFICATION_ACTION_TERMINAL_CONFLICT";
    public static final String CERTIFICATION_STALE = "CERTIFICATION_STALE";
    public static final String BUILD_SESSION_NOT_CERTIFIABLE = "BUILD_SESSION_NOT_CERTIFIABLE";
    public static final String CERTIFICATION_POLICY_MISMATCH = "CERTIFICATION_POLICY_MISMATCH";
    public static final String CERTIFICATION_DISPATCH_COMPLETED = "CERTIFICATION_DISPATCH_COMPLETED";

    public static final String CERTIFICATION_STATUS = "certificationStatus";
    public static final String CERTIFICATION_MANIFEST_REF = "certificationManifestRef";
    public static final String CERTIFICATION_MANIFEST_HASH = "certificationManifestHash";
    public static final String CERTIFICATION_EVIDENCE_REF = "certificationEvidenceRef";
    public static final String CERTIFICATION_EVIDENCE_HASH = "certificationEvidenceHash";

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_PARK_GRACE = Duration.ofSeconds(30);
    public static final Duration PARK_OBSERVATION_BACKOFF = Duration.ofMillis(250);

    private final CertificationDispatchIntentRepository intents;
    private final CertificationRepository certifications;
    private final BuildSessionRepository buildSessions;
    private final AgentPackCertificationPolicyCatalog trustedPolicy;
    private final CertificationIssuanceService issuanceService;
    private final CompletableActionRuntime actionRuntime;
    private final RunStore runStore;
    private final Clock clock;
    private final Duration lease;
    private final Duration parkGrace;

    public CertificationDispatchRunner(
            CertificationDispatchIntentRepository intents,
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            AgentPackCertificationPolicy trustedPolicy,
            CertificationIssuanceService issuanceService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock) {
        this(
                intents,
                certifications,
                buildSessions,
                trustedPolicy,
                issuanceService,
                actionRuntime,
                runStore,
                clock,
                DEFAULT_LEASE,
                DEFAULT_PARK_GRACE);
    }

    public CertificationDispatchRunner(
            CertificationDispatchIntentRepository intents,
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            AgentPackCertificationPolicy trustedPolicy,
            CertificationIssuanceService issuanceService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock,
            Duration lease,
            Duration parkGrace) {
        this(intents, certifications, buildSessions, AgentPackCertificationPolicyCatalog.singleton(trustedPolicy),
                issuanceService, actionRuntime, runStore, clock, lease, parkGrace);
    }

    public CertificationDispatchRunner(
            CertificationDispatchIntentRepository intents,
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            AgentPackCertificationPolicyCatalog trustedPolicy,
            CertificationIssuanceService issuanceService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock) {
        this(intents, certifications, buildSessions, trustedPolicy, issuanceService, actionRuntime,
                runStore, clock, DEFAULT_LEASE, DEFAULT_PARK_GRACE);
    }

    public CertificationDispatchRunner(
            CertificationDispatchIntentRepository intents,
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            AgentPackCertificationPolicyCatalog trustedPolicy,
            CertificationIssuanceService issuanceService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock,
            Duration lease,
            Duration parkGrace) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.certifications = Objects.requireNonNull(certifications, "certifications");
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.trustedPolicy = Objects.requireNonNull(trustedPolicy, "trustedPolicy");
        this.issuanceService = Objects.requireNonNull(issuanceService, "issuanceService");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = requirePositive(lease, "lease");
        this.parkGrace = requirePositive(parkGrace, "parkGrace");
    }

    /** Claims and processes at most one durable intent. */
    public boolean tickOnce() {
        Instant now = now();
        String recoveryClaim = UUID.randomUUID().toString();
        Optional<CertificationDispatchIntent> recovered =
                intents.claimExpiredRunning(now, lease, recoveryClaim);
        if (recovered.isPresent()) {
            process(recovered.orElseThrow(), recoveryClaim, now);
            return true;
        }

        String claim = UUID.randomUUID().toString();
        Optional<CertificationDispatchIntent> next = intents.claimNext(now, lease, claim);
        if (next.isEmpty()) {
            return false;
        }
        process(next.orElseThrow(), claim, now);
        return true;
    }

    public int drain(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        int processed = 0;
        while (processed < maximum && tickOnce()) {
            processed++;
        }
        return processed;
    }

    private void process(
            CertificationDispatchIntent intent, String claimToken, Instant observedAt) {
        if (intent.status() != CertificationDispatchIntentStatus.RUNNING
                || intent.claimToken().filter(claimToken::equals).isEmpty()) {
            return;
        }
        ActionRun actionRun = runStore.find(intent.actionRunId()).orElse(null);
        Certification certification = certifications
                .find(intent.tenantId(), intent.certificationId())
                .orElse(null);
        if (!hasImmutableOwnerBinding(intent, actionRun, certification)) {
            terminalize(
                    intent, intent.orphan(claimToken, ACTION_OWNER_INVALID, observedAt));
            return;
        }

        if (actionRun.status() == ActionRunStatus.RUNNING) {
            handlePrePark(intent, claimToken, observedAt);
            return;
        }
        if (actionRun.status().isTerminal()) {
            reconcileTerminalAction(intent, claimToken, observedAt, actionRun);
            return;
        }
        if (!hasWaitingOwnerBinding(intent, actionRun)) {
            terminalize(
                    intent, intent.orphan(claimToken, ACTION_OWNER_INVALID, observedAt));
            return;
        }

        if (!trustedPolicy.matches(certification.inputLock())) {
            completeFailure(intent, claimToken, observedAt, actionRun, CERTIFICATION_POLICY_MISMATCH,
                    "current trusted Agent Pack certification profile or provenance does not match");
            return;
        }
        Optional<CertificationIssuanceOutcome> issued = issuanceService.observeIssued(intent);
        if (issued.isPresent()) {
            completeSuccess(intent, claimToken, observedAt, actionRun, issued.orElseThrow());
            return;
        }
        if (!isExactRequested(intent, certification)) {
            completeFailure(
                    intent,
                    claimToken,
                    observedAt,
                    actionRun,
                    CERTIFICATION_STALE,
                    "Certification is no longer the exact REQUESTED version");
            return;
        }
        BuildSession session = buildSessions
                .find(intent.tenantId(), certification.inputLock().buildSessionId())
                .orElse(null);
        if (!isCertifiableSession(intent, certification, session, observedAt)) {
            completeFailure(
                    intent,
                    claimToken,
                    observedAt,
                    actionRun,
                    BUILD_SESSION_NOT_CERTIFIABLE,
                    "BuildSession is not the current uncancelled Agent Pack CERTIFY operation");
            return;
        }
        try {
            CertificationIssuanceOutcome outcome = issuanceService.issue(intent);
            completeSuccess(intent, claimToken, now(), actionRun, outcome);
        } catch (CertificationIssuanceService.EvidenceRejected rejected) {
            completeFailure(intent, claimToken, now(), actionRun, "CERTIFICATION_FULL_EVIDENCE_REJECTED",
                    "Final certification evidence integrity did not hold");
        } catch (RuntimeException uncertainIssuance) {
            // Evidence/manifest staging and Certification CAS form a recoverable idempotent window.
            // Keep the lease-bearing RUNNING intent; a later claimant observes domain truth first.
        }
    }

    private void handlePrePark(
            CertificationDispatchIntent intent, String claimToken, Instant observedAt) {
        Instant graceUntil = intent.createdAt().plus(parkGrace);
        if (observedAt.isBefore(graceUntil)) {
            Instant retryAt = observedAt.plus(PARK_OBSERVATION_BACKOFF);
            if (retryAt.isAfter(graceUntil)) {
                retryAt = graceUntil;
            }
            terminalize(
                    intent,
                    intent.uncertain(claimToken, PARK_UNCERTAIN, observedAt, retryAt));
        } else {
            terminalize(
                    intent,
                    intent.orphanBeforeWaiting(
                            claimToken, ORPHANED_BEFORE_WAITING, observedAt));
        }
    }

    private void completeSuccess(
            CertificationDispatchIntent intent,
            String claimToken,
            Instant completedAt,
            ActionRun observedAction,
            CertificationIssuanceOutcome outcome) {
        try {
            issuanceService.requireFullEvidence(intent);
        } catch (CertificationIssuanceService.EvidenceRejected rejected) {
            completeFailure(intent, claimToken, now(), observedAction, "CERTIFICATION_FULL_EVIDENCE_REJECTED",
                    "Final certification evidence integrity did not hold");
            return;
        }
        completedAt = now();
        if (intent.leaseUntil().filter(completedAt::isBefore).isEmpty()
                || intents.find(intent.operationId()).filter(intent::equals).isEmpty()) {
            return; // A slow read cannot complete through an expired or superseded claim.
        }
        ActionExecutionResult result = ActionExecutionResult.succeeded(output(outcome));
        try {
            actionRuntime.complete(intent.actionRunId(), observedAction.attemptToken(), result);
        } catch (RuntimeException completionUncertain) {
            return;
        }
        ActionRun canonical = runStore.find(intent.actionRunId()).orElse(null);
        if (canonical != null
                && canonical.status() == ActionRunStatus.SUCCEEDED
                && exactTerminalResult(outcome, canonical.result())) {
            terminalize(
                    intent,
                    intent.complete(
                            claimToken, CERTIFICATION_DISPATCH_COMPLETED, completedAt));
        }
    }

    private void completeFailure(
            CertificationDispatchIntent intent,
            String claimToken,
            Instant completedAt,
            ActionRun observedAction,
            String code,
            String message) {
        completedAt = now();
        if (intent.leaseUntil().filter(completedAt::isBefore).isEmpty()
                || intents.find(intent.operationId()).filter(intent::equals).isEmpty()) {
            return; // Failed readback does not let an expired claimant win a terminal Action.
        }
        ActionExecutionResult failure = new ActionExecutionResult(
                ActionExecutionStatus.FAILED,
                code,
                message,
                Map.of(CertificationIssueAction.CERTIFICATION_ID, intent.certificationId().value()),
                RetryDisposition.MANUAL_REVIEW);
        try {
            actionRuntime.complete(intent.actionRunId(), observedAction.attemptToken(), failure);
        } catch (RuntimeException completionUncertain) {
            return;
        }
        ActionRun canonical = runStore.find(intent.actionRunId()).orElse(null);
        if (canonical != null && canonical.status().isTerminal()) {
            terminalize(intent, intent.complete(
                    claimToken,
                    canonical.result() == null ? code : canonical.result().code(),
                    completedAt));
        }
    }

    private void reconcileTerminalAction(
            CertificationDispatchIntent intent,
            String claimToken,
            Instant observedAt,
            ActionRun actionRun) {
        Optional<CertificationIssuanceOutcome> outcome = issuanceService.observeIssued(intent);
        if (actionRun.status() == ActionRunStatus.SUCCEEDED
                && outcome.isPresent()
                && exactTerminalResult(outcome.orElseThrow(), actionRun.result())) {
            terminalize(
                    intent,
                    intent.complete(
                            claimToken, CERTIFICATION_DISPATCH_COMPLETED, observedAt));
        } else {
            terminalize(
                    intent,
                    intent.orphan(claimToken, ACTION_TERMINAL_CONFLICT, observedAt));
        }
    }

    private void terminalize(
            CertificationDispatchIntent expected, CertificationDispatchIntent next) {
        intents.compareAndSet(expected, next);
    }

    public static boolean hasImmutableOwnerBinding(
            CertificationDispatchIntent intent,
            ActionRun actionRun,
            Certification certification) {
        if (intent == null
                || actionRun == null
                || certification == null
                || !intent.actionRunId().equals(actionRun.runId())
                || !intent.tenantId().value().equals(actionRun.tenantId())
                || !CertificationIssueAction.ACTION_ID.equals(actionRun.actionId())
                || actionRun.requestChannel() != ActionRequestChannel.INTERNAL
                || actionRun.proposerType() != ActionProposerType.SERVICE
                || actionRun.userId().isBlank()
                || actionRun.requesterId().isBlank()
                || actionRun.attemptToken().isBlank()
                || !intent.attemptTokenHash().equals(
                        CertificationAttemptTokens.hash(actionRun.attemptToken()))
                || !CertificationIssueAction.RESOURCE_TYPE.equals(
                        actionRun.contextMetadata().get("resource.type"))
                || !intent.certificationId().value().equals(
                        actionRun.contextMetadata().get("resource.id"))
                || !hasPermission(
                        actionRun.contextMetadata().get("actor.permissions"),
                        CertificationIssueAction.PERMISSION)
                || !certification.certificationId().equals(intent.certificationId())
                || !certification.inputLock().tenantId().equals(intent.tenantId())
                || !certification.inputLockArtifact().hash().equals(intent.inputLockManifestHash())) {
            return false;
        }
        try {
            CertificationIssueInput input = CertificationIssueInput.from(actionRun.input());
            return input.certificationId().equals(intent.certificationId())
                    && input.inputLockManifestHash().equals(intent.inputLockManifestHash())
                    && input.expectedCertificationVersion()
                            == intent.expectedCertificationVersion()
                    && CertificationIssueIdempotencyKeys
                            .derive(certification, intent.expectedCertificationVersion())
                            .equals(actionRun.duplicateKey());
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    public static boolean isExactRequested(
            CertificationDispatchIntent intent, Certification certification) {
        return certification != null
                && certification.status() == CertificationStatus.REQUESTED
                && certification.version() == intent.expectedCertificationVersion()
                && certification.certificationId().equals(intent.certificationId())
                && certification.inputLock().tenantId().equals(intent.tenantId())
                && certification.inputLockArtifact().hash().equals(intent.inputLockManifestHash());
    }

    public static boolean hasWaitingOwnerBinding(
            CertificationDispatchIntent intent, ActionRun actionRun) {
        return intent != null
                && actionRun != null
                && actionRun.status() == ActionRunStatus.WAITING_EXTERNAL
                && intent.operationId().equals(actionRun.externalOperationId())
                && intent.deadlineAt().equals(actionRun.dueAt())
                && actionRun.externalOperationMetadata().equals(Map.of(
                        "dispatchMode", "durable-certification-intent",
                        CertificationIssueAction.CERTIFICATION_ID,
                        intent.certificationId().value()));
    }

    public static boolean isCertifiableSession(
            CertificationDispatchIntent intent,
            Certification certification,
            BuildSession session,
            Instant observedAt) {
        if (session == null || certification == null || observedAt == null) {
            return false;
        }
        var lock = certification.inputLock();
        return session.tenantId().equals(intent.tenantId())
                && session.buildSessionId().equals(lock.buildSessionId())
                && session.productLineId().equals(ProductLineId.AGENT_PACK)
                && session.status() == BuildSessionStatus.CERTIFYING
                && session.currentPhase() == BuildSessionPhase.CERTIFY
                && session.currentCandidateId().filter(lock.candidateId()::equals).isPresent()
                && session.currentCandidateHash().filter(lock.candidateHash()::equals).isPresent()
                && session.currentCertificationId().isEmpty()
                && session.cancellationRequestedAt().isEmpty()
                && intent.deadlineAt().equals(session.deadlineAt())
                && observedAt.isBefore(session.deadlineAt());
    }

    public static boolean exactTerminalResult(
            CertificationIssuanceOutcome outcome, ActionExecutionResult result) {
        if (outcome == null
                || result == null
                || result.status() != ActionExecutionStatus.SUCCEEDED) {
            return false;
        }
        return result.output().equals(output(outcome));
    }

    private static Map<String, Object> output(CertificationIssuanceOutcome outcome) {
        return Map.of(
                CertificationIssueAction.CERTIFICATION_ID,
                outcome.certification().certificationId().value(),
                CERTIFICATION_STATUS,
                CertificationStatus.CERTIFIED.name(),
                CERTIFICATION_MANIFEST_REF,
                outcome.certificationManifest().reference().value(),
                CERTIFICATION_MANIFEST_HASH,
                outcome.certificationManifest().hash().sha256(),
                CERTIFICATION_EVIDENCE_REF,
                outcome.certificationEvidence().reference().value(),
                CERTIFICATION_EVIDENCE_HASH,
                outcome.certificationEvidence().hash().sha256());
    }

    private static boolean hasPermission(Object value, String required) {
        return value instanceof Collection<?> permissions && permissions.stream().anyMatch(required::equals);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
