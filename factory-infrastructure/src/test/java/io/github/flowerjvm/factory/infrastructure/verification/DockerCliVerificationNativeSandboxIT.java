package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.verification.MavenDependencyLock;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import io.github.flowerjvm.factory.contracts.verification.VerificationRequest;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixtureSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in native proof for the pinned Docker sandbox; the explicit profile fails if Docker is unavailable. */
class DockerCliVerificationNativeSandboxIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void seededFixtureProducesExactBlockingRuleEvidence() throws Exception {
        var store = new MemoryArtifacts();
        var mapper = new ObjectMapper();
        var tenant = new TenantId("tenant-native-fixture");
        var installed = new Pr4VerificationFixtureInstaller(store, mapper).install(tenant);
        VerificationFixtureSet set = mapper.readValue(
                store.required(installed.reference()).content(), VerificationFixtureSet.class);
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("seeded-fixture"));
        for (var fixture : set.fixtures()) {
            Path output = workspace.resolve(fixture.path());
            Files.createDirectories(output.getParent());
            Files.write(output, store.required(fixture.artifactRef()).content());
        }

        var execution = sandbox().execute(workspace, curatedRepository(), VerificationCommand.FLOWER_CHECK);
        String sarif = new String(
                execution.evidenceFiles().getOrDefault("flower-check.sarif", new byte[0]),
                StandardCharsets.UTF_8);

        assertTrue(execution.exitCode() != 0, text(execution));
        assertTrue(sarif.contains("FLOWER-CHECK-001"),
                "exit=" + execution.exitCode() + " evidence=" + execution.evidenceFiles().keySet()
                        + "\n" + text(execution) + "\n" + sarif);
    }

    @Test
    void runsRealMavenTestsAndStrictFlowerCheckWithoutNetwork() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("valid"));
        writeMinimalFlowerProject(workspace, false);
        var sandbox = sandbox();

        Path repository = curatedRepository();
        var verify = sandbox.execute(workspace, repository, VerificationCommand.MAVEN_VERIFY);
        var flowerCheck = sandbox.execute(workspace, repository, VerificationCommand.FLOWER_CHECK);
        var dependencyTree = sandbox.execute(workspace, repository, VerificationCommand.MAVEN_DEPENDENCY_TREE);

        assertEquals(0, verify.exitCode(), text(verify));
        assertTrue(verify.evidenceFiles().keySet().stream().anyMatch(value -> value.startsWith("surefire/")), text(verify));
        assertEquals(0, flowerCheck.exitCode(), text(flowerCheck));
        assertTrue(flowerCheck.evidenceFiles().containsKey("flower-check.sarif"), text(flowerCheck));
        assertFalse(text(flowerCheck).contains("no Java source roots"), text(flowerCheck));
        assertFalse(text(flowerCheck).contains("FLOWER-CHECK-PARSE"), text(flowerCheck));
        assertEquals(0, dependencyTree.exitCode(), text(dependencyTree));
        assertTrue(new String(dependencyTree.evidenceFiles().get("dependency-tree.txt"), StandardCharsets.UTF_8)
                .contains("io.github.flowerjvm:flower-core:jar:0.1.3"), text(dependencyTree));
    }

    @Test
    void readOnlyRootBlocksOutsideWorkspaceWriteAndLeavesHostCanaryUnchanged() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("outside-write"));
        writeMinimalFlowerProject(workspace, true);
        Path hostCanary = temporaryDirectory.resolve("host-canary.txt");
        Files.writeString(hostCanary, "unchanged", StandardCharsets.UTF_8);

        var execution = sandbox().execute(workspace, curatedRepository(), VerificationCommand.MAVEN_VERIFY);

        assertTrue(execution.exitCode() != 0, text(execution));
        assertTrue(text(execution).contains("Read-only file system") || text(execution).contains("Permission denied"), text(execution));
        assertEquals("unchanged", Files.readString(hostCanary, StandardCharsets.UTF_8));
        assertFalse(Files.exists(workspace.resolve("escaped.txt")));
    }

    @Test
    void candidateCannotMutateReadOnlySourceMount() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("source-mutation"));
        writeMinimalFlowerProject(workspace, false, true);
        String originalPom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);

        var execution = sandbox().execute(workspace, curatedRepository(), VerificationCommand.MAVEN_VERIFY);

        assertTrue(execution.exitCode() != 0, text(execution));
        assertEquals(originalPom, Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8));
    }

    @Test
    void inContainerTimeoutBoundsAbandonedClientExecution() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("internal-timeout"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        var sandbox = new DockerCliVerificationSandbox("docker", Duration.ofSeconds(1), 256 * 1024);
        List<String> arguments = sandbox.dockerArguments(
                "factory-timeout-proof", workspace, curatedRepository(), VerificationCommand.MAVEN_VERIFY);
        List<String> execution = sandbox.dockerExecArguments(
                "factory-timeout-proof", VerificationCommand.MAVEN_VERIFY);

        assertTrue(arguments.contains("46s"));
        assertTrue(execution.contains("1s"));
        assertTrue(execution.stream().anyMatch(value -> value.contains(
                "/usr/bin/timeout --signal=TERM --kill-after=10s")));
    }

    @Test
    void actualVerifierUsesTrustedCuratedRepositoryFixtureSelfTestAndMachineEvidence() throws Exception {
        actualVerifierAndReadGate(false);
    }

    @Test
    void maintenanceVerifierRequiresRealGoldenEvidenceAndRejectsTamperingOrPr4OnlyResult() throws Exception {
        actualVerifierAndReadGate(true);
    }

    private void actualVerifierAndReadGate(boolean maintenance) throws Exception {
        var store = new MemoryArtifacts();
        var mapper = new ObjectMapper();
        var hasher = new OrdinalSourceTreeHasher();
        var tenant = new TenantId("tenant-native-verifier");
        var toolchainInstaller = new Pr4MavenToolchainInstaller(store, mapper, curatedRepository());
        var installedToolchain = toolchainInstaller.install(tenant);
        assertEquals(Pr4MavenToolchainInstaller.EXPECTED_HASH, installedToolchain.hash());

        MavenToolchainLock toolchain = mapper.readValue(
                store.required(installedToolchain.reference()).content(), MavenToolchainLock.class);
        List<String> dependencyPrefixes = List.of(
                "io/github/flowerjvm/flower-core/0.1.3/",
                "org/junit/jupiter/junit-jupiter/5.12.2/",
                "org/junit/jupiter/junit-jupiter-api/5.12.2/",
                "org/junit/jupiter/junit-jupiter-params/5.12.2/",
                "org/junit/jupiter/junit-jupiter-engine/5.12.2/",
                "org/junit/platform/junit-platform-commons/1.12.2/",
                "org/junit/platform/junit-platform-engine/1.12.2/",
                "org/opentest4j/opentest4j/1.3.0/",
                "org/apiguardian/apiguardian-api/1.1.2/");
        var dependencyFiles = toolchain.files().stream()
                .filter(file -> dependencyPrefixes.stream().anyMatch(file.path()::startsWith)).toList();
        List<String> expectedCoordinates = List.of(
                "io.github.flowerjvm:flower-core:jar:0.1.3",
                "org.apiguardian:apiguardian-api:jar:1.1.2",
                "org.junit.jupiter:junit-jupiter-api:jar:5.12.2",
                "org.junit.jupiter:junit-jupiter-engine:jar:5.12.2",
                "org.junit.jupiter:junit-jupiter-params:jar:5.12.2",
                "org.junit.jupiter:junit-jupiter:jar:5.12.2",
                "org.junit.platform:junit-platform-commons:jar:1.12.2",
                "org.junit.platform:junit-platform-engine:jar:1.12.2",
                "org.opentest4j:opentest4j:jar:1.3.0");
        var dependencyLock = new MavenDependencyLock(
                MavenDependencyLock.SCHEMA_VERSION, MavenDependencyLock.REPOSITORY_ID,
                MavenDependencyLock.REPOSITORY_URL, MavenDependencyLock.GRAPH_ALGORITHM_ID,
                expectedCoordinates, dependencyFiles.size(),
                dependencyFiles.stream().mapToLong(file -> file.sizeBytes()).sum(), dependencyFiles);
        byte[] dependencyBytes = mapper.copy()
                .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(dependencyLock);
        ContentHash dependencyHash = hasher.sha256(dependencyBytes);
        ArtifactReference dependencyRef = new ArtifactReference("native/dependency-lock/" + dependencyHash.sha256());
        store.store(new Artifact(tenant, dependencyRef, dependencyHash, "application/json", dependencyBytes));

        Path candidateSource = Files.createDirectory(temporaryDirectory.resolve("actual-verifier-source"));
        if (maintenance) {
            MaintenanceAcceptanceTestCandidates.write(candidateSource, MaintenanceAcceptanceTestCandidates.Mutation.NONE);
        } else {
            writeMinimalFlowerProject(candidateSource, false);
        }
        var sourceEntries = new ArrayList<CandidateSourceEntry>();
        try (var paths = Files.walk(candidateSource)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(file);
                ContentHash hash = hasher.sha256(bytes);
                ArtifactReference ref = new ArtifactReference("native/source/" + hash.sha256());
                store.store(new Artifact(tenant, ref, hash, "text/plain", bytes));
                sourceEntries.add(new CandidateSourceEntry(
                        candidateSource.relativize(file).toString().replace('\\', '/'), ref, hash, bytes.length));
            }
        }
        var candidateId = new CandidateId("candidate-native-verifier");
        var buildSessionId = new BuildSessionId("session-native-verifier");
        ContentHash candidateHash = hasher.hashEntries(sourceEntries);
        var sourceManifest = new CandidateSourceManifest(
                CandidateSourceManifest.SCHEMA_VERSION, candidateId, buildSessionId,
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID, candidateHash,
                sourceEntries.size(), sourceEntries.stream().mapToLong(CandidateSourceEntry::sizeBytes).sum(), sourceEntries);
        byte[] sourceManifestBytes = mapper.writeValueAsBytes(sourceManifest);
        ContentHash sourceManifestHash = hasher.sha256(sourceManifestBytes);
        ArtifactReference sourceManifestRef = new ArtifactReference("native/source-manifest/" + sourceManifestHash.sha256());
        store.store(new Artifact(tenant, sourceManifestRef, sourceManifestHash, "application/json", sourceManifestBytes));
        var fixtures = new Pr4VerificationFixtureInstaller(store, mapper).descriptor();
        var request = new VerificationRequest(
                tenant, new VerificationRunId("verification-native-verifier"), buildSessionId, candidateId,
                sourceManifestRef, candidateHash, dependencyRef, dependencyHash,
                installedToolchain.reference(), maintenance
                        ? io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract.GATE_PROFILE
                        : "factory-v0.1-pr4", installedToolchain.hash(),
                fixtures.reference(), fixtures.hash());
        var verifier = new IndependentMavenVerifier(
                store, mapper, new DockerCliVerificationSandbox(),
                temporaryDirectory.resolve("actual-verifier-workspaces"), toolchainInstaller);

        var result = verifier.verify(request);

        assertEquals(VerificationStatus.PASSED, result.status(), result.stableCodes().toString());
        assertEquals(maintenance ? 10 : 7, result.evidenceArtifacts().size());
        var candidate = new io.github.flowerjvm.factory.application.candidate.CandidateVersion(
                candidateId, tenant, buildSessionId, Optional.empty(), sourceManifestRef, candidateHash,
                dependencyRef, dependencyHash, installedToolchain.reference(), installedToolchain.hash(),
                io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus.GENERATED,
                new io.github.flowerjvm.factory.contracts.ids.WorkOrderId("native-generation-order"),
                java.time.Instant.parse("2026-09-06T00:00:00Z"));
        var candidates = new SingleCandidate(candidate);
        var validator = new ArtifactVerificationEvidenceValidator(store, candidates, mapper,
                fixtures.reference(), fixtures.hash(), installedToolchain.reference(), installedToolchain.hash());
        var created = java.time.Instant.parse("2026-09-06T00:00:00Z");
        var run = new io.github.flowerjvm.factory.application.verification.VerificationRun(
                request.verificationRunId(), tenant, buildSessionId, candidateId, candidateHash,
                request.gateProfile(), installedToolchain.hash(), fixtures.hash(),
                io.github.flowerjvm.factory.application.verification.VerificationRunStatus.PASSED,
                Optional.of(result.resultManifestRef()), Optional.of(result.resultManifestHash()),
                Optional.of("VERIFIED"), Optional.of(result.disposition()),
                Optional.of(created.plusSeconds(1)), Optional.of(created.plusSeconds(2)), 2, created, created.plusSeconds(2));
        assertTrue(validator.isReviewEligible(run), "real complete evidence must pass the independent read gate");
        if (maintenance) {
            Artifact actual = store.required(result.evidenceArtifacts().getLast());
            String actualKey = tenant.value() + "\n" + actual.reference().value();
            store.values.put(actualKey, new Artifact(tenant, actual.reference(), hasher.sha256("{}".getBytes()),
                    "application/json", "{}".getBytes()));
            assertFalse(validator.isReviewEligible(run), "a valid new hash cannot hide incorrect actual evidence");
            store.values.put(actualKey, actual);
            assertTrue(validator.isReviewEligible(run));
            var manifest = mapper.readValue(store.required(result.resultManifestRef()).content(),
                    io.github.flowerjvm.factory.contracts.verification.VerificationResultManifest.class);
            var acceptance = manifest.commands().getLast();
            ContentHash wrongMainHash = hasher.sha256("different candidate main".getBytes(StandardCharsets.UTF_8));
            var forgedActual = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(actual.content());
            forgedActual.put("mainSourceHash", wrongMainHash.sha256());
            byte[] forgedActualBytes = mapper.writeValueAsBytes(forgedActual);
            ContentHash forgedActualHash = hasher.sha256(forgedActualBytes);
            ArtifactReference forgedActualRef = new ArtifactReference("native/wrong-main-actual/" + forgedActualHash.sha256());
            store.store(new Artifact(tenant, forgedActualRef, forgedActualHash, "application/json", forgedActualBytes));
            var forgedSummary = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                    store.required(acceptance.machineEvidenceRef()).content());
            assertFalse(wrongMainHash.sha256().equals(forgedSummary.path("mainSourceHash").asText()));
            forgedSummary.put("mainSourceHash", wrongMainHash.sha256());
            forgedSummary.put("actualHash", forgedActualHash.sha256());
            forgedSummary.put("probeHash", hasher.sha256(MaintenanceAcceptanceGate.probeBytes(
                    candidateHash, wrongMainHash, mapper)).sha256());
            byte[] forgedSummaryBytes = mapper.writeValueAsBytes(forgedSummary);
            assertTrue(MaintenanceAcceptanceGate.evidenceMatches(
                    forgedSummaryBytes, forgedActualBytes, candidateHash, mapper),
                    "the forged evidence is internally consistent and still matches every golden output");
            ContentHash forgedSummaryHash = hasher.sha256(forgedSummaryBytes);
            ArtifactReference forgedSummaryRef = new ArtifactReference("native/wrong-main-summary/" + forgedSummaryHash.sha256());
            store.store(new Artifact(tenant, forgedSummaryRef, forgedSummaryHash, "application/json", forgedSummaryBytes));
            var forgedCommand = new io.github.flowerjvm.factory.contracts.verification.VerificationCommandEvidence(
                    acceptance.commandId(), acceptance.arguments(), acceptance.exitCode(), acceptance.testCount(),
                    acceptance.flowerCheckFindingCount(), acceptance.rawLogRef(), acceptance.rawLogHash(),
                    forgedSummaryRef, forgedSummaryHash);
            var forgedManifest = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(manifest);
            ((com.fasterxml.jackson.databind.node.ArrayNode) forgedManifest.path("commands"))
                    .set(3, mapper.valueToTree(forgedCommand));
            var forgedEvidenceRefs = (com.fasterxml.jackson.databind.node.ArrayNode) forgedManifest.path("evidenceArtifacts");
            forgedEvidenceRefs.set(8, mapper.valueToTree(forgedSummaryRef));
            forgedEvidenceRefs.set(9, mapper.valueToTree(forgedActualRef));
            byte[] forgedManifestBytes = mapper.writeValueAsBytes(forgedManifest);
            ContentHash forgedManifestHash = hasher.sha256(forgedManifestBytes);
            ArtifactReference forgedManifestRef = new ArtifactReference("native/wrong-main-manifest/" + forgedManifestHash.sha256());
            store.store(new Artifact(tenant, forgedManifestRef, forgedManifestHash, "application/json", forgedManifestBytes));
            var forgedRun = new io.github.flowerjvm.factory.application.verification.VerificationRun(
                    run.verificationRunId(), tenant, buildSessionId, candidateId, candidateHash, run.gateProfile(),
                    run.toolchainLockHash(), run.fixtureSetHash(), run.status(), Optional.of(forgedManifestRef),
                    Optional.of(forgedManifestHash), run.terminalCode(), run.disposition(), run.startedAt(), run.completedAt(),
                    run.version(), run.createdAt(), run.updatedAt());
            assertFalse(validator.isReviewEligible(forgedRun),
                    "internally consistent acceptance locks must still match the immutable candidate main-source subhash");
            assertTrue(validator.isReviewEligible(run), "the original exact candidate and evidence remain eligible");
            var technicalOnly = new io.github.flowerjvm.factory.contracts.verification.VerificationResultManifest(
                    manifest.schemaVersion(), manifest.verificationRunId(), manifest.buildSessionId(), manifest.candidateId(),
                    manifest.candidateManifestRef(), manifest.candidateHash(), manifest.dependencyLockRef(),
                    manifest.dependencyLockHash(), manifest.sourceHashBefore(), manifest.sourceHashAfter(),
                    manifest.gateProfile(), manifest.toolchainLockRef(), manifest.toolchainLockHash(), manifest.fixtureSetRef(),
                    manifest.fixtureSetHash(), manifest.fixtureSelfTest(), manifest.sandbox(), manifest.status(),
                    manifest.disposition(), manifest.stableCodes(), manifest.commands().subList(0, 3),
                    manifest.evidenceArtifacts().subList(0, 7));
            byte[] reduced = mapper.writeValueAsBytes(technicalOnly);
            ContentHash reducedHash = hasher.sha256(reduced);
            ArtifactReference reducedRef = new ArtifactReference("native/pr4-only/" + reducedHash.sha256());
            store.store(new Artifact(tenant, reducedRef, reducedHash, "application/json", reduced));
            var downgraded = new io.github.flowerjvm.factory.application.verification.VerificationRun(
                    run.verificationRunId(), tenant, buildSessionId, candidateId, candidateHash, run.gateProfile(),
                    run.toolchainLockHash(), run.fixtureSetHash(), run.status(), Optional.of(reducedRef),
                    Optional.of(reducedHash), run.terminalCode(), run.disposition(), run.startedAt(), run.completedAt(),
                    run.version(), run.createdAt(), run.updatedAt());
            assertFalse(validator.isReviewEligible(downgraded), "PR4-only evidence cannot qualify the new product");
        }
    }

    private record SingleCandidate(io.github.flowerjvm.factory.application.candidate.CandidateVersion value)
            implements io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository {
        @Override public void create(io.github.flowerjvm.factory.application.candidate.CandidateVersion ignored) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<io.github.flowerjvm.factory.application.candidate.CandidateVersion> find(
                TenantId tenant, CandidateId id) {
            return value.tenantId().equals(tenant) && value.candidateId().equals(id) ? Optional.of(value) : Optional.empty();
        }
        @Override public Optional<io.github.flowerjvm.factory.application.candidate.CandidateVersion> findByBuildSessionAndWorkOrder(
                TenantId tenant, BuildSessionId session, io.github.flowerjvm.factory.contracts.ids.WorkOrderId order) {
            return value.tenantId().equals(tenant) && value.buildSessionId().equals(session)
                    && value.createdByWorkOrderId().equals(order) ? Optional.of(value) : Optional.empty();
        }
    }

    private DockerCliVerificationSandbox sandbox() {
        return new DockerCliVerificationSandbox("docker", Duration.ofMinutes(3), 2 * 1024 * 1024);
    }

    private static Path curatedRepository() {
        String configured = System.getProperty("factory.verification.nativeCuratedRepository");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("native sandbox profile requires a separately curated Maven repository");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static void writeMinimalFlowerProject(Path root, boolean outsideWrite) throws IOException {
        writeMinimalFlowerProject(root, outsideWrite, false);
    }

    private static void writeMinimalFlowerProject(Path root, boolean outsideWrite, boolean mutateSource) throws IOException {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.github.flowerjvm.factory.fixture</groupId>
                  <artifactId>native-sandbox-fixture</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>io.github.flowerjvm</groupId>
                      <artifactId>flower-core</artifactId>
                      <version>0.1.3</version>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.12.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-clean-plugin</artifactId>
                        <version>3.4.1</version>
                      </plugin>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-resources-plugin</artifactId>
                        <version>3.3.1</version>
                      </plugin>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.14.1</version>
                      </plugin>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.5.2</version>
                      </plugin>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-jar-plugin</artifactId>
                        <version>3.4.2</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;
        Files.writeString(root.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        Path mainDirectory = Files.createDirectories(root.resolve("src/main/java/fixture"));
        Files.writeString(mainDirectory.resolve("FixtureSource.java"), """
                package fixture;

                final class FixtureSource {
                    int value() {
                        return 2;
                    }
                }
                """, StandardCharsets.UTF_8);
        Path testDirectory = Files.createDirectories(root.resolve("src/test/java/fixture"));
        String body = outsideWrite
                ? "java.nio.file.Files.writeString(java.nio.file.Path.of(\"/escaped.txt\"), \"blocked\");"
                : mutateSource
                        ? "java.nio.file.Files.writeString(java.nio.file.Path.of(\"pom.xml\"), \"mutated\");"
                        : "org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1);";
        Files.writeString(testDirectory.resolve("NativeFixtureTest.java"), """
                package fixture;

                final class NativeFixtureTest {
                    @org.junit.jupiter.api.Test
                    void fixture() throws Exception {
                        %s
                    }
                }
                """.formatted(body), StandardCharsets.UTF_8);
    }

    private static String text(SandboxExecutionResult result) {
        return new String(result.output(), StandardCharsets.UTF_8);
    }

    private static final class MemoryArtifacts implements ArtifactStore {
        private final Map<String, Artifact> values = new HashMap<>();
        @Override public ArtifactReference store(Artifact artifact) {
            String key = artifact.tenantId().value() + "\n" + artifact.reference().value();
            Artifact existing = values.putIfAbsent(key, artifact);
            if (existing != null && !java.util.Arrays.equals(existing.content(), artifact.content())) {
                throw new IllegalStateException("immutable artifact collision");
            }
            return artifact.reference();
        }
        @Override public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(tenantId.value() + "\n" + reference.value()));
        }
        Artifact required(ArtifactReference reference) {
            return values.values().stream().filter(value -> value.reference().equals(reference)).findFirst().orElseThrow();
        }
    }
}
