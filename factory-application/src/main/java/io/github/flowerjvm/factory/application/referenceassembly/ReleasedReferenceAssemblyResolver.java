package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.util.Objects;

/** Reconstructs a released Reference Assembly only from exact same-tenant durable evidence. */
public final class ReleasedReferenceAssemblyResolver
        implements ReleasedReferenceAssemblyReadGate {
    private final ReferenceAssemblyRepository referenceAssemblies;
    private final BuildSessionRepository buildSessions;
    private final ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents;
    private final RunStore actionRuns;
    private final ReferenceAssemblyArtifactSupport artifactSupport;
    private final CertifiedAgentComponentReadGate componentReadGate;
    private final ReferenceAssemblyAdmissionPolicies admissionPolicies;

    public ReleasedReferenceAssemblyResolver(
            ReferenceAssemblyRepository referenceAssemblies,
            BuildSessionRepository buildSessions,
            ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents,
            RunStore actionRuns,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate) {
        this(referenceAssemblies, buildSessions, releaseIntents, actionRuns, artifacts, codec, componentReadGate, null);
    }

    public ReleasedReferenceAssemblyResolver(
            ReferenceAssemblyRepository referenceAssemblies,
            BuildSessionRepository buildSessions,
            ReferenceAssemblyReleaseDispatchIntentRepository releaseIntents,
            RunStore actionRuns,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate,
            ReferenceAssemblyAdmissionPolicies admissionPolicies) {
        this.referenceAssemblies = Objects.requireNonNull(
                referenceAssemblies, "referenceAssemblies");
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.releaseIntents = Objects.requireNonNull(releaseIntents, "releaseIntents");
        this.actionRuns = Objects.requireNonNull(actionRuns, "actionRuns");
        this.artifactSupport = new ReferenceAssemblyArtifactSupport(artifacts, codec);
        this.componentReadGate = Objects.requireNonNull(componentReadGate, "componentReadGate");
        this.admissionPolicies = admissionPolicies;
    }

    /** The same opaque failure is returned for absence, lifecycle, tenant, evidence, or revocation. */
    @Override
    public ResolvedReleasedReferenceAssembly resolve(
            TenantId trustedTenantId, ReferenceAssemblyId referenceAssemblyId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        try {
            ReferenceAssembly assembly = referenceAssemblies
                    .find(trustedTenantId, referenceAssemblyId)
                    .orElseThrow(ReleasedReferenceAssemblyResolver::unavailable);
            requireReleasedIdentity(trustedTenantId, referenceAssemblyId, assembly);

            CertificationArtifactLock releaseLock = assembly.releaseManifest().orElseThrow();
            ArtifactReference canonicalReleaseReference = new ArtifactReference(
                    ReferenceAssemblyArtifactSupport.RELEASE_MANIFEST_REFERENCE_PREFIX
                            + releaseLock.hash().sha256());
            if (!releaseLock.reference().equals(canonicalReleaseReference)) {
                throw unavailable();
            }
            ReferenceAssemblyReleaseManifest release = artifactSupport.readReleaseManifest(
                    trustedTenantId, releaseLock);
            requireExactReleaseLedger(assembly, release);
            ReferenceAssemblyRequirement requirement = artifactSupport.readRequirement(
                    trustedTenantId, release.requirement());
            var contract = artifactSupport.readConsumerContract(trustedTenantId, release.consumerContract());
            artifactSupport.readOpaque(trustedTenantId, release.hostFixture());
            artifactSupport.readOpaque(trustedTenantId, release.policySnapshot());
            ReferenceAssemblyManifest manifest = artifactSupport.readManifest(
                    trustedTenantId, release.assemblyManifest());
            ReferenceAssemblyInspectionReport inspection = artifactSupport.readInspectionReport(
                    trustedTenantId, release.inspectionReport());
            CertificationArtifactLock subjectLock = new CertificationArtifactLock(
                    new ArtifactReference(
                            ReferenceAssemblyArtifactSupport.RELEASE_SUBJECT_REFERENCE_PREFIX
                                    + release.releaseSubjectHash().sha256()),
                    release.releaseSubjectHash());
            ReferenceAssemblyReleaseSubject subject = artifactSupport.readReleaseSubject(
                    trustedTenantId, subjectLock);

            requireExactReleasedGraph(
                    assembly, release, requirement, manifest, inspection, subject);
            requireExactTerminalAuthority(trustedTenantId, assembly, releaseLock);
            ResolvedCertifiedAgentComponent component = Objects.requireNonNull(
                    componentReadGate.resolve(trustedTenantId, release.component()),
                    "resolved component");
            if (admissionPolicies != null) {
                ReferenceAssemblyAssembler.requireCompatibility(trustedTenantId, requirement, contract, component,
                        admissionPolicies.requireApproved(requirement));
            } else if (!io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract
                    .CONTRACT_VERSION.equals(contract.contractVersion())) {
                throw unavailable();
            }
            if (!component.reference().equals(release.component())
                    || !component.inputLock().tenantId().equals(trustedTenantId)
                    || !component.compatibilityDescriptor().tenantId().equals(trustedTenantId)) {
                throw unavailable();
            }
            return new ResolvedReleasedReferenceAssembly(
                    assembly,
                    releaseLock,
                    release,
                    requirement,
                    manifest,
                    inspection,
                    subject,
                    component);
        } catch (RuntimeException unavailableOrInvalid) {
            throw unavailable();
        }
    }

    private void requireExactTerminalAuthority(
            TenantId tenantId,
            ReferenceAssembly assembly,
            CertificationArtifactLock releaseLock) {
        BuildSession session = buildSessions
                .find(tenantId, assembly.buildSessionId())
                .orElseThrow(ReleasedReferenceAssemblyResolver::unavailable);
        ReferenceAssemblyReleaseDispatchIntent intent = releaseIntents
                .findLatest(tenantId, assembly.referenceAssemblyId())
                .orElseThrow(ReleasedReferenceAssemblyResolver::unavailable);
        if (!session.tenantId().equals(tenantId)
                || !session.buildSessionId().equals(assembly.buildSessionId())
                || !ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())
                || session.status() != BuildSessionStatus.SUCCEEDED
                || session.currentPhase() != BuildSessionPhase.COMPLETE
                || !session.requirementsArtifactRef().equals(assembly.requirement().reference())
                || !session.requirementsHash().equals(assembly.requirement().hash())
                || session.cancellationRequestedAt().isPresent()
                || !session.deadlineAt().equals(intent.deadlineAt())
                || intent.status() != ReferenceAssemblyReleaseDispatchIntentStatus.COMPLETED
                || intent.lastCode()
                        .filter(ReferenceAssemblyReleaseDispatchRunner.DISPATCH_COMPLETED::equals)
                        .isEmpty()
                || assembly.version()
                        != Math.addExact(intent.expectedReferenceAssemblyVersion(), 2L)
                || !ReferenceAssemblyReleaseService.matchesImmutableIdentity(intent, assembly)) {
            throw unavailable();
        }

        ActionRun actionRun = actionRuns
                .find(intent.actionRunId())
                .orElseThrow(ReleasedReferenceAssemblyResolver::unavailable);
        ReferenceAssemblyReleaseOutcome outcome =
                new ReferenceAssemblyReleaseOutcome(assembly, releaseLock, false);
        boolean exactTerminalAction =
                ReferenceAssemblyReleaseDispatchRunner.hasExactTerminalOwnerBinding(
                        intent, actionRun, assembly)
                && ReferenceAssemblyReleaseDispatchRunner.exactTerminalResult(
                        outcome, actionRun.result());
        if (!exactTerminalAction) {
            throw unavailable();
        }
    }

    private static void requireReleasedIdentity(
            TenantId tenantId,
            ReferenceAssemblyId referenceAssemblyId,
            ReferenceAssembly assembly) {
        if (!assembly.tenantId().equals(tenantId)
                || !assembly.referenceAssemblyId().equals(referenceAssemblyId)
                || assembly.status() != ReferenceAssemblyStatus.RELEASED
                || assembly.releaseManifest().isEmpty()
                || assembly.assemblyManifest().isEmpty()
                || assembly.inspectionReport().isEmpty()
                || assembly.releaseDecisionPointId().isEmpty()
                || assembly.releaseSubjectHash().isEmpty()
                || assembly.releaseActionRunId().isEmpty()) {
            throw unavailable();
        }
    }

    private static void requireExactReleaseLedger(
            ReferenceAssembly assembly, ReferenceAssemblyReleaseManifest release) {
        CertifiedAgentComponentRef component = release.component();
        boolean exact = ProductLineId.REFERENCE_ASSEMBLY.equals(release.productLineId())
                && release.referenceAssemblyId().equals(assembly.referenceAssemblyId())
                && release.requirement().equals(assembly.requirement())
                && release.consumerContract().equals(assembly.consumerContract())
                && release.hostFixture().equals(assembly.hostFixture())
                && release.policySnapshot().equals(assembly.policySnapshot())
                && component.certificationId().equals(assembly.componentCertificationId())
                && component.candidateHash().equals(assembly.componentCandidateHash())
                && component.certificationManifest()
                        .equals(assembly.componentCertificationManifest())
                && release.assemblyManifest()
                        .equals(assembly.assemblyManifest().orElseThrow())
                && release.inspectionReport()
                        .equals(assembly.inspectionReport().orElseThrow())
                && release.releaseDecisionPointId()
                        .equals(assembly.releaseDecisionPointId().orElseThrow())
                && release.releaseSubjectHash()
                        .equals(assembly.releaseSubjectHash().orElseThrow())
                && release.releaseActionRunId()
                        .equals(assembly.releaseActionRunId().orElseThrow());
        if (!exact) {
            throw unavailable();
        }
    }

    private static void requireExactReleasedGraph(
            ReferenceAssembly assembly,
            ReferenceAssemblyReleaseManifest release,
            ReferenceAssemblyRequirement requirement,
            ReferenceAssemblyManifest manifest,
            ReferenceAssemblyInspectionReport inspection,
            ReferenceAssemblyReleaseSubject subject) {
        CertifiedAgentComponentRef component = release.component();
        boolean exact = ProductLineId.REFERENCE_ASSEMBLY.equals(release.productLineId())
                && release.referenceAssemblyId().equals(assembly.referenceAssemblyId())
                && release.requirement().equals(assembly.requirement())
                && release.consumerContract().equals(assembly.consumerContract())
                && release.hostFixture().equals(assembly.hostFixture())
                && release.policySnapshot().equals(assembly.policySnapshot())
                && component.certificationId().equals(assembly.componentCertificationId())
                && component.candidateHash().equals(assembly.componentCandidateHash())
                && component.certificationManifest()
                        .equals(assembly.componentCertificationManifest())
                && release.assemblyManifest()
                        .equals(assembly.assemblyManifest().orElseThrow())
                && release.inspectionReport()
                        .equals(assembly.inspectionReport().orElseThrow())
                && release.releaseDecisionPointId()
                        .equals(assembly.releaseDecisionPointId().orElseThrow())
                && release.releaseSubjectHash()
                        .equals(assembly.releaseSubjectHash().orElseThrow())
                && release.releaseActionRunId()
                        .equals(assembly.releaseActionRunId().orElseThrow())
                && requirement.consumerContract().equals(release.consumerContract())
                && requirement.hostFixture().equals(release.hostFixture())
                && requirement.policySnapshot().equals(release.policySnapshot())
                && requirement.component().equals(component)
                && manifest.requirement().equals(release.requirement())
                && manifest.consumerContract().equals(release.consumerContract())
                && manifest.hostFixture().equals(release.hostFixture())
                && manifest.policySnapshot().equals(release.policySnapshot())
                && manifest.component().equals(component)
                && inspection.assemblyManifest().equals(release.assemblyManifest())
                && inspection.consumerContract().equals(release.consumerContract())
                && inspection.hostFixture().equals(release.hostFixture())
                && inspection.policySnapshot().equals(release.policySnapshot())
                && inspection.component().equals(component)
                && ReferenceAssemblyInspectionReport.PASSED.equals(inspection.status())
                && ProductLineId.REFERENCE_ASSEMBLY.equals(subject.productLineId())
                && subject.referenceAssemblyId().equals(assembly.referenceAssemblyId())
                && subject.assemblyManifest().equals(release.assemblyManifest())
                && subject.inspectionReport().equals(release.inspectionReport())
                && subject.componentCertificationId().equals(component.certificationId())
                && subject.componentCandidateHash().equals(component.candidateHash())
                && subject.componentCertificationManifest()
                        .equals(component.certificationManifest())
                && subject.policySnapshot().equals(release.policySnapshot())
                && subject.inspectedAssemblyVersion() + 3 == assembly.version();
        if (!exact) {
            throw unavailable();
        }
    }

    private static ReleasedReferenceAssemblyResolutionException unavailable() {
        return new ReleasedReferenceAssemblyResolutionException();
    }
}
