package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.flowerjvm.factory.application.certification.CertificationAttemptTokens;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchTransaction;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CertificationIssueActionExecutorTest {
    @Test
    void executorOnlyPreparesDurableIntentAndReturnsAwaitingDescriptor() {
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        Instant deadline = now.plusSeconds(600);
        TenantId tenant = new TenantId("tenant-a");
        CertificationIssueInput input = new CertificationIssueInput(
                new CertificationId("certification-001"),
                new ContentHash("a".repeat(64)),
                0);
        AtomicReference<String> observed = new AtomicReference<>();
        CertificationDispatchTransaction transaction = new CertificationDispatchTransaction() {
            @Override
            public CertificationDispatchIntent prepare(
                    TenantId trustedTenant,
                    CertificationIssueInput trustedInput,
                    String actionRunId,
                    String attemptTokenHash,
                    Instant preparedAt) {
                assertEquals(tenant, trustedTenant);
                assertEquals(input, trustedInput);
                assertEquals("action-run-001", actionRunId);
                assertEquals(CertificationAttemptTokens.hash("attempt-token"), attemptTokenHash);
                assertEquals(now, preparedAt);
                observed.set("prepared");
                return CertificationDispatchIntent.pending(
                        CertificationDispatchOperationIds.derive(tenant, input),
                        tenant,
                        input.certificationId(),
                        input.inputLockManifestHash(),
                        input.expectedCertificationVersion(),
                        actionRunId,
                        attemptTokenHash,
                        deadline,
                        now);
            }

            @Override
            public Optional<CertificationDispatchIntent> findExact(
                    TenantId tenantId,
                    CertificationIssueInput exactInput,
                    String actionRunId,
                    String attemptTokenHash) {
                return Optional.empty();
            }
        };
        CertificationIssueActionExecutor executor = new CertificationIssueActionExecutor(
                transaction, Clock.fixed(now, ZoneOffset.UTC));
        ActionProposal proposal = ActionProposal.builder(CertificationIssueAction.ACTION_ID)
                .proposalId("proposal-001")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-service")
                .input(input.toMap())
                .idempotencyKey("key-001")
                .build();
        ExecutionContext execution = new ExecutionContext(
                tenant.value(), "factory-service", "action-run-001", "trace-001", Map.of());

        ActionDispatch.Awaiting awaiting = executor.dispatchDeferred(new ActionExecutionContext(
                execution,
                proposal,
                CertificationIssueAction.definition(),
                input.toMap(),
                "attempt-token"));

        assertEquals("prepared", observed.get());
        assertEquals(CertificationDispatchOperationIds.derive(tenant, input), awaiting.operationId());
        assertEquals(deadline, awaiting.dueAt());
        assertEquals("durable-certification-intent", awaiting.metadata().get("dispatchMode"));
        assertEquals(input.certificationId().value(),
                awaiting.metadata().get(CertificationIssueAction.CERTIFICATION_ID));
    }
}
