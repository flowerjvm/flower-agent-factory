package io.github.flowerjvm.factory.infrastructure.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionCheck;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class JacksonReferenceAssemblyArtifactCodecTest {
    @Test
    void roundTripsAllReferenceAssemblyArtifactsWithDeterministicCanonicalBytes() {
        JacksonReferenceAssemblyArtifactCodec first = new JacksonReferenceAssemblyArtifactCodec();
        JacksonReferenceAssemblyArtifactCodec second = new JacksonReferenceAssemblyArtifactCodec();
        ReferenceAssemblyConsumerContract consumerContract = consumerContract();
        ReferenceAssemblyRequirement requirement = requirement(component('1'));
        ReferenceAssemblyManifest manifest = manifest(requirement.component());
        ReferenceAssemblyInspectionReport inspection = inspection(manifest.component());
        ReferenceAssemblyReleaseSubject releaseSubject = releaseSubject();
        ReferenceAssemblyReleaseManifest releaseManifest = releaseManifest(
                manifest.component(), sha256(first.writeReleaseSubject(releaseSubject)));

        assertArrayEquals(first.writeRequirement(requirement), second.writeRequirement(requirement));
        assertArrayEquals(
                first.writeConsumerContract(consumerContract),
                second.writeConsumerContract(consumerContract));
        assertArrayEquals(first.writeManifest(manifest), second.writeManifest(manifest));
        assertArrayEquals(
                first.writeInspectionReport(inspection),
                second.writeInspectionReport(inspection));
        assertArrayEquals(
                first.writeReleaseSubject(releaseSubject),
                second.writeReleaseSubject(releaseSubject));
        assertArrayEquals(
                first.writeReleaseManifest(releaseManifest),
                second.writeReleaseManifest(releaseManifest));
        assertEquals(requirement, first.readRequirement(first.writeRequirement(requirement)));
        assertEquals(
                consumerContract,
                first.readConsumerContract(first.writeConsumerContract(consumerContract)));
        assertEquals(manifest, first.readManifest(first.writeManifest(manifest)));
        assertEquals(
                inspection,
                first.readInspectionReport(first.writeInspectionReport(inspection)));
        assertEquals(
                releaseSubject,
                first.readReleaseSubject(first.writeReleaseSubject(releaseSubject)));
        assertEquals(
                releaseManifest,
                first.readReleaseManifest(first.writeReleaseManifest(releaseManifest)));
        assertFalse(new String(first.writeRequirement(requirement), StandardCharsets.UTF_8)
                .contains("tenantId"));
        String releaseJson = new String(
                first.writeReleaseSubject(releaseSubject), StandardCharsets.UTF_8)
                + new String(first.writeReleaseManifest(releaseManifest), StandardCharsets.UTF_8);
        assertFalse(releaseJson.contains("\"tenantId\""));
        assertFalse(releaseJson.contains("\"createdAt\""));
        assertFalse(releaseJson.contains("\"issuedAt\""));
        assertFalse(releaseJson.contains("\"selfHash\""));
    }

    @Test
    void rejectsUnknownDuplicateTrailingAndNonCanonicalJsonForEveryArtifact() {
        JacksonReferenceAssemblyArtifactCodec codec = new JacksonReferenceAssemblyArtifactCodec();
        List<StrictCase> cases = List.of(
                new StrictCase(
                        codec.writeRequirement(requirement(component('1'))),
                        ReferenceAssemblyRequirement.SCHEMA_VERSION,
                        bytes -> codec.readRequirement(bytes)),
                new StrictCase(
                        codec.writeConsumerContract(consumerContract()),
                        ReferenceAssemblyConsumerContract.SCHEMA_VERSION,
                        bytes -> codec.readConsumerContract(bytes)),
                new StrictCase(
                        codec.writeManifest(manifest(component('1'))),
                        ReferenceAssemblyManifest.SCHEMA_VERSION,
                        bytes -> codec.readManifest(bytes)),
                new StrictCase(
                        codec.writeInspectionReport(inspection(component('1'))),
                        ReferenceAssemblyInspectionReport.SCHEMA_VERSION,
                        bytes -> codec.readInspectionReport(bytes)),
                new StrictCase(
                        codec.writeReleaseSubject(releaseSubject()),
                        ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                        bytes -> codec.readReleaseSubject(bytes)),
                new StrictCase(
                        codec.writeReleaseManifest(releaseManifest(
                                component('1'), sha256(codec.writeReleaseSubject(releaseSubject())))),
                        ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                        bytes -> codec.readReleaseManifest(bytes)));

        for (StrictCase value : cases) {
            String json = new String(value.canonical(), StandardCharsets.UTF_8);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> value.reader().accept(
                            (json.substring(0, json.length() - 1) + ",\"unknown\":true}")
                                    .getBytes(StandardCharsets.UTF_8)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> value.reader().accept(
                            ("{\"schemaVersion\":\"" + value.schemaVersion() + "\"," + json.substring(1))
                                    .getBytes(StandardCharsets.UTF_8)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> value.reader().accept((json + "{}").getBytes(StandardCharsets.UTF_8)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> value.reader().accept((json + "\n").getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void rejectsEmptyOversizedAndStructurallyNonConcreteContracts() {
        JacksonReferenceAssemblyArtifactCodec codec = new JacksonReferenceAssemblyArtifactCodec();

        assertThrows(IllegalArgumentException.class, () -> codec.readRequirement(new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.readRequirement(
                        new byte[JacksonReferenceAssemblyArtifactCodec.MAX_ARTIFACT_BYTES + 1]));
        assertThrows(
                IllegalArgumentException.class,
                () -> requirement(component("different-role", '1')));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReferenceAssemblyConsumerContract(
                        ReferenceAssemblyConsumerContract.SCHEMA_VERSION,
                        ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                        ReferenceAssemblyConsumerContract.CONTRACT_ID,
                        ReferenceAssemblyConsumerContract.CONTRACT_VERSION,
                        "different-role",
                        ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                        lock("agent-product-contract", '8'),
                        lock("api-signature", '9'),
                        lock("host-fixture", 'a'),
                        ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                        ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                        ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID));
        List<ReferenceAssemblyInspectionCheck> reversed = passedChecks().reversed();
        assertThrows(
                IllegalArgumentException.class,
                () -> inspection(component('1'), reversed, ReferenceAssemblyInspectionReport.PASSED));
        List<ReferenceAssemblyInspectionCheck> oneFailed = passedChecks().stream()
                .map(check -> check.checkId().equals("host-fixture")
                        ? new ReferenceAssemblyInspectionCheck(
                                check.checkId(), false, "REFERENCE_ASSEMBLY_HOST_FIXTURE_REJECTED")
                        : check)
                .toList();
        assertThrows(
                IllegalArgumentException.class,
                () -> inspection(component('1'), oneFailed, ReferenceAssemblyInspectionReport.PASSED));
    }

    @Test
    void changingExactComponentCertificationManifestChangesCanonicalAssemblyBytesAndHash() {
        JacksonReferenceAssemblyArtifactCodec codec = new JacksonReferenceAssemblyArtifactCodec();
        byte[] first = codec.writeManifest(manifest(component('1')));
        byte[] second = codec.writeManifest(manifest(component('f')));

        assertNotEquals(new String(first, StandardCharsets.UTF_8),
                new String(second, StandardCharsets.UTF_8));
        assertNotEquals(sha256(first), sha256(second));
    }

    @Test
    void changingOneExactReleaseLockChangesCanonicalBytesAndContentHash() {
        JacksonReferenceAssemblyArtifactCodec codec = new JacksonReferenceAssemblyArtifactCodec();
        ReferenceAssemblyReleaseSubject subject = releaseSubject();
        ReferenceAssemblyReleaseSubject changedSubject = new ReferenceAssemblyReleaseSubject(
                ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                subject.referenceAssemblyId(),
                subject.inspectedAssemblyVersion(),
                lock("assembly-manifest-changed", 'f'),
                subject.inspectionReport(),
                subject.componentCertificationId(),
                subject.componentCandidateHash(),
                subject.componentCertificationManifest(),
                subject.policySnapshot());

        byte[] subjectBytes = codec.writeReleaseSubject(subject);
        byte[] changedSubjectBytes = codec.writeReleaseSubject(changedSubject);
        assertNotEquals(sha256(subjectBytes), sha256(changedSubjectBytes));

        ContentHash subjectHash = sha256(subjectBytes);
        ReferenceAssemblyReleaseManifest manifest = releaseManifest(component('1'), subjectHash);
        ReferenceAssemblyReleaseManifest changedManifest = new ReferenceAssemblyReleaseManifest(
                ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                ReferenceAssemblyReleaseManifest.RELEASE_ALGORITHM_ID,
                manifest.referenceAssemblyId(),
                manifest.requirement(),
                manifest.consumerContract(),
                manifest.hostFixture(),
                manifest.policySnapshot(),
                manifest.component(),
                manifest.assemblyManifest(),
                lock("inspection-report-changed", 'd'),
                manifest.releaseDecisionPointId(),
                manifest.releaseSubjectHash(),
                manifest.releaseActionRunId());
        assertNotEquals(
                sha256(codec.writeReleaseManifest(manifest)),
                sha256(codec.writeReleaseManifest(changedManifest)));
    }

    @Test
    void rejectsInvalidReleaseVersionsAlgorithmsAndBoundedOwnerIdentities() {
        ReferenceAssemblyReleaseSubject subject = releaseSubject();
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReferenceAssemblyReleaseSubject(
                        ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                        ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                        subject.referenceAssemblyId(),
                        -1,
                        subject.assemblyManifest(),
                        subject.inspectionReport(),
                        subject.componentCertificationId(),
                        subject.componentCandidateHash(),
                        subject.componentCertificationManifest(),
                        subject.policySnapshot()));

        ReferenceAssemblyReleaseManifest manifest =
                releaseManifest(component('1'), hash('0'));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReferenceAssemblyReleaseManifest(
                        ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                        ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                        "factory.reference-assembly-release-manifest.v2",
                        manifest.referenceAssemblyId(),
                        manifest.requirement(),
                        manifest.consumerContract(),
                        manifest.hostFixture(),
                        manifest.policySnapshot(),
                        manifest.component(),
                        manifest.assemblyManifest(),
                        manifest.inspectionReport(),
                        manifest.releaseDecisionPointId(),
                        manifest.releaseSubjectHash(),
                        manifest.releaseActionRunId()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReferenceAssemblyReleaseManifest(
                        ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                        ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                        ReferenceAssemblyReleaseManifest.RELEASE_ALGORITHM_ID,
                        manifest.referenceAssemblyId(),
                        manifest.requirement(),
                        manifest.consumerContract(),
                        manifest.hostFixture(),
                        manifest.policySnapshot(),
                        manifest.component(),
                        manifest.assemblyManifest(),
                        manifest.inspectionReport(),
                        manifest.releaseDecisionPointId(),
                        manifest.releaseSubjectHash(),
                        "x".repeat(65)));
    }

    private static ReferenceAssemblyReleaseSubject releaseSubject() {
        return new ReferenceAssemblyReleaseSubject(
                ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                new ReferenceAssemblyId("reference-assembly-release"),
                3,
                lock("assembly-manifest", 'e'),
                lock("inspection-report", 'f'),
                new CertificationId("certification-reference-assembly"),
                hash('2'),
                lock("certification-manifest", '1'),
                lock("policy", 'c'));
    }

    private static ReferenceAssemblyReleaseManifest releaseManifest(
            CertifiedAgentComponentRef component, ContentHash releaseSubjectHash) {
        return new ReferenceAssemblyReleaseManifest(
                ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                ReferenceAssemblyReleaseManifest.RELEASE_ALGORITHM_ID,
                new ReferenceAssemblyId("reference-assembly-release"),
                lock("requirement", 'd'),
                lock("consumer-contract", 'b'),
                lock("host-fixture", 'a'),
                lock("policy", 'c'),
                component,
                lock("assembly-manifest", 'e'),
                lock("inspection-report", 'f'),
                new DecisionPointId("decision-point-reference-assembly-release"),
                releaseSubjectHash,
                "release-action-run-001");
    }

    private static ReferenceAssemblyRequirement requirement(CertifiedAgentComponentRef component) {
        return new ReferenceAssemblyRequirement(
                ReferenceAssemblyRequirement.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                lock("consumer-contract", 'b'),
                lock("host-fixture", 'a'),
                lock("policy", 'c'),
                component);
    }

    private static ReferenceAssemblyConsumerContract consumerContract() {
        return new ReferenceAssemblyConsumerContract(
                ReferenceAssemblyConsumerContract.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                ReferenceAssemblyConsumerContract.CONTRACT_ID,
                ReferenceAssemblyConsumerContract.CONTRACT_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                lock("agent-product-contract", '8'),
                lock("api-signature", '9'),
                lock("host-fixture", 'a'),
                ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
    }

    private static ReferenceAssemblyManifest manifest(CertifiedAgentComponentRef component) {
        return new ReferenceAssemblyManifest(
                ReferenceAssemblyManifest.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID,
                lock("requirement", 'd'),
                lock("consumer-contract", 'b'),
                lock("host-fixture", 'a'),
                lock("policy", 'c'),
                component);
    }

    private static ReferenceAssemblyInspectionReport inspection(
            CertifiedAgentComponentRef component) {
        return inspection(
                component,
                passedChecks(),
                ReferenceAssemblyInspectionReport.PASSED);
    }

    private static ReferenceAssemblyInspectionReport inspection(
            CertifiedAgentComponentRef component,
            List<ReferenceAssemblyInspectionCheck> checks,
            String status) {
        return new ReferenceAssemblyInspectionReport(
                ReferenceAssemblyInspectionReport.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                lock("assembly-manifest", 'e'),
                lock("consumer-contract", 'b'),
                lock("host-fixture", 'a'),
                lock("policy", 'c'),
                component,
                checks,
                status);
    }

    private static List<ReferenceAssemblyInspectionCheck> passedChecks() {
        return ReferenceAssemblyInspectionReport.REQUIRED_CHECK_IDS.stream()
                .map(checkId -> new ReferenceAssemblyInspectionCheck(
                        checkId,
                        true,
                        "REFERENCE_ASSEMBLY_"
                                + checkId.replace('-', '_').toUpperCase(Locale.ROOT)
                                + "_PASSED"))
                .toList();
    }

    private static CertifiedAgentComponentRef component(char certificationManifestHash) {
        return component(ReferenceAssemblyRequirement.COMPONENT_ROLE, certificationManifestHash);
    }

    private static CertifiedAgentComponentRef component(
            String componentRole, char certificationManifestHash) {
        return new CertifiedAgentComponentRef(
                CertifiedAgentComponentRef.SCHEMA_VERSION,
                componentRole,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new CertificationId("certification-reference-assembly"),
                lock("certification-manifest", certificationManifestHash),
                new CandidateId("candidate-reference-assembly"),
                hash('2'),
                lock("source-manifest", '3'),
                lock("input-lock", '4'),
                new VerificationRunId("verification-reference-assembly"),
                lock("verification-result", '5'),
                lock("compatibility", '6'),
                lock("certification-evidence", '7'),
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE);
    }

    private static CertificationArtifactLock lock(String name, char hashCharacter) {
        return new CertificationArtifactLock(
                new ArtifactReference("factory-reference-assembly/" + name + "/sha256/" + hash(hashCharacter).sha256()),
                hash(hashCharacter));
    }

    private static ContentHash hash(char character) {
        return new ContentHash(String.valueOf(character).repeat(64));
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record StrictCase(
            byte[] canonical,
            String schemaVersion,
            Consumer<byte[]> reader) {
        private StrictCase {
            canonical = canonical.clone();
        }

        @Override
        public byte[] canonical() {
            return canonical.clone();
        }
    }
}
