package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded, restart-safe consumer for the PR4 verifier intent ledger. */
public final class VerificationDispatchRunner {
    public static final String ORPHANED_BEFORE_WAITING = "VERIFICATION_ACTION_ORPHANED_BEFORE_WAITING";
    public static final String PARK_UNCERTAIN = "VERIFICATION_ACTION_PARK_UNCERTAIN";
    public static final String ACTION_OWNER_INVALID = "VERIFICATION_ACTION_OWNER_INVALID";
    public static final String ACTION_RESULT_INVALID = "VERIFICATION_ACTION_RESULT_INVALID";
    public static final String EXECUTION_BLOCKED = "VERIFICATION_EXECUTION_BLOCKED";
    public static final String DEADLINE_EXCEEDED = "VERIFICATION_DEADLINE_EXCEEDED";
    public static final String RUNNER_LEASE_EXPIRED = "VERIFICATION_RUNNER_LEASE_EXPIRED_UNCERTAIN";
    public static final String DOMAIN_RUNNING_UNCERTAIN = "VERIFICATION_DOMAIN_RUNNING_UNCERTAIN";
    public static final String COMPLETED = "VERIFICATION_DISPATCH_COMPLETED";
    public static final String REVIEW_EVIDENCE_BLOCKED = "VERIFICATION_REVIEW_EVIDENCE_BLOCKED";
    // Operational uncertainty boundary only. Expiry never licenses verifier re-execution.
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(30);
    public static final Duration DEFAULT_PARK_GRACE = Duration.ofSeconds(30);

    private final VerificationDispatchIntentRepository intents;
    private final VerificationRunRepository verificationRuns;
    private final CandidateVersionRepository candidates;
    private final VerificationExecutionService executionService;
    private final CompletableActionRuntime actionRuntime;
    private final RunStore runStore;
    private final Clock clock;
    private final Duration lease;
    private final Duration parkGrace;
    private final VerificationReviewEvidenceRecorder reviewEvidence;

    public VerificationDispatchRunner(
            VerificationDispatchIntentRepository intents,
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            VerificationExecutionService executionService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock) {
        this(intents, verificationRuns, candidates, executionService, actionRuntime, runStore, clock,
                DEFAULT_LEASE, DEFAULT_PARK_GRACE);
    }

    public VerificationDispatchRunner(
            VerificationDispatchIntentRepository intents, VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates, VerificationExecutionService executionService,
            CompletableActionRuntime actionRuntime, RunStore runStore, Clock clock,
            VerificationReviewEvidenceRecorder reviewEvidence) {
        this(intents, verificationRuns, candidates, executionService, actionRuntime, runStore, clock,
                DEFAULT_LEASE, DEFAULT_PARK_GRACE, reviewEvidence);
    }

    public VerificationDispatchRunner(
            VerificationDispatchIntentRepository intents,
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            VerificationExecutionService executionService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock,
            Duration lease,
            Duration parkGrace) {
        this(intents, verificationRuns, candidates, executionService, actionRuntime, runStore, clock,
                lease, parkGrace, VerificationReviewEvidenceRecorder.NONE);
    }

