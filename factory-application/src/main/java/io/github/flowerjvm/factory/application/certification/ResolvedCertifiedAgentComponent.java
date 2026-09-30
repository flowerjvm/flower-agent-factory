package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import java.util.Objects;

/** Exact, evidence-bound component snapshot safe to add to a downstream product input lock. */
public record ResolvedCertifiedAgentComponent(
        CertifiedAgentComponentRef reference,
        Certification certification,
        CertificationInputLock inputLock,
        CertificationEvidenceManifest evidence,
        CertifiedAgentComponentManifest manifest,
        AgentPackCompatibilityDescriptor compatibilityDescriptor,
        CandidateVersion candidate,
        VerificationRun verificationRun) {

    public ResolvedCertifiedAgentComponent {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(certification, "certification");
        Objects.requireNonNull(inputLock, "inputLock");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(compatibilityDescriptor, "compatibilityDescriptor");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(verificationRun, "verificationRun");
    }
}
