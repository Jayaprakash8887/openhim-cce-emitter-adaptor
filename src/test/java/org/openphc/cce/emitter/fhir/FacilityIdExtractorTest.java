package org.openphc.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FacilityIdExtractorTest {

    private FacilityIdExtractor extractor;
    private FhirContext fhirContext;

    @BeforeEach
    void setUp() {
        extractor = new FacilityIdExtractor();
        fhirContext = FhirContext.forR4();
    }

    @Test
    void extractsFromEncounterLocationReference() {
        Encounter encounter = new Encounter();
        encounter.addLocation()
                .setLocation(new Reference("Location/0030"));

        assertThat(extractor.extract(encounter)).isEqualTo("0030");
    }

    @Test
    void extractsFromEncounterLocationIdentifier() {
        Encounter encounter = new Encounter();
        Reference locationRef = new Reference();
        locationRef.setIdentifier(new Identifier().setValue("Kacyiru Health Center"));
        encounter.addLocation().setLocation(locationRef);

        assertThat(extractor.extract(encounter)).isEqualTo("Kacyiru Health Center");
    }

    @Test
    void prefersReferenceOverIdentifier() {
        Encounter encounter = new Encounter();
        Reference locationRef = new Reference("Location/0030");
        locationRef.setIdentifier(new Identifier().setValue("Kacyiru Health Center"));
        encounter.addLocation().setLocation(locationRef);

        assertThat(extractor.extract(encounter)).isEqualTo("0030");
    }

    @Test
    void returnsNullForEncounterWithoutLocation() {
        Encounter encounter = new Encounter();
        assertThat(extractor.extract(encounter)).isNull();
    }

    @Test
    void returnsNullForNonEncounterResource() {
        Observation observation = new Observation();
        assertThat(extractor.extract(observation)).isNull();
    }

    @Test
    void returnsNullForNullResource() {
        assertThat(extractor.extract(null)).isNull();
    }

    @Test
    void extractsFromParsedEbuzimaPayload() {
        String json = """
                {
                  "resourceType": "Encounter",
                  "id": "809cd034-f2d8-44d0-a95e-f42c44305afa",
                  "status": "in-progress",
                  "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB"},
                  "subject": {"reference": "Patient/240717-SITE-7293"},
                  "location": [{"location": {"reference": "Location/0030", "type": "Location",
                    "identifier": {"value": "Kacyiru Health Center"}, "display": "Kacyiru Health Center"}}]
                }
                """;
        IBaseResource resource = fhirContext.newJsonParser().parseResource(json);
        assertThat(extractor.extract(resource)).isEqualTo("0030");
    }
}
