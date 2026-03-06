package org.openphc.cce.emitter.openhim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openphc.cce.emitter.config.OpenHimProperties;
import org.openphc.cce.emitter.openhim.model.OpenHimResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Wraps mediator responses in the {@code application/json+openhim} envelope
 * format expected by OpenHIM Core.
 *
 * <p>OpenHIM Core requires mediator responses to include the mediator URN,
 * a status string, the actual HTTP response details, and an array of
 * orchestration entries documenting downstream service calls.
 */
@Component
public class OpenHimResponseWrapper {

    private static final Logger log = LoggerFactory.getLogger(OpenHimResponseWrapper.class);

    private final OpenHimProperties openHimProperties;
    private final ObjectMapper objectMapper;

    public OpenHimResponseWrapper(OpenHimProperties openHimProperties, ObjectMapper objectMapper) {
        this.openHimProperties = openHimProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Wraps the given response body and status into an OpenHIM-compatible envelope.
     *
     * @param body            the response body as a Jackson {@link JsonNode} (will be serialized to JSON string)
     * @param status          the HTTP status code
     * @param orchestrations  orchestration entries documenting downstream calls
     * @return the fully populated {@link OpenHimResponse} envelope
     */
    public OpenHimResponse wrap(JsonNode body, HttpStatus status,
                                 List<OpenHimResponse.Orchestration> orchestrations) {
        String bodyString = serializeBody(body);
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String openHimStatus = resolveStatus(status);

        OpenHimResponse response = OpenHimResponse.builder()
                .mediatorUrn(openHimProperties.mediator().urn())
                .status(openHimStatus)
                .response(OpenHimResponse.Response.builder()
                        .status(status.value())
                        .headers(Map.of("Content-Type", "application/json"))
                        .body(bodyString)
                        .timestamp(timestamp)
                        .build())
                .orchestrations(orchestrations != null ? orchestrations : List.of())
                .build();

        log.debug("Wrapped OpenHIM response: status={}, httpStatus={}", openHimStatus, status.value());

        return response;
    }

    /**
     * Resolves the OpenHIM status string from the HTTP status code.
     *
     * @param status the HTTP status
     * @return {@code "Successful"} for 2xx, {@code "Failed"} otherwise
     */
    private String resolveStatus(HttpStatus status) {
        return status.is2xxSuccessful() ? "Successful" : "Failed";
    }

    /**
     * Serializes the {@link JsonNode} body to a JSON string.
     *
     * @param body the response body node, may be {@code null}
     * @return the JSON string representation, or {@code null} if body is null
     */
    private String serializeBody(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize response body: {}", e.getMessage());
            return body.toString();
        }
    }
}
