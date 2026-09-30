package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native PostgreSQL coverage for the composable Reference Assembly ledgers and release boundary. */
@Testcontainers
class JdbcReferenceAssemblyNativePostgresqlIT {
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:10Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void nativeFractionalBusinessDeadlineRetainsExactApprovalAndIntentAcrossJdbcReleaseCommit()
            throws Exception {
        Instant deadline = BASE.plusSeconds(300).plusNanos(123_456_000);
        var fixture = JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                isolatedDataSource("factory_reference_fractional_release"), "native-fractional", deadline);
        JdbcReferenceAssemblyReleaseTransactionTest.assertFractionalDeadlineCommit(fixture, deadline);
    }

    @Test
    void nativeDispatchAtomicallyBindsTheActionOwnerAndConvergesOnOneIntent()
            throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource("factory_reference_dispatch");
        var fixture = JdbcReferenceAssemblyReleaseDispatchTransactionTest.Fixture.create(
                dataSource, "native-dispatch");

        var first = fixture.prepare();
        var retry = fixture.prepare();

        assertEquals(first, retry);
        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.PENDING, first.status());
        assertEquals(1, fixture.intentCount());
        assertEquals(fixture.actionRunId(), fixture.storedAssembly()
                .releaseActionRunId()
                .orElseThrow());
    }

    @Test
    void nativeExecutorCanonicalizesNanosecondClockBeforeStrictAtomicDispatch()
            throws Exception {
        var fixture = JdbcReferenceAssemblyReleaseDispatchTransactionTest.Fixture.create(
                isolatedDataSource("factory_reference_nano_dispatch"), "native-nano");
        Instant observed = Instant.parse("2026-09-01T00:00:20.123456789Z");
        String requester = ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID;
        var proposal = ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("proposal-" + fixture.actionRunId())
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId(requester)
                .input(fixture.input().toMap())
                .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        fixture.approvedAssembly(), fixture.input().expectedReferenceAssemblyVersion()))
                .build();
        var context = new ActionExecutionContext(
                new ExecutionContext(fixture.tenant().value(), requester, fixture.actionRunId(),
                        fixture.canonicalTrace(), Map.of(
                                "actor.permissions", Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                                "resource.type", ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                                "resource.id", fixture.input().referenceAssemblyId().value())),
                proposal, ReferenceAssemblyReleaseAction.definition(),
                fixture.input().toMap(), fixture.attemptToken());
        var executor = new ReferenceAssemblyReleaseActionExecutor(
                fixture.transaction(), Clock.fixed(observed, ZoneOffset.UTC));

        var awaiting = executor.dispatchDeferred(context);
        var exactRetry = executor.dispatchDeferred(context);
        var stored = fixture.transaction().findExact(fixture.tenant(), fixture.input(),
                fixture.actionRunId(), fixture.attemptTokenHash()).orElseThrow();

        assertEquals(awaiting, exactRetry);
        assertEquals(stored.operationId(), awaiting.operationId());
        assertEquals(fixture.sessionDeadline(), awaiting.dueAt());
        assertEquals(observed.truncatedTo(ChronoUnit.MICROS), stored.createdAt());
        assertEquals(stored.createdAt(), fixture.storedAssembly().updatedAt());
        assertEquals(1, fixture.intentCount());
        assertEquals(fixture.input().expectedReferenceAssemblyVersion() + 1,
                fixture.storedAssembly().version());
        assertEquals(fixture.actionRunId(), fixture.storedAssembly().releaseActionRunId().orElseThrow());
    }

    @Test
    void nativeRawNanosecondPreparationStillFailsWithNoBindingOrIntent() throws Exception {
        var fixture = JdbcReferenceAssemblyReleaseDispatchTransactionTest.Fixture.create(
                isolatedDataSource("factory_reference_reject_nano"), "native-reject-nano");

        var rejected = assertThrows(IllegalArgumentException.class,
                () -> fixture.transaction().prepare(fixture.tenant(), fixture.input(),
                        fixture.actionRunId(), fixture.attemptTokenHash(),
                        Instant.parse("2026-09-01T00:00:20.123456789Z")));

        assertEquals("preparedAt must use microsecond precision", rejected.getMessage());
        fixture.assertNoDispatchResidue();
    }

    @Test
    void nativeControlledReleaseCommitsOnceAndExactRetryReadsTheReleasedProduct()
            throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource("factory_reference_release");
        var fixture = JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                dataSource, "native-release");

        ReferenceAssemblyReleaseTransaction.ReleaseCommit first =
                fixture.transactionAt(BASE.plusSeconds(11)).commit(
                        fixture.intent(), fixture.expected(), fixture.proposed());
        ReferenceAssemblyReleaseTransaction.ReleaseCommit retry =
                fixture.transactionAt(BASE.plusSeconds(12)).commit(
                        fixture.intent(), fixture.expected(), fixture.proposed());

        assertTrue(first.committedNow());
        assertFalse(retry.committedNow());
        assertEquals(first.referenceAssembly(), retry.referenceAssembly());
        assertEquals(ReferenceAssemblyStatus.RELEASED, retry.referenceAssembly().status());
        assertEquals(retry.referenceAssembly(), fixture.assemblies()
                .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                .orElseThrow());
    }

    @Test
    void nativeReleaseVersusBuildSessionCancellationHasOneSafeJdbcWinner()
            throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource(
                "factory_reference_release_session_race");
        JdbcReferenceAssemblyReleaseConcurrencyAssertions.assertBuildSessionCancellationRace(
                JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                        dataSource, "nrs"));
    }

    @Test
    void nativeReleaseVersusActionCancellationHasOneSafeJdbcWinner()
            throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource(
                "factory_reference_release_action_race");
        JdbcReferenceAssemblyReleaseConcurrencyAssertions.assertActionCancellationRace(
                JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                        dataSource, "nra"));
    }

    @Test
    void nativeReleaseVersusCertificationRevocationPreservesSafeJdbcLedgers()
            throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource(
                "factory_reference_release_certification_race");
        JdbcReferenceAssemblyReleaseConcurrencyAssertions.assertCertificationRevocationRace(
                JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                        dataSource, "nrc"));
    }

    private static PGSimpleDataSource isolatedDataSource(String schema) throws SQLException {
        PGSimpleDataSource administrator = new PGSimpleDataSource();
        administrator.setURL(POSTGRES.getJdbcUrl());
        administrator.setUser(POSTGRES.getUsername());
        administrator.setPassword(POSTGRES.getPassword());
        try (var connection = administrator.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        PGSimpleDataSource isolated = new PGSimpleDataSource();
        isolated.setURL(POSTGRES.getJdbcUrl());
        isolated.setUser(POSTGRES.getUsername());
        isolated.setPassword(POSTGRES.getPassword());
        isolated.setCurrentSchema(schema);
        return isolated;
    }
}
