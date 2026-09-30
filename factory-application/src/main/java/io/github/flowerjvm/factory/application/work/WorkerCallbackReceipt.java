package io.github.flowerjvm.factory.application.work;

import java.util.Objects;
import java.util.Optional;

/** Uniform callback receipt; rejected responses never reveal whether another tenant owns a run. */
public record WorkerCallbackReceipt(Status status, String code, Optional<String> callbackId) {
    public enum Status { ACCEPTED, DUPLICATE, REJECTED }

    public WorkerCallbackReceipt {
        Objects.requireNonNull(status, "status");
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase code");
        }
        callbackId = Objects.requireNonNull(callbackId, "callbackId");
        if (status == Status.REJECTED && callbackId.isPresent()) {
            throw new IllegalArgumentException("a rejected callback must not disclose an inbox id");
        }
    }
}
