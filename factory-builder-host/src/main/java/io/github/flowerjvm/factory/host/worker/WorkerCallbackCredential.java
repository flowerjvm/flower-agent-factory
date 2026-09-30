package io.github.flowerjvm.factory.host.worker;

import io.github.flowerjvm.factory.application.work.TrustedWorkerCallbackContext;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Arrays;
import java.util.Objects;

/** Host-owned credential binding. Secret bytes are never exposed through {@link #toString()}. */
public final class WorkerCallbackCredential {
    private final String keyId;
    private final TrustedWorkerCallbackContext trustedContext;
    private final byte[] hmacSecret;

    public WorkerCallbackCredential(
            String keyId,
            TenantId tenantId,
            String workerBindingId,
            String authenticatedPrincipalRef,
            byte[] hmacSecret) {
        this.keyId = requireText(keyId, "keyId", 128);
        this.trustedContext = new TrustedWorkerCallbackContext(
                Objects.requireNonNull(tenantId, "tenantId"),
                workerBindingId,
                authenticatedPrincipalRef);
        byte[] copy = Objects.requireNonNull(hmacSecret, "hmacSecret").clone();
        if (copy.length < 32 || copy.length > 128) {
            Arrays.fill(copy, (byte) 0);
            throw new IllegalArgumentException("hmacSecret must contain 32..128 bytes");
        }
        this.hmacSecret = copy;
    }

    public String keyId() {
        return keyId;
    }

    public TrustedWorkerCallbackContext trustedContext() {
        return trustedContext;
    }

    byte[] secretCopy() {
        return hmacSecret.clone();
    }

    @Override
    public String toString() {
        return "WorkerCallbackCredential[keyId=" + keyId
                + ", trustedContext=" + trustedContext
                + ", hmacSecret=[REDACTED]]";
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
