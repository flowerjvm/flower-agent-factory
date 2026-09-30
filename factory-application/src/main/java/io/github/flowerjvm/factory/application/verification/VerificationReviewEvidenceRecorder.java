package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import java.util.Optional;

/** Off-tick readback in the existing governed verifier attempt, before its canonical completion. */
@FunctionalInterface
public interface VerificationReviewEvidenceRecorder {
    /** Compatibility constructor behavior only; production-plan readers still require a receipt. */
    VerificationReviewEvidenceRecorder NONE = (intent, action, run) -> Optional.empty();

    Optional<CertificationArtifactLock> validateAndRecord(
            VerificationDispatchIntent intent, ActionRun action, VerificationRun run);
}
