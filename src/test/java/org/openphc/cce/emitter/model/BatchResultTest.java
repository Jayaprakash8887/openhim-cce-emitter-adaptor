package org.openphc.cce.emitter.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BatchResult}.
 */
class BatchResultTest {

    @Test
    void fromResultsShouldCountAccepted() {
        List<TransformationResult> results = List.of(
                new TransformationResult(buildEvent("1"), true, "accepted", null),
                new TransformationResult(buildEvent("2"), true, "accepted", null)
        );

        BatchResult batch = BatchResult.fromResults(results);

        assertThat(batch.eventsProcessed()).isEqualTo(2);
        assertThat(batch.eventsAccepted()).isEqualTo(2);
        assertThat(batch.eventsRejected()).isZero();
        assertThat(batch.eventsDuplicate()).isZero();
    }

    @Test
    void fromResultsShouldCountRejected() {
        List<TransformationResult> results = List.of(
                new TransformationResult(buildEvent("1"), true, "accepted", null),
                new TransformationResult(null, false, null, "Collector returned 400")
        );

        BatchResult batch = BatchResult.fromResults(results);

        assertThat(batch.eventsProcessed()).isEqualTo(2);
        assertThat(batch.eventsAccepted()).isEqualTo(1);
        assertThat(batch.eventsRejected()).isEqualTo(1);
        assertThat(batch.eventsDuplicate()).isZero();
    }

    @Test
    void fromResultsShouldCountDuplicates() {
        List<TransformationResult> results = List.of(
                new TransformationResult(buildEvent("1"), true, "accepted", null),
                new TransformationResult(buildEvent("2"), true, "duplicate", null),
                new TransformationResult(buildEvent("3"), true, "duplicate", null)
        );

        BatchResult batch = BatchResult.fromResults(results);

        assertThat(batch.eventsProcessed()).isEqualTo(3);
        assertThat(batch.eventsAccepted()).isEqualTo(1);
        assertThat(batch.eventsRejected()).isZero();
        assertThat(batch.eventsDuplicate()).isEqualTo(2);
    }

    @Test
    void fromResultsShouldHandleMixedResults() {
        List<TransformationResult> results = List.of(
                new TransformationResult(buildEvent("1"), true, "accepted", null),
                new TransformationResult(buildEvent("2"), true, "duplicate", null),
                new TransformationResult(null, false, null, "Parse error"),
                new TransformationResult(buildEvent("4"), true, "accepted", null)
        );

        BatchResult batch = BatchResult.fromResults(results);

        assertThat(batch.eventsProcessed()).isEqualTo(4);
        assertThat(batch.eventsAccepted()).isEqualTo(2);
        assertThat(batch.eventsRejected()).isEqualTo(1);
        assertThat(batch.eventsDuplicate()).isEqualTo(1);
    }

    @Test
    void fromResultsShouldHandleEmptyList() {
        BatchResult batch = BatchResult.fromResults(List.of());

        assertThat(batch.eventsProcessed()).isZero();
        assertThat(batch.eventsAccepted()).isZero();
        assertThat(batch.eventsRejected()).isZero();
        assertThat(batch.eventsDuplicate()).isZero();
        assertThat(batch.results()).isEmpty();
    }

    @Test
    void fromResultsShouldReturnUnmodifiableResultsList() {
        List<TransformationResult> results = List.of(
                new TransformationResult(buildEvent("1"), true, "accepted", null)
        );

        BatchResult batch = BatchResult.fromResults(results);

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> batch.results().add(new TransformationResult(null, false, null, "hack"))
        );
    }

    @Test
    void fromResultsShouldHandleDuplicateStatusCaseInsensitive() {
        // "Duplicate" with capital D should still count as duplicate
        List<TransformationResult> results = List.of(
                new TransformationResult(buildEvent("1"), true, "Duplicate", null)
        );

        BatchResult batch = BatchResult.fromResults(results);

        assertThat(batch.eventsDuplicate()).isEqualTo(1);
        assertThat(batch.eventsAccepted()).isZero();
    }

    private CloudEventDto buildEvent(String id) {
        return CloudEventDto.builder()
                .specversion("1.0")
                .id(id)
                .source("ebuzima")
                .type("Encounter")
                .build();
    }
}
