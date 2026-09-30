package io.github.flowerjvm.factory.application.incidentapplication;

/**
 * Concrete incident-application production equipment. All methods run on the bounded host control
 * lane, never a Flower tick. Implementations own immutable staging and independently verify all
 * referenced bytes. They may not issue a Certification, record a human Decision, or release.
 */
public interface IncidentApplicationProductionTool {
    IncidentApplicationBuildWorkOrder plan(IncidentApplicationOrder order);
    IncidentApplicationPreparedProduct produce(IncidentApplicationBuildWorkOrder workOrder);
    IncidentApplicationWholeVerification verify(IncidentApplicationBuildWorkOrder workOrder,
            IncidentApplicationPreparedProduct product);
    /** Re-read the complete immutable graph without invoking the product, before release/readback. */
    void validate(IncidentApplicationBuildWorkOrder workOrder, IncidentApplicationPreparedProduct product,
            IncidentApplicationWholeVerification verification);
}
