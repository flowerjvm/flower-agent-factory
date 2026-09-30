package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic tenant-scoped identity for one exact Reference Assembly release operation. */
public final class ReferenceAssemblyReleaseDispatchOperationIds {
    private static final byte[] DOMAIN =
            "factory.reference-assembly.release-dispatch.v1".getBytes(StandardCharsets.UTF_8);
    private static final Pattern FLOATING_SEGMENT =
            Pattern.compile("(?i)(?:^|[:/@._-])(latest|head)(?:$|[:/@._-])");

    private ReferenceAssemblyReleaseDispatchOperationIds() {}

    public static String derive(TenantId tenantId, ReferenceAssemblyReleaseInput input) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(input, "input");
        requireExactIdentity(tenantId.value(), "tenantId");
        requireExactIdentity(input.referenceAssemblyId().value(), "referenceAssemblyId");
        requireExactIdentity(input.releaseDecisionPointId().value(), "releaseDecisionPointId");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, DOMAIN);
            append(digest, tenantId.value());
            append(digest, input.referenceAssemblyId().value());
            append(digest, input.assemblyManifestHash().sha256());
            append(digest, input.inspectionReportHash().sha256());
            append(digest, input.releaseDecisionPointId().value());
            append(digest, input.releaseSubjectHash().sha256());
            append(digest, Long.toString(input.expectedReferenceAssemblyVersion()));
            return "reference-assembly-release:"
                    + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void append(MessageDigest digest, String value) {
        if (value == null) {
            throw new IllegalArgumentException("release operation identity values must not be null");
        }
        append(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void append(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static void requireExactIdentity(String value, String name) {
        if (value == null
                || value.isBlank()
                || value.length() > 128
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)
                || FLOATING_SEGMENT.matcher(value).find()
                || value.matches(".*[?*\\[\\]{}].*")) {
            throw new IllegalArgumentException(name + " must be a bounded exact identity");
        }
    }
}
