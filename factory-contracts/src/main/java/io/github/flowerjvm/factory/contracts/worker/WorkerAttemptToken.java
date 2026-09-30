package io.github.flowerjvm.factory.contracts.worker;

import java.util.Objects;

/**
 * Transient Action-attempt credential passed to a Coding Worker.
 *
 * <p>The raw value is deliberately excluded from {@link #toString()}. Callers must use
 * {@link #reveal()} only at the transport/cryptographic boundary and must never persist it in a
 * Factory domain row, callback payload, log, or transcript.
 */
public final class WorkerAttemptToken {
    private static final int MAX_LENGTH = 4096;

    private final String value;

    public WorkerAttemptToken(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("attempt token must be bounded non-control text");
        }
        this.value = value;
    }

    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "WorkerAttemptToken[REDACTED]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkerAttemptToken token && value.equals(token.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
