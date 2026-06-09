package org.openphc.cce.emitter.exception;

public class FacilityFilterRejectedException extends RuntimeException {

    private final String facilityId;
    private final String sourceKey;
    private final String reason;

    public FacilityFilterRejectedException(String facilityId, String sourceKey, String reason) {
        super("Event rejected by facility filter: facilityId='" + facilityId
                + "' source='" + sourceKey + "' reason=" + reason);
        this.facilityId = facilityId;
        this.sourceKey = sourceKey;
        this.reason = reason;
    }

    public String getFacilityId() { return facilityId; }
    public String getSourceKey() { return sourceKey; }
    public String getReason() { return reason; }
}
