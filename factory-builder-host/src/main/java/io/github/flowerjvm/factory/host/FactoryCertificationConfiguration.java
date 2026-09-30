package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.CertificationIssueActionExecutor;
import io.github.flowerjvm.factory.application.action.CertificationIssueActionValidator;
import io.github.flowerjvm.factory.application.action.CertificationIssuePolicyGate;
import io.github.flowerjvm.factory.application.action.CertificationIssuePreExecutionGuard;
import io.github.flowerjvm.factory.application.action.CertificationIssueVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestService;
import io.github.flowerjvm.factory.application.certification.ActionBackedAgentPackCertificationLauncher;
import io.github.flowerjvm.factory.application.certification.ActionRuntimeCertificationEvidenceOwner;
import io.github.flowerjvm.factory.application.certification.CertificationArtifactCodec;
import io.github.flowerjvm.factory.application.certification.CertificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchRunner;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchTransaction;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceService;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceTransaction;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertificationRequestTransaction;
import io.github.flowerjvm.factory.application.certification.CertifiedComponentResolver;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.flow.AgentPackCertificationFlowFactory;
import io.github.flowerjvm.factory.application.flow.FactoryCertificationContinuationRecovery;
import io.github.flowerjvm.factory.application.flow.LedgerBackedAgentPackCertificationFlowCoordinator;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCertificationDispatchIntentRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCertificationDispatchTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCertificationIssuanceTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCertificationRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCertificationRequestTransaction;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Concrete PR6-A2 wiring for the Agent Pack certification continuation. */
@Configuration(proxyBeanMethods = false)
public class FactoryCertificationConfiguration {
    static final String FACTORY_CERTIFICATION_VERSION = "0.1.0-internal.1";
    static final String FLOWER_VERSION = "0.1.3";
    static final String ACTION_RUNTIME_VERSION = "0.3.3";

    @Bean
    CertificationArtifactCodec certificationArtifactCodec(ObjectMapper objectMapper) {
        return new JacksonCertificationArtifactCodec(objectMapper);
    }

    @Bean
    CertificationRepository certificationRepository(
            DataSource dataSource,
            CertificationArtifactCodec codec,
            WorkerProtocolArtifactDecoder workerDecoder) {
        return new JdbcCertificationRepository(dataSource, codec, workerDecoder);
    }

    @Bean
    AgentPackCertificationPolicyCatalog agentPackCertificationPolicyCatalog(
            WorkOrderRepository workOrders, WorkerProtocolArtifacts workerArtifacts) {
        return AgentPackCertificationPolicyCatalog.production(
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                FACTORY_CERTIFICATION_VERSION,
                FLOWER_VERSION,
                ACTION_RUNTIME_VERSION,
                workOrders,
                workerArtifacts);
    }

    @Bean
    CertificationRequestTransaction certificationRequestTransaction(
            DataSource dataSource,
            AgentPackCertificationPolicyCatalog policy,
            CertificationArtifactCodec codec,
            WorkerProtocolArtifactDecoder workerDecoder) {
        return new JdbcCertificationRequestTransaction(dataSource, policy, codec, workerDecoder);
    }

    @Bean
    AgentPackCertificationRequestService agentPackCertificationRequestService(
            BuildSessionRepository buildSessions,
            CandidateVersionRepository candidates,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts workerArtifacts,
            VerificationRunRepository verifications,
            @Qualifier("verificationEvidenceValidator") VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            VerificationDispatchIntentRepository verificationIntents,
            ArtifactStore artifacts,
            CertificationArtifactCodec codec,
            CertificationRequestTransaction transaction,
            AgentPackCertificationPolicyCatalog policy,
            Clock factoryClock) {
        return new AgentPackCertificationRequestService(
                buildSessions,
                candidates,
                workOrders,
                workerArtifacts,
                verifications,
                verificationEvidence,
                verificationOwner,
                verificationIntents,
                artifacts,
                codec,
                transaction,
                policy,
                factoryClock);
    }

    @Bean
    CertificationDispatchIntentRepository certificationDispatchIntentRepository(
            DataSource dataSource) {
        return new JdbcCertificationDispatchIntentRepository(dataSource);
    }

