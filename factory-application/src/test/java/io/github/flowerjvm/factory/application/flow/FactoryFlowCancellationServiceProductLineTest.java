package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.work.WorkerCancellationRequester;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FactoryFlowCancellationServiceProductLineTest {
    private static final Instant NOW = Instant.parse("2026-09-02T08:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TenantId TENANT = new TenantId("tenant-product-cancel");
    private static final BuildSessionId SESSION =
            new BuildSessionId("build-product-cancel");

    @Test
    void missingRequiredProductLineRequesterFailsClosedBeforeAnyMutationOrWorkerFallback() {
        MemorySessions sessions = new MemorySessions(runningSession());
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        FactoryFlowCancellationService service = new FactoryFlowCancellationService(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-missing-owner").build()).build(),
                new FactoryProductLineRegistry(List.of(flowFactory())),
                workerCancellation(workerRuns),
                CLOCK);

        assertEquals(
                FlowCancellationDisposition.CONFLICT,
                service.cancel(TENANT, SESSION));
        assertEquals(BuildSessionStatus.RUNNING, sessions.current().status());
        assertEquals(0, workerRuns.activeLookups.get());
    }

    @Test
    void missingRequiredRequesterDuringCancellationConvergesToManualReview() {
        MemorySessions sessions = new MemorySessions(
                runningSession().requestCancellation(NOW.minusSeconds(1)));
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        FactoryFlowCancellationService service = new FactoryFlowCancellationService(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-missing-owner-recovery").build()).build(),
                new FactoryProductLineRegistry(List.of(flowFactory())),
                workerCancellation(workerRuns),
                CLOCK);

        assertEquals(
                FlowCancellationDisposition.CONFLICT,
                service.cancel(TENANT, SESSION));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, sessions.current().status());
        assertEquals(
                Optional.of(FactoryFlowCancellationService.CANCELLATION_EFFECT_CONFLICT),
                sessions.current().terminalCode());
        assertEquals(0, workerRuns.activeLookups.get());
    }

    @Test
    void productLinePreflightConflictLeavesSessionAndFallbackWorkerUntouched() {
        MemorySessions sessions = new MemorySessions(runningSession());
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        RecordingProductLineRequester productLine = new RecordingProductLineRequester(
                false, FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT);
        FactoryFlowCancellationService service = service(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-preflight").build()).build(),
                workerRuns,
                productLine);

        assertEquals(
                FlowCancellationDisposition.CONFLICT,
                service.cancel(TENANT, SESSION));
        assertEquals(BuildSessionStatus.RUNNING, sessions.current().status());
        assertEquals(0, productLine.requestCalls.get());
        assertEquals(0, workerRuns.activeLookups.get());
    }

    @Test
    void productLineConflictAfterCancellationCasEntersManualReview() {
        MemorySessions sessions = new MemorySessions(runningSession());
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        RecordingProductLineRequester productLine = new RecordingProductLineRequester(
                true, FactoryCancellationRequestDisposition.CONFLICT);
        FactoryFlowCancellationService service = service(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-post-cas-conflict").build()).build(),
                workerRuns,
                productLine);

        assertEquals(
                FlowCancellationDisposition.CONFLICT,
                service.cancel(TENANT, SESSION));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, sessions.current().status());
        assertEquals(
                Optional.of(FactoryFlowCancellationService.CANCELLATION_EFFECT_CONFLICT),
                sessions.current().terminalCode());
        assertEquals(1, productLine.requestCalls.get());
        assertEquals(0, workerRuns.activeLookups.get());
    }

    @Test
    void cancellingSessionWhosePreflightOwnerChangedEntersManualReview() {
        MemorySessions sessions = new MemorySessions(
                runningSession().requestCancellation(NOW.minusSeconds(1)));
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        RecordingProductLineRequester productLine = new RecordingProductLineRequester(
                false, FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT);
        FactoryFlowCancellationService service = service(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-revalidation-conflict").build()).build(),
                workerRuns,
                productLine);

        assertEquals(
                FlowCancellationDisposition.CONFLICT,
                service.cancel(TENANT, SESSION));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, sessions.current().status());
        assertEquals(
                Optional.of(FactoryFlowCancellationService.CANCELLATION_EFFECT_CONFLICT),
                sessions.current().terminalCode());
        assertEquals(0, productLine.requestCalls.get());
        assertEquals(0, workerRuns.activeLookups.get());
    }

    @Test
    void productLineNoEffectClosesSessionWithoutWorkerRunFallback() {
        MemorySessions sessions = new MemorySessions(runningSession());
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        RecordingProductLineRequester productLine = new RecordingProductLineRequester(
                true, FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT);
        FactoryFlowCancellationService service = service(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-no-effect").build()).build(),
                workerRuns,
                productLine);

        assertEquals(
                FlowCancellationDisposition.CANCELLATION_REQUESTED,
                service.cancel(TENANT, SESSION));
        assertEquals(BuildSessionStatus.CANCELLED, sessions.current().status());
        assertEquals(
                Optional.of("BUILD_SESSION_CANCELLED_BEFORE_EXTERNAL_EFFECT"),
                sessions.current().terminalCode());
        assertEquals(1, productLine.requestCalls.get());
        assertEquals(BuildSessionStatus.CANCELLING, productLine.requestedSession.get().status());
        assertEquals(0, workerRuns.activeLookups.get());
    }

    @Test
    void runningActionCancellationDenialPendingEffectKeepsSessionCancelling() {
        MemorySessions sessions = new MemorySessions(runningSession());
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        RecordingProductLineRequester productLine = new RecordingProductLineRequester(
                true, FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING);
        Worker worker = Worker.builder("cancel-pending").build();
        Engine engine = Engine.builder()
                .worker(worker)
                .checkpointStore(new MemoryCheckpointStore())
                .build();
        engine.attach();
        try {
            ReferenceAssemblyFlowFactory flowFactory = flowFactory();
            worker.submit(flowFactory.create(
                    sessions.current(), "product-cancel-flow-run", "product-cancel-trace"));
            FactoryFlowCancellationService service = new FactoryFlowCancellationService(
                    sessions,
                    engine,
                    new FactoryProductLineRegistry(List.of(flowFactory)),
                    workerCancellation(workerRuns),
                    CLOCK,
                    List.of(productLine));

            assertEquals(
                    FlowCancellationDisposition.CANCELLATION_REQUESTED,
                    service.cancel(TENANT, SESSION));
            assertEquals(BuildSessionStatus.CANCELLING, sessions.current().status());
            assertEquals(Optional.empty(), sessions.current().terminalCode());
            assertEquals(1, productLine.requestCalls.get());
            assertEquals(0, workerRuns.activeLookups.get());
        } finally {
            engine.stop();
        }
    }

    @Test
    void duplicateProductLineCancellationOwnersAreRejectedAtConstruction() {
        MemorySessions sessions = new MemorySessions(runningSession());
        RecordingWorkerRuns workerRuns = new RecordingWorkerRuns();
        var first = new RecordingProductLineRequester(
                true, FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT);
        var second = new RecordingProductLineRequester(
                true, FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT);

        assertThrows(
                IllegalArgumentException.class,
                () -> new FactoryFlowCancellationService(
                        sessions,
                        Engine.builder()
                                .worker(Worker.builder("cancel-duplicates").build())
                                .build(),
                        new FactoryProductLineRegistry(List.of(flowFactory())),
                        workerCancellation(workerRuns),
                        CLOCK,
                        List.of(first, second)));
    }

    private static FactoryFlowCancellationService service(
            MemorySessions sessions,
            Engine engine,
            RecordingWorkerRuns workerRuns,
            RecordingProductLineRequester productLine) {
        return new FactoryFlowCancellationService(
                sessions,
                engine,
                new FactoryProductLineRegistry(List.of(flowFactory())),
                workerCancellation(workerRuns),
                CLOCK,
                List.of(productLine));
    }

    private static ReferenceAssemblyFlowFactory flowFactory() {
        return new ReferenceAssemblyFlowFactory(
                (phase, buildSessionId, context) -> StepResult.stay());
    }

    @SuppressWarnings("removal")
    private static WorkerCancellationRequester workerCancellation(
            RecordingWorkerRuns workerRuns) {
        return new WorkerCancellationRequester(workerRuns, unusedRuntime());
    }

    private static CompletableActionRuntime unusedRuntime() {
        return new CompletableActionRuntime() {
            @Override
            public ActionExecutionResult handle(
                    ActionProposal proposal, ExecutionContext context) {
                throw new AssertionError("fallback Worker Action must not be called");
            }

            @Override
            public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
                throw new AssertionError("fallback Worker Action must not be called");
            }

            @Override
            public ActionExecutionResult complete(
                    String runId, String attemptToken, ActionExecutionResult result) {
                throw new AssertionError("fallback Worker Action must not be called");
            }

            @Override
            public ActionExecutionResult cancel(String runId, String reason) {
                throw new AssertionError("fallback Worker Action must not be called");
            }
        };
    }

    private static BuildSession runningSession() {
        return new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-product-cancel"),
                ProductLineId.REFERENCE_ASSEMBLY,
                "request-product-cancel",
                "product-cancel-principal",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.PACKAGE_RELEASE,
                new ArtifactReference("artifact:product-cancel-requirement"),
                new ContentHash("a".repeat(64)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                4,
                NOW.minusSeconds(60),
                NOW.minusSeconds(10));
    }

    private static final class RecordingProductLineRequester
            implements FactoryProductLineCancellationRequester {
        private final boolean canRequest;
        private final FactoryCancellationRequestDisposition disposition;
        private final AtomicInteger requestCalls = new AtomicInteger();
        private final AtomicReference<BuildSession> requestedSession = new AtomicReference<>();

        private RecordingProductLineRequester(
                boolean canRequest, FactoryCancellationRequestDisposition disposition) {
            this.canRequest = canRequest;
            this.disposition = disposition;
        }

        @Override
        public ProductLineId productLineId() {
            return ProductLineId.REFERENCE_ASSEMBLY;
        }

        @Override
        public boolean canRequest(BuildSession session) {
            return canRequest;
        }

        @Override
        public FactoryCancellationRequestDisposition request(BuildSession cancellingSession) {
            requestCalls.incrementAndGet();
            requestedSession.set(cancellingSession);
            return disposition;
        }
    }

    private static final class RecordingWorkerRuns implements WorkerRunRepository {
        private final AtomicInteger activeLookups = new AtomicInteger();

        @Override
        public void create(WorkerRunRecord workerRun) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            return Optional.empty();
        }

        @Override
        public Optional<WorkerRunRecord> findActiveByBuildSession(
                TenantId tenantId, BuildSessionId buildSessionId) {
            activeLookups.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            return false;
        }
    }

    private static final class MemorySessions implements BuildSessionRepository {
        private final AtomicReference<BuildSession> current;

        private MemorySessions(BuildSession initial) {
            current = new AtomicReference<>(initial);
        }

        private BuildSession current() {
            return current.get();
        }

        @Override
        public void create(BuildSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BuildSession> find(
                TenantId tenantId, BuildSessionId buildSessionId) {
            return Optional.of(current.get()).filter(session ->
                    session.tenantId().equals(tenantId)
                            && session.buildSessionId().equals(buildSessionId));
        }

        @Override
        public List<BuildSession> findCancelling(Instant updatedBefore, int limit) {
            BuildSession value = current.get();
            return value.status() == BuildSessionStatus.CANCELLING
                            && !value.updatedAt().isAfter(updatedBefore)
                            && limit > 0
                    ? List.of(value)
                    : List.of();
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            return current.compareAndSet(expected, next);
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
}
