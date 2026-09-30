package io.github.flowerjvm.factory.application.referenceassembly;

import java.util.Objects;

/**
 * Final atomic commit boundary for a governed Reference Assembly release.
 *
 * <p>The implementation must own the database transaction, lock and revalidate the assembly,
 * BuildSession, approved DecisionPoint, current component Certification, ActionRun, and claimed
 * release intent, then use the repository's internal release-only CAS. Artifact staging happens
 * before this boundary and grants no authority by itself.
 */
@FunctionalInterface
public interface ReferenceAssemblyReleaseTransaction {
    ReleaseCommit commit(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expectedActionBound,
            ReferenceAssembly proposedReleased);

    record ReleaseCommit(ReferenceAssembly referenceAssembly, boolean committedNow) {
        public ReleaseCommit {
            Objects.requireNonNull(referenceAssembly, "referenceAssembly");
        }
    }
}
