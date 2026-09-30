package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Trusted exact consumer-contract entries for this manifest-graph ProductLine only. */
public final class ReferenceAssemblyAdmissionPolicies {
    private final Map<CertificationArtifactLock, ReferenceAssemblyAdmissionPolicy> entries;

    public ReferenceAssemblyAdmissionPolicies(List<ReferenceAssemblyAdmissionPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        if (policies.isEmpty() || policies.size() > 16) {
            throw new IllegalArgumentException("Reference Assembly requires a bounded nonempty policy catalog");
        }
        var indexed = new LinkedHashMap<CertificationArtifactLock, ReferenceAssemblyAdmissionPolicy>();
        for (var policy : policies) {
            Objects.requireNonNull(policy, "policy");
            if (indexed.putIfAbsent(policy.approvedConsumerContract(), policy) != null) {
                throw new IllegalArgumentException("duplicate exact Reference Assembly consumer contract");
            }
        }
        this.entries = Map.copyOf(indexed);
    }

    public static ReferenceAssemblyAdmissionPolicies of(ReferenceAssemblyAdmissionPolicy policy) {
        return new ReferenceAssemblyAdmissionPolicies(List.of(policy));
    }

    /** Both immutable reference and hash must match. No latest-version or legacy fallback. */
    public Optional<ReferenceAssemblyAdmissionPolicy> find(CertificationArtifactLock consumerContract) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(consumerContract, "consumerContract")));
    }

    ReferenceAssemblyAdmissionPolicy requireApproved(ReferenceAssemblyRequirement requirement) {
        var selected = find(requirement.consumerContract()).orElseThrow(() -> new ReferenceAssemblyException(
                ReferenceAssemblyException.ADMISSION_POLICY_MISMATCH,
                "Reference Assembly consumer contract has no exact trusted entry"));
        if (!requirement.hostFixture().equals(selected.approvedHostFixture())
                || !requirement.policySnapshot().equals(selected.approvedPolicySnapshot())) {
            throw new ReferenceAssemblyException(ReferenceAssemblyException.ADMISSION_POLICY_MISMATCH,
                    "Reference Assembly requirement mixes different trusted catalog entries");
        }
        return selected;
    }
}
