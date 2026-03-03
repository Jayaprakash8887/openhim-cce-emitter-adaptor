package org.openphc.cce.emitter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry support across the application.
 *
 * <p>This allows {@code @Retryable} and {@code @Recover} annotations to be used
 * on service methods (e.g., {@code CollectorForwardingService}) for automatic
 * retry with exponential backoff on transient failures.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
