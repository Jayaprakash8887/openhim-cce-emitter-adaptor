package org.openphc.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Reference;
import org.openphc.cce.emitter.exception.PatientIdNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Extracts the patient UPID from FHIR R4 resources using reflection.
 *
 * <p>The patient UPID is extracted from the {@code subject.reference} or
 * {@code patient.reference} field depending on the resource type. The
 * {@code "Patient/"} prefix is stripped to return the bare identifier.
 *
 * <p>Extraction strategy (tried in order):
 * <ol>
 *   <li>{@code getSubject()} — used by most clinical resources
 *       (Encounter, Observation, Condition, MedicationRequest, MedicationDispense,
 *       ServiceRequest, Procedure, DiagnosticReport, CarePlan, CareTeam, Goal,
 *       MedicationAdministration, MedicationStatement, RiskAssessment, etc.)</li>
 *   <li>{@code getPatient()} — used by resources that model the patient relationship directly
 *       (EpisodeOfCare, Immunization, AllergyIntolerance, FamilyMemberHistory,
 *       NutritionOrder, Consent, etc.)</li>
 * </ol>
 *
 * <p>This reflection-based approach automatically supports any FHIR R4 resource that
 * has a {@code getSubject()} or {@code getPatient()} method returning a {@link Reference}.
 * No code changes are needed when new resource types are introduced.
 *
 * @throws PatientIdNotFoundException if the reference is missing or cannot be extracted
 */
@Component
public class PatientIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(PatientIdExtractor.class);
    private static final String PATIENT_PREFIX = "Patient/";

    /** Method names to try, in priority order. */
    private static final String[] ACCESSOR_METHODS = {"getSubject", "getPatient"};

    /**
     * Extracts the patient UPID from the given FHIR resource.
     *
     * <p>Tries {@code getSubject()} first, then {@code getPatient()}.
     * The first method that exists and returns a non-empty {@link Reference} wins.
     *
     * @param resource the parsed FHIR R4 resource
     * @return the patient UPID (without {@code "Patient/"} prefix)
     * @throws PatientIdNotFoundException if the patient reference cannot be found
     */
    public String extract(IBaseResource resource) {
        if (resource == null) {
            throw new PatientIdNotFoundException("Cannot extract patient ID from null resource");
        }

        String resourceType = resource.fhirType();
        Reference reference = extractReference(resource, resourceType);

        if (reference == null || !reference.hasReference()) {
            throw new PatientIdNotFoundException(
                    "No patient reference found in " + resourceType + " resource");
        }

        String rawReference = reference.getReference();
        String patientId = stripPatientPrefix(rawReference);

        if (patientId.isBlank()) {
            throw new PatientIdNotFoundException(
                    "Patient reference is blank in " + resourceType + " resource");
        }

        log.debug("Extracted patient UPID '{}' from {} resource", patientId, resourceType);
        return patientId;
    }

    /**
     * Attempts to extract a patient {@link Reference} by reflectively invoking
     * {@code getSubject()} or {@code getPatient()} on the resource.
     */
    private Reference extractReference(IBaseResource resource, String resourceType) {
        for (String methodName : ACCESSOR_METHODS) {
            Reference ref = invokeReferenceAccessor(resource, methodName);
            if (ref != null) {
                return ref;
            }
        }

        throw new PatientIdNotFoundException(
                "No getSubject() or getPatient() method found on " + resourceType
                        + " resource — cannot extract patient reference");
    }

    /**
     * Reflectively invokes the named method on the resource. Returns the {@link Reference}
     * if the method exists and returns a {@code Reference}; otherwise returns {@code null}.
     */
    private Reference invokeReferenceAccessor(IBaseResource resource, String methodName) {
        try {
            Method method = resource.getClass().getMethod(methodName);
            if (Reference.class.isAssignableFrom(method.getReturnType())) {
                return (Reference) method.invoke(resource);
            }
        } catch (NoSuchMethodException e) {
            // Method doesn't exist on this resource type — try next
        } catch (ReflectiveOperationException e) {
            log.warn("Failed to invoke {}() on {}: {}",
                    methodName, resource.fhirType(), e.getMessage());
        }
        return null;
    }

    private String stripPatientPrefix(String reference) {
        if (reference.startsWith(PATIENT_PREFIX)) {
            return reference.substring(PATIENT_PREFIX.length());
        }
        // Return as-is if no prefix — some systems may use a bare ID
        return reference;
    }
}
