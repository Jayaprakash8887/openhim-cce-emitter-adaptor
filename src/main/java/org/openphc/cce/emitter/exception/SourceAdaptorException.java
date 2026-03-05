package org.openphc.cce.emitter.exception;

/**
 * Thrown when a matched source adaptor encounters an error during processing.
 *
 * <p>This is a catch-all for adaptor-layer failures that don't fall into
 * more specific categories (e.g., FHIR parsing, patient ID extraction).
 * Mapped to HTTP 400 with error code {@code SOURCE_ADAPTOR_ERROR}.
 */
public class SourceAdaptorException extends RuntimeException {

    public SourceAdaptorException(String message) {
        super(message);
    }

    public SourceAdaptorException(String message, Throwable cause) {
        super(message, cause);
    }
}
