package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openphc.cce.emitter.config.CollectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Manages OAuth2 access tokens for authenticating with the CCE Collector via Keycloak.
 *
 * <p>Uses the {@code client_credentials} grant type. Tokens are cached and refreshed
 * automatically when they expire (with a safety buffer).
 *
 * <p>If Keycloak is not configured, falls back to the static token from configuration.
 */
@Service
public class CollectorTokenService {

    private static final Logger log = LoggerFactory.getLogger(CollectorTokenService.class);

    /** Refresh token 30 seconds before actual expiry to avoid edge-case failures. */
    private static final long EXPIRY_BUFFER_SECONDS = 30;

    private final CollectorProperties.AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.MIN;

    public CollectorTokenService(CollectorProperties collectorProperties, ObjectMapper objectMapper) {
        this.authProperties = collectorProperties.auth();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
    }

    /**
     * Returns a valid Bearer token for authenticating with the CCE Collector.
     *
     * <p>If OAuth2 (Keycloak) is configured, fetches/caches a token via {@code client_credentials}.
     * Otherwise, returns the static token from configuration.
     *
     * @return Bearer token string
     * @throws CollectorTokenException if token fetch fails
     */
    public String getToken() {
        if (authProperties == null || !authProperties.isOAuth2Configured()) {
            return authProperties != null ? authProperties.token() : null;
        }
        return getOAuth2Token();
    }

    private synchronized String getOAuth2Token() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        log.debug("Fetching new OAuth2 token from Keycloak: {}", authProperties.tokenEndpoint());

        String formBody = "grant_type=" + encode("client_credentials")
                + "&client_id=" + encode(authProperties.clientId())
                + "&client_secret=" + encode(authProperties.clientSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(authProperties.tokenEndpoint()))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Keycloak token request failed: HTTP {} — {}", response.statusCode(), response.body());
                throw new CollectorTokenException(
                        "Keycloak token request failed with HTTP " + response.statusCode());
            }

            TokenResponse tokenResponse = objectMapper.readValue(response.body(), TokenResponse.class);
            cachedToken = tokenResponse.accessToken();
            tokenExpiry = Instant.now().plusSeconds(tokenResponse.expiresIn() - EXPIRY_BUFFER_SECONDS);

            log.info("OAuth2 token acquired, expires in {}s", tokenResponse.expiresIn());

            return cachedToken;

        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to fetch OAuth2 token from Keycloak: {}", ex.getMessage());
            throw new CollectorTokenException("Failed to fetch OAuth2 token: " + ex.getMessage(), ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType
    ) {}

    public static class CollectorTokenException extends RuntimeException {
        public CollectorTokenException(String message) { super(message); }
        public CollectorTokenException(String message, Throwable cause) { super(message, cause); }
    }
}
