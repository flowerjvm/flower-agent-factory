package io.github.flowerjvm.factory.application.referenceassembly;

/** Stable fail-closed signal raised when an exact release authority no longer holds. */
public final class ReferenceAssemblyReleaseAuthorityException extends RuntimeException {
    public static final String AUTHORITY_INVALID =
            "REFERENCE_ASSEMBLY_RELEASE_AUTHORITY_INVALID";

    public ReferenceAssemblyReleaseAuthorityException(String message) {
        super(message);
    }

    public ReferenceAssemblyReleaseAuthorityException(String message, Throwable cause) {
        super(message, cause);
    }

    public String code() {
        return AUTHORITY_INVALID;
    }
}
