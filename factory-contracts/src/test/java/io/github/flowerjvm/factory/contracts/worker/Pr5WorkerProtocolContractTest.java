package io.github.flowerjvm.factory.contracts.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Pr5WorkerProtocolContractTest {
    private static final ContentHash HASH = new ContentHash("a".repeat(64));
    private static final ArtifactReference REF = new ArtifactReference("artifact:pr5");

    @Test
    void attemptCredentialNeverAppearsInDiagnosticText() {
        WorkerAttemptToken token = new WorkerAttemptToken("top-secret-attempt-token");

        assertEquals("top-secret-attempt-token", token.reveal());
        assertFalse(token.toString().contains("top-secret-attempt-token"));
        assertTrue(token.toString().contains("REDACTED"));
    }

    @Test
    void completionPayloadIsTokenlessAndStrictlyBindsSuccessfulResult() {
        var result = resultManifest(Optional.empty());
        var payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-1",
                new WorkOrderId("order-1"),
                new WorkerRunId("run-1"),
                "operation-1",
                "b".repeat(64),
                WorkerRunStatus.SUCCEEDED,
                Optional.of(result),
                "WORKER_OK");

        assertEquals(result, payload.result().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingWorkerCompletionPayload(
                        WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                        "event-2",
                        new WorkOrderId("order-1"),
                        new WorkerRunId("run-1"),
                        "operation-1",
                        "b".repeat(64),
                        WorkerRunStatus.FAILED,
                        Optional.of(result),
                        "WORKER_FAILED"));
    }

    @Test
    void candidatePrimaryResultMustBeTheSourceManifestAndAggregateHash() {
        CandidateOutputLock candidate = new CandidateOutputLock(
                new CandidateId("candidate-1"),
                Optional.empty(),
                new ArtifactReference("artifact:source-manifest"),
                new ContentHash("1".repeat(64)),
                new ContentHash("2".repeat(64)),
                new ArtifactReference("artifact:dependencies"),
                new ContentHash("3".repeat(64)),
                new ArtifactReference("artifact:toolchain"),
                new ContentHash("4".repeat(64)));

        assertThrows(
                IllegalArgumentException.class,
                () -> resultManifest(Optional.of(candidate)));
    }

    @Test
    void repairPathsRejectTraversalDevicesPercentAndCaseCollisions() {
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/./Main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/../Main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src//Main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/%2e%2e/Main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/CON.txt")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/aux")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("C:/src/Main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/name /Main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/name\u0000/Main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/Main.java", "SRC/main.java")));
        assertThrows(IllegalArgumentException.class, () -> repair(List.of("src/trailing./Main.java")));
    }

    @Test
    void unknownStatusCannotMasqueradeAsAWorkerFailure() {
        WorkerStatusObservation unknown = WorkerStatusObservation.unknown("WORKER_STATUS_UNKNOWN");

        assertEquals(WorkerLookupState.UNKNOWN, unknown.lookupState());
        assertTrue(unknown.snapshot().isEmpty());
        assertTrue(unknown.terminalPayload().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerStatusObservation(
                        WorkerLookupState.UNKNOWN,
                        Optional.of(new WorkerRunSnapshot(
                                new TenantId("tenant-1"),
                                new WorkOrderId("order-1"),
                                new WorkerRunId("run-1"),
                                "operation-1",
                                WorkerRunStatus.FAILED,
                                Optional.empty(),
                                Optional.of("WORKER_FAILED"))),
                        Optional.empty(),
                        Optional.of("WORKER_STATUS_UNKNOWN")));
    }

    private static CodingWorkerResultManifest resultManifest(Optional<CandidateOutputLock> candidate) {
        return new CodingWorkerResultManifest(
                WorkerProtocol.RESULT_SCHEMA_VERSION,
                new WorkOrderId("order-1"),
                new WorkerRunId("run-1"),
                "operation-1",
                "factory.output.v1",
                "1",
                REF,
                HASH,
                Optional.empty(),
                Optional.empty(),
                candidate,
                Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static CodingWorkerRepairLock repair(List<String> paths) {
        return new CodingWorkerRepairLock(
                new CandidateId("candidate-base"),
                HASH,
                REF,
                HASH,
                paths,
                1,
                2);
    }
}
