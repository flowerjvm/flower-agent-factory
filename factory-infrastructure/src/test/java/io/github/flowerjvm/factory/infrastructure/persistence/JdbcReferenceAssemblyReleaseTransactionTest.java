package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDeadlines;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchRunner;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchOperationIds;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.infrastructure.referenceassembly.JacksonReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcReferenceAssemblyReleaseTransactionTest {
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:10Z");

    @Test
    void fractionalMicrosecondBusinessDeadlineSurvivesJdbcActionReadbackAndFinalCommit() {
        Instant deadline = BASE.plusSeconds(300).plusNanos(123_456_000);
        Fixture fixture = Fixture.create("fractional-deadline", deadline);
        assertFractionalDeadlineCommit(fixture, deadline);
    }

    static void assertFractionalDeadlineCommit(Fixture fixture, Instant deadline) {
        JdbcRunStore reloadedStore = new JdbcRunStore(fixture.dataSource(), new ObjectMapper());
        ActionRun action = reloadedStore.find(fixture.actionRunId()).orElseThrow();
        Instant transportDeadline = deadline.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(deadline, fixture.intent().deadlineAt());
        assertEquals(deadline, fixture.sessions().find(fixture.tenant(), fixture.session().buildSessionId()).orElseThrow().deadlineAt());
        assertEquals(deadline, new JdbcDecisionPointRepository(fixture.dataSource())
                .find(fixture.tenant(), fixture.intent().releaseDecisionPointId()).orElseThrow().dueAt());
        assertEquals(transportDeadline, action.dueAt());
        assertEquals(transportDeadline.toString(), action.result().output().get("dueAt"));
        assertTrue(ReferenceAssemblyReleaseDispatchRunner.hasWaitingOwnerBinding(fixture.intent(), action));
        var committed = fixture.transactionAt(BASE.plusSeconds(11).plusNanos(987_654_321))
                .commit(fixture.intent(), fixture.expected(), fixture.proposed());
        assertTrue(committed.committedNow());
        assertEquals(fixture.proposed(), committed.referenceAssembly());
        assertEquals(deadline, fixture.intents().find(fixture.intent().operationId()).orElseThrow().deadlineAt());
        assertEquals(deadline, fixture.sessions().find(fixture.tenant(), fixture.session().buildSessionId()).orElseThrow().deadlineAt());
        assertFalse(fixture.transactionAt(BASE.plusSeconds(12)).commit(
                fixture.intent(), fixture.expected(), fixture.proposed()).committedNow());
    }

    @Test
    void roundedUpTransportDeadlineAndUnroundedDeferredReceiptAreRejectedExactly() {
        Instant deadline = BASE.plusSeconds(300).plusNanos(123_456_000);
        Fixture wrongDeadline = Fixture.create("fractional-wrong-due", deadline);
        var wrongStore = new JdbcRunStore(wrongDeadline.dataSource(), new ObjectMapper());
        ActionRun current = wrongStore.find(wrongDeadline.actionRunId()).orElseThrow();
        assertTrue(wrongStore.compareAndSet(current, current.toBuilder().version(current.version() + 1)
                .dueAt(current.dueAt().plusMillis(1)).build()));
        assertThrows(IllegalStateException.class, () -> wrongDeadline.transactionAt(BASE.plusSeconds(11))
                .commit(wrongDeadline.intent(), wrongDeadline.expected(), wrongDeadline.proposed()));

        Fixture wrongReceipt = Fixture.create("frac-wrong-receipt", deadline);
        wrongStore = new JdbcRunStore(wrongReceipt.dataSource(), new ObjectMapper());
        current = wrongStore.find(wrongReceipt.actionRunId()).orElseThrow();
        ActionExecutionResult receipt = current.result();
        var forged = new ActionExecutionResult(receipt.status(), receipt.code(), receipt.message(),
                Map.of("runId", wrongReceipt.actionRunId(), "operationId", wrongReceipt.intent().operationId(),
                        "dueAt", deadline.toString()), receipt.retryDisposition());
        assertTrue(wrongStore.compareAndSet(current, current.toBuilder().version(current.version() + 1)
                .result(forged).build()));
        assertThrows(IllegalStateException.class, () -> wrongReceipt.transactionAt(BASE.plusSeconds(11))
                .commit(wrongReceipt.intent(), wrongReceipt.expected(), wrongReceipt.proposed()));
    }

    @Test
    void canonicalTransportWindowNeverExtendsIntoTheRemainingBusinessMicroseconds() {
        Instant deadline = BASE.plusSeconds(300).plusNanos(123_456_000);
        Fixture fixture = Fixture.create("fractional-cutoff", deadline);
        Instant actionDeadline = ReferenceAssemblyReleaseDeadlines.actionDueAt(deadline);
        assertTrue(actionDeadline.isBefore(deadline));
        assertThrows(IllegalStateException.class, () -> fixture.transactionAt(actionDeadline)
                .commit(fixture.intent(), fixture.expected(), fixture.proposed()));
        assertEquals(fixture.expected(), fixture.assemblies().find(
                fixture.tenant(), fixture.expected().referenceAssemblyId()).orElseThrow());
    }

    @Test
    void commitsOnlyThroughReleaseCasAndExactRetryConverges() {
        Fixture fixture = Fixture.create("commit");

        ReferenceAssemblyReleaseTransaction.ReleaseCommit first =
                fixture.transactionAt(BASE.plusSeconds(11)).commit(
                        fixture.intent(), fixture.expected(), fixture.proposed());
        ReferenceAssemblyReleaseTransaction.ReleaseCommit retry =
                fixture.transactionAt(BASE.plusSeconds(12)).commit(
                        fixture.intent(), fixture.expected(), fixture.proposed());

        assertTrue(first.committedNow());
        assertEquals(fixture.proposed(), first.referenceAssembly());
        assertFalse(retry.committedNow());
        assertEquals(fixture.proposed(), retry.referenceAssembly());
        assertEquals(
                fixture.proposed(),
                fixture.assemblies()
                        .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                        .orElseThrow());
    }

    @Test
    void releaseFencesAStaleBuildSessionCancellationSnapshot() {
        Fixture fixture = Fixture.create("session-fence");
        BuildSession staleSession = fixture.session();
        BuildSession staleCancellation =
                staleSession.requestCancellation(BASE.plusSeconds(12));

        fixture.transactionAt(BASE.plusSeconds(11)).commit(
                fixture.intent(), fixture.expected(), fixture.proposed());

        assertFalse(fixture.sessions().compareAndSet(staleSession, staleCancellation));
        BuildSession canonical = fixture.sessions()
                .find(fixture.tenant(), staleSession.buildSessionId())
                .orElseThrow();
        assertEquals(staleSession.version() + 1, canonical.version());
        assertEquals(staleSession.status(), canonical.status());
        assertTrue(canonical.cancellationRequestedAt().isEmpty());
    }

    @Test
    void componentRemainsIndependentlyRevocableAfterRelease() {
        Fixture fixture = Fixture.create("post-revoke");

        fixture.transactionAt(BASE.plusSeconds(11)).commit(
                fixture.intent(), fixture.expected(), fixture.proposed());

        Certification certified = fixture.certifications()
                .find(fixture.tenant(), fixture.component().certificationId())
                .orElseThrow();
        Certification revoked = certified.revoke(
                "COMPONENT_REVOKED_AFTER_RELEASE", BASE.plusSeconds(12));
        assertTrue(fixture.certifications().compareAndSet(certified, revoked));
        assertEquals(
                revoked,
                fixture.certifications()
                        .find(fixture.tenant(), certified.certificationId())
                        .orElseThrow());
        assertEquals(
                fixture.proposed(),
                fixture.assemblies()
                        .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                        .orElseThrow());
    }

    @Test
    void releaseFencesAStaleActionCancellationSnapshot() {
        Fixture fixture = Fixture.create("action-fence");
        JdbcRunStore runs = new JdbcRunStore(fixture.dataSource(), new ObjectMapper());
        ActionRun staleAction = runs.find(fixture.actionRunId()).orElseThrow();
        ActionRun staleCancellation = staleAction.toBuilder()
                .version(staleAction.version() + 1)
                .status(ActionRunStatus.CANCELLED)
                .result(ActionExecutionResult.cancelled(
                        "FACTORY_CANCELLATION_REQUESTED",
                        "stale cancellation formed before release"))
                .updatedAt(BASE.plusSeconds(12))
                .build();

        fixture.transactionAt(BASE.plusSeconds(11)).commit(
                fixture.intent(), fixture.expected(), fixture.proposed());

        assertFalse(runs.compareAndSet(staleAction, staleCancellation));
        ActionRun canonical = runs.find(fixture.actionRunId()).orElseThrow();
        assertEquals(staleAction.version() + 1, canonical.version());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, canonical.status());
    }

    @Test
    void releaseFailsClosedWhenSessionCancellationWinsBeforeCommit() {
        Fixture fixture = Fixture.create("cancel-wins");
        BuildSession cancelling = fixture.session().requestCancellation(BASE.plusSeconds(11));
        assertTrue(fixture.sessions().compareAndSet(fixture.session(), cancelling));

        assertThrows(
                IllegalStateException.class,
                () -> fixture.transactionAt(BASE.plusSeconds(12)).commit(
                        fixture.intent(), fixture.expected(), fixture.proposed()));
        assertEquals(
                fixture.expected(),
                fixture.assemblies()
                        .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                        .orElseThrow());
    }

    @Test
    void releaseFailsClosedWhenComponentRevocationWinsBeforeCommit() {
        Fixture fixture = Fixture.create("revoke-wins");
        Certification certified = fixture.component();
        Certification revoked = certified.revoke(
                "COMPONENT_REVOKED", BASE.plusSeconds(11));
        assertTrue(fixture.certifications().compareAndSet(certified, revoked));

        assertThrows(
                IllegalStateException.class,
                () -> fixture.transactionAt(BASE.plusSeconds(12)).commit(
                        fixture.intent(), fixture.expected(), fixture.proposed()));
        assertEquals(
                fixture.expected(),
                fixture.assemblies()
                        .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                        .orElseThrow());
    }

    @Test
    void forgedReleasedSnapshotIsRejectedBeforeAnyLedgerMutation() {
        Fixture fixture = Fixture.create("forged-proposed");
        ReferenceAssembly canonical = fixture.proposed();
        ReferenceAssembly forged = new ReferenceAssembly(
                canonical.referenceAssemblyId(),
                canonical.tenantId(),
                canonical.buildSessionId(),
                canonical.requirement(),
                canonical.consumerContract(),
                canonical.hostFixture(),
                canonical.policySnapshot(),
                canonical.componentCertificationId(),
                hash('f'),
                canonical.componentCertificationManifest(),
                ReferenceAssemblyStatus.RELEASED,
                canonical.assemblyManifest(),
                canonical.inspectionReport(),
                canonical.releaseManifest(),
                canonical.releaseDecisionPointId(),
                canonical.releaseSubjectHash(),
                canonical.releaseActionRunId(),
                Optional.empty(),
                canonical.version(),
                canonical.createdAt(),
                canonical.updatedAt());

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.transactionAt(BASE.plusSeconds(11)).commit(
                        fixture.intent(), fixture.expected(), forged));

        ContentHash unstagedHash = hash('6');
        CertificationArtifactLock canonicalLookingButUnstaged = new CertificationArtifactLock(
                new ArtifactReference(
                        "factory-reference-assembly/release-manifest/sha256/"
                                + unstagedHash.sha256()),
                unstagedHash);
        ReferenceAssembly fabricatedManifest = fixture.expected().release(
                canonicalLookingButUnstaged,
                fixture.intent().releaseDecisionPointId(),
                fixture.intent().releaseSubjectHash(),
                fixture.intent().actionRunId(),
                fixture.proposed().updatedAt());
        assertThrows(
                IllegalStateException.class,
                () -> fixture.transactionAt(BASE.plusSeconds(11)).commit(
                        fixture.intent(), fixture.expected(), fabricatedManifest));
        assertEquals(
                fixture.expected(),
                fixture.assemblies()
                        .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                        .orElseThrow());
    }

    @Test
    void wrongWaitingActionOrStaleClaimedIntentFailsClosed() throws Exception {
        Fixture wrongAction = Fixture.create("wrong-action");
        try (var connection = wrongAction.dataSource().getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE action_run SET external_operation_id = ? WHERE run_id = ?
                        """)) {
            statement.setString(1, "reference-assembly-release:"
                    + "0".repeat(64));
            statement.setString(2, wrongAction.actionRunId());
            assertEquals(1, statement.executeUpdate());
        }
        assertThrows(
                IllegalStateException.class,
                () -> wrongAction.transactionAt(BASE.plusSeconds(11)).commit(
                        wrongAction.intent(), wrongAction.expected(), wrongAction.proposed()));

        Fixture staleIntent = Fixture.create("stale-intent");
        Instant reclaimedAt = staleIntent.intent().leaseUntil().orElseThrow();
        ReferenceAssemblyReleaseDispatchIntent reclaimed = staleIntent.intent().claim(
                "replacement-claim", reclaimedAt, Duration.ofMinutes(5));
        assertTrue(staleIntent.intents().compareAndSet(staleIntent.intent(), reclaimed));
        assertThrows(
                IllegalStateException.class,
                () -> staleIntent.transactionAt(reclaimedAt.plusSeconds(1)).commit(
                        staleIntent.intent(), staleIntent.expected(), staleIntent.proposed()));
        assertEquals(
                staleIntent.expected(),
                staleIntent.assemblies()
                        .find(staleIntent.tenant(), staleIntent.expected().referenceAssemblyId())
                        .orElseThrow());
    }

    @Test
    void nonCanonicalDeferredActionReceiptFailsClosed() throws Exception {
        Fixture fixture = Fixture.create("bad-receipt");
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE action_run SET result_code = ? WHERE run_id = ?
                        """)) {
            statement.setString(1, "FORGED_DEFERRED_RECEIPT");
            statement.setString(2, fixture.actionRunId());
            assertEquals(1, statement.executeUpdate());
        }

        assertThrows(
                IllegalStateException.class,
                () -> fixture.transactionAt(BASE.plusSeconds(11)).commit(
                        fixture.intent(), fixture.expected(), fixture.proposed()));
        assertEquals(
                fixture.expected(),
                fixture.assemblies()
                        .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                        .orElseThrow());
    }

    record Fixture(
            JdbcReferenceAssemblyRepositoryTest.Fixture base,
            JdbcBuildSessionRepository sessions,
            JdbcReferenceAssemblyRepository assemblies,
            JdbcCertificationRepository certifications,
            JdbcReferenceAssemblyReleaseDispatchIntentRepository intents,
            BuildSession session,
            Certification component,
            String actionRunId,
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            ReferenceAssembly proposed) {

        static Fixture create(String suffix) {
            var base = JdbcReferenceAssemblyRepositoryTest.Fixture.create("release-tx-" + suffix);
            return create(base, suffix);
        }

        static Fixture create(String suffix, Instant deadline) {
            return create(JdbcReferenceAssemblyRepositoryTest.Fixture.create("release-tx-" + suffix, deadline), suffix);
        }

        static Fixture create(DataSource dataSource, String suffix) {
            var base = JdbcReferenceAssemblyRepositoryTest.Fixture.create(
                    dataSource, "release-tx-" + suffix);
            return create(base, suffix);
        }

        static Fixture create(DataSource dataSource, String suffix, Instant deadline) {
            return create(JdbcReferenceAssemblyRepositoryTest.Fixture.create(dataSource, "release-tx-" + suffix, deadline), suffix);
        }

        private static Fixture create(
                JdbcReferenceAssemblyRepositoryTest.Fixture base,
                String suffix) {
            var sessions = new JdbcBuildSessionRepository(base.certificationFixture().dataSource());
            var assemblies = base.repository();
            var certifications = base.certificationFixture().certifications();
            var intents = new JdbcReferenceAssemblyReleaseDispatchIntentRepository(
                    base.certificationFixture().dataSource());
            Certification component = base.componentCertification();

            assemblies.create(base.requested());
            ReferenceAssembly resolved = base.requested().resolveComponent(BASE.plusSeconds(1));
            ReferenceAssembly assembled = resolved.assemble(
                    base.assemblyManifest(), BASE.plusSeconds(2));
            ReferenceAssembly inspected = assembled.inspect(
                    base.inspectionReport(), BASE.plusSeconds(3));
            requireCas(assemblies.compareAndSet(base.requested(), resolved));
            requireCas(assemblies.compareAndSet(resolved, assembled));
            requireCas(assemblies.compareAndSet(assembled, inspected));

            BuildSession resolving = base.session().advanceReferenceAssemblyPhase(
                    BuildSessionPhase.UNDERSTAND_CUSTOMER,
                    BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                    BASE.plusSeconds(1));
            BuildSession assembling = resolving.advanceReferenceAssemblyPhase(
                    BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                    BuildSessionPhase.ASSEMBLE_CANDIDATE,
                    BASE.plusSeconds(2));
            BuildSession testing = assembling.advanceReferenceAssemblyPhase(
                    BuildSessionPhase.ASSEMBLE_CANDIDATE,
                    BuildSessionPhase.TEST,
                    BASE.plusSeconds(3));
            BuildSession reviewing = testing.awaitReferenceAssemblyReleaseReview(
                    BASE.plusSeconds(4));
            requireCas(sessions.compareAndSet(base.session(), resolving));
            requireCas(sessions.compareAndSet(resolving, assembling));
            requireCas(sessions.compareAndSet(assembling, testing));
            requireCas(sessions.compareAndSet(testing, reviewing));

            ReferenceAssemblyReleaseSubject releaseSubject =
                    new ReferenceAssemblyReleaseSubject(
                            ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                            ProductLineId.REFERENCE_ASSEMBLY,
                            inspected.referenceAssemblyId(),
                            inspected.version(),
                            inspected.assemblyManifest().orElseThrow(),
                            inspected.inspectionReport().orElseThrow(),
                            inspected.componentCertificationId(),
                            inspected.componentCandidateHash(),
                            inspected.componentCertificationManifest(),
                            inspected.policySnapshot());
            byte[] subjectBytes = new JacksonReferenceAssemblyArtifactCodec()
                    .writeReleaseSubject(releaseSubject);
            ContentHash subjectHash = sha256(subjectBytes);
            CertificationArtifactLock subjectLock = storeCanonical(
                    base,
                    "factory-reference-assembly/release-subject/sha256/",
                    subjectBytes);
            DecisionPointId pointId = new DecisionPointId(
                    "reference-release-point-" + suffix);
            DecisionPoint open = new DecisionPoint(
                    pointId,
                    base.tenant(),
                    base.session().buildSessionId(),
                    ReferenceAssemblyReleaseReviewService.DECISION_TYPE,
                    DecisionPointStatus.OPEN,
                    ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE,
                    inspected.referenceAssemblyId().value(),
                    inspected.version(),
                    subjectHash,
                    subjectLock.reference(),
                    ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID,
                    Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION),
                    1,
                    inspected.policySnapshot().reference(),
                    BASE.plusSeconds(4),
                    reviewing.deadlineAt(),
                    Optional.empty(),
                    Optional.empty(),
                    0);
            JdbcDecisionPointRepository points = new JdbcDecisionPointRepository(
                    base.certificationFixture().dataSource());
            points.create(open);
            Decision approval = new Decision(
                    new DecisionId("reference-release-decision-" + suffix),
                    base.tenant(),
                    pointId,
                    "reference-release-approval-" + suffix,
                    DecisionOutcome.APPROVE,
                    Optional.of("approved"),
                    Optional.empty(),
                    "reviewer-a",
                    new ArtifactReference("artifact:release-authority:" + suffix),
                    subjectHash,
                    BASE.plusSeconds(5));
            new JdbcDecisionRepository(base.certificationFixture().dataSource()).create(approval);
            DecisionPoint approved = open.decide(approval, BASE.plusSeconds(5));
            requireCas(points.compareAndSet(open, approved));

            ReferenceAssembly reviewBound = inspected.bindReleaseReview(
                    pointId, subjectHash, BASE.plusSeconds(6));
            requireCas(assemblies.compareAndSet(inspected, reviewBound));
            BuildSession packageSession = reviewing.resumeReferenceAssemblyRelease(
                    BASE.plusSeconds(6));
            requireCas(sessions.compareAndSet(reviewing, packageSession));

            ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                    reviewBound.referenceAssemblyId(),
                    reviewBound.assemblyManifest().orElseThrow().hash(),
                    reviewBound.inspectionReport().orElseThrow().hash(),
                    pointId,
                    subjectHash,
                    reviewBound.version());
            String actionRunId = "reference-release-action-" + suffix;
            String attemptToken = "reference-release-attempt-" + suffix;
            String operationId = ReferenceAssemblyReleaseDispatchOperationIds.derive(
                    base.tenant(), input);
            createWaitingAction(
                    base,
                    reviewBound,
                    input,
                    actionRunId,
                    attemptToken,
                    operationId,
                    packageSession.deadlineAt(),
                    suffix);

            ReferenceAssembly actionBound = reviewBound.bindReleaseAction(
                    actionRunId, BASE.plusSeconds(7));
            requireCas(assemblies.compareAndSet(reviewBound, actionBound));

            ReferenceAssemblyReleaseDispatchIntent pending =
                    ReferenceAssemblyReleaseDispatchIntent.pending(
                            operationId,
                            base.tenant(),
                            input,
                            actionRunId,
                            ReferenceAssemblyReleaseAttemptTokens.hash(attemptToken),
                            packageSession.deadlineAt(),
                            BASE.plusSeconds(8));
            intents.create(pending);
            ReferenceAssemblyReleaseDispatchIntent running = pending.claim(
                    "release-claim-" + suffix,
                    BASE.plusSeconds(9),
                    Duration.ofMinutes(5));
            requireCas(intents.compareAndSet(pending, running));

            var componentInput = component.inputLock();
            CertifiedAgentComponentRef componentRef = new CertifiedAgentComponentRef(
                    CertifiedAgentComponentRef.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    component.certificationId(),
                    component.certificationManifest().orElseThrow(),
                    componentInput.candidateId(),
                    componentInput.candidateHash(),
                    componentInput.sourceManifest(),
                    component.inputLockArtifact(),
                    componentInput.verificationRunId(),
                    componentInput.verificationResultManifest(),
                    componentInput.compatibilityDescriptor(),
                    component.certificationEvidence().orElseThrow(),
                    componentInput.certificationProfile());
            ReferenceAssemblyReleaseManifest release = new ReferenceAssemblyReleaseManifest(
                    ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                    ProductLineId.REFERENCE_ASSEMBLY,
                    ReferenceAssemblyReleaseManifest.RELEASE_ALGORITHM_ID,
                    actionBound.referenceAssemblyId(),
                    actionBound.requirement(),
                    actionBound.consumerContract(),
                    actionBound.hostFixture(),
                    actionBound.policySnapshot(),
                    componentRef,
                    actionBound.assemblyManifest().orElseThrow(),
                    actionBound.inspectionReport().orElseThrow(),
                    pointId,
                    subjectHash,
                    actionRunId);
            byte[] releaseBytes = new JacksonReferenceAssemblyArtifactCodec()
                    .writeReleaseManifest(release);
            CertificationArtifactLock releaseManifest = storeCanonical(
                    base,
                    "factory-reference-assembly/release-manifest/sha256/",
                    releaseBytes);
            ReferenceAssembly proposed = actionBound.release(
                    releaseManifest,
                    pointId,
                    subjectHash,
                    actionRunId,
                    BASE.plusSeconds(10));
            return new Fixture(
                    base,
                    sessions,
                    assemblies,
                    certifications,
                    intents,
                    packageSession,
                    component,
                    actionRunId,
                    running,
                    actionBound,
                    proposed);
        }

        javax.sql.DataSource dataSource() {
            return base.certificationFixture().dataSource();
        }

        io.github.flowerjvm.factory.contracts.ids.TenantId tenant() {
            return base.tenant();
        }

        JdbcReferenceAssemblyReleaseTransaction transactionAt(Instant now) {
            return new JdbcReferenceAssemblyReleaseTransaction(
                    dataSource(), Clock.fixed(now, ZoneOffset.UTC));
        }

        private static void createWaitingAction(
                JdbcReferenceAssemblyRepositoryTest.Fixture base,
                ReferenceAssembly reviewBound,
                ReferenceAssemblyReleaseInput input,
                String actionRunId,
                String attemptToken,
                String operationId,
                Instant deadlineAt,
                String suffix) {
            ActionProposal proposal = ActionProposal.builder(
                            ReferenceAssemblyReleaseAction.ACTION_ID)
                    .proposalId("reference-release-proposal-" + suffix)
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .requesterId(ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID)
                    .reason("Package exact approved Reference Assembly")
                    .input(input.toMap())
                    .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(reviewBound))
                    .build();
            ExecutionContext context = new ExecutionContext(
                    base.tenant().value(),
                    ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                    actionRunId,
                    "reference-release-trace-" + suffix,
                    Map.of(
                            "actor.permissions", Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                            "resource.type", ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                            "resource.id", reviewBound.referenceAssemblyId().value()));
            ActionRun requested = ActionRun.requested(proposal, context);
            ActionRun waiting = requested.toBuilder()
                    .version(1)
                    .status(ActionRunStatus.WAITING_EXTERNAL)
                    .currentStage("execute-action")
                    .dueAt(ReferenceAssemblyReleaseDeadlines.actionDueAt(deadlineAt))
                    .attemptToken(attemptToken)
                    .externalOperationId(operationId)
                    .externalOperationMetadata(Map.of(
                            "dispatchMode", ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                            ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                            reviewBound.referenceAssemblyId().value()))
                    .result(new ActionExecutionResult(
                            ActionExecutionStatus.ACCEPTED,
                            "ACTION_DEFERRED",
                            "Action was dispatched and is awaiting completion.",
                            Map.of(
                                    "runId", actionRunId,
                                    "operationId", operationId,
                                    "dueAt", ReferenceAssemblyReleaseDeadlines.actionDueAt(deadlineAt).toString()),
                            RetryDisposition.NEVER))
                    .createdAt(BASE.plusSeconds(7))
                    .updatedAt(BASE.plusSeconds(8))
                    .build();
            new JdbcRunStore(base.certificationFixture().dataSource(), new ObjectMapper())
                    .create(waiting);
        }

        private static void requireCas(boolean result) {
            if (!result) {
                throw new AssertionError("fixture CAS did not persist");
            }
        }
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }

    private static CertificationArtifactLock storeCanonical(
            JdbcReferenceAssemblyRepositoryTest.Fixture base,
            String prefix,
            byte[] content) {
        ContentHash contentHash = sha256(content);
        ArtifactReference reference = new ArtifactReference(prefix + contentHash.sha256());
        base.certificationFixture().artifacts().store(new Artifact(
                base.tenant(), reference, contentHash, "application/json", content));
        return new CertificationArtifactLock(reference, contentHash);
    }

    private static ContentHash sha256(byte[] value) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