    public VerificationDispatchRunner(
            VerificationDispatchIntentRepository intents, VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates, VerificationExecutionService executionService,
            CompletableActionRuntime actionRuntime, RunStore runStore, Clock clock,
            Duration lease, Duration parkGrace, VerificationReviewEvidenceRecorder reviewEvidence) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.verificationRuns = Objects.requireNonNull(verificationRuns, "verificationRuns");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = requirePositive(lease, "lease");
        this.parkGrace = requirePositive(parkGrace, "parkGrace");
        this.reviewEvidence = Objects.requireNonNull(reviewEvidence, "reviewEvidence");
    }

    /** Claims and processes at most one intent so host scheduling remains bounded. */
    public boolean tickOnce() {
        Instant now = clock.instant();
        String reconciliationToken = UUID.randomUUID().toString();
        var expired = intents.claimExpiredRunningForReconciliation(now, lease, reconciliationToken);
        if (expired.isPresent()) {
            processExpired(expired.orElseThrow(), reconciliationToken, now);
            return true;
        }
        String claimToken = UUID.randomUUID().toString();
        Optional<VerificationDispatchIntent> claimed = intents.claimNext(now, lease, claimToken);
        if (claimed.isEmpty()) {
            return false;
        }
        if (claimed.orElseThrow().status() != VerificationDispatchIntentStatus.RUNNING) {
            return true;
        }
        process(claimed.orElseThrow(), claimToken, now);
        return true;
    }

    private void processExpired(
            VerificationDispatchIntent intent, String claimToken, Instant observedAt) {
        ActionRun actionRun = runStore.find(intent.actionRunId()).orElse(null);
        VerificationRun domain = verificationRuns.find(intent.tenantId(), intent.verificationRunId()).orElse(null);
        CandidateVersion candidate = candidates.find(intent.tenantId(), intent.candidateId()).orElse(null);
        if (actionRun != null && domain != null && candidate != null
                && hasExactOwnerBinding(intent, actionRun, domain, candidate)) {
            if (actionRun.status() == ActionRunStatus.WAITING_EXTERNAL
                    && intent.operationId().equals(actionRun.externalOperationId())
                    && domain.status().isTerminal()) {
                completeSuccess(
                        intent, claimToken, observedAt, actionRun,
                        new VerificationExecutionOutcome(domain, false));
                return;
            }
            if (actionRun.status().isTerminal()) {
                reconcileTerminalAction(intent, claimToken, observedAt, actionRun, domain);
                return;
            }
        }
        terminalize(intent, intent.orphan(claimToken, RUNNER_LEASE_EXPIRED, observedAt));
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

    private void process(VerificationDispatchIntent intent, String claimToken, Instant observedAt) {
        ActionRun actionRun = runStore.find(intent.actionRunId()).orElse(null);
        VerificationRun domain = verificationRuns.find(intent.tenantId(), intent.verificationRunId())
                .orElse(null);
        CandidateVersion candidate = candidates.find(intent.tenantId(), intent.candidateId()).orElse(null);
        if (actionRun == null || domain == null || candidate == null
                || !hasExactOwnerBinding(intent, actionRun, domain, candidate)) {
            terminalize(intent, intent.orphan(claimToken, ACTION_OWNER_INVALID, observedAt));
            return;
        }
        if (actionRun.status() == ActionRunStatus.RUNNING) {
            // prepare() commits before ActionPipeline parks the Run. One grace observation avoids
            // misclassifying that healthy window as the 0.3.3 unrecoverable RUNNING crash gap.
            if (intent.attemptCount() == 1) {
                VerificationDispatchIntent uncertain = intent.uncertain(
                        claimToken,
                        PARK_UNCERTAIN,
                        observedAt,
                        observedAt.plus(parkGrace));
                terminalize(intent, uncertain);
            } else {
                terminalize(intent, intent.orphan(claimToken, ORPHANED_BEFORE_WAITING, observedAt));
            }
            return;
        }
        if (actionRun.status().isTerminal()) {
            reconcileTerminalAction(intent, claimToken, observedAt, actionRun, domain);
            return;
        }
        if (actionRun.status() != ActionRunStatus.WAITING_EXTERNAL
                || !intent.operationId().equals(actionRun.externalOperationId())) {
            terminalize(intent, intent.orphan(claimToken, ACTION_OWNER_INVALID, observedAt));
            return;
        }
        if (domain == null || !hasDomainBinding(intent, domain)) {
            completeFailure(intent, claimToken, observedAt, ACTION_OWNER_INVALID,
                    "VerificationRun no longer has the immutable dispatch binding");
            return;
        }

        if (!domain.status().isTerminal() && !observedAt.isBefore(intent.deadlineAt())) {
            completeFailure(intent, claimToken, observedAt, DEADLINE_EXCEEDED,
                    "persisted verification deadline elapsed before terminal evidence");
            return;
        }
        if (domain.status() == VerificationRunStatus.RUNNING) {
            // This claim did not perform the REQUESTED -> RUNNING CAS, so an earlier verifier
            // effect may still be active. Never turn this observation into a second invocation.
            terminalize(intent, intent.orphan(claimToken, DOMAIN_RUNNING_UNCERTAIN, observedAt));
            return;
        }

        try {
            VerificationExecutionOutcome outcome;
            if (domain.status().isTerminal()) {
                outcome = new VerificationExecutionOutcome(domain, false);
            } else {
                outcome = executionService.execute(
                        intent.tenantId(), intent.verificationRunId(), domain.version());
            }
            completeSuccess(intent, claimToken, clock.instant(), actionRun, outcome);
        } catch (RuntimeException executionFailure) {
            completeFailure(intent, claimToken, clock.instant(), EXECUTION_BLOCKED,
                    "independent verification did not establish terminal evidence");
        }
    }

    private void completeSuccess(
            VerificationDispatchIntent intent,
            String claimToken,
            Instant completedAt,
            ActionRun observedAction,
            VerificationExecutionOutcome outcome) {
        VerificationRun run = outcome.verificationRun();
        if (!run.status().isTerminal() || !hasDomainBinding(intent, run)) {
            completeFailure(intent, claimToken, completedAt, ACTION_RESULT_INVALID,
                    "verification execution returned a non-terminal or mismatched result");
            return;
        }
        final ActionExecutionResult result;
        try {
            // This method is shared by first completion and domain-terminal recovery. A lost
            // process may repeat only readback, never the already-terminal verifier execution.
            var receipt = reviewEvidence.validateAndRecord(intent, observedAction, run);
            if (run.status() != VerificationRunStatus.PASSED && receipt.isPresent()) {
                throw new IllegalArgumentException("failed verification cannot carry review eligibility");
            }
            result = ActionExecutionResult.succeeded(VerificationReviewEvidenceOutput.add(
                    output(run, outcome.executedNow()), receipt));
        } catch (RuntimeException invalidReadback) {
            completeFailure(intent, claimToken, clock.instant(), REVIEW_EVIDENCE_BLOCKED,
                    "verification evidence readback did not establish review eligibility");
            return;
        }
        // Readback can be lengthy. An expired or replaced claim may not publish a new
        // terminal result; durable reconciliation owns the already-terminal domain run.
        if (!ownsCurrentClaim(intent, claimToken)) return;
        actionRuntime.complete(intent.actionRunId(), observedAction.attemptToken(), result);
        ActionRun canonical = runStore.find(intent.actionRunId()).orElse(null);
        if (canonical != null && canonical.status() == ActionRunStatus.SUCCEEDED
                && exactTerminalResult(run, canonical.result())) {
            terminalize(intent, intent.complete(claimToken, COMPLETED, clock.instant()));
        }
        // A crash or transient completion failure leaves the lease-bearing intent recoverable.
    }

    private void completeFailure(
            VerificationDispatchIntent intent,
            String claimToken,
            Instant completedAt,
            String code,
            String message) {
        if (!ownsCurrentClaim(intent, claimToken)) return;
        ActionRun observed = runStore.find(intent.actionRunId()).orElse(null);
        if (observed == null || observed.status() != ActionRunStatus.WAITING_EXTERNAL
                || !hasImmutableOwnerBinding(intent, observed)
                || !intent.operationId().equals(observed.externalOperationId())) {
            terminalize(intent, intent.orphan(claimToken, ACTION_OWNER_INVALID, completedAt));
            return;
        }
        actionRuntime.complete(
                intent.actionRunId(),
                observed.attemptToken(),
                new ActionExecutionResult(
                        ActionExecutionStatus.FAILED,
                        code,
                        message,
                        Map.of(VerificationRunAction.VERIFICATION_RUN_ID, intent.verificationRunId().value()),
                        RetryDisposition.MANUAL_REVIEW));
        ActionRun canonical = runStore.find(intent.actionRunId()).orElse(null);
        if (canonical != null && canonical.status().isTerminal()) {
            terminalize(intent, intent.complete(claimToken, canonical.result() == null
                    ? code : canonical.result().code(), completedAt));
        }
    }

    private void reconcileTerminalAction(
            VerificationDispatchIntent intent,
            String claimToken,
            Instant observedAt,
            ActionRun actionRun,
            VerificationRun domain) {
        if (actionRun.status() == ActionRunStatus.SUCCEEDED
                && domain != null
                && domain.status().isTerminal()
                && hasDomainBinding(intent, domain)
                && exactTerminalResult(domain, actionRun.result())) {
            terminalize(intent, intent.complete(claimToken, COMPLETED, observedAt));
        } else {
            terminalize(intent, intent.complete(
                    claimToken,
                    actionRun.result() == null ? ACTION_RESULT_INVALID : actionRun.result().code(),
                    observedAt));
        }
    }

    private void terminalize(VerificationDispatchIntent expected, VerificationDispatchIntent next) {
        intents.compareAndSet(expected, next);
    }

    private boolean ownsCurrentClaim(VerificationDispatchIntent intent, String claimToken) {
        return intent.status() == VerificationDispatchIntentStatus.RUNNING
                && intent.claimToken().filter(claimToken::equals).isPresent()
                && intent.leaseUntil().filter(clock.instant()::isBefore).isPresent()
                && intents.findLatest(intent.tenantId(), intent.verificationRunId()).filter(intent::equals).isPresent();
    }

    public static boolean hasImmutableOwnerBinding(VerificationDispatchIntent intent, ActionRun actionRun) {
        if (!intent.actionRunId().equals(actionRun.runId())
                || !intent.tenantId().value().equals(actionRun.tenantId())
                || !VerificationRunAction.ACTION_ID.equals(actionRun.actionId())
                || actionRun.attemptToken() == null
                || actionRun.attemptToken().isBlank()
                || !intent.attemptTokenHash().equals(VerificationAttemptTokens.hash(actionRun.attemptToken()))) {
            return false;
        }
        try {
            VerificationRunInput input = VerificationRunInput.from(actionRun.input());
            return input.verificationRunId().equals(intent.verificationRunId())
                    && input.candidateId().equals(intent.candidateId())
                    && input.expectedVerificationRunVersion() == intent.expectedVerificationRunVersion()
                    && VerificationRunAction.RESOURCE_TYPE.equals(actionRun.contextMetadata().get("resource.type"))
                    && intent.candidateId().value().equals(actionRun.contextMetadata().get("resource.id"));
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    public static boolean hasDomainBinding(VerificationDispatchIntent intent, VerificationRun domain) {
        return intent.tenantId().equals(domain.tenantId())
                && intent.verificationRunId().equals(domain.verificationRunId())
                && intent.candidateId().equals(domain.candidateId())
                && domain.version() == intent.expectedVerificationRunVersion()
                        + switch (domain.status()) {
                            case REQUESTED -> 0;
                            case RUNNING -> 1;
                            case PASSED, FAILED -> 2;
                        };
    }

    public static boolean hasExactOwnerBinding(
            VerificationDispatchIntent intent,
            ActionRun actionRun,
            VerificationRun domain,
            CandidateVersion candidate) {
        return hasImmutableOwnerBinding(intent, actionRun)
                && hasDomainBinding(intent, domain)
                && candidate.tenantId().equals(intent.tenantId())
                && candidate.candidateId().equals(intent.candidateId())
                && candidate.buildSessionId().equals(domain.buildSessionId())
                && candidate.sourceHash().equals(domain.candidateHash())
                && candidate.toolchainLockHash().equals(domain.toolchainLockHash())
                && VerificationRunIdempotencyKeys.derive(
                                domain, candidate, intent.expectedVerificationRunVersion())
                        .equals(actionRun.duplicateKey());
    }

    public static boolean exactTerminalResult(VerificationRun domain, ActionExecutionResult result) {
        if (result == null || result.status() != ActionExecutionStatus.SUCCEEDED) {
            return false;
        }
        Map<String, Object> output = result.output();
        final java.util.Set<String> expectedKeys = new java.util.HashSet<>(Set.of(
                        VerificationRunAction.VERIFICATION_RUN_ID,
                        "verificationStatus",
                        "terminalCode",
                        "resultManifestRef",
                        "executedNow"));
        try {
            if (VerificationReviewEvidenceOutput.lock(output).isPresent()) {
                if (domain.status() != VerificationRunStatus.PASSED) return false;
                expectedKeys.addAll(VerificationReviewEvidenceOutput.KEYS);
            }
        } catch (IllegalArgumentException invalidReceipt) {
            return false;
        }
        return output.keySet().equals(expectedKeys) && output.get("executedNow") instanceof Boolean
                && domain.verificationRunId().value().equals(output.get(VerificationRunAction.VERIFICATION_RUN_ID))
                && domain.status().name().equals(output.get("verificationStatus"))
                && domain.terminalCode().orElseThrow().equals(output.get("terminalCode"))
                && domain.resultManifestRef().orElseThrow().value().equals(output.get("resultManifestRef"));
    }

    private static Map<String, Object> output(VerificationRun run, boolean executedNow) {
        return Map.of(
                VerificationRunAction.VERIFICATION_RUN_ID, run.verificationRunId().value(),
                "verificationStatus", run.status().name(),
                "terminalCode", run.terminalCode().orElseThrow(),
                "resultManifestRef", run.resultManifestRef().orElseThrow().value(),
                "executedNow", executedNow);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
