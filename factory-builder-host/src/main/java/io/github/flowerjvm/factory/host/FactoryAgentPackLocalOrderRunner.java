package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionIntakeLauncher;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeAction;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeInput;
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

/** Explicit local operator entry, not a public authenticated API or an approval mechanism. */
public final class FactoryAgentPackLocalOrderRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryAgentPackLocalOrderRunner.class);
    private final ActionBackedAgentPackProductionIntakeLauncher intake;
    private final BuildSessionRepository sessions;
    private final FactoryProductLineFlowLauncher flows;
    private final TenantId trustedTenant;
    private final String requestKey;
    private final AgentPackProductionIntakeInput input;

    public FactoryAgentPackLocalOrderRunner(
            ActionBackedAgentPackProductionIntakeLauncher intake, BuildSessionRepository sessions,
            FactoryProductLineFlowLauncher flows, TenantId trustedTenant, String requestKey,
            AgentPackProductionIntakeInput input) {
        this.intake = Objects.requireNonNull(intake, "intake");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.flows = Objects.requireNonNull(flows, "flows");
        this.trustedTenant = Objects.requireNonNull(trustedTenant, "trustedTenant");
        this.requestKey = bounded(requestKey);
        this.input = Objects.requireNonNull(input, "input");
        bounded(trustedTenant.value());
    }

    @Override
    public void run(ApplicationArguments ignored) {
        var result = intake.submit(trustedTenant, input.buildSessionId(), input.projectId(), requestKey, input);
        if (result.status() != ActionExecutionStatus.SUCCEEDED) {
            // In-progress duplicates are not stolen. The operator may retry the identical request.
            throw new IllegalStateException("Agent Pack intake did not commit successfully: " + result.status());
        }
        var canonical = sessions.find(trustedTenant, input.buildSessionId()).orElseThrow(() ->
                new IllegalStateException("successful intake has no durable BuildSession"));
        if (!canonical.tenantId().equals(trustedTenant)
                || !canonical.buildSessionId().equals(input.buildSessionId())
                || !canonical.projectId().equals(input.projectId())
                || !canonical.productLineId().equals(ProductLineId.AGENT_PACK)
                || !canonical.createdBy().equals(AgentPackProductionIntakeAction.REQUESTER_ID)
                || !canonical.requestIdempotencyKey().equals(requestKey)
                || !canonical.deadlineAt().equals(input.deadlineAt())
                || canonical.maxRepairRounds() != input.maxRepairRounds()) {
            throw new IllegalStateException("intake result does not match the trusted local order");
        }
        if (canonical.status().isTerminal() || canonical.cancellationRequestedAt().isPresent()) {
            LOG.info("Agent Pack order {} already closed or cancelling; no new Flow submitted", input.buildSessionId().value());
            return;
        }
        if (canonical.status() != BuildSessionStatus.RUNNING
                || canonical.currentPhase() != BuildSessionPhase.UNDERSTAND_CUSTOMER) {
            // Later phases are restored from their original checkpoint by FactoryFlowerConfiguration.
            // Recreating the initial Flow would lose its phase/context and is forbidden.
            LOG.info("Agent Pack order {} already passed intake or requires attention; existing checkpoint/continuation owns recovery",
                    input.buildSessionId().value());
            return;
        }
        String identity = identity(trustedTenant.value(), input.buildSessionId().value());
        flows.launch(trustedTenant, input.buildSessionId(), "agent-pack-production-" + identity, "production-" + identity);
        LOG.info("Agent Pack order {} committed and Flow submitted; no product approval or shipment implied",
                input.buildSessionId().value());
    }

    private static String identity(String tenant, String session) {
        String material = tenant.length() + ":" + tenant + session.length() + ":" + session;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("local order identity must be bounded non-control text");
        }
        return value;
    }
}
