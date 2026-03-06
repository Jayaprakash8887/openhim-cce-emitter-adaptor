package org.openphc.cce.emitter.openhim.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenHIM mediator registration descriptor — JSON model sent to {@code POST /mediators}
 * on the OpenHIM Core API to register this mediator.
 *
 * <p>Follows the OpenHIM mediator specification. {@code defaultChannelConfig} is intentionally
 * empty because the eBUZIMA EMR already has an existing OpenHIM channel — the OpenHIM admin
 * adds this mediator as a secondary route on that channel.
 *
 * @see <a href="https://openhim.org/docs/configuration/mediators">OpenHIM Mediator Docs</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediatorDescriptor {

    /** Unique mediator URN (e.g., {@code "urn:mediator:cce-emitter-adaptor"}). */
    private String urn;

    /** Mediator semantic version (e.g., {@code "1.0.0"}). */
    private String version;

    /** Human-readable mediator name. */
    private String name;

    /** Mediator description. */
    private String description;

    /**
     * Default channel configuration — intentionally empty.
     * The eBUZIMA channel already exists; this mediator is added as a secondary route.
     */
    @Builder.Default
    private List<Object> defaultChannelConfig = List.of();

    /** Mediator endpoint definitions. */
    private List<Endpoint> endpoints;

    /**
     * Mediator endpoint descriptor — describes how OpenHIM Core can reach this mediator.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Endpoint {

        /** Endpoint display name. */
        private String name;

        /** Hostname or container name where the mediator runs. */
        private String host;

        /** Request path (e.g., {@code "/inbound"}). */
        private String path;

        /** Listen port (e.g., {@code 8082}). */
        private int port;

        /** Whether this is the primary endpoint. */
        private boolean primary;

        /** Protocol type ({@code "http"} or {@code "https"}). */
        private String type;
    }
}
