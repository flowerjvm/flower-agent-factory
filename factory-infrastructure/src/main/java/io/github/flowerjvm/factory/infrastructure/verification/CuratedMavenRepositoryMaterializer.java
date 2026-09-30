package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.ARTIFACT_CHECKSUM_MISMATCH;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.DEPENDENCY_LOCK_INVALID;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.MANIFEST_REFERENCE_UNRESOLVED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_QUOTA_EXCEEDED;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.MavenDependencyLock;
import io.github.flowerjvm.factory.contracts.verification.MavenRepositoryFile;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;

/** Builds a bounded, per-run repository solely from immutable lock-listed ArtifactStore bytes. */
final class CuratedMavenRepositoryMaterializer {
    static final int MAX_LOCK_BYTES = 4 * 1024 * 1024;
    static final int MAX_FILES = 2_048;
    static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;
    private final OrdinalSourceTreeHasher hasher;

    CuratedMavenRepositoryMaterializer(ArtifactStore artifacts, ObjectMapper mapper, OrdinalSourceTreeHasher hasher) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    MaterializedLocks materialize(
            VerificationLockInput dependencyInput,
            VerificationLockInput toolchainInput,
            Path repository)
            throws CandidateMaterializationException {
        MavenDependencyLock dependencyLock = readLock(
                dependencyInput, MavenDependencyLock.class, "dependency");
        MavenToolchainLock toolchainLock = readLock(
                toolchainInput, MavenToolchainLock.class, "toolchain");
        long aggregateBytes;
        try {
            aggregateBytes = Math.addExact(dependencyLock.totalBytes(), toolchainLock.totalBytes());
        } catch (ArithmeticException exception) {
            throw failure(WORKSPACE_QUOTA_EXCEEDED, "curated Maven repository size overflowed");
        }
        if (dependencyLock.fileCount() > MAX_FILES
                || toolchainLock.fileCount() > MAX_FILES
                || dependencyLock.fileCount() + (long) toolchainLock.fileCount() > MAX_FILES
                || aggregateBytes > MAX_TOTAL_BYTES) {
            throw failure(WORKSPACE_QUOTA_EXCEEDED, "curated Maven repository exceeds fixed bounds");
        }

        var selected = new LinkedHashMap<String, MavenRepositoryFile>();
        // Toolchain entries are authoritative. Candidate dependencies may share only identical bytes at a path.
        for (MavenRepositoryFile file : toolchainLock.files()) {
            selected.put(file.path(), file);
        }
        for (MavenRepositoryFile file : dependencyLock.files()) {
            MavenRepositoryFile protectedFile = selected.get(file.path());
            if (protectedFile != null
                    && (!protectedFile.contentHash().equals(file.contentHash())
                    || protectedFile.sizeBytes() != file.sizeBytes())) {
                throw failure(DEPENDENCY_LOCK_INVALID, "dependency lock attempted to override trusted toolchain bytes");
            }
            selected.putIfAbsent(file.path(), file);
        }
        if (selected.size() > MAX_FILES
                || selected.values().stream().mapToLong(MavenRepositoryFile::sizeBytes).sum() > MAX_TOTAL_BYTES) {
            throw failure(WORKSPACE_QUOTA_EXCEEDED, "curated Maven repository union exceeds fixed bounds");
        }
        validatePaths(selected.values());
        writeVerifiedRepository(dependencyInput.tenantId(), repository, selected.values());
        return new MaterializedLocks(dependencyLock, toolchainLock);
    }

    private <T> T readLock(VerificationLockInput input, Class<T> type, String kind)
            throws CandidateMaterializationException {
        var artifact = artifacts.find(input.tenantId(), input.reference()).orElseThrow(() ->
                failure(MANIFEST_REFERENCE_UNRESOLVED, kind + " lock artifact is unavailable"));
        byte[] lockBytes = artifact.content();
        if (lockBytes.length > MAX_LOCK_BYTES
                || !"application/json".equals(artifact.mediaType())
                || !artifact.tenantId().equals(input.tenantId())
                || !artifact.reference().equals(input.reference())
                || !artifact.contentHash().equals(input.hash())
                || !hasher.sha256(lockBytes).equals(input.hash())) {
            throw failure(ARTIFACT_CHECKSUM_MISMATCH, kind + " lock bytes do not match its pinned identity");
        }
        try {
            return mapper.readValue(lockBytes, type);
        } catch (IOException | RuntimeException exception) {
            throw new CandidateMaterializationException(
                    DEPENDENCY_LOCK_INVALID, kind + " lock is not strict v1 JSON", exception);
        }
    }

