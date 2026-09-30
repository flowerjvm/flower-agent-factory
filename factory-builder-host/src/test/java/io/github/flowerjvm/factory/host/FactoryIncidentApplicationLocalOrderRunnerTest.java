package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Synthetic host transport fixtures only; no real certification or production execution claim. */
class FactoryIncidentApplicationLocalOrderRunnerTest {
    private static final TenantId TENANT = new TenantId("incident-host-test");
    private static final String KEY = "incident-request";
    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");
    private static final String PREFIX = "factory.production.incident-application.local-order.";
    private static final CertificationArtifactLock LOCK = new CertificationArtifactLock(new ArtifactReference("fixture:lock"), new ContentHash("a".repeat(64)));
    private static final CertifiedAgentComponentRef COMPONENT = new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION,
            "embedded-agent-pack", ProductLineId.AGENT_PACK, CertifiedArtifactType.AGENT_PACK, new CertificationId("fixture-cert"), LOCK,
            new CandidateId("fixture-candidate"), new ContentHash("b".repeat(64)), LOCK, LOCK, new VerificationRunId("fixture-verification"),
            LOCK, LOCK, LOCK, "fixture-profile");
    private static final IncidentApplicationOrder ORDER = order(IncidentApplicationOrder.Variant.BASIC);
    private static final Map<String,Object> INPUT = IncidentApplicationIntake.input(ORDER);

    @Test void usesRegisteredActionAndCanonicalReadbackThenLaunches() {
        var f = new Fixture(); f.runner().run(new DefaultApplicationArguments());
        assertEquals(1, f.proposals.size());
        var proposal = f.proposals.getFirst(); var context = f.contexts.getFirst();
        assertEquals(IncidentApplicationActions.INTAKE, proposal.actionId());
        assertEquals(INPUT, proposal.input()); assertEquals(KEY, proposal.idempotencyKey());
        assertEquals(ActionProposerType.SERVICE, proposal.proposerType());
        assertEquals(ActionRequestChannel.INTERNAL, proposal.requestChannel());
        IncidentApplicationActions.authority(proposal, context);
        verify(f.flows).launch(eq(TENANT), eq(ORDER.buildSessionId()), matches("incident-initial-[a-f0-9]{64}"), matches("incident-initial-trace-[a-f0-9]{64}"));
    }

    @Test void denialUncertaintyOrAwaitingCannotLaunchOrReadResultAsAuthority() {
        for (var result : List.of(ActionExecutionResult.denied("DENIED", "denied"),
                ActionExecutionResult.manualReviewFailure("UNCERTAIN", "uncertain"), ActionExecutionResult.accepted("WAIT", Map.of()))) {
            var f = new Fixture(); f.result = result;
            assertThrows(IllegalStateException.class, () -> f.runner().run(new DefaultApplicationArguments()));
            verifyNoInteractions(f.ledger, f.flows);
        }
    }

    @Test void selectionFailureDoesNotSubmitAction() {
        var f = new Fixture(); when(f.intake.requireSelection(TENANT, KEY, INPUT, true)).thenThrow(new IllegalArgumentException("stale component"));
        assertThrows(IllegalArgumentException.class, () -> f.runner().run(new DefaultApplicationArguments()));
        assertTrue(f.proposals.isEmpty()); verifyNoInteractions(f.ledger, f.flows);
    }

    @Test void missingOrDifferentCanonicalProductDoesNotLaunch() {
        var f = new Fixture(); when(f.ledger.find(TENANT, ORDER.buildSessionId())).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> f.runner().run(new DefaultApplicationArguments())); verifyNoInteractions(f.flows);
        var other = new Fixture(); when(other.ledger.find(TENANT, ORDER.buildSessionId())).thenReturn(Optional.of(product(order(IncidentApplicationOrder.Variant.HISTORY))));
        assertThrows(IllegalStateException.class, () -> other.runner().run(new DefaultApplicationArguments())); verifyNoInteractions(other.flows);
    }

    @Test void progressedProductStaysWithDurableRecovery() {
        var f = new Fixture(); var accepted = product(ORDER);
        when(f.ledger.find(TENANT, ORDER.buildSessionId())).thenReturn(Optional.of(accepted.next(IncidentApplicationProduct.Status.BUILDING,
                null,null,null,null,null,null,"operation",null,null,NOW)));
        f.runner().run(new DefaultApplicationArguments()); verifyNoInteractions(f.flows);
    }

    @Test void retryHasFreshActionLifecycleButStableInitialFlowIdentity() {
        var f = new Fixture(); f.runner().run(new DefaultApplicationArguments()); f.runner().run(new DefaultApplicationArguments());
        assertNotEquals(f.contexts.getFirst().runId(), f.contexts.getLast().runId());
        assertEquals(f.proposals.getFirst().idempotencyKey(), f.proposals.getLast().idempotencyKey());
        var ids = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.flows,times(2)).launch(eq(TENANT),eq(ORDER.buildSessionId()),ids.capture(),anyString());
        assertEquals(ids.getAllValues().getFirst(),ids.getAllValues().getLast());
    }

    @Test void disabledEntryNeedsNoDependencies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(FactoryIncidentApplicationLocalOrderConfiguration.class); context.refresh();
            assertTrue(context.getBeansOfType(FactoryIncidentApplicationLocalOrderRunner.class).isEmpty());
        }
    }

    @Test void explicitEntryRequiresAllSelectionsAndEnabledLineWithoutRunningAtRefresh() {
        for (String missing : List.of("tenant-id","project-id","session-id","request-key","variant","deadline-at",
                "certification-id","source-hash","certification-manifest-ref","certification-manifest-hash","line-enabled","none")) {
            var f = new Fixture(); var props = properties(); props.remove(PREFIX + missing);
            if (missing.equals("line-enabled")) props.remove("factory.production.incident-application.enabled");
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("incident-host-fixture",props));
                context.registerBean(ActionRuntime.class, () -> f.runtime);
                context.registerBean(IncidentApplicationIntake.class, () -> f.intake);
                context.registerBean(IncidentApplicationLedger.class, () -> f.ledger);
                context.registerBean(FactoryProductLineFlowLauncher.class, () -> f.flows);
                context.register(FactoryIncidentApplicationLocalOrderConfiguration.class);
                if (missing.equals("none")) { context.refresh(); assertEquals(1,context.getBeansOfType(FactoryIncidentApplicationLocalOrderRunner.class).size()); }
                else assertThrows(RuntimeException.class,context::refresh,missing);
                assertTrue(f.proposals.isEmpty()); verifyNoInteractions(f.intake,f.flows);
            }
        }
    }

    private static IncidentApplicationOrder order(IncidentApplicationOrder.Variant variant) {
        return new IncidentApplicationOrder(new BuildSessionId("incident-host-session"),TENANT,new ProjectId("incident-host-project"),KEY,variant,COMPONENT,NOW.plusSeconds(3600));
    }
    private static IncidentApplicationProduct product(IncidentApplicationOrder order) {
        return IncidentApplicationProduct.accepted(new IncidentApplicationBuildWorkOrder(order,LOCK,LOCK,LOCK,LOCK,LOCK,"fixture-assembly"),NOW);
    }
    private static Map<String,Object> properties() {
        var props = new LinkedHashMap<String,Object>(); props.put(PREFIX+"enabled","true");
        props.put("factory.production.incident-application.enabled","true"); props.put(PREFIX+"tenant-id",TENANT.value());
        props.put(PREFIX+"project-id",ORDER.projectId().value()); props.put(PREFIX+"session-id",ORDER.buildSessionId().value());
        props.put(PREFIX+"request-key",KEY); props.put(PREFIX+"variant",ORDER.variant().name()); props.put(PREFIX+"deadline-at",ORDER.deadlineAt().toString());
        props.put(PREFIX+"certification-id",COMPONENT.certificationId().value()); props.put(PREFIX+"source-hash",COMPONENT.candidateHash().sha256());
        props.put(PREFIX+"certification-manifest-ref",LOCK.reference().value()); props.put(PREFIX+"certification-manifest-hash",LOCK.hash().sha256()); return props;
    }
    private static final class Fixture {
        final IncidentApplicationIntake intake = mock(IncidentApplicationIntake.class);
        final IncidentApplicationLedger ledger = mock(IncidentApplicationLedger.class);
        final FactoryProductLineFlowLauncher flows = mock(FactoryProductLineFlowLauncher.class);
        final List<ActionProposal> proposals = new ArrayList<>(); final List<ExecutionContext> contexts = new ArrayList<>();
        ActionExecutionResult result = ActionExecutionResult.succeeded(Map.of("untrusted-result","not-authority"));
        final ActionRuntime runtime = (proposal,context) -> { proposals.add(proposal); contexts.add(context); return result; };
        Fixture() { when(intake.requireSelection(TENANT,KEY,INPUT,true)).thenReturn(ORDER); when(ledger.find(TENANT,ORDER.buildSessionId())).thenReturn(Optional.of(product(ORDER))); }
        FactoryIncidentApplicationLocalOrderRunner runner() { return new FactoryIncidentApplicationLocalOrderRunner(runtime,intake,ledger,flows,TENANT,KEY,INPUT); }
    }
}
