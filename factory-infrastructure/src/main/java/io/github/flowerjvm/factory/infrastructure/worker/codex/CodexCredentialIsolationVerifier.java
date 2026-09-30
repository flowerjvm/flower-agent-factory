package io.github.flowerjvm.factory.infrastructure.worker.codex;

import java.time.Instant;
import java.util.Objects;

/**
 * Evidence boundary for OS-enforced denial of Coding Agent reads from its credential profile.
 * A configuration flag or operator assertion is not acceptable evidence.
 */
@FunctionalInterface
public interface CodexCredentialIsolationVerifier {
    ProvenIsolation verify(CodexWorkerBinding binding);

    record ProvenIsolation(
            String bindingId,
            String verifierId,
            String evidenceSha256,
            Instant verifiedAt) {
        public ProvenIsolation {
            bindingId = bounded(bindingId, "bindingId", 128);
            verifierId = bounded(verifierId, "verifierId", 128);
            if (evidenceSha256 == null || !evidenceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("evidenceSha256 must be lowercase SHA-256");
            }
            Objects.requireNonNull(verifiedAt, "verifiedAt");
        }

        private static String bounded(String value, String name, int maximum) {
            if (value == null || value.isBlank() || value.length() > maximum
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(name + " must be bounded non-control text");
            }
            return value;
        }
    }
}
