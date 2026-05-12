package org.openphc.cce.emitter.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.openphc.cce.emitter.config.CollectorProperties;
import org.openphc.cce.emitter.exception.CollectorClientException;
import org.openphc.cce.emitter.exception.CollectorForwardingException;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

/**
 * Forwards CloudEvents to the CCE Collector via HTTP POST with retry support.
 *
 * <p>Retry behaviour:
 * <ul>
 *   <li>5xx responses and network timeouts trigger retry with exponential backoff</li>
 *   <li>4xx responses are non-retryable (Collector client errors)</li>
 *   <li>After all retries are exhausted, a {@link CollectorForwardingException} propagates</li>
 * </ul>
 *
 * <p>Collector response codes:
 * <ul>
 *   <li>202 Accepted — event accepted for processing</li>
 *   <li>200 OK — duplicate event (already received, idempotent success)</li>
 *   <li>400/422 — validation error, non-retryable</li>
 *   <li>5xx — server error, retryable</li>
 * </ul>
 */
@Service
public class CollectorForwardingService {

    private static final Logger log = LoggerFactory.getLogger(CollectorForwardingService.class);

    private static final CollectorResponse DEFAULT_RESPONSE = new CollectorResponse(
            new CollectorResponse.DataPayload(null, "accepted", null, null), null);

    private final RestClient collectorRestClient;
    private final CollectorProperties collectorProperties;
    private final Timer collectorLatencyTimer;
    private final Counter retriesCounter;
    private final Counter rejectedCounter;

    public CollectorForwardingService(
            @Qualifier("collectorRestClient") RestClient collectorRestClient,
            CollectorProperties collectorProperties,
            MeterRegistry meterRegistry) {
        this.collectorRestClient = collectorRestClient;
        this.collectorProperties = collectorProperties;
        this.collectorLatencyTimer = Timer.builder("cce.emitter.collector.latency")
                .description("Collector forwarding latency")
                .register(meterRegistry);
        this.retriesCounter = Counter.builder("cce.emitter.collector.retries")
                .description("Collector retry attempts")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("cce.emitter.events.rejected")
                .description("Events rejected by Collector")
                .tag("source", "collector")
                .tag("reason", "client_error")
                .register(meterRegistry);
    }

    /**
     * Forwards a CloudEvent to the CCE Collector.
     *
     * <p>Retries on 5xx/timeout with exponential backoff. Does not retry on 4xx.
     *
     * @param event the CloudEvent to forward
     * @return the Collector's response
     * @throws CollectorForwardingException on retryable failures (5xx/timeout)
     * @throws CollectorClientException on non-retryable failures (4xx)
     */
    @Retryable(
            retryFor = CollectorForwardingException.class,
            noRetryFor = CollectorClientException.class,
            maxAttemptsExpression = "${cce.collector.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${cce.collector.retry.backoff-ms:1000}",
                    multiplier = 2.0
            )
    )
    public CollectorResponse forward(CloudEventDto event) {
        String eventsPath = collectorProperties.eventsPath();

        log.debug("Forwarding CloudEvent id={} to Collector at {}", event.getId(), eventsPath);

        return collectorLatencyTimer.record(() -> doForward(event, eventsPath));
    }

    private CollectorResponse doForward(CloudEventDto event, String eventsPath) {
        try {
            CollectorResponse response = collectorRestClient.post()
                    .uri(eventsPath)
                    .body(event)
                    .retrieve()
                    .body(CollectorResponse.class);

            if (response == null) {
                response = DEFAULT_RESPONSE;
            }

            String status = response.data() != null ? response.data().status() : "accepted";
            log.info("Collector accepted CloudEvent id={} — status={}", event.getId(), status);

            return response;

        } catch (HttpClientErrorException ex) {
            // 4xx — non-retryable
            int statusCode = ex.getStatusCode().value();
            String body = ex.getResponseBodyAsString();
            log.warn("Collector rejected CloudEvent id={} — HTTP {} : {}",
                    event.getId(), statusCode, body);
            rejectedCounter.increment();
            throw new CollectorClientException(
                    "Collector returned " + statusCode + ": " + body, statusCode, ex);

        } catch (HttpServerErrorException ex) {
            // 5xx — retryable
            int statusCode = ex.getStatusCode().value();
            log.warn("Collector server error for CloudEvent id={} — HTTP {} (will retry)",
                    event.getId(), statusCode);
            throw new CollectorForwardingException(
                    "Collector returned " + statusCode, ex);

        } catch (ResourceAccessException ex) {
            // Network timeout / connection refused — retryable
            // Also covers SocketTimeoutException wrapped by Spring's HTTP client
            log.warn("Collector unreachable for CloudEvent id={} — {} (will retry)",
                    event.getId(), ex.getMessage());
            throw new CollectorForwardingException(
                    "Collector unreachable: " + ex.getMessage(), ex);

        } catch (Exception ex) {
            // SocketTimeoutException can escape ResourceAccessException wrapping when
            // it occurs during response header/body reading in RestClient's message
            // converters (outside the HTTP client layer). Treat as retryable.
            if (ex instanceof SocketTimeoutException || ex.getCause() instanceof SocketTimeoutException) {
                log.warn("Collector read timed out for CloudEvent id={} — {} (will retry)",
                        event.getId(), ex.getMessage());
                throw new CollectorForwardingException(
                        "Collector read timed out: " + ex.getMessage(), ex);
            }
            // Unexpected errors — log at ERROR and let the GlobalExceptionHandler deal with it
            log.error("Unexpected error forwarding CloudEvent id={} — {}",
                    event.getId(), ex.getMessage(), ex);
            throw new RuntimeException(
                    "Unexpected error forwarding to Collector: " + ex.getMessage(), ex);
        }
    }

    /**
     * Recovery method invoked after all retry attempts are exhausted.
     *
     * @param ex    the last {@link CollectorForwardingException}
     * @param event the CloudEvent that failed to forward
     * @return never returns — always re-throws
     */
    @Recover
    public CollectorResponse recover(CollectorForwardingException ex, CloudEventDto event) {
        log.error("All retry attempts exhausted for CloudEvent id={} — {}",
                event != null ? event.getId() : "unknown", ex.getMessage());
        retriesCounter.increment();
        throw new CollectorForwardingException(
                "Collector forwarding failed after all retries: " + ex.getMessage(), ex);
    }

    /**
     * Recovery method for non-retryable client errors.
     * Re-throws the original exception so it propagates to the error handler.
     */
    @Recover
    public CollectorResponse recover(CollectorClientException ex, CloudEventDto event) {
        log.warn("Non-retryable client error for CloudEvent id={} — {}",
                event != null ? event.getId() : "unknown", ex.getMessage());
        throw ex;
    }
}
