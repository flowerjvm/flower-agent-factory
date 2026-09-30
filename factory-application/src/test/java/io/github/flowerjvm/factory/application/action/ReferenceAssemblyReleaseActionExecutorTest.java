package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchOperationIds;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchTransaction;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Executor boundary only: preparing a synthetic intent is not release or approval evidence. */
class ReferenceAssemblyReleaseActionExecutorTest {
    private static final TenantId TENANT = new TenantId("release-executor-tenant");
    private static final Instant DEADLINE = Instant.parse("2026-09-07T03:00:00Z");
    private static final ReferenceAssemblyReleaseInput INPUT = new ReferenceAssemblyReleaseInput(
            new ReferenceAssemblyId("reference-assembly-test"), new ContentHash("a".repeat(64)),
            new ContentHash("b".repeat(64)), new DecisionPointId("release-review-test"),
            new ContentHash("c".repeat(64)), 4);

    @Test
    void nanosecondClockUsesOneExactlyTruncatedMicrosecondObservationAndPreservesEveryReleaseLock() {
        for (Instant observed : List.of(Instant.parse("2026-09-07T02:00:00.123456789Z"),
                Instant.parse("2026-09-07T02:00:00.999999999Z"))) {
            assertPreparedAt(observed, observed.truncatedTo(ChronoUnit.MICROS));
        }
    }

    @Test
    void alreadyCanonicalMicrosecondClockIsPassedThroughWithoutChangingTheDeadline() {
        Instant canonical = Instant.parse("2026-09-07T02:00:00.123456Z");
        assertPreparedAt(canonical, canonical);
    }

    @Test
    void fractionalBusinessDeadlineStaysOnIntentWhileAwaitingUsesExactMillisecondTransport() {
        assertPreparedAt(Instant.parse("2026-09-07T02:00:00.123456789Z"),
                Instant.parse("2026-09-07T02:00:00.123456Z"),
                Instant.parse("2026-09-07T03:00:00.123456Z"),
                Instant.parse("2026-09-07T03:00:00.123Z"));
    }

    @Test
    void transactionRejectionPropagatesWithoutAlternateLookupRetryOrAwaitingSuccess() {
        var calls = new AtomicInteger();
        var rejected = new IllegalStateException("synthetic exact-state rejection");
        var transaction = new ReferenceAssemblyReleaseDispatchTransaction() {
            public ReferenceAssemblyReleaseDispatchIntent prepare(TenantId tenant, ReferenceAssemblyReleaseInput input,
                    String runId, String tokenHash, Instant preparedAt) {
                calls.incrementAndGet();
                assertEquals(Instant.parse("2026-09-07T02:00:00.123456Z"), preparedAt);
                throw rejected;
            }
            public Optional<ReferenceAssemblyReleaseDispatchIntent> findExact(TenantId tenant,
                    ReferenceAssemblyReleaseInput input, String runId, String tokenHash) {
                throw new AssertionError("executor must not recover through an alternate lookup");
            }
        };
        var executor = new ReferenceAssemblyReleaseActionExecutor(transaction,
                Clock.fixed(Instant.parse("2026-09-07T02:00:00.123456789Z"), ZoneOffset.UTC));
        assertSame(rejected, assertThrows(IllegalStateException.class, () -> executor.dispatchDeferred(context())));
        assertEquals(1, calls.get());
    }

    private static void assertPreparedAt(Instant observed, Instant expected) {
        assertPreparedAt(observed, expected, DEADLINE, DEADLINE);
    }

    private static void assertPreparedAt(
            Instant observed, Instant expected, Instant businessDeadline, Instant actionDeadline) {
        var preparations = new AtomicInteger();
        var clockReads = new AtomicInteger();
        var transaction = new ReferenceAssemblyReleaseDispatchTransaction() {
            public ReferenceAssemblyReleaseDispatchIntent prepare(TenantId tenant, ReferenceAssemblyReleaseInput input,
                    String runId, String tokenHash, Instant preparedAt) {
                preparations.incrementAndGet();
                assertEquals(TENANT, tenant);
                assertEquals(INPUT, input);
                assertEquals("release-action-test", runId);
                assertEquals(ReferenceAssemblyReleaseAttemptTokens.hash("release-attempt-test"), tokenHash);
                assertEquals(expected, preparedAt);
                assertEquals(0, preparedAt.getNano() % 1_000);
                // The real intent still enforces canonical precision; this fixture would reject nanos.
                return ReferenceAssemblyReleaseDispatchIntent.pending(
                        ReferenceAssemblyReleaseDispatchOperationIds.derive(tenant, input),
                        tenant, input, runId, tokenHash, businessDeadline, preparedAt);
            }
            public Optional<ReferenceAssemblyReleaseDispatchIntent> findExact(TenantId tenant,
                    ReferenceAssemblyReleaseInput input, String runId, String tokenHash) {
                throw new AssertionError("executor must only prepare through the transaction boundary");
            }
        };
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(observed, zone); }
            public Instant instant() { clockReads.incrementAndGet(); return observed; }
        };
        var executor = new ReferenceAssemblyReleaseActionExecutor(transaction, clock);
        var awaiting = executor.dispatchDeferred(context());
        assertEquals(ReferenceAssemblyReleaseDispatchOperationIds.derive(TENANT, INPUT), awaiting.operationId());
        assertEquals(actionDeadline, awaiting.dueAt());
        assertEquals(Map.of("dispatchMode", ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID, INPUT.referenceAssemblyId().value()), awaiting.metadata());
        assertEquals(1, preparations.get());
        assertEquals(1, clockReads.get());
    }

    private static ActionExecutionContext context() {
        var proposal = ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("release-proposal-test").requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE).requesterId("factory-builder")
                .input(INPUT.toMap()).idempotencyKey("release-key-test").build();
        return new ActionExecutionContext(
                new ExecutionContext(TENANT.value(), "factory-builder", "release-action-test", "release-trace-test", Map.of()),
                proposal, ReferenceAssemblyReleaseAction.definition(), INPUT.toMap(), "release-attempt-test");
    }
}
