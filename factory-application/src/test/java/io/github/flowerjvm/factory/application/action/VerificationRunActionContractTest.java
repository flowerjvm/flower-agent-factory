package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.VerificationExecutionService;
import io.github.flowerjvm.factory.application.verification.VerificationAttemptTokens;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentStatus;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchRunner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchTransaction;
import io.github.flowerjvm.factory.application.verification.ActionRuntimeVerificationEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.verification.VerificationResult;
import io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VerificationRunActionContractTest {
    private static final TenantId TENANT = new TenantId("tenant-pr4");
    private static final BuildSessionId SESSION = new BuildSessionId("session-pr4");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-pr4");
    private static final VerificationRunId RUN = new VerificationRunId("verification-pr4");
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final ArtifactReference SOURCE_MANIFEST = new ArtifactReference("artifact:source-manifest");
    private static final ArtifactReference RESULT_MANIFEST = new ArtifactReference("artifact:result-manifest");

    @Test
    void controlsFailClosedForUnknownFieldsPermissionResourceAndMutableState() {
        var candidates = new InMemoryCandidates(candidate());
        var runs = new InMemoryVerificationRuns(requestedRun());
        var sessions = new InMemoryBuildSessions(verifyingSession());
        var validator = new VerificationRunActionValidator();
        var policy = new VerificationRunPolicyGate(runs, candidates);
        var guard = new VerificationRunPreExecutionGuard(
                sessions, candidates, runs, hash("4"), Clock.fixed(NOW, ZoneOffset.UTC));
        var proposal = proposal(requestedRun(), input(requestedRun()).toMap());
        var context = context(true, CANDIDATE.value());

        assertTrue(validator.validate(proposal, VerificationRunAction.definition(), context).valid());
        assertFalse(validator.validate(
                        proposal.toBuilder().input(Map.of("unexpected", "value")).build(),
                        VerificationRunAction.definition(),
                        context)
                .valid());
        assertTrue(policy.evaluate(proposal, VerificationRunAction.definition(), context).allowedToExecuteNow());
        assertFalse(policy.evaluate(proposal, VerificationRunAction.definition(), context(false, CANDIDATE.value()))
                .allowedToExecuteNow());
        assertFalse(policy.evaluate(proposal, VerificationRunAction.definition(), context(true, "other-candidate"))
                .allowedToExecuteNow());
        assertTrue(guard.check(
                        proposal,
                        VerificationRunAction.definition(),
                        context,
                        PolicyDecision.allow())
                .allowed());

        sessions.replace(session(BuildSessionStatus.RUNNING, BuildSessionPhase.TEST));
        assertFalse(guard.check(
                        proposal,
                        VerificationRunAction.definition(),
                        context,
                        PolicyDecision.allow())
                .allowed());

        var visibility = new VerificationRunVisibilityScopeResolver(runs, candidates);
        assertEquals("candidate:" + CANDIDATE.value(), visibility.resolve(proposal, context));
        assertThrows(IllegalArgumentException.class, () -> visibility.resolve(proposal, context(true, "other")));

        assertFalse(new FactoryActionInputValidatorRouter(Map.of())
                .validate(proposal, VerificationRunAction.definition(), context)
                .valid());
        assertFalse(new FactoryActionPolicyGateRouter(Map.of())
                .evaluate(proposal, VerificationRunAction.definition(), context)
                .allowedToExecuteNow());
        assertFalse(new FactoryPreExecutionGuardRouter(Map.of())
                .check(proposal, VerificationRunAction.definition(), context, PolicyDecision.allow())
                .allowed());
        assertThrows(IllegalArgumentException.class, () -> new FactoryDuplicateVisibilityScopeRouter(Map.of())
                .resolve(proposal, context));
    }

    @Test
    void launcherUsesDeterministicDomainIdFreshBoundedActionIdAndDoesNotResubmitRunning() {
        var canonical = new AtomicReference<VerificationRun>();
        var calls = new AtomicInteger();
        var actionContexts = new ArrayDeque<ExecutionContext>();
        var launcher = new ActionBackedVerificationRunLauncher(
                requested -> {
                    canonical.compareAndSet(null, requested);
                    return canonical.get();
                },
                (proposal, context) -> {
                    calls.incrementAndGet();
                    actionContexts.add(context);
                    canonical.updateAndGet(run -> run.start(NOW));
                    return ActionExecutionResult.accepted("VERIFICATION_ACCEPTED", Map.of());
                },
                hash("4"));

        VerificationRun first = launcher.ensureRequested(verifyingSession(), candidate(), NOW);
        VerificationRun second = launcher.ensureRequested(verifyingSession(), candidate(), NOW.plusSeconds(1));

        assertEquals(ActionBackedVerificationRunLauncher.deriveId(verifyingSession(), candidate(), hash("4")),
                first.verificationRunId());
        assertEquals(first.verificationRunId(), second.verificationRunId());
        assertEquals(VerificationRunStatus.RUNNING, second.status());
        assertEquals(1, calls.get());
        assertTrue(actionContexts.getFirst().runId().length() <= 64);
    }

    @Test
    void executionServiceCommitsEvidenceBoundFirstTerminalResultAndReturnsCanonicalRetry() {
        ServiceFixture fixture = serviceFixture();

        var first = fixture.service().execute(TENANT, RUN, 0);
        var retry = fixture.service().execute(TENANT, RUN, 0);

        assertTrue(first.executedNow());
        assertFalse(retry.executedNow());
        assertEquals(VerificationRunStatus.PASSED, retry.verificationRun().status());
        assertEquals(RESULT_MANIFEST, retry.verificationRun().resultManifestRef().orElseThrow());
        assertEquals(hash("5"), retry.verificationRun().resultManifestHash().orElseThrow());
        assertEquals(VerificationStableCodes.VERIFIED, retry.verificationRun().terminalCode().orElseThrow());
        assertEquals(VerificationDisposition.REVIEW_ELIGIBLE,
                retry.verificationRun().disposition().orElseThrow());
        assertEquals(1, fixture.verifierCalls().get());
    }

    @Test
    void deferredExecutorDurablyPreparesIntentWithoutRunningVerifier() {
        ServiceFixture fixture = serviceFixture();
        var prepared = new AtomicReference<VerificationDispatchIntent>();
        var executor = new VerificationRunActionExecutor(
                dispatchTransaction(prepared), Clock.fixed(NOW, ZoneOffset.UTC));
        VerificationRun run = fixture.runs().find(TENANT, RUN).orElseThrow();
        var proposal = proposal(run, input(run).toMap());
        var executionContext = new ActionExecutionContext(
                context(true, CANDIDATE.value()),
                proposal,
                VerificationRunAction.definition(),
                proposal.input(),
                "attempt-token");

        var awaiting = executor.dispatchDeferred(executionContext);

        assertEquals(VerificationDispatchOperationIds.derive(input(run)), awaiting.operationId());
        assertEquals(prepared.get().deadlineAt(), awaiting.dueAt());
        assertEquals(VerificationRunStatus.REQUESTED, fixture.runs().find(TENANT, RUN).orElseThrow().status());
        assertEquals(0, fixture.verifierCalls().get());
    }

    @Test
    void deterministicOperationIdIsFixedLengthForLongDomainIds() {
        var longInput = new VerificationRunInput(
                new VerificationRunId("v".repeat(2_048)),
                new CandidateId("c".repeat(2_048)),
                Long.MAX_VALUE);

        String first = VerificationDispatchOperationIds.derive(longInput);

        assertEquals(77, first.length());
        assertEquals(first, VerificationDispatchOperationIds.derive(longInput));
    }

    @Test
    void fullActionPipelineReturnsAcceptedAfterDurableIntentWithoutVerifierWork() {
        ServiceFixture fixture = serviceFixture();
        var prepared = new AtomicReference<VerificationDispatchIntent>();
        var deferredExecutor = new VerificationRunActionExecutor(
                dispatchTransaction(prepared), Clock.fixed(NOW, ZoneOffset.UTC));
        var sessions = new InMemoryBuildSessions(verifyingSession());
        var runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(deferredExecutor)),
                new VerificationRunActionValidator(),
                new VerificationRunPolicyGate(fixture.runs(), fixture.candidates()),
                ApprovalGate.unsupported(),
                new InMemoryDuplicateActionPolicy(
                        new VerificationRunVisibilityScopeResolver(fixture.runs(), fixture.candidates())),
                AuditSink.noop(),
                TraceSink.noop(),
                new InMemoryRunStore(),
                new VerificationRunPreExecutionGuard(
                        sessions,
                        fixture.candidates(),
                        fixture.runs(),
                        hash("4"),
                        Clock.fixed(NOW, ZoneOffset.UTC)));
        VerificationRun run = fixture.runs().find(TENANT, RUN).orElseThrow();

        var result = runtime.handle(
                proposal(run, input(run).toMap()),
                context(true, CANDIDATE.value()));

        assertEquals(ActionExecutionStatus.ACCEPTED, result.status());
        assertEquals(RUN, prepared.get().verificationRunId());
        assertEquals(VerificationRunStatus.REQUESTED, fixture.runs().find(TENANT, RUN).orElseThrow().status());
        assertEquals(0, fixture.verifierCalls().get());
    }

    @Test
    void deferredExecutorReplayReturnsTheSamePreparedIntent() {
        ServiceFixture fixture = serviceFixture();
        var prepared = new AtomicReference<VerificationDispatchIntent>();
        var executor = new VerificationRunActionExecutor(
                dispatchTransaction(prepared), Clock.fixed(NOW, ZoneOffset.UTC));
        VerificationRun run = fixture.runs().find(TENANT, RUN).orElseThrow();
        var proposal = proposal(run, input(run).toMap());
        var executionContext = new ActionExecutionContext(
                context(true, CANDIDATE.value()), proposal, VerificationRunAction.definition(),
                proposal.input(), "attempt-token");

        var first = executor.dispatchDeferred(executionContext);
        var second = executor.dispatchDeferred(executionContext);

        assertEquals(first, second);
        assertEquals(0, fixture.verifierCalls().get());
    }

    @Test
    void runnerParksThePrepareToWaitingRaceThenCompletesTheSameActionAttempt() {
        ServiceFixture fixture = serviceFixture();
        MutableClock clock = new MutableClock(NOW);
        InMemoryDispatchIntents intents = new InMemoryDispatchIntents();
        InMemoryRunStore actionRuns = new InMemoryRunStore();
        String attemptToken = "attempt-token";
        VerificationRun domain = fixture.runs().find(TENANT, RUN).orElseThrow();
        VerificationDispatchIntent intent = pendingIntent(attemptToken, NOW.minusSeconds(1));
        intents.create(intent);
        actionRuns.create(actionRun(domain, ActionRunStatus.RUNNING, attemptToken, null));
        TestCompletableRuntime runtime = new TestCompletableRuntime(actionRuns, clock);
        VerificationDispatchRunner runner = runner(fixture, intents, actionRuns, runtime, clock);

        assertTrue(runner.tickOnce());
        assertEquals(VerificationDispatchIntentStatus.UNCERTAIN,
                intents.find(intent.operationId()).orElseThrow().status());
        assertEquals(0, fixture.verifierCalls().get());

        ActionRun running = actionRuns.find("action-run-pr4").orElseThrow();
        assertTrue(actionRuns.compareAndSet(running, running.toBuilder()
                .version(running.version() + 1)
                .status(ActionRunStatus.WAITING_EXTERNAL)
                .currentStage("EXECUTE")
                .externalOperationId(intent.operationId())
                .updatedAt(clock.instant())
                .build()));
        clock.advance(Duration.ofSeconds(29));
        assertFalse(runner.tickOnce());
        assertEquals(0, fixture.verifierCalls().get());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(runner.tickOnce());
        assertEquals(1, fixture.verifierCalls().get());
        assertEquals(ActionRunStatus.SUCCEEDED,
                actionRuns.find("action-run-pr4").orElseThrow().status());
        assertEquals(VerificationDispatchIntentStatus.COMPLETED,
                intents.find(intent.operationId()).orElseThrow().status());
    }

    @Test
    void expiredRunningIntentIsClassifiedWithoutVerifierReexecutionAndCannotBeReopened() {
        ServiceFixture fixture = serviceFixture();
        MutableClock clock = new MutableClock(NOW);
        InMemoryDispatchIntents intents = new InMemoryDispatchIntents();
        InMemoryRunStore actionRuns = new InMemoryRunStore();
        String attemptToken = "attempt-token";
        VerificationRun requested = fixture.runs().find(TENANT, RUN).orElseThrow();
        VerificationDispatchIntent pending = pendingIntent(attemptToken, NOW.minusSeconds(1));
        VerificationDispatchIntent running = pending.claim("old-claim", NOW, Duration.ofMinutes(30));
        intents.create(running);
        actionRuns.create(actionRun(requested, ActionRunStatus.WAITING_EXTERNAL, attemptToken, null));
        TestCompletableRuntime runtime = new TestCompletableRuntime(actionRuns, clock);
        VerificationDispatchRunner runner = runner(fixture, intents, actionRuns, runtime, clock);

        clock.advance(Duration.ofMinutes(30).minusNanos(1));
        assertFalse(runner.tickOnce());
        assertEquals(0, fixture.verifierCalls().get());

        clock.advance(Duration.ofNanos(1));
        assertTrue(runner.tickOnce());
        VerificationDispatchIntent orphaned = intents.find(pending.operationId()).orElseThrow();
        assertEquals(VerificationDispatchIntentStatus.ORPHANED, orphaned.status());
        assertEquals(VerificationDispatchRunner.RUNNER_LEASE_EXPIRED, orphaned.lastCode().orElseThrow());
        assertEquals(0, fixture.verifierCalls().get());
        assertEquals(0, runtime.completions());

        VerificationRun started = requested.start(clock.instant());
        assertTrue(fixture.runs().compareAndSet(requested, started));
        VerificationRun terminal = started.complete(
                VerificationRunStatus.PASSED,
                RESULT_MANIFEST,
                hash("5"),
                VerificationStableCodes.VERIFIED,
                VerificationDisposition.REVIEW_ELIGIBLE,
                clock.instant().plusNanos(1));
        assertTrue(fixture.runs().compareAndSet(started, terminal));
        ActionRun waiting = actionRuns.find("action-run-pr4").orElseThrow();
        assertTrue(actionRuns.compareAndSet(waiting, waiting.toBuilder()
                .version(waiting.version() + 1)
                .status(ActionRunStatus.SUCCEEDED)
                .currentStage("COMPLETE")
                .result(ActionExecutionResult.succeeded(terminalOutput(terminal)))
                .updatedAt(clock.instant().plusNanos(1))
                .build()));

        var owner = new ActionRuntimeVerificationEvidenceOwner(
                intents,
                actionRuns,
                fixture.candidates(),
                (tenant, action, key) -> Optional.of("action-run-pr4"));
        assertEquals(VerificationActionEvidenceOwner.Status.ORPHANED, owner.assess(terminal).status());
        assertEquals(0, fixture.verifierCalls().get());
    }

    @Test
    void expiredRunningIntentWithTerminalDomainOnlyCompletesTheParkedAction() {
        ServiceFixture fixture = serviceFixture();
        MutableClock clock = new MutableClock(NOW);
        InMemoryDispatchIntents intents = new InMemoryDispatchIntents();
        InMemoryRunStore actionRuns = new InMemoryRunStore();
        String attemptToken = "attempt-token";
        VerificationRun requested = fixture.runs().find(TENANT, RUN).orElseThrow();
        VerificationRun started = requested.start(NOW);
        assertTrue(fixture.runs().compareAndSet(requested, started));
        VerificationRun terminal = started.complete(
                VerificationRunStatus.PASSED,
                RESULT_MANIFEST,
                hash("5"),
                VerificationStableCodes.VERIFIED,
                VerificationDisposition.REVIEW_ELIGIBLE,
                NOW.plusNanos(1));
        assertTrue(fixture.runs().compareAndSet(started, terminal));
        VerificationDispatchIntent pending = pendingIntent(attemptToken, NOW.minusSeconds(31));
        intents.create(pending.claim("old-claim", NOW.minusSeconds(30), Duration.ofSeconds(30)));
        actionRuns.create(actionRun(requested, ActionRunStatus.WAITING_EXTERNAL, attemptToken, null));
        TestCompletableRuntime runtime = new TestCompletableRuntime(actionRuns, clock);
        VerificationDispatchRunner runner = runner(fixture, intents, actionRuns, runtime, clock);

        assertTrue(runner.tickOnce());

        assertEquals(0, fixture.verifierCalls().get());
        assertEquals(1, runtime.completions());
        assertEquals(ActionRunStatus.SUCCEEDED,
                actionRuns.find("action-run-pr4").orElseThrow().status());
        assertEquals(VerificationDispatchIntentStatus.COMPLETED,
                intents.find(pending.operationId()).orElseThrow().status());
    }

    @Test
    void newlyClaimedIntentNeverReexecutesAnAlreadyRunningVerificationDomain() {
        ServiceFixture fixture = serviceFixture();
        MutableClock clock = new MutableClock(NOW);
        InMemoryDispatchIntents intents = new InMemoryDispatchIntents();
        InMemoryRunStore actionRuns = new InMemoryRunStore();
        String attemptToken = "attempt-token";
        VerificationRun requested = fixture.runs().find(TENANT, RUN).orElseThrow();
        VerificationRun alreadyRunning = requested.start(NOW);
        assertTrue(fixture.runs().compareAndSet(requested, alreadyRunning));
        assertThrows(IllegalStateException.class, () ->
                fixture.service().execute(TENANT, RUN, alreadyRunning.version()));
        assertEquals(0, fixture.verifierCalls().get());
        VerificationDispatchIntent pending = pendingIntent(attemptToken, NOW.minusSeconds(1));
        intents.create(pending);
        actionRuns.create(actionRun(requested, ActionRunStatus.WAITING_EXTERNAL, attemptToken, null));
        TestCompletableRuntime runtime = new TestCompletableRuntime(actionRuns, clock);

        assertTrue(runner(fixture, intents, actionRuns, runtime, clock).tickOnce());

        VerificationDispatchIntent orphaned = intents.find(pending.operationId()).orElseThrow();
        assertEquals(VerificationDispatchIntentStatus.ORPHANED, orphaned.status());
        assertEquals(VerificationDispatchRunner.DOMAIN_RUNNING_UNCERTAIN,
                orphaned.lastCode().orElseThrow());
        assertEquals(0, fixture.verifierCalls().get());
        assertEquals(0, runtime.completions());
    }

    @Test
    void restartReconcilesActionCompletionBeforeIntentTerminalCasWithoutVerifierWork() {
        ServiceFixture fixture = serviceFixture();
        MutableClock clock = new MutableClock(NOW);
        InMemoryDispatchIntents intents = new InMemoryDispatchIntents();
        InMemoryRunStore actionRuns = new InMemoryRunStore();
        String attemptToken = "attempt-token";
        VerificationRun requested = fixture.runs().find(TENANT, RUN).orElseThrow();
        VerificationRun started = requested.start(NOW);
        assertTrue(fixture.runs().compareAndSet(requested, started));
        VerificationRun terminal = started.complete(
                VerificationRunStatus.PASSED,
                RESULT_MANIFEST,
                hash("5"),
                VerificationStableCodes.VERIFIED,
                VerificationDisposition.REVIEW_ELIGIBLE,
                NOW.plusNanos(1));
        assertTrue(fixture.runs().compareAndSet(started, terminal));
        VerificationDispatchIntent pending = pendingIntent(attemptToken, NOW.minusSeconds(31));
        intents.create(pending.claim("crashed-owner", NOW.minusSeconds(30), Duration.ofSeconds(30)));
        actionRuns.create(actionRun(
                requested,
                ActionRunStatus.SUCCEEDED,
                attemptToken,
                ActionExecutionResult.succeeded(terminalOutput(terminal))).toBuilder()
                .externalOperationId(pending.operationId())
                .build());
        TestCompletableRuntime runtime = new TestCompletableRuntime(actionRuns, clock);

        assertTrue(runner(fixture, intents, actionRuns, runtime, clock).tickOnce());

        assertEquals(VerificationDispatchIntentStatus.COMPLETED,
                intents.find(pending.operationId()).orElseThrow().status());
        assertEquals(0, fixture.verifierCalls().get());
        assertEquals(0, runtime.completions());
    }

    private static ServiceFixture serviceFixture() {
        var runs = new InMemoryVerificationRuns(requestedRun());
        var candidates = new InMemoryCandidates(candidate());
        var artifacts = new InMemoryArtifacts();
        artifacts.add(new Artifact(
                TENANT, SOURCE_MANIFEST, hash("9"), "application/json", "{}".getBytes()));
        artifacts.add(new Artifact(
                TENANT, RESULT_MANIFEST, hash("5"), "application/json", "{}".getBytes()));
        artifacts.add(new Artifact(
                TENANT, candidate().dependencyLockRef(), candidate().dependencyLockHash(),
                "application/json", "dependency".getBytes()));
        var calls = new AtomicInteger();
        var service = new VerificationExecutionService(
                runs,
                candidates,
                artifacts,
                request -> {
                    calls.incrementAndGet();
                    assertEquals(RUN, request.verificationRunId());
                    assertEquals(SOURCE_MANIFEST, request.candidateManifest());
                    assertEquals(hash("1"), request.candidateHash());
                    return new VerificationResult(
                            VerificationStatus.PASSED,
                            VerificationDisposition.REVIEW_ELIGIBLE,
                            List.of(VerificationStableCodes.VERIFIED),
                            RESULT_MANIFEST,
                            hash("5"),
                            List.of(RESULT_MANIFEST));
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ArtifactReference("artifact:fixture-set"));
        return new ServiceFixture(service, runs, candidates, calls);
    }

    private static VerificationDispatchTransaction dispatchTransaction(
            AtomicReference<VerificationDispatchIntent> prepared) {
        return new VerificationDispatchTransaction() {
            @Override
            public VerificationDispatchIntent prepare(
                    TenantId tenantId,
                    VerificationRunInput input,
                    String actionRunId,
                    String attemptTokenHash,
                    Instant preparedAt) {
                VerificationDispatchIntent created = VerificationDispatchIntent.pending(
                        VerificationDispatchOperationIds.derive(input),
                        tenantId,
                        input.verificationRunId(),
                        input.candidateId(),
                        input.expectedVerificationRunVersion(),
                        actionRunId,
                        attemptTokenHash,
                        NOW.plusSeconds(600),
                        preparedAt);
                prepared.compareAndSet(null, created);
                VerificationDispatchIntent canonical = prepared.get();
                if (!canonical.sameImmutableIdentity(created)) {
                    throw new IllegalStateException("different replay owner");
                }
                return canonical;
            }

            @Override
            public Optional<VerificationDispatchIntent> findExact(
                    TenantId tenantId,
                    VerificationRunInput input,
                    String actionRunId,
                    String attemptTokenHash) {
                return Optional.ofNullable(prepared.get());
            }
        };
    }

    private static VerificationDispatchRunner runner(
            ServiceFixture fixture,
            VerificationDispatchIntentRepository intents,
            InMemoryRunStore actionRuns,
            CompletableActionRuntime runtime,
            Clock clock) {
        return new VerificationDispatchRunner(
                intents,
                fixture.runs(),
                fixture.candidates(),
                fixture.service(),
                runtime,
                actionRuns,
                clock,
                Duration.ofMinutes(30),
                Duration.ofSeconds(30));
    }

    private static VerificationDispatchIntent pendingIntent(String attemptToken, Instant createdAt) {
        VerificationRunInput input = new VerificationRunInput(RUN, CANDIDATE, 0);
        return VerificationDispatchIntent.pending(
                VerificationDispatchOperationIds.derive(input),
                TENANT,
                RUN,
                CANDIDATE,
                0,
                "action-run-pr4",
                VerificationAttemptTokens.hash(attemptToken),
                NOW.plusSeconds(3_600),
                createdAt);
    }

    private static ActionRun actionRun(
            VerificationRun domain,
            ActionRunStatus status,
            String attemptToken,
            ActionExecutionResult result) {
        VerificationRunInput input = new VerificationRunInput(RUN, CANDIDATE, 0);
        return ActionRun.builder()
                .runId("action-run-pr4")
                .version(4)
                .tenantId(TENANT.value())
                .userId("factory-verifier")
                .traceId("trace-pr4")
                .contextMetadata(Map.of(
                        "resource.type", VerificationRunAction.RESOURCE_TYPE,
                        "resource.id", CANDIDATE.value()))
                .actionId(VerificationRunAction.ACTION_ID)
                .proposalId("proposal-pr4")
                .requesterId("factory-verifier")
                .input(input.toMap())
                .duplicateKey(VerificationRunIdempotencyKeys.derive(domain, candidate(), 0))
                .status(status)
                .currentStage(status == ActionRunStatus.WAITING_EXTERNAL ? "EXECUTE" : "DISPATCH")
                .attemptToken(attemptToken)
                .externalOperationId(status == ActionRunStatus.WAITING_EXTERNAL
                        ? VerificationDispatchOperationIds.derive(input)
                        : null)
                .result(result)
                .createdAt(NOW.minusSeconds(2))
                .updatedAt(NOW.minusSeconds(1))
                .build();
    }

    private static Map<String, Object> terminalOutput(VerificationRun run) {
        return Map.of(
                VerificationRunAction.VERIFICATION_RUN_ID, run.verificationRunId().value(),
                "verificationStatus", run.status().name(),
                "terminalCode", run.terminalCode().orElseThrow(),
                "resultManifestRef", run.resultManifestRef().orElseThrow().value(),
                "executedNow", true);
    }

    private static VerificationRunInput input(VerificationRun run) {
        return new VerificationRunInput(run.verificationRunId(), run.candidateId(), run.version());
    }

    private static ActionProposal proposal(VerificationRun run, Map<String, Object> input) {
        return ActionProposal.builder(VerificationRunAction.ACTION_ID)
                .proposalId("proposal-pr4")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-verifier")
                .input(input)
                .idempotencyKey(VerificationRunIdempotencyKeys.derive(run, candidate()))
                .build();
    }

    private static ExecutionContext context(boolean permission, String candidateId) {
        return context(permission, candidateId, "action-run-pr4");
    }

    private static ExecutionContext context(boolean permission, String candidateId, String actionRunId) {
        return new ExecutionContext(
                TENANT.value(),
                "factory-verifier",
                actionRunId,
                "trace-pr4",
                Map.of(
                        "actor.permissions", permission ? Set.of(VerificationRunAction.PERMISSION) : Set.of(),
                        "resource.type", VerificationRunAction.RESOURCE_TYPE,
                        "resource.id", candidateId));
    }

    private static VerificationRun requestedRun() {
        return new VerificationRun(
                RUN,
                TENANT,
                SESSION,
                CANDIDATE,
                hash("1"),
                ActionBackedVerificationRunLauncher.GATE_PROFILE,
                hash("3"),
                hash("4"),
                VerificationRunStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.minusSeconds(1),
                NOW.minusSeconds(1));
    }

    private static CandidateVersion candidate() {
        return new CandidateVersion(
                CANDIDATE,
                TENANT,
                SESSION,
                Optional.empty(),
                SOURCE_MANIFEST,
                hash("1"),
                new ArtifactReference("artifact:dependency-lock"),
                hash("2"),
                new ArtifactReference("artifact:toolchain-lock"),
                hash("3"),
                CandidateVersionStatus.GENERATED,
                new WorkOrderId("work-order-pr4"),
                NOW.minusSeconds(2));
    }

    private static BuildSession verifyingSession() {
        return session(BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST);
    }

    private static BuildSession session(BuildSessionStatus status, BuildSessionPhase phase) {
        return new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-pr4"),
                ProductLineId.AGENT_PACK,
                "request-pr4",
                "principal-pr4",
                status,
                phase,
                new ArtifactReference("artifact:requirements"),
                hash("0"),
                Optional.empty(),
                Optional.of("coding-worker"),
                Optional.empty(),
                Optional.of(CANDIDATE),
                Optional.of(hash("1")),
                Optional.empty(),
                0,
                2,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.minusSeconds(60),
                NOW.minusSeconds(1));
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }

    private record ServiceFixture(
            VerificationExecutionService service,
            InMemoryVerificationRuns runs,
            InMemoryCandidates candidates,
            AtomicInteger verifierCalls) {}

    private static final class InMemoryBuildSessions implements BuildSessionRepository {
        private BuildSession value;

        private InMemoryBuildSessions(BuildSession value) {
            this.value = value;
        }

        void replace(BuildSession next) {
            value = next;
        }

        @Override
        public void create(BuildSession session) {
            if (value != null) {
                throw new IllegalStateException("duplicate session");
            }
            value = session;
        }

        @Override
        public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
            return value != null && value.tenantId().equals(tenantId) && value.buildSessionId().equals(buildSessionId)
                    ? Optional.of(value)
                    : Optional.empty();
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            if (!expected.equals(value)) {
                return false;
            }
            value = next;
            return true;
        }
    }

    private static final class InMemoryCandidates implements CandidateVersionRepository {
        private final CandidateVersion candidate;

        private InMemoryCandidates(CandidateVersion candidate) {
            this.candidate = candidate;
        }

        @Override
        public void create(CandidateVersion candidateVersion) {
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
                TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId createdByWorkOrderId) {
            return Optional.empty();
        }
    }

    private static final class InMemoryVerificationRuns implements VerificationRunRepository {
        private final Map<String, VerificationRun> records = new ConcurrentHashMap<>();

        private InMemoryVerificationRuns(VerificationRun initial) {
            records.put(initial.verificationRunId().value(), initial);
        }

        @Override
        public void create(VerificationRun verificationRun) {
            if (records.putIfAbsent(verificationRun.verificationRunId().value(), verificationRun) != null) {
                throw new IllegalStateException("duplicate verification run");
            }
        }

        @Override
        public Optional<VerificationRun> find(TenantId tenantId, VerificationRunId verificationRunId) {
            return Optional.ofNullable(records.get(verificationRunId.value()))
                    .filter(run -> run.tenantId().equals(tenantId));
        }

        @Override
        public Optional<VerificationRun> findLatestForCandidate(
                TenantId tenantId,
                BuildSessionId buildSessionId,
                CandidateId candidateId,
                ContentHash candidateHash,
                String gateProfile) {
            return records.values().stream()
                    .filter(run -> run.tenantId().equals(tenantId)
                            && run.buildSessionId().equals(buildSessionId)
                            && run.candidateId().equals(candidateId)
                            && run.candidateHash().equals(candidateHash)
                            && run.gateProfile().equals(gateProfile))
                    .findFirst();
        }

        @Override
        public synchronized boolean compareAndSet(VerificationRun expected, VerificationRun next) {
            VerificationRun current = records.get(expected.verificationRunId().value());
            if (!expected.equals(current) || next.version() != expected.version() + 1) {
                return false;
            }
            records.put(next.verificationRunId().value(), next);
            return true;
        }
    }

    private static final class InMemoryArtifacts implements ArtifactStore {
        private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();

        void add(Artifact artifact) {
            artifacts.put(artifact.reference().value(), artifact);
        }

        @Override
        public ArtifactReference store(Artifact artifact) {
            add(artifact);
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(artifacts.get(reference.value()))
                    .filter(artifact -> artifact.tenantId().equals(tenantId));
        }
    }

    private static final class InMemoryDispatchIntents implements VerificationDispatchIntentRepository {
        private VerificationDispatchIntent value;

        @Override
        public synchronized void create(VerificationDispatchIntent intent) {
            if (value != null) {
                throw new IllegalStateException("duplicate intent");
            }
            value = intent;
        }

        @Override
        public synchronized Optional<VerificationDispatchIntent> find(String operationId) {
            return value != null && value.operationId().equals(operationId)
                    ? Optional.of(value)
                    : Optional.empty();
        }

        @Override
        public synchronized Optional<VerificationDispatchIntent> findLatest(
                TenantId tenantId, VerificationRunId verificationRunId) {
            return value != null
                            && value.tenantId().equals(tenantId)
                            && value.verificationRunId().equals(verificationRunId)
                    ? Optional.of(value)
                    : Optional.empty();
        }

        @Override
        public synchronized Optional<VerificationDispatchIntent> claimNext(
                Instant now, Duration lease, String claimToken) {
            if (value == null) {
                return Optional.empty();
            }
            boolean claimable = value.status() == VerificationDispatchIntentStatus.PENDING
                    || (value.status() == VerificationDispatchIntentStatus.UNCERTAIN
                            && value.leaseUntil().filter(retryAt -> !now.isBefore(retryAt)).isPresent());
            if (!claimable) {
                return Optional.empty();
            }
            value = value.claim(claimToken, now, lease);
            return Optional.of(value);
        }

        @Override
        public synchronized Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
                Instant now, Duration lease, String claimToken) {
            if (value == null
                    || value.status() != VerificationDispatchIntentStatus.RUNNING
                    || value.leaseUntil().filter(expiry -> !now.isBefore(expiry)).isEmpty()) {
                return Optional.empty();
            }
            value = value.claim(claimToken, now, lease);
            return Optional.of(value);
        }

        @Override
        public synchronized boolean compareAndSet(
                VerificationDispatchIntent expected, VerificationDispatchIntent next) {
            if (!expected.equals(value)) {
                return false;
            }
            value = next;
            return true;
        }
    }

    private static final class TestCompletableRuntime implements CompletableActionRuntime {
        private final InMemoryRunStore actionRuns;
        private final Clock clock;
        private final AtomicInteger completions = new AtomicInteger();

        private TestCompletableRuntime(InMemoryRunStore actionRuns, Clock clock) {
            this.actionRuns = actionRuns;
            this.clock = clock;
        }

        int completions() {
            return completions.get();
        }

        @Override
        public ActionExecutionResult complete(
                String runId, String attemptToken, ActionExecutionResult result) {
            ActionRun current = actionRuns.find(runId).orElseThrow();
            if (current.status() != ActionRunStatus.WAITING_EXTERNAL
                    || !attemptToken.equals(current.attemptToken())) {
                throw new IllegalStateException("completion does not own the parked Action attempt");
            }
            ActionRunStatus terminal = result.status() == ActionExecutionStatus.SUCCEEDED
                    ? ActionRunStatus.SUCCEEDED
                    : ActionRunStatus.FAILED;
            ActionRun next = current.toBuilder()
                    .version(current.version() + 1)
                    .status(terminal)
                    .currentStage("COMPLETE")
                    .result(result)
                    .updatedAt(clock.instant())
                    .build();
            if (!actionRuns.compareAndSet(current, next)) {
                throw new IllegalStateException("Action completion CAS lost");
            }
            completions.incrementAndGet();
            return result;
        }

        @Override
        public ActionExecutionResult cancel(String runId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
