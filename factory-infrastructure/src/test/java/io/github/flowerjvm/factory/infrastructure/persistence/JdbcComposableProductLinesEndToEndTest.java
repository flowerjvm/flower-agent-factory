package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.CertificationIssueActionExecutor;
import io.github.flowerjvm.factory.application.action.CertificationIssueActionValidator;
import io.github.flowerjvm.factory.application.action.CertificationIssuePolicyGate;
import io.github.flowerjvm.factory.application.action.CertificationIssuePreExecutionGuard;
import io.github.flowerjvm.factory.application.action.CertificationIssueVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionValidator;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleasePolicyGate;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleasePreExecutionGuard;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.certification.ActionBackedAgentPackCertificationLauncher;
import io.github.flowerjvm.factory.application.certification.ActionRuntimeCertificationEvidenceOwner;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.certification.AgentPackProductContract;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchRunner;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceService;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.certification.CertifiedComponentResolutionException;
import io.github.flowerjvm.factory.application.certification.CertifiedComponentResolver;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingDisposition;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingService;
import io.github.flowerjvm.factory.application.decision.DecisionRequestContext;
import io.github.flowerjvm.factory.application.decision.ReferenceAssemblyReleaseDecisionSubjectAuthority;
import io.github.flowerjvm.factory.application.flow.LedgerBackedReferenceAssemblyFlowCoordinator;
import io.github.flowerjvm.factory.application.flow.ReferenceAssemblyBuildPhase;
import io.github.flowerjvm.factory.application.flow.ReferenceAssemblyFlowFactory;
import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAssembler;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyInspector;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyException;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyProductLineCatalog;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyProductLineCatalog.Entry;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAuthorityVerifier;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchRunner;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseOutcome;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRequestService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReleasedReferenceAssemblyResolver;
import io.github.flowerjvm.factory.application.referenceassembly.ReleasedReferenceAssemblyResolutionException;
import io.github.flowerjvm.factory.application.verification.ActionRuntimeVerificationEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.referenceassembly.JacksonReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Governed issuance/composition regression with synthetic verification and reviewer fixtures.
 * This is not evidence of native business acceptance, real Worker generation or human approval.
 */
class JdbcComposableProductLinesEndToEndTest {
    private static final Instant CERTIFICATION_TIME =
            Instant.parse("2026-09-01T00:00:06Z");
    private static final TenantId TENANT = PersistenceFixtures.TENANT;

    @Test
    void governedAgentPackCertificationBecomesAReleasedSecondLineComponent() throws Exception {
        governedComposition(null);
    }

    @Test
    void productionLegacyCatalogGovernsIssuanceAndUnchangedComponentShipment() throws Exception {
        governedComposition(Entry.LEGACY_PR4);
    }

    @Test
    void productionMaintenanceCatalogGovernsIssuanceAndUnchangedComponentShipment() throws Exception {
        governedComposition(Entry.MAINTENANCE_INVESTIGATION_V1);
    }

    @Test
    void microsecondBusinessDeadlineSurvivesRegisteredDispatchJdbcReloadAndFullReleasedRead()
            throws Exception {
        governedComposition(Entry.MAINTENANCE_INVESTIGATION_V1, Duration.ofNanos(123_456_000));
    }

    private void governedComposition(Entry selectedEntry) throws Exception {
        governedComposition(selectedEntry, Duration.ZERO);
    }

