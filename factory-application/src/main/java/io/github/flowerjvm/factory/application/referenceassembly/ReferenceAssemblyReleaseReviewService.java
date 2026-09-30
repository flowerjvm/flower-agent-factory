package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Opens and reconciles the hash-bound human review for one inspected Reference Assembly. */
public final class ReferenceAssemblyReleaseReviewService {
    public static final String DECISION_TYPE = "REFERENCE_ASSEMBLY_RELEASE_REVIEW";
    public static final String SUBJECT_TYPE = "REFERENCE_ASSEMBLY_RELEASE";
    public static final String OPTIONS_SCHEMA_ID =
            "factory.reference-assembly-release-review-options.v1";
    public static final String REQUIRED_PERMISSION =
            "factory.reference-assembly.release.approve";

    public static final String ASSEMBLY_NOT_FOUND =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_ASSEMBLY_NOT_FOUND";
    public static final String ASSEMBLY_NOT_ELIGIBLE =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_ASSEMBLY_NOT_ELIGIBLE";
    public static final String SESSION_NOT_FOUND =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_SESSION_NOT_FOUND";
    public static final String SESSION_NOT_ELIGIBLE =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_SESSION_NOT_ELIGIBLE";
    public static final String ARTIFACT_INVALID =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_ARTIFACT_INVALID";
    public static final String GRAPH_MISMATCH =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_GRAPH_MISMATCH";
    public static final String COMPONENT_REJECTED =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_COMPONENT_REJECTED";
    public static final String DECISION_POINT_CONFLICT =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_DECISION_POINT_CONFLICT";
    public static final String REPOSITORY_CONFLICT =
            "REFERENCE_ASSEMBLY_RELEASE_REVIEW_REPOSITORY_CONFLICT";

    private static final String DECISION_ID_PREFIX = "reference-assembly-release-review-";

    private final BuildSessionRepository buildSessions;
    private final ReferenceAssemblyRepository referenceAssemblies;
    private final DecisionPointRepository decisionPoints;
    private final ReferenceAssemblyArtifactSupport artifactSupport;
    private final CertifiedAgentComponentReadGate componentReadGate;
    private final ReferenceAssemblyAdmissionPolicies admissionPolicies;
    private final Clock clock;

    public ReferenceAssemblyReleaseReviewService(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository referenceAssemblies,
            DecisionPointRepository decisionPoints,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate,
            Clock clock) {
        this(buildSessions, referenceAssemblies, decisionPoints, artifacts, codec, componentReadGate, null, clock);
    }

