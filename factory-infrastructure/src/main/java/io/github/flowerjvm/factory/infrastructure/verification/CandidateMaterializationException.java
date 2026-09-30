package io.github.flowerjvm.factory.infrastructure.verification;

/** Fail-closed candidate input rejection carrying a stable, non-secret machine code. */
public final class CandidateMaterializationException extends Exception {
    private final String stableCode;

    public CandidateMaterializationException(String stableCode, String message) {
        super(message);
        this.stableCode = stableCode;
    }

    public CandidateMaterializationException(String stableCode, String message, Throwable cause) {
        super(message, cause);
        this.stableCode = stableCode;
    }

    public String stableCode() {
        return stableCode;
    }
}
