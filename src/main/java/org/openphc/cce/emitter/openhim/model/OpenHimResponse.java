package org.openphc.cce.emitter.openhim.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * OpenHIM mediator response envelope — wraps the adaptor's actual response
 * in the {@code application/json+openhim} format expected by OpenHIM Core.
 *
 * <p>OpenHIM Core uses this envelope to populate the transaction log,
 * display orchestration details, and determine the final transaction status.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
 *   "status": "Successful",
 *   "response": {
 *     "status": 202,
 *     "headers": {"Content-Type": "application/json"},
 *     "body": "...",
 *     "timestamp": "2026-02-25T08:00:00Z"
 *   },
 *   "orchestrations": []
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenHimResponse {

    /** Mediator URN — serialized as {@code "x-mediator-urn"} per OpenHIM spec. */
    @JsonProperty("x-mediator-urn")
    private String mediatorUrn;

    /**
     * Overall transaction status: {@code "Successful"}, {@code "Failed"},
     * {@code "Completed"}, or {@code "Completed with error(s)"}.
     */
    private String status;

    /** The actual HTTP response details returned by the mediator. */
    private Response response;

    /** Orchestration entries recording downstream service calls made by the mediator. */
    @Builder.Default
    private List<Orchestration> orchestrations = List.of();

    /**
     * The mediator's HTTP response details — populated in the OpenHIM transaction log.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {

        /** HTTP status code (e.g., {@code 202}). */
        private int status;

        /** Response headers. */
        private Map<String, String> headers;

        /** Response body as a JSON string. */
        private String body;

        /** ISO-8601 UTC timestamp of the response. */
        private String timestamp;
    }

    /**
     * Orchestration entry — records a downstream service call made by the mediator
     * (e.g., a call to the CCE Collector).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Orchestration {

        /** Human-readable name for the orchestration (e.g., {@code "Forward to CCE Collector"}). */
        private String name;

        /** Details of the outbound request. */
        private Request request;

        /** Details of the response received. */
        private OrchestrationResponse response;

        /**
         * Outbound request details.
         */
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Request {

            /** HTTP method (e.g., {@code "POST"}). */
            private String method;

            /** Request path or full URL. */
            private String path;

            /** Request body (JSON string). */
            private String body;

            /** ISO-8601 UTC timestamp of the request. */
            private String timestamp;

            /** Request headers. */
            private Map<String, String> headers;
        }

        /**
         * Response received from the orchestrated service.
         */
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class OrchestrationResponse {

            /** HTTP status code returned by the orchestrated service. */
            private int status;

            /** Response body (JSON string). */
            private String body;

            /** ISO-8601 UTC timestamp of the response. */
            private String timestamp;

            /** Response headers. */
            private Map<String, String> headers;
        }
    }
}
