package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.ARTIFACT_CHECKSUM_MISMATCH;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.CANDIDATE_HASH_MISMATCH;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.CANDIDATE_MANIFEST_INVALID;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.CANDIDATE_SOURCE_MUTATED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.BUILD_POLICY_VIOLATION;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.DEPENDENCY_TREE_MISMATCH;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.DEPENDENCY_LOCK_INVALID;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.FLOWER_CHECK_FAILED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.FLOWER_CHECK_EVIDENCE_INCOMPLETE;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.MANIFEST_REFERENCE_UNRESOLVED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.MAVEN_VERIFICATION_FAILED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.MAVEN_TESTS_MISSING;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.SECRET_MATERIAL_DETECTED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.VERIFICATION_INTERNAL_ERROR;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.VERIFICATION_PROFILE_UNSUPPORTED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.PRODUCT_ACCEPTANCE_FAILED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.FIXTURE_SET_INVALID;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.VERIFIER_SELF_TEST_FAILED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.VERIFIED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_WRITE_OUTSIDE_ALLOWED_PATH;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.verification.VerificationCommandEvidence;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.verification.VerificationRequest;
import io.github.flowerjvm.factory.contracts.verification.VerificationResult;
import io.github.flowerjvm.factory.contracts.verification.VerificationResultManifest;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixture;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixtureSet;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixtureSelfTestEvidence;
import io.github.flowerjvm.factory.contracts.verification.Verifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Independent PR4 verifier. It is blocking infrastructure and must run only outside Flower ticks. */
public final class IndependentMavenVerifier implements Verifier {
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FIXTURE_MANIFEST_BYTES = 1024 * 1024;
    private static final long MAX_FIXTURE_BYTES = 8L * 1024 * 1024;
    private static final ContentHash UNAVAILABLE_SOURCE_HASH =
            new OrdinalSourceTreeHasher().sha256(new byte[0]);

    private final ArtifactStore artifactStore;
    private final ObjectMapper strictMapper;
    private final VerificationSandbox sandbox;
    private final Path workspaceBase;
    private final OrdinalSourceTreeHasher hasher;
    private final CandidateWorkspaceMaterializer materializer;
    private final SecretScanner secretScanner;
    private final CandidateMavenBuildPolicy buildPolicy;
    private final CuratedMavenRepositoryMaterializer repositoryMaterializer;
    private final Pr4VerificationFixtureInstaller fixtureInstaller;
    private final TrustedToolchainInstaller toolchainInstaller;
    private final CandidateSourceManifestReader sourceManifests;

    public IndependentMavenVerifier(
            ArtifactStore artifactStore,
            ObjectMapper objectMapper,
            VerificationSandbox sandbox,
            Path workspaceBase) {
        this(
                artifactStore,
                objectMapper,
                sandbox,
                workspaceBase,
                new OrdinalSourceTreeHasher(),
                CandidateWorkspaceMaterializer.DEFAULT_LIMITS,
                new SecretScanner(),
                new Pr4MavenToolchainInstaller(
                        artifactStore, objectMapper, Pr4MavenToolchainInstaller.configuredCache()));
    }

    public IndependentMavenVerifier(
            ArtifactStore artifactStore,
            ObjectMapper objectMapper,
            VerificationSandbox sandbox,
            Path workspaceBase,
            Pr4MavenToolchainInstaller toolchainInstaller) {
        this(artifactStore, objectMapper, sandbox, workspaceBase, new OrdinalSourceTreeHasher(),
                CandidateWorkspaceMaterializer.DEFAULT_LIMITS, new SecretScanner(), toolchainInstaller);
    }

    public IndependentMavenVerifier(
            ArtifactStore artifactStore,
            ObjectMapper objectMapper,
            VerificationSandbox sandbox,
            Path workspaceBase,
            OrdinalSourceTreeHasher hasher,
            CandidateWorkspaceMaterializer.Limits limits,
            SecretScanner secretScanner) {
        this(artifactStore, objectMapper, sandbox, workspaceBase, hasher, limits, secretScanner,
                new Pr4MavenToolchainInstaller(
                        artifactStore, objectMapper, Pr4MavenToolchainInstaller.configuredCache()));
    }

    public IndependentMavenVerifier(
            ArtifactStore artifactStore,
            ObjectMapper objectMapper,
            VerificationSandbox sandbox,
            Path workspaceBase,
            OrdinalSourceTreeHasher hasher,
            CandidateWorkspaceMaterializer.Limits limits,
            SecretScanner secretScanner,
            Pr4MavenToolchainInstaller toolchainInstaller) {
        this(artifactStore, objectMapper, sandbox, workspaceBase, hasher, limits, secretScanner,
                adapt(toolchainInstaller));
    }

