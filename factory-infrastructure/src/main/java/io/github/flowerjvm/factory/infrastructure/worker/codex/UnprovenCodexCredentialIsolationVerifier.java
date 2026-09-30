package io.github.flowerjvm.factory.infrastructure.worker.codex;

/** Production-safe default until an OS-isolated launcher supplies a successful sentinel proof. */
public final class UnprovenCodexCredentialIsolationVerifier
        implements CodexCredentialIsolationVerifier {
    @Override
    public ProvenIsolation verify(CodexWorkerBinding binding) {
        throw new CodexCredentialIsolationException();
    }
}
