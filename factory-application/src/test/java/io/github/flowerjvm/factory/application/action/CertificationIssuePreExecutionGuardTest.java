package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
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
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
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
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CertificationIssuePreExecutionGuardTest {
    private static final TenantId TENANT = new TenantId("tenant-certification-guard");
    private static final BuildSessionId SESSION = new BuildSessionId("session-certification-guard");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId("work-certification-guard");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-certification-guard");
    private static final VerificationRunId VERIFICATION =
            new VerificationRunId("verification-certification-guard");
    private static final CertificationId CERTIFICATION =
            new CertificationId("certification-guard");
    private static final String VERIFICATION_ACTION_RUN = "verification-action-run";
    private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");

    @Test
    void completeExactAgentPackEvidenceChainIsAdmitted() {
        Fixture fixture = new Fixture();

        PreExecutionDecision decision = fixture.check();

        assertTrue(decision.allowed());
    }

    @Test
    void certificationAndTrustedPolicyAreRecheckedAtTheLastResponsibleMoment() {
        denied("CERTIFICATION_STALE", fixture -> fixture.certification =
                fixture.certification.reject("CERTIFICATION_REJECTED", NOW.minusSeconds(1)));
        denied("CERTIFICATION_STALE", fixture -> fixture.expectedCertificationVersion = 1);
        denied("CERTIFICATION_INPUT_LOCK_MISMATCH", fixture -> fixture.proposalInputHash = hash('f'));
        denied("CERTIFICATION_POLICY_MISMATCH", fixture -> fixture.trustedPolicy =
                fixture.policyWithGate("release"));
    }

    @Test
    void buildSessionMustBeTheLiveExactAgentPackCertifyAuthority() {
        denied("BUILD_SESSION_NOT_CERTIFIABLE", fixture -> fixture.session = fixture.session(
                new ProductLineId("tos"), BuildSessionStatus.CERTIFYING, BuildSessionPhase.CERTIFY,
                CANDIDATE, fixture.inputLock.candidateHash(), Optional.empty(), NOW.plusSeconds(60)));
        denied("BUILD_SESSION_NOT_CERTIFIABLE", fixture -> fixture.session = fixture.session(
                ProductLineId.AGENT_PACK, BuildSessionStatus.RUNNING, BuildSessionPhase.CERTIFY,
                CANDIDATE, fixture.inputLock.candidateHash(), Optional.empty(), NOW.plusSeconds(60)));
        denied("BUILD_SESSION_NOT_CERTIFIABLE", fixture -> fixture.session = fixture.session(
                ProductLineId.AGENT_PACK, BuildSessionStatus.CERTIFYING, BuildSessionPhase.EVALUATE,
                CANDIDATE, fixture.inputLock.candidateHash(), Optional.empty(), NOW.plusSeconds(60)));
        denied("BUILD_SESSION_NOT_CERTIFIABLE", fixture -> fixture.session = fixture.session(
                ProductLineId.AGENT_PACK, BuildSessionStatus.CERTIFYING, BuildSessionPhase.CERTIFY,
                new CandidateId("other-candidate"), hash('e'), Optional.empty(), NOW.plusSeconds(60)));
        denied("BUILD_SESSION_NOT_CERTIFIABLE", fixture -> fixture.session = fixture.session(
                ProductLineId.AGENT_PACK, BuildSessionStatus.CERTIFYING, BuildSessionPhase.CERTIFY,
                CANDIDATE, fixture.inputLock.candidateHash(), Optional.of(NOW.minusSeconds(1)),
                NOW.plusSeconds(60)));
        denied("BUILD_SESSION_NOT_CERTIFIABLE", fixture -> fixture.session = fixture.session(
                ProductLineId.AGENT_PACK, BuildSessionStatus.CERTIFYING, BuildSessionPhase.CERTIFY,
                CANDIDATE, fixture.inputLock.candidateHash(), Optional.empty(), NOW));
    }

    @Test
    void generationAndCandidateLocksMustRemainExactAndReadable() {
        denied("CERTIFICATION_WORK_ORDER_MISMATCH", fixture -> fixture.workOrder = fixture.workOrder(
                new ArtifactReference("artifact:other-input"), fixture.inputLock.generationInputManifest().hash(),
                fixture.inputLock.policySnapshot().reference()));
        denied("CERTIFICATION_GENERATION_INPUT_INVALID", fixture -> fixture.artifacts.replace(new Artifact(
                TENANT,
                fixture.inputLock.generationInputManifest().reference(),
                fixture.inputLock.generationInputManifest().hash(),
                "application/json",
                "corrupt-generation-input".getBytes(StandardCharsets.UTF_8))));
        denied("CERTIFICATION_GENERATION_INPUT_INVALID", fixture -> fixture.decoderThrows = true);
        denied("CERTIFICATION_GENERATION_INPUT_MISMATCH", fixture -> fixture.generationInput =
                fixture.generationInput("release"));
        denied("CERTIFICATION_CANDIDATE_MISMATCH", fixture -> fixture.candidate = fixture.candidate(hash('e')));
    }

    @Test
    void onlyPassedReviewEligibleCanonicalVerificationEvidenceIsAdmitted() {
        denied("CERTIFICATION_VERIFICATION_NOT_ELIGIBLE", fixture -> fixture.verification =
                fixture.verification(hash('e')));
        denied("CERTIFICATION_VERIFICATION_NOT_ELIGIBLE", fixture -> fixture.evidenceEligible = false);
        denied("CERTIFICATION_VERIFICATION_OWNER_INVALID", fixture -> fixture.owner =
                VerificationActionEvidenceOwner.Assessment.pending());
        denied("CERTIFICATION_VERIFICATION_OWNER_INVALID", fixture -> fixture.intent = fixture.intent(
                "other-verification-action-run"));
    }

    @Test
    void corruptEvidenceAndOwnerReadersFailClosedWithStableCodes() {
        denied("CERTIFICATION_VERIFICATION_EVIDENCE_INVALID", fixture -> fixture.evidenceThrows = true);
        denied("CERTIFICATION_VERIFICATION_OWNER_INVALID", fixture -> fixture.ownerThrows = true);
        denied("CERTIFICATION_VERIFICATION_OWNER_INVALID", fixture -> fixture.intentThrows = true);
    }

    private static void denied(String expectedCode, Consumer<Fixture> mutation) {
        Fixture fixture = new Fixture();
        mutation.accept(fixture);

        PreExecutionDecision decision = fixture.check();

        assertFalse(decision.allowed());
        assertEquals(expectedCode, decision.code());
    }

    private static final class Fixture {
        private final MutableArtifacts artifacts = new MutableArtifacts();
        private final CertificationArtifactLock source = lock("source", '1');
        private final CertificationArtifactLock dependency = lock("dependency", '2');
        private final CertificationArtifactLock toolchain = lock("toolchain", '3');
        private final CertificationArtifactLock generationInputArtifact =
                storedLock("generation-input", "generation-input");
        private final CertificationArtifactLock productContract = lock("product-contract", '5');
        private final CertificationArtifactLock apiSignatures = lock("api-signatures", '6');
        private final CertificationArtifactLock verificationResult = lock("verification-result", '7');
        private final CertificationArtifactLock policySnapshot = storedLock("policy-snapshot", "policy-snapshot");
        private final CertificationArtifactLock compatibility = lock("compatibility", 'a');
        private final CertificationInputLock inputLock;
        private Certification certification;
        private BuildSession session;
        private WorkOrder workOrder;
        private CodingWorkerInputManifest generationInput;
        private CandidateVersion candidate;
        private VerificationRun verification;
        private VerificationDispatchIntent intent;
        private AgentPackCertificationPolicy trustedPolicy;
        private ContentHash proposalInputHash;
        private long expectedCertificationVersion;
        private boolean decoderThrows;
        private boolean evidenceEligible = true;
        private boolean evidenceThrows;
        private boolean ownerThrows;
        private boolean intentThrows;
        private VerificationActionEvidenceOwner.Assessment owner =
                VerificationActionEvidenceOwner.Assessment.canonical();

        private Fixture() {
            inputLock = new CertificationInputLock(
                    CertificationInputLock.SCHEMA_VERSION,
                    TENANT,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    SESSION,
                    WORK_ORDER,
                    CANDIDATE,
                    source.hash(),
                    source,
                    dependency,
                    toolchain,
                    generationInputArtifact,
                    productContract,
                    apiSignatures,
                    "sha256-ordinal-v1",
                    "internal",
                    VERIFICATION,
                    VERIFICATION_ACTION_RUN,
                    verificationResult,
                    hash('8'),
                    policySnapshot,
                    compatibility,
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            certification = Certification.requested(
                    CERTIFICATION, inputLock, lock("certification-input", 'b'), NOW.minusSeconds(30));
            session = session(
                    ProductLineId.AGENT_PACK,
                    BuildSessionStatus.CERTIFYING,
                    BuildSessionPhase.CERTIFY,
                    CANDIDATE,
                    inputLock.candidateHash(),
                    Optional.empty(),
                    NOW.plusSeconds(60));
            workOrder = workOrder(
                    generationInputArtifact.reference(), generationInputArtifact.hash(), policySnapshot.reference());
            generationInput = generationInput("internal");
            candidate = candidate(source.hash());
            verification = verification(verificationResult.hash());
            intent = intent(VERIFICATION_ACTION_RUN);
            trustedPolicy = policyWithGate("internal");
            proposalInputHash = certification.inputLockArtifact().hash();
        }

        private PreExecutionDecision check() {
            return guard().check(
                    proposal(),
                    CertificationIssueAction.definition(),
                    context(),
                    PolicyDecision.allow());
        }

        private CertificationIssuePreExecutionGuard guard() {
            CertificationRepository certifications = new CertificationRepository() {
                @Override public void create(Certification ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<Certification> find(TenantId tenantId, CertificationId id) {
                    return TENANT.equals(tenantId) && CERTIFICATION.equals(id)
                            ? Optional.of(certification) : Optional.empty();
                }
                @Override public boolean compareAndSet(Certification expected, Certification next) {
                    throw new UnsupportedOperationException();
                }
            };
            BuildSessionRepository buildSessions = new BuildSessionRepository() {
                @Override public void create(BuildSession ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<BuildSession> find(TenantId tenantId, BuildSessionId id) {
                    return TENANT.equals(tenantId) && SESSION.equals(id) ? Optional.of(session) : Optional.empty();
                }
                @Override public boolean compareAndSet(BuildSession expected, BuildSession next) {
                    throw new UnsupportedOperationException();
                }
            };
            WorkOrderRepository workOrders = new WorkOrderRepository() {
                @Override public void create(WorkOrder ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId id) {
                    return TENANT.equals(tenantId) && WORK_ORDER.equals(id)
                            ? Optional.of(workOrder) : Optional.empty();
                }
            };
            CandidateVersionRepository candidates = new CandidateVersionRepository() {
                @Override public void create(CandidateVersion ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId id) {
                    return TENANT.equals(tenantId) && CANDIDATE.equals(id)
                            ? Optional.of(candidate) : Optional.empty();
                }
                @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                        TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
                    throw new UnsupportedOperationException();
                }
            };
            VerificationRunRepository verifications = new VerificationRunRepository() {
                @Override public void create(VerificationRun ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<VerificationRun> find(TenantId tenantId, VerificationRunId id) {
                    return TENANT.equals(tenantId) && VERIFICATION.equals(id)
                            ? Optional.of(verification) : Optional.empty();
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
                        TenantId tenantId, VerificationRunId verificationRunId) {
                    if (intentThrows) {
                        throw new IllegalArgumentException("corrupt verification intent");
                    }
                    return Optional.ofNullable(intent);
                }
                @Override public Optional<VerificationDispatchIntent> claimNext(
                        Instant now, Duration lease, String claimToken) {
                    throw new UnsupportedOperationException();
                }
                @Override public Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
                        Instant now, Duration lease, String claimToken) {
                    throw new UnsupportedOperationException();
                }
                @Override public boolean compareAndSet(
                        VerificationDispatchIntent expected, VerificationDispatchIntent next) {
                    throw new UnsupportedOperationException();
                }
            };
            WorkerProtocolArtifactDecoder decoder = new WorkerProtocolArtifactDecoder() {
                @Override public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) {
                    throw new UnsupportedOperationException();
                }
                @Override public CodingWorkerInputManifest decodeInputManifest(byte[] content) {
                    if (decoderThrows) {
                        throw new IllegalArgumentException("corrupt generation input");
                    }
                    return generationInput;
                }
                @Override public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
                    throw new UnsupportedOperationException();
                }
            };
            return new CertificationIssuePreExecutionGuard(
                    certifications,
                    buildSessions,
                    workOrders,
                    new WorkerProtocolArtifacts(artifacts, decoder),
                    candidates,
                    verifications,
                    run -> {
                        if (evidenceThrows) {
                            throw new IllegalArgumentException("corrupt verification evidence");
                        }
                        return evidenceEligible;
                    },
                    run -> {
                        if (ownerThrows) {
                            throw new IllegalArgumentException("corrupt verification owner");
                        }
                        return owner;
                    },
                    intents,
                    trustedPolicy,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private ActionProposal proposal() {
            CertificationIssueInput input = new CertificationIssueInput(
                    CERTIFICATION, proposalInputHash, expectedCertificationVersion);
            return ActionProposal.builder(CertificationIssueAction.ACTION_ID)
                    .proposalId("proposal-certification-guard")
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .requesterId("factory-certifier")
                    .input(input.toMap())
                    .idempotencyKey(CertificationIssueIdempotencyKeys.derive(
                            certification, expectedCertificationVersion))
                    .build();
        }

        private ExecutionContext context() {
            return new ExecutionContext(
                    TENANT.value(),
                    "factory-certifier",
                    "certification-action-run",
                    "trace-certification-guard",
                    Map.of(
                            "actor.permissions", Set.of(CertificationIssueAction.PERMISSION),
                            "resource.type", CertificationIssueAction.RESOURCE_TYPE,
                            "resource.id", CERTIFICATION.value()));
        }

        private AgentPackCertificationPolicy policyWithGate(String gateProfile) {
            return new AgentPackCertificationPolicy(
                    productContract,
                    gateProfile,
                    inputLock.verificationFixtureSetHash(),
                    inputLock.sourceLockAlgorithmId(),
                    inputLock.certificationProfile(),
                    inputLock.factoryVersion(),
                    inputLock.flowerVersion(),
                    inputLock.actionRuntimeVersion());
        }

        private BuildSession session(
                ProductLineId productLine,
                BuildSessionStatus status,
                BuildSessionPhase phase,
                CandidateId candidateId,
                ContentHash candidateHash,
                Optional<Instant> cancellation,
                Instant deadline) {
            return new BuildSession(
                    SESSION,
                    TENANT,
                    new ProjectId("project-certification-guard"),
                    productLine,
                    "request-certification-guard",
                    "principal-certification-guard",
                    status,
                    phase,
                    new ArtifactReference("artifact:requirements"),
                    hash('0'),
                    Optional.of("manager-worker"),
                    Optional.of("coding-worker"),
                    Optional.empty(),
                    Optional.of(candidateId),
                    Optional.of(candidateHash),
                    Optional.empty(),
                    0,
                    2,
                    NOW.minusSeconds(120),
                    deadline,
                    cancellation,
                    Optional.empty(),
                    Optional.empty(),
                    2,
                    NOW.minusSeconds(120),
                    NOW.minusSeconds(30));
        }

        private WorkOrder workOrder(
                ArtifactReference inputReference,
                ContentHash inputHash,
                ArtifactReference policyReference) {
            return new WorkOrder(
                    WORK_ORDER,
                    TENANT,
                    SESSION,
                    "generate-candidate",
                    "Generate exact Agent Pack candidate",
                    1,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("base"),
                    new ArtifactReference("artifact:instruction"),
                    hash('d'),
                    inputReference,
                    inputHash,
                    "workspace:certification-guard",
                    List.of("/input"),
                    List.of("/output"),
                    Set.of(),
                    "agent-pack-candidate",
                    "1",
                    policyReference,
                    NOW.plusSeconds(30),
                    1,
                    "work-certification-guard",
                    WorkOrderCreatorType.SYSTEM,
                    "factory",
                    NOW.minusSeconds(90));
        }

        private CodingWorkerInputManifest generationInput(String gateProfile) {
            return new CodingWorkerInputManifest(
                    CodingWorkerInputManifest.SCHEMA_VERSION,
                    WORK_ORDER,
                    SESSION,
                    "agent-pack-generator",
                    "1.0.0",
                    new ArtifactReference("artifact:skill"),
                    hash('c'),
                    dependency.reference(),
                    dependency.hash(),
                    toolchain.reference(),
                    toolchain.hash(),
                    apiSignatures.reference(),
                    apiSignatures.hash(),
                    productContract.reference(),
                    productContract.hash(),
                    gateProfile,
                    new ArtifactReference("artifact:requirement-matrix"),
                    hash('d'),
                    "sha256-ordinal-v1",
                    Optional.empty());
        }

        private CandidateVersion candidate(ContentHash sourceHash) {
            return new CandidateVersion(
                    CANDIDATE,
                    TENANT,
                    SESSION,
                    Optional.empty(),
                    source.reference(),
                    sourceHash,
                    dependency.reference(),
                    dependency.hash(),
                    toolchain.reference(),
                    toolchain.hash(),
                    CandidateVersionStatus.GENERATED,
                    WORK_ORDER,
                    NOW.minusSeconds(60));
        }

        private VerificationRun verification(ContentHash resultHash) {
            return new VerificationRun(
                    VERIFICATION,
                    TENANT,
                    SESSION,
                    CANDIDATE,
                    source.hash(),
                    "internal",
                    toolchain.hash(),
                    hash('8'),
                    VerificationRunStatus.PASSED,
                    Optional.of(verificationResult.reference()),
                    Optional.of(resultHash),
                    Optional.of("VERIFICATION_PASSED"),
                    Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                    Optional.of(NOW.minusSeconds(20)),
                    Optional.of(NOW.minusSeconds(10)),
                    2,
                    NOW.minusSeconds(30),
                    NOW.minusSeconds(10));
        }

        private VerificationDispatchIntent intent(String actionRunId) {
            return VerificationDispatchIntent.pending(
                    "operation-certification-guard",
                    TENANT,
                    VERIFICATION,
                    CANDIDATE,
                    0,
                    actionRunId,
                    "e".repeat(64),
                    NOW.plusSeconds(30),
                    NOW.minusSeconds(20));
        }

        private CertificationArtifactLock storedLock(String name, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            CertificationArtifactLock lock =
                    new CertificationArtifactLock(new ArtifactReference("artifact:" + name), sha256(bytes));
            artifacts.store(new Artifact(TENANT, lock.reference(), lock.hash(), "application/json", bytes));
            return lock;
        }
    }

    private static CertificationArtifactLock lock(String name, char hash) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:" + name), hash(hash));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MutableArtifacts implements ArtifactStore {
        private final Map<ArtifactReference, Artifact> values = new java.util.LinkedHashMap<>();

        @Override
        public ArtifactReference store(Artifact artifact) {
            values.put(artifact.reference(), artifact);
            return artifact.reference();
        }

        private void replace(Artifact artifact) {
            values.put(artifact.reference(), artifact);
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference))
                    .filter(artifact -> artifact.tenantId().equals(tenantId));
        }
    }
}
