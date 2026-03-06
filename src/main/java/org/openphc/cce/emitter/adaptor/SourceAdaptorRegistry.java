package org.openphc.cce.emitter.adaptor;

import org.openphc.cce.emitter.model.InboundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Registry that discovers and selects the appropriate {@link SourceAdaptor}
 * for an inbound request.
 *
 * <p>All registered adaptors are injected via Spring's {@code List<SourceAdaptor>}.
 * Selection follows first-match semantics — the first adaptor whose
 * {@link SourceAdaptor#canHandle(InboundRequest)} returns {@code true} is selected.
 *
 * <p>If no adaptor matches, {@link #findAdaptor(InboundRequest)} returns
 * {@link Optional#empty()}, and the caller silently ignores the request.
 */
@Component
public class SourceAdaptorRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceAdaptorRegistry.class);

    private final List<SourceAdaptor> adaptors;

    public SourceAdaptorRegistry(List<SourceAdaptor> adaptors) {
        this.adaptors = adaptors;
        log.info("Registered {} source adaptor(s): {}", adaptors.size(),
                adaptors.stream().map(SourceAdaptor::getSourceIdentifier).toList());
    }

    /**
     * Finds the first adaptor that can handle the given request.
     *
     * @param request the normalized inbound request
     * @return the matching adaptor, or empty if no adaptor matches
     */
    public Optional<SourceAdaptor> findAdaptor(InboundRequest request) {
        for (SourceAdaptor adaptor : adaptors) {
            if (adaptor.canHandle(request)) {
                log.debug("Selected adaptor '{}' for request", adaptor.getSourceIdentifier());
                return Optional.of(adaptor);
            }
        }
        log.debug("No adaptor matched — request will be silently ignored");
        return Optional.empty();
    }
}
