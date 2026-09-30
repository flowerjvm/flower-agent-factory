package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import java.util.Map;
import java.util.Set;

/** Strict v1 Certification issuance input; authority remains in the stored Certification. */
public record CertificationIssueInput(
        CertificationId certificationId,
        ContentHash inputLockManifestHash,
        long expectedCertificationVersion) {

    private static final Set<String> FIELDS = Set.of(
            CertificationIssueAction.CERTIFICATION_ID,
            CertificationIssueAction.INPUT_LOCK_MANIFEST_HASH,
            CertificationIssueAction.EXPECTED_CERTIFICATION_VERSION);

    public CertificationIssueInput {
        if (certificationId == null || inputLockManifestHash == null) {
            throw new IllegalArgumentException("certificationId and inputLockManifestHash are required");
        }
        if (expectedCertificationVersion < 0) {
            throw new IllegalArgumentException("expectedCertificationVersion must not be negative");
        }
    }

    public static CertificationIssueInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("input must contain exactly " + FIELDS);
        }
        String certificationId = requireText(
                input.get(CertificationIssueAction.CERTIFICATION_ID),
                CertificationIssueAction.CERTIFICATION_ID);
        String inputLockManifestHash = requireText(
                input.get(CertificationIssueAction.INPUT_LOCK_MANIFEST_HASH),
                CertificationIssueAction.INPUT_LOCK_MANIFEST_HASH);
        Object expectedVersion = input.get(CertificationIssueAction.EXPECTED_CERTIFICATION_VERSION);
        if (!(expectedVersion instanceof Byte
                || expectedVersion instanceof Short
                || expectedVersion instanceof Integer
                || expectedVersion instanceof Long)) {
            throw new IllegalArgumentException("expectedCertificationVersion must be an integer");
        }
        return new CertificationIssueInput(
                new CertificationId(certificationId),
                new ContentHash(inputLockManifestHash),
                ((Number) expectedVersion).longValue());
    }

    public Map<String, Object> toMap() {
        return Map.of(
                CertificationIssueAction.CERTIFICATION_ID, certificationId.value(),
                CertificationIssueAction.INPUT_LOCK_MANIFEST_HASH, inputLockManifestHash.sha256(),
                CertificationIssueAction.EXPECTED_CERTIFICATION_VERSION, expectedCertificationVersion);
    }

    private static String requireText(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text.trim();
    }
}
