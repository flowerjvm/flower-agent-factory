package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Fixed-size logical identity for one versioned, resource-bound production phase. */
public final class AgentPackProductionIdempotencyKeys {
    private AgentPackProductionIdempotencyKeys() {}

    public static String derive(BuildSession session) {
        Objects.requireNonNull(session, "session");
        return "agent-pack-production-" + digest("factory.agent-pack.production.prepare.idempotency.v1",
                session.tenantId().value(), session.buildSessionId().value(), session.projectId().value(),
                session.productLineId().value(), session.requestIdempotencyKey(), session.requirementsHash().sha256(),
                Long.toString(session.version()), session.currentPhase().id(), Integer.toString(session.repairRound()),
                session.selectedManagerWorkerBinding().orElse(""), session.selectedCodingWorkerBinding().orElse(""),
                session.currentCandidateId().map(value -> value.value()).orElse(""),
                session.currentCandidateHash().map(value -> value.sha256()).orElse(""));
    }

    public static String trace(BuildSession session) {
        return "production:" + digest(session.tenantId().value(), session.buildSessionId().value());
    }

    private static String digest(String... fields) {
        var material = new StringBuilder();
        for (String field : fields) material.append(field.length()).append(':').append(field).append('\n');
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
