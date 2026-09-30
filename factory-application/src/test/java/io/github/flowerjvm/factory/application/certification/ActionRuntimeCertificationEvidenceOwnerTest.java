package io.github.flowerjvm.factory.application.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
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
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionRuntimeCertificationEvidenceOwnerTest {
    private static final TenantId TENANT = new TenantId("tenant-certification-owner");
    private static final CertificationId CERTIFICATION =
            new CertificationId("certification-owner");
    private static final String ACTION_RUN = "certification-action-owner";
    private static final String ATTEMPT_TOKEN = "certification-attempt-token";
    private static final Instant NOW = Instant.parse("2026-09-02T02:00:00Z");

    @Test
    void canonicalCompletedIntentAndSucceededExactActionOwnTheCertification() {
        Fixture fixture = new Fixture();

        assertEquals(
                CertificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED,
                fixture.owner(fixture.intent, fixture.action(ActionRunStatus.SUCCEEDED, fixture.exactResult(), ATTEMPT_TOKEN))
                        .assess(fixture.certified)
                        .status());
    }

    @Test
    void exactSucceededActionBeforeIntentCompletedCasRemainsPendingForRunnerReconciliation() {
        Fixture fixture = new Fixture();

        CertificationActionEvidenceOwner.Assessment assessment = fixture
                .owner(
                        fixture.runningIntent(),
                        fixture.action(
                                ActionRunStatus.SUCCEEDED,
                                fixture.exactResult(),
                                ATTEMPT_TOKEN))
                .assess(fixture.certified);

        assertEquals(CertificationActionEvidenceOwner.Status.PENDING, assessment.status());
    }

    @Test
    void certifiedSeedOrManualRowWithoutLatestIntentFailsClosed() {
        Fixture fixture = new Fixture();

        CertificationActionEvidenceOwner.Assessment assessment =
                fixture.owner(null, null).assess(fixture.certified);

        assertEquals(CertificationActionEvidenceOwner.Status.INVALID_TERMINAL, assessment.status());
        assertEquals(ActionRuntimeCertificationEvidenceOwner.ACTION_INTENT_MISSING, assessment.code());
    }

    @Test
    void completedIntentWithoutItsActionRunFailsClosed() {
        Fixture fixture = new Fixture();

        CertificationActionEvidenceOwner.Assessment assessment =
                fixture.owner(fixture.intent, null).assess(fixture.certified);

        assertEquals(CertificationActionEvidenceOwner.Status.INVALID_TERMINAL, assessment.status());
    }

    @Test
    void wrongActionOwnerBindingFailsClosed() {
        Fixture fixture = new Fixture();

        CertificationActionEvidenceOwner.Assessment assessment = fixture
                .owner(
                        fixture.intent,
                        fixture.action(ActionRunStatus.SUCCEEDED, fixture.exactResult(), "different-token"))
                .assess(fixture.certified);

        assertEquals(CertificationActionEvidenceOwner.Status.INVALID_TERMINAL, assessment.status());
    }

    @Test
    void nonDeterministicDispatchOperationFailsClosed() {
        Fixture fixture = new Fixture();
        CertificationDispatchIntent wrongOperation = fixture.completedIntent("certification:wrong-operation");

        CertificationActionEvidenceOwner.Assessment assessment = fixture
                .owner(
                        wrongOperation,
                        fixture.action(ActionRunStatus.SUCCEEDED, fixture.exactResult(), ATTEMPT_TOKEN))
                .assess(fixture.certified);

        assertEquals(CertificationActionEvidenceOwner.Status.INVALID_TERMINAL, assessment.status());
    }

    @Test
    void failedTerminalActionCannotOwnCertifiedLedger() {
        Fixture fixture = new Fixture();

        CertificationActionEvidenceOwner.Assessment assessment = fixture
                .owner(
                        fixture.intent,
                        fixture.action(
                                ActionRunStatus.FAILED,
                                ActionExecutionResult.failed("CERTIFICATION_FAILED", "failed"),
                                ATTEMPT_TOKEN))
                .assess(fixture.certified);

        assertEquals(CertificationActionEvidenceOwner.Status.INVALID_TERMINAL, assessment.status());
    }

    @Test
    void succeededActionWithDifferentTerminalResultCannotOwnCertification() {
        Fixture fixture = new Fixture();

        CertificationActionEvidenceOwner.Assessment assessment = fixture
                .owner(
                        fixture.intent,
                        fixture.action(
                                ActionRunStatus.SUCCEEDED,
                                ActionExecutionResult.succeeded(Map.of("wrong", "result")),
                                ATTEMPT_TOKEN))
                .assess(fixture.certified);

        assertEquals(CertificationActionEvidenceOwner.Status.INVALID_TERMINAL, assessment.status());
    }

    private static final class Fixture {
        private final CertificationArtifactLock inputArtifact = lock("input", 'b');
        private final CertificationArtifactLock manifest = lock("manifest", 'c');
        private final CertificationArtifactLock evidence = lock("evidence", 'd');
        private final Certification certified = Certification.requested(
                        CERTIFICATION,
                        input(),
                        inputArtifact,
                        NOW.minusSeconds(20))
                .certify(manifest, evidence, ACTION_RUN, NOW.minusSeconds(10), Optional.empty());
        private final CertificationIssueInput input = new CertificationIssueInput(
                CERTIFICATION, inputArtifact.hash(), 0);
        private final String operationId = CertificationDispatchOperationIds.derive(TENANT, input);
        private final CertificationDispatchIntent intent = completedIntent(operationId);

        private CertificationDispatchIntent completedIntent(String operation) {
            return CertificationDispatchIntent.pending(
                        operation,
                        TENANT,
                        CERTIFICATION,
                        inputArtifact.hash(),
                        0,
                        ACTION_RUN,
                        CertificationAttemptTokens.hash(ATTEMPT_TOKEN),
                        NOW.plusSeconds(300),
                        NOW.minusSeconds(19))
                .claim("claim", NOW.minusSeconds(18), Duration.ofSeconds(60))
                .complete("claim", CertificationDispatchRunner.CERTIFICATION_DISPATCH_COMPLETED,
                        NOW.minusSeconds(9));
        }

        private CertificationDispatchIntent runningIntent() {
            return CertificationDispatchIntent.pending(
                            operationId,
                            TENANT,
                            CERTIFICATION,
                            inputArtifact.hash(),
                            0,
                            ACTION_RUN,
                            CertificationAttemptTokens.hash(ATTEMPT_TOKEN),
                            NOW.plusSeconds(300),
                            NOW.minusSeconds(19))
                    .claim("claim", NOW.minusSeconds(18), Duration.ofSeconds(60));
        }

        private ActionRuntimeCertificationEvidenceOwner owner(
                CertificationDispatchIntent observedIntent, ActionRun action) {
            InMemoryRunStore runs = new InMemoryRunStore();
            if (action != null) {
                runs.create(action);
            }
            return new ActionRuntimeCertificationEvidenceOwner(repository(observedIntent), runs);
        }

        private ActionRun action(
                ActionRunStatus status, ActionExecutionResult result, String attemptToken) {
            return ActionRun.builder()
                    .runId(ACTION_RUN)
                    .version(5)
                    .tenantId(TENANT.value())
                    .userId("certification-principal")
                    .traceId("trace-certification-owner")
                    .contextMetadata(Map.of(
                            "actor.permissions", Set.of(CertificationIssueAction.PERMISSION),
                            "resource.type", CertificationIssueAction.RESOURCE_TYPE,
                            "resource.id", CERTIFICATION.value()))
                    .actionId(CertificationIssueAction.ACTION_ID)
                    .proposalId("proposal-certification-owner")
                    .requesterId("factory-service")
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .input(input.toMap())
                    .duplicateKey(CertificationIssueIdempotencyKeys.derive(certified, 0))
                    .status(status)
                    .currentStage("TERMINAL")
                    .attemptToken(attemptToken)
                    .externalOperationId(operationId)
                    .externalOperationMetadata(Map.of(
                            "dispatchMode", "durable-certification-intent",
                            CertificationIssueAction.CERTIFICATION_ID,
                            CERTIFICATION.value()))
                    .dueAt(intent.deadlineAt())
                    .result(result)
                    .createdAt(NOW.minusSeconds(19))
                    .updatedAt(NOW.minusSeconds(9))
                    .build();
        }

        private ActionExecutionResult exactResult() {
            return ActionExecutionResult.succeeded(Map.of(
                    CertificationIssueAction.CERTIFICATION_ID,
                    CERTIFICATION.value(),
                    CertificationDispatchRunner.CERTIFICATION_STATUS,
                    CertificationStatus.CERTIFIED.name(),
                    CertificationDispatchRunner.CERTIFICATION_MANIFEST_REF,
                    manifest.reference().value(),
                    CertificationDispatchRunner.CERTIFICATION_MANIFEST_HASH,
                    manifest.hash().sha256(),
                    CertificationDispatchRunner.CERTIFICATION_EVIDENCE_REF,
                    evidence.reference().value(),
                    CertificationDispatchRunner.CERTIFICATION_EVIDENCE_HASH,
                    evidence.hash().sha256()));
        }
    }

    private static CertificationDispatchIntentRepository repository(
            CertificationDispatchIntent intent) {
        return new CertificationDispatchIntentRepository() {
            @Override
            public void create(CertificationDispatchIntent value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<CertificationDispatchIntent> find(String operationId) {
                return Optional.ofNullable(intent);
            }

            @Override
            public Optional<CertificationDispatchIntent> findLatest(
                    TenantId tenantId, CertificationId certificationId) {
                return Optional.ofNullable(intent);
            }

            @Override
            public Optional<CertificationDispatchIntent> claimNext(
                    Instant now, Duration lease, String claimToken) {
                return Optional.empty();
            }

            @Override
            public Optional<CertificationDispatchIntent> claimExpiredRunning(
                    Instant now, Duration lease, String claimToken) {
                return Optional.empty();
            }

            @Override
            public boolean compareAndSet(
                    CertificationDispatchIntent expected, CertificationDispatchIntent next) {
                return false;
            }
        };
    }

    private static CertificationInputLock input() {
        return new CertificationInputLock(
                CertificationInputLock.SCHEMA_VERSION,
                TENANT,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new BuildSessionId("build-owner"),
                new WorkOrderId("work-owner"),
                new CandidateId("candidate-owner"),
                hash('1'),
                lock("source", '1'),
                lock("dependency", '2'),
                lock("toolchain", '3'),
                lock("generation", '4'),
                lock("product", '5'),
                lock("api", '6'),
                "sha256-ordinal-v1",
                "internal",
                new VerificationRunId("verification-owner"),
                "verification-action-owner",
                lock("verification", '7'),
                hash('8'),
                lock("policy", '9'),
                lock("compatibility", 'a'),
                "internal",
                "0.2.0",
                "0.1.3",
                "0.3.3");
    }

    private static CertificationArtifactLock lock(String name, char value) {
        return new CertificationArtifactLock(
                new ArtifactReference("artifact:" + name), hash(value));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
