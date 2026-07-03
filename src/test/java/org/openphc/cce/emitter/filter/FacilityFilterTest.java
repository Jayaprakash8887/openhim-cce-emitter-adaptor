package org.openphc.cce.emitter.filter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.exception.FacilityFilterRejectedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FacilityFilterTest {

    private static final String SOURCE = "ebuzima";

    private FacilityFilter filter(String... ids) {
        return new FacilityFilter(new FacilityFilterProperties(List.of(ids)), new SimpleMeterRegistry());
    }

    // ==================== No ids configured → all events pass ====================

    @Nested
    class NoFilter {

        @Test
        void anyFacilityId_passes() {
            assertThatCode(() -> filter().enforceFilter("9999", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        void nullFacilityId_passes() {
            assertThatCode(() -> filter().enforceFilter(null, SOURCE))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== Ids configured → only listed facilities pass ====================

    @Nested
    class WithFilter {

        @Test
        void facilityInList_passes() {
            assertThatCode(() -> filter("0030").enforceFilter("0030", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        void facilityNotInList_throws() {
            assertThatThrownBy(() -> filter("0030").enforceFilter("9999", SOURCE))
                    .isInstanceOf(FacilityFilterRejectedException.class)
                    .hasMessageContaining("NOT_IN_ALLOWLIST");
        }

        @Test
        void nullFacilityId_passesThrough() {
            // null = no facility context (e.g. Patient, Observation) — always passes through
            assertThatCode(() -> filter("0030").enforceFilter(null, SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        void blankFacilityId_passesThrough() {
            // blank = treated as absent facility context — passes through like null
            assertThatCode(() -> filter("0030").enforceFilter("   ", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        void configuredIdWithWhitespace_trimmedBeforeMatch() {
            assertThatCode(() -> filter("  0030  ").enforceFilter("0030", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        void uuidCaseInsensitive_upperInbound_lowerConfig() {
            assertThatCode(() -> filter("550e8400-e29b-41d4-a716-446655440000")
                    .enforceFilter("550e8400-E29B-41D4-A716-446655440000", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        void uuidCaseInsensitive_upperConfig_lowerInbound() {
            assertThatCode(() -> filter("550E8400-E29B-41D4-A716-446655440000")
                    .enforceFilter("550e8400-e29b-41d4-a716-446655440000", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        void exceptionCarriesFacilityAndSource() {
            assertThatExceptionOfType(FacilityFilterRejectedException.class)
                    .isThrownBy(() -> filter("0030").enforceFilter("9999", SOURCE))
                    .satisfies(ex -> {
                        assertThat(ex.getFacilityId()).isEqualTo("9999");
                        assertThat(ex.getSourceKey()).isEqualTo(SOURCE);
                        assertThat(ex.getReason()).isEqualTo("NOT_IN_ALLOWLIST");
                    });
        }
    }

    // ==================== Micrometer counter ====================

    @Nested
    class Metrics {

        @Test
        void counterIncrementedOnDeny() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            FacilityFilter f = new FacilityFilter(
                    new FacilityFilterProperties(List.of("0030")),
                    registry);

            assertThatExceptionOfType(FacilityFilterRejectedException.class)
                    .isThrownBy(() -> f.enforceFilter("9999", SOURCE));

            double count = registry.counter("cce.emitter.events.filtered.total",
                    "source", SOURCE, "facility", "9999", "reason", "NOT_IN_ALLOWLIST").count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        void counterNotIncrementedOnPass() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            FacilityFilter f = new FacilityFilter(
                    new FacilityFilterProperties(List.of("0030")),
                    registry);

            f.enforceFilter("0030", SOURCE);

            assertThat(registry.getMeters()).isEmpty();
        }
    }
}
