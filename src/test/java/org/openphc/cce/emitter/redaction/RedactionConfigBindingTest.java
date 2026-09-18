package org.openphc.cce.emitter.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the <b>real</b> {@code application.yml} and asserts on the rules that come out.
 *
 * <p>Redaction is a compliance control whose failure mode is silent. A typo in the YAML, or the
 * folded comma-separated {@code fields} scalars not splitting the way we assume, would not throw —
 * it would quietly forward clinical data to the Collector. {@link ClinicalDataRedactorTest} proves
 * the redactor honours the rules it is given; this proves the rules production actually ships are
 * the right ones.
 */
class RedactionConfigBindingTest {

    private static ClinicalDataRedactionProperties properties;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void bindRealApplicationYml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addFirst);

        properties = Binder.get(environment)
                .bind("cce.emitter.redaction", ClinicalDataRedactionProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "cce.emitter.redaction is absent from application.yml — redaction would fall "
                                + "back to Java defaults with nothing auditable in configuration"));
    }

    private static Map<String, List<String>> rulesByType() {
        return properties.rules().stream().collect(Collectors.toMap(
                ClinicalDataRedactionProperties.ResourceRule::resourceType,
                ClinicalDataRedactionProperties.ResourceRule::fields,
                (first, second) -> first));
    }

    @Test
    @DisplayName("redaction is enabled in the shipped configuration")
    void enabledInShippedConfig() {
        assertThat(properties.enabled()).isTrue();
    }

    @Test
    @DisplayName("folded comma-separated field lists split into individual field names")
    void foldedScalarsSplitIntoFieldNames() {
        List<String> observation = rulesByType().get("Observation");

        // Had the folded scalar not split, this would be a single comma-joined string.
        assertThat(observation).contains("valueString", "valueCodeableConcept", "component");
        assertThat(observation).allSatisfy(field -> {
            assertThat(field).doesNotContain(",");
            assertThat(field).isEqualTo(field.trim());
        });
    }

    @Test
    @DisplayName("every resource type seen in Rwanda is configured, plus the wildcard fallback")
    void allObservedResourceTypesConfigured() {
        assertThat(rulesByType().keySet()).contains(
                // PROD
                "Observation", "Encounter", "ServiceRequest", "MedicationRequest", "Condition",
                "MedicationDispense", "Consent", "Procedure", "MedicationAdministration",
                "AllergyIntolerance",
                // UAT only
                "ImagingStudy",
                ClinicalDataRedactionProperties.ANY_RESOURCE_TYPE);
    }

    @Test
    @DisplayName("code is stripped where it is the finding, kept where it is the trigger key")
    void codeHandledPerResourceType() {
        Map<String, List<String>> rules = rulesByType();

        for (String type : List.of("Condition", "AllergyIntolerance", "ServiceRequest", "Procedure")) {
            assertThat(rules.get(type))
                    .as("%s.code is the clinical finding and must be stripped", type)
                    .contains("code");
        }

        assertThat(rules.get("Observation"))
                .as("Observation.code is the LOINC trigger key — stripping it breaks protocol matching")
                .doesNotContain("code");
    }

    @Test
    @DisplayName("drug identity is stripped from every medication resource")
    void medicationResourcesStripTheDrug() {
        Map<String, List<String>> rules = rulesByType();
        for (String type : List.of("MedicationRequest", "MedicationDispense", "MedicationAdministration")) {
            assertThat(rules.get(type))
                    .as("%s must not forward which drug was involved", type)
                    .contains("medicationCodeableConcept");
        }
    }

    @Test
    @DisplayName("every rule strips the core value[x] set")
    void everyRuleStripsValues() {
        rulesByType().forEach((type, fields) -> assertThat(fields)
                .as("rule '%s' must strip value[x]", type)
                .contains("valueString", "valueQuantity", "valueCodeableConcept"));
    }

    @Test
    @DisplayName("patient-name paths are configured; the UPID reference is not")
    void patientNamePathsConfigured() {
        assertThat(properties.removePaths()).contains("subject.display", "patient.display");
        assertThat(properties.removePaths()).doesNotContain("subject.reference", "patient.reference");
    }

    @Test
    @DisplayName("the shipped YAML redacts a real Condition end to end")
    void shippedConfigRedactsARealCondition() throws IOException {
        ClinicalDataRedactor redactor = new ClinicalDataRedactor(properties, new SimpleMeterRegistry());

        JsonNode redacted = redactor.redact(MAPPER.readTree("""
                {
                  "resourceType": "Condition",
                  "id": "abc-123",
                  "clinicalStatus": {"coding": [{"code": "active"}]},
                  "code": {"coding": [{"code": "5A11", "display": "Type 2 diabetes mellitus"}],
                           "text": "Type 2 diabetes mellitus"},
                  "subject": {"reference": "Patient/241018-1228-7904", "display": "Jane Doe"},
                  "recordedDate": "2026-07-24T10:51:53+02:00"
                }
                """));

        assertThat(redacted.has("code")).as("the diagnosis").isFalse();
        assertThat(redacted.path("subject").has("display")).as("the patient name").isFalse();

        // What CCE needs to keep working
        assertThat(redacted.path("subject").path("reference").asText()).isEqualTo("Patient/241018-1228-7904");
        assertThat(redacted.path("clinicalStatus").path("coding").get(0).path("code").asText()).isEqualTo("active");
        assertThat(redacted.path("recordedDate").asText()).isEqualTo("2026-07-24T10:51:53+02:00");
    }
}
