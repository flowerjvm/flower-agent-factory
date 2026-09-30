package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;

/** Strict canonical JSON boundary for the concrete Reference Assembly artifacts. */
public interface ReferenceAssemblyArtifactCodec {
    String MEDIA_TYPE = "application/json";

    byte[] writeRequirement(ReferenceAssemblyRequirement value);

    ReferenceAssemblyRequirement readRequirement(byte[] content);

    byte[] writeConsumerContract(ReferenceAssemblyConsumerContract value);

    ReferenceAssemblyConsumerContract readConsumerContract(byte[] content);

    byte[] writeManifest(ReferenceAssemblyManifest value);

    ReferenceAssemblyManifest readManifest(byte[] content);

    byte[] writeInspectionReport(ReferenceAssemblyInspectionReport value);

    ReferenceAssemblyInspectionReport readInspectionReport(byte[] content);

    byte[] writeReleaseSubject(ReferenceAssemblyReleaseSubject value);

    ReferenceAssemblyReleaseSubject readReleaseSubject(byte[] content);

    byte[] writeReleaseManifest(ReferenceAssemblyReleaseManifest value);

    ReferenceAssemblyReleaseManifest readReleaseManifest(byte[] content);
}
