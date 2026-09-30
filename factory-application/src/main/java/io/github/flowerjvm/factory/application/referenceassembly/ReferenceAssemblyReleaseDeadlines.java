package io.github.flowerjvm.factory.application.referenceassembly;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** The exact transport deadline supported by Action Runtime 0.3.3's millisecond JDBC store. */
public final class ReferenceAssemblyReleaseDeadlines {
    private ReferenceAssemblyReleaseDeadlines() {}

    /**
     * Derives the one canonical Action dueAt without changing the microsecond business deadline.
     * Session, review and dispatch-intent locks retain the original instant. Rounding down can
     * end the transport window less than one millisecond earlier, but never extends approval.
     * Owner checks must compare against this exact value, not truncate the observed Action value.
     */
    public static Instant actionDueAt(Instant businessDeadline) {
        Objects.requireNonNull(businessDeadline, "businessDeadline");
        if (!businessDeadline.equals(businessDeadline.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("businessDeadline must use microsecond precision");
        }
        return businessDeadline.truncatedTo(ChronoUnit.MILLIS);
    }
}
