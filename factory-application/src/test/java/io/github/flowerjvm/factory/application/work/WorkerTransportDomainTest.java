package io.github.flowerjvm.factory.application.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.outbox.DispatchClaimPurpose;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.WorkerProtocol;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerTransportDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-1");

    @Test
    void attemptProofMatchesCrossLanguageGoldenVector() {
        assertEquals(
                "8e095c1c0627141eae7f9cab4feb7bfd5a4e7036b1f24bcc1eed984d10646fb6",
                WorkerAttemptProofs.create(
                        "attempt-token", "event-1", "operation-1", new WorkerRunId("run-1")));
    }

    @Test
    void operationIdentityPartitionsTheGlobalWorkerJournalByTenantAndWorkOrder() {
        WorkerRunId sharedRunId = new WorkerRunId("shared-run");
        String tenantA = WorkerDispatchOperationIds.derive(
                new TenantId("tenant-a"), new WorkOrderId("order-a"),
                sharedRunId, 1, "codex-worker");
        String tenantB = WorkerDispatchOperationIds.derive(
                new TenantId("tenant-b"), new WorkOrderId("order-a"),
                sharedRunId, 1, "codex-worker");
        String orderB = WorkerDispatchOperationIds.derive(
                new TenantId("tenant-a"), new WorkOrderId("order-b"),
                sharedRunId, 1, "codex-worker");

        assertTrue(!tenantA.equals(tenantB));
        assertTrue(!tenantA.equals(orderB));
    }

    @Test
    void expiredDispatchLeaseCanOnlyReconcileBeforeAuthoritativeNoEffectRetry() {
        DispatchOutbox pending = pending(WorkerOutboxOperations.DISPATCH, "dispatch-1");
        DispatchOutbox submitting = pending.claimForSubmission("owner-a", NOW, Duration.ofSeconds(10));

        assertEquals(DispatchClaimPurpose.SUBMIT, submitting.claimPurpose().orElseThrow());
        assertThrows(
                IllegalStateException.class,
                () -> submitting.claimForSubmission("owner-b", NOW.plusSeconds(11), Duration.ofSeconds(10)));

        DispatchOutbox reconciling = submitting.claimForReconciliation(
                "owner-b", NOW.plusSeconds(10), Duration.ofSeconds(10));
        assertEquals(DispatchClaimPurpose.RECONCILE, reconciling.claimPurpose().orElseThrow());
        assertEquals(1, reconciling.attemptCount());

        DispatchOutbox retry = reconciling.retryAfterProvenNoEffect(
                "owner-b",
                "WORKER_OPERATION_AUTHORITATIVELY_ABSENT",
                NOW.plusSeconds(11),
                NOW.plusSeconds(20));
        assertEquals(DispatchOutboxStatus.RETRY_WAIT, retry.status());
        DispatchOutbox secondSubmission = retry.claimForSubmission(
                "owner-c", NOW.plusSeconds(20), Duration.ofSeconds(10));
        assertEquals(2, secondSubmission.attemptCount());
    }

    @Test
    void dispatchAndCancelHaveDifferentLegalTerminalStates() {
        DispatchOutbox dispatch = pending(WorkerOutboxOperations.DISPATCH, "dispatch-1")
                .claimForSubmission("owner", NOW, Duration.ofSeconds(10));
        DispatchOutbox cancel = pending(WorkerOutboxOperations.CANCEL, "cancel-1")
                .claimForSubmission("owner", NOW, Duration.ofSeconds(10));

        assertEquals(
                DispatchOutboxStatus.DISPATCHED,
                dispatch.dispatched("owner", "WORKER_DISPATCH_ACCEPTED", NOW.plusSeconds(1)).status());
        assertThrows(
                IllegalStateException.class,
                () -> dispatch.confirmed("owner", "WORKER_CANCEL_CONFIRMED", NOW.plusSeconds(1)));
        assertEquals(
                DispatchOutboxStatus.CONFIRMED,
                cancel.confirmed("owner", "WORKER_CANCEL_CONFIRMED", NOW.plusSeconds(1)).status());
    }

    @Test
    void unknownProviderFailureCannotChooseAutomaticRetry() {
        var payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-1",
                new WorkOrderId("order-1"),
                new WorkerRunId("run-1"),
                "operation-1",
                "a".repeat(64),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_SAYS_RETRY_NOW");

        WorkerCompletionClassification classification =
                new ConservativeWorkerCompletionClassifier().classify(payload);

        assertEquals(WorkerRunStatus.FAILED, classification.terminalStatus());
        assertEquals(WorkerRetryDisposition.MANUAL_REVIEW, classification.retryDisposition());
        assertEquals("WORKER_FAILURE_REQUIRES_REVIEW", classification.code());
    }

    @Test
    void callbackInboxUsesOwnerAwareClaimsAndKeepsOnlyAttemptHash() {
        WorkerCallbackInboxEntry received = WorkerCallbackInboxEntry.received(
                "callback-1",
                TENANT,
                "codex-worker",
                "event-1",
                new WorkOrderId("order-1"),
                new WorkerRunId("run-1"),
                "operation-1",
                new ContentHash("b".repeat(64)),
                new ArtifactReference("artifact:callback-body"),
                new ContentHash("c".repeat(64)),
                NOW);
        WorkerCallbackInboxEntry claimed = received.claim("owner-a", NOW, Duration.ofSeconds(10));

        assertThrows(
                IllegalStateException.class,
                () -> claimed.applied("owner-b", "WORKER_CALLBACK_APPLIED", NOW.plusSeconds(1)));
        assertEquals(
                WorkerCallbackInboxStatus.APPLIED,
                claimed.applied("owner-a", "WORKER_CALLBACK_APPLIED", NOW.plusSeconds(1)).status());
        assertTrue(claimed.toString().contains("attemptTokenHash"));
    }

    private static DispatchOutbox pending(String operationType, String id) {
        return new DispatchOutbox(
                new DispatchOutboxId(id),
                TENANT,
                operationType,
                "WORKER_RUN",
                "run-1",
                "operation-1",
                new ArtifactReference("artifact:payload"),
                DispatchOutboxStatus.PENDING,
                NOW,
                0,
                Optional.empty(),
                0,
                NOW,
                NOW);
    }
}
