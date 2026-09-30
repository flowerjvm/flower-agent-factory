package io.github.flowerjvm.factory.application.decision;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DecisionSubjectAuthorityTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(300);
    private static final ContentHash SUBJECT_HASH = hash('a');
    private static final String PERMISSION = "factory.reference-assembly.release.approve";

    @Test
    void agentPackAuthorityPreservesExactReleaseReviewCandidateBoundary() {
        BuildSession session = session(
                "agent",
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(new CandidateId("candidate-agent")),
                Optional.of(SUBJECT_HASH),
                Optional.empty(),
                lock("agent-requirement", '1'));
        DecisionPoint point = point(
                new DecisionPointId("point-agent"),
                session,
                DecisionPoint.RELEASE_REVIEW_TYPE,
                AgentPackReleaseReviewDecisionSubjectAuthority.SUBJECT_TYPE,
                "candidate-agent",
                7,
                SUBJECT_HASH,
                "factory.agent.release.approve");
        var authority = new AgentPackReleaseReviewDecisionSubjectAuthority();

        assertDoesNotThrow(() -> authority.validateCurrentSubject(TENANT, session, point));

        DecisionPoint changedHash = point(
                point.decisionPointId(),
                session,
                point.type(),
                point.subjectType(),
                point.subjectId(),
                point.subjectVersion(),
                hash('b'),
                "factory.agent.release.approve");
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                TENANT, session, changedHash));

        BuildSession wrongLine = session(
                "agent",
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                session.currentCandidateId(),
                session.currentCandidateHash(),
                Optional.empty(),
                lock("agent-requirement", '1'));
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                TENANT, wrongLine, point));
    }

    @Test
    void referenceAssemblyAuthorityRequiresExactBoundSnapshotVersionAndSessionState() {
        ReferenceFixture fixture = referenceFixture("authority");
        AtomicReference<ReferenceAssembly> canonical =
                new AtomicReference<>(fixture.reviewBound());
        var authority = new ReferenceAssemblyReleaseDecisionSubjectAuthority(
                referenceAssemblies(canonical));

        assertDoesNotThrow(() -> authority.validateCurrentSubject(
                TENANT, fixture.session(), fixture.point()));
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                new TenantId("tenant-b"), fixture.session(), fixture.point()));

        DecisionPoint wrongPoint = copyPoint(
                fixture.point(),
                new DecisionPointId("point-reference-authority-forged"),
                fixture.point().type(),
                fixture.point().subjectVersion(),
                fixture.point().subjectHash());
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                TENANT, fixture.session(), wrongPoint));

        DecisionPoint wrongHash = copyPoint(
                fixture.point(),
                fixture.point().decisionPointId(),
                fixture.point().type(),
                fixture.point().subjectVersion(),
                hash('c'));
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                TENANT, fixture.session(), wrongHash));

        DecisionPoint wrongVersion = copyPoint(
                fixture.point(),
                fixture.point().decisionPointId(),
                fixture.point().type(),
                fixture.point().subjectVersion() + 1,
                fixture.point().subjectHash());
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                TENANT, fixture.session(), wrongVersion));

        canonical.set(fixture.reviewBound().bindReleaseAction(
                "release-action-run", NOW.plusSeconds(6)));
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                TENANT, fixture.session(), fixture.point()));

        canonical.set(fixture.reviewBound());
        BuildSession wrongPhase = session(
                "authority",
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.PACKAGE_RELEASE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                fixture.reviewBound().requirement());
        assertSubjectChanged(() -> authority.validateCurrentSubject(
                TENANT, wrongPhase, fixture.point()));
    }

    @Test
    void collectionRoutesBothProductLinesAndUnknownOrDuplicateTypesFailClosed() {
        ReferenceFixture fixture = referenceFixture("routing");
        AtomicInteger transactionCalls = new AtomicInteger();
        DecisionRecordingTransaction transaction = (session, point, decision, decided) -> {
            transactionCalls.incrementAndGet();
            return DecisionRecordingDisposition.APPLIED;
        };
        ReferenceAssemblyReleaseDecisionSubjectAuthority referenceAuthority =
                new ReferenceAssemblyReleaseDecisionSubjectAuthority(
                        referenceAssemblies(new AtomicReference<>(fixture.reviewBound())));
        DecisionRecordingService service = service(
                fixture.session(),
                fixture.point(),
                transaction,
                List.of(
                        new AgentPackReleaseReviewDecisionSubjectAuthority(),
                        referenceAuthority));

        DecisionRecordingResult applied = service.record(
                decision(fixture.point(), "request-reference"),
                context(PERMISSION));

        assertEquals(DecisionRecordingDisposition.APPLIED, applied.disposition());
        assertEquals(1, transactionCalls.get());

        DecisionPoint unknown = copyPoint(
                fixture.point(),
                fixture.point().decisionPointId(),
                "UNREGISTERED_REVIEW",
                fixture.point().subjectVersion(),
                fixture.point().subjectHash());
        DecisionRecordingService unknownService = service(
                fixture.session(), unknown, transaction, List.of(referenceAuthority));
        IllegalArgumentException unavailable = assertThrows(
                IllegalArgumentException.class,
                () -> unknownService.record(
                        decision(unknown, "request-unknown"), context(PERMISSION)));
        assertEquals("DECISION_SUBJECT_AUTHORITY_UNAVAILABLE", unavailable.getMessage());
        assertEquals(1, transactionCalls.get());

        DecisionSubjectAuthority duplicate = new DecisionSubjectAuthority() {
            @Override
            public String decisionType() {
                return referenceAuthority.decisionType();
            }

            @Override
            public void validateCurrentSubject(
                    TenantId tenantId, BuildSession session, DecisionPoint point) {}
        };
        IllegalArgumentException duplicateFailure = assertThrows(
                IllegalArgumentException.class,
                () -> service(
                        fixture.session(),
                        fixture.point(),
                        transaction,
                        List.of(referenceAuthority, duplicate)));
        assertEquals(
                "DECISION_SUBJECT_AUTHORITY_DUPLICATE: "
                        + ReferenceAssemblyReleaseReviewService.DECISION_TYPE,
                duplicateFailure.getMessage());
    }

    @Test
    void existingConstructorRetainsAgentPackDefaultAuthority() {
        BuildSession session = session(
                "default-agent",
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(new CandidateId("candidate-default")),
                Optional.of(SUBJECT_HASH),
                Optional.empty(),
                lock("default-agent-requirement", '2'));
        DecisionPoint point = point(
                new DecisionPointId("point-default-agent"),
                session,
                DecisionPoint.RELEASE_REVIEW_TYPE,
                AgentPackReleaseReviewDecisionSubjectAuthority.SUBJECT_TYPE,
                "candidate-default",
                1,
                SUBJECT_HASH,
                "factory.agent.release.approve");
        DecisionRecordingTransaction transaction =
                (currentSession, currentPoint, decision, decided) ->
                        DecisionRecordingDisposition.APPLIED;
        DecisionRecordingService service = new DecisionRecordingService(
                buildSessions(session),
                decisionPoints(point),
                decisions(),
                transaction);

        DecisionRecordingResult result = service.record(
                decision(point, "request-default-agent"),
                context("factory.agent.release.approve"));

        assertEquals(DecisionRecordingDisposition.APPLIED, result.disposition());
    }

    private static DecisionRecordingService service(
            BuildSession session,
            DecisionPoint point,
            DecisionRecordingTransaction transaction,
            Collection<? extends DecisionSubjectAuthority> authorities) {
        return new DecisionRecordingService(
                buildSessions(session),
                decisionPoints(point),
                decisions(),
                transaction,
                authorities);
    }

    private static BuildSessionRepository buildSessions(BuildSession canonical) {
        return new BuildSessionRepository() {
            @Override
            public void create(BuildSession session) {}

            @Override
            public Optional<BuildSession> find(
                    TenantId tenantId, BuildSessionId buildSessionId) {
                return canonical.tenantId().equals(tenantId)
                                && canonical.buildSessionId().equals(buildSessionId)
                        ? Optional.of(canonical)
                        : Optional.empty();
            }

            @Override
            public boolean compareAndSet(BuildSession expected, BuildSession next) {
                return false;
            }
        };
    }

    private static DecisionPointRepository decisionPoints(DecisionPoint canonical) {
        return new DecisionPointRepository() {
            @Override
            public void create(DecisionPoint point) {}

            @Override
            public Optional<DecisionPoint> find(
                    TenantId tenantId, DecisionPointId decisionPointId) {
                return canonical.tenantId().equals(tenantId)
                                && canonical.decisionPointId().equals(decisionPointId)
                        ? Optional.of(canonical)
                        : Optional.empty();
            }

            @Override
            public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) {
                return false;
            }
        };
    }

    private static DecisionRepository decisions() {
        return new DecisionRepository() {
            @Override
            public void create(Decision decision) {}

            @Override
            public Optional<Decision> find(TenantId tenantId, DecisionId decisionId) {
                return Optional.empty();
            }

            @Override
            public Optional<Decision> findByRequestIdempotencyKey(
                    TenantId tenantId,
                    DecisionPointId decisionPointId,
                    String requestIdempotencyKey) {
                return Optional.empty();
            }
        };
    }

    private static ReferenceAssemblyRepository referenceAssemblies(
            AtomicReference<ReferenceAssembly> canonical) {
        return new ReferenceAssemblyRepository() {
            @Override
            public void create(ReferenceAssembly assembly) {}

            @Override
            public Optional<ReferenceAssembly> find(
                    TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
                ReferenceAssembly current = canonical.get();
                return current.tenantId().equals(tenantId)
                                && current.referenceAssemblyId().equals(referenceAssemblyId)
                        ? Optional.of(current)
                        : Optional.empty();
            }

            @Override
            public Optional<ReferenceAssembly> findByBuildSession(
                    TenantId tenantId, BuildSessionId buildSessionId) {
                return Optional.empty();
            }

            @Override
            public List<ReferenceAssembly> findReleasedByComponentCertification(
                    TenantId tenantId, CertificationId componentCertificationId) {
                return List.of();
            }

            @Override
            public boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next) {
                return false;
            }
        };
    }

    private static ReferenceFixture referenceFixture(String suffix) {
        CertificationArtifactLock requirement = lock("requirement-" + suffix, '1');
        BuildSession session = session(
                suffix,
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                requirement);
        ReferenceAssembly inspected = ReferenceAssembly.requested(
                        new ReferenceAssemblyId("reference-assembly-" + suffix),
                        TENANT,
                        session.buildSessionId(),
                        requirement,
                        lock("consumer-" + suffix, '2'),
                        lock("host-" + suffix, '3'),
                        lock("policy-" + suffix, '4'),
                        new CertificationId("certification-" + suffix),
                        hash('5'),
                        lock("component-" + suffix, '5'),
                        NOW)
                .resolveComponent(NOW.plusSeconds(1))
                .assemble(lock("assembly-" + suffix, '6'), NOW.plusSeconds(2))
                .inspect(lock("inspection-" + suffix, '7'), NOW.plusSeconds(3));
        DecisionPointId pointId = new DecisionPointId("point-reference-" + suffix);
        DecisionPoint point = new DecisionPoint(
                pointId,
                TENANT,
                session.buildSessionId(),
                ReferenceAssemblyReleaseReviewService.DECISION_TYPE,
                DecisionPointStatus.OPEN,
                ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE,
                inspected.referenceAssemblyId().value(),
                inspected.version(),
                SUBJECT_HASH,
                new ArtifactReference("artifact:reference-release-subject:" + suffix),
                ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID,
                Set.of(PERMISSION),
                1,
                inspected.policySnapshot().reference(),
                NOW,
                DEADLINE,
                Optional.empty(),
                Optional.empty(),
                0);
        ReferenceAssembly reviewBound = inspected.bindReleaseReview(
                pointId, SUBJECT_HASH, NOW.plusSeconds(4));
        return new ReferenceFixture(session, point, reviewBound);
    }

    private static BuildSession session(
            String suffix,
            ProductLineId productLineId,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Optional<CandidateId> candidateId,
            Optional<ContentHash> candidateHash,
            Optional<Instant> cancellationRequestedAt,
            CertificationArtifactLock requirement) {
        return new BuildSession(
                new BuildSessionId("build-" + suffix),
                TENANT,
                new ProjectId("project-" + suffix),
                productLineId,
                "request-" + suffix,
                "principal-a",
                status,
                phase,
                requirement.reference(),
                requirement.hash(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                candidateId,
                candidateHash,
                Optional.empty(),
                0,
                3,
                NOW.minusSeconds(1),
                DEADLINE,
                cancellationRequestedAt,
                Optional.empty(),
                Optional.empty(),
                4,
                NOW.minusSeconds(1),
                NOW);
    }

    private static DecisionPoint point(
            DecisionPointId decisionPointId,
            BuildSession session,
            String type,
            String subjectType,
            String subjectId,
            long subjectVersion,
            ContentHash subjectHash,
            String permission) {
        return new DecisionPoint(
                decisionPointId,
                TENANT,
                session.buildSessionId(),
                type,
                DecisionPointStatus.OPEN,
                subjectType,
                subjectId,
                subjectVersion,
                subjectHash,
                new ArtifactReference("artifact:decision-subject:" + decisionPointId.value()),
                DecisionPoint.RELEASE_REVIEW_TYPE.equals(type)
                        ? AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID : "factory.decision-options.v1",
                Set.of(permission),
                1,
                new ArtifactReference("artifact:decision-policy:" + decisionPointId.value()),
                NOW,
                DEADLINE,
                Optional.empty(),
                Optional.empty(),
                0);
    }

    private static DecisionPoint copyPoint(
            DecisionPoint source,
            DecisionPointId decisionPointId,
            String type,
            long subjectVersion,
            ContentHash subjectHash) {
        return new DecisionPoint(
                decisionPointId,
                source.tenantId(),
                source.buildSessionId(),
                type,
                source.status(),
                source.subjectType(),
                source.subjectId(),
                subjectVersion,
                subjectHash,
                source.questionArtifactRef(),
                source.optionsSchemaId(),
                source.requiredPermissions(),
                source.minimumApprovers(),
                source.policySnapshotRef(),
                source.openedAt(),
                source.dueAt(),
                source.decidedAt(),
                source.terminalDecisionId(),
                source.version());
    }

    private static Decision decision(DecisionPoint point, String requestKey) {
        return new Decision(
                new DecisionId("decision-" + requestKey),
                TENANT,
                point.decisionPointId(),
                requestKey,
                DecisionOutcome.APPROVE,
                Optional.of("release"),
                Optional.empty(),
                "reviewer-a",
                new ArtifactReference("artifact:untrusted-authority"),
                point.subjectHash(),
                NOW);
    }

    private static DecisionRequestContext context(String permission) {
        return new DecisionRequestContext(
                TENANT,
                NOW.plusSeconds(10),
                "reviewer-a",
                Set.of(permission),
                new ArtifactReference("artifact:trusted-authority"));
    }

    private static CertificationArtifactLock lock(String name, char hash) {
        return new CertificationArtifactLock(
                new ArtifactReference("artifact:" + name), hash(hash));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }

    private static void assertSubjectChanged(org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, executable);
        assertEquals("DECISION_SUBJECT_CHANGED", failure.getMessage());
    }

    private record ReferenceFixture(
            BuildSession session, DecisionPoint point, ReferenceAssembly reviewBound) {}
}
