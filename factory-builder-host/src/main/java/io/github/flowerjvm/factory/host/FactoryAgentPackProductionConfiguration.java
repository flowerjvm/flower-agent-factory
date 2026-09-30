package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewService;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewTransaction;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionRepository;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionLauncher;
import io.github.flowerjvm.factory.application.production.AgentPackProductionActionExecutor;
import io.github.flowerjvm.factory.application.production.AgentPackProductionActionValidator;
import io.github.flowerjvm.factory.application.production.AgentPackProductionCodec;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPhasePreparation;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPolicyGate;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPreExecutionGuard;
import io.github.flowerjvm.factory.application.production.AgentPackProductionRepairEvidenceReader;
import io.github.flowerjvm.factory.application.production.AgentPackProductionRepairFindings;
import io.github.flowerjvm.factory.application.production.AgentPackProductionService;
import io.github.flowerjvm.factory.application.production.AgentPackProductionVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.production.AgentPackWorkPreparationTransaction;
import io.github.flowerjvm.factory.application.verification.AgentPackGenerationVerificationProfiles;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcAgentPackReleaseReviewTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcAgentPackWorkPreparationTransaction;
import io.github.flowerjvm.factory.infrastructure.production.JacksonAgentPackProductionCodec;
import io.github.flowerjvm.factory.infrastructure.verification.ArtifactAgentPackProductionRepairEvidenceReader;
import io.github.flowerjvm.factory.infrastructure.verification.Pr4MavenToolchainInstaller;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Host-neutral wiring: the concrete Agent Pack application service owns recipe semantics. */
@Configuration(proxyBeanMethods = false)
public class FactoryAgentPackProductionConfiguration {
    @Bean
    AgentPackProductionCodec agentPackProductionCodec(ObjectMapper mapper) {
        return new JacksonAgentPackProductionCodec(mapper);
    }

    @Bean
    AgentPackWorkPreparationTransaction agentPackWorkPreparationTransaction(
            DataSource dataSource, ObjectMapper mapper, Clock factoryClock) {
        return new JdbcAgentPackWorkPreparationTransaction(dataSource, mapper, factoryClock);
    }

    @Bean
    AgentPackReleaseReviewTransaction agentPackReleaseReviewTransaction(
            DataSource dataSource, ObjectMapper mapper, Clock factoryClock,
            @Value("${factory.production.agent-pack.review-window:PT24H}") String reviewWindow) {
        return new JdbcAgentPackReleaseReviewTransaction(dataSource, mapper, factoryClock, Duration.parse(reviewWindow));
    }

    @Bean
    AgentPackReleaseReviewService agentPackReleaseReviewService(
            BuildSessionRepository sessions, CandidateVersionRepository candidates,
            VerificationRunRepository verifications, AgentPackGenerationVerificationProfiles profiles,
            VerificationEvidenceValidator evidence, VerificationActionEvidenceOwner owners,
            ArtifactStore artifacts, AgentPackReleaseReviewTransaction transaction, Clock factoryClock,
            @Value("${factory.production.agent-pack.review-window:PT24H}") String reviewWindow) {
        return new AgentPackReleaseReviewService(sessions, candidates, verifications, profiles, evidence,
                owners, artifacts, transaction, factoryClock, Duration.parse(reviewWindow));
    }

    @Bean
    AgentPackProductionRepairEvidenceReader agentPackProductionRepairEvidenceReader(
            ArtifactStore artifacts, ObjectMapper mapper) {
        return new ArtifactAgentPackProductionRepairEvidenceReader(artifacts, mapper,
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_REF,
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                Pr4MavenToolchainInstaller.EXPECTED_REFERENCE, Pr4MavenToolchainInstaller.EXPECTED_HASH);
    }

    @Bean
    AgentPackProductionRepairFindings agentPackProductionRepairFindings(
            BuildSessionRepository sessions, VerificationRunRepository verifications,
            AgentPackGenerationVerificationProfiles profiles, VerificationActionEvidenceOwner owners,
            DecisionPointRepository points, DecisionRepository decisions,
            AgentPackProductionRepairEvidenceReader reader, Clock factoryClock) {
        return new AgentPackProductionRepairFindings(sessions, verifications, profiles, owners,
                points, decisions, reader, factoryClock);
    }

    @Bean
    AgentPackProductionService agentPackProductionService(
            BuildSessionRepository sessions, WorkOrderRepository orders,
            CandidateVersionRepository candidates, ArtifactStore artifacts, WorkerProtocolArtifacts protocol,
            AgentPackProductionCodec codec, AgentPackWorkPreparationTransaction transaction,
            AgentPackReleaseReviewService reviews, AgentPackProductionRepairFindings findings, Clock factoryClock,
            @Value("${factory.production.agent-pack.work-window:PT20M}") String workWindow) {
        return new AgentPackProductionService(sessions, orders, candidates, artifacts, protocol, codec,
                transaction, reviews, findings, factoryClock, Duration.parse(workWindow));
    }

    @Bean
    AgentPackProductionActionExecutor agentPackProductionActionExecutor(AgentPackProductionService service) {
        return new AgentPackProductionActionExecutor(service);
    }

    @Bean
    AgentPackProductionActionValidator agentPackProductionActionValidator() {
        return new AgentPackProductionActionValidator();
    }

    @Bean
    AgentPackProductionPolicyGate agentPackProductionPolicyGate(BuildSessionRepository sessions, Clock factoryClock) {
        return new AgentPackProductionPolicyGate(sessions, factoryClock);
    }

    @Bean
    AgentPackProductionPreExecutionGuard agentPackProductionPreExecutionGuard(BuildSessionRepository sessions, Clock factoryClock) {
        return new AgentPackProductionPreExecutionGuard(sessions, factoryClock);
    }

    @Bean
    AgentPackProductionVisibilityScopeResolver agentPackProductionVisibilityScopeResolver(BuildSessionRepository sessions) {
        return new AgentPackProductionVisibilityScopeResolver(sessions);
    }

    @Bean
    AgentPackProductionPhasePreparation agentPackProductionPhasePreparation(
            ArtifactStore artifacts, ActionRuntime factoryActionRuntime, Clock factoryClock) {
        return new AgentPackProductionPhasePreparation(artifacts,
                new ActionBackedAgentPackProductionLauncher(factoryActionRuntime, factoryClock));
    }
}
