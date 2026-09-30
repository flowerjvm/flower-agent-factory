package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * Re-reads the complete trusted Reference Assembly authority immediately before release dispatch.
 * No caller-controlled tenant or previously resolved in-memory component is trusted.
 */
public final class ReferenceAssemblyReleaseAuthorityVerifier {
    private final ReferenceAssemblyRepository assemblies;
    private final BuildSessionRepository buildSessions;
    private final DecisionPointRepository decisionPoints;
    private final ReferenceAssemblyAssembler assembler;
    private final ReferenceAssemblyArtifactSupport artifactSupport;
    private final Clock clock;

    public ReferenceAssemblyReleaseAuthorityVerifier(
            ReferenceAssemblyRepository assemblies,
            BuildSessionRepository buildSessions,
            DecisionPointRepository decisionPoints,
            ReferenceAssemblyAssembler assembler,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            Clock clock) {
        this.assemblies = Objects.requireNonNull(assemblies, "assemblies");
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.decisionPoints = Objects.requireNonNull(decisionPoints, "decisionPoints");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.artifactSupport = new ReferenceAssemblyArtifactSupport(artifacts, codec);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns the exact canonical snapshots only when every release invariant still holds. */
    public Authority verify(TenantId trustedTenantId, ReferenceAssemblyReleaseInput input) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(input, "input");
        try {
            Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            ReferenceAssembly assembly = assemblies
                    .find(trustedTenantId, input.referenceAssemblyId())
                    .orElseThrow();
            BuildSession session = buildSessions
                    .find(trustedTenantId, assembly.buildSessionId())
                    .orElseThrow();
            DecisionPoint point = decisionPoints
                    .find(trustedTenantId, input.releaseDecisionPointId())
                    .orElseThrow();

            requireExactAssembly(trustedTenantId, input, assembly, observedAt);
            requireExactSession(trustedTenantId, assembly, session, observedAt);
            requireExactDecision(input, assembly, session, point, observedAt);

            CertificationArtifactLock subjectLock = new CertificationArtifactLock(
                    point.questionArtifactRef(), point.subjectHash());
            ReferenceAssemblyReleaseSubject subject =
                    artifactSupport.readReleaseSubject(trustedTenantId, subjectLock);
            requireExactSubject(input, assembly, subject, subjectLock);

            ReferenceAssemblyAssembler.ComponentResolution component =
                    assembler.resolveComponent(trustedTenantId, assembly.requirement());
            ReferenceAssemblyManifest manifest = artifactSupport.readManifest(
                    trustedTenantId, assembly.assemblyManifest().orElseThrow());
            ReferenceAssemblyInspectionReport inspection = artifactSupport.readInspectionReport(
                    trustedTenantId, assembly.inspectionReport().orElseThrow());
            requireExactGraph(assembly, component, manifest, inspection);
            return new Authority(assembly, session, point, subject, component);
        } catch (ReferenceAssemblyReleaseAuthorityException stable) {
            throw stable;
        } catch (RuntimeException invalidOrUnavailable) {
            throw invalid("Reference Assembly release authority failed closed", invalidOrUnavailable);
        }
    }

    private static void requireExactAssembly(
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            ReferenceAssembly assembly,
            Instant observedAt) {
        boolean exact = assembly.tenantId().equals(tenantId)
                && assembly.referenceAssemblyId().equals(input.referenceAssemblyId())
                && assembly.status() == ReferenceAssemblyStatus.INSPECTED
                && assembly.version() == input.expectedReferenceAssemblyVersion()
                && assembly.releaseActionRunId().isEmpty()
                && assembly.releaseManifest().isEmpty()
                && assembly.assemblyManifest()
                        .map(lock -> lock.hash().equals(input.assemblyManifestHash()))
                        .orElse(false)
                && assembly.inspectionReport()
                        .map(lock -> lock.hash().equals(input.inspectionReportHash()))
                        .orElse(false)
                && assembly.releaseDecisionPointId()
                        .filter(input.releaseDecisionPointId()::equals)
                        .isPresent()
                && assembly.releaseSubjectHash()
                        .filter(input.releaseSubjectHash()::equals)
                        .isPresent()
                && !observedAt.isBefore(assembly.updatedAt());
        require(exact, "Reference Assembly is not the exact unowned INSPECTED version");
    }

