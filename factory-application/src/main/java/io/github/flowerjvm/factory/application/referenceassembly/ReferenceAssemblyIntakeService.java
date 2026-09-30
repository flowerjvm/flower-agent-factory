package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Registered intake executor only. Full component evidence validation runs on the bounded host
 * control caller, never a Flower tick. Original certified artifacts are only read; the transaction
 * receives code-owned catalog inputs, one requirement and one immutable acceptance receipt.
 */
public final class ReferenceAssemblyIntakeService {
    private final BuildSessionRepository sessions;
    private final CertificationRepository certifications;
    private final CertifiedAgentComponentReadGate fullResolver;
    private final ArtifactStore artifacts;
    private final ReferenceAssemblyArtifactCodec codec;
    private final ReferenceAssemblyIntakeTransaction transaction;
    private final Clock clock;

    public ReferenceAssemblyIntakeService(BuildSessionRepository sessions, CertificationRepository certifications,
            CertifiedAgentComponentReadGate fullResolver, ArtifactStore artifacts, ReferenceAssemblyArtifactCodec codec,
            ReferenceAssemblyIntakeTransaction transaction, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.certifications = Objects.requireNonNull(certifications, "certifications");
        this.fullResolver = Objects.requireNonNull(fullResolver, "fullResolver");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BuildSession accept(TenantId tenant, String createdBy, String requestKey, ReferenceAssemblyIntakeInput input) {
        var prepared = check(tenant, createdBy, requestKey, input, clock.instant());
        var resolved = Objects.requireNonNull(fullResolver.resolve(tenant, prepared.component()), "resolved component");
        require(resolved.reference().equals(prepared.component())
                        && resolved.certification().equals(prepared.certification())
                        && resolved.inputLock().equals(prepared.certification().inputLock()),
                "full component read did not return the exact current certification");
        // A full read may take time. Recheck current certification, immutable selection and deadline
        // before handing off to the adapter's independently locked pre-commit full read gate.
        var observedAt = clock.instant();
        var current = check(tenant, createdBy, requestKey, input, observedAt);
        require(current.certification().equals(prepared.certification()), "certification changed during full evidence validation");
        Instant now = observedAt.truncatedTo(ChronoUnit.MICROS);
        var pristine = new BuildSession(input.buildSessionId(), tenant, input.projectId(), ProductLineId.REFERENCE_ASSEMBLY,
                requestKey, createdBy, BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER,
                current.requirement().reference(), current.requirement().hash(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 0, now, input.deadlineAt(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, now, now);
        var accepted = Objects.requireNonNull(transaction.accept(pristine, current.component(), current.staged()), "accepted session");
        requireExact(accepted, tenant, createdBy, requestKey, input, current.requirement());
        return requireAcceptedSession(tenant, input.projectId(), input.buildSessionId(), requestKey, input);
    }

    /** Read-only canonical receipt/requirement and current-certification metadata check for host post-commit launch. */
    public BuildSession requireAcceptedSession(TenantId tenant, ProjectId project, BuildSessionId sessionId,
                                               String requestKey, ReferenceAssemblyIntakeInput input) {
        require(project.equals(input.projectId()) && sessionId.equals(input.buildSessionId()), "trusted readback target mismatch");
        var prepared = check(tenant, ReferenceAssemblyIntakeAction.REQUESTER_ID, requestKey, input, clock.instant());
        var session = sessions.find(tenant, sessionId).orElseThrow(() -> new IllegalArgumentException("accepted session is missing"));
        requireExact(session, tenant, ReferenceAssemblyIntakeAction.REQUESTER_ID, requestKey, input, prepared.requirement());
        var expectedReceipt = ReferenceAssemblyIntakeReceipt.artifact(tenant, session.createdBy(), requestKey, input, prepared.requirement());
        require(ReferenceAssemblyIntakeReceipt.exact(artifacts.find(tenant, expectedReceipt.reference()).orElse(null), expectedReceipt),
                "accepted receipt is missing or different");
        var expectedRequirement = prepared.staged().stream().filter(a -> a.reference().equals(prepared.requirement().reference()))
                .findFirst().orElseThrow();
        require(exactArtifact(artifacts.find(tenant, expectedRequirement.reference()).orElse(null), expectedRequirement),
                "accepted requirement is missing or different");
        return session;
    }

    /** Quick pre-duplicate/guard path: ledger metadata and bounded code-owned receipt, no source-tree read. */
    void checkCurrent(TenantId tenant, String createdBy, String requestKey, ReferenceAssemblyIntakeInput input, Instant now) {
        check(tenant, createdBy, requestKey, input, now);
    }

    private Prepared check(TenantId tenant, String createdBy, String requestKey, ReferenceAssemblyIntakeInput input, Instant now) {
        ReferenceAssemblyIntakeInput.boundedText(tenant.value(), "tenant", 128);
        ReferenceAssemblyIntakeInput.boundedText(requestKey, "requestKey", 255);
        require(ReferenceAssemblyIntakeAction.REQUESTER_ID.equals(createdBy), "intake requires the Factory service owner");
        input.requireLiveAt(now);
        Certification certification = certifications.find(tenant, input.certificationId())
                .orElseThrow(() -> new IllegalArgumentException("canonical certification is missing"));
        var lock = certification.inputLock();
        require(tenant.equals(lock.tenantId()) && input.certificationId().equals(certification.certificationId())
                        && certification.status() == CertificationStatus.CERTIFIED && certification.revokedAt().isEmpty()
                        && !now.isBefore(certification.updatedAt())
                        && certification.expiresAt().map(expiry -> now.isBefore(expiry)).orElse(true)
                        && input.sourceHash().equals(lock.candidateHash())
                        && certification.certificationManifest().filter(input.certificationManifest()::equals).isPresent(),
                "component is not the exact current certified selection");
        var component = new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE, lock.productLineId(), lock.artifactType(), certification.certificationId(),
                certification.certificationManifest().orElseThrow(), lock.candidateId(), lock.candidateHash(), lock.sourceManifest(),
                certification.inputLockArtifact(), lock.verificationRunId(), lock.verificationResultManifest(),
                lock.compatibilityDescriptor(), certification.certificationEvidence().orElseThrow(), lock.certificationProfile());
        var staging = new StagingStore();
        var catalog = new ReferenceAssemblyProductLineCatalog(staging, codec);
        var policy = catalog.admissionPolicy(input.catalogEntry());
        require(lock.productContractBundle().equals(policy.expectedAgentProductContract())
                        && lock.apiSignatureIndex().equals(policy.expectedApiSignatureIndex())
                        && lock.certificationProfile().equals(policy.requiredCertificationProfile())
                        && lock.flowerVersion().equals(policy.requiredFlowerVersion())
                        && lock.actionRuntimeVersion().equals(policy.requiredActionRuntimeVersion())
                        && lock.sourceLockAlgorithmId().equals(policy.requiredSourceLockAlgorithmId())
                        && lock.gateProfile().equals(MaintenanceInvestigationProductContract.GATE_PROFILE),
                "certified component does not match the explicit Maintenance consumer entry");
        var requirement = catalog.stageRequirement(tenant, component, input.catalogEntry());
        var receipt = ReferenceAssemblyIntakeReceipt.artifact(tenant, createdBy, requestKey, input, requirement);
        staging.store(receipt);
        var existing = sessions.find(tenant, input.buildSessionId());
        existing.ifPresent(value -> {
            requireExact(value, tenant, createdBy, requestKey, input, requirement);
            require(!now.isBefore(value.updatedAt()), "session clock moved behind the current ledger");
        });
        var priorReceipt = artifacts.find(tenant, receipt.reference());
        if (priorReceipt.isPresent()) {
            require(ReferenceAssemblyIntakeReceipt.exact(priorReceipt.orElseThrow(), receipt), "immutable request key is already bound differently");
            require(existing.isPresent(), "receipt without its accepted session requires reconciliation");
        } else {
            require(existing.isEmpty(), "session without an exact intake receipt requires reconciliation");
        }
        return new Prepared(certification, component, requirement, List.copyOf(staging.values.values()));
    }

    public static void requireExact(BuildSession session, TenantId tenant, String createdBy, String requestKey,
                                    ReferenceAssemblyIntakeInput input, CertificationArtifactLock requirement) {
        require(session.tenantId().equals(tenant) && session.buildSessionId().equals(input.buildSessionId())
                        && session.projectId().equals(input.projectId()) && session.productLineId().equals(ProductLineId.REFERENCE_ASSEMBLY)
                        && session.createdBy().equals(createdBy) && session.requestIdempotencyKey().equals(requestKey)
                        && session.deadlineAt().equals(input.deadlineAt()) && session.maxRepairRounds() == 0 && session.repairRound() == 0
                        && session.requirementsArtifactRef().equals(requirement.reference()) && session.requirementsHash().equals(requirement.hash())
                        && session.selectedManagerWorkerBinding().isEmpty() && session.selectedCodingWorkerBinding().isEmpty()
                        && session.currentCandidateId().isEmpty() && session.currentCertificationId().isEmpty()
                        && session.currentBlueprintRef().isEmpty() && session.currentCandidateHash().isEmpty()
                        && session.cancellationRequestedAt().isEmpty() && session.status() != BuildSessionStatus.CANCELLED,
                "accepted order differs from its immutable Reference Assembly intake identity");
    }

    private static boolean exactArtifact(Artifact actual, Artifact expected) {
        return actual != null && actual.tenantId().equals(expected.tenantId()) && actual.reference().equals(expected.reference())
                && actual.contentHash().equals(expected.contentHash()) && actual.mediaType().equals(expected.mediaType())
                && Arrays.equals(actual.content(), expected.content());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private record Prepared(Certification certification, CertifiedAgentComponentRef component,
                            CertificationArtifactLock requirement, List<Artifact> staged) {}

    /** Invocation-local staging only; no caller-controlled artifact is accepted by this store. */
    private static final class StagingStore implements ArtifactStore {
        private final LinkedHashMap<ArtifactReference, Artifact> values = new LinkedHashMap<>();
        private int bytes;
        public ArtifactReference store(Artifact artifact) {
            var previous = values.get(artifact.reference());
            if (previous != null) {
                require(exactArtifact(previous, artifact), "staging reference collision");
                return artifact.reference();
            }
            require(values.size() < 16 && artifact.content().length <= 256 * 1024
                            && bytes + artifact.content().length <= 512 * 1024,
                    "code-owned staging exceeded its intake bound");
            values.put(artifact.reference(), artifact); bytes += artifact.content().length;
            return artifact.reference();
        }
        public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference)).filter(a -> a.tenantId().equals(tenant));
        }
    }
}
