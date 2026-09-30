package io.github.flowerjvm.factory.infrastructure.worker.codex;

/** Sanitized local protocol failure; provider stdout/stderr and credentials are never retained. */
public final class CodexWorkerProtocolException extends RuntimeException {
    private final String stableCode;
    private final boolean effectUncertain;

    CodexWorkerProtocolException(String stableCode, boolean effectUncertain) {
        super(requireCode(stableCode));
        this.stableCode = stableCode;
        this.effectUncertain = effectUncertain;
    }

    public String stableCode() {
        return stableCode;
    }

    public boolean effectUncertain() {
        return effectUncertain;
    }

    private static String requireCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            return "CODING_WORKER_PROTOCOL_INVALID";
        }
        return code;
    }
}
