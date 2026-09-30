package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecisionType;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcDuplicateActionPolicy;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcActionDuplicateAcceptanceTest {
    @Test
    void firstTerminalResultSurvivesRestartAndResourceScopesAreIndependent() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("duplicate_first_result");
        FactoryDatabaseMigrations.migrate(dataSource);
        var policy = policy(dataSource);
        var proposal = proposal("proposal-a", "shared-key");
        var owner = context("run-a", "resource-a");
        var first = ActionExecutionResult.succeeded(Map.of("result", "first"));

        assertEquals(DuplicateActionDecisionType.ACCEPT, policy.reserve(proposal, owner).type());
        assertEquals(DuplicateActionDecisionType.REJECT, policy.reserve(proposal, owner).type());
        policy.complete(proposal, owner, first);
        policy.complete(proposal, owner, ActionExecutionResult.succeeded(Map.of("result", "later")));

        assertEquals(
                "run-a",
                new JdbcWorkerActionDuplicateOwnerLookup(dataSource)
                        .findOwnerRunId("tenant-a", "factory.worker.dispatch", "shared-key")
                        .orElseThrow());

        var restarted = policy(dataSource);
        var existing = restarted.reserve(
                proposal("proposal-a-retry", "shared-key"),
                context("run-a-retry", "resource-a"));
        assertEquals(DuplicateActionDecisionType.RETURN_EXISTING, existing.type());
        assertEquals(first, existing.existingResult());
        assertEquals(
                DuplicateActionDecisionType.ACCEPT,
                restarted.reserve(
                                proposal("proposal-b", "shared-key"),
                                context("run-b", "resource-b"))
                        .type());
    }

    @Test
    void staleOwnerCannotWinAfterReleaseAndRereserveAbaSequence() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("duplicate_aba");
        FactoryDatabaseMigrations.migrate(dataSource);
        var policy = policy(dataSource);
        var proposal = proposal("proposal-a", "aba-key");
        var ownerA = context("run-a", "resource-a");
        var ownerB = context("run-b", "resource-a");
        var winner = ActionExecutionResult.succeeded(Map.of("owner", "b"));

        assertEquals(DuplicateActionDecisionType.ACCEPT, policy.reserve(proposal, ownerA).type());
        policy.release(proposal, ownerA, new IllegalStateException("known pre-dispatch failure"));
        assertEquals(DuplicateActionDecisionType.ACCEPT, policy.reserve(proposal, ownerB).type());
        policy.complete(proposal, ownerA, ActionExecutionResult.succeeded(Map.of("owner", "stale-a")));
        policy.complete(proposal, ownerB, winner);

        var existing = policy(dataSource).reserve(
                proposal("proposal-c", "aba-key"),
                context("run-c", "resource-a"));
        assertEquals(DuplicateActionDecisionType.RETURN_EXISTING, existing.type());
        assertEquals(winner, existing.existingResult());
    }

    @Test
    void runningReservationHasNoTtlTakeoverAndRetryNeedsANewLogicalAttemptKey() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("duplicate_retry");
        FactoryDatabaseMigrations.migrate(dataSource);
        var firstProcess = policy(dataSource);
        assertEquals(
                DuplicateActionDecisionType.ACCEPT,
                firstProcess.reserve(
                                proposal("proposal-running", "running-key"),
                                context("run-running", "resource-a"))
                        .type());
        assertEquals(
                DuplicateActionDecisionType.REJECT,
                policy(dataSource).reserve(
                                proposal("proposal-takeover", "running-key"),
                                context("run-takeover", "resource-a"))
                        .type());

        var retryProposal = proposal("proposal-retry", "attempt-key-1");
        var retryOwner = context("run-retry", "resource-a");
        var retryable = ActionExecutionResult.retryableFailure(
                "WORKER_QUEUE_TEMPORARY_FAILURE", "queue unavailable");
        assertEquals(DuplicateActionDecisionType.ACCEPT, firstProcess.reserve(retryProposal, retryOwner).type());
        firstProcess.complete(retryProposal, retryOwner, retryable);
        assertEquals(
                retryable,
                policy(dataSource).reserve(
                                proposal("proposal-retry-redelivery", "attempt-key-1"),
                                context("run-retry-redelivery", "resource-a"))
                        .existingResult());
        assertEquals(
                DuplicateActionDecisionType.ACCEPT,
                policy(dataSource).reserve(
                                proposal("proposal-new-attempt", "attempt-key-2"),
                                context("run-new-attempt", "resource-a"))
                        .type());
    }

    static JdbcDuplicateActionPolicy policy(JdbcDataSource dataSource) {
        return JdbcDuplicateActionPolicy.create(
                dataSource,
                (proposal, context) -> (String) context.metadata().get("resource.id"));
    }

    static ActionProposal proposal(String proposalId, String idempotencyKey) {
        return ActionProposal.builder("factory.worker.dispatch")
                .proposalId(proposalId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-builder")
                .idempotencyKey(idempotencyKey)
                .build();
    }

    static ExecutionContext context(String runId, String resourceId) {
        return new ExecutionContext(
                "tenant-a",
                "factory-builder",
                runId,
                "trace-" + runId,
                Map.of("resource.id", resourceId));
    }
}
