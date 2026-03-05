package org.openphc.cce.emitter.exception;

/**
 * Thrown when the CCE Collector responds with a non-retryable client
 * error (4xx status code).
 *
 * <p>This exception is explicitly excluded from retry via
 * {@code @Retryable(noRetryFor = CollectorClientException.class)}.
 * The original Collector status code and message are preserved for
 * inclusion in the error response.
 */
public class CollectorClientException extends RuntimeException {

    private final int statusCode;

    public CollectorClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public CollectorClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Returns the HTTP status code from the Collector's error response.
     *
     * @return HTTP status code (4xx)
     */
    public int getStatusCode() {
        return statusCode;
    }
}
