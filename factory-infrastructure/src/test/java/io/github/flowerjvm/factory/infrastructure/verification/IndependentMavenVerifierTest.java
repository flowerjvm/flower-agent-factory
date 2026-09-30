package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.CANDIDATE_MANIFEST_INVALID;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.FLOWER_CHECK_EVIDENCE_INCOMPLETE;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.FLOWER_CHECK_FAILED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.MAVEN_TESTS_MISSING;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.MAVEN_VERIFICATION_FAILED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.PRODUCT_ACCEPTANCE_FAILED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.SECRET_MATERIAL_DETECTED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.VERIFICATION_PROFILE_UNSUPPORTED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.VERIFIED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_WRITE_OUTSIDE_ALLOWED_PATH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.verification.MavenDependencyLock;
import io.github.flowerjvm.factory.contracts.verification.MavenRepositoryFile;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.verification.VerificationRequest;
import io.github.flowerjvm.factory.contracts.verification.VerificationResultManifest;
import io.github.flowerjvm.factory.contracts.verification.VerificationSandboxEvidence;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndependentMavenVerifierTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final BuildSessionId SESSION = new BuildSessionId("session-a");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-a");
    private static final VerificationRunId RUN = new VerificationRunId("verification-a");
    @TempDir
    Path temporaryDirectory;

    @Test
    void validIndependentEvidenceIsHashBoundAndReviewEligible() throws Exception {
        var fixture = fixture(Map.of(
                "pom.xml", "<project/>",
                "src/test/java/ExampleTest.java", "class ExampleTest {}"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertEquals(VerificationStatus.PASSED, result.status());
        assertEquals(VerificationDisposition.REVIEW_ELIGIBLE, result.disposition());
        assertEquals(List.of(VERIFIED), result.stableCodes());
        assertEquals(7, result.evidenceArtifacts().size());
        Artifact resultArtifact = fixture.store().required(result.resultManifestRef());
        assertEquals(result.resultManifestHash(), fixture.hasher().sha256(resultArtifact.content()));
        VerificationResultManifest manifest = new ObjectMapper().readValue(
                resultArtifact.content(), VerificationResultManifest.class);
        assertEquals(fixture.request().candidateHash(), manifest.candidateHash());
        assertEquals(manifest.sourceHashBefore(), manifest.sourceHashAfter());
        assertEquals(fixture.request().toolchainLockHash(), manifest.toolchainLockHash());
        assertEquals(fixture.request().fixtureSetHash(), manifest.fixtureSetHash());
        assertEquals(3, manifest.commands().size());
        for (int index = 0; index < manifest.commands().size(); index++) {
            Artifact log = fixture.store().required(result.evidenceArtifacts().get(1 + index * 2));
            assertEquals(manifest.commands().get(index).rawLogRef(), log.reference());
            assertEquals(manifest.commands().get(index).rawLogHash(), fixture.hasher().sha256(log.content()));
        }
    }

    @Test
    void productionScalarSourceManifestPassesIndependentVerificationAndFullReviewReadback() throws Exception {
        var fixture = fixture(Map.of("pom.xml", "<project/>", "src/test/java/ExampleTest.java", "class ExampleTest {}"), true);
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));
        sandbox.canonicalReviewEvidenceShape = true;
        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertEquals(VerificationStatus.PASSED, result.status());
        assertEquals(List.of(VERIFIED), result.stableCodes());
        var run = terminalRun(fixture.request(), result);
        var validator = reviewValidator(fixture);
        assertTrue(validator.isReviewEligible(run), "receipt recorder's full reader must accept the same scalar source wire");
        assertEquals(fixture.request().candidateHash(), fixture.readResult(result.resultManifestRef()).sourceHashBefore());
        assertEquals(fixture.request().candidateHash(), fixture.readResult(result.resultManifestRef()).sourceHashAfter());

        Artifact source = fixture.store().required(fixture.request().candidateManifest());
        var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(source.content());
        unknown.put("unknown", true);
        fixture.store().replace(source.reference(), new ObjectMapper().writeValueAsBytes(unknown), fixture.hasher());
        assertFalse(validator.isReviewEligible(run), "rehashing malformed source metadata cannot grant review");
    }

    @Test
    void historicalRecordObjectSourceManifestStillPassesFullReviewReadback() throws Exception {
        var fixture = fixture(Map.of("pom.xml", "<project/>", "src/test/java/ExampleTest.java", "class ExampleTest {}"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));
        sandbox.canonicalReviewEvidenceShape = true;
        Artifact original = fixture.store().required(fixture.request().candidateManifest());
        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertEquals(VerificationStatus.PASSED, result.status());
        assertTrue(reviewValidator(fixture).isReviewEligible(terminalRun(fixture.request(), result)));
        Artifact after = fixture.store().required(fixture.request().candidateManifest());
        assertArrayEquals(original.content(), after.content());
        assertEquals(original.contentHash(), after.contentHash());
    }

    @Test
    void scalarMaintenanceManifestAlsoPassesTheMainSourceBoundAcceptanceReadback() throws Exception {
        String main = "class Example {}";
        var fixture = fixture(Map.of("pom.xml", "<project/>", "src/main/java/Example.java", main), true);
        var mapper = new ObjectMapper();
        var observations = new ArrayList<Map<String, Object>>();
        // Scripted sandbox evidence tests the reader contract; this is not a built Maintenance product.
        for (var acceptanceCase : MaintenanceInvestigationProductContract.cases()) {
            for (int invocation = 0; invocation < acceptanceCase.repeatInvocations(); invocation++) {
                observations.add(Map.of("caseId", acceptanceCase.caseId(), "invocation", invocation,
                        "actual", acceptanceCase.expectedOutput()));
            }
        }
        ContentHash mainHash = fixture.hasher().sha256(("src/main/java/Example.java\t"
                + fixture.hasher().sha256(main.getBytes(StandardCharsets.UTF_8)).sha256())
                .getBytes(StandardCharsets.UTF_8));
        byte[] actual = mapper.writeValueAsBytes(Map.of(
                "schemaVersion", "factory.maintenance-actual.v1",
                "candidateSourceHash", fixture.request().candidateHash().sha256(),
                "mainSourceHash", mainHash.sha256(),
                "inputHash", fixture.hasher().sha256(MaintenanceAcceptanceGate.inputBytes(mapper)).sha256(),
                "cases", observations));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS"),
                VerificationCommand.MAINTENANCE_ACCEPTANCE,
                result(0, "synthetic acceptance result", Map.of("maintenance-acceptance.json", actual))));
        sandbox.canonicalReviewEvidenceShape = true;
        var request = withProfile(fixture.request(), MaintenanceInvestigationProductContract.GATE_PROFILE);

        var result = fixture.verifier(sandbox).verify(request);

        assertEquals(VerificationStatus.PASSED, result.status());
        assertEquals(List.of(VERIFIED), result.stableCodes());
        assertTrue(reviewValidator(fixture).isReviewEligible(terminalRun(request, result)));
    }

    @Test
    void unsupportedProfileIsBlockedWithoutRunningAnySandboxCommand() {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        var sandbox = new ScriptedSandbox(Map.of());

        var result = fixture.verifier(sandbox).verify(withProfile(fixture.request(), "unknown-product-v1"));

        assertEquals(VerificationStatus.FAILED, result.status());
        assertEquals(VerificationDisposition.BLOCKED, result.disposition());
        assertEquals(List.of(VERIFICATION_PROFILE_UNSUPPORTED), result.stableCodes());
        assertTrue(sandbox.invocations.isEmpty());
        assertTrue(result.evidenceArtifacts().isEmpty());
        assertTrue(fixture.readResult(result.resultManifestRef()).commands().isEmpty());
    }

    @Test
    void technicalPassAndZeroExitCannotPromoteSemanticallyFailedMaintenanceAcceptance() throws Exception {
        String main = "class Example {}";
        var fixture = fixture(Map.of("pom.xml", "<project/>", "src/main/java/Example.java", main));
        var mapper = new ObjectMapper();
        var observations = new ArrayList<Map<String, Object>>();
        for (var acceptanceCase : MaintenanceInvestigationProductContract.cases()) {
            for (int invocation = 0; invocation < acceptanceCase.repeatInvocations(); invocation++) {
                Object actual = acceptanceCase.caseId().equals("NORMAL")
                        ? Map.of("notTheRequiredOutput", true) : acceptanceCase.expectedOutput();
                observations.add(Map.of("caseId", acceptanceCase.caseId(), "invocation", invocation, "actual", actual));
            }
        }
        ContentHash mainHash = fixture.hasher().sha256(("src/main/java/Example.java\t"
                + fixture.hasher().sha256(main.getBytes(StandardCharsets.UTF_8)).sha256())
                .getBytes(StandardCharsets.UTF_8));
        byte[] actualBytes = mapper.writeValueAsBytes(Map.of(
                "schemaVersion", "factory.maintenance-actual.v1",
                "candidateSourceHash", fixture.request().candidateHash().sha256(),
                "mainSourceHash", mainHash.sha256(),
                "inputHash", fixture.hasher().sha256(MaintenanceAcceptanceGate.inputBytes(mapper)).sha256(),
                "cases", observations));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS"),
                VerificationCommand.MAINTENANCE_ACCEPTANCE,
                        result(0, "candidate returned normally", Map.of("maintenance-acceptance.json", actualBytes))));

        var result = fixture.verifier(sandbox).verify(withProfile(
                fixture.request(), MaintenanceInvestigationProductContract.GATE_PROFILE));

        assertEquals(VerificationStatus.FAILED, result.status());
        assertEquals(VerificationDisposition.REPAIR_REQUIRED, result.disposition());
        assertEquals(List.of(PRODUCT_ACCEPTANCE_FAILED), result.stableCodes());
        assertEquals(10, result.evidenceArtifacts().size());
        var manifest = fixture.readResult(result.resultManifestRef());
        assertEquals(4, manifest.commands().size());
        var acceptance = manifest.commands().get(3);
        assertEquals(VerificationCommand.MAINTENANCE_ACCEPTANCE.commandId(), acceptance.commandId());
        assertEquals(0, acceptance.exitCode());
        var summary = mapper.readTree(fixture.store().required(acceptance.machineEvidenceRef()).content());
        assertFalse(summary.path("passed").asBoolean());
        assertEquals(mapper.valueToTree(List.of("NORMAL")), summary.path("failedCaseIds"));
    }

    private static VerificationRequest withProfile(VerificationRequest request, String profile) {
        return new VerificationRequest(request.tenantId(), request.verificationRunId(), request.buildSessionId(),
                request.candidateId(), request.candidateManifest(), request.candidateHash(), request.dependencyLockRef(),
                request.dependencyLockHash(), request.toolchainLockRef(), profile, request.toolchainLockHash(),
                request.fixtureSetRef(), request.fixtureSetHash());
    }

    @Test
    void seededBuildDefectIsRepairRequiredAndNeverReviewEligible() {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenFailure("BUILD FAILURE"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertEquals(VerificationStatus.FAILED, result.status());
        assertEquals(VerificationDisposition.REPAIR_REQUIRED, result.disposition());
        assertTrue(result.stableCodes().contains(MAVEN_VERIFICATION_FAILED));
        assertFalse(result.disposition() == VerificationDisposition.REVIEW_ELIGIBLE);
    }

    @Test
    void seededFlowerCheckFailureIsRepairRequired() {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerFailure("FLOWER-CHECK-BLOCKING failure"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertEquals(VerificationDisposition.REPAIR_REQUIRED, result.disposition());
        assertTrue(result.stableCodes().contains(FLOWER_CHECK_FAILED));
    }

    @Test
    void exitZeroWithoutTestsOrConclusiveFlowerEvidenceFailsClosed() {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, successWithoutEvidence("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, successWithoutEvidence("FLOWER-CHECK-PARSE fallback\nBUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertTrue(result.stableCodes().contains(MAVEN_TESTS_MISSING));
        assertTrue(result.stableCodes().contains(FLOWER_CHECK_EVIDENCE_INCOMPLETE));
        assertEquals(VerificationDisposition.BLOCKED, result.disposition());
    }

    @Test
    void secretCandidateIsBlockedBeforeSandboxAndSecretNeverAppearsInEvidence() {
        String secret = "FACTORY_TEST_SECRET=do-not-store";
        var fixture = fixture(Map.of("application.properties", secret));
        var sandbox = new ScriptedSandbox(Map.of());

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertEquals(List.of(SECRET_MATERIAL_DETECTED), result.stableCodes());
        assertEquals(0, sandbox.invocations.size());
        for (Artifact artifact : fixture.store().values.values()) {
            if (artifact.reference().value().contains("verification/")) {
                assertFalse(new String(artifact.content(), StandardCharsets.UTF_8).contains(secret));
            }
        }
    }

    @Test
    void secretInCandidateManifestMetadataIsBlockedBeforeMaterialization() throws Exception {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        Artifact manifestArtifact = fixture.store().required(fixture.request().candidateManifest());
        byte[] secretManifest = new String(manifestArtifact.content(), StandardCharsets.UTF_8)
                .replace("candidate-file/", "FACTORY_TEST_SECRET/candidate-file/")
                .getBytes(StandardCharsets.UTF_8);
        fixture.store().replace(manifestArtifact.reference(), secretManifest, fixture.hasher());
        var sandbox = new ScriptedSandbox(Map.of());

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertEquals(List.of(SECRET_MATERIAL_DETECTED), result.stableCodes());
        assertEquals(0, sandbox.invocations.size());
    }

    @Test
    void runtimeSecretFailsClosedAndOnlySanitizedArtifactsAreStored() {
        String secret = "sk-proj-abcdefghijklmnopqrstuvwx";
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("runtime emitted " + secret),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertTrue(result.stableCodes().contains(SECRET_MATERIAL_DETECTED));
        assertEquals(VerificationDisposition.BLOCKED, result.disposition());
        assertStoredVerificationArtifactsDoNotContain(fixture.store(), secret);
    }

    @Test
    void escapedSecretReferenceIsRejectedAfterStrictJsonParsingWithoutReexposure() throws Exception {
        String secret = "sk-proj-abcdefghijklmnopqrstuvwx";
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        Artifact manifestArtifact = fixture.store().required(fixture.request().candidateManifest());
        String escaped = "sk-\\u0070roj-abcdefghijklmnopqrstuvwx";
        byte[] secretManifest = new String(manifestArtifact.content(), StandardCharsets.UTF_8)
                .replaceFirst("candidate-file/[0-9a-f]{64}", escaped)
                .getBytes(StandardCharsets.UTF_8);
        fixture.store().replace(manifestArtifact.reference(), secretManifest, fixture.hasher());

        var result = fixture.verifier(new ScriptedSandbox(Map.of())).verify(fixture.request());

        assertEquals(List.of(SECRET_MATERIAL_DETECTED), result.stableCodes());
        assertStoredVerificationArtifactsDoNotContain(fixture.store(), secret);
    }

    @Test
    void outsideAllowedWorkspaceWriteIsBlocked() {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));
        sandbox.writeUnexpected = true;

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertTrue(result.stableCodes().contains(WORKSPACE_WRITE_OUTSIDE_ALLOWED_PATH));
        assertEquals(VerificationDisposition.BLOCKED, result.disposition());
    }

    @Test
    void candidateSourceMutationIsDetectedByBeforeAfterHash() {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        var sandbox = new ScriptedSandbox(Map.of(
                VerificationCommand.MAVEN_VERIFY, mavenSuccess("BUILD SUCCESS"),
                VerificationCommand.FLOWER_CHECK, flowerSuccess("BUILD SUCCESS"),
                VerificationCommand.MAVEN_DEPENDENCY_TREE, dependencySuccess("BUILD SUCCESS")));
        sandbox.mutateSource = true;

        var result = fixture.verifier(sandbox).verify(fixture.request());

        assertTrue(result.stableCodes().contains("CANDIDATE_SOURCE_MUTATED"));
        VerificationResultManifest manifest = fixture.readResult(result.resultManifestRef());
        assertFalse(manifest.sourceHashBefore().equals(manifest.sourceHashAfter()));
    }

    @Test
    void strictManifestParsingRejectsUnknownFieldsAndTraversal() throws Exception {
        var fixture = fixture(Map.of("pom.xml", "<project/>"));
        Artifact manifestArtifact = fixture.store().required(fixture.request().candidateManifest());
        byte[] unknown = new String(manifestArtifact.content(), StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"unknown\":true,")
                .getBytes(StandardCharsets.UTF_8);
        fixture.store().replace(manifestArtifact.reference(), unknown, fixture.hasher());

        var invalid = fixture.verifier(new ScriptedSandbox(Map.of())).verify(fixture.request());
        assertEquals(List.of(CANDIDATE_MANIFEST_INVALID), invalid.stableCodes());

        byte[] duplicate = ("{\"schemaVersion\":\"" + CandidateSourceManifest.SCHEMA_VERSION
                + "\",\"schemaVersion\":\"" + CandidateSourceManifest.SCHEMA_VERSION + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        fixture.store().replace(manifestArtifact.reference(), duplicate, fixture.hasher());
        var duplicateResult = fixture.verifier(new ScriptedSandbox(Map.of())).verify(fixture.request());
        assertEquals(List.of(CANDIDATE_MANIFEST_INVALID), duplicateResult.stableCodes());

        var traversalFixture = fixture(Map.of("pom.xml", "<project/>"));
        Artifact traversalManifest = traversalFixture.store().required(traversalFixture.request().candidateManifest());
        byte[] traversalBytes = new String(traversalManifest.content(), StandardCharsets.UTF_8)
                .replace("\"path\":\"pom.xml\"", "\"path\":\"../pom.xml\"")
                .getBytes(StandardCharsets.UTF_8);
        traversalFixture.store().replace(traversalManifest.reference(), traversalBytes, traversalFixture.hasher());
        var traversal = traversalFixture.verifier(new ScriptedSandbox(Map.of())).verify(traversalFixture.request());
        assertEquals(List.of(CANDIDATE_MANIFEST_INVALID), traversal.stableCodes());
    }

    private Fixture fixture(Map<String, String> files) {
        return fixture(files, false);
    }

    private Fixture fixture(Map<String, String> files, boolean productionScalarWire) {
        var store = new MemoryArtifactStore();
        var hasher = new OrdinalSourceTreeHasher();
        var mapper = new ObjectMapper();
        var entries = new ArrayList<CandidateSourceEntry>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String content = entry.getKey().equals("pom.xml") && entry.getValue().equals("<project/>")
                    ? validPom() : entry.getValue();
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            ContentHash hash = hasher.sha256(bytes);
            ArtifactReference reference = new ArtifactReference("candidate-file/" + hash.sha256());
            store.store(new Artifact(TENANT, reference, hash, "text/plain", bytes));
            entries.add(new CandidateSourceEntry(entry.getKey(), reference, hash, bytes.length));
        });
        ContentHash candidateHash = hasher.hashEntries(entries);
        var manifest = new CandidateSourceManifest(
                CandidateSourceManifest.SCHEMA_VERSION,
                CANDIDATE,
                SESSION,
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                candidateHash,
                entries.size(),
                entries.stream().mapToLong(CandidateSourceEntry::sizeBytes).sum(),
                entries);
        try {
            byte[] manifestBytes = productionScalarWire
                    ? CandidateSourceManifestTestWire.scalarBytes(mapper, manifest)
                    : mapper.writeValueAsBytes(manifest);
            ContentHash manifestHash = hasher.sha256(manifestBytes);
            ArtifactReference manifestReference = new ArtifactReference("candidate-manifest/" + manifestHash.sha256());
            store.store(new Artifact(TENANT, manifestReference, manifestHash, "application/json", manifestBytes));

            byte[] dependencyBytes = "<project/>".getBytes(StandardCharsets.UTF_8);
            ContentHash dependencyFileHash = hasher.sha256(dependencyBytes);
            ArtifactReference dependencyFileRef = new ArtifactReference(
                    "dependency-file/" + dependencyFileHash.sha256());
            store.store(new Artifact(TENANT, dependencyFileRef, dependencyFileHash, "application/xml", dependencyBytes));
            var dependencyFile = new MavenRepositoryFile(
                    "io/example/demo/1.0/demo-1.0.pom", dependencyFileRef, dependencyFileHash,
                    dependencyBytes.length);
            var dependencyLock = new MavenDependencyLock(
                    MavenDependencyLock.SCHEMA_VERSION,
                    MavenDependencyLock.REPOSITORY_ID,
                    MavenDependencyLock.REPOSITORY_URL,
                    MavenDependencyLock.GRAPH_ALGORITHM_ID,
                    List.of("io.example:demo:jar:1.0"),
                    1,
                    dependencyBytes.length,
                    List.of(dependencyFile));
            byte[] dependencyLockBytes = mapper.writeValueAsBytes(dependencyLock);
            ContentHash dependencyLockHash = hasher.sha256(dependencyLockBytes);
            ArtifactReference dependencyLockRef = new ArtifactReference(
                    "dependency-lock/" + dependencyLockHash.sha256());
            store.store(new Artifact(
                    TENANT, dependencyLockRef, dependencyLockHash, "application/json", dependencyLockBytes));

            var toolchainInstaller = new FakeToolchainInstaller(store, mapper, hasher);
            var toolchain = toolchainInstaller.descriptor();
            var fixture = new Pr4VerificationFixtureInstaller(store, mapper).descriptor();
            var request = new VerificationRequest(
                    TENANT,
                    RUN,
                    SESSION,
                    CANDIDATE,
                    manifestReference,
                    candidateHash,
                    dependencyLockRef,
                    dependencyLockHash,
                    toolchain.reference(),
                    "factory-v0.1-pr4",
                    toolchain.hash(),
                    fixture.reference(),
                    fixture.hash());
            return new Fixture(store, hasher, request, toolchainInstaller);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static VerificationRun terminalRun(VerificationRequest request,
            io.github.flowerjvm.factory.contracts.verification.VerificationResult result) {
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        return new VerificationRun(request.verificationRunId(), request.tenantId(), request.buildSessionId(),
                request.candidateId(), request.candidateHash(), request.gateProfile(), request.toolchainLockHash(),
                request.fixtureSetHash(), VerificationRunStatus.REQUESTED, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, now, now)
                .start(now).complete(result.status() == VerificationStatus.PASSED
                                ? VerificationRunStatus.PASSED : VerificationRunStatus.FAILED,
                        result.resultManifestRef(), result.resultManifestHash(), result.stableCodes().getFirst(),
                        result.disposition(), now.plusSeconds(1));
    }

    private ArtifactVerificationEvidenceValidator reviewValidator(Fixture fixture) {
        var request = fixture.request();
        var candidate = new CandidateVersion(request.candidateId(), request.tenantId(), request.buildSessionId(),
                Optional.empty(), request.candidateManifest(), request.candidateHash(), request.dependencyLockRef(),
                request.dependencyLockHash(), request.toolchainLockRef(), request.toolchainLockHash(),
                CandidateVersionStatus.GENERATED, new WorkOrderId("synthetic-wire-generation"),
                Instant.parse("2026-09-05T23:59:00Z"));
        var candidates = new CandidateVersionRepository() {
            @Override public void create(CandidateVersion value) { throw new UnsupportedOperationException(); }
            @Override public Optional<CandidateVersion> find(TenantId tenant, CandidateId id) {
                return tenant.equals(candidate.tenantId()) && id.equals(candidate.candidateId())
                        ? Optional.of(candidate) : Optional.empty();
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenant, BuildSessionId session, WorkOrderId order) { return Optional.empty(); }
        };
        return new ArtifactVerificationEvidenceValidator(fixture.store(), candidates, new ObjectMapper(),
                request.fixtureSetRef(), request.fixtureSetHash(), request.toolchainLockRef(), request.toolchainLockHash());
    }

    private static SandboxExecutionResult mavenSuccess(String output) {
        return result(0, output, Map.of("surefire/TEST-Example.xml", successfulSurefire()));
    }

    private static SandboxExecutionResult mavenFailure(String output) {
        return result(1, output, Map.of("surefire/TEST-Example.xml", failedSurefire()));
    }

    private static SandboxExecutionResult flowerSuccess(String output) {
        return result(0, output, Map.of("flower-check.sarif", sarif()));
    }

    private static SandboxExecutionResult flowerFailure(String output) {
        return result(1, output, Map.of("flower-check.sarif", sarif("FLOWER-CHECK-001")));
    }

    private static SandboxExecutionResult dependencySuccess(String output) {
        return result(0, output, Map.of("dependency-tree.txt",
                "[INFO] +- io.example:demo:jar:1.0:compile\n".getBytes(StandardCharsets.UTF_8)));
    }

    private static SandboxExecutionResult successWithoutEvidence(String output) {
        return result(0, output, Map.of());
    }

    private static SandboxExecutionResult result(int exitCode, String output, Map<String, byte[]> evidence) {
        return new SandboxExecutionResult(
                "unused", List.of("unused"), exitCode, output.getBytes(StandardCharsets.UTF_8), evidence);
    }

    private static byte[] successfulSurefire() {
        return "<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"/>"
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] failedSurefire() {
        return "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"/>"
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sarif(String... rules) {
        String results = java.util.Arrays.stream(rules)
                .map(rule -> "{\"ruleId\":\"" + rule + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return ("{\"version\":\"2.1.0\",\"runs\":[{\"results\":[" + results + "]}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String validPom() {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>io.example</groupId>
                  <artifactId>candidate</artifactId><version>1.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-clean-plugin</artifactId><version>3.4.1</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                  </plugins></build>
                </project>
                """;
    }

    private static void assertStoredVerificationArtifactsDoNotContain(MemoryArtifactStore store, String secret) {
        for (Artifact artifact : store.values.values()) {
            if (artifact.reference().value().startsWith("verification")) {
                assertFalse(artifact.reference().value().contains(secret));
                assertFalse(new String(artifact.content(), StandardCharsets.UTF_8).contains(secret));
            }
        }
    }

    private record Fixture(
            MemoryArtifactStore store,
            OrdinalSourceTreeHasher hasher,
            VerificationRequest request,
            TrustedToolchainInstaller toolchainInstaller) {
        IndependentMavenVerifier verifier(VerificationSandbox sandbox) {
            return new IndependentMavenVerifier(
                    store,
                    new ObjectMapper(),
                    sandbox,
                    Path.of(System.getProperty("java.io.tmpdir"), "factory-test-" + System.nanoTime()),
                    new OrdinalSourceTreeHasher(),
                    CandidateWorkspaceMaterializer.DEFAULT_LIMITS,
                    new SecretScanner(),
                    toolchainInstaller);
        }

        VerificationResultManifest readResult(ArtifactReference reference) {
            try {
                return new ObjectMapper().readValue(store.required(reference).content(), VerificationResultManifest.class);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class ScriptedSandbox implements VerificationSandbox {
        private final Map<VerificationCommand, SandboxExecutionResult> results;
        private final List<VerificationCommand> invocations = new ArrayList<>();
        private boolean writeUnexpected;
        private boolean mutateSource;
        private boolean canonicalReviewEvidenceShape;

        private ScriptedSandbox(Map<VerificationCommand, SandboxExecutionResult> results) {
            this.results = new LinkedHashMap<>(results);
        }

        @Override
        public SandboxExecutionResult execute(Path workspace, VerificationCommand command) {
            invocations.add(command);
            if (command == VerificationCommand.FLOWER_CHECK
                    && workspace.getFileName().toString().equals("fixture-workspace")) {
                return new SandboxExecutionResult(
                        command.commandId(), command.arguments(), 1,
                        "seeded defect detected".getBytes(StandardCharsets.UTF_8),
                        Map.of("flower-check.sarif", sarif("FLOWER-CHECK-001")));
            }
            try {
                if (writeUnexpected && command == VerificationCommand.MAVEN_VERIFY) {
                    Files.writeString(workspace.resolve("outside.txt"), "forbidden");
                }
                if (mutateSource && command == VerificationCommand.MAVEN_VERIFY) {
                    Files.writeString(workspace.resolve("pom.xml"), "mutated");
                }
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            SandboxExecutionResult scripted = results.get(command);
            assertNotNull(scripted, "missing scripted result for " + command);
            return new SandboxExecutionResult(
                    command.commandId(), command.arguments(), scripted.exitCode(), scripted.output(),
                    scripted.evidenceFiles());
        }

        @Override
        public SandboxExecutionResult execute(
                Path workspace, Path curatedMavenRepository, VerificationCommand command) {
            return execute(workspace, command);
        }

        @Override
        public VerificationSandboxEvidence evidence() {
            if (canonicalReviewEvidenceShape) {
                // Explicit synthetic wire fixture for readback integration, not native Docker evidence.
                return new VerificationSandboxEvidence("docker-cli", DockerCliVerificationSandbox.IMAGE,
                        "factory.verifier-sandbox.v1", true, true, true, 1_073_741_824L, 2.0, 256,
                        DockerCliVerificationSandbox.DEFAULT_TIMEOUT.toMillis());
            }
            return new VerificationSandboxEvidence(
                    "deterministic-fake",
                    "sha256:" + "f".repeat(64),
                    "factory.test-sandbox.v1",
                    true,
                    true,
                    true,
                    1_048_576,
                    1.0,
                    8,
                    10_000);
        }
    }

    private static final class FakeToolchainInstaller implements TrustedToolchainInstaller {
        private final MemoryArtifactStore store;
        private final Artifact toolchainFile;
        private final Artifact lockArtifact;
        private final InstalledToolchain descriptor;

        private FakeToolchainInstaller(
                MemoryArtifactStore store, ObjectMapper mapper, OrdinalSourceTreeHasher hasher) throws IOException {
            this.store = store;
            byte[] fileBytes = "<project/>".getBytes(StandardCharsets.UTF_8);
            ContentHash fileHash = hasher.sha256(fileBytes);
            ArtifactReference fileRef = new ArtifactReference("trusted-toolchain-file/" + fileHash.sha256());
            this.toolchainFile = new Artifact(TENANT, fileRef, fileHash, "application/xml", fileBytes);
            var file = new MavenRepositoryFile(
                    "org/apache/maven/maven-model/3.9.11/maven-model-3.9.11.pom",
                    fileRef, fileHash, fileBytes.length);
            var lock = new MavenToolchainLock(
                    MavenToolchainLock.SCHEMA_VERSION,
                    MavenToolchainLock.REPOSITORY_ID,
                    MavenToolchainLock.REPOSITORY_URL,
                    Pr4MavenToolchainInstaller.ALLOWED_BUILD_PLUGINS,
                    1,
                    fileBytes.length,
                    List.of(file));
            byte[] lockBytes = mapper.writeValueAsBytes(lock);
            ContentHash lockHash = hasher.sha256(lockBytes);
            ArtifactReference lockRef = new ArtifactReference("trusted-toolchain-lock/" + lockHash.sha256());
            this.lockArtifact = new Artifact(TENANT, lockRef, lockHash, "application/json", lockBytes);
            this.descriptor = new InstalledToolchain(lockRef, lockHash);
        }

        @Override
        public InstalledToolchain descriptor() {
            return descriptor;
        }

        @Override
        public void install(TenantId tenantId) {
            if (!TENANT.equals(tenantId)) throw new AssertionError("unexpected tenant");
            store.store(toolchainFile);
            store.store(lockArtifact);
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<ArtifactReference, Artifact> values = new HashMap<>();

        @Override
        public ArtifactReference store(Artifact artifact) {
            Artifact previous = values.putIfAbsent(artifact.reference(), artifact);
            if (previous != null) {
                assertArrayEquals(previous.content(), artifact.content());
            }
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference)).filter(value -> value.tenantId().equals(tenantId));
        }

        Artifact required(ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference)).orElseThrow();
        }

        void replace(ArtifactReference reference, byte[] bytes, OrdinalSourceTreeHasher hasher) {
            Artifact previous = required(reference);
            values.put(reference, new Artifact(previous.tenantId(), reference, hasher.sha256(bytes), previous.mediaType(), bytes));
        }
    }
}
