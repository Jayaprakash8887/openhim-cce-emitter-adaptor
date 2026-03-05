package org.openphc.cce.emitter.exception;

/**
 * Thrown when FHIR resource parsing or transformation fails.
 *
 * <p>Covers malformed JSON, invalid FHIR structure, or unsupported resource types.
 * Mapped to HTTP 422 with error code {@code FHIR_MAPPING_ERROR}.
 */
public class FhirMappingException extends RuntimeException {

    public FhirMappingException(String message) {
        super(message);
    }

    public FhirMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
