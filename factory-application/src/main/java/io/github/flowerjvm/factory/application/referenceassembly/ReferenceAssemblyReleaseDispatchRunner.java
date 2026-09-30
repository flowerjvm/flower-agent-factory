package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
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
import java.util.Set;
import java.util.UUID;

/** Bounded, restart-safe consumer for governed Reference Assembly release intents. */
public final class ReferenceAssemblyReleaseDispatchRunner {
    public static final String PARK_UNCERTAIN =
            "REFERENCE_ASSEMBLY_RELEASE_ACTION_PARK_UNCERTAIN";
    public static final String ORPHANED_BEFORE_WAITING =
            "REFERENCE_ASSEMBLY_RELEASE_ACTION_ORPHANED_BEFORE_WAITING";
    public static final String ACTION_OWNER_INVALID =
            "REFERENCE_ASSEMBLY_RELEASE_ACTION_OWNER_INVALID";
    public static final String ACTION_TERMINAL_CONFLICT =
            "REFERENCE_ASSEMBLY_RELEASE_ACTION_TERMINAL_CONFLICT";
    public static final String DEADLINE_EXCEEDED =
            "REFERENCE_ASSEMBLY_RELEASE_DEADLINE_EXCEEDED";
    public static final String DISPATCH_COMPLETED =
            "REFERENCE_ASSEMBLY_RELEASE_DISPATCH_COMPLETED";
    public static final String RELEASE_STATUS = "releaseStatus";
    public static final String RELEASE_MANIFEST_REF = "releaseManifestRef";
    public static final String RELEASE_MANIFEST_HASH = "releaseManifestHash";
    private static final String TERMINAL_ACTION_STAGE = "execute-action";
    private static final Set<String> TERMINAL_CONTEXT_KEYS = Set.of(
            "actor.permissions", "resource.type", "resource.id");

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_PARK_GRACE = Duration.ofSeconds(30);
    public static final Duration PARK_OBSERVATION_BACKOFF = Duration.ofMillis(250);

    private final ReferenceAssemblyReleaseDispatchIntentRepository intents;
    private final ReferenceAssemblyRepository assemblies;
    private final ReferenceAssemblyReleaseService releaseService;
    private final CompletableActionRuntime actionRuntime;
    private final RunStore runStore;
    private final Clock clock;
    private final Duration lease;
    private final Duration parkGrace;

    public ReferenceAssemblyReleaseDispatchRunner(
            ReferenceAssemblyReleaseDispatchIntentRepository intents,
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseService releaseService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock) {
        this(
                intents,
                assemblies,
                releaseService,
                actionRuntime,
                runStore,
                clock,
                DEFAULT_LEASE,
                DEFAULT_PARK_GRACE);
    }

