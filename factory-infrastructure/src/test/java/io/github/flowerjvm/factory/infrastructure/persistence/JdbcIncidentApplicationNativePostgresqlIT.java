package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native PostgreSQL transaction/owner contention; no real Factory DB, credentials or product code. */
@Testcontainers
class JdbcIncidentApplicationNativePostgresqlIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void nativeRenewalPreservesBytesAndReleasesThroughFreshDecision() throws Exception {
        JdbcIncidentReviewRenewalTest.assertLifecycle(fixture("incident_native_renew"));
    }
    @Test void nativeConcurrentRenewalCreatesOneWindow() throws Exception {
        JdbcIncidentReviewRenewalTest.assertConcurrent(fixture("incident_native_renew_race"));
    }
    @Test void nativeLifecycleHasDistinctWholeProductCertificateAndExactRelease() throws Exception {
        JdbcIncidentApplicationLedgerTest.assertLifecycle(fixture("incident_native_lifecycle"));
    }
    @Test void concurrentIntakeHasOneProductAndNoBuilderInvocation() throws Exception {
        var f=fixture("incident_native_intake"); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<ActionExecutionStatus> task=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return f.launcher.submit(f.order.tenantId(),f.order.requestKey(),IncidentApplicationIntake.input(f.order)).status();};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            assertTrue(first==ActionExecutionStatus.SUCCEEDED || second==ActionExecutionStatus.SUCCEEDED);
        }
        assertEquals(1,f.count("factory_incident_application"));assertEquals(0,f.builds.get());
        assertEquals(ActionExecutionStatus.SUCCEEDED,f.launcher.submit(f.order.tenantId(),f.order.requestKey(),IncidentApplicationIntake.input(f.order)).status());
    }
    @Test void concurrentStageClaimsDispatchOnce() throws Exception {
        var f=fixture("incident_native_claim");f.accept();f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.BUILD);
        var operation=f.ledger.pending(8).getFirst();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return f.ledger.claim(operation.operationId(),"claim-"+java.util.UUID.randomUUID(),f.clock.instant()).isPresent();};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals(0,f.builds.get());assertEquals(1,f.count("factory_incident_application_intent"));
        f.clock.value=f.clock.value.plusSeconds(301);f.runner.drain(8);
        assertEquals(IncidentApplicationProduct.Status.MANUAL_REVIEW,f.product().status());assertEquals(0,f.builds.get());
    }
    @Test void approvedProductCannotShipAfterCurrentComponentRevocation() throws Exception {
        var f=fixture("incident_native_revoked");f.inspect();assertEquals(ActionExecutionStatus.SUCCEEDED,f.approve(f.product().releaseSubject().hash()).status());
        var certification=f.producer.certification.certifications().find(f.order.tenantId(),f.order.component().certificationId()).orElseThrow();
        assertTrue(f.producer.certification.certifications().compareAndSet(certification,certification.revoke("NATIVE_REVOKED",f.clock.instant())));
        f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.RELEASE);f.runner.drain(8);assertNull(f.product().releaseManifest());
    }
    private static IncidentApplicationLedgerTestSupport fixture(String schema) throws Exception {
        PGSimpleDataSource administrator=new PGSimpleDataSource();administrator.setURL(POSTGRES.getJdbcUrl());administrator.setUser(POSTGRES.getUsername());administrator.setPassword(POSTGRES.getPassword());
        try(var c=administrator.getConnection();var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        PGSimpleDataSource isolated=new PGSimpleDataSource();isolated.setURL(POSTGRES.getJdbcUrl());isolated.setUser(POSTGRES.getUsername());isolated.setPassword(POSTGRES.getPassword());isolated.setCurrentSchema(schema);
        return new IncidentApplicationLedgerTestSupport(isolated,schema);
    }
}
