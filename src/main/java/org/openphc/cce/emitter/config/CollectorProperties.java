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
     * @param token static Bearer token for authenticating with the CCE Gateway
     */
    public record AuthProperties(
            String token
    ) {}

    /**
     * Constructs the full events endpoint URL.
     *
     * @return {@code <url><eventsPath>}
     */
    public String eventsUrl() {
        return url + eventsPath;
    }
}
