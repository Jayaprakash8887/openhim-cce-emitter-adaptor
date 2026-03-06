package org.openphc.cce.emitter.adaptor;

import org.openphc.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.cce.emitter.fhir.FhirResourceParser;
import org.openphc.cce.emitter.fhir.PatientIdExtractor;

/**
 * A concrete {@link AbstractSourceAdaptor} instantiated from configuration.
 *
 * <p>Created by {@link SourceAdaptorConfig} for each entry in
 * {@code cce.emitter.sources.*}. No additional customization is needed —
 * the abstract base class handles all FHIR-to-CloudEvent transformation logic.
 */
class ConfiguredSourceAdaptor extends AbstractSourceAdaptor {

    ConfiguredSourceAdaptor(
            String sourceKey,
            String clientId,
            FhirResourceParser fhirResourceParser,
            PatientIdExtractor patientIdExtractor,
            CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder) {
        super(sourceKey, clientId, fhirResourceParser, patientIdExtractor, cloudEventEnvelopeBuilder);
    }
}