    @Bean
    CertificationDispatchTransaction certificationDispatchTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcCertificationDispatchTransaction(dataSource, objectMapper);
    }

    @Bean
    CertificationActionEvidenceOwner certificationActionEvidenceOwner(
            CertificationDispatchIntentRepository intents,
            RunStore actionRunStore) {
        return new ActionRuntimeCertificationEvidenceOwner(intents, actionRunStore);
    }

    @Bean
    ActionBackedAgentPackCertificationLauncher actionBackedAgentPackCertificationLauncher(
            DefaultActionRuntime factoryActionRuntime) {
        return new ActionBackedAgentPackCertificationLauncher(factoryActionRuntime);
    }

    @Bean
    LedgerBackedAgentPackCertificationFlowCoordinator agentPackCertificationFlowCoordinator(
            BuildSessionRepository buildSessions,
            CertificationRepository certifications,
            CertificationDispatchIntentRepository intents,
            AgentPackCertificationRequestService requester,
            ActionBackedAgentPackCertificationLauncher launcher,
            CertificationActionEvidenceOwner certificationOwner,
            Clock factoryClock) {
        return new LedgerBackedAgentPackCertificationFlowCoordinator(
                buildSessions,
                certifications,
                intents,
                requester,
                launcher,
                certificationOwner,
                factoryClock);
    }

    @Bean
    AgentPackCertificationFlowFactory agentPackCertificationFlowFactory(
            LedgerBackedAgentPackCertificationFlowCoordinator coordinator) {
        return new AgentPackCertificationFlowFactory(coordinator);
    }

    @Bean
    @Primary
    CertifiedComponentResolver certifiedComponentResolver(
            CertificationRepository certifications,
            CertificationActionEvidenceOwner certificationOwner,
            BuildSessionRepository buildSessions,
            WorkOrderRepository workOrders,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            VerificationDispatchIntentRepository verificationIntents,
            ArtifactStore artifacts,
            WorkerProtocolArtifacts workerArtifacts,
            @Qualifier("fullVerificationEvidenceValidator") VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            CertificationArtifactCodec codec,
            Clock factoryClock) {
        return new CertifiedComponentResolver(
                certifications,
                certificationOwner,
                buildSessions,
                workOrders,
                candidates,
                verifications,
                verificationIntents,
                artifacts,
                workerArtifacts,
                verificationEvidence,
                verificationOwner,
                codec,
                factoryClock);
    }

    /** Only internal Flower observers may consume the bounded receipt-backed projection. */
    @Bean
    CertifiedAgentComponentReadGate tickComponentReadGate(
            CertificationRepository certifications, CertificationActionEvidenceOwner certificationOwner,
            BuildSessionRepository buildSessions, WorkOrderRepository workOrders,
            CandidateVersionRepository candidates, VerificationRunRepository verifications,
            VerificationDispatchIntentRepository verificationIntents, ArtifactStore artifacts,
            WorkerProtocolArtifacts workerArtifacts,
            @Qualifier("verificationEvidenceValidator") VerificationEvidenceValidator tickEvidence,
            VerificationActionEvidenceOwner verificationOwner, CertificationArtifactCodec codec, Clock factoryClock) {
        return new CertifiedComponentResolver(certifications, certificationOwner, buildSessions, workOrders,
                candidates, verifications, verificationIntents, artifacts, workerArtifacts,
                tickEvidence, verificationOwner, codec, factoryClock);
    }

    @Bean
    CertificationIssueActionValidator certificationIssueActionValidator() {
        return new CertificationIssueActionValidator();
    }

    @Bean
    CertificationIssuePolicyGate certificationIssuePolicyGate(
            CertificationRepository certifications,
            AgentPackCertificationPolicyCatalog policy) {
        return new CertificationIssuePolicyGate(certifications, policy);
    }

    @Bean
    CertificationIssueVisibilityScopeResolver certificationIssueVisibilityScopeResolver(
            CertificationRepository certifications) {
        return new CertificationIssueVisibilityScopeResolver(certifications);
    }

    @Bean
    CertificationIssuePreExecutionGuard certificationIssuePreExecutionGuard(
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts workerArtifacts,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            @Qualifier("verificationEvidenceValidator") VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            VerificationDispatchIntentRepository verificationIntents,
            AgentPackCertificationPolicyCatalog policy,
            Clock factoryClock) {
        return new CertificationIssuePreExecutionGuard(
                certifications,
                buildSessions,
                workOrders,
                workerArtifacts,
                candidates,
                verifications,
                verificationEvidence,
                verificationOwner,
                verificationIntents,
                policy,
                factoryClock);
    }

    @Bean
    CertificationIssueActionExecutor certificationIssueActionExecutor(
            CertificationDispatchTransaction transaction,
            Clock factoryClock) {
        return new CertificationIssueActionExecutor(transaction, factoryClock);
    }

    @Bean
    CertificationIssuanceTransaction certificationIssuanceTransaction(
            DataSource dataSource,
            CertificationArtifactCodec codec,
            WorkerProtocolArtifactDecoder workerDecoder) {
        return new JdbcCertificationIssuanceTransaction(dataSource, codec, workerDecoder);
    }

    @Bean
    CertificationIssuanceService certificationIssuanceService(
            CertificationRepository certifications,
            CertificationIssuanceTransaction issuanceTransaction,
            ArtifactStore artifacts,
            CertificationArtifactCodec codec,
            Clock factoryClock, VerificationRunRepository verificationRuns,
            @Qualifier("fullVerificationEvidenceValidator") VerificationEvidenceValidator fullEvidence,
            VerificationActionEvidenceOwner verificationOwner) {
        return new CertificationIssuanceService(
                certifications, issuanceTransaction, artifacts, codec, factoryClock,
                verificationRuns, fullEvidence, verificationOwner);
    }

    @Bean
    CertificationDispatchRunner certificationDispatchRunner(
            CertificationDispatchIntentRepository intents,
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            AgentPackCertificationPolicyCatalog policy,
            CertificationIssuanceService issuance,
            DefaultActionRuntime factoryActionRuntime,
            RunStore actionRunStore,
            Clock factoryClock) {
        return new CertificationDispatchRunner(
                intents,
                certifications,
                buildSessions,
                policy,
                issuance,
                factoryActionRuntime,
                actionRunStore,
                factoryClock);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService certificationExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "factory-certification-dispatch");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    CertificationDispatchPump certificationDispatchPump(
            CertificationDispatchRunner runner,
            @Qualifier("certificationExecutor") ScheduledExecutorService executor) {
        return new CertificationDispatchPump(runner, executor, Duration.ofMillis(250));
    }

    @Bean
    CertificationContinuationPump certificationContinuationPump(
            FactoryCertificationContinuationRecovery recovery,
            @Qualifier("certificationExecutor") ScheduledExecutorService executor) {
        return new CertificationContinuationPump(recovery, executor, Duration.ofSeconds(1));
    }
}
