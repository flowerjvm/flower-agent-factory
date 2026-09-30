package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import java.time.Instant;

/** Non-blocking host boundary that requests independent verification through the Action Runtime. */
@FunctionalInterface
public interface VerificationRunLauncher {
    VerificationRun ensureRequested(BuildSession buildSession, CandidateVersion candidate, Instant requestedAt);

    default String profileFor(CandidateVersion candidate) {
        return ActionBackedVerificationRunLauncher.GATE_PROFILE;
    }
}
