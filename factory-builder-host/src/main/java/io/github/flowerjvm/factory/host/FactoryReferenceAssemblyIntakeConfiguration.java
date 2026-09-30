package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.referenceassembly.*;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyIntakeTransaction;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit local production capability, not an implicit consumer, deployment or automatic approval. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.production.reference-assembly", name = "intake-enabled", havingValue = "true")
public class FactoryReferenceAssemblyIntakeConfiguration {
    @Bean
    ReferenceAssemblyIntakeTransaction referenceAssemblyIntakeTransaction(DataSource dataSource, ObjectMapper mapper,
            @Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate fullResolver, Clock factoryClock) {
        return new JdbcReferenceAssemblyIntakeTransaction(dataSource, mapper, fullResolver, factoryClock);
    }

    @Bean
    ReferenceAssemblyIntakeService referenceAssemblyIntakeService(BuildSessionRepository sessions,
            CertificationRepository certifications,
            @Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate fullResolver,
            ArtifactStore artifacts, ReferenceAssemblyArtifactCodec codec, ReferenceAssemblyIntakeTransaction transaction,
            Clock factoryClock) {
        return new ReferenceAssemblyIntakeService(sessions, certifications, fullResolver, artifacts, codec, transaction, factoryClock);
    }

    @Bean
    ReferenceAssemblyIntakeAdmission referenceAssemblyIntakeAdmission(ReferenceAssemblyIntakeService service, Clock factoryClock) {
        return new ReferenceAssemblyIntakeAdmission(service, factoryClock);
    }

    @Bean
    ReferenceAssemblyIntakeActionExecutor referenceAssemblyIntakeActionExecutor(ReferenceAssemblyIntakeService service,
            ReferenceAssemblyIntakeAdmission admission) {
        return new ReferenceAssemblyIntakeActionExecutor(service, admission);
    }

    @Bean
    ReferenceAssemblyIntakeActionValidator referenceAssemblyIntakeActionValidator() {
        return new ReferenceAssemblyIntakeActionValidator();
    }

    @Bean
    ReferenceAssemblyIntakePolicyGate referenceAssemblyIntakePolicyGate(ReferenceAssemblyIntakeAdmission admission) {
        return new ReferenceAssemblyIntakePolicyGate(admission);
    }

    @Bean
    ReferenceAssemblyIntakePreExecutionGuard referenceAssemblyIntakePreExecutionGuard(ReferenceAssemblyIntakeAdmission admission) {
        return new ReferenceAssemblyIntakePreExecutionGuard(admission);
    }

    @Bean
    ReferenceAssemblyIntakeVisibilityScopeResolver referenceAssemblyIntakeVisibilityScopeResolver(ReferenceAssemblyIntakeAdmission admission) {
        return new ReferenceAssemblyIntakeVisibilityScopeResolver(admission);
    }

    @Bean
    ActionBackedReferenceAssemblyIntakeLauncher referenceAssemblyIntakeLauncher(ActionRuntime factoryActionRuntime, Clock factoryClock) {
        return new ActionBackedReferenceAssemblyIntakeLauncher(factoryActionRuntime, factoryClock);
    }
}