    public ReferenceAssemblyReleaseReviewService(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository referenceAssemblies,
            DecisionPointRepository decisionPoints,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate,
            ReferenceAssemblyAdmissionPolicies admissionPolicies,
            Clock clock) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.referenceAssemblies = Objects.requireNonNull(
                referenceAssemblies, "referenceAssemblies");
        this.decisionPoints = Objects.requireNonNull(decisionPoints, "decisionPoints");
        this.artifactSupport = new ReferenceAssemblyArtifactSupport(artifacts, codec);
        this.componentReadGate = Objects.requireNonNull(componentReadGate, "componentReadGate");
        this.admissionPolicies = admissionPolicies;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Uses only the caller-authenticated tenant and durable assembly identity as authority. */
    public ReferenceAssemblyReleaseReviewResult ensureReleaseReview(
            TenantId trustedTenantId, ReferenceAssemblyId referenceAssemblyId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReferenceAssembly assembly = findAssembly(trustedTenantId, referenceAssemblyId);
        requireEligibleAssembly(trustedTenantId, referenceAssemblyId, assembly, observedAt);
        BuildSession session = findSession(trustedTenantId, assembly);
        requireEligibleSession(trustedTenantId, assembly, session, observedAt);

        if (assembly.releaseDecisionPointId().isPresent()) {
            return observeBound(
                    trustedTenantId,
                    assembly,
                    observedAt,
                    ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT);
        }
        validateStoredGraph(trustedTenantId, assembly);

        ReferenceAssemblyReleaseSubject subject = releaseSubject(assembly, assembly.version());
        CertificationArtifactLock subjectArtifact = stageSubject(trustedTenantId, subject);
        DecisionPointId decisionPointId = deriveDecisionPointId(
                trustedTenantId, referenceAssemblyId, subjectArtifact.hash());
        DecisionPointObservation pointObservation = ensureDecisionPoint(
                trustedTenantId,
                assembly,
                session,
                observedAt,
                subject,
                subjectArtifact,
                decisionPointId);

        ReferenceAssembly latest = findAssembly(trustedTenantId, referenceAssemblyId);
        if (latest.releaseDecisionPointId().isPresent()) {
            return observeBound(
                    trustedTenantId,
                    latest,
                    observedAt,
                    ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT);
        }
        if (!latest.equals(assembly)) {
            throw conflict(
                    "Reference Assembly changed after its exact release subject was staged");
        }
        BuildSession latestSession = findSession(trustedTenantId, latest);
        requireEligibleSession(trustedTenantId, latest, latestSession, observedAt);
        requireExactDecisionPoint(
                pointObservation.decisionPoint(),
                trustedTenantId,
                latest,
                latestSession,
                observedAt,
                subject,
                subjectArtifact,
                decisionPointId);

        ReferenceAssembly bound;
        try {
            bound = assembly.bindReleaseReview(
                    decisionPointId, subjectArtifact.hash(), observedAt);
        } catch (RuntimeException invalidTransition) {
            throw fail(
                    ASSEMBLY_NOT_ELIGIBLE,
                    "Reference Assembly cannot bind the exact release review",
                    invalidTransition);
        }

        boolean boundByThisCall;
        try {
            boundByThisCall = referenceAssemblies.compareAndSet(assembly, bound);
        } catch (RuntimeException repositoryFailure) {
            ReferenceAssembly afterFailure = findAssembly(trustedTenantId, referenceAssemblyId);
            if (hasExactBinding(afterFailure, decisionPointId, subjectArtifact.hash())) {
                return observeBound(
                        trustedTenantId,
                        afterFailure,
                        observedAt,
                        ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT);
            }
            throw fail(
                    REPOSITORY_CONFLICT,
                    "Reference Assembly release-review binding CAS failed closed",
                    repositoryFailure);
        }

        ReferenceAssembly canonical = findAssembly(trustedTenantId, referenceAssemblyId);
        if (!boundByThisCall) {
            if (hasExactBinding(canonical, decisionPointId, subjectArtifact.hash())) {
                return observeBound(
                        trustedTenantId,
                        canonical,
                        observedAt,
                        ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT);
            }
            throw conflict("Reference Assembly release-review binding CAS lost to a mismatch");
        }
        if (!canonical.equals(bound)) {
            throw conflict("Reference Assembly binding CAS did not expose its exact next snapshot");
        }
        ReferenceAssemblyReleaseReviewResult.Disposition disposition =
                pointObservation.createdByThisCall()
                        ? ReferenceAssemblyReleaseReviewResult.Disposition.CREATED_AND_BOUND
                        : ReferenceAssemblyReleaseReviewResult.Disposition.RECOVERED_AND_BOUND;
        return observeBound(
                trustedTenantId,
                canonical,
                observedAt,
                disposition);
    }

    private ReferenceAssemblyReleaseReviewResult observeBound(
            TenantId tenantId,
            ReferenceAssembly assembly,
            Instant observedAt,
            ReferenceAssemblyReleaseReviewResult.Disposition disposition) {
        requireEligibleAssembly(
                tenantId, assembly.referenceAssemblyId(), assembly, observedAt);
        BuildSession session = findSession(tenantId, assembly);
        requireEligibleSession(tenantId, assembly, session, observedAt);
        validateStoredGraph(tenantId, assembly);
        DecisionPointId pointId = assembly.releaseDecisionPointId()
                .orElseThrow(() -> conflict("Reference Assembly release review is not bound"));
        ContentHash subjectHash = assembly.releaseSubjectHash()
                .orElseThrow(() -> conflict("Reference Assembly release subject is not bound"));
        DecisionPoint point = findDecisionPoint(tenantId, pointId)
                .orElseThrow(() -> pointConflict(
                        "bound release DecisionPoint is missing in tenant scope"));
        CertificationArtifactLock subjectArtifact = new CertificationArtifactLock(
                point.questionArtifactRef(), subjectHash);
        ReferenceAssemblyReleaseSubject subject = readSubject(tenantId, subjectArtifact);
        requireExactSubject(assembly, subject, true);
        requireExactDecisionPoint(
                point,
                tenantId,
                assembly,
                session,
                observedAt,
                subject,
                subjectArtifact,
                pointId);
        return new ReferenceAssemblyReleaseReviewResult(
                disposition, assembly, point, subject, subjectArtifact);
    }

