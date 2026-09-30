package io.github.flowerjvm.factory.infrastructure.verification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.verification.VerificationResultManifest;
import io.github.flowerjvm.factory.contracts.verification.VerificationSandboxEvidence;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import io.github.flowerjvm.factory.contracts.verification.MavenDependencyLock;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixtureSet;
import io.github.flowerjvm.factory.contracts.verification.VerificationFixture;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Artifact-backed strict read validation for the PR4 human-review gate. */
public final class ArtifactVerificationEvidenceValidator implements VerificationEvidenceValidator {
    static final int MAX_RESULT_MANIFEST_BYTES = 1024 * 1024;
    static final int MAX_COMMAND_LOG_BYTES = DockerCliVerificationSandbox.DEFAULT_MAX_LOG_BYTES + 64;

    private final ArtifactStore artifacts;
    private final CandidateVersionRepository candidates;
    private final ObjectMapper mapper;
    private final ArtifactReference requiredFixtureSetRef;
    private final ContentHash requiredFixtureSetHash;
    private final ArtifactReference requiredToolchainRef;
    private final ContentHash requiredToolchainHash;
    private final OrdinalSourceTreeHasher hasher = new OrdinalSourceTreeHasher();
    private final CandidateSourceManifestReader sourceManifests;

    public ArtifactVerificationEvidenceValidator(
            ArtifactStore artifacts,
            CandidateVersionRepository candidates,
            ObjectMapper mapper,
            ArtifactReference requiredFixtureSetRef,
            ContentHash requiredFixtureSetHash,
            ArtifactReference requiredToolchainRef,
            ContentHash requiredToolchainHash) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.requiredFixtureSetRef = Objects.requireNonNull(requiredFixtureSetRef, "requiredFixtureSetRef");
        this.requiredFixtureSetHash = Objects.requireNonNull(requiredFixtureSetHash, "requiredFixtureSetHash");
        this.requiredToolchainRef = Objects.requireNonNull(requiredToolchainRef, "requiredToolchainRef");
        this.requiredToolchainHash = Objects.requireNonNull(requiredToolchainHash, "requiredToolchainHash");
        this.sourceManifests = new CandidateSourceManifestReader(this.mapper);
    }

    @Override
    public boolean isReviewEligible(VerificationRun run) {
        try {
            if (!VerificationProfiles.supported(run.gateProfile())
                    || run.status() != VerificationRunStatus.PASSED
                    || run.resultManifestRef().isEmpty()
                    || run.resultManifestHash().isEmpty()
                    || run.terminalCode().filter("VERIFIED"::equals).isEmpty()
                    || run.disposition().filter(value -> value == VerificationDisposition.REVIEW_ELIGIBLE).isEmpty()
                    || !run.fixtureSetHash().equals(requiredFixtureSetHash)
                    || !run.toolchainLockHash().equals(requiredToolchainHash)) {
                return false;
            }
            List<VerificationCommand> expectedCommands = VerificationProfiles.commands(run.gateProfile());
            boolean productAcceptance = VerificationProfiles.maintenance(run.gateProfile());
            var candidate = candidates.find(run.tenantId(), run.candidateId()).orElse(null);
            if (candidate == null
                    || !candidate.tenantId().equals(run.tenantId())
                    || !candidate.buildSessionId().equals(run.buildSessionId())
                    || !candidate.sourceHash().equals(run.candidateHash())
                    || !candidate.toolchainLockRef().equals(requiredToolchainRef)
                    || !candidate.toolchainLockHash().equals(requiredToolchainHash)) {
                return false;
            }
            var artifact = artifacts.find(run.tenantId(), run.resultManifestRef().orElseThrow()).orElse(null);
            if (artifact == null
                    || !"application/json".equals(artifact.mediaType())
                    || artifact.content().length > MAX_RESULT_MANIFEST_BYTES
                    || !artifact.contentHash().equals(run.resultManifestHash().orElseThrow())
                    || !hasher.sha256(artifact.content()).equals(run.resultManifestHash().orElseThrow())) {
                return false;
            }
            var manifest = mapper.readValue(artifact.content(), VerificationResultManifest.class);
            if (!manifest.verificationRunId().equals(run.verificationRunId())
                    || !manifest.buildSessionId().equals(run.buildSessionId())
                    || !manifest.candidateId().equals(run.candidateId())
                    || !manifest.candidateManifestRef().equals(candidate.sourceManifestRef())
                    || !manifest.candidateHash().equals(candidate.sourceHash())
                    || !manifest.dependencyLockRef().equals(candidate.dependencyLockRef())
                    || !manifest.dependencyLockHash().equals(candidate.dependencyLockHash())
                    || !manifest.toolchainLockRef().equals(requiredToolchainRef)
                    || !manifest.toolchainLockHash().equals(requiredToolchainHash)
                    || !manifest.fixtureSetRef().equals(requiredFixtureSetRef)
                    || !manifest.fixtureSetHash().equals(requiredFixtureSetHash)
                    || !manifest.gateProfile().equals(run.gateProfile())
                    || manifest.status() != VerificationStatus.PASSED
                    || manifest.disposition() != VerificationDisposition.REVIEW_ELIGIBLE
                    || !manifest.stableCodes().equals(List.of("VERIFIED"))
                    || !manifest.sourceHashBefore().equals(candidate.sourceHash())
                    || !manifest.sourceHashAfter().equals(candidate.sourceHash())
                    || !manifest.sandbox().equals(expectedSandbox())
                    || manifest.commands().size() != expectedCommands.size()
                    || manifest.fixtureSelfTest() == null
                    || manifest.evidenceArtifacts().size() != 1 + expectedCommands.size() * 2
                            + (productAcceptance ? 1 : 0)
                    || new HashSet<>(manifest.evidenceArtifacts()).size() != manifest.evidenceArtifacts().size()) {
                return false;
            }
            var selfTest = manifest.fixtureSelfTest();
            VerificationFixtureSet fixtureSet = readFixtureSet(run);
            if (selfTest.exitCode() == 0
                    || !selfTest.expectedRuleIds().equals(selfTest.observedRuleIds())
                    || selfTest.fixtureIds().isEmpty()
                    || fixtureSet == null
                    || !fixtureIdentityMatches(fixtureSet, selfTest)
                    || !manifest.evidenceArtifacts().getFirst().equals(selfTest.machineEvidenceRef())
                    || !selfTestEvidenceMatches(run, selfTest)) {
                return false;
            }
            MavenDependencyLock dependencyLock = readDependencyLock(
                    run, candidate.dependencyLockRef(), candidate.dependencyLockHash());
            if (dependencyLock == null) return false;
            for (int index = 0; index < expectedCommands.size(); index++) {
                VerificationCommand expected = expectedCommands.get(index);
                var command = manifest.commands().get(index);
                if (!command.commandId().equals(expected.commandId())
                        || !command.arguments().equals(expected.arguments())
                        || command.exitCode() != 0
                        || (expected == VerificationCommand.MAVEN_VERIFY && command.testCount() < 1)
                        || (expected != VerificationCommand.MAVEN_VERIFY && command.testCount() != 0)
                        || (expected == VerificationCommand.FLOWER_CHECK && command.flowerCheckFindingCount() != 0)
                        || (expected != VerificationCommand.FLOWER_CHECK && command.flowerCheckFindingCount() != 0)
                        || !manifest.evidenceArtifacts().get(1 + index * 2).equals(command.rawLogRef())
                        || !manifest.evidenceArtifacts().get(2 + index * 2).equals(command.machineEvidenceRef())
                        || !evidenceArtifactMatches(run, command.rawLogRef(), command.rawLogHash())
                        || !(expected == VerificationCommand.MAINTENANCE_ACCEPTANCE
                                ? productAcceptanceEvidenceMatches(run, manifest, command, candidate)
                                : commandMachineEvidenceMatches(
                                        run, expected, command, dependencyLock.expectedDependencyCoordinates()))) {
                    return false;
                }
            }
            return exactInputArtifact(run, candidate.dependencyLockRef(), candidate.dependencyLockHash(),
                            "application/json", 4 * 1024 * 1024)
                    && exactInputArtifact(run, requiredToolchainRef, requiredToolchainHash,
                            "application/json", 4 * 1024 * 1024)
                    && exactInputArtifact(run, requiredFixtureSetRef, requiredFixtureSetHash,
                            "application/json", MAX_RESULT_MANIFEST_BYTES)
                    && fixtureArtifactsMatch(run, fixtureSet)
                    && exactCandidateSourceTree(run, candidate);
        } catch (RuntimeException | java.io.IOException invalidEvidence) {
            return false;
        }
    }

    private boolean productAcceptanceEvidenceMatches(
            VerificationRun run, VerificationResultManifest manifest,
            io.github.flowerjvm.factory.contracts.verification.VerificationCommandEvidence command,
            io.github.flowerjvm.factory.application.candidate.CandidateVersion candidate) {
        byte[] summary = machineBytes(run, command.machineEvidenceRef(), command.machineEvidenceHash());
        var actual = artifacts.find(run.tenantId(), manifest.evidenceArtifacts().getLast()).orElse(null);
        if (summary == null || actual == null
                || !actual.tenantId().equals(run.tenantId())
                || !actual.reference().equals(manifest.evidenceArtifacts().getLast())
                || !"application/json".equals(actual.mediaType())
                || actual.content().length > MAX_RESULT_MANIFEST_BYTES
                || !hasher.sha256(actual.content()).equals(actual.contentHash())
                || !acceptanceMainSourceMatches(run, candidate, summary)
                || !MaintenanceAcceptanceGate.evidenceMatches(
                        summary, actual.content(), run.candidateHash(), mapper)) return false;
        for (var required : MaintenanceInvestigationProductContract.artifacts(run.tenantId())) {
            if (!exactInputArtifact(run, required.reference(), required.contentHash(),
                    required.mediaType(), MAX_RESULT_MANIFEST_BYTES)) return false;
        }
        return true;
    }

    private boolean acceptanceMainSourceMatches(VerificationRun run,
            io.github.flowerjvm.factory.application.candidate.CandidateVersion candidate, byte[] summary) {
        try {
            var source = artifacts.find(run.tenantId(), candidate.sourceManifestRef()).orElse(null);
            if (source == null || source.content().length > 4 * 1024 * 1024) return false;
            var manifest = sourceManifests.read(source.content());
            var mainEntries = manifest.files().stream()
                    .filter(entry -> entry.path().startsWith("src/main/java/"))
                    .map(entry -> entry.path() + "\t" + entry.contentHash().sha256())
                    .sorted().toList();
            if (mainEntries.isEmpty()) return false;
            ContentHash expected = hasher.sha256(String.join("\n", mainEntries)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JsonNode actual = mapper.readTree(summary).path("mainSourceHash");
            // The entire source tree is independently rehashed before this read gate returns true.
            return actual.isTextual() && expected.sha256().equals(actual.textValue());
        } catch (java.io.IOException | RuntimeException invalid) {
            return false;
        }
    }

    private boolean exactInputArtifact(
            VerificationRun run,
            ArtifactReference reference,
            ContentHash hash,
            String mediaType,
            int maximumBytes) {
        var artifact = artifacts.find(run.tenantId(), reference).orElse(null);
        if (artifact == null || !artifact.reference().equals(reference)
                || !artifact.mediaType().equals(mediaType) || !artifact.contentHash().equals(hash)) return false;
        byte[] bytes = artifact.content();
        return bytes.length <= maximumBytes && hasher.sha256(bytes).equals(hash);
    }

    private boolean exactCandidateSourceTree(
            VerificationRun run,
            io.github.flowerjvm.factory.application.candidate.CandidateVersion candidate) {
        var manifestArtifact = artifacts.find(run.tenantId(), candidate.sourceManifestRef()).orElse(null);
        if (manifestArtifact == null || !"application/json".equals(manifestArtifact.mediaType())) return false;
        byte[] manifestBytes = manifestArtifact.content();
        if (manifestBytes.length > 4 * 1024 * 1024
                || !hasher.sha256(manifestBytes).equals(manifestArtifact.contentHash())) return false;
        try {
            CandidateSourceManifest manifest = sourceManifests.read(manifestBytes);
            if (!manifest.candidateId().equals(candidate.candidateId())
                    || !manifest.buildSessionId().equals(candidate.buildSessionId())
                    || !manifest.candidateHash().equals(candidate.sourceHash())
                    || manifest.fileCount() > CandidateWorkspaceMaterializer.DEFAULT_LIMITS.maxFiles()
                    || manifest.totalBytes() > CandidateWorkspaceMaterializer.DEFAULT_LIMITS.maxTotalBytes()) return false;
            var verified = new java.util.ArrayList<CandidateSourceEntry>();
            var paths = new HashSet<String>();
            var foldedPaths = new HashSet<String>();
            for (CandidateSourceEntry entry : manifest.files()) {
                if (!paths.add(entry.path())
                        || !foldedPaths.add(entry.path().toLowerCase(Locale.ROOT))
                        || !safeSourcePath(entry.path())
                        || entry.sizeBytes() > CandidateWorkspaceMaterializer.DEFAULT_LIMITS.maxFileBytes()) return false;
                var artifact = artifacts.find(run.tenantId(), entry.artifactRef()).orElse(null);
                if (artifact == null || !artifact.contentHash().equals(entry.contentHash())) return false;
                byte[] source = artifact.content();
                if (source.length != entry.sizeBytes() || !hasher.sha256(source).equals(entry.contentHash())) return false;
                verified.add(new CandidateSourceEntry(
                        entry.path(), entry.artifactRef(), hasher.sha256(source), source.length));
            }
            return hasher.hashEntries(verified).equals(candidate.sourceHash());
        } catch (java.io.IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean safeSourcePath(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.length() > 512) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.chars().anyMatch(character -> character < 32 || "<>:\"|?*".indexOf(character) >= 0)
                    || segment.matches("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")
                    || segment.equals("target") || segment.equals(".factory-evidence")) return false;
        }
        return true;
    }

    private VerificationFixtureSet readFixtureSet(VerificationRun run) {
        var artifact = artifacts.find(run.tenantId(), requiredFixtureSetRef).orElse(null);
        if (artifact == null || !"application/json".equals(artifact.mediaType())
                || !artifact.contentHash().equals(requiredFixtureSetHash)) return null;
        byte[] bytes = artifact.content();
        if (bytes.length > MAX_RESULT_MANIFEST_BYTES || !hasher.sha256(bytes).equals(requiredFixtureSetHash)) return null;
        try {
            return mapper.readValue(bytes, VerificationFixtureSet.class);
        } catch (java.io.IOException | RuntimeException exception) {
            return null;
        }
    }

    private static boolean fixtureIdentityMatches(
            VerificationFixtureSet fixtureSet,
            io.github.flowerjvm.factory.contracts.verification.VerificationFixtureSelfTestEvidence evidence) {
        List<String> fixtureIds = fixtureSet.fixtures().stream()
                .map(VerificationFixture::fixtureId).distinct().sorted().toList();
        List<String> ruleIds = fixtureSet.fixtures().stream()
                .map(VerificationFixture::expectedRuleId).distinct().sorted().toList();
        return evidence.fixtureIds().equals(fixtureIds)
                && evidence.expectedRuleIds().equals(ruleIds)
                && evidence.observedRuleIds().equals(ruleIds);
    }

    private boolean fixtureArtifactsMatch(VerificationRun run, VerificationFixtureSet fixtureSet) {
        long total = 0;
        var paths = new HashSet<String>();
        var foldedPaths = new HashSet<String>();
        for (VerificationFixture fixture : fixtureSet.fixtures()) {
            if (!paths.add(fixture.path())
                    || !foldedPaths.add(fixture.path().toLowerCase(Locale.ROOT))
                    || !safeSourcePath(fixture.path())
                    || fixture.sizeBytes() > 8L * 1024 * 1024) return false;
            total = Math.addExact(total, fixture.sizeBytes());
            if (total > 8L * 1024 * 1024) return false;
            var artifact = artifacts.find(run.tenantId(), fixture.artifactRef()).orElse(null);
            if (artifact == null || !artifact.mediaType().equals("text/plain; charset=utf-8")
                    || !artifact.contentHash().equals(fixture.contentHash())) return false;
            byte[] bytes = artifact.content();
            if (bytes.length != fixture.sizeBytes() || !hasher.sha256(bytes).equals(fixture.contentHash())) return false;
        }
        return true;
    }

    private boolean evidenceArtifactMatches(VerificationRun run, ArtifactReference reference, ContentHash hash) {
        var log = artifacts.find(run.tenantId(), reference).orElse(null);
        if (log == null || !log.mediaType().startsWith("text/plain")
                || log.content().length > MAX_COMMAND_LOG_BYTES) return false;
        return log.reference().equals(reference)
                && log.contentHash().equals(hash)
                && hasher.sha256(log.content()).equals(hash);
    }

    private boolean selfTestEvidenceMatches(
            VerificationRun run,
            io.github.flowerjvm.factory.contracts.verification.VerificationFixtureSelfTestEvidence evidence) {
        byte[] bytes = machineBytes(run, evidence.machineEvidenceRef(), evidence.machineEvidenceHash());
        if (bytes == null) return false;
        try {
            JsonNode root = mapper.readTree(bytes);
            return exactFields(root, Set.of(
                    "schemaVersion", "exitCode", "fixtureIds", "expectedRuleIds", "observedRuleIds",
                    "originalSarifSha256", "originalSarifSizeBytes"))
                    && "factory.verifier-self-test-evidence.v1".equals(root.path("schemaVersion").asText())
                    && root.path("exitCode").asInt(0) == evidence.exitCode()
                    && textArray(root.path("fixtureIds")).equals(evidence.fixtureIds())
                    && textArray(root.path("expectedRuleIds")).equals(evidence.expectedRuleIds())
                    && textArray(root.path("observedRuleIds")).equals(evidence.observedRuleIds())
                    && root.path("originalSarifSha256").asText().matches("[0-9a-f]{64}")
                    && root.path("originalSarifSizeBytes").canConvertToInt()
                    && root.path("originalSarifSizeBytes").asInt() > 0
                    && root.path("originalSarifSizeBytes").asInt() <= MAX_RESULT_MANIFEST_BYTES;
        } catch (java.io.IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean commandMachineEvidenceMatches(
            VerificationRun run,
            VerificationCommand expected,
            io.github.flowerjvm.factory.contracts.verification.VerificationCommandEvidence evidence,
            List<String> expectedDependencies) {
        byte[] bytes = machineBytes(run, evidence.machineEvidenceRef(), evidence.machineEvidenceHash());
        if (bytes == null) return false;
        try {
            JsonNode root = mapper.readTree(bytes);
            Set<String> fields = new HashSet<>(Set.of(
                    "schemaVersion", "commandId", "testCount", "flowerCheckFindingCount", "inputDigests"));
            if (expected == VerificationCommand.FLOWER_CHECK) fields.add("observedRuleIds");
            if (expected == VerificationCommand.MAVEN_DEPENDENCY_TREE) fields.add("dependencyCoordinates");
            if (!exactFields(root, fields)
                    || !"factory.verification-machine-evidence.v1".equals(root.path("schemaVersion").asText())
                    || !expected.commandId().equals(root.path("commandId").asText())
                    || root.path("testCount").asInt(-1) != evidence.testCount()
                    || root.path("flowerCheckFindingCount").asInt(-1) != evidence.flowerCheckFindingCount()
                    || !validInputDigests(expected, root.path("inputDigests"))) return false;
            if (expected == VerificationCommand.FLOWER_CHECK
                    && !textArray(root.path("observedRuleIds")).isEmpty()) return false;
            return expected != VerificationCommand.MAVEN_DEPENDENCY_TREE
                    || textArray(root.path("dependencyCoordinates")).equals(expectedDependencies.stream().sorted().toList());
        } catch (java.io.IOException | RuntimeException exception) {
            return false;
        }
    }

    private byte[] machineBytes(VerificationRun run, ArtifactReference reference, ContentHash hash) {
        var artifact = artifacts.find(run.tenantId(), reference).orElse(null);
        if (artifact == null || !artifact.mediaType().equals("application/json")
                || artifact.content().length > MAX_RESULT_MANIFEST_BYTES
                || !artifact.contentHash().equals(hash) || !hasher.sha256(artifact.content()).equals(hash)) return null;
        return artifact.content();
    }

    private MavenDependencyLock readDependencyLock(
            VerificationRun run, ArtifactReference reference, ContentHash hash) {
        var artifact = artifacts.find(run.tenantId(), reference).orElse(null);
        if (artifact == null || !artifact.mediaType().equals("application/json")
                || !artifact.contentHash().equals(hash)) return null;
        byte[] bytes = artifact.content();
        if (bytes.length > 4 * 1024 * 1024 || !hasher.sha256(bytes).equals(hash)) return null;
        try { return mapper.readValue(bytes, MavenDependencyLock.class); }
        catch (java.io.IOException | RuntimeException exception) { return null; }
    }

    private static boolean validInputDigests(VerificationCommand command, JsonNode digests) {
        if (!digests.isArray() || digests.isEmpty() || digests.size() > 2_048) return false;
        var names = new HashSet<String>();
        for (JsonNode digest : digests) {
            if (!exactFields(digest, Set.of("name", "sha256", "sizeBytes"))) return false;
            String name = digest.path("name").asText();
            boolean expectedName = switch (command) {
                case MAVEN_VERIFY -> name.startsWith("surefire/") && name.endsWith(".xml");
                case FLOWER_CHECK -> name.equals("flower-check.sarif");
                case MAVEN_DEPENDENCY_TREE -> name.equals("dependency-tree.txt");
                case MAINTENANCE_ACCEPTANCE -> false; // Validated by the separate product evidence gate.
            };
            if (!expectedName || !names.add(name)
                    || !digest.path("sha256").asText().matches("[0-9a-f]{64}")
                    || !digest.path("sizeBytes").canConvertToInt()
                    || digest.path("sizeBytes").asInt() < 1
                    || digest.path("sizeBytes").asInt() > MAX_RESULT_MANIFEST_BYTES) return false;
        }
        return true;
    }

    private static boolean exactFields(JsonNode object, Set<String> fields) {
        if (!object.isObject() || object.size() != fields.size()) return false;
        var names = new HashSet<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names.equals(fields);
    }

    private static List<String> textArray(JsonNode array) {
        if (!array.isArray() || array.size() > 8_192) return List.of();
        var result = new java.util.ArrayList<String>();
        for (JsonNode node : array) {
            if (!node.isTextual() || node.asText().isBlank()) return List.of();
            result.add(node.asText());
        }
        return List.copyOf(result);
    }

    private static VerificationSandboxEvidence expectedSandbox() {
        return new VerificationSandboxEvidence(
                "docker-cli",
                DockerCliVerificationSandbox.IMAGE,
                "factory.verifier-sandbox.v1",
                true,
                true,
                true,
                1_073_741_824L,
                2.0,
                256,
                DockerCliVerificationSandbox.DEFAULT_TIMEOUT.toMillis());
    }
}
