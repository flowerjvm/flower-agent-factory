package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void recordsEachReleaseRecoveryBoundaryAsOneVersionedSnapshot() {
        ReferenceAssembly requested = requested("release");
        ReferenceAssembly resolved = requested.resolveComponent(CREATED_AT.plusSeconds(1));
        ReferenceAssembly assembled = resolved.assemble(lock("assembly", '6'), CREATED_AT.plusSeconds(2));
        ReferenceAssembly inspected = assembled.inspect(lock("inspection", '7'), CREATED_AT.plusSeconds(3));
        DecisionPointId pointId = new DecisionPointId("point-reference-release");
        ContentHash subjectHash = hash('8');
        ReferenceAssembly reviewBound = inspected.bindReleaseReview(
                pointId, subjectHash, CREATED_AT.plusSeconds(4));
        ReferenceAssembly actionBound = reviewBound.bindReleaseAction(
                "release-action-001", CREATED_AT.plusSeconds(5));
        ReferenceAssembly reviewRejected = reviewBound.reject(
                "REFERENCE_ASSEMBLY_REVIEW_REJECTED", CREATED_AT.plusSeconds(5));
        ReferenceAssembly released = actionBound.release(
                lock("release", '9'),
                pointId,
                subjectHash,
                "release-action-001",
                CREATED_AT.plusSeconds(6));

        assertEquals(ReferenceAssemblyStatus.INSPECTED, actionBound.status());
        assertEquals(5, actionBound.version());
        assertEquals(Optional.empty(), actionBound.releaseManifest());
        assertEquals(ReferenceAssemblyStatus.RELEASED, released.status());
        assertEquals(6, released.version());
        assertEquals(Optional.of(pointId), released.releaseDecisionPointId());
        assertEquals(Optional.of(subjectHash), released.releaseSubjectHash());
        assertEquals(Optional.of("release-action-001"), released.releaseActionRunId());
        assertEquals(Optional.of(pointId), reviewRejected.releaseDecisionPointId());
        assertEquals(Optional.of(subjectHash), reviewRejected.releaseSubjectHash());
        assertThrows(IllegalStateException.class, () -> actionBound.reject(
                "REFERENCE_ASSEMBLY_ACTION_REJECTED", CREATED_AT.plusSeconds(6)));
        assertThrows(IllegalArgumentException.class, () -> actionBound.release(
                lock("release", '9'),
                pointId,
                subjectHash,
                "different-action",
                CREATED_AT.plusSeconds(6)));
    }

    @Test
    void failedInspectionCanBeRetainedInARejectedTerminalSnapshot() {
        ReferenceAssembly assembled = requested("inspection-failure")
                .resolveComponent(CREATED_AT.plusSeconds(1))
                .assemble(lock("assembly-failed", 'a'), CREATED_AT.plusSeconds(2));

        ReferenceAssembly rejected = assembled.rejectAfterInspection(
                lock("inspection-failed", 'b'),
                "REFERENCE_ASSEMBLY_INSPECTION_FAILED",
                CREATED_AT.plusSeconds(3));

        assertEquals(ReferenceAssemblyStatus.REJECTED, rejected.status());
        assertEquals(Optional.of(lock("inspection-failed", 'b')), rejected.inspectionReport());
        assertEquals(Optional.of("REFERENCE_ASSEMBLY_INSPECTION_FAILED"), rejected.stableCode());
        assertThrows(IllegalStateException.class, () -> rejected.reject(
                "REFERENCE_ASSEMBLY_REJECTED_AGAIN", CREATED_AT.plusSeconds(4)));
    }

    @Test
    void lifecycleRejectsPartialReviewAuthorityAndFloatingIdentity() {
        ReferenceAssembly inspected = requested("invalid-shape")
                .resolveComponent(CREATED_AT.plusSeconds(1))
                .assemble(lock("assembly-invalid", 'c'), CREATED_AT.plusSeconds(2))
                .inspect(lock("inspection-invalid", 'd'), CREATED_AT.plusSeconds(3));

        assertThrows(IllegalArgumentException.class, () -> new ReferenceAssembly(
                inspected.referenceAssemblyId(),
                inspected.tenantId(),
                inspected.buildSessionId(),
                inspected.requirement(),
                inspected.consumerContract(),
                inspected.hostFixture(),
                inspected.policySnapshot(),
                inspected.componentCertificationId(),
                inspected.componentCandidateHash(),
                inspected.componentCertificationManifest(),
                ReferenceAssemblyStatus.INSPECTED,
                inspected.assemblyManifest(),
                inspected.inspectionReport(),
                Optional.empty(),
                Optional.of(new DecisionPointId("point-partial")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                inspected.version() + 1,
                inspected.createdAt(),
                CREATED_AT.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceAssemblyId("assembly-latest"));
    }

    private static ReferenceAssembly requested(String suffix) {
        return ReferenceAssembly.requested(
                new ReferenceAssemblyId("reference-assembly-" + suffix),
                new TenantId("tenant-a"),
                new BuildSessionId("build-reference-" + suffix),
                lock("requirement-" + suffix, '1'),
                lock("consumer-" + suffix, '2'),
                lock("host-" + suffix, '3'),
                lock("policy-" + suffix, '4'),
                new CertificationId("component-certification-" + suffix),
                hash('5'),
                lock("component-manifest-" + suffix, '5'),
                CREATED_AT);
    }

    private static CertificationArtifactLock lock(String name, char hash) {
        return new CertificationArtifactLock(
                new ArtifactReference("artifact:" + name), hash(hash));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
