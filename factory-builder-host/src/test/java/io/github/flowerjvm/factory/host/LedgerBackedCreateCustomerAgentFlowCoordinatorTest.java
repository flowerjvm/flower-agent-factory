package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildPhase;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingService;
import io.github.flowerjvm.factory.application.decision.DecisionRequestContext;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowFactory;
import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowWakeup;
import io.github.flowerjvm.factory.application.flow.LedgerBackedCreateCustomerAgentFlowCoordinator;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionLauncher;
import io.github.flowerjvm.factory.application.production.AgentPackProductionActionExecutor;
import io.github.flowerjvm.factory.application.production.AgentPackProductionActionValidator;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPhasePreparation;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPolicyGate;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPreExecutionGuard;
import io.github.flowerjvm.factory.application.production.AgentPackProductionService;
import io.github.flowerjvm.factory.application.production.AgentPackProductionVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.production.ProductionPreparationResult;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.AgentPackGenerationVerificationProfiles;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerCompletion;
import io.github.flowerjvm.factory.application.work.WorkerCompletionActionProjection;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.application.work.WorkerActionRunRecovery;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.infrastructure.persistence.FactoryDatabaseMigrations;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcBuildSessionRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcArtifactStore;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcCandidateVersionRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDecisionPointRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDecisionRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDecisionRecordingTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcVerificationRunRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcVerificationRunRequestTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerDispatchTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkerRunRepository;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcWorkOrderRepository;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.step.StepResult;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class LedgerBackedCreateCustomerAgentFlowCoordinatorTest {
    private static final TenantId TENANT = new TenantId("tenant-ledger");
    private static final BuildSessionId SESSION = new BuildSessionId("session-ledger");
    private static final WorkOrderId ORDER = new WorkOrderId("order-design");
    private static final WorkerRunId RUN = new WorkerRunId("run-design");
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final FlowId FLOW_ID = FlowId.of(CreateCustomerAgentFlowFactory.FLOW_TYPE, SESSION.value());

    @Test
    void missingDesignPreparationRecoversAndObservesDurableWorkOnTheNextTickOnly() {
        assertProductionPreparationRecovery(ProductionWait.DESIGN);
    }

    @Test
    void missingGenerationPreparationRecoversAndObservesDurableWorkOnTheNextTickOnly() {
        assertProductionPreparationRecovery(ProductionWait.GENERATION);
    }

    @Test
    void repairingGenerationDoesNotReuseTheEarlierTerminalOrderAfterRecovery() {
        assertProductionPreparationRecovery(ProductionWait.REPAIR);
    }

    @Test
    void runningGenerationAfterRedesignPreparesRevisionTwoInsteadOfReusingRevisionOneSuccess() {
        assertProductionPreparationRecovery(ProductionWait.REDESIGNED_GENERATION);
    }

    @Test
    void missingHumanReviewPreparationRecoversAndOpensOneExactSubjectReview() {
        assertProductionPreparationRecovery(ProductionWait.HUMAN_REVIEW);
    }

    @Test
    void everyMissingProductionWaitHonorsItsPersistedExactDeadlineBeforePreparing() {
        for (ProductionWait wait : ProductionWait.values()) {
            var fixture = new ProductionWaitFixture(wait, NOW);
            FlowTestHarness first = productionWaitCheckpoint(fixture, NOW.minusMillis(1));
            try (FlowTestHarness restarted = first.restart()) {
                first.close();
                var factory = new CreateCustomerAgentFlowFactory(fixture.coordinator(javaClock(restarted.clock())));
                assertEquals(1, restarted.recoverActiveCount(factory.registry()), wait.name());
                restarted.tick();
                assertProductionIdentity(restarted);
                assertEquals(wait.step, restarted.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
                assertEquals(0, fixture.preparations.get());
                assertEquals(NOW, fixture.sessions.current().deadlineAt());

                fixture.planPresent = true;
                restarted.clock().setTime(NOW.toEpochMilli());
                restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
                restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
                restarted.tick();
                assertEquals(FlowState.FAILED, restarted.latestSnapshot(FLOW_ID).orElseThrow().state());
                assertProductionIdentity(restarted);
                assertEquals(BuildSessionStatus.FAILED, fixture.sessions.current().status());
                assertEquals(LedgerBackedCreateCustomerAgentFlowCoordinator.BUILD_SESSION_DEADLINE_EXCEEDED,
                        fixture.sessions.current().terminalCode().orElseThrow());
                assertEquals(0, fixture.preparations.get());
                assertEquals(0, fixture.dispatches.get());
            }
        }
    }

    @Test
    void everyMissingProductionWaitHonorsDurableCancellationOnItsFirstRecoveredTick() {
        for (ProductionWait wait : ProductionWait.values()) {
            var fixture = new ProductionWaitFixture(wait, NOW.plusSeconds(3600));
            FlowTestHarness first = productionWaitCheckpoint(fixture, NOW);
            try (FlowTestHarness restarted = first.restart()) {
                first.close();
                BuildSession before = fixture.sessions.current();
                assertTrue(fixture.sessions.compareAndSet(before, before.requestCancellation(NOW)));
                fixture.planPresent = true;
                var factory = new CreateCustomerAgentFlowFactory(fixture.coordinator(javaClock(restarted.clock())));
                assertEquals(1, restarted.recoverActiveCount(factory.registry()), wait.name());
                restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
                restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
                restarted.tick();
                assertProductionIdentity(restarted);
                assertEquals(FlowState.FAILED, restarted.latestSnapshot(FLOW_ID).orElseThrow().state());
                assertEquals(BuildSessionStatus.CANCELLING, fixture.sessions.current().status());
                assertEquals(LedgerBackedCreateCustomerAgentFlowCoordinator.BUILD_SESSION_CANCELLATION_REQUESTED,
                        fixture.sessions.current().terminalCode().orElseThrow());
                long version = fixture.sessions.current().version();
                restarted.tick();
                assertProductionIdentity(restarted);
                assertEquals(version, fixture.sessions.current().version());
                assertEquals(0, fixture.preparations.get());
                assertEquals(0, fixture.dispatches.get());
            }
        }
    }

    private static void assertProductionPreparationRecovery(ProductionWait wait) {
        var fixture = new ProductionWaitFixture(wait, NOW.plusSeconds(3600));
        FlowTestHarness first = productionWaitCheckpoint(fixture, NOW);
        try (FlowTestHarness restarted = first.restart()) {
            first.close();
            fixture.planPresent = true;
            var factory = new CreateCustomerAgentFlowFactory(fixture.coordinator(javaClock(restarted.clock())));
            assertEquals(1, restarted.recoverActiveCount(factory.registry()));
            restarted.tick();
            assertProductionIdentity(restarted);
            assertEquals(wait.step, restarted.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
            assertEquals(1, fixture.preparations.get());
            assertEquals(0, fixture.dispatches.get(), "preparation cannot dispatch in the same tick");
            assertEquals(fixture.initial, fixture.sessions.current(), "preparation does not advance the phase");
            if (wait == ProductionWait.HUMAN_REVIEW) {
                assertEquals(DecisionPointStatus.OPEN, fixture.decision.get().status());
                assertEquals(fixture.initial.currentCandidateId().orElseThrow().value(), fixture.decision.get().subjectId());
                assertEquals(fixture.initial.currentCandidateHash().orElseThrow(), fixture.decision.get().subjectHash());
            } else {
                assertEquals(WorkerRunStatus.REQUESTED, fixture.run.get().status());
                assertEquals(new WorkOrderId("production-new-order"), fixture.order.get().workOrderId());
                assertEquals(NOW, fixture.order.get().createdAt());
                assertEquals(fixture.initial.repairRound() + 1, fixture.order.get().revision());
            }

            restarted.tick();
            assertEquals(wait == ProductionWait.HUMAN_REVIEW ? 0 : 1, fixture.dispatches.get());
            if (wait != ProductionWait.HUMAN_REVIEW) {
                assertEquals(WorkerRunStatus.WAITING_EXTERNAL, fixture.run.get().status());
            }
            restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
            restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
            restarted.tick();
            restarted.tick();
            assertEquals(1, fixture.preparations.get(), "duplicate notifications cannot prepare a second effect");
            assertEquals(wait == ProductionWait.HUMAN_REVIEW ? 0 : 1, fixture.dispatches.get());

            // Synthetic domain completion only: no Worker, model, or human authority is exercised by this fixture.
            if (wait == ProductionWait.HUMAN_REVIEW) {
                fixture.decision.set(reviewPoint(DecisionPointStatus.APPROVED,
                        fixture.initial.currentCandidateId().orElseThrow(),
                        fixture.initial.currentCandidateHash().orElseThrow(), NOW.plusSeconds(300)));
            } else {
                WorkerRunRecord waiting = fixture.run.get();
                fixture.run.set(waiting.complete(WorkerRunStatus.FAILED, Optional.empty(), Optional.empty(),
                        "SYNTHETIC_WORKER_FAILURE", "synthetic test completion", WorkerRetryDisposition.NEVER, NOW));
            }
            restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
            restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION));
            restarted.tick();
            if (wait == ProductionWait.HUMAN_REVIEW) restarted.tick();
            assertEquals(wait == ProductionWait.HUMAN_REVIEW ? FlowState.FINISHED : FlowState.FAILED,
                    restarted.latestSnapshot(FLOW_ID).orElseThrow().state());
            assertProductionIdentity(restarted);
            assertEquals(1, fixture.preparations.get());
            assertEquals(wait == ProductionWait.HUMAN_REVIEW ? 0 : 1, fixture.dispatches.get());
        }
    }

    private static void assertProductionIdentity(FlowTestHarness harness) {
        assertEquals(CreateCustomerAgentFlowFactory.executionContext(SESSION, TENANT.value(),
                        "principal-ledger", "production-flow-run", "production-flow-trace", "project-ledger"),
                harness.latestSnapshot(FLOW_ID).orElseThrow().executionContext());
    }

    private static FlowTestHarness productionWaitCheckpoint(ProductionWaitFixture fixture, Instant instant) {
        FlowTestHarness first = FlowTestHarness.create();
        first.clock().setTime(instant.toEpochMilli());
        var coordinator = fixture.coordinator(javaClock(first.clock()));
        // Position the durable six-step graph without manufacturing successes in preceding production phases.
        var checkpointFactory = new CreateCustomerAgentFlowFactory((phase, context) ->
                phase == BuildPhase.ACCEPT_REQUIREMENTS ? StepResult.goTo(fixture.wait.step)
                        : coordinator.advance(phase, context));
        first.submit(checkpointFactory.create(session(BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER, null, null), "production-flow-run", "production-flow-trace"));
        first.tick();
        first.tick();
        assertEquals(fixture.wait.step, first.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        assertProductionIdentity(first);
        assertEquals(0, fixture.preparations.get());
        assertEquals(0, fixture.dispatches.get());
        return first;
    }

    private enum ProductionWait {
        DESIGN(BuildSessionPhase.DESIGN_AGENT, CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE),
        GENERATION(BuildSessionPhase.GENERATE_CANDIDATE, CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE),
        REPAIR(BuildSessionPhase.GENERATE_CANDIDATE, CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE),
        REDESIGNED_GENERATION(BuildSessionPhase.GENERATE_CANDIDATE, CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE),
        HUMAN_REVIEW(BuildSessionPhase.HUMAN_RELEASE_REVIEW, CreateCustomerAgentFlowFactory.HUMAN_REVIEW);
        private final BuildSessionPhase phase;
        private final String step;
        ProductionWait(BuildSessionPhase phase, String step) { this.phase = phase; this.step = step; }
    }

    /** Real registered preparation controls around synthetic in-memory domain intents, never produced software. */
    private static final class ProductionWaitFixture {
        private final ProductionWait wait;
        private final BuildSession initial;
        private final MutableBuildSessions sessions;
        private final AtomicReference<WorkOrder> order = new AtomicReference<>();
        private final AtomicReference<WorkerRunRecord> run = new AtomicReference<>();
        private final AtomicReference<DecisionPoint> decision = new AtomicReference<>();
        private final AtomicInteger preparations = new AtomicInteger();
        private final AtomicInteger dispatches = new AtomicInteger();
        private boolean planPresent;

        ProductionWaitFixture(ProductionWait wait, Instant deadline) {
            this.wait = wait;
            BuildSessionStatus status = wait == ProductionWait.REPAIR ? BuildSessionStatus.REPAIRING
                    : wait == ProductionWait.HUMAN_REVIEW ? BuildSessionStatus.WAITING_RELEASE_REVIEW
                    : BuildSessionStatus.RUNNING;
            boolean hasCandidate = wait == ProductionWait.REPAIR || wait == ProductionWait.REDESIGNED_GENERATION
                    || wait == ProductionWait.HUMAN_REVIEW;
            BuildSession base = withDeadline(session(status, wait.phase,
                    hasCandidate ? new CandidateId("production-base-candidate") : null,
                    hasCandidate ? hash("c") : null), deadline);
            if (wait == ProductionWait.REPAIR || wait == ProductionWait.REDESIGNED_GENERATION) {
                base = new BuildSession(base.buildSessionId(), base.tenantId(), base.projectId(), base.productLineId(),
                        base.requestIdempotencyKey(), base.createdBy(), base.status(), base.currentPhase(),
                        base.requirementsArtifactRef(), base.requirementsHash(), base.selectedManagerWorkerBinding(),
                        base.selectedCodingWorkerBinding(), base.currentBlueprintRef(), base.currentCandidateId(),
                        base.currentCandidateHash(), base.currentCertificationId(), 1, base.maxRepairRounds(),
                        base.startedAt(), base.deadlineAt(), base.cancellationRequestedAt(), base.terminalCode(),
                        base.terminalMessage(), 3, base.createdAt(), base.updatedAt());
            }
            initial = wait == ProductionWait.DESIGN ? base : withBlueprint(base);
            sessions = new MutableBuildSessions(initial);
            if (wait == ProductionWait.REPAIR || wait == ProductionWait.REDESIGNED_GENERATION) {
                order.set(productionOrder("production-old-order", NOW.minusSeconds(31)));
                run.set(wait == ProductionWait.REPAIR ? failedWorkerRun(order.get())
                        : requestedWorkerRun(order.get(), RUN).startDispatch(new DispatchOutboxId("old-outbox"),
                                "old-action-run", hash("a"), NOW.minusSeconds(20))
                                .awaitExternal("old-external-session", NOW.minusSeconds(20))
                                .complete(WorkerRunStatus.SUCCEEDED, Optional.of(new ArtifactReference("artifact:old-candidate")),
                                        Optional.of(hash("c")), "SYNTHETIC_OLD_SUCCESS", "synthetic prior round",
                                        WorkerRetryDisposition.NEVER, NOW.minusSeconds(10)));
                assertTrue(order.get().createdAt().isBefore(initial.updatedAt()));
            }
        }

        private WorkOrder productionOrder(String id, Instant createdAt) {
            return new WorkOrder(new WorkOrderId(id), TENANT, SESSION,
                    wait == ProductionWait.DESIGN ? LedgerBackedCreateCustomerAgentFlowCoordinator.DESIGN_WORK_ORDER_PHASE
                            : LedgerBackedCreateCustomerAgentFlowCoordinator.GENERATE_WORK_ORDER_PHASE,
                    "Synthetic preparation test intent", id.equals("production-old-order") ? 1 : initial.repairRound() + 1,
                    Optional.empty(), initial.currentCandidateId(),
                    Optional.empty(), new ArtifactReference("artifact:production-instruction"), hash("1"),
                    new ArtifactReference("artifact:production-input"), hash("2"), "workspace:production-test",
                    List.of("inputs"), List.of("candidate"), Set.of(new WorkerCapability("java")),
                    "factory.synthetic-preparation", "1", new ArtifactReference("artifact:production-policy"),
                    initial.deadlineAt(), 1, "logical-" + id, WorkOrderCreatorType.SYSTEM, "factory-builder", createdAt);
        }

        private LedgerBackedCreateCustomerAgentFlowCoordinator coordinator(Clock clock) {
            WorkOrderRepository orders = new WorkOrderRepository() {
                public void create(WorkOrder value) { assertTrue(order.compareAndSet(null, value)); }
                public Optional<WorkOrder> find(TenantId tenant, WorkOrderId id) {
                    return Optional.ofNullable(order.get()).filter(v -> v.tenantId().equals(tenant) && v.workOrderId().equals(id));
                }
                public Optional<WorkOrder> findLatestByBuildSessionAndPhase(TenantId tenant, BuildSessionId id, String phase) {
                    return Optional.ofNullable(order.get()).filter(v -> v.tenantId().equals(tenant)
                            && v.buildSessionId().equals(id) && v.phase().equals(phase));
                }
            };
            WorkerRunRepository runs = new WorkerRunRepository() {
                public void create(WorkerRunRecord value) { assertTrue(run.compareAndSet(null, value)); }
                public Optional<WorkerRunRecord> find(TenantId tenant, WorkerRunId id) {
                    return Optional.ofNullable(run.get()).filter(v -> v.tenantId().equals(tenant) && v.workerRunId().equals(id));
                }
                public Optional<WorkerRunRecord> findLatestByWorkOrder(TenantId tenant, WorkOrderId id) {
                    return Optional.ofNullable(run.get()).filter(v -> v.tenantId().equals(tenant) && v.workOrderId().equals(id));
                }
                public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) { return run.compareAndSet(expected, next); }
            };
            DecisionPointRepository decisions = new DecisionPointRepository() {
                public void create(DecisionPoint point) { assertTrue(decision.compareAndSet(null, point)); }
                public Optional<DecisionPoint> find(TenantId tenant, DecisionPointId id) {
                    return Optional.ofNullable(decision.get()).filter(v -> v.tenantId().equals(tenant) && v.decisionPointId().equals(id));
                }
                public Optional<DecisionPoint> findLatestByBuildSessionAndSubject(TenantId tenant, BuildSessionId id,
                        String type, String subjectType, String subjectId, ContentHash subjectHash) {
                    return Optional.ofNullable(decision.get()).filter(v -> v.tenantId().equals(tenant)
                            && v.buildSessionId().equals(id) && v.type().equals(type) && v.subjectType().equals(subjectType)
                            && v.subjectId().equals(subjectId) && v.subjectHash().equals(subjectHash));
                }
                public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) { return decision.compareAndSet(expected, next); }
            };
            ArtifactStore plans = new ArtifactStore() {
                public ArtifactReference store(Artifact artifact) { throw new UnsupportedOperationException(); }
                public Optional<Artifact> find(TenantId tenant, ArtifactReference ref) {
                    return planPresent && tenant.equals(TENANT)
                            && ref.equals(AgentPackProductionService.planReference(TENANT, SESSION))
                            ? Optional.of(new Artifact(TENANT, ref, hash("0"), "application/json", new byte[0]))
                            : Optional.empty();
                }
            };
            var runtime = new DefaultActionRuntime(new InMemoryActionRegistry(List.of(
                    new AgentPackProductionActionExecutor((tenant, id, version) -> {
                        assertEquals(TENANT, tenant); assertEquals(SESSION, id); assertEquals(initial.version(), version);
                        preparations.incrementAndGet();
                        if (wait == ProductionWait.HUMAN_REVIEW) {
                            DecisionPoint point = reviewPoint(DecisionPointStatus.OPEN,
                                    initial.currentCandidateId().orElseThrow(), initial.currentCandidateHash().orElseThrow(),
                                    clock.instant().plusSeconds(300));
                            assertTrue(decision.compareAndSet(null, point));
                            return new ProductionPreparationResult("PRODUCTION_REVIEW_OPENED", Optional.empty(),
                                    Optional.empty(), Optional.of(point.decisionPointId()));
                        }
                        WorkOrder prepared = productionOrder("production-new-order", clock.instant());
                        order.set(prepared);
                        run.set(requestedWorkerRun(prepared, RUN));
                        return new ProductionPreparationResult("PRODUCTION_WORK_PREPARED",
                                Optional.of(prepared.workOrderId()), Optional.of(RUN), Optional.empty());
                    }))), new AgentPackProductionActionValidator(), new AgentPackProductionPolicyGate(sessions, clock),
                    ApprovalGate.unsupported(), new InMemoryDuplicateActionPolicy(new AgentPackProductionVisibilityScopeResolver(sessions)),
                    event -> {}, TraceSink.noop(), new InMemoryRunStore(), new AgentPackProductionPreExecutionGuard(sessions, clock));
            var preparation = new AgentPackProductionPhasePreparation(plans,
                    new ActionBackedAgentPackProductionLauncher(runtime, clock));
            ActionRuntime workerRuntime = (proposal, context) -> {
                assertEquals(WorkerDispatchAction.ACTION_ID, proposal.actionId());
                assertEquals("production-new-order", proposal.input().get(WorkerDispatchAction.WORK_ORDER_ID));
                assertEquals(TENANT.value(), context.tenantId());
                assertEquals("principal-ledger", context.userId());
                assertEquals("production-flow-trace", context.traceId());
                WorkerRunRecord requested = run.get();
                WorkerRunRecord waiting = requested.startDispatch(new DispatchOutboxId("production-outbox"),
                        context.runId(), hash("a"), clock.instant()).awaitExternal("synthetic-external-session", clock.instant());
                assertTrue(run.compareAndSet(requested, waiting));
                dispatches.incrementAndGet();
                return ActionExecutionResult.accepted("WAITING_EXTERNAL", Map.of());
            };
            return new LedgerBackedCreateCustomerAgentFlowCoordinator(sessions, orders, runs,
                    emptyCandidateLedger(), emptyVerificationLedger(),
                    (session, candidate, at) -> { throw new AssertionError("unexpected verification launch"); },
                    value -> true, canonicalOwner(), FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                    decisions, new WorkerActionRunRecovery(runs, RunStore.noop()), workerRuntime, clock, Optional.of(preparation));
        }
    }

    @Test
    void maintenanceGenerationQueriesItsExactProfileAndUsesOnlyMatchingTerminalEvidence() throws Exception {
        var fixture = maintenanceVerificationFixture();
        fixture.terminal(ActionBackedVerificationRunLauncher.GATE_PROFILE, "pr4");
        VerificationRun maintenance = fixture.terminal(MaintenanceInvestigationProductContract.GATE_PROFILE, "maintenance");

        StepResult result = fixture.coordinator(fixture.verifications()).advance(
                BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.DONE, result.type());
        assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, fixture.currentSession().status());
        assertEquals(maintenance.verificationRunId(), fixture.validatedRun().get());
        assertEquals(0, fixture.actionSubmissions().get());
    }

    @Test
    void pr4TerminalDoesNotSatisfyMaintenanceGenerationAndRequestsTheNewGate() throws Exception {
        var fixture = maintenanceVerificationFixture();
        VerificationRun legacy = fixture.terminal(ActionBackedVerificationRunLauncher.GATE_PROFILE, "pr4");

        StepResult result = fixture.coordinator(fixture.verifications()).advance(
                BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.STAY, result.type());
        assertEquals(BuildSessionStatus.VERIFYING, fixture.currentSession().status());
        assertEquals(BuildSessionPhase.TEST, fixture.currentSession().currentPhase());
        assertEquals(1, fixture.actionSubmissions().get());
        assertNull(fixture.validatedRun().get());
        assertEquals(legacy, fixture.verifications().find(TENANT, legacy.verificationRunId()).orElseThrow());
        VerificationRun requested = fixture.verifications().findLatestForCandidate(
                TENANT, SESSION, fixture.candidate().candidateId(), fixture.candidate().sourceHash(),
                MaintenanceInvestigationProductContract.GATE_PROFILE).orElseThrow();
        assertEquals(VerificationRunStatus.REQUESTED, requested.status());
        assertEquals(ActionBackedVerificationRunLauncher.deriveId(
                        fixture.currentSession(), fixture.candidate(),
                        FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                        MaintenanceInvestigationProductContract.GATE_PROFILE),
                requested.verificationRunId());
    }

    @Test
    void maintenanceFlowRejectsPr4TerminalEvenIfRepositoryViolatesProfileFilter() throws Exception {
        var fixture = maintenanceVerificationFixture();
        VerificationRun legacy = fixture.terminal(ActionBackedVerificationRunLauncher.GATE_PROFILE, "pr4");

        StepResult result = fixture.coordinator(verificationLedger(legacy)).advance(
                BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.BLOCKED, fixture.currentSession().status());
        assertEquals(LedgerBackedCreateCustomerAgentFlowCoordinator.VERIFICATION_REQUIRED,
                fixture.currentSession().terminalCode().orElseThrow());
        assertNull(fixture.validatedRun().get());
        assertEquals(0, fixture.actionSubmissions().get());
    }

    @Test
    void recoveredAgentFlowRejectsSessionOwnedByAnotherProductLineBeforeDispatch() {
        BuildSession wrongLine = withProductLine(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, null, null),
                new ProductLineId("other-line"));
        var sessions = new MutableBuildSessions(wrongLine);
        WorkOrder order = workOrder();
        var coordinator = coordinator(
                sessions,
                workOrderLedger(order),
                workerRunLedger(requestedWorkerRun(order, RUN)),
                emptyCandidateLedger(),
                emptyVerificationLedger(),
                emptyDecisionLedger(),
                (proposal, context) -> {
                    throw new AssertionError("wrong product line must fail before dispatch");
                });

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.advance(BuildPhase.DESIGN_CANDIDATE, stepContext()));
        assertEquals(wrongLine, sessions.current());
    }

    @Test
    void newCoordinatorAfterRestartObservesPersistedDispatchAndDoesNotDispatchAgain() {
        DataSource dataSource = dataSource();
        FactoryDatabaseMigrations.configured(dataSource).migrate();
        ObjectMapper objectMapper = new ObjectMapper();
        BuildSessionRepository sessions = new JdbcBuildSessionRepository(dataSource);
        WorkOrderRepository orders = new JdbcWorkOrderRepository(dataSource, objectMapper);
        WorkerRunRepository runs = new JdbcWorkerRunRepository(dataSource, objectMapper);
        CandidateVersionRepository candidates = new JdbcCandidateVersionRepository(dataSource);
        VerificationRunRepository verifications = new JdbcVerificationRunRepository(dataSource);
        DecisionPointRepository decisions = new JdbcDecisionPointRepository(dataSource, objectMapper);
        seed(sessions, orders, runs);

        AtomicInteger firstRuntimeCalls = new AtomicInteger();
        ActionRuntime firstRuntime = (proposal, context) -> {
            firstRuntimeCalls.incrementAndGet();
            WorkerRunRecord requested = runs.find(TENANT, RUN).orElseThrow();
            dispatchAndAwait(
                    dataSource,
                    objectMapper,
                    runs,
                    orders.find(TENANT, ORDER).orElseThrow(),
                    requested,
                    proposal,
                    context,
                    "fake-session-ledger");
            return ActionExecutionResult.accepted("WAITING_EXTERNAL", Map.of());
        };
        var firstCoordinator = coordinator(
                sessions, orders, runs, candidates, verifications, decisions, firstRuntime);
        var firstFactory = new CreateCustomerAgentFlowFactory(firstCoordinator);
        FlowTestHarness first = FlowTestHarness.create();
        first.submit(firstFactory.create(sessions.find(TENANT, SESSION).orElseThrow(), "flow-run", "trace"));
        first.tick();
        first.tick();
        first.tick();
        for (int tick = 0; tick < 5 && firstRuntimeCalls.get() == 0; tick++) {
            first.tick();
        }
        assertEquals(1, firstRuntimeCalls.get());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, runs.find(TENANT, RUN).orElseThrow().status());

        FlowTestHarness restarted = first.restart();
        first.close();
        AtomicInteger restartedRuntimeCalls = new AtomicInteger();
        var restartedCoordinator = coordinator(
                sessions,
                orders,
                runs,
                candidates,
                verifications,
                decisions,
                (proposal, context) -> {
                    restartedRuntimeCalls.incrementAndGet();
                    return ActionExecutionResult.accepted("UNEXPECTED", Map.of());
                });
        var restartedFactory = new CreateCustomerAgentFlowFactory(restartedCoordinator);
        assertEquals(1, restarted.recoverActiveCount(restartedFactory.registry()));
        restarted.tick();

        assertEquals(0, restartedRuntimeCalls.get());
        assertEquals(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE,
                restarted.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        assertEquals(CreateCustomerAgentFlowFactory.executionContext(
                        SESSION, TENANT.value(), "principal-ledger", "flow-run", "trace", "project-ledger"),
                restarted.activeSnapshot(FLOW_ID).orElseThrow().executionContext());
        restarted.close();
    }

    @Test
    void restartedFlowDoesNotAgeRequeueRunningVerification() {
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        BuildSessionRepository sessions = new JdbcBuildSessionRepository(dataSource);
        WorkOrderRepository orders = new JdbcWorkOrderRepository(dataSource, objectMapper);
        WorkerRunRepository runs = new JdbcWorkerRunRepository(dataSource, objectMapper);
        CandidateVersionRepository candidates = new JdbcCandidateVersionRepository(dataSource);
        VerificationRunRepository verifications = new JdbcVerificationRunRepository(dataSource);
        DecisionPointRepository decisions = new JdbcDecisionPointRepository(dataSource, objectMapper);
        CandidateId candidateId = new CandidateId("candidate-running-recovery");
        ContentHash candidateHash = hash("b");
        BuildSession persisted = session(
                BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash);
        sessions.create(persisted);
        WorkOrder order = workOrder(
                new WorkOrderId("order-running-recovery"),
                LedgerBackedCreateCustomerAgentFlowCoordinator.GENERATE_WORK_ORDER_PHASE,
                Optional.of(candidateId),
                "logical-running-recovery");
        orders.create(order);
        CandidateVersion candidate = new CandidateVersion(
                candidateId, TENANT, SESSION, Optional.empty(),
                new ArtifactReference("artifact:candidate-running-recovery"), candidateHash,
                new ArtifactReference("artifact:dependency-running-recovery"), hash("c"),
                new ArtifactReference("artifact:toolchain-running-recovery"), hash("d"),
                CandidateVersionStatus.GENERATED, order.workOrderId(), NOW.minusSeconds(20));
        candidates.create(candidate);
        VerificationRun requested = new VerificationRun(
                ActionBackedVerificationRunLauncher.deriveId(
                        persisted, candidate, FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH),
                TENANT, SESSION, candidateId, candidateHash,
                LedgerBackedCreateCustomerAgentFlowCoordinator.PR4_GATE_PROFILE,
                candidate.toolchainLockHash(), FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                VerificationRunStatus.REQUESTED,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                0, NOW.minusSeconds(10), NOW.minusSeconds(10));
        verifications.create(requested);
        VerificationRun running = requested.start(NOW);
        assertTrue(verifications.compareAndSet(requested, running));

        BuildSession flowSubmission = session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, null, null);
        var firstFactory = new CreateCustomerAgentFlowFactory((phase, context) ->
                phase == BuildPhase.ACCEPT_REQUIREMENTS
                        ? StepResult.goTo(CreateCustomerAgentFlowFactory.VERIFY_CANDIDATE)
                        : StepResult.stay());
        FlowTestHarness first = FlowTestHarness.create();
        first.clock().setTime(NOW.toEpochMilli());
        first.submit(firstFactory.create(flowSubmission, "flow-run", "trace"));
        first.tick();
        assertEquals(CreateCustomerAgentFlowFactory.VERIFY_CANDIDATE,
                first.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());

        FlowTestHarness restarted = first.restart();
        first.close();
        AtomicInteger actionSubmissions = new AtomicInteger();
        var launcher = new ActionBackedVerificationRunLauncher(
                new JdbcVerificationRunRequestTransaction(dataSource),
                (proposal, context) -> {
                    actionSubmissions.incrementAndGet();
                    return ActionExecutionResult.accepted("VERIFICATION_ACCEPTED", Map.of());
                },
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH);
        Clock manualClock = javaClock(restarted.clock());
        var coordinator = new LedgerBackedCreateCustomerAgentFlowCoordinator(
                sessions, orders, runs, candidates, verifications, launcher, run -> false,
                canonicalOwner(),
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH, decisions,
                new WorkerActionRunRecovery(runs, RunStore.noop()),
                (proposal, context) -> ActionExecutionResult.denied("UNEXPECTED_WORKER_ACTION", "not used"),
                manualClock);
        var restartedFactory = new CreateCustomerAgentFlowFactory(coordinator);
        assertEquals(1, restarted.recoverActiveCount(restartedFactory.registry()));

        restarted.tick();
        assertEquals(0, actionSubmissions.get());
        assertEquals(VerificationRunStatus.RUNNING,
                verifications.find(TENANT, running.verificationRunId()).orElseThrow().status());

        restarted.tick();

        assertEquals(0, actionSubmissions.get());
        VerificationRun recovered = verifications.find(TENANT, running.verificationRunId()).orElseThrow();
        assertEquals(VerificationRunStatus.RUNNING, recovered.status());
        assertEquals(1, recovered.version());
        assertEquals(CreateCustomerAgentFlowFactory.VERIFY_CANDIDATE,
                restarted.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        restarted.close();
    }

    @Test
    void restartedFlowHonorsVerificationCompletedBeforeDeadlineWhenObservedAfterDeadline() {
        CandidateId candidateId = new CandidateId("candidate-verification-before-deadline");
        ContentHash candidateHash = hash("a");
        BuildSession persisted = withDeadline(
                session(BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash),
                NOW);
        MutableBuildSessions sessions = new MutableBuildSessions(persisted);
        CandidateVersion candidate = verificationCandidate(candidateId, candidateHash);
        VerificationRun terminal = terminalVerificationAt(
                candidateId,
                candidateHash,
                VerificationRunStatus.PASSED,
                NOW.minusMillis(1));

        BuildSession flowSubmission = session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, null, null);
        var checkpointFactory = new CreateCustomerAgentFlowFactory((phase, context) ->
                phase == BuildPhase.ACCEPT_REQUIREMENTS
                        ? StepResult.goTo(CreateCustomerAgentFlowFactory.VERIFY_CANDIDATE)
                        : StepResult.stay());
        FlowTestHarness first = FlowTestHarness.create();
        first.clock().setTime(NOW.minusSeconds(1).toEpochMilli());
        first.submit(checkpointFactory.create(
                flowSubmission, "flow-run-verification-deadline", "trace-verification-deadline"));
        first.tick();
        assertEquals(CreateCustomerAgentFlowFactory.VERIFY_CANDIDATE,
                first.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());

        FlowTestHarness restarted = first.restart();
        first.close();
        restarted.advance(2_000);
        var restartedFactory = new CreateCustomerAgentFlowFactory(verificationCoordinator(
                sessions, candidate, terminal, javaClock(restarted.clock())));
        assertEquals(1, restarted.recoverActiveCount(restartedFactory.registry()));

        restarted.tick();

        assertEquals(CreateCustomerAgentFlowFactory.executionContext(
                        SESSION,
                        TENANT.value(),
                        "principal-ledger",
                        "flow-run-verification-deadline",
                        "trace-verification-deadline",
                        "project-ledger"),
                restarted.activeSnapshot(FLOW_ID).orElseThrow().executionContext());
        assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, sessions.current().status());
        assertEquals(BuildSessionPhase.HUMAN_RELEASE_REVIEW, sessions.current().currentPhase());
        restarted.close();
    }

    @Test
    void verificationCompletedAtExactSessionDeadlineFailsWithStableDeadlineOutcome() {
        CandidateId candidateId = new CandidateId("candidate-verification-exact-deadline");
        ContentHash candidateHash = hash("8");
        MutableBuildSessions sessions = new MutableBuildSessions(withDeadline(
                session(BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash),
                NOW));
        CandidateVersion candidate = verificationCandidate(candidateId, candidateHash);
        VerificationRun terminal = terminalVerificationAt(
                candidateId, candidateHash, VerificationRunStatus.PASSED, NOW);

        StepResult result = verificationCoordinator(
                        sessions, candidate, terminal, Clock.fixed(NOW, ZoneOffset.UTC))
                .advance(BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.FAILED, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.BUILD_SESSION_DEADLINE_EXCEEDED,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void nonTerminalVerificationAtSessionDeadlineFailsWithoutResubmission() {
        CandidateId candidateId = new CandidateId("candidate-verification-running-at-deadline");
        ContentHash candidateHash = hash("7");
        MutableBuildSessions sessions = new MutableBuildSessions(withDeadline(
                session(BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash),
                NOW));
        CandidateVersion candidate = verificationCandidate(candidateId, candidateHash);
        VerificationRun requested = new VerificationRun(
                new VerificationRunId("verification-running-at-deadline"),
                TENANT,
                SESSION,
                candidateId,
                candidateHash,
                LedgerBackedCreateCustomerAgentFlowCoordinator.PR4_GATE_PROFILE,
                candidate.toolchainLockHash(),
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                VerificationRunStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.minusSeconds(20),
                NOW.minusSeconds(20));

        StepResult result = verificationCoordinator(
                        sessions, candidate, requested, Clock.fixed(NOW, ZoneOffset.UTC))
                .advance(BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.FAILED, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.BUILD_SESSION_DEADLINE_EXCEEDED,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void terminalVerificationWithPendingActionReconciliationStaysRecoverable() {
        CandidateId candidateId = new CandidateId("candidate-terminal-action-pending");
        ContentHash candidateHash = hash("6");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash));
        CandidateVersion candidate = verificationCandidate(candidateId, candidateHash);
        VerificationRun terminal = terminalVerification(
                candidateId, candidateHash, VerificationRunStatus.PASSED);

        StepResult result = verificationCoordinator(
                        sessions,
                        candidate,
                        terminal,
                        run -> VerificationActionEvidenceOwner.Assessment.pending(),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .advance(BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.STAY, result.type());
        assertEquals(BuildSessionStatus.VERIFYING, sessions.current().status());
        assertTrue(sessions.current().terminalCode().isEmpty());
    }

    @Test
    void terminalVerificationWithUnreconciledActionIsBoundedBySessionDeadline() {
        CandidateId candidateId = new CandidateId("candidate-terminal-action-pending-deadline");
        ContentHash candidateHash = hash("3");
        MutableBuildSessions sessions = new MutableBuildSessions(withDeadline(session(
                BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash), NOW));
        CandidateVersion candidate = verificationCandidate(candidateId, candidateHash);
        VerificationRun terminal = terminalVerificationAt(
                candidateId, candidateHash, VerificationRunStatus.PASSED, NOW.minusMillis(1));

        StepResult result = verificationCoordinator(
                        sessions,
                        candidate,
                        terminal,
                        run -> VerificationActionEvidenceOwner.Assessment.pending(),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .advance(BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.FAILED, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.BUILD_SESSION_DEADLINE_EXCEEDED,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void invalidTerminalActionOwnerStopsRequestedVerificationInManualReview() {
        CandidateId candidateId = new CandidateId("candidate-requested-invalid-action");
        ContentHash candidateHash = hash("5");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash));
        CandidateVersion candidate = verificationCandidate(candidateId, candidateHash);
        VerificationRun requested = requestedVerification(candidate);

        StepResult result = verificationCoordinator(
                        sessions,
                        candidate,
                        requested,
                        run -> new VerificationActionEvidenceOwner.Assessment(
                                VerificationActionEvidenceOwner.Status.INVALID_TERMINAL,
                                "ACTION_FAILED_BEFORE_INTENT"),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .advance(BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.VERIFICATION_ACTION_TERMINAL_MISMATCH,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void orphanedParkGapStopsRequestedVerificationWithoutResubmission() {
        CandidateId candidateId = new CandidateId("candidate-requested-orphaned-action");
        ContentHash candidateHash = hash("4");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash));
        CandidateVersion candidate = verificationCandidate(candidateId, candidateHash);
        VerificationRun requested = requestedVerification(candidate);

        StepResult result = verificationCoordinator(
                        sessions,
                        candidate,
                        requested,
                        run -> new VerificationActionEvidenceOwner.Assessment(
                                VerificationActionEvidenceOwner.Status.ORPHANED,
                                "VERIFICATION_ACTION_ORPHANED_BEFORE_WAITING"),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .advance(BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, sessions.current().status());
        assertEquals("VERIFICATION_ACTION_ORPHANED_BEFORE_WAITING",
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void dispatchActionRunIdIsBoundedForLongFlowAndWorkerIds() {
        var capturedRunId = new AtomicReference<String>();
        var secondRunId = new AtomicReference<String>();
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, null, null));
        WorkOrder order = workOrder();
        WorkerRunRecord requested = requestedWorkerRun(
                order, new WorkerRunId("worker-" + "w".repeat(120)));
        AtomicInteger calls = new AtomicInteger();
        var coordinator = coordinator(
                sessions,
                workOrderLedger(order),
                workerRunLedger(requested),
                emptyCandidateLedger(),
                emptyVerificationLedger(),
                emptyDecisionLedger(),
                (proposal, context) -> {
                    if (calls.getAndIncrement() == 0) {
                        capturedRunId.set(context.runId());
                    } else {
                        secondRunId.set(context.runId());
                    }
                    return ActionExecutionResult.accepted("WAITING_EXTERNAL", Map.of());
                });

        coordinator.advance(BuildPhase.DESIGN_CANDIDATE, stepContext("flow-" + "f".repeat(120)));
        coordinator.advance(BuildPhase.DESIGN_CANDIDATE, stepContext("flow-" + "f".repeat(120)));

        assertTrue(capturedRunId.get().length() <= 64);
        assertTrue(secondRunId.get().length() <= 64);
        org.junit.jupiter.api.Assertions.assertNotEquals(capturedRunId.get(), secondRunId.get());
    }

    @Test
    void failedVerificationPersistsRepairPhaseBeforeGenerateJump() {
        CandidateId candidateId = new CandidateId("candidate-repair");
        ContentHash candidateHash = hash("b");
        MutableBuildSessions sessions = new MutableBuildSessions(
                session(BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash));
        VerificationRun failed = terminalVerification(candidateId, candidateHash, VerificationRunStatus.FAILED);
        VerificationRunRepository verifications = verificationLedger(failed);
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        CandidateVersion candidate = new CandidateVersion(
                candidateId, TENANT, SESSION, Optional.empty(), new ArtifactReference("artifact:candidate-repair"),
                candidateHash, new ArtifactReference("artifact:dependency-lock-repair"), hash("c"),
                new ArtifactReference("artifact:toolchain-lock-repair"), failed.toolchainLockHash(),
                CandidateVersionStatus.GENERATED, ORDER, NOW.minusSeconds(20));
        CandidateVersionRepository candidateLedger = new CandidateVersionRepository() {
            @Override public void create(CandidateVersion value) { throw new UnsupportedOperationException(); }
            @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId id) {
                return Optional.of(candidate);
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenantId, BuildSessionId sessionId, WorkOrderId workOrderId) {
                return Optional.of(candidate);
            }
        };
        var coordinator = coordinator(
                sessions,
                new JdbcWorkOrderRepository(dataSource, objectMapper),
                new JdbcWorkerRunRepository(dataSource, objectMapper),
                candidateLedger,
                verifications,
                new JdbcDecisionPointRepository(dataSource, objectMapper),
                (proposal, context) -> { throw new AssertionError("verification observation must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.VERIFY_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.GOTO, result.type());
        assertEquals(CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE, result.targetStepId());
        assertEquals(BuildSessionStatus.REPAIRING, sessions.current().status());
        assertEquals(BuildSessionPhase.GENERATE_CANDIDATE, sessions.current().currentPhase());
        assertEquals(1, sessions.current().repairRound());
        long repairedVersion = sessions.current().version();
        StepResult recovered = coordinator.advance(BuildPhase.VERIFY_CANDIDATE, stepContext());
        assertEquals(StepResult.Type.GOTO, recovered.type());
        assertEquals(CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE, recovered.targetStepId());
        assertEquals(repairedVersion, sessions.current().version());
    }

    @Test
    void changeRequestPersistsDesignRepairPhaseBeforeDesignJump() {
        CandidateId candidateId = new CandidateId("candidate-change");
        ContentHash candidateHash = hash("c");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                candidateId,
                candidateHash));
        DecisionPoint changeRequest = changeRequest(candidateId, candidateHash);
        DecisionPointRepository decisions = decisionLedger(changeRequest);
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        var coordinator = coordinator(
                sessions,
                new JdbcWorkOrderRepository(dataSource, objectMapper),
                new JdbcWorkerRunRepository(dataSource, objectMapper),
                new JdbcCandidateVersionRepository(dataSource),
                new JdbcVerificationRunRepository(dataSource),
                decisions,
                (proposal, context) -> { throw new AssertionError("review observation must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.HUMAN_REVIEW, stepContext());

        assertEquals(StepResult.Type.GOTO, result.type());
        assertEquals(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE, result.targetStepId());
        assertEquals(BuildSessionStatus.REPAIRING, sessions.current().status());
        assertEquals(BuildSessionPhase.DESIGN_AGENT, sessions.current().currentPhase());
        assertEquals(1, sessions.current().repairRound());
        long repairedVersion = sessions.current().version();
        StepResult recovered = coordinator.advance(BuildPhase.HUMAN_REVIEW, stepContext());
        assertEquals(StepResult.Type.GOTO, recovered.type());
        assertEquals(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE, recovered.targetStepId());
        assertEquals(repairedVersion, sessions.current().version());
    }

    @Test
    void exactSessionDeadlinePersistsFailureBeforeFlowFailure() {
        MutableBuildSessions sessions = new MutableBuildSessions(withDeadline(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, null, null),
                NOW));
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        var coordinator = coordinator(
                sessions,
                new JdbcWorkOrderRepository(dataSource, objectMapper),
                new JdbcWorkerRunRepository(dataSource, objectMapper),
                new JdbcCandidateVersionRepository(dataSource),
                new JdbcVerificationRunRepository(dataSource),
                new JdbcDecisionPointRepository(dataSource, objectMapper),
                (proposal, context) -> { throw new AssertionError("expired session must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.ACCEPT_REQUIREMENTS, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.FAILED, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.BUILD_SESSION_DEADLINE_EXCEEDED,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void openDecisionExpiresAtExactDueAtBeforeFlowFailure() {
        CandidateId candidateId = new CandidateId("candidate-expired");
        ContentHash candidateHash = hash("f");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                candidateId,
                candidateHash));
        MutableDecisionPoints decisions = new MutableDecisionPoints(
                reviewPoint(DecisionPointStatus.OPEN, candidateId, candidateHash, NOW));
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        var coordinator = coordinator(
                sessions,
                new JdbcWorkOrderRepository(dataSource, objectMapper),
                new JdbcWorkerRunRepository(dataSource, objectMapper),
                new JdbcCandidateVersionRepository(dataSource),
                new JdbcVerificationRunRepository(dataSource),
                decisions,
                (proposal, context) -> { throw new AssertionError("review observation must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.HUMAN_REVIEW, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(DecisionPointStatus.EXPIRED, decisions.current().status());
        assertEquals(NOW, decisions.current().decidedAt().orElseThrow());
        assertTrue(decisions.current().terminalDecisionId().isEmpty());
        assertEquals(BuildSessionStatus.BLOCKED, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.DECISION_EXPIRED,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void cancelledDecisionPersistsCancellingWithoutClaimingCancelled() {
        CandidateId candidateId = new CandidateId("candidate-cancelled");
        ContentHash candidateHash = hash("7");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                candidateId,
                candidateHash));
        MutableDecisionPoints decisions = new MutableDecisionPoints(reviewPoint(
                DecisionPointStatus.CANCELLED, candidateId, candidateHash, NOW.plusSeconds(300)));
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        var coordinator = coordinator(
                sessions,
                new JdbcWorkOrderRepository(dataSource, objectMapper),
                new JdbcWorkerRunRepository(dataSource, objectMapper),
                new JdbcCandidateVersionRepository(dataSource),
                new JdbcVerificationRunRepository(dataSource),
                decisions,
                (proposal, context) -> { throw new AssertionError("review observation must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.HUMAN_REVIEW, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.CANCELLING, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.DECISION_CANCELLED,
                sessions.current().terminalCode().orElseThrow());
        assertEquals(NOW, sessions.current().cancellationRequestedAt().orElseThrow());
        long cancellationVersion = sessions.current().version();
        assertEquals(StepResult.Type.FAIL,
                coordinator.advance(BuildPhase.HUMAN_REVIEW, stepContext()).type());
        assertEquals(cancellationVersion, sessions.current().version());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.DECISION_CANCELLED,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void persistedDomainTransitionsRecoverFlowerCheckpointResultsBeforeDeadlineFailure() {
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        WorkOrderRepository orders = new JdbcWorkOrderRepository(dataSource, objectMapper);
        WorkerRunRepository runs = new JdbcWorkerRunRepository(dataSource, objectMapper);
        CandidateVersionRepository candidates = new JdbcCandidateVersionRepository(dataSource);
        VerificationRunRepository verifications = new JdbcVerificationRunRepository(dataSource);
        DecisionPointRepository decisions = new JdbcDecisionPointRepository(dataSource, objectMapper);
        ActionRuntime noDispatch = (proposal, context) -> {
            throw new AssertionError("persisted step projection must not dispatch");
        };
        CandidateId candidateId = new CandidateId("candidate-checkpoint");
        ContentHash candidateHash = hash("9");

        BuildSession afterAccept = session(BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, null, null);
        assertEquals(StepResult.Type.DONE, coordinator(
                new MutableBuildSessions(afterAccept), orders, runs, candidates, verifications, decisions, noDispatch)
                .advance(BuildPhase.ACCEPT_REQUIREMENTS, stepContext()).type());

        BuildSession afterDesign = withBlueprint(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.GENERATE_CANDIDATE, null, null));
        assertEquals(StepResult.Type.DONE, coordinator(
                new MutableBuildSessions(afterDesign), orders, runs, candidates, verifications, decisions, noDispatch)
                .advance(BuildPhase.DESIGN_CANDIDATE, stepContext()).type());

        BuildSession afterGenerate = session(
                BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidateHash);
        assertEquals(StepResult.Type.DONE, coordinator(
                new MutableBuildSessions(afterGenerate), orders, runs, candidates, verifications, decisions, noDispatch)
                .advance(BuildPhase.GENERATE_CANDIDATE, stepContext()).type());

        BuildSession afterVerify = session(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                candidateId,
                candidateHash);
        assertEquals(StepResult.Type.DONE, coordinator(
                new MutableBuildSessions(afterVerify), orders, runs, candidates, verifications, decisions, noDispatch)
                .advance(BuildPhase.VERIFY_CANDIDATE, stepContext()).type());

        BuildSession readyAfterDeadline = withDeadline(session(
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                candidateId,
                candidateHash), NOW);
        MutableBuildSessions readySessions = new MutableBuildSessions(readyAfterDeadline);
        assertEquals(StepResult.Type.DONE, coordinator(
                readySessions, orders, runs, candidates, verifications, decisions, noDispatch)
                .advance(BuildPhase.MARK_CANDIDATE_READY, stepContext()).type());
        assertEquals(BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE, readySessions.current().status());
    }

    @Test
    void unrelatedDecisionTypeCannotAuthorizeRelease() {
        CandidateId candidateId = new CandidateId("candidate-wrong-review-type");
        ContentHash candidateHash = hash("a");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                candidateId,
                candidateHash));
        DecisionPoint unrelated = withDecisionType(
                reviewPoint(DecisionPointStatus.APPROVED, candidateId, candidateHash, NOW.plusSeconds(300)),
                "DESIGN_REVIEW");
        var coordinator = coordinator(
                sessions,
                workOrderLedger(workOrder()),
                workerRunLedger(requestedWorkerRun(workOrder(), RUN)),
                emptyCandidateLedger(),
                emptyVerificationLedger(),
                decisionLedger(unrelated),
                (proposal, context) -> { throw new AssertionError("review must not dispatch"); });

        assertEquals(StepResult.Type.STAY,
                coordinator.advance(BuildPhase.HUMAN_REVIEW, stepContext()).type());
        assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, sessions.current().status());
    }

    @Test
    void sessionCutoffUsesTheActionFirstTerminalWinnerBeforePersistingSessionFailure() {
        WorkOrder order = workOrder();
        String attemptToken = "session-cutoff-token";
        WorkerRunRecord requested = requestedWorkerRun(order, RUN);
        WorkerRunRecord waiting = requested.startDispatch(
                        new DispatchOutboxId("outbox-session-cutoff"),
                        "action-session-cutoff",
                        WorkerDispatchOperationIds.hashAttemptToken(attemptToken),
                        NOW.minusSeconds(20))
                .awaitExternal("external-session-cutoff", NOW.minusSeconds(10));
        ActionProposal proposal = ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                .proposalId("proposal-session-cutoff")
                .requestChannel(io.github.flowerjvm.flower.action.runtime.ActionRequestChannel.INTERNAL)
                .proposerType(io.github.flowerjvm.flower.action.runtime.ActionProposerType.SERVICE)
                .requesterId("factory-builder")
                .reason("test enclosing deadline arbitration")
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, order.workOrderId().value(),
                        WorkerDispatchAction.WORKER_RUN_ID, waiting.workerRunId().value(),
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, requested.version()))
                .idempotencyKey(io.github.flowerjvm.factory.application.action.WorkerDispatchIdempotencyKeys
                        .derive(order, requested))
                .build();
        ActionRun owner = ActionRun.requested(
                        proposal,
                        new ExecutionContext(TENANT.value(), "principal-ledger", "action-session-cutoff", "trace", Map.of()))
                .toBuilder()
                .status(ActionRunStatus.WAITING_EXTERNAL)
                .attemptToken(attemptToken)
                .externalOperationId(waiting.operationId())
                .dueAt(waiting.deadlineAt())
                .updatedAt(NOW.minusSeconds(10))
                .build();
        ActionExecutionResult callbackWinner = WorkerCompletionActionProjection.toActionResult(new WorkerCompletion(
                TENANT,
                waiting.workerRunId(),
                waiting.operationId(),
                attemptToken,
                WorkerRunStatus.SUCCEEDED,
                Optional.of(new ArtifactReference("artifact:session-cutoff-blueprint")),
                Optional.of(hash("b")),
                "WORKER_SUCCEEDED",
                "callback won before enclosing deadline",
                WorkerRetryDisposition.NEVER,
                NOW.minusMillis(1)));

        MutableBuildSessions successSessions = new MutableBuildSessions(withDeadline(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, null, null), NOW));
        MutableWorkerRuns successRuns = new MutableWorkerRuns(waiting);
        OneRunStore callbackRuns = new OneRunStore(owner);
        FirstTerminalRuntime callbackRuntime = new FirstTerminalRuntime(callbackRuns, callbackWinner);
        var successCoordinator = coordinator(
                successSessions,
                workOrderLedger(order),
                successRuns,
                emptyCandidateLedger(),
                emptyVerificationLedger(),
                emptyDecisionLedger(),
                callbackRuntime,
                new WorkerActionRunRecovery(successRuns, callbackRuns, callbackRuntime,
                        io.github.flowerjvm.factory.application.work.WorkerActionDuplicateOwnerLookup.none()));

        assertEquals(StepResult.Type.DONE,
                successCoordinator.advance(BuildPhase.DESIGN_CANDIDATE, stepContext()).type());
        assertEquals(WorkerRunStatus.SUCCEEDED, successRuns.current().status());
        assertEquals(BuildSessionPhase.GENERATE_CANDIDATE, successSessions.current().currentPhase());

        MutableBuildSessions timeoutSessions = new MutableBuildSessions(withDeadline(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, null, null), NOW));
        MutableWorkerRuns timeoutRuns = new MutableWorkerRuns(waiting);
        OneRunStore timeoutActionRuns = new OneRunStore(owner);
        FirstTerminalRuntime timeoutRuntime = new FirstTerminalRuntime(timeoutActionRuns, null);
        var timeoutCoordinator = coordinator(
                timeoutSessions,
                workOrderLedger(order),
                timeoutRuns,
                emptyCandidateLedger(),
                emptyVerificationLedger(),
                emptyDecisionLedger(),
                timeoutRuntime,
                new WorkerActionRunRecovery(timeoutRuns, timeoutActionRuns, timeoutRuntime,
                        io.github.flowerjvm.factory.application.work.WorkerActionDuplicateOwnerLookup.none()));

        assertEquals(StepResult.Type.FAIL,
                timeoutCoordinator.advance(BuildPhase.DESIGN_CANDIDATE, stepContext()).type());
        assertEquals(WorkerRunStatus.TIMED_OUT, timeoutRuns.current().status());
        assertEquals(BuildSessionStatus.FAILED, timeoutSessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.BUILD_SESSION_DEADLINE_EXCEEDED,
                timeoutSessions.current().terminalCode().orElseThrow());
    }

    @Test
    void rejectedDecisionPersistsBlockedOutcomeBeforeFlowFailure() {
        CandidateId candidateId = new CandidateId("candidate-rejected");
        ContentHash candidateHash = hash("8");
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                candidateId,
                candidateHash));
        MutableDecisionPoints decisions = new MutableDecisionPoints(reviewPoint(
                DecisionPointStatus.REJECTED, candidateId, candidateHash, NOW.plusSeconds(300)));
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        var coordinator = coordinator(
                sessions,
                new JdbcWorkOrderRepository(dataSource, objectMapper),
                new JdbcWorkerRunRepository(dataSource, objectMapper),
                new JdbcCandidateVersionRepository(dataSource),
                new JdbcVerificationRunRepository(dataSource),
                decisions,
                (proposal, context) -> { throw new AssertionError("review observation must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.HUMAN_REVIEW, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.BLOCKED, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.DECISION_REJECTED,
                sessions.current().terminalCode().orElseThrow());
    }

    @Test
    void correctableDesignFailurePersistsSamePhaseRepairBeforeDesignJump() {
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, null, null));
        WorkOrder order = workOrder();
        WorkerRunRecord failed = failedWorkerRun(order);
        WorkOrderRepository orders = new WorkOrderRepository() {
            @Override public void create(WorkOrder value) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId id) {
                return Optional.of(order);
            }
            @Override public Optional<WorkOrder> findLatestByBuildSessionAndPhase(
                    TenantId tenantId, BuildSessionId sessionId, String phase) { return Optional.of(order); }
        };
        WorkerRunRepository runs = new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord value) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId id) {
                return Optional.of(failed);
            }
            @Override public Optional<WorkerRunRecord> findLatestByWorkOrder(
                    TenantId tenantId, WorkOrderId id) { return Optional.of(failed); }
            @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) { return false; }
        };
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        var coordinator = coordinator(
                sessions, orders, runs, new JdbcCandidateVersionRepository(dataSource),
                new JdbcVerificationRunRepository(dataSource),
                new JdbcDecisionPointRepository(dataSource, objectMapper),
                (proposal, context) -> { throw new AssertionError("terminal WorkerRun must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.DESIGN_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.GOTO, result.type());
        assertEquals(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE, result.targetStepId());
        assertEquals(BuildSessionStatus.REPAIRING, sessions.current().status());
        assertEquals(BuildSessionPhase.DESIGN_AGENT, sessions.current().currentPhase());
        assertEquals(1, sessions.current().repairRound());
    }

    @Test
    void cancelledWorkerRunPersistsCancellingWithoutClaimingSessionCancelled() {
        MutableBuildSessions sessions = new MutableBuildSessions(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, null, null));
        WorkOrder order = workOrder();
        WorkerRunRecord cancelled = cancelledWorkerRun(order);
        var coordinator = coordinator(
                sessions,
                workOrderLedger(order),
                workerRunLedger(cancelled),
                emptyCandidateLedger(),
                emptyVerificationLedger(),
                emptyDecisionLedger(),
                (proposal, context) -> { throw new AssertionError("terminal WorkerRun must not dispatch"); });

        StepResult result = coordinator.advance(BuildPhase.DESIGN_CANDIDATE, stepContext());

        assertEquals(StepResult.Type.FAIL, result.type());
        assertEquals(BuildSessionStatus.CANCELLING, sessions.current().status());
        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.WORKER_RUN_CANCELLED,
                sessions.current().terminalCode().orElseThrow());
        assertEquals(NOW, sessions.current().cancellationRequestedAt().orElseThrow());
    }

    @Test
    void happyPathConsumesJdbcLedgersAndFinishesAtHashBoundCandidateReady() {
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        BuildSessionRepository sessions = new JdbcBuildSessionRepository(dataSource);
        WorkOrderRepository orders = new JdbcWorkOrderRepository(dataSource, objectMapper);
        WorkerRunRepository runs = new JdbcWorkerRunRepository(dataSource, objectMapper);
        CandidateVersionRepository candidates = new JdbcCandidateVersionRepository(dataSource);
        VerificationRunRepository verifications = new JdbcVerificationRunRepository(dataSource);
        DecisionPointRepository decisionPoints = new JdbcDecisionPointRepository(dataSource, objectMapper);
        var decisionRepository = new JdbcDecisionRepository(dataSource);
        var decisionService = new DecisionRecordingService(
                sessions,
                decisionPoints,
                decisionRepository,
                new JdbcDecisionRecordingTransaction(dataSource, objectMapper));
        BuildSession initial = session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, null, null);
        sessions.create(initial);

        WorkOrder designOrder = workOrder(
                new WorkOrderId("order-happy-design"),
                LedgerBackedCreateCustomerAgentFlowCoordinator.DESIGN_WORK_ORDER_PHASE,
                Optional.empty(),
                "logical-happy-design");
        CandidateId candidateId = new CandidateId("candidate-happy");
        WorkOrder generateOrder = workOrder(
                new WorkOrderId("order-happy-generate"),
                LedgerBackedCreateCustomerAgentFlowCoordinator.GENERATE_WORK_ORDER_PHASE,
                Optional.of(candidateId),
                "logical-happy-generate");
        orders.create(designOrder);
        orders.create(generateOrder);
        ContentHash blueprintHash = hash("3");
        ContentHash candidateHash = hash("4");
        WorkerRunRecord designRun = requestedWorkerRun(
                designOrder, new WorkerRunId("run-happy-design"));
        WorkerRunRecord generateRun = requestedWorkerRun(
                generateOrder, new WorkerRunId("run-happy-generate"));
        runs.create(designRun);
        runs.create(generateRun);
        ContentHash toolchainHash = hash("5");
        candidates.create(new CandidateVersion(
                candidateId, TENANT, SESSION, Optional.empty(), new ArtifactReference("artifact:candidate"),
                candidateHash, new ArtifactReference("artifact:dependency-lock"), hash("6"),
                new ArtifactReference("artifact:toolchain-lock"), toolchainHash,
                CandidateVersionStatus.GENERATED, generateOrder.workOrderId(), NOW.minusSeconds(10)));
        verifications.create(new VerificationRun(
                new VerificationRunId("verification-happy"), TENANT, SESSION, candidateId, candidateHash,
                LedgerBackedCreateCustomerAgentFlowCoordinator.PR4_GATE_PROFILE,
                toolchainHash, FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                VerificationRunStatus.PASSED,
                Optional.of(new ArtifactReference("artifact:verification-result")),
                Optional.of(hash("7")), Optional.of("VERIFIED"),
                Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                Optional.of(NOW.minusSeconds(8)), Optional.of(NOW.minusSeconds(4)), 2,
                NOW.minusSeconds(9), NOW.minusSeconds(4)));
        AtomicInteger actionCalls = new AtomicInteger();
        var coordinator = coordinator(
                sessions, orders, runs, candidates, verifications, decisionPoints,
                (proposal, context) -> {
                    actionCalls.incrementAndGet();
                    WorkerRunId workerRunId = new WorkerRunId(
                            (String) proposal.input().get(WorkerDispatchAction.WORKER_RUN_ID));
                    WorkerRunRecord requested = runs.find(TENANT, workerRunId).orElseThrow();
                    WorkOrder order = orders.find(TENANT, requested.workOrderId()).orElseThrow();
                    boolean design = workerRunId.equals(designRun.workerRunId());
                    WorkerRunRecord waiting = dispatchAndAwait(
                            dataSource,
                            objectMapper,
                            runs,
                            order,
                            requested,
                            proposal,
                            context,
                            "fake-session:" + workerRunId.value());
                    WorkerRunRecord completed = waiting.complete(
                            WorkerRunStatus.SUCCEEDED,
                            Optional.of(new ArtifactReference(
                                    design ? "artifact:blueprint" : "artifact:candidate")),
                            Optional.of(design ? blueprintHash : candidateHash),
                            "WORKER_SUCCEEDED",
                            "Deterministic fake worker succeeded",
                            WorkerRetryDisposition.NEVER,
                            NOW.plusMillis(2));
                    if (!runs.compareAndSet(waiting, completed)) {
                        throw new AssertionError("test ActionRuntime lost terminal WorkerRun CAS");
                    }
                    return ActionExecutionResult.accepted("WAITING_EXTERNAL", Map.of());
                });
        var factory = new CreateCustomerAgentFlowFactory(coordinator);

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(factory.create(initial, "flow-run", "trace"));
            tickUntilPhase(harness, sessions, BuildSessionPhase.HUMAN_RELEASE_REVIEW);
            BuildSession awaitingReview = sessions.find(TENANT, SESSION).orElseThrow();
            assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, awaitingReview.status());
            assertEquals(new ArtifactReference("artifact:blueprint"), awaitingReview.currentBlueprintRef().orElseThrow());
            assertEquals(candidateId, awaitingReview.currentCandidateId().orElseThrow());
            assertEquals(candidateHash, awaitingReview.currentCandidateHash().orElseThrow());

            DecisionPoint open = new DecisionPoint(
                    new DecisionPointId("decision-point-happy"), TENANT, SESSION, "RELEASE_REVIEW",
                    DecisionPointStatus.OPEN, LedgerBackedCreateCustomerAgentFlowCoordinator.CANDIDATE_SUBJECT_TYPE,
                    candidateId.value(), awaitingReview.version(), candidateHash,
                    new ArtifactReference("artifact:review-question"), io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID,
                    Set.of(io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION), 1, new ArtifactReference("artifact:review-policy"),
                    NOW.minusSeconds(1), NOW.plusSeconds(300), Optional.empty(), Optional.empty(), 0);
            decisionPoints.create(open);
            Decision approval = new Decision(
                    new DecisionId("decision-happy"), TENANT, open.decisionPointId(), "review-request-happy",
                    DecisionOutcome.APPROVE, Optional.of("approve"), Optional.of("candidate accepted"),
                    "reviewer", new ArtifactReference("artifact:untrusted-placeholder"), candidateHash,
                    NOW.minusSeconds(60));
            decisionService.record(approval, new DecisionRequestContext(
                    TENANT, NOW, "reviewer", Set.of(io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION),
                    new ArtifactReference("artifact:reviewer-authority")));
            for (int tick = 0; tick < 10 && !harness.latestSnapshot(FLOW_ID).orElseThrow().state().isTerminal(); tick++) {
                harness.tick();
            }

            assertEquals(FlowState.FINISHED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
            BuildSession ready = sessions.find(TENANT, SESSION).orElseThrow();
            assertEquals(BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE, ready.status());
            assertEquals(BuildSessionPhase.HUMAN_RELEASE_REVIEW, ready.currentPhase());
            assertEquals(2, actionCalls.get());
        }
    }

    private static LedgerBackedCreateCustomerAgentFlowCoordinator coordinator(
            BuildSessionRepository sessions,
            WorkOrderRepository orders,
            WorkerRunRepository runs,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            DecisionPointRepository decisions,
            ActionRuntime actionRuntime) {
        return new LedgerBackedCreateCustomerAgentFlowCoordinator(
                sessions,
                orders,
                runs,
                candidates,
                verifications,
                (session, candidate, requestedAt) -> {
                    throw new IllegalStateException("verification launch is not expected in this fixture");
                },
                run -> true,
                canonicalOwner(),
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                decisions,
                new WorkerActionRunRecovery(runs, io.github.flowerjvm.flower.action.runtime.run.RunStore.noop()),
                actionRuntime,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LedgerBackedCreateCustomerAgentFlowCoordinator coordinator(
            BuildSessionRepository sessions,
            WorkOrderRepository orders,
            WorkerRunRepository runs,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            DecisionPointRepository decisions,
            ActionRuntime actionRuntime,
            WorkerActionRunRecovery recovery) {
        return new LedgerBackedCreateCustomerAgentFlowCoordinator(
                sessions, orders, runs, candidates, verifications,
                (session, candidate, requestedAt) -> {
                    throw new IllegalStateException("verification launch is not expected in this fixture");
                },
                run -> true,
                canonicalOwner(),
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                decisions, recovery,
                actionRuntime, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StepContext stepContext() {
        return stepContext("flow-run");
    }

    private static StepContext stepContext(String flowRunId) {
        return (StepContext) Proxy.newProxyInstance(
                StepContext.class.getClassLoader(),
                new Class<?>[] {StepContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "executionContext" -> CreateCustomerAgentFlowFactory.executionContext(
                            SESSION,
                            TENANT.value(),
                            "principal-ledger",
                            flowRunId,
                            "trace",
                            "project-ledger");
                    case "flowId" -> FLOW_ID;
                    case "currentStepId" -> "test";
                    case "stepNo", "elapsedMillis" -> 0;
                    case "timedOut", "hasSignal" -> false;
                    default -> null;
                });
    }

    private static Clock javaClock(io.github.flowerjvm.flower.core.time.ManualClock clock) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                if (!ZoneOffset.UTC.equals(zone)) {
                    throw new IllegalArgumentException("test clock is fixed to UTC");
                }
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(clock.currentTimeMillis());
            }
        };
    }

    private static WorkOrderRepository workOrderLedger(WorkOrder value) {
        return new WorkOrderRepository() {
            @Override public void create(WorkOrder order) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId id) {
                return Optional.of(value);
            }
            @Override public Optional<WorkOrder> findLatestByBuildSessionAndPhase(
                    TenantId tenantId, BuildSessionId sessionId, String phase) {
                return Optional.of(value);
            }
        };
    }

    private static WorkerRunRepository workerRunLedger(WorkerRunRecord value) {
        return new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId id) {
                return Optional.of(value);
            }
            @Override public Optional<WorkerRunRecord> findLatestByWorkOrder(
                    TenantId tenantId, WorkOrderId id) {
                return Optional.of(value);
            }
            @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
                return false;
            }
        };
    }

    private static CandidateVersionRepository emptyCandidateLedger() {
        DataSource dataSource = migratedDataSource();
        return new JdbcCandidateVersionRepository(dataSource);
    }

    private static VerificationRunRepository emptyVerificationLedger() {
        DataSource dataSource = migratedDataSource();
        return new JdbcVerificationRunRepository(dataSource);
    }

    private static DecisionPointRepository emptyDecisionLedger() {
        DataSource dataSource = migratedDataSource();
        return new JdbcDecisionPointRepository(dataSource, new ObjectMapper());
    }

    private static WorkerRunRecord requestedWorkerRun(WorkOrder order, WorkerRunId runId) {
        Instant createdAt = NOW.minusSeconds(30);
        return new WorkerRunRecord(
                runId, TENANT, SESSION, order.workOrderId(), 1, "fake-coding-worker", "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("java"))), WorkerRunStatus.REQUESTED,
                Optional.empty(), WorkerDispatchOperationIds.derive(
                        TENANT, order.workOrderId(), runId, 1, "fake-coding-worker"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW.plusSeconds(1800),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, createdAt, createdAt);
    }

    private static VerificationRun terminalVerification(
            CandidateId candidateId, ContentHash candidateHash, VerificationRunStatus status) {
        return terminalVerificationAt(
                candidateId, candidateHash, status, NOW.minusSeconds(10));
    }

    private static VerificationRun terminalVerificationAt(
            CandidateId candidateId,
            ContentHash candidateHash,
            VerificationRunStatus status,
            Instant completedAt) {
        return new VerificationRun(
                new VerificationRunId("verification-" + candidateId.value()), TENANT, SESSION, candidateId, candidateHash,
                LedgerBackedCreateCustomerAgentFlowCoordinator.PR4_GATE_PROFILE,
                hash("d"), FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH, status,
                Optional.of(new ArtifactReference("artifact:verification-result")),
                Optional.of(hash("e")), Optional.of(status == VerificationRunStatus.PASSED
                        ? "VERIFIED"
                        : "MAVEN_VERIFICATION_FAILED"),
                Optional.of(status == VerificationRunStatus.PASSED
                        ? VerificationDisposition.REVIEW_ELIGIBLE
                        : VerificationDisposition.REPAIR_REQUIRED),
                Optional.of(completedAt.minusSeconds(10)), Optional.of(completedAt), 2,
                completedAt.minusSeconds(20), completedAt);
    }

    private static VerificationRun requestedVerification(CandidateVersion candidate) {
        return new VerificationRun(
                new VerificationRunId("verification-" + candidate.candidateId().value()),
                candidate.tenantId(),
                candidate.buildSessionId(),
                candidate.candidateId(),
                candidate.sourceHash(),
                LedgerBackedCreateCustomerAgentFlowCoordinator.PR4_GATE_PROFILE,
                candidate.toolchainLockHash(),
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                VerificationRunStatus.REQUESTED,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                0,
                NOW.minusSeconds(20),
                NOW.minusSeconds(20));
    }

    private static CandidateVersion verificationCandidate(
            CandidateId candidateId, ContentHash candidateHash) {
        return new CandidateVersion(
                candidateId,
                TENANT,
                SESSION,
                Optional.empty(),
                new ArtifactReference("artifact:" + candidateId.value()),
                candidateHash,
                new ArtifactReference("artifact:dependency:" + candidateId.value()),
                hash("c"),
                new ArtifactReference("artifact:toolchain:" + candidateId.value()),
                hash("d"),
                CandidateVersionStatus.GENERATED,
                ORDER,
                NOW.minusSeconds(30));
    }

    private static LedgerBackedCreateCustomerAgentFlowCoordinator verificationCoordinator(
            BuildSessionRepository sessions,
            CandidateVersion candidate,
            VerificationRun verification,
            Clock clock) {
        return verificationCoordinator(sessions, candidate, verification, canonicalOwner(), clock);
    }

    private static LedgerBackedCreateCustomerAgentFlowCoordinator verificationCoordinator(
            BuildSessionRepository sessions,
            CandidateVersion candidate,
            VerificationRun verification,
            VerificationActionEvidenceOwner evidenceOwner,
            Clock clock) {
        DataSource dataSource = migratedDataSource();
        ObjectMapper objectMapper = new ObjectMapper();
        WorkerRunRepository runs = new JdbcWorkerRunRepository(dataSource, objectMapper);
        CandidateVersionRepository candidates = new CandidateVersionRepository() {
            @Override public void create(CandidateVersion value) { throw new UnsupportedOperationException(); }
            @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId id) {
                return candidate.tenantId().equals(tenantId) && candidate.candidateId().equals(id)
                        ? Optional.of(candidate)
                        : Optional.empty();
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenantId, BuildSessionId sessionId, WorkOrderId workOrderId) {
                return Optional.empty();
            }
        };
        return new LedgerBackedCreateCustomerAgentFlowCoordinator(
                sessions,
                new JdbcWorkOrderRepository(dataSource, objectMapper),
                runs,
                candidates,
                verificationLedger(verification),
                (session, value, requestedAt) -> {
                    throw new AssertionError("deadline observation must not submit verification");
                },
                run -> true,
                evidenceOwner,
                FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                new JdbcDecisionPointRepository(dataSource, objectMapper),
                new WorkerActionRunRecovery(runs, RunStore.noop()),
                (proposal, context) -> {
                    throw new AssertionError("deadline observation must not dispatch a worker Action");
                },
                clock);
    }

    private static VerificationRunRepository verificationLedger(VerificationRun value) {
        return new VerificationRunRepository() {
            @Override public void create(VerificationRun run) { throw new UnsupportedOperationException(); }
            @Override public Optional<VerificationRun> find(TenantId tenantId, VerificationRunId id) {
                return Optional.of(value);
            }
            @Override public Optional<VerificationRun> findLatestForCandidate(
                    TenantId tenantId, BuildSessionId sessionId, CandidateId candidateId,
                    ContentHash candidateHash, String profile) { return Optional.of(value); }
            @Override public Optional<VerificationRun> findLatestForCandidate(
                    TenantId tenantId, BuildSessionId sessionId, CandidateId candidateId,
                    ContentHash candidateHash) { return Optional.of(value); }
            @Override public boolean compareAndSet(VerificationRun expected, VerificationRun next) { return false; }
        };
    }

    private static VerificationActionEvidenceOwner canonicalOwner() {
        return run -> run.status().isTerminal()
                ? VerificationActionEvidenceOwner.Assessment.canonical()
                : VerificationActionEvidenceOwner.Assessment.pending();
    }

    private static DecisionPoint changeRequest(CandidateId candidateId, ContentHash candidateHash) {
        return new DecisionPoint(
                new DecisionPointId("decision-point-change"), TENANT, SESSION, DecisionPoint.RELEASE_REVIEW_TYPE,
                DecisionPointStatus.CHANGES_REQUESTED, LedgerBackedCreateCustomerAgentFlowCoordinator.CANDIDATE_SUBJECT_TYPE,
                candidateId.value(), 0, candidateHash, new ArtifactReference("artifact:question"),
                "factory.review-options.v1", Set.of("factory.review"), 1,
                new ArtifactReference("artifact:policy"), NOW.minusSeconds(30), NOW.plusSeconds(300),
                Optional.of(NOW.minusSeconds(5)), Optional.of(new DecisionId("decision-change")), 1);
    }

    private static DecisionPoint reviewPoint(
            DecisionPointStatus status,
            CandidateId candidateId,
            ContentHash candidateHash,
            Instant dueAt) {
        boolean open = status == DecisionPointStatus.OPEN;
        return new DecisionPoint(
                new DecisionPointId("decision-point-" + status.name().toLowerCase()),
                TENANT,
                SESSION,
                DecisionPoint.RELEASE_REVIEW_TYPE,
                status,
                LedgerBackedCreateCustomerAgentFlowCoordinator.CANDIDATE_SUBJECT_TYPE,
                candidateId.value(),
                0,
                candidateHash,
                new ArtifactReference("artifact:question:" + status.name().toLowerCase()),
                "factory.review-options.v1",
                Set.of("factory.review"),
                1,
                new ArtifactReference("artifact:policy:" + status.name().toLowerCase()),
                NOW.minusSeconds(30),
                dueAt,
                open ? Optional.empty() : Optional.of(NOW.minusSeconds(5)),
                open ? Optional.empty() : Optional.of(new DecisionId("decision-" + status.name().toLowerCase())),
                open ? 0 : 1);
    }

    private static DecisionPointRepository decisionLedger(DecisionPoint value) {
        return new DecisionPointRepository() {
            @Override public void create(DecisionPoint point) { throw new UnsupportedOperationException(); }
            @Override public Optional<DecisionPoint> find(TenantId tenantId, DecisionPointId id) {
                return Optional.of(value);
            }
            @Override public Optional<DecisionPoint> findLatestByBuildSessionAndSubject(
                    TenantId tenantId, BuildSessionId sessionId, String type, String subjectType,
                    String subjectId, ContentHash subjectHash) {
                return value.tenantId().equals(tenantId)
                                && value.buildSessionId().equals(sessionId)
                                && value.type().equals(type)
                                && value.subjectType().equals(subjectType)
                                && value.subjectId().equals(subjectId)
                                && value.subjectHash().equals(subjectHash)
                        ? Optional.of(value)
                        : Optional.empty();
            }
            @Override public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) { return false; }
        };
    }

    private static BuildSession session(
            BuildSessionStatus status,
            BuildSessionPhase phase,
            CandidateId candidateId,
            ContentHash candidateHash) {
        return new BuildSession(
                SESSION, TENANT, new ProjectId("project-ledger"), ProductLineId.AGENT_PACK,
                "request-ledger", "principal-ledger",
                status, phase, new ArtifactReference("artifact:requirements"), hash("0"),
                Optional.empty(), Optional.of("fake-coding-worker"), Optional.empty(),
                Optional.ofNullable(candidateId), Optional.ofNullable(candidateHash), Optional.empty(), 0, 3,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, NOW.minusSeconds(60), NOW.minusSeconds(30));
    }

    private static BuildSession withDeadline(BuildSession value, Instant deadlineAt) {
        return new BuildSession(
                value.buildSessionId(), value.tenantId(), value.projectId(), value.productLineId(),
                value.requestIdempotencyKey(),
                value.createdBy(), value.status(), value.currentPhase(), value.requirementsArtifactRef(),
                value.requirementsHash(), value.selectedManagerWorkerBinding(),
                value.selectedCodingWorkerBinding(), value.currentBlueprintRef(), value.currentCandidateId(),
                value.currentCandidateHash(), value.currentCertificationId(), value.repairRound(),
                value.maxRepairRounds(), value.startedAt(), deadlineAt, value.cancellationRequestedAt(),
                value.terminalCode(), value.terminalMessage(), value.version(), value.createdAt(), value.updatedAt());
    }

    private static BuildSession withBlueprint(BuildSession value) {
        return new BuildSession(
                value.buildSessionId(), value.tenantId(), value.projectId(), value.productLineId(),
                value.requestIdempotencyKey(),
                value.createdBy(), value.status(), value.currentPhase(), value.requirementsArtifactRef(),
                value.requirementsHash(), value.selectedManagerWorkerBinding(),
                value.selectedCodingWorkerBinding(), Optional.of(new ArtifactReference("artifact:blueprint")),
                value.currentCandidateId(), value.currentCandidateHash(), value.currentCertificationId(),
                value.repairRound(), value.maxRepairRounds(), value.startedAt(), value.deadlineAt(),
                value.cancellationRequestedAt(), value.terminalCode(), value.terminalMessage(), value.version(),
                value.createdAt(), value.updatedAt());
    }

    private static BuildSession withProductLine(BuildSession value, ProductLineId productLineId) {
        return new BuildSession(
                value.buildSessionId(), value.tenantId(), value.projectId(), productLineId,
                value.requestIdempotencyKey(), value.createdBy(), value.status(), value.currentPhase(),
                value.requirementsArtifactRef(), value.requirementsHash(),
                value.selectedManagerWorkerBinding(), value.selectedCodingWorkerBinding(),
                value.currentBlueprintRef(), value.currentCandidateId(), value.currentCandidateHash(),
                value.currentCertificationId(), value.repairRound(), value.maxRepairRounds(),
                value.startedAt(), value.deadlineAt(), value.cancellationRequestedAt(),
                value.terminalCode(), value.terminalMessage(), value.version(),
                value.createdAt(), value.updatedAt());
    }

    private static DecisionPoint withDecisionType(DecisionPoint value, String type) {
        return new DecisionPoint(
                value.decisionPointId(), value.tenantId(), value.buildSessionId(), type, value.status(),
                value.subjectType(), value.subjectId(), value.subjectVersion(), value.subjectHash(),
                value.questionArtifactRef(), value.optionsSchemaId(), value.requiredPermissions(),
                value.minimumApprovers(), value.policySnapshotRef(), value.openedAt(), value.dueAt(),
                value.decidedAt(), value.terminalDecisionId(), value.version());
    }

    private static WorkOrder workOrder() {
        return new WorkOrder(
                ORDER, TENANT, SESSION, LedgerBackedCreateCustomerAgentFlowCoordinator.DESIGN_WORK_ORDER_PHASE,
                "Produce a deterministic blueprint", 1, Optional.empty(), Optional.empty(), Optional.empty(),
                new ArtifactReference("artifact:instruction"), hash("1"),
                new ArtifactReference("artifact:input"), hash("2"), "workspace:ledger",
                List.of("inputs"), List.of("candidate"), Set.of(new WorkerCapability("java")),
                "agent-blueprint", "1", new ArtifactReference("artifact:policy"),
                NOW.plusSeconds(1800), 1, "logical-design-ledger", WorkOrderCreatorType.SYSTEM,
                "factory-builder", NOW.minusSeconds(30));
    }

    private static WorkOrder workOrder(
            WorkOrderId id, String phase, Optional<CandidateId> candidateId, String logicalKey) {
        return new WorkOrder(
                id, TENANT, SESSION, phase, "Produce deterministic PR3 output", 1,
                Optional.empty(), candidateId, Optional.empty(),
                new ArtifactReference("artifact:instruction:" + id.value()), hash("1"),
                new ArtifactReference("artifact:input:" + id.value()), hash("2"), "workspace:" + id.value(),
                List.of("inputs"), List.of("candidate"), Set.of(new WorkerCapability("java")),
                "factory.pr3-output", "1", new ArtifactReference("artifact:policy:" + id.value()),
                NOW.plusSeconds(1800), 1, logicalKey, WorkOrderCreatorType.SYSTEM,
                "factory-builder", NOW.minusSeconds(30));
    }

    private static WorkerRunRecord dispatchAndAwait(
            DataSource dataSource,
            ObjectMapper objectMapper,
            WorkerRunRepository runs,
            WorkOrder order,
            WorkerRunRecord requested,
            ActionProposal proposal,
            ExecutionContext context,
            String externalSessionRef) {
        new JdbcRunStore(dataSource, objectMapper).create(ActionRun.requested(proposal, context));
        DispatchOutboxId outboxId = new DispatchOutboxId("outbox-" + requested.workerRunId().value());
        WorkerRunRecord dispatching = requested.startDispatch(
                outboxId,
                context.runId(),
                hash("a"),
                NOW);
        DispatchOutbox outbox = new DispatchOutbox(
                outboxId,
                requested.tenantId(),
                "WORKER_DISPATCH",
                "WORKER_RUN",
                requested.workerRunId().value(),
                requested.operationId(),
                order.inputArtifactManifestRef(),
                DispatchOutboxStatus.PENDING,
                NOW,
                0,
                Optional.empty(),
                0,
                NOW,
                NOW);
        boolean prepared = new JdbcWorkerDispatchTransaction(
                        dataSource,
                        objectMapper,
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .prepare(order, requested, dispatching, outbox);
        if (!prepared) {
            throw new AssertionError("test ActionRuntime could not prepare WorkerRun dispatch");
        }
        WorkerRunRecord waiting = dispatching.awaitExternal(externalSessionRef, NOW.plusMillis(1));
        if (!runs.compareAndSet(dispatching, waiting)) {
            throw new AssertionError("test ActionRuntime lost WAITING_EXTERNAL CAS");
        }
        return waiting;
    }

    private static void tickUntilPhase(
            FlowTestHarness harness, BuildSessionRepository sessions, BuildSessionPhase phase) {
        for (int tick = 0; tick < 20; tick++) {
            harness.tick();
            if (sessions.find(TENANT, SESSION).orElseThrow().currentPhase() == phase) {
                return;
            }
        }
        throw new AssertionError("Flow did not reach durable phase " + phase);
    }

    private static WorkerRunRecord failedWorkerRun(WorkOrder order) {
        return new WorkerRunRecord(
                RUN, TENANT, SESSION, order.workOrderId(), 1, "fake-coding-worker", "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("java"))), WorkerRunStatus.FAILED,
                Optional.of("action-design"), WorkerDispatchOperationIds.derive(
                        TENANT, order.workOrderId(), RUN, 1, "fake-coding-worker"),
                Optional.of(hash("a")), Optional.of("fake-session"),
                Optional.of(new io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId("outbox-design")),
                Optional.of(NOW.minusSeconds(20)), NOW.plusSeconds(1800), Optional.empty(), Optional.empty(),
                Optional.of(NOW.minusSeconds(10)), Optional.empty(), Optional.empty(),
                Optional.of("CORRECTABLE"), Optional.of("repair input"),
                Optional.of(WorkerRetryDisposition.AFTER_CORRECTION), 2, NOW.minusSeconds(30), NOW.minusSeconds(10));
    }

    private static WorkerRunRecord cancelledWorkerRun(WorkOrder order) {
        return new WorkerRunRecord(
                RUN, TENANT, SESSION, order.workOrderId(), 1, "fake-coding-worker", "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("java"))), WorkerRunStatus.CANCELLED,
                Optional.empty(), WorkerDispatchOperationIds.derive(
                        TENANT, order.workOrderId(), RUN, 1, "fake-coding-worker"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW.plusSeconds(1800),
                Optional.empty(), Optional.of(NOW.minusSeconds(15)), Optional.of(NOW.minusSeconds(10)),
                Optional.empty(), Optional.empty(), Optional.of("CANCELLED"),
                Optional.of("worker cancellation confirmed"), Optional.of(WorkerRetryDisposition.NEVER),
                1, NOW.minusSeconds(30), NOW.minusSeconds(10));
    }

    private static DataSource migratedDataSource() {
        DataSource dataSource = dataSource();
        FactoryDatabaseMigrations.configured(dataSource).migrate();
        return dataSource;
    }

    private static void seed(
            BuildSessionRepository sessions, WorkOrderRepository orders, WorkerRunRepository runs) {
        BuildSession session = new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-ledger"),
                ProductLineId.AGENT_PACK,
                "request-ledger",
                "principal-ledger",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                new ArtifactReference("artifact:requirements"),
                hash("0"),
                Optional.empty(),
                Optional.of("fake-coding-worker"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                3,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
        sessions.create(session);
        WorkOrder order = new WorkOrder(
                ORDER,
                TENANT,
                SESSION,
                LedgerBackedCreateCustomerAgentFlowCoordinator.DESIGN_WORK_ORDER_PHASE,
                "Produce a deterministic blueprint",
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new ArtifactReference("artifact:instruction"),
                hash("1"),
                new ArtifactReference("artifact:input"),
                hash("2"),
                "workspace:ledger",
                List.of("inputs"),
                List.of("candidate"),
                Set.of(new WorkerCapability("java")),
                "agent-blueprint",
                "1",
                new ArtifactReference("artifact:policy"),
                NOW.plusSeconds(1800),
                1,
                "logical-design-ledger",
                WorkOrderCreatorType.SYSTEM,
                "factory-builder",
                NOW.minusSeconds(30));
        orders.create(order);
        String operationId = WorkerDispatchOperationIds.derive(
                TENANT, ORDER, RUN, 1, "fake-coding-worker");
        runs.create(new WorkerRunRecord(
                RUN,
                TENANT,
                SESSION,
                ORDER,
                1,
                "fake-coding-worker",
                "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("java"))),
                WorkerRunStatus.REQUESTED,
                Optional.empty(),
                operationId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW.plusSeconds(1800),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.minusSeconds(30),
                NOW.minusSeconds(30)));
    }

    private static ContentHash hash(String value) {
        return new ContentHash(value.repeat(64));
    }

    private static MaintenanceVerificationFixture maintenanceVerificationFixture() throws Exception {
        DataSource dataSource = migratedDataSource();
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ArtifactStore artifacts = new JdbcArtifactStore(dataSource, clock);
        MaintenanceInvestigationProductContract.artifacts(TENANT).forEach(artifacts::store);
        Artifact dependency = maintenanceArtifact(artifacts, "dependency", "dependency".getBytes(StandardCharsets.UTF_8));
        Artifact toolchain = maintenanceArtifact(artifacts, "toolchain", "toolchain".getBytes(StandardCharsets.UTF_8));
        Artifact skill = maintenanceArtifact(artifacts, "skill", "skill".getBytes(StandardCharsets.UTF_8));
        CandidateId candidateId = new CandidateId("candidate-maintenance-profile");
        CandidateVersion candidate = new CandidateVersion(candidateId, TENANT, SESSION, Optional.empty(),
                new ArtifactReference("artifact:maintenance-source"), hash("b"),
                dependency.reference(), dependency.contentHash(), toolchain.reference(), toolchain.contentHash(),
                CandidateVersionStatus.GENERATED, ORDER, NOW.minusSeconds(30));
        var candidates = new JdbcCandidateVersionRepository(dataSource);
        var sessions = new JdbcBuildSessionRepository(dataSource);
        sessions.create(session(
                BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST, candidateId, candidate.sourceHash()));
        var contract = MaintenanceInvestigationProductContract.lock();
        var api = MaintenanceInvestigationProductContract.apiSignatureIndexLock();
        var matrix = MaintenanceInvestigationProductContract.requirementTestMatrixLock();
        var input = mapper.createObjectNode()
                .put("schemaVersion", CodingWorkerInputManifest.SCHEMA_VERSION)
                .put("workOrderId", ORDER.value()).put("buildSessionId", SESSION.value())
                .put("skillId", "flower").put("skillVersion", "0.3.3")
                .put("skillArtifactRef", skill.reference().value()).put("skillHash", skill.contentHash().sha256())
                .put("dependencyLockRef", dependency.reference().value()).put("dependencyLockHash", dependency.contentHash().sha256())
                .put("toolchainLockRef", toolchain.reference().value()).put("toolchainLockHash", toolchain.contentHash().sha256())
                .put("apiSignatureIndexRef", api.reference().value()).put("apiSignatureIndexHash", api.hash().sha256())
                .put("productContractBundleRef", contract.reference().value()).put("productContractBundleHash", contract.hash().sha256())
                .put("gateProfile", MaintenanceInvestigationProductContract.GATE_PROFILE)
                .put("requirementTestMatrixRef", matrix.reference().value()).put("requirementTestMatrixHash", matrix.hash().sha256())
                .put("sourceLockAlgorithmId", CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID)
                .putNull("repairLock");
        Artifact manifest = maintenanceArtifact(artifacts, "input", mapper.writeValueAsBytes(input));
        var orders = new JdbcWorkOrderRepository(dataSource, mapper);
        orders.create(new WorkOrder(ORDER, TENANT, SESSION, BuildSessionPhase.GENERATE_CANDIDATE.id(),
                "generate maintenance pack", 1, Optional.empty(), Optional.empty(), Optional.empty(),
                skill.reference(), skill.contentHash(), manifest.reference(), manifest.contentHash(),
                "workspace-maintenance", List.of("src"), List.of("src"), Set.of(), "schema", "1",
                new ArtifactReference("artifact:policy"), NOW.plusSeconds(600), 1, "maintenance-order-key",
                WorkOrderCreatorType.SERVICE, "factory", NOW.minusSeconds(30)));
        // The real FK chain is BuildSession -> generation WorkOrder -> CandidateVersion.
        candidates.create(candidate);
        var profiles = new AgentPackGenerationVerificationProfiles(orders,
                new WorkerProtocolArtifacts(artifacts, new JacksonWorkerProtocolArtifactDecoder(mapper)), candidates);
        var submissions = new AtomicInteger();
        var launcher = new ActionBackedVerificationRunLauncher(new JdbcVerificationRunRequestTransaction(dataSource),
                (proposal, context) -> {
                    submissions.incrementAndGet();
                    return ActionExecutionResult.accepted("VERIFICATION_ACCEPTED", Map.of());
                }, FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH, profiles);
        return new MaintenanceVerificationFixture(sessions, candidate, candidates, orders,
                new JdbcWorkerRunRepository(dataSource, mapper), new JdbcVerificationRunRepository(dataSource),
                new JdbcDecisionPointRepository(dataSource, mapper), launcher, submissions, new AtomicReference<>());
    }

    private static Artifact maintenanceArtifact(ArtifactStore artifacts, String name, byte[] content) throws Exception {
        Artifact artifact = new Artifact(TENANT, new ArtifactReference("artifact:maintenance-" + name),
                new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))),
                "application/json", content);
        artifacts.store(artifact);
        return artifact;
    }

    private record MaintenanceVerificationFixture(
            BuildSessionRepository sessions, CandidateVersion candidate, CandidateVersionRepository candidates,
            WorkOrderRepository orders, WorkerRunRepository runs, VerificationRunRepository verifications,
            DecisionPointRepository decisions, ActionBackedVerificationRunLauncher launcher,
            AtomicInteger actionSubmissions, AtomicReference<VerificationRunId> validatedRun) {
        BuildSession currentSession() {
            return sessions.find(TENANT, SESSION).orElseThrow();
        }

        VerificationRun terminal(String profile, String suffix) {
            VerificationRun terminal = new VerificationRun(new VerificationRunId("verification-maintenance-" + suffix),
                    TENANT, SESSION, candidate.candidateId(), candidate.sourceHash(), profile,
                    candidate.toolchainLockHash(), FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH,
                    VerificationRunStatus.REQUESTED, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW.minusSeconds(30), NOW.minusSeconds(30))
                    .start(NOW.minusSeconds(20)).complete(VerificationRunStatus.PASSED,
                            new ArtifactReference("artifact:verification-" + suffix), hash("e"), "VERIFIED",
                            VerificationDisposition.REVIEW_ELIGIBLE, NOW.minusSeconds(10));
            verifications.create(terminal);
            return terminal;
        }

        LedgerBackedCreateCustomerAgentFlowCoordinator coordinator(VerificationRunRepository observation) {
            return new LedgerBackedCreateCustomerAgentFlowCoordinator(sessions, orders, runs, candidates,
                    observation, launcher, run -> { validatedRun.set(run.verificationRunId()); return true; },
                    canonicalOwner(), FactoryActionRuntimeConfiguration.PR4_FIXTURE_SET_HASH, decisions,
                    new WorkerActionRunRecovery(runs, RunStore.noop()),
                    (proposal, context) -> { throw new AssertionError("verification cannot dispatch a worker Action"); },
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static final class MutableBuildSessions implements BuildSessionRepository {
        private final AtomicReference<BuildSession> value;

        private MutableBuildSessions(BuildSession initial) { this.value = new AtomicReference<>(initial); }
        @Override public void create(BuildSession session) { throw new UnsupportedOperationException(); }
        @Override public Optional<BuildSession> find(TenantId tenantId, BuildSessionId sessionId) {
            BuildSession current = value.get();
            return current.tenantId().equals(tenantId) && current.buildSessionId().equals(sessionId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public boolean compareAndSet(BuildSession expected, BuildSession next) {
            return value.compareAndSet(expected, next);
        }
        private BuildSession current() { return value.get(); }
    }

    private static final class MutableDecisionPoints implements DecisionPointRepository {
        private final AtomicReference<DecisionPoint> value;

        private MutableDecisionPoints(DecisionPoint initial) { this.value = new AtomicReference<>(initial); }
        @Override public void create(DecisionPoint point) { throw new UnsupportedOperationException(); }
        @Override public Optional<DecisionPoint> find(TenantId tenantId, DecisionPointId id) {
            DecisionPoint current = value.get();
            return current.tenantId().equals(tenantId) && current.decisionPointId().equals(id)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<DecisionPoint> findLatestByBuildSessionAndSubject(
                TenantId tenantId,
                BuildSessionId sessionId,
                String type,
                String subjectType,
                String subjectId,
                ContentHash subjectHash) {
            DecisionPoint current = value.get();
            return current.tenantId().equals(tenantId)
                            && current.buildSessionId().equals(sessionId)
                            && current.type().equals(type)
                            && current.subjectType().equals(subjectType)
                            && current.subjectId().equals(subjectId)
                            && current.subjectHash().equals(subjectHash)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) {
            return value.compareAndSet(expected, next);
        }
        private DecisionPoint current() { return value.get(); }
    }

    private static final class MutableWorkerRuns implements WorkerRunRepository {
        private final AtomicReference<WorkerRunRecord> value;

        private MutableWorkerRuns(WorkerRunRecord initial) { this.value = new AtomicReference<>(initial); }
        @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
        @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId runId) {
            WorkerRunRecord current = value.get();
            return current.tenantId().equals(tenantId) && current.workerRunId().equals(runId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkerRunRecord> findLatestByWorkOrder(
                TenantId tenantId, WorkOrderId orderId) {
            WorkerRunRecord current = value.get();
            return current.tenantId().equals(tenantId) && current.workOrderId().equals(orderId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            return value.compareAndSet(expected, next);
        }
        private WorkerRunRecord current() { return value.get(); }
    }

    private static final class OneRunStore implements RunStore {
        private final AtomicReference<ActionRun> value;

        private OneRunStore(ActionRun initial) { this.value = new AtomicReference<>(initial); }
        @Override public ActionRun create(ActionRun run) { value.set(run); return run; }
        @Override public Optional<ActionRun> find(String runId) {
            ActionRun current = value.get();
            return current.runId().equals(runId) ? Optional.of(current) : Optional.empty();
        }
        @Override public boolean compareAndSet(ActionRun expected, ActionRun next) {
            return value.compareAndSet(expected, next);
        }
        @Override public List<ActionRun> findResumable(String tenantId) {
            ActionRun current = value.get();
            return current.tenantId().equals(tenantId) && !current.status().isTerminal()
                    ? List.of(current) : List.of();
        }
    }

    private static final class FirstTerminalRuntime implements CompletableActionRuntime {
        private final OneRunStore runs;
        private final ActionExecutionResult fixedWinner;

        private FirstTerminalRuntime(OneRunStore runs, ActionExecutionResult fixedWinner) {
            this.runs = runs;
            this.fixedWinner = fixedWinner;
        }
        @Override public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
            throw new AssertionError("deadline reconciliation must not propose a new Action");
        }
        @Override public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }
        @Override public ActionExecutionResult complete(
                String runId, String attemptToken, ActionExecutionResult result) {
            ActionExecutionResult winner = fixedWinner == null ? result : fixedWinner;
            ActionRun current = runs.find(runId).orElseThrow();
            ActionRun next = current.toBuilder()
                    .status(winner.terminalSuccess() ? ActionRunStatus.SUCCEEDED : ActionRunStatus.FAILED)
                    .result(winner)
                    .updatedAt(NOW)
                    .build();
            runs.compareAndSet(current, next);
            return winner;
        }
        @Override public ActionExecutionResult cancel(String runId, String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
