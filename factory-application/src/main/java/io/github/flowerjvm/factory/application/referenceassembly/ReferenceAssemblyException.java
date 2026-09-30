package io.github.flowerjvm.factory.application.referenceassembly;

/** Bounded, stable fail-closed error from the concrete Reference Assembly line. */
public final class ReferenceAssemblyException extends RuntimeException {
    public static final String ARTIFACT_INVALID = "REFERENCE_ASSEMBLY_ARTIFACT_INVALID";
    public static final String CANONICAL_ARTIFACT_INVALID =
            "REFERENCE_ASSEMBLY_CANONICAL_ARTIFACT_INVALID";
    public static final String STAGING_CONFLICT = "REFERENCE_ASSEMBLY_STAGING_CONFLICT";
    public static final String COMPONENT_REJECTED = "REFERENCE_ASSEMBLY_COMPONENT_REJECTED";
    public static final String COMPONENT_MISMATCH = "REFERENCE_ASSEMBLY_COMPONENT_MISMATCH";
    public static final String CONTRACT_MISMATCH = "REFERENCE_ASSEMBLY_CONTRACT_MISMATCH";
    public static final String FIXTURE_MISMATCH = "REFERENCE_ASSEMBLY_FIXTURE_MISMATCH";
    public static final String RUNTIME_MISMATCH = "REFERENCE_ASSEMBLY_RUNTIME_MISMATCH";
    public static final String ALGORITHM_MISMATCH = "REFERENCE_ASSEMBLY_ALGORITHM_MISMATCH";
    public static final String ADMISSION_POLICY_MISMATCH =
            "REFERENCE_ASSEMBLY_ADMISSION_POLICY_MISMATCH";

    private final String code;

    public ReferenceAssemblyException(String code, String message) {
        this(code, message, null);
    }

    public ReferenceAssemblyException(String code, String message, Throwable cause) {
        super(requireMessage(message), cause);
        if (code == null
                || code.length() > 128
                || !code.matches("REFERENCE_ASSEMBLY_[A-Z0-9]+(?:_[A-Z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "code must be a bounded REFERENCE_ASSEMBLY stable code");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static String requireMessage(String message) {
        if (message == null
                || message.isBlank()
                || message.length() > 512
                || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("message must be bounded non-control text");
        }
        return message;
    }
}
