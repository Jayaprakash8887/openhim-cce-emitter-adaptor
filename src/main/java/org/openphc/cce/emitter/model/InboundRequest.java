package org.openphc.cce.emitter.model;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wraps the raw inbound HTTP request into a normalized model with
 * case-insensitive header access and structured metadata.
 *
 * <p>Created via the static {@link #from(String, Map, HttpServletRequest)} factory.
 * Headers are stored with lowercase keys for consistent lookup.
 */
public class InboundRequest {

    private final String body;
    private final Map<String, String> headers;
    private final String path;

    private InboundRequest(String body, Map<String, String> headers, String path) {
        this.body = body;
        this.headers = headers;
        this.path = path;
    }

    /**
     * Factory method to create an {@link InboundRequest} from Spring MVC parameters.
     *
     * <p>All header keys are normalized to lowercase for consistent retrieval.
     *
     * @param body    raw request body
     * @param headers HTTP headers (keys will be lowercased)
     * @param request the servlet request (for path extraction)
     * @return a new InboundRequest instance
     */
    public static InboundRequest from(String body, Map<String, String> headers, HttpServletRequest request) {
        return new InboundRequest(body, normalizeHeaders(headers), request.getRequestURI());
    }

    /**
     * Overloaded factory for testing — creates an InboundRequest without an HttpServletRequest.
     *
     * @param body    raw request body
     * @param headers HTTP headers (keys will be lowercased)
     * @param path    request path
     * @return a new InboundRequest instance
     */
    public static InboundRequest from(String body, Map<String, String> headers, String path) {
        return new InboundRequest(body, normalizeHeaders(headers), path);
    }

    /**
     * Normalizes header keys to lowercase for consistent case-insensitive lookup.
     */
    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        return Collections.unmodifiableMap(
                headers.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toLowerCase(),
                                Map.Entry::getValue,
                                (existing, replacement) -> existing
                        ))
        );
    }

    /**
     * Retrieves a header value by name (case-insensitive).
     *
     * @param name header name
     * @return an Optional containing the header value, or empty if not present
     */
    public Optional<String> getHeader(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase()));
    }

    /**
     * Checks if the request body likely contains a FHIR resource by looking
     * for the {@code "resourceType"} key in the JSON body.
     *
     * @return true if the body appears to contain a FHIR resource
     */
    public boolean containsFhirResource() {
        return body != null && body.contains("\"resourceType\"");
    }

    /** @return the raw request body */
    public String getBody() {
        return body;
    }

    /** @return unmodifiable map of headers with lowercase keys */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** @return the request URI path */
    public String getPath() {
        return path;
    }
}