    private static void validatePaths(java.util.Collection<MavenRepositoryFile> files)
            throws CandidateMaterializationException {
        var paths = new HashSet<String>();
        var folded = new HashSet<String>();
        for (MavenRepositoryFile file : files) {
            validatePath(file.path(), paths, folded);
            if (file.sizeBytes() > MAX_FILE_BYTES) {
                throw failure(WORKSPACE_QUOTA_EXCEEDED, "curated Maven file exceeds fixed bounds");
            }
        }
    }

    private void writeVerifiedRepository(
            TenantId tenantId, Path repository, java.util.Collection<MavenRepositoryFile> files)
            throws CandidateMaterializationException {
        try {
            Files.createDirectory(repository);
        } catch (IOException exception) {
            throw new CandidateMaterializationException(
                    DEPENDENCY_LOCK_INVALID, "curated Maven repository could not be created", exception);
        }
        for (MavenRepositoryFile file : files) {
            var stored = artifacts.find(tenantId, file.artifactRef()).orElseThrow(() ->
                    failure(MANIFEST_REFERENCE_UNRESOLVED, "curated Maven artifact is unavailable"));
            byte[] bytes = stored.content();
            if (!stored.reference().equals(file.artifactRef())
                    || !stored.tenantId().equals(tenantId)
                    || !stored.contentHash().equals(file.contentHash())
                    || !hasher.sha256(bytes).equals(file.contentHash())
                    || bytes.length != file.sizeBytes()) {
                throw failure(ARTIFACT_CHECKSUM_MISMATCH, "curated Maven artifact differs from its lock entry");
            }
            try {
                Path output = resolve(repository, file.path());
                Files.createDirectories(output.getParent());
                Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
                    throw failure(DEPENDENCY_LOCK_INVALID, "curated Maven output is not a regular file");
                }
                byte[] written = Files.readAllBytes(output);
                if (written.length != file.sizeBytes() || !hasher.sha256(written).equals(file.contentHash())) {
                    throw failure(ARTIFACT_CHECKSUM_MISMATCH, "curated Maven output changed during materialization");
                }
            } catch (IOException exception) {
                throw new CandidateMaterializationException(
                        DEPENDENCY_LOCK_INVALID, "curated Maven repository file could not be materialized", exception);
            }
        }
    }

    private static void validatePath(String path, HashSet<String> paths, HashSet<String> folded)
            throws CandidateMaterializationException {
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.length() > 512) {
            throw failure(DEPENDENCY_LOCK_INVALID, "dependency repository path is not canonical");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.chars().anyMatch(Character::isISOControl)
                    || segment.matches("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) {
                throw failure(DEPENDENCY_LOCK_INVALID, "dependency repository path contains traversal");
            }
        }
        if (!paths.add(path) || !folded.add(path.toLowerCase(Locale.ROOT))) {
            throw failure(DEPENDENCY_LOCK_INVALID, "dependency repository paths collide");
        }
        String extension = path.substring(path.lastIndexOf('.') + 1);
        if (!java.util.Set.of("pom", "jar").contains(extension)) {
            throw failure(DEPENDENCY_LOCK_INVALID, "dependency repository file type is not allowed");
        }
    }

    private static Path resolve(Path root, String path) throws CandidateMaterializationException {
        Path output = root;
        for (String segment : path.split("/")) {
            output = output.resolve(segment);
        }
        output = output.normalize();
        if (!output.startsWith(root) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(DEPENDENCY_LOCK_INVALID, "dependency repository path escaped or collided");
        }
        return output;
    }

    private static CandidateMaterializationException failure(String code, String message) {
        return new CandidateMaterializationException(code, message);
    }

    record VerificationLockInput(TenantId tenantId, io.github.flowerjvm.factory.contracts.artifact.ArtifactReference reference,
                                 ContentHash hash) {}

    record MaterializedLocks(MavenDependencyLock dependencyLock, MavenToolchainLock toolchainLock) {}

}
