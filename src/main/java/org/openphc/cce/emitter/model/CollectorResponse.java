package org.openphc.cce.emitter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO to parse responses from the CCE Collector API.
 *
 * <p>The Collector returns different shapes for success/duplicate vs. error responses:
 * <ul>
 *   <li>Success (202) / Duplicate (200): {@code {"data": {"eventId": "...", "status": "accepted|duplicate", ...}}}</li>
 *   <li>Error (400/422): {@code {"error": {"code": "...", "message": "..."}}}</li>
 * </ul>
 *
 * @param data  populated on success/duplicate responses
 * @param error populated on error responses
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollectorResponse(
        DataPayload data,
        ErrorPayload error
) {

    /**
     * Success/duplicate response payload from the Collector.
     *
     * @param eventId       the Collector-assigned event ID
     * @param status        {@code "accepted"} or {@code "duplicate"}
     * @param correlationId the trace correlation ID
     * @param timestamp     when the Collector processed the event
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataPayload(
            String eventId,
            String status,
            String correlationId,
            String timestamp
    ) {}

    /**
     * Error response payload from the Collector.
     *
     * @param code    error code (e.g., {@code "VALIDATION_ERROR"})
     * @param message human-readable error description
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorPayload(
            String code,
            String message
    ) {}
}
