package io.github.flowerjvm.factory.host.worker;

import io.github.flowerjvm.factory.application.work.TrustedWorkerCallbackContext;
import io.github.flowerjvm.factory.application.work.WorkerCallbackCommand;
import io.github.flowerjvm.factory.application.work.WorkerCallbackIngressService;
import io.github.flowerjvm.factory.application.work.WorkerCallbackReceipt;
import io.github.flowerjvm.factory.application.work.WorkerCompletionPayloadStager;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactEncoder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.http.MediaType;

/** Routes authenticated status-poll completions through the same immutable callback inbox. */
public final class ArtifactWorkerCompletionPayloadStager implements WorkerCompletionPayloadStager {
    private final ArtifactStore artifacts;
    private final JacksonWorkerProtocolArtifactEncoder encoder;
    private final WorkerCallbackIngressService ingress;

    public ArtifactWorkerCompletionPayloadStager(
            ArtifactStore artifacts,
            JacksonWorkerProtocolArtifactEncoder encoder,
            WorkerCallbackIngressService ingress) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @Override
    public WorkerCallbackReceipt stage(
            TenantId tenantId,
            String workerBindingId,
            CodingWorkerCompletionPayload payload,
            Instant observedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(observedAt, "observedAt");
        byte[] content = encoder.encodeCompletionPayload(payload);
        if (content.length == 0 || content.length > WorkerCallbackController.MAX_CALLBACK_BYTES) {
            throw new IllegalArgumentException("WORKER_COMPLETION_PAYLOAD_SIZE_INVALID");
        }
        ContentHash hash = hash(content);
        ArtifactReference reference = new ArtifactReference(
                "factory-worker/status-completion/v1/" + hash.sha256());
        artifacts.store(new Artifact(
                tenantId, reference, hash, MediaType.APPLICATION_JSON_VALUE, content));
        TrustedWorkerCallbackContext context = new TrustedWorkerCallbackContext(
                tenantId,
                workerBindingId,
                "factory-worker-status-poller:" + workerBindingId);
        return ingress.receiveFromTrustedStatusJournal(
                new WorkerCallbackCommand(context, reference, hash), observedAt);
    }

    private static ContentHash hash(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
