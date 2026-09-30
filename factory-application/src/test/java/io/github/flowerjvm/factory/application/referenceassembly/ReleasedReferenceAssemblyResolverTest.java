package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
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
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReleasedReferenceAssemblyResolverTest {
    @Test
    void fractionalBusinessDeadlineResolvesOnlyTheExactlyCanonicalTerminalActionDeadline() {
        Fixture fixture = new Fixture();
        Instant businessDeadline = Fixture.NOW.plusSeconds(3600);
        Instant transportDeadline = Instant.parse("2026-09-02T04:04:05.123Z");
        assertEquals(businessDeadline, fixture.completedIntent.deadlineAt());
        assertEquals(transportDeadline, fixture.actionRuns.current.dueAt());
        assertEquals(fixture.released, fixture.resolver().resolve(fixture.tenant, fixture.assemblyId).referenceAssembly());
        for (Instant forged : List.of(businessDeadline, transportDeadline.plusNanos(1_000),
                transportDeadline.minusNanos(1_000), transportDeadline.plusMillis(1))) {
            fixture.actionRuns.current = fixture.actionRuns.current.toBuilder().dueAt(forged).build();
            assertThrows(ReleasedReferenceAssemblyResolutionException.class,
                    () -> fixture.resolver().resolve(fixture.tenant, fixture.assemblyId));
        }
    }

    @Test
    void finalReleaseRejectsFullComponentIntegrityBeforeTransactionalCommit() {
        Fixture fixture = new Fixture();
        fixture.repository.current = fixture.inspected
                .bindReleaseReview(fixture.releaseDecisionPointId, fixture.releaseSubjectLock.hash(), Fixture.NOW.minusSeconds(6))
                .bindReleaseAction(fixture.releaseActionRunId, Fixture.NOW.minusSeconds(5));
        fixture.componentGate.failure = new IllegalStateException("private source corruption details");
        AtomicInteger commits = new AtomicInteger();
        ReferenceAssemblyReleaseService service = fixture.releaseService(commits);
        var intent = fixture.pendingIntent().claim("claim-full-read", Fixture.NOW, Duration.ofMinutes(1));

        var rejected = assertThrows(ReferenceAssemblyReleaseService.EvidenceRejected.class,
                () -> service.release(intent));

        assertEquals("REFERENCE_ASSEMBLY_FULL_EVIDENCE_REJECTED", rejected.getMessage());
        assertEquals(0, commits.get());
        assertEquals(1, fixture.componentGate.calls.get());
        assertEquals(ReferenceAssemblyStatus.INSPECTED, fixture.repository.current.status());
    }

    @Test
    void finalReleaseChecksFullComponentAndBoundedObservationDoesNotRepeatIt() {
        Fixture fixture = new Fixture();
        fixture.repository.current = fixture.inspected
                .bindReleaseReview(fixture.releaseDecisionPointId, fixture.releaseSubjectLock.hash(), Fixture.NOW.minusSeconds(6))
                .bindReleaseAction(fixture.releaseActionRunId, Fixture.NOW.minusSeconds(5));
        AtomicInteger commits = new AtomicInteger();
        ReferenceAssemblyReleaseService service = fixture.releaseService(commits);
        var intent = fixture.pendingIntent().claim("claim-full-read", Fixture.NOW, Duration.ofMinutes(1));

        assertTrue(service.release(intent).committedNow());
        assertEquals(1, commits.get());
        assertEquals(1, fixture.componentGate.calls.get());
        assertTrue(service.observeReleased(intent).isPresent());
        assertEquals(1, fixture.componentGate.calls.get(), "Flow observation must remain a bounded manifest read");

        service.requireFullEvidence(intent);
        assertEquals(2, fixture.componentGate.calls.get(), "off-tick completion must independently reread the component");
    }

    @Test
    void recoveredReleasedMetadataDoesNotAuthorizeSuccessWithoutFullIntegrity() {
        Fixture fixture = new Fixture();
        fixture.componentGate.failure = new IllegalStateException("private source corruption details");
        AtomicInteger commits = new AtomicInteger();
        ReferenceAssemblyReleaseService service = fixture.releaseService(commits);
        var intent = fixture.pendingIntent().claim("claim-full-read", Fixture.NOW, Duration.ofMinutes(1));

        assertTrue(service.observeReleased(intent).isPresent());
        assertEquals(0, fixture.componentGate.calls.get());
        assertThrows(ReferenceAssemblyReleaseService.EvidenceRejected.class,
                () -> service.requireFullEvidence(intent));
        assertEquals(1, fixture.componentGate.calls.get());
        assertEquals(0, commits.get());
    }

    @Test
    void resolvesExactCanonicalReleasedProductThroughReadGate() {
        Fixture fixture = new Fixture();
        ReleasedReferenceAssemblyReadGate readGate = fixture.resolver();

        ResolvedReleasedReferenceAssembly resolved = readGate.resolve(
                fixture.tenant, fixture.assemblyId);

        assertEquals(fixture.released, resolved.referenceAssembly());
        assertEquals(fixture.releaseLock, resolved.releaseManifestArtifact());
        assertEquals(fixture.releaseManifest, resolved.releaseManifest());
        assertEquals(fixture.requirement, resolved.requirement());
        assertEquals(fixture.manifest, resolved.assemblyManifest());
        assertEquals(fixture.inspection, resolved.inspectionReport());
        assertEquals(fixture.releaseSubject, resolved.releaseSubject());
        assertEquals(fixture.resolvedComponent, resolved.component());
        assertEquals(1, fixture.componentGate.calls.get());
    }

    @Test
    void unreleasedRejectedAndTenantMismatchAreTheSameOpaqueFailure() {
        Fixture unreleased = new Fixture();
        unreleased.repository.current = unreleased.inspected;
        assertUnavailable(() -> unreleased.resolver().resolve(
                unreleased.tenant, unreleased.assemblyId));

        Fixture rejected = new Fixture();
        rejected.repository.current = rejected.inspected.reject(
                "REFERENCE_ASSEMBLY_RELEASE_REJECTED", Fixture.NOW.plusSeconds(1));
        assertUnavailable(() -> rejected.resolver().resolve(
                rejected.tenant, rejected.assemblyId));

        Fixture wrongTenant = new Fixture();
        wrongTenant.repository.ignoreTenant = true;
        assertUnavailable(() -> wrongTenant.resolver().resolve(
                new TenantId("other-tenant"), wrongTenant.assemblyId));
    }

    @Test
    void tamperedCanonicalReleaseManifestIsOpaqueAndFailsBeforeComponentGate() {
        Fixture fixture = new Fixture();
        fixture.store.replaceContentKeepingDeclaredHash(
                fixture.tenant,
                fixture.releaseLock,
                "tampered-release-manifest".getBytes(StandardCharsets.UTF_8));

        assertUnavailable(() -> fixture.resolver().resolve(
                fixture.tenant, fixture.assemblyId));
        assertEquals(0, fixture.componentGate.calls.get());
    }

    @Test
    void canonicalManifestWhosePolicyLockDiffersFromLedgerFailsClosed() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyReleaseManifest wrong = fixture.releaseManifest(
                fixture.stageOpaque("different-release-policy", "different-release-policy"));
        CertificationArtifactLock wrongLock = new ReferenceAssemblyArtifactSupport(
                        fixture.store, fixture.codec)
                .stageReleaseManifest(fixture.tenant, wrong);
        fixture.repository.current = fixture.releaseWith(wrongLock);

        assertUnavailable(() -> fixture.resolver().resolve(
                fixture.tenant, fixture.assemblyId));
        assertEquals(0, fixture.componentGate.calls.get());
    }

    @Test
    void currentlyRevokedUpstreamComponentMakesReleasedProductUnavailable() {
        Fixture fixture = new Fixture();
        fixture.componentGate.failure = new IllegalStateException("component revoked");

        assertUnavailable(() -> fixture.resolver().resolve(
                fixture.tenant, fixture.assemblyId));
        assertEquals(1, fixture.componentGate.calls.get());
    }

    @Test
    void releasedProductRequiresTheExactCompletedV14Intent() {
        Fixture missing = new Fixture();
        missing.releaseIntents.current = Optional.empty();
        assertUnavailable(() -> missing.resolver().resolve(missing.tenant, missing.assemblyId));

        Fixture nonTerminal = new Fixture();
        nonTerminal.releaseIntents.current = Optional.of(nonTerminal.pendingIntent());
        assertUnavailable(() -> nonTerminal.resolver()
                .resolve(nonTerminal.tenant, nonTerminal.assemblyId));
        assertEquals(0, nonTerminal.componentGate.calls.get());
    }

    @Test
    void releasedProductRequiresItsSucceededActionRunAndExactTerminalResult() {
        Fixture wrongResult = new Fixture();
        wrongResult.actionRuns.current = wrongResult.succeededAction(
                ActionExecutionResult.succeeded(Map.of("releaseStatus", "RELEASED")));

        assertUnavailable(() -> wrongResult.resolver()
                .resolve(wrongResult.tenant, wrongResult.assemblyId));
        assertEquals(0, wrongResult.componentGate.calls.get());

        Fixture nonSucceeded = new Fixture();
        nonSucceeded.actionRuns.current = nonSucceeded.action(
                ActionRunStatus.FAILED,
                ActionExecutionResult.failed("RELEASE_FAILED", "release failed"));
        assertUnavailable(() -> nonSucceeded.resolver()
                .resolve(nonSucceeded.tenant, nonSucceeded.assemblyId));
        assertEquals(0, nonSucceeded.componentGate.calls.get());

        Fixture wrongCode = new Fixture();
        ActionExecutionResult exactCode = wrongCode.exactResult();
        wrongCode.actionRuns.current = wrongCode.succeededAction(new ActionExecutionResult(
                exactCode.status(),
                "NON_CANONICAL_RELEASE_SUCCESS",
                exactCode.message(),
                exactCode.output(),
                exactCode.retryDisposition()));
        assertUnavailable(() -> wrongCode.resolver()
                .resolve(wrongCode.tenant, wrongCode.assemblyId));
        assertEquals(0, wrongCode.componentGate.calls.get());

        Fixture wrongRetry = new Fixture();
        ActionExecutionResult exactRetry = wrongRetry.exactResult();
        wrongRetry.actionRuns.current = wrongRetry.succeededAction(new ActionExecutionResult(
                exactRetry.status(),
                exactRetry.code(),
                exactRetry.message(),
                exactRetry.output(),
                RetryDisposition.MANUAL_REVIEW));
        assertUnavailable(() -> wrongRetry.resolver()
                .resolve(wrongRetry.tenant, wrongRetry.assemblyId));
        assertEquals(0, wrongRetry.componentGate.calls.get());
    }

    @Test
    void releasedProductRequiresTheCanonicalReleasePrincipalAndRequester() {
        Fixture wrongPrincipal = new Fixture();
        wrongPrincipal.actionRuns.current = wrongPrincipal.actionRuns.current.toBuilder()
                .userId("different-release-principal")
                .build();
        assertUnavailable(() -> wrongPrincipal.resolver()
                .resolve(wrongPrincipal.tenant, wrongPrincipal.assemblyId));
        assertEquals(0, wrongPrincipal.componentGate.calls.get());

        Fixture wrongRequester = new Fixture();
        wrongRequester.actionRuns.current = wrongRequester.actionRuns.current.toBuilder()
                .requesterId("different-release-requester")
                .build();
        assertUnavailable(() -> wrongRequester.resolver()
                .resolve(wrongRequester.tenant, wrongRequester.assemblyId));
        assertEquals(0, wrongRequester.componentGate.calls.get());
    }

    @Test
    void releasedProductRequiresANonBlankTerminalActionTrace() {
        Fixture fixture = new Fixture();
        fixture.actionRuns.current = fixture.actionRuns.current.toBuilder()
                .traceId(" ")
                .build();

        assertUnavailable(() -> fixture.resolver().resolve(fixture.tenant, fixture.assemblyId));
        assertEquals(0, fixture.componentGate.calls.get());
    }

    @Test
    void releasedProductRequiresTheExactCanonicalTerminalContext() {
        Fixture extraPermission = new Fixture();
        extraPermission.actionRuns.current = extraPermission.actionRuns.current.toBuilder()
                .contextMetadata(Map.of(
                        "actor.permissions",
                        Set.of(ReferenceAssemblyReleaseAction.PERMISSION, "unrelated.permission"),
                        "resource.type",
                        ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                        "resource.id",
                        extraPermission.assemblyId.value()))
                .build();
        assertUnavailable(() -> extraPermission.resolver()
                .resolve(extraPermission.tenant, extraPermission.assemblyId));
        assertEquals(0, extraPermission.componentGate.calls.get());

        Fixture extraContext = new Fixture();
        extraContext.actionRuns.current = extraContext.actionRuns.current.toBuilder()
                .contextMetadata(Map.of(
                        "actor.permissions",
                        Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                        "resource.type",
                        ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                        "resource.id",
                        extraContext.assemblyId.value(),
                        "untrusted.extra",
                        "value"))
                .build();
        assertUnavailable(() -> extraContext.resolver()
                .resolve(extraContext.tenant, extraContext.assemblyId));
        assertEquals(0, extraContext.componentGate.calls.get());
    }

    @Test
    void releasedProductRequiresTheCanonicalTerminalActionStage() {
        Fixture fixture = new Fixture();
        fixture.actionRuns.current = fixture.actionRuns.current.toBuilder()
                .currentStage("record-result")
                .build();

        assertUnavailable(() -> fixture.resolver().resolve(fixture.tenant, fixture.assemblyId));
        assertEquals(0, fixture.componentGate.calls.get());
    }

    @Test
    void releasedProductRequiresItsOwningSucceededCompleteBuildSession() {
        Fixture fixture = new Fixture();
        fixture.buildSessions.current = fixture.buildSession(
                BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE);

        assertUnavailable(() -> fixture.resolver().resolve(fixture.tenant, fixture.assemblyId));
        assertEquals(0, fixture.componentGate.calls.get());
    }

    @Test
    void boundedReverseProvenancePortDefaultsToUnsupported() {
        Fixture fixture = new Fixture();

        assertThrows(
                UnsupportedOperationException.class,
                () -> fixture.repository.findReleasedByComponentCertification(
                        fixture.tenant,
                        fixture.component.certificationId(),
                        10));
    }

    private static void assertUnavailable(Runnable work) {
        ReleasedReferenceAssemblyResolutionException failure = assertThrows(
                ReleasedReferenceAssemblyResolutionException.class, work::run);
        assertEquals(
                ReleasedReferenceAssemblyResolutionException.NOT_AVAILABLE,
                failure.code());
        assertEquals("released Reference Assembly is unavailable", failure.getMessage());
    }

    private static final class Fixture {
        private static final Instant NOW = Instant.parse("2026-09-02T03:04:05.123456Z");
        private final TenantId tenant = new TenantId("tenant-released-reference");
        private final BuildSessionId buildSessionId =
                new BuildSessionId("build-released-reference");
        private final ReferenceAssemblyId assemblyId =
                new ReferenceAssemblyId("released-reference-assembly");
        private final DecisionPointId releaseDecisionPointId =
                new DecisionPointId("released-reference-decision");
        private final String releaseActionRunId = "released-reference-action";
        private final MemoryArtifactStore store = new MemoryArtifactStore();
        private final TestCodec codec = new TestCodec();
        private final CertificationArtifactLock hostFixture =
                stageOpaque("host-fixture", "trusted-host-fixture");
        private final CertificationArtifactLock policySnapshot =
                stageOpaque("policy-snapshot", "trusted-release-policy");
        private final CertificationArtifactLock productContract = lock("agent-product-contract");
        private final CertificationArtifactLock apiSignature = lock("agent-api-signature");
        private final ResolvedCertifiedAgentComponent resolvedComponent = resolvedComponent();
        private final CertifiedAgentComponentRef component = resolvedComponent.reference();
        private final ReferenceAssemblyConsumerContract consumer = consumerContract();
        private final CertificationArtifactLock consumerLock = stageCanonical(
                "consumer-contract", codec.writeConsumerContract(consumer));
        private final ReferenceAssemblyRequirement requirement = new ReferenceAssemblyRequirement(
                ReferenceAssemblyRequirement.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                consumerLock,
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
                consumerLock,
                hostFixture,
                policySnapshot,
                component);
        private final CertificationArtifactLock manifestLock =
                new ReferenceAssemblyArtifactSupport(store, codec)
                        .stageManifest(tenant, manifest);
        private final ReferenceAssemblyInspectionReport inspection = inspection();
        private final CertificationArtifactLock inspectionLock =
                new ReferenceAssemblyArtifactSupport(store, codec)
                        .stageInspection(tenant, inspection);
        private final ReferenceAssembly inspected = ReferenceAssembly.requested(
                        assemblyId,
                        tenant,
                        buildSessionId,
                        requirementLock,
                        consumerLock,
                        hostFixture,
                        policySnapshot,
                        component.certificationId(),
                        component.candidateHash(),
                        component.certificationManifest(),
                        NOW.minusSeconds(10))
                .resolveComponent(NOW.minusSeconds(9))
                .assemble(manifestLock, NOW.minusSeconds(8))
                .inspect(inspectionLock, NOW.minusSeconds(7));
        private final ReferenceAssemblyReleaseSubject releaseSubject =
                new ReferenceAssemblyReleaseSubject(
                        ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                        ProductLineId.REFERENCE_ASSEMBLY,
                        assemblyId,
                        inspected.version(),
                        manifestLock,
                        inspectionLock,
                        component.certificationId(),
                        component.candidateHash(),
                        component.certificationManifest(),
                        policySnapshot);
        private final CertificationArtifactLock releaseSubjectLock =
                new ReferenceAssemblyArtifactSupport(store, codec)
                        .stageReleaseSubject(tenant, releaseSubject);
        private final ReferenceAssemblyReleaseManifest releaseManifest =
                releaseManifest(policySnapshot);
        private final CertificationArtifactLock releaseLock =
                new ReferenceAssemblyArtifactSupport(store, codec)
                        .stageReleaseManifest(tenant, releaseManifest);
        private final ReferenceAssembly released = releaseWith(releaseLock);
        private final MemoryReferenceAssemblyRepository repository =
                new MemoryReferenceAssemblyRepository(released);
        private final ComponentGate componentGate = new ComponentGate(resolvedComponent);
        private final ReferenceAssemblyReleaseDispatchIntent completedIntent = completedIntent();
        private final MemoryReleaseIntents releaseIntents =
                new MemoryReleaseIntents(completedIntent);
        private final MemoryBuildSessions buildSessions = new MemoryBuildSessions(buildSession(
                BuildSessionStatus.SUCCEEDED, BuildSessionPhase.COMPLETE));
        private final MemoryRunStore actionRuns =
                new MemoryRunStore(succeededAction(exactResult()));

        private ReleasedReferenceAssemblyResolver resolver() {
            return new ReleasedReferenceAssemblyResolver(
                    repository,
                    buildSessions,
                    releaseIntents,
                    actionRuns,
                    store,
                    codec,
                    componentGate);
        }

        private ReferenceAssemblyReleaseService releaseService(AtomicInteger commits) {
            return new ReferenceAssemblyReleaseService(repository, (intent, expected, proposed) -> {
                assertEquals(expected, repository.current);
                commits.incrementAndGet();
                repository.current = proposed;
                return new ReferenceAssemblyReleaseTransaction.ReleaseCommit(proposed, true);
            }, store, codec, Clock.fixed(NOW, ZoneOffset.UTC), componentGate);
        }

        private ReferenceAssemblyReleaseDispatchIntent pendingIntent() {
            ReferenceAssemblyReleaseInput input = releaseInput();
            return ReferenceAssemblyReleaseDispatchIntent.pending(
                    ReferenceAssemblyReleaseDispatchOperationIds.derive(tenant, input),
                    tenant,
                    input,
                    releaseActionRunId,
                    ReferenceAssemblyReleaseAttemptTokens.hash("released-reference-attempt"),
                    NOW.plusSeconds(3600),
                    NOW.minusSeconds(5));
        }

        private ReferenceAssemblyReleaseDispatchIntent completedIntent() {
            return pendingIntent()
                    .claim("released-reference-claim", NOW.minusSeconds(4), Duration.ofMinutes(1))
                    .complete(
                            "released-reference-claim",
                            ReferenceAssemblyReleaseDispatchRunner.DISPATCH_COMPLETED,
                            NOW.minusSeconds(3));
        }

        private ReferenceAssemblyReleaseInput releaseInput() {
            return new ReferenceAssemblyReleaseInput(
                    assemblyId,
                    manifestLock.hash(),
                    inspectionLock.hash(),
                    releaseDecisionPointId,
                    releaseSubjectLock.hash(),
                    inspected.version() + 1);
        }

        private BuildSession buildSession(
                BuildSessionStatus status, BuildSessionPhase phase) {
            boolean terminal = status.isTerminal();
            return new BuildSession(
                    buildSessionId,
                    tenant,
                    new ProjectId("project-released-reference"),
                    ProductLineId.REFERENCE_ASSEMBLY,
                    "request-released-reference",
                    "released-reference-principal",
                    status,
                    phase,
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
                    NOW.minusSeconds(120),
                    NOW.plusSeconds(3600),
                    Optional.empty(),
                    terminal ? Optional.of("REFERENCE_ASSEMBLY_RELEASED") : Optional.empty(),
                    terminal ? Optional.of("Reference Assembly release manifest is ready")
                            : Optional.empty(),
                    6,
                    NOW.minusSeconds(120),
                    NOW.minusSeconds(3));
        }

        private ActionRun succeededAction(ActionExecutionResult result) {
            return action(ActionRunStatus.SUCCEEDED, result);
        }

        private ActionRun action(ActionRunStatus status, ActionExecutionResult result) {
            return ActionRun.builder()
                    .runId(releaseActionRunId)
                    .version(5)
                    .tenantId(tenant.value())
                    .userId(ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID)
                    .traceId("trace-released-reference")
                    .contextMetadata(Map.of(
                            "actor.permissions", Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                            "resource.type", ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                            "resource.id", assemblyId.value()))
                    .actionId(ReferenceAssemblyReleaseAction.ACTION_ID)
                    .proposalId("proposal-released-reference")
                    .requesterId(ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID)
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .input(releaseInput().toMap())
                    .duplicateKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(
                            released, completedIntent.expectedReferenceAssemblyVersion()))
                    .status(status)
                    .currentStage("execute-action")
                    .attemptToken("released-reference-attempt")
                    .externalOperationId(completedIntent.operationId())
                    .externalOperationMetadata(Map.of(
                            "dispatchMode", ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                            ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                            assemblyId.value()))
                    .dueAt(ReferenceAssemblyReleaseDeadlines.actionDueAt(completedIntent.deadlineAt()))
                    .result(result)
                    .createdAt(NOW.minusSeconds(5))
                    .updatedAt(NOW.minusSeconds(3))
                    .build();
        }

        private ActionExecutionResult exactResult() {
            return ActionExecutionResult.succeeded(Map.of(
                    ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                    assemblyId.value(),
                    ReferenceAssemblyReleaseDispatchRunner.RELEASE_STATUS,
                    ReferenceAssemblyStatus.RELEASED.name(),
                    ReferenceAssemblyReleaseDispatchRunner.RELEASE_MANIFEST_REF,
                    releaseLock.reference().value(),
                    ReferenceAssemblyReleaseDispatchRunner.RELEASE_MANIFEST_HASH,
                    releaseLock.hash().sha256()));
        }

        private ReferenceAssembly releaseWith(CertificationArtifactLock requestedReleaseLock) {
            return inspected
                    .bindReleaseReview(
                            releaseDecisionPointId,
                            releaseSubjectLock.hash(),
                            NOW.minusSeconds(6))
                    .bindReleaseAction(releaseActionRunId, NOW.minusSeconds(5))
                    .release(
                            requestedReleaseLock,
                            releaseDecisionPointId,
                            releaseSubjectLock.hash(),
                            releaseActionRunId,
                            NOW.minusSeconds(4));
        }

        private ReferenceAssemblyReleaseManifest releaseManifest(
                CertificationArtifactLock requestedPolicy) {
            return new ReferenceAssemblyReleaseManifest(
                    ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                    ProductLineId.REFERENCE_ASSEMBLY,
                    ReferenceAssemblyReleaseManifest.RELEASE_ALGORITHM_ID,
                    assemblyId,
                    requirementLock,
                    consumerLock,
                    hostFixture,
                    requestedPolicy,
                    component,
                    manifestLock,
                    inspectionLock,
                    releaseDecisionPointId,
                    releaseSubjectLock.hash(),
                    releaseActionRunId);
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

        private ReferenceAssemblyInspectionReport inspection() {
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
                    manifestLock,
                    consumerLock,
                    hostFixture,
                    policySnapshot,
                    component,
                    checks,
                    ReferenceAssemblyInspectionReport.PASSED);
        }

        private ResolvedCertifiedAgentComponent resolvedComponent() {
            BuildSessionId componentBuild = new BuildSessionId("released-component-build");
            WorkOrderId workOrder = new WorkOrderId("released-component-work-order");
            CandidateId candidate = new CandidateId("released-component-candidate");
            VerificationRunId verification =
                    new VerificationRunId("released-component-verification");
            CertificationId certificationId =
                    new CertificationId("released-component-certification");
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
                    "test/released-reference/" + name + "/sha256/" + contentHash.sha256());
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

    private static final class ComponentGate
            implements io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate {
        private final ResolvedCertifiedAgentComponent component;
        private final AtomicInteger calls = new AtomicInteger();
        private RuntimeException failure;

        private ComponentGate(ResolvedCertifiedAgentComponent component) {
            this.component = component;
        }

        @Override
        public ResolvedCertifiedAgentComponent resolve(
                TenantId trustedTenantId, CertifiedAgentComponentRef reference) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return component;
        }
    }

    private static final class MemoryBuildSessions implements BuildSessionRepository {
        private BuildSession current;

        private MemoryBuildSessions(BuildSession current) {
            this.current = current;
        }

        @Override
        public void create(BuildSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BuildSession> find(
                TenantId tenantId, BuildSessionId buildSessionId) {
            return current.tenantId().equals(tenantId)
                            && current.buildSessionId().equals(buildSessionId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MemoryReleaseIntents
            implements ReferenceAssemblyReleaseDispatchIntentRepository {
        private Optional<ReferenceAssemblyReleaseDispatchIntent> current;

        private MemoryReleaseIntents(ReferenceAssemblyReleaseDispatchIntent current) {
            this.current = Optional.of(current);
        }

        @Override
        public void create(ReferenceAssemblyReleaseDispatchIntent intent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> find(String operationId) {
            return current.filter(intent -> intent.operationId().equals(operationId));
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> findLatest(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            return current.filter(intent -> intent.tenantId().equals(tenantId)
                    && intent.referenceAssemblyId().equals(referenceAssemblyId));
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> claimNext(
                Instant now, Duration lease, String claimToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> claimExpiredRunning(
                Instant now, Duration lease, String claimToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean compareAndSet(
                ReferenceAssemblyReleaseDispatchIntent expected,
                ReferenceAssemblyReleaseDispatchIntent next) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MemoryRunStore implements RunStore {
        private ActionRun current;

        private MemoryRunStore(ActionRun current) {
            this.current = current;
        }

        @Override
        public ActionRun create(ActionRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ActionRun> find(String runId) {
            return current != null && current.runId().equals(runId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean compareAndSet(ActionRun expected, ActionRun next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ActionRun> findResumable(String tenantId) {
            return List.of();
        }
    }

    private static final class MemoryReferenceAssemblyRepository
            implements ReferenceAssemblyRepository {
        private ReferenceAssembly current;
        private boolean ignoreTenant;

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
            if ((ignoreTenant || current.tenantId().equals(tenantId))
                    && current.referenceAssemblyId().equals(referenceAssemblyId)) {
                return Optional.of(current);
            }
            return Optional.empty();
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
            throw new UnsupportedOperationException();
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

        private void replaceContentKeepingDeclaredHash(
                TenantId tenantId, CertificationArtifactLock lock, byte[] content) {
            Artifact current = values.get(key(tenantId, lock.reference()));
            values.put(
                    key(tenantId, lock.reference()),
                    new Artifact(
                            tenantId,
                            current.reference(),
                            current.contentHash(),
                            current.mediaType(),
                            content));
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

        private static String key(byte[] content) {
            return Base64.getEncoder().encodeToString(content);
        }
    }

    private static ContentHash hash(String value) {
        return ReferenceAssemblyArtifactSupport.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
