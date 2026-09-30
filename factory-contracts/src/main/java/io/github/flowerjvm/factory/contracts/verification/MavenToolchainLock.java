package io.github.flowerjvm.factory.contracts.verification;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Factory-owned immutable Maven verifier toolchain and its complete repository file set. */
public record MavenToolchainLock(
        String schemaVersion,
        String repositoryId,
        String repositoryUrl,
        List<String> allowedBuildPlugins,
        int fileCount,
        long totalBytes,
        List<MavenRepositoryFile> files) {

    public static final String SCHEMA_VERSION = "factory.maven-toolchain-lock.v1";
    public static final String REPOSITORY_ID = MavenDependencyLock.REPOSITORY_ID;
    public static final String REPOSITORY_URL = MavenDependencyLock.REPOSITORY_URL;
    private static final Pattern PLUGIN = Pattern.compile(
            "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+");

    public MavenToolchainLock {
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !REPOSITORY_ID.equals(repositoryId)
                || !REPOSITORY_URL.equals(repositoryUrl)) {
            throw new IllegalArgumentException("unsupported Maven toolchain lock identity");
        }
        allowedBuildPlugins = List.copyOf(Objects.requireNonNull(allowedBuildPlugins, "allowedBuildPlugins"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        var pluginKeys = new HashSet<String>();
        if (allowedBuildPlugins.isEmpty()
                || new HashSet<>(allowedBuildPlugins).size() != allowedBuildPlugins.size()
                || allowedBuildPlugins.stream().anyMatch(value -> value == null
                        || !PLUGIN.matcher(value).matches()
                        || value.toUpperCase(Locale.ROOT).contains("SNAPSHOT")
                        || value.toUpperCase(Locale.ROOT).contains("LATEST")
                        || !pluginKeys.add(value.substring(0, value.lastIndexOf(':'))))) {
            throw new IllegalArgumentException("toolchain plugins must be unique exact release coordinates");
        }
        if (fileCount != files.size() || fileCount < 1) {
            throw new IllegalArgumentException("fileCount must equal the non-empty files list");
        }
        long computedTotal = 0;
        var paths = new HashSet<String>();
        for (MavenRepositoryFile file : files) {
            computedTotal = Math.addExact(computedTotal, file.sizeBytes());
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("Maven toolchain repository paths must be unique");
            }
        }
        if (computedTotal != totalBytes) {
            throw new IllegalArgumentException("totalBytes must equal the toolchain repository file sizes");
        }
    }
}
