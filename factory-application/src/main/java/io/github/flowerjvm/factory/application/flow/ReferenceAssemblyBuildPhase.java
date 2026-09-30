package io.github.flowerjvm.factory.application.flow;

/** Stable six-step production shape of the concrete Reference Assembly product line. */
public enum ReferenceAssemblyBuildPhase {
    ACCEPT_REFERENCE_REQUIREMENTS,
    RESOLVE_CERTIFIED_COMPONENT,
    ASSEMBLE_REFERENCE_MANIFEST,
    INSPECT_REFERENCE_ASSEMBLY,
    WAIT_REFERENCE_RELEASE_REVIEW,
    RELEASE_REFERENCE_ASSEMBLY
}