    private void governedComposition(Entry selectedEntry, Duration deadlineFraction) throws Exception {
        DataSource dataSource = FactoryDatabaseMigrationsTest.h2(
                "composable-product-lines-" + UUID.randomUUID());
        FactoryDatabaseMigrations.migrate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        MutableClock clock = new MutableClock(CERTIFICATION_TIME);
        JdbcArtifactStore artifacts = new JdbcArtifactStore(dataSource, clock);
        ReferenceAssemblyArtifactCodec referenceCodec =
                new JacksonReferenceAssemblyArtifactCodec(objectMapper);
        ReferenceAssemblyProductLineCatalog catalog =
                new ReferenceAssemblyProductLineCatalog(artifacts, referenceCodec);
        catalog.provisionTenant(TENANT);
        Entry entry = selectedEntry == null ? Entry.LEGACY_PR4 : selectedEntry;
        catalog.provisionTenant(TENANT, entry);
        if (selectedEntry != null) {
            // The same canonical matrix may describe this test's input even on the legacy profile.
            // Only the maintenance profile mandates that exact matrix.
            for (var artifact : MaintenanceInvestigationProductContract.artifacts(TENANT)) {
                if (artifacts.find(TENANT, artifact.reference()).isEmpty()) artifacts.store(artifact);
            }
        }

        JdbcCertificationRepositoryTest.Fixture producer =
                selectedEntry == null
                ?
                JdbcCertificationRepositoryTest.Fixture.createForCertifiedComponentConsumer(
                        dataSource,
                        "composition-e2e",
                        AgentPackProductContract.lock(),
                        catalog.expectedApiSignatureIndex(),
                        CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID)
                : JdbcCertificationRepositoryTest.Fixture.createForVersionedComponentConsumer(
                        dataSource, "composition-e2e",
                        entry == Entry.LEGACY_PR4 ? ActionBackedVerificationRunLauncher.GATE_PROFILE
                                : MaintenanceInvestigationProductContract.GATE_PROFILE,
                        entry == Entry.LEGACY_PR4 ? AgentPackProductContract.lock()
                                : MaintenanceInvestigationProductContract.lock(),
                        catalog.expectedApiSignatureIndex(entry),
                        MaintenanceInvestigationProductContract.requirementTestMatrixLock());
        producer.certifications().create(producer.requested());
        makeProducerSessionCertifiable(producer, CERTIFICATION_TIME.minusSeconds(1));

        JdbcBuildSessionRepository sessions = new JdbcBuildSessionRepository(dataSource);
        JdbcWorkOrderRepository workOrders = new JdbcWorkOrderRepository(dataSource, objectMapper);
        CandidateVersionRepository candidates = new JdbcCandidateVersionRepository(dataSource);
        VerificationRunRepository verifications = new JdbcVerificationRunRepository(dataSource);
        VerificationDispatchIntentRepository verificationIntents =
                new JdbcVerificationDispatchIntentRepository(dataSource);
        RunStore actionRuns = new ClockAlignedRunStore(
                new JdbcRunStore(dataSource, objectMapper), clock);
        JacksonWorkerProtocolArtifactDecoder workerDecoder =
                new JacksonWorkerProtocolArtifactDecoder(objectMapper);
        WorkerProtocolArtifacts workerArtifacts =
                new WorkerProtocolArtifacts(artifacts, workerDecoder);
        // Deliberately synthetic; native verifier/read-gate evidence has separate Docker tests.
        VerificationEvidenceValidator verificationEvidence = ignored -> true;
        VerificationActionEvidenceOwner verificationOwner =
                new ActionRuntimeVerificationEvidenceOwner(
                        verificationIntents,
                        actionRuns,
                        candidates,
                        new JdbcVerificationActionDuplicateOwnerLookup(dataSource));
        AgentPackCertificationPolicyCatalog certificationPolicy = selectedEntry == null
                ? AgentPackCertificationPolicyCatalog.singleton(policyFor(producer))
                : AgentPackCertificationPolicyCatalog.production(
                        producer.inputLock().verificationFixtureSetHash(),
                        producer.inputLock().factoryVersion(), producer.inputLock().flowerVersion(),
                        producer.inputLock().actionRuntimeVersion(), workOrders, workerArtifacts);
        assertTrue(certificationPolicy.matches(producer.inputLock()));

        JdbcCertificationDispatchIntentRepository certificationIntents =
                new JdbcCertificationDispatchIntentRepository(dataSource);
        DefaultActionRuntime certificationRuntime = certificationRuntime(
                dataSource,
                objectMapper,
                clock,
                producer,
                sessions,
                workOrders,
                candidates,
                verifications,
                verificationIntents,
                actionRuns,
                workerArtifacts,
                verificationEvidence,
                verificationOwner,
                certificationPolicy);
        BuildSession producerSession = sessions
                .find(TENANT, producer.inputLock().buildSessionId())
                .orElseThrow();
        AtomicInteger lifecycle = new AtomicInteger();
        var certificationLauncher = new ActionBackedAgentPackCertificationLauncher(
                certificationRuntime,
                () -> "composition-cert-lifecycle-" + lifecycle.incrementAndGet());
        var certificationProposal = certificationLauncher.ensureProposed(
                producerSession,
                producer.requested(),
                flowerIdentity(producerSession, "composition-cert-flow", "composition-cert-trace"),
                clock.instant());

        assertEquals(ActionExecutionStatus.ACCEPTED, certificationProposal.status());
        var pendingCertificationIntent = certificationIntents
                .findLatest(TENANT, producer.certificationId())
                .orElseThrow();
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, actionRuns
                .find(pendingCertificationIntent.actionRunId())
                .orElseThrow()
                .status());

