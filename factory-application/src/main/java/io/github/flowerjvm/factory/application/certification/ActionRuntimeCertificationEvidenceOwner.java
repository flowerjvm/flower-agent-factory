package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.util.Objects;

/** Strict read-only reconciliation of a Certification, latest dispatch intent and ActionRun. */
public final class ActionRuntimeCertificationEvidenceOwner implements CertificationActionEvidenceOwner {
    public static final String ACTION_INTENT_MISSING = "CERTIFICATION_ACTION_INTENT_MISSING";
    public static final String ACTION_ORPHANED = "CERTIFICATION_ACTION_ORPHANED";
    public static final String ACTION_TERMINAL_MISMATCH = "CERTIFICATION_ACTION_TERMINAL_MISMATCH";

    private final CertificationDispatchIntentRepository intents;
    private final RunStore runStore;

    public ActionRuntimeCertificationEvidenceOwner(
            CertificationDispatchIntentRepository intents, RunStore runStore) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
    }

    @Override
    public Assessment assess(Certification certification) {
        Objects.requireNonNull(certification, "certification");
        var tenantId = certification.inputLock().tenantId();
        CertificationDispatchIntent intent = intents
                .findLatest(tenantId, certification.certificationId())
                .orElse(null);
        if (intent == null) {
            return new Assessment(Status.INVALID_TERMINAL, ACTION_INTENT_MISSING);
        }
        if (!intent.tenantId().equals(tenantId)
                || !intent.certificationId().equals(certification.certificationId())) {
            return new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
        }
        if (intent.status() == CertificationDispatchIntentStatus.ORPHANED
                || intent.status() == CertificationDispatchIntentStatus.ORPHANED_BEFORE_WAITING) {
            return new Assessment(Status.ORPHANED, intent.lastCode().orElse(ACTION_ORPHANED));
        }

        ActionRun actionRun = runStore.find(intent.actionRunId()).orElse(null);
        if (intent.status() != CertificationDispatchIntentStatus.COMPLETED) {
            if (actionRun == null || !actionRun.status().isTerminal()) {
                return Assessment.pending();
            }
            return hasExactCertifiedTerminalBinding(intent, actionRun, certification)
                    ? Assessment.pending()
                    : new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
        }
        return hasExactCertifiedTerminalBinding(intent, actionRun, certification)
                ? Assessment.canonical()
                : new Assessment(Status.INVALID_TERMINAL, ACTION_TERMINAL_MISMATCH);
    }

    private static boolean hasExactCertifiedTerminalBinding(
            CertificationDispatchIntent intent,
            ActionRun actionRun,
            Certification certification) {
        if (actionRun == null
                || actionRun.status() != ActionRunStatus.SUCCEEDED
                || certification.status() != CertificationStatus.CERTIFIED
                || certification.version() != intent.expectedCertificationVersion() + 1
                || certification.actionRunId().filter(intent.actionRunId()::equals).isEmpty()
                || !hasExactOperationBinding(intent, actionRun)
                || !CertificationDispatchRunner.hasImmutableOwnerBinding(
                        intent, actionRun, certification)) {
            return false;
        }

        CertificationIssuanceOutcome outcome;
        try {
            outcome = new CertificationIssuanceOutcome(
                    certification,
                    certification.certificationEvidence().orElseThrow(),
                    certification.certificationManifest().orElseThrow(),
                    false);
        } catch (RuntimeException invalidLedger) {
            return false;
        }
        return CertificationDispatchRunner.exactTerminalResult(outcome, actionRun.result());
    }

    private static boolean hasExactOperationBinding(
            CertificationDispatchIntent intent, ActionRun actionRun) {
        CertificationIssueInput input = new CertificationIssueInput(
                intent.certificationId(),
                intent.inputLockManifestHash(),
                intent.expectedCertificationVersion());
        String expectedOperation = CertificationDispatchOperationIds.derive(intent.tenantId(), input);
        return intent.operationId().equals(expectedOperation)
                && intent.operationId().equals(actionRun.externalOperationId());
    }
}
