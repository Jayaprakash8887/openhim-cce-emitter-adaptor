package org.openphc.cce.emitter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.exception.CollectorClientException;
import org.openphc.cce.emitter.exception.CollectorForwardingException;
import org.openphc.cce.emitter.exception.GlobalExceptionHandler;
import org.openphc.cce.emitter.openhim.model.OpenHimResponse;
import org.openphc.cce.emitter.service.InboundEventService;
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
 * <p>Uses standalone MockMvc setup with a mocked {@link InboundEventService}.
 * The controller is a thin HTTP layer — these tests verify request binding,
 * status code mapping, content-type, and JSON serialization only.
 * Orchestration logic is tested in {@code InboundEventServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class InboundEventControllerTest {

    private static final String OPENHIM_MEDIA_TYPE = "application/json+openhim";
    private static final String INBOUND_PATH = "/inbound";

    @Mock
    private InboundEventService inboundEventService;

    private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        InboundEventController controller = new InboundEventController(inboundEventService, objectMapper);

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

    private OpenHimResponse acceptedResult() {
        return OpenHimResponse.builder()
                .mediatorUrn("urn:mediator:cce-emitter-adaptor")
                .status("Successful")
                .response(OpenHimResponse.Response.builder()
                        .status(202)
                        .headers(java.util.Map.of("Content-Type", "application/json"))
                        .body("{\"status\":\"processed\",\"eventsForwarded\":1}")
                        .timestamp("2026-03-01T10:00:00Z")
                        .build())
                .orchestrations(List.of(
                        OpenHimResponse.Orchestration.builder()
                                .name("Forward to CCE Collector")
                                .request(OpenHimResponse.Orchestration.Request.builder()
                                        .method("POST").path("/v1/events")
                                        .body("{}").timestamp("2026-03-01T10:00:00Z")
                                        .headers(java.util.Map.of("Content-Type", "application/json"))
                                        .build())
                                .response(OpenHimResponse.Orchestration.OrchestrationResponse.builder()
                                        .status(202).body("{}")
                                        .timestamp("2026-03-01T10:00:00Z")
                                        .build())
                                .build()))
                .build();
    }

    private OpenHimResponse ignoredResult() {
        return OpenHimResponse.builder()
                .mediatorUrn("urn:mediator:cce-emitter-adaptor")
                .status("Successful")
                .response(OpenHimResponse.Response.builder()
                        .status(200)
                        .headers(java.util.Map.of("Content-Type", "application/json"))
                        .body("{\"status\":\"ignored\",\"message\":\"No matching source or non-processable payload\"}")
                        .timestamp("2026-03-01T10:00:00Z")
                        .build())
                .orchestrations(List.of())
                .build();
    }

    // ==================== Successful forwarding (202 Accepted) ====================

    @Nested
    class SuccessfulForwarding {

        @Test
        void returns202WithOpenHimEnvelope() throws Exception {
            when(inboundEventService.process(any())).thenReturn(acceptedResult());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
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
            when(inboundEventService.process(any())).thenReturn(acceptedResult());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.orchestrations", hasSize(1)))
                    .andExpect(jsonPath("$.orchestrations[0].name", is("Forward to CCE Collector")))
                    .andExpect(jsonPath("$.orchestrations[0].request.method", is("POST")))
                    .andExpect(jsonPath("$.orchestrations[0].request.path", is("/v1/events")))
                    .andExpect(jsonPath("$.orchestrations[0].response.status", is(202)));
        }

        @Test
        void delegatesToService() throws Exception {
            when(inboundEventService.process(any())).thenReturn(acceptedResult());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isAccepted());

            verify(inboundEventService).process(any());
        }
    }

    // ==================== Silent ignore (200 OK) ====================

    @Nested
    class SilentIgnore {

        @Test
        void returns200WithIgnoredStatus() throws Exception {
            when(inboundEventService.process(any())).thenReturn(ignoredResult());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(OPENHIM_MEDIA_TYPE))
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.response.status", is(200)))
                    .andExpect(jsonPath("$.response.body", containsString("ignored")))
                    .andExpect(jsonPath("$.orchestrations", hasSize(0)));
        }

        @Test
        void emptyBody_delegatesToService() throws Exception {
            when(inboundEventService.process(any())).thenReturn(ignoredResult());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("Successful")));
        }
    }

    // ==================== Error handling ====================

    @Nested
    class ErrorHandling {

        @Test
        void collectorForwardingException_returns502() throws Exception {
            when(inboundEventService.process(any()))
                    .thenThrow(new CollectorForwardingException("Collector returned 503"));

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_FORWARDING_ERROR")));
        }

        @Test
        void collectorClientException_returnsDynamicStatus() throws Exception {
            when(inboundEventService.process(any()))
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
            when(inboundEventService.process(any())).thenReturn(acceptedResult());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(content().contentTypeCompatibleWith(OPENHIM_MEDIA_TYPE));
        }

        @Test
        void ignoredResponse_hasOpenHimMediaType() throws Exception {
            when(inboundEventService.process(any())).thenReturn(ignoredResult());

            mockMvc.perform(post(INBOUND_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ENCOUNTER_JSON))
                    .andExpect(content().contentTypeCompatibleWith(OPENHIM_MEDIA_TYPE));
        }
    }
}
