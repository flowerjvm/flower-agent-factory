package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Package-private exact artifact read/stage mechanics shared by two independent graph readers. */
final class ReferenceAssemblyArtifactSupport {
    static final String MANIFEST_REFERENCE_PREFIX =
            "factory-reference-assembly/assembly-manifest/sha256/";
    static final String INSPECTION_REFERENCE_PREFIX =
            "factory-reference-assembly/inspection-report/sha256/";
    static final String RELEASE_SUBJECT_REFERENCE_PREFIX =
            "factory-reference-assembly/release-subject/sha256/";
    static final String RELEASE_MANIFEST_REFERENCE_PREFIX =
            "factory-reference-assembly/release-manifest/sha256/";

    private final ArtifactStore artifacts;
    private final ReferenceAssemblyArtifactCodec codec;

    ReferenceAssemblyArtifactSupport(
            ArtifactStore artifacts, ReferenceAssemblyArtifactCodec codec) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    ReferenceAssemblyRequirement readRequirement(
            TenantId tenantId, CertificationArtifactLock lock) {
        return readCanonical(tenantId, lock, codec::readRequirement);
    }

    ReferenceAssemblyConsumerContract readConsumerContract(
            TenantId tenantId, CertificationArtifactLock lock) {
        return readCanonical(tenantId, lock, codec::readConsumerContract);
    }

    ReferenceAssemblyManifest readManifest(
            TenantId tenantId, CertificationArtifactLock lock) {
        return readCanonical(tenantId, lock, codec::readManifest);
    }

    ReferenceAssemblyInspectionReport readInspectionReport(
            TenantId tenantId, CertificationArtifactLock lock) {
        return readCanonical(tenantId, lock, codec::readInspectionReport);
    }

    ReferenceAssemblyReleaseSubject readReleaseSubject(
            TenantId tenantId, CertificationArtifactLock lock) {
        return readCanonical(tenantId, lock, codec::readReleaseSubject);
    }

    ReferenceAssemblyReleaseManifest readReleaseManifest(
            TenantId tenantId, CertificationArtifactLock lock) {
        return readCanonical(tenantId, lock, codec::readReleaseManifest);
    }

    void readOpaque(TenantId tenantId, CertificationArtifactLock lock) {
        readExact(tenantId, lock);
    }

    CertificationArtifactLock stageManifest(
            TenantId tenantId, ReferenceAssemblyManifest manifest) {
        return stageCanonical(
                tenantId, MANIFEST_REFERENCE_PREFIX, codec.writeManifest(manifest));
    }

    CertificationArtifactLock stageInspection(
            TenantId tenantId, ReferenceAssemblyInspectionReport report) {
        return stageCanonical(
                tenantId, INSPECTION_REFERENCE_PREFIX, codec.writeInspectionReport(report));
    }

    CertificationArtifactLock stageReleaseSubject(
            TenantId tenantId, ReferenceAssemblyReleaseSubject subject) {
        return stageCanonical(
                tenantId, RELEASE_SUBJECT_REFERENCE_PREFIX, codec.writeReleaseSubject(subject));
    }

    CertificationArtifactLock stageReleaseManifest(
            TenantId tenantId, ReferenceAssemblyReleaseManifest manifest) {
        return stageCanonical(
                tenantId, RELEASE_MANIFEST_REFERENCE_PREFIX, codec.writeReleaseManifest(manifest));
    }

    private <T> T readCanonical(
            TenantId tenantId,
            CertificationArtifactLock lock,
            Function<byte[], T> reader) {
        Artifact artifact = readExact(tenantId, lock);
        if (!ReferenceAssemblyArtifactCodec.MEDIA_TYPE.equals(artifact.mediaType())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.CANONICAL_ARTIFACT_INVALID,
                    "Reference Assembly canonical artifact media type is not application/json");
        }
        try {
            return Objects.requireNonNull(reader.apply(artifact.content()), "decoded artifact");
        } catch (RuntimeException invalid) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.CANONICAL_ARTIFACT_INVALID,
                    "Reference Assembly artifact is not strict canonical JSON",
                    invalid);
        }
    }

    private Artifact readExact(TenantId tenantId, CertificationArtifactLock lock) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(lock, "lock");
        Artifact artifact;
        try {
            artifact = artifacts.find(tenantId, lock.reference()).orElse(null);
        } catch (RuntimeException corrupt) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.ARTIFACT_INVALID,
                    "Reference Assembly artifact lookup failed integrity checks",
                    corrupt);
        }
        if (artifact == null
                || !artifact.tenantId().equals(tenantId)
                || !artifact.reference().equals(lock.reference())
                || !artifact.contentHash().equals(lock.hash())
                || !sha256(artifact.content()).equals(lock.hash())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.ARTIFACT_INVALID,
                    "Reference Assembly artifact is missing or differs from its exact lock");
        }
        return artifact;
    }

    private CertificationArtifactLock stageCanonical(
            TenantId tenantId, String referencePrefix, byte[] content) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(content, "content");
        ContentHash hash = sha256(content);
        ArtifactReference reference = new ArtifactReference(referencePrefix + hash.sha256());
        Artifact expected = new Artifact(
                tenantId,
                reference,
                hash,
                ReferenceAssemblyArtifactCodec.MEDIA_TYPE,
                content);
        Optional<Artifact> existing;
        try {
            existing = artifacts.find(tenantId, reference);
        } catch (RuntimeException corrupt) {
            throw stagingConflict(corrupt);
        }
        if (existing.isPresent()) {
            requireExpected(existing.orElseThrow(), expected);
            return new CertificationArtifactLock(reference, hash);
        }
        try {
            ArtifactReference storedReference = artifacts.store(expected);
            if (!reference.equals(storedReference)) {
                throw new ReferenceAssemblyException(
                        ReferenceAssemblyException.STAGING_CONFLICT,
                        "Artifact store returned a non-canonical Reference Assembly reference");
            }
        } catch (ReferenceAssemblyException stable) {
            throw stable;
        } catch (RuntimeException possibleRace) {
            // A concurrent content-addressed writer is acceptable only when its exact bytes won.
        }
        Artifact stored;
        try {
            stored = artifacts.find(tenantId, reference).orElse(null);
        } catch (RuntimeException corrupt) {
            throw stagingConflict(corrupt);
        }
        if (stored == null) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.STAGING_CONFLICT,
                    "Staged Reference Assembly artifact is not readable");
        }
        requireExpected(stored, expected);
        return new CertificationArtifactLock(reference, hash);
    }

    private static void requireExpected(Artifact actual, Artifact expected) {
        if (!actual.tenantId().equals(expected.tenantId())
                || !actual.reference().equals(expected.reference())
                || !actual.contentHash().equals(expected.contentHash())
                || !actual.mediaType().equals(expected.mediaType())
                || !Arrays.equals(actual.content(), expected.content())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.STAGING_CONFLICT,
                    "Content-addressed Reference Assembly artifact contains conflicting bytes");
        }
    }

    private static ReferenceAssemblyException stagingConflict(RuntimeException cause) {
        return new ReferenceAssemblyException(
                ReferenceAssemblyException.STAGING_CONFLICT,
                "Reference Assembly artifact staging failed integrity checks",
                cause);
    }

    static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
