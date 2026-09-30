package io.github.flowerjvm.factory.infrastructure.verification;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import java.util.List;

/** Explicit command sets: adding a product gate must not change historical PR4 evidence. */
final class VerificationProfiles {
    private static final List<VerificationCommand> TECHNICAL = List.of(
            VerificationCommand.MAVEN_VERIFY,
            VerificationCommand.FLOWER_CHECK,
            VerificationCommand.MAVEN_DEPENDENCY_TREE);

    private VerificationProfiles() {}

    static boolean supported(String profile) {
        return ActionBackedVerificationRunLauncher.GATE_PROFILE.equals(profile) || maintenance(profile);
    }

    static boolean maintenance(String profile) {
        return MaintenanceInvestigationProductContract.GATE_PROFILE.equals(profile);
    }

    static List<VerificationCommand> technicalCommands() {
        return TECHNICAL;
    }

    static List<VerificationCommand> commands(String profile) {
        if (maintenance(profile)) {
            return List.of(VerificationCommand.MAVEN_VERIFY, VerificationCommand.FLOWER_CHECK,
                    VerificationCommand.MAVEN_DEPENDENCY_TREE, VerificationCommand.MAINTENANCE_ACCEPTANCE);
        }
        if (!supported(profile)) throw new IllegalArgumentException("unsupported verification profile");
        return TECHNICAL;
    }
}
