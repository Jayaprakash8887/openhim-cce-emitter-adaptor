package org.openphc.cce.emitter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for the CCE Collector target.
 *
 * <p>Binds to the {@code cce.collector.*} namespace in {@code application.yml}.
 *
 * @param url        Collector base URL (e.g., {@code http://localhost:5001})
 * @param eventsPath events endpoint path (default: {@code /v1/events})
 * @param timeout    HTTP connect + read timeout in milliseconds
 * @param retry      retry behaviour for transient Collector failures
 * @param auth       authentication configuration for outbound Collector calls
 */
@ConfigurationProperties(prefix = "cce.collector")
public record CollectorProperties(
        String url,
        String eventsPath,
        int timeout,
        RetryProperties retry,
        AuthProperties auth
) {

    /**
     * Retry configuration for Collector forwarding (5xx / timeout errors).
     *
     * @param maxAttempts maximum number of retry attempts
     * @param backoffMs   initial backoff delay in milliseconds (doubles per retry)
     */
    public record RetryProperties(
            int maxAttempts,
            long backoffMs
    ) {}

    /**
     * Authentication configuration for outbound Collector / CCE Gateway calls.
     *
     * <p>The Emitter uses its own credentials to authenticate with the CCE Gateway,
     * separate from the inbound OpenHIM channel authentication.
     *
     * <p>Two modes are supported:
     * <ul>
     *   <li><b>Static token</b> — set {@code token} directly (local dev)</li>
     *   <li><b>OAuth2 client_credentials</b> — set {@code keycloak-host}, {@code realm},
     *       {@code client-id}, and {@code client-secret}; token is fetched at runtime</li>
     * </ul>
     *
     * <p>If Keycloak properties are present, OAuth2 takes precedence over the static token.
     *
     * @param token        static Bearer token (local dev fallback)
     * @param keycloakHost Keycloak base URL (e.g., {@code https://keycloak.cce.mdtlabs.org})
     * @param realm        Keycloak realm name (e.g., {@code cce})
     * @param clientId     OAuth2 client ID for client_credentials grant
     * @param clientSecret OAuth2 client secret
     */
    public record AuthProperties(
            String token,
            String keycloakHost,
            String realm,
            String clientId,
            String clientSecret
    ) {
        /**
         * Returns {@code true} if Keycloak OAuth2 properties are configured.
         */
        public boolean isOAuth2Configured() {
            return keycloakHost != null && !keycloakHost.isBlank()
                    && realm != null && !realm.isBlank()
                    && clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }

        /**
         * Returns the Keycloak token endpoint URL.
         */
        public String tokenEndpoint() {
            return keycloakHost + "/realms/" + realm + "/protocol/openid-connect/token";
        }
    }

    /**
     * Constructs the full events endpoint URL.
     *
     * @return {@code <url><eventsPath>}
     */
    public String eventsUrl() {
        return url + eventsPath;
    }
}
