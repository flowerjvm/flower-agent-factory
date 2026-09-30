package io.github.flowerjvm.factory.contracts.verification;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable Maven Central input lock used to build one read-only per-verification repository. */
public record MavenDependencyLock(
        String schemaVersion,
        String repositoryId,
        String repositoryUrl,
        String graphAlgorithmId,
        List<String> expectedDependencyCoordinates,
        int fileCount,
        long totalBytes,
        List<MavenRepositoryFile> files) {

    public static final String SCHEMA_VERSION = "factory.maven-dependency-lock.v1";
    public static final String REPOSITORY_ID = "central";
    public static final String REPOSITORY_URL = "https://repo.maven.apache.org/maven2";
    public static final String GRAPH_ALGORITHM_ID = "factory.maven-coordinate-set.v1";
    private static final Pattern COORDINATE = Pattern.compile(
            "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?:[A-Za-z0-9_.-]+");

    public MavenDependencyLock {
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !REPOSITORY_ID.equals(repositoryId)
                || !REPOSITORY_URL.equals(repositoryUrl)
                || !GRAPH_ALGORITHM_ID.equals(graphAlgorithmId)) {
            throw new IllegalArgumentException("unsupported Maven dependency lock identity");
        }
        expectedDependencyCoordinates = List.copyOf(
                Objects.requireNonNull(expectedDependencyCoordinates, "expectedDependencyCoordinates"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (fileCount != files.size() || fileCount < 1) {
            throw new IllegalArgumentException("fileCount must equal the non-empty files list");
        }
        long computedTotal = 0;
        var paths = new HashSet<String>();
        for (MavenRepositoryFile file : files) {
            computedTotal = Math.addExact(computedTotal, file.sizeBytes());
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("Maven repository paths must be unique");
            }
        }
        if (computedTotal != totalBytes) {
            throw new IllegalArgumentException("totalBytes must equal the repository file sizes");
        }
        if (new HashSet<>(expectedDependencyCoordinates).size() != expectedDependencyCoordinates.size()
                || expectedDependencyCoordinates.isEmpty()
                || expectedDependencyCoordinates.stream().anyMatch(value -> value == null
                        || !COORDINATE.matcher(value).matches()
                        || value.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT")
                        || value.toUpperCase(java.util.Locale.ROOT).contains("LATEST")
                        || value.contains("[")
                        || value.contains("(")
                        || value.contains("]")
                        || value.contains(")"))) {
            throw new IllegalArgumentException("dependency coordinates must be unique and non-blank");
        }
    }
}
