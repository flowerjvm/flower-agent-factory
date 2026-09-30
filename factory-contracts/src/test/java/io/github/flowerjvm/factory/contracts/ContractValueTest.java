package io.github.flowerjvm.factory.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContractValueTest {
    @Test
    void identifiersRejectBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> new TenantId(" "));
        assertThrows(IllegalArgumentException.class, () -> new BuildSessionId(""));
        assertThrows(IllegalArgumentException.class, () -> new WorkOrderId(null));
        assertThrows(IllegalArgumentException.class, () -> new CandidateId(" "));
        assertThrows(IllegalArgumentException.class, () -> new DecisionId(""));
        assertThrows(IllegalArgumentException.class, () -> new DispatchOutboxId(null));
        assertThrows(IllegalArgumentException.class, () -> new VerificationRunId(" "));
        assertEquals("agent-pack", ProductLineId.AGENT_PACK.value());
        assertThrows(IllegalArgumentException.class, () -> new ProductLineId("Agent-Pack"));
        assertThrows(IllegalArgumentException.class, () -> new ProductLineId("agent_pack"));
        assertThrows(IllegalArgumentException.class, () -> new ProductLineId("a".repeat(129)));
    }

    @Test
    void contentHashNormalizesHexCaseAndRejectsWrongLength() {
        var uppercase = "A".repeat(64);
        assertEquals("a".repeat(64), new ContentHash(uppercase).sha256());
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("abc"));
    }

    @Test
    void artifactDefensivelyCopiesContent() {
        byte[] source = {1, 2, 3};
        var artifact = new Artifact(
                new TenantId("tenant-a"),
                new ArtifactReference("artifact:requirements"),
                new ContentHash("0".repeat(64)),
                "text/markdown",
                source);

        source[0] = 9;
        byte[] firstRead = artifact.content();
        firstRead[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, artifact.content());
    }

    @Test
    void workOrderDefensivelyCopiesCollectionsAndLocksItsInputManifest() {
        var readPaths = new ArrayList<>(List.of("inputs/requirements.md"));
        var writePaths = new ArrayList<>(List.of("candidate"));
        var capabilities = new HashSet<>(Set.of(new WorkerCapability("repository-read")));
        var order = order(readPaths, writePaths, capabilities);

        readPaths.clear();
        writePaths.clear();
        capabilities.clear();

        assertEquals(1, order.allowedReadPaths().size());
        assertEquals(1, order.allowedWritePaths().size());
        assertEquals(1, order.requiredCapabilities().size());
        assertThrows(UnsupportedOperationException.class, () -> order.allowedReadPaths().clear());
        assertEquals("artifact:input-manifest", order.inputArtifactManifestRef().value());
        assertEquals("2".repeat(64), order.inputManifestHash().sha256());
    }

    @Test
    void workOrderKeepsAttemptOperationIdentityOnWorkerRun() {
        var componentNames = Arrays.stream(WorkOrder.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertFalse(componentNames.contains("operationId"));
        assertTrue(componentNames.contains("logicalIdempotencyKey"));
    }

    @Test
    void workerDispatchUsesFactoryOwnedRunAndOperationIdentity() throws NoSuchMethodException {
        var workOrder = order(
                List.of("inputs/requirements.md"),
                List.of("candidate"),
                Set.of(new WorkerCapability("repository-read")));
        var workerRunId = new WorkerRunId("worker-run-1");
        var request = new WorkerDispatchRequest(
                workOrder,
                workerRunId,
                "operation:worker-run-1:attempt:1",
                new WorkerAttemptToken("test-attempt-token"));

        assertEquals(workerRunId, request.workerRunId());
        assertEquals("operation:worker-run-1:attempt:1", request.operationId());
        assertEquals(workOrder, request.workOrder());
        assertEquals(
                WorkerDispatchRequest.class,
                CodingWorker.class.getMethod("submit", WorkerDispatchRequest.class).getParameterTypes()[0]);
        assertThrows(NoSuchMethodException.class, () -> CodingWorker.class.getMethod("submit", WorkOrder.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerDispatchRequest(
                        workOrder, workerRunId, " ", new WorkerAttemptToken("test-attempt-token")));
    }

    @Test
    void contractsDoNotDependOnApplicationModule() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.github.flowerjvm.factory.application.FactoryApplicationModule"));
    }

    private static WorkOrder order(
            List<String> readPaths,
            List<String> writePaths,
            Set<WorkerCapability> capabilities) {
        return new WorkOrder(
                new WorkOrderId("work-1"),
                new TenantId("tenant-a"),
                new BuildSessionId("session-1"),
                "generate-candidate",
                "Generate a locked candidate",
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new ArtifactReference("artifact:instruction"),
                new ContentHash("1".repeat(64)),
                new ArtifactReference("artifact:input-manifest"),
                new ContentHash("2".repeat(64)),
                "workspace:session-1",
                readPaths,
                writePaths,
                capabilities,
                "factory.pack-candidate",
                "1.0.0",
                new ArtifactReference("artifact:policy-snapshot"),
                Instant.parse("2026-08-11T00:00:00Z"),
                3,
                "work-order:session-1:generate:1",
                WorkOrderCreatorType.SYSTEM,
                "factory-builder",
                Instant.parse("2026-08-10T00:00:00Z"));
    }
}
