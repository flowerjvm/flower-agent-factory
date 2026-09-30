package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;

/** Strict canonical JSON boundary for the four stored PR6-A certification artifacts. */
public interface CertificationArtifactCodec {
    String MEDIA_TYPE = "application/json";

    byte[] writeInputLock(CertificationInputLock value);

    CertificationInputLock readInputLock(byte[] content);

    byte[] writeEvidence(CertificationEvidenceManifest value);

    CertificationEvidenceManifest readEvidence(byte[] content);

    byte[] writeComponentManifest(CertifiedAgentComponentManifest value);

    CertifiedAgentComponentManifest readComponentManifest(byte[] content);

    byte[] writeCompatibilityDescriptor(AgentPackCompatibilityDescriptor value);

    AgentPackCompatibilityDescriptor readCompatibilityDescriptor(byte[] content);
}
