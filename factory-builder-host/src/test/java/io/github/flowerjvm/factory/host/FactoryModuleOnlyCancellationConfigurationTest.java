package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.flow.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.application.work.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.flow.FlowId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Installed-line cancellation works without enabling a model adapter or claiming absent Worker effects stopped. */
class FactoryModuleOnlyCancellationConfigurationTest {
    private static final Instant NOW=Instant.parse("2026-09-13T00:00:00Z");
    @Test void codexDisabledInstalledLineGetsRealCancellationAndRecoveryBoundary() {
        var sessions=mock(BuildSessionRepository.class); var engine=mock(Engine.class); var flows=mock(FactoryFlowRegistry.class);
        var workers=mock(WorkerRunRepository.class); var requester=mock(IncidentApplicationCancellationRequester.class);
        when(requester.productLineId()).thenReturn(ProductLineId.INCIDENT_APPLICATION);
        var state=new AtomicReference<>(session(ProductLineId.INCIDENT_APPLICATION));
        when(sessions.find(any(),any())).thenAnswer(call->Optional.of(state.get()));
        when(sessions.compareAndSet(any(),any())).thenAnswer(call->state.compareAndSet(call.getArgument(0),call.getArgument(1)));
        when(requester.canRequest(any())).thenReturn(true);
        when(requester.request(any())).thenReturn(FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT);
        var flowId=FlowId.of(IncidentApplicationFlowFactory.FLOW_TYPE,state.get().buildSessionId().value());
        when(flows.flowId(any())).thenReturn(flowId);
        try(var context=context(true,false)) {
            context.registerBean(BuildSessionRepository.class,()->sessions); context.registerBean(Engine.class,()->engine);
            context.registerBean(FactoryFlowRegistry.class,()->flows); context.registerBean(WorkerRunRepository.class,()->workers);
            context.registerBean(Clock.class,()->Clock.fixed(NOW,ZoneOffset.UTC));
            context.registerBean(IncidentApplicationCancellationRequester.class,()->requester); context.refresh();
            assertEquals(1,context.getBeansOfType(FactoryFlowCancellationService.class).size());
            assertEquals(1,context.getBeansOfType(FactoryFlowCancellationRecovery.class).size());
            assertTrue(context.getBeansOfType(WorkerCancellationRequester.class).isEmpty());
            var current=state.get();
            assertEquals(FlowCancellationDisposition.CANCELLATION_REQUESTED,context.getBean(FactoryFlowCancellationService.class).cancel(current.tenantId(),current.buildSessionId()));
            assertEquals(BuildSessionStatus.CANCELLED,state.get().status());
            verify(requester).request(argThat(value->value.status()==BuildSessionStatus.CANCELLING && value.cancellationRequestedAt().isPresent()));
            verify(engine).cancel(flowId); verifyNoInteractions(workers);
        }
    }
    @Test void missingWorkerAdapterFailsClosedBeforeAgentSessionMutation() {
        var sessions=mock(BuildSessionRepository.class); var engine=mock(Engine.class); var flows=mock(FactoryFlowRegistry.class);
        var agent=session(ProductLineId.AGENT_PACK); when(sessions.find(agent.tenantId(),agent.buildSessionId())).thenReturn(Optional.of(agent));
        when(flows.flowId(agent)).thenReturn(FlowId.of("agent-pack",agent.buildSessionId().value()));
        var service=new FactoryFlowCancellationService(sessions,engine,flows,Clock.fixed(NOW,ZoneOffset.UTC),List.of());
        assertEquals(FlowCancellationDisposition.CONFLICT,service.cancel(agent.tenantId(),agent.buildSessionId()));
        verify(sessions,never()).compareAndSet(any(),any()); verifyNoInteractions(engine);
    }
    @Test void disabledIncidentOrEnabledCodexDoesNotInstallCompetingCancellationService() {
        for(var values:List.of(new boolean[]{false,false},new boolean[]{true,true})) {
            try(var context=context(values[0],values[1])) { context.refresh();
                assertTrue(context.getBeansOfType(FactoryFlowCancellationService.class).isEmpty());
                assertTrue(context.getBeansOfType(FactoryFlowCancellationRecovery.class).isEmpty());
            }
        }
    }
    @Test void moduleOnlyCancellationRecoveryUsesTheExistingIncidentControlLane() {
        var runner=mock(IncidentApplicationRunner.class); var recovery=mock(IncidentApplicationRecovery.class);
        var cancellations=mock(FactoryFlowCancellationRecovery.class); var executor=mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(executor).scheduleWithFixedDelay(any(),anyLong(),anyLong(),any());
        var pump=new IncidentApplicationPump(runner,recovery,executor,Duration.ofMillis(250),cancellations); pump.start();
        var callback=ArgumentCaptor.forClass(Runnable.class); verify(executor).scheduleWithFixedDelay(callback.capture(),eq(0L),eq(250L),eq(TimeUnit.MILLISECONDS));
        callback.getValue().run(); var order=inOrder(cancellations,recovery,runner);
        order.verify(cancellations).tickOnce(); order.verify(recovery).recoverBatch(32); order.verify(runner).drain(2); pump.stop();
    }
    private static AnnotationConfigApplicationContext context(boolean incident,boolean codex) {
        var context=new AnnotationConfigApplicationContext(); context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-cancellation-mode",
                Map.of("factory.production.incident-application.enabled",Boolean.toString(incident),"factory.worker.codex.enabled",Boolean.toString(codex))));
        context.register(FactoryModuleOnlyCancellationConfiguration.class); return context;
    }
    private static BuildSession session(ProductLineId line) {
        return new BuildSession(new BuildSessionId("module-only-session"),new TenantId("module-only-tenant"),new ProjectId("module-only-project"),line,"request","synthetic-owner",
                BuildSessionStatus.RUNNING,BuildSessionPhase.BUILD,new ArtifactReference("fixture:requirements"),new ContentHash("a".repeat(64)),Optional.empty(),Optional.empty(),
                Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),0,0,NOW,NOW.plusSeconds(600),Optional.empty(),Optional.empty(),Optional.empty(),0,NOW,NOW);
    }
}
