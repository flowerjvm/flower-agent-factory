package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpoint;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpointStore;
import io.github.flowerjvm.flower.core.step.StepResult;
import io.github.flowerjvm.flower.core.worker.Worker;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FactoryCertificationContinuationRecoveryTest {
    private static final String WORKER = "certification-continuation-recovery-test";
    private static final Instant NOW = Instant.parse("2026-09-02T04:00:00Z");

    @Test
    void normalCandidateReadyHandoffIsBoundedAndIdempotentlyLaunched() {
        BuildSession candidateReady = candidateReady();
        try (Fixture fixture = new Fixture(candidateReady, 1)) {
            assertEquals(1, fixture.recovery.recoverBatch());
            assertEquals(1, fixture.recovery.recoverBatch());

            fixture.worker.tickOnce();

            var snapshots = fixture.worker.snapshot();
            assertEquals(1, snapshots.size());
            assertEquals(
                    FlowId.of(
                            AgentPackCertificationFlowFactory.FLOW_TYPE,
                            candidateReady.buildSessionId().value()),
                    snapshots.getFirst().flowId());
            assertEquals(
                    FactoryCertificationContinuationRecovery.flowRunId(candidateReady),
                    snapshots.getFirst().executionContext().runIdOrNull());
            assertEquals(
                    FactoryCertificationContinuationRecovery.traceId(candidateReady),
                    snapshots.getFirst().executionContext().traceIdOrNull());
            assertEquals(NOW, fixture.sessions.lastUpdatedBefore);
            assertEquals(1, fixture.sessions.lastLimit);
        }
    }

    @Test
    void crashAfterRequestCommitBeforeCheckpointRelaunchesSameDeterministicContinuationIdentity() {
        BuildSession candidateReady = candidateReady();
        BuildSession requestCommitted = candidateReady.beginAgentPackCertification(NOW.minusSeconds(1));
        try (Fixture fixture = new Fixture(requestCommitted, 8)) {
            assertEquals(1, fixture.recovery.recoverBatch());

            fixture.worker.tickOnce();

            var snapshot = fixture.worker.snapshot().getFirst();
            assertEquals(
                    FlowId.of(
                            AgentPackCertificationFlowFactory.FLOW_TYPE,
                            requestCommitted.buildSessionId().value()),
                    snapshot.flowId());
            assertEquals(
                    FactoryCertificationContinuationRecovery.flowRunId(candidateReady),
                    FactoryCertificationContinuationRecovery.flowRunId(requestCommitted));
            assertEquals(
                    FactoryCertificationContinuationRecovery.traceId(candidateReady),
                    FactoryCertificationContinuationRecovery.traceId(requestCommitted));
            assertEquals(
                    FactoryCertificationContinuationRecovery.flowRunId(requestCommitted),
                    snapshot.executionContext().runIdOrNull());
        }
    }

    @Test
    void rejectsUnboundedScannerLimits() {
        BuildSession candidateReady = candidateReady();
        MemorySessions sessions = new MemorySessions(candidateReady);
        Worker worker = Worker.builder(WORKER + "-bounds").build();
        Engine engine = Engine.builder()
                .worker(worker)
                .checkpointStore(new MemoryCheckpointStore())
                .build();
        var launcher = launcher(sessions, engine, WORKER + "-bounds");

        assertThrows(
                IllegalArgumentException.class,
                () -> new FactoryCertificationContinuationRecovery(
                        sessions, launcher, Clock.fixed(NOW, ZoneOffset.UTC), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FactoryCertificationContinuationRecovery(
                        sessions, launcher, Clock.fixed(NOW, ZoneOffset.UTC), 1_001));
    }

    private static final class Fixture implements AutoCloseable {
        private final MemorySessions sessions;
        private final Worker worker;
        private final Engine engine;
        private final FactoryCertificationContinuationRecovery recovery;

        private Fixture(BuildSession session, int limit) {
            this.sessions = new MemorySessions(session);
            this.worker = Worker.builder(WORKER).build();
            this.engine = Engine.builder()
                    .worker(worker)
                    .checkpointStore(new MemoryCheckpointStore())
                    .build();
            engine.attach();
            this.recovery = new FactoryCertificationContinuationRecovery(
                    sessions,
                    launcher(sessions, engine, WORKER),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    limit);
        }

        @Override
        public void close() {
            engine.stop();
        }
    }

    private static final class MemoryCheckpointStore implements FlowCheckpointStore {
        private final Map<FlowId, FlowCheckpoint> checkpoints = new HashMap<>();

        @Override
        public void save(FlowCheckpoint checkpoint) {
            checkpoints.put(checkpoint.flowId(), checkpoint);
        }

        @Override
        public void delete(FlowId flowId) {
            checkpoints.remove(flowId);
        }

        @Override
        public Optional<FlowCheckpoint> find(FlowId flowId) {
            return Optional.ofNullable(checkpoints.get(flowId));
        }
    }

    private static FactoryContinuationFlowLauncher launcher(
            BuildSessionRepository sessions, Engine engine, String workerName) {
        var primary = new CreateCustomerAgentFlowFactory((phase, context) -> StepResult.stay());
        var continuation = new AgentPackCertificationFlowFactory(
                (buildSessionId, context) -> StepResult.stay());
        var flows = new FactoryFlowRegistry(
                new FactoryProductLineRegistry(List.of(primary)), List.of(continuation));
        return new FactoryContinuationFlowLauncher(sessions, flows, engine, workerName);
    }

    private static final class MemorySessions implements BuildSessionRepository {
        private final BuildSession current;
        private Instant lastUpdatedBefore;
        private int lastLimit;

        private MemorySessions(BuildSession current) {
            this.current = current;
        }

        @Override
        public void create(BuildSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
            return current.tenantId().equals(tenantId)
                            && current.buildSessionId().equals(buildSessionId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public List<BuildSession> findCertificationContinuationCandidates(
                Instant updatedBefore, int limit) {
            this.lastUpdatedBefore = updatedBefore;
            this.lastLimit = limit;
            return isCandidate(current) && !current.updatedAt().isAfter(updatedBefore)
                    ? List.of(current)
                    : List.of();
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            return false;
        }

        private static boolean isCandidate(BuildSession session) {
            if (!ProductLineId.AGENT_PACK.equals(session.productLineId())
                    || session.currentCertificationId().isPresent()
                    || session.cancellationRequestedAt().isPresent()
                    || session.status().isTerminal()) {
                return false;
            }
            return (session.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                            && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW)
                    || (session.status() == BuildSessionStatus.CERTIFYING
                            && session.currentPhase() == BuildSessionPhase.CERTIFY);
        }
    }

    private static BuildSession candidateReady() {
        Instant createdAt = NOW.minusSeconds(100);
        return new BuildSession(
                new BuildSessionId("build-certification-recovery"),
                new TenantId("tenant-certification-recovery"),
                new ProjectId("project-certification-recovery"),
                ProductLineId.AGENT_PACK,
                "request-certification-recovery",
                "principal-certification-recovery",
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                new ArtifactReference("artifact:requirements:certification-recovery"),
                new ContentHash("a".repeat(64)),
                Optional.of("manager"),
                Optional.of("coding"),
                Optional.of(new ArtifactReference("artifact:blueprint:certification-recovery")),
                Optional.of(new CandidateId("candidate-certification-recovery")),
                Optional.of(new ContentHash("b".repeat(64))),
                Optional.empty(),
                0,
                3,
                createdAt,
                NOW.plusSeconds(300),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                8,
                createdAt,
                NOW.minusSeconds(2));
    }
}
