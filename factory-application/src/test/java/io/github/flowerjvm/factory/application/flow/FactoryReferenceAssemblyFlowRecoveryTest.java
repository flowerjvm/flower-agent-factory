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

class FactoryReferenceAssemblyFlowRecoveryTest {
    private static final String WORKER = "reference-assembly-recovery-test";
    private static final Instant NOW = Instant.parse("2026-09-02T04:00:00Z");

    @Test
    void relaunchesOneStablePrimaryFlowAcrossDomainBeforeCheckpointRetries() {
        BuildSession progressed = session(BuildSessionPhase.RESOLVE_REUSE_STRATEGY);
        MemorySessions sessions = new MemorySessions(progressed);
        Worker worker = Worker.builder(WORKER).build();
        Engine engine = Engine.builder()
                .worker(worker)
                .checkpointStore(new MemoryCheckpointStore())
                .build();
        engine.attach();
        try {
            var line = new ReferenceAssemblyFlowFactory(
                    (phase, buildSessionId, context) -> StepResult.stay());
            var launcher = new FactoryProductLineFlowLauncher(
                    sessions,
                    new FactoryProductLineRegistry(List.of(line)),
                    engine,
                    WORKER);
            var recovery = new FactoryReferenceAssemblyFlowRecovery(
                    sessions, launcher, Clock.fixed(NOW, ZoneOffset.UTC), 8);

            assertEquals(1, recovery.recoverBatch());
            assertEquals(1, recovery.recoverBatch());
            worker.tickOnce();

            var snapshot = worker.snapshot().getFirst();
            assertEquals(
                    FlowId.of(ReferenceAssemblyFlowFactory.FLOW_TYPE,
                            progressed.buildSessionId().value()),
                    snapshot.flowId());
            assertEquals(
                    FactoryReferenceAssemblyFlowRecovery.flowRunId(progressed),
                    snapshot.executionContext().runIdOrNull());
            assertEquals(
                    FactoryReferenceAssemblyFlowRecovery.traceId(progressed),
                    snapshot.executionContext().traceIdOrNull());
            assertEquals(NOW, sessions.lastUpdatedBefore);
            assertEquals(8, sessions.lastLimit);
        } finally {
            engine.stop();
        }
    }

    @Test
    void rejectsUnboundedScannerLimits() {
        BuildSession current = session(BuildSessionPhase.UNDERSTAND_CUSTOMER);
        MemorySessions sessions = new MemorySessions(current);
        Worker worker = Worker.builder(WORKER + "-bounds").build();
        Engine engine = Engine.builder()
                .worker(worker)
                .checkpointStore(new MemoryCheckpointStore())
                .build();
        var line = new ReferenceAssemblyFlowFactory(
                (phase, buildSessionId, context) -> StepResult.stay());
        var launcher = new FactoryProductLineFlowLauncher(
                sessions,
                new FactoryProductLineRegistry(List.of(line)),
                engine,
                WORKER + "-bounds");

        assertThrows(
                IllegalArgumentException.class,
                () -> new FactoryReferenceAssemblyFlowRecovery(
                        sessions, launcher, Clock.fixed(NOW, ZoneOffset.UTC), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FactoryReferenceAssemblyFlowRecovery(
                        sessions, launcher, Clock.fixed(NOW, ZoneOffset.UTC), 1_001));
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
        public List<BuildSession> findReferenceAssemblyFlowCandidates(
                Instant updatedBefore, int limit) {
            lastUpdatedBefore = updatedBefore;
            lastLimit = limit;
            return current.updatedAt().isAfter(updatedBefore)
                    ? List.of()
                    : List.of(current);
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            return false;
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

    private static BuildSession session(BuildSessionPhase phase) {
        return new BuildSession(
                new BuildSessionId("build-reference-assembly-recovery"),
                new TenantId("tenant-reference-assembly-recovery"),
                new ProjectId("project-reference-assembly-recovery"),
                ProductLineId.REFERENCE_ASSEMBLY,
                "request-reference-assembly-recovery",
                "principal-reference-assembly-recovery",
                BuildSessionStatus.RUNNING,
                phase,
                new ArtifactReference("artifact:reference-assembly-requirements"),
                new ContentHash("a".repeat(64)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                1,
                NOW.minusSeconds(100),
                NOW.plusSeconds(300),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1,
                NOW.minusSeconds(100),
                NOW.minusSeconds(1));
    }
}
