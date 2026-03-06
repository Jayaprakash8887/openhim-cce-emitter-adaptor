package org.openphc.cce.emitter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for mediator identity and endpoint
 * registration with OpenHIM Core.
 *
 * <p>Binds to the {@code mediator.*} namespace in {@code application.yml}.
 *
 * @param urn      unique mediator URN (e.g., {@code urn:mediator:cce-emitter-adaptor})
 * @param version  mediator semantic version
 * @param name     human-readable mediator name
 * @param endpoint endpoint identity registered with OpenHIM Core
 */
@ConfigurationProperties(prefix = "mediator")
public record MediatorProperties(
        String urn,
        String version,
        String name,
        EndpointProperties endpoint
) {

    /**
     * Mediator endpoint identity registered with OpenHIM Core.
     * The port is derived from {@code server.port} (single source of truth).
     *
     * @param host hostname or container name where the mediator runs (default: {@code emitter-adaptor})
     * @param path request path (default: {@code /inbound})
     * @param type protocol type — {@code "http"} or {@code "https"} (default: {@code http})
     */
    public record EndpointProperties(
            String host,
            String path,
            String type
    ) {}
}
