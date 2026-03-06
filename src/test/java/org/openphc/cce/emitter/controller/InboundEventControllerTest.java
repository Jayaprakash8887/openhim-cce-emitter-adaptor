package org.openphc.cce.emitter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.CollectorProperties;
import org.openphc.cce.emitter.config.MediatorProperties;
import org.openphc.cce.emitter.exception.CollectorClientException;
import org.openphc.cce.emitter.exception.CollectorForwardingException;
import org.openphc.cce.emitter.exception.GlobalExceptionHandler;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.openphc.cce.emitter.openhim.OpenHimResponseWrapper;
import org.openphc.cce.emitter.adaptor.SourceAdaptorService;
import org.openphc.cce.emitter.service.CollectorForwardingService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-level tests for {@link InboundEventController}.
 *
 * <p>Uses standalone MockMvc setup with Mockito mocks — avoids Spring Boot
 * context loading for fast, isolated controller tests.
 */
@ExtendWith(MockitoExtension.class)
class InboundEventControllerTest {

    private static final String OPENHIM_MEDIA_TYPE = "application/json+openhim";
    private static final String INBOUND_PATH = "/inbound";

    @Mock
    private SourceAdaptorService sourceAdaptorService;

    @Mock
    private CollectorForwardingService collectorForwardingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();

        MediatorProperties mediatorProperties = new MediatorProperties(
                "urn:mediator:cce-emitter-adaptor", "1.0.0", "CCE Emitter Adaptor",
                new MediatorProperties.EndpointProperties("localhost", "/inbound", "http"));

        CollectorProperties collectorProperties = new CollectorProperties(
                "http://localhost:5001", "/v1/events", 5000,
                new CollectorProperties.RetryProperties(3, 1000L),
                new CollectorProperties.AuthProperties("test-token"));

        OpenHimResponseWrapper responseWrapper = new OpenHimResponseWrapper(mediatorProperties, objectMapper);

        InboundEventController controller = new InboundEventController(
                sourceAdaptorService, collectorForwardingService,
                responseWrapper, collectorProperties, objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ==================== Sample Data ====================

    private static final String ENCOUNTER_JSON = """
            {
              "resourceType": "Encounter",
              "id": "enc-001",
              "status": "in-progress",
              "class": {
                "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                "code": "AMB"
              },
              "subject": {
                "reference": "Patient/260225-0002-5501"
              }
            }
            """;

    private static final String BUNDLE_JSON = """
            {
              "resourceType": "Bundle",
              "type": "transaction",
              "entry": []
            }
            """;

    private CloudEventDto sampleCloudEvent() {
        return CloudEventDto.builder()
                .specversion("1.0")
                .id("evt-001")
                .source("ebuzima")
                .type("Encounter")
                .subject("260225-0002-5501")
                .datacontenttype("application/fhir+json")
                .correlationid("corr-001")
                .build();
    }

    private CollectorResponse successResponse() {
        return new CollectorResponse(
                new CollectorResponse.DataPayload("evt-001", "accepted", "corr-001", "2026-03-01T10:00:00Z"),
                null);
    }

    private CollectorResponse duplicateResponse() {
        return new CollectorResponse(
                new CollectorResponse.DataPayload("evt-001", "duplicate", "corr-001", "2026-03-01T10:00:00Z"),
                null);
    }

    // ==================== Successful forwarding (202 Accepted) ====================

    @Nested
    class SuccessfulForwarding {

        @Test
        void validFhirWithMatchingSource_returns202WithOpenHimEnvelope() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(successResponse());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .header("X-Facility-Id", "0002")
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isAccepted())
                    .andExpect(content().contentTypeCompatibleWith(OPENHIM_MEDIA_TYPE))
                    .andExpect(jsonPath("$.x-mediator-urn", is("urn:mediator:cce-emitter-adaptor")))
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.response.status", is(202)))
                    .andExpect(jsonPath("$.response.body", containsString("processed")))
                    .andExpect(jsonPath("$.response.body", containsString("eventsForwarded")));
        }

        @Test
        void responseContainsOrchestrations() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(successResponse());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.orchestrations", hasSize(1)))
                    .andExpect(jsonPath("$.orchestrations[0].name", is("Forward to CCE Collector")))
                    .andExpect(jsonPath("$.orchestrations[0].request.method", is("POST")))
                    .andExpect(jsonPath("$.orchestrations[0].request.path", is("/v1/events")))
                    .andExpect(jsonPath("$.orchestrations[0].response.status", is(202)));
        }

        @Test
        void multipleEvents_forwardsAllAndReturns202() throws Exception {
            CloudEventDto event1 = sampleCloudEvent();
            CloudEventDto event2 = CloudEventDto.builder()
                    .specversion("1.0").id("evt-002").source("ebuzima")
                    .type("Observation").subject("260225-0002-5501").build();

            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(event1, event2));
            when(collectorForwardingService.forward(event1)).thenReturn(successResponse());
            when(collectorForwardingService.forward(event2)).thenReturn(
                    new CollectorResponse(
                            new CollectorResponse.DataPayload("evt-002", "accepted", "corr-002", null), null));

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.orchestrations", hasSize(2)))
                    .andExpect(jsonPath("$.response.body", containsString("\"eventsForwarded\":2")));
        }

        @Test
        void duplicateEvent_returns202WithDuplicateStatus() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(duplicateResponse());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.orchestrations[0].response.status", is(200)));
        }
    }

    // ==================== Silent ignore (200 OK) ====================

    @Nested
    class SilentIgnore {

        @Test
        void noMatchingSource_returns200WithIgnoredStatus() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "unknown-client")
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(OPENHIM_MEDIA_TYPE))
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.response.status", is(200)))
                    .andExpect(jsonPath("$.response.body", containsString("ignored")))
                    .andExpect(jsonPath("$.orchestrations", hasSize(0)));
        }

        @Test
        void fhirBundle_returns200Ignored() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .content(BUNDLE_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response.body", containsString("ignored")));
        }

        @Test
        void emptyBody_returns200Ignored() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("Successful")));
        }

        @Test
        void noHeaders_returns200Ignored() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response.body", containsString("ignored")));
        }
    }

    // ==================== Error handling ====================

    @Nested
    class ErrorHandling {

        @Test
        void collectorForwardingException_returns502() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any()))
                    .thenThrow(new CollectorForwardingException("Collector returned 503"));

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_FORWARDING_ERROR")));
        }

        @Test
        void collectorClientException_returnsDynamicStatus() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any()))
                    .thenThrow(new CollectorClientException("type is required", 422, null));

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_CLIENT_ERROR")));
        }
    }

    // ==================== Content-type ====================

    @Nested
    class ContentType {

        @Test
        void successResponse_hasOpenHimMediaType() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(successResponse());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(content().contentTypeCompatibleWith(OPENHIM_MEDIA_TYPE));
        }

        @Test
        void ignoredResponse_hasOpenHimMediaType() throws Exception {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(content().contentTypeCompatibleWith(OPENHIM_MEDIA_TYPE));
        }
    }
}
