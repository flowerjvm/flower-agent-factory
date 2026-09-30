package io.github.flowerjvm.factory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class Pr3DomainModelTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final BuildSessionId SESSION = new BuildSessionId("session-1");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-1");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId("work-order-1");
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void candidateVersionIsAnInsertOnlyGeneratedSnapshot() {
        CandidateVersionRepository repository = new InMemoryCandidateVersionRepository();
        var candidate = candidate(Optional.empty());

        repository.create(candidate);

        assertEquals(
                candidate,
                repository.find(TENANT, CANDIDATE).orElseThrow());
        assertEquals(
                candidate,
                repository.findByBuildSessionAndWorkOrder(TENANT, SESSION, WORK_ORDER).orElseThrow());
        assertFalse(repository.find(new TenantId("tenant-b"), CANDIDATE).isPresent());
        assertThrows(IllegalStateException.class, () -> repository.create(candidate));
        assertThrows(
                IllegalStateException.class,
                () -> repository.create(candidate(
                        new CandidateId("candidate-same-work-order"), Optional.empty())));
        assertEquals(1, CandidateVersionStatus.values().length);
        assertEquals(CandidateVersionStatus.GENERATED, candidate.status());
    }

    @Test
    void candidateVersionRejectsSelfParent() {
        assertThrows(IllegalArgumentException.class, () -> candidate(Optional.of(CANDIDATE)));
    }

    @Test
    void verificationRunUsesExplicitRequestedRunningTerminalLifecycle() {
        var requested = requestedVerification();
        var started = requested.start(CREATED_AT.plusSeconds(1));
        var passed = started.complete(
                VerificationRunStatus.PASSED,
                new ArtifactReference("artifact:verification-result"),
                CREATED_AT.plusSeconds(2));

        assertEquals(1, started.version());
        assertEquals(VerificationRunStatus.RUNNING, started.status());
        assertEquals(2, passed.version());
        assertEquals(VerificationRunStatus.PASSED, passed.status());
        assertTrue(passed.status().isTerminal());
        assertEquals(requested.candidateHash(), passed.candidateHash());
        assertEquals(requested.gateProfile(), passed.gateProfile());
        assertEquals(requested.toolchainLockHash(), passed.toolchainLockHash());
        assertEquals(requested.fixtureSetHash(), passed.fixtureSetHash());
        assertThrows(
                IllegalStateException.class,
                () -> requested.complete(
                        VerificationRunStatus.FAILED,
                        new ArtifactReference("artifact:result"),
                        CREATED_AT.plusSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.complete(
                        VerificationRunStatus.RUNNING,
                        new ArtifactReference("artifact:result"),
                        CREATED_AT.plusSeconds(2)));
    }

    @Test
    void verificationRepositoryIsTenantScopedAndVersionCasOnly() {
        VerificationRunRepository repository = new InMemoryVerificationRunRepository();
        var requested = requestedVerification();
        var running = requested.start(CREATED_AT.plusSeconds(1));
        repository.create(requested);

        assertTrue(repository.compareAndSet(requested, running));
        assertFalse(repository.compareAndSet(requested, running));
        var passed = running.complete(
                VerificationRunStatus.PASSED,
                new ArtifactReference("artifact:verification-repository-result"),
                CREATED_AT.plusSeconds(2));
        assertTrue(repository.compareAndSet(running, passed));
        var terminalOverwrite = new VerificationRun(
                passed.verificationRunId(),
                passed.tenantId(),
                passed.buildSessionId(),
                passed.candidateId(),
                passed.candidateHash(),
                passed.gateProfile(),
                passed.toolchainLockHash(),
                passed.fixtureSetHash(),
                VerificationRunStatus.FAILED,
                Optional.of(new ArtifactReference("artifact:terminal-overwrite")),
                passed.startedAt(),
                Optional.of(CREATED_AT.plusSeconds(3)),
                passed.version() + 1,
                passed.createdAt(),
                CREATED_AT.plusSeconds(3));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.compareAndSet(passed, terminalOverwrite));
        assertEquals(
                passed,
                repository.findLatestForCandidate(
                                TENANT,
                                SESSION,
                                CANDIDATE,
                                requested.candidateHash(),
                                requested.gateProfile())
                        .orElseThrow());
        assertFalse(repository.find(new TenantId("tenant-b"), requested.verificationRunId()).isPresent());
    }

    @Test
    void verificationLatestQueryUsesCreationOrderRatherThanLifecycleVersion() {
        VerificationRunRepository repository = new InMemoryVerificationRunRepository();
        var olderRequested = requestedVerification("verification-older", CREATED_AT);
        var olderTerminal = olderRequested
                .start(CREATED_AT.plusSeconds(1))
                .complete(
                        VerificationRunStatus.PASSED,
                        new ArtifactReference("artifact:older-result"),
                        CREATED_AT.plusSeconds(2));
        repository.create(olderRequested);
        assertTrue(repository.compareAndSet(olderRequested, olderRequested.start(CREATED_AT.plusSeconds(1))));
        assertTrue(repository.compareAndSet(olderRequested.start(CREATED_AT.plusSeconds(1)), olderTerminal));
        var newerRequested = requestedVerification("verification-newer", CREATED_AT.plusSeconds(10));
        repository.create(newerRequested);

        assertEquals(
                newerRequested,
                repository.findLatestForCandidate(
                                TENANT,
                                SESSION,
                                CANDIDATE,
                                newerRequested.candidateHash(),
                                newerRequested.gateProfile())
                        .orElseThrow());
    }

    @Test
    void verificationRunRejectsInvalidLifecycleShapesAndTimeTravel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerificationRun(
                        new VerificationRunId("verification-invalid"),
                        TENANT,
                        SESSION,
                        CANDIDATE,
                        hash("1"),
                        " ",
                        hash("2"),
                        hash("3"),
                        VerificationRunStatus.REQUESTED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        CREATED_AT,
                        CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> requestedVerification().start(CREATED_AT.minusSeconds(1)));
    }

    private static CandidateVersion candidate(Optional<CandidateId> parentCandidateId) {
        return candidate(CANDIDATE, parentCandidateId);
    }

    private static CandidateVersion candidate(
            CandidateId candidateId, Optional<CandidateId> parentCandidateId) {
        return new CandidateVersion(
                candidateId,
                TENANT,
                SESSION,
                parentCandidateId,
                new ArtifactReference("artifact:candidate-source-manifest"),
                hash("1"),
                new ArtifactReference("artifact:dependency-lock"),
                hash("2"),
                new ArtifactReference("artifact:toolchain-lock"),
                hash("3"),
                CandidateVersionStatus.GENERATED,
                WORK_ORDER,
                CREATED_AT);
    }

    private static VerificationRun requestedVerification() {
        return requestedVerification("verification-1", CREATED_AT);
    }

    private static VerificationRun requestedVerification(String verificationRunId, Instant createdAt) {
        return new VerificationRun(
                new VerificationRunId(verificationRunId),
                TENANT,
                SESSION,
                CANDIDATE,
                hash("1"),
                "pr3-deterministic",
                hash("2"),
                hash("3"),
                VerificationRunStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                createdAt,
                createdAt);
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }

    private record CandidateKey(TenantId tenantId, CandidateId candidateId) {}

    private record CandidateOwnerKey(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            WorkOrderId createdByWorkOrderId) {}

    private static final class InMemoryCandidateVersionRepository implements CandidateVersionRepository {
        private final Map<CandidateKey, CandidateVersion> records = new ConcurrentHashMap<>();
        private final Map<CandidateOwnerKey, CandidateId> candidatesByOwner = new ConcurrentHashMap<>();

        @Override
        public synchronized void create(CandidateVersion candidateVersion) {
            var key = new CandidateKey(candidateVersion.tenantId(), candidateVersion.candidateId());
            var ownerKey = new CandidateOwnerKey(
                    candidateVersion.tenantId(),
                    candidateVersion.buildSessionId(),
                    candidateVersion.createdByWorkOrderId());
            if (records.containsKey(key) || candidatesByOwner.containsKey(ownerKey)) {
                throw new IllegalStateException("CandidateVersion already exists");
            }
            records.put(key, candidateVersion);
            candidatesByOwner.put(ownerKey, candidateVersion.candidateId());
        }

        @Override
        public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
            return Optional.ofNullable(records.get(new CandidateKey(tenantId, candidateId)));
        }

        @Override
        public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                TenantId tenantId,
                BuildSessionId buildSessionId,
                WorkOrderId createdByWorkOrderId) {
            return records.values().stream()
                    .filter(candidate -> candidate.tenantId().equals(tenantId))
                    .filter(candidate -> candidate.buildSessionId().equals(buildSessionId))
                    .filter(candidate -> candidate.createdByWorkOrderId().equals(createdByWorkOrderId))
                    .findFirst();
        }
    }

    private record VerificationKey(TenantId tenantId, VerificationRunId verificationRunId) {}

    private static final class InMemoryVerificationRunRepository implements VerificationRunRepository {
        private final Map<VerificationKey, VerificationRun> records = new ConcurrentHashMap<>();

        @Override
        public void create(VerificationRun verificationRun) {
            var key = new VerificationKey(verificationRun.tenantId(), verificationRun.verificationRunId());
            if (records.putIfAbsent(key, verificationRun) != null) {
                throw new IllegalStateException("VerificationRun already exists");
            }
        }

        @Override
        public Optional<VerificationRun> find(TenantId tenantId, VerificationRunId verificationRunId) {
            return Optional.ofNullable(records.get(new VerificationKey(tenantId, verificationRunId)));
        }

        @Override
        public Optional<VerificationRun> findLatestForCandidate(
                TenantId tenantId,
                BuildSessionId buildSessionId,
                CandidateId candidateId,
                ContentHash candidateHash,
                String gateProfile) {
            return records.values().stream()
                    .filter(run -> run.tenantId().equals(tenantId))
                    .filter(run -> run.buildSessionId().equals(buildSessionId))
                    .filter(run -> run.candidateId().equals(candidateId))
                    .filter(run -> run.candidateHash().equals(candidateHash))
                    .filter(run -> run.gateProfile().equals(gateProfile))
                    .max(Comparator.comparing(VerificationRun::createdAt)
                            .thenComparing(run -> run.verificationRunId().value()));
        }

        @Override
        public boolean compareAndSet(VerificationRun expected, VerificationRun next) {
            if (!expected.tenantId().equals(next.tenantId())
                    || !expected.verificationRunId().equals(next.verificationRunId())) {
                throw new IllegalArgumentException("CAS identity must not change");
            }
            if (next.version() != expected.version() + 1) {
                throw new IllegalArgumentException("CAS version must increase by exactly one");
            }
            if (expected.status().isTerminal()) {
                throw new IllegalArgumentException("terminal VerificationRun must not be overwritten");
            }
            return records.replace(
                    new VerificationKey(expected.tenantId(), expected.verificationRunId()), expected, next);
        }
    }
}
