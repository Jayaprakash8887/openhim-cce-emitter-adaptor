package org.openphc.cce.emitter.adaptor;

import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.InboundRequest;

import java.util.List;

/**
 * Strategy interface for source system adaptors.
 *
 * <p>Each implementation knows how to detect requests from a specific source
 * system and transform them into CloudEvents for forwarding to the CCE Collector.
 *
 * <p>Discovery is handled by {@link SourceAdaptorRegistry}, which iterates
 * registered adaptors and selects the first whose {@link #canHandle(InboundRequest)}
 * returns {@code true}.
 */
public interface SourceAdaptor {

    /**
     * Determines whether this adaptor can handle the given inbound request.
     *
     * <p>Typically checks {@code X-OpenHIM-ClientID} or {@code X-Source-System}
     * headers against configured source system identifiers.
     *
     * @param request the normalized inbound request
     * @return {@code true} if this adaptor should process the request
     */
    boolean canHandle(InboundRequest request);

    /**
     * Transforms the inbound request into one or more CloudEvents.
     *
     * <p>For individual FHIR resources, this typically produces a single CloudEvent.
     * Bundle resources are silently ignored (out of scope for v1.0) — returns an empty list.
     *
     * @param request the normalized inbound request (must have been accepted by {@link #canHandle})
     * @return a list of CloudEvents to forward; empty if the payload should be silently ignored
     */
    List<CloudEventDto> adapt(InboundRequest request);

    /**
     * Returns the source system identifier for this adaptor (e.g., {@code "ebuzima"}).
     *
     * <p>Used as the {@code source} field in CloudEvents.
     *
     * @return the source system identifier
     */
    String getSourceIdentifier();
}
