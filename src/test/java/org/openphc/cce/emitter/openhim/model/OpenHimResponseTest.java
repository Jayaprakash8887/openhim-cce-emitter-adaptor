package org.openphc.cce.emitter.openhim.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson serialization tests for OpenHIM model DTOs.
 * Verifies JSON structure matches the OpenHIM mediator specification.
 */
class OpenHimResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("OpenHimResponse serialization")
    class ResponseEnvelopeTests {

        @Test
        @DisplayName("should serialize mediator URN as 'x-mediator-urn'")
        void shouldSerializeMediatorUrn() throws Exception {
            OpenHimResponse response = OpenHimResponse.builder()
                    .mediatorUrn("urn:mediator:cce-emitter-adaptor")
                    .status("Successful")
                    .build();

            String json = objectMapper.writeValueAsString(response);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.has("x-mediator-urn")).isTrue();
            assertThat(node.get("x-mediator-urn").asText()).isEqualTo("urn:mediator:cce-emitter-adaptor");
            assertThat(node.has("mediatorUrn")).isFalse();
        }

        @Test
        @DisplayName("should serialize full response envelope with all fields")
        void shouldSerializeFullEnvelope() throws Exception {
            OpenHimResponse response = OpenHimResponse.builder()
                    .mediatorUrn("urn:mediator:cce-emitter-adaptor")
                    .status("Successful")
                    .response(OpenHimResponse.Response.builder()
                            .status(202)
                            .headers(Map.of("Content-Type", "application/json"))
                            .body("{\"eventId\":\"evt-001\"}")
                            .timestamp("2026-02-25T08:00:00Z")
                            .build())
                    .orchestrations(List.of())
                    .build();

            String json = objectMapper.writeValueAsString(response);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("status").asText()).isEqualTo("Successful");
            assertThat(node.get("response").get("status").asInt()).isEqualTo(202);
            assertThat(node.get("response").get("headers").get("Content-Type").asText())
                    .isEqualTo("application/json");
            assertThat(node.get("response").get("body").asText())
                    .isEqualTo("{\"eventId\":\"evt-001\"}");
            assertThat(node.get("response").get("timestamp").asText())
                    .isEqualTo("2026-02-25T08:00:00Z");
            assertThat(node.get("orchestrations").isArray()).isTrue();
            assertThat(node.get("orchestrations")).isEmpty();
        }

        @Test
        @DisplayName("should omit null fields")
        void shouldOmitNullFields() throws Exception {
            OpenHimResponse response = OpenHimResponse.builder()
                    .mediatorUrn("urn:mediator:cce-emitter-adaptor")
                    .status("Successful")
                    .build();

            String json = objectMapper.writeValueAsString(response);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.has("response")).isFalse();
        }

        @Test
        @DisplayName("should serialize Failed status")
        void shouldSerializeFailedStatus() throws Exception {
            OpenHimResponse response = OpenHimResponse.builder()
                    .mediatorUrn("urn:mediator:cce-emitter-adaptor")
                    .status("Failed")
                    .response(OpenHimResponse.Response.builder()
                            .status(502)
                            .body("{\"error\":\"Collector unreachable\"}")
                            .timestamp("2026-02-25T08:00:00Z")
                            .build())
                    .build();

            String json = objectMapper.writeValueAsString(response);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("status").asText()).isEqualTo("Failed");
            assertThat(node.get("response").get("status").asInt()).isEqualTo(502);
        }

        @Test
        @DisplayName("should round-trip deserialize from JSON")
        void shouldRoundTripDeserialize() throws Exception {
            String json = """
                    {
                      "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
                      "status": "Successful",
                      "response": {
                        "status": 202,
                        "headers": {"Content-Type": "application/json"},
                        "body": "{\\"accepted\\":true}",
                        "timestamp": "2026-02-25T08:00:00Z"
                      },
                      "orchestrations": []
                    }
                    """;

            OpenHimResponse response = objectMapper.readValue(json, OpenHimResponse.class);

            assertThat(response.getMediatorUrn()).isEqualTo("urn:mediator:cce-emitter-adaptor");
            assertThat(response.getStatus()).isEqualTo("Successful");
            assertThat(response.getResponse().getStatus()).isEqualTo(202);
            assertThat(response.getOrchestrations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Orchestration serialization")
    class OrchestrationTests {

        @Test
        @DisplayName("should serialize orchestration with request and response details")
        void shouldSerializeOrchestration() throws Exception {
            OpenHimResponse.Orchestration orchestration = OpenHimResponse.Orchestration.builder()
                    .name("Forward to CCE Collector")
                    .request(OpenHimResponse.Orchestration.Request.builder()
                            .method("POST")
                            .path("/v1/events")
                            .body("{\"type\":\"Encounter\"}")
                            .timestamp("2026-02-25T08:00:00Z")
                            .headers(Map.of("Content-Type", "application/json"))
                            .build())
                    .response(OpenHimResponse.Orchestration.OrchestrationResponse.builder()
                            .status(202)
                            .body("{\"data\":{\"eventId\":\"evt-001\",\"status\":\"accepted\"}}")
                            .timestamp("2026-02-25T08:00:01Z")
                            .build())
                    .build();

            String json = objectMapper.writeValueAsString(orchestration);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("name").asText()).isEqualTo("Forward to CCE Collector");
            assertThat(node.get("request").get("method").asText()).isEqualTo("POST");
            assertThat(node.get("request").get("path").asText()).isEqualTo("/v1/events");
            assertThat(node.get("request").get("body").asText()).isEqualTo("{\"type\":\"Encounter\"}");
            assertThat(node.get("request").get("headers").get("Content-Type").asText())
                    .isEqualTo("application/json");
            assertThat(node.get("response").get("status").asInt()).isEqualTo(202);
            assertThat(node.get("response").get("body").asText())
                    .contains("accepted");
        }

        @Test
        @DisplayName("should serialize response with orchestrations list")
        void shouldSerializeWithOrchestrationsList() throws Exception {
            OpenHimResponse response = OpenHimResponse.builder()
                    .mediatorUrn("urn:mediator:cce-emitter-adaptor")
                    .status("Successful")
                    .response(OpenHimResponse.Response.builder()
                            .status(202)
                            .body("{\"processed\":1}")
                            .timestamp("2026-02-25T08:00:00Z")
                            .build())
                    .orchestrations(List.of(
                            OpenHimResponse.Orchestration.builder()
                                    .name("Forward to CCE Collector")
                                    .request(OpenHimResponse.Orchestration.Request.builder()
                                            .method("POST")
                                            .path("/v1/events")
                                            .timestamp("2026-02-25T08:00:00Z")
                                            .build())
                                    .response(OpenHimResponse.Orchestration.OrchestrationResponse.builder()
                                            .status(202)
                                            .timestamp("2026-02-25T08:00:01Z")
                                            .build())
                                    .build()
                    ))
                    .build();

            String json = objectMapper.writeValueAsString(response);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("orchestrations").size()).isEqualTo(1);
            assertThat(node.get("orchestrations").get(0).get("name").asText())
                    .isEqualTo("Forward to CCE Collector");
        }
    }

    @Nested
    @DisplayName("MediatorDescriptor serialization")
    class DescriptorTests {

        @Test
        @DisplayName("should serialize mediator descriptor with all fields")
        void shouldSerializeDescriptor() throws Exception {
            MediatorDescriptor descriptor = MediatorDescriptor.builder()
                    .urn("urn:mediator:cce-emitter-adaptor")
                    .version("1.0.0")
                    .name("CCE Emitter Adaptor")
                    .description("Wraps FHIR R4 resources into CloudEvents for CCE Collector")
                    .defaultChannelConfig(List.of())
                    .endpoints(List.of(
                            MediatorDescriptor.Endpoint.builder()
                                    .name("CCE Emitter Adaptor")
                                    .host("emitter-adaptor")
                                    .path("/inbound")
                                    .port(8082)
                                    .primary(true)
                                    .type("http")
                                    .build()
                    ))
                    .build();

            String json = objectMapper.writeValueAsString(descriptor);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("urn").asText()).isEqualTo("urn:mediator:cce-emitter-adaptor");
            assertThat(node.get("version").asText()).isEqualTo("1.0.0");
            assertThat(node.get("name").asText()).isEqualTo("CCE Emitter Adaptor");
            assertThat(node.get("defaultChannelConfig").isArray()).isTrue();
            assertThat(node.get("defaultChannelConfig")).isEmpty();
            assertThat(node.get("endpoints").size()).isEqualTo(1);
            assertThat(node.get("endpoints").get(0).get("port").asInt()).isEqualTo(8082);
            assertThat(node.get("endpoints").get(0).get("primary").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("should have empty defaultChannelConfig by default")
        void shouldHaveEmptyDefaultChannelConfig() throws Exception {
            MediatorDescriptor descriptor = MediatorDescriptor.builder()
                    .urn("urn:mediator:cce-emitter-adaptor")
                    .version("1.0.0")
                    .name("CCE Emitter Adaptor")
                    .build();

            String json = objectMapper.writeValueAsString(descriptor);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("defaultChannelConfig").isArray()).isTrue();
            assertThat(node.get("defaultChannelConfig")).isEmpty();
        }
    }

    @Nested
    @DisplayName("HeartbeatRequest serialization")
    class HeartbeatTests {

        @Test
        @DisplayName("should serialize heartbeat request with uptime")
        void shouldSerializeHeartbeatRequest() throws Exception {
            HeartbeatRequest heartbeat = new HeartbeatRequest(120000L);

            String json = objectMapper.writeValueAsString(heartbeat);
            JsonNode node = objectMapper.readTree(json);

            assertThat(node.get("uptime").asLong()).isEqualTo(120000L);
        }

        @Test
        @DisplayName("should round-trip deserialize heartbeat request")
        void shouldRoundTripHeartbeat() throws Exception {
            String json = "{\"uptime\":60000}";

            HeartbeatRequest heartbeat = objectMapper.readValue(json, HeartbeatRequest.class);

            assertThat(heartbeat.uptime()).isEqualTo(60000L);
        }
    }
}
