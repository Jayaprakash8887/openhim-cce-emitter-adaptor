package org.openphc.cce.emitter.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "cce.emitter.facility-filter")
public record FacilityFilterProperties(List<String> ids) {
    public FacilityFilterProperties {
        if (ids == null) ids = List.of();
    }
}
