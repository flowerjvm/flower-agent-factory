package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcIncidentReviewRenewalTest.fixture;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcIncidentReviewRenewalTest.renew;
import io.github.flowerjvm.factory.application.action.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.*;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Synthetic JDBC products. Owner faults affect reads only; no Action/Decision/FK row is patched. */
class JdbcIncidentReviewRenewalFencesTest {
    @Test void missingRenewalOwnerDeniesFreshDecisionAndReleaseThroughRegisteredActions() {
        assertDecisionAndReleaseBlocked(OwnerFault.MISSING);
    }

    @Test void runningRenewalOwnerDoesNotGrantApprovalOrReleaseWhileTerminalResultIsUnknown() {
        assertDecisionAndReleaseBlocked(OwnerFault.RUNNING);
    }

    @Test void failedRenewalOwnerCannotAuthorizeDecisionOrReleaseDespitePersistedWindow() {
        assertDecisionAndReleaseBlocked(OwnerFault.FAILED);
    }

    private void assertDecisionAndReleaseBlocked(OwnerFault fault) {
        var f = renewed("owner_fence_" + fault.name().toLowerCase(Locale.ROOT));
        var product = f.product(); var proof = f.ledger.reviewRenewal(f.order.tenantId(), f.order.buildSessionId()).orElseThrow();
        var owner = f.runs.find(proof.actionRunId()).orElseThrow();
        var view = faultView(f, owner, fault);
        var runtime = controlledRuntime(f, view);
        assertThrows(IllegalArgumentException.class, () -> view.ledger().reviewRenewal(f.order.tenantId(), f.order.buildSessionId()));
        var point = f.points.find(f.order.tenantId(), product.decisionPointId()).orElseThrow();
        var authority = new DecisionRecordAuthority(f.order.tenantId(), f.order.projectId(), "synthetic-fence-human",
                Set.of(DecisionRecordAction.PERMISSION, IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION),
                new ArtifactReference("fixture:renewal-fence-human-authority"));
        var result = new ActionBackedDecisionRecordLauncher(runtime, f.clock).record(authority, point.decisionPointId(),
                "synthetic-fence-decision-" + UUID.randomUUID(), new DecisionRecordInput(point.decisionPointId(), point.version(),
                        product.releaseSubject().hash(), DecisionOutcome.APPROVE, Optional.of("Synthetic owner-visibility boundary test")));
        assertEquals(ActionExecutionStatus.DENIED, result.status());
        assertEquals(point, f.points.find(f.order.tenantId(), point.decisionPointId()).orElseThrow());
        assertEquals(product, f.product());
        // A valid independent approval is deliberately present for the next assertion, so an
        // OPEN point cannot mask the renewal-owner check at the RELEASE pre-execution gate.
        assertEquals(ActionExecutionStatus.SUCCEEDED, f.approve(product.releaseSubject().hash()).status());
        var release = new IncidentApplicationLauncher(runtime).stage(f.product(), IncidentApplicationIntent.Stage.RELEASE);
        assertEquals(ActionExecutionStatus.DENIED, release.status());
        assertTrue(f.ledger.stageIntent(f.order.tenantId(), f.order.buildSessionId(), IncidentApplicationIntent.Stage.RELEASE).isEmpty());
        assertEquals(product, f.product()); assertNull(f.product().releaseManifest()); assertNull(f.product().productCertification());
        assertEquals(owner, f.runs.find(owner.runId()).orElseThrow());
        assertEquals(proof, f.ledger.reviewRenewal(f.order.tenantId(), f.order.buildSessionId()).orElseThrow());
        assertEquals(1, f.builds.get()); assertEquals(1, f.verifies.get());
    }