    private DecisionPointObservation ensureDecisionPoint(
            TenantId tenantId,
            ReferenceAssembly assembly,
            BuildSession session,
            Instant observedAt,
            ReferenceAssemblyReleaseSubject subject,
            CertificationArtifactLock subjectArtifact,
            DecisionPointId decisionPointId) {
        Optional<DecisionPoint> existing = findDecisionPoint(tenantId, decisionPointId);
        if (existing.isPresent()) {
            DecisionPoint point = existing.orElseThrow();
            requireExactDecisionPoint(
                    point,
                    tenantId,
                    assembly,
                    session,
                    observedAt,
                    subject,
                    subjectArtifact,
                    decisionPointId);
            return new DecisionPointObservation(point, false);
        }

        DecisionPoint requested = new DecisionPoint(
                decisionPointId,
                tenantId,
                assembly.buildSessionId(),
                DECISION_TYPE,
                DecisionPointStatus.OPEN,
                SUBJECT_TYPE,
                assembly.referenceAssemblyId().value(),
                subject.inspectedAssemblyVersion(),
                subjectArtifact.hash(),
                subjectArtifact.reference(),
                OPTIONS_SCHEMA_ID,
                Set.of(REQUIRED_PERMISSION),
                1,
                assembly.policySnapshot().reference(),
                observedAt,
                session.deadlineAt(),
                Optional.empty(),
                Optional.empty(),
                0);
        RuntimeException createFailure = null;
        boolean createReturned = false;
        try {
            decisionPoints.create(requested);
            createReturned = true;
        } catch (RuntimeException possibleRaceOrFailure) {
            createFailure = possibleRaceOrFailure;
        }
        Optional<DecisionPoint> observed = findDecisionPoint(tenantId, decisionPointId);
        if (observed.isEmpty()) {
            throw fail(
                    REPOSITORY_CONFLICT,
                    "release DecisionPoint create did not expose a canonical row",
                    createFailure);
        }
        DecisionPoint point = observed.orElseThrow();
        requireExactDecisionPoint(
                point,
                tenantId,
                assembly,
                session,
                observedAt,
                subject,
                subjectArtifact,
                decisionPointId);
        return new DecisionPointObservation(point, createReturned && point.equals(requested));
    }

