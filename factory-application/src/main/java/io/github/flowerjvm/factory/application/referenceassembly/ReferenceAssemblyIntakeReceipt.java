package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Code-owned length-prefixed UTF-8 request receipt; no parser, mutable time or candidate rewriting. */
public final class ReferenceAssemblyIntakeReceipt {
    public static final String SCHEMA_VERSION = "factory.reference-assembly-intake.v1";
    public static final String MEDIA_TYPE = "application/vnd.flower.reference-assembly-intake.v1";
    public static final int MAX_BYTES = 16 * 1024;
    private ReferenceAssemblyIntakeReceipt() {}

    public static ArtifactReference reference(TenantId tenant, String requestKey) {
        ReferenceAssemblyIntakeInput.boundedText(tenant.value(), "tenant", 128);
        ReferenceAssemblyIntakeInput.boundedText(requestKey, "requestKey", 255);
        return new ArtifactReference("factory-reference-assembly/intake/" + hash(fields(tenant.value(), requestKey)).sha256());
    }

    public static Artifact artifact(TenantId tenant, String createdBy, String requestKey,
                                    ReferenceAssemblyIntakeInput input, CertificationArtifactLock requirement) {
        ReferenceAssemblyIntakeInput.boundedText(createdBy, "createdBy", 128);
        byte[] bytes = fields(SCHEMA_VERSION, tenant.value(), createdBy, requestKey, input.buildSessionId().value(),
                input.projectId().value(), input.deadlineAt().toString(), input.catalogEntry().name(),
                input.certificationId().value(), input.sourceHash().sha256(),
                input.certificationManifest().reference().value(), input.certificationManifest().hash().sha256(),
                requirement.reference().value(), requirement.hash().sha256());
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("intake receipt exceeds its bound");
        return new Artifact(tenant, reference(tenant, requestKey), hash(bytes), MEDIA_TYPE, bytes);
    }

    public static Artifact artifactFor(BuildSession session, CertifiedAgentComponentRef component) {
        return artifact(session.tenantId(), session.createdBy(), session.requestIdempotencyKey(),
                new ReferenceAssemblyIntakeInput(session.buildSessionId(), session.projectId(), session.deadlineAt(),
                        ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1,
                        component.certificationId(), component.candidateHash(), component.certificationManifest()),
                new CertificationArtifactLock(session.requirementsArtifactRef(), session.requirementsHash()));
    }

    public static boolean exact(Artifact actual, Artifact expected) {
        return actual != null && actual.content().length <= MAX_BYTES && actual.tenantId().equals(expected.tenantId())
                && actual.reference().equals(expected.reference()) && actual.mediaType().equals(expected.mediaType())
                && actual.contentHash().equals(expected.contentHash()) && Arrays.equals(actual.content(), expected.content());
    }

    static byte[] fields(String... fields) {
        var out = new java.io.ByteArrayOutputStream();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            out.writeBytes((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    static ContentHash hash(byte[] bytes) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