    @Test void releasedReadGateRejectsMissingRunningFailedAndWrongSuccessRenewalOwnersWithoutChangingCanonicalRows() {
        var f = renewed("released_owner_fence");
        assertEquals(ActionExecutionStatus.SUCCEEDED, f.approve(f.product().releaseSubject().hash()).status());
        f.stage(IncidentApplicationIntent.Stage.RELEASE);
        var product = f.product(); assertEquals(IncidentApplicationProduct.Status.RELEASED, product.status());
        var proof = f.ledger.reviewRenewal(f.order.tenantId(), f.order.buildSessionId()).orElseThrow();
        var owner = f.runs.find(proof.actionRunId()).orElseThrow();
        var good = new IncidentApplicationReleasedReadGate(f.ledger, f.sessions, f.points, f.decisions, f.artifacts, f.runs, f.producer.gate(), f.tool);
        assertEquals(product, good.resolve(f.order.tenantId(), f.order.buildSessionId()));
        for (OwnerFault fault : OwnerFault.values()) {
            var view = faultView(f, owner, fault);
            var gate = new IncidentApplicationReleasedReadGate(view.ledger(), f.sessions, f.points, f.decisions, f.artifacts,
                    view.runs(), f.producer.gate(), f.tool);
            var denied = assertThrows(IllegalArgumentException.class, () -> gate.resolve(f.order.tenantId(), f.order.buildSessionId()), fault.name());
            assertEquals("INCIDENT_APPLICATION_RELEASE_NOT_ELIGIBLE", denied.getMessage());
            assertEquals(owner, f.runs.find(owner.runId()).orElseThrow());
            assertEquals(product, f.product());
        }
        assertEquals(product, good.resolve(f.order.tenantId(), f.order.buildSessionId()));
        assertEquals(1, f.builds.get()); assertEquals(1, f.verifies.get());
    }

    @Test void cancellationAfterRenewedApprovalAndReleaseDispatchPreventsFinalPublication() {
        var f = renewed("cancel_after_dispatch"); var claim = approvedReleaseClaim(f);
        var product = f.product(); var session = f.sessions.find(f.order.tenantId(), f.order.buildSessionId()).orElseThrow();
        f.clock.value = f.clock.instant().plusSeconds(1);
        assertTrue(f.sessions.compareAndSet(session, session.requestCancellation(f.clock.instant())));
        assertThrows(IllegalArgumentException.class, () -> f.ledger.commitRelease(claim, f.clock.instant()));
        assertUnpublishedAndUnchanged(f, product, claim);
        assertTrue(f.sessions.find(f.order.tenantId(), f.order.buildSessionId()).orElseThrow().cancellationRequestedAt().isPresent());
    }

    @Test void componentRevocationAfterRenewedApprovalAndReleaseDispatchPreventsFinalPublication() {
        var f = renewed("revoke_after_dispatch"); var claim = approvedReleaseClaim(f); var product = f.product();
        var certificates = f.producer.certification.certifications();
        var component = certificates.find(f.order.tenantId(), f.order.component().certificationId()).orElseThrow();
        f.clock.value = f.clock.instant().plusSeconds(1);
        assertTrue(certificates.compareAndSet(component, component.revoke("SYNTHETIC_POST_RENEWAL_REVOCATION", f.clock.instant())));
        assertThrows(IllegalArgumentException.class, () -> f.ledger.commitRelease(claim, f.clock.instant()));
        assertUnpublishedAndUnchanged(f, product, claim);
        assertTrue(certificates.find(f.order.tenantId(), f.order.component().certificationId()).orElseThrow().revokedAt().isPresent());
    }

    private static void assertUnpublishedAndUnchanged(IncidentApplicationLedgerTestSupport f, IncidentApplicationProduct before,
            IncidentApplicationIntent claim) {
        assertEquals(before, f.product()); assertNull(f.product().productCertification()); assertNull(f.product().releaseManifest());
        assertEquals(claim, f.ledger.intent(claim.operationId()).orElseThrow());
        assertEquals(1, f.builds.get()); assertEquals(1, f.verifies.get());
    }

    private static IncidentApplicationIntent approvedReleaseClaim(IncidentApplicationLedgerTestSupport f) {
        assertEquals(ActionExecutionStatus.SUCCEEDED, f.approve(f.product().releaseSubject().hash()).status());
        assertEquals(ActionExecutionStatus.ACCEPTED, f.launcher.stage(f.product(), IncidentApplicationIntent.Stage.RELEASE).status());
        var operation = f.ledger.intent(f.product().activeOperationId()).orElseThrow();
        return f.ledger.claim(operation.operationId(), "synthetic-fence-claim", f.clock.instant()).orElseThrow();
    }