    private void validateStoredGraph(TenantId tenantId, ReferenceAssembly assembly) {
        ReferenceAssemblyRequirement requirement;
        io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract contract;
        ReferenceAssemblyManifest manifest;
        ReferenceAssemblyInspectionReport inspection;
        try {
            requirement = artifactSupport.readRequirement(tenantId, assembly.requirement());
            contract = artifactSupport.readConsumerContract(tenantId, requirement.consumerContract());
            artifactSupport.readOpaque(tenantId, requirement.hostFixture());
            artifactSupport.readOpaque(tenantId, requirement.policySnapshot());
            CertificationArtifactLock manifestLock = assembly.assemblyManifest().orElseThrow();
            CertificationArtifactLock inspectionLock = assembly.inspectionReport().orElseThrow();
            manifest = artifactSupport.readManifest(tenantId, manifestLock);
            inspection = artifactSupport.readInspectionReport(tenantId, inspectionLock);
        } catch (RuntimeException invalidArtifact) {
            throw fail(
                    ARTIFACT_INVALID,
                    "Reference Assembly release-review graph failed its exact artifact read gate",
                    invalidArtifact);
        }

        CertifiedAgentComponentRef component = requirement.component();
        if (!requirement.consumerContract().equals(assembly.consumerContract())
                || !requirement.hostFixture().equals(assembly.hostFixture())
                || !requirement.policySnapshot().equals(assembly.policySnapshot())
                || !component.certificationId().equals(assembly.componentCertificationId())
                || !component.candidateHash().equals(assembly.componentCandidateHash())
                || !component.certificationManifest()
                        .equals(assembly.componentCertificationManifest())
                || !manifest.requirement().equals(assembly.requirement())
                || !manifest.consumerContract().equals(assembly.consumerContract())
                || !manifest.hostFixture().equals(assembly.hostFixture())
                || !manifest.policySnapshot().equals(assembly.policySnapshot())
                || !manifest.component().equals(component)
                || !inspection.assemblyManifest()
                        .equals(assembly.assemblyManifest().orElseThrow())
                || !inspection.consumerContract().equals(assembly.consumerContract())
                || !inspection.hostFixture().equals(assembly.hostFixture())
                || !inspection.policySnapshot().equals(assembly.policySnapshot())
                || !inspection.component().equals(component)
                || !ReferenceAssemblyInspectionReport.PASSED.equals(inspection.status())) {
            throw fail(
                    GRAPH_MISMATCH,
                    "stored Reference Assembly release-review graph differs from its durable locks");
        }

        ResolvedCertifiedAgentComponent resolved;
        try {
            resolved = Objects.requireNonNull(
                    componentReadGate.resolve(tenantId, component), "resolved component");
            if (admissionPolicies != null) {
                ReferenceAssemblyAssembler.requireCompatibility(tenantId, requirement, contract, resolved,
                        admissionPolicies.requireApproved(requirement));
            } else if (!io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract
                    .CONTRACT_VERSION.equals(contract.contractVersion())) {
                throw new IllegalStateException("versioned Reference Assembly review requires an exact admission catalog");
            }
        } catch (RuntimeException rejected) {
            throw fail(
                    COMPONENT_REJECTED,
                    "current Certified Agent component failed its strict read gate",
                    rejected);
        }
        if (!resolved.reference().equals(component)
                || !resolved.inputLock().tenantId().equals(tenantId)
                || !resolved.compatibilityDescriptor().tenantId().equals(tenantId)) {
            throw fail(
                    COMPONENT_REJECTED,
                    "current Certified Agent component differs from the exact assembly reference");
        }
    }

    private static ReferenceAssemblyReleaseSubject releaseSubject(
            ReferenceAssembly assembly, long inspectedVersion) {
        return new ReferenceAssemblyReleaseSubject(
                ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                assembly.referenceAssemblyId(),
                inspectedVersion,
                assembly.assemblyManifest().orElseThrow(),
                assembly.inspectionReport().orElseThrow(),
                assembly.componentCertificationId(),
                assembly.componentCandidateHash(),
                assembly.componentCertificationManifest(),
                assembly.policySnapshot());
    }

    private CertificationArtifactLock stageSubject(
            TenantId tenantId, ReferenceAssemblyReleaseSubject subject) {
        try {
            return artifactSupport.stageReleaseSubject(tenantId, subject);
        } catch (RuntimeException invalid) {
            throw fail(
                    ARTIFACT_INVALID,
                    "Reference Assembly release subject could not be staged exactly",
                    invalid);
        }
    }

    private ReferenceAssemblyReleaseSubject readSubject(
            TenantId tenantId, CertificationArtifactLock lock) {
        try {
            return artifactSupport.readReleaseSubject(tenantId, lock);
        } catch (RuntimeException invalid) {
            throw fail(
                    ARTIFACT_INVALID,
                    "bound Reference Assembly release subject is not canonical",
                    invalid);
        }
    }

