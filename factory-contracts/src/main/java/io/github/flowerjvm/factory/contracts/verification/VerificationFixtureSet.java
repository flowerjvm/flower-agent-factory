package io.github.flowerjvm.factory.contracts.verification;

import java.util.List;
import java.util.Objects;

/** Host-owned immutable negative fixture set executed before candidate review eligibility. */
public record VerificationFixtureSet(String schemaVersion, List<VerificationFixture> fixtures) {
    public static final String SCHEMA_VERSION = "factory.verification-fixture-set.v1";
    public VerificationFixtureSet {
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("unsupported fixture schema");
        fixtures = List.copyOf(Objects.requireNonNull(fixtures, "fixtures"));
        if (fixtures.isEmpty()) throw new IllegalArgumentException("fixture set must not be empty");
        var groups = fixtures.stream().collect(java.util.stream.Collectors.groupingBy(VerificationFixture::fixtureId));
        if (groups.values().stream().anyMatch(group ->
                group.stream().map(VerificationFixture::expectedRuleId).distinct().count() != 1)) {
            throw new IllegalArgumentException("each fixture needs one exact expected rule");
        }
    }
}
