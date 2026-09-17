package org.openphc.cce.emitter.redaction;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for clinical-data redaction, bound to {@code cce.emitter.redaction}.
 *
 * <p>CCE is a care <em>coordination</em> engine: it needs to know <em>that</em> a clinical step
 * happened, when, for which patient, at which facility — not <em>what the clinical finding was</em>.
 * The fields removed here are the ones carrying the finding itself (the measurement, the diagnosis
 * text, the free-text complaint), none of which are read by protocol matching, the compliance
 * engine, or the analytics dashboard.
 *
 * <p>Both lists are configurable so the rules can be tightened or relaxed without a rebuild —
 * a protocol that legitimately needs to condition on a clinical value can have that one field
 * removed from {@code remove-fields} rather than forcing redaction to be switched off wholesale.
 *
 * @param enabled       master switch; {@code false} passes payloads through untouched
 * @param removeFields  field names removed from the FHIR resource root (e.g. {@code valueQuantity})
 * @param removePaths   dot-delimited nested paths removed from the resource
 *                      (e.g. {@code subject.display}, the patient's name)
 */
@ConfigurationProperties(prefix = "cce.emitter.redaction")
public record ClinicalDataRedactionProperties(
        Boolean enabled,
        List<String> removeFields,
        List<String> removePaths
) {

    /**
     * FHIR elements that carry the clinical finding itself. Verified against every reader in the
     * platform (matcher, collector, compliance, insights, ClickHouse materialised columns): none
     * of these are consumed, so removing them changes no downstream behaviour.
     */
    private static final List<String> DEFAULT_REMOVE_FIELDS = List.of(
            // Observation.value[x] — the measurement or coded finding
            "valueQuantity", "valueCodeableConcept", "valueString", "valueBoolean",
            "valueInteger", "valueRange", "valueRatio", "valueSampledData",
            "valueTime", "valueDateTime", "valuePeriod", "valueAttachment",
            // Sub-observations, each carrying their own value[x]
            "component",
            // Free-text and interpretive clinical content
            "note", "text", "interpretation", "dataAbsentReason",
            "bodySite", "method", "specimen", "referenceRange",
            // Embedded resources (Provenance, contained Observations with values)
            "contained",
            // Condition — severity/staging/supporting evidence
            "severity", "stage", "evidence",
            // MedicationRequest / ServiceRequest — dosing and clinical justification
            "dosageInstruction", "reasonCode", "reasonReference",
            "orderDetail", "patientInstruction"
    );

    /**
     * Nested paths removed in addition to the root-level fields.
     *
     * <p>{@code subject.display} is the patient's name. It is not clinical data, but it is direct
     * identifying data travelling in the same payload, and nothing downstream reads it — the
     * patient is keyed on {@code subject.reference} (the UPID), which is preserved.
     */
    private static final List<String> DEFAULT_REMOVE_PATHS = List.of(
            "subject.display"
    );

    public ClinicalDataRedactionProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (removeFields == null || removeFields.isEmpty()) removeFields = DEFAULT_REMOVE_FIELDS;
        if (removePaths == null || removePaths.isEmpty()) removePaths = DEFAULT_REMOVE_PATHS;
    }
}
