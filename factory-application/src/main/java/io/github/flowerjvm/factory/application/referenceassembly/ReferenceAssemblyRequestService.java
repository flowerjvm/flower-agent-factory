package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Constructs one deterministic Reference Assembly request from trusted exact-lock inputs. */
public final class ReferenceAssemblyRequestService {
    public static final String SESSION_NOT_FOUND =
            "REFERENCE_ASSEMBLY_REQUEST_SESSION_NOT_FOUND";
    public static final String SESSION_NOT_ELIGIBLE =
            "REFERENCE_ASSEMBLY_REQUEST_SESSION_NOT_ELIGIBLE";
    public static final String ARTIFACT_INVALID =
            "REFERENCE_ASSEMBLY_REQUEST_ARTIFACT_INVALID";
    public static final String ADMISSION_REJECTED =
            "REFERENCE_ASSEMBLY_REQUEST_ADMISSION_REJECTED";
    public static final String REPOSITORY_CONFLICT =
            "REFERENCE_ASSEMBLY_REQUEST_REPOSITORY_CONFLICT";

    private static final byte[] ID_DOMAIN =
            "factory.reference-assembly-request.v1".getBytes(StandardCharsets.UTF_8);

    private final BuildSessionRepository buildSessions;
    private final ReferenceAssemblyRepository referenceAssemblies;
    private final ReferenceAssemblyArtifactSupport artifactSupport;
    private final ReferenceAssemblyAdmissionPolicies admissionPolicies;
    private final Clock clock;

    public ReferenceAssemblyRequestService(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository referenceAssemblies,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            ReferenceAssemblyAdmissionPolicy admissionPolicy,
            Clock clock) {
        this(buildSessions, referenceAssemblies, artifacts, codec,
                ReferenceAssemblyAdmissionPolicies.of(admissionPolicy), clock);
    }

    public ReferenceAssemblyRequestService(
            BuildSessionRepository buildSessions,
            ReferenceAssemblyRepository referenceAssemblies,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            ReferenceAssemblyAdmissionPolicies admissionPolicies,
            Clock clock) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.referenceAssemblies = Objects.requireNonNull(
                referenceAssemblies, "referenceAssemblies");
        this.artifactSupport = new ReferenceAssemblyArtifactSupport(artifacts, codec);
        this.admissionPolicies = Objects.requireNonNull(admissionPolicies, "admissionPolicies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Uses only the caller-authenticated tenant and durable BuildSession id as authority. */
    public ReferenceAssemblyRequestOutcome ensureRequested(
            TenantId trustedTenantId, BuildSessionId buildSessionId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        BuildSession session = findSession(trustedTenantId, buildSessionId);
        requireEligibleSession(trustedTenantId, buildSessionId, session, requestedAt);

        CertificationArtifactLock requirementLock = new CertificationArtifactLock(
                session.requirementsArtifactRef(), session.requirementsHash());
        ReferenceAssemblyRequirement requirement;
        ReferenceAssemblyConsumerContract contract;
        try {
            requirement = artifactSupport.readRequirement(trustedTenantId, requirementLock);
            contract = artifactSupport.readConsumerContract(
                    trustedTenantId, requirement.consumerContract());
            artifactSupport.readOpaque(trustedTenantId, requirement.hostFixture());
            artifactSupport.readOpaque(trustedTenantId, requirement.policySnapshot());
        } catch (RuntimeException invalid) {
            throw fail(
                    ARTIFACT_INVALID,
                    "Reference Assembly request graph failed its exact artifact read gate",
                    invalid);
        }
        requireAdmitted(requirement, contract);

        ReferenceAssemblyId assemblyId = deriveId(trustedTenantId, buildSessionId);
        ReferenceAssembly requested = ReferenceAssembly.requested(
                assemblyId,
                trustedTenantId,
                buildSessionId,
                requirementLock,
                requirement.consumerContract(),
                requirement.hostFixture(),
                requirement.policySnapshot(),
                requirement.component().certificationId(),
                requirement.component().candidateHash(),
                requirement.component().certificationManifest(),
                requestedAt);

        Optional<ReferenceAssembly> existing = findByBuildSession(
                trustedTenantId, buildSessionId);
        if (existing.isPresent()) {
            return exactExistingOutcome(session, requested, existing.orElseThrow());
        }
        if (findById(trustedTenantId, assemblyId).isPresent()) {
            throw conflict("deterministic Reference Assembly id is bound outside its BuildSession index");
        }

        boolean createReturned = false;
        try {
            referenceAssemblies.create(requested);
            createReturned = true;
        } catch (RuntimeException possibleDuplicate) {
            // A duplicate/race is acceptable only after both repository indexes expose the exact
            // canonical version-zero request. All other failures remain a stable conflict.
        }
        ReferenceAssembly observed = findByBuildSession(trustedTenantId, buildSessionId)
                .orElseThrow(() -> conflict("Reference Assembly create did not expose a canonical row"));
        ReferenceAssemblyRequestOutcome reconciled =
                exactExistingOutcome(session, requested, observed);
        if (createReturned && observed.equals(requested)) {
            return new ReferenceAssemblyRequestOutcome(
                    ReferenceAssemblyRequestDisposition.CREATED, observed);
        }
        return reconciled;
    }

    private BuildSession findSession(TenantId tenantId, BuildSessionId buildSessionId) {
        try {
            return buildSessions.find(tenantId, buildSessionId)
                    .orElseThrow(() -> fail(
                            SESSION_NOT_FOUND, "trusted Reference Assembly BuildSession is missing"));
        } catch (ReferenceAssemblyRequestException stable) {
            throw stable;
        } catch (RuntimeException failure) {
            throw fail(
                    SESSION_NOT_FOUND,
                    "trusted Reference Assembly BuildSession lookup failed closed",
                    failure);
        }
    }

    private static void requireEligibleSession(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            BuildSession session,
            Instant requestedAt) {
        if (!session.tenantId().equals(tenantId)
                || !session.buildSessionId().equals(buildSessionId)
                || !ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())
                || session.status() != BuildSessionStatus.RUNNING
                || session.currentPhase() != BuildSessionPhase.UNDERSTAND_CUSTOMER
                || session.cancellationRequestedAt().isPresent()
                || session.currentCertificationId().isPresent()
                || requestedAt.isBefore(session.updatedAt())
                || !requestedAt.isBefore(session.deadlineAt())) {
            throw fail(
                    SESSION_NOT_ELIGIBLE,
                    "BuildSession is not an exact live Reference Assembly request authority");
        }
    }

