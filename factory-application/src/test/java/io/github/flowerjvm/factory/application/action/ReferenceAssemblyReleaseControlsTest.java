package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyReleaseControlsTest {
    private static final TenantId TENANT = new TenantId("tenant-reference-release");
    private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");

    @Test
    void definitionAndInputArePinnedToTheStrictSixFieldSchema() {
        ReferenceAssembly assembly = assembly(
                TENANT, "reference-release", '6', '7', "point-reference-release", '8');
        ReferenceAssemblyReleaseInput input = input(assembly, assembly.version());
        ActionProposal proposal = proposal(assembly, input);
        var definition = ReferenceAssemblyReleaseAction.definition();
        var validator = new ReferenceAssemblyReleaseActionValidator();

        assertEquals("factory.release.package", definition.actionId());
        assertEquals("factory.release.package", ReferenceAssemblyReleaseAction.PERMISSION);
        assertEquals(ActionEffect.WRITE, definition.effect());
        assertEquals(ActionRiskLevel.MEDIUM, definition.riskLevel());
        assertEquals(Set.of(ActionRequestChannel.INTERNAL), definition.allowedRequestChannels());
        assertEquals(Set.of(ActionProposerType.SERVICE), definition.allowedProposerTypes());
        assertEquals(Set.of(ReferenceAssemblyReleaseAction.PERMISSION), definition.requiredPermissions());
        assertEquals("factory.release.package.input.v1", definition.inputSchemaId());
        assertEquals("factory.release.package.output.v1", definition.outputSchemaId());
        assertEquals(
                ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                definition.metadata().get("resourceType"));
        assertFalse(definition.approvalRequiredByDefault());
        assertTrue(definition.auditRequired());

        assertEquals(input, ReferenceAssemblyReleaseInput.from(input.toMap()));
        assertTrue(validator.validate(proposal, definition, context(TENANT, assembly.referenceAssemblyId()))
                .valid());

        Map<String, Object> withTenant = new HashMap<>(input.toMap());
        withTenant.put("tenantId", TENANT.value());
        assertFalse(validator.validate(
                        proposal.toBuilder().input(withTenant).build(),
                        definition,
                        context(TENANT, assembly.referenceAssemblyId()))
                .valid());

        Map<String, Object> missingSubject = new HashMap<>(input.toMap());
        missingSubject.remove(ReferenceAssemblyReleaseAction.RELEASE_SUBJECT_HASH);
        assertFalse(validator.validate(
                        proposal.toBuilder().input(missingSubject).build(),
                        definition,
                        context(TENANT, assembly.referenceAssemblyId()))
                .valid());
        Map<String, Object> fractionalVersion = new HashMap<>(input.toMap());
        fractionalVersion.put(
                ReferenceAssemblyReleaseAction.EXPECTED_REFERENCE_ASSEMBLY_VERSION,
                1.5d);
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseInput.from(fractionalVersion));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReferenceAssemblyReleaseInput(
                        assembly.referenceAssemblyId(),
                        input.assemblyManifestHash(),
                        input.inspectionReportHash(),
                        input.releaseDecisionPointId(),
                        input.releaseSubjectHash(),
                        -1));

        Map<String, Object> oversizedDecision = new HashMap<>(input.toMap());
        oversizedDecision.put(
                ReferenceAssemblyReleaseAction.RELEASE_DECISION_POINT_ID,
                "p".repeat(129));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceAssemblyReleaseInput.from(oversizedDecision));
    }

    @Test
    void idempotencyKeyBindsTenantResourceEveryExactLockAndExpectedVersion() {
        ReferenceAssembly base = assembly(
                TENANT, "reference-key", '1', '2', "point-reference-key", '3');
        String key = ReferenceAssemblyReleaseIdempotencyKeys.derive(base);

        assertEquals(key, ReferenceAssemblyReleaseIdempotencyKeys.derive(base, base.version()));
        assertNotEquals(key, ReferenceAssemblyReleaseIdempotencyKeys.derive(base, base.version() + 1));
        assertEquals(7, Set.of(
                        key,
                        ReferenceAssemblyReleaseIdempotencyKeys.derive(assembly(
                                new TenantId("tenant-reference-release-other"),
                                "reference-key", '1', '2', "point-reference-key", '3')),
                        ReferenceAssemblyReleaseIdempotencyKeys.derive(assembly(
                                TENANT, "reference-key-other", '1', '2', "point-reference-key", '3')),
                        ReferenceAssemblyReleaseIdempotencyKeys.derive(assembly(
                                TENANT, "reference-key", '4', '2', "point-reference-key", '3')),
                        ReferenceAssemblyReleaseIdempotencyKeys.derive(assembly(
                                TENANT, "reference-key", '1', '5', "point-reference-key", '3')),
                        ReferenceAssemblyReleaseIdempotencyKeys.derive(assembly(
                                TENANT, "reference-key", '1', '2', "point-reference-key-other", '3')),
                        ReferenceAssemblyReleaseIdempotencyKeys.derive(assembly(
                                TENANT, "reference-key", '1', '2', "point-reference-key", '6')))
                .size());

        ReferenceAssembly uninspected = ReferenceAssembly.requested(
                new ReferenceAssemblyId("reference-uninspected"),
                TENANT,
                new BuildSessionId("build-reference-uninspected"),
                lock("requirement-uninspected", 'a'),
                lock("consumer-uninspected", 'b'),
                lock("host-uninspected", 'c'),
                lock("policy-uninspected", 'd'),
                new CertificationId("certification-uninspected"),
                hash('e'),
                lock("component-uninspected", 'e'),
                NOW);
        assertThrows(
                IllegalStateException.class,
                () -> ReferenceAssemblyReleaseIdempotencyKeys.derive(uninspected));
    }

    @Test
    void visibilityUsesTrustedTenantResourceAndStoredExactReleaseLocks() {
        ReferenceAssembly assembly = assembly(
                TENANT, "reference-visible", '6', '7', "point-reference-visible", '8');
        ReferenceAssemblyReleaseInput input = input(assembly, assembly.version());
        ActionProposal proposal = proposal(assembly, input);
        var resolver = new ReferenceAssemblyReleaseVisibilityScopeResolver(repository(assembly));

        assertEquals(
                "reference-assembly:" + assembly.referenceAssemblyId().value(),
                resolver.resolve(proposal, context(TENANT, assembly.referenceAssemblyId())));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        proposal,
                        context(
                                new TenantId("tenant-reference-release-other"),
                                assembly.referenceAssemblyId())));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        proposal,
                        context(TENANT, new ReferenceAssemblyId("reference-hidden"))));

        ReferenceAssemblyReleaseInput drifted = new ReferenceAssemblyReleaseInput(
                input.referenceAssemblyId(),
                hash('f'),
                input.inspectionReportHash(),
                input.releaseDecisionPointId(),
                input.releaseSubjectHash(),
                input.expectedReferenceAssemblyVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        proposal(assembly, drifted),
                        context(TENANT, assembly.referenceAssemblyId())));

        ReferenceAssemblyRepository maliciousRepository = repositoryIgnoringTenant(assembly);
        var failClosed = new ReferenceAssemblyReleaseVisibilityScopeResolver(maliciousRepository);
        assertThrows(
                IllegalArgumentException.class,
                () -> failClosed.resolve(
                        proposal,
                        context(
                                new TenantId("tenant-reference-release-other"),
                                assembly.referenceAssemblyId())));
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
            ReferenceAssembly assembly, ReferenceAssemblyReleaseInput input) {
        return ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("proposal-" + assembly.referenceAssemblyId().value())
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-release-service")
                .input(input.toMap())
                .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        assembly, input.expectedReferenceAssemblyVersion()))
                .build();
    }

    private static ExecutionContext context(
            TenantId tenantId, ReferenceAssemblyId resourceId) {
        return new ExecutionContext(
                tenantId.value(),
                "factory-release-service",
                "release-action-run",
                "trace-reference-release",
                Map.of(
                        "actor.permissions", Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                        "resource.type", ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                        "resource.id", resourceId.value()));
    }

    private static ReferenceAssembly assembly(
            TenantId tenantId,
            String id,
            char assemblyHash,
            char inspectionHash,
            String decisionPointId,
            char subjectHash) {
        ReferenceAssembly requested = ReferenceAssembly.requested(
                new ReferenceAssemblyId(id),
                tenantId,
                new BuildSessionId("build-" + id),
                lock("requirement-" + id, 'a'),
                lock("consumer-" + id, 'b'),
                lock("host-" + id, 'c'),
                lock("policy-" + id, 'd'),
                new CertificationId("certification-" + id),
                hash('e'),
                lock("component-" + id, 'e'),
                NOW);
        return requested
                .resolveComponent(NOW.plusSeconds(1))
                .assemble(lock("assembly-" + id, assemblyHash), NOW.plusSeconds(2))
                .inspect(lock("inspection-" + id, inspectionHash), NOW.plusSeconds(3))
                .bindReleaseReview(
                        new DecisionPointId(decisionPointId),
                        hash(subjectHash),
                        NOW.plusSeconds(4));
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

    private static ReferenceAssemblyRepository repositoryIgnoringTenant(
            ReferenceAssembly value) {
        return new ReferenceAssemblyRepository() {
            @Override
            public void create(ReferenceAssembly assembly) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ReferenceAssembly> find(
                    TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
                return value.referenceAssemblyId().equals(referenceAssemblyId)
                        ? Optional.of(value)
                        : Optional.empty();
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
