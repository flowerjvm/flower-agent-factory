package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.production.AgentPackProductionRepairEvidenceReader;
import io.github.flowerjvm.factory.application.production.AgentPackProductionRepairFindings;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.*;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Exact failure evidence decoder. It never returns PASSED, executes candidate code, or grants
 * review eligibility. Logs are hash-checked before returning only bounded untrusted excerpts.
 */
public final class ArtifactAgentPackProductionRepairEvidenceReader implements AgentPackProductionRepairEvidenceReader {
    private static final int MAX_MANIFEST = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_MANIFEST = 256 * 1024;
    private static final int MAX_RESULT = 1024 * 1024;
    private static final Set<String> REPAIR_CODES = Set.of(MAVEN_VERIFICATION_FAILED, FLOWER_CHECK_FAILED, PRODUCT_ACCEPTANCE_FAILED);
    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;
    private final ArtifactReference fixtureRef;
    private final ContentHash fixtureHash;
    private final ArtifactReference toolchainRef;
    private final ContentHash toolchainHash;
    private final OrdinalSourceTreeHasher hasher = new OrdinalSourceTreeHasher();
    private final CandidateSourceManifestReader sourceManifests;

    public ArtifactAgentPackProductionRepairEvidenceReader(
            ArtifactStore artifacts, ObjectMapper objectMapper, ArtifactReference fixtureRef,
            ContentHash fixtureHash, ArtifactReference toolchainRef, ContentHash toolchainHash) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                        .maxNumberLength(32).maxStringLength(MAX_RESULT).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.fixtureRef = Objects.requireNonNull(fixtureRef, "fixtureRef");
        this.fixtureHash = Objects.requireNonNull(fixtureHash, "fixtureHash");
        this.toolchainRef = Objects.requireNonNull(toolchainRef, "toolchainRef");
        this.toolchainHash = Objects.requireNonNull(toolchainHash, "toolchainHash");
        this.sourceManifests = new CandidateSourceManifestReader(this.mapper);
    }

    @Override
    public ContentHash validateSource(CandidateVersion candidate) {
        try {
            Artifact source = exact(candidate.tenantId(), candidate.sourceManifestRef(), null, "application/json", MAX_SOURCE_MANIFEST);
            CandidateSourceManifest manifest = sourceManifests.read(source.content());
            var limits = CandidateWorkspaceMaterializer.DEFAULT_LIMITS;
            if (!manifest.candidateId().equals(candidate.candidateId())
                    || !manifest.buildSessionId().equals(candidate.buildSessionId())
                    || !manifest.candidateHash().equals(candidate.sourceHash()) || manifest.fileCount() < 1
                    || manifest.fileCount() > limits.maxFiles() || manifest.totalBytes() > limits.maxTotalBytes()) throw invalid();
            var foldedPaths = new HashSet<String>();
            for (CandidateSourceEntry entry : manifest.files()) {
                String folded = PortableRelativePath.caseFold(entry.path());
                if (entry.path().length() > limits.maxPathCharacters() || !foldedPaths.add(folded)
                        || entry.sizeBytes() > limits.maxFileBytes()
                        || Arrays.stream(folded.split("/")).anyMatch(s -> s.equals("target") || s.equals(".factory-evidence"))) {
                    throw invalid();
                }
                // The preparation Action checks only bounded metadata. Actual source bytes,
                // size/hash, secret and scope checks belong to outbox-lane materialization.
            }
            for (String path : foldedPaths) {
                for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                    if (foldedPaths.contains(path.substring(0, slash))) throw invalid();
                }
            }
            if (!hasher.hashEntries(manifest.files()).equals(candidate.sourceHash())) throw invalid();
            return source.contentHash();
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }

    @Override
    public Failure readFailure(CandidateVersion candidate, VerificationRun run) {
        try {
            if (!VerificationProfiles.supported(run.gateProfile()) || run.status() != VerificationRunStatus.FAILED
                    || run.disposition().filter(VerificationDisposition.REPAIR_REQUIRED::equals).isEmpty()
                    || !run.tenantId().equals(candidate.tenantId()) || !run.buildSessionId().equals(candidate.buildSessionId())
                    || !run.candidateId().equals(candidate.candidateId()) || !run.candidateHash().equals(candidate.sourceHash())
                    || !run.toolchainLockHash().equals(toolchainHash) || !run.fixtureSetHash().equals(fixtureHash)
                    || !candidate.toolchainLockRef().equals(toolchainRef) || !candidate.toolchainLockHash().equals(toolchainHash)) throw invalid();
            validateSource(candidate);
            Artifact result = exact(run.tenantId(), run.resultManifestRef().orElseThrow(), run.resultManifestHash().orElseThrow(),
                    "application/json", MAX_RESULT);
            VerificationResultManifest manifest = mapper.readValue(result.content(), VerificationResultManifest.class);
            if (!manifest.verificationRunId().equals(run.verificationRunId()) || !manifest.buildSessionId().equals(run.buildSessionId())
                    || !manifest.candidateId().equals(candidate.candidateId()) || !manifest.candidateHash().equals(candidate.sourceHash())
                    || !manifest.candidateManifestRef().equals(candidate.sourceManifestRef())
                    || !manifest.dependencyLockRef().equals(candidate.dependencyLockRef())
                    || !manifest.dependencyLockHash().equals(candidate.dependencyLockHash())
                    || !manifest.toolchainLockRef().equals(toolchainRef) || !manifest.toolchainLockHash().equals(toolchainHash)
                    || !manifest.fixtureSetRef().equals(fixtureRef) || !manifest.fixtureSetHash().equals(fixtureHash)
                    || !manifest.gateProfile().equals(run.gateProfile()) || manifest.status() != VerificationStatus.FAILED
                    || manifest.disposition() != VerificationDisposition.REPAIR_REQUIRED
                    || !manifest.sourceHashBefore().equals(candidate.sourceHash()) || !manifest.sourceHashAfter().equals(candidate.sourceHash())
                    || !manifest.sandbox().equals(expectedSandbox()) || manifest.stableCodes().isEmpty()
                    || !REPAIR_CODES.containsAll(manifest.stableCodes())
                    || new HashSet<>(manifest.stableCodes()).size() != manifest.stableCodes().size()
                    || !manifest.stableCodes().getFirst().equals(run.terminalCode().orElseThrow())) throw invalid();
            exact(run.tenantId(), candidate.dependencyLockRef(), candidate.dependencyLockHash(), "application/json", MAX_MANIFEST);
            exact(run.tenantId(), toolchainRef, toolchainHash, "application/json", MAX_MANIFEST);
            Artifact fixtures = exact(run.tenantId(), fixtureRef, fixtureHash, "application/json", MAX_RESULT);
            VerificationFixtureSet fixtureSet = mapper.readValue(fixtures.content(), VerificationFixtureSet.class);
            var self = manifest.fixtureSelfTest();
            if (self == null || !self.expectedRuleIds().equals(self.observedRuleIds())
                    || !self.fixtureIds().equals(fixtureSet.fixtures().stream().map(VerificationFixture::fixtureId).distinct().sorted().toList())
                    || !self.expectedRuleIds().equals(fixtureSet.fixtures().stream().map(VerificationFixture::expectedRuleId).distinct().sorted().toList())) throw invalid();
            exact(run.tenantId(), self.machineEvidenceRef(), self.machineEvidenceHash(), "application/json", MAX_RESULT);
            boolean acceptance = manifest.stableCodes().contains(PRODUCT_ACCEPTANCE_FAILED);
            List<VerificationCommand> expected = acceptance ? VerificationProfiles.commands(run.gateProfile()) : VerificationProfiles.technicalCommands();
            if (acceptance && (!VerificationProfiles.maintenance(run.gateProfile())
                    || !manifest.stableCodes().equals(List.of(PRODUCT_ACCEPTANCE_FAILED)))) throw invalid();
            if (manifest.commands().size() != expected.size()) throw invalid();
            var expectedRefs = new ArrayList<ArtifactReference>();
            expectedRefs.add(self.machineEvidenceRef());
            var actualFailureCodes = new LinkedHashSet<String>();
            var diagnostics = new ArrayList<Diagnostic>();
            for (int index = 0; index < expected.size(); index++) {
                VerificationCommand command = expected.get(index);
                var actual = manifest.commands().get(index);
                if (!actual.commandId().equals(command.commandId()) || !actual.arguments().equals(command.arguments())
                        || (acceptance && command != VerificationCommand.MAINTENANCE_ACCEPTANCE && actual.exitCode() != 0)) throw invalid();
                Artifact log = exact(run.tenantId(), actual.rawLogRef(), actual.rawLogHash(), "text/plain; charset=utf-8",
                        DockerCliVerificationSandbox.DEFAULT_MAX_LOG_BYTES + 64);
                Artifact machine = exact(run.tenantId(), actual.machineEvidenceRef(), actual.machineEvidenceHash(), "application/json", MAX_RESULT);
                expectedRefs.add(log.reference());
                expectedRefs.add(machine.reference());
                String snippet = new String(log.content(), StandardCharsets.UTF_8);
                if (command == VerificationCommand.MAINTENANCE_ACCEPTANCE) {
                    if (manifest.evidenceArtifacts().isEmpty()) throw invalid();
                    Artifact raw = exact(run.tenantId(), manifest.evidenceArtifacts().getLast(), null, "application/json", MAX_RESULT);
                    expectedRefs.add(raw.reference());
                    JsonNode summary = mapper.readTree(machine.content());
                    if (!summary.path("passed").isBoolean() || summary.path("passed").booleanValue()
                            || !summary.path("schemaVersion").asText().equals(MaintenanceAcceptanceGate.SUMMARY_SCHEMA)
                            || !summary.path("gateProfile").asText().equals(run.gateProfile())
                            || !summary.path("candidateSourceHash").asText().equals(candidate.sourceHash().sha256())
                            || !summary.path("actualHash").asText().equals(raw.contentHash().sha256())
                            || !summary.path("productContractHash").asText().equals(MaintenanceInvestigationProductContract.lock().hash().sha256())
                            || !summary.path("failedCaseIds").isArray() || summary.path("failedCaseIds").size() > 16) throw invalid();
                    snippet = "Factory acceptance failed case IDs: " + summary.path("failedCaseIds") + "\n" + snippet;
                    actualFailureCodes.add(PRODUCT_ACCEPTANCE_FAILED);
                } else if (actual.exitCode() != 0) {
                    actualFailureCodes.add(command == VerificationCommand.FLOWER_CHECK ? FLOWER_CHECK_FAILED : MAVEN_VERIFICATION_FAILED);
                }
                diagnostics.add(new Diagnostic(command.commandId(), log.reference(), log.contentHash(), bounded(snippet)));
            }
            if (!manifest.stableCodes().equals(List.copyOf(actualFailureCodes))
                    || !expectedRefs.equals(manifest.evidenceArtifacts())
                    || new HashSet<>(expectedRefs).size() != expectedRefs.size()) throw invalid();
            return new Failure(manifest, diagnostics);
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }

    private Artifact exact(TenantId tenant, ArtifactReference reference, ContentHash expectedHash, String mediaType, int maximum) {
        Artifact artifact = artifacts.find(tenant, reference).orElseThrow(ArtifactAgentPackProductionRepairEvidenceReader::invalid);
        if (!artifact.tenantId().equals(tenant) || !artifact.reference().equals(reference)
                || (expectedHash != null && !artifact.contentHash().equals(expectedHash))
                || (mediaType != null && !artifact.mediaType().equals(mediaType))
                || artifact.content().length > maximum || !hasher.sha256(artifact.content()).equals(artifact.contentHash())) throw invalid();
        return artifact;
    }

    private static String bounded(String text) {
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int length = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > 4096) break;
            bytes += length;
            end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }

    static VerificationSandboxEvidence expectedSandbox() {
        return new VerificationSandboxEvidence("docker-cli", DockerCliVerificationSandbox.IMAGE,
                "factory.verifier-sandbox.v1", true, true, true, 1_073_741_824L, 2.0, 256,
                DockerCliVerificationSandbox.DEFAULT_TIMEOUT.toMillis());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(AgentPackProductionRepairFindings.INVALID);
    }
}
