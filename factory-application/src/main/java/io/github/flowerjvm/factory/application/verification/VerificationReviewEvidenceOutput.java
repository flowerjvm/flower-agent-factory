package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict optional extension; the legacy verification output remains byte-for-byte unchanged. */
public final class VerificationReviewEvidenceOutput {
    public static final String SCHEMA_VERSION = "factory.verification-review-evidence.v1";
    public static final String SCHEMA_KEY = "reviewEvidenceSchemaVersion";
    public static final String REFERENCE_KEY = "reviewEvidenceRef";
    public static final String HASH_KEY = "reviewEvidenceHash";
    public static final String REFERENCE_PREFIX = "artifact:verification-review-evidence:";
    public static final Set<String> KEYS = Set.of(SCHEMA_KEY, REFERENCE_KEY, HASH_KEY);
    private VerificationReviewEvidenceOutput() {}

    public static Optional<CertificationArtifactLock> lock(Map<String, Object> output) {
        if (KEYS.stream().noneMatch(output::containsKey)) return Optional.empty();
        if (!output.keySet().containsAll(KEYS) || !SCHEMA_VERSION.equals(output.get(SCHEMA_KEY))
                || !(output.get(REFERENCE_KEY) instanceof String reference)
                || !(output.get(HASH_KEY) instanceof String hash) || !hash.matches("[0-9a-f]{64}")
                || !reference.equals(REFERENCE_PREFIX + hash)) {
            throw new IllegalArgumentException("verification review receipt output is invalid");
        }
        return Optional.of(new CertificationArtifactLock(new ArtifactReference(reference), new ContentHash(hash)));
    }

    public static Map<String, Object> add(Map<String, Object> base, Optional<CertificationArtifactLock> receipt) {
        if (receipt.isEmpty()) return base;
        var result = new LinkedHashMap<>(base);
        result.put(SCHEMA_KEY, SCHEMA_VERSION);
        result.put(REFERENCE_KEY, receipt.orElseThrow().reference().value());
        result.put(HASH_KEY, receipt.orElseThrow().hash().sha256());
        lock(result);
        return Map.copyOf(result);
    }
}
