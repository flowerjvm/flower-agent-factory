package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact ref/hash loader for every worker protocol artifact consumed by application services. */
public final class WorkerProtocolArtifacts {
    private final ArtifactStore artifacts;
    private final WorkerProtocolArtifactDecoder decoder;

    public WorkerProtocolArtifacts(ArtifactStore artifacts, WorkerProtocolArtifactDecoder decoder) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public CodingWorkerCompletionPayload readCompletion(WorkerCallbackCommand command) {
        Objects.requireNonNull(command, "command");
        Artifact artifact = exact(
                command.trustedContext().tenantId(), command.payloadArtifactRef(), command.payloadHash());
        return decoder.decodeCompletionPayload(artifact.content());
    }

    public CodingWorkerCompletionPayload readCompletion(WorkerCallbackInboxEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Artifact artifact = exact(entry.tenantId(), entry.payloadArtifactRef(), entry.payloadHash());
        return decoder.decodeCompletionPayload(artifact.content());
    }

    public CodingWorkerInputManifest readInput(WorkOrder order) {
        Objects.requireNonNull(order, "order");
        Artifact artifact = exact(order.tenantId(), order.inputArtifactManifestRef(), order.inputManifestHash());
        return decoder.decodeInputManifest(artifact.content());
    }

    public CandidateSourceManifest readCandidateSource(
            TenantId tenantId, ArtifactReference reference, ContentHash artifactHash) {
        return decoder.decodeCandidateSourceManifest(exact(tenantId, reference, artifactHash).content());
    }

    /** Reads a manifest through an already-canonical domain reference, still rehashing its bytes. */
    public CandidateSourceManifest readCanonicalCandidateSource(
            TenantId tenantId, ArtifactReference reference) {
        Artifact artifact = artifacts.find(tenantId, reference)
                .orElseThrow(() -> new IllegalArgumentException("WORKER_ARTIFACT_REFERENCE_UNRESOLVED"));
        verifyArtifact(artifact, tenantId, reference, artifact.contentHash());
        return decoder.decodeCandidateSourceManifest(artifact.content());
    }

    public Artifact exact(TenantId tenantId, ArtifactReference reference, ContentHash expectedHash) {
        Artifact artifact = artifacts.find(tenantId, reference)
                .orElseThrow(() -> new IllegalArgumentException("WORKER_ARTIFACT_REFERENCE_UNRESOLVED"));
        verifyArtifact(artifact, tenantId, reference, expectedHash);
        return artifact;
    }

    private static void verifyArtifact(
            Artifact artifact,
            TenantId tenantId,
            ArtifactReference reference,
            ContentHash expectedHash) {
        byte[] content = artifact.content();
        if (!artifact.tenantId().equals(tenantId)
                || !artifact.reference().equals(reference)
                || !artifact.contentHash().equals(expectedHash)
                || !sha256(content).equals(expectedHash)) {
            throw new IllegalArgumentException("WORKER_ARTIFACT_HASH_MISMATCH");
        }
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
