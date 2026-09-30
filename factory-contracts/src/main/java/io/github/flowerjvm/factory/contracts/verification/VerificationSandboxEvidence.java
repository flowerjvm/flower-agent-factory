package io.github.flowerjvm.factory.contracts.verification;

/** Fail-closed isolation facts bound into every independent verification result. */
public record VerificationSandboxEvidence(
        String backend,
        String imageDigest,
        String policyId,
        boolean networkDisabled,
        boolean readOnlyRootFilesystem,
        boolean nonRoot,
        long memoryBytes,
        double cpuCount,
        int pidLimit,
        long timeoutMillis) {

    public VerificationSandboxEvidence {
        backend = requireText(backend, "backend");
        imageDigest = requireText(imageDigest, "imageDigest");
        policyId = requireText(policyId, "policyId");
        if (memoryBytes < 1 || cpuCount <= 0 || pidLimit < 1 || timeoutMillis < 1) {
            throw new IllegalArgumentException("sandbox resource limits must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
