package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.candidate.CandidateIngestionService;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationService;
import io.github.flowerjvm.factory.application.flow.FactoryFlowRegistry;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationRecovery;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineCancellationRequester;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.work.ConservativeWorkerCompletionClassifier;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerCallbackInboxRepository;
import io.github.flowerjvm.factory.application.work.WorkerCallbackIngressService;
import io.github.flowerjvm.factory.application.work.WorkerCallbackProcessor;
import io.github.flowerjvm.factory.application.work.WorkerCallbackSecurityAudit;
import io.github.flowerjvm.factory.application.work.WorkerCancelPublisher;
import io.github.flowerjvm.factory.application.work.WorkerCancellationOutcomeTransaction;
import io.github.flowerjvm.factory.application.work.WorkerCancellationIntentRecovery;
import io.github.flowerjvm.factory.application.work.WorkerCancellationRequester;
import io.github.flowerjvm.factory.application.work.WorkerCancellationTransaction;
import io.github.flowerjvm.factory.application.work.WorkerCompletionClassifier;
import io.github.flowerjvm.factory.application.work.WorkerCompletionPayloadStager;
import io.github.flowerjvm.factory.application.work.WorkerCompletionService;
import io.github.flowerjvm.factory.application.work.WorkerDispatchAcceptanceTransaction;
import io.github.flowerjvm.factory.application.work.WorkerDispatchPreParkOrphanTransaction;
import io.github.flowerjvm.factory.application.work.WorkerDispatchPublisher;
import io.github.flowerjvm.factory.application.work.WorkerDispatchStartTransaction;
import io.github.flowerjvm.factory.application.work.WorkerDispatchUncertaintyService;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.host.worker.ArtifactWorkerCompletionPayloadStager;
import io.github.flowerjvm.factory.host.worker.ConfiguredWorkerCallbackCredentialRegistry;
import io.github.flowerjvm.factory.host.worker.WorkerCallbackAuthenticator;
import io.github.flowerjvm.factory.host.worker.WorkerCallbackCommandHandler;
import io.github.flowerjvm.factory.host.worker.WorkerCallbackCredentialRegistry;
import io.github.flowerjvm.factory.host.worker.WorkerCallbackRateLimiter;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerCallbackInboxRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerCallbackSecurityAudit;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerCancellationOutcomeTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerDispatchAcceptanceTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerDispatchPreParkOrphanTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerDispatchStartTransaction;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactEncoder;
import io.github.flowerjvm.factory.infrastructure.worker.codex.CodexCodingWorker;
import io.github.flowerjvm.factory.infrastructure.worker.codex.CodexCredentialIsolationVerifier;
import io.github.flowerjvm.factory.infrastructure.worker.codex.CodexPermissionProfileCredentialIsolationVerifier;
import io.github.flowerjvm.factory.infrastructure.worker.codex.CodexWorkerBinding;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.core.engine.Engine;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** PR5 authenticated callback, immutable artifact, and durable inbox wiring. */
@Configuration(proxyBeanMethods = false)
public class FactoryWorkerTransportConfiguration {
    private static final String CODEX_PREFIX = "factory.worker.codex.";

    @Bean
    WorkerCallbackCredentialRegistry workerCallbackCredentialRegistry(Environment environment) {
        return new ConfiguredWorkerCallbackCredentialRegistry(environment);
    }

    @Bean
    WorkerCallbackAuthenticator workerCallbackAuthenticator(
            WorkerCallbackCredentialRegistry credentials,
            Clock factoryClock) {
        return new WorkerCallbackAuthenticator(credentials, factoryClock, Duration.ofMinutes(5));
    }

    @Bean
    WorkerCallbackRateLimiter workerCallbackRateLimiter(Clock factoryClock) {
        return new WorkerCallbackRateLimiter(factoryClock);
    }

    @Bean
    WorkerCallbackInboxRepository workerCallbackInboxRepository(DataSource dataSource) {
        return new JdbcWorkerCallbackInboxRepository(dataSource);
    }

    @Bean
    WorkerCallbackSecurityAudit workerCallbackSecurityAudit(DataSource dataSource) {
        return new JdbcWorkerCallbackSecurityAudit(dataSource);
    }

    @Bean
    CandidateIngestionService candidateIngestionService(
            WorkerProtocolArtifacts artifacts,
            CandidateVersionRepository candidates) {
        return new CandidateIngestionService(artifacts, candidates);
    }

    @Bean
    WorkerCompletionClassifier workerCompletionClassifier() {
        return new ConservativeWorkerCompletionClassifier();
    }

