package org.openphc.cce.emitter.fhir;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.EpisodeOfCare;
import org.hl7.fhir.r4.model.FamilyMemberHistory;
import org.hl7.fhir.r4.model.Goal;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationAdministration;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.RiskAssessment;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.exception.PatientIdNotFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PatientIdExtractor}.
 *
 * <p>Tests extraction via reflection from subject-based and patient-based
 * FHIR resource types, including types beyond the original 10 CCE protocol types.
 */
class PatientIdExtractorTest {

    private static final String PATIENT_UPID = "260225-0002-5501";
    private static final String PATIENT_REF = "Patient/" + PATIENT_UPID;
    private static final String UPID_SYSTEM = "http://openphc.org/identifier/upid";

    private PatientIdExtractor extractor;

    @BeforeEach
    void setUp() {
        EmitterProperties props = new EmitterProperties(Map.of(), UPID_SYSTEM);
        extractor = new PatientIdExtractor(props);
    }

    // --- Subject-based resources (via getSubject()) ---

    @Nested
    class SubjectBasedResources {

        @Test
        void extract_encounter_returnsPatientUpid() {
            Encounter resource = new Encounter();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_observation_returnsPatientUpid() {
            Observation resource = new Observation();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_condition_returnsPatientUpid() {
            Condition resource = new Condition();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_medicationRequest_returnsPatientUpid() {
            MedicationRequest resource = new MedicationRequest();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_medicationDispense_returnsPatientUpid() {
            MedicationDispense resource = new MedicationDispense();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_serviceRequest_returnsPatientUpid() {
            ServiceRequest resource = new ServiceRequest();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_procedure_returnsPatientUpid() {
            Procedure resource = new Procedure();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_diagnosticReport_returnsPatientUpid() {
            DiagnosticReport resource = new DiagnosticReport();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        // --- Additional subject-based types (auto-supported via reflection) ---

        @Test
        void extract_carePlan_returnsPatientUpid() {
            CarePlan resource = new CarePlan();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_goal_returnsPatientUpid() {
            Goal resource = new Goal();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_medicationAdministration_returnsPatientUpid() {
            MedicationAdministration resource = new MedicationAdministration();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_medicationStatement_returnsPatientUpid() {
            MedicationStatement resource = new MedicationStatement();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_riskAssessment_returnsPatientUpid() {
            RiskAssessment resource = new RiskAssessment();
            resource.setSubject(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }
    }

    // --- Patient-based resources (via getPatient()) ---

    @Nested
    class PatientBasedResources {

        @Test
        void extract_episodeOfCare_returnsPatientUpid() {
            EpisodeOfCare resource = new EpisodeOfCare();
            resource.setPatient(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_immunization_returnsPatientUpid() {
            Immunization resource = new Immunization();
            resource.setPatient(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        // --- Additional patient-based types (auto-supported via reflection) ---

        @Test
        void extract_allergyIntolerance_returnsPatientUpid() {
            AllergyIntolerance resource = new AllergyIntolerance();
            resource.setPatient(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_familyMemberHistory_returnsPatientUpid() {
            FamilyMemberHistory resource = new FamilyMemberHistory();
            resource.setPatient(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_nutritionOrder_returnsPatientUpid() {
            NutritionOrder resource = new NutritionOrder();
            resource.setPatient(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_consent_returnsPatientUpid() {
            Consent resource = new Consent();
            resource.setPatient(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_relatedPerson_returnsPatientUpid() {
            RelatedPerson resource = new RelatedPerson();
            resource.setPatient(new Reference(PATIENT_REF));
            assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
        }
    }

    // --- Patient resource (self-referencing) ---

    @Nested
    class PatientResource {

        @Test
        void extract_patientWithUpidIdentifier_returnsUpidValue() {
            Patient patient = new Patient();
            patient.setId("patient-internal-001");
            patient.addIdentifier(new Identifier()
                    .setSystem(UPID_SYSTEM)
                    .setValue(PATIENT_UPID));

            assertThat(extractor.extract(patient)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_patientWithMultipleIdentifiers_returnsUpidMatch() {
            Patient patient = new Patient();
            patient.setId("patient-internal-001");
            patient.addIdentifier(new Identifier()
                    .setSystem("http://hospital.org/mrn")
                    .setValue("MRN-12345"));
            patient.addIdentifier(new Identifier()
                    .setSystem(UPID_SYSTEM)
                    .setValue(PATIENT_UPID));

            assertThat(extractor.extract(patient)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_patientWithNoUpidIdentifier_fallsBackToId() {
            Patient patient = new Patient();
            patient.setId(PATIENT_UPID);
            patient.addIdentifier(new Identifier()
                    .setSystem("http://hospital.org/mrn")
                    .setValue("MRN-12345"));

            assertThat(extractor.extract(patient)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_patientWithIdOnly_returnsId() {
            Patient patient = new Patient();
            patient.setId(PATIENT_UPID);

            assertThat(extractor.extract(patient)).isEqualTo(PATIENT_UPID);
        }

        @Test
        void extract_patientWithNoIdentifierAndNoId_throwsException() {
            Patient patient = new Patient();

            assertThatThrownBy(() -> extractor.extract(patient))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("No UPID found in Patient resource");
        }
    }

    // --- Reference without Patient/ prefix ---

    @Test
    void extract_referenceWithoutPrefix_returnsBareId() {
        Encounter resource = new Encounter();
        resource.setSubject(new Reference(PATIENT_UPID));
        assertThat(extractor.extract(resource)).isEqualTo(PATIENT_UPID);
    }

    // --- Error cases ---

    @Nested
    class ErrorCases {

        @Test
        void extract_nullResource_throwsPatientIdNotFoundException() {
            assertThatThrownBy(() -> extractor.extract(null))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("null resource");
        }

        @Test
        void extract_resourceWithNoSubjectOrPatient_throwsPatientIdNotFoundException() {
            // Bundle has neither getSubject() nor getPatient()
            Bundle resource = new Bundle();
            resource.setType(Bundle.BundleType.COLLECTION);

            assertThatThrownBy(() -> extractor.extract(resource))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("No getSubject() or getPatient() method found");
        }

        @Test
        void extract_encounterWithNoSubject_throwsPatientIdNotFoundException() {
            Encounter resource = new Encounter();

            assertThatThrownBy(() -> extractor.extract(resource))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("No patient reference found");
        }

        @Test
        void extract_encounterWithEmptyReference_throwsPatientIdNotFoundException() {
            Encounter resource = new Encounter();
            resource.setSubject(new Reference());

            assertThatThrownBy(() -> extractor.extract(resource))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("No patient reference found");
        }

        @Test
        void extract_immunizationWithNoPatient_throwsPatientIdNotFoundException() {
            Immunization resource = new Immunization();

            assertThatThrownBy(() -> extractor.extract(resource))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("No patient reference found");
        }

        @Test
        void extract_episodeOfCareWithNoPatient_throwsPatientIdNotFoundException() {
            EpisodeOfCare resource = new EpisodeOfCare();

            assertThatThrownBy(() -> extractor.extract(resource))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("No patient reference found");
        }
    }
}
