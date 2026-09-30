package io.github.flowerjvm.factory.infrastructure.verification;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.MavenRepositoryFile;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Verifies a separately provisioned, Factory-owned Maven Central cache against one compiled-in
 * manifest hash, then installs only its exact pom/jar bytes into tenant ArtifactStore scope.
 */
public final class Pr4MavenToolchainInstaller {
    public static final ContentHash EXPECTED_HASH = new ContentHash(
            "65ea079185af59593cdbb99d3ed94505a3867cfb2e124e7f982265de7ff8f51d");
    public static final ArtifactReference EXPECTED_REFERENCE = new ArtifactReference(
            "factory-verification/pr4/maven-toolchain/" + EXPECTED_HASH.sha256());
    public static final String CACHE_PROPERTY = "factory.verification.toolchainRepository";
    public static final List<String> ALLOWED_BUILD_PLUGINS = List.of(
            "io.github.flowerjvm:flower-check-maven-plugin:0.1.3",
            "org.apache.maven.plugins:maven-clean-plugin:3.4.1",
            "org.apache.maven.plugins:maven-compiler-plugin:3.14.1",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1",
            "org.apache.maven.plugins:maven-jar-plugin:3.4.2",
            "org.apache.maven.plugins:maven-resources-plugin:3.3.1",
            "org.apache.maven.plugins:maven-surefire-plugin:3.5.2");

    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;
    private final Path trustedCache;
    private final OrdinalSourceTreeHasher hasher = new OrdinalSourceTreeHasher();
    private volatile ToolchainMaterial cached;

