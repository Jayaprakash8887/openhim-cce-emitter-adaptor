package org.openphc.cce.emitter.adaptor;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.cce.emitter.cloudevents.EventIdGenerator;
import org.openphc.cce.emitter.exception.FhirMappingException;
import org.openphc.cce.emitter.exception.PatientIdNotFoundException;
import org.openphc.cce.emitter.fhir.FhirResourceParser;
import org.openphc.cce.emitter.fhir.PatientIdExtractor;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.model.SourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link AbstractSourceAdaptor} via the concrete
 * {@link ConfiguredSourceAdaptor} class.
 *
 * <p>Uses real {@link FhirResourceParser}, {@link PatientIdExtractor}, and
 * {@link CloudEventEnvelopeBuilder} instances (not mocks) to verify the
 * full FHIR-to-CloudEvent transformation pipeline.
 */
class AbstractSourceAdaptorTest {

    private static final String SOURCE_KEY = "ebuzima";
    private static final String CLIENT_ID = "ebuzima-emr-client";
    private static final String PATIENT_UPID = "260225-0002-5501";

    private ConfiguredSourceAdaptor adaptor;

    private String encounterJson;
    private String observationJson;
    private String bundleJson;

    @BeforeEach
    void setUp() throws IOException {
        FhirContext fhirContext = FhirContext.forR4();
        FhirResourceParser fhirResourceParser = new FhirResourceParser(fhirContext);
        PatientIdExtractor patientIdExtractor = new PatientIdExtractor();
        ObjectMapper objectMapper = new ObjectMapper();
        EventIdGenerator idGenerator = new EventIdGenerator();
        CloudEventEnvelopeBuilder envelopeBuilder = new CloudEventEnvelopeBuilder(idGenerator, objectMapper);

        adaptor = new ConfiguredSourceAdaptor(
                SOURCE_KEY, CLIENT_ID,
                fhirResourceParser, patientIdExtractor, envelopeBuilder);

        encounterJson = loadFixture("ebuzima/fhir-encounter.json");
        observationJson = loadFixture("ebuzima/fhir-observation.json");
        bundleJson = loadFixture("ebuzima/fhir-bundle.json");
    }

    // ==================== canHandle() ====================

    @Nested
    class CanHandle {

        @Test
        void matchesByOpenHimClientIdHeader() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            assertThat(adaptor.canHandle(request)).isTrue();
        }

