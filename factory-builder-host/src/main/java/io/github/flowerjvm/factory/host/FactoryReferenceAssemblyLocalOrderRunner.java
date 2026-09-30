package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.*;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Local trusted operator transport. Certification/inspection/review/shipment remain separate governed stages. */
public final class FactoryReferenceAssemblyLocalOrderRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryReferenceAssemblyLocalOrderRunner.class);
    private final ActionBackedReferenceAssemblyIntakeLauncher intake;
    private final ReferenceAssemblyIntakeService service;
    private final FactoryProductLineFlowLauncher flows;
    private final TenantId tenant;
    private final String requestKey;
    private final ReferenceAssemblyIntakeInput input;

    public FactoryReferenceAssemblyLocalOrderRunner(ActionBackedReferenceAssemblyIntakeLauncher intake,
            ReferenceAssemblyIntakeService service, FactoryProductLineFlowLauncher flows, TenantId tenant,
            String requestKey, ReferenceAssemblyIntakeInput input) {
        this.intake = Objects.requireNonNull(intake); this.service = Objects.requireNonNull(service);
        this.flows = Objects.requireNonNull(flows); this.tenant = Objects.requireNonNull(tenant);
        this.input = Objects.requireNonNull(input); this.requestKey = bounded(requestKey);
        bounded(tenant.value());
    }

    @Override
    public void run(ApplicationArguments ignored) {
        var result = intake.submit(tenant, input.projectId(), input.buildSessionId(), requestKey, input);
        if (result.status() != ActionExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("Reference Assembly intake did not commit successfully: " + result.status());
        }
        // No cached result field is a BuildSession, component lock or workflow authority.
        var session = service.requireAcceptedSession(tenant, input.projectId(), input.buildSessionId(), requestKey, input);
        if (!session.tenantId().equals(tenant) || !session.projectId().equals(input.projectId())
                || !session.buildSessionId().equals(input.buildSessionId())
                || !session.productLineId().equals(ProductLineId.REFERENCE_ASSEMBLY)
                || !session.requestIdempotencyKey().equals(requestKey) || !session.deadlineAt().equals(input.deadlineAt())
                || session.maxRepairRounds() != 0) {
            throw new IllegalStateException("intake result does not match the trusted local assembly order");
        }
        if (session.cancellationRequestedAt().isPresent() || session.status() != BuildSessionStatus.RUNNING
                || session.currentPhase() != BuildSessionPhase.UNDERSTAND_CUSTOMER) {
            LOG.info("Reference Assembly order already progressed or needs attention; durable recovery owns continuation");
            return;
        }
        String identity = identity(tenant.value(), input.buildSessionId().value());
        flows.launch(tenant, input.buildSessionId(), "reference-assembly-production-" + identity, "assembly-production-" + identity);
        LOG.info("Reference Assembly intake committed and initial Flow submitted; no product approval or shipment implied");
    }

    private static String identity(String tenant, String session) {
        try {
            String material = tenant.length() + ":" + tenant + session.length() + ":" + session;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static String bounded(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("local order identity must be bounded");
        return value;
    }
}
