package io.github.flowerjvm.factory.infrastructure.certification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JacksonCertificationArtifactCodecTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-001");
    private static final CertificationId CERTIFICATION = new CertificationId("certification-001");
    private static final Instant ISSUED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void writesDeterministicCanonicalJsonAndStrictlyReadsEveryPr6aArtifact() {
        JacksonCertificationArtifactCodec first = new JacksonCertificationArtifactCodec();
        JacksonCertificationArtifactCodec second = new JacksonCertificationArtifactCodec();
        CertificationInputLock input = inputLock();
        CertificationEvidenceManifest evidence = evidence(input);
        AgentPackCompatibilityDescriptor compatibility = compatibility(input, "0.2.0");
        CertifiedAgentComponentManifest manifest = manifest(input);

        assertArrayEquals(first.writeInputLock(input), second.writeInputLock(input));
        assertArrayEquals(first.writeEvidence(evidence), second.writeEvidence(evidence));
        assertArrayEquals(first.writeCompatibilityDescriptor(compatibility),
                second.writeCompatibilityDescriptor(compatibility));
        assertArrayEquals(first.writeComponentManifest(manifest), second.writeComponentManifest(manifest));
        assertEquals(input, first.readInputLock(first.writeInputLock(input)));
        assertEquals(evidence, first.readEvidence(first.writeEvidence(evidence)));
        assertEquals(compatibility,
                first.readCompatibilityDescriptor(first.writeCompatibilityDescriptor(compatibility)));
        assertEquals(manifest, first.readComponentManifest(first.writeComponentManifest(manifest)));
    }

    @Test
    void contentHashChangesWhenAnExactCompatibilityLockChanges() {
        JacksonCertificationArtifactCodec codec = new JacksonCertificationArtifactCodec();
        CertificationInputLock input = inputLock();

        assertNotEquals(
                sha256(codec.writeCompatibilityDescriptor(compatibility(input, "0.2.0"))),
                sha256(codec.writeCompatibilityDescriptor(compatibility(input, "0.2.1"))));
    }

    @Test
    void rejectsNonCanonicalUnknownDuplicateAndTrailingJson() {
        JacksonCertificationArtifactCodec codec = new JacksonCertificationArtifactCodec();
        byte[] canonical = codec.writeInputLock(inputLock());
        String json = new String(canonical, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> codec.readInputLock((json + "\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.readInputLock((json.substring(0, json.length() - 1) + ",\"unknown\":true}")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.readInputLock(("{\"schemaVersion\":\"x\"," + json.substring(1))
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.readInputLock((json + "{}").getBytes(StandardCharsets.UTF_8)));
    }

    private static CertificationInputLock inputLock() {
        return new CertificationInputLock(
                CertificationInputLock.SCHEMA_VERSION,
                TENANT,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new BuildSessionId("build-001"),
                new WorkOrderId("work-order-001"),
                CANDIDATE,
                hash('1'),
                lock("artifact:source", '1'),
                lock("artifact:dependency", '2'),
                lock("artifact:toolchain", '3'),
                lock("artifact:generation-input", '4'),
                lock("artifact:product-contract", '5'),
                lock("artifact:api-signature", '6'),
                "sha256-ordinal-v1",
                "internal",
                new VerificationRunId("verification-001"),
                "action-verification-001",
                lock("artifact:verification-result", '7'),
                hash('8'),
                lock("artifact:policy", '9'),
                lock("artifact:compatibility", 'a'),
                "internal",
                "0.2.0",
                "0.1.3",
                "0.3.3");
    }

    private static CertificationEvidenceManifest evidence(CertificationInputLock input) {
        return new CertificationEvidenceManifest(
                CertificationEvidenceManifest.SCHEMA_VERSION,
                TENANT,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                CERTIFICATION,
                CANDIDATE,
                input.candidateHash(),
                hash('b'),
                input.verificationRunId(),
                input.verificationActionRunId(),
                input.verificationResultManifest().hash(),
                input.compatibilityDescriptor().hash(),
                input.certificationProfile(),
                input.factoryVersion(),
                input.flowerVersion(),
                input.actionRuntimeVersion());
    }

    private static AgentPackCompatibilityDescriptor compatibility(
            CertificationInputLock input, String factoryVersion) {
        return new AgentPackCompatibilityDescriptor(
                AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                TENANT,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                CANDIDATE,
                input.candidateHash(),
                input.productContractBundle(),
                input.apiSignatureIndex(),
                input.dependencyLock(),
                input.toolchainLock(),
                input.gateProfile(),
                input.sourceLockAlgorithmId(),
                factoryVersion,
                input.flowerVersion(),
                input.actionRuntimeVersion());
    }

    private static CertifiedAgentComponentManifest manifest(CertificationInputLock input) {
        return new CertifiedAgentComponentManifest(
                CertifiedAgentComponentManifest.SCHEMA_VERSION,
                TENANT,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                CERTIFICATION,
                CANDIDATE,
                input.candidateHash(),
                input.sourceManifest(),
                lock("artifact:input-lock", 'b'),
                input.verificationRunId(),
                input.verificationResultManifest(),
                input.compatibilityDescriptor(),
                lock("artifact:evidence", 'c'),
                input.certificationProfile(),
                input.factoryVersion(),
                ISSUED_AT,
                ISSUED_AT.plusSeconds(3600),
                CertifiedAgentComponentManifest.CERTIFIED_STATUS);
    }

    private static CertificationArtifactLock lock(String reference, char hashCharacter) {
        return new CertificationArtifactLock(new ArtifactReference(reference), hash(hashCharacter));
    }

    private static ContentHash hash(char character) {
        return new ContentHash(String.valueOf(character).repeat(64));
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
