package org.openphc.cce.emitter.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.openhim.model.OpenHimResponse;
import org.openphc.cce.emitter.service.InboundEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Main inbound endpoint for the Emitter Adaptor mediator.
 *
 * <p>Receives FHIR R4 resource payloads from OpenHIM Core (secondary route)
 * and delegates all processing to {@link InboundEventService}.
 *
 * <p>This controller handles only HTTP concerns: request binding,
 * content-type negotiation, and JSON serialization for the
 * {@code application/json+openhim} response format.
 */
@RestController
@RequestMapping("/inbound")
public class InboundEventController {

    private static final Logger log = LoggerFactory.getLogger(InboundEventController.class);
    private static final MediaType OPENHIM_MEDIA_TYPE = MediaType.parseMediaType("application/json+openhim");

    private final InboundEventService inboundEventService;
    private final ObjectMapper objectMapper;

    public InboundEventController(InboundEventService inboundEventService, ObjectMapper objectMapper) {
        this.inboundEventService = inboundEventService;
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
     * @throws JsonProcessingException if JSON serialization fails
     */
    @PostMapping
    public ResponseEntity<String> handleInbound(
            @RequestBody(required = false) String body,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) throws JsonProcessingException {

        log.debug("Inbound request received: path={}, contentLength={}",
                request.getRequestURI(), body != null ? body.length() : 0);

        InboundRequest inboundRequest = InboundRequest.from(body, headers, request);
        OpenHimResponse envelope = inboundEventService.process(inboundRequest);

        return ResponseEntity.status(envelope.getResponse().getStatus())
                .contentType(OPENHIM_MEDIA_TYPE)
                .body(objectMapper.writeValueAsString(envelope));
    }

}
        