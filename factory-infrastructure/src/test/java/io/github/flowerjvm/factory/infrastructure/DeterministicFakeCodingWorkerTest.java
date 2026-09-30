package io.github.flowerjvm.factory.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.FactoryApplicationModule;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicFakeCodingWorkerTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final WorkerCapability REPOSITORY_READ = new WorkerCapability("repository-read");

    @Test
    void duplicateSubmissionReturnsSameRunAndDispatchesOnce() {
        var worker = new DeterministicFakeCodingWorker(new WorkerCapabilities(Set.of(REPOSITORY_READ)));
        var order = order(REPOSITORY_READ);

        var request = request(order);
        var first = worker.submit(request);
        var duplicate = worker.submit(request);

        assertEquals(first.workerRunId(), duplicate.workerRunId());
        assertEquals(1, worker.dispatchCount());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, worker.status(TENANT, first.workerRunId()).status());
    }

    @Test
    void completionIsObservedThroughStatusAndTenantScope() {
        var worker = new DeterministicFakeCodingWorker(new WorkerCapabilities(Set.of(REPOSITORY_READ)));
        var submission = worker.submit(request(order(REPOSITORY_READ)));
        worker.complete(TENANT, submission.workerRunId(), new ArtifactReference("artifact:result"));

        var snapshot = worker.status(TENANT, submission.workerRunId());
        assertEquals(WorkerRunStatus.SUCCEEDED, snapshot.status());
        assertEquals("artifact:result", snapshot.resultArtifact().orElseThrow().value());
        assertThrows(
                NoSuchElementException.class,
                () -> worker.status(new TenantId("tenant-b"), submission.workerRunId()));
    }

    @Test
    void terminalResultSurvivesDuplicateSubmitCompletionAndCancel() {
        var worker = new DeterministicFakeCodingWorker(new WorkerCapabilities(Set.of(REPOSITORY_READ)));
        var order = order(REPOSITORY_READ);
        var request = request(order);
        var submission = worker.submit(request);
        worker.complete(TENANT, submission.workerRunId(), new ArtifactReference("artifact:original"));

        var duplicate = worker.submit(request);
        worker.complete(TENANT, submission.workerRunId(), new ArtifactReference("artifact:replacement"));
        var cancel = worker.cancel(TENANT, submission.workerRunId(), "late cancellation");
        var terminal = worker.status(TENANT, submission.workerRunId());

        assertEquals(submission.workerRunId(), duplicate.workerRunId());
        assertEquals(WorkerRunStatus.SUCCEEDED, duplicate.status());
        assertEquals(WorkerRunStatus.SUCCEEDED, cancel.status());
        assertEquals("WORKER_ALREADY_TERMINAL", cancel.stableCode());
        assertEquals("artifact:original", terminal.resultArtifact().orElseThrow().value());
        assertEquals(1, worker.dispatchCount());
    }

    @Test
    void capabilityMismatchIsRejectedBeforeDispatch() {
        var worker = new DeterministicFakeCodingWorker(new WorkerCapabilities(Set.of()));

        assertThrows(IllegalArgumentException.class, () -> worker.submit(request(order(REPOSITORY_READ))));
        assertEquals(0, worker.dispatchCount());
    }

    @Test
    void infrastructureSeesInnerModulesButNotHost() throws Exception {
        assertEquals(FactoryApplicationModule.class, Class.forName(FactoryApplicationModule.class.getName()));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.github.flowerjvm.factory.host.FactoryBuilderHostModule"));
    }

    private static WorkOrder order(WorkerCapability capability) {
        return new WorkOrder(
                new WorkOrderId("work-1"),
                TENANT,
                new BuildSessionId("session-1"),
                "generate-candidate",
                "generate test candidate",
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.of("base-1"),
                new ArtifactReference("artifact:instruction"),
                new ContentHash("a".repeat(64)),
                new ArtifactReference("artifact:input-manifest"),
                new ContentHash("b".repeat(64)),
                "workspace:test",
                List.of("/workspace/input"),
                List.of("/workspace/output"),
                Set.of(capability),
                "pack-candidate",
                "1",
                new ArtifactReference("artifact:policy"),
                Instant.parse("2026-08-11T00:00:00Z"),
                1,
                "idempotency-1",
                WorkOrderCreatorType.SYSTEM,
                "factory-test",
                Instant.parse("2026-08-10T00:00:00Z"));
    }

    private static WorkerDispatchRequest request(WorkOrder order) {
        return new WorkerDispatchRequest(
                order,
                new WorkerRunId("worker-run-1"),
                "worker-dispatch-operation-1",
                new WorkerAttemptToken("test-attempt-token"));
    }
}
