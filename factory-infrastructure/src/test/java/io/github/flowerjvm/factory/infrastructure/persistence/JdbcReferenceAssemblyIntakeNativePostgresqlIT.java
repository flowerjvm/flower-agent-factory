package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeReceipt;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native database semantics against synthetic fixtures in disposable, isolated PostgreSQL schemas. */
@Testcontainers
class JdbcReferenceAssemblyIntakeNativePostgresqlIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Clock CLOCK = Clock.fixed(JdbcReferenceAssemblyIntakeTransactionTest.NOW, ZoneOffset.UTC);

    @Test
    void concurrentExactIntakeSurvivesTheNativeUniqueViolationSavepointAndCommitsOnce() throws Exception {
        JdbcReferenceAssemblyIntakeTransactionTest.assertConcurrentExact(fixture("native_ra_intake_exact"));
    }

    @Test
    void concurrentChangedPayloadHasOneImmutableRequestOwner() throws Exception {
        var fixture = fixture("native_ra_intake_changed");
        var changed = fixture.request(new BuildSessionId("native-other-session"), new ProjectId("native-other-project"),
                fixture.session.requestIdempotencyKey(), fixture.session.deadlineAt(),
                JdbcReferenceAssemblyIntakeTransactionTest.NOW, fixture.tenant());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    fixture.transaction(fixture.gate(), CLOCK).accept(fixture.session, fixture.component, fixture.staged);
                    return true;
                } catch (FactoryPersistenceException rejected) { return false; }
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    fixture.transaction(fixture.gate(), CLOCK).accept(changed, fixture.component, fixture.stage(changed));
                    return true;
                } catch (FactoryPersistenceException rejected) { return false; }
            });
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, fixture.raSessionCount());
        assertTrue(fixture.receipt().isPresent());
    }

    @Test
    void existingSessionWithoutReceiptIsNotRetroactivelyClaimed() throws Exception {
        var fixture = fixture("native_ra_intake_orphan_session");
        fixture.staged.stream().filter(artifact -> !artifact.reference().equals(
                ReferenceAssemblyIntakeReceipt.reference(fixture.tenant(), fixture.session.requestIdempotencyKey())))
                .forEach(fixture.certification.artifacts()::store);
        new JdbcBuildSessionRepository(fixture.dataSource).create(fixture.session);
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), CLOCK)
                .accept(fixture.session, fixture.component, fixture.staged));
        assertTrue(fixture.receipt().isEmpty());
        assertEquals(1, fixture.raSessionCount());
        assertEquals(0, fixture.gateReads.get());
    }

    @Test
    void existingReceiptWithoutSessionDoesNotRecreateTheMissingLedger() throws Exception {
        var fixture = fixture("native_ra_intake_orphan_receipt");
        fixture.certification.artifacts().store(ReferenceAssemblyIntakeReceipt.artifactFor(fixture.session, fixture.component));
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), CLOCK)
                .accept(fixture.session, fixture.component, fixture.staged));
        assertTrue(fixture.receipt().isPresent());
        assertEquals(0, fixture.raSessionCount());
        assertEquals(0, fixture.gateReads.get());
    }

    @Test
    void fullReadRunsUnderTheCanonicalCertificationRowLockAndRevocationBlocksLaterConsumption() throws Exception {
        var fixture = fixture("native_ra_intake_cert_lock");
        var transaction = fixture.transaction((tenant, component) -> {
            try (var connection = fixture.dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement(
                        "SELECT certification_id FROM factory_certification WHERE tenant_id = ? AND certification_id = ? FOR UPDATE NOWAIT")) {
                    statement.setString(1, tenant.value());
                    statement.setString(2, component.certificationId().value());
                    SQLException locked = assertThrows(SQLException.class, statement::executeQuery);
                    assertEquals("55P03", locked.getSQLState());
                } finally { connection.rollback(); }
            } catch (SQLException failed) { throw new IllegalStateException(failed); }
            return fixture.resolved();
        }, CLOCK);
        assertEquals(fixture.session, transaction.accept(fixture.session, fixture.component, fixture.staged));
        var certified = fixture.certification.certified();
        assertTrue(fixture.certification.certifications().compareAndSet(certified,
                certified.revoke("REVOKED", JdbcReferenceAssemblyIntakeTransactionTest.NOW)));
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), CLOCK)
                .accept(fixture.session, fixture.component, fixture.staged));
        assertEquals(1, fixture.raSessionCount());
        assertTrue(fixture.receipt().isPresent());
    }

    private static JdbcReferenceAssemblyIntakeTransactionTest.Fixture fixture(String schema) throws SQLException {
        PGSimpleDataSource administrator = new PGSimpleDataSource();
        administrator.setURL(POSTGRES.getJdbcUrl());
        administrator.setUser(POSTGRES.getUsername());
        administrator.setPassword(POSTGRES.getPassword());
        try (var connection = administrator.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        PGSimpleDataSource isolated = new PGSimpleDataSource();
        isolated.setURL(POSTGRES.getJdbcUrl());
        isolated.setUser(POSTGRES.getUsername());
        isolated.setPassword(POSTGRES.getPassword());
        isolated.setCurrentSchema(schema);
        return new JdbcReferenceAssemblyIntakeTransactionTest.Fixture(isolated, schema);
    }
}
