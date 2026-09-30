package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationActions;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationReviewRenewalAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueActionExecutor;
import io.github.flowerjvm.factory.application.action.CertificationIssueActionValidator;
import io.github.flowerjvm.factory.application.action.CertificationIssuePolicyGate;
import io.github.flowerjvm.factory.application.action.CertificationIssuePreExecutionGuard;
import io.github.flowerjvm.factory.application.action.CertificationIssueVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.action.FactoryActionInputValidatorRouter;
import io.github.flowerjvm.factory.application.action.FactoryActionPolicyGateRouter;
import io.github.flowerjvm.factory.application.action.FactoryDuplicateVisibilityScopeRouter;
import io.github.flowerjvm.factory.application.action.FactoryPreExecutionGuardRouter;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionValidator;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleasePolicyGate;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleasePreExecutionGuard;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunActionExecutor;
import io.github.flowerjvm.factory.application.action.VerificationRunActionValidator;
import io.github.flowerjvm.factory.application.action.VerificationRunPolicyGate;
import io.github.flowerjvm.factory.application.action.VerificationRunPreExecutionGuard;
import io.github.flowerjvm.factory.application.action.VerificationRunVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchActionExecutor;
import io.github.flowerjvm.factory.application.action.WorkerDispatchActionValidator;
import io.github.flowerjvm.factory.application.action.WorkerDispatchPolicyGate;
import io.github.flowerjvm.factory.application.action.WorkerDispatchPreExecutionGuard;
import io.github.flowerjvm.factory.application.action.WorkerDispatchTransaction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionRepository;
import io.github.flowerjvm.factory.application.decision.DecisionRecordAction;
import io.github.flowerjvm.factory.application.decision.DecisionRecordActionExecutor;
import io.github.flowerjvm.factory.application.decision.DecisionRecordActionValidator;
import io.github.flowerjvm.factory.application.decision.DecisionRecordPolicyGate;
import io.github.flowerjvm.factory.application.decision.DecisionRecordPreExecutionGuard;
import io.github.flowerjvm.factory.application.decision.DecisionRecordVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingService;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingTransaction;
import io.github.flowerjvm.factory.application.decision.DecisionSubjectAuthority;
import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowCoordinator;
import io.github.flowerjvm.factory.application.flow.LedgerBackedCreateCustomerAgentFlowCoordinator;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.production.AgentPackProductionAction;
import io.github.flowerjvm.factory.application.production.AgentPackProductionActionExecutor;
import io.github.flowerjvm.factory.application.production.AgentPackProductionActionValidator;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPolicyGate;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPreExecutionGuard;
import io.github.flowerjvm.factory.application.production.AgentPackProductionVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPhasePreparation;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeAction;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeActionExecutor;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeActionValidator;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakePolicyGate;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakePreExecutionGuard;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeAction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeActionExecutor;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeActionValidator;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakePolicyGate;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakePreExecutionGuard;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationReviewEvidenceRecorder;
import io.github.flowerjvm.factory.infrastructure.verification.ArtifactVerificationReviewEvidence;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.AgentPackGenerationVerificationProfiles;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactEncoder;
import io.github.flowerjvm.factory.application.verification.VerificationExecutionService;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.ActionRuntimeVerificationEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationActionDuplicateOwnerLookup;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchRunner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchTransaction;
import io.github.flowerjvm.factory.application.verification.VerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.VerificationRunRequestTransaction;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.application.work.WorkerCompletionService;
import io.github.flowerjvm.factory.application.work.WorkerCancellationTransaction;
import io.github.flowerjvm.factory.application.work.WorkerActionRunRecovery;
import io.github.flowerjvm.factory.application.work.WorkerActionDuplicateOwnerLookup;
import io.github.flowerjvm.factory.infrastructure.persistence.FactoryDatabaseMigrations;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcActionAuditSink;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcArtifactStore;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcBuildSessionRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCandidateVersionRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDecisionPointRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDecisionRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDecisionRecordingTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDispatchOutboxRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcVerificationRunRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcVerificationRunRequestTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcVerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcVerificationDispatchTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcVerificationActionDuplicateOwnerLookup;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerDispatchTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerCancellationTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerRunRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerActionDuplicateOwnerLookup;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkOrderRepository;
import io.github.flowerjvm.factory.infrastructure.verification.DockerCliVerificationSandbox;
import io.github.flowerjvm.factory.infrastructure.verification.ArtifactVerificationEvidenceValidator;
import io.github.flowerjvm.factory.infrastructure.verification.IndependentMavenVerifier;
import io.github.flowerjvm.factory.infrastructure.verification.Pr4MavenToolchainInstaller;
import io.github.flowerjvm.factory.infrastructure.verification.VerificationSandbox;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.verification.Verifier;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutor;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;

