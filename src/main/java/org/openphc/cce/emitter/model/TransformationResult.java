package org.openphc.cce.emitter.model;

/**
 * Captures the outcome of forwarding a single CloudEvent to the Collector.
 *
 * @param event           the CloudEvent that was forwarded
 * @param collectorStatus the Collector's reported status ({@code "accepted"} or {@code "duplicate"})
 */
public record TransformationResult(
        CloudEventDto event,
        String collectorStatus
) {

    /**
     * Creates a result from a Collector response.
     *
     * @param event    the forwarded CloudEvent
     * @param response the Collector's response
     * @return a TransformationResult with the resolved status
     */
    public static TransformationResult from(CloudEventDto event, CollectorResponse response) {
        String status = response.data() != null ? response.data().status() : "accepted";
        return new TransformationResult(event, status);
    }
}