        CertificationIssuanceService issuance = new CertificationIssuanceService(
                producer.certifications(),
                new JdbcCertificationIssuanceTransaction(
                        dataSource, producer.codec(), workerDecoder),
                artifacts,
                producer.codec(),
                clock);
        CertificationDispatchRunner certificationRunner = new CertificationDispatchRunner(
                certificationIntents,
                producer.certifications(),
                sessions,
                certificationPolicy,
                issuance,
                certificationRuntime,
                actionRuns,
                clock);
        assertTrue(certificationRunner.tickOnce());

        Certification certified = producer.certifications()
                .find(TENANT, producer.certificationId())
                .orElseThrow();
        assertEquals(CertificationStatus.CERTIFIED, certified.status());
        assertEquals(ActionRunStatus.SUCCEEDED, actionRuns
                .find(certified.actionRunId().orElseThrow())
                .orElseThrow()
                .status());
        assertTrue(certificationIntents
                .findLatest(TENANT, producer.certificationId())
                .orElseThrow()
                .status()
                .isTerminal());

        CertificationActionEvidenceOwner certificationOwner =
                new ActionRuntimeCertificationEvidenceOwner(certificationIntents, actionRuns);
        CertifiedComponentResolver componentResolver = new CertifiedComponentResolver(
                producer.certifications(),
                certificationOwner,
                sessions,
                workOrders,
                candidates,
                verifications,
                verificationIntents,
                artifacts,
                workerArtifacts,
                verificationEvidence,
                verificationOwner,
                producer.codec(),
                clock);
        CertifiedAgentComponentRef component = componentReference(certified);
        assertEquals(component, componentResolver.resolve(TENANT, component).reference());

        clock.advance(Duration.ofMinutes(1));
        var requirement = catalog.stageRequirement(TENANT, component, entry);
        BuildSession referenceSession = referenceSession(requirement, clock.instant(), deadlineFraction);
        sessions.create(referenceSession);

        ReferenceAssemblyRepository assemblies =
                new JdbcReferenceAssemblyRepository(dataSource);
        DecisionPointRepository decisionPoints =
                new JdbcDecisionPointRepository(dataSource, objectMapper);
        ReferenceAssemblyRequestService requests = new ReferenceAssemblyRequestService(
                sessions,
                assemblies,
                artifacts,
                referenceCodec,
                catalog.admissionPolicies(),
                clock);
        ReferenceAssemblyAssembler assembler = new ReferenceAssemblyAssembler(
                artifacts,
                referenceCodec,
                componentResolver,
                catalog.admissionPolicies());
        if (selectedEntry != null) {
            Entry wrongEntry = entry == Entry.LEGACY_PR4
                    ? Entry.MAINTENANCE_INVESTIGATION_V1 : Entry.LEGACY_PR4;
            var wrongRequirement = catalog.stageRequirement(TENANT, component, wrongEntry);
            assertThrows(ReferenceAssemblyException.class,
                    () -> assembler.resolveComponent(TENANT, wrongRequirement));
        }
        ReferenceAssemblyInspector inspector = new ReferenceAssemblyInspector(
                artifacts,
                referenceCodec,
                componentResolver,
                catalog.admissionPolicies());
        ReferenceAssemblyReleaseReviewService reviews =
                new ReferenceAssemblyReleaseReviewService(
                        sessions,
                        assemblies,
                        decisionPoints,
                        artifacts,
                        referenceCodec,
                        componentResolver,
                        catalog.admissionPolicies(),
                        clock);

