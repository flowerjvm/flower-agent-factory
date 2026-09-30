package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.util.Objects;

/** Read-only reconciliation of the verification intent, ActionRun and domain terminal evidence. */
public final class ActionRuntimeVerificationEvidenceOwner implements VerificationActionEvidenceOwner {
    public static final String ACTION_ORPHANED = "VERIFICATION_ACTION_ORPHANED";
    public static final String ACTION_RUNNING_WITHOUT_INTENT = "VERIFICATION_ACTION_RUNNING_WITHOUT_INTENT";
    public static final String ACTION_TERMINAL_MISMATCH = "VERIFICATION_ACTION_TERMINAL_MISMATCH";

    private final VerificationDispatchIntentRepository intents;
    private final RunStore runStore;
    private final CandidateVersionRepository candidates;
    private final VerificationActionDuplicateOwnerLookup duplicateOwners;

    public ActionRuntimeVerificationEvidenceOwner(
            VerificationDispatchIntentRepository intents,
            RunStore runStore,
            CandidateVersionRepository candidates,
            VerificationActionDuplicateOwnerLookup duplicateOwners) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.duplicateOwners = Objects.requireNonNull(duplicateOwners, "duplicateOwners");
    }

    @Override
    public Assessment assess(VerificationRun verificationRun) {
        Objects.requireNonNull(verificationRun, "verificationRun");
        var intent = intents.findLatest(verificationRun.tenantId(), verificationRun.verificationRunId());
        if (intent.isEmpty()) {
            var candidate = candidates.find(verificationRun.tenantId(), verificationRun.candidateId());
            if (candidate.isEmpty()) {
                return new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
            }
            String key = VerificationRunIdempotencyKeys.derive(
                    verificationRun, candidate.orElseThrow(), proposalVersion(verificationRun));
            var ownerId = duplicateOwners.findOwnerRunId(
                    verificationRun.tenantId().value(), VerificationRunAction.ACTION_ID, key);
            if (ownerId.isPresent()) {
                ActionRun owner = runStore.find(ownerId.orElseThrow()).orElse(null);
                if (owner == null || !matchesDomainIdentity(owner, verificationRun)
                        || !key.equals(owner.duplicateKey())) {
                    return new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
                }
                if (owner.status() == ActionRunStatus.RUNNING
                        || owner.status() == ActionRunStatus.WAITING_EXTERNAL) {
                    return new Assessment(Status.ORPHANED, ACTION_RUNNING_WITHOUT_INTENT);
                }
                if (owner.status().isTerminal()) {
                    return new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
                }
            }
            return verificationRun.status().isTerminal()
                    ? new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH)
                    : Assessment.pending();
        }
        VerificationDispatchIntent owner = intent.orElseThrow();
        if (owner.status() == VerificationDispatchIntentStatus.ORPHANED) {
            return new Assessment(Status.ORPHANED, owner.lastCode().orElse(ACTION_ORPHANED));
        }
        ActionRun action = runStore.find(owner.actionRunId()).orElse(null);
        var candidate = candidates.find(owner.tenantId(), owner.candidateId()).orElse(null);
        if (action != null
                && candidate != null
                && action.status() == ActionRunStatus.SUCCEEDED
                && owner.operationId().equals(action.externalOperationId())
                && VerificationDispatchRunner.hasExactOwnerBinding(owner, action, verificationRun, candidate)
                && verificationRun.status().isTerminal()
                && VerificationDispatchRunner.hasDomainBinding(owner, verificationRun)
                && VerificationDispatchRunner.exactTerminalResult(verificationRun, action.result())) {
            if (VerificationReviewEvidenceOutput.lock(action.result().output()).isPresent()) {
                // Receipt readers require both durable owners to be terminal. Do not let the
                // Action-completed / intent-still-running gap look like corrupt product evidence.
                if (!owner.status().isTerminal()) return Assessment.pending();
                if (owner.status() != VerificationDispatchIntentStatus.COMPLETED
                        || owner.lastCode().filter(VerificationDispatchRunner.COMPLETED::equals).isEmpty()) {
                    return new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
                }
            }
            return Assessment.canonical();
        }
        if (owner.status() != VerificationDispatchIntentStatus.COMPLETED) {
            return Assessment.pending();
        }
        return new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
    }

    private static boolean matchesDomainIdentity(ActionRun action, VerificationRun domain) {
        if (!VerificationRunAction.ACTION_ID.equals(action.actionId())
                || !domain.tenantId().value().equals(action.tenantId())
                || !VerificationRunAction.RESOURCE_TYPE.equals(
                        action.contextMetadata().get("resource.type"))
                || !domain.candidateId().value().equals(
                        action.contextMetadata().get("resource.id"))) {
            return false;
        }
        try {
            VerificationRunInput input = VerificationRunInput.from(action.input());
            return input.verificationRunId().equals(domain.verificationRunId())
                    && input.candidateId().equals(domain.candidateId())
                    && input.expectedVerificationRunVersion() == proposalVersion(domain);
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    private static long proposalVersion(VerificationRun run) {
        return run.version() - switch (run.status()) {
            case REQUESTED -> 0;
            case RUNNING -> 1;
            case PASSED, FAILED -> 2;
        };
    }
}
