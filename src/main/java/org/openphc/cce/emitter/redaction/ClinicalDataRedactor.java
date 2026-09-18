package org.openphc.cce.emitter.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.openphc.cce.emitter.redaction.ClinicalDataRedactionProperties.ResourceRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Strips clinical findings from a FHIR resource before it leaves the adaptor.
 *
 * <p>Applied at the last step of the inbound pipeline — after the patient UPID, facility ID and
 * clinical timestamp have already been extracted from the complete resource — so redaction cannot
 * affect routing, facility attribution or SLA timing. What leaves the adaptor, and therefore what
 * is persisted in {@code inbound_event_log}, Kafka, {@code compliance_event_log} and the ClickHouse
 * mirror, is the minimised payload.
 *
 * <p>Which fields are removed depends on the resource type, because the same element carries very
 * different sensitivity per resource — see {@link ClinicalDataRedactionProperties}.
 *
 * <p>Redaction is deliberately shallow: root-level fields plus a small set of explicit nested
 * paths. A recursive scrub would also strip the {@code valueString} inside {@code extension[]},
 * which is how {@code source-facility} attribution works, and the {@code coding}/{@code display}
 * elements protocol matching and the dashboard depend on.
 *
 * <p>The result stays structurally valid FHIR R4 — the collector re-parses every payload with HAPI
 * and rejects anything malformed as {@code INVALID_FHIR}.
 */
@Component
public class ClinicalDataRedactor {

    private static final Logger log = LoggerFactory.getLogger(ClinicalDataRedactor.class);

    private final boolean enabled;
    /** resourceType → fields to remove. Includes the {@code *} fallback entry. */
    private final Map<String, Set<String>> fieldsByResourceType;
    private final Set<String> fallbackFields;
    private final List<String[]> removePaths;
    private final MeterRegistry meterRegistry;

    public ClinicalDataRedactor(ClinicalDataRedactionProperties properties, MeterRegistry meterRegistry) {
        this.enabled = Boolean.TRUE.equals(properties.enabled());
        this.meterRegistry = meterRegistry;

        this.fieldsByResourceType = new LinkedHashMap<>();
        for (ResourceRule rule : properties.rules()) {
            if (rule == null || rule.resourceType() == null || rule.fields() == null) {
                continue;
            }
            fieldsByResourceType.put(
                    rule.resourceType().trim(),
                    rule.fields().stream()
                            .map(String::trim)
                            .filter(f -> !f.isEmpty())
                            .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        this.fallbackFields = fieldsByResourceType.getOrDefault(
                ClinicalDataRedactionProperties.ANY_RESOURCE_TYPE, Set.of());

        this.removePaths = properties.removePaths().stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(p -> p.split("\\."))
                .toList();

        if (enabled) {
            log.info("Clinical-data redaction ACTIVE — rules for {} resource type(s) [{}], {} nested path(s)",
                    fieldsByResourceType.size(),
                    String.join(", ", fieldsByResourceType.keySet()),
                    removePaths.size());
        } else {
            log.warn("Clinical-data redaction DISABLED — full clinical payloads will be forwarded to the collector");
        }
    }

    /**
     * Returns the resource with clinical content removed, per the rule for its resource type.
     *
     * @param data the parsed FHIR resource destined for the CloudEvent {@code data} field
     * @return the same node, mutated in place; returned for call-site readability
     */
    public JsonNode redact(JsonNode data) {
        if (!enabled || data == null || !data.isObject()) {
            return data;
        }

        ObjectNode resource = (ObjectNode) data;
        String resourceType = resource.path("resourceType").asText("");
        Set<String> fields = fieldsByResourceType.getOrDefault(resourceType, fallbackFields);

        List<String> removed = new ArrayList<>();

        for (String field : fields) {
            if (resource.has(field)) {
                resource.remove(field);
                removed.add(field);
            }
        }

        for (String[] path : removePaths) {
            if (removeNested(resource, path)) {
                removed.add(String.join(".", path));
            }
        }

        if (!removed.isEmpty()) {
            Counter.builder("cce.emitter.events.redacted.total")
                    .description("Inbound events from which clinical fields were removed")
                    .tag("resource_type", resourceType.isEmpty() ? "unknown" : resourceType)
                    .register(meterRegistry)
                    .increment();
            // Field NAMES only — never their values, which are the clinical data itself.
            log.debug("Redacted {} field(s) from {}: {}", removed.size(), resourceType, removed);
        }

        return resource;
    }

    /**
     * Removes a dot-delimited nested field, walking only through object nodes.
     *
     * @return {@code true} if the field existed and was removed
     */
    private boolean removeNested(ObjectNode root, String[] path) {
        ObjectNode cursor = root;
        for (int i = 0; i < path.length - 1; i++) {
            JsonNode next = cursor.get(path[i]);
            if (next == null || !next.isObject()) {
                return false;
            }
            cursor = (ObjectNode) next;
        }
        String leaf = path[path.length - 1];
        if (cursor.has(leaf)) {
            cursor.remove(leaf);
            return true;
        }
        return false;
    }
}
