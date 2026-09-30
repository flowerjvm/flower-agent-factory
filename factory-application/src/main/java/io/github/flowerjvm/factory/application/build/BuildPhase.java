package io.github.flowerjvm.factory.application.build;

/** The six phases explicitly scoped for the first executable Factory slice. */
public enum BuildPhase {
    ACCEPT_REQUIREMENTS,
    DESIGN_CANDIDATE,
    GENERATE_CANDIDATE,
    VERIFY_CANDIDATE,
    HUMAN_REVIEW,
    MARK_CANDIDATE_READY
}
