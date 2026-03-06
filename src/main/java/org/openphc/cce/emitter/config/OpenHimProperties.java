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
     * @param username Core API username
     * @param password Core API password
     */
    public record CoreProperties(
            String host,
            int apiPort,
            String username,
            String password
    ) {

        /**
         * Constructs the full OpenHIM Core API base URL.
         *
         * @return base URL in the form {@code https://<host>:<apiPort>}
         */
        public String apiUrl() {
            return "https://" + host + ":" + apiPort;
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
