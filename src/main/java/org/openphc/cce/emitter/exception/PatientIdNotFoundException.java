package org.openphc.cce.emitter.exception;

/**
 * Thrown when a patient UPID (subject/patient reference) cannot be
 * extracted from the FHIR resource.
 *
 * <p>The patient UPID is required for the CloudEvent {@code subject} field,
 * which serves as the Kafka partition key and Compliance routing key.
 * Mapped to HTTP 400 with error code {@code PATIENT_ID_NOT_FOUND}.
 */
public class PatientIdNotFoundException extends RuntimeException {

    public PatientIdNotFoundException(String message) {
        super(message);
    }

    public PatientIdNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
