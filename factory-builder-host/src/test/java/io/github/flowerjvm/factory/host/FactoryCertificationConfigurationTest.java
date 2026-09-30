package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.certification.ActionBackedAgentPackCertificationLauncher;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchRunner;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceService;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.action.CertificationIssuePolicyGate;
import io.github.flowerjvm.factory.application.action.CertificationIssuePreExecutionGuard;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestService;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequester;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceTransaction;
import io.github.flowerjvm.factory.application.certification.CertificationRequestTransaction;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.AgentPackCertificationFlowFactory;
import io.github.flowerjvm.factory.application.flow.FactoryContinuationFlow;
import io.github.flowerjvm.factory.application.flow.FactoryCertificationContinuationRecovery;
import io.github.flowerjvm.factory.application.flow.FactoryFlowRegistry;
import io.github.flowerjvm.factory.application.flow.LedgerBackedAgentPackCertificationFlowCoordinator;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCertificationIssuanceTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCertificationRequestTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.FactoryDatabaseMigrations;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcBuildSessionRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpointStore;
import io.github.flowerjvm.flower.core.recovery.FlowFactoryRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class FactoryCertificationConfigurationTest {
    @Test
    void fullHostContextSeparatesTickReceiptsFromFinalIssuanceAndPublicFullReadGate() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, FactoryCertificationConfigurationTest::dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(
                    FactoryActionRuntimeConfiguration.class,
                    FactoryWorkerTransportConfiguration.class,
                    FactoryCertificationConfiguration.class,
                    FactoryFlowerConfiguration.class);
            context.refresh();

            JdbcCertificationRequestTransaction requestTransaction = assertInstanceOf(
                    JdbcCertificationRequestTransaction.class,
                    context.getBean(CertificationRequestTransaction.class));
            assertSame(
                    context.getBean(AgentPackCertificationPolicyCatalog.class),
                    ReflectionTestUtils.getField(requestTransaction, "policy"));
            assertInstanceOf(
                    JdbcCertificationIssuanceTransaction.class,
                    context.getBean(CertificationIssuanceTransaction.class));
            AgentPackCertificationRequestService requestService =
                    context.getBean(AgentPackCertificationRequestService.class);
            var policies = context.getBean(AgentPackCertificationPolicyCatalog.class);
            assertSame(policies, ReflectionTestUtils.getField(requestService, "policies"));
            assertSame(policies, ReflectionTestUtils.getField(
                    context.getBean(CertificationIssuePolicyGate.class), "trustedPolicy"));
            assertSame(policies, ReflectionTestUtils.getField(
                    context.getBean(CertificationIssuePreExecutionGuard.class), "trustedPolicy"));
            assertSame(policies, ReflectionTestUtils.getField(
                    context.getBean(CertificationDispatchRunner.class), "trustedPolicy"));
            Object fastEvidence = context.getBean("verificationEvidenceValidator");
            Object fullEvidence = context.getBean("fullVerificationEvidenceValidator");
            assertSame(fastEvidence, ReflectionTestUtils.getField(requestService, "verificationEvidence"));
            assertSame(fastEvidence, ReflectionTestUtils.getField(
                    context.getBean(CertificationIssuePreExecutionGuard.class), "verificationEvidence"));
            assertSame(fullEvidence, ReflectionTestUtils.getField(
                    context.getBean(CertificationIssuanceService.class), "fullEvidence"));
            Object fullComponents = context.getBean("certifiedComponentResolver");
            Object tickComponents = context.getBean("tickComponentReadGate");
            assertSame(fullEvidence, ReflectionTestUtils.getField(fullComponents, "verificationEvidence"));
            assertSame(fastEvidence, ReflectionTestUtils.getField(tickComponents, "verificationEvidence"));
            assertSame(fullComponents, context.getBean(CertifiedAgentComponentReadGate.class),
                    "public unqualified component reads must retain the complete source integrity gate");
            assertSame(requestService, context.getBean(AgentPackCertificationRequester.class));
            context.getBean(ActionBackedAgentPackCertificationLauncher.class);
            context.getBean(LedgerBackedAgentPackCertificationFlowCoordinator.class);
            AgentPackCertificationFlowFactory continuation =
                    context.getBean(AgentPackCertificationFlowFactory.class);
            assertSame(continuation, context.getBean(FactoryContinuationFlow.class));
            assertTrue(context.getBean(FlowFactoryRegistry.class)
                    .contains(AgentPackCertificationFlowFactory.FLOW_TYPE));
            context.getBean(FactoryFlowRegistry.class);
            context.getBean(FactoryCertificationContinuationRecovery.class);
            assertTrue(context.getBean(CertificationDispatchPump.class).isRunning());
            assertTrue(context.getBean(CertificationContinuationPump.class).isRunning());
        }
    }

    @Test
    void startupScanReregistersCertifyingRowLostBeforeFirstCheckpoint() {
        DataSource dataSource = dataSource();
        FactoryDatabaseMigrations.configured(dataSource).migrate();
        BuildSession certifying = certifyingSession();
        new JdbcBuildSessionRepository(dataSource).create(certifying);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(
                    FactoryActionRuntimeConfiguration.class,
                    FactoryWorkerTransportConfiguration.class,
                    FactoryCertificationConfiguration.class,
                    FactoryFlowerConfiguration.class);
            context.refresh();

            Engine engine = context.getBean(Engine.class);
            engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER).tickOnce();
            var snapshots = engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER).snapshot();
            FlowId expectedFlowId = FlowId.of(
                    AgentPackCertificationFlowFactory.FLOW_TYPE,
                    certifying.buildSessionId().value());

            assertEquals(1, snapshots.size());
            assertEquals(expectedFlowId, snapshots.getFirst().flowId());
            assertEquals(
                    AgentPackCertificationFlowFactory.ISSUE_CERTIFICATION,
                    snapshots.getFirst().currentStepId());
            assertEquals(certifying.tenantId().value(),
                    snapshots.getFirst().executionContext().tenantIdOrNull());
            assertTrue(snapshots.getFirst().executionContext().runIdOrNull()
                    .startsWith("certification-recovery-run-"));
            assertTrue(context.getBean(FlowCheckpointStore.class).find(expectedFlowId).isPresent());
            engine.stop();
        }
    }

    private static BuildSession certifyingSession() {
        Instant now = Instant.now();
        return new BuildSession(
                new BuildSessionId("host-certification-recovery"),
                new TenantId("tenant-host-certification"),
                new ProjectId("project-host-certification"),
                ProductLineId.AGENT_PACK,
                "request-host-certification",
                "principal-host-certification",
                BuildSessionStatus.CERTIFYING,
                BuildSessionPhase.CERTIFY,
                new ArtifactReference("artifact:host-certification-requirements"),
                new ContentHash("a".repeat(64)),
                Optional.of("manager"),
                Optional.of("coding"),
                Optional.of(new ArtifactReference("artifact:host-certification-blueprint")),
                Optional.of(new CandidateId("candidate-host-certification")),
                Optional.of(new ContentHash("b".repeat(64))),
                Optional.empty(),
                0,
                3,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                8,
                now.minusSeconds(60),
                now.minusSeconds(1));
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
