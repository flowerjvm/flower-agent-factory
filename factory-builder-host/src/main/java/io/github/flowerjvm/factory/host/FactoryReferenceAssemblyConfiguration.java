package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionValidator;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleasePolicyGate;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleasePreExecutionGuard;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.ReferenceAssemblyReleaseDecisionSubjectAuthority;
import io.github.flowerjvm.factory.application.flow.LedgerBackedReferenceAssemblyFlowCoordinator;
import io.github.flowerjvm.factory.application.flow.FactoryReferenceAssemblyFlowRecovery;
import io.github.flowerjvm.factory.application.flow.ReferenceAssemblyFlowFactory;
import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAdmissionPolicies;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAssembler;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyCancellationRequester;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyInspector;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyProductLineCatalog;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAuthorityVerifier;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchRunner;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRequestService;
import io.github.flowerjvm.factory.application.referenceassembly.ReleasedReferenceAssemblyReadGate;
import io.github.flowerjvm.factory.application.referenceassembly.ReleasedReferenceAssemblyResolver;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyReleaseDispatchIntentRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyReleaseDispatchTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyReleaseTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyRepository;
import io.github.flowerjvm.factory.infrastructure.referenceassembly.JacksonReferenceAssemblyArtifactCodec;
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

/** Runtime wiring for exact, versioned Reference Assembly consumer contracts. */
@Configuration(proxyBeanMethods = false)
public class FactoryReferenceAssemblyConfiguration {
    @Bean
    ReferenceAssemblyArtifactCodec referenceAssemblyArtifactCodec(ObjectMapper objectMapper) {
        return new JacksonReferenceAssemblyArtifactCodec(objectMapper);
    }

    @Bean
    ReferenceAssemblyRepository referenceAssemblyRepository(DataSource dataSource) {
        return new JdbcReferenceAssemblyRepository(dataSource);
    }

    @Bean
    ReferenceAssemblyReleaseDispatchIntentRepository referenceAssemblyReleaseDispatchIntentRepository(
            DataSource dataSource) {
        return new JdbcReferenceAssemblyReleaseDispatchIntentRepository(dataSource);
    }

    @Bean
    ReferenceAssemblyProductLineCatalog referenceAssemblyProductLineCatalog(
            ArtifactStore artifacts, ReferenceAssemblyArtifactCodec codec) {
        return new ReferenceAssemblyProductLineCatalog(artifacts, codec);
    }

    @Bean
    ReferenceAssemblyAdmissionPolicies referenceAssemblyAdmissionPolicies(
            ReferenceAssemblyProductLineCatalog catalog) {
        return catalog.admissionPolicies();
    }

    @Bean
    ReferenceAssemblyRequestService referenceAssemblyRequestService(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository assemblies,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            ReferenceAssemblyAdmissionPolicies policy,
            Clock factoryClock) {
        return new ReferenceAssemblyRequestService(
                buildSessions, assemblies, artifacts, codec, policy, factoryClock);
    }

    @Bean
    ReferenceAssemblyAssembler referenceAssemblyAssembler(
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            @Qualifier("tickComponentReadGate") CertifiedAgentComponentReadGate components,
            ReferenceAssemblyAdmissionPolicies policy) {
        return new ReferenceAssemblyAssembler(artifacts, codec, components, policy);
    }

    @Bean
    ReferenceAssemblyInspector referenceAssemblyInspector(
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            @Qualifier("tickComponentReadGate") CertifiedAgentComponentReadGate components,
            ReferenceAssemblyAdmissionPolicies policy) {
        return new ReferenceAssemblyInspector(artifacts, codec, components, policy);
    }

    @Bean
    ReferenceAssemblyReleaseReviewService referenceAssemblyReleaseReviewService(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository assemblies,
            DecisionPointRepository decisionPoints,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            @Qualifier("tickComponentReadGate") CertifiedAgentComponentReadGate components,
            ReferenceAssemblyAdmissionPolicies policies,
            Clock factoryClock) {
        return new ReferenceAssemblyReleaseReviewService(
                buildSessions,
                assemblies,
                decisionPoints,
                artifacts,
                codec,
                components,
                policies,
                factoryClock);
    }

    @Bean
    ReferenceAssemblyReleaseDecisionSubjectAuthority referenceAssemblyReleaseDecisionSubjectAuthority(
            ReferenceAssemblyRepository assemblies) {
        return new ReferenceAssemblyReleaseDecisionSubjectAuthority(assemblies);
    }

    @Bean
    ReferenceAssemblyReleaseAuthorityVerifier referenceAssemblyReleaseAuthorityVerifier(
            ReferenceAssemblyRepository assemblies,
            BuildSessionRepository buildSessions,
            DecisionPointRepository decisionPoints,
            ReferenceAssemblyAssembler assembler,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            Clock factoryClock) {
        return new ReferenceAssemblyReleaseAuthorityVerifier(
                assemblies,
                buildSessions,
                decisionPoints,
                assembler,
                artifacts,
                codec,
                factoryClock);
    }

    @Bean
    ReferenceAssemblyReleaseActionValidator referenceAssemblyReleaseActionValidator() {
        return new ReferenceAssemblyReleaseActionValidator();
    }

    @Bean
    ReferenceAssemblyReleasePolicyGate referenceAssemblyReleasePolicyGate(
            ReferenceAssemblyRepository assemblies) {
        return new ReferenceAssemblyReleasePolicyGate(assemblies);
    }