    public ReferenceAssemblyReleaseDispatchRunner(
            ReferenceAssemblyReleaseDispatchIntentRepository intents,
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseService releaseService,
            CompletableActionRuntime actionRuntime,
            RunStore runStore,
            Clock clock,
            Duration lease,
            Duration parkGrace) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.assemblies = Objects.requireNonNull(assemblies, "assemblies");
        this.releaseService = Objects.requireNonNull(releaseService, "releaseService");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = requirePositive(lease, "lease");
        this.parkGrace = requirePositive(parkGrace, "parkGrace");
    }

    /** Claims and processes at most one intent so the host scheduler remains non-blocking. */
    public boolean tickOnce() {
        Instant observedAt = now();
        String recoveryClaim = UUID.randomUUID().toString();
        Optional<ReferenceAssemblyReleaseDispatchIntent> recovered =
                intents.claimExpiredRunning(observedAt, lease, recoveryClaim);
        if (recovered.isPresent()) {
            process(recovered.orElseThrow(), recoveryClaim, observedAt);
            return true;
        }
        String claim = UUID.randomUUID().toString();
        Optional<ReferenceAssemblyReleaseDispatchIntent> next =
                intents.claimNext(observedAt, lease, claim);
        if (next.isEmpty()) {
            return false;
        }
        process(next.orElseThrow(), claim, observedAt);
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
            ReferenceAssemblyReleaseDispatchIntent intent,
            String claimToken,
            Instant observedAt) {
        if (intent.status() != ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING
                || intent.claimToken().filter(claimToken::equals).isEmpty()) {
            return;
        }
        ActionRun actionRun = runStore.find(intent.actionRunId()).orElse(null);
        ReferenceAssembly assembly = assemblies
                .find(intent.tenantId(), intent.referenceAssemblyId())
                .orElse(null);
        if (!hasImmutableOwnerBinding(intent, actionRun, assembly)) {
            terminalize(intent, intent.orphan(claimToken, ACTION_OWNER_INVALID, observedAt));
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
            terminalize(intent, intent.orphan(claimToken, ACTION_OWNER_INVALID, observedAt));
            return;
        }

        Optional<ReferenceAssemblyReleaseOutcome> released =
                releaseService.observeReleased(intent);
        if (released.isPresent()) {
            completeSuccess(
                    intent, claimToken, observedAt, actionRun, released.orElseThrow());
            return;
        }
        if (!observedAt.isBefore(intent.deadlineAt())) {
            completeFailure(
                    intent,
                    claimToken,
                    observedAt,
                    actionRun,
                    DEADLINE_EXCEEDED,
                    "release deadline elapsed before an exact shipment was committed");
            return;
        }

        try {
            ReferenceAssemblyReleaseOutcome outcome = releaseService.release(intent);
            completeSuccess(intent, claimToken, now(), actionRun, outcome);
        } catch (ReferenceAssemblyReleaseService.EvidenceRejected rejected) {
            completeFailure(intent, claimToken, now(), actionRun, "REFERENCE_ASSEMBLY_FULL_EVIDENCE_REJECTED",
                    "Final component evidence integrity did not hold");
        } catch (RuntimeException releaseUncertain) {
            // Artifact staging plus a transaction commit is an idempotent recovery window. Keep
            // the lease-bearing intent so a later claimant observes durable release truth first.
        }
    }

    private void handlePrePark(
            ReferenceAssemblyReleaseDispatchIntent intent,
            String claimToken,
            Instant observedAt) {
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
            ReferenceAssemblyReleaseDispatchIntent intent,
            String claimToken,
            Instant completedAt,
            ActionRun observedAction,
            ReferenceAssemblyReleaseOutcome outcome) {
        try {
            releaseService.requireFullEvidence(intent);
        } catch (ReferenceAssemblyReleaseService.EvidenceRejected rejected) {
            completeFailure(intent, claimToken, now(), observedAction, "REFERENCE_ASSEMBLY_FULL_EVIDENCE_REJECTED",
                    "Final component evidence integrity did not hold");
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
                    intent.complete(claimToken, DISPATCH_COMPLETED, completedAt));
        }
    }

    private void completeFailure(
            ReferenceAssemblyReleaseDispatchIntent intent,
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
                Map.of(
                        ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                        intent.referenceAssemblyId().value()),
                RetryDisposition.MANUAL_REVIEW);
        try {
            actionRuntime.complete(intent.actionRunId(), observedAction.attemptToken(), failure);
        } catch (RuntimeException completionUncertain) {
            return;
        }
        ActionRun canonical = runStore.find(intent.actionRunId()).orElse(null);
        if (canonical != null && canonical.status().isTerminal()) {
            terminalize(
                    intent,
                    intent.complete(
                            claimToken,
                            canonical.result() == null ? code : canonical.result().code(),
                            completedAt));
        }
    }

    private void reconcileTerminalAction(
            ReferenceAssemblyReleaseDispatchIntent intent,
            String claimToken,
            Instant observedAt,
            ActionRun actionRun) {
        Optional<ReferenceAssemblyReleaseOutcome> outcome =
                releaseService.observeReleased(intent);
        if (actionRun.status() == ActionRunStatus.SUCCEEDED
                && outcome.isPresent()
                && exactTerminalResult(outcome.orElseThrow(), actionRun.result())) {
            terminalize(
                    intent,
                    intent.complete(claimToken, DISPATCH_COMPLETED, observedAt));
        } else {
            terminalize(
                    intent,
                    intent.orphan(claimToken, ACTION_TERMINAL_CONFLICT, observedAt));
        }
    }

    private void terminalize(
            ReferenceAssemblyReleaseDispatchIntent expected,
            ReferenceAssemblyReleaseDispatchIntent next) {
        intents.compareAndSet(expected, next);
    }

    public static boolean hasImmutableOwnerBinding(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ActionRun actionRun,
            ReferenceAssembly assembly) {
        if (intent == null
                || actionRun == null
                || assembly == null
                || !intent.actionRunId().equals(actionRun.runId())
                || !intent.tenantId().value().equals(actionRun.tenantId())
                || !ReferenceAssemblyReleaseAction.ACTION_ID.equals(actionRun.actionId())
                || actionRun.requestChannel() != ActionRequestChannel.INTERNAL
                || actionRun.proposerType() != ActionProposerType.SERVICE
                || actionRun.userId().isBlank()
                || actionRun.requesterId().isBlank()
                || actionRun.attemptToken().isBlank()
                || !intent.attemptTokenHash().equals(
                        ReferenceAssemblyReleaseAttemptTokens.hash(actionRun.attemptToken()))
                || !ReferenceAssemblyReleaseAction.RESOURCE_TYPE.equals(
                        actionRun.contextMetadata().get("resource.type"))
                || !intent.referenceAssemblyId().value().equals(
                        actionRun.contextMetadata().get("resource.id"))
                || !hasPermission(
                        actionRun.contextMetadata().get("actor.permissions"),
                        ReferenceAssemblyReleaseAction.PERMISSION)
                || !ReferenceAssemblyReleaseService.matchesImmutableIdentity(intent, assembly)
                || assembly.releaseActionRunId().filter(intent.actionRunId()::equals).isEmpty()) {
            return false;
        }
        try {
            ReferenceAssemblyReleaseInput input =
                    ReferenceAssemblyReleaseInput.from(actionRun.input());
            return input.referenceAssemblyId().equals(intent.referenceAssemblyId())
                    && input.assemblyManifestHash().equals(intent.assemblyManifestHash())
                    && input.inspectionReportHash().equals(intent.inspectionReportHash())
                    && input.releaseDecisionPointId().equals(intent.releaseDecisionPointId())
                    && input.releaseSubjectHash().equals(intent.releaseSubjectHash())
                    && input.expectedReferenceAssemblyVersion()
                            == intent.expectedReferenceAssemblyVersion()
                    && ReferenceAssemblyReleaseIdempotencyKeys
                            .derive(assembly, intent.expectedReferenceAssemblyVersion())
                            .equals(actionRun.duplicateKey());
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    public static boolean hasWaitingOwnerBinding(
            ReferenceAssemblyReleaseDispatchIntent intent, ActionRun actionRun) {
        return actionRun != null
                && actionRun.status() == ActionRunStatus.WAITING_EXTERNAL
                && intent.operationId().equals(actionRun.externalOperationId())
                && ReferenceAssemblyReleaseDeadlines.actionDueAt(intent.deadlineAt()).equals(actionRun.dueAt())
                && actionRun.externalOperationMetadata().equals(Map.of(
                        "dispatchMode",
                        ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                        ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                        intent.referenceAssemblyId().value()));
    }

    /** Exact immutable and deferred-operation evidence retained by a successful terminal Run. */
    public static boolean hasExactTerminalOwnerBinding(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ActionRun actionRun,
            ReferenceAssembly assembly) {
        return actionRun != null
                && actionRun.status() == ActionRunStatus.SUCCEEDED
                && hasImmutableOwnerBinding(intent, actionRun, assembly)
                && ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                        actionRun.userId())
                && ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                        actionRun.requesterId())
                && !actionRun.traceId().isBlank()
                && TERMINAL_ACTION_STAGE.equals(actionRun.currentStage())
                && actionRun.contextMetadata().keySet().equals(TERMINAL_CONTEXT_KEYS)
                && hasExactPermission(
                        actionRun.contextMetadata().get("actor.permissions"),
                        ReferenceAssemblyReleaseAction.PERMISSION)
                && intent.operationId().equals(actionRun.externalOperationId())
                && ReferenceAssemblyReleaseDeadlines.actionDueAt(intent.deadlineAt()).equals(actionRun.dueAt())
                && actionRun.externalOperationMetadata().equals(Map.of(
                        "dispatchMode",
                        ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                        ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                        intent.referenceAssemblyId().value()));
    }

    public static boolean exactTerminalResult(
            ReferenceAssemblyReleaseOutcome outcome, ActionExecutionResult result) {
        return outcome != null
                && result != null
                && ActionExecutionResult.succeeded(output(outcome)).equals(result);
    }

    private static Map<String, Object> output(
            ReferenceAssemblyReleaseOutcome outcome) {
        return Map.of(
                ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                outcome.referenceAssembly().referenceAssemblyId().value(),
                RELEASE_STATUS,
                ReferenceAssemblyStatus.RELEASED.name(),
                RELEASE_MANIFEST_REF,
                outcome.releaseManifest().reference().value(),
                RELEASE_MANIFEST_HASH,
                outcome.releaseManifest().hash().sha256());
    }

    private static boolean hasPermission(Object value, String required) {
        return value instanceof Collection<?> permissions
                && permissions.stream().anyMatch(required::equals);
    }

    private static boolean hasExactPermission(Object value, String required) {
        return value instanceof Collection<?> permissions
                && permissions.size() == 1
                && permissions.contains(required);
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
