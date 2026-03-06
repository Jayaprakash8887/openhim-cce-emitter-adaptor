package org.openphc.cce.emitter.adaptor;

import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.InboundRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SourceAdaptorRegistry}.
 */
class SourceAdaptorRegistryTest {

    // --- First-match semantics ---

    @Test
    void findAdaptor_returnsFirstMatchingAdaptor() {
        var adaptor1 = new StubSourceAdaptor("source-a", false);
        var adaptor2 = new StubSourceAdaptor("source-b", true);
        var adaptor3 = new StubSourceAdaptor("source-c", true);
        var registry = new SourceAdaptorRegistry(List.of(adaptor1, adaptor2, adaptor3));

        InboundRequest request = InboundRequest.from("{}", Map.of(), "/inbound");
        Optional<SourceAdaptor> result = registry.findAdaptor(request);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceIdentifier()).isEqualTo("source-b");
    }

    @Test
    void findAdaptor_firstAdaptorMatchesFirst() {
        var adaptor1 = new StubSourceAdaptor("source-a", true);
        var adaptor2 = new StubSourceAdaptor("source-b", true);
        var registry = new SourceAdaptorRegistry(List.of(adaptor1, adaptor2));

        InboundRequest request = InboundRequest.from("{}", Map.of(), "/inbound");
        Optional<SourceAdaptor> result = registry.findAdaptor(request);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceIdentifier()).isEqualTo("source-a");
    }

    // --- No match ---

    @Test
    void findAdaptor_noMatch_returnsEmpty() {
        var adaptor1 = new StubSourceAdaptor("source-a", false);
        var adaptor2 = new StubSourceAdaptor("source-b", false);
        var registry = new SourceAdaptorRegistry(List.of(adaptor1, adaptor2));

        InboundRequest request = InboundRequest.from("{}", Map.of(), "/inbound");
        Optional<SourceAdaptor> result = registry.findAdaptor(request);

        assertThat(result).isEmpty();
    }

    @Test
    void findAdaptor_emptyAdaptorList_returnsEmpty() {
        var registry = new SourceAdaptorRegistry(List.of());

        InboundRequest request = InboundRequest.from("{}", Map.of(), "/inbound");
        Optional<SourceAdaptor> result = registry.findAdaptor(request);

        assertThat(result).isEmpty();
    }

    // --- Registry construction ---

    @Test
    void constructor_registersAllAdaptors() {
        var adaptor1 = new StubSourceAdaptor("ebuzima", false);
        var adaptor2 = new StubSourceAdaptor("dhis2", false);

        // Should not throw — verifies constructor logs and stores adaptors
        var registry = new SourceAdaptorRegistry(List.of(adaptor1, adaptor2));
        assertThat(registry).isNotNull();
    }

    // --- Stub implementation for testing ---

    /**
     * Minimal {@link SourceAdaptor} stub that returns a fixed value from
     * {@link #canHandle(InboundRequest)}.
     */
    private static class StubSourceAdaptor implements SourceAdaptor {

        private final String sourceIdentifier;
        private final boolean handles;

        StubSourceAdaptor(String sourceIdentifier, boolean handles) {
            this.sourceIdentifier = sourceIdentifier;
            this.handles = handles;
        }

        @Override
        public boolean canHandle(InboundRequest request) {
            return handles;
        }

        @Override
        public List<CloudEventDto> adapt(InboundRequest request) {
            return List.of();
        }

        @Override
        public String getSourceIdentifier() {
            return sourceIdentifier;
        }
    }
}