    private void requireAdmitted(
            ReferenceAssemblyRequirement requirement,
            ReferenceAssemblyConsumerContract contract) {
        ReferenceAssemblyAdmissionPolicy admissionPolicy = admissionPolicies.find(requirement.consumerContract())
                .orElseThrow(() -> fail(ADMISSION_REJECTED,
                        "Reference Assembly consumer contract has no exact trusted entry"));
        if (!requirement.consumerContract().equals(admissionPolicy.approvedConsumerContract())
                || !requirement.hostFixture().equals(admissionPolicy.approvedHostFixture())
                || !requirement.policySnapshot().equals(admissionPolicy.approvedPolicySnapshot())
                || !contract.requiredHostFixture().equals(admissionPolicy.approvedHostFixture())
                || !contract.requiredAgentProductContract().equals(
                        admissionPolicy.expectedAgentProductContract())
                || !contract.requiredApiSignatureIndex().equals(
                        admissionPolicy.expectedApiSignatureIndex())
                || !contract.requiredComponentRole().equals(
                        admissionPolicy.requiredComponentRole())
                || !requirement.component().componentRole().equals(
                        admissionPolicy.requiredComponentRole())
                || !contract.requiredCertificationProfile().equals(
                        admissionPolicy.requiredCertificationProfile())
                || !requirement.component().certificationProfile().equals(
                        admissionPolicy.requiredCertificationProfile())
                || !contract.requiredFlowerVersion().equals(
                        admissionPolicy.requiredFlowerVersion())
                || !contract.requiredActionRuntimeVersion().equals(
                        admissionPolicy.requiredActionRuntimeVersion())
                || !contract.assemblyAlgorithmId().equals(
                        admissionPolicy.assemblyAlgorithmId())) {
            throw fail(
                    ADMISSION_REJECTED,
                    "Reference Assembly request graph is outside the trusted admission policy");
        }
    }

    private ReferenceAssemblyRequestOutcome exactExistingOutcome(
            BuildSession session,
            ReferenceAssembly requested,
            ReferenceAssembly existing) {
        Optional<ReferenceAssembly> indexed = findById(
                requested.tenantId(), requested.referenceAssemblyId());
        if (indexed.filter(existing::equals).isEmpty()
                || !isExactCanonicalRequest(session, requested, existing)) {
            throw conflict("stored Reference Assembly differs from the canonical request");
        }
        return new ReferenceAssemblyRequestOutcome(
                ReferenceAssemblyRequestDisposition.EXISTING_EXACT, existing);
    }

    private static boolean isExactCanonicalRequest(
            BuildSession session, ReferenceAssembly requested, ReferenceAssembly existing) {
        return existing.referenceAssemblyId().equals(requested.referenceAssemblyId())
                && existing.tenantId().equals(requested.tenantId())
                && existing.buildSessionId().equals(requested.buildSessionId())
                && existing.requirement().equals(requested.requirement())
                && existing.consumerContract().equals(requested.consumerContract())
                && existing.hostFixture().equals(requested.hostFixture())
                && existing.policySnapshot().equals(requested.policySnapshot())
                && existing.componentCertificationId().equals(
                        requested.componentCertificationId())
                && existing.componentCandidateHash().equals(
                        requested.componentCandidateHash())
                && existing.componentCertificationManifest().equals(
                        requested.componentCertificationManifest())
                && !existing.createdAt().isBefore(session.updatedAt())
                && existing.createdAt().isBefore(session.deadlineAt());
    }

    private Optional<ReferenceAssembly> findByBuildSession(
            TenantId tenantId, BuildSessionId buildSessionId) {
        try {
            return referenceAssemblies.findByBuildSession(tenantId, buildSessionId);
        } catch (RuntimeException failure) {
            throw fail(
                    REPOSITORY_CONFLICT,
                    "Reference Assembly BuildSession index lookup failed closed",
                    failure);
        }
    }

    private Optional<ReferenceAssembly> findById(
            TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
        try {
            return referenceAssemblies.find(tenantId, referenceAssemblyId);
        } catch (RuntimeException failure) {
            throw fail(
                    REPOSITORY_CONFLICT,
                    "Reference Assembly identity lookup failed closed",
                    failure);
        }
    }

    static ReferenceAssemblyId deriveId(TenantId tenantId, BuildSessionId buildSessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, ID_DOMAIN);
            updateLengthPrefixed(digest, tenantId.value().getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(digest, buildSessionId.value().getBytes(StandardCharsets.UTF_8));
            return new ReferenceAssemblyId(
                    "reference-assembly-" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static ReferenceAssemblyRequestException conflict(String message) {
        return fail(REPOSITORY_CONFLICT, message);
    }

    private static ReferenceAssemblyRequestException fail(String code, String message) {
        return new ReferenceAssemblyRequestException(code, message);
    }

    private static ReferenceAssemblyRequestException fail(
            String code, String message, Throwable cause) {
        return new ReferenceAssemblyRequestException(code, message, cause);
    }
}
