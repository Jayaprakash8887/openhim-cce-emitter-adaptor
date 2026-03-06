package org.openphc.cce.emitter.openhim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.MediatorProperties;
import org.openphc.cce.emitter.config.OpenHimProperties;
import org.openphc.cce.emitter.openhim.model.MediatorDescriptor;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MediatorRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
class MediatorRegistrarTest {

    @Mock
    private RestClient coreApiRestClient;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private MediatorRegistrar registrar;

    @BeforeEach
    void setUp() {
        var coreProps = new OpenHimProperties.CoreProperties("localhost", 8080,
                "root@openhim.org", "openhim-password");
        var heartbeatProps = new OpenHimProperties.HeartbeatProperties(true, 10);
        var openHimProperties = new OpenHimProperties(coreProps, heartbeatProps);

        var endpointProps = new MediatorProperties.EndpointProperties(
                "emitter-adaptor", "/inbound", "http");
        var mediatorProperties = new MediatorProperties(
                "urn:mediator:cce-emitter-adaptor", "1.0.0", "CCE Emitter Adaptor", endpointProps);

        registrar = new MediatorRegistrar(coreApiRestClient, openHimProperties,
                mediatorProperties, 8082);
    }

    @Test
    @DisplayName("should POST mediator descriptor to /mediators on registration")
    void shouldPostDescriptorOnRegistration() {
        when(coreApiRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(eq("/mediators"))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(MediatorDescriptor.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        registrar.register();

        verify(coreApiRestClient).post();
        verify(requestBodyUriSpec).uri("/mediators");
        verify(requestBodySpec).body(any(MediatorDescriptor.class));
    }

    @Test
    @DisplayName("should build descriptor with correct URN, version, and name")
    void shouldBuildDescriptorWithCorrectIdentity() {
        MediatorDescriptor descriptor = registrar.buildDescriptor();

        assertThat(descriptor.getUrn()).isEqualTo("urn:mediator:cce-emitter-adaptor");
        assertThat(descriptor.getVersion()).isEqualTo("1.0.0");
        assertThat(descriptor.getName()).isEqualTo("CCE Emitter Adaptor");
    }

    @Test
    @DisplayName("should build descriptor with empty defaultChannelConfig")
    void shouldBuildDescriptorWithEmptyChannelConfig() {
        MediatorDescriptor descriptor = registrar.buildDescriptor();

        assertThat(descriptor.getDefaultChannelConfig()).isEmpty();
    }

    @Test
    @DisplayName("should build descriptor with correct endpoint")
    void shouldBuildDescriptorWithCorrectEndpoint() {
        MediatorDescriptor descriptor = registrar.buildDescriptor();

        assertThat(descriptor.getEndpoints()).hasSize(1);
        var endpoint = descriptor.getEndpoints().get(0);
        assertThat(endpoint.getName()).isEqualTo("CCE Emitter Adaptor");
        assertThat(endpoint.getHost()).isEqualTo("emitter-adaptor");
        assertThat(endpoint.getPath()).isEqualTo("/inbound");
        assertThat(endpoint.getPort()).isEqualTo(8082);
        assertThat(endpoint.isPrimary()).isTrue();
        assertThat(endpoint.getType()).isEqualTo("http");
    }

    @Test
    @DisplayName("should not throw on registration failure — non-fatal")
    void shouldNotThrowOnRegistrationFailure() {
        when(coreApiRestClient.post()).thenThrow(new RuntimeException("Connection refused"));

        assertThatNoException().isThrownBy(() -> registrar.register());
    }

    @Test
    @DisplayName("should pass descriptor object to RestClient body")
    void shouldPassDescriptorToRestClient() {
        ArgumentCaptor<MediatorDescriptor> bodyCaptor = ArgumentCaptor.forClass(MediatorDescriptor.class);

        when(coreApiRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(eq("/mediators"))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(bodyCaptor.capture())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        registrar.register();

        MediatorDescriptor descriptor = bodyCaptor.getValue();
        assertThat(descriptor.getUrn()).isEqualTo("urn:mediator:cce-emitter-adaptor");
        assertThat(descriptor.getDefaultChannelConfig()).isEmpty();
        assertThat(descriptor.getEndpoints()).hasSize(1);
    }
}
