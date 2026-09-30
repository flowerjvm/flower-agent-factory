package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.candidate.*;
import io.github.flowerjvm.factory.application.verification.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.verification.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ArtifactAgentPackProductionRepairEvidenceReaderTest {
    static final TenantId TENANT = new TenantId("repair-reader");
    static final BuildSessionId SESSION = new BuildSessionId("repair-reader-session");
    static final CandidateId CANDIDATE = new CandidateId("repair-reader-candidate");
    static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    static final OrdinalSourceTreeHasher HASHER = new OrdinalSourceTreeHasher();

    @Test
    void readsOnlyCanonicalMetadataAndReturnsBoundedExactFailureDiagnosticsWithoutSourceFileReads() throws Exception {
        var f = new Fixture();
        var evidence = f.reader().readFailure(f.candidate, f.run);
        assertEquals(VerificationStatus.FAILED, evidence.manifest().status());
        assertEquals(VerificationDisposition.REPAIR_REQUIRED, evidence.manifest().disposition());
        assertEquals(3, evidence.diagnostics().size());
        assertEquals(4095, evidence.diagnostics().getFirst().snippet().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(f.manifest.commands().getFirst().rawLogHash(), evidence.diagnostics().getFirst().hash());
        assertEquals(0, f.sourceFileReads);
        assertEquals(f.storeCallsAtReady, f.storeCalls, "read gate does not stage artifacts");
        assertEquals(f.sourceArtifact.contentHash(), f.reader().validateSource(f.candidate));
        assertEquals(0, f.sourceFileReads);
    }

    @Test
    void scalarProductionManifestSupportsExactRepairEvidenceWithoutReadingSourceFiles() throws Exception {
        var f = new Fixture();
        var parsed = f.mapper.readValue(f.sourceArtifact.content(), CandidateSourceManifest.class);
        Artifact scalar = f.put(f.candidate.sourceManifestRef(), "application/json",
                CandidateSourceManifestTestWire.scalarBytes(f.mapper, parsed));
        int storesBeforeRead = f.storeCalls;

        assertEquals(scalar.contentHash(), f.reader().validateSource(f.candidate));
        var evidence = f.reader().readFailure(f.candidate, f.run);
        assertEquals(VerificationStatus.FAILED, evidence.manifest().status());
        assertEquals(VerificationDisposition.REPAIR_REQUIRED, evidence.manifest().disposition());
        assertEquals(3, evidence.diagnostics().size());
        assertEquals(0, f.sourceFileReads);
        assertEquals(storesBeforeRead, f.storeCalls);
    }

    @Test
    void mixedRecordAndScalarSourceManifestCannotBecomeRepairEvidenceEvenWhenRehashed() throws Exception {
        var f = new Fixture();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) f.mapper.readTree(f.sourceArtifact.content());
        root.put("candidateHash", f.candidate.sourceHash().sha256());
        f.put(f.candidate.sourceManifestRef(), "application/json", f.mapper.writeValueAsBytes(root));
        assertThrows(IllegalArgumentException.class, () -> f.reader().validateSource(f.candidate));
        assertThrows(IllegalArgumentException.class, () -> f.reader().readFailure(f.candidate, f.run));
        assertEquals(0, f.sourceFileReads);
    }

    @Test
    void failedContextCannotBecomePassedReviewEvidence() throws Exception {
        var f = new Fixture();
        f.run = copy(f.run, "status", VerificationRunStatus.PASSED,
                "disposition", Optional.of(VerificationDisposition.REVIEW_ELIGIBLE));
        assertThrows(IllegalArgumentException.class, () -> f.reader().readFailure(f.candidate, f.run));
    }

    @Test
    void sourceManifestAndDiagnosticReadBoundsFailClosed() throws Exception {
        var f = new Fixture();
        byte[] original = f.sourceArtifact.content();
        byte[] oversized = Arrays.copyOf(original, 256 * 1024 + 1);
        Arrays.fill(oversized, original.length, oversized.length, (byte) ' ');
        f.put(f.candidate.sourceManifestRef(), "application/json", oversized);
        assertThrows(IllegalArgumentException.class, () -> f.reader().validateSource(f.candidate));
        assertEquals(0, f.sourceFileReads);
        var g = new Fixture();
        var command = g.manifest.commands().getFirst();
        byte[] oversizedLog = new byte[DockerCliVerificationSandbox.DEFAULT_MAX_LOG_BYTES + 65];
        g.put(command.rawLogRef(), "text/plain; charset=utf-8", oversizedLog);
        var commands = new ArrayList<>(g.manifest.commands());
        commands.set(0, copy(command, "rawLogHash", HASHER.sha256(oversizedLog)));
        g.manifest = copy(g.manifest, "commands", commands);
        g.restage();
        assertThrows(IllegalArgumentException.class, () -> g.reader().readFailure(g.candidate, g.run));
    }

    @Test
    void duplicateAndTrailingResultJsonAreRejectedEvenWithExactUpdatedHash() throws Exception {
        var f = new Fixture();
        String json = new String(f.artifacts.get(f.run.resultManifestRef().orElseThrow()).content(), StandardCharsets.UTF_8);
        byte[] duplicate = ("{\"schemaVersion\":\"" + VerificationResultManifest.SCHEMA_VERSION + "\"," + json.substring(1))
                .getBytes(StandardCharsets.UTF_8);
        f.replaceResult(duplicate);
        assertThrows(IllegalArgumentException.class, () -> f.reader().readFailure(f.candidate, f.run));
        var g = new Fixture();
        byte[] trailing = (new String(g.artifacts.get(g.run.resultManifestRef().orElseThrow()).content(), StandardCharsets.UTF_8) + "{}")
                .getBytes(StandardCharsets.UTF_8);
        g.replaceResult(trailing);
        assertThrows(IllegalArgumentException.class, () -> g.reader().readFailure(g.candidate, g.run));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvidence")
    void rejectsConsistentlyRehashedButWrongEvidence(String label, Consumer<Fixture> mutation) throws Exception {
        var f = new Fixture();
        mutation.accept(f);
        f.restage();
        assertThrows(IllegalArgumentException.class, () -> f.reader().readFailure(f.candidate, f.run), label);
    }

    static Stream<Arguments> invalidEvidence() {
        Map<String, Consumer<Fixture>> cases = new LinkedHashMap<>();
        cases.put("wrong candidate", f -> f.manifest = copy(f.manifest, "candidateId", new CandidateId("other")));
        cases.put("wrong profile", f -> f.manifest = copy(f.manifest, "gateProfile", "other"));
        cases.put("different source artifact", f -> f.manifest = copy(f.manifest, "candidateManifestRef", ref("other")));
        cases.put("mutated source after verifier", f -> f.manifest = copy(f.manifest, "sourceHashAfter", hash("changed")));
        cases.put("different dependency lock", f -> f.manifest = copy(f.manifest, "dependencyLockHash", hash("other")));
        cases.put("different toolchain", f -> f.manifest = copy(f.manifest, "toolchainLockRef", ref("other")));
        cases.put("different fixture", f -> f.manifest = copy(f.manifest, "fixtureSetHash", hash("other")));
        cases.put("infrastructure code disguised repairable", f -> f.manifest = copy(f.manifest, "stableCodes", List.of(VerificationStableCodes.SANDBOX_TIMEOUT)));
        cases.put("blocked disposition", f -> f.manifest = copy(f.manifest, "disposition", VerificationDisposition.BLOCKED));
        cases.put("missing self test", f -> f.manifest = copy(f.manifest, "fixtureSelfTest", null));
        cases.put("missing command", f -> f.manifest = copy(f.manifest, "commands", f.manifest.commands().subList(0, 2)));
        cases.put("reordered evidence", f -> { var list = new ArrayList<>(f.manifest.evidenceArtifacts()); Collections.swap(list, 1, 2); f.manifest = copy(f.manifest, "evidenceArtifacts", list); });
        cases.put("log bytes corrupt", f -> { var command = f.manifest.commands().getFirst(); var old = f.artifacts.get(command.rawLogRef()); f.artifacts.put(old.reference(), new Artifact(TENANT, old.reference(), old.contentHash(), old.mediaType(), new byte[] {1})); });
        cases.put("all commands passed contradicts failure", f -> { var commands = new ArrayList<>(f.manifest.commands()); commands.set(0, copy(commands.getFirst(), "exitCode", 0)); f.manifest = copy(f.manifest, "commands", commands); });
        cases.put("foreign log artifact", f -> { var old = f.artifacts.get(f.manifest.commands().getFirst().rawLogRef()); f.artifacts.put(old.reference(), new Artifact(new TenantId("other"), old.reference(), old.contentHash(), old.mediaType(), old.content())); });
        return cases.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    private static final class Fixture {
        final Map<ArtifactReference, Artifact> artifacts = new HashMap<>();
        final ObjectMapper mapper = new ObjectMapper();
        int sourceFileReads;
        int storeCalls;
        int storeCallsAtReady;
        final ArtifactStore store = new ArtifactStore() {
            public ArtifactReference store(Artifact artifact) { storeCalls++; artifacts.put(artifact.reference(), artifact); return artifact.reference(); }
            public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
                if (reference.equals(ref("source-file"))) { sourceFileReads++; throw new AssertionError("source byte read on preparation tick"); }
                return Optional.ofNullable(artifacts.get(reference));
            }
        };
        final Artifact sourceArtifact;
        final Artifact fixtureArtifact;
        final Artifact toolchain;
        CandidateVersion candidate;
        VerificationRun run;
        VerificationResultManifest manifest;

        Fixture() throws Exception {
            Artifact dependency = put(ref("dependency"), "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            toolchain = put(ref("toolchain"), "application/json", "{\"tool\":1}".getBytes(StandardCharsets.UTF_8));
            var entry = new CandidateSourceEntry("src/main/java/Agent.java", ref("source-file"), hash("source bytes"), 12);
            var sourceHash = HASHER.hashEntries(List.of(entry));
            sourceArtifact = put(ref("source"), "application/json", mapper.writeValueAsBytes(new CandidateSourceManifest(
                    CandidateSourceManifest.SCHEMA_VERSION, CANDIDATE, SESSION, CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                    sourceHash, 1, 12, List.of(entry))));
            candidate = new CandidateVersion(CANDIDATE, TENANT, SESSION, Optional.empty(), sourceArtifact.reference(), sourceHash,
                    dependency.reference(), dependency.contentHash(), toolchain.reference(), toolchain.contentHash(), CandidateVersionStatus.GENERATED,
                    new WorkOrderId("generation"), NOW.minusSeconds(30));
            var fixture = new VerificationFixture("negative", "src/Negative.java", ref("fixture-file"), hash("fixture bytes"), 1, "FLOWER-001");
            fixtureArtifact = put(ref("fixtures"), "application/json", mapper.writeValueAsBytes(new VerificationFixtureSet(
                    VerificationFixtureSet.SCHEMA_VERSION, List.of(fixture))));
            Artifact self = put(ref("self-test"), "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            var selfEvidence = new VerificationFixtureSelfTestEvidence(List.of("negative"), List.of("FLOWER-001"), List.of("FLOWER-001"),
                    1, self.reference(), self.contentHash());
            var commands = new ArrayList<VerificationCommandEvidence>();
            var evidenceRefs = new ArrayList<ArtifactReference>();
            evidenceRefs.add(self.reference());
            for (var command : VerificationProfiles.technicalCommands()) {
                var log = put(ref("log-" + command.commandId()), "text/plain; charset=utf-8", "한".repeat(5000).getBytes(StandardCharsets.UTF_8));
                var machine = put(ref("machine-" + command.commandId()), "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                commands.add(new VerificationCommandEvidence(command.commandId(), command.arguments(), command == VerificationCommand.MAVEN_VERIFY ? 1 : 0,
                        0, 0, log.reference(), log.contentHash(), machine.reference(), machine.contentHash()));
                evidenceRefs.add(log.reference()); evidenceRefs.add(machine.reference());
            }
            run = new VerificationRun(new VerificationRunId("repair-reader-run"), TENANT, SESSION, CANDIDATE, candidate.sourceHash(),
                    ActionBackedVerificationRunLauncher.GATE_PROFILE, candidate.toolchainLockHash(), fixtureArtifact.contentHash(),
                    VerificationRunStatus.FAILED, Optional.of(ref("result")), Optional.of(hash("placeholder")),
                    Optional.of(VerificationStableCodes.MAVEN_VERIFICATION_FAILED), Optional.of(VerificationDisposition.REPAIR_REQUIRED),
                    Optional.of(NOW.minusSeconds(20)), Optional.of(NOW.minusSeconds(10)), 2, NOW.minusSeconds(20), NOW.minusSeconds(10));
            manifest = new VerificationResultManifest(VerificationResultManifest.SCHEMA_VERSION, run.verificationRunId(), SESSION, CANDIDATE,
                    candidate.sourceManifestRef(), candidate.sourceHash(), candidate.dependencyLockRef(), candidate.dependencyLockHash(),
                    candidate.sourceHash(), candidate.sourceHash(), run.gateProfile(), toolchain.reference(), toolchain.contentHash(),
                    fixtureArtifact.reference(), fixtureArtifact.contentHash(), selfEvidence, ArtifactAgentPackProductionRepairEvidenceReader.expectedSandbox(),
                    VerificationStatus.FAILED, VerificationDisposition.REPAIR_REQUIRED, List.of(VerificationStableCodes.MAVEN_VERIFICATION_FAILED),
                    commands, evidenceRefs);
            restage();
            storeCallsAtReady = storeCalls;
        }

        ArtifactAgentPackProductionRepairEvidenceReader reader() {
            return new ArtifactAgentPackProductionRepairEvidenceReader(store, mapper, fixtureArtifact.reference(), fixtureArtifact.contentHash(),
                    toolchain.reference(), toolchain.contentHash());
        }
        void restage() throws Exception { replaceResult(mapper.writeValueAsBytes(manifest)); }
        void replaceResult(byte[] bytes) {
            Artifact result = put(run.resultManifestRef().orElseThrow(), "application/json", bytes);
            run = copy(run, "resultManifestHash", Optional.of(result.contentHash()));
        }
        Artifact put(ArtifactReference ref, String media, byte[] bytes) {
            Artifact artifact = new Artifact(TENANT, ref, HASHER.sha256(bytes), media, bytes); store.store(artifact); return artifact;
        }
    }

    static ArtifactReference ref(String value) { return new ArtifactReference("artifact:repair-reader-test:" + value); }
    static ContentHash hash(String value) { return HASHER.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    @SuppressWarnings("unchecked")
    static <T extends Record> T copy(T original, Object... replacements) {
        try {
            var components = original.getClass().getRecordComponents(); var types = new Class<?>[components.length]; var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType(); values[i] = components[i].getAccessor().invoke(original);
                for (int j = 0; j < replacements.length; j += 2) if (components[i].getName().equals(replacements[j])) values[i] = replacements[j + 1];
            }
            return (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
