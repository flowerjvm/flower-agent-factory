package io.github.flowerjvm.factory.application.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
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
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
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
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentPackCertificationRequestServiceTest {
    private static final TenantId TENANT = new TenantId("tenant-certification-request");
    private static final BuildSessionId SESSION = new BuildSessionId("session-certification-request");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId("work-certification-request");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-certification-request");
    private static final VerificationRunId VERIFICATION =
            new VerificationRunId("verification-certification-request");
    private static final String VERIFICATION_ACTION_RUN = "verification-action-owner";
    private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");

    @Test
    void trustedEvidenceConstructsAndAtomicallyRequestsOneCertification() {
        Fixture fixture = new Fixture();

        AgentPackCertificationRequestOutcome outcome = fixture.service().ensureRequested(TENANT, SESSION);

        assertEquals(CertificationRequestDisposition.CREATED, outcome.disposition());
        assertEquals(CertificationStatus.REQUESTED, outcome.certification().status());
        assertEquals(BuildSessionStatus.CERTIFYING, outcome.buildSession().status());
        assertEquals(BuildSessionPhase.CERTIFY, outcome.buildSession().currentPhase());
        assertEquals(CANDIDATE, outcome.certification().inputLock().candidateId());
        assertEquals(VERIFICATION_ACTION_RUN, outcome.certification().inputLock().verificationActionRunId());
        assertEquals(
                fixture.sourceArtifactHash,
                outcome.certification().inputLock().sourceManifest().hash());
        assertNotEquals(
                fixture.candidateHash,
                outcome.certification().inputLock().sourceManifest().hash(),
                "the source artifact content hash must not be confused with the candidate tree hash");
    }

    @Test
    void crashAfterArtifactStageBeforeRowsConvergesOnTheSameIdAndInputHash() {
        Fixture fixture = new Fixture();
        fixture.transaction.failBeforeCommit.set(true);

        AgentPackCertificationRequestException failure = assertThrows(
                AgentPackCertificationRequestException.class,
                () -> fixture.service().ensureRequested(TENANT, SESSION));
        assertEquals(AgentPackCertificationRequestService.TRANSACTION_INVALID, failure.code());
        Certification firstAttempt = fixture.transaction.lastAttempt.get();
        int stagedAfterCrash = fixture.artifacts.size();

        AgentPackCertificationRequestOutcome retry = fixture.service().ensureRequested(TENANT, SESSION);

        assertEquals(CertificationRequestDisposition.CREATED, retry.disposition());
        assertEquals(firstAttempt.certificationId(), retry.certification().certificationId());
        assertEquals(firstAttempt.inputLockArtifact(), retry.certification().inputLockArtifact());
        assertEquals(stagedAfterCrash, fixture.artifacts.size());
    }

    @Test
    void concurrentIdenticalRequestsHaveOneWinnerAndOneExactObservation() throws Exception {
        Fixture fixture = new Fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<AgentPackCertificationRequestOutcome> request =
                    () -> fixture.service().ensureRequested(TENANT, SESSION);
            List<Future<AgentPackCertificationRequestOutcome>> futures =
                    executor.invokeAll(List.of(request, request));
            List<AgentPackCertificationRequestOutcome> outcomes = new ArrayList<>();
            for (Future<AgentPackCertificationRequestOutcome> future : futures) {
                outcomes.add(future.get());
            }

            assertEquals(
                    Set.of(CertificationRequestDisposition.CREATED, CertificationRequestDisposition.EXISTING_EXACT),
                    Set.of(outcomes.get(0).disposition(), outcomes.get(1).disposition()));
            assertEquals(
                    outcomes.get(0).certification().certificationId(),
                    outcomes.get(1).certification().certificationId());
            assertEquals(
                    outcomes.get(0).certification().inputLockArtifact(),
                    outcomes.get(1).certification().inputLockArtifact());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void alreadyCertifyingExactRetryConvergesToExistingRequestedPair() {
        Fixture fixture = new Fixture();
        AgentPackCertificationRequestOutcome first = fixture.service().ensureRequested(TENANT, SESSION);

        AgentPackCertificationRequestOutcome retry = fixture.service().ensureRequested(TENANT, SESSION);

        assertEquals(CertificationRequestDisposition.EXISTING_EXACT, retry.disposition());
        assertEquals(first.certification(), retry.certification());
        assertEquals(first.buildSession(), retry.buildSession());
    }

    @Test
    void staleCandidateFailsClosedBeforeAnyCertificationRow() {
        Fixture fixture = new Fixture();
        fixture.candidate = fixture.candidate(hash('e'));

        AgentPackCertificationRequestException failure = assertThrows(
                AgentPackCertificationRequestException.class,
                () -> fixture.service().ensureRequested(TENANT, SESSION));

        assertEquals(AgentPackCertificationRequestService.CANDIDATE_MISMATCH, failure.code());
        assertNull(fixture.transaction.certification);
    }

    @Test
    void staleTrustedPolicyFailsClosedBeforeCanonicalArtifactsAreStaged() {
        Fixture fixture = new Fixture();
        CertificationArtifactLock otherContract = fixture.artifacts.put("other-product-contract", "other-contract");
        fixture.policy = new AgentPackCertificationPolicy(
                otherContract,
                fixture.policy.gateProfile(),
                fixture.policy.verificationFixtureSetHash(),
                fixture.policy.sourceLockAlgorithmId(),
                fixture.policy.certificationProfile(),
                fixture.policy.factoryVersion(),
                fixture.policy.flowerVersion(),
                fixture.policy.actionRuntimeVersion());
        int artifactsBefore = fixture.artifacts.size();

        AgentPackCertificationRequestException failure = assertThrows(
                AgentPackCertificationRequestException.class,
                () -> fixture.service().ensureRequested(TENANT, SESSION));

        assertEquals(AgentPackCertificationRequestService.POLICY_MISMATCH, failure.code());
        assertEquals(artifactsBefore, fixture.artifacts.size());
        assertNull(fixture.transaction.certification);
    }

    @Test
    void verificationOwnerOrLatestIntentMismatchFailsClosed() {
        Fixture pendingOwner = new Fixture();
        pendingOwner.owner = VerificationActionEvidenceOwner.Assessment.pending();
        assertOwnerFailure(pendingOwner);

        Fixture wrongIntent = new Fixture();
        wrongIntent.intent = wrongIntent.completedIntent(VERIFICATION_ACTION_RUN, 1);
        assertOwnerFailure(wrongIntent);
    }

    private static void assertOwnerFailure(Fixture fixture) {
        AgentPackCertificationRequestException failure = assertThrows(
                AgentPackCertificationRequestException.class,
                () -> fixture.service().ensureRequested(TENANT, SESSION));
        assertEquals(AgentPackCertificationRequestService.VERIFICATION_OWNER_INVALID, failure.code());
        assertNull(fixture.transaction.certification);
    }

    @Test
    void maintenanceRequestSelectsItsOwnLatestVerificationGateAndExactContract() {
        Fixture fixture = new Fixture();
        fixture.useMaintenanceContract();
        var outcome = fixture.service().ensureRequested(TENANT, SESSION);
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, fixture.queriedGate.get());
        assertEquals(MaintenanceInvestigationProductContract.lock(), outcome.certification().inputLock().productContractBundle());
        assertEquals(MaintenanceInvestigationProductContract.apiSignatureIndexLock(), outcome.certification().inputLock().apiSignatureIndex());
        assertEquals("internal", outcome.certification().inputLock().certificationProfile());
    }

    @Test
    void maintenanceRequestCannotBorrowAnOtherwisePassedLegacyVerification() {
        Fixture fixture = new Fixture();
        fixture.useMaintenanceContract();
        fixture.verification = new Fixture().verification;
        var failure = assertThrows(AgentPackCertificationRequestException.class,
                () -> fixture.service().ensureRequested(TENANT, SESSION));
        assertEquals(AgentPackCertificationRequestService.VERIFICATION_NOT_ELIGIBLE, failure.code());
        assertNull(fixture.transaction.lastAttempt.get());
    }

    @Test
    void changedMaintenanceRequirementMatrixFailsBeforeVerificationLookupOrCertificationStaging() {
        Fixture fixture = new Fixture();
        fixture.useMaintenanceContract();
        var input = fixture.generationInput;
        fixture.generationInput = new CodingWorkerInputManifest(input.schemaVersion(), input.workOrderId(),
                input.buildSessionId(), input.skillId(), input.skillVersion(), input.skillArtifactRef(), input.skillHash(),
                input.dependencyLockRef(), input.dependencyLockHash(), input.toolchainLockRef(), input.toolchainLockHash(),
                input.apiSignatureIndexRef(), input.apiSignatureIndexHash(), input.productContractBundleRef(),
                input.productContractBundleHash(), input.gateProfile(), new ArtifactReference("other-matrix"),
                input.requirementTestMatrixHash(), input.sourceLockAlgorithmId(), input.repairLock());
        var failure = assertThrows(AgentPackCertificationRequestException.class,
                () -> fixture.service().ensureRequested(TENANT, SESSION));
        assertEquals(AgentPackCertificationRequestService.POLICY_MISMATCH, failure.code());
        assertNull(fixture.queriedGate.get());
        assertNull(fixture.transaction.lastAttempt.get());
    }

    private static final class Fixture {
        private final MemoryArtifactStore artifacts = new MemoryArtifactStore();
        private final ContentHash candidateHash = hash('c');
        private final ContentHash fixtureHash = hash('8');
        private final CertificationArtifactLock dependency = artifacts.put("dependency", "dependency-lock");
        private final CertificationArtifactLock toolchain = artifacts.put("toolchain", "toolchain-lock");
        private CertificationArtifactLock productContract =
                artifacts.put("product-contract", "product-contract");
        private CertificationArtifactLock apiSignature = artifacts.put("api-signature", "api-signature");
        private final CertificationArtifactLock policySnapshot = artifacts.put("policy", "policy-snapshot");
        private final CertificationArtifactLock generationInputArtifact =
                artifacts.put("generation-input", "generation-input");
        private final CertificationArtifactLock verificationResult =
                artifacts.put("verification-result", "verification-result");
        private final CertificationArtifactLock sourceArtifact = artifacts.put("source", "source-manifest");
        private final ContentHash sourceArtifactHash = sourceArtifact.hash();
        private final AtomicReference<BuildSession> session = new AtomicReference<>(releaseReadySession());
        private final TestTransaction transaction = new TestTransaction(session);
        private CandidateVersion candidate = candidate(candidateHash);
        private final WorkOrder workOrder = workOrder();
        private CodingWorkerInputManifest generationInput = generationInput();
        private final CandidateSourceManifest sourceManifest = new CandidateSourceManifest(
                CandidateSourceManifest.SCHEMA_VERSION,
                CANDIDATE,
                SESSION,
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                candidateHash,
                0,
                0,
                List.of());
        private VerificationRun verification = verification();
        private boolean multiProfile;
        private final AtomicReference<String> queriedGate = new AtomicReference<>();
        private VerificationDispatchIntent intent = completedIntent(VERIFICATION_ACTION_RUN);
        private VerificationActionEvidenceOwner.Assessment owner =
                VerificationActionEvidenceOwner.Assessment.canonical();
        private AgentPackCertificationPolicy policy = new AgentPackCertificationPolicy(
                productContract,
                "internal",
                fixtureHash,
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                "agent-pack-release",
                "0.1.0",
                "0.3.3",
                "0.3.3");

        private void useMaintenanceContract() {
            multiProfile = true;
            MaintenanceInvestigationProductContract.artifacts(TENANT).forEach(artifacts::store);
            productContract = MaintenanceInvestigationProductContract.lock();
            apiSignature = MaintenanceInvestigationProductContract.apiSignatureIndexLock();
            var input = generationInput();
            var matrix = MaintenanceInvestigationProductContract.requirementTestMatrixLock();
            generationInput = new CodingWorkerInputManifest(input.schemaVersion(), input.workOrderId(),
                    input.buildSessionId(), input.skillId(), input.skillVersion(), input.skillArtifactRef(), input.skillHash(),
                    input.dependencyLockRef(), input.dependencyLockHash(), input.toolchainLockRef(), input.toolchainLockHash(),
                    apiSignature.reference(), apiSignature.hash(), productContract.reference(), productContract.hash(),
                    MaintenanceInvestigationProductContract.GATE_PROFILE, matrix.reference(), matrix.hash(),
                    input.sourceLockAlgorithmId(), input.repairLock());
            verification = verification();
        }

        private AgentPackCertificationRequestService service() {
            BuildSessionRepository buildSessions = new BuildSessionRepository() {
                @Override
                public void create(BuildSession value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
                    BuildSession current = session.get();
                    return current.tenantId().equals(tenantId) && current.buildSessionId().equals(buildSessionId)
                            ? Optional.of(current)
                            : Optional.empty();
                }

                @Override
                public boolean compareAndSet(BuildSession expected, BuildSession next) {
                    return session.compareAndSet(expected, next);
                }
            };
            CandidateVersionRepository candidates = new CandidateVersionRepository() {
                @Override
                public void create(CandidateVersion value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
                    return candidate.tenantId().equals(tenantId) && candidate.candidateId().equals(candidateId)
                            ? Optional.of(candidate)
                            : Optional.empty();
                }

                @Override
                public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                        TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
                    return Optional.empty();
                }
            };
            WorkOrderRepository workOrders = new WorkOrderRepository() {
                @Override
                public void create(WorkOrder value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId) {
                    return workOrder.tenantId().equals(tenantId) && workOrder.workOrderId().equals(workOrderId)
                            ? Optional.of(workOrder)
                            : Optional.empty();
                }
            };
            WorkerProtocolArtifacts workerArtifacts = new WorkerProtocolArtifacts(
                    artifacts,
                    new WorkerProtocolArtifactDecoder() {
                        @Override
                        public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CodingWorkerInputManifest decodeInputManifest(byte[] content) {
                            return generationInput;
                        }

                        @Override
                        public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
                            return sourceManifest;
                        }
                    });
            VerificationRunRepository verificationRuns = new VerificationRunRepository() {
                @Override
                public void create(VerificationRun value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<VerificationRun> find(TenantId tenantId, VerificationRunId verificationRunId) {
                    return verification.tenantId().equals(tenantId)
                                    && verification.verificationRunId().equals(verificationRunId)
                            ? Optional.of(verification)
                            : Optional.empty();
                }

                @Override
                public Optional<VerificationRun> findLatestForCandidate(
                        TenantId tenantId,
                        BuildSessionId buildSessionId,
                        CandidateId candidateId,
                        ContentHash candidateHash,
                        String gateProfile) {
                    queriedGate.set(gateProfile);
                    return gateProfile.equals(verification.gateProfile()) ? Optional.of(verification) : Optional.empty();
                }

                @Override
                public boolean compareAndSet(VerificationRun expected, VerificationRun next) {
                    return false;
                }
            };
            VerificationDispatchIntentRepository intents = new VerificationDispatchIntentRepository() {
                @Override
                public void create(VerificationDispatchIntent value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<VerificationDispatchIntent> find(String operationId) {
                    return Optional.empty();
                }

                @Override
                public Optional<VerificationDispatchIntent> findLatest(
                        TenantId tenantId, VerificationRunId verificationRunId) {
                    return intent.tenantId().equals(tenantId) && intent.verificationRunId().equals(verificationRunId)
                            ? Optional.of(intent)
                            : Optional.empty();
                }

                @Override
                public Optional<VerificationDispatchIntent> claimNext(
                        Instant now, Duration lease, String claimToken) {
                    return Optional.empty();
                }

                @Override
                public Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
                        Instant now, Duration lease, String claimToken) {
                    return Optional.empty();
                }

                @Override
                public boolean compareAndSet(VerificationDispatchIntent expected, VerificationDispatchIntent next) {
                    return false;
                }
            };
            VerificationEvidenceValidator evidence = ignored -> true;
            VerificationActionEvidenceOwner evidenceOwner = ignored -> owner;
            return new AgentPackCertificationRequestService(
                    buildSessions,
                    candidates,
                    workOrders,
                    workerArtifacts,
                    verificationRuns,
                    evidence,
                    evidenceOwner,
                    intents,
                    artifacts,
                    new TestCertificationArtifactCodec(),
                    transaction,
                    multiProfile ? AgentPackCertificationPolicyCatalog.production(fixtureHash, "0.1.0-internal.1",
                            "0.1.3", "0.3.3", workOrders, workerArtifacts)
                            : AgentPackCertificationPolicyCatalog.singleton(policy),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private CandidateVersion candidate(ContentHash sourceHash) {
            return new CandidateVersion(
                    CANDIDATE,
                    TENANT,
                    SESSION,
                    Optional.empty(),
                    sourceArtifact.reference(),
                    sourceHash,
                    dependency.reference(),
                    dependency.hash(),
                    toolchain.reference(),
                    toolchain.hash(),
                    CandidateVersionStatus.GENERATED,
                    WORK_ORDER,
                    NOW.minusSeconds(120));
        }

        private WorkOrder workOrder() {
            return new WorkOrder(
                    WORK_ORDER,
                    TENANT,
                    SESSION,
                    BuildSessionPhase.GENERATE_CANDIDATE.id(),
                    "generate exact Agent Pack candidate",
                    1,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("base-revision"),
                    new ArtifactReference("instruction"),
                    hash('1'),
                    generationInputArtifact.reference(),
                    generationInputArtifact.hash(),
                    "workspace",
                    List.of("src"),
                    List.of("src"),
                    Set.of(),
                    "candidate-output",
                    "1",
                    policySnapshot.reference(),
                    NOW.plusSeconds(120),
                    1,
                    "generation-key",
                    WorkOrderCreatorType.SYSTEM,
                    "flow-run",
                    NOW.minusSeconds(180));
        }

        private CodingWorkerInputManifest generationInput() {
            return new CodingWorkerInputManifest(
                    CodingWorkerInputManifest.SCHEMA_VERSION,
                    WORK_ORDER,
                    SESSION,
                    "agent-pack-builder",
                    "1.0.0",
                    new ArtifactReference("skill"),
                    hash('2'),
                    dependency.reference(),
                    dependency.hash(),
                    toolchain.reference(),
                    toolchain.hash(),
                    apiSignature.reference(),
                    apiSignature.hash(),
                    productContract.reference(),
                    productContract.hash(),
                    "internal",
                    new ArtifactReference("requirement-matrix"),
                    hash('3'),
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                    Optional.empty());
        }

        private VerificationRun verification() {
            return new VerificationRun(
                    VERIFICATION,
                    TENANT,
                    SESSION,
                    CANDIDATE,
                    candidateHash,
                    generationInput.gateProfile(),
                    toolchain.hash(),
                    fixtureHash,
                    VerificationRunStatus.PASSED,
                    Optional.of(verificationResult.reference()),
                    Optional.of(verificationResult.hash()),
                    Optional.of("VERIFICATION_PASSED"),
                    Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                    Optional.of(NOW.minusSeconds(60)),
                    Optional.of(NOW.minusSeconds(30)),
                    2,
                    NOW.minusSeconds(90),
                    NOW.minusSeconds(30));
        }

        private VerificationDispatchIntent completedIntent(String actionRunId) {
            return completedIntent(actionRunId, 0);
        }

        private VerificationDispatchIntent completedIntent(String actionRunId, long expectedVersion) {
            return VerificationDispatchIntent.pending(
                            "verification:operation",
                            TENANT,
                            VERIFICATION,
                            CANDIDATE,
                            expectedVersion,
                            actionRunId,
                            String.valueOf('9').repeat(64),
                            NOW.plusSeconds(120),
                            NOW.minusSeconds(80))
                    .claim("claim", NOW.minusSeconds(70), Duration.ofSeconds(30))
                    .complete("claim", "VERIFICATION_COMPLETED", NOW.minusSeconds(40));
        }
    }

    private static final class TestTransaction implements CertificationRequestTransaction {
        private final AtomicReference<BuildSession> session;
        private final AtomicBoolean failBeforeCommit = new AtomicBoolean();
        private final AtomicReference<Certification> lastAttempt = new AtomicReference<>();
        private Certification certification;

        private TestTransaction(AtomicReference<BuildSession> session) {
            this.session = session;
        }

        @Override
        public synchronized AgentPackCertificationRequestOutcome ensureRequested(
                BuildSession expectedSession,
                BuildSession certifyingSession,
                Certification requestedCertification) {
            lastAttempt.set(requestedCertification);
            if (failBeforeCommit.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated crash before database commit");
            }
            BuildSession current = session.get();
            if (certification != null) {
                boolean exact = certification.certificationId().equals(requestedCertification.certificationId())
                        && certification.inputLock().equals(requestedCertification.inputLock())
                        && certification.inputLockArtifact().equals(requestedCertification.inputLockArtifact())
                        && current.status() == BuildSessionStatus.CERTIFYING
                        && current.currentPhase() == BuildSessionPhase.CERTIFY
                        && current.currentCandidateId().equals(certifyingSession.currentCandidateId())
                        && current.currentCandidateHash().equals(certifyingSession.currentCandidateHash());
                return new AgentPackCertificationRequestOutcome(
                        exact
                                ? CertificationRequestDisposition.EXISTING_EXACT
                                : CertificationRequestDisposition.CONFLICT,
                        certification,
                        current);
            }
            if (!current.equals(expectedSession)) {
                throw new IllegalStateException("session CAS conflict without a canonical Certification");
            }
            certification = requestedCertification;
            session.set(certifyingSession);
            return new AgentPackCertificationRequestOutcome(
                    CertificationRequestDisposition.CREATED, certification, certifyingSession);
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> stored = new ConcurrentHashMap<>();

        @Override
        public ArtifactReference store(Artifact artifact) {
            String key = key(artifact.tenantId(), artifact.reference());
            Artifact existing = stored.putIfAbsent(key, artifact);
            if (existing != null
                    && (!existing.contentHash().equals(artifact.contentHash())
                            || !existing.mediaType().equals(artifact.mediaType())
                            || !Arrays.equals(existing.content(), artifact.content()))) {
                throw new IllegalStateException("artifact reference collision");
            }
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(stored.get(key(tenantId, reference)));
        }

        private CertificationArtifactLock put(String name, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            ContentHash hash = sha256(bytes);
            ArtifactReference reference = new ArtifactReference("artifact/" + name + "/" + hash.sha256());
            store(new Artifact(TENANT, reference, hash, "application/octet-stream", bytes));
            return new CertificationArtifactLock(reference, hash);
        }

        private int size() {
            return stored.size();
        }

        private static String key(TenantId tenantId, ArtifactReference reference) {
            return tenantId.value() + "\n" + reference.value();
        }
    }

    private static final class TestCertificationArtifactCodec implements CertificationArtifactCodec {
        @Override
        public byte[] writeInputLock(CertificationInputLock value) {
            return bytes("input-lock", value);
        }

        @Override
        public CertificationInputLock readInputLock(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeEvidence(CertificationEvidenceManifest value) {
            return bytes("evidence", value);
        }

        @Override
        public CertificationEvidenceManifest readEvidence(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeComponentManifest(CertifiedAgentComponentManifest value) {
            return bytes("component", value);
        }

        @Override
        public CertifiedAgentComponentManifest readComponentManifest(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeCompatibilityDescriptor(AgentPackCompatibilityDescriptor value) {
            return bytes("compatibility", value);
        }

        @Override
        public AgentPackCompatibilityDescriptor readCompatibilityDescriptor(byte[] content) {
            throw new UnsupportedOperationException();
        }

        private static byte[] bytes(String kind, Object value) {
            return (kind + "\n" + value).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static BuildSession releaseReadySession() {
        return new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-certification-request"),
                ProductLineId.AGENT_PACK,
                "request-key",
                "test",
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                new ArtifactReference("requirements"),
                hash('f'),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(CANDIDATE),
                Optional.of(hash('c')),
                Optional.empty(),
                0,
                2,
                NOW.minusSeconds(600),
                NOW.plusSeconds(300),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                10,
                NOW.minusSeconds(600),
                NOW.minusSeconds(1));
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
