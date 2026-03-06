package org.openphc.cce.emitter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

import org.openphc.cce.emitter.config.CollectorProperties;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.model.TransformationResult;
import org.openphc.cce.emitter.openhim.OpenHimResponseWrapper;
import org.openphc.cce.emitter.openhim.model.OpenHimResponse;
import org.openphc.cce.emitter.adaptor.SourceAdaptorService;
import org.openphc.cce.emitter.service.CollectorForwardingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main inbound endpoint for the Emitter Adaptor mediator.
 *
 * <p>Receives FHIR R4 resource payloads from OpenHIM Core (secondary route),
 * processes them through the event pipeline, forwards resulting CloudEvents
 * to the CCE Collector, and returns an OpenHIM-compatible response envelope.
 *
 * <h3>Processing flow</h3>
 * <ol>
 *   <li>{@code InboundRequest.from()} — normalize request</li>
 *   <li>{@code SourceAdaptorService.adapt()} — resolve source, transform FHIR → CloudEvent</li>
 *   <li>{@code CollectorForwardingService.forward()} — POST to Collector with retry</li>
 *   <li>{@code OpenHimResponseWrapper.wrap()} — wrap in {@code application/json+openhim} envelope</li>
 * </ol>
 *
 * <p>When no source matches or the payload is not a FHIR resource, the request
 * is silently ignored with 200 OK — by design, since this mediator sits on a
 * secondary route and receives all traffic on the OpenHIM channel.
 */
@RestController
@RequestMapping("/inbound")
public class InboundEventController {

    private static final Logger log = LoggerFactory.getLogger(InboundEventController.class);
    private static final MediaType OPENHIM_MEDIA_TYPE = MediaType.parseMediaType("application/json+openhim");

    private final SourceAdaptorService sourceAdaptorService;
    private final CollectorForwardingService collectorForwardingService;
    private final OpenHimResponseWrapper responseWrapper;
    private final CollectorProperties collectorProperties;
    private final ObjectMapper objectMapper;

    public InboundEventController(
            SourceAdaptorService sourceAdaptorService,
            CollectorForwardingService collectorForwardingService,
            OpenHimResponseWrapper responseWrapper,
            CollectorProperties collectorProperties,
            ObjectMapper objectMapper) {
        this.sourceAdaptorService = sourceAdaptorService;
        this.collectorForwardingService = collectorForwardingService;
        this.responseWrapper = responseWrapper;
        this.collectorProperties = collectorProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Receives a FHIR R4 resource from OpenHIM Core and processes it through
     * the full pipeline.
     *
     * <p>Returns {@code application/json+openhim} content type which requires
     * manual JSON serialization since Spring's Jackson converter does not
     * recognize this media type natively.
     *
     * @param body    raw request body
     * @param headers HTTP request headers
     * @param request the servlet request (for path extraction)
     * @return OpenHIM envelope response with 202 on success, 200 on silent ignore
     */
    @PostMapping
    public ResponseEntity<String> handleInbound(
            @RequestBody(required = false) String body,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {

        log.debug("Inbound request received: path={}, contentLength={}",
                request.getRequestURI(), body != null ? body.length() : 0);

        // 1. Normalize the request
        InboundRequest inboundRequest = InboundRequest.from(body, headers, request);

        // 2. Source resolution + FHIR → CloudEvent transformation
        List<CloudEventDto> events = sourceAdaptorService.adapt(inboundRequest);

        // 3. No events produced → silently ignore (200 OK)
        if (events.isEmpty()) {
            log.debug("No events produced — returning 200 OK (silent ignore)");
            JsonNode responseBody = objectMapper.createObjectNode()
                    .put("status", "ignored")
                    .put("message", "No matching source or non-processable payload");
            OpenHimResponse envelope = responseWrapper.wrap(
                    responseBody, HttpStatus.OK, List.of());
            return ResponseEntity.ok()
                    .contentType(OPENHIM_MEDIA_TYPE)
                    .body(toJson(envelope));
        }

        // 4. Forward each CloudEvent to the Collector and collect results
        List<TransformationResult> results = new ArrayList<>();
        List<OpenHimResponse.Orchestration> orchestrations = new ArrayList<>();

        for (CloudEventDto event : events) {
            String requestTimestamp = nowUtc();
            CollectorResponse collectorResponse = collectorForwardingService.forward(event);
            results.add(TransformationResult.from(event, collectorResponse));
            orchestrations.add(buildOrchestration(event, collectorResponse, requestTimestamp));
        }

        // 5. Build success response
        JsonNode responseBody = buildSuccessBody(results);
        OpenHimResponse envelope = responseWrapper.wrap(
                responseBody, HttpStatus.ACCEPTED, orchestrations);

        log.info("Inbound request processed: {} event(s) forwarded", results.size());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .contentType(OPENHIM_MEDIA_TYPE)
                .body(toJson(envelope));
    }

    // ==================== Helper methods ====================

    private OpenHimResponse.Orchestration buildOrchestration(
            CloudEventDto event, CollectorResponse collectorResponse, String requestTimestamp) {

        String responseTimestamp = nowUtc();
        String collectorStatus = collectorResponse.data() != null
                ? collectorResponse.data().status() : "accepted";

        return OpenHimResponse.Orchestration.builder()
                .name("Forward to CCE Collector")
                .request(OpenHimResponse.Orchestration.Request.builder()
                        .method("POST")
                        .path(collectorProperties.eventsPath())
                        .body(toJson(event))
                        .timestamp(requestTimestamp)
                        .headers(Map.of("Content-Type", "application/json"))
                        .build())
                .response(OpenHimResponse.Orchestration.OrchestrationResponse.builder()
                        .status(collectorStatus.equals("duplicate") ? 200 : 202)
                        .body(toJson(collectorResponse))
                        .timestamp(responseTimestamp)
                        .build())
                .build();
    }

    private JsonNode buildSuccessBody(List<TransformationResult> results) {
        var root = objectMapper.createObjectNode();
        root.put("status", "processed");
        root.put("eventsForwarded", results.size());

        var eventsArray = root.putArray("events");
        for (TransformationResult result : results) {
            var entry = objectMapper.createObjectNode();
            entry.put("eventId", result.event().getId());
            entry.put("type", result.event().getType());
            entry.put("subject", result.event().getSubject());
            entry.put("collectorStatus", result.collectorStatus());
            eventsArray.add(entry);
        }

        return root;
    }

    /**
     * Serializes any object to JSON, returning {@code "{}"} on failure.
     *
     * <p>Used for OpenHIM response envelopes ({@code application/json+openhim}
     * requires manual serialization since Spring's Jackson converter does not
     * recognise this media type natively) and for orchestration logging payloads.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            log.warn("JSON serialization failed: {}", ex.getMessage());
            return "{}";
        }
    }

    private String nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
