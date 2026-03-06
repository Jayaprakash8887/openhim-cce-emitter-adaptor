package org.openphc.cce.emitter.adaptor;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.openphc.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.fhir.FhirResourceParser;
import org.openphc.cce.emitter.fhir.PatientIdExtractor;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.model.SourceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Abstract base class for source system adaptors providing shared FHIR parsing
 * and CloudEvent construction logic.
 *
 * <p>Concrete adaptors are instantiated per configured source in
 * {@code cce.emitter.sources.*}. Since eBUZIMA (and similar systems) already
 * send FHIR R4 resources, no payload mapping is required — this base class
 * handles the full transformation pipeline.
 *
 * <p>Header-based routing: returns {@code true} from {@link #canHandle} if the
 * inbound {@code X-OpenHIM-ClientID} header matches the configured
 * {@link EmitterProperties.SourceProperties#clientId()}, or the
 * {@code X-Source-System} header matches the source key.
 *
 * <p>Bundle resources are silently ignored (out of scope for v1.0).
 */
public abstract class AbstractSourceAdaptor implements SourceAdaptor {

    private static final Logger log = LoggerFactory.getLogger(AbstractSourceAdaptor.class);

    private static final String HEADER_OPENHIM_CLIENT_ID = "x-openhim-clientid";
    private static final String HEADER_SOURCE_SYSTEM = "x-source-system";
    private static final String HEADER_FACILITY_ID = "x-facility-id";
    private static final String HEADER_SOURCE_EVENT_ID = "x-source-event-id";
    private static final String HEADER_CORRELATION_ID = "x-correlation-id";

    private final String sourceKey;
    private final String clientId;
    private final FhirResourceParser fhirResourceParser;
    private final PatientIdExtractor patientIdExtractor;
    private final CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder;

    /**
     * @param sourceKey               the source system key (e.g., {@code "ebuzima"})
     * @param clientId                the configured OpenHIM client ID for this source
     * @param fhirResourceParser      FHIR JSON parser
     * @param patientIdExtractor      patient UPID extractor
     * @param cloudEventEnvelopeBuilder CloudEvent envelope builder
     */
    protected AbstractSourceAdaptor(
            String sourceKey,
            String clientId,
            FhirResourceParser fhirResourceParser,
            PatientIdExtractor patientIdExtractor,
            CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder) {
        this.sourceKey = sourceKey;
        this.clientId = clientId;
        this.fhirResourceParser = fhirResourceParser;
        this.patientIdExtractor = patientIdExtractor;
        this.cloudEventEnvelopeBuilder = cloudEventEnvelopeBuilder;
    }

    /**
     * Returns {@code true} if the inbound request matches this source system.
     *
     * <p>Match priority:
     * <ol>
     *   <li>{@code X-OpenHIM-ClientID} header matches configured {@code clientId}</li>
     *   <li>{@code X-Source-System} header matches the source key</li>
     * </ol>
     */
    @Override
    public boolean canHandle(InboundRequest request) {
        // Priority 1: X-OpenHIM-ClientID header
        boolean clientIdMatch = request.getHeader(HEADER_OPENHIM_CLIENT_ID)
                .map(value -> value.equals(clientId))
                .orElse(false);

        if (clientIdMatch) {
            log.debug("Source '{}' matched by X-OpenHIM-ClientID header", sourceKey);
            return true;
        }

        // Priority 2: X-Source-System header
        boolean sourceSystemMatch = request.getHeader(HEADER_SOURCE_SYSTEM)
                .map(value -> value.equalsIgnoreCase(sourceKey))
                .orElse(false);

        if (sourceSystemMatch) {
            log.debug("Source '{}' matched by X-Source-System header", sourceKey);
            return true;
        }

        return false;
    }

    /**
     * Transforms the inbound request into CloudEvents.
     *
     * <p>Parses the FHIR resource, extracts the patient UPID, builds metadata,
     * and constructs a CloudEvent envelope. Bundle resources are silently ignored.
     */
    @Override
    public List<CloudEventDto> adapt(InboundRequest request) {
        String body = request.getBody();

        if (!request.containsFhirResource()) {
            log.debug("Request body does not contain a FHIR resource — skipping");
            return List.of();
        }

        // Parse FHIR resource
        IBaseResource resource = fhirResourceParser.parse(body);

        // Silently ignore Bundle resources (out of scope for v1.0)
        if (resource instanceof Bundle) {
            log.debug("Bundle resource received — silently ignoring (out of scope for v1.0)");
            return List.of();
        }

        String resourceType = resource.fhirType();
        String patientUpid = patientIdExtractor.extract(resource);

        // Build source metadata from headers
        SourceMetadata metadata = buildSourceMetadata(request);
        request.setMetadata(metadata);

        // Build CloudEvent
        CloudEventDto event = cloudEventEnvelopeBuilder.build(body, patientUpid, resourceType, metadata);

        log.info("Adapted {} resource from '{}' → CloudEvent id={}, subject={}",
                resourceType, sourceKey, event.getId(), event.getSubject());

        return List.of(event);
    }

    @Override
    public String getSourceIdentifier() {
        return sourceKey;
    }

    /**
     * Builds {@link SourceMetadata} from inbound request headers.
     *
     * <p>If the {@code X-Correlation-Id} header is absent, a UUID is generated
     * by the adaptor for downstream tracing.
     */
    SourceMetadata buildSourceMetadata(InboundRequest request) {
        String facilityId = request.getHeader(HEADER_FACILITY_ID).orElse(null);
        String sourceEventId = request.getHeader(HEADER_SOURCE_EVENT_ID).orElse(null);
        String correlationId = request.getHeader(HEADER_CORRELATION_ID)
                .orElseGet(() -> UUID.randomUUID().toString());

        return new SourceMetadata(
                sourceKey,
                facilityId,
                sourceEventId,
                correlationId,
                OffsetDateTime.now(ZoneOffset.UTC),
                request.getPath()
        );
    }
}
