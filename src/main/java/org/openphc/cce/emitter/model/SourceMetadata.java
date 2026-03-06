package org.openphc.cce.emitter.model;

import java.time.OffsetDateTime;

/**
 * Structured metadata extracted from inbound request headers and context.
 *
 * <p>Populated during adaptor selection and carried through the pipeline
 * to inform CloudEvent envelope construction.
 *
 * @param sourceIdentifier    resolved source system key (e.g., {@code "ebuzima"})
 * @param facilityId          facility FOSA ID from {@code X-Facility-Id} header (nullable)
 * @param sourceEventId       source system event ID from {@code X-Source-Event-Id} header (nullable)
 * @param correlationId       trace correlation ID from {@code X-Correlation-Id} header or adaptor-generated (nullable on input)
 * @param eventTime           when the adaptor received the event (UTC)
 * @param sourcePath          request URI path (e.g., {@code "/inbound"})
 */
public record SourceMetadata(
        String sourceIdentifier,
        String facilityId,
        String sourceEventId,
        String correlationId,
        OffsetDateTime eventTime,
        String sourcePath
) {}
