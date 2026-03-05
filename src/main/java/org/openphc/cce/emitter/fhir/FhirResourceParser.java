package org.openphc.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.cce.emitter.exception.FhirMappingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses raw JSON strings into HAPI FHIR {@link IBaseResource} instances
 * and detects the FHIR resource type.
 *
 * <p>Uses the shared {@link FhirContext} singleton for R4 parsing.
 * Throws {@link FhirMappingException} on any parse failure.
 */
@Component
public class FhirResourceParser {

    private static final Logger log = LoggerFactory.getLogger(FhirResourceParser.class);

    private final FhirContext fhirContext;

    public FhirResourceParser(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    /**
     * Parses a FHIR R4 JSON string into an {@link IBaseResource}.
     *
     * @param json the raw FHIR JSON string
     * @return the parsed FHIR resource
     * @throws FhirMappingException if the JSON is null, blank, or cannot be parsed as a valid FHIR R4 resource
     */
    public IBaseResource parse(String json) {
        if (json == null || json.isBlank()) {
            throw new FhirMappingException("FHIR resource JSON is null or blank");
        }

        try {
            IParser parser = fhirContext.newJsonParser();
            IBaseResource resource = parser.parseResource(json);
            log.debug("Parsed FHIR resource: resourceType={}, id={}",
                    resource.fhirType(), resource.getIdElement().getIdPart());
            return resource;
        } catch (DataFormatException e) {
            throw new FhirMappingException("Failed to parse FHIR resource JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Detects the FHIR resource type from a JSON string without full parsing.
     *
     * <p>Parses the resource and returns the {@code resourceType} value
     * (e.g., {@code "Encounter"}, {@code "Observation"}).
     *
     * @param json the raw FHIR JSON string
     * @return the FHIR resource type name
     * @throws FhirMappingException if the JSON cannot be parsed
     */
    public String detectResourceType(String json) {
        IBaseResource resource = parse(json);
        return resource.fhirType();
    }
}
