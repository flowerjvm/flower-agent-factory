package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;

/** Strict v1 lock for every regular file that forms one immutable candidate. */
public record CandidateSourceManifest(
        String schemaVersion,
        CandidateId candidateId,
        BuildSessionId buildSessionId,
        String sourceLockAlgorithmId,
        ContentHash candidateHash,
        int fileCount,
        long totalBytes,
        List<CandidateSourceEntry> files) {

    public static final String SCHEMA_VERSION = "factory.candidate-source-manifest.v1";
    public static final String SOURCE_LOCK_ALGORITHM_ID = "factory.ordinal-sha256.v1";
    public static final int MAX_FILE_COUNT = 4_096;
    public static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;

    public CandidateSourceManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported candidate source manifest schemaVersion");
        }
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        if (!SOURCE_LOCK_ALGORITHM_ID.equals(sourceLockAlgorithmId)) {
            throw new IllegalArgumentException("unsupported sourceLockAlgorithmId");
        }
        Objects.requireNonNull(candidateHash, "candidateHash");
        if (fileCount < 0 || fileCount > MAX_FILE_COUNT) {
            throw new IllegalArgumentException("fileCount exceeds the stable candidate bound");
        }
        if (totalBytes < 0 || totalBytes > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("totalBytes exceeds the stable candidate bound");
        }
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (fileCount != files.size()) {
            throw new IllegalArgumentException("fileCount must equal files.size");
        }
        long declaredBytes;
        try {
            declaredBytes = files.stream().mapToLong(CandidateSourceEntry::sizeBytes).reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("candidate file sizes overflow", exception);
        }
        if (totalBytes != declaredBytes) {
            throw new IllegalArgumentException("totalBytes must equal the sum of file sizes");
        }
        var paths = new HashSet<String>();
        var caseFoldedPaths = new HashSet<String>();
        for (CandidateSourceEntry file : files) {
            if (!paths.add(file.path())
                    || !caseFoldedPaths.add(PortableRelativePath.caseFold(file.path()))) {
                throw new IllegalArgumentException(
                        "candidate source paths must be unique without portable case collisions");
            }
        }
    }
}
