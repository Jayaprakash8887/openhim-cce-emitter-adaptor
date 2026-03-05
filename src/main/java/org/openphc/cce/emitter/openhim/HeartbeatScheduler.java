package org.openphc.cce.emitter.openhim;

import org.openphc.cce.emitter.config.OpenHimProperties;
import org.openphc.cce.emitter.openhim.model.HeartbeatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends periodic heartbeats to OpenHIM Core to indicate this mediator is alive.
 *
 * <p>Enabled only when {@code openhim.heartbeat.enabled=true}. Heartbeats are sent
 * at a fixed interval configured by {@code openhim.heartbeat.interval-seconds}.
 *
 * <p>Heartbeat failure is <strong>non-fatal</strong> — the application continues
 * running and logs a warning.
 */
@Component
@ConditionalOnProperty(name = "openhim.heartbeat.enabled", havingValue = "true")
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final RestClient coreApiRestClient;
    private final OpenHimProperties openHimProperties;
    private final long startTimeMs;

    public HeartbeatScheduler(
            @Qualifier("coreApiRestClient") RestClient coreApiRestClient,
            OpenHimProperties openHimProperties) {
        this.coreApiRestClient = coreApiRestClient;
        this.openHimProperties = openHimProperties;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * Sends a heartbeat to OpenHIM Core at the configured interval.
     *
     * <p>The heartbeat includes the mediator's uptime in milliseconds.
     */
    @Scheduled(fixedDelayString = "${openhim.heartbeat.interval-seconds:10}000")
    public void sendHeartbeat() {
        String urn = openHimProperties.mediator().urn();
        long uptime = System.currentTimeMillis() - startTimeMs;

        try {
            HeartbeatRequest heartbeat = new HeartbeatRequest(uptime);

            coreApiRestClient.post()
                    .uri("/mediators/{urn}/heartbeat", urn)
                    .body(heartbeat)
                    .retrieve()
                    .toBodilessEntity();

            log.debug("Heartbeat sent for '{}' — uptime={}ms", urn, uptime);
        } catch (Exception e) {
            log.warn("Heartbeat failed for '{}': {}", urn, e.getMessage());
        }
    }

    /**
     * Returns the mediator uptime in milliseconds.
     * Exposed for testing purposes.
     */
    long getUptimeMs() {
        return System.currentTimeMillis() - startTimeMs;
    }
}
