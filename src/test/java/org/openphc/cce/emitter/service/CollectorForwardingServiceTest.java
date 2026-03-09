package org.openphc.cce.emitter.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.CollectorProperties;
import org.openphc.cce.emitter.exception.CollectorClientException;
import org.openphc.cce.emitter.exception.CollectorForwardingException;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CollectorForwardingService}.
 *
 * <p>Tests the forwarding logic with a mocked {@link RestClient}.
 * Does NOT test Spring Retry itself (that requires {@code @SpringBootTest}).
 */
@ExtendWith(MockitoExtension.class)
class CollectorForwardingServiceTest {

    @Mock
    private RestClient collectorRestClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private CollectorForwardingService service;

    private static final String EVENTS_PATH = "/v1/events";

    @BeforeEach
    void setUp() {
        CollectorProperties properties = new CollectorProperties(
                "http://localhost:5001",
                EVENTS_PATH,
                5000,
                new CollectorProperties.RetryProperties(3, 1000L),
                new CollectorProperties.AuthProperties("test-token")
        );

        service = new CollectorForwardingService(collectorRestClient, properties);
    }

    private void stubRestClientChain() {
        when(collectorRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(CloudEventDto.class))).thenReturn(requestBodySpec);
    }

    private CloudEventDto sampleEvent() {
        return CloudEventDto.builder()
                .specversion("1.0")
                .id("evt-001")
                .source("ebuzima")
                .type("Encounter")
                .subject("260225-0002-5501")
                .datacontenttype("application/fhir+json")
                .build();
    }

    // ==================== 202 Accepted ====================

    @Nested
    class AcceptedResponse {

        @Test
        void returns202_parsesSuccessResponse() {
            stubRestClientChain();
            CollectorResponse expected = new CollectorResponse(
                    new CollectorResponse.DataPayload("evt-001", "accepted", "corr-1", "2026-03-01T10:00:00Z"),
                    null);
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(expected);

            CollectorResponse response = service.forward(sampleEvent());

            assertThat(response).isNotNull();
            assertThat(response.data()).isNotNull();
            assertThat(response.data().status()).isEqualTo("accepted");
            assertThat(response.data().eventId()).isEqualTo("evt-001");
        }

        @Test
        void callsCollectorWithCorrectEventsPath() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(null);

            service.forward(sampleEvent());

            verify(requestBodyUriSpec).uri(EVENTS_PATH);
        }

        @Test
        void serializesEventAsJsonBody() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(null);

            service.forward(sampleEvent());

            verify(requestBodySpec).body(any(CloudEventDto.class));
        }
    }

    // ==================== 200 Duplicate ====================

    @Nested
    class DuplicateResponse {

        @Test
        void returns200Duplicate_parsesAsSuccess() {
            stubRestClientChain();
            CollectorResponse expected = new CollectorResponse(
                    new CollectorResponse.DataPayload("evt-001", "duplicate", null, null),
                    null);
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(expected);

            CollectorResponse response = service.forward(sampleEvent());

            assertThat(response.data().status()).isEqualTo("duplicate");
        }
    }

    // ==================== 400 Client Error ====================

    @Nested
    class ClientErrorResponse {

        @Test
        void returns400_throwsCollectorClientException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(
                    HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST,
                            "Bad Request",
                            HttpHeaders.EMPTY,
                            "{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"type is required\"}}".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8));

            assertThatThrownBy(() -> service.forward(sampleEvent()))
                    .isInstanceOf(CollectorClientException.class)
                    .satisfies(ex -> {
                        CollectorClientException cce = (CollectorClientException) ex;
                        assertThat(cce.getStatusCode()).isEqualTo(400);
                    });
        }

        @Test
        void returns422_throwsCollectorClientException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(
                    HttpClientErrorException.create(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unprocessable",
                            HttpHeaders.EMPTY,
                            "{}".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8));

            assertThatThrownBy(() -> service.forward(sampleEvent()))
                    .isInstanceOf(CollectorClientException.class)
                    .satisfies(ex -> {
                        CollectorClientException cce = (CollectorClientException) ex;
                        assertThat(cce.getStatusCode()).isEqualTo(422);
                    });
        }
    }

    // ==================== 5xx Server Error ====================

    @Nested
    class ServerErrorResponse {

        @Test
        void returns503_throwsCollectorForwardingException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(
                    HttpServerErrorException.create(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Service Unavailable",
                            HttpHeaders.EMPTY,
                            "{}".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8));

            assertThatThrownBy(() -> service.forward(sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("503");
        }

        @Test
        void returns500_throwsCollectorForwardingException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(
                    HttpServerErrorException.create(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Server Error",
                            HttpHeaders.EMPTY,
                            "{}".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8));

            assertThatThrownBy(() -> service.forward(sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("500");
        }
    }

    // ==================== Network timeout ====================

    @Nested
    class NetworkTimeout {

        @Test
        void connectionTimeout_throwsCollectorForwardingException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(
                    new ResourceAccessException("I/O error",
                            new SocketTimeoutException("Read timed out")));

            assertThatThrownBy(() -> service.forward(sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("unreachable");
        }
    }

    // ==================== Recovery ====================

    @Nested
    class Recovery {

        @Test
        void recover_rethrowsCollectorForwardingException() {
            CollectorForwardingException original =
                    new CollectorForwardingException("Collector returned 503");

            assertThatThrownBy(() -> service.recover(original, sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("all retries");
        }

        @Test
        void recover_handlesNullEvent() {
            CollectorForwardingException original =
                    new CollectorForwardingException("timeout");

            assertThatThrownBy(() -> service.recover(original, null))
                    .isInstanceOf(CollectorForwardingException.class);
        }
    }

    // ==================== Null response ====================

    @Nested
    class EdgeCases {

        @Test
        void nullResponse_returnsDefaultCollectorResponse() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(null);

            CollectorResponse response = service.forward(sampleEvent());

            assertThat(response).isNotNull();
            assertThat(response.data()).isNotNull();
            assertThat(response.data().status()).isEqualTo("accepted");
        }
    }
}