    public Pr4MavenToolchainInstaller(ArtifactStore artifacts, ObjectMapper mapper, Path trustedCache) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.trustedCache = Objects.requireNonNull(trustedCache, "trustedCache").toAbsolutePath().normalize();
    }

    public static Path configuredCache() {
        String configured = System.getProperty(CACHE_PROPERTY);
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".m2", "factory-pr4-toolchain-repository")
                : Path.of(configured);
    }

    public InstalledToolchain descriptor() {
        return material().installed();
    }

    /** Read-only, pinned manifest materialization. No ArtifactStore operation is performed. */
    public Artifact manifestArtifact(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return new Artifact(tenantId, EXPECTED_REFERENCE, EXPECTED_HASH,
                "application/json", material().manifestBytes());
    }

    /** Read-only exact selection from the compiled-in repository, never a path-based cache escape. */
    public List<Artifact> repositoryArtifacts(TenantId tenantId, List<String> exactRepositoryPaths) {
        Objects.requireNonNull(tenantId, "tenantId");
        List<String> selected = List.copyOf(Objects.requireNonNull(exactRepositoryPaths, "exactRepositoryPaths"));
        if (selected.isEmpty() || selected.size() > 62 || new HashSet<>(selected).size() != selected.size()
                || selected.stream().anyMatch(path -> path.length() > 1024
                        || !path.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\\.(?:jar|pom)")
                        || List.of(path.split("/")).contains("..") || List.of(path.split("/")).contains("."))) {
            throw new IllegalArgumentException("selected Maven files must be unique bounded exact repository paths");
        }
        List<ToolchainFile> files = material().files().stream()
                .filter(file -> selected.contains(file.entry().path())).toList();
        if (files.size() != selected.size()) {
            throw new IllegalArgumentException("selected Maven file is absent from the compiled-in repository");
        }
        long total = files.stream().mapToLong(file -> file.entry().sizeBytes()).sum();
        if (total > 32L * 1024 * 1024) throw new IllegalArgumentException("selected Maven files exceed input bounds");
        return files.stream().map(file -> readArtifact(tenantId, file)).toList();
    }

    public InstalledToolchain install(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        ToolchainMaterial material = material();
        for (ToolchainFile file : material.files()) {
            artifacts.store(readArtifact(tenantId, file));
        }
        artifacts.store(new Artifact(
                tenantId, EXPECTED_REFERENCE, EXPECTED_HASH, "application/json", material.manifestBytes()));
        return material.installed();
    }

    private Artifact readArtifact(TenantId tenantId, ToolchainFile file) {
        try {
            // Descriptor caching must not allow a subsequently replaced directory/link to be followed.
            for (Path path = file.path(); path != null; path = path.getParent()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (Files.isSymbolicLink(path) || attributes.isOther()
                        || (path.equals(file.path()) ? !attributes.isRegularFile() : !attributes.isDirectory())) {
                    throw new IllegalStateException("trusted PR4 Maven toolchain contains a replaced or special entry");
                }
            }
            byte[] bytes;
            try (var input = Files.newInputStream(file.path(), LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(Math.toIntExact(file.entry().sizeBytes()) + 1);
            }
            if (bytes.length != file.entry().sizeBytes()
                    || !hasher.sha256(bytes).equals(file.entry().contentHash())) {
                throw new IllegalStateException("trusted PR4 Maven toolchain file changed after descriptor verification");
            }
            return new Artifact(tenantId, file.entry().artifactRef(), file.entry().contentHash(),
                    file.path().toString().endsWith(".jar") ? "application/java-archive" : "application/xml", bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("trusted PR4 Maven toolchain file could not be read", exception);
        }
    }

    private ToolchainMaterial material() {
        ToolchainMaterial current = cached;
        if (current != null) return current;
        synchronized (this) {
            if (cached == null) cached = loadAndVerify();
            return cached;
        }
    }

    private ToolchainMaterial loadAndVerify() {
        try {
            for (Path directory = trustedCache; directory != null; directory = directory.getParent()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || Files.isSymbolicLink(directory) || attributes.isOther()) {
                    throw new IllegalStateException("trusted PR4 Maven toolchain cache is unavailable or a reparse point");
                }
            }
            var materialized = new ArrayList<ToolchainFile>();
            long total = 0;
            try (var paths = Files.walk(trustedCache)) {
                for (Path file : paths.sorted(Comparator.comparing(path ->
                        trustedCache.relativize(path).toString().replace('\\', '/'))).toList()) {
                    if (file.equals(trustedCache)) continue;
                    BasicFileAttributes attributes = Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (Files.isSymbolicLink(file) || attributes.isOther()) {
                        throw new IllegalStateException("trusted PR4 Maven toolchain contains a reparse/special entry");
                    }
                    if (!attributes.isRegularFile()) continue;
                    String relative = trustedCache.relativize(file).toString().replace('\\', '/');
                    if (!relative.endsWith(".pom") && !relative.endsWith(".jar")) continue;
                    if (attributes.size() < 1 || attributes.size() > CuratedMavenRepositoryMaterializer.MAX_FILE_BYTES) {
                        throw new IllegalStateException("trusted PR4 Maven toolchain file exceeds bounds");
                    }
                    total = Math.addExact(total, attributes.size());
                    if (materialized.size() >= CuratedMavenRepositoryMaterializer.MAX_FILES
                            || total > CuratedMavenRepositoryMaterializer.MAX_TOTAL_BYTES) {
                        throw new IllegalStateException("trusted PR4 Maven toolchain exceeds repository bounds");
                    }
                    byte[] bytes;
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        bytes = input.readNBytes(Math.toIntExact(attributes.size()) + 1);
                    }
                    if (bytes.length != attributes.size()) {
                        throw new IllegalStateException("trusted PR4 Maven toolchain changed during descriptor verification");
                    }
                    ContentHash hash = hasher.sha256(bytes);
                    var entry = new MavenRepositoryFile(
                            relative,
                            new ArtifactReference("factory-verification/pr4/maven-toolchain/file/" + hash.sha256()),
                            hash,
                            bytes.length);
                    materialized.add(new ToolchainFile(file, entry));
                }
            }
            var entries = materialized.stream().map(ToolchainFile::entry).toList();
            var lock = new MavenToolchainLock(
                    MavenToolchainLock.SCHEMA_VERSION,
                    MavenToolchainLock.REPOSITORY_ID,
                    MavenToolchainLock.REPOSITORY_URL,
                    ALLOWED_BUILD_PLUGINS,
                    entries.size(), total, entries);
            byte[] manifest = mapper.writeValueAsBytes(lock);
            ContentHash actual = hasher.sha256(manifest);
            if (!actual.equals(EXPECTED_HASH)) {
                throw new IllegalStateException("trusted PR4 Maven toolchain does not match the compiled-in Central lock");
            }
            return new ToolchainMaterial(
                    new InstalledToolchain(EXPECTED_REFERENCE, EXPECTED_HASH), manifest, materialized);
        } catch (IOException | ArithmeticException exception) {
            throw new IllegalStateException("trusted PR4 Maven toolchain could not be verified", exception);
        }
    }

    public record InstalledToolchain(ArtifactReference reference, ContentHash hash) {}
    private record ToolchainMaterial(
            InstalledToolchain installed, byte[] manifestBytes, List<ToolchainFile> files) {
        private ToolchainMaterial { manifestBytes = manifestBytes.clone(); files = List.copyOf(files); }
        @Override public byte[] manifestBytes() { return manifestBytes.clone(); }
    }
    private record ToolchainFile(Path path, MavenRepositoryFile entry) {}
}
