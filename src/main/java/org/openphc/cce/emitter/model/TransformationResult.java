package org.openphc.cce.emitter.model;

/**
 * Captures the outcome of transforming a single FHIR resource into a CloudEvent
 * and forwarding it to the Collector.
 *
 * @param event           the CloudEvent that was forwarded (may be null on failure before envelope construction)
 * @param success         whether the forwarding was successful
 * @param collectorStatus the Collector's reported status ({@code "accepted"}, {@code "duplicate"}, or {@code null} on failure)
 * @param errorMessage    error description if forwarding failed (null on success)
 */
public record TransformationResult(
        CloudEventDto event,
        boolean success,
        String collectorStatus,
        String errorMessage
) {

    /**
     * Creates a successful result from a Collector response.
     *
     * @param event    the forwarded CloudEvent
     * @param response the Collector's response
     * @return a success TransformationResult
     */
    public static TransformationResult from(CloudEventDto event, CollectorResponse response) {
        String status = response.data() != null ? response.data().status() : "accepted";
        return new TransformationResult(event, true, status, null);
    }

    /**
     * Creates a failure result.
     *
     * @param event        the CloudEvent that failed to forward (may be null)
     * @param errorMessage description of the failure
     * @return a failure TransformationResult
     */
    public static TransformationResult failure(CloudEventDto event, String errorMessage) {
        return new TransformationResult(event, false, null, errorMessage);
    }
}
