package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcReferenceAssemblyRepositoryTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:10Z");

    @Test
    void roundTripsTenantScopedLedgerAndAllowsOnlyOneVersionCasWinner() {
        Fixture fixture = Fixture.create("roundtrip");
        fixture.repository().create(fixture.requested());

        assertEquals(
                fixture.requested(),
                fixture.repository()
                        .find(fixture.tenant(), fixture.requested().referenceAssemblyId())
                        .orElseThrow());
        assertEquals(
                fixture.requested(),
                fixture.repository()
                        .findByBuildSession(fixture.tenant(), fixture.session().buildSessionId())
                        .orElseThrow());
        assertTrue(fixture.repository()
                .find(new TenantId("tenant-b"), fixture.requested().referenceAssemblyId())
                .isEmpty());

        ReferenceAssembly resolved = fixture.requested().resolveComponent(CREATED_AT.plusSeconds(1));
        assertTrue(fixture.repository().compareAndSet(fixture.requested(), resolved));
        assertFalse(fixture.repository().compareAndSet(fixture.requested(), resolved));

        ReferenceAssembly backwards = new ReferenceAssembly(
                resolved.referenceAssemblyId(),
                resolved.tenantId(),
                resolved.buildSessionId(),
                resolved.requirement(),
                resolved.consumerContract(),
                resolved.hostFixture(),
                resolved.policySnapshot(),
                resolved.componentCertificationId(),
                resolved.componentCandidateHash(),
                resolved.componentCertificationManifest(),
                ReferenceAssemblyStatus.ASSEMBLED,
                Optional.of(fixture.assemblyManifest()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                resolved.version() + 1,
                resolved.createdAt(),
                CREATED_AT);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.repository().compareAndSet(resolved, backwards));
    }

    @Test
    void persistsStagedOwnersButOrdinaryCasCannotMakeReleaseVisible() throws Exception {
        Fixture fixture = Fixture.create("release");
        fixture.repository().create(fixture.requested());
        ReferenceAssembly resolved = fixture.requested().resolveComponent(CREATED_AT.plusSeconds(1));
        ReferenceAssembly assembled = resolved.assemble(fixture.assemblyManifest(), CREATED_AT.plusSeconds(2));
        ReferenceAssembly inspected = assembled.inspect(fixture.inspectionReport(), CREATED_AT.plusSeconds(3));
        DecisionPoint approvedPoint = fixture.approvedReleasePoint(CREATED_AT.plusSeconds(4));
        ReferenceAssembly reviewBound = inspected.bindReleaseReview(
                approvedPoint.decisionPointId(), approvedPoint.subjectHash(), CREATED_AT.plusSeconds(6));
        String actionRunId = fixture.componentCertification().actionRunId().orElseThrow();
        ReferenceAssembly actionBound = reviewBound.bindReleaseAction(
                actionRunId, CREATED_AT.plusSeconds(7));
        ReferenceAssembly released = actionBound.release(
                fixture.releaseManifest(),
                approvedPoint.decisionPointId(),
                approvedPoint.subjectHash(),
                actionRunId,
                CREATED_AT.plusSeconds(8));

        assertTrue(fixture.repository().compareAndSet(fixture.requested(), resolved));
        assertTrue(fixture.repository().compareAndSet(resolved, assembled));
        assertTrue(fixture.repository().compareAndSet(assembled, inspected));
        assertTrue(fixture.repository().compareAndSet(inspected, reviewBound));

        ReferenceAssembly forgedExpected = new ReferenceAssembly(
                reviewBound.referenceAssemblyId(),
                reviewBound.tenantId(),
                reviewBound.buildSessionId(),
                reviewBound.requirement(),
                reviewBound.consumerContract(),
                reviewBound.hostFixture(),
                reviewBound.policySnapshot(),
                reviewBound.componentCertificationId(),
                reviewBound.componentCandidateHash(),
                reviewBound.componentCertificationManifest(),
                ReferenceAssemblyStatus.INSPECTED,
                reviewBound.assemblyManifest(),
                reviewBound.inspectionReport(),
                Optional.empty(),
                Optional.of(new DecisionPointId("point-forged-review-owner")),
                Optional.of(hash('e')),
                Optional.empty(),
                Optional.empty(),
                reviewBound.version(),
                reviewBound.createdAt(),
                reviewBound.updatedAt());
        ReferenceAssembly forgedNext = forgedExpected.bindReleaseAction(
                actionRunId, CREATED_AT.plusSeconds(7));
        assertFalse(fixture.repository().compareAndSet(forgedExpected, forgedNext));
        assertEquals(reviewBound, fixture.repository()
                .find(fixture.tenant(), fixture.requested().referenceAssemblyId())
                .orElseThrow());

        assertTrue(fixture.repository().compareAndSet(reviewBound, actionBound));

        ReferenceAssembly forgedRejection = new ReferenceAssembly(
                actionBound.referenceAssemblyId(),
                actionBound.tenantId(),
                actionBound.buildSessionId(),
                actionBound.requirement(),
                actionBound.consumerContract(),
                actionBound.hostFixture(),
                actionBound.policySnapshot(),
                actionBound.componentCertificationId(),
                actionBound.componentCandidateHash(),
                actionBound.componentCertificationManifest(),
                ReferenceAssemblyStatus.REJECTED,
                actionBound.assemblyManifest(),
                actionBound.inspectionReport(),
                Optional.empty(),
                actionBound.releaseDecisionPointId(),
                actionBound.releaseSubjectHash(),
                actionBound.releaseActionRunId(),
                Optional.of("REFERENCE_ASSEMBLY_ACTION_REJECTED"),
                actionBound.version() + 1,
                actionBound.createdAt(),
                CREATED_AT.plusSeconds(8));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.repository().compareAndSet(actionBound, forgedRejection));
        assertTrue(fixture.repository().findReleasedByComponentCertification(
                fixture.tenant(), fixture.componentCertification().certificationId()).isEmpty());
        assertTrue(fixture.repository().findReleasedByComponentCertification(
                fixture.tenant(),
                fixture.componentCertification().certificationId(),
                10).isEmpty());

        IllegalArgumentException bypass = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().compareAndSet(actionBound, released));
        assertEquals(
                "RELEASED ReferenceAssembly requires the controlled release transaction",
                bypass.getMessage());
        assertEquals(actionBound, fixture.repository()
                .find(fixture.tenant(), fixture.requested().referenceAssemblyId())
                .orElseThrow());
        assertTrue(fixture.repository().findReleasedByComponentCertification(
                fixture.tenant(), fixture.componentCertification().certificationId()).isEmpty());

        try (var autoCommit = fixture.certificationFixture().dataSource().getConnection()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.repository().compareAndSetReleased(
                            autoCommit, actionBound, released));
        }
        try (var transaction = fixture.certificationFixture().dataSource().getConnection()) {
            transaction.setAutoCommit(false);
            assertTrue(fixture.repository().compareAndSetReleased(
                    transaction, actionBound, released));
            transaction.commit();
        }

        assertEquals(
                java.util.List.of(released),
                fixture.repository().findReleasedByComponentCertification(
                        fixture.tenant(), fixture.componentCertification().certificationId()));
        assertEquals(
                java.util.List.of(released),
                fixture.repository().findReleasedByComponentCertification(
                        fixture.tenant(),
                        fixture.componentCertification().certificationId(),
                        1));
        assertTrue(fixture.repository().findReleasedByComponentCertification(
                new TenantId("tenant-b"), fixture.componentCertification().certificationId()).isEmpty());
        assertTrue(fixture.repository().findReleasedByComponentCertification(
                new TenantId("tenant-b"),
                fixture.componentCertification().certificationId(),
                1).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().findReleasedByComponentCertification(
                        fixture.tenant(),
                        fixture.componentCertification().certificationId(),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().findReleasedByComponentCertification(
                        fixture.tenant(),
                        fixture.componentCertification().certificationId(),
                        1_001));
    }

    @Test
    void createRejectsDuplicateSessionAndNonExactComponentOrArtifactLocks() {
        Fixture fixture = Fixture.create("exact-inputs");
        fixture.repository().create(fixture.requested());

        ReferenceAssembly duplicateSession = ReferenceAssembly.requested(
                new ReferenceAssemblyId("reference-assembly-exact-inputs-duplicate"),
                fixture.tenant(),
                fixture.session().buildSessionId(),
                fixture.requested().requirement(),
                fixture.requested().consumerContract(),
                fixture.requested().hostFixture(),
                fixture.requested().policySnapshot(),
                fixture.requested().componentCertificationId(),
                fixture.requested().componentCandidateHash(),
                fixture.requested().componentCertificationManifest(),
                CREATED_AT);
        assertThrows(DuplicateLedgerRecordException.class,
                () -> fixture.repository().create(duplicateSession));

        Fixture wrongComponent = Fixture.create("wrong-component");
        ReferenceAssembly wrongCandidateHash = ReferenceAssembly.requested(
                wrongComponent.requested().referenceAssemblyId(),
                wrongComponent.tenant(),
                wrongComponent.session().buildSessionId(),
                wrongComponent.requested().requirement(),
                wrongComponent.requested().consumerContract(),
                wrongComponent.requested().hostFixture(),
                wrongComponent.requested().policySnapshot(),
                wrongComponent.requested().componentCertificationId(),
                hash('f'),
                wrongComponent.requested().componentCertificationManifest(),
                CREATED_AT);
        assertThrows(FactoryPersistenceException.class,
                () -> wrongComponent.repository().create(wrongCandidateHash));

        Fixture wrongOrder = Fixture.create("wrong-order");
        ReferenceAssembly differentExistingRequirement = ReferenceAssembly.requested(
                wrongOrder.requested().referenceAssemblyId(),
                wrongOrder.tenant(),
                wrongOrder.session().buildSessionId(),
                wrongOrder.assemblyManifest(),
                wrongOrder.requested().consumerContract(),
                wrongOrder.requested().hostFixture(),
                wrongOrder.requested().policySnapshot(),
                wrongOrder.requested().componentCertificationId(),
                wrongOrder.requested().componentCandidateHash(),
                wrongOrder.requested().componentCertificationManifest(),
                CREATED_AT);
        assertThrows(FactoryPersistenceException.class,
                () -> wrongOrder.repository().create(differentExistingRequirement));

        Fixture cancelling = Fixture.create("cancelling-order");
        BuildSession cancellingSession = cancelling.session().requestCancellation(CREATED_AT);
        assertTrue(new JdbcBuildSessionRepository(cancelling.certificationFixture().dataSource())
                .compareAndSet(cancelling.session(), cancellingSession));
        assertThrows(FactoryPersistenceException.class,
                () -> cancelling.repository().create(cancelling.requested()));
    }

    @Test
    void databaseRejectsPartialStagedReleaseAuthority() throws Exception {
        Fixture fixture = Fixture.create("partial-authority");
        fixture.repository().create(fixture.requested());
        ReferenceAssembly resolved = fixture.requested().resolveComponent(CREATED_AT.plusSeconds(1));
        ReferenceAssembly assembled = resolved.assemble(fixture.assemblyManifest(), CREATED_AT.plusSeconds(2));
        ReferenceAssembly inspected = assembled.inspect(fixture.inspectionReport(), CREATED_AT.plusSeconds(3));
        assertTrue(fixture.repository().compareAndSet(fixture.requested(), resolved));
        assertTrue(fixture.repository().compareAndSet(resolved, assembled));
        assertTrue(fixture.repository().compareAndSet(assembled, inspected));

        try (var connection = fixture.certificationFixture().dataSource().getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE factory_reference_assembly
                        SET release_action_run_id = ?
                        WHERE tenant_id = ? AND reference_assembly_id = ?
                        """)) {
            statement.setString(1, fixture.componentCertification().actionRunId().orElseThrow());
            statement.setString(2, fixture.tenant().value());
            statement.setString(3, fixture.requested().referenceAssemblyId().value());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    record Fixture(
            JdbcCertificationRepositoryTest.Fixture certificationFixture,
            BuildSession session,
            ReferenceAssembly requested,
            CertificationArtifactLock assemblyManifest,
            CertificationArtifactLock inspectionReport,
            CertificationArtifactLock releaseManifest,
            JdbcReferenceAssemblyRepository repository) {

        static Fixture create(String suffix) {
            return create(suffix, CREATED_AT.plusSeconds(3600));
        }

        static Fixture create(String suffix, Instant deadline) {
            JdbcCertificationRepositoryTest.Fixture certificationFixture =
                    JdbcCertificationRepositoryTest.Fixture.create("reference-" + suffix);
            return create(certificationFixture, suffix, deadline);
        }

        static Fixture create(DataSource dataSource, String suffix) {
            return create(dataSource, suffix, CREATED_AT.plusSeconds(3600));
        }

        static Fixture create(DataSource dataSource, String suffix, Instant deadline) {
            JdbcCertificationRepositoryTest.Fixture certificationFixture =
                    JdbcCertificationRepositoryTest.Fixture.create(
                            dataSource, "reference-" + suffix);
            return create(certificationFixture, suffix, deadline);
        }

        private static Fixture create(
                JdbcCertificationRepositoryTest.Fixture certificationFixture,
                String suffix, Instant deadline) {
            certificationFixture.certifications().create(certificationFixture.requested());
            Certification componentCertification = certificationFixture.certified();
            if (!certificationFixture.certifications().compareAndSet(
                    certificationFixture.requested(), componentCertification)) {
                throw new AssertionError("could not certify component fixture");
            }

            CertificationArtifactLock requirement = store(
                    certificationFixture, "artifact:reference-requirement:" + suffix, "requirement");
            CertificationArtifactLock consumerContract = store(
                    certificationFixture, "artifact:reference-consumer:" + suffix, "consumer");
            CertificationArtifactLock hostFixture = store(
                    certificationFixture, "artifact:reference-host:" + suffix, "host");
            CertificationArtifactLock policySnapshot = store(
                    certificationFixture, "artifact:reference-policy:" + suffix, "policy");
            CertificationArtifactLock assemblyManifest = store(
                    certificationFixture, "artifact:reference-assembly-manifest:" + suffix, "assembly");
            CertificationArtifactLock inspectionReport = store(
                    certificationFixture, "artifact:reference-inspection:" + suffix, "inspection");
            CertificationArtifactLock releaseManifest = store(
                    certificationFixture, "artifact:reference-release:" + suffix, "release");

            BuildSession session = referenceSession(
                    suffix, certificationFixture.tenant(), requirement, deadline);
            new JdbcBuildSessionRepository(certificationFixture.dataSource()).create(session);

            ReferenceAssembly requested = ReferenceAssembly.requested(
                    new ReferenceAssemblyId("reference-assembly-" + suffix),
                    certificationFixture.tenant(),
                    session.buildSessionId(),
                    requirement,
                    consumerContract,
                    hostFixture,
                    policySnapshot,
                    componentCertification.certificationId(),
                    componentCertification.inputLock().candidateHash(),
                    componentCertification.certificationManifest().orElseThrow(),
                    CREATED_AT);
            return new Fixture(
                    certificationFixture,
                    session,
                    requested,
                    assemblyManifest,
                    inspectionReport,
                    releaseManifest,
                    new JdbcReferenceAssemblyRepository(certificationFixture.dataSource()));
        }

        TenantId tenant() {
            return certificationFixture.tenant();
        }

        Certification componentCertification() {
            return certificationFixture.certifications()
                    .find(certificationFixture.tenant(), certificationFixture.certificationId())
                    .orElseThrow();
        }

        DecisionPoint approvedReleasePoint(Instant openedAt) {
            ContentHash subjectHash = hash('9');
            DecisionPoint open = new DecisionPoint(
                    new DecisionPointId("point-" + requested.referenceAssemblyId().value()),
                    tenant(),
                    session.buildSessionId(),
                    "CERTIFIED_BUNDLE_RELEASE_REVIEW",
                    DecisionPointStatus.OPEN,
                    "REFERENCE_ASSEMBLY",
                    requested.referenceAssemblyId().value(),
                    requested.version(),
                    subjectHash,
                    new ArtifactReference("artifact:release-question:" + requested.referenceAssemblyId().value()),
                    "reference-assembly-release-options-v1",
                    Set.of("factory.reference-assembly.release"),
                    1,
                    requested.policySnapshot().reference(),
                    openedAt,
                    openedAt.plusSeconds(60),
                    Optional.empty(),
                    Optional.empty(),
                    0);
            JdbcDecisionPointRepository points =
                    new JdbcDecisionPointRepository(certificationFixture.dataSource());
            points.create(open);
            Decision decision = new Decision(
                    new DecisionId("decision-" + requested.referenceAssemblyId().value()),
                    tenant(),
                    open.decisionPointId(),
                    "approve-" + requested.referenceAssemblyId().value(),
                    DecisionOutcome.APPROVE,
                    Optional.of("release"),
                    Optional.empty(),
                    "reviewer-a",
                    new ArtifactReference("artifact:authority:" + requested.referenceAssemblyId().value()),
                    subjectHash,
                    openedAt.plusSeconds(1));
            new JdbcDecisionRepository(certificationFixture.dataSource()).create(decision);
            DecisionPoint approved = open.decide(decision, openedAt.plusSeconds(1));
            if (!points.compareAndSet(open, approved)) {
                throw new AssertionError("could not approve release point fixture");
            }
            return approved;
        }

        private static CertificationArtifactLock store(
                JdbcCertificationRepositoryTest.Fixture fixture,
                String reference,
                String content) {
            return fixture.store(
                    reference,
                    "application/json",
                    content.getBytes(StandardCharsets.UTF_8));
        }

        private static BuildSession referenceSession(
                String suffix,
                TenantId tenantId,
                CertificationArtifactLock requirement, Instant deadline) {
            return new BuildSession(
                    new BuildSessionId("build-reference-" + suffix),
                    tenantId,
                    new ProjectId("project-reference-" + suffix),
                    ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                    "request-reference-" + suffix,
                    "principal-a",
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.UNDERSTAND_CUSTOMER,
                    requirement.reference(),
                    requirement.hash(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    3,
                    CREATED_AT.minusSeconds(1),
                    deadline,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    CREATED_AT.minusSeconds(1),
                    CREATED_AT.minusSeconds(1));
        }
    }

    private static CertificationArtifactLock lock(String reference, char hash) {
        return new CertificationArtifactLock(new ArtifactReference(reference), hash(hash));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
