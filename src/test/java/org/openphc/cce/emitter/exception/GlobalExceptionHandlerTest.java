package org.openphc.cce.emitter.exception;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>Each test verifies that the correct HTTP status and error code are returned
 * for the corresponding exception type.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleFhirMappingException_returns422WithCorrectCode() {
        FhirMappingException ex = new FhirMappingException("Invalid FHIR resource structure");

        ResponseEntity<Map<String, Object>> response = handler.handleFhirMappingException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertErrorBody(response, "FHIR_MAPPING_ERROR", "Invalid FHIR resource structure");
    }

    @Test
    void handlePatientIdNotFoundException_returns400WithCorrectCode() {
        PatientIdNotFoundException ex = new PatientIdNotFoundException("No patient reference found in Observation");

        ResponseEntity<Map<String, Object>> response = handler.handlePatientIdNotFoundException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorBody(response, "PATIENT_ID_NOT_FOUND", "No patient reference found in Observation");
    }

    @Test
    void handleCollectorForwardingException_returns502WithCorrectCode() {
        CollectorForwardingException ex = new CollectorForwardingException("All retries exhausted for event abc-123");

        ResponseEntity<Map<String, Object>> response = handler.handleCollectorForwardingException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertErrorBody(response, "COLLECTOR_FORWARDING_ERROR", "All retries exhausted for event abc-123");
    }

    @Test
    void handleSourceAdaptorException_returns400WithCorrectCode() {
        SourceAdaptorException ex = new SourceAdaptorException("Adaptor processing failed for source: ebuzima");

        ResponseEntity<Map<String, Object>> response = handler.handleSourceAdaptorException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorBody(response, "SOURCE_ADAPTOR_ERROR", "Adaptor processing failed for source: ebuzima");
    }

    @Test
    void handleCollectorClientException_returns4xxStatusFromException() {
        CollectorClientException ex = new CollectorClientException("Validation failed: missing type field", 422);

        ResponseEntity<Map<String, Object>> response = handler.handleCollectorClientException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertErrorBody(response, "COLLECTOR_CLIENT_ERROR", "Validation failed: missing type field");
    }

    @Test
    void handleCollectorClientException_fallsBackTo400ForUnknownStatusCode() {
        CollectorClientException ex = new CollectorClientException("Unknown error", 499);

        ResponseEntity<Map<String, Object>> response = handler.handleCollectorClientException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorBody(response, "COLLECTOR_CLIENT_ERROR", "Unknown error");
    }

    @Test
    void allErrorResponses_includeTimestamp() {
        FhirMappingException ex = new FhirMappingException("test");

        ResponseEntity<Map<String, Object>> response = handler.handleFhirMappingException(ex);

        assertThat(response.getBody()).containsKey("timestamp");
        assertThat(response.getBody().get("timestamp").toString()).isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private void assertErrorBody(ResponseEntity<Map<String, Object>> response, String expectedCode, String expectedMessage) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("error");

        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertThat(error).containsEntry("code", expectedCode);
        assertThat(error).containsEntry("message", expectedMessage);
    }
}
