package org.openphc.cce.emitter.adaptor;

import org.openphc.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.fhir.FhirResourceParser;
import org.openphc.cce.emitter.fhir.PatientIdExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Auto-configures {@link SourceAdaptor} beans from the {@code cce.emitter.sources.*}
 * configuration map.
 *
 * <p>For each entry in {@code cce.emitter.sources}, a {@link ConfiguredSourceAdaptor}
 * is created with the source key and client ID. No separate subclass is needed —
 * the {@link AbstractSourceAdaptor} base class handles FHIR parsing and CloudEvent
 * construction for any source system that sends FHIR R4 resources.
 */
@Configuration
public class SourceAdaptorConfig {

    private static final Logger log = LoggerFactory.getLogger(SourceAdaptorConfig.class);

    @Bean
    public List<SourceAdaptor> sourceAdaptors(
            EmitterProperties emitterProperties,
            FhirResourceParser fhirResourceParser,
            PatientIdExtractor patientIdExtractor,
            CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder) {

        Map<String, EmitterProperties.SourceProperties> sources = emitterProperties.sources();

        if (sources == null || sources.isEmpty()) {
            log.warn("No source systems configured under cce.emitter.sources — " +
                    "all inbound requests will be silently ignored");
            return List.of();
        }

        List<SourceAdaptor> adaptors = sources.entrySet().stream()
                .map(entry -> {
                    String sourceKey = entry.getKey();
                    String clientId = entry.getValue().clientId();
                    log.info("Configuring source adaptor: key='{}', clientId='{}'", sourceKey, clientId);
                    return (SourceAdaptor) new ConfiguredSourceAdaptor(
                            sourceKey, clientId,
                            fhirResourceParser, patientIdExtractor, cloudEventEnvelopeBuilder);
                })
                .toList();

        return adaptors;
    }
}