    @Bean
    WorkerCallbackIngressService workerCallbackIngressService(
            WorkerProtocolArtifacts artifacts,
            WorkerRunRepository workerRuns,
            RunStore actionRunStore,
            WorkerCallbackInboxRepository inbox,
            WorkerCallbackSecurityAudit audit,
            Clock factoryClock) {
        return new WorkerCallbackIngressService(
                artifacts, workerRuns, actionRunStore, inbox, audit, factoryClock);
    }

    @Bean
    WorkerCallbackCommandHandler workerCallbackCommandHandler(WorkerCallbackIngressService ingress) {
        return command -> ingress.receive(command);
    }

    @Bean
    WorkerCompletionPayloadStager workerCompletionPayloadStager(
            ArtifactStore artifactStore,
            JacksonWorkerProtocolArtifactEncoder encoder,
            WorkerCallbackIngressService ingress) {
        return new ArtifactWorkerCompletionPayloadStager(artifactStore, encoder, ingress);
    }

    @Bean
    WorkerCallbackProcessor workerCallbackProcessor(
            WorkerCallbackInboxRepository inbox,
            WorkerProtocolArtifacts artifacts,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore actionRunStore,
            CandidateIngestionService candidates,
            WorkerCompletionService completions,
            WorkerCompletionClassifier classifier,
            Clock factoryClock) {
        return new WorkerCallbackProcessor(
                inbox,
                artifacts,
                workOrders,
                workerRuns,
                actionRunStore,
                candidates,
                completions,
                classifier,
                factoryClock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    CodingWorker codexCodingWorker(
            Environment environment,
            ArtifactStore artifactStore,
            WorkOrderRepository workOrders,
            CodexCredentialIsolationVerifier credentialIsolation,
            io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository candidates) {
        String workspaceRef = required(environment, "workspace-ref");
        Path workspace = configuredPath(environment, "workspace-path");
        CodexWorkerBinding binding = new CodexWorkerBinding(
                required(environment, "binding-id"),
                configuredPath(environment, "node-executable"),
                configuredPath(environment, "runner-entrypoint"),
                configuredPath(environment, "state-root"),
                configuredPath(environment, "workspace-base"),
                configuredPath(environment, "codex-home"),
                Map.of(workspaceRef, workspace),
                allowlistedLaunchEnvironment(),
                optional(environment, "model"),
                optional(environment, "codex-path").map(value -> absoluteConfiguredPath(value, "codex-path")),
                optional(environment, "process-timeout")
                        .map(FactoryWorkerTransportConfiguration::parseDuration)
                        .orElse(Duration.ofSeconds(30)));
        return new CodexCodingWorker(binding, artifactStore, workOrders, credentialIsolation, candidates);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    CodexCredentialIsolationVerifier codexCredentialIsolationVerifier() {
        // The verifier executes the fixed runner's real permission-profile sentinel. Merely
        // setting enabled=true never asserts isolation; a failed/missing proof blocks capability
        // advertisement and every submit before external effect.
        return new CodexPermissionProfileCredentialIsolationVerifier();
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    AgentPackProductionWorkerSelection agentPackProductionWorkerSelection(
            Environment environment, CodingWorker codingWorker) {
        // Selecting metadata has no process/model effect. The intake Action probes capabilities.
        return new AgentPackProductionWorkerSelection(required(environment, "workspace-ref"),
                required(environment, "binding-id"), CodexCodingWorker.ADAPTER_VERSION, codingWorker);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerDispatchAcceptanceTransaction workerDispatchAcceptanceTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkerDispatchAcceptanceTransaction(dataSource, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerDispatchStartTransaction workerDispatchStartTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkerDispatchStartTransaction(dataSource, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerDispatchPreParkOrphanTransaction workerDispatchPreParkOrphanTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkerDispatchPreParkOrphanTransaction(dataSource, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerCancellationOutcomeTransaction workerCancellationOutcomeTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkerCancellationOutcomeTransaction(dataSource, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerDispatchUncertaintyService workerDispatchUncertaintyService(
            WorkerRunRepository workerRuns,
            RunStore actionRunStore,
            DefaultActionRuntime factoryActionRuntime) {
        return new WorkerDispatchUncertaintyService(workerRuns, actionRunStore, factoryActionRuntime);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerDispatchPublisher workerDispatchPublisher(
            DispatchOutboxRepository outboxes,
            WorkerDispatchStartTransaction dispatchStarts,
            WorkerDispatchPreParkOrphanTransaction preParkOrphans,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore actionRunStore,
            CodingWorker codingWorker,
            WorkerDispatchAcceptanceTransaction acceptance,
            WorkerCompletionPayloadStager completionStager,
            WorkerCallbackInboxRepository callbackInbox,
            WorkerDispatchUncertaintyService uncertainty,
            Clock factoryClock) {
        return new WorkerDispatchPublisher(
                outboxes,
                dispatchStarts,
                preParkOrphans,
                workOrders,
                workerRuns,
                actionRunStore,
                codingWorker,
                acceptance,
                completionStager,
                callbackInbox,
                uncertainty,
                factoryClock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerCancelPublisher workerCancelPublisher(
            DispatchOutboxRepository outboxes,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore actionRunStore,
            CodingWorker codingWorker,
            WorkerCancellationOutcomeTransaction outcomes,
            DefaultActionRuntime factoryActionRuntime,
            Clock factoryClock) {
        return new WorkerCancelPublisher(
                outboxes,
                workOrders,
                workerRuns,
                actionRunStore,
                codingWorker,
                outcomes,
                factoryActionRuntime,
                factoryClock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerCancellationRequester workerCancellationRequester(
            WorkerRunRepository workerRuns,
            DefaultActionRuntime factoryActionRuntime,
            WorkerCancellationIntentRecovery cancellationIntentRecovery) {
        return new WorkerCancellationRequester(
                workerRuns, factoryActionRuntime, cancellationIntentRecovery);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerCancellationIntentRecovery workerCancellationIntentRecovery(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            DispatchOutboxRepository outboxes,
            RunStore actionRunStore,
            WorkerCancellationTransaction cancellationTransaction,
            Clock factoryClock) {
        return new WorkerCancellationIntentRecovery(
                workOrders,
                workerRuns,
                outboxes,
                actionRunStore,
                cancellationTransaction,
                factoryClock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    FactoryFlowCancellationService factoryFlowCancellationService(
            BuildSessionRepository sessions,
            Engine factoryFlowerEngine,
            FactoryFlowRegistry flows,
            WorkerCancellationRequester workerCancellationRequester,
            Clock factoryClock,
            List<FactoryProductLineCancellationRequester> productLineCancellations) {
        return new FactoryFlowCancellationService(
                sessions,
                factoryFlowerEngine,
                flows,
                workerCancellationRequester,
                factoryClock,
                productLineCancellations);
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    FactoryFlowCancellationRecovery factoryFlowCancellationRecovery(
            BuildSessionRepository sessions,
            WorkerRunRepository workerRuns,
            FactoryFlowCancellationService cancellations,
            Clock factoryClock) {
        return new FactoryFlowCancellationRecovery(
                sessions, workerRuns, cancellations, factoryClock);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    ScheduledExecutorService workerTransportExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("factory-worker-transport").factory());
    }

    @Bean
    @ConditionalOnProperty(prefix = "factory.worker.codex", name = "enabled", havingValue = "true")
    WorkerTransportPump workerTransportPump(
            WorkerCallbackProcessor callbacks,
            FactoryFlowCancellationRecovery flowCancellationRecovery,
            WorkerCancellationIntentRecovery cancellationIntentRecovery,
            WorkerCancelPublisher cancellations,
            WorkerDispatchPublisher dispatches,
            @Qualifier("workerTransportExecutor") ScheduledExecutorService workerTransportExecutor) {
        return new WorkerTransportPump(
                callbacks,
                flowCancellationRecovery,
                cancellationIntentRecovery,
                cancellations,
                dispatches,
                workerTransportExecutor,
                Duration.ofMillis(250));
    }

    private static Path configuredPath(Environment environment, String name) {
        return absoluteConfiguredPath(required(environment, name), name);
    }

    private static Path absoluteConfiguredPath(String value, String name) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalStateException(CODEX_PREFIX + name + " must be an absolute path");
        }
        return path.normalize();
    }

    private static String required(Environment environment, String name) {
        return optional(environment, name).orElseThrow(() ->
                new IllegalStateException(CODEX_PREFIX + name + " must be configured when Codex Worker is enabled"));
    }

    private static Optional<String> optional(Environment environment, String name) {
        String value = environment.getProperty(CODEX_PREFIX + name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static Duration parseDuration(String value) {
        try {
            return Duration.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    CODEX_PREFIX + "process-timeout must be an ISO-8601 duration", exception);
        }
    }

    private static Map<String, String> allowlistedLaunchEnvironment() {
        List<String> names = List.of(
                "PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC",
                "LANG", "LC_ALL", "TZ");
        var result = new HashMap<String, String>();
        var folded = new HashSet<String>();
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null
                    && !value.isBlank()
                    && value.length() <= 32 * 1024
                    && folded.add(name.toUpperCase(Locale.ROOT))) {
                result.put(name, value);
            }
        }
        return Map.copyOf(result);
    }
}
