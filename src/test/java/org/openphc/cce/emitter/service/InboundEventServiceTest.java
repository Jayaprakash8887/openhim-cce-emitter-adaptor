package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.adaptor.SourceAdaptorService;
import org.openphc.cce.emitter.config.CollectorProperties;
import org.openphc.cce.emitter.config.MediatorProperties;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.openhim.OpenHimResponseWrapper;
import org.openphc.cce.emitter.openhim.model.OpenHimResponse;
import org.openphc.cce.emitter.service.InboundEventService.PipelineResult;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InboundEventService}.
 *
 * <p>Verifies the orchestration pipeline: source resolution, Collector forwarding,
 * orchestration building, and OpenHIM response envelope construction.
 */
@ExtendWith(MockitoExtension.class)
class InboundEventServiceTest {

    @Mock
    private SourceAdaptorService sourceAdaptorService;

    @Mock
    private CollectorForwardingService collectorForwardingService;

    private InboundEventService service;

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

        service = new InboundEventService(
                sourceAdaptorService, collectorForwardingService,
                responseWrapper, collectorProperties, objectMapper);
    }

    // ==================== Sample Data ====================

    private InboundRequest sampleRequest() {
        return InboundRequest.from(
                "{\"resourceType\":\"Encounter\",\"id\":\"enc-001\",\"subject\":{\"reference\":\"Patient/PAT-001\"}}",
                Map.of("X-OpenHIM-ClientID", "ebuzima-emr-client"),
                "/inbound");
    }

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

    // ==================== Successful forwarding ====================

    @Nested
    class SuccessfulForwarding {

        @Test
        void singleEvent_returns202WithOrchestration() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(successResponse());

            PipelineResult result = service.process(sampleRequest());

            assertThat(result.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(result.envelope().getStatus()).isEqualTo("Successful");
            assertThat(result.envelope().getResponse().getStatus()).isEqualTo(202);
            assertThat(result.envelope().getOrchestrations()).hasSize(1);
            assertThat(result.envelope().getOrchestrations().get(0).getName())
                    .isEqualTo("Forward to CCE Collector");
        }

        @Test
        void orchestrationContainsRequestAndResponse() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(successResponse());

            PipelineResult result = service.process(sampleRequest());

            OpenHimResponse.Orchestration orch = result.envelope().getOrchestrations().get(0);
            assertThat(orch.getRequest().getMethod()).isEqualTo("POST");
            assertThat(orch.getRequest().getPath()).isEqualTo("/v1/events");
            assertThat(orch.getResponse().getStatus()).isEqualTo(202);
        }

        @Test
        void responseBodyContainsProcessedStatus() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(successResponse());

            PipelineResult result = service.process(sampleRequest());

            String body = result.envelope().getResponse().getBody();
            assertThat(body).contains("\"status\":\"processed\"");
            assertThat(body).contains("\"eventsForwarded\":1");
        }

        @Test
        void multipleEvents_forwardsAllAndBuildsOrchestrations() {
            CloudEventDto event1 = sampleCloudEvent();
            CloudEventDto event2 = CloudEventDto.builder()
                    .specversion("1.0").id("evt-002").source("ebuzima")
                    .type("Observation").subject("260225-0002-5501").build();

            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(event1, event2));
            when(collectorForwardingService.forward(event1)).thenReturn(successResponse());
            when(collectorForwardingService.forward(event2)).thenReturn(
                    new CollectorResponse(
                            new CollectorResponse.DataPayload("evt-002", "accepted", "corr-002", null), null));

            PipelineResult result = service.process(sampleRequest());

            assertThat(result.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(result.envelope().getOrchestrations()).hasSize(2);
            assertThat(result.envelope().getResponse().getBody())
                    .contains("\"eventsForwarded\":2");
            verify(collectorForwardingService, times(2)).forward(any());
        }

        @Test
        void duplicateEvent_returns202WithDuplicateOrchestrationStatus() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(duplicateResponse());

            PipelineResult result = service.process(sampleRequest());

            assertThat(result.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(result.envelope().getOrchestrations().get(0).getResponse().getStatus())
                    .isEqualTo(200);
        }
    }

    // ==================== Silent ignore (200 OK) ====================

    @Nested
    class SilentIgnore {

        @Test
        void noEventsProduced_returns200WithIgnoredStatus() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            PipelineResult result = service.process(sampleRequest());

            assertThat(result.httpStatus()).isEqualTo(HttpStatus.OK);
            assertThat(result.envelope().getStatus()).isEqualTo("Successful");
            assertThat(result.envelope().getResponse().getStatus()).isEqualTo(200);
            assertThat(result.envelope().getResponse().getBody()).contains("ignored");
            assertThat(result.envelope().getOrchestrations()).isEmpty();
        }

        @Test
        void noEventsProduced_doesNotCallCollector() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            service.process(sampleRequest());

            verifyNoInteractions(collectorForwardingService);
        }
    }

    // ==================== Delegation verification ====================

    @Nested
    class Delegation {

        @Test
        void delegatesToSourceAdaptorService() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of());

            service.process(sampleRequest());

            verify(sourceAdaptorService).adapt(any(InboundRequest.class));
        }

        @Test
        void delegatesToCollectorForwardingService() {
            when(sourceAdaptorService.adapt(any())).thenReturn(List.of(sampleCloudEvent()));
            when(collectorForwardingService.forward(any())).thenReturn(successResponse());

            service.process(sampleRequest());

            verify(collectorForwardingService).forward(any(CloudEventDto.class));
        }
    }
}
