package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;

/** PR3 transaction boundary closing the Decision insert / DecisionPoint terminal CAS crash window. */
public interface DecisionRecordingTransaction {
    DecisionRecordingDisposition record(
            BuildSession expectedBuildSession,
            DecisionPoint expectedOpen,
            Decision decision,
            DecisionPoint decided);
}
