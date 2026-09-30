package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyReleaseDispatchIntentTest {
    private static final TenantId TENANT = new TenantId("tenant-reference-release");
    private static final Instant NOW = Instant.parse("2026-09-02T10:11:12.123456Z");
    private static final Instant DEADLINE = NOW.plusSeconds(300);
    private static final String ACTION_RUN_ID = "reference-release-action-run";
    private static final String ATTEMPT_HASH =
            ReferenceAssemblyReleaseAttemptTokens.hash("release-attempt-token");
    private static final ReferenceAssemblyReleaseInput INPUT = input(
            "assembly-a", "assembly-manifest", "inspection-report", "decision-a", "subject-a", 7);
    private static final String OPERATION_ID =
            ReferenceAssemblyReleaseDispatchOperationIds.derive(TENANT, INPUT);

    @Test
    void operationIdIsDeterministicTenantScopedAndLocksEveryReleaseInputField() {
        assertEquals(
                OPERATION_ID,
                ReferenceAssemblyReleaseDispatchOperationIds.derive(TENANT, INPUT));
        assertTrue(OPERATION_ID.matches("reference-assembly-release:[0-9a-f]{64}"));

        List<String> changed = List.of(
                ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        new TenantId("tenant-reference-release-2"), INPUT),
                ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        TENANT,
                        input("assembly-b", "assembly-manifest", "inspection-report",
                                "decision-a", "subject-a", 7)),
                ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        TENANT,
                        input("assembly-a", "assembly-manifest-2", "inspection-report",
                                "decision-a", "subject-a", 7)),
                ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        TENANT,
                        input("assembly-a", "assembly-manifest", "inspection-report-2",
                                "decision-a", "subject-a", 7)),
                ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        TENANT,
                        input("assembly-a", "assembly-manifest", "inspection-report",
                                "decision-b", "subject-a", 7)),
                ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        TENANT,
                        input("assembly-a", "assembly-manifest", "inspection-report",
                                "decision-a", "subject-b", 7)),
                ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        TENANT,
                        input("assembly-a", "assembly-manifest", "inspection-report",
                                "decision-a", "subject-a", 8)));

        changed.forEach(value -> assertNotEquals(OPERATION_ID, value));
    }

    @Test
    void attemptTokenHashIsExactBoundedAndOneWay() {
        assertEquals(64, ATTEMPT_HASH.length());
        assertEquals(
                ATTEMPT_HASH,
                ReferenceAssemblyReleaseAttemptTokens.hash("release-attempt-token"));
        assertNotEquals(
                ATTEMPT_HASH,
                ReferenceAssemblyReleaseAttemptTokens.hash("release-attempt-token-2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseAttemptTokens.hash(" release-attempt-token "));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseAttemptTokens.hash("release\nattempt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseAttemptTokens.hash("x".repeat(513)));
    }

    @Test
    void pendingSnapshotHasPristineLifecycleAndEveryImmutableReleaseLock() {
        ReferenceAssemblyReleaseDispatchIntent pending = pending();

        assertEquals(OPERATION_ID, pending.operationId());
        assertEquals(TENANT, pending.tenantId());
        assertEquals(INPUT.referenceAssemblyId(), pending.referenceAssemblyId());
        assertEquals(INPUT.assemblyManifestHash(), pending.assemblyManifestHash());
        assertEquals(INPUT.inspectionReportHash(), pending.inspectionReportHash());
        assertEquals(INPUT.releaseDecisionPointId(), pending.releaseDecisionPointId());
        assertEquals(INPUT.releaseSubjectHash(), pending.releaseSubjectHash());
        assertEquals(7, pending.expectedReferenceAssemblyVersion());
        assertEquals(ACTION_RUN_ID, pending.actionRunId());
        assertEquals(ATTEMPT_HASH, pending.attemptTokenHash());
        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.PENDING, pending.status());
        assertEquals(Optional.empty(), pending.claimToken());
        assertEquals(Optional.empty(), pending.leaseUntil());
        assertEquals(0, pending.attemptCount());
        assertEquals(0, pending.version());
        assertEquals(NOW, pending.createdAt());
        assertEquals(NOW, pending.updatedAt());
    }

    @Test
    void claimBindsLeaseAndIncrementsAttemptAndCasVersion() {
        ReferenceAssemblyReleaseDispatchIntent pending = pending();

        ReferenceAssemblyReleaseDispatchIntent claimed = pending.claim(
                "claim-one", NOW.plusSeconds(1), Duration.ofSeconds(30));

        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING, claimed.status());
        assertEquals(Optional.of("claim-one"), claimed.claimToken());
        assertEquals(Optional.of(NOW.plusSeconds(31)), claimed.leaseUntil());
        assertEquals(1, claimed.attemptCount());
        assertEquals(1, claimed.version());
        assertEquals(NOW.plusSeconds(1), claimed.updatedAt());
        assertTrue(pending.sameImmutableIdentity(claimed));
    }

    @Test
    void uncertainRetryCannotBeReclaimedUntilItsExactRetryTime() {
        ReferenceAssemblyReleaseDispatchIntent uncertain = pending()
                .claim("claim-one", NOW.plusSeconds(1), Duration.ofSeconds(30))
                .uncertain(
                        "claim-one",
                        "ACTION_NOT_YET_WAITING",
                        NOW.plusSeconds(2),
                        NOW.plusSeconds(3));

        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.UNCERTAIN, uncertain.status());
        assertEquals(2, uncertain.version());
        assertEquals(1, uncertain.attemptCount());
        assertEquals(Optional.of("ACTION_NOT_YET_WAITING"), uncertain.lastCode());
        assertThrows(
                IllegalStateException.class,
                () -> uncertain.claim(
                        "claim-two", NOW.plusSeconds(2), Duration.ofSeconds(30)));

        ReferenceAssemblyReleaseDispatchIntent reclaimed = uncertain.claim(
                "claim-two", NOW.plusSeconds(3), Duration.ofSeconds(30));
        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING, reclaimed.status());
        assertEquals(2, reclaimed.attemptCount());
        assertEquals(3, reclaimed.version());
        assertEquals(Optional.of("ACTION_NOT_YET_WAITING"), reclaimed.lastCode());
    }

    @Test
    void runningLeaseCanBeRecoveredOnlyAtOrAfterExactExpiry() {
        ReferenceAssemblyReleaseDispatchIntent running = pending().claim(
                "claim-one", NOW.plusSeconds(1), Duration.ofSeconds(5));

        assertThrows(
                IllegalStateException.class,
                () -> running.claim(
                        "claim-two", NOW.plusSeconds(5), Duration.ofSeconds(5)));

        ReferenceAssemblyReleaseDispatchIntent recovered = running.claim(
                "claim-two", NOW.plusSeconds(6), Duration.ofSeconds(5));
        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING, recovered.status());
        assertEquals(Optional.of("claim-two"), recovered.claimToken());
        assertEquals(2, recovered.attemptCount());
        assertEquals(2, recovered.version());
        assertTrue(running.sameImmutableIdentity(recovered));
    }

    @Test
    void terminalTransitionsPreserveIdentityAndCannotBeMutatedAgain() {
        ReferenceAssemblyReleaseDispatchIntent completed = pending()
                .claim("complete-owner", NOW.plusSeconds(1), Duration.ofSeconds(5))
                .complete("complete-owner", "RELEASE_COMPLETED", NOW.plusSeconds(2));
        ReferenceAssemblyReleaseDispatchIntent orphaned = pending()
                .claim("orphan-owner", NOW.plusSeconds(1), Duration.ofSeconds(5))
                .orphan("orphan-owner", "ACTION_OWNER_ORPHANED", NOW.plusSeconds(2));
        ReferenceAssemblyReleaseDispatchIntent orphanedBeforeWaiting = pending()
                .claim("pre-park-owner", NOW.plusSeconds(1), Duration.ofSeconds(5))
                .orphanBeforeWaiting(
                        "pre-park-owner",
                        "ACTION_NEVER_WAITED",
                        NOW.plusSeconds(2));

        assertTerminal(completed, ReferenceAssemblyReleaseDispatchIntentStatus.COMPLETED);
        assertTerminal(orphaned, ReferenceAssemblyReleaseDispatchIntentStatus.ORPHANED);
        assertTerminal(
                orphanedBeforeWaiting,
                ReferenceAssemblyReleaseDispatchIntentStatus.ORPHANED_BEFORE_WAITING);
        assertThrows(
                IllegalStateException.class,
                () -> completed.claim("late-owner", NOW.plusSeconds(3), Duration.ofSeconds(5)));
        assertThrows(
                IllegalStateException.class,
                () -> completed.complete(
                        "complete-owner", "SECOND_COMPLETION", NOW.plusSeconds(3)));
    }

    @Test
    void wrongClaimAndNonMonotonicOrNonCanonicalTimesFailClosed() {
        ReferenceAssemblyReleaseDispatchIntent running = pending().claim(
                "claim-owner", NOW.plusSeconds(1), Duration.ofSeconds(5));

        assertThrows(
                IllegalStateException.class,
                () -> running.complete(
                        "wrong-owner", "RELEASE_COMPLETED", NOW.plusSeconds(2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> running.uncertain(
                        "claim-owner",
                        "ACTION_NOT_YET_WAITING",
                        NOW,
                        NOW.plusSeconds(2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> pending().claim(
                        "claim-owner",
                        NOW.plusNanos(1_000),
                        Duration.ofNanos(1_001)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseDispatchIntent.pending(
                        OPERATION_ID,
                        TENANT,
                        INPUT,
                        ACTION_RUN_ID,
                        ATTEMPT_HASH,
                        DEADLINE,
                        NOW.plusNanos(1)));
    }

    @Test
    void rehydrationRejectsImpossibleLifecycleShapes() {
        ReferenceAssemblyReleaseDispatchIntent base = pending();

        assertThrows(
                IllegalArgumentException.class,
                () -> rehydrate(
                        base,
                        ReferenceAssemblyReleaseDispatchIntentStatus.PENDING,
                        Optional.of("illegal-claim"),
                        Optional.empty(),
                        0,
                        Optional.empty(),
                        0,
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> rehydrate(
                        base,
                        ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING,
                        Optional.empty(),
                        Optional.of(NOW.plusSeconds(5)),
                        1,
                        Optional.empty(),
                        1,
                        NOW.plusSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> rehydrate(
                        base,
                        ReferenceAssemblyReleaseDispatchIntentStatus.UNCERTAIN,
                        Optional.empty(),
                        Optional.of(NOW.plusSeconds(5)),
                        1,
                        Optional.empty(),
                        2,
                        NOW.plusSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> rehydrate(
                        base,
                        ReferenceAssemblyReleaseDispatchIntentStatus.COMPLETED,
                        Optional.empty(),
                        Optional.of(NOW.plusSeconds(5)),
                        1,
                        Optional.of("RELEASE_COMPLETED"),
                        2,
                        NOW.plusSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReferenceAssemblyReleaseDispatchIntent(
                        base.operationId(),
                        base.tenantId(),
                        base.referenceAssemblyId(),
                        base.assemblyManifestHash(),
                        base.inspectionReportHash(),
                        base.releaseDecisionPointId(),
                        base.releaseSubjectHash(),
                        base.expectedReferenceAssemblyVersion(),
                        base.actionRunId(),
                        base.attemptTokenHash(),
                        NOW,
                        base.status(),
                        base.claimToken(),
                        base.leaseUntil(),
                        base.attemptCount(),
                        base.lastCode(),
                        base.version(),
                        NOW,
                        NOW));
    }

    @Test
    void duplicateIdentityRequiresEveryImmutableOwnerAndReleaseLock() {
        ReferenceAssemblyReleaseDispatchIntent base = pending();
        ReferenceAssemblyReleaseDispatchIntent progressed = base.claim(
                "claim-owner", NOW.plusSeconds(1), Duration.ofSeconds(5));
        ReferenceAssemblyReleaseDispatchIntent differentAction =
                ReferenceAssemblyReleaseDispatchIntent.pending(
                        OPERATION_ID,
                        TENANT,
                        INPUT,
                        "different-action-run",
                        ATTEMPT_HASH,
                        DEADLINE,
                        NOW);
        ReferenceAssemblyReleaseDispatchIntent differentAttempt =
                ReferenceAssemblyReleaseDispatchIntent.pending(
                        OPERATION_ID,
                        TENANT,
                        INPUT,
                        ACTION_RUN_ID,
                        ReferenceAssemblyReleaseAttemptTokens.hash("different-attempt"),
                        DEADLINE,
                        NOW);
        ReferenceAssemblyReleaseDispatchIntent differentDeadline =
                ReferenceAssemblyReleaseDispatchIntent.pending(
                        OPERATION_ID,
                        TENANT,
                        INPUT,
                        ACTION_RUN_ID,
                        ATTEMPT_HASH,
                        DEADLINE.plusSeconds(1),
                        NOW);

        assertTrue(base.sameImmutableIdentity(progressed));
        assertFalse(base.sameImmutableIdentity(differentAction));
        assertFalse(base.sameImmutableIdentity(differentAttempt));
        assertFalse(base.sameImmutableIdentity(differentDeadline));
        assertFalse(base.sameImmutableIdentity(null));
    }

    @Test
    void boundedExactIdentitiesRejectFloatingOrOversizedAuthority() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseDispatchIntent.pending(
                        "reference-assembly-release:" + "0".repeat(64),
                        TENANT,
                        INPUT,
                        ACTION_RUN_ID,
                        ATTEMPT_HASH,
                        DEADLINE,
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseDispatchIntent.pending(
                        OPERATION_ID,
                        TENANT,
                        INPUT,
                        "action-run-latest",
                        ATTEMPT_HASH,
                        DEADLINE,
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseDispatchIntent.pending(
                        OPERATION_ID,
                        new TenantId("t".repeat(129)),
                        INPUT,
                        ACTION_RUN_ID,
                        ATTEMPT_HASH,
                        DEADLINE,
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseDispatchOperationIds.derive(
                        new TenantId("tenant\ninvalid"), INPUT));
    }

    private static ReferenceAssemblyReleaseDispatchIntent pending() {
        return ReferenceAssemblyReleaseDispatchIntent.pending(
                OPERATION_ID,
                TENANT,
                INPUT,
                ACTION_RUN_ID,
                ATTEMPT_HASH,
                DEADLINE,
                NOW);
    }

    private static void assertTerminal(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssemblyReleaseDispatchIntentStatus expectedStatus) {
        assertEquals(expectedStatus, intent.status());
        assertTrue(intent.status().isTerminal());
        assertEquals(Optional.empty(), intent.claimToken());
        assertEquals(Optional.empty(), intent.leaseUntil());
        assertEquals(1, intent.attemptCount());
        assertEquals(2, intent.version());
        assertTrue(pending().sameImmutableIdentity(intent));
    }

    private static ReferenceAssemblyReleaseDispatchIntent rehydrate(
            ReferenceAssemblyReleaseDispatchIntent base,
            ReferenceAssemblyReleaseDispatchIntentStatus status,
            Optional<String> claimToken,
            Optional<Instant> leaseUntil,
            int attemptCount,
            Optional<String> lastCode,
            long version,
            Instant updatedAt) {
        return new ReferenceAssemblyReleaseDispatchIntent(
                base.operationId(),
                base.tenantId(),
                base.referenceAssemblyId(),
                base.assemblyManifestHash(),
                base.inspectionReportHash(),
                base.releaseDecisionPointId(),
                base.releaseSubjectHash(),
                base.expectedReferenceAssemblyVersion(),
                base.actionRunId(),
                base.attemptTokenHash(),
                base.deadlineAt(),
                status,
                claimToken,
                leaseUntil,
                attemptCount,
                lastCode,
                version,
                base.createdAt(),
                updatedAt);
    }

    private static ReferenceAssemblyReleaseInput input(
            String assemblyId,
            String assemblyManifest,
            String inspectionReport,
            String decisionPoint,
            String releaseSubject,
            long expectedVersion) {
        return new ReferenceAssemblyReleaseInput(
                new ReferenceAssemblyId(assemblyId),
                hash(assemblyManifest),
                hash(inspectionReport),
                new DecisionPointId(decisionPoint),
                hash(releaseSubject),
                expectedVersion);
    }

    private static ContentHash hash(String value) {
        return ReferenceAssemblyArtifactSupport.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
