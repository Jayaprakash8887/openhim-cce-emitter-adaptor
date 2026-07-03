package org.openphc.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Extracts the facility identifier from FHIR R4 resources.
 *
 * <p>Extraction strategies (tried in order):
 * <ol>
 *   <li>{@code Encounter.location[0].location} — nested component, handled explicitly</li>
 *   <li>{@code getLocationReference()} returning {@code List<Reference>} — e.g. {@code ServiceRequest}</li>
 *   <li>{@code getLocation()} returning a direct {@code Reference} — e.g. {@code Procedure}, {@code Immunization}</li>
 * </ol>
 *
 * <p>Any {@code ResourceType/id} prefix (e.g. {@code Location/0030}, {@code Organization/1302})
 * is stripped generically — the bare ID after the last {@code /} is used for filter comparison.
 *
 * <p>Returns {@code null} if the resource carries no location information — such events
 * pass through the facility filter unconditionally.
 */
@Component
public class FacilityIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(FacilityIdExtractor.class);

    /**
     * Extracts the facility ID from the given FHIR R4 resource.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code Encounter} — reads the nested {@code location[0].location} component</li>
     *   <li>Any resource with {@code locationReference[]} (e.g. {@code ServiceRequest}) —
     *       first entry's reference is used</li>
     *   <li>Any resource with a direct {@code location} {@link Reference}
     *       (e.g. {@code Procedure}, {@code Immunization})</li>
     * </ol>
     *
     * <p>Resources that carry no location information (e.g. {@code Patient},
     * {@code RelatedPerson}, {@code Observation}) return {@code null}, causing the
     * event to pass through the facility filter unconditionally.
     *
     * @param resource the parsed FHIR R4 resource; may be any type
     * @return bare facility ID (e.g. {@code "1302"}), or {@code null} if no location present
     */
    public String extract(IBaseResource resource) {
        if (resource == null) {
            return null;
        }

        // Encounter.location[] holds EncounterLocationComponent (not a plain Reference),
        // so it needs explicit handling rather than the generic reflection path below.
        if (resource instanceof Encounter encounter) {
            return extractFromEncounter(encounter);
        }

        // Try locationReference[] (e.g. ServiceRequest) then location (e.g. Procedure, Immunization)
        // Both List<Reference> and direct Reference shapes are handled by the same reflective helper.
        Reference locationRef = resolveLocationReference(resource, "getLocationReference");
        if (locationRef == null) {
            locationRef = resolveLocationReference(resource, "getLocation");
        }

        return locationRef != null ? extractId(locationRef, resource.fhirType()) : null;
    }

    /**
     * Extracts the facility ID from {@code Encounter.location[0].location}.
     *
     * <p>Example payload fragment:
     * <pre>{@code
     * "location": [{ "location": { "reference": "Location/0030" } }]
     * }</pre>
     * → returns {@code "0030"}
     */
    private String extractFromEncounter(Encounter encounter) {
        List<Encounter.EncounterLocationComponent> locations = encounter.getLocation();
        if (locations == null || locations.isEmpty()) {
            return null;
        }

        Reference locationRef = locations.get(0).getLocation();
        if (locationRef == null) {
            return null;
        }

        return extractId(locationRef, "Encounter");
    }

    /**
     * Extracts the bare facility ID from a {@link Reference}.
     *
     * <p>Strips any {@code ResourceType/} prefix generically — only the segment after
     * the last {@code /} is used, so both {@code "Location/1302"} and
     * {@code "Organization/1302"} produce {@code "1302"}.
     *
     * <p>Falls back to {@code identifier.value} when no reference string is set
     * (e.g. {@code "locationReference": [{ "identifier": { "value": "1302" } }]}).
     *
     * @param locationRef  the FHIR Reference to extract from
     * @param resourceType the FHIR resource type — used only for debug logging
     * @return bare facility ID, or {@code null} if the reference carries no usable value
     */
    private String extractId(Reference locationRef, String resourceType) {
        if (locationRef.hasReference()) {
            String rawReference = locationRef.getReference(); // e.g. "Location/1302" or "Organization/1302"
            String facilityId = rawReference.contains("/") ? rawReference.substring(rawReference.lastIndexOf('/') + 1) : rawReference;
            if (!facilityId.isBlank()) {
                log.debug("Extracted facility ID '{}' from {} location reference", facilityId, resourceType);
                return facilityId;
            }
        }

        // Fallback: identifier.value when reference string is absent
        if (locationRef.hasIdentifier() && locationRef.getIdentifier().hasValue()) {
            String facilityId = locationRef.getIdentifier().getValue();
            log.debug("Extracted facility ID '{}' from {} location identifier", facilityId, resourceType);
            return facilityId;
        }

        return null;
    }

    /**
     * Reflectively invokes {@code methodName()} on {@code resource} and resolves the result
     * to a {@link Reference}, handling both field shapes used across FHIR R4 resources:
     *
     * <ul>
     *   <li>{@code List<Reference>} — iterates entries and returns the first one that has a
     *       usable reference string or identifier (e.g. {@code getLocationReference()} on
     *       {@code ServiceRequest}):
     *       <pre>{@code "locationReference": [{ "reference": "Location/1302" }] }</pre></li>
     *   <li>Direct {@code Reference} — returned as-is if it has a reference or identifier
     *       (e.g. {@code getLocation()} on {@code Procedure} or {@code Immunization}):
     *       <pre>{@code "location": { "reference": "Location/0030" } }</pre></li>
     * </ul>
     *
     * @param resource    the FHIR resource to introspect
     * @param methodName  no-arg getter to invoke (e.g. {@code "getLocationReference"}, {@code "getLocation"})
     * @return the first usable {@link Reference}, or {@code null} if none found
     */
    private Reference resolveLocationReference(IBaseResource resource, String methodName) {
        try {
            // Reflectively invoke the getter (e.g. getLocationReference, getLocation)
            Method getter = resource.getClass().getMethod(methodName);
            Object result = getter.invoke(resource);

            // List<Reference> shape — e.g. ServiceRequest.locationReference[]
            // Iterate all entries and return the first one that carries a usable value.
            // Source systems may send Organization/ or Location/ prefixes — accept any.
            if (result instanceof List<?> locationList) {
                return locationList.stream()
                        .filter(item -> item instanceof Reference)       // skip non-Reference entries
                        .map(item -> (Reference) item)
                        .filter(locationRef -> locationRef.hasReference() || locationRef.hasIdentifier()) // must have a value
                        .findFirst()
                        .orElse(null);
            }

            // Direct Reference shape — e.g. Procedure.location, Immunization.location
            // Return only if the reference actually carries a value; null otherwise.
            if (result instanceof Reference locationRef) {
                return (locationRef.hasReference() || locationRef.hasIdentifier()) ? locationRef : null;
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            // Method does not exist on this resource type — not applicable, fall through
        }
        return null;
    }
}
