package org.openphc.cce.emitter.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.emitter.exception.FacilityFilterRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces facility-based filtering for inbound FHIR events.
 *
 * <p>Behaviour is driven entirely by {@link FacilityFilterProperties#ids()}:
 * <ul>
 *   <li><b>Empty list</b> — filter is inactive; all events pass through unconditionally.</li>
 *   <li><b>Non-empty list</b> — only events whose facility ID appears in the configured set
 *       are admitted. Events with a missing or unrecognised facility ID are rejected with
 *       {@link FacilityFilterRejectedException}, which the global exception handler maps to
 *       {@code 403 Forbidden}.</li>
 * </ul>
 *
 * <p>Every denial increments the {@code cce.emitter.events.filtered.total} Micrometer counter
 * with {@code source}, {@code facility}, and {@code reason} tags for Prometheus visibility.
 */
@Component
public class FacilityFilter {

    private static final Logger log = LoggerFactory.getLogger(FacilityFilter.class);

    /** Immutable set of allowed facility IDs built once at startup. Empty means no filtering. */
    private final Set<String> allowedFacilityIds;
    private final MeterRegistry meterRegistry;

    /**
     * Builds the filter from the bound configuration properties.
     *
     * <p>IDs are trimmed and blank entries discarded at construction time so that
     * {@link #enforceFilter} never has to deal with dirty config values at runtime.
     *
     * @param properties    bound facility-filter configuration
     * @param meterRegistry Micrometer registry used to record denial counters
     */
    public FacilityFilter(FacilityFilterProperties properties, MeterRegistry meterRegistry) {
        this.allowedFacilityIds = properties.ids().stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.meterRegistry = meterRegistry;

        if (allowedFacilityIds.isEmpty()) {
            log.info("Facility filter: inactive — no ids configured, all events pass through");
        } else {
            log.info("Facility filter: active — {} facility id(s) configured", allowedFacilityIds.size());
        }
    }

    /**
     * Evaluates the facility filter for a single inbound event.
     *
     * <p>Call this after facility ID resolution ({@code buildSourceMetadata}) and before
     * building the CloudEvent envelope so that denied events never reach the Collector.
     *
     * <p>No-op when the configured ID list is empty (filter inactive) or when
     * {@code inboundFacilityId} is null/blank — resources with no facility context
     * (e.g. {@code Patient}, {@code RelatedPerson}) are always forwarded.
     *
     * @param inboundFacilityId the facility ID resolved from the request
     *                          (from {@code X-Facility-Id} header or FHIR payload); may be {@code null}
     * @param sourceSystemKey   the matched source system key (e.g. {@code "ebuzima"});
     *                          used as a Micrometer tag and in the exception message
     * @throws FacilityFilterRejectedException if a non-null facility ID is not in the allowlist —
     *                                         caller lets this propagate to {@code GlobalExceptionHandler}
     *                                         for a 403 response
     */
    public void enforceFilter(String inboundFacilityId, String sourceSystemKey) {
        // No IDs configured → filter is inactive, admit everything
        if (allowedFacilityIds.isEmpty()) {
            return;
        }

        // No facility ID resolved — resource has no facility context (e.g. Patient, RelatedPerson), pass through
        if (inboundFacilityId == null || inboundFacilityId.isBlank()) {
            return;
        }

        String normalizedFacilityId = inboundFacilityId.toLowerCase();

        // Facility ID present but not in the configured allowlist
        if (!allowedFacilityIds.contains(normalizedFacilityId)) {
            throw buildRejection(normalizedFacilityId, sourceSystemKey, "NOT_IN_ALLOWLIST");
        }
    }

    /**
     * Increments the denial counter and returns a {@link FacilityFilterRejectedException} to throw.
     *
     * <p>Returning the exception rather than throwing it internally lets the compiler see the
     * {@code throw} at each call site, making the control flow explicit without a dead
     * {@code return} statement after the call.
     *
     * <p>The counter uses lazy registration ({@code Counter.builder(...).register(registry)})
     * so that label combinations only appear in Prometheus after the first occurrence —
     * avoiding pre-seeded zero-value series for IDs that never trigger.
     *
     * @param facilityId      the normalised facility ID that was denied, or {@code null} when absent
     * @param sourceSystemKey the source system that sent the event
     * @param rejectionReason a short reason code included in the counter tag and exception message
     * @return the exception — always thrown by the caller
     */
    private FacilityFilterRejectedException buildRejection(String facilityId, String sourceSystemKey, String rejectionReason) {
        Counter.builder("cce.emitter.events.filtered.total")
                .tag("source", sourceSystemKey != null ? sourceSystemKey : "unknown")
                .tag("facility", facilityId != null ? facilityId : "missing")
                .tag("reason", rejectionReason)
                .register(meterRegistry)
                .increment();
        return new FacilityFilterRejectedException(facilityId, sourceSystemKey, rejectionReason);
    }
}
