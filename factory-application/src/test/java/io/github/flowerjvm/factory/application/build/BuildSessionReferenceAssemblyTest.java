package io.github.flowerjvm.factory.application.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuildSessionReferenceAssemblyTest {
    private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");

    @Test
    void exactProductionStagesReviewAndReleaseProjectionFormOneStrictChain() {
        BuildSession requested = session(
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                NOW.plusSeconds(60));

        BuildSession resolved = requested.advanceReferenceAssemblyPhase(
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                NOW);
        BuildSession assembled = resolved.advanceReferenceAssemblyPhase(
                BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                BuildSessionPhase.ASSEMBLE_CANDIDATE,
                NOW.plusSeconds(1));
        BuildSession inspected = assembled.advanceReferenceAssemblyPhase(
                BuildSessionPhase.ASSEMBLE_CANDIDATE,
                BuildSessionPhase.TEST,
                NOW.plusSeconds(2));
        BuildSession waiting = inspected.awaitReferenceAssemblyReleaseReview(
                NOW.plusSeconds(3));
        BuildSession packaging = waiting.resumeReferenceAssemblyRelease(
                NOW.plusSeconds(4));
        BuildSession completed = packaging.completeReferenceAssemblyRelease(
                NOW.plusSeconds(120));

        assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, waiting.status());
        assertEquals(BuildSessionPhase.HUMAN_RELEASE_REVIEW, waiting.currentPhase());
        assertEquals(BuildSessionStatus.RUNNING, packaging.status());
        assertEquals(BuildSessionPhase.PACKAGE_RELEASE, packaging.currentPhase());
        assertEquals(BuildSessionStatus.SUCCEEDED, completed.status());
        assertEquals(BuildSessionPhase.COMPLETE, completed.currentPhase());
        assertEquals("REFERENCE_ASSEMBLY_RELEASED", completed.terminalCode().orElseThrow());
        assertEquals(requested.version() + 6, completed.version());
    }

    @Test
    void phaseOrderLineCancellationAndPreReleaseDeadlineFailClosed() {
        BuildSession exact = session(
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                NOW.plusSeconds(60));
        assertThrows(
                IllegalArgumentException.class,
                () -> exact.advanceReferenceAssemblyPhase(
                        BuildSessionPhase.UNDERSTAND_CUSTOMER,
                        BuildSessionPhase.TEST,
                        NOW));
        assertThrows(
                IllegalStateException.class,
                () -> exact.advanceReferenceAssemblyPhase(
                        BuildSessionPhase.UNDERSTAND_CUSTOMER,
                        BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                        NOW.plusSeconds(60)));
        BuildSession wrongLine = session(
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                NOW.plusSeconds(60));
        assertThrows(
                IllegalStateException.class,
                () -> wrongLine.advanceReferenceAssemblyPhase(
                        BuildSessionPhase.UNDERSTAND_CUSTOMER,
                        BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                        NOW));
    }

    @Test
    void deterministicFailureAndUncertainOwnerUseDifferentDurableStates() {
        BuildSession running = session(
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.TEST,
                NOW.plusSeconds(60));

        BuildSession failed = running.failReferenceAssembly(
                "REFERENCE_ASSEMBLY_INSPECTION_REJECTED", "inspection failed", NOW);
        BuildSession review = running.manualReviewReferenceAssembly(
                "REFERENCE_ASSEMBLY_RELEASE_OWNER_INVALID", "owner uncertain", NOW);

        assertEquals(BuildSessionStatus.FAILED, failed.status());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, review.status());
        assertThrows(
                IllegalStateException.class,
                () -> failed.failReferenceAssembly("ANOTHER_FAILURE", "again", NOW.plusSeconds(1)));
    }

    private static BuildSession session(
            ProductLineId productLine,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Instant deadline) {
        return new BuildSession(
                new BuildSessionId("build-reference-assembly-transition"),
                new TenantId("tenant-reference-assembly-transition"),
                new ProjectId("project-reference-assembly-transition"),
                productLine,
                "request-key",
                "test",
                status,
                phase,
                new ArtifactReference("requirements"),
                hash('f'),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                1,
                NOW.minusSeconds(600),
                deadline,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                7,
                NOW.minusSeconds(600),
                NOW.minusSeconds(1));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
