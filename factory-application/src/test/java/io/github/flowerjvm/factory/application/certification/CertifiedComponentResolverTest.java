package io.github.flowerjvm.factory.application.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CertifiedComponentResolverTest {
    @Test
    void resolvesOnlyFromExactTenantScopedStoredArtifacts() {
        Fixture fixture = new Fixture(false);

        CertifiedAgentComponentReadGate readGate = fixture.resolver();
        ResolvedCertifiedAgentComponent resolved = readGate.resolve(fixture.tenant, fixture.reference);

        assertEquals(fixture.inputLock, resolved.inputLock());
        assertEquals(fixture.compatibility, resolved.compatibilityDescriptor());
        assertTrue(fixture.artifacts.readCount.get() >= 12);
    }

    @Test
    void failsClosedForCrossTenantAndExactEvidenceOrCompatibilityHashMismatch() {
        Fixture fixture = new Fixture(false);
        assertCode(CertifiedComponentResolver.CERTIFICATION_NOT_FOUND,
                () -> fixture.resolver().resolve(new TenantId("tenant-b"), fixture.reference));

        CertifiedAgentComponentRef wrongEvidence = fixture.reference(
                fixture.reference.compatibilityDescriptor(), fixture.lock("wrong-evidence", "wrong-evidence", "application/json"));
        assertCode(CertifiedComponentResolver.LOCK_MISMATCH,
                () -> fixture.resolver().resolve(fixture.tenant, wrongEvidence));

        CertifiedAgentComponentRef wrongCompatibility = fixture.reference(
                fixture.lock("wrong-compatibility", "wrong-compatibility", "application/json"),
                fixture.reference.certificationEvidence());
        assertCode(CertifiedComponentResolver.LOCK_MISMATCH,
                () -> fixture.resolver().resolve(fixture.tenant, wrongCompatibility));
    }

    @Test
    void rejectsFailedAndLegacyVerificationBeforeItCanAuthorizeConsumption() {
        Fixture failed = new Fixture(false);
        failed.verification = failed.verification(
                VerificationRunStatus.FAILED,
                failed.inputLock.verificationResultManifest().hash(),
                VerificationDisposition.REPAIR_REQUIRED);
        assertCode(CertifiedComponentResolver.VERIFICATION_NOT_ELIGIBLE,
                () -> failed.resolver().resolve(failed.tenant, failed.reference));

        Fixture legacy = new Fixture(true);
        assertCode(CertifiedComponentResolver.VERIFICATION_LEGACY_EVIDENCE,
                () -> legacy.resolver().resolve(legacy.tenant, legacy.reference));
    }

    @Test
    void rejectsActionUnboundOrDifferentVerificationOwner() {
        Fixture fixture = new Fixture(false);
        fixture.owner = VerificationActionEvidenceOwner.Assessment.pending();
        assertCode(CertifiedComponentResolver.VERIFICATION_UNBOUND,
                () -> fixture.resolver().resolve(fixture.tenant, fixture.reference));

        Fixture wrongAction = new Fixture(false);
        wrongAction.intent = wrongAction.completedIntent("different-action-run");
        assertCode(CertifiedComponentResolver.VERIFICATION_UNBOUND,
                () -> wrongAction.resolver().resolve(wrongAction.tenant, wrongAction.reference));
    }

    @Test
    void rejectsCertifiedLedgerWithoutCanonicalIssuanceActionOwner() {
        Fixture fixture = new Fixture(false);
        fixture.certificationOwner = CertificationActionEvidenceOwner.Assessment.pending();

        assertCode(CertifiedComponentResolver.CERTIFICATION_UNBOUND,
                () -> fixture.resolver().resolve(fixture.tenant, fixture.reference));
    }

    @Test
    void rejectsGenerationInputThatDoesNotBindProductContractAndGateProfile() {
        Fixture fixture = new Fixture(false);
        CodingWorkerInputManifest original = fixture.generationInput;
        fixture.generationInput = new CodingWorkerInputManifest(
                CodingWorkerInputManifest.SCHEMA_VERSION,
                original.workOrderId(),
                original.buildSessionId(),
                original.skillId(),
                original.skillVersion(),
                original.skillArtifactRef(),
                original.skillHash(),
                original.dependencyLockRef(),
                original.dependencyLockHash(),
                original.toolchainLockRef(),
                original.toolchainLockHash(),
                original.apiSignatureIndexRef(),
                original.apiSignatureIndexHash(),
                original.productContractBundleRef(),
                original.productContractBundleHash(),
                "strict",
                original.requirementTestMatrixRef(),
                original.requirementTestMatrixHash(),
                original.sourceLockAlgorithmId(),
                original.repairLock());

        assertCode(CertifiedComponentResolver.LOCK_MISMATCH,
                () -> fixture.resolver().resolve(fixture.tenant, fixture.reference));
    }

    @Test
    void validatesRepositoryTenantIdentityBeforeCallingEvidenceOrOwnerReaders() {
        Fixture fixture = new Fixture(false);
        VerificationRun original = fixture.verification;
        fixture.verification = new VerificationRun(
                original.verificationRunId(),
                new TenantId("tenant-b"),
                original.buildSessionId(),
                original.candidateId(),
                original.candidateHash(),
                original.gateProfile(),
                original.toolchainLockHash(),
                original.fixtureSetHash(),
                original.status(),
                original.resultManifestRef(),
                original.resultManifestHash(),
                original.terminalCode(),
                original.disposition(),
                original.startedAt(),
                original.completedAt(),
                original.version(),
                original.createdAt(),
                original.updatedAt());

        assertCode(CertifiedComponentResolver.VERIFICATION_NOT_ELIGIBLE,
                () -> fixture.resolver().resolve(fixture.tenant, fixture.reference));
        assertEquals(0, fixture.evidenceReads.get());
        assertEquals(0, fixture.ownerReads.get());
    }

    @Test
    void requiresApplicationJsonForCanonicalCertificationArtifacts() {
        Fixture fixture = new Fixture(false);
        fixture.artifacts.replaceMediaType(fixture.tenant, fixture.certification.inputLockArtifact().reference(),
                "application/octet-stream");

        assertCode(CertifiedComponentResolver.ARTIFACT_INVALID,
                () -> fixture.resolver().resolve(fixture.tenant, fixture.reference));
    }

    private static void assertCode(String expected, Runnable work) {
        CertifiedComponentResolutionException failure =
                assertThrows(CertifiedComponentResolutionException.class, work::run);
        assertEquals(expected, failure.code());
    }

    private static final class Fixture {
        private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
        private final TenantId tenant = new TenantId("tenant-a");
        private final BuildSessionId buildSessionId = new BuildSessionId("build-001");
        private final WorkOrderId workOrderId = new WorkOrderId("work-order-001");
        private final CandidateId candidateId = new CandidateId("candidate-001");
        private final VerificationRunId verificationId = new VerificationRunId("verification-001");
        private final CertificationId certificationId = new CertificationId("certification-001");
        private final MemoryArtifactStore artifacts = new MemoryArtifactStore();
        private final AtomicInteger evidenceReads = new AtomicInteger();
        private final AtomicInteger ownerReads = new AtomicInteger();

        private final BuildSession buildSession;
        private final WorkOrder workOrder;
        private final CandidateVersion candidate;
        private VerificationRun verification;
        private VerificationDispatchIntent intent;
        private CodingWorkerInputManifest generationInput;
        private final CertificationInputLock inputLock;
        private final CertificationEvidenceManifest evidence;
        private final AgentPackCompatibilityDescriptor compatibility;
        private final CertifiedAgentComponentManifest manifest;
        private final Certification certification;
        private final CertifiedAgentComponentRef reference;
        private VerificationActionEvidenceOwner.Assessment owner =
                VerificationActionEvidenceOwner.Assessment.canonical();
        private CertificationActionEvidenceOwner.Assessment certificationOwner =
                CertificationActionEvidenceOwner.Assessment.canonical();

        private Fixture(boolean legacy) {
            CertificationArtifactLock source = lock("source", "source", "application/octet-stream");
            CertificationArtifactLock dependency = lock("dependency", "dependency", "application/octet-stream");
            CertificationArtifactLock toolchain = lock("toolchain", "toolchain", "application/octet-stream");
            CertificationArtifactLock generation = lock("generation", "generation", "application/json");
            CertificationArtifactLock product = lock("product", "product", "application/octet-stream");
            CertificationArtifactLock api = lock("api", "api", "application/octet-stream");
            CertificationArtifactLock policy = lock("policy", "policy", "application/octet-stream");
            CertificationArtifactLock verificationResult = legacy
                    ? new CertificationArtifactLock(
                            new ArtifactReference("artifact:verification-result"),
                            VerificationRun.LEGACY_RESULT_MANIFEST_HASH)
                    : lock("verification-result", "verification-result", "application/json");
            ContentHash fixtureHash = sha256("fixtures");

            buildSession = new BuildSession(
                    buildSessionId,
                    tenant,
                    new ProjectId("project-001"),
                    ProductLineId.AGENT_PACK,
                    "request-001",
                    "principal-a",
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.EVALUATE,
                    new ArtifactReference("artifact:requirements"),
                    sha256("requirements"),
                    Optional.of("manager"),
                    Optional.of("coding"),
                    Optional.empty(),
                    Optional.of(candidateId),
                    Optional.of(source.hash()),
                    Optional.empty(),
                    0,
                    3,
                    NOW,
                    NOW.plusSeconds(3600),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    0, NOW, NOW);
            workOrder = new WorkOrder(
                    workOrderId,
                    tenant,
                    buildSessionId,
                    "generate-candidate",
                    "Generate locked candidate",
                    1,
                    Optional.empty(), Optional.empty(), Optional.of("base"),
                    new ArtifactReference("artifact:instruction"),
                    sha256("instruction"),
                    generation.reference(),
                    generation.hash(),
                    "workspace:001",
                    List.of("/input"),
                    List.of("/output"),
                    Set.of(),
                    "agent-pack-candidate",
                    "1",
                    policy.reference(),
                    NOW.plusSeconds(1800),
                    1,
                    "logical-001",
                    WorkOrderCreatorType.SYSTEM,
                    "factory",
                    NOW);
            candidate = new CandidateVersion(
                    candidateId, tenant, buildSessionId, Optional.empty(),
                    source.reference(), source.hash(),
                    dependency.reference(), dependency.hash(),
                    toolchain.reference(), toolchain.hash(),
                    CandidateVersionStatus.GENERATED, workOrderId, NOW);
            verification = verification(
                    VerificationRunStatus.PASSED,
                    verificationResult.hash(),
                    VerificationDisposition.REVIEW_ELIGIBLE);
            intent = completedIntent("verification-action-001");

            generationInput = new CodingWorkerInputManifest(
                    CodingWorkerInputManifest.SCHEMA_VERSION,
                    workOrderId,
                    buildSessionId,
                    "agent-pack-generator",
                    "1.0.0",
                    new ArtifactReference("artifact:skill"),
                    sha256("skill"),
                    dependency.reference(), dependency.hash(),
                    toolchain.reference(), toolchain.hash(),
                    api.reference(), api.hash(),
                    product.reference(), product.hash(),
                    "internal",
                    new ArtifactReference("artifact:requirement-matrix"),
                    sha256("requirement-matrix"),
                    "sha256-ordinal-v1",
                    Optional.empty());

            compatibility = new AgentPackCompatibilityDescriptor(
                    AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    candidateId,
                    source.hash(),
                    product,
                    api,
                    dependency,
                    toolchain,
                    "internal",
                    "sha256-ordinal-v1",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertificationArtifactLock compatibilityLock = lock(
                    "compatibility", "compatibility", CertificationArtifactCodec.MEDIA_TYPE);
            inputLock = new CertificationInputLock(
                    CertificationInputLock.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    buildSessionId,
                    workOrderId,
                    candidateId,
                    source.hash(),
                    source,
                    dependency,
                    toolchain,
                    generation,
                    product,
                    api,
                    "sha256-ordinal-v1",
                    "internal",
                    verificationId,
                    "verification-action-001",
                    verificationResult,
                    fixtureHash,
                    policy,
                    compatibilityLock,
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertificationArtifactLock inputArtifact = lock("input", "input", CertificationArtifactCodec.MEDIA_TYPE);
            evidence = new CertificationEvidenceManifest(
                    CertificationEvidenceManifest.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    candidateId,
                    source.hash(),
                    inputArtifact.hash(),
                    verificationId,
                    "verification-action-001",
                    verificationResult.hash(),
                    compatibilityLock.hash(),
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertificationArtifactLock evidenceLock = lock(
                    "evidence", "evidence", CertificationArtifactCodec.MEDIA_TYPE);
            manifest = new CertifiedAgentComponentManifest(
                    CertifiedAgentComponentManifest.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    candidateId,
                    source.hash(),
                    source,
                    inputArtifact,
                    verificationId,
                    verificationResult,
                    compatibilityLock,
                    evidenceLock,
                    "internal",
                    "0.2.0",
                    NOW,
                    NOW.plusSeconds(3600),
                    CertifiedAgentComponentManifest.CERTIFIED_STATUS);
            CertificationArtifactLock manifestLock = lock(
                    "manifest", "manifest", CertificationArtifactCodec.MEDIA_TYPE);
            certification = Certification.requested(certificationId, inputLock, inputArtifact, NOW)
                    .certify(manifestLock, evidenceLock, "certification-action-001", NOW,
                            Optional.of(NOW.plusSeconds(3600)));
            reference = new CertifiedAgentComponentRef(
                    CertifiedAgentComponentRef.SCHEMA_VERSION,
                    "embedded-agent-pack",
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    manifestLock,
                    candidateId,
                    source.hash(),
                    source,
                    inputArtifact,
                    verificationId,
                    verificationResult,
                    compatibilityLock,
                    evidenceLock,
                    "internal");
        }

        private CertifiedComponentResolver resolver() {
            CertificationRepository certificationRepository = new CertificationRepository() {
                @Override public void create(Certification ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<Certification> find(TenantId requestedTenant, CertificationId requestedId) {
                    return requestedTenant.equals(tenant) && requestedId.equals(certificationId)
                            ? Optional.of(certification) : Optional.empty();
                }
                @Override public boolean compareAndSet(Certification expected, Certification next) {
                    throw new UnsupportedOperationException();
                }
            };
            BuildSessionRepository buildSessions = new BuildSessionRepository() {
                @Override public void create(BuildSession ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<BuildSession> find(TenantId ignored, BuildSessionId id) {
                    return id.equals(buildSessionId) ? Optional.of(buildSession) : Optional.empty();
                }
                @Override public boolean compareAndSet(BuildSession expected, BuildSession next) {
                    throw new UnsupportedOperationException();
                }
            };
            WorkOrderRepository workOrders = new WorkOrderRepository() {
                @Override public void create(WorkOrder ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<WorkOrder> find(TenantId ignored, WorkOrderId id) {
                    return id.equals(workOrderId) ? Optional.of(workOrder) : Optional.empty();
                }
            };
            CandidateVersionRepository candidates = new CandidateVersionRepository() {
                @Override public void create(CandidateVersion ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<CandidateVersion> find(TenantId ignored, CandidateId id) {
                    return id.equals(candidateId) ? Optional.of(candidate) : Optional.empty();
                }
                @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                        TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
                    throw new UnsupportedOperationException();
                }
            };
            VerificationRunRepository verifications = new VerificationRunRepository() {
                @Override public void create(VerificationRun ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<VerificationRun> find(TenantId ignored, VerificationRunId id) {
                    return id.equals(verificationId) ? Optional.of(verification) : Optional.empty();
                }
                @Override public Optional<VerificationRun> findLatestForCandidate(
                        TenantId tenantId, BuildSessionId buildSessionId, CandidateId candidateId,
                        ContentHash candidateHash, String gateProfile) {
                    throw new UnsupportedOperationException();
                }
                @Override public boolean compareAndSet(VerificationRun expected, VerificationRun next) {
                    throw new UnsupportedOperationException();
                }
            };
            VerificationDispatchIntentRepository intents = new VerificationDispatchIntentRepository() {
                @Override public void create(VerificationDispatchIntent ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<VerificationDispatchIntent> find(String operationId) {
                    throw new UnsupportedOperationException();
                }
                @Override public Optional<VerificationDispatchIntent> findLatest(
                        TenantId ignored, VerificationRunId id) {
                    return id.equals(verificationId) ? Optional.of(intent) : Optional.empty();
                }
                @Override public Optional<VerificationDispatchIntent> claimNext(
                        Instant now, Duration lease, String claimToken) { throw new UnsupportedOperationException(); }
                @Override public Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
                        Instant now, Duration lease, String claimToken) { throw new UnsupportedOperationException(); }
                @Override public boolean compareAndSet(
                        VerificationDispatchIntent expected, VerificationDispatchIntent next) {
                    throw new UnsupportedOperationException();
                }
            };
            WorkerProtocolArtifactDecoder workerDecoder = new WorkerProtocolArtifactDecoder() {
                @Override public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) {
                    throw new UnsupportedOperationException();
                }
                @Override public CodingWorkerInputManifest decodeInputManifest(byte[] content) {
                    if (!"generation".equals(new String(content, StandardCharsets.UTF_8))) {
                        throw new IllegalArgumentException("unexpected generation input bytes");
                    }
                    return generationInput;
                }
                @Override public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
                    throw new UnsupportedOperationException();
                }
            };
            CertificationArtifactCodec codec = new MarkerCodec(inputLock, evidence, manifest, compatibility);
            return new CertifiedComponentResolver(
                    certificationRepository,
                    ignored -> certificationOwner,
                    buildSessions,
                    workOrders,
                    candidates,
                    verifications,
                    intents,
                    artifacts,
                    new WorkerProtocolArtifacts(artifacts, workerDecoder),
                    run -> { evidenceReads.incrementAndGet(); return true; },
                    run -> { ownerReads.incrementAndGet(); return owner; },
                    codec,
                    Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        }

        private VerificationRun verification(
                VerificationRunStatus status, ContentHash resultHash, VerificationDisposition disposition) {
            return new VerificationRun(
                    verificationId,
                    tenant,
                    buildSessionId,
                    candidateId,
                    sha256("source"),
                    "internal",
                    sha256("toolchain"),
                    sha256("fixtures"),
                    status,
                    Optional.of(new ArtifactReference("artifact:verification-result")),
                    Optional.of(resultHash),
                    Optional.of(status == VerificationRunStatus.PASSED ? "VERIFICATION_PASSED" : "VERIFICATION_FAILED"),
                    Optional.of(disposition),
                    Optional.of(NOW),
                    Optional.of(NOW),
                    2,
                    NOW,
                    NOW);
        }

        private VerificationDispatchIntent completedIntent(String actionRunId) {
            return VerificationDispatchIntent.pending(
                            "operation-001",
                            tenant,
                            verificationId,
                            candidateId,
                            0,
                            actionRunId,
                            "1".repeat(64),
                            NOW.plusSeconds(60),
                            NOW)
                    .claim("claim-001", NOW, Duration.ofSeconds(10))
                    .complete("claim-001", "VERIFICATION_COMPLETED", NOW);
        }

        private CertifiedAgentComponentRef reference(
                CertificationArtifactLock compatibilityLock, CertificationArtifactLock evidenceLock) {
            return new CertifiedAgentComponentRef(
                    reference.schemaVersion(), reference.componentRole(), reference.productLineId(),
                    reference.artifactType(), reference.certificationId(), reference.certificationManifest(),
                    reference.candidateId(), reference.candidateHash(), reference.sourceManifest(),
                    reference.inputLockManifest(), reference.verificationRunId(),
                    reference.verificationResultManifest(), compatibilityLock, evidenceLock,
                    reference.certificationProfile());
        }

        private CertificationArtifactLock lock(String referenceSuffix, String content, String mediaType) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            ArtifactReference reference = new ArtifactReference("artifact:" + referenceSuffix);
            ContentHash hash = sha256(bytes);
            artifacts.store(new Artifact(tenant, reference, hash, mediaType, bytes));
            return new CertificationArtifactLock(reference, hash);
        }
    }

    private static final class MarkerCodec implements CertificationArtifactCodec {
        private final CertificationInputLock input;
        private final CertificationEvidenceManifest evidence;
        private final CertifiedAgentComponentManifest manifest;
        private final AgentPackCompatibilityDescriptor compatibility;

        private MarkerCodec(
                CertificationInputLock input,
                CertificationEvidenceManifest evidence,
                CertifiedAgentComponentManifest manifest,
                AgentPackCompatibilityDescriptor compatibility) {
            this.input = input;
            this.evidence = evidence;
            this.manifest = manifest;
            this.compatibility = compatibility;
        }

        @Override public byte[] writeInputLock(CertificationInputLock value) { return marker("input"); }
        @Override public CertificationInputLock readInputLock(byte[] content) {
            requireMarker(content, "input"); return input;
        }
        @Override public byte[] writeEvidence(CertificationEvidenceManifest value) { return marker("evidence"); }
        @Override public CertificationEvidenceManifest readEvidence(byte[] content) {
            requireMarker(content, "evidence"); return evidence;
        }
        @Override public byte[] writeComponentManifest(CertifiedAgentComponentManifest value) {
            return marker("manifest");
        }
        @Override public CertifiedAgentComponentManifest readComponentManifest(byte[] content) {
            requireMarker(content, "manifest"); return manifest;
        }
        @Override public byte[] writeCompatibilityDescriptor(AgentPackCompatibilityDescriptor value) {
            return marker("compatibility");
        }
        @Override public AgentPackCompatibilityDescriptor readCompatibilityDescriptor(byte[] content) {
            requireMarker(content, "compatibility"); return compatibility;
        }
        private static byte[] marker(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        private static void requireMarker(byte[] content, String expected) {
            if (!expected.equals(new String(content, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("unexpected marker");
            }
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> values = new LinkedHashMap<>();
        private final AtomicInteger readCount = new AtomicInteger();

        @Override
        public ArtifactReference store(Artifact artifact) {
            values.put(key(artifact.tenantId(), artifact.reference()), artifact);
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            readCount.incrementAndGet();
            return Optional.ofNullable(values.get(key(tenantId, reference)));
        }

        private void replaceMediaType(TenantId tenantId, ArtifactReference reference, String mediaType) {
            Artifact current = values.get(key(tenantId, reference));
            values.put(key(tenantId, reference), new Artifact(
                    current.tenantId(), current.reference(), current.contentHash(), mediaType, current.content()));
        }

        private static String key(TenantId tenantId, ArtifactReference reference) {
            return tenantId.value() + "\u0000" + reference.value();
        }
    }

    private static ContentHash sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ContentHash sha256(byte[] value) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
