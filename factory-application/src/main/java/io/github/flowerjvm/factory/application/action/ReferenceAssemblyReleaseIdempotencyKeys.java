package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Tenant- and exact-release-lock-bound logical key for one packaging attempt. */
public final class ReferenceAssemblyReleaseIdempotencyKeys {
    private static final String VERSION = "factory.release.package.idempotency.v1";

    private ReferenceAssemblyReleaseIdempotencyKeys() {}

    public static String derive(ReferenceAssembly assembly) {
        if (assembly == null) {
            throw new IllegalArgumentException("assembly must not be null");
        }
        return derive(assembly, assembly.version());
    }

    /** Derives the original logical-attempt key for an authorized historical replay. */
    public static String derive(ReferenceAssembly assembly, long expectedVersion) {
        if (assembly == null) {
            throw new IllegalArgumentException("assembly must not be null");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        StringBuilder material = new StringBuilder();
        append(material, VERSION);
        append(material, assembly.tenantId().value());
        append(material, assembly.referenceAssemblyId().value());
        append(material, assembly.assemblyManifest()
                .orElseThrow(() -> missing("assemblyManifest"))
                .hash()
                .sha256());
        append(material, assembly.inspectionReport()
                .orElseThrow(() -> missing("inspectionReport"))
                .hash()
                .sha256());
        append(material, assembly.releaseDecisionPointId()
                .orElseThrow(() -> missing("releaseDecisionPointId"))
                .value());
        append(material, assembly.releaseSubjectHash()
                .orElseThrow(() -> missing("releaseSubjectHash"))
                .sha256());
        append(material, Long.toString(expectedVersion));
        try {
            return "reference-assembly-release-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static IllegalStateException missing(String field) {
        return new IllegalStateException("ReferenceAssembly has no exact " + field + " lock");
    }

    private static void append(StringBuilder material, String value) {
        material.append(value.length()).append(':').append(value).append('\n');
    }
}
