package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Payload-light security audit event; it never contains credentials, transcript, or result data. */
public record WorkerCallbackAuditEvent(
        Optional<TenantId> trustedTenantId,
        String workerBindingId,
        Optional<String> authenticatedPrincipalRef,
        Optional<String> eventId,
        Optional<String> workerRunId,
        String code,
        boolean accepted,
        Instant observedAt) {

    public WorkerCallbackAuditEvent {
        trustedTenantId = Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        workerBindingId = text(workerBindingId, "workerBindingId", 128);
        authenticatedPrincipalRef = optional(authenticatedPrincipalRef, "authenticatedPrincipalRef", 256);
        eventId = optional(eventId, "eventId", 128);
        workerRunId = optional(workerRunId, "workerRunId", 128);
        code = text(code, "code", 128);
        if (!code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase code");
        }
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static Optional<String> optional(Optional<String> value, String name, int max) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> text(text, name, max));
        return value;
    }

    private static String text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
