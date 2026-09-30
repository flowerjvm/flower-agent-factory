package io.github.flowerjvm.factory.host.worker;

import java.util.Optional;

/** Resolves an opaque transport key id to trusted tenant and worker-binding authority. */
@FunctionalInterface
public interface WorkerCallbackCredentialRegistry {
    Optional<WorkerCallbackCredential> find(String keyId);
}
