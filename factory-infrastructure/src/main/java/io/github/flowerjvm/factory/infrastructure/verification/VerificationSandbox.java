package io.github.flowerjvm.factory.infrastructure.verification;

import io.github.flowerjvm.factory.contracts.verification.VerificationSandboxEvidence;
import java.nio.file.Path;

/** Isolation seam for fixed, independently selected verification commands. */
public interface VerificationSandbox {
    SandboxExecutionResult execute(Path workspace, Path curatedMavenRepository, VerificationCommand command)
            throws SandboxExecutionException;

    /** Compatibility bridge for deterministic tests; production verification always supplies a curated repo. */
    default SandboxExecutionResult execute(Path workspace, VerificationCommand command)
            throws SandboxExecutionException {
        return execute(workspace, Path.of(System.getProperty("java.io.tmpdir")), command);
    }

    VerificationSandboxEvidence evidence();
}
