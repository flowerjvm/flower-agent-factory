package io.github.flowerjvm.factory.host.worker;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.env.Environment;

/**
 * Single-binding v0.1 registry populated only from external host configuration.
 *
 * <p>The secret is deliberately absent from application YAML and source defaults. Spring relaxed
 * binding permits deployment environments to provide
 * {@code FACTORY_WORKER_CALLBACK_HMAC_SECRET_BASE64} without writing it to the repository.
 */
public final class ConfiguredWorkerCallbackCredentialRegistry
        implements WorkerCallbackCredentialRegistry {
    static final String PREFIX = "factory.worker.callback.";
    private static final List<String> REQUIRED = List.of(
            "key-id", "tenant-id", "binding-id", "principal-ref", "hmac-secret-base64");

    private final Optional<WorkerCallbackCredential> credential;

    public ConfiguredWorkerCallbackCredentialRegistry(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        List<String> values = REQUIRED.stream()
                .map(name -> trimToNull(environment.getProperty(PREFIX + name)))
                .toList();
        long present = values.stream().filter(Objects::nonNull).count();
        if (present == 0) {
            this.credential = Optional.empty();
            return;
        }
        if (present != REQUIRED.size()) {
            throw new IllegalStateException("Factory worker callback credential is only partially configured");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(values.get(4));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalStateException("Factory worker callback HMAC secret is not valid base64", invalidBase64);
        }
        try {
            this.credential = Optional.of(new WorkerCallbackCredential(
                    values.get(0),
                    new TenantId(values.get(1)),
                    values.get(2),
                    values.get(3),
                    secret));
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public Optional<WorkerCallbackCredential> find(String keyId) {
        if (keyId == null) {
            return Optional.empty();
        }
        return credential.filter(candidate -> candidate.keyId().equals(keyId));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
