package org.openphc.cce.emitter.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CloudEvents v1.0 envelope DTO for events forwarded to the CCE Collector.
 *
 * <p>Field names follow the CloudEvents spec — all lowercase, no separators.
 * The {@code @JsonPropertyOrder} ensures consistent serialization order
 * matching the CloudEvents specification layout.
 *
 * @see <a href="https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md">CloudEvents Spec v1.0</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "specversion", "id", "source", "type", "subject", "time",
        "datacontenttype", "facilityid", "sourceeventid", "correlationid",
        "protocolinstanceid", "protocoldefinitionid", "actionid", "data"
})
public class CloudEventDto {

    // --- Required fields ---

    /** CloudEvents specification version — always {@code "1.0"}. */
    private String specversion;

    /** Unique event identifier (UUID). Deduplication key together with {@code source}. */
    private String id;

    /** Source system identifier (e.g., {@code "ebuzima"}). Deduplication key together with {@code id}. */
    private String source;

    /**
     * Event type — set to the FHIR {@code resourceType} value as-is
     * (e.g., {@code "Encounter"}, {@code "Observation"}).
     * This is the only field validated by the Collector.
     */
    private String type;

    // --- Recommended fields ---

    /** Patient UPID — used as Kafka partition key and Compliance routing key. */
    private String subject;

    /** Event timestamp in ISO-8601 UTC format. */
    private String time;

    /** Content type of the {@code data} field — always {@code "application/fhir+json"} for FHIR payloads. */
    private String datacontenttype;

    // --- Extension fields (recommended) ---

    /** Healthcare facility FOSA ID from {@code X-Facility-Id} header. */
    private String facilityid;

    /** Source system's original event ID from {@code X-Source-Event-Id} header. */
    private String sourceeventid;

    /** Trace correlation ID. Uses inbound {@code X-Correlation-Id} or adaptor-generated. */
    private String correlationid;

    // --- Extension fields (optional — Compliance Service resolves) ---

    /** Protocol instance ID — usually {@code null}, resolved by Compliance Service. */
    private String protocolinstanceid;

    /** Protocol definition ID — usually {@code null}, resolved by Compliance Service. */
    private String protocoldefinitionid;

    /** Action ID — usually {@code null}, resolved by Compliance Service. */
    private String actionid;

    // --- Data payload ---

    /** The FHIR R4 resource JSON as a parsed object structure. */
    private Map<String, Object> data;
}