    private static IncidentApplicationLedgerTestSupport renewed(String suffix) {
        var f = fixture(suffix); f.inspect(); f.clock.value = f.order.deadlineAt();
        var result = renew(f, IncidentApplicationReviewRenewalAction.input(f.product(), f.clock.instant().plusSeconds(3600)));
        assertEquals(ActionExecutionStatus.SUCCEEDED, result.status(), result.toString());
        return f;
    }

    private static FaultView faultView(IncidentApplicationLedgerTestSupport f, ActionRun canonical, OwnerFault fault) {
        Optional<ActionRun> substituted = switch (fault) {
            case MISSING -> Optional.empty();
            case RUNNING -> Optional.of(canonical.toBuilder().status(ActionRunStatus.RUNNING).result(null).build());
            case FAILED -> Optional.of(canonical.toBuilder().status(ActionRunStatus.FAILED)
                    .result(ActionExecutionResult.manualReviewFailure("SYNTHETIC_OWNER_UNCERTAIN", "Synthetic read boundary")).build());
            case WRONG_SUCCESS -> Optional.of(canonical.toBuilder().result(ActionExecutionResult.succeeded(Map.of("wrong", "result"))).build());
        };
        var view = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(), new Class<?>[]{RunStore.class}, (proxy, method, args) -> {
            if (method.getName().equals("find") && canonical.runId().equals(args[0])) return substituted;
            if (args != null) for (Object arg : args) {
                if (arg instanceof ActionRun run && canonical.runId().equals(run.runId())) throw new AssertionError("Renewal owner must not be physically mutated by the fault view");
            }
            try { return method.invoke(f.runs, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        return new FaultView(view, new JdbcIncidentApplicationLedger(f.dataSource, f.mapper, view, f.producer.gate(), f.tool, f.clock));
    }

    private static DefaultActionRuntime controlledRuntime(IncidentApplicationLedgerTestSupport f, FaultView view) {
        var production = new IncidentApplicationActions(view.ledger(), f.intake, view.runs(), f.clock);
        var authority = new IncidentApplicationDecisionAuthority(view.ledger(), f.clock);
        var admission = new DecisionRecordAdmission(f.sessions, f.points, f.decisions, List.of(authority), f.clock);
        var recording = new DecisionRecordingService(f.sessions, f.points, f.decisions,
                new JdbcDecisionRecordingTransaction(f.dataSource, f.mapper), List.of(authority));
        return new DefaultActionRuntime(new InMemoryActionRegistry(List.of(production.releaseExecutor(), new DecisionRecordActionExecutor(recording, admission))),
                new FactoryActionInputValidatorRouter(Map.of(IncidentApplicationActions.RELEASE, production, DecisionRecordAction.ACTION_ID, new DecisionRecordActionValidator())),
                new FactoryActionPolicyGateRouter(Map.of(IncidentApplicationActions.RELEASE, production, DecisionRecordAction.ACTION_ID, new DecisionRecordPolicyGate(admission))),
                ApprovalGate.unsupported(), new JdbcDuplicateActionPolicy(f.dataSource, f.mapper,
                        new FactoryDuplicateVisibilityScopeRouter(Map.of(IncidentApplicationActions.RELEASE, production, DecisionRecordAction.ACTION_ID, new DecisionRecordVisibilityScopeResolver(admission)))),
                AuditSink.noop(), TraceSink.noop(), view.runs(),
                new FactoryPreExecutionGuardRouter(Map.of(IncidentApplicationActions.RELEASE, production, DecisionRecordAction.ACTION_ID, new DecisionRecordPreExecutionGuard(admission))));
    }

    private enum OwnerFault { MISSING, RUNNING, FAILED, WRONG_SUCCESS }
    private record FaultView(RunStore runs, JdbcIncidentApplicationLedger ledger) { }
}
