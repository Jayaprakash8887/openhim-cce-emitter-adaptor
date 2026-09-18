package org.openphc.cce.emitter.redaction;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for clinical-data redaction, bound to {@code cce.emitter.redaction}.
 *
 * <p>CCE is a care <em>coordination</em> engine: it needs to know <em>that</em> a clinical step
 * happened, when, for which patient and at which facility — not <em>what the clinical finding
 * was</em>.
 *
 * <p>Rules are per-resource-type because the same FHIR element carries very different sensitivity
 * depending on the resource. {@code code} is the clearest case:
 *
 * <ul>
 *   <li>{@code Condition.code} — the diagnosis ("Type 2 diabetes mellitus") → must be removed</li>
 *   <li>{@code AllergyIntolerance.code} — the allergy ("allergy on aminophyline") → must be removed</li>
 *   <li>{@code ServiceRequest.code} — the test ordered ("a1-Acid Glycoprotein") → must be removed</li>
 *   <li>{@code Observation.code} — the observation <em>type</em> (LOINC 8716-3 "Vital signs") →
 *       must be <b>kept</b>: protocol triggers match on it, so removing it would stop compliance
 *       tracking working</li>
 * </ul>
 *
 * <h2>Nested paths</h2>
 *
 * <p>Root-level fields are listed in each rule's {@code fields}. Content nested inside a structure
 * that must be kept is expressed as a dot-delimited path, either globally ({@code removePaths},
 * applied to every resource) or scoped to one type ({@link ResourceRule#removePaths}).
 *
 * <p>A segment ending in {@code []} steps through an array and applies to every element:
 *
 * <pre>
 *   subject.display                      → Observation.subject.display
 *   hospitalization.dischargeDisposition → Encounter.hospitalization.dischargeDisposition
 *   reaction[].manifestation             → AllergyIntolerance.reaction[*].manifestation
 * </pre>
 *
 * <p>The {@code []} marker is required where the data is an array. A path that omits it walks into
 * an object only, so {@code reaction.manifestation} would match nothing — see
 * {@link ClinicalDataRedactor} for how that mismatch is surfaced rather than silently ignored.
 *
 * @param enabled     master switch; {@code false} passes payloads through untouched
 * @param removePaths nested paths removed from every resource regardless of type (e.g.
 *                    {@code subject.display} — the patient's name). Type-specific paths belong on
 *                    the rule instead; the two are additive.
 * @param rules       per-resource-type field lists; the entry with resourceType {@code *} applies
 *                    to any type without its own rule
 */
@ConfigurationProperties(prefix = "cce.emitter.redaction")
public record ClinicalDataRedactionProperties(
        Boolean enabled,
        List<String> removePaths,
        List<ResourceRule> rules
) {

    /** Wildcard resource type: the fallback rule applied when no type-specific rule matches. */
    public static final String ANY_RESOURCE_TYPE = "*";

    /**
     * Redaction rule for one FHIR resource type.
     *
     * <p>{@code fields} covers the resource root, which is where most clinical findings live.
     * {@code removePaths} covers content nested inside a structure that must otherwise be kept —
     * {@code Encounter.hospitalization} is retained because {@code hospitalization.origin} drives
     * facility attribution, yet {@code hospitalization.dischargeDisposition} is clinical.
     *
     * @param resourceType FHIR resource type (e.g. {@code Condition}), or {@code *} for the default
     * @param fields       field names removed from that resource's root
     * @param removePaths  dot-delimited nested paths removed from this resource type only, in
     *                     addition to the global {@link ClinicalDataRedactionProperties#removePaths}.
     *                     A segment ending in {@code []} steps through an array — see
     *                     {@link ClinicalDataRedactionProperties#removePaths} for the syntax.
     */
    public record ResourceRule(String resourceType, List<String> fields, List<String> removePaths) {

        public ResourceRule {
            if (fields == null) fields = List.of();
            if (removePaths == null) removePaths = List.of();
        }

        /**
         * A rule with no type-specific nested paths — the common case.
         *
         * <p>Deliberately a static factory, not an overloaded constructor: an extra constructor on
         * a record stops Spring Boot identifying the canonical one, and the whole
         * {@code cce.emitter.redaction} tree then fails to bind.
         */
        public static ResourceRule of(String resourceType, List<String> fields) {
            return new ResourceRule(resourceType, fields, List.of());
        }
    }

    /**
     * Clinical content that is sensitive on every resource type that carries it. Verified against
     * every reader in the platform (matcher, collector, compliance, insights, ClickHouse
     * materialised columns): none of these are consumed, so removing them changes no behaviour.
     */
    private static final List<String> COMMON_CLINICAL_FIELDS = List.of(
            // value[x] — the measurement or coded finding
            "valueQuantity", "valueCodeableConcept", "valueString", "valueBoolean",
            "valueInteger", "valueRange", "valueRatio", "valueSampledData",
            "valueTime", "valueDateTime", "valuePeriod", "valueAttachment",
            // Free-text and interpretive clinical content
            "note", "text", "interpretation", "dataAbsentReason",
            "bodySite", "method", "specimen", "referenceRange",
            // Embedded resources (Provenance, contained Observations carrying values)
            "contained",
            // Condition-style qualifiers
            "severity", "stage", "evidence",
            // Ordering / prescribing detail and clinical justification
            "dosageInstruction", "reasonCode", "reasonReference",
            "orderDetail", "patientInstruction"
    );

    /** {@link #COMMON_CLINICAL_FIELDS} plus the extra fields given. */
    private static List<String> commonPlus(String... extra) {
        List<String> all = new ArrayList<>(COMMON_CLINICAL_FIELDS);
        all.addAll(List.of(extra));
        return List.copyOf(all);
    }

    /**
     * One rule per resource type actually observed in Rwanda production, plus a wildcard fallback.
     * Volumes at time of writing (PROD {@code inbound_event_log}): Observation 141k, Encounter 48k,
     * ServiceRequest 33k, MedicationRequest 30k, Condition 25k, MedicationDispense 20k, Consent 16k,
     * Procedure 4.2k, MedicationAdministration 1.6k, AllergyIntolerance 22.
     */
    private static final List<ResourceRule> DEFAULT_RULES = List.of(
            // code = the observation TYPE (LOINC) and is what protocol triggers match on — kept.
            // component holds sub-observations, each with their own value[x].
            ResourceRule.of("Observation", commonPlus("component")),

            // Encounter.type carries VISIT_ENCOUNTER / CONSULTATION_ENCOUNTER / TRANSFER_ENCOUNTER,
            // which drive protocol matching and the referral KPI — type, class and serviceType kept.
            // hospitalization and location are the facility source, also kept.
            // diagnosis = the encounter's diagnosis; reasonCode = why the patient attended.
            // dischargeDisposition ("Died in hospital", "Transferred to ICU") is a clinical outcome,
            // but it is nested inside hospitalization, which is KEPT because hospitalization.origin
            // is a facility source. Hence a type-scoped nested path rather than a root field.
            new ResourceRule("Encounter", commonPlus("diagnosis"),
                    List.of("hospitalization.dischargeDisposition")),

            // code = the test/procedure requested ("a1-Acid Glycoprotein").
            // category (laboratory vs other) and locationReference (facility) are kept.
            ResourceRule.of("ServiceRequest", commonPlus("code")),

            // medicationCodeableConcept = the drug; dispenseRequest carries quantity and refills.
            // intent and authoredOn are kept — protocol conditions read intent.
            ResourceRule.of("MedicationRequest",
                    commonPlus("medicationCodeableConcept", "medicationReference", "dispenseRequest")),

            // code = the diagnosis itself. clinicalStatus/verificationStatus are the trigger keys.
            ResourceRule.of("Condition", commonPlus("code")),

            // quantity = how much of the drug was dispensed. whenHandedOver is kept (clinical time).
            ResourceRule.of("MedicationDispense",
                    commonPlus("medicationCodeableConcept", "medicationReference", "quantity")),

            // Consent carries no clinical finding — category/scope/status are consent metadata, and
            // are what the Consent step matches on. Listed explicitly so it is documented as
            // reviewed rather than silently falling through to the wildcard rule.
            ResourceRule.of("Consent", COMMON_CLINICAL_FIELDS),

            // code = the procedure performed. performedDateTime and location are kept.
            ResourceRule.of("Procedure", commonPlus("code", "outcome", "complication")),

            // dosage = how much was administered; supportingInformation may point at clinical data.
            ResourceRule.of("MedicationAdministration",
                    commonPlus("medicationCodeableConcept", "medicationReference",
                            "dosage", "supportingInformation")),

            // code = the allergen ("allergy on aminophyline"); reaction holds manifestation detail.
            ResourceRule.of("AllergyIntolerance", commonPlus("code", "reaction")),

            // Seen in UAT (not yet in PROD). The radiology findings live in fields that appear on
            // no other resource type — conclusion is free-text narrative from the reporting
            // radiologist — so without this rule they would pass straight through the wildcard.
            ResourceRule.of("ImagingStudy",
                    commonPlus("conclusion", "conclusionCode", "description",
                            "procedureCode", "series", "modality")),

            // Not currently received from eBuzima, but included so a new feed cannot leak the
            // vaccine given before anyone notices the resource type is unhandled.
            ResourceRule.of("Immunization", commonPlus("vaccineCode")),

            // Fallback for any resource type without its own rule. Conservative: strips the common
            // clinical fields but leaves `code` alone, since on an unrecognised type `code` may be
            // the structural discriminator rather than a finding.
            ResourceRule.of(ANY_RESOURCE_TYPE, COMMON_CLINICAL_FIELDS)
    );

    /**
     * Nested paths removed from every resource.
     *
     * <p>These are patient names. Not clinical data, but direct identifying data travelling in the
     * same payload, and nothing downstream reads them — the patient is keyed on the reference
     * ({@code Patient/<UPID>}), which is preserved. {@code patient.display} is included because
     * AllergyIntolerance and several other resources use {@code patient} rather than
     * {@code subject}.
     */
    private static final List<String> DEFAULT_REMOVE_PATHS = List.of(
            "subject.display",
            "patient.display"
    );

    public ClinicalDataRedactionProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (removePaths == null || removePaths.isEmpty()) removePaths = DEFAULT_REMOVE_PATHS;
        if (rules == null || rules.isEmpty()) rules = DEFAULT_RULES;
    }
}
