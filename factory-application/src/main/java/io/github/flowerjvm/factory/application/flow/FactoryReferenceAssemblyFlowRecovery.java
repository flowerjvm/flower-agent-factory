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

/** Bounded restart scanner for Reference Assembly primary-flow launch/checkpoint gaps. */
public final class FactoryReferenceAssemblyFlowRecovery {
    public static final int DEFAULT_SCAN_LIMIT = 64;
    private static final String IDENTITY_SCHEMA =
            "factory-reference-assembly-primary-flow.v1";

    private final BuildSessionRepository sessions;
    private final FactoryProductLineFlowLauncher launcher;
    private final Clock clock;
    private final int scanLimit;

    public FactoryReferenceAssemblyFlowRecovery(
            BuildSessionRepository sessions,
            FactoryProductLineFlowLauncher launcher,
            Clock clock) {
        this(sessions, launcher, clock, DEFAULT_SCAN_LIMIT);
    }

    public FactoryReferenceAssemblyFlowRecovery(
            BuildSessionRepository sessions,
            FactoryProductLineFlowLauncher launcher,
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

    /** Submits one oldest-first batch using the stable primary Flow identity. */
    public int recoverBatch() {
        Instant updatedBefore = clock.instant();
        int submitted = 0;
        for (BuildSession session :
                sessions.findReferenceAssemblyFlowCandidates(updatedBefore, scanLimit)) {
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
        return "reference-assembly-recovery-run-" + identityHash(session);
    }

    static String traceId(BuildSession session) {
        return "reference-assembly-recovery-trace-" + identityHash(session);
    }

    private static String identityHash(BuildSession session) {
        Objects.requireNonNull(session, "session");
        String canonical = IDENTITY_SCHEMA
                + '\n'
                + session.tenantId().value()
                + '\n'
                + session.buildSessionId().value();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
