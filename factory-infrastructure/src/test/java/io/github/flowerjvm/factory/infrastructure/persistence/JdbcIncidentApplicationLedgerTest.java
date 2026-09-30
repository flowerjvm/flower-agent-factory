package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.*;
import org.junit.jupiter.api.Test;

/** H2 fast relational and registered Action integration tests with explicitly synthetic products. */
class JdbcIncidentApplicationLedgerTest {
    static IncidentApplicationLedgerTestSupport fixture(String name) {
        return new IncidentApplicationLedgerTestSupport(FactoryDatabaseMigrationsTest.h2("incident_"+name),name);
    }
    @Test void governedBuildInspectExactApprovalSeparateCertificateAndRelease() throws Exception { assertLifecycle(fixture("lifecycle")); }
    static void assertLifecycle(IncidentApplicationLedgerTestSupport f) throws Exception {
        f.inspect(); assertEquals(1,f.builds.get()); assertEquals(1,f.verifies.get());
        assertNull(f.product().productCertification());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.RELEASE).status());
        assertEquals(IncidentApplicationProduct.Status.REVIEW,f.product().status());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.approve(new ContentHash("f".repeat(64))).status());
        assertEquals(ActionExecutionStatus.SUCCEEDED,f.approve(f.product().releaseSubject().hash()).status());
        f.stage(IncidentApplicationIntent.Stage.RELEASE);
        assertEquals(IncidentApplicationProduct.Status.RELEASED,f.product().status());
        assertNotNull(f.product().productCertification()); assertNotNull(f.product().releaseManifest());
        assertNotEquals(f.order.component().certificationManifest(),f.product().productCertification());
        assertEquals(BuildSessionStatus.SUCCEEDED,f.sessions.find(f.order.tenantId(),f.order.buildSessionId()).orElseThrow().status());
        assertEquals(IncidentApplicationIntent.Status.COMPLETED,f.ledger.intent(f.product().activeOperationId()).orElseThrow().status());
        assertEquals(1,f.count("factory_incident_application")); assertEquals(3,f.count("factory_incident_application_intent"));
        var released=new IncidentApplicationReleasedReadGate(f.ledger,f.sessions,f.points,f.decisions,f.artifacts,f.runs,f.producer.gate(),f.tool);
        assertEquals(f.product(),released.resolve(f.order.tenantId(),f.order.buildSessionId()));
        f.runner.drain(8); assertEquals(1,f.builds.get()); assertEquals(1,f.verifies.get());
    }
    @Test void wrongTenantAndPayloadCannotConsumeCompletedIntake() {
        var f=fixture("isolation"); f.accept();
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.launcher.submit(new TenantId("other-tenant"),f.order.requestKey(),IncidentApplicationIntake.input(f.order)).status());
        var changed=new java.util.HashMap<>(IncidentApplicationIntake.input(f.order)); changed.put("variant","HISTORY");
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.launcher.submit(f.order.tenantId(),f.order.requestKey(),changed).status());
        assertEquals(f.order,f.product().order());
    }
    @Test void verifierFailureNeverCreatesReviewOrCertificate() {
        var f=fixture("failed_verifier"); f.verificationPass=false; f.accept(); f.stage(IncidentApplicationIntent.Stage.BUILD); f.stage(IncidentApplicationIntent.Stage.VERIFY);
        assertEquals(IncidentApplicationProduct.Status.FAILED,f.product().status()); assertNull(f.product().decisionPointId());
        assertNull(f.product().productCertification()); assertNull(f.product().releaseManifest());
        assertEquals(IncidentApplicationIntent.Status.COMPLETED,f.ledger.intent(f.product().activeOperationId()).orElseThrow().status());
    }
    @Test void deadlineEqualityPreventsStageExecution() {
        var f=fixture("deadline"); f.accept(); f.clock.value=f.order.deadlineAt(); f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.BUILD); f.runner.drain(8);
        assertEquals(0,f.builds.get()); assertNull(f.product().productCertification());
    }
    @Test void cancellationBeforeDispatchCannotBuildOrRelease() {
        var f=fixture("cancel"); f.accept();
        var session=f.sessions.find(f.order.tenantId(),f.order.buildSessionId()).orElseThrow();
        assertTrue(f.sessions.compareAndSet(session,session.requestCancellation(f.clock.instant())));
        f.ledger.cancel(f.order.tenantId(),f.order.buildSessionId(),f.clock.instant());
        f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.BUILD); f.runner.drain(8);
        assertEquals(0,f.builds.get()); assertTrue(f.product().terminal()); assertNull(f.product().releaseManifest());
    }
    @Test void revocationAfterInspectionPreventsApprovalAndRelease() {
        var f=fixture("revoked"); f.inspect(); var old=f.producer.certification.certifications().find(f.order.tenantId(),f.order.component().certificationId()).orElseThrow();
        assertTrue(f.producer.certification.certifications().compareAndSet(old,old.revoke("TEST_REVOKED",f.clock.instant())));
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.approve(f.product().releaseSubject().hash()).status());
        f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.RELEASE); f.runner.drain(8); assertNull(f.product().releaseManifest());
    }
}
