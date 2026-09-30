package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.*;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** No default tenant, product, component, deadline, request identity or model/provider invocation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.production.reference-assembly.local-order", name = "enabled", havingValue = "true")
public class FactoryReferenceAssemblyLocalOrderConfiguration {
    private static final String PREFIX = "factory.production.reference-assembly.local-order.";

    @Bean
    FactoryReferenceAssemblyLocalOrderRunner referenceAssemblyLocalOrderRunner(Environment environment,
            ActionBackedReferenceAssemblyIntakeLauncher intake, ReferenceAssemblyIntakeService service,
            FactoryProductLineFlowLauncher flows) {
        var input = new ReferenceAssemblyIntakeInput(new BuildSessionId(required(environment, "session-id")),
                new ProjectId(required(environment, "project-id")), Instant.parse(required(environment, "deadline-at")),
                ReferenceAssemblyProductLineCatalog.Entry.valueOf(required(environment, "catalog-entry-id")),
                new CertificationId(required(environment, "certification-id")), new ContentHash(required(environment, "source-hash")),
                new CertificationArtifactLock(new ArtifactReference(required(environment, "certification-manifest-ref")),
                        new ContentHash(required(environment, "certification-manifest-hash"))));
        return new FactoryReferenceAssemblyLocalOrderRunner(intake, service, flows,
                new TenantId(required(environment, "tenant-id")), required(environment, "request-key"), input);
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(PREFIX + name + " must be explicitly configured");
        }
        return value;
    }
}