        ReferenceAssemblyReleaseAuthorityVerifier releaseAuthority =
                new ReferenceAssemblyReleaseAuthorityVerifier(
                        assemblies,
                        sessions,
                        decisionPoints,
                        assembler,
                        artifacts,
                        referenceCodec,
                        clock);
        ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents =
                new JdbcReferenceAssemblyReleaseDispatchIntentRepository(dataSource);
        DefaultActionRuntime releaseRuntime = releaseRuntime(
                dataSource,
                objectMapper,
                clock,
                assemblies,
                releaseAuthority,
                actionRuns);
        ReferenceAssemblyReleaseService releaseService = new ReferenceAssemblyReleaseService(
                assemblies,
                new JdbcReferenceAssemblyReleaseTransaction(dataSource, objectMapper, clock),
                artifacts,
                referenceCodec,
                clock);
        ReferenceAssemblyReleaseDispatchRunner releaseRunner =
                new ReferenceAssemblyReleaseDispatchRunner(
                        releaseIntents,
                        assemblies,
                        releaseService,
                        releaseRuntime,
                        actionRuns,
                        clock);
        LedgerBackedReferenceAssemblyFlowCoordinator coordinator =
                new LedgerBackedReferenceAssemblyFlowCoordinator(
                        sessions,
                        assemblies,
                        decisionPoints,
                        requests,
                        assembler,
                        inspector,
                        reviews,
                        new ActionBackedReferenceAssemblyReleaseLauncher(releaseRuntime),
                        releaseIntents,
                        releaseService,
                        actionRuns,
                        clock);
        StepContext stepContext = stepContext(referenceSession);

        drivePhase(coordinator, ReferenceAssemblyBuildPhase.ACCEPT_REFERENCE_REQUIREMENTS,
                referenceSession.buildSessionId(), stepContext, clock);
        drivePhase(coordinator, ReferenceAssemblyBuildPhase.RESOLVE_CERTIFIED_COMPONENT,
                referenceSession.buildSessionId(), stepContext, clock);
        drivePhase(coordinator, ReferenceAssemblyBuildPhase.ASSEMBLE_REFERENCE_MANIFEST,
                referenceSession.buildSessionId(), stepContext, clock);
        drivePhase(coordinator, ReferenceAssemblyBuildPhase.INSPECT_REFERENCE_ASSEMBLY,
                referenceSession.buildSessionId(), stepContext, clock);

