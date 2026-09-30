package io.github.flowerjvm.factory.infrastructure.worker.codex;

import java.time.Instant;
import java.util.Objects;

/**
 * Production-capable verifier backed by the fixed Node runner's real Codex sandbox sentinel probe.
 * Hosts must opt into this implementation explicitly; the adapter's default remains fail-closed.
 */
public final class CodexPermissionProfileCredentialIsolationVerifier
        implements CodexCredentialIsolationVerifier {
    static final String VERIFIER_ID = "codex-permission-profile-command-sentinel-v1";

    @Override
    public ProvenIsolation verify(CodexWorkerBinding binding) {
        Objects.requireNonNull(binding, "binding");
        CodexWorkerProtocolClient.CapabilitiesReply reply =
                new CodexWorkerProtocolClient(binding).capabilities();
        return new ProvenIsolation(
                binding.bindingId(),
                VERIFIER_ID,
                reply.isolationEvidenceSha256(),
                Instant.now());
    }
}
