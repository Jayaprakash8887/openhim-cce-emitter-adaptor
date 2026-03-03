package org.openphc.cce.emitter.model;

import java.util.List;

/**
 * Aggregated result of processing a batch of CloudEvents (e.g., from a FHIR Bundle).
 *
 * @param eventsProcessed total number of events processed
 * @param eventsAccepted  number of events accepted by the Collector
 * @param eventsRejected  number of events rejected or failed
 * @param eventsDuplicate number of events detected as duplicates by the Collector
 * @param results         individual result for each event
 */
public record BatchResult(
        int eventsProcessed,
        int eventsAccepted,
        int eventsRejected,
        int eventsDuplicate,
        List<TransformationResult> results
) {

    /**
     * Constructs a {@link BatchResult} by aggregating individual {@link TransformationResult}s.
     *
     * @param results list of per-event results
     * @return aggregated batch result
     */
    public static BatchResult fromResults(List<TransformationResult> results) {
        int processed = results.size();
        int accepted = 0;
        int rejected = 0;
        int duplicate = 0;

        for (TransformationResult result : results) {
            if (!result.success()) {
                rejected++;
            } else if ("duplicate".equalsIgnoreCase(result.collectorStatus())) {
                duplicate++;
            } else {
                accepted++;
            }
        }

        return new BatchResult(processed, accepted, rejected, duplicate, List.copyOf(results));
    }
}
