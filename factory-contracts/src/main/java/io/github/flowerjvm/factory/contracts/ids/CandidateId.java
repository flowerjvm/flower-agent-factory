package io.github.flowerjvm.factory.contracts.ids;

/** Identity of one immutable candidate snapshot. */
public record CandidateId(String value) {
    public CandidateId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
    }
}
