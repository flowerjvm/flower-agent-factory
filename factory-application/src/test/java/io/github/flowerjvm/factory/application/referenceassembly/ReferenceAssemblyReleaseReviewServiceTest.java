package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
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
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionCheck;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyReleaseReviewServiceTest {
    @Test
    void createsCanonicalSubjectDecisionPointAndBinding() {
        Fixture fixture = new Fixture();

        ReferenceAssemblyReleaseReviewResult result = fixture.service()
                .ensureReleaseReview(fixture.tenant, fixture.assemblyId);

        assertEquals(
                ReferenceAssemblyReleaseReviewResult.Disposition.CREATED_AND_BOUND,
                result.disposition());
        assertEquals(ReferenceAssemblyStatus.INSPECTED, result.referenceAssembly().status());
        assertEquals(result.decisionPoint().decisionPointId(),
                result.referenceAssembly().releaseDecisionPointId().orElseThrow());
        assertEquals(result.releaseSubjectArtifact().hash(),
                result.referenceAssembly().releaseSubjectHash().orElseThrow());
        assertEquals(ReferenceAssemblyReleaseReviewService.DECISION_TYPE,
                result.decisionPoint().type());
        assertEquals(ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE,
                result.decisionPoint().subjectType());
        assertEquals(ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID,
                result.decisionPoint().optionsSchemaId());
        assertEquals(Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION),
                result.decisionPoint().requiredPermissions());
        assertEquals(fixture.session.deadlineAt(), result.decisionPoint().dueAt());
        assertEquals(1, result.decisionPoint().minimumApprovers());
        assertEquals(fixture.policySnapshot.reference(),
                result.decisionPoint().policySnapshotRef());
        assertEquals(fixture.inspected.version(),
                result.releaseSubject().inspectedAssemblyVersion());
        assertEquals(
                ReferenceAssemblyArtifactSupport.RELEASE_SUBJECT_REFERENCE_PREFIX
                        + result.releaseSubjectArtifact().hash().sha256(),
                result.releaseSubjectArtifact().reference().value());
        assertEquals(1, fixture.decisions.createCalls.get());
        assertEquals(1, fixture.assemblies.casCalls.get());
        assertEquals(2, fixture.gate.calls.get());
    }

    @Test
    void crashAfterDecisionCreateBeforeAssemblyCasRecoversExactPointAndBinding() {
        Fixture fixture = new Fixture();
        fixture.assemblies.failNextCasWithoutWrite = true;

        assertCode(
                ReferenceAssemblyReleaseReviewService.REPOSITORY_CONFLICT,
                () -> fixture.service().ensureReleaseReview(fixture.tenant, fixture.assemblyId));
        assertFalse(fixture.assemblies.current.releaseDecisionPointId().isPresent());
        assertEquals(1, fixture.decisions.values.size());

        ReferenceAssemblyReleaseReviewResult recovered = fixture.service(
                        Clock.fixed(Fixture.NOW.plusSeconds(1), ZoneOffset.UTC))
                .ensureReleaseReview(fixture.tenant, fixture.assemblyId);

        assertEquals(
                ReferenceAssemblyReleaseReviewResult.Disposition.RECOVERED_AND_BOUND,
                recovered.disposition());
        assertEquals(1, fixture.decisions.createCalls.get());
        assertEquals(2, fixture.assemblies.casCalls.get());
        assertTrue(recovered.referenceAssembly().releaseDecisionPointId().isPresent());
    }

    @Test
    void concurrentExactBindingThatWinsCasObservationConverges() {
        Fixture fixture = new Fixture();
        fixture.assemblies.writeThenReturnFalse = true;

        ReferenceAssemblyReleaseReviewResult result = fixture.service()
                .ensureReleaseReview(fixture.tenant, fixture.assemblyId);

        assertEquals(
                ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT,
                result.disposition());
        assertEquals(fixture.assemblies.current, result.referenceAssembly());
        assertTrue(result.referenceAssembly().releaseDecisionPointId().isPresent());
    }

    @Test
    void exactRetryReturnsCurrentTerminalDecisionPointWithoutCreatingAnything() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyReleaseReviewResult created = fixture.service()
                .ensureReleaseReview(fixture.tenant, fixture.assemblyId);
        DecisionPoint open = created.decisionPoint();
        DecisionPoint approved = new DecisionPoint(
                open.decisionPointId(),
                open.tenantId(),
                open.buildSessionId(),
                open.type(),
                DecisionPointStatus.APPROVED,
                open.subjectType(),
                open.subjectId(),
                open.subjectVersion(),
                open.subjectHash(),
                open.questionArtifactRef(),
                open.optionsSchemaId(),
                open.requiredPermissions(),
                open.minimumApprovers(),
                open.policySnapshotRef(),
                open.openedAt(),
                open.dueAt(),
                Optional.of(Fixture.NOW.plusSeconds(1)),
                Optional.of(new DecisionId("decision-reference-release-approved")),
                1);
        fixture.decisions.replace(approved);

        ReferenceAssemblyReleaseReviewResult retry = fixture.service(
                        Clock.fixed(Fixture.NOW.plusSeconds(2), ZoneOffset.UTC))
                .ensureReleaseReview(fixture.tenant, fixture.assemblyId);

        assertEquals(
                ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT,
                retry.disposition());
        assertEquals(DecisionPointStatus.APPROVED, retry.decisionPoint().status());
        assertEquals(approved, retry.decisionPoint());
        assertEquals(1, fixture.decisions.createCalls.get());
        assertEquals(1, fixture.assemblies.casCalls.get());
    }

    @Test
    void mismatchedStoredDecisionPointFailsClosed() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyReleaseReviewResult created = fixture.service()
                .ensureReleaseReview(fixture.tenant, fixture.assemblyId);
        DecisionPoint point = created.decisionPoint();
        fixture.decisions.replace(new DecisionPoint(
                point.decisionPointId(),
                point.tenantId(),
                point.buildSessionId(),
                point.type(),
                point.status(),
                point.subjectType(),
                point.subjectId(),
                point.subjectVersion(),
                point.subjectHash(),
                point.questionArtifactRef(),
                point.optionsSchemaId(),
                Set.of("factory.reference-assembly.release.other"),
                point.minimumApprovers(),
                point.policySnapshotRef(),
                point.openedAt(),
                point.dueAt(),
                point.decidedAt(),
                point.terminalDecisionId(),
                point.version()));

        assertCode(
                ReferenceAssemblyReleaseReviewService.DECISION_POINT_CONFLICT,
                () -> fixture.service(Clock.fixed(
                                Fixture.NOW.plusSeconds(1), ZoneOffset.UTC))
                        .ensureReleaseReview(fixture.tenant, fixture.assemblyId));
    }

    @Test
    void staleOrRejectedCurrentCertifiedComponentFailsClosedBeforeReviewCreation() {
        Fixture fixture = new Fixture();
        fixture.gate.failure = new IllegalStateException("certification revoked");

        assertCode(
                ReferenceAssemblyReleaseReviewService.COMPONENT_REJECTED,
                () -> fixture.service().ensureReleaseReview(fixture.tenant, fixture.assemblyId));
        assertEquals(0, fixture.decisions.createCalls.get());
        assertEquals(0, fixture.assemblies.casCalls.get());
    }

    @Test
    void requirementManifestOrInspectionMismatchFailsClosed() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyManifest mismatched = new ReferenceAssemblyManifest(
                ReferenceAssemblyManifest.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID,
                fixture.inspected.requirement(),
                fixture.inspected.consumerContract(),
                fixture.inspected.hostFixture(),
                fixture.lock("different-policy"),
                fixture.component);
        fixture.codec.replaceDecoded(fixture.store.require(fixture.assemblyManifest).content(), mismatched);

        assertCode(
                ReferenceAssemblyReleaseReviewService.GRAPH_MISMATCH,
                () -> fixture.service().ensureReleaseReview(fixture.tenant, fixture.assemblyId));
        assertEquals(0, fixture.decisions.createCalls.get());
    }

    @Test
    void wrongTenantSessionCancellationAndDeadlineAreNotReleaseAuthority() {
        Fixture wrongTenant = new Fixture();
        wrongTenant.sessions.session = wrongTenant.copySession(
                new TenantId("other-tenant"), Optional.empty(), wrongTenant.session.deadlineAt());
        assertCode(
                ReferenceAssemblyReleaseReviewService.SESSION_NOT_ELIGIBLE,
                () -> wrongTenant.service()
                        .ensureReleaseReview(wrongTenant.tenant, wrongTenant.assemblyId));

        Fixture cancelled = new Fixture();
        cancelled.sessions.session = cancelled.copySession(
                cancelled.tenant, Optional.of(Fixture.NOW.minusSeconds(1)),
                cancelled.session.deadlineAt());
        assertCode(
                ReferenceAssemblyReleaseReviewService.SESSION_NOT_ELIGIBLE,
                () -> cancelled.service()
                        .ensureReleaseReview(cancelled.tenant, cancelled.assemblyId));

        Fixture expired = new Fixture();
        assertCode(
                ReferenceAssemblyReleaseReviewService.SESSION_NOT_ELIGIBLE,
                () -> expired.service(Clock.fixed(
                                expired.session.deadlineAt(), ZoneOffset.UTC))
                        .ensureReleaseReview(expired.tenant, expired.assemblyId));
    }

    @Test
    void decisionPointIdUsesLengthPrefixedTenantAssemblyAndSubjectHash() {
        ContentHash subject = hash("same-release-subject");
        DecisionPointId left = ReferenceAssemblyReleaseReviewService.deriveDecisionPointId(
                new TenantId("a"), new ReferenceAssemblyId("bc"), subject);
        DecisionPointId right = ReferenceAssemblyReleaseReviewService.deriveDecisionPointId(
                new TenantId("ab"), new ReferenceAssemblyId("c"), subject);

        assertNotEquals(left, right);
        assertTrue(left.value().startsWith("reference-assembly-release-review-"));
        assertTrue(left.value().length() <= 128);
        assertEquals(
                left,
                ReferenceAssemblyReleaseReviewService.deriveDecisionPointId(
                        new TenantId("a"), new ReferenceAssemblyId("bc"), subject));
    }

    private static void assertCode(String expected, Runnable work) {
        ReferenceAssemblyReleaseReviewException failure = assertThrows(
                ReferenceAssemblyReleaseReviewException.class, work::run);
        assertEquals(expected, failure.code());
    }

    private static final class Fixture {
        private static final Instant NOW = Instant.parse("2026-09-02T03:04:05.123456Z");
        private final TenantId tenant = new TenantId("tenant-reference-release-review");
        private final BuildSessionId buildSessionId =
                new BuildSessionId("build-reference-release-review");
        private final ReferenceAssemblyId assemblyId =
                new ReferenceAssemblyId("reference-assembly-release-review-fixture");
        private final MemoryArtifactStore store = new MemoryArtifactStore();
        private final TestCodec codec = new TestCodec();
        private final CertificationArtifactLock hostFixture =
                stageOpaque("host-fixture", "trusted-host-fixture");
        private final CertificationArtifactLock policySnapshot =
                stageOpaque("policy-snapshot", "trusted-release-policy");
        private final CertificationArtifactLock productContract = lock("agent-product-contract");
        private final CertificationArtifactLock apiSignature = lock("agent-api-signature");
        private final ResolvedCertifiedAgentComponent resolved = resolvedComponent();
        private final CertifiedAgentComponentRef component = resolved.reference();
        private final ReferenceAssemblyConsumerContract contract = consumerContract();
        private final CertificationArtifactLock consumerContract = stageCanonical(
                "consumer-contract", codec.writeConsumerContract(contract));
        private final ReferenceAssemblyRequirement requirement = new ReferenceAssemblyRequirement(
                ReferenceAssemblyRequirement.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                consumerContract,
                hostFixture,
                policySnapshot,
                component);
        private final CertificationArtifactLock requirementLock = stageCanonical(
                "requirement", codec.writeRequirement(requirement));
        private final ReferenceAssemblyManifest manifest = new ReferenceAssemblyManifest(
                ReferenceAssemblyManifest.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID,
                requirementLock,
                consumerContract,
                hostFixture,
                policySnapshot,
                component);
        private final CertificationArtifactLock assemblyManifest =
                new ReferenceAssemblyArtifactSupport(store, codec)
                        .stageManifest(tenant, manifest);
        private final ReferenceAssemblyInspectionReport inspection = inspectionReport();
        private final CertificationArtifactLock inspectionReport =
                new ReferenceAssemblyArtifactSupport(store, codec)
                        .stageInspection(tenant, inspection);
        private final ReferenceAssembly inspected = ReferenceAssembly.requested(
                        assemblyId,
                        tenant,
                        buildSessionId,
                        requirementLock,
                        consumerContract,
                        hostFixture,
                        policySnapshot,
                        component.certificationId(),
                        component.candidateHash(),
                        component.certificationManifest(),
                        NOW.minusSeconds(10))
                .resolveComponent(NOW.minusSeconds(9))
                .assemble(assemblyManifest, NOW.minusSeconds(8))
                .inspect(inspectionReport, NOW.minusSeconds(7));
        private final BuildSession session = session();
        private final MemoryBuildSessionRepository sessions =
                new MemoryBuildSessionRepository(session);
        private final MemoryReferenceAssemblyRepository assemblies =
                new MemoryReferenceAssemblyRepository(inspected);
        private final MemoryDecisionPointRepository decisions =
                new MemoryDecisionPointRepository();
        private final Gate gate = new Gate(resolved);

        private ReferenceAssemblyReleaseReviewService service() {
            return service(Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private ReferenceAssemblyReleaseReviewService service(Clock requestedClock) {
            return new ReferenceAssemblyReleaseReviewService(
                    sessions,
                    assemblies,
                    decisions,
                    store,
                    codec,
                    gate,
                    requestedClock);
        }

        private BuildSession session() {
            return new BuildSession(
                    buildSessionId,
                    tenant,
                    new ProjectId("project-reference-release-review"),
                    ProductLineId.REFERENCE_ASSEMBLY,
                    "request-reference-release-review",
                    "reference-release-reviewer",
                    BuildSessionStatus.WAITING_RELEASE_REVIEW,
                    BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                    requirementLock.reference(),
                    requirementLock.hash(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    0,
                    NOW.minusSeconds(60),
                    NOW.plusSeconds(600),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    7,
                    NOW.minusSeconds(60),
                    NOW.minusSeconds(6));
        }

        private BuildSession copySession(
                TenantId requestedTenant,
                Optional<Instant> cancellation,
                Instant deadline) {
            return new BuildSession(
                    session.buildSessionId(),
                    requestedTenant,
                    session.projectId(),
                    session.productLineId(),
                    session.requestIdempotencyKey(),
                    session.createdBy(),
                    session.status(),
                    session.currentPhase(),
                    session.requirementsArtifactRef(),
                    session.requirementsHash(),
                    session.selectedManagerWorkerBinding(),
                    session.selectedCodingWorkerBinding(),
                    session.currentBlueprintRef(),
                    session.currentCandidateId(),
                    session.currentCandidateHash(),
                    session.currentCertificationId(),
                    session.repairRound(),
                    session.maxRepairRounds(),
                    session.startedAt(),
                    deadline,
                    cancellation,
                    session.terminalCode(),
                    session.terminalMessage(),
                    session.version(),
                    session.createdAt(),
                    session.updatedAt());
        }

        private ReferenceAssemblyConsumerContract consumerContract() {
            return new ReferenceAssemblyConsumerContract(
                    ReferenceAssemblyConsumerContract.SCHEMA_VERSION,
                    ProductLineId.REFERENCE_ASSEMBLY,
                    ReferenceAssemblyConsumerContract.CONTRACT_ID,
                    ReferenceAssemblyConsumerContract.CONTRACT_VERSION,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE,
                    ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                    productContract,
                    apiSignature,
                    hostFixture,
                    ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                    ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                    ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        }

        private ReferenceAssemblyInspectionReport inspectionReport() {
            List<ReferenceAssemblyInspectionCheck> checks =
                    ReferenceAssemblyInspectionReport.REQUIRED_CHECK_IDS.stream()
                            .map(id -> new ReferenceAssemblyInspectionCheck(
                                    id,
                                    true,
                                    "REFERENCE_ASSEMBLY_"
                                            + id.toUpperCase().replace('-', '_')
                                            + "_PASSED"))
                            .toList();
            return new ReferenceAssemblyInspectionReport(
                    ReferenceAssemblyInspectionReport.SCHEMA_VERSION,
                    ProductLineId.REFERENCE_ASSEMBLY,
                    assemblyManifest,
                    consumerContract,
                    hostFixture,
                    policySnapshot,
                    component,
                    checks,
                    ReferenceAssemblyInspectionReport.PASSED);
        }

        private ResolvedCertifiedAgentComponent resolvedComponent() {
            BuildSessionId componentBuild = new BuildSessionId("component-build");
            WorkOrderId workOrder = new WorkOrderId("component-work-order");
            CandidateId candidate = new CandidateId("component-candidate");
            VerificationRunId verification = new VerificationRunId("component-verification");
            CertificationId certificationId = new CertificationId("component-certification");
            CertificationArtifactLock source = lock("component-source");
            CertificationArtifactLock dependency = lock("component-dependency");
            CertificationArtifactLock toolchain = lock("component-toolchain");
            CertificationArtifactLock generationInput = lock("component-generation-input");
            CertificationArtifactLock verificationResult = lock("component-verification-result");
            CertificationArtifactLock compatibilityLock = lock("component-compatibility");
            CertificationArtifactLock inputArtifact = lock("component-input-lock");
            CertificationArtifactLock evidenceLock = lock("component-evidence");
            CertificationArtifactLock certificationManifest = lock("component-manifest");
            ContentHash candidateHash = source.hash();
            ContentHash fixtureHash = hash("component-fixture-set");
            Instant issuedAt = NOW.minusSeconds(120);
            CertificationInputLock input = new CertificationInputLock(
                    CertificationInputLock.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    componentBuild,
                    workOrder,
                    candidate,
                    candidateHash,
                    source,
                    dependency,
                    toolchain,
                    generationInput,
                    productContract,
                    apiSignature,
                    "sha256-ordinal-v1",
                    "internal",
                    verification,
                    "verification-action-run",
                    verificationResult,
                    fixtureHash,
                    policySnapshot,
                    compatibilityLock,
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            AgentPackCompatibilityDescriptor compatibility =
                    new AgentPackCompatibilityDescriptor(
                            AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                            tenant,
                            ProductLineId.AGENT_PACK,
                            CertifiedArtifactType.AGENT_PACK,
                            candidate,
                            candidateHash,
                            productContract,
                            apiSignature,
                            dependency,
                            toolchain,
                            "internal",
                            "sha256-ordinal-v1",
                            "0.2.0",
                            "0.1.3",
                            "0.3.3");
            CertificationEvidenceManifest evidence = new CertificationEvidenceManifest(
                    CertificationEvidenceManifest.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    candidate,
                    candidateHash,
                    inputArtifact.hash(),
                    verification,
                    "verification-action-run",
                    verificationResult.hash(),
                    compatibilityLock.hash(),
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertifiedAgentComponentManifest componentManifest =
                    new CertifiedAgentComponentManifest(
                            CertifiedAgentComponentManifest.SCHEMA_VERSION,
                            tenant,
                            ProductLineId.AGENT_PACK,
                            CertifiedArtifactType.AGENT_PACK,
                            certificationId,
                            candidate,
                            candidateHash,
                            source,
                            inputArtifact,
                            verification,
                            verificationResult,
                            compatibilityLock,
                            evidenceLock,
                            "internal",
                            "0.2.0",
                            issuedAt,
                            NOW.plusSeconds(3600),
                            CertifiedAgentComponentManifest.CERTIFIED_STATUS);
            Certification certification = Certification.requested(
                            certificationId, input, inputArtifact, issuedAt)
                    .certify(
                            certificationManifest,
                            evidenceLock,
                            "certification-action-run",
                            issuedAt.plusSeconds(1),
                            Optional.of(NOW.plusSeconds(3600)));
            CertifiedAgentComponentRef reference = new CertifiedAgentComponentRef(
                    CertifiedAgentComponentRef.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    certificationManifest,
                    candidate,
                    candidateHash,
                    source,
                    inputArtifact,
                    verification,
                    verificationResult,
                    compatibilityLock,
                    evidenceLock,
                    "internal");
            CandidateVersion candidateVersion = new CandidateVersion(
                    candidate,
                    tenant,
                    componentBuild,
                    Optional.empty(),
                    source.reference(),
                    candidateHash,
                    dependency.reference(),
                    dependency.hash(),
                    toolchain.reference(),
                    toolchain.hash(),
                    CandidateVersionStatus.GENERATED,
                    workOrder,
                    issuedAt);
            VerificationRun verificationRun = new VerificationRun(
                    verification,
                    tenant,
                    componentBuild,
                    candidate,
                    candidateHash,
                    "internal",
                    toolchain.hash(),
                    fixtureHash,
                    VerificationRunStatus.PASSED,
                    Optional.of(verificationResult.reference()),
                    Optional.of(verificationResult.hash()),
                    Optional.of("VERIFICATION_PASSED"),
                    Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                    Optional.of(issuedAt),
                    Optional.of(issuedAt.plusSeconds(1)),
                    2,
                    issuedAt,
                    issuedAt.plusSeconds(1));
            return new ResolvedCertifiedAgentComponent(
                    reference,
                    certification,
                    input,
                    evidence,
                    componentManifest,
                    compatibility,
                    candidateVersion,
                    verificationRun);
        }

        private CertificationArtifactLock stageOpaque(String name, String value) {
            return stage(name, "application/octet-stream", value.getBytes(StandardCharsets.UTF_8));
        }

        private CertificationArtifactLock stageCanonical(String name, byte[] content) {
            return stage(name, ReferenceAssemblyArtifactCodec.MEDIA_TYPE, content);
        }

        private CertificationArtifactLock stage(String name, String mediaType, byte[] content) {
            ContentHash contentHash = ReferenceAssemblyArtifactSupport.sha256(content);
            ArtifactReference reference = new ArtifactReference(
                    "test/reference-release-review/" + name + "/sha256/" + contentHash.sha256());
            store.store(new Artifact(tenant, reference, contentHash, mediaType, content));
            return new CertificationArtifactLock(reference, contentHash);
        }

        private CertificationArtifactLock lock(String name) {
            ContentHash contentHash = hash(name);
            return new CertificationArtifactLock(
                    new ArtifactReference("locked/" + name + "/sha256/" + contentHash.sha256()),
                    contentHash);
        }
    }

    private static final class Gate
            implements io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate {
        private final ResolvedCertifiedAgentComponent resolved;
        private final AtomicInteger calls = new AtomicInteger();
        private RuntimeException failure;

        private Gate(ResolvedCertifiedAgentComponent resolved) {
            this.resolved = resolved;
        }

        @Override
        public ResolvedCertifiedAgentComponent resolve(
                TenantId trustedTenantId, CertifiedAgentComponentRef reference) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return resolved;
        }
    }

    private static final class MemoryBuildSessionRepository implements BuildSessionRepository {
        private BuildSession session;

        private MemoryBuildSessionRepository(BuildSession session) {
            this.session = session;
        }

        @Override
        public void create(BuildSession ignored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
            return Optional.ofNullable(session);
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MemoryReferenceAssemblyRepository
            implements ReferenceAssemblyRepository {
        private ReferenceAssembly current;
        private final AtomicInteger casCalls = new AtomicInteger();
        private boolean failNextCasWithoutWrite;
        private boolean writeThenReturnFalse;

        private MemoryReferenceAssemblyRepository(ReferenceAssembly current) {
            this.current = current;
        }

        @Override
        public void create(ReferenceAssembly ignored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReferenceAssembly> find(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            if (current.tenantId().equals(tenantId)
                    && current.referenceAssemblyId().equals(referenceAssemblyId)) {
                return Optional.of(current);
            }
            return Optional.empty();
        }

        @Override
        public Optional<ReferenceAssembly> findByBuildSession(
                TenantId tenantId, BuildSessionId buildSessionId) {
            if (current.tenantId().equals(tenantId)
                    && current.buildSessionId().equals(buildSessionId)) {
                return Optional.of(current);
            }
            return Optional.empty();
        }

        @Override
        public List<ReferenceAssembly> findReleasedByComponentCertification(
                TenantId tenantId, CertificationId componentCertificationId) {
            return List.of();
        }

        @Override
        public boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next) {
            casCalls.incrementAndGet();
            if (failNextCasWithoutWrite) {
                failNextCasWithoutWrite = false;
                return false;
            }
            if (!current.equals(expected)) {
                return false;
            }
            current = next;
            if (writeThenReturnFalse) {
                writeThenReturnFalse = false;
                return false;
            }
            return true;
        }
    }

    private static final class MemoryDecisionPointRepository
            implements DecisionPointRepository {
        private final Map<String, DecisionPoint> values = new LinkedHashMap<>();
        private final AtomicInteger createCalls = new AtomicInteger();

        @Override
        public void create(DecisionPoint point) {
            createCalls.incrementAndGet();
            String key = key(point.tenantId(), point.decisionPointId());
            if (values.putIfAbsent(key, point) != null) {
                throw new IllegalStateException("duplicate DecisionPoint");
            }
        }

        @Override
        public Optional<DecisionPoint> find(
                TenantId tenantId, DecisionPointId decisionPointId) {
            return Optional.ofNullable(values.get(key(tenantId, decisionPointId)));
        }

        @Override
        public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) {
            String key = key(expected.tenantId(), expected.decisionPointId());
            if (!values.get(key).equals(expected)) {
                return false;
            }
            values.put(key, next);
            return true;
        }

        private void replace(DecisionPoint point) {
            values.put(key(point.tenantId(), point.decisionPointId()), point);
        }

        private static String key(TenantId tenantId, DecisionPointId decisionPointId) {
            return tenantId.value() + "\u0000" + decisionPointId.value();
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> values = new LinkedHashMap<>();

        @Override
        public ArtifactReference store(Artifact artifact) {
            String key = key(artifact.tenantId(), artifact.reference());
            Artifact existing = values.putIfAbsent(key, artifact);
            if (existing != null
                    && (!existing.contentHash().equals(artifact.contentHash())
                            || !existing.mediaType().equals(artifact.mediaType())
                            || !Arrays.equals(existing.content(), artifact.content()))) {
                throw new IllegalStateException("artifact conflict");
            }
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(key(tenantId, reference)));
        }

        private Artifact require(CertificationArtifactLock lock) {
            return values.values().stream()
                    .filter(value -> value.reference().equals(lock.reference()))
                    .findFirst()
                    .orElseThrow();
        }

        private static String key(TenantId tenantId, ArtifactReference reference) {
            return tenantId.value() + "\u0000" + reference.value();
        }
    }

    private static final class TestCodec implements ReferenceAssemblyArtifactCodec {
        private final Map<String, Object> decoded = new LinkedHashMap<>();

        @Override
        public byte[] writeRequirement(ReferenceAssemblyRequirement value) {
            return write("requirement", value);
        }

        @Override
        public ReferenceAssemblyRequirement readRequirement(byte[] content) {
            return read("requirement", content, ReferenceAssemblyRequirement.class);
        }

        @Override
        public byte[] writeConsumerContract(ReferenceAssemblyConsumerContract value) {
            return write("consumer-contract", value);
        }

        @Override
        public ReferenceAssemblyConsumerContract readConsumerContract(byte[] content) {
            return read("consumer-contract", content, ReferenceAssemblyConsumerContract.class);
        }

        @Override
        public byte[] writeManifest(ReferenceAssemblyManifest value) {
            return write("manifest", value);
        }

        @Override
        public ReferenceAssemblyManifest readManifest(byte[] content) {
            return read("manifest", content, ReferenceAssemblyManifest.class);
        }

        @Override
        public byte[] writeInspectionReport(ReferenceAssemblyInspectionReport value) {
            return write("inspection", value);
        }

        @Override
        public ReferenceAssemblyInspectionReport readInspectionReport(byte[] content) {
            return read("inspection", content, ReferenceAssemblyInspectionReport.class);
        }

        @Override
        public byte[] writeReleaseSubject(ReferenceAssemblyReleaseSubject value) {
            return write("release-subject", value);
        }

        @Override
        public ReferenceAssemblyReleaseSubject readReleaseSubject(byte[] content) {
            return read("release-subject", content, ReferenceAssemblyReleaseSubject.class);
        }

        @Override
        public byte[] writeReleaseManifest(ReferenceAssemblyReleaseManifest value) {
            return write("release-manifest", value);
        }

        @Override
        public ReferenceAssemblyReleaseManifest readReleaseManifest(byte[] content) {
            return read("release-manifest", content, ReferenceAssemblyReleaseManifest.class);
        }

        private byte[] write(String kind, Object value) {
            byte[] content = (kind + "|" + value).getBytes(StandardCharsets.UTF_8);
            decoded.put(key(content), value);
            return content;
        }

        private <T> T read(String kind, byte[] content, Class<T> type) {
            Object value = decoded.get(key(content));
            if (!type.isInstance(value)
                    || !new String(content, StandardCharsets.UTF_8).startsWith(kind + "|")) {
                throw new IllegalArgumentException("not canonical test content");
            }
            return type.cast(value);
        }

        private void replaceDecoded(byte[] content, Object replacement) {
            decoded.put(key(content), replacement);
        }

        private static String key(byte[] content) {
            return Base64.getEncoder().encodeToString(content);
        }
    }

    private static ContentHash hash(String value) {
        return ReferenceAssemblyArtifactSupport.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
