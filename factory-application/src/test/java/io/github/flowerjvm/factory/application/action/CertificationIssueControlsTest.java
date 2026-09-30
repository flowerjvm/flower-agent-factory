package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CertificationIssueControlsTest {
    private static final TenantId TENANT = new TenantId("tenant-certification-controls");
    private static final CertificationId CERTIFICATION = new CertificationId("certification-controls");
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void definitionAndInputRemainStableStrictAndVersionBound() {
        Certification certification = requested(CERTIFICATION, inputLock());
        CertificationIssueInput input = input(certification, 0);
        ActionProposal proposal = proposal(certification, 0);
        var validator = new CertificationIssueActionValidator();

        assertEquals("factory.certification.issue", CertificationIssueAction.definition().actionId());
        assertEquals(input, CertificationIssueInput.from(input.toMap()));
        assertTrue(validator.validate(proposal, CertificationIssueAction.definition(), context(true, CERTIFICATION))
                .valid());
        assertFalse(validator.validate(
                        proposal.toBuilder().input(Map.of("unexpected", "field")).build(),
                        CertificationIssueAction.definition(),
                        context(true, CERTIFICATION))
                .valid());
        assertThrows(IllegalArgumentException.class, () -> CertificationIssueInput.from(Map.of(
                CertificationIssueAction.CERTIFICATION_ID, CERTIFICATION.value(),
                CertificationIssueAction.INPUT_LOCK_MANIFEST_HASH,
                        certification.inputLockArtifact().hash().sha256(),
                CertificationIssueAction.EXPECTED_CERTIFICATION_VERSION, 0.5d)));
        assertThrows(IllegalArgumentException.class, () -> new CertificationIssueInput(
                CERTIFICATION, certification.inputLockArtifact().hash(), -1));

        assertEquals(
                CertificationIssueIdempotencyKeys.derive(certification),
                CertificationIssueIdempotencyKeys.derive(certification, 0));
        assertNotEquals(
                CertificationIssueIdempotencyKeys.derive(certification, 0),
                CertificationIssueIdempotencyKeys.derive(certification, 1));
    }

    @Test
    void trustedPolicyRejectsFloatingVersionsAndAnyExactAdmissionDrift() {
        CertificationInputLock lock = inputLock();
        AgentPackCertificationPolicy policy = policy(lock);

        assertTrue(policy.matches(lock));
        assertThrows(IllegalArgumentException.class, () -> new AgentPackCertificationPolicy(
                lock.productContractBundle(), lock.gateProfile(), lock.verificationFixtureSetHash(),
                lock.sourceLockAlgorithmId(), lock.certificationProfile(), "latest",
                lock.flowerVersion(), lock.actionRuntimeVersion()));
        assertFalse(new AgentPackCertificationPolicy(
                        lock.productContractBundle(), "release", lock.verificationFixtureSetHash(),
                        lock.sourceLockAlgorithmId(), lock.certificationProfile(), lock.factoryVersion(),
                        lock.flowerVersion(), lock.actionRuntimeVersion())
                .matches(lock));
    }

    @Test
    void policyAuthenticatesEveryAuthorityDimensionBeforeDuplicateLookup() {
        MutableCertifications repository = new MutableCertifications(requested(CERTIFICATION, inputLock()));
        Certification current = repository.value;
        CertificationIssuePolicyGate gate = new CertificationIssuePolicyGate(repository, policy(current.inputLock()));

        assertTrue(gate.evaluate(
                        proposal(current, 0),
                        CertificationIssueAction.definition(),
                        context(true, CERTIFICATION))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(current, 0),
                        CertificationIssueAction.definition(),
                        context(false, CERTIFICATION))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(current, 0),
                        CertificationIssueAction.definition(),
                        context(true, new CertificationId("other-certification")))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(current, 0).toBuilder().idempotencyKey("tampered-key").build(),
                        CertificationIssueAction.definition(),
                        context(true, CERTIFICATION))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(current, 1),
                        CertificationIssueAction.definition(),
                        context(true, CERTIFICATION))
                .allowedToExecuteNow());

        var drifted = new AgentPackCertificationPolicy(
                current.inputLock().productContractBundle(),
                "release",
                current.inputLock().verificationFixtureSetHash(),
                current.inputLock().sourceLockAlgorithmId(),
                current.inputLock().certificationProfile(),
                current.inputLock().factoryVersion(),
                current.inputLock().flowerVersion(),
                current.inputLock().actionRuntimeVersion());
        assertFalse(new CertificationIssuePolicyGate(repository, drifted)
                .evaluate(proposal(current, 0), CertificationIssueAction.definition(), context(true, CERTIFICATION))
                .allowedToExecuteNow());
    }

    @Test
    void onlyThePreviouslyAuthorizedOriginalAttemptMayReachDuplicateAfterTerminalization() {
        Certification requested = requested(CERTIFICATION, inputLock());
        Certification terminal = requested.certify(
                lock("certification-manifest", 'c'),
                lock("certification-evidence", 'd'),
                "certification-action-run",
                NOW.plusSeconds(1),
                Optional.of(NOW.plusSeconds(60)));
        MutableCertifications repository = new MutableCertifications(terminal);
        CertificationIssuePolicyGate gate = new CertificationIssuePolicyGate(repository, policy(terminal.inputLock()));

        assertTrue(gate.evaluate(
                        proposal(terminal, 0), CertificationIssueAction.definition(), context(true, CERTIFICATION))
                .allowedToExecuteNow());
        assertFalse(gate.evaluate(
                        proposal(terminal, terminal.version()),
                        CertificationIssueAction.definition(),
                        context(true, CERTIFICATION))
                .allowedToExecuteNow());
    }

    @Test
    void duplicateVisibilityIsBoundToTheTrustedCertificationResource() {
        Certification first = requested(CERTIFICATION, inputLock());
        CertificationId secondId = new CertificationId("certification-controls-second");
        Certification second = requested(secondId, inputLock());
        MutableCertifications repository = new MutableCertifications(first, second);
        CertificationIssueVisibilityScopeResolver visibility =
                new CertificationIssueVisibilityScopeResolver(repository);

        assertEquals("certification:" + CERTIFICATION.value(),
                visibility.resolve(proposal(first, 0), context(true, CERTIFICATION)));
        assertEquals("certification:" + secondId.value(),
                visibility.resolve(proposal(second, 0), context(true, secondId)));
        assertThrows(IllegalArgumentException.class,
                () -> visibility.resolve(proposal(first, 0), context(true, secondId)));
    }

    private static Certification requested(CertificationId id, CertificationInputLock lock) {
        return Certification.requested(id, lock, lock("certification-input", 'b'), NOW);
    }

    private static CertificationIssueInput input(Certification certification, long expectedVersion) {
        return new CertificationIssueInput(
                certification.certificationId(), certification.inputLockArtifact().hash(), expectedVersion);
    }

    private static ActionProposal proposal(Certification certification, long expectedVersion) {
        return ActionProposal.builder(CertificationIssueAction.ACTION_ID)
                .proposalId("proposal-certification-controls")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-certifier")
                .input(input(certification, expectedVersion).toMap())
                .idempotencyKey(CertificationIssueIdempotencyKeys.derive(certification, expectedVersion))
                .build();
    }

    private static ExecutionContext context(
            boolean hasPermission, CertificationId resourceCertificationId) {
        return new ExecutionContext(
                TENANT.value(),
                "factory-certifier",
                "certification-action-run",
                "trace-certification-controls",
                Map.of(
                        "actor.permissions",
                                hasPermission ? Set.of(CertificationIssueAction.PERMISSION) : Set.of(),
                        "resource.type", CertificationIssueAction.RESOURCE_TYPE,
                        "resource.id", resourceCertificationId.value()));
    }

    private static AgentPackCertificationPolicy policy(CertificationInputLock lock) {
        return new AgentPackCertificationPolicy(
                lock.productContractBundle(),
                lock.gateProfile(),
                lock.verificationFixtureSetHash(),
                lock.sourceLockAlgorithmId(),
                lock.certificationProfile(),
                lock.factoryVersion(),
                lock.flowerVersion(),
                lock.actionRuntimeVersion());
    }

    private static CertificationInputLock inputLock() {
        return new CertificationInputLock(
                CertificationInputLock.SCHEMA_VERSION,
                TENANT,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new BuildSessionId("build-certification-controls"),
                new WorkOrderId("work-certification-controls"),
                new CandidateId("candidate-certification-controls"),
                hash('1'),
                lock("source", '1'),
                lock("dependency", '2'),
                lock("toolchain", '3'),
                lock("generation", '4'),
                lock("product", '5'),
                lock("api", '6'),
                "sha256-ordinal-v1",
                "internal",
                new VerificationRunId("verification-certification-controls"),
                "verification-action-run",
                lock("verification", '7'),
                hash('8'),
                lock("policy", '9'),
                lock("compatibility", 'a'),
                "internal",
                "0.2.0",
                "0.1.3",
                "0.3.3");
    }

    private static CertificationArtifactLock lock(String name, char hash) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:" + name), hash(hash));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }

    private static final class MutableCertifications implements CertificationRepository {
        private final Map<CertificationId, Certification> values;
        private Certification value;

        private MutableCertifications(Certification... values) {
            this.values = new java.util.LinkedHashMap<>();
            for (Certification certification : values) {
                this.values.put(certification.certificationId(), certification);
            }
            this.value = values[0];
        }

        @Override
        public void create(Certification certification) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Certification> find(TenantId tenantId, CertificationId certificationId) {
            return Optional.ofNullable(values.get(certificationId))
                    .filter(certification -> certification.inputLock().tenantId().equals(tenantId));
        }

        @Override
        public boolean compareAndSet(Certification expected, Certification next) {
            throw new UnsupportedOperationException();
        }
    }
}