    IndependentMavenVerifier(
            ArtifactStore artifactStore,
            ObjectMapper objectMapper,
            VerificationSandbox sandbox,
            Path workspaceBase,
            OrdinalSourceTreeHasher hasher,
            CandidateWorkspaceMaterializer.Limits limits,
            SecretScanner secretScanner,
            TrustedToolchainInstaller toolchainInstaller) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.strictMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.workspaceBase = Objects.requireNonNull(workspaceBase, "workspaceBase").toAbsolutePath().normalize();
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.materializer = new CandidateWorkspaceMaterializer(artifactStore, hasher, limits);
        this.secretScanner = Objects.requireNonNull(secretScanner, "secretScanner");
        this.buildPolicy = new CandidateMavenBuildPolicy();
        this.repositoryMaterializer = new CuratedMavenRepositoryMaterializer(
                artifactStore, this.strictMapper, hasher);
        this.fixtureInstaller = new Pr4VerificationFixtureInstaller(artifactStore, this.strictMapper);
        this.toolchainInstaller = Objects.requireNonNull(toolchainInstaller, "toolchainInstaller");
        this.sourceManifests = new CandidateSourceManifestReader(this.strictMapper);
    }

    private static TrustedToolchainInstaller adapt(Pr4MavenToolchainInstaller installer) {
        Objects.requireNonNull(installer, "installer");
        return new TrustedToolchainInstaller() {
            @Override
            public InstalledToolchain descriptor() {
                var descriptor = installer.descriptor();
                return new InstalledToolchain(descriptor.reference(), descriptor.hash());
            }

            @Override
            public void install(TenantId tenantId) {
                installer.install(tenantId);
            }
        };
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        Objects.requireNonNull(request, "request");
        var codes = new LinkedHashSet<String>();
        var evidence = new ArrayList<ArtifactReference>();
        var commands = new ArrayList<VerificationCommandEvidence>();
        ContentHash sourceBefore = UNAVAILABLE_SOURCE_HASH;
        ContentHash sourceAfter = UNAVAILABLE_SOURCE_HASH;
        VerificationFixtureSelfTestEvidence fixtureSelfTest = null;
        Path runRoot = null;
        boolean unsafeRequestReferences = false;

        try {
            validateRequestReferences(request);
            if (!VerificationProfiles.supported(request.gateProfile())) {
                codes.add(VERIFICATION_PROFILE_UNSUPPORTED);
                throw VerificationAbort.INSTANCE;
            }
            Files.createDirectories(workspaceBase);
            if (!Files.isDirectory(workspaceBase, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspaceBase)) {
                codes.add(VERIFICATION_INTERNAL_ERROR);
                throw VerificationAbort.INSTANCE;
            }
            Path canonicalWorkspaceBase = workspaceBase.toRealPath();
            if (hasCleanupResidue(canonicalWorkspaceBase)) {
                codes.add(VERIFICATION_INTERNAL_ERROR);
                throw VerificationAbort.INSTANCE;
            }
            runRoot = Files.createTempDirectory(canonicalWorkspaceBase, "verification-");
            Path workspace = runRoot.resolve("workspace");
            Path curatedRepository = runRoot.resolve("maven-repository");

            CandidateSourceManifest manifest = loadManifest(request);
            var candidate = materializer.materialize(request.tenantId(), manifest, workspace);
            sourceBefore = candidate.sourceHash();
            sourceAfter = sourceBefore;

            if (!manifest.candidateId().equals(request.candidateId())
                    || !manifest.buildSessionId().equals(request.buildSessionId())
                    || !manifest.candidateHash().equals(request.candidateHash())) {
                codes.add(CANDIDATE_HASH_MISMATCH);
                throw VerificationAbort.INSTANCE;
            }
            if (containsSecret(workspace, candidate.sourcePaths())) {
                codes.add(SECRET_MATERIAL_DETECTED);
                throw VerificationAbort.INSTANCE;
            }
            var trustedToolchain = toolchainInstaller.descriptor();
            if (!trustedToolchain.reference().equals(request.toolchainLockRef())
                    || !trustedToolchain.hash().equals(request.toolchainLockHash())) {
                codes.add(DEPENDENCY_LOCK_INVALID);
                throw VerificationAbort.INSTANCE;
            }
            toolchainInstaller.install(request.tenantId());
            var locks = repositoryMaterializer.materialize(
                    new CuratedMavenRepositoryMaterializer.VerificationLockInput(
                            request.tenantId(), request.dependencyLockRef(), request.dependencyLockHash()),
                    new CuratedMavenRepositoryMaterializer.VerificationLockInput(
                            request.tenantId(), request.toolchainLockRef(), request.toolchainLockHash()),
                    curatedRepository);
            rejectSensitiveReference(request.dependencyLockRef());
            rejectSensitiveReference(request.toolchainLockRef());
            for (var file : locks.dependencyLock().files()) rejectSensitiveReference(file.artifactRef());
            for (var file : locks.toolchainLock().files()) rejectSensitiveReference(file.artifactRef());
            buildPolicy.validate(workspace, locks.toolchainLock());
            VerificationFixtureSet fixtureSet = loadAndInstallFixtureSet(request);
            SelfTestRun selfTest = runVerifierSelfTest(
                    request, fixtureSet, runRoot.resolve("fixture-workspace"), curatedRepository);
            if (!selfTest.passed()) {
                codes.add(VERIFIER_SELF_TEST_FAILED);
                throw VerificationAbort.INSTANCE;
            }
            byte[] selfTestSummary = selfTestEvidenceSummary(selfTest);
            ContentHash selfTestHash = hasher.sha256(selfTestSummary);
            ArtifactReference selfTestRef = storeArtifact(
                    request, "fixture-self-test", "application/json", selfTestSummary, selfTestHash);
            evidence.add(selfTestRef);
            fixtureSelfTest = new VerificationFixtureSelfTestEvidence(
                    selfTest.fixtureIds(), selfTest.expectedRules(), selfTest.observedRules(),
                    selfTest.exitCode(), selfTestRef, selfTestHash);

            for (VerificationCommand command : VerificationProfiles.technicalCommands()) {
                SandboxExecutionResult execution;
                try {
                    execution = sandbox.execute(workspace, curatedRepository, command);
                } catch (SandboxExecutionException exception) {
                    codes.add(exception.stableCode());
                    break;
                }
                boolean commandSecret = !secretScanner.scan("command-output", execution.output()).isEmpty()
                        || containsSecretEvidence(execution.evidenceFiles());
                if (commandSecret) {
                    codes.add(SECRET_MATERIAL_DETECTED);
                }
                byte[] sanitizedLog = secretScanner.redact(execution.output());
                ContentHash logHash = hasher.sha256(sanitizedLog);
                ArtifactReference logReference = storeArtifact(
                        request,
                        "command-" + command.commandId(),
                        "text/plain; charset=utf-8",
                        sanitizedLog,
                        logHash);
                evidence.add(logReference);
                if (commandSecret) {
                    break;
                }
                int testCount = command == VerificationCommand.MAVEN_VERIFY
                        ? verifiedTestCount(execution.evidenceFiles()) : 0;
                int flowerFindings = command == VerificationCommand.FLOWER_CHECK
                        ? flowerFindingCount(execution.evidenceFiles()) : 0;
                byte[] machineSummary = machineEvidenceSummary(command, execution.evidenceFiles(), testCount, flowerFindings);
                ContentHash machineHash = hasher.sha256(machineSummary);
                ArtifactReference machineReference = storeArtifact(
                        request, "machine-" + command.commandId(), "application/json", machineSummary, machineHash);
                evidence.add(machineReference);
                commands.add(new VerificationCommandEvidence(
                        execution.commandId(),
                        execution.arguments(),
                        execution.exitCode(),
                        testCount,
                        flowerFindings,
                        logReference,
                        logHash,
                        machineReference,
                        machineHash));
                if (execution.exitCode() != 0) {
                    codes.add(command == VerificationCommand.FLOWER_CHECK
                            ? FLOWER_CHECK_FAILED
                            : MAVEN_VERIFICATION_FAILED);
                }
                if (command == VerificationCommand.MAVEN_VERIFY
                        && execution.exitCode() == 0
                        && testCount == 0) {
                    codes.add(MAVEN_TESTS_MISSING);
                }
                if (command == VerificationCommand.FLOWER_CHECK
                        && execution.exitCode() == 0
                        && flowerFindings != 0) {
                    codes.add(FLOWER_CHECK_EVIDENCE_INCOMPLETE);
                }
                if (command == VerificationCommand.MAVEN_DEPENDENCY_TREE
                        && execution.exitCode() == 0
                        && !dependencyTreeMatches(
                                execution.evidenceFiles(), locks.dependencyLock().expectedDependencyCoordinates())) {
                    codes.add(DEPENDENCY_TREE_MISMATCH);
                }
            }

            if (codes.isEmpty() && VerificationProfiles.maintenance(request.gateProfile())) {
                runProductAcceptance(request, workspace, curatedRepository, sourceBefore, codes, commands, evidence);
            }
            sourceAfter = hashCurrentSources(workspace, manifest.files());
            if (!sourceAfter.equals(sourceBefore)) {
                codes.add(CANDIDATE_SOURCE_MUTATED);
            }
            if (hasUnexpectedWorkspaceWrites(workspace, candidate.sourcePaths())) {
                codes.add(WORKSPACE_WRITE_OUTSIDE_ALLOWED_PATH);
            }
        } catch (CandidateMaterializationException exception) {
            codes.add(exception.stableCode());
        } catch (ManifestException exception) {
            codes.add(exception.stableCode());
            unsafeRequestReferences = SECRET_MATERIAL_DETECTED.equals(exception.stableCode());
        } catch (VerificationAbort ignored) {
            // The fail-closed stable code was recorded immediately before aborting this attempt.
        } catch (Exception exception) {
            codes.add(VERIFICATION_INTERNAL_ERROR);
        } finally {
            if (runRoot != null && !quarantineAndDelete(runRoot)) {
                codes.add(VERIFICATION_INTERNAL_ERROR);
            }
        }
        return unsafeRequestReferences
                ? finishConfidentialFailure(request, codes)
                : finish(request, sourceBefore, sourceAfter, fixtureSelfTest, codes, commands, evidence);
    }

    private void runProductAcceptance(
            VerificationRequest request, Path workspace, Path curatedRepository, ContentHash sourceHash,
            Set<String> codes, List<VerificationCommandEvidence> commands, List<ArtifactReference> evidence)
            throws Exception {
        for (Artifact artifact : MaintenanceInvestigationProductContract.artifacts(request.tenantId())) {
            artifactStore.store(artifact);
        }
        var result = new MaintenanceAcceptanceGate(sandbox, strictMapper)
                .verify(workspace, curatedRepository, sourceHash);
        var execution = result.execution();
        if (!secretScanner.scan("acceptance-log", execution.output()).isEmpty()
                || !secretScanner.scan("acceptance-actual", result.actualBytes()).isEmpty()
                || !secretScanner.scan("acceptance-summary", result.summaryBytes()).isEmpty()) {
            codes.add(SECRET_MATERIAL_DETECTED);
            return;
        }
        byte[] log = secretScanner.redact(execution.output());
        ContentHash logHash = hasher.sha256(log);
        ContentHash summaryHash = hasher.sha256(result.summaryBytes());
        ContentHash actualHash = hasher.sha256(result.actualBytes());
        ArtifactReference logRef = storeArtifact(request, "maintenance-acceptance-log",
                "text/plain; charset=utf-8", log, logHash);
        ArtifactReference summaryRef = storeArtifact(request, "maintenance-acceptance-summary",
                "application/json", result.summaryBytes(), summaryHash);
        ArtifactReference actualRef = storeArtifact(request, "maintenance-acceptance-actual",
                "application/json", result.actualBytes(), actualHash);
        evidence.add(logRef);
        evidence.add(summaryRef);
        evidence.add(actualRef);
        commands.add(new VerificationCommandEvidence(
                execution.commandId(), execution.arguments(), execution.exitCode(), 0, 0,
                logRef, logHash, summaryRef, summaryHash));
        if (!result.passed()) codes.add(PRODUCT_ACCEPTANCE_FAILED);
    }

    private CandidateSourceManifest loadManifest(VerificationRequest request) throws ManifestException {
        rejectSensitiveReference(request.candidateManifest());
        var artifact = artifactStore.find(request.tenantId(), request.candidateManifest()).orElseThrow(() ->
                new ManifestException(MANIFEST_REFERENCE_UNRESOLVED, "candidate manifest is unavailable"));
        byte[] content = artifact.content();
        if (content.length > MAX_MANIFEST_BYTES
                || !artifact.tenantId().equals(request.tenantId())
                || !artifact.reference().equals(request.candidateManifest())
                || !"application/json".equals(artifact.mediaType())
                || !hasher.sha256(content).equals(artifact.contentHash())) {
            throw new ManifestException(ARTIFACT_CHECKSUM_MISMATCH, "candidate manifest checksum differs");
        }
        if (!secretScanner.scan("candidate-manifest.json", content).isEmpty()) {
            throw new ManifestException(SECRET_MATERIAL_DETECTED, "candidate manifest contains secret material");
        }
        try {
            CandidateSourceManifest manifest = sourceManifests.read(content);
            for (CandidateSourceEntry entry : manifest.files()) rejectSensitiveReference(entry.artifactRef());
            return manifest;
        } catch (IOException | RuntimeException exception) {
            throw new ManifestException(CANDIDATE_MANIFEST_INVALID, "candidate manifest is not strict v1 JSON", exception);
        }
    }

    private void validateRequestReferences(VerificationRequest request) throws ManifestException {
        rejectSensitiveReference(request.candidateManifest());
        rejectSensitiveReference(request.dependencyLockRef());
        rejectSensitiveReference(request.toolchainLockRef());
        rejectSensitiveReference(request.fixtureSetRef());
    }

    private VerificationFixtureSet loadAndInstallFixtureSet(VerificationRequest request)
            throws ManifestException {
        Pr4VerificationFixtureInstaller.InstalledFixtureSet trusted = fixtureInstaller.descriptor();
        if (!trusted.reference().equals(request.fixtureSetRef()) || !trusted.hash().equals(request.fixtureSetHash())) {
            throw new ManifestException(FIXTURE_SET_INVALID, "fixture set does not match host policy");
        }
        fixtureInstaller.install(request.tenantId());
        rejectSensitiveReference(request.fixtureSetRef());
        var artifact = artifactStore.find(request.tenantId(), request.fixtureSetRef()).orElseThrow(() ->
                new ManifestException(MANIFEST_REFERENCE_UNRESOLVED, "fixture set is unavailable"));
        byte[] bytes = artifact.content();
        if (bytes.length > MAX_FIXTURE_MANIFEST_BYTES
                || !artifact.reference().equals(request.fixtureSetRef())
                || !"application/json".equals(artifact.mediaType())
                || !artifact.contentHash().equals(request.fixtureSetHash())
                || !hasher.sha256(bytes).equals(request.fixtureSetHash())) {
            throw new ManifestException(ARTIFACT_CHECKSUM_MISMATCH, "fixture set bytes differ from host policy");
        }
        try {
            VerificationFixtureSet set = strictMapper.readValue(bytes, VerificationFixtureSet.class);
            if (set.fixtures().size() > 64
                    || set.fixtures().stream().mapToLong(VerificationFixture::sizeBytes).sum() > MAX_FIXTURE_BYTES) {
                throw new ManifestException(FIXTURE_SET_INVALID, "fixture set exceeds fixed bounds");
            }
            return set;
        } catch (ManifestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ManifestException(FIXTURE_SET_INVALID, "fixture set is not strict v1 JSON", exception);
        }
    }

    private SelfTestRun runVerifierSelfTest(
            VerificationRequest request,
            VerificationFixtureSet fixtureSet,
            Path fixtureWorkspace,
            Path curatedRepository) throws IOException, ManifestException {
        Files.createDirectory(fixtureWorkspace);
        var paths = new HashSet<String>();
        var folded = new HashSet<String>();
        for (VerificationFixture fixture : fixtureSet.fixtures()) {
            if (!paths.add(fixture.path()) || !folded.add(fixture.path().toLowerCase(java.util.Locale.ROOT))) {
                throw new ManifestException(FIXTURE_SET_INVALID, "fixture paths collide");
            }
            validateFixturePath(fixture.path());
            rejectSensitiveReference(fixture.artifactRef());
            var artifact = artifactStore.find(request.tenantId(), fixture.artifactRef()).orElseThrow(() ->
                    new ManifestException(MANIFEST_REFERENCE_UNRESOLVED, "fixture file is unavailable"));
            byte[] bytes = artifact.content();
            if (bytes.length != fixture.sizeBytes()
                    || bytes.length > MAX_FIXTURE_BYTES
                    || !artifact.contentHash().equals(fixture.contentHash())
                    || !hasher.sha256(bytes).equals(fixture.contentHash())) {
                throw new ManifestException(ARTIFACT_CHECKSUM_MISMATCH, "fixture file differs from host policy");
            }
            Path output = resolveCanonical(fixtureWorkspace, fixture.path()).normalize();
            if (!output.startsWith(fixtureWorkspace) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new ManifestException(FIXTURE_SET_INVALID, "fixture path escaped or collided");
            }
            Files.createDirectories(output.getParent());
            Files.write(output, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        SandboxExecutionResult execution;
        try {
            execution = sandbox.execute(fixtureWorkspace, curatedRepository, VerificationCommand.FLOWER_CHECK);
        } catch (SandboxExecutionException exception) {
            return SelfTestRun.failed();
        }
        if (!secretScanner.scan("fixture-command-output", execution.output()).isEmpty()) {
            throw new ManifestException(SECRET_MATERIAL_DETECTED, "fixture output contains secret material");
        }
        List<String> fixtureIds = fixtureSet.fixtures().stream().map(VerificationFixture::fixtureId).distinct().sorted().toList();
        List<String> expected = fixtureSet.fixtures().stream().map(VerificationFixture::expectedRuleId).distinct().sorted().toList();
        byte[] sarif = execution.evidenceFiles().getOrDefault("flower-check.sarif", new byte[0]);
        List<String> observed = observedSarifRules(sarif);
        boolean passed = execution.exitCode() != 0
                && observed.equals(expected)
                && fixtureFilesUnchanged(fixtureWorkspace, fixtureSet.fixtures());
        return new SelfTestRun(passed, fixtureIds, expected, observed, execution.exitCode(), sarif);
    }

    static boolean fixtureDetectsExpectedRules(java.util.Map<String, byte[]> files, Set<String> expected) {
        byte[] report = files.get("flower-check.sarif");
        if (report == null || report.length > 4 * 1024 * 1024) return false;
        try {
            JsonNode root = new ObjectMapper().readTree(report);
            if (!"2.1.0".equals(root.path("version").asText()) || root.path("runs").size() != 1) return false;
            JsonNode results = root.path("runs").get(0).path("results");
            if (!results.isArray()) return false;
            var actual = new HashSet<String>();
            results.forEach(result -> actual.add(result.path("ruleId").asText()));
            return actual.equals(expected) && !actual.contains("FLOWER-CHECK-PARSE");
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private List<String> observedSarifRules(byte[] report) {
        if (report.length == 0 || report.length > 4 * 1024 * 1024) return List.of();
        try {
            JsonNode root = strictMapper.readTree(report);
            if (!"2.1.0".equals(root.path("version").asText()) || root.path("runs").size() != 1) return List.of();
            JsonNode results = root.path("runs").get(0).path("results");
            if (!results.isArray()) return List.of();
            var actual = new java.util.TreeSet<String>();
            results.forEach(result -> actual.add(result.path("ruleId").asText()));
            if (actual.contains("") || actual.contains("FLOWER-CHECK-PARSE")) return List.of();
            return List.copyOf(actual);
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }

    private boolean fixtureFilesUnchanged(
            Path workspace, List<VerificationFixture> fixtures) {
        try {
            for (VerificationFixture fixture : fixtures) {
                Path file = resolveCanonical(workspace, fixture.path());
                byte[] current = Files.readAllBytes(file);
                if (current.length != fixture.sizeBytes() || !hasher.sha256(current).equals(fixture.contentHash())) {
                    return false;
                }
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private byte[] machineEvidenceSummary(
            VerificationCommand command,
            java.util.Map<String, byte[]> files,
            int testCount,
            int flowerFindings) throws IOException {
        var summary = strictMapper.createObjectNode();
        summary.put("schemaVersion", "factory.verification-machine-evidence.v1");
        summary.put("commandId", command.commandId());
        summary.put("testCount", testCount);
        summary.put("flowerCheckFindingCount", flowerFindings);
        var inputs = summary.putArray("inputDigests");
        files.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            var item = inputs.addObject();
            item.put("name", entry.getKey());
            item.put("sha256", hasher.sha256(entry.getValue()).sha256());
            item.put("sizeBytes", entry.getValue().length);
        });
        if (command == VerificationCommand.MAVEN_DEPENDENCY_TREE) {
            var coordinates = summary.putArray("dependencyCoordinates");
            parsedDependencyCoordinates(files).forEach(coordinates::add);
        } else if (command == VerificationCommand.FLOWER_CHECK) {
            var rules = summary.putArray("observedRuleIds");
            observedSarifRules(files.getOrDefault("flower-check.sarif", new byte[0])).forEach(rules::add);
        }
        return strictMapper.writeValueAsBytes(summary);
    }

    private boolean containsSecretEvidence(java.util.Map<String, byte[]> files) {
        return files.values().stream().anyMatch(bytes ->
                !secretScanner.scan("machine-evidence", bytes).isEmpty());
    }

    private byte[] selfTestEvidenceSummary(SelfTestRun selfTest) throws IOException {
        if (!secretScanner.scan("fixture-sarif", selfTest.sarif()).isEmpty()) {
            throw new IOException("fixture evidence contains secret material");
        }
        var summary = strictMapper.createObjectNode();
        summary.put("schemaVersion", "factory.verifier-self-test-evidence.v1");
        summary.put("exitCode", selfTest.exitCode());
        var fixtureIds = summary.putArray("fixtureIds");
        selfTest.fixtureIds().forEach(fixtureIds::add);
        var expected = summary.putArray("expectedRuleIds");
        selfTest.expectedRules().forEach(expected::add);
        var observed = summary.putArray("observedRuleIds");
        selfTest.observedRules().forEach(observed::add);
        summary.put("originalSarifSha256", hasher.sha256(selfTest.sarif()).sha256());
        summary.put("originalSarifSizeBytes", selfTest.sarif().length);
        return strictMapper.writeValueAsBytes(summary);
    }

    private static List<String> parsedDependencyCoordinates(java.util.Map<String, byte[]> files) {
        byte[] tree = files.get("dependency-tree.txt");
        if (tree == null || tree.length > 4 * 1024 * 1024) return List.of();
        var actual = new java.util.TreeSet<String>();
        for (String raw : new String(tree, StandardCharsets.UTF_8).split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[INFO]")) line = line.substring(6).strip();
            int marker = Math.max(line.lastIndexOf("+-"), line.lastIndexOf("\\-"));
            if (marker < 0) continue; // project root and non-tree diagnostic lines are not dependencies.
            line = line.substring(marker + 2).strip();
            int omitted = line.indexOf(" -- ");
            if (omitted >= 0) line = line.substring(0, omitted).strip();
            String[] parts = line.split(":", -1);
            if ((parts.length != 5 && parts.length != 6)
                    || java.util.Arrays.stream(parts).anyMatch(part -> !part.matches("[A-Za-z0-9_.-]+"))
                    || !Set.of("compile", "test", "runtime", "provided", "system", "import")
                            .contains(parts[parts.length - 1])) return List.of();
            actual.add(String.join(":", java.util.Arrays.copyOf(parts, parts.length - 1)));
        }
        return List.copyOf(actual);
    }

    private static void validateFixturePath(String path) throws ManifestException {
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.length() > 512) {
            throw new ManifestException(FIXTURE_SET_INVALID, "fixture path is not canonical");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.equals("target") || segment.equals(".factory-evidence")) {
                throw new ManifestException(FIXTURE_SET_INVALID, "fixture path contains a reserved segment");
            }
        }
    }

    private void rejectSensitiveReference(ArtifactReference reference) throws ManifestException {
        if (!secretScanner.scan("artifact-reference", reference.value().getBytes(StandardCharsets.UTF_8)).isEmpty()) {
            throw new ManifestException(SECRET_MATERIAL_DETECTED, "artifact reference contains secret material");
        }
    }

    private boolean containsSecret(Path workspace, Set<String> sourcePaths) throws IOException {
        for (String path : sourcePaths.stream().sorted().toList()) {
            Path file = resolveCanonical(workspace, path);
            if (!secretScanner.scan(path, Files.readAllBytes(file)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private ContentHash hashCurrentSources(Path workspace, List<CandidateSourceEntry> declared)
            throws CandidateMaterializationException {
        var current = new ArrayList<CandidateSourceEntry>(declared.size());
        try {
            for (CandidateSourceEntry entry : declared) {
                Path file = resolveCanonical(workspace, entry.path());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                    return UNAVAILABLE_SOURCE_HASH;
                }
                byte[] content = Files.readAllBytes(file);
                current.add(new CandidateSourceEntry(
                        entry.path(), entry.artifactRef(), hasher.sha256(content), content.length));
            }
            return hasher.hashEntries(current);
        } catch (IOException exception) {
            throw new CandidateMaterializationException(
                    CANDIDATE_SOURCE_MUTATED, "candidate sources could not be hashed after verification", exception);
        }
    }

    private boolean hasUnexpectedWorkspaceWrites(Path workspace, Set<String> sourcePaths) throws IOException {
        Set<String> allowedDirectories = new HashSet<>();
        allowedDirectories.add("");
        for (String source : sourcePaths) {
            int slash = source.lastIndexOf('/');
            while (slash >= 0) {
                String directory = source.substring(0, slash);
                allowedDirectories.add(directory);
                slash = directory.lastIndexOf('/');
            }
        }
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                if (path.equals(workspace)) {
                    continue;
                }
                String relative = workspace.relativize(path).toString().replace('\\', '/');
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (Files.isSymbolicLink(path) || attributes.isOther()) {
                    return true;
                }
                if (containsGeneratedSegment(relative)) {
                    continue;
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!allowedDirectories.contains(relative)) {
                        return true;
                    }
                } else if (!sourcePaths.contains(relative) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
            }
        }
        return false;
    }

    private VerificationResult finish(
            VerificationRequest request,
            ContentHash sourceBefore,
            ContentHash sourceAfter,
            VerificationFixtureSelfTestEvidence fixtureSelfTest,
            Set<String> mutableCodes,
            List<VerificationCommandEvidence> commands,
            List<ArtifactReference> evidence) {
        var codes = new ArrayList<>(mutableCodes);
        if (codes.isEmpty()) {
            codes.add(VERIFIED);
        }
        VerificationStatus status = codes.equals(List.of(VERIFIED))
                ? VerificationStatus.PASSED
                : VerificationStatus.FAILED;
        VerificationDisposition disposition = status == VerificationStatus.PASSED
                ? VerificationDisposition.REVIEW_ELIGIBLE
                : isRepairable(codes) ? VerificationDisposition.REPAIR_REQUIRED : VerificationDisposition.BLOCKED;
        var manifest = new VerificationResultManifest(
                VerificationResultManifest.SCHEMA_VERSION,
                request.verificationRunId(),
                request.buildSessionId(),
                request.candidateId(),
                request.candidateManifest(),
                request.candidateHash(),
                request.dependencyLockRef(),
                request.dependencyLockHash(),
                sourceBefore,
                sourceAfter,
                request.gateProfile(),
                request.toolchainLockRef(),
                request.toolchainLockHash(),
                request.fixtureSetRef(),
                request.fixtureSetHash(),
                fixtureSelfTest,
                sandbox.evidence(),
                status,
                disposition,
                codes,
                commands,
                evidence);
        try {
            byte[] encoded = strictMapper.writeValueAsBytes(manifest);
            ContentHash manifestHash = hasher.sha256(encoded);
            ArtifactReference manifestReference = storeArtifact(
                    request, "result-manifest", "application/json", encoded, manifestHash);
            return new VerificationResult(
                    status, disposition, codes, manifestReference, manifestHash, evidence);
        } catch (IOException exception) {
            throw new IllegalStateException("verification result manifest could not be encoded", exception);
        }
    }

    private VerificationResult finishConfidentialFailure(
            VerificationRequest request, Set<String> mutableCodes) {
        var codes = mutableCodes.contains(SECRET_MATERIAL_DETECTED)
                ? List.of(SECRET_MATERIAL_DETECTED)
                : List.of(VERIFICATION_INTERNAL_ERROR);
        ArtifactReference redactedCandidate = redactedReference("candidate", request.candidateManifest());
        ArtifactReference redactedDependency = redactedReference("dependency", request.dependencyLockRef());
        ArtifactReference redactedToolchain = redactedReference("toolchain", request.toolchainLockRef());
        ArtifactReference redactedFixture = redactedReference("fixture", request.fixtureSetRef());
        var manifest = new VerificationResultManifest(
                VerificationResultManifest.SCHEMA_VERSION,
                request.verificationRunId(), request.buildSessionId(), request.candidateId(),
                redactedCandidate, request.candidateHash(), redactedDependency, request.dependencyLockHash(),
                UNAVAILABLE_SOURCE_HASH, UNAVAILABLE_SOURCE_HASH, request.gateProfile(),
                redactedToolchain, request.toolchainLockHash(), redactedFixture, request.fixtureSetHash(),
                null, sandbox.evidence(), VerificationStatus.FAILED,
                VerificationDisposition.BLOCKED, codes, List.of(), List.of());
        try {
            byte[] encoded = strictMapper.writeValueAsBytes(manifest);
            ContentHash hash = hasher.sha256(encoded);
            ArtifactReference reference = storeArtifact(
                    request, "result-manifest-confidential", "application/json", encoded, hash);
            return new VerificationResult(
                    VerificationStatus.FAILED, VerificationDisposition.BLOCKED, codes, reference, hash, List.of());
        } catch (IOException exception) {
            throw new IllegalStateException("confidential verification result could not be encoded", exception);
        }
    }

    private ArtifactReference redactedReference(String kind, ArtifactReference unsafe) {
        ContentHash digest = hasher.sha256(unsafe.value().getBytes(StandardCharsets.UTF_8));
        return new ArtifactReference("verification-redacted/" + kind + "/" + digest.sha256());
    }

    private ArtifactReference storeArtifact(
            VerificationRequest request, String kind, String mediaType, byte[] content, ContentHash contentHash) {
        if (content.length > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("verification evidence exceeds the per-artifact bound");
        }
        if (!secretScanner.scan("generated-verification-artifact", content).isEmpty()) {
            throw new IllegalArgumentException("generated verification artifact contains secret material");
        }
        ArtifactReference expected = new ArtifactReference(
                "verification/" + request.verificationRunId().value() + "/" + kind + "/" + contentHash.sha256());
        ArtifactReference stored = artifactStore.store(
                new Artifact(request.tenantId(), expected, contentHash, mediaType, content));
        if (!stored.equals(expected)) {
            throw new IllegalStateException("artifact store changed a verifier-selected immutable reference");
        }
        return stored;
    }

    private static boolean isRepairable(List<String> codes) {
        return !codes.isEmpty()
                && codes.stream().allMatch(code ->
                        code.equals(MAVEN_VERIFICATION_FAILED) || code.equals(FLOWER_CHECK_FAILED)
                                || code.equals(PRODUCT_ACCEPTANCE_FAILED));
    }

    static int verifiedTestCount(java.util.Map<String, byte[]> files) {
        int total = 0;
        boolean sawReport = false;
        for (var entry : files.entrySet()) {
            if (!entry.getKey().startsWith("surefire/") || !entry.getKey().endsWith(".xml")) {
                continue;
            }
            sawReport = true;
            if (entry.getValue().length > 1024 * 1024) return 0;
            try {
                SuiteCount suite = parseSurefireSuite(entry.getValue());
                if (suite.failures() != 0 || suite.errors() != 0 || suite.skipped() > suite.tests()) return 0;
                total = Math.addExact(total, suite.tests() - suite.skipped());
            } catch (RuntimeException | XMLStreamException exception) {
                return 0;
            }
        }
        return sawReport ? total : 0;
    }

    private static int boundedNonNegativeInt(String value) {
        if (!value.matches("[0-9]{1,8}")) throw new IllegalArgumentException("invalid bounded integer");
        return Integer.parseInt(value);
    }

    private static SuiteCount parseSurefireSuite(byte[] report) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new java.io.ByteArrayInputStream(report));
        int events = 0;
        int depth = 0;
        int textBytes = 0;
        SuiteCount result = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (++events > 10_000) throw new XMLStreamException("Surefire report has too many events");
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new XMLStreamException("Surefire report contains forbidden XML constructs");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 32 || reader.getAttributeCount() > 16) {
                        throw new XMLStreamException("Surefire report exceeds structural bounds");
                    }
                    for (int index = 0; index < reader.getAttributeCount(); index++) {
                        if (reader.getAttributeLocalName(index).length() > 64
                                || reader.getAttributeValue(index).length() > (depth == 1 ? 256 : 16 * 1024)) {
                            throw new XMLStreamException("Surefire report attribute exceeds bound");
                        }
                    }
                    if (depth == 1) {
                        if (!"testsuite".equals(reader.getLocalName()) || result != null) {
                            throw new XMLStreamException("Surefire report root must be one testsuite");
                        }
                        result = new SuiteCount(
                                requiredBoundedAttribute(reader, "tests"),
                                requiredBoundedAttribute(reader, "failures"),
                                requiredBoundedAttribute(reader, "errors"),
                                optionalBoundedAttribute(reader, "skipped"));
                    } else if ("testsuite".equals(reader.getLocalName())) {
                        throw new XMLStreamException("nested testsuite is forbidden");
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (--depth < 0) throw new XMLStreamException("unbalanced Surefire report");
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA
                        || event == XMLStreamConstants.COMMENT) {
                    textBytes = Math.addExact(textBytes, reader.getTextLength());
                    if (textBytes > 64 * 1024) throw new XMLStreamException("Surefire report text exceeds bound");
                }
            }
        } finally {
            reader.close();
        }
        if (result == null || depth != 0) throw new XMLStreamException("Surefire report is incomplete");
        return result;
    }

    private static int requiredBoundedAttribute(XMLStreamReader reader, String name) {
        String value = uniqueAttribute(reader, name);
        if (value == null) throw new IllegalArgumentException("missing bounded integer");
        return boundedNonNegativeInt(value);
    }

    private static int optionalBoundedAttribute(XMLStreamReader reader, String name) {
        String value = uniqueAttribute(reader, name);
        return value == null ? 0 : boundedNonNegativeInt(value);
    }

    private static String uniqueAttribute(XMLStreamReader reader, String name) {
        String value = null;
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (name.equals(reader.getAttributeLocalName(index))) {
                if (value != null) throw new IllegalArgumentException("duplicate report attribute");
                value = reader.getAttributeValue(index);
            }
        }
        return value;
    }

    private record SuiteCount(int tests, int failures, int errors, int skipped) {}

    private int flowerFindingCount(java.util.Map<String, byte[]> files) {
        byte[] report = files.get("flower-check.sarif");
        if (report == null) {
            return Integer.MAX_VALUE;
        }
        try {
            JsonNode root = strictMapper.readTree(report);
            if (!"2.1.0".equals(root.path("version").asText()) || root.path("runs").size() != 1) {
                return Integer.MAX_VALUE;
            }
            JsonNode results = root.path("runs").get(0).path("results");
            if (!results.isArray()) {
                return Integer.MAX_VALUE;
            }
            for (JsonNode result : results) {
                if ("FLOWER-CHECK-PARSE".equals(result.path("ruleId").asText())) {
                    return Integer.MAX_VALUE;
                }
            }
            return results.size();
        } catch (IOException | RuntimeException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean dependencyTreeMatches(
            java.util.Map<String, byte[]> files, List<String> expectedCoordinates) {
        return new java.util.TreeSet<>(parsedDependencyCoordinates(files))
                .equals(new java.util.TreeSet<>(expectedCoordinates));
    }

    private static boolean containsGeneratedSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("target") || segment.equals(".factory-evidence")) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveCanonical(Path workspace, String path) {
        Path resolved = workspace;
        for (String segment : path.split("/")) {
            resolved = resolved.resolve(segment);
        }
        return resolved;
    }

    private static boolean hasCleanupResidue(Path base) throws IOException {
        try (var children = Files.list(base)) {
            return children.anyMatch(path -> path.getFileName().toString().startsWith(".verification-quarantine-"));
        }
    }

    private static boolean quarantineAndDelete(Path root) {
        try {
            Path base = root.getParent().toRealPath();
            if (!root.toAbsolutePath().normalize().getParent().equals(base)
                    || !root.getFileName().toString().startsWith("verification-")) return false;
            Path quarantine = base.resolve(".verification-quarantine-" + java.util.UUID.randomUUID());
            Files.move(root, quarantine, StandardCopyOption.ATOMIC_MOVE);
            deleteTreeNoFollow(quarantine);
            return !Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static void deleteTreeNoFollow(Path root) throws IOException {
        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (Files.isSymbolicLink(file) || attributes.isOther()) {
                            // Delete the reparse/link entry itself. Never traverse its target.
                            Files.delete(file);
                        } else if (attributes.isRegularFile()) {
                            Files.delete(file);
                        } else {
                            throw new IOException("cleanup encountered a special file");
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                        if (failure != null) throw failure;
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static final class VerificationAbort extends Exception {
        private static final VerificationAbort INSTANCE = new VerificationAbort();

        private VerificationAbort() {
            super(null, null, false, false);
        }
    }

    private static final class ManifestException extends Exception {
        private final String stableCode;

        private ManifestException(String stableCode, String message) {
            super(message);
            this.stableCode = stableCode;
        }

        private ManifestException(String stableCode, String message, Throwable cause) {
            super(message, cause);
            this.stableCode = stableCode;
        }

        private String stableCode() {
            return stableCode;
        }
    }

    private record SelfTestRun(
            boolean passed,
            List<String> fixtureIds,
            List<String> expectedRules,
            List<String> observedRules,
            int exitCode,
            byte[] sarif) {
        private SelfTestRun {
            fixtureIds = List.copyOf(fixtureIds);
            expectedRules = List.copyOf(expectedRules);
            observedRules = List.copyOf(observedRules);
            sarif = sarif.clone();
        }
        @Override public byte[] sarif() { return sarif.clone(); }
        private static SelfTestRun failed() {
            return new SelfTestRun(false, List.of(), List.of(), List.of(), 0, new byte[0]);
        }
    }
}
