package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bounded restart scanner for the gap before a certification continuation's first checkpoint.
 *
 * <p>The database ledger is the launch authority. Repeated scans deliberately submit the same
 * Flow identity through {@link FactoryContinuationFlowLauncher}, whose {@code IGNORE} duplicate
 * policy makes an already queued, active, or recovered continuation a no-op.
 */
public final class FactoryCertificationContinuationRecovery {
    public static final int DEFAULT_SCAN_LIMIT = 64;
    private static final String IDENTITY_SCHEMA = "factory-certification-continuation.v1";

    private final BuildSessionRepository sessions;
    private final FactoryContinuationFlowLauncher launcher;
    private final Clock clock;
    private final int scanLimit;

    public FactoryCertificationContinuationRecovery(
            BuildSessionRepository sessions,
            FactoryContinuationFlowLauncher launcher,
            Clock clock) {
        this(sessions, launcher, clock, DEFAULT_SCAN_LIMIT);
    }

    public FactoryCertificationContinuationRecovery(
            BuildSessionRepository sessions,
            FactoryContinuationFlowLauncher launcher,
            Clock clock,
            int scanLimit) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (scanLimit < 1 || scanLimit > 1_000) {
            throw new IllegalArgumentException("scanLimit must be between 1 and 1000");
        }
        this.scanLimit = scanLimit;
    }

    /** Launches one bounded oldest-first batch and returns the number submitted. */
    public int recoverBatch() {
        Instant updatedBefore = clock.instant();
        int submitted = 0;
        for (BuildSession session :
                sessions.findCertificationContinuationCandidates(updatedBefore, scanLimit)) {
            launcher.launch(
                    session.tenantId(),
                    session.buildSessionId(),
                    flowRunId(session),
                    traceId(session));
            submitted++;
        }
        return submitted;
    }

    static String flowRunId(BuildSession session) {
        return "certification-recovery-run-" + identityHash(session);
    }

    static String traceId(BuildSession session) {
        return "certification-recovery-trace-" + identityHash(session);
    }

    private static String identityHash(BuildSession session) {
        Objects.requireNonNull(session, "session");
        String canonical = IDENTITY_SCHEMA
                + '\n'
                + session.tenantId().value()
                + '\n'
                + session.buildSessionId().value();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
