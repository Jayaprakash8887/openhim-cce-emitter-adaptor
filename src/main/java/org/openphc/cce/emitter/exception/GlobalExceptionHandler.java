package org.openphc.cce.emitter.exception;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Centralized exception handler that maps custom exceptions to consistent
 * JSON error responses.
 *
 * <p>Error responses follow a simple structure suitable for wrapping inside
 * the OpenHIM mediator envelope by {@code OpenHimResponseWrapper} in the
 * controller layer:
 * <pre>
 * {
 *   "error": {
 *     "code": "ERROR_CODE",
 *     "message": "Human-readable description"
 *   }
 * }
 * </pre>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FhirMappingException.class)
    public ResponseEntity<Map<String, Object>> handleFhirMappingException(FhirMappingException ex) {
        log.warn("FHIR mapping error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "FHIR_MAPPING_ERROR", ex.getMessage());
    }

    @ExceptionHandler(PatientIdNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePatientIdNotFoundException(PatientIdNotFoundException ex) {
        log.warn("Patient ID not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "PATIENT_ID_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(CollectorForwardingException.class)
    public ResponseEntity<Map<String, Object>> handleCollectorForwardingException(CollectorForwardingException ex) {
        log.error("Collector forwarding failed after retries: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_GATEWAY, "COLLECTOR_FORWARDING_ERROR", ex.getMessage());
    }

    @ExceptionHandler(CollectorClientException.class)
    public ResponseEntity<Map<String, Object>> handleCollectorClientException(CollectorClientException ex) {
        log.warn("Collector client error ({}): {}", ex.getStatusCode(), ex.getMessage());
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return buildErrorResponse(status, "COLLECTOR_CLIENT_ERROR", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = Map.of(
                "error", Map.of(
                        "code", code,
                        "message", message
                ),
                "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
        );
        return ResponseEntity.status(status).body(body);
    }
}
