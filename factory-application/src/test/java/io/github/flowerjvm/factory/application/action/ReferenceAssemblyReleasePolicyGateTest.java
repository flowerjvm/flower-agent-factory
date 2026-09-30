package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyReleasePolicyGateTest {
    private static final TenantId TENANT = new TenantId("tenant-release-policy");
    private static final ReferenceAssemblyId ASSEMBLY_ID =
            new ReferenceAssemblyId("reference-release-policy");
    private static final DecisionPointId DECISION_POINT_ID =
            new DecisionPointId("point-release-policy");
    private static final ContentHash SUBJECT_HASH = hash('8');
    private static final Instant NOW = Instant.parse("2026-09-02T02:00:00Z");

    @Test
    void firstExecutionRequiresTheExactUnownedInspectedVersion() {
        ReferenceAssembly reviewBound = reviewBound();
        var gate = new ReferenceAssemblyReleasePolicyGate(repository(reviewBound));

        assertTrue(gate.evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(reviewBound, reviewBound.version() - 1),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(reviewBound, reviewBound.version() + 1),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, false))
                .allowedToExecuteNow());

        ActionProposal wrongChannel = ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("proposal-wrong-channel")
                .requestChannel(ActionRequestChannel.API)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId(ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID)
                .input(input(reviewBound, reviewBound.version()).toMap())
                .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(reviewBound))
                .build();
        assertFalse(gate.evaluate(
                        wrongChannel,
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
    }

    @Test
    void onlyTheOriginalOwnedAttemptMayReplayThroughInspectedAndReleasedSnapshots() {
        ReferenceAssembly reviewBound = reviewBound();
        ReferenceAssembly actionBound = reviewBound.bindReleaseAction(
                "release-action-run", NOW.plusSeconds(5));
        ReferenceAssembly released = actionBound.release(
                lock("release-manifest", '9'),
                DECISION_POINT_ID,
                SUBJECT_HASH,
                "release-action-run",
                NOW.plusSeconds(6));

        String originalKey = ReferenceAssemblyReleaseIdempotencyKeys.derive(
                reviewBound, reviewBound.version());
        assertEquals(
                originalKey,
                ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        actionBound, reviewBound.version()));
        assertEquals(
                originalKey,
                ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        released, reviewBound.version()));

        var ownedGate = new ReferenceAssemblyReleasePolicyGate(repository(actionBound));
        assertTrue(ownedGate.evaluate(
                        proposal(actionBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
        assertFalse(ownedGate.evaluate(
                        proposal(actionBound, reviewBound.version() - 1),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
        assertFalse(ownedGate.evaluate(
                        proposal(actionBound, actionBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());

        var releasedGate = new ReferenceAssemblyReleasePolicyGate(repository(released));
        assertTrue(releasedGate.evaluate(
                        proposal(released, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
        assertFalse(releasedGate.evaluate(
                        proposal(released, actionBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());
    }

    @Test
    void stateExistenceTenantAndLockDifferencesShareOneOpaqueDenial() {
        ReferenceAssembly reviewBound = reviewBound();
        ReferenceAssembly rejected = reviewBound.reject(
                "REFERENCE_ASSEMBLY_RELEASE_REJECTED", NOW.plusSeconds(5));
        ReferenceAssemblyReleaseInput driftedInput = new ReferenceAssemblyReleaseInput(
                ASSEMBLY_ID,
                hash('f'),
                reviewBound.inspectionReport().orElseThrow().hash(),
                DECISION_POINT_ID,
                SUBJECT_HASH,
                reviewBound.version());

        PolicyDecision missing = new ReferenceAssemblyReleasePolicyGate(repository())
                .evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true));
        PolicyDecision wrongTenant = new ReferenceAssemblyReleasePolicyGate(repository(reviewBound))
                .evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(new TenantId("tenant-release-policy-other"), ASSEMBLY_ID, true));
        PolicyDecision wrongResource = new ReferenceAssemblyReleasePolicyGate(repository(reviewBound))
                .evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(
                                TENANT,
                                new ReferenceAssemblyId("reference-release-policy-other"),
                                true));
        PolicyDecision wrongLock = new ReferenceAssemblyReleasePolicyGate(repository(reviewBound))
                .evaluate(
                        proposal(reviewBound, driftedInput),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true));
        PolicyDecision rejectedState = new ReferenceAssemblyReleasePolicyGate(repository(rejected))
                .evaluate(
                        proposal(rejected, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true));

        for (PolicyDecision denied : List.of(
                missing, wrongTenant, wrongResource, wrongLock, rejectedState)) {
            assertFalse(denied.allowedToExecuteNow());
            assertEquals(ReferenceAssemblyReleasePolicyGate.DENY_MESSAGE, denied.reason());
        }
    }

    @Test
    void canonicalLauncherPrincipalAndRequesterAreRequiredAtAdmission() {
        ReferenceAssembly reviewBound = reviewBound();
        var gate = new ReferenceAssemblyReleasePolicyGate(repository(reviewBound));

        assertFalse(gate.evaluate(
                        proposal(
                                reviewBound,
                                input(reviewBound, reviewBound.version()),
                                "noncanonical-release-requester"),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(
                                TENANT,
                                ASSEMBLY_ID,
                                "noncanonical-release-principal",
                                Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                                Map.of()))
                .allowedToExecuteNow());

        assertFalse(gate.evaluate(
                        proposal(
                                reviewBound,
                                input(reviewBound, reviewBound.version()),
                                "noncanonical-release-requester"),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(TENANT, ASSEMBLY_ID, true))
                .allowedToExecuteNow());

        assertFalse(gate.evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(
                                TENANT,
                                ASSEMBLY_ID,
                                "noncanonical-release-principal",
                                Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                                Map.of()))
                .allowedToExecuteNow());
    }

    @Test
    void extraPermissionOrContextKeyIsRejectedAtAdmission() {
        ReferenceAssembly reviewBound = reviewBound();
        var gate = new ReferenceAssemblyReleasePolicyGate(repository(reviewBound));

        assertFalse(gate.evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(
                                TENANT,
                                ASSEMBLY_ID,
                                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                                Set.of(
                                        ReferenceAssemblyReleaseAction.PERMISSION,
                                        "unrelated.permission"),
                                Map.of()))
                .allowedToExecuteNow());

        assertFalse(gate.evaluate(
                        proposal(reviewBound, reviewBound.version()),
                        ReferenceAssemblyReleaseAction.definition(),
                        context(
                                TENANT,
                                ASSEMBLY_ID,
                                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                                Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                                Map.of("untrusted.extra", "value")))
                .allowedToExecuteNow());
    }

    private static ReferenceAssembly reviewBound() {
        return ReferenceAssembly.requested(
                        ASSEMBLY_ID,
                        TENANT,
                        new BuildSessionId("build-release-policy"),
                        lock("requirement", '1'),
                        lock("consumer", '2'),
                        lock("host", '3'),
                        lock("policy", '4'),
                        new CertificationId("certification-release-policy"),
                        hash('5'),
                        lock("component-manifest", '5'),
                        NOW)
                .resolveComponent(NOW.plusSeconds(1))
                .assemble(lock("assembly-manifest", '6'), NOW.plusSeconds(2))
                .inspect(lock("inspection-report", '7'), NOW.plusSeconds(3))
                .bindReleaseReview(DECISION_POINT_ID, SUBJECT_HASH, NOW.plusSeconds(4));
    }

    private static ReferenceAssemblyReleaseInput input(
            ReferenceAssembly assembly, long expectedVersion) {
        return new ReferenceAssemblyReleaseInput(
                assembly.referenceAssemblyId(),
                assembly.assemblyManifest().orElseThrow().hash(),
                assembly.inspectionReport().orElseThrow().hash(),
                assembly.releaseDecisionPointId().orElseThrow(),
                assembly.releaseSubjectHash().orElseThrow(),
                expectedVersion);
    }

    private static ActionProposal proposal(
            ReferenceAssembly assembly, long expectedVersion) {
        return proposal(assembly, input(assembly, expectedVersion));
    }

    private static ActionProposal proposal(
            ReferenceAssembly assembly, ReferenceAssemblyReleaseInput input) {
        return ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("proposal-release-policy")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId(ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID)
                .input(input.toMap())
                .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        assembly, input.expectedReferenceAssemblyVersion()))
                .build();
    }

    private static ActionProposal proposal(
            ReferenceAssembly assembly,
            ReferenceAssemblyReleaseInput input,
            String requesterId) {
        return ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("proposal-release-policy")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId(requesterId)
                .input(input.toMap())
                .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        assembly, input.expectedReferenceAssemblyVersion()))
                .build();
    }

    private static ExecutionContext context(
            TenantId tenantId,
            ReferenceAssemblyId resourceId,
            boolean hasPermission) {
        return context(
                tenantId,
                resourceId,
                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                hasPermission
                        ? Set.of(ReferenceAssemblyReleaseAction.PERMISSION)
                        : Set.of(),
                Map.of());
    }

    private static ExecutionContext context(
            TenantId tenantId,
            ReferenceAssemblyId resourceId,
            String userId,
            Set<String> permissions,
            Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("actor.permissions", permissions);
        metadata.put("resource.type", ReferenceAssemblyReleaseAction.RESOURCE_TYPE);
        metadata.put("resource.id", resourceId.value());
        metadata.putAll(extraMetadata);
        return new ExecutionContext(
                tenantId.value(),
                userId,
                "release-action-run",
                "trace-release-policy",
                metadata);
    }

    private static ReferenceAssemblyRepository repository(ReferenceAssembly... values) {
        return new ReferenceAssemblyRepository() {
            @Override
            public void create(ReferenceAssembly assembly) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ReferenceAssembly> find(
                    TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
                return List.of(values).stream()
                        .filter(value -> value.tenantId().equals(tenantId))
                        .filter(value -> value.referenceAssemblyId().equals(referenceAssemblyId))
                        .findFirst();
            }

            @Override
            public Optional<ReferenceAssembly> findByBuildSession(
                    TenantId tenantId, BuildSessionId buildSessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ReferenceAssembly> findReleasedByComponentCertification(
                    TenantId tenantId, CertificationId componentCertificationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static CertificationArtifactLock lock(String name, char hash) {
        return new CertificationArtifactLock(
                new ArtifactReference("artifact:" + name), hash(hash));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
