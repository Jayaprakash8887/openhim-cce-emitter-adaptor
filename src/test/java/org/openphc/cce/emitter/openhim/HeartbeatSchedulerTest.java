package org.openphc.cce.emitter.openhim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.OpenHimProperties;
import org.openphc.cce.emitter.openhim.model.HeartbeatRequest;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HeartbeatScheduler}.
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatSchedulerTest {

    @Mock
    private RestClient coreApiRestClient;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private HeartbeatScheduler scheduler;

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

        scheduler = new HeartbeatScheduler(coreApiRestClient, openHimProperties);
    }

    @Test
    @DisplayName("should POST heartbeat with uptime to correct URI")
    void shouldPostHeartbeatToCorrectUri() {
        when(coreApiRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(eq("/mediators/{urn}/heartbeat"),
                eq("urn:mediator:cce-emitter-adaptor"))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(HeartbeatRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        scheduler.sendHeartbeat();

        verify(requestBodyUriSpec).uri("/mediators/{urn}/heartbeat",
                "urn:mediator:cce-emitter-adaptor");
    }

    @Test
    @DisplayName("should include uptime in heartbeat request body")
    void shouldIncludeUptimeInRequest() {
        ArgumentCaptor<HeartbeatRequest> captor = ArgumentCaptor.forClass(HeartbeatRequest.class);

        when(coreApiRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(eq("/mediators/{urn}/heartbeat"),
                eq("urn:mediator:cce-emitter-adaptor"))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(captor.capture())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        scheduler.sendHeartbeat();

        HeartbeatRequest captured = captor.getValue();
        assertThat(captured.uptime()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("should not throw on heartbeat failure — non-fatal")
    void shouldNotThrowOnHeartbeatFailure() {
        when(coreApiRestClient.post()).thenThrow(new RuntimeException("Connection refused"));

        assertThatNoException().isThrownBy(() -> scheduler.sendHeartbeat());
    }

    @Test
    @DisplayName("should report positive uptime")
    void shouldReportPositiveUptime() {
        assertThat(scheduler.getUptimeMs()).isGreaterThanOrEqualTo(0);
    }
}
