package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.verification.VerificationResultManifest;
import java.util.List;
import java.util.Objects;

/** Bounded read-only decoding for repair context, never a review/release eligibility validator. */
public interface AgentPackProductionRepairEvidenceReader {
    /** Validate bounded canonical manifest metadata/tree-of-hashes, without loading source files. */
    ContentHash validateSource(CandidateVersion candidate);

    /** Decode exact FAILED/REPAIR_REQUIRED Factory evidence, including hash-bound diagnostic logs. */
    Failure readFailure(CandidateVersion candidate, VerificationRun run);

    record Failure(VerificationResultManifest manifest, List<Diagnostic> diagnostics) {
        public Failure {
            Objects.requireNonNull(manifest, "manifest");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.size() > 4) throw new IllegalArgumentException("too many repair diagnostics");
        }
    }

    record Diagnostic(String commandId, ArtifactReference reference, ContentHash hash, String snippet) {
        public Diagnostic {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(snippet, "snippet");
            if (snippet.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
                throw new IllegalArgumentException("repair diagnostic exceeds bound");
            }
        }
    }
}
