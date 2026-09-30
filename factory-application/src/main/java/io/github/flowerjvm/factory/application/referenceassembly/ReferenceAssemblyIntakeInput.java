package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A bounded exact component selection, never tenant, principal, permission or approval authority. */
public record ReferenceAssemblyIntakeInput(
        BuildSessionId buildSessionId, ProjectId projectId, Instant deadlineAt,
        ReferenceAssemblyProductLineCatalog.Entry catalogEntry, CertificationId certificationId,
        ContentHash sourceHash, CertificationArtifactLock certificationManifest) {
    private static final Set<String> FIELDS = Set.of("buildSessionId", "projectId", "deadlineAt", "catalogEntryId",
            "certificationId", "sourceHash", "certificationManifestRef", "certificationManifestHash");

    public ReferenceAssemblyIntakeInput {
        boundedText(Objects.requireNonNull(buildSessionId, "buildSessionId").value(), "buildSessionId", 128);
        boundedText(Objects.requireNonNull(projectId, "projectId").value(), "projectId", 128);
        boundedText(Objects.requireNonNull(certificationId, "certificationId").value(), "certificationId", 128);
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(certificationManifest, "certificationManifest");
        boundedText(certificationManifest.reference().value(), "certificationManifestRef", 1024);
        if (catalogEntry != ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1) {
            throw new IllegalArgumentException("new intake requires the explicit Maintenance Investigation entry");
        }
        if (deadlineAt == null || !deadlineAt.equals(deadlineAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("deadline requires microsecond precision");
        }
    }

    public void requireLiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!deadlineAt.isAfter(now) || deadlineAt.isAfter(now.plus(Duration.ofHours(48)))) {
            throw new IllegalArgumentException("intake deadline must be in the next 48 hours");
        }
    }

    public static ReferenceAssemblyIntakeInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(FIELDS)
                || input.values().stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("intake requires exactly eight string fields");
        }
        String deadline = (String) input.get("deadlineAt");
        Instant parsed = Instant.parse(deadline);
        if (!parsed.toString().equals(deadline)) throw new IllegalArgumentException("deadline must be canonical UTC");
        return new ReferenceAssemblyIntakeInput(new BuildSessionId((String) input.get("buildSessionId")),
                new ProjectId((String) input.get("projectId")), parsed,
                ReferenceAssemblyProductLineCatalog.Entry.valueOf((String) input.get("catalogEntryId")),
                new CertificationId((String) input.get("certificationId")), hash(input.get("sourceHash")),
                new CertificationArtifactLock(new ArtifactReference((String) input.get("certificationManifestRef")),
                        hash(input.get("certificationManifestHash"))));
    }

    public Map<String, Object> toMap() {
        return Map.of("buildSessionId", buildSessionId.value(), "projectId", projectId.value(),
                "deadlineAt", deadlineAt.toString(), "catalogEntryId", catalogEntry.name(),
                "certificationId", certificationId.value(), "sourceHash", sourceHash.sha256(),
                "certificationManifestRef", certificationManifest.reference().value(),
                "certificationManifestHash", certificationManifest.hash().sha256());
    }

    private static ContentHash hash(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("hash must be exact lowercase SHA-256");
        }
        return new ContentHash(text);
    }

    static String boundedText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be bounded non-control text");
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    throw new IllegalArgumentException(field + " must be well-formed Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " must be well-formed Unicode");
            }
        }
        return value;
    }
}