    @Bean
    ReferenceAssemblyReleaseVisibilityScopeResolver referenceAssemblyReleaseVisibilityScopeResolver(
            ReferenceAssemblyRepository assemblies) {
        return new ReferenceAssemblyReleaseVisibilityScopeResolver(assemblies);
    }

    @Bean
    ReferenceAssemblyReleasePreExecutionGuard referenceAssemblyReleasePreExecutionGuard(
            ReferenceAssemblyReleaseAuthorityVerifier authority) {
        return new ReferenceAssemblyReleasePreExecutionGuard(authority);
    }

    @Bean
    ReferenceAssemblyReleaseDispatchTransaction referenceAssemblyReleaseDispatchTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcReferenceAssemblyReleaseDispatchTransaction(dataSource, objectMapper);
    }

    @Bean
    ReferenceAssemblyReleaseActionExecutor referenceAssemblyReleaseActionExecutor(
            ReferenceAssemblyReleaseDispatchTransaction transaction, Clock factoryClock) {
        return new ReferenceAssemblyReleaseActionExecutor(transaction, factoryClock);
    }

    @Bean
    ReferenceAssemblyReleaseTransaction referenceAssemblyReleaseTransaction(
            DataSource dataSource, ObjectMapper objectMapper, Clock factoryClock) {
        return new JdbcReferenceAssemblyReleaseTransaction(dataSource, objectMapper, factoryClock);
    }

    @Bean
    ReferenceAssemblyReleaseService referenceAssemblyReleaseService(
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseTransaction transaction,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            Clock factoryClock,
            @Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate fullComponents) {
        return new ReferenceAssemblyReleaseService(
                assemblies, transaction, artifacts, codec, factoryClock, fullComponents);
    }

    @Bean
    ActionBackedReferenceAssemblyReleaseLauncher referenceAssemblyReleaseLauncher(
            DefaultActionRuntime factoryActionRuntime) {
        return new ActionBackedReferenceAssemblyReleaseLauncher(factoryActionRuntime);
    }

    @Bean
    ReferenceAssemblyCancellationRequester referenceAssemblyCancellationRequester(
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseDispatchIntentRepository intents,
            RunStore actionRunStore,
            DefaultActionRuntime factoryActionRuntime) {
        return new ReferenceAssemblyCancellationRequester(
                assemblies, intents, actionRunStore, factoryActionRuntime);
    }

    @Bean
    ReferenceAssemblyReleaseDispatchRunner referenceAssemblyReleaseDispatchRunner(
            ReferenceAssemblyReleaseDispatchIntentRepository intents,
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseService releases,
            DefaultActionRuntime factoryActionRuntime,
            RunStore actionRunStore,
            Clock factoryClock) {
        return new ReferenceAssemblyReleaseDispatchRunner(
                intents,
                assemblies,
                releases,
                factoryActionRuntime,
                actionRunStore,
                factoryClock);
    }

    @Bean
    LedgerBackedReferenceAssemblyFlowCoordinator referenceAssemblyFlowCoordinator(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository assemblies,
            DecisionPointRepository decisionPoints,
            ReferenceAssemblyRequestService requests,
            ReferenceAssemblyAssembler assembler,
            ReferenceAssemblyInspector inspector,
            ReferenceAssemblyReleaseReviewService reviews,
            ActionBackedReferenceAssemblyReleaseLauncher releaseLauncher,
            ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents,
            ReferenceAssemblyReleaseService releaseService,
            RunStore actionRunStore,
            Clock factoryClock) {
        return new LedgerBackedReferenceAssemblyFlowCoordinator(
                buildSessions,
                assemblies,
                decisionPoints,
                requests,
                assembler,
                inspector,
                reviews,
                releaseLauncher,
                releaseIntents,
                releaseService,
                actionRunStore,
                factoryClock);
    }

    @Bean
    ReferenceAssemblyFlowFactory referenceAssemblyFlowFactory(
            LedgerBackedReferenceAssemblyFlowCoordinator coordinator) {
        return new ReferenceAssemblyFlowFactory(coordinator);
    }

    @Bean
    ReleasedReferenceAssemblyReadGate releasedReferenceAssemblyReadGate(
            ReferenceAssemblyRepository assemblies,
            BuildSessionRepository buildSessions,
            ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents,
            RunStore actionRuns,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            @Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate components,
            ReferenceAssemblyAdmissionPolicies policies) {
        return new ReleasedReferenceAssemblyResolver(
                assemblies,
                buildSessions,
                releaseIntents,
                actionRuns,
                artifacts,
                codec,
                components,
                policies);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService referenceAssemblyExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "factory-reference-assembly");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    ReferenceAssemblyReleaseDispatchPump referenceAssemblyReleaseDispatchPump(
            ReferenceAssemblyReleaseDispatchRunner runner,
            @Qualifier("referenceAssemblyExecutor") ScheduledExecutorService executor) {
        return new ReferenceAssemblyReleaseDispatchPump(
                runner, executor, Duration.ofMillis(250));
    }

    @Bean
    ReferenceAssemblyFlowRecoveryPump referenceAssemblyFlowRecoveryPump(
            FactoryReferenceAssemblyFlowRecovery recovery,
            @Qualifier("referenceAssemblyExecutor") ScheduledExecutorService executor) {
        return new ReferenceAssemblyFlowRecoveryPump(
                recovery, executor, Duration.ofSeconds(1));
    }
}
