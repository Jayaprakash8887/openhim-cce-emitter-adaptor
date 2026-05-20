package org.openphc.cce.emitter.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for source system adaptor routing.
 *
 * <p>Binds to the {@code cce.emitter.*} namespace in {@code application.yml}.
 * Each entry in {@code sources} maps a source system key (e.g., {@code "ebuzima"})
 * to its routing configuration.
 *
 * @param sources map of source system key → {@link SourceProperties}
 */
@ConfigurationProperties(prefix = "cce.emitter")
public record EmitterProperties(
        Map<String, SourceProperties> sources,
        String patientIdentifierSystem
) {

    /**
     * Configuration for a single source system.
     *
     * @param clientId the OpenHIM client ID used for header-based routing
     */
    public record SourceProperties(
            String clientId
    ) {}
}
