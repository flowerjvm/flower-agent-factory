package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Code-owned trust root for the first concrete Agent Pack product contract.
 *
 * <p>The contract is deliberately a real immutable artifact rather than an arbitrary configured
 * hash. A production order must place these exact bytes at the exact tenant-scoped reference and
 * bind that lock in its Coding Worker input manifest before the order can be certified.
 */
public final class AgentPackProductContract {
    public static final String CONTRACT_ID = "flower-agent-pack";
    public static final String CONTRACT_VERSION = "1.0.0";
    public static final String MEDIA_TYPE = "application/json";

    private static final byte[] CANONICAL_BYTES = (
                    "{\"artifactType\":\"AGENT_PACK\","
                            + "\"candidateManifestSchemaVersion\":\""
                            + CandidateSourceManifest.SCHEMA_VERSION
                            + "\","
                            + "\"certifiedComponentManifestSchemaVersion\":\""
                            + CertifiedAgentComponentManifest.SCHEMA_VERSION
                            + "\","
                            + "\"contractId\":\"flower-agent-pack\","
                            + "\"contractVersion\":\"1.0.0\","
                            + "\"generationInputSchemaVersion\":\""
                            + CodingWorkerInputManifest.SCHEMA_VERSION
                            + "\","
                            + "\"productLineId\":\"agent-pack\","
                            + "\"requiredGateProfile\":\""
                            + ActionBackedVerificationRunLauncher.GATE_PROFILE
                            + "\","
                            + "\"schemaVersion\":\"factory.product-contract.v1\","
                            + "\"sourceLockAlgorithmId\":\""
                            + CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID
                            + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    private static final ContentHash CONTENT_HASH = sha256(CANONICAL_BYTES);
    private static final ArtifactReference REFERENCE = new ArtifactReference(
            "factory-product-contract/agent-pack/1.0.0/sha256/" + CONTENT_HASH.sha256());
    private static final CertificationArtifactLock LOCK =
            new CertificationArtifactLock(REFERENCE, CONTENT_HASH);

    private AgentPackProductContract() {}

    public static CertificationArtifactLock lock() {
        return LOCK;
    }

    public static byte[] canonicalBytes() {
        return Arrays.copyOf(CANONICAL_BYTES, CANONICAL_BYTES.length);
    }

    public static Artifact artifact(TenantId tenantId) {
        return new Artifact(
                Objects.requireNonNull(tenantId, "tenantId"),
                REFERENCE,
                CONTENT_HASH,
                MEDIA_TYPE,
                CANONICAL_BYTES);
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
