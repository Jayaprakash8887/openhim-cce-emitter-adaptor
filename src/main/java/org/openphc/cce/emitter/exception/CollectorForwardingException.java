package org.openphc.cce.emitter.exception;

/**
 * Thrown when forwarding a CloudEvent to the CCE Collector fails with
 * a retryable error (5xx response or network timeout).
 *
 * <p>This exception is the retry trigger for {@code @Retryable} in
 * {@code CollectorForwardingService}. After all retry attempts are
 * exhausted, it propagates up and is mapped to HTTP 502 with error
 * code {@code COLLECTOR_FORWARDING_ERROR}.
 */
public class CollectorForwardingException extends RuntimeException {

    public CollectorForwardingException(String message) {
        super(message);
    }

    public CollectorForwardingException(String message, Throwable cause) {
        super(message, cause);
    }
}
