package org.openphc.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.exception.FhirMappingException;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link FhirResourceParser}.
 */
class FhirResourceParserTest {

    private FhirResourceParser parser;

    @BeforeEach
    void setUp() {
        parser = new FhirResourceParser(FhirContext.forR4());
    }

    // --- parse() tests ---

    @Test
    void parse_validEncounter_returnsEncounterResource() {
        String json = """
                {
                  "resourceType": "Encounter",
                  "id": "enc-001",
                  "status": "in-progress",
                  "class": {
                    "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                    "code": "AMB"
                  },
                  "subject": {
                    "reference": "Patient/260225-0002-5501"
                  }
                }
                """;

        IBaseResource resource = parser.parse(json);

        assertThat(resource).isInstanceOf(Encounter.class);
        assertThat(resource.getIdElement().getIdPart()).isEqualTo("enc-001");
    }

    @Test
    void parse_validObservation_returnsObservationResource() {
        String json = """
                {
                  "resourceType": "Observation",
                  "id": "obs-001",
                  "status": "final",
                  "code": {
                    "coding": [{
                      "system": "http://loinc.org",
                      "code": "8867-4",
                      "display": "Heart rate"
                    }]
                  },
                  "subject": {
                    "reference": "Patient/260225-0002-5501"
                  }
                }
                """;

        IBaseResource resource = parser.parse(json);

        assertThat(resource).isInstanceOf(Observation.class);
        assertThat(resource.getIdElement().getIdPart()).isEqualTo("obs-001");
    }

    @Test
    void parse_validBundle_returnsBundleResource() {
        String json = """
                {
                  "resourceType": "Bundle",
                  "id": "bundle-001",
                  "type": "collection",
                  "entry": []
                }
                """;

        IBaseResource resource = parser.parse(json);

        assertThat(resource).isInstanceOf(Bundle.class);
    }

    @Test
    void parse_nullJson_throwsFhirMappingException() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void parse_blankJson_throwsFhirMappingException() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void parse_emptyJson_throwsFhirMappingException() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void parse_invalidJson_throwsFhirMappingException() {
        assertThatThrownBy(() -> parser.parse("{ not valid json }"))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("Failed to parse FHIR resource JSON");
    }

    @Test
    void parse_validJsonButNotFhir_throwsFhirMappingException() {
        String json = """
                {
                  "name": "not a FHIR resource",
                  "value": 42
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("Failed to parse FHIR resource JSON");
    }

    // --- detectResourceType() tests ---

    @Test
    void detectResourceType_encounter_returnsEncounter() {
        String json = """
                {
                  "resourceType": "Encounter",
                  "id": "enc-001",
                  "status": "in-progress",
                  "class": {
                    "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                    "code": "AMB"
                  }
                }
                """;

        assertThat(parser.detectResourceType(json)).isEqualTo("Encounter");
    }

    @Test
    void detectResourceType_observation_returnsObservation() {
        String json = """
                {
                  "resourceType": "Observation",
                  "id": "obs-001",
                  "status": "final",
                  "code": {
                    "coding": [{
                      "system": "http://loinc.org",
                      "code": "8867-4"
                    }]
                  }
                }
                """;

        assertThat(parser.detectResourceType(json)).isEqualTo("Observation");
    }

    @Test
    void detectResourceType_bundle_returnsBundle() {
        String json = """
                {
                  "resourceType": "Bundle",
                  "id": "bundle-001",
                  "type": "collection",
                  "entry": []
                }
                """;

        assertThat(parser.detectResourceType(json)).isEqualTo("Bundle");
    }

    @Test
    void detectResourceType_invalidJson_throwsFhirMappingException() {
        assertThatThrownBy(() -> parser.detectResourceType("not json"))
                .isInstanceOf(FhirMappingException.class);
    }
}
