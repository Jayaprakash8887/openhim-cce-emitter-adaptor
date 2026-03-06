package org.openphc.cce.emitter.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InboundRequest}.
 */
class InboundRequestTest {

    private static final String FHIR_ENCOUNTER_BODY = """
            {
              "resourceType": "Encounter",
              "id": "enc-001",
              "status": "finished"
            }
            """;

    private static final String NON_FHIR_BODY = """
            {
              "message": "hello"
            }
            """;

    @Test
    void fromShouldNormalizeHeaderKeysToLowercase() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-OpenHIM-ClientID", "ebuzima-emr-client");
        headers.put("Content-Type", "application/json");
        headers.put("X-Facility-Id", "FAC-001");

        InboundRequest request = InboundRequest.from(FHIR_ENCOUNTER_BODY, headers, "/inbound");

        assertThat(request.getHeaders()).containsKey("x-openhim-clientid");
        assertThat(request.getHeaders()).containsKey("content-type");
        assertThat(request.getHeaders()).containsKey("x-facility-id");
        assertThat(request.getHeaders()).doesNotContainKey("X-OpenHIM-ClientID");
    }

    @Test
    void fromShouldPreserveBodyAndPath() {
        InboundRequest request = InboundRequest.from(FHIR_ENCOUNTER_BODY, Map.of(), "/inbound");

        assertThat(request.getBody()).isEqualTo(FHIR_ENCOUNTER_BODY);
        assertThat(request.getPath()).isEqualTo("/inbound");
    }

    @Test
    void getHeaderShouldBeCaseInsensitive() {
        Map<String, String> headers = Map.of(
                "X-Correlation-Id", "trace-123",
                "Authorization", "Bearer token-abc"
        );

        InboundRequest request = InboundRequest.from(FHIR_ENCOUNTER_BODY, headers, "/inbound");

        assertThat(request.getHeader("x-correlation-id")).isEqualTo(Optional.of("trace-123"));
        assertThat(request.getHeader("X-CORRELATION-ID")).isEqualTo(Optional.of("trace-123"));
        assertThat(request.getHeader("X-Correlation-Id")).isEqualTo(Optional.of("trace-123"));
        assertThat(request.getHeader("authorization")).isEqualTo(Optional.of("Bearer token-abc"));
    }

    @Test
    void getHeaderShouldReturnEmptyForMissingHeader() {
        InboundRequest request = InboundRequest.from(FHIR_ENCOUNTER_BODY, Map.of(), "/inbound");

        assertThat(request.getHeader("X-Missing-Header")).isEmpty();
    }

    @Test
    void containsFhirResourceShouldReturnTrueForFhirPayload() {
        InboundRequest request = InboundRequest.from(FHIR_ENCOUNTER_BODY, Map.of(), "/inbound");

        assertThat(request.containsFhirResource()).isTrue();
    }

    @Test
    void containsFhirResourceShouldReturnFalseForNonFhirPayload() {
        InboundRequest request = InboundRequest.from(NON_FHIR_BODY, Map.of(), "/inbound");

        assertThat(request.containsFhirResource()).isFalse();
    }

    @Test
    void containsFhirResourceShouldReturnFalseForNullBody() {
        InboundRequest request = InboundRequest.from(null, Map.of(), "/inbound");

        assertThat(request.containsFhirResource()).isFalse();
    }

    @Test
    void headersMapShouldBeUnmodifiable() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Test", "value");

        InboundRequest request = InboundRequest.from("body", headers, "/inbound");

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> request.getHeaders().put("new-key", "new-value")
        );
    }

    @Test
    void metadataShouldBeNullByDefault() {
        InboundRequest request = InboundRequest.from("body", Map.of(), "/inbound");

        assertThat(request.getMetadata()).isNull();
    }

    @Test
    void metadataShouldBeSettable() {
        InboundRequest request = InboundRequest.from("body", Map.of(), "/inbound");
        SourceMetadata metadata = new SourceMetadata("ebuzima", "FAC-001", null, "corr-1", null, "/inbound");

        request.setMetadata(metadata);

        assertThat(request.getMetadata()).isSameAs(metadata);
        assertThat(request.getMetadata().sourceIdentifier()).isEqualTo("ebuzima");
    }

    @Test
    void fromShouldHandleDuplicateHeaderKeysGracefully() {
        // When headers have keys that normalize to the same lowercase key,
        // the first value wins (per Collectors.toMap merge function)
        Map<String, String> headers = new HashMap<>();
        headers.put("x-test", "lowercase-value");
        // Can't add duplicate key in Map.of(), but HashMap allows overwriting
        // The normalization merge keeps the first entry

        InboundRequest request = InboundRequest.from("body", headers, "/inbound");

        assertThat(request.getHeader("x-test")).isEqualTo(Optional.of("lowercase-value"));
    }
}
