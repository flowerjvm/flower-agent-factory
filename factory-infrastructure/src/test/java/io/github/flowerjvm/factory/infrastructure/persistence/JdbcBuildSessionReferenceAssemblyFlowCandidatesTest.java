package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JdbcBuildSessionReferenceAssemblyFlowCandidatesTest {
    private static final Instant BASE = Instant.parse("2026-09-02T07:00:00Z");

    @Test
    void selectsOnlyExactActiveReferenceAssemblyStatesAcrossTenants() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("reference_assembly_flow_filter");
        FactoryDatabaseMigrations.migrate(dataSource);
        var repository = new JdbcBuildSessionRepository(dataSource);

        List<BuildSession> eligible = List.of(
                active(
                        "understand",
                        "tenant-z",
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.UNDERSTAND_CUSTOMER,
                        BASE),
                active(
                        "resolve",
                        "tenant-a",
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                        BASE.plusSeconds(1)),
                active(
                        "assemble",
                        "tenant-b",
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.ASSEMBLE_CANDIDATE,
                        BASE.plusSeconds(2)),
                active(
                        "inspect",
                        "tenant-c",
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.TEST,
                        BASE.plusSeconds(3)),
                active(
                        "release",
                        "tenant-d",
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.PACKAGE_RELEASE,
                        BASE.plusSeconds(4)),
                active(
                        "review",
                        "tenant-e",
                        BuildSessionStatus.WAITING_RELEASE_REVIEW,
                        BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                        BASE.plusSeconds(5)));

        List<BuildSession> excluded = List.of(
                session(
                        "agent-pack",
                        new TenantId("tenant-a"),
                        ProductLineId.AGENT_PACK,
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.UNDERSTAND_CUSTOMER,
                        BASE.plusSeconds(6),
                        BASE.plusSeconds(300),
                        Optional.empty(),
                        Optional.empty()),
                session(
                        "other-line",
                        new TenantId("tenant-a"),
                        new ProductLineId("other-line"),
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.TEST,
                        BASE.plusSeconds(7),
                        BASE.plusSeconds(300),
                        Optional.empty(),
                        Optional.empty()),
                active(
                        "running-review",
                        "tenant-a",
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                        BASE.plusSeconds(8)),
                active(
                        "waiting-package",
                        "tenant-a",
                        BuildSessionStatus.WAITING_RELEASE_REVIEW,
                        BuildSessionPhase.PACKAGE_RELEASE,
                        BASE.plusSeconds(9)),
                session(
                        "succeeded",
                        new TenantId("tenant-a"),
                        ProductLineId.REFERENCE_ASSEMBLY,
                        BuildSessionStatus.SUCCEEDED,
                        BuildSessionPhase.COMPLETE,
                        BASE.plusSeconds(10),
                        BASE.plusSeconds(300),
                        Optional.empty(),
                        Optional.of("REFERENCE_ASSEMBLY_RELEASED")),
                session(
                        "failed",
                        new TenantId("tenant-b"),
                        ProductLineId.REFERENCE_ASSEMBLY,
                        BuildSessionStatus.FAILED,
                        BuildSessionPhase.TEST,
                        BASE.plusSeconds(11),
                        BASE.plusSeconds(300),
                        Optional.empty(),
                        Optional.of("REFERENCE_ASSEMBLY_INSPECTION_REJECTED")),
                session(
                        "cancelled",
                        new TenantId("tenant-c"),
                        ProductLineId.REFERENCE_ASSEMBLY,
                        BuildSessionStatus.CANCELLED,
                        BuildSessionPhase.TEST,
                        BASE.plusSeconds(12),
                        BASE.plusSeconds(300),
                        Optional.of(BASE.plusSeconds(11)),
                        Optional.of("REFERENCE_ASSEMBLY_CANCELLED")),
                session(
                        "cancelling",
                        new TenantId("tenant-d"),
                        ProductLineId.REFERENCE_ASSEMBLY,
                        BuildSessionStatus.CANCELLING,
                        BuildSessionPhase.TEST,
                        BASE.plusSeconds(13),
                        BASE.plusSeconds(300),
                        Optional.of(BASE.plusSeconds(13)),
                        Optional.empty()),
                session(
                        "cancel-requested",
                        new TenantId("tenant-e"),
                        ProductLineId.REFERENCE_ASSEMBLY,
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.TEST,
                        BASE.plusSeconds(14),
                        BASE.plusSeconds(300),
                        Optional.of(BASE.plusSeconds(14)),
                        Optional.empty()),
                active(
                        "newer-than-cutoff",
                        "tenant-f",
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.TEST,
                        BASE.plusSeconds(21)));

        for (BuildSession value : concat(eligible, excluded)) {
            repository.create(value);
        }

        assertEquals(
                eligible,
                repository.findReferenceAssemblyFlowCandidates(BASE.plusSeconds(20), 100));
    }

    @Test
    void ordersTenantAgnosticCandidatesOldestFirstAndEnforcesBounds() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("reference_assembly_flow_order");
        FactoryDatabaseMigrations.migrate(dataSource);
        var repository = new JdbcBuildSessionRepository(dataSource);

        BuildSession oldest = ordered("oldest", "tenant-z", BASE, BASE.plusSeconds(200));
        BuildSession earlyDeadline = ordered(
                "early-deadline", "tenant-z", BASE.plusSeconds(1), BASE.plusSeconds(100));
        BuildSession tenantFirstIdA = ordered(
                "id-a", "tenant-a", BASE.plusSeconds(1), BASE.plusSeconds(200));
        BuildSession tenantFirstIdB = ordered(
                "id-b", "tenant-a", BASE.plusSeconds(1), BASE.plusSeconds(200));
        BuildSession tenantSecond = ordered(
                "id-c", "tenant-b", BASE.plusSeconds(1), BASE.plusSeconds(200));
        for (BuildSession value : List.of(
                tenantSecond, tenantFirstIdB, oldest, tenantFirstIdA, earlyDeadline)) {
            repository.create(value);
        }

        assertEquals(
                List.of(oldest, earlyDeadline, tenantFirstIdA),
                repository.findReferenceAssemblyFlowCandidates(BASE.plusSeconds(2), 3));
        assertEquals(
                List.of(oldest, earlyDeadline, tenantFirstIdA, tenantFirstIdB, tenantSecond),
                repository.findReferenceAssemblyFlowCandidates(BASE.plusSeconds(2), 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.findReferenceAssemblyFlowCandidates(BASE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.findReferenceAssemblyFlowCandidates(BASE, 1_001));
        assertThrows(
                NullPointerException.class,
                () -> repository.findReferenceAssemblyFlowCandidates(null, 1));
    }

    private static BuildSession active(
            String suffix,
            String tenant,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Instant updatedAt) {
        return session(
                suffix,
                new TenantId(tenant),
                ProductLineId.REFERENCE_ASSEMBLY,
                status,
                phase,
                updatedAt,
                BASE.plusSeconds(300),
                Optional.empty(),
                Optional.empty());
    }

    private static BuildSession ordered(
            String suffix, String tenant, Instant updatedAt, Instant deadlineAt) {
        return session(
                suffix,
                new TenantId(tenant),
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.TEST,
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
                new BuildSessionId("build-ra-" + suffix),
                tenantId,
                new ProjectId("project-ra-" + suffix),
                productLineId,
                "request-ra-" + suffix,
                "principal-reference-assembly",
                status,
                phase,
                new ArtifactReference("artifact:requirements:ra:" + suffix),
                new ContentHash("a".repeat(64)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                createdAt,
                deadlineAt,
                cancellationRequestedAt,
                terminalCode,
                terminalCode,
                7,
                createdAt,
                updatedAt);
    }

    private static List<BuildSession> concat(
            List<BuildSession> first, List<BuildSession> second) {
        var all = new java.util.ArrayList<BuildSession>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
