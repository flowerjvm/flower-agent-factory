package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyReleaseDeadlinesTest {
    @Test
    void wholeSecondsAndMillisecondsKeepTheirHistoricalTransportBytes() {
        for (String value : List.of("2026-09-07T13:00:00Z", "2026-09-07T13:00:00.123Z")) {
            Instant business = Instant.parse(value);
            assertEquals(value, ReferenceAssemblyReleaseDeadlines.actionDueAt(business).toString());
        }
    }

    @Test
    void fractionalMicrosecondsOnlyShortenTransportAndLeaveTheExactBusinessInstantUnchanged() {
        for (String fraction : List.of("123001", "123456", "123999")) {
            Instant business = Instant.parse("2026-09-07T13:00:00." + fraction + "Z");
            Instant action = ReferenceAssemblyReleaseDeadlines.actionDueAt(business);
            assertEquals(Instant.parse("2026-09-07T13:00:00.123Z"), action);
            assertFalse(action.isAfter(business));
            assertEquals("2026-09-07T13:00:00." + fraction + "Z", business.toString());
        }
    }

    @Test
    void noncanonicalBusinessPrecisionIsRejectedInsteadOfSilentlyChangingTheApprovalLock() {
        assertThrows(IllegalArgumentException.class, () -> ReferenceAssemblyReleaseDeadlines.actionDueAt(
                Instant.parse("2026-09-07T13:00:00.123456789Z")));
        assertThrows(NullPointerException.class, () -> ReferenceAssemblyReleaseDeadlines.actionDueAt(null));
    }
}
