package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.CollectorProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CollectorTokenService}.
 *
 * <p>The service constructs its own {@link HttpClient} internally, so the Keycloak HTTP call is
 * exercised by replacing that client with a Mockito mock via {@link ReflectionTestUtils}. A real
 * {@link ObjectMapper} is used so the {@code TokenResponse} JSON binding is genuinely verified.
 */
class CollectorTokenServiceTest {

    private static final String TOKEN_JSON =
            "{\"access_token\":\"abc-123\",\"expires_in\":300,\"token_type\":\"Bearer\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
    }

    private CollectorTokenService serviceWith(CollectorProperties.AuthProperties auth) {
        CollectorProperties props =
                new CollectorProperties("http://collector:5001", "/v1/events", 2000, null, auth);
        CollectorTokenService service = new CollectorTokenService(props, objectMapper);
        // Replace the internally-constructed HttpClient with our mock.
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        return service;
    }

    private static CollectorProperties.AuthProperties oauthAuth() {
        return new CollectorProperties.AuthProperties(
                null, "https://kc.example.org", "cce", "emitter-client", "s3cret");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        lenient().when(resp.statusCode()).thenReturn(status);
        lenient().when(resp.body()).thenReturn(body);
        return resp;
    }

    @Nested
    class StaticAndFallbackModes {

        @Test
        void getToken_whenAuthIsNull_returnsNull() {
            CollectorTokenService service = serviceWith(null);

            assertThat(service.getToken()).isNull();
        }

        @Test
        void getToken_whenOAuth2NotConfigured_returnsStaticToken() {
            CollectorProperties.AuthProperties staticAuth =
                    new CollectorProperties.AuthProperties("static-token", null, null, null, null);
            CollectorTokenService service = serviceWith(staticAuth);

            assertThat(service.getToken()).isEqualTo("static-token");
        }

        @Test
        void getToken_whenOAuth2NotConfigured_doesNotCallKeycloak() throws Exception {
            CollectorProperties.AuthProperties staticAuth =
                    new CollectorProperties.AuthProperties("static-token", null, null, null, null);
            CollectorTokenService service = serviceWith(staticAuth);

            service.getToken();

            verify(httpClient, never()).send(any(), any());
        }
    }

    @Nested
    class OAuth2Mode {

        @Test
        void getToken_whenKeycloakReturns200_returnsAccessToken() throws Exception {
            CollectorTokenService service = serviceWith(oauthAuth());
            doReturn(response(200, TOKEN_JSON)).when(httpClient).send(any(HttpRequest.class), any());

            assertThat(service.getToken()).isEqualTo("abc-123");
        }

        @Test
        void getToken_cachesTokenAcrossCalls() throws Exception {
            CollectorTokenService service = serviceWith(oauthAuth());
            doReturn(response(200, TOKEN_JSON)).when(httpClient).send(any(HttpRequest.class), any());

            String first = service.getToken();
            String second = service.getToken();

            assertThat(first).isEqualTo(second).isEqualTo("abc-123");
            // expires_in=300 minus 30s buffer → cached, so Keycloak is hit only once.
            verify(httpClient, times(1)).send(any(HttpRequest.class), any());
        }

        @Test
        void getToken_whenKeycloakReturnsNon200_throwsCollectorTokenException() throws Exception {
            CollectorTokenService service = serviceWith(oauthAuth());
            doReturn(response(401, "unauthorized"))
                    .when(httpClient)
                    .send(any(HttpRequest.class), any());

            assertThatThrownBy(service::getToken)
                    .isInstanceOf(CollectorTokenService.CollectorTokenException.class)
                    .hasMessageContaining("401");
        }

        @Test
        void getToken_whenIOException_throwsCollectorTokenExceptionWithCause() throws Exception {
            CollectorTokenService service = serviceWith(oauthAuth());
            doThrow(new IOException("connection reset"))
                    .when(httpClient)
                    .send(any(HttpRequest.class), any());

            assertThatThrownBy(service::getToken)
                    .isInstanceOf(CollectorTokenService.CollectorTokenException.class)
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        void getToken_whenInterrupted_throwsAndSetsInterruptFlag() throws Exception {
            CollectorTokenService service = serviceWith(oauthAuth());
            doThrow(new InterruptedException("interrupted"))
                    .when(httpClient)
                    .send(any(HttpRequest.class), any());

            try {
                assertThatThrownBy(service::getToken)
                        .isInstanceOf(CollectorTokenService.CollectorTokenException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                // Clear the interrupt flag so it cannot leak into other tests.
                Thread.interrupted();
            }
        }
    }
}
