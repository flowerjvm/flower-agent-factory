package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Host bean/routing smoke only, with no real producer, model, Docker process or order. */
class FactoryIncidentApplicationConfigurationTest {
    @TempDir Path directory;
    @Test void disabledLineDoesNotRequireEquipmentOrCreateActions() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(FactoryIncidentApplicationConfiguration.class);context.refresh();
            assertTrue(context.getBeansOfType(IncidentApplicationActions.class).isEmpty());
            assertTrue(context.getBeansOfType(IncidentApplicationReviewRenewalAction.class).isEmpty());
            assertTrue(context.getBeansOfType(IncidentApplicationProductionTool.class).isEmpty());
        }
    }
    @Test void enabledLineWiresRegisteredControlsWithoutAnyImplicitOrderOrEquipmentExecution() throws Exception {
        var dataSource=new JdbcDataSource();dataSource.setURL("jdbc:h2:mem:incident_host_wiring_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        var components=mock(CertifiedAgentComponentReadGate.class);var certifications=mock(CertificationRepository.class);
        var flows=mock(FactoryProductLineFlowLauncher.class);
        try(var context=new AnnotationConfigApplicationContext()) {
            var properties=new HashMap<String,Object>();String prefix="factory.production.incident-application.";
            properties.put(prefix+"enabled","true");properties.put(prefix+"catalog-root",directory.resolve("absent-catalog").toString());
            properties.put(prefix+"maven-repository",directory.resolve("absent-repository").toString());
            properties.put(prefix+"workspace-root",directory.resolve("absent-workspace").toString());properties.put(prefix+"docker-executable","equipment-must-not-run");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-incident-wiring",properties));
            context.registerBean(DataSource.class,()->dataSource);context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
            context.registerBean("certifiedComponentResolver",CertifiedAgentComponentReadGate.class,()->components);
            context.registerBean(CertificationRepository.class,()->certifications);context.registerBean(FactoryProductLineFlowLauncher.class,()->flows);
            context.register(FactoryActionRuntimeConfiguration.class,FactoryIncidentApplicationConfiguration.class);context.refresh();
            assertEquals(1,context.getBeansOfType(IncidentApplicationActions.class).size());
            assertEquals(1,context.getBeansOfType(IncidentApplicationReviewRenewalAction.class).size());
            assertEquals(1,context.getBeansOfType(IncidentApplicationFlowFactory.class).size());
            assertEquals(1,context.getBeansOfType(IncidentApplicationCancellationRequester.class).size());
            var input=Map.<String,Object>of("buildSessionId","absent-order","projectId","fixture-project","variant","BASIC",
                    "certificationId","absent-certification","candidateHash","a".repeat(64),"certificationManifestRef","fixture:cert",
                    "certificationManifestHash","b".repeat(64),"deadlineAt",Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
            var result=context.getBean(IncidentApplicationLauncher.class).submit(new TenantId("fixture-tenant"),"fixture-request",input);
            assertEquals(ActionExecutionStatus.DENIED,result.status());
            var renewalInput = Map.<String,Object>of("buildSessionId", "absent-order", "projectId", "fixture-project",
                    "expectedVersion", 4, "previousDecisionPointId", "absent-point", "subjectHash", "a".repeat(64),
                    "deadlineAt", "2026-09-16T00:00:00Z");
            String principal = "windows-sid:S-1-5-21-100-200-300-1001";
            var renewalProposal = ActionProposal.builder(IncidentApplicationReviewRenewalAction.ID)
                    .proposalId("synthetic-renewal-proposal").requestChannel(ActionRequestChannel.CLI).proposerType(ActionProposerType.USER)
                    .requesterId(principal).input(renewalInput)
                    .idempotencyKey(IncidentApplicationReviewRenewalAction.key(new TenantId("fixture-tenant"), renewalInput)).build();
            var renewalContext = new ExecutionContext("fixture-tenant", principal, UUID.randomUUID().toString(), "synthetic-renewal-trace",
                    Map.of("actor.permissions", Set.of(IncidentApplicationReviewRenewalAction.ID), "actor.authoritySnapshotRef", "fixture:protected-renewal-grant",
                            "resource.type", IncidentApplicationActions.RESOURCE, "resource.id", "absent-order", "resource.projectId", "fixture-project"));
            var renewalResult = context.getBean(DefaultActionRuntime.class).handle(renewalProposal, renewalContext);
            assertEquals(ActionExecutionStatus.DENIED, renewalResult.status());
            assertEquals("POLICY_DENIED", renewalResult.code());
            assertEquals("INCIDENT_APPLICATION_RENEWAL_NOT_AUTHORIZED_OR_STALE", renewalResult.message());
            assertTrue(context.getBean(IncidentApplicationLedger.class).active(32).isEmpty());
            verifyNoInteractions(components,flows);
            assertFalse(Files.exists(directory.resolve("absent-workspace")));
        }
    }
}
