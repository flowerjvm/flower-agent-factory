package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.util.Objects;

/** Authenticated pointer to a tokenless, immutable Coding Worker callback body. */
public record WorkerCallbackCommand(
        TrustedWorkerCallbackContext trustedContext,
        ArtifactReference payloadArtifactRef,
        ContentHash payloadHash) {

    public WorkerCallbackCommand {
        Objects.requireNonNull(trustedContext, "trustedContext");
        Objects.requireNonNull(payloadArtifactRef, "payloadArtifactRef");
        Objects.requireNonNull(payloadHash, "payloadHash");
    }
}
