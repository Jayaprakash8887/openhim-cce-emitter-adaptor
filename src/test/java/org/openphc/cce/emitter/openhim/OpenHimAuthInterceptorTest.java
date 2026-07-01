package org.openphc.cce.emitter.openhim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.OpenHimProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OpenHimAuthInterceptor}.
 *
 * <p>Verifies the OpenHIM challenge-response auth header computation against an independently
 * computed SHA-512 expectation, and the documented fail-soft behaviour: if the challenge fetch
 * fails the request is still forwarded, just without auth headers.
 */
@ExtendWith(MockitoExtension.class)
class OpenHimAuthInterceptorTest {

    private static final String USERNAME = "root@openhim.org";
    private static final String PASSWORD = "openhim-password";
    private static final String SERVER_SALT = "server-salt-value";
    private static final String TS = "1620000000000";

    @Mock private RestClient authRestClient;
    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private ClientHttpRequestExecution execution;
    @Mock private ClientHttpResponse clientHttpResponse;

    private OpenHimAuthInterceptor interceptor;
    private HttpRequest request;
    private HttpHeaders headers;
    private final byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        OpenHimProperties.CoreProperties core =
                new OpenHimProperties.CoreProperties(
                        "openhim", 8080, "https", "token", USERNAME, PASSWORD);
        interceptor = new OpenHimAuthInterceptor(core, authRestClient);

        request = mock(HttpRequest.class);
        headers = new HttpHeaders();
        lenient().when(request.getHeaders()).thenReturn(headers);
    }

    @SuppressWarnings("unchecked")
    private void stubChallenge(OpenHimAuthInterceptor.AuthChallenge challenge) {
        when(authRestClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), any(Object[].class)))
                .thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(OpenHimAuthInterceptor.AuthChallenge.class)).thenReturn(challenge);
    }

    private static String sha512Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void intercept_whenChallengeSucceeds_setsAllAuthHeadersAndForwards() throws Exception {
        stubChallenge(new OpenHimAuthInterceptor.AuthChallenge(SERVER_SALT, TS));
        when(execution.execute(request, body)).thenReturn(clientHttpResponse);

        ClientHttpResponse result = interceptor.intercept(request, body, execution);

        // Static headers.
        assertThat(headers.getFirst("auth-username")).isEqualTo(USERNAME);
        assertThat(headers.getFirst("auth-ts")).isEqualTo(TS);

        // auth-salt is a random 16-byte value rendered as 32 hex chars.
        String authSalt = headers.getFirst("auth-salt");
        assertThat(authSalt).isNotNull().hasSize(32).matches("[0-9a-f]+");

        // auth-token must equal SHA512(SHA512(serverSalt+password) + authSalt + ts).
        String expectedPasswordHash = sha512Hex(SERVER_SALT + PASSWORD);
        String expectedAuthToken = sha512Hex(expectedPasswordHash + authSalt + TS);
        assertThat(headers.getFirst("auth-token")).isEqualTo(expectedAuthToken);

        assertThat(result).isSameAs(clientHttpResponse);
        verify(execution).execute(request, body);
    }

    @Test
    void intercept_generatesFreshSaltPerRequest() throws Exception {
        stubChallenge(new OpenHimAuthInterceptor.AuthChallenge(SERVER_SALT, TS));
        when(execution.execute(any(), any())).thenReturn(clientHttpResponse);

        interceptor.intercept(request, body, execution);
        String firstSalt = headers.getFirst("auth-salt");

        HttpHeaders secondHeaders = new HttpHeaders();
        HttpRequest secondRequest = mock(HttpRequest.class);
        when(secondRequest.getHeaders()).thenReturn(secondHeaders);
        interceptor.intercept(secondRequest, body, execution);
        String secondSalt = secondHeaders.getFirst("auth-salt");

        assertThat(firstSalt).isNotEqualTo(secondSalt);
    }

    @Test
    void intercept_whenChallengeFetchFails_forwardsWithoutAuthHeaders() throws Exception {
        when(authRestClient.get()).thenThrow(new RuntimeException("OpenHIM unreachable"));
        when(execution.execute(request, body)).thenReturn(clientHttpResponse);

        ClientHttpResponse result = interceptor.intercept(request, body, execution);

        // Fail-soft: no auth headers set, but the request is still forwarded.
        assertThat(headers.getFirst("auth-username")).isNull();
        assertThat(headers.getFirst("auth-token")).isNull();
        assertThat(result).isSameAs(clientHttpResponse);
        verify(execution).execute(request, body);
    }
}
