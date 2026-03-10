package org.openphc.cce.emitter.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for retry and error handling behaviour.
 *
 * <p>Uses a WireMock-stubbed Collector to simulate server errors (503),
 * client errors (422), and network timeouts to verify retry with backoff
 * and correct error propagation via the GlobalExceptionHandler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class RetryIntegrationTest {

    private static WireMockServer collectorMock;

    @Autowired
    private MockMvc mockMvc;

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
        // Fast backoff for tests
        registry.add("cce.collector.retry.backoff-ms", () -> "50");
        registry.add("cce.collector.retry.max-attempts", () -> "3");
    }

    private String loadFixture(String path) throws Exception {
        return Files.readString(Path.of("src/test/resources/" + path));
    }

    // ==================== Retry on 5xx ====================

    @Test
    @DisplayName("should retry 3 times on 503 then return 502 COLLECTOR_FORWARDING_ERROR")
    void retryOnServerError() throws Exception {
        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("Service Unavailable")));

        String encounterJson = loadFixture("fhir/encounter-visit.json");

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                        .content(encounterJson))
                .andExpect(status().isBadGateway());

        // Verify Collector was called 3 times (initial + 2 retries = max-attempts of 3)
        collectorMock.verify(3, postRequestedFor(urlEqualTo("/v1/events")));
    }

    // ==================== No retry on 4xx ====================

    @Test
    @DisplayName("should NOT retry on 422 — return error immediately")
    void noRetryOnClientError() throws Exception {
        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "error": {
                                    "code": "VALIDATION_ERROR",
                                    "message": "Missing required field: type"
                                  }
                                }
                                """)));

        String encounterJson = loadFixture("fhir/encounter-visit.json");

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                        .content(encounterJson))
                .andExpect(status().isUnprocessableEntity());

        // Verify Collector was called exactly once — no retries
        collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events")));
    }

    // ==================== Retry then succeed ====================

    @Test
    @DisplayName("should succeed after transient 503 followed by 202")
    void retryThenSucceed() throws Exception {
        // First call: 503, second call: 202
        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable"))
                .willSetStateTo("second-attempt"));

        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "eventId": "collector-evt-001",
                                    "status": "accepted",
                                    "correlationId": "corr-001",
                                    "timestamp": "2026-02-25T08:00:00Z"
                                  }
                                }
                                """)));

        String encounterJson = loadFixture("fhir/encounter-visit.json");

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                        .content(encounterJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("Successful")))
                .andExpect(jsonPath("$.orchestrations", hasSize(1)));

        collectorMock.verify(2, postRequestedFor(urlEqualTo("/v1/events")));
    }

    // ==================== 400 Bad Request ====================

    @Test
    @DisplayName("should NOT retry on 400 — return error immediately")
    void noRetryOn400() throws Exception {
        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "error": {
                                    "code": "BAD_REQUEST",
                                    "message": "Malformed event"
                                  }
                                }
                                """)));

        String encounterJson = loadFixture("fhir/encounter-visit.json");

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                        .content(encounterJson))
                .andExpect(status().isBadRequest());

        collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events")));
    }
}
