package org.openphc.cce.emitter.model;

import java.util.List;

/**
 * Response body model for successfully processed inbound events.
 *
 * <p>Serialized as the {@code body} field inside the OpenHIM response envelope.
 *
 * @param status          processing outcome ({@code "processed"})
 * @param eventsForwarded number of events forwarded to the Collector
 * @param events          per-event details
 */
public record ProcessedEventsResponse(
        String status,
        int eventsForwarded,
        List<EventDetail> events
) {

    /**
     * Per-event forwarding detail.
     *
     * @param eventId         the CloudEvent ID
     * @param type            the FHIR resource type (CloudEvent type)
     * @param subject         the patient UPID
     * @param collectorStatus the Collector's reported status ({@code "accepted"} or {@code "duplicate"})
     */
    public record EventDetail(
            String eventId,
            String type,
            String subject,
            String collectorStatus
    ) {}

    /**
     * Builds a response from a list of transformation results.
     */
    public static ProcessedEventsResponse from(List<TransformationResult> results) {
        List<EventDetail> details = results.stream()
                .map(r -> new EventDetail(
                        r.event().getId(),
                        r.event().getType(),
                        r.event().getSubject(),
                        r.collectorStatus()))
                .toList();
        return new ProcessedEventsResponse("processed", results.size(), details);
    }
}
