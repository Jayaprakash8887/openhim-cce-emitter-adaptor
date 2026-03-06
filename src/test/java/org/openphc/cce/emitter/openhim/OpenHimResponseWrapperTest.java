package org.openphc.cce.emitter.openhim;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.OpenHimProperties;
import org.openphc.cce.emitter.openhim.model.OpenHimResponse;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenHimResponseWrapper}.
 */
class OpenHimResponseWrapperTest {

    private OpenHimResponseWrapper wrapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var coreProps = new OpenHimProperties.CoreProperties("localhost", 8080,
                "root@openhim.org", "openhim-password");
        var mediatorProps = new OpenHimProperties.MediatorProperties(
                "urn:mediator:cce-emitter-adaptor", "1.0.0", "CCE Emitter Adaptor");
        var heartbeatProps = new OpenHimProperties.HeartbeatProperties(true, 10);
        var endpointProps = new OpenHimProperties.EndpointProperties(
                "emitter-adaptor", "/inbound", 8082, "http");
        var openHimProperties = new OpenHimProperties(coreProps, mediatorProps,
                heartbeatProps, endpointProps);

        wrapper = new OpenHimResponseWrapper(openHimProperties, objectMapper);
    }

    @Nested
    @DisplayName("Successful responses")
    class SuccessfulResponses {

        @Test
        @DisplayName("should wrap 202 response with 'Successful' status")
        void shouldWrap202AsSuccessful() {
            OpenHimResponse response = wrapper.wrap(
                    Map.of("processed", 1),
                    HttpStatus.ACCEPTED,
                    List.of());

            assertThat(response.getMediatorUrn()).isEqualTo("urn:mediator:cce-emitter-adaptor");
            assertThat(response.getStatus()).isEqualTo("Successful");
            assertThat(response.getResponse().getStatus()).isEqualTo(202);
        }

        @Test
        @DisplayName("should wrap 200 response with 'Successful' status")
        void shouldWrap200AsSuccessful() {
            OpenHimResponse response = wrapper.wrap(
                    Map.of("status", "ignored"),
                    HttpStatus.OK,
                    List.of());

            assertThat(response.getStatus()).isEqualTo("Successful");
            assertThat(response.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("should serialize object body to JSON string")
        void shouldSerializeObjectBody() {
            OpenHimResponse response = wrapper.wrap(
                    Map.of("eventId", "evt-001", "status", "accepted"),
                    HttpStatus.ACCEPTED,
                    List.of());

            assertThat(response.getResponse().getBody()).contains("eventId");
            assertThat(response.getResponse().getBody()).contains("evt-001");
        }

        @Test
        @DisplayName("should pass through string body as-is")
        void shouldPassThroughStringBody() {
            String body = "{\"already\":\"serialized\"}";

            OpenHimResponse response = wrapper.wrap(body, HttpStatus.ACCEPTED, List.of());

            assertThat(response.getResponse().getBody()).isEqualTo(body);
        }

        @Test
        @DisplayName("should include Content-Type header in response")
        void shouldIncludeContentTypeHeader() {
            OpenHimResponse response = wrapper.wrap("body", HttpStatus.ACCEPTED, List.of());

            assertThat(response.getResponse().getHeaders())
                    .containsEntry("Content-Type", "application/json");
        }

        @Test
        @DisplayName("should include ISO-8601 timestamp")
        void shouldIncludeTimestamp() {
            OpenHimResponse response = wrapper.wrap("body", HttpStatus.ACCEPTED, List.of());

            assertThat(response.getResponse().getTimestamp()).isNotNull();
            assertThat(response.getResponse().getTimestamp()).contains("T");
            assertThat(response.getResponse().getTimestamp()).containsAnyOf("+", "Z");
        }
    }

    @Nested
    @DisplayName("Failed responses")
    class FailedResponses {

        @Test
        @DisplayName("should wrap 502 response with 'Failed' status")
        void shouldWrap502AsFailed() {
            OpenHimResponse response = wrapper.wrap(
                    Map.of("error", "Collector unreachable"),
                    HttpStatus.BAD_GATEWAY,
                    List.of());

            assertThat(response.getStatus()).isEqualTo("Failed");
            assertThat(response.getResponse().getStatus()).isEqualTo(502);
        }

        @Test
        @DisplayName("should wrap 400 response with 'Failed' status")
        void shouldWrap400AsFailed() {
            OpenHimResponse response = wrapper.wrap(
                    Map.of("error", "Bad request"),
                    HttpStatus.BAD_REQUEST,
                    List.of());

            assertThat(response.getStatus()).isEqualTo("Failed");
            assertThat(response.getResponse().getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("should wrap 422 response with 'Failed' status")
        void shouldWrap422AsFailed() {
            OpenHimResponse response = wrapper.wrap(
                    Map.of("error", "FHIR mapping error"),
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    List.of());

            assertThat(response.getStatus()).isEqualTo("Failed");
            assertThat(response.getResponse().getStatus()).isEqualTo(422);
        }
    }

    @Nested
    @DisplayName("Orchestrations")
    class Orchestrations {

        @Test
        @DisplayName("should include orchestration entries in response")
        void shouldIncludeOrchestrations() {
            var orchestration = OpenHimResponse.Orchestration.builder()
                    .name("Forward to CCE Collector")
                    .request(OpenHimResponse.Orchestration.Request.builder()
                            .method("POST")
                            .path("/v1/events")
                            .body("{\"type\":\"Encounter\"}")
                            .timestamp("2026-02-25T08:00:00Z")
                            .build())
                    .response(OpenHimResponse.Orchestration.OrchestrationResponse.builder()
                            .status(202)
                            .body("{\"data\":{\"eventId\":\"evt-001\"}}")
                            .timestamp("2026-02-25T08:00:01Z")
                            .build())
                    .build();

            OpenHimResponse response = wrapper.wrap("body", HttpStatus.ACCEPTED,
                    List.of(orchestration));

            assertThat(response.getOrchestrations()).hasSize(1);
            assertThat(response.getOrchestrations().get(0).getName())
                    .isEqualTo("Forward to CCE Collector");
            assertThat(response.getOrchestrations().get(0).getRequest().getMethod())
                    .isEqualTo("POST");
            assertThat(response.getOrchestrations().get(0).getResponse().getStatus())
                    .isEqualTo(202);
        }

        @Test
        @DisplayName("should default to empty orchestrations when null is passed")
        void shouldDefaultToEmptyOrchestrations() {
            OpenHimResponse response = wrapper.wrap("body", HttpStatus.ACCEPTED, null);

            assertThat(response.getOrchestrations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle null body")
        void shouldHandleNullBody() {
            OpenHimResponse response = wrapper.wrap(null, HttpStatus.ACCEPTED, List.of());

            assertThat(response.getResponse().getBody()).isNull();
        }
    }
}
