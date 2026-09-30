package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import java.util.Objects;

/** Immutable, per-order production inputs. A blueprint never changes these authority locks. */
public record AgentPackProductionPlan(
        String schemaVersion,
        TenantId tenantId,
        BuildSessionId buildSessionId,
        String recipeId,
        CertificationArtifactLock requirements,
        String skillId,
        String skillVersion,
        CertificationArtifactLock skill,
        CertificationArtifactLock dependencyLock,
        CertificationArtifactLock toolchainLock,
        CertificationArtifactLock productContract,
        CertificationArtifactLock apiSignatureIndex,
        CertificationArtifactLock requirementTestMatrix,
        String gateProfile,
        CertificationArtifactLock policySnapshot,
        String workspaceRef,
        WorkerBinding manager,
        WorkerBinding coding) {
    public static final String SCHEMA_VERSION = "factory.agent-pack-production-plan.v1";

    public AgentPackProductionPlan {
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("unsupported production plan");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        recipeId = text(recipeId, "recipeId");
        Objects.requireNonNull(requirements, "requirements");
        skillId = text(skillId, "skillId");
        skillVersion = text(skillVersion, "skillVersion");
        if (skillVersion.length() > 64) throw new IllegalArgumentException("skillVersion is too long");
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(dependencyLock, "dependencyLock");
        Objects.requireNonNull(toolchainLock, "toolchainLock");
        Objects.requireNonNull(productContract, "productContract");
        Objects.requireNonNull(apiSignatureIndex, "apiSignatureIndex");
        Objects.requireNonNull(requirementTestMatrix, "requirementTestMatrix");
        gateProfile = text(gateProfile, "gateProfile");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        workspaceRef = text(workspaceRef, "workspaceRef");
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(coding, "coding");
    }

    public record WorkerBinding(String bindingId, String adapterVersion, WorkerCapabilities capabilities) {
        public WorkerBinding {
            bindingId = text(bindingId, "bindingId");
            adapterVersion = text(adapterVersion, "adapterVersion");
            Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