    private static void requireExactSubject(
            ReferenceAssembly assembly,
            ReferenceAssemblyReleaseSubject subject,
            boolean assemblyIsBound) {
        long expectedAssemblyVersion = subject.inspectedAssemblyVersion()
                + (assemblyIsBound ? 1 : 0)
                + (assembly.releaseActionRunId().isPresent() ? 1 : 0);
        if (!subject.referenceAssemblyId().equals(assembly.referenceAssemblyId())
                || !ProductLineId.REFERENCE_ASSEMBLY.equals(subject.productLineId())
                || !subject.assemblyManifest()
                        .equals(assembly.assemblyManifest().orElseThrow())
                || !subject.inspectionReport()
                        .equals(assembly.inspectionReport().orElseThrow())
                || !subject.componentCertificationId()
                        .equals(assembly.componentCertificationId())
                || !subject.componentCandidateHash().equals(assembly.componentCandidateHash())
                || !subject.componentCertificationManifest()
                        .equals(assembly.componentCertificationManifest())
                || !subject.policySnapshot().equals(assembly.policySnapshot())
                || assembly.version() != expectedAssemblyVersion) {
            throw pointConflict(
                    "release subject differs from the exact inspected assembly snapshot");
        }
    }

    private static void requireExactDecisionPoint(
            DecisionPoint point,
            TenantId tenantId,
            ReferenceAssembly assembly,
            BuildSession session,
            Instant observedAt,
            ReferenceAssemblyReleaseSubject subject,
            CertificationArtifactLock subjectArtifact,
            DecisionPointId expectedId) {
        DecisionPointId derivedId = deriveDecisionPointId(
                tenantId, assembly.referenceAssemblyId(), subjectArtifact.hash());
        ArtifactReference canonicalQuestion = new ArtifactReference(
                ReferenceAssemblyArtifactSupport.RELEASE_SUBJECT_REFERENCE_PREFIX
                        + subjectArtifact.hash().sha256());
        boolean lifecycleVersionValid = point.status() == DecisionPointStatus.OPEN
                ? point.version() == 0
                : point.version() >= 1;
        if (!expectedId.equals(derivedId)
                || !point.decisionPointId().equals(expectedId)
                || !point.tenantId().equals(tenantId)
                || !point.buildSessionId().equals(assembly.buildSessionId())
                || !DECISION_TYPE.equals(point.type())
                || !SUBJECT_TYPE.equals(point.subjectType())
                || !point.subjectId().equals(assembly.referenceAssemblyId().value())
                || point.subjectVersion() != subject.inspectedAssemblyVersion()
                || !point.subjectHash().equals(subjectArtifact.hash())
                || !point.questionArtifactRef().equals(subjectArtifact.reference())
                || !point.questionArtifactRef().equals(canonicalQuestion)
                || !OPTIONS_SCHEMA_ID.equals(point.optionsSchemaId())
                || !point.requiredPermissions().equals(Set.of(REQUIRED_PERMISSION))
                || point.minimumApprovers() != 1
                || !point.policySnapshotRef().equals(assembly.policySnapshot().reference())
                || !point.dueAt().equals(session.deadlineAt())
                || point.openedAt().isBefore(assembly.createdAt())
                || !point.openedAt().isBefore(point.dueAt())
                || point.openedAt().isAfter(observedAt)
                || !point.openedAt().equals(point.openedAt().truncatedTo(ChronoUnit.MICROS))
                || !lifecycleVersionValid) {
            throw pointConflict(
                    "stored release DecisionPoint differs from the exact canonical review");
        }
    }

    private ReferenceAssembly findAssembly(
            TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
        Optional<ReferenceAssembly> found;
        try {
            found = referenceAssemblies.find(tenantId, referenceAssemblyId);
        } catch (RuntimeException repositoryFailure) {
            throw fail(
                    REPOSITORY_CONFLICT,
                    "Reference Assembly lookup failed closed",
                    repositoryFailure);
        }
        if (found.isEmpty()) {
            throw fail(ASSEMBLY_NOT_FOUND, "trusted Reference Assembly is missing");
        }
        return found.orElseThrow();
    }

    private BuildSession findSession(TenantId tenantId, ReferenceAssembly assembly) {
        Optional<BuildSession> found;
        try {
            found = buildSessions.find(tenantId, assembly.buildSessionId());
        } catch (RuntimeException repositoryFailure) {
            throw fail(
                    REPOSITORY_CONFLICT,
                    "Reference Assembly BuildSession lookup failed closed",
                    repositoryFailure);
        }
        if (found.isEmpty()) {
            throw fail(SESSION_NOT_FOUND, "trusted Reference Assembly BuildSession is missing");
        }
        return found.orElseThrow();
    }

