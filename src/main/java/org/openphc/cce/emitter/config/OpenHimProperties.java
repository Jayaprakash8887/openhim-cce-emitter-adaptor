package org.openphc.cce.emitter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for OpenHIM Core connection
 * and heartbeat scheduling.
 *
 * <p>Binds to the {@code openhim.*} namespace in {@code application.yml}.
 *
 * @param core      OpenHIM Core API connection settings
 * @param heartbeat Heartbeat scheduler settings
 */
@ConfigurationProperties(prefix = "openhim")
public record OpenHimProperties(
        CoreProperties core,
        HeartbeatProperties heartbeat
) {

    /**
     * OpenHIM Core API connection settings.
     *
     * @param host     Core hostname (default: {@code localhost})
     * @param apiPort  Core API port (default: {@code 8080})
     * @param scheme   URL scheme — {@code https} (default) or {@code http}
     * @param authType Authentication type — {@code basic} (default, for local OpenHIM) or {@code token} (standard OpenHIM challenge-response)
     * @param username Core API username
     * @param password Core API password
     */
    public record CoreProperties(
            String host,
            int apiPort,
            String scheme,
            String authType,
            String username,
            String password
    ) {

        /**
         * Constructs the full OpenHIM Core API base URL.
         *
         * @return base URL in the form {@code <scheme>://<host>:<apiPort>}
         */
        public String apiUrl() {
            String s = (scheme != null && !scheme.isBlank()) ? scheme : "https";
            return s + "://" + host + ":" + apiPort;
        }

        /**
         * Returns whether token-based (challenge-response) auth should be used.
         */
        public boolean isTokenAuth() {
            return "token".equalsIgnoreCase(authType);
        }
    }

    /**
     * Heartbeat scheduler settings.
     *
     * @param enabled         whether the heartbeat scheduler is active
     * @param intervalSeconds interval between heartbeats in seconds
     */
    public record HeartbeatProperties(
            boolean enabled,
            int intervalSeconds
    ) {}
}
