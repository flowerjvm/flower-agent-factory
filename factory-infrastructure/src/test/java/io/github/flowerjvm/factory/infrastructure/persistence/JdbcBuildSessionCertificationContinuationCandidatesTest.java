package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JdbcBuildSessionCertificationContinuationCandidatesTest {
    private static final Instant BASE = Instant.parse("2026-09-02T03:00:00Z");

    @Test
    void selectsOnlyExactLiveAgentPackHandoffAndPostRequestCommitStates() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("certification_continuation_filter");
        FactoryDatabaseMigrations.migrate(dataSource);
        var repository = new JdbcBuildSessionRepository(dataSource);

        BuildSession candidateReady = session(
                "candidate-ready",
                new TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                BASE,
                BASE.plusSeconds(300),
                Optional.empty(),
                Optional.empty());
        BuildSession certifying = session(
                "certifying",
                new TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CERTIFYING,
                BuildSessionPhase.CERTIFY,
                BASE.plusSeconds(1),
                BASE.plusSeconds(300),
                Optional.empty(),
                Optional.empty());
        BuildSession wrongLine = session(
                "wrong-line",
                new TenantId("tenant-a"),
                new ProductLineId("other-line"),
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                BASE.plusSeconds(2),
                BASE.plusSeconds(300),
                Optional.empty(),
                Optional.empty());
        BuildSession wrongPhase = session(
                "wrong-phase",
                new TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.CERTIFY,
                BASE.plusSeconds(3),
                BASE.plusSeconds(300),
                Optional.empty(),
                Optional.empty());
        BuildSession cancellationRequested = session(
                "cancellation-requested",
                new TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                BASE.plusSeconds(4),
                BASE.plusSeconds(300),
                Optional.of(BASE.plusSeconds(4)),
                Optional.empty());
        BuildSession terminal = session(
                "terminal",
                new TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.FAILED,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                BASE.plusSeconds(5),
                BASE.plusSeconds(300),
                Optional.empty(),
                Optional.of("TERMINAL"));
        BuildSession newerThanCutoff = session(
                "newer-than-cutoff",
                new TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CERTIFYING,
                BuildSessionPhase.CERTIFY,
                BASE.plusSeconds(11),
                BASE.plusSeconds(300),
                Optional.empty(),
                Optional.empty());
        for (BuildSession value : List.of(
                candidateReady,
                certifying,
                wrongLine,
                wrongPhase,
                cancellationRequested,
                terminal,
                newerThanCutoff)) {
            repository.create(value);
        }

        assertEquals(
                List.of(candidateReady, certifying),
                repository.findCertificationContinuationCandidates(BASE.plusSeconds(10), 100));
    }

    @Test
    void appliesStableOldestFirstOrderingAndStrictBounds() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("certification_continuation_order");
        FactoryDatabaseMigrations.migrate(dataSource);
        var repository = new JdbcBuildSessionRepository(dataSource);

        BuildSession oldest = eligible(
                "oldest", "tenant-z", BASE, BASE.plusSeconds(200));
        BuildSession earlyDeadline = eligible(
                "early-deadline", "tenant-z", BASE.plusSeconds(1), BASE.plusSeconds(100));
        BuildSession tenantFirstIdA = eligible(
                "id-a", "tenant-a", BASE.plusSeconds(1), BASE.plusSeconds(200));
        BuildSession tenantFirstIdB = eligible(
                "id-b", "tenant-a", BASE.plusSeconds(1), BASE.plusSeconds(200));
        BuildSession tenantSecond = eligible(
                "id-a-tenant-b", "tenant-b", BASE.plusSeconds(1), BASE.plusSeconds(200));
        for (BuildSession value : List.of(
                tenantSecond, tenantFirstIdB, oldest, tenantFirstIdA, earlyDeadline)) {
            repository.create(value);
        }

        assertEquals(
                List.of(oldest, earlyDeadline, tenantFirstIdA),
                repository.findCertificationContinuationCandidates(BASE.plusSeconds(2), 3));
        assertEquals(
                List.of(oldest, earlyDeadline, tenantFirstIdA, tenantFirstIdB, tenantSecond),
                repository.findCertificationContinuationCandidates(BASE.plusSeconds(2), 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.findCertificationContinuationCandidates(BASE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.findCertificationContinuationCandidates(BASE, 1_001));
        assertThrows(
                NullPointerException.class,
                () -> repository.findCertificationContinuationCandidates(null, 1));
    }

    private static BuildSession eligible(
            String suffix, String tenant, Instant updatedAt, Instant deadlineAt) {
        return session(
                suffix,
                new TenantId(tenant),
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                updatedAt,
                deadlineAt,
                Optional.empty(),
                Optional.empty());
    }

    private static BuildSession session(
            String suffix,
            TenantId tenantId,
            ProductLineId productLineId,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Instant updatedAt,
            Instant deadlineAt,
            Optional<Instant> cancellationRequestedAt,
            Optional<String> terminalCode) {
        Instant createdAt = BASE.minusSeconds(100);
        return new BuildSession(
                new BuildSessionId("build-" + suffix),
                tenantId,
                new ProjectId("project-" + suffix),
                productLineId,
                "request-" + suffix,
                "principal-a",
                status,
                phase,
                new ArtifactReference("artifact:requirements:" + suffix),
                new ContentHash("a".repeat(64)),
                Optional.of("manager"),
                Optional.of("coding"),
                Optional.of(new ArtifactReference("artifact:blueprint:" + suffix)),
                Optional.of(new CandidateId("candidate-" + suffix)),
                Optional.of(new ContentHash("b".repeat(64))),
                Optional.empty(),
                0,
                3,
                createdAt,
                deadlineAt,
                cancellationRequestedAt,
                terminalCode,
                terminalCode,
                7,
                createdAt,
                updatedAt);
    }
}
