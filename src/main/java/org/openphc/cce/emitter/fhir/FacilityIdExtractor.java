package org.openphc.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Extracts the facility identifier from FHIR R4 resources.
 *
 * <p>Extraction strategies (tried in order):
 * <ol>
 *   <li>Encounter {@code location[0].location.reference} — strips {@code "Location/"} prefix</li>
 *   <li>Encounter {@code location[0].location.identifier.value} — direct identifier value</li>
 * </ol>
 *
 * <p>Returns {@code null} if the resource has no facility/location information.
 */
@Component
public class FacilityIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(FacilityIdExtractor.class);
    private static final String LOCATION_PREFIX = "Location/";

    /**
     * Extracts the facility ID from the given FHIR resource.
     *
     * @param resource the parsed FHIR R4 resource
     * @return the facility ID, or {@code null} if not available
     */
    public String extract(IBaseResource resource) {
        if (resource == null) {
            return null;
        }

        if (resource instanceof Encounter encounter) {
            return extractFromEncounter(encounter);
        }

        // For non-Encounter resources (Observation, Condition, etc.),
        // facility is typically resolved via the parent Encounter.
        // Return null — the collector will associate via correlation.
        return null;
    }

    private String extractFromEncounter(Encounter encounter) {
        List<Encounter.EncounterLocationComponent> locations = encounter.getLocation();
        if (locations == null || locations.isEmpty()) {
            return null;
        }

        Reference locationRef = locations.get(0).getLocation();
        if (locationRef == null) {
            return null;
        }

        // Strategy 1: Extract from reference (e.g., "Location/0030" → "0030")
        if (locationRef.hasReference()) {
            String ref = locationRef.getReference();
            String facilityId = ref.startsWith(LOCATION_PREFIX)
                    ? ref.substring(LOCATION_PREFIX.length())
                    : ref;
            if (!facilityId.isBlank()) {
                log.debug("Extracted facility ID '{}' from Encounter location reference", facilityId);
                return facilityId;
            }
        }

        // Strategy 2: Extract from identifier value
        if (locationRef.hasIdentifier() && locationRef.getIdentifier().hasValue()) {
            String value = locationRef.getIdentifier().getValue();
            log.debug("Extracted facility ID '{}' from Encounter location identifier", value);
            return value;
        }

        return null;
    }
}
