package io.github.flowerjvm.factory.infrastructure.persistence;

import org.junit.jupiter.api.Test;

class JdbcReferenceAssemblyReleaseConcurrencyTest {

    @Test
    void releaseVersusBuildSessionCancellationHasOneSafeJdbcWinner() throws Exception {
        JdbcReferenceAssemblyReleaseConcurrencyAssertions.assertBuildSessionCancellationRace(
                JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                        "race-h2-build-session"));
    }

    @Test
    void releaseVersusActionCancellationHasOneSafeJdbcWinner() throws Exception {
        JdbcReferenceAssemblyReleaseConcurrencyAssertions.assertActionCancellationRace(
                JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                        "race-h2-action"));
    }

    @Test
    void releaseVersusCertificationRevocationPreservesSafeJdbcLedgers() throws Exception {
        JdbcReferenceAssemblyReleaseConcurrencyAssertions.assertCertificationRevocationRace(
                JdbcReferenceAssemblyReleaseTransactionTest.Fixture.create(
                        "race-h2-certification"));
    }
}