    private Optional<DecisionPoint> findDecisionPoint(
            TenantId tenantId, DecisionPointId decisionPointId) {
        try {
            return decisionPoints.find(tenantId, decisionPointId);
        } catch (RuntimeException repositoryFailure) {
            throw fail(
                    REPOSITORY_CONFLICT,
                    "release DecisionPoint lookup failed closed",
                    repositoryFailure);
        }
    }

    private static void requireEligibleAssembly(
            TenantId tenantId,
            ReferenceAssemblyId referenceAssemblyId,
            ReferenceAssembly assembly,
            Instant observedAt) {
        boolean reviewPair = assembly.releaseDecisionPointId().isPresent()
                == assembly.releaseSubjectHash().isPresent();
        if (!assembly.tenantId().equals(tenantId)
                || !assembly.referenceAssemblyId().equals(referenceAssemblyId)
                || assembly.status() != ReferenceAssemblyStatus.INSPECTED
                || assembly.assemblyManifest().isEmpty()
                || assembly.inspectionReport().isEmpty()
                || assembly.releaseManifest().isPresent()
                || !reviewPair
                || observedAt.isBefore(assembly.updatedAt())) {
            throw fail(
                    ASSEMBLY_NOT_ELIGIBLE,
                    "Reference Assembly is not an exact INSPECTED release-review authority");
        }
    }

    private static void requireEligibleSession(
            TenantId tenantId,
            ReferenceAssembly assembly,
            BuildSession session,
            Instant observedAt) {
        CertificationArtifactLock sessionRequirement = new CertificationArtifactLock(
                session.requirementsArtifactRef(), session.requirementsHash());
        if (!session.tenantId().equals(tenantId)
                || !session.buildSessionId().equals(assembly.buildSessionId())
                || !ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())
                || session.status() != BuildSessionStatus.WAITING_RELEASE_REVIEW
                || session.currentPhase() != BuildSessionPhase.HUMAN_RELEASE_REVIEW
                || session.cancellationRequestedAt().isPresent()
                || !sessionRequirement.equals(assembly.requirement())
                || observedAt.isBefore(session.updatedAt())
                || !observedAt.isBefore(session.deadlineAt())) {
            throw fail(
                    SESSION_NOT_ELIGIBLE,
                    "BuildSession is not an exact live Reference Assembly release-review authority");
        }
    }

    private static boolean hasExactBinding(
            ReferenceAssembly assembly, DecisionPointId decisionPointId, ContentHash subjectHash) {
        return assembly.status() == ReferenceAssemblyStatus.INSPECTED
                && assembly.releaseDecisionPointId().filter(decisionPointId::equals).isPresent()
                && assembly.releaseSubjectHash().filter(subjectHash::equals).isPresent();
    }

    static DecisionPointId deriveDecisionPointId(
            TenantId tenantId,
            ReferenceAssemblyId referenceAssemblyId,
            ContentHash subjectHash) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        Objects.requireNonNull(subjectHash, "subjectHash");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, tenantId.value());
            updateLengthPrefixed(digest, referenceAssemblyId.value());
            updateLengthPrefixed(digest, subjectHash.sha256());
            return new DecisionPointId(
                    DECISION_ID_PREFIX + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static ReferenceAssemblyReleaseReviewException conflict(String message) {
        return fail(REPOSITORY_CONFLICT, message);
    }

    private static ReferenceAssemblyReleaseReviewException pointConflict(String message) {
        return fail(DECISION_POINT_CONFLICT, message);
    }

    private static ReferenceAssemblyReleaseReviewException fail(
            String code, String message) {
        return new ReferenceAssemblyReleaseReviewException(code, message);
    }

    private static ReferenceAssemblyReleaseReviewException fail(
            String code, String message, Throwable cause) {
        return new ReferenceAssemblyReleaseReviewException(code, message, cause);
    }

    private record DecisionPointObservation(
            DecisionPoint decisionPoint, boolean createdByThisCall) {
        private DecisionPointObservation {
            Objects.requireNonNull(decisionPoint, "decisionPoint");
        }
    }
}
