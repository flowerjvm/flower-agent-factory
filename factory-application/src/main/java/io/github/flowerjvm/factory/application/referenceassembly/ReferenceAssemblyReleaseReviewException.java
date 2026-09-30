package io.github.flowerjvm.factory.application.referenceassembly;

/** Stable fail-closed error while opening or reconciling a Reference Assembly release review. */
public final class ReferenceAssemblyReleaseReviewException extends IllegalStateException {
    private final String code;

    public ReferenceAssemblyReleaseReviewException(String code, String message) {
        this(code, message, null);
    }

    public ReferenceAssemblyReleaseReviewException(
            String code, String message, Throwable cause) {
        super(requireMessage(message), cause);
        if (code == null
                || code.length() > 128
                || !code.matches(
                        "REFERENCE_ASSEMBLY_RELEASE_REVIEW_[A-Z0-9]+(?:_[A-Z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "code must be a bounded REFERENCE_ASSEMBLY_RELEASE_REVIEW stable code");
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
