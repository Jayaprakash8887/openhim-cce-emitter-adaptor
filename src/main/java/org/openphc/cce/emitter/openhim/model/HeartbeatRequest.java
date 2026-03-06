package org.openphc.cce.emitter.openhim.model;

/**
 * Heartbeat request body sent to {@code POST /mediators/{urn}/heartbeat}
 * on the OpenHIM Core API.
 *
 * @param uptime mediator uptime in milliseconds since application startup
 */
public record HeartbeatRequest(long uptime) {}