        ReferenceAssembly inspected = openReview(
                coordinator, assemblies, referenceSession, stepContext, clock);
        DecisionPoint open = decisionPoints
                .find(TENANT, inspected.releaseDecisionPointId().orElseThrow())
                .orElseThrow();
        clock.advance(Duration.ofSeconds(1));
        DecisionRecordingService decisionService = new DecisionRecordingService(
                sessions,
                decisionPoints,
                new JdbcDecisionRepository(dataSource),
                new JdbcDecisionRecordingTransaction(dataSource, objectMapper),
                List.of(new ReferenceAssemblyReleaseDecisionSubjectAuthority(assemblies)));
        Decision approval = new Decision(
                new DecisionId("composition-reference-release-approval"),
                TENANT,
                open.decisionPointId(),
                "composition-reference-release-approval-request",
                DecisionOutcome.APPROVE,
                Optional.of("release"),
                Optional.of("approved for Factory shipment"),
                "composition-reviewer",
                new ArtifactReference("artifact:composition-review-authority"),
                open.subjectHash(),
                clock.instant());
        var recorded = decisionService.record(
                approval,
                new DecisionRequestContext(
                        TENANT,
                        clock.instant(),
                        "composition-reviewer",
                        Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION),
                        new ArtifactReference("artifact:composition-review-authority")));
        assertEquals(DecisionRecordingDisposition.APPLIED, recorded.disposition());

        drivePhase(coordinator, ReferenceAssemblyBuildPhase.WAIT_REFERENCE_RELEASE_REVIEW,
                referenceSession.buildSessionId(), stepContext, clock);
        clock.advance(Duration.ofSeconds(1));
        StepResult proposal = coordinator.advance(
                ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                referenceSession.buildSessionId(),
                stepContext);
        assertEquals(StepResult.Type.STAY, proposal.type());

        ReferenceAssembly actionBound = assemblies
                .findByBuildSession(TENANT, referenceSession.buildSessionId())
                .orElseThrow();
        var pendingRelease = releaseIntents
                .findLatest(TENANT, actionBound.referenceAssemblyId())
                .orElseThrow();
        assertEquals(
                ReferenceAssemblyReleaseDispatchIntentStatus.PENDING,
                pendingRelease.status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, actionRuns
                .find(pendingRelease.actionRunId())
                .orElseThrow()
                .status());
        assertEquals(referenceSession.deadlineAt(), pendingRelease.deadlineAt());
        assertEquals(referenceSession.deadlineAt(), open.dueAt());
        var canonicalActionDeadline = referenceSession.deadlineAt()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(canonicalActionDeadline,
                actionRuns.find(pendingRelease.actionRunId()).orElseThrow().dueAt());
        assertEquals(!deadlineFraction.isZero(),
                canonicalActionDeadline.isBefore(referenceSession.deadlineAt()));

        clock.advance(Duration.ofSeconds(1));
        assertTrue(releaseRunner.tickOnce());
        var observedReleaseIntent = releaseIntents
                .findLatest(TENANT, actionBound.referenceAssemblyId())
                .orElseThrow();
        assertEquals(
                ReferenceAssemblyReleaseDispatchIntentStatus.COMPLETED,
                observedReleaseIntent.status(),
                () -> "intent=" + observedReleaseIntent
                        + ", assembly=" + assemblies
                                .find(TENANT, actionBound.referenceAssemblyId())
                                .orElseThrow()
                        + ", action=" + actionRuns
                                .find(pendingRelease.actionRunId())
                                .orElseThrow());
        assertEquals(
                ActionRunStatus.SUCCEEDED,
                actionRuns.find(pendingRelease.actionRunId()).orElseThrow().status());
        drivePhase(coordinator, ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                referenceSession.buildSessionId(), stepContext, clock);

        ReferenceAssembly released = assemblies
                .find(TENANT, actionBound.referenceAssemblyId())
                .orElseThrow();
        assertEquals(ReferenceAssemblyStatus.RELEASED, released.status());
        BuildSession completed = sessions
                .find(TENANT, referenceSession.buildSessionId())
                .orElseThrow();
        assertEquals(BuildSessionStatus.SUCCEEDED, completed.status());
        assertEquals(BuildSessionPhase.COMPLETE, completed.currentPhase());
        assertEquals(completed.deadlineAt(), observedReleaseIntent.deadlineAt());
        assertEquals(
                Optional.of(ReferenceAssemblyReleaseDispatchRunner.DISPATCH_COMPLETED),
                observedReleaseIntent.lastCode());
        assertEquals(
                observedReleaseIntent.expectedReferenceAssemblyVersion() + 2,
                released.version());
        ActionRun releasedAction = actionRuns
                .find(observedReleaseIntent.actionRunId())
                .orElseThrow();
        assertTrue(ReferenceAssemblyReleaseDispatchRunner.hasImmutableOwnerBinding(
                observedReleaseIntent, releasedAction, released));
        assertEquals(observedReleaseIntent.operationId(), releasedAction.externalOperationId());
        assertEquals(canonicalActionDeadline, releasedAction.dueAt());
        assertTrue(ReferenceAssemblyReleaseDispatchRunner.exactTerminalResult(
                new ReferenceAssemblyReleaseOutcome(
                        released, released.releaseManifest().orElseThrow(), false),
                releasedAction.result()));
        assertEquals(
                component,
                componentResolver.resolve(TENANT, component).reference());

        var resolvedRelease = new ReleasedReferenceAssemblyResolver(
                        assemblies,
                        sessions,
                        releaseIntents,
                        actionRuns,
                        artifacts,
                        referenceCodec,
                        componentResolver,
                        catalog.admissionPolicies())
                .resolve(TENANT, released.referenceAssemblyId());
        assertEquals(component, resolvedRelease.releaseManifest().component());
        assertEquals(producer.inputLock().candidateHash(), resolvedRelease.component().candidate().sourceHash());
        assertEquals(producer.inputLock().sourceManifest(), component.sourceManifest());
        assertEquals(producer.inputLock().gateProfile(), resolvedRelease.component().inputLock().gateProfile());
        assertEquals(certified.certificationId(),
                resolvedRelease.component().certification().certificationId());
        assertEquals(
                List.of(released),
                assemblies.findReleasedByComponentCertification(
                        TENANT, certified.certificationId()));
        assertFalse(
                resolvedRelease.releaseManifestArtifact().reference().value().isBlank());
        if (entry == Entry.MAINTENANCE_INVESTIGATION_V1) {
            assertThrows(ReleasedReferenceAssemblyResolutionException.class,
                    () -> new ReleasedReferenceAssemblyResolver(
                            assemblies, sessions, releaseIntents, actionRuns, artifacts,
                            referenceCodec, componentResolver)
                            .resolve(TENANT, released.referenceAssemblyId()));
        }

        clock.advance(Duration.ofSeconds(1));
        Certification revoked = certified.revoke(
                "COMPONENT_REVOKED_AFTER_RELEASE", clock.instant());
        assertTrue(producer.certifications().compareAndSet(certified, revoked));
        assertEquals(CertificationStatus.REVOKED, revoked.status());
        assertThrows(
                CertifiedComponentResolutionException.class,
                () -> componentResolver.resolve(TENANT, component));
        assertThrows(
                ReleasedReferenceAssemblyResolutionException.class,
                () -> new ReleasedReferenceAssemblyResolver(
                                assemblies,
                                sessions,
                                releaseIntents,
                                actionRuns,
                                artifacts,
                                referenceCodec,
                                componentResolver,
                                catalog.admissionPolicies())
                        .resolve(TENANT, released.referenceAssemblyId()));
    }

    private static DefaultActionRuntime certificationRuntime(
            DataSource dataSource,
            ObjectMapper objectMapper,
            Clock clock,
            JdbcCertificationRepositoryTest.Fixture producer,
            JdbcBuildSessionRepository sessions,
            WorkOrderRepository workOrders,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            VerificationDispatchIntentRepository verificationIntents,
            RunStore actionRuns,
            WorkerProtocolArtifacts workerArtifacts,
            VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            AgentPackCertificationPolicyCatalog policy) {
        var executor = new CertificationIssueActionExecutor(
                new JdbcCertificationDispatchTransaction(dataSource, objectMapper), clock);
        var guard = new CertificationIssuePreExecutionGuard(
                producer.certifications(),
                sessions,
                workOrders,
                workerArtifacts,
                candidates,
                verifications,
                verificationEvidence,
                verificationOwner,
                verificationIntents,
                policy,
                clock);
        return new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                new CertificationIssueActionValidator(),
                new CertificationIssuePolicyGate(producer.certifications(), policy),
                ApprovalGate.unsupported(),
                new JdbcDuplicateActionPolicy(
                        dataSource,
                        objectMapper,
                        new CertificationIssueVisibilityScopeResolver(
                                producer.certifications())),
                AuditSink.noop(),
                TraceSink.noop(),
                actionRuns,
                guard);
    }

    private static DefaultActionRuntime releaseRuntime(
            DataSource dataSource,
            ObjectMapper objectMapper,
            Clock clock,
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseAuthorityVerifier authority,
            RunStore actionRuns) {
        var executor = new ReferenceAssemblyReleaseActionExecutor(
                new JdbcReferenceAssemblyReleaseDispatchTransaction(dataSource, objectMapper),
                clock);
        return new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                new ReferenceAssemblyReleaseActionValidator(),
                new ReferenceAssemblyReleasePolicyGate(assemblies),
                ApprovalGate.unsupported(),
                new JdbcDuplicateActionPolicy(
                        dataSource,
                        objectMapper,
                        new ReferenceAssemblyReleaseVisibilityScopeResolver(assemblies)),
                AuditSink.noop(),
                TraceSink.noop(),
                actionRuns,
                new ReferenceAssemblyReleasePreExecutionGuard(authority));
    }

    private static AgentPackCertificationPolicy policyFor(
            JdbcCertificationRepositoryTest.Fixture producer) {
        var input = producer.inputLock();
        return new AgentPackCertificationPolicy(
                input.productContractBundle(),
                input.gateProfile(),
                input.verificationFixtureSetHash(),
                input.sourceLockAlgorithmId(),
                input.certificationProfile(),
                input.factoryVersion(),
                input.flowerVersion(),
                input.actionRuntimeVersion());
    }

    private static CertifiedAgentComponentRef componentReference(Certification certification) {
        var input = certification.inputLock();
        return new CertifiedAgentComponentRef(
                CertifiedAgentComponentRef.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ProductLineId.AGENT_PACK,
                input.artifactType(),
                certification.certificationId(),
                certification.certificationManifest().orElseThrow(),
                input.candidateId(),
                input.candidateHash(),
                input.sourceManifest(),
                certification.inputLockArtifact(),
                input.verificationRunId(),
                input.verificationResultManifest(),
                input.compatibilityDescriptor(),
                certification.certificationEvidence().orElseThrow(),
                input.certificationProfile());
    }

    private static BuildSession referenceSession(
            io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock requirement,
            Instant now, Duration deadlineFraction) {
        return new BuildSession(
                new BuildSessionId("build-composition-reference-assembly"),
                TENANT,
                new ProjectId("project-composition-reference-assembly"),
                ProductLineId.REFERENCE_ASSEMBLY,
                "request-composition-reference-assembly",
                "composition-requester",
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
                0,
                now.minusSeconds(1),
                now.plusSeconds(3600).plus(deadlineFraction),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                now.minusSeconds(1),
                now.minusSeconds(1));
    }

    private static ReferenceAssembly openReview(
            LedgerBackedReferenceAssemblyFlowCoordinator coordinator,
            ReferenceAssemblyRepository assemblies,
            BuildSession session,
            StepContext context,
            MutableClock clock) {
        for (int tick = 0; tick < 8; tick++) {
            clock.advance(Duration.ofSeconds(1));
            StepResult result = coordinator.advance(
                    ReferenceAssemblyBuildPhase.WAIT_REFERENCE_RELEASE_REVIEW,
                    session.buildSessionId(),
                    context);
            assertNotEquals(StepResult.Type.FAIL, result.type(), failureMessage(result));
            ReferenceAssembly current = assemblies
                    .findByBuildSession(TENANT, session.buildSessionId())
                    .orElseThrow();
            if (current.releaseDecisionPointId().isPresent()) {
                return current;
            }
        }
        return fail("release review was not opened within bounded ticks");
    }

    private static void drivePhase(
            LedgerBackedReferenceAssemblyFlowCoordinator coordinator,
            ReferenceAssemblyBuildPhase phase,
            BuildSessionId sessionId,
            StepContext context,
            MutableClock clock) {
        for (int tick = 0; tick < 12; tick++) {
            clock.advance(Duration.ofSeconds(1));
            StepResult result = coordinator.advance(phase, sessionId, context);
            assertNotEquals(StepResult.Type.FAIL, result.type(), failureMessage(result));
            if (result.type() == StepResult.Type.DONE) {
                return;
            }
        }
        fail(phase + " did not complete within bounded ticks");
    }

    private static String failureMessage(StepResult result) {
        return result.cause() == null ? "unexpected coordinator failure" : result.cause().getMessage();
    }

    private static StepContext stepContext(BuildSession session) {
        ExecutionContext identity = flowerIdentity(
                session, "composition-reference-flow", "composition-reference-trace");
        return (StepContext) Proxy.newProxyInstance(
                StepContext.class.getClassLoader(),
                new Class<?>[] {StepContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "executionContext" -> identity;
                    case "flowId" -> FlowId.of(
                            ReferenceAssemblyFlowFactory.FLOW_TYPE,
                            session.buildSessionId().value());
                    case "currentStepId" -> ReferenceAssemblyFlowFactory.ACCEPT_REFERENCE_REQUIREMENTS;
                    case "stepNo", "elapsedMillis" -> 0;
                    case "timedOut", "hasSignal" -> false;
                    default -> null;
                });
    }

    private static ExecutionContext flowerIdentity(
            BuildSession session, String runId, String traceId) {
        return ExecutionContext.builder()
                .tenantId(session.tenantId().value())
                .userId(session.createdBy())
                .sessionId(session.buildSessionId().value())
                .runId(runId)
                .traceId(traceId)
                .correlationId(session.projectId().value())
                .build();
    }

    private static void makeProducerSessionCertifiable(
            JdbcCertificationRepositoryTest.Fixture producer,
            Instant updatedAt) throws Exception {
        try (var connection = producer.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_build_session SET
                            status = 'CERTIFYING', current_phase = 'certify',
                            current_candidate_id = ?, current_candidate_hash = ?,
                            deadline_at = ?, version = version + 1, updated_at = ?
                        WHERE tenant_id = ? AND build_session_id = ?
                        """)) {
            statement.setString(1, producer.inputLock().candidateId().value());
            statement.setString(2, producer.inputLock().candidateHash().sha256());
            statement.setTimestamp(3, Timestamp.from(updatedAt.plusSeconds(3600)));
            statement.setTimestamp(4, Timestamp.from(updatedAt));
            statement.setString(5, producer.tenant().value());
            statement.setString(6, producer.inputLock().buildSessionId().value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    /** Keeps Action Runtime's wall-clock transitions on this test's deterministic ledger clock. */
    private static final class ClockAlignedRunStore implements RunStore {
        private final RunStore delegate;
        private final Clock clock;

        private ClockAlignedRunStore(RunStore delegate, Clock clock) {
            this.delegate = delegate;
            this.clock = clock;
        }

        @Override
        public ActionRun create(ActionRun run) {
            Instant timestamp = clock.instant();
            return delegate.create(run.toBuilder()
                    .createdAt(timestamp)
                    .updatedAt(timestamp)
                    .build());
        }

        @Override
        public Optional<ActionRun> find(String runId) {
            return delegate.find(runId);
        }

        @Override
        public boolean compareAndSet(ActionRun expected, ActionRun next) {
            ActionRun canonical = delegate.find(expected.runId()).orElse(null);
            if (canonical == null || canonical.version() != expected.version()) {
                return false;
            }
            if (!canonical.actionId().equals(expected.actionId())
                    || !canonical.tenantId().equals(expected.tenantId())
                    || !canonical.duplicateKey().equals(expected.duplicateKey())
                    || canonical.status() != expected.status()
                    || !canonical.currentStage().equals(expected.currentStage())) {
                return false;
            }
            return delegate.compareAndSet(
                    canonical,
                    next.toBuilder()
                            .createdAt(canonical.createdAt())
                            .updatedAt(clock.instant())
                            .build());
        }

        @Override
        public List<ActionRun> findResumable(String actionId) {
            return delegate.findResumable(actionId);
        }

    }
}
