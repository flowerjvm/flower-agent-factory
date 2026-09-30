package io.github.flowerjvm.factory.application.referenceassembly;

/** Opaque fail-closed result when a released product cannot be proven from current evidence. */
public final class ReleasedReferenceAssemblyResolutionException extends RuntimeException {
    public static final String NOT_AVAILABLE = "RELEASED_REFERENCE_ASSEMBLY_NOT_AVAILABLE";
    private static final String MESSAGE = "released Reference Assembly is unavailable";

    private final String code;

    ReleasedReferenceAssemblyResolutionException() {
        super(MESSAGE);
        this.code = NOT_AVAILABLE;
    }

    public String code() {
        return code;
    }
}
