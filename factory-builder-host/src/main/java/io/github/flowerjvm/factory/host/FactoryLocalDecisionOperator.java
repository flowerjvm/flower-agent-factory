package io.github.flowerjvm.factory.host;

import com.sun.security.auth.module.NTSystem;
import io.github.flowerjvm.factory.application.decision.DecisionRecordAuthority;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Explicit host-owned SID grant, separate from the human's proposed decision payload. */
final class FactoryLocalDecisionOperator {
    private static final Set<String> KEYS = Set.of("schemaVersion", "windowsSid", "tenantId", "projectId", "decisionPointId", "permissions");
    private static final Set<String> PERMISSIONS = Set.of("factory.decision.record", "factory.agent.release.approve",
            "factory.reference-assembly.release.approve", "factory.incident-application.release.approve");
    private FactoryLocalDecisionOperator() {}

    static BoundAuthority authenticate(Path bindingPath, Supplier<String> nativeSid) {
        byte[] bytes = FactoryLocalDecisionDocuments.read(bindingPath);
        return authenticate(bytes, nativeSid);
    }

    static BoundAuthority authenticate(byte[] bytes, Supplier<String> nativeSid) {
        var document = FactoryLocalDecisionDocuments.decode(bytes, KEYS);
        if (!"factory.local-decision-operator.v1".equals(document.get("schemaVersion"))) throw FactoryLocalDecisionDocuments.invalid();
        String sid = FactoryLocalDecisionDocuments.text(document.get("windowsSid"));
        if (!sid.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+-[0-9]+") || !sid.equals(nativeSid.get())) {
            throw new IllegalStateException("LOCAL_DECISION_OPERATOR_UNAUTHENTICATED");
        }
        if (!(document.get("permissions") instanceof List<?> values) || values.isEmpty()
                || values.size() > PERMISSIONS.size() || values.stream().anyMatch(value -> !PERMISSIONS.contains(value))
                || Set.copyOf(values).size() != values.size()) throw FactoryLocalDecisionDocuments.invalid();
        Set<String> permissions = values.stream().map(String.class::cast).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!permissions.contains("factory.decision.record")) throw FactoryLocalDecisionDocuments.invalid();
        var authority = new DecisionRecordAuthority(new TenantId(FactoryLocalDecisionDocuments.text(document.get("tenantId"))),
                new ProjectId(FactoryLocalDecisionDocuments.text(document.get("projectId"))), "windows-sid:" + sid, permissions,
                new ArtifactReference("local-operator-binding-sha256:" + digest(bytes)));
        return new BoundAuthority(authority, new DecisionPointId(FactoryLocalDecisionDocuments.text(document.get("decisionPointId"))));
    }

    static String currentWindowsSid() {
        try {
            // Native token identity, not USERNAME, JVM user.name or a --principal argument.
            String sid = new NTSystem().getUserSID();
            if (sid == null || sid.isBlank()) throw new IllegalStateException();
            return sid;
        } catch (RuntimeException | LinkageError failure) {
            throw new IllegalStateException("LOCAL_DECISION_NATIVE_IDENTITY_UNAVAILABLE");
        }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    record BoundAuthority(DecisionRecordAuthority authority, DecisionPointId decisionPointId) {}
}