    private static void requireExactSession(
            TenantId tenantId,
            ReferenceAssembly assembly,
            BuildSession session,
            Instant observedAt) {
        CertificationArtifactLock sessionRequirement = new CertificationArtifactLock(
                session.requirementsArtifactRef(), session.requirementsHash());
        boolean exact = session.tenantId().equals(tenantId)
                && session.buildSessionId().equals(assembly.buildSessionId())
                && ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())
                && session.status() == BuildSessionStatus.RUNNING
                && session.currentPhase() == BuildSessionPhase.PACKAGE_RELEASE
                && session.cancellationRequestedAt().isEmpty()
                && sessionRequirement.equals(assembly.requirement())
                && !observedAt.isBefore(session.updatedAt())
                && observedAt.isBefore(session.deadlineAt());
        require(exact, "BuildSession is not the exact live release phase");
    }

    private static void requireExactDecision(
            ReferenceAssemblyReleaseInput input,
            ReferenceAssembly assembly,
            BuildSession session,
            DecisionPoint point,
            Instant observedAt) {
        ArtifactReference canonicalQuestion = new ArtifactReference(
                ReferenceAssemblyArtifactSupport.RELEASE_SUBJECT_REFERENCE_PREFIX
                        + input.releaseSubjectHash().sha256());
        boolean exact = point.decisionPointId().equals(input.releaseDecisionPointId())
                && point.tenantId().equals(assembly.tenantId())
                && point.buildSessionId().equals(assembly.buildSessionId())
                && ReferenceAssemblyReleaseReviewService.DECISION_TYPE.equals(point.type())
                && point.status() == DecisionPointStatus.APPROVED
                && point.version() == 1
                && ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE.equals(point.subjectType())
                && point.subjectId().equals(assembly.referenceAssemblyId().value())
                && point.subjectVersion() + 1 == input.expectedReferenceAssemblyVersion()
                && point.subjectHash().equals(input.releaseSubjectHash())
                && point.questionArtifactRef().equals(canonicalQuestion)
                && ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID.equals(
                        point.optionsSchemaId())
                && point.requiredPermissions().equals(
                        Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION))
                && point.minimumApprovers() == 1
                && point.policySnapshotRef().equals(assembly.policySnapshot().reference())
                && point.dueAt().equals(session.deadlineAt())
                && point.openedAt().isBefore(point.dueAt())
                && point.decidedAt().filter(value -> !value.isAfter(observedAt)).isPresent()
                && point.terminalDecisionId().isPresent();
        require(exact, "release DecisionPoint is not the exact approved authority");
    }

    private static void requireExactSubject(
            ReferenceAssemblyReleaseInput input,
            ReferenceAssembly assembly,
            ReferenceAssemblyReleaseSubject subject,
            CertificationArtifactLock subjectLock) {
        boolean exact = subjectLock.hash().equals(input.releaseSubjectHash())
                && ProductLineId.REFERENCE_ASSEMBLY.equals(subject.productLineId())
                && subject.referenceAssemblyId().equals(assembly.referenceAssemblyId())
                && subject.inspectedAssemblyVersion() + 1
                        == input.expectedReferenceAssemblyVersion()
                && subject.assemblyManifest()
                        .equals(assembly.assemblyManifest().orElseThrow())
                && subject.inspectionReport()
                        .equals(assembly.inspectionReport().orElseThrow())
                && subject.componentCertificationId()
                        .equals(assembly.componentCertificationId())
                && subject.componentCandidateHash().equals(assembly.componentCandidateHash())
                && subject.componentCertificationManifest()
                        .equals(assembly.componentCertificationManifest())
                && subject.policySnapshot().equals(assembly.policySnapshot());
        require(exact, "canonical release subject differs from the inspected ledger snapshot");
    }

    private static void requireExactGraph(
            ReferenceAssembly assembly,
            ReferenceAssemblyAssembler.ComponentResolution component,
            ReferenceAssemblyManifest manifest,
            ReferenceAssemblyInspectionReport inspection) {
        var requirement = component.requirement();
        boolean exact = requirement.consumerContract().equals(assembly.consumerContract())
                && requirement.hostFixture().equals(assembly.hostFixture())
                && requirement.policySnapshot().equals(assembly.policySnapshot())
                && requirement.component().equals(component.component().reference())
                && requirement.component().certificationId()
                        .equals(assembly.componentCertificationId())
                && requirement.component().candidateHash()
                        .equals(assembly.componentCandidateHash())
                && requirement.component().certificationManifest()
                        .equals(assembly.componentCertificationManifest())
                && manifest.requirement().equals(assembly.requirement())
                && manifest.consumerContract().equals(assembly.consumerContract())
                && manifest.hostFixture().equals(assembly.hostFixture())
                && manifest.policySnapshot().equals(assembly.policySnapshot())
                && manifest.component().equals(requirement.component())
                && inspection.assemblyManifest()
                        .equals(assembly.assemblyManifest().orElseThrow())
                && inspection.consumerContract().equals(assembly.consumerContract())
                && inspection.hostFixture().equals(assembly.hostFixture())
                && inspection.policySnapshot().equals(assembly.policySnapshot())
                && inspection.component().equals(requirement.component())
                && ReferenceAssemblyInspectionReport.PASSED.equals(inspection.status());
        require(exact, "assembled or independently inspected graph differs from durable locks");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static ReferenceAssemblyReleaseAuthorityException invalid(String message) {
        return new ReferenceAssemblyReleaseAuthorityException(message);
    }

    private static ReferenceAssemblyReleaseAuthorityException invalid(
            String message, Throwable cause) {
        return new ReferenceAssemblyReleaseAuthorityException(message, cause);
    }

    /** Canonical snapshots proven at one pre-dispatch observation. */
    public record Authority(
            ReferenceAssembly assembly,
            BuildSession buildSession,
            DecisionPoint decisionPoint,
            ReferenceAssemblyReleaseSubject releaseSubject,
            ReferenceAssemblyAssembler.ComponentResolution component) {
        public Authority {
            Objects.requireNonNull(assembly, "assembly");
            Objects.requireNonNull(buildSession, "buildSession");
            Objects.requireNonNull(decisionPoint, "decisionPoint");
            Objects.requireNonNull(releaseSubject, "releaseSubject");
            Objects.requireNonNull(component, "component");
        }
    }
}