        @Test
        void matchesBySourceSystemHeader() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-Source-System", SOURCE_KEY),
                    "/inbound");

            assertThat(adaptor.canHandle(request)).isTrue();
        }

        @Test
        void sourceSystemHeaderIsCaseInsensitive() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-Source-System", "EBUZIMA"),
                    "/inbound");

            assertThat(adaptor.canHandle(request)).isTrue();
        }

        @Test
        void clientIdHeaderTakesPriorityOverSourceSystem() {
            // Both headers present — ClientID should be checked first
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-System", "wrong-source"),
                    "/inbound");

            assertThat(adaptor.canHandle(request)).isTrue();
        }

        @Test
        void noMatchingHeaders_returnsFalse() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", "other-client"),
                    "/inbound");

            assertThat(adaptor.canHandle(request)).isFalse();
        }

        @Test
        void noHeaders_returnsFalse() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(),
                    "/inbound");

            assertThat(adaptor.canHandle(request)).isFalse();
        }

        @Test
        void wrongClientId_fallsBackToSourceSystem() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", "wrong-client",
                            "X-Source-System", SOURCE_KEY),
                    "/inbound");

            assertThat(adaptor.canHandle(request)).isTrue();
        }
    }

    // ==================== adapt() — Encounter ====================

    @Nested
    class AdaptEncounter {

        @Test
        void encounter_producesSingleCloudEvent() {
            InboundRequest request = buildRequest(encounterJson);

            List<CloudEventDto> events = adaptor.adapt(request);

            assertThat(events).hasSize(1);
        }

        @Test
        void encounter_hasCorrectType() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getType()).isEqualTo("Encounter");
        }

        @Test
        void encounter_hasCorrectSubject() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getSubject()).isEqualTo(PATIENT_UPID);
        }

        @Test
        void encounter_hasCorrectSource() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getSource()).isEqualTo(SOURCE_KEY);
        }

        @Test
        void encounter_hasSpecVersion() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getSpecversion()).isEqualTo("1.0");
        }

        @Test
        void encounter_hasDataContentType() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getDatacontenttype()).isEqualTo("application/fhir+json");
        }

        @Test
        void encounter_hasEventId() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getId()).isNotNull().isNotBlank();
        }

        @Test
        void encounter_hasTimestamp() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getTime()).isNotNull().isNotBlank();
        }

        @Test
        void encounter_hasDataPayload() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getData()).isNotNull();
            assertThat(event.getData().get("resourceType").asText()).isEqualTo("Encounter");
        }
    }

    // ==================== adapt() — Observation ====================

    @Nested
    class AdaptObservation {

        @Test
        void observation_producesSingleCloudEvent() {
            InboundRequest request = buildRequest(observationJson);

            List<CloudEventDto> events = adaptor.adapt(request);

            assertThat(events).hasSize(1);
        }

        @Test
        void observation_hasCorrectType() {
            InboundRequest request = buildRequest(observationJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getType()).isEqualTo("Observation");
        }

        @Test
        void observation_hasCorrectSubject() {
            InboundRequest request = buildRequest(observationJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getSubject()).isEqualTo(PATIENT_UPID);
        }

        @Test
        void observation_dataContainsObservationResource() {
            InboundRequest request = buildRequest(observationJson);

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getData().get("resourceType").asText()).isEqualTo("Observation");
            assertThat(event.getData().get("id").asText()).isEqualTo("obs-lab-hb-001");
        }
    }

    // ==================== adapt() — Bundle (silently ignored) ====================

    @Nested
    class AdaptBundle {

        @Test
        void bundle_returnsEmptyList() {
            InboundRequest request = buildRequest(bundleJson);

            List<CloudEventDto> events = adaptor.adapt(request);

            assertThat(events).isEmpty();
        }
    }

    // ==================== adapt() — Non-FHIR payload ====================

    @Nested
    class AdaptNonFhir {

        @Test
        void nonFhirPayload_returnsEmptyList() {
            InboundRequest request = InboundRequest.from(
                    "{\"message\": \"hello\"}",
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            List<CloudEventDto> events = adaptor.adapt(request);

            assertThat(events).isEmpty();
        }

        @Test
        void nullBody_returnsEmptyList() {
            InboundRequest request = InboundRequest.from(
                    null,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            List<CloudEventDto> events = adaptor.adapt(request);

            assertThat(events).isEmpty();
        }
    }

    // ==================== adapt() — Malformed FHIR ====================

    @Nested
    class AdaptMalformedFhir {

        @Test
        void malformedFhirJson_throwsFhirMappingException() {
            // Contains "resourceType" so passes containsFhirResource() check,
            // but is not valid FHIR JSON
            String malformed = "{\"resourceType\": \"Encounter\", \"invalidField\": [}";
            InboundRequest request = InboundRequest.from(
                    malformed,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            assertThatThrownBy(() -> adaptor.adapt(request))
                    .isInstanceOf(FhirMappingException.class);
        }
    }

    // ==================== adapt() — Missing patient reference ====================

    @Nested
    class AdaptMissingPatient {

        @Test
        void encounterWithoutSubject_throwsPatientIdNotFoundException() {
            String noSubjectEncounter = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-no-subject",
                      "status": "in-progress",
                      "class": {
                        "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                        "code": "AMB"
                      }
                    }
                    """;
            InboundRequest request = InboundRequest.from(
                    noSubjectEncounter,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            assertThatThrownBy(() -> adaptor.adapt(request))
                    .isInstanceOf(PatientIdNotFoundException.class);
        }
    }

    // ==================== buildSourceMetadata() ====================

    @Nested
    class BuildSourceMetadata {

        @Test
        void extractsAllHeadersIntoMetadata() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Facility-Id", "0002",
                            "X-Source-Event-Id", "enc-visit-001",
                            "X-Correlation-Id", "corr-abc-123",
                            "Authorization", "Bearer token123"),
                    "/inbound");

            SourceMetadata metadata = adaptor.buildSourceMetadata(request);

            assertThat(metadata.sourceIdentifier()).isEqualTo(SOURCE_KEY);
            assertThat(metadata.facilityId()).isEqualTo("0002");
            assertThat(metadata.sourceEventId()).isEqualTo("enc-visit-001");
            assertThat(metadata.correlationId()).isEqualTo("corr-abc-123");
            assertThat(metadata.authorizationHeader()).isEqualTo("Bearer token123");
            assertThat(metadata.sourcePath()).isEqualTo("/inbound");
            assertThat(metadata.eventTime()).isNotNull();
        }

        @Test
        void missingCorrelationId_generatesUuid() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            SourceMetadata metadata = adaptor.buildSourceMetadata(request);

            assertThat(metadata.correlationId()).isNotNull().isNotBlank();
            // Should be a valid UUID format
            assertThat(metadata.correlationId())
                    .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        }

        @Test
        void missingOptionalHeaders_returnsNullValues() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(),
                    "/inbound");

            SourceMetadata metadata = adaptor.buildSourceMetadata(request);

            assertThat(metadata.facilityId()).isNull();
            assertThat(metadata.sourceEventId()).isNull();
            assertThat(metadata.authorizationHeader()).isNull();
        }
    }

    // ==================== adapt() — Headers flow to CloudEvent ====================

    @Nested
    class HeadersToCloudEvent {

        @Test
        void facilityIdHeader_mappedToCloudEvent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Facility-Id", "0002"),
                    "/inbound");

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getFacilityid()).isEqualTo("0002");
        }

        @Test
        void sourceEventIdHeader_mappedToCloudEvent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-Event-Id", "enc-visit-001"),
                    "/inbound");

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getSourceeventid()).isEqualTo("enc-visit-001");
        }

        @Test
        void correlationIdHeader_mappedToCloudEvent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Correlation-Id", "corr-trace-001"),
                    "/inbound");

            CloudEventDto event = adaptor.adapt(request).get(0);

            assertThat(event.getCorrelationid()).isEqualTo("corr-trace-001");
        }

        @Test
        void withSourceEventId_generatesDeterministicEventId() {
            InboundRequest request1 = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-Event-Id", "enc-visit-001"),
                    "/inbound");

            InboundRequest request2 = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-Event-Id", "enc-visit-001"),
                    "/inbound");

            CloudEventDto event1 = adaptor.adapt(request1).get(0);
            CloudEventDto event2 = adaptor.adapt(request2).get(0);

            assertThat(event1.getId()).isEqualTo(event2.getId());
        }
    }

    // ==================== getSourceIdentifier() ====================

    @Test
    void getSourceIdentifier_returnsSourceKey() {
        assertThat(adaptor.getSourceIdentifier()).isEqualTo(SOURCE_KEY);
    }

    // ==================== Helpers ====================

    private InboundRequest buildRequest(String body) {
        return InboundRequest.from(
                body,
                Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                "/inbound");
    }

    private String loadFixture(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Test fixture not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
