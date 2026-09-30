package io.github.flowerjvm.factory.contracts.worker;

import java.util.Objects;

/** Stable adapter failure that does not expose provider messages or credentials. */
public final class WorkerDispatchException extends RuntimeException {
    private final String stableCode;
    private final WorkerEffectCertainty effectCertainty;
    private final WorkerRetryDisposition retryDisposition;

    public WorkerDispatchException(
            String stableCode,
            WorkerEffectCertainty effectCertainty,
            WorkerRetryDisposition retryDisposition) {
        super(requireStableCode(stableCode));
        this.stableCode = stableCode;
        this.effectCertainty = Objects.requireNonNull(effectCertainty, "effectCertainty");
        this.retryDisposition = Objects.requireNonNull(retryDisposition, "retryDisposition");
        if (effectCertainty == WorkerEffectCertainty.UNCERTAIN
                && retryDisposition != WorkerRetryDisposition.MANUAL_REVIEW) {
            throw new IllegalArgumentException("uncertain external effects require MANUAL_REVIEW");
        }
    }

    public String stableCode() { return stableCode; }
    public WorkerEffectCertainty effectCertainty() { return effectCertainty; }
    public WorkerRetryDisposition retryDisposition() { return retryDisposition; }

    private static String requireStableCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("stableCode must be a bounded uppercase code");
        }
        return code;
    }
}
