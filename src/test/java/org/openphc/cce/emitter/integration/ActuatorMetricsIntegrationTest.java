package org.openphc.cce.emitter.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Actuator endpoints and Micrometer metrics.
 *
 * <p>Uses {@link TestRestTemplate} (real HTTP) instead of MockMvc so that
 * Actuator Prometheus endpoint (which registers outside the main DispatcherServlet)
 * is reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class ActuatorMetricsIntegrationTest {

    private static WireMockServer collectorMock;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void startWireMock() {
        collectorMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        collectorMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        collectorMock.stop();
    }

    @AfterEach
    void resetWireMock() {
        collectorMock.resetAll();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("cce.collector.url", () -> "http://localhost:" + collectorMock.port());
    }

    private String loadFixture(String path) throws Exception {
        return Files.readString(Path.of("src/test/resources/" + path));
    }

    private void postEncounterToInbound() throws Exception {
        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "eventId": "evt-001",
                                    "status": "accepted",
                                    "correlationId": "corr-001",
                                    "timestamp": "2026-02-25T08:00:00Z"
                                  }
                                }
                                """)));

        String encounterJson = loadFixture("fhir/encounter-visit.json");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-OpenHIM-ClientID", "ebuzima-emr-client");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/inbound", new HttpEntity<>(encounterJson, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ==================== Health Probes ====================

    @Nested
    @DisplayName("Health Probes")
    class HealthProbes {

        @Test
        @DisplayName("GET /actuator/health returns UP")
        void healthEndpoint() {
            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"status\":\"UP\"");
        }

        @Test
        @DisplayName("GET /actuator/health/liveness returns UP")
        void livenessProbe() {
            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/liveness", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"status\":\"UP\"");
        }

        @Test
        @DisplayName("GET /actuator/health/readiness returns UP")
        void readinessProbe() {
            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/readiness", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"status\":\"UP\"");
        }
    }

    // ==================== Prometheus Endpoint ====================

    @Nested
    @DisplayName("Prometheus Metrics")
    class PrometheusMetrics {

        @Test
        @DisplayName("GET /actuator/prometheus returns metric output")
        void prometheusEndpoint() {
            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .contains("jvm_memory")
                    .contains("http_server");
        }
    }

    // ==================== Custom Application Metrics ====================

    @Nested
    @DisplayName("Custom Application Metrics")
    class CustomMetrics {

        @Test
        @DisplayName("should expose cce.emitter.events.received counter after an inbound request")
        void eventsReceivedCounter() throws Exception {
            postEncounterToInbound();

            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("cce_emitter_events_received");
        }

        @Test
        @DisplayName("should expose cce.emitter.events.forwarded counter after successful forwarding")
        void eventsForwardedCounter() throws Exception {
            postEncounterToInbound();

            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("cce_emitter_events_forwarded");
        }

        @Test
        @DisplayName("should expose cce.emitter.collector.latency timer after forwarding")
        void collectorLatencyTimer() throws Exception {
            postEncounterToInbound();

            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("cce_emitter_collector_latency");
        }
    }
}
