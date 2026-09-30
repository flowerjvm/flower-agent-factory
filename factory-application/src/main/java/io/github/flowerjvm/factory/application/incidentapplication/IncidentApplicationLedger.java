package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import java.time.Instant;
import java.util.*;

/** Transactional ledger owned by the concrete line, with no unconditional update or force-release API. */
public interface IncidentApplicationLedger {
    Optional<IncidentApplicationProduct> find(TenantId tenant, BuildSessionId session);
    Optional<IncidentApplicationIntent> intent(String operationId);
    /** Verified immutable renewal including its canonical successful Action owner; absence means original window. */
    default Optional<IncidentApplicationReviewRenewal> reviewRenewal(TenantId tenant, BuildSessionId session) { return Optional.empty(); }
    default void requireRenewal(TenantId tenant, BuildSessionId session, Map<String,Object> input, boolean executing) { throw IncidentApplicationOrder.invalid(); }
    default IncidentApplicationReviewRenewal renewReview(TenantId tenant, BuildSessionId session, Map<String,Object> input, ActionRun owner, Instant now) { throw IncidentApplicationOrder.invalid(); }
    default Instant executionDeadline(IncidentApplicationProduct product) {
        return reviewRenewal(product.order().tenantId(),product.order().buildSessionId())
                .map(IncidentApplicationReviewRenewal::deadlineAt).orElse(product.order().deadlineAt());
    }
    /** Inspected artifact version stays fixed when only release-control state advances. */
    default long inspectedVersion(IncidentApplicationProduct product, long reviewVersion) {
        return reviewRenewal(product.order().tenantId(),product.order().buildSessionId())
                .map(renewal -> { IncidentApplicationActions.require(renewal.reviewedProductVersion()==reviewVersion); return renewal.inspectedVersion(); })
                .orElse(reviewVersion);
    }
    default Optional<IncidentApplicationIntent> stageIntent(TenantId tenant, BuildSessionId session, IncidentApplicationIntent.Stage stage) { throw IncidentApplicationOrder.invalid(); }
    List<IncidentApplicationProduct> active(int limit);
    List<IncidentApplicationIntent> pending(int limit);
    IncidentApplicationProduct accept(IncidentApplicationBuildWorkOrder workOrder, ActionRun owner, Instant now);
    IncidentApplicationIntent prepare(TenantId tenant, BuildSessionId session, IncidentApplicationIntent.Stage stage,
            long expectedVersion, ActionRun owner, Instant now);
    Optional<IncidentApplicationIntent> claim(String operationId, String claimToken, Instant now);
    IncidentApplicationProduct commitBuild(IncidentApplicationIntent claimed, IncidentApplicationPreparedProduct result, Instant now);
    IncidentApplicationProduct commitVerification(IncidentApplicationIntent claimed, IncidentApplicationWholeVerification result, Instant now);
    IncidentApplicationProduct commitRelease(IncidentApplicationIntent claimed, Instant now);
    void complete(IncidentApplicationIntent expected, ActionRun canonicalTerminal, Instant now);
    void fail(IncidentApplicationIntent expected, String stableCode, boolean uncertain, Instant now);
    void cancel(TenantId tenant, BuildSessionId session, Instant now);
    void stop(TenantId tenant, BuildSessionId session, String stableCode, Instant now);
    /** Stale Flow observations must not terminate a newly renewed review. */
    default void stop(TenantId tenant, BuildSessionId session, long expectedVersion, String stableCode, Instant now) { stop(tenant,session,stableCode,now); }
    /** Quick ledger metadata only; full product and component evidence is checked outside ticks by the runner. */
    void requireCurrentComponent(IncidentApplicationOrder order, Instant now);
    /** Denies premature release before duplicate reservation; it must not poison the later approved key. */
    default void requireReleaseApproval(TenantId tenant, BuildSessionId session) { throw IncidentApplicationOrder.invalid(); }
    default void requirePriorStages(TenantId tenant, BuildSessionId session, IncidentApplicationIntent.Stage stage) { if(stage != IncidentApplicationIntent.Stage.BUILD) throw IncidentApplicationOrder.invalid(); }
}
