package io.github.flowerjvm.factory.infrastructure.verification;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixture;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixtureSet;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Installs checked-in host verification policy bytes as immutable tenant-scoped artifacts. */
public final class Pr4VerificationFixtureInstaller {
    public static final TenantId SYSTEM_TENANT = new TenantId("factory-system");
    private static final List<Resource> RESOURCES = List.of(
            new Resource("pom.xml", "aecb6fadc5eb43da5037a9e32c609929e3200cc76fb4cc32acd7ded31cafd234", 232),
            new Resource("src/main/java/fixture/SeededBlockingStep.java", "f94bc9fcebd70ce2329443d41729df5c1c019020031c5fc8ce8a38df59b1aa4b", 464));

    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;
    private final OrdinalSourceTreeHasher hasher = new OrdinalSourceTreeHasher();

    public Pr4VerificationFixtureInstaller(ArtifactStore artifacts, ObjectMapper mapper) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public InstalledFixtureSet install(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        FixtureMaterial material = material();
        for (FixtureFile file : material.files()) {
            artifacts.store(new Artifact(
                    tenantId, file.fixture().artifactRef(), file.fixture().contentHash(),
                    "text/plain; charset=utf-8", file.bytes()));
        }
        artifacts.store(new Artifact(
                tenantId, material.installed().reference(), material.installed().hash(),
                "application/json", material.manifestBytes()));
        return material.installed();
    }

    /** Returns the immutable host policy identity without requiring tenant storage side effects. */
    public InstalledFixtureSet descriptor() {
        return material().installed();
    }

    private FixtureMaterial material() {
        var fixtureFiles = new ArrayList<FixtureFile>();
        var fixtures = new ArrayList<VerificationFixture>();
        for (Resource resource : RESOURCES) {
            byte[] bytes = read(resource.path());
            ContentHash hash = hasher.sha256(bytes);
            if (!hash.sha256().equals(resource.hash()) || bytes.length != resource.size()) {
                throw new IllegalStateException("checked-in PR4 fixture bytes changed without a fixture version update");
            }
            ArtifactReference reference = new ArtifactReference("factory-verification/pr4/seeded-blocking-step/" + resource.path());
            var fixture = new VerificationFixture(
                    "seeded-blocking-step", resource.path(), reference, hash, bytes.length, "FLOWER-CHECK-001");
            fixtures.add(fixture);
            fixtureFiles.add(new FixtureFile(fixture, bytes));
        }
        try {
            byte[] manifest = mapper.writeValueAsBytes(
                    new VerificationFixtureSet(VerificationFixtureSet.SCHEMA_VERSION, fixtures));
            ContentHash hash = hasher.sha256(manifest);
            ArtifactReference reference = new ArtifactReference("factory-verification/pr4/fixture-set/" + hash.sha256());
            return new FixtureMaterial(
                    new InstalledFixtureSet(reference, hash), manifest, List.copyOf(fixtureFiles));
        } catch (IOException exception) {
            throw new IllegalStateException("PR4 fixture manifest could not be encoded", exception);
        }
    }

    private static byte[] read(String path) {
        String resource = "/factory-verification/pr4/seeded-blocking-step/" + path;
        try (InputStream input = Pr4VerificationFixtureInstaller.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing checked-in PR4 fixture " + resource);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("checked-in PR4 fixture could not be read", exception);
        }
    }

    public record InstalledFixtureSet(ArtifactReference reference, ContentHash hash) {}
    private record FixtureMaterial(InstalledFixtureSet installed, byte[] manifestBytes, List<FixtureFile> files) {
        private FixtureMaterial { manifestBytes = manifestBytes.clone(); files = List.copyOf(files); }
        @Override public byte[] manifestBytes() { return manifestBytes.clone(); }
    }
    private record FixtureFile(VerificationFixture fixture, byte[] bytes) {
        private FixtureFile { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private record Resource(String path, String hash, int size) {}
}
