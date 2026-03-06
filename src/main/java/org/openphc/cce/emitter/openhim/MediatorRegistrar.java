package org.openphc.cce.emitter.openhim;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.openphc.cce.emitter.config.MediatorProperties;
import org.openphc.cce.emitter.config.OpenHimProperties;
import org.openphc.cce.emitter.openhim.model.MediatorDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Registers this mediator with OpenHIM Core on application startup.
 *
 * <p>On {@link ApplicationReadyEvent}, builds a {@link MediatorDescriptor} from
 * configuration properties and POSTs it to {@code /mediators} on the OpenHIM Core API.
 *
 * <p>Registration failure is <strong>non-fatal</strong> — the application continues
 * running and logs a warning. This allows the adaptor to function even when
 * OpenHIM Core is temporarily unavailable.
 */
@Component
public class MediatorRegistrar {

    private static final Logger log = LoggerFactory.getLogger(MediatorRegistrar.class);

    private static final String MEDIATOR_DESCRIPTION =
            "Wraps FHIR R4 resources into CloudEvents for CCE Collector";

    private final RestClient coreApiRestClient;
    private final OpenHimProperties openHimProperties;
    private final MediatorProperties mediatorProperties;
    private final ObjectMapper objectMapper;
    private final int serverPort;

    public MediatorRegistrar(
            @Qualifier("coreApiRestClient") RestClient coreApiRestClient,
            OpenHimProperties openHimProperties,
            MediatorProperties mediatorProperties,
            ObjectMapper objectMapper,
            @Value("${server.port}") int serverPort) {
        this.coreApiRestClient = coreApiRestClient;
        this.openHimProperties = openHimProperties;
        this.mediatorProperties = mediatorProperties;
        this.objectMapper = objectMapper;
        this.serverPort = serverPort;
    }

    /**
     * Registers the mediator with OpenHIM Core on application startup.
     * Non-fatal — logs warning on failure.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        String urn = mediatorProperties.urn();
        log.info("Registering mediator '{}' with OpenHIM Core at {}",
                urn, openHimProperties.core().apiUrl());

        try {
            MediatorDescriptor descriptor = buildDescriptor();
            String body = objectMapper.writeValueAsString(descriptor);

            coreApiRestClient.post()
                    .uri("/mediators")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully registered mediator '{}' with OpenHIM Core", urn);
        } catch (Exception e) {
            log.warn("Failed to register mediator '{}' with OpenHIM Core: {}. " +
                    "Application will continue running — registration will be retried on next heartbeat.",
                    urn, e.getMessage());
        }
    }

    /**
     * Builds the {@link MediatorDescriptor} from configuration properties.
     */
    MediatorDescriptor buildDescriptor() {
        var mp = mediatorProperties.endpoint();

        return MediatorDescriptor.builder()
                .urn(mediatorProperties.urn())
                .version(mediatorProperties.version())
                .name(mediatorProperties.name())
                .description(MEDIATOR_DESCRIPTION)
                .defaultChannelConfig(List.of())
                .endpoints(List.of(
                        MediatorDescriptor.Endpoint.builder()
                                .name(mediatorProperties.name())
                                .host(mp.host())
                                .path(mp.path())
                                .port(serverPort)
                                .primary(true)
                                .type(mp.type())
                                .build()
                ))
                .build();
    }
}