/** Production wiring for durable Factory ledgers and every installed governed Action. */
@Configuration(proxyBeanMethods = false)
@Import(FactoryDecisionConfiguration.class)
public class FactoryActionRuntimeConfiguration {
    static final ContentHash PR4_FIXTURE_SET_HASH = new ContentHash(
            "6a2ae8440eb6883de2abdd91256219344ee49b70cc1275668e8a9a0f7053ecf8");
    static final ArtifactReference PR4_FIXTURE_SET_REF = new ArtifactReference(
            "factory-verification/pr4/fixture-set/" + PR4_FIXTURE_SET_HASH.sha256());

    @Bean(initMethod = "migrate")
    Flyway factoryFlyway(DataSource dataSource) {
        return FactoryDatabaseMigrations.configured(dataSource);
    }

    @Bean
    Clock factoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    BuildSessionRepository buildSessionRepository(DataSource dataSource) {
        return new JdbcBuildSessionRepository(dataSource);
    }

    @Bean
    WorkOrderRepository workOrderRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkOrderRepository(dataSource, objectMapper);
    }

    @Bean
    WorkerRunRepository workerRunRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkerRunRepository(dataSource, objectMapper);
    }

    @Bean
    DecisionPointRepository decisionPointRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcDecisionPointRepository(dataSource, objectMapper);
    }

    @Bean
    DecisionRepository decisionRepository(DataSource dataSource) {
        return new JdbcDecisionRepository(dataSource);
    }

    @Bean
    DecisionRecordingTransaction decisionRecordingTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcDecisionRecordingTransaction(dataSource, objectMapper);
    }

    @Bean
    DecisionRecordingService decisionRecordingService(
            BuildSessionRepository buildSessions,
            DecisionPointRepository decisionPoints,
            DecisionRepository decisions,
            DecisionRecordingTransaction transaction,
            @Qualifier("factoryDecisionSubjectAuthorities") List<DecisionSubjectAuthority> authorities) {
        return new DecisionRecordingService(
                buildSessions, decisionPoints, decisions, transaction, authorities);
    }

    @Bean
    CandidateVersionRepository candidateVersionRepository(DataSource dataSource) {
        return new JdbcCandidateVersionRepository(dataSource);
    }

    @Bean
    VerificationRunRepository verificationRunRepository(DataSource dataSource) {
        return new JdbcVerificationRunRepository(dataSource);
    }

    @Bean
    ArtifactStore artifactStore(DataSource dataSource, Clock factoryClock) {
        return new JdbcArtifactStore(dataSource, factoryClock);
    }

    @Bean
    VerificationRunRequestTransaction verificationRunRequestTransaction(DataSource dataSource) {
        return new JdbcVerificationRunRequestTransaction(dataSource);
    }

    @Bean
    VerificationDispatchIntentRepository verificationDispatchIntentRepository(DataSource dataSource) {
        return new JdbcVerificationDispatchIntentRepository(dataSource);
    }

    @Bean
    VerificationDispatchTransaction verificationDispatchTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcVerificationDispatchTransaction(dataSource, objectMapper);
    }

    @Bean
    VerificationSandbox verificationSandbox() {
        return new DockerCliVerificationSandbox();
    }

    @Bean
    Pr4MavenToolchainInstaller pr4MavenToolchainInstaller(
            ArtifactStore artifactStore, ObjectMapper objectMapper) {
        return new Pr4MavenToolchainInstaller(
                artifactStore, objectMapper, Pr4MavenToolchainInstaller.configuredCache());
    }

    @Bean
    Verifier independentVerifier(
            ArtifactStore artifactStore,
            ObjectMapper objectMapper,
            VerificationSandbox verificationSandbox,
            Pr4MavenToolchainInstaller toolchainInstaller) {
        return new IndependentMavenVerifier(
                artifactStore,
                objectMapper,
                verificationSandbox,
                Path.of(System.getProperty("java.io.tmpdir"), "flower-agent-factory", "verification-workspaces"),
                toolchainInstaller);
    }

    @Bean
    WorkerProtocolArtifactDecoder workerProtocolArtifactDecoder(ObjectMapper objectMapper) {
        return new JacksonWorkerProtocolArtifactDecoder(objectMapper);
    }

    @Bean
    JacksonWorkerProtocolArtifactEncoder workerProtocolArtifactEncoder(ObjectMapper objectMapper) {
        return new JacksonWorkerProtocolArtifactEncoder(objectMapper);
    }

    @Bean
    WorkerProtocolArtifacts workerProtocolArtifacts(
            ArtifactStore artifactStore, WorkerProtocolArtifactDecoder decoder) {
        return new WorkerProtocolArtifacts(artifactStore, decoder);
    }

    @Bean
    AgentPackGenerationVerificationProfiles agentPackGenerationVerificationProfiles(
            WorkOrderRepository workOrders, WorkerProtocolArtifacts artifacts, CandidateVersionRepository candidates) {
        return new AgentPackGenerationVerificationProfiles(workOrders, artifacts, candidates);
    }

    @Bean
    VerificationExecutionService verificationExecutionService(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            ArtifactStore artifactStore,
            Verifier independentVerifier,
            Clock factoryClock,
            AgentPackGenerationVerificationProfiles generationProfiles) {
        return new VerificationExecutionService(
                verificationRuns, candidates, artifactStore, independentVerifier, factoryClock,
                PR4_FIXTURE_SET_REF, generationProfiles);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService verificationExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "factory-independent-verifier");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Bean
    VerificationRunActionExecutor verificationRunActionExecutor(
            VerificationDispatchTransaction dispatchTransaction,
            Clock factoryClock) {
        return new VerificationRunActionExecutor(dispatchTransaction, factoryClock);
    }

    @Bean
    VerificationDispatchRunner verificationDispatchRunner(
            VerificationDispatchIntentRepository intents,
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            VerificationExecutionService executionService,
            DefaultActionRuntime factoryActionRuntime,
            RunStore actionRunStore,
            Clock factoryClock,
            VerificationReviewEvidenceRecorder reviewEvidence) {
        return new VerificationDispatchRunner(
                intents, verificationRuns, candidates, executionService,
                factoryActionRuntime, actionRunStore, factoryClock, reviewEvidence);
    }

    @Bean
    VerificationDispatchPump verificationDispatchPump(
            VerificationDispatchRunner runner,
            @Qualifier("verificationExecutor") ScheduledExecutorService verificationExecutor) {
        return new VerificationDispatchPump(runner, verificationExecutor, Duration.ofMillis(250));
    }

    @Bean
    VerificationActionEvidenceOwner verificationActionEvidenceOwner(
            VerificationDispatchIntentRepository intents,
            RunStore actionRunStore,
            CandidateVersionRepository candidates,
            VerificationActionDuplicateOwnerLookup duplicateOwners) {
        return new ActionRuntimeVerificationEvidenceOwner(
                intents, actionRunStore, candidates, duplicateOwners);
    }

    @Bean
    VerificationActionDuplicateOwnerLookup verificationActionDuplicateOwnerLookup(DataSource dataSource) {
        return new JdbcVerificationActionDuplicateOwnerLookup(dataSource);
    }

    @Bean
    VerificationRunLauncher verificationRunLauncher(
            VerificationRunRequestTransaction requestTransaction,
            ActionRuntime factoryActionRuntime,
            AgentPackGenerationVerificationProfiles generationProfiles) {
        return new ActionBackedVerificationRunLauncher(
                requestTransaction, factoryActionRuntime, PR4_FIXTURE_SET_HASH, generationProfiles);
    }

    @Bean
    VerificationEvidenceValidator fullVerificationEvidenceValidator(
            ArtifactStore artifactStore,
            CandidateVersionRepository candidates,
            ObjectMapper objectMapper) {
        return new ArtifactVerificationEvidenceValidator(
                artifactStore, candidates, objectMapper,
                PR4_FIXTURE_SET_REF, PR4_FIXTURE_SET_HASH,
                Pr4MavenToolchainInstaller.EXPECTED_REFERENCE,
                Pr4MavenToolchainInstaller.EXPECTED_HASH);
    }

    @Bean
    @Primary
    ArtifactVerificationReviewEvidence verificationEvidenceValidator(
            ArtifactStore artifacts, BuildSessionRepository sessions, CandidateVersionRepository candidates,
            VerificationRunRepository verifications, VerificationDispatchIntentRepository intents,
            RunStore actionRunStore,
            @Qualifier("fullVerificationEvidenceValidator") VerificationEvidenceValidator fullValidator,
            ObjectMapper mapper, Clock factoryClock) {
        // New production orders observe a canonical off-tick receipt. Historical orders retain
        // their original full readback behavior; final issuance/release still selects the full gate.
        return new ArtifactVerificationReviewEvidence(artifacts, sessions, candidates, verifications,
                intents, actionRunStore, fullValidator, mapper, factoryClock);
    }

    @Bean
    CreateCustomerAgentFlowCoordinator createCustomerAgentFlowCoordinator(
            BuildSessionRepository buildSessions,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            VerificationRunLauncher verificationLauncher,
            VerificationEvidenceValidator verificationEvidenceValidator,
            VerificationActionEvidenceOwner verificationActionEvidenceOwner,
            DecisionPointRepository decisions,
            WorkerActionRunRecovery actionRunRecovery,
            ActionRuntime factoryActionRuntime,
            Clock factoryClock,
            ObjectProvider<AgentPackProductionPhasePreparation> productionPreparation) {
        return new LedgerBackedCreateCustomerAgentFlowCoordinator(
                buildSessions,
                workOrders,
                workerRuns,
                candidates,
                verifications,
                verificationLauncher,
                verificationEvidenceValidator,
                verificationActionEvidenceOwner,
                PR4_FIXTURE_SET_HASH,
                decisions,
                actionRunRecovery,
                factoryActionRuntime,
                factoryClock, java.util.Optional.ofNullable(productionPreparation.getIfAvailable()));
    }

    @Bean
    DispatchOutboxRepository dispatchOutboxRepository(DataSource dataSource) {
        return new JdbcDispatchOutboxRepository(dataSource);
    }

    @Bean
    WorkerDispatchTransaction workerDispatchTransaction(
            DataSource dataSource,
            ObjectMapper objectMapper,
            Clock factoryClock) {
        return new JdbcWorkerDispatchTransaction(dataSource, objectMapper, factoryClock);
    }

    @Bean
    WorkerCancellationTransaction workerCancellationTransaction(
            DataSource dataSource,
            ObjectMapper objectMapper) {
        return new JdbcWorkerCancellationTransaction(dataSource, objectMapper);
    }

    @Bean
    WorkerDispatchActionExecutor workerDispatchActionExecutor(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            WorkerDispatchTransaction transaction,
            WorkerCancellationTransaction cancellationTransaction,
            Clock factoryClock) {
        return new WorkerDispatchActionExecutor(
                workOrders, workerRuns, transaction, cancellationTransaction, factoryClock);
    }

    @Bean
    ActionInputValidator actionInputValidator(
            DecisionRecordActionValidator decisionValidator,
            ObjectProvider<CertificationIssueActionValidator> certificationValidator,
            ObjectProvider<ReferenceAssemblyReleaseActionValidator> referenceAssemblyValidator,
            ObjectProvider<AgentPackProductionActionValidator> productionValidator,
            ObjectProvider<AgentPackProductionIntakeActionValidator> intakeValidator,
            ObjectProvider<ReferenceAssemblyIntakeActionValidator> assemblyIntakeValidator,
            ObjectProvider<IncidentApplicationActions> incidentActions,
            ObjectProvider<IncidentApplicationReviewRenewalAction> incidentRenewal) {
        Map<String, ActionInputValidator> routes = new LinkedHashMap<>();
        routes.put(WorkerDispatchAction.ACTION_ID, new WorkerDispatchActionValidator());
        routes.put(VerificationRunAction.ACTION_ID, new VerificationRunActionValidator());
        routes.put(DecisionRecordAction.ACTION_ID, decisionValidator);
        certificationValidator.ifAvailable(value ->
                routes.put(CertificationIssueAction.ACTION_ID, value));
        referenceAssemblyValidator.ifAvailable(value ->
                routes.put(ReferenceAssemblyReleaseAction.ACTION_ID, value));
        productionValidator.ifAvailable(value -> routes.put(AgentPackProductionAction.ACTION_ID, value));
        intakeValidator.ifAvailable(value -> routes.put(AgentPackProductionIntakeAction.ACTION_ID, value));
        assemblyIntakeValidator.ifAvailable(value -> routes.put(ReferenceAssemblyIntakeAction.ACTION_ID, value));
        incidentActions.ifAvailable(value -> IncidentApplicationActions.IDS.forEach(id -> routes.put(id, value)));
        incidentRenewal.ifAvailable(value -> routes.put(IncidentApplicationReviewRenewalAction.ID, value));
        return new FactoryActionInputValidatorRouter(routes);
    }

    @Bean
    PolicyGate actionPolicyGate(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            AgentPackGenerationVerificationProfiles generationProfiles,
            DecisionRecordPolicyGate decisionPolicy,
            ObjectProvider<CertificationIssuePolicyGate> certificationPolicy,
            ObjectProvider<ReferenceAssemblyReleasePolicyGate> referenceAssemblyPolicy,
            ObjectProvider<AgentPackProductionPolicyGate> productionPolicy,
            ObjectProvider<AgentPackProductionIntakePolicyGate> intakePolicy,
            ObjectProvider<ReferenceAssemblyIntakePolicyGate> assemblyIntakePolicy,
            ObjectProvider<IncidentApplicationActions> incidentActions,
            ObjectProvider<IncidentApplicationReviewRenewalAction> incidentRenewal) {
        Map<String, PolicyGate> routes = new LinkedHashMap<>();
        routes.put(
                WorkerDispatchAction.ACTION_ID,
                new WorkerDispatchPolicyGate(workOrders, workerRuns));
        routes.put(
                VerificationRunAction.ACTION_ID,
                new VerificationRunPolicyGate(verificationRuns, candidates, generationProfiles));
        routes.put(DecisionRecordAction.ACTION_ID, decisionPolicy);
        certificationPolicy.ifAvailable(value ->
                routes.put(CertificationIssueAction.ACTION_ID, value));
        referenceAssemblyPolicy.ifAvailable(value ->
                routes.put(ReferenceAssemblyReleaseAction.ACTION_ID, value));
        productionPolicy.ifAvailable(value -> routes.put(AgentPackProductionAction.ACTION_ID, value));
        intakePolicy.ifAvailable(value -> routes.put(AgentPackProductionIntakeAction.ACTION_ID, value));
        assemblyIntakePolicy.ifAvailable(value -> routes.put(ReferenceAssemblyIntakeAction.ACTION_ID, value));
        incidentActions.ifAvailable(value -> IncidentApplicationActions.IDS.forEach(id -> routes.put(id, value)));
        incidentRenewal.ifAvailable(value -> routes.put(IncidentApplicationReviewRenewalAction.ID, value));
        return new FactoryActionPolicyGateRouter(routes);
    }

    @Bean
    PreExecutionGuard actionPreExecutionGuard(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            BuildSessionRepository buildSessions,
            CandidateVersionRepository candidates,
            VerificationRunRepository verificationRuns,
            Clock factoryClock,
            AgentPackGenerationVerificationProfiles generationProfiles,
            DecisionRecordPreExecutionGuard decisionGuard,
            ObjectProvider<CertificationIssuePreExecutionGuard> certificationGuard,
            ObjectProvider<ReferenceAssemblyReleasePreExecutionGuard> referenceAssemblyGuard,
            ObjectProvider<AgentPackProductionPreExecutionGuard> productionGuard,
            ObjectProvider<AgentPackProductionIntakePreExecutionGuard> intakeGuard,
            ObjectProvider<ReferenceAssemblyIntakePreExecutionGuard> assemblyIntakeGuard,
            ObjectProvider<IncidentApplicationActions> incidentActions,
            ObjectProvider<IncidentApplicationReviewRenewalAction> incidentRenewal) {
        Map<String, PreExecutionGuard> routes = new LinkedHashMap<>();
        routes.put(
                WorkerDispatchAction.ACTION_ID,
                new WorkerDispatchPreExecutionGuard(
                        workOrders, workerRuns, buildSessions, factoryClock));
        routes.put(
                VerificationRunAction.ACTION_ID,
                new VerificationRunPreExecutionGuard(
                        buildSessions,
                        candidates,
                        verificationRuns,
                        PR4_FIXTURE_SET_HASH,
                        factoryClock, generationProfiles));
        routes.put(DecisionRecordAction.ACTION_ID, decisionGuard);
        certificationGuard.ifAvailable(value ->
                routes.put(CertificationIssueAction.ACTION_ID, value));
        referenceAssemblyGuard.ifAvailable(value ->
                routes.put(ReferenceAssemblyReleaseAction.ACTION_ID, value));
        productionGuard.ifAvailable(value -> routes.put(AgentPackProductionAction.ACTION_ID, value));
        intakeGuard.ifAvailable(value -> routes.put(AgentPackProductionIntakeAction.ACTION_ID, value));
        assemblyIntakeGuard.ifAvailable(value -> routes.put(ReferenceAssemblyIntakeAction.ACTION_ID, value));
        incidentActions.ifAvailable(value -> IncidentApplicationActions.IDS.forEach(id -> routes.put(id, value)));
        incidentRenewal.ifAvailable(value -> routes.put(IncidentApplicationReviewRenewalAction.ID, value));
        return new FactoryPreExecutionGuardRouter(routes);
    }

    @Bean
    ApprovalGate actionApprovalGate() {
        return (proposal, definition, context, policyDecision) -> {
            throw new IllegalStateException("Factory PR2 worker dispatch must not enter an approval wait");
        };
    }

    @Bean
    RunStore actionRunStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcRunStore(dataSource, objectMapper);
    }

    @Bean
    WorkerActionRunRecovery workerActionRunRecovery(
            WorkerRunRepository workerRuns,
            RunStore actionRunStore,
            DefaultActionRuntime factoryActionRuntime,
            WorkerActionDuplicateOwnerLookup workerActionDuplicateOwnerLookup) {
        return new WorkerActionRunRecovery(
                workerRuns,
                actionRunStore,
                factoryActionRuntime,
                workerActionDuplicateOwnerLookup);
    }

    @Bean
    WorkerActionDuplicateOwnerLookup workerActionDuplicateOwnerLookup(DataSource dataSource) {
        return new JdbcWorkerActionDuplicateOwnerLookup(dataSource);
    }

    @Bean
    DuplicateActionPolicy actionDuplicatePolicy(
            DataSource dataSource,
            ObjectMapper objectMapper,
            WorkOrderRepository workOrders,
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            DecisionRecordVisibilityScopeResolver decisionVisibility,
            ObjectProvider<CertificationIssueVisibilityScopeResolver> certificationVisibility,
            ObjectProvider<ReferenceAssemblyReleaseVisibilityScopeResolver> referenceAssemblyVisibility,
            ObjectProvider<AgentPackProductionVisibilityScopeResolver> productionVisibility,
            ObjectProvider<AgentPackProductionIntakeVisibilityScopeResolver> intakeVisibility,
            ObjectProvider<ReferenceAssemblyIntakeVisibilityScopeResolver> assemblyIntakeVisibility,
            ObjectProvider<IncidentApplicationActions> incidentActions,
            ObjectProvider<IncidentApplicationReviewRenewalAction> incidentRenewal) {
        var routes = new LinkedHashMap<String,
                io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver>();
        routes.put(
                WorkerDispatchAction.ACTION_ID,
                new WorkerDispatchVisibilityScopeResolver(workOrders));
        routes.put(
                VerificationRunAction.ACTION_ID,
                new VerificationRunVisibilityScopeResolver(verificationRuns, candidates));
        routes.put(DecisionRecordAction.ACTION_ID, decisionVisibility);
        certificationVisibility.ifAvailable(value ->
                routes.put(CertificationIssueAction.ACTION_ID, value));
        referenceAssemblyVisibility.ifAvailable(value ->
                routes.put(ReferenceAssemblyReleaseAction.ACTION_ID, value));
        productionVisibility.ifAvailable(value -> routes.put(AgentPackProductionAction.ACTION_ID, value));
        intakeVisibility.ifAvailable(value -> routes.put(AgentPackProductionIntakeAction.ACTION_ID, value));
        assemblyIntakeVisibility.ifAvailable(value -> routes.put(ReferenceAssemblyIntakeAction.ACTION_ID, value));
        incidentActions.ifAvailable(value -> IncidentApplicationActions.IDS.forEach(id -> routes.put(id, value)));
        incidentRenewal.ifAvailable(value -> routes.put(IncidentApplicationReviewRenewalAction.ID, value));
        return new JdbcDuplicateActionPolicy(
                dataSource,
                objectMapper,
                new FactoryDuplicateVisibilityScopeRouter(routes));
    }

    @Bean
    AuditSink actionAuditSink(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcActionAuditSink(dataSource, objectMapper);
    }

    @Bean
    TraceSink actionTraceSink() {
        return TraceSink.noop();
    }

    @Bean
    DefaultActionRuntime factoryActionRuntime(
            WorkerDispatchActionExecutor workerDispatchActionExecutor,
            VerificationRunActionExecutor verificationRunActionExecutor,
            ActionInputValidator actionInputValidator,
            PolicyGate actionPolicyGate,
            ApprovalGate actionApprovalGate,
            DuplicateActionPolicy actionDuplicatePolicy,
            AuditSink actionAuditSink,
            TraceSink actionTraceSink,
            RunStore actionRunStore,
            PreExecutionGuard actionPreExecutionGuard,
            DecisionRecordActionExecutor decisionExecutor,
            ObjectProvider<CertificationIssueActionExecutor> certificationExecutor,
            ObjectProvider<ReferenceAssemblyReleaseActionExecutor> referenceAssemblyExecutor,
            ObjectProvider<AgentPackProductionActionExecutor> productionExecutor,
            ObjectProvider<AgentPackProductionIntakeActionExecutor> intakeExecutor,
            ObjectProvider<ReferenceAssemblyIntakeActionExecutor> assemblyIntakeExecutor,
            ObjectProvider<IncidentApplicationActions> incidentActions,
            ObjectProvider<IncidentApplicationReviewRenewalAction> incidentRenewal) {
        List<ActionExecutor> executors = new ArrayList<>();
        executors.add(workerDispatchActionExecutor);
        executors.add(verificationRunActionExecutor);
        executors.add(decisionExecutor);
        certificationExecutor.ifAvailable(executors::add);
        referenceAssemblyExecutor.ifAvailable(executors::add);
        productionExecutor.ifAvailable(executors::add);
        intakeExecutor.ifAvailable(executors::add);
        assemblyIntakeExecutor.ifAvailable(executors::add);
        incidentActions.ifAvailable(value -> {
            executors.add(value.intakeExecutor());
            executors.add(value.stageExecutor());
            executors.add(value.releaseExecutor());
        });
        incidentRenewal.ifAvailable(value -> executors.add(value.executor()));
        return new DefaultActionRuntime(
                new InMemoryActionRegistry(executors),
                actionInputValidator,
                actionPolicyGate,
                actionApprovalGate,
                actionDuplicatePolicy,
                actionAuditSink,
                actionTraceSink,
                actionRunStore,
                actionPreExecutionGuard);
    }

    @Bean
    WorkerCompletionService workerCompletionService(
            WorkerRunRepository workerRuns,
            DefaultActionRuntime factoryActionRuntime,
            Clock factoryClock) {
        return new WorkerCompletionService(workerRuns, factoryActionRuntime, factoryClock);
    }
}
