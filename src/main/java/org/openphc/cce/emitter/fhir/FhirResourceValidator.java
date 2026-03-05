package org.openphc.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.cce.emitter.exception.FhirMappingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates the structural correctness of parsed FHIR R4 resources.
 *
 * <p>Uses the HAPI FHIR {@link FhirValidator} for structural validation.
 * Warning-level issues are logged; error-level issues cause a
 * {@link FhirMappingException} to be thrown.
 */
@Component
public class FhirResourceValidator {

    private static final Logger log = LoggerFactory.getLogger(FhirResourceValidator.class);

    private final FhirValidator validator;

    public FhirResourceValidator(FhirContext fhirContext) {
        this.validator = fhirContext.newValidator();
    }

    /**
     * Validates a FHIR resource for structural correctness.
     *
     * <p>Warnings are logged but do not cause failure.
     * Errors and fatal issues throw {@link FhirMappingException}.
     *
     * @param resource the parsed FHIR resource to validate
     * @throws FhirMappingException if the resource has structural errors
     */
    public void validate(IBaseResource resource) {
        if (resource == null) {
            throw new FhirMappingException("Cannot validate null FHIR resource");
        }

        ValidationResult result = validator.validateWithResult(resource);
        List<SingleValidationMessage> messages = result.getMessages();

        for (SingleValidationMessage message : messages) {
            switch (message.getSeverity()) {
                case ERROR, FATAL ->
                        log.error("FHIR validation {}: {} at {}",
                                message.getSeverity(), message.getMessage(), message.getLocationString());
                case WARNING ->
                        log.warn("FHIR validation warning: {} at {}",
                                message.getMessage(), message.getLocationString());
                case INFORMATION ->
                        log.debug("FHIR validation info: {} at {}",
                                message.getMessage(), message.getLocationString());
            }
        }

        if (!result.isSuccessful()) {
            String errorSummary = messages.stream()
                    .filter(m -> m.getSeverity() == ca.uhn.fhir.validation.ResultSeverityEnum.ERROR
                            || m.getSeverity() == ca.uhn.fhir.validation.ResultSeverityEnum.FATAL)
                    .map(SingleValidationMessage::getMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Unknown validation error");

            throw new FhirMappingException(
                    "FHIR resource validation failed for " + resource.fhirType() + ": " + errorSummary);
        }

        log.debug("FHIR resource validation passed for {}", resource.fhirType());
    }
}
