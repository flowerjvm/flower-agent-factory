package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic tenant-scoped operation identity for one exact Certification issuance attempt. */
public final class CertificationDispatchOperationIds {
    private static final String SCHEMA = "factory.certification.dispatch.v1";

    private CertificationDispatchOperationIds() {}

    public static String derive(TenantId tenantId, CertificationIssueInput input) {
        if (tenantId == null || input == null) {
            throw new IllegalArgumentException("tenantId and input are required");
        }
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, tenantId.value());
        append(material, input.certificationId().value());
        append(material, input.inputLockManifestHash().sha256());
        append(material, Long.toString(input.expectedCertificationVersion()));
        try {
            return "certification:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void append(StringBuilder material, String value) {
        material.append(value.length()).append(':').append(value).append('\n');
    }
}
