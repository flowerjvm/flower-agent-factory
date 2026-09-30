package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import java.util.List;

/**
 * Registered intake executor's atomic boundary. The adapter must revalidate current canonical
 * certification under its row lock, stage only exact supplied immutable artifacts, and create one
 * pristine session plus immutable request receipt. Exact restart re-observation never resets a
 * progressed session. It does not dispatch a Flow, Worker, approval, certification or release.
 */
@FunctionalInterface
public interface ReferenceAssemblyIntakeTransaction {
    BuildSession accept(BuildSession pristineSession, CertifiedAgentComponentRef component, List<Artifact> stagedArtifacts);
}
