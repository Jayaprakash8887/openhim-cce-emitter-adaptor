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
 * Full pipeline integration tests — boots the real Spring context,
 * sends HTTP requests to {@code /inbound}, and verifies CloudEvents
 * are forwarded to a WireMock-stubbed CCE Collector.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class FullPipelineIntegrationTest {

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
    }

    // ==================== Helper methods ====================

    private String loadFixture(String path) throws Exception {
        return Files.readString(Path.of("src/test/resources/" + path));
    }

    private void stubCollectorAccepted() {
        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
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
    }

    private void stubCollectorDuplicate() {
        collectorMock.stubFor(WireMock.post(urlEqualTo("/v1/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "eventId": "collector-evt-001",
                                    "status": "duplicate",
                                    "correlationId": "corr-001",
                                    "timestamp": "2026-02-25T08:00:00Z"
                                  }
                                }
                                """)));
    }

    // ==================== Happy Path Tests ====================

    @Nested
    @DisplayName("Happy Path — FHIR Encounter")
    class HappyPathEncounter {

        @Test
        @DisplayName("should forward Encounter as CloudEvent and return 202 with OpenHIM envelope")
        void forwardEncounter() throws Exception {
            stubCollectorAccepted();

            String encounterJson = loadFixture("fhir/encounter-visit.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .header("X-Facility-Id", "0002")
                            .header("X-Source-Event-Id", "enc-visit-001")
                            .header("X-Correlation-Id", "corr-test-001")
                            .content(encounterJson))
                    .andExpect(status().isAccepted())
                    .andExpect(content().contentTypeCompatibleWith("application/json+openhim"))
                    .andExpect(jsonPath("$.x-mediator-urn", is("urn:mediator:cce-emitter-adaptor")))
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.response.status", is(202)))
                    .andExpect(jsonPath("$.orchestrations", hasSize(1)))
                    .andExpect(jsonPath("$.orchestrations[0].name", is("Forward to CCE Collector")))
                    .andExpect(jsonPath("$.orchestrations[0].request.method", is("POST")))
                    .andExpect(jsonPath("$.orchestrations[0].request.path", is("/v1/events")))
                    .andExpect(jsonPath("$.orchestrations[0].response.status", is(202)));

            // Verify Collector received the CloudEvent with correct fields
            collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events"))
                    .withRequestBody(matchingJsonPath("$.specversion", equalTo("1.0")))
                    .withRequestBody(matchingJsonPath("$.source", equalTo("ebuzima")))
                    .withRequestBody(matchingJsonPath("$.type", equalTo("Encounter")))
                    .withRequestBody(matchingJsonPath("$.subject", equalTo("260225-0002-5501")))
                    .withRequestBody(matchingJsonPath("$.facilityid", equalTo("0002")))
                    .withRequestBody(matchingJsonPath("$.sourceeventid", equalTo("enc-visit-001")))
                    .withRequestBody(matchingJsonPath("$.correlationid", equalTo("corr-test-001")))
                    .withRequestBody(matchingJsonPath("$.datacontenttype", equalTo("application/fhir+json")))
                    .withRequestBody(matchingJsonPath("$.data.resourceType", equalTo("Encounter"))));
        }
    }

    @Nested
    @DisplayName("Happy Path — FHIR Observation")
    class HappyPathObservation {

        @Test
        @DisplayName("should forward Observation as CloudEvent and return 202")
        void forwardObservation() throws Exception {
            stubCollectorAccepted();

            String observationJson = loadFixture("fhir/observation-lab.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content(observationJson))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.response.status", is(202)));

            collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events"))
                    .withRequestBody(matchingJsonPath("$.type", equalTo("Observation")))
                    .withRequestBody(matchingJsonPath("$.subject", equalTo("260225-0002-5501")))
                    .withRequestBody(matchingJsonPath("$.data.resourceType", equalTo("Observation"))));
        }
    }

    // ==================== Source Routing Tests ====================

    @Nested
    @DisplayName("Source Routing")
    class SourceRouting {

        @Test
        @DisplayName("should route by X-Source-System header when X-OpenHIM-ClientID is absent")
        void routeBySourceSystemHeader() throws Exception {
            stubCollectorAccepted();

            String encounterJson = loadFixture("fhir/encounter-visit.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Source-System", "ebuzima")
                            .content(encounterJson))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status", is("Successful")));

            collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events"))
                    .withRequestBody(matchingJsonPath("$.source", equalTo("ebuzima"))));
        }

        @Test
        @DisplayName("should silently ignore unknown source — 200 OK, no Collector call")
        void unknownSourceIgnored() throws Exception {
            String encounterJson = loadFixture("fhir/encounter-visit.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "unknown-client")
                            .content(encounterJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("Successful")));

            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }
    }

    // ==================== Bundle Handling ====================

    @Nested
    @DisplayName("Bundle Handling")
    class BundleHandling {

        @Test
        @DisplayName("should silently ignore Bundle resources — 200 OK, no Collector call")
        void bundleIgnored() throws Exception {
            String bundleJson = loadFixture("ebuzima/fhir-bundle.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content(bundleJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("Successful")));

            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }
    }

    // ==================== Duplicate Handling ====================

    @Nested
    @DisplayName("Duplicate Handling")
    class DuplicateHandling {

        @Test
        @DisplayName("should handle Collector duplicate response (200) gracefully")
        void handleDuplicate() throws Exception {
            stubCollectorDuplicate();

            String encounterJson = loadFixture("fhir/encounter-visit.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content(encounterJson))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.orchestrations[0].response.status", is(200)));
        }
    }

    // ==================== Correlation ID ====================

    @Nested
    @DisplayName("Correlation ID Handling")
    class CorrelationIdHandling {

        @Test
        @DisplayName("should generate correlationid when X-Correlation-Id header is absent")
        void generatesCorrelationId() throws Exception {
            stubCollectorAccepted();

            String encounterJson = loadFixture("fhir/encounter-visit.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content(encounterJson))
                    .andExpect(status().isAccepted());

            // Verify correlationid is present (auto-generated)
            collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events"))
                    .withRequestBody(matchingJsonPath("$.correlationid")));
        }
    }

    // ==================== Empty / Missing Body ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should return 200 OK for empty/null body")
        void emptyBody() throws Exception {
            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client"))
                    .andExpect(status().isOk());

            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }

        @Test
        @DisplayName("should return 200 OK for non-JSON body")
        void nonJsonBody() throws Exception {
            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content("this is not json"))
                    .andExpect(status().isOk());

            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }
    }

    // ==================== Response Body Content ====================

    @Nested
    @DisplayName("Response Body (ProcessedEventsResponse)")
    class ResponseBody {

        @Test
        @DisplayName("should include event details in response body")
        void responseBodyContainsEventDetails() throws Exception {
            stubCollectorAccepted();

            String encounterJson = loadFixture("fhir/encounter-visit.json");

            mockMvc.perform(post("/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content(encounterJson))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.response.body", containsString("\"status\":\"processed\"")))
                    .andExpect(jsonPath("$.response.body", containsString("\"eventsForwarded\":1")))
                    .andExpect(jsonPath("$.response.body", containsString("\"type\":\"Encounter\"")))
                    .andExpect(jsonPath("$.response.body", containsString("\"collectorStatus\":\"accepted\"")));
        }
    }
}
