package io.github.flowerjvm.factory.infrastructure.verification;

/** Fail-closed sandbox infrastructure failure with a stable machine code. */
public final class SandboxExecutionException extends Exception {
    private final String stableCode;

    public SandboxExecutionException(String stableCode, String message) {
        super(message);
        this.stableCode = stableCode;
    }

    public SandboxExecutionException(String stableCode, String message, Throwable cause) {
        super(message, cause);
        this.stableCode = stableCode;
    }

    public String stableCode() {
        return stableCode;
    }
}
