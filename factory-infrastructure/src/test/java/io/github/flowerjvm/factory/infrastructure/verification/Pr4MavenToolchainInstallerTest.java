package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pr4MavenToolchainInstallerTest {
    @TempDir Path temporary;
    private final TenantId tenant = new TenantId("tenant-read-only-toolchain");

    @Test
    void absentOrUnpinnedCacheCannotProduceAReadOnlyManifestOrFiles() throws Exception {
        var installer = installer(temporary.resolve("absent"));
        assertThrows(IllegalStateException.class, () -> installer.manifestArtifact(tenant));
        Files.writeString(temporary.resolve("fake.pom"), "not the compiled-in Maven repository");
        var unpinned = installer(temporary);
        assertThrows(IllegalStateException.class, () -> unpinned.manifestArtifact(tenant));
        assertThrows(IllegalStateException.class, () -> unpinned.repositoryArtifacts(tenant, List.of("g/a/1/a-1.pom")));
    }

    @Test
    void traversalAbsoluteDuplicateAndEmptySelectionsFailBeforeCacheAccess() {
        var installer = installer(temporary.resolve("absent"));
        for (var selection : List.of(List.<String>of(), List.of("../a.pom"), List.of("/g/a.pom"),
                List.of("g/../a.pom"), List.of("g/./a.pom"), List.of("C:\\cache\\a.pom"),
                List.of("g/a/1/a-1.pom", "g/a/1/a-1.pom"))) {
            assertThrows(IllegalArgumentException.class, () -> installer.repositoryArtifacts(tenant, selection));
        }
    }

    private Pr4MavenToolchainInstaller installer(Path root) {
        ArtifactStore noAccess = new ArtifactStore() {
            @Override public ArtifactReference store(Artifact artifact) { throw new AssertionError("read-only API stored artifacts"); }
            @Override public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
                throw new AssertionError("read-only API queried artifacts");
            }
        };
        return new Pr4MavenToolchainInstaller(noAccess, new ObjectMapper(), root);
    }
}
