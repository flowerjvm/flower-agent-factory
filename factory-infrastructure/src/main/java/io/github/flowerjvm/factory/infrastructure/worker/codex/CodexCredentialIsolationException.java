package io.github.flowerjvm.factory.infrastructure.worker.codex;

/** Sanitized fail-closed signal; it never contains profile paths, credentials, or probe output. */
public final class CodexCredentialIsolationException extends RuntimeException {
    public static final String STABLE_CODE = "WORKER_CREDENTIAL_ISOLATION_UNPROVEN";

    public CodexCredentialIsolationException() {
        super(STABLE_CODE);
    }
}
