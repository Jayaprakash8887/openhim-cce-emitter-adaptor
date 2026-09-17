package org.openphc.cce.emitter.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redaction is verified against payloads captured verbatim from Rwanda UAT, not hand-written
 * fixtures — the point of the change is that real eBuzima events stop carrying clinical findings.
 *
 * <p>The two samples are the two shapes that actually occur: a free-text finding
 * ({@code valueString}) and a coded diagnosis ({@code valueCodeableConcept}).
 */
class ClinicalDataRedactorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ClinicalDataRedactor redactor;

    /** Chief Complaints observation from UAT — the finding is free text: "HEADACHE". */
    private static final String CHIEF_COMPLAINT = """
            {
              "id": "0312872e-f002-41c7-be52-9468e5211c92",
              "code": { "coding": [ { "code": "33747-0", "system": "http://loinc.org", "display": "Chief Complaints" } ] },
              "status": "final",
              "subject": { "reference": "Patient/260908-0000-6502" },
              "category": [ { "coding": [ { "code": "survey", "system": "http://terminology.hl7.org/CodeSystem/observation-category", "display": "Survey" } ] } ],
              "encounter": { "reference": "Encounter/ae1b3cab-4665-4db9-8a09-6efc5ba27550" },
              "extension": [
                { "url": "http://example.org/fhir/StructureDefinition/source-system", "valueString": "eBuzima" },
                { "url": "http://example.org/fhir/StructureDefinition/source-facility", "valueString": "0000" }
              ],
              "performer": [ { "display": "QT Admins", "reference": "Practitioner/HLC-PRAC-2026-00005" } ],
              "valueString": "HEADACHE",
              "resourceType": "Observation",
              "effectiveDateTime": "2026-09-08T20:03:03+02:00"
            }
            """;

    /** Diagnosis observation from UAT — coded finding plus the patient's name in subject.display. */
    private static final String DIAGNOSIS = """
            {
              "id": "ef833deb-e75a-41e1-977d-412ec5f5cf4b",
              "code": { "text": "Diagnosis", "coding": [ { "code": "diagnosis", "display": "Diagnosis" } ] },
              "text": { "div": "<div xmlns='http://www.w3.org/1999/xhtml'>Observation</div>", "status": "generated" },
              "issued": "2026-09-08T20:03:21+02:00",
              "status": "final",
              "subject": {
                "type": "Patient",
                "display": "TEST  YAN7",
                "reference": "Patient/260908-0000-6502",
                "identifier": { "type": { "coding": { "code": "UPID", "display": "UPID" } }, "value": "260908-0000-6502" }
              },
              "category": [ { "coding": [ { "code": "diagnosis", "system": "http://terminology.hl7.org/CodeSystem/observation-category", "display": "Diagnosis" } ] } ],
              "contained": [ { "id": "d90a04b9", "resourceType": "Provenance" } ],
              "valueCodeableConcept": { "coding": [ { "code": "MB4D - Headache, not elsewhere classified", "display": "MB4D - Headache, not elsewhere classified" } ] },
              "resourceType": "Observation",
              "effectiveDateTime": "2026-09-08T20:03:21+02:00"
            }
            """;

    @BeforeEach
    void setUp() {
        redactor = new ClinicalDataRedactor(
                new ClinicalDataRedactionProperties(true, null, null), new SimpleMeterRegistry());
    }

    private JsonNode redact(String json) throws Exception {
        return redactor.redact(mapper.readTree(json));
    }

    @Test
    @DisplayName("free-text clinical finding is removed")
    void removesValueString() throws Exception {
        assertThat(redact(CHIEF_COMPLAINT).has("valueString")).isFalse();
    }

    @Test
    @DisplayName("coded diagnosis is removed")
    void removesValueCodeableConcept() throws Exception {
        JsonNode out = redact(DIAGNOSIS);
        assertThat(out.has("valueCodeableConcept")).isFalse();
        assertThat(out.toString()).doesNotContain("Headache");
    }

    @Test
    @DisplayName("narrative and contained resources are removed")
    void removesNarrativeAndContained() throws Exception {
        JsonNode out = redact(DIAGNOSIS);
        assertThat(out.has("text")).isFalse();
        assertThat(out.has("contained")).isFalse();
    }

    @Test
    @DisplayName("patient name is removed but the UPID reference is preserved")
    void removesPatientNameKeepsUpid() throws Exception {
        JsonNode subject = redact(DIAGNOSIS).get("subject");
        assertThat(subject.has("display")).isFalse();
        assertThat(subject.get("reference").asText()).isEqualTo("Patient/260908-0000-6502");
        assertThat(subject.get("identifier").get("value").asText()).isEqualTo("260908-0000-6502");
    }

    @Test
    @DisplayName("every field CCE actually matches on survives redaction")
    void preservesFieldsUsedDownstream() throws Exception {
        JsonNode out = redact(CHIEF_COMPLAINT);

        // Protocol trigger matching: resourceType + code.coding.code + category
        assertThat(out.get("resourceType").asText()).isEqualTo("Observation");
        assertThat(out.get("code").get("coding").get(0).get("code").asText()).isEqualTo("33747-0");
        assertThat(out.get("category").get(0).get("coding").get(0).get("code").asText()).isEqualTo("survey");
        assertThat(out.get("status").asText()).isEqualTo("final");

        // Clinical time drives SLA evaluation
        assertThat(out.get("effectiveDateTime").asText()).isEqualTo("2026-09-08T20:03:03+02:00");

        // Patient key and facility attribution
        assertThat(out.get("subject").get("reference").asText()).isEqualTo("Patient/260908-0000-6502");
        assertThat(out.get("extension").toString()).contains("source-facility").contains("0000");

        // Practitioner is surfaced in the insights dashboard
        assertThat(out.get("performer").get(0).get("reference").asText())
                .isEqualTo("Practitioner/HLC-PRAC-2026-00005");
    }

    @Test
    @DisplayName("source-facility extension survives despite being a valueString")
    void doesNotStripValueStringInsideExtensions() throws Exception {
        // Redaction is shallow by design: a recursive scrub would also strip the extension's
        // valueString, which is how facility attribution works.
        JsonNode ext = redact(CHIEF_COMPLAINT).get("extension");
        assertThat(ext.get(1).get("valueString").asText()).isEqualTo("0000");
    }

    @Test
    @DisplayName("redaction disabled leaves the payload untouched")
    void disabledIsPassThrough() throws Exception {
        ClinicalDataRedactor off = new ClinicalDataRedactor(
                new ClinicalDataRedactionProperties(false, null, null), new SimpleMeterRegistry());
        assertThat(off.redact(mapper.readTree(CHIEF_COMPLAINT)).get("valueString").asText())
                .isEqualTo("HEADACHE");
    }

    @Test
    @DisplayName("custom field list overrides the defaults")
    void honoursCustomConfiguration() throws Exception {
        ClinicalDataRedactor custom = new ClinicalDataRedactor(
                new ClinicalDataRedactionProperties(true, List.of("status"), List.of()),
                new SimpleMeterRegistry());
        JsonNode out = custom.redact(mapper.readTree(CHIEF_COMPLAINT));
        assertThat(out.has("status")).isFalse();
        // not in the custom list, so it stays
        assertThat(out.has("valueString")).isTrue();
    }

    @Test
    @DisplayName("payload without clinical fields is unchanged")
    void handlesPayloadWithNothingToRedact() throws Exception {
        String minimal = """
                { "resourceType": "Encounter", "status": "in-progress",
                  "subject": { "reference": "Patient/260908-0000-6502" } }
                """;
        JsonNode out = redact(minimal);
        assertThat(out.get("resourceType").asText()).isEqualTo("Encounter");
        assertThat(out.get("subject").get("reference").asText()).isEqualTo("Patient/260908-0000-6502");
    }
}
