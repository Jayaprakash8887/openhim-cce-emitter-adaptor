# CCE Emitter Adaptor — Architecture Overview

## 1. Purpose

The Emitter Adaptor is an **OpenHIM mediator** built as a standalone **Spring Boot 3.x** application. It serves as the bridge between the **eBUZIMA EMR** and the CCE platform. It is responsible for:

1. **Receiving** eBUZIMA clinical visit data via OpenHIM Core routing
2. **Mapping** eBUZIMA-native JSON payloads to FHIR R4 resources
3. **Normalizing** event types to the `org.openphc.cce.<resource>` format
4. **Constructing** CloudEvents v1.0 envelopes with CCE-required fields and extensions
5. **Forwarding** the normalized CloudEvents to the CCE Collector Service via `RestClient`

## 2. System Context

```
┌─────────────────────────────────────────────────────────────┐
│                    eBUZIMA EMR                                │
│            (Clinical visits, observations, etc.)             │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    OpenHIM Core (RHIE)                        │
│   Channel routing, transaction logging, access control       │
└──────────────────────────┬──────────────────────────────────┘
                           │  HTTP (routed to mediator)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│           ★ CCE Emitter Adaptor (this service) ★             │
│   Spring Boot 3.4.x + HAPI FHIR 7.4.0                       │
│                                                              │
│  1. @RestController receives HTTP POST                       │
│  2. Parse eBUZIMA-native JSON payload                        │
│  3. Map to FHIR R4 resources (Encounter, Observation, etc.) │
│  4. Normalize event type → org.openphc.cce.<resource>        │
│  5. Build CloudEvents v1.0 envelope                          │
│  6. Forward via RestClient to CCE Collector                  │
│  7. Wrap response in OpenHIM mediator format                 │
└──────────────────────────┬──────────────────────────────────┘
                           │  HTTP POST (CloudEvents JSON)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CCE Gateway (OAuth + routing)                    │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CCE Collector Service                            │
│    Validate (type only) → Deduplicate → Publish to Kafka     │
└──────────────────────────┬──────────────────────────────────┘
                           │  Kafka: cce.events.inbound
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CCE Compliance Service                           │
│    Match → Enroll → Complete Steps → Detect Deviations       │
└─────────────────────────────────────────────────────────────┘
```

## 3. Architecture Principles

| Principle | Application |
|-----------|-------------|
| **Spring Boot Standard** | Standard Spring Boot application — embedded Tomcat, DI, `@ConfigurationProperties`, Actuator health/metrics |
| **Stateless** | No local database; no session state; all context derived from inbound request |
| **Single Responsibility** | eBUZIMA adaptor handles eBUZIMA-specific format; extensible via `SourceAdaptor` interface |
| **Open/Closed** | Future source systems can be added by implementing `SourceAdaptor` and annotating `@Component` — auto-discovered |
| **Idempotent Output** | Same source event always produces the same CloudEvents `id` — Collector handles dedup |
| **Fail-Fast** | Invalid/unmappable payloads rejected immediately with descriptive errors |
| **Retry with Backoff** | Collector forwarding uses Spring Retry with exponential backoff on 5xx/timeout |

## 4. Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build tool | Gradle (Kotlin DSL) | 8.x |
| HTTP server | Embedded Tomcat | (via Spring Boot) |
| REST endpoints | Spring Web (`@RestController`) | |
| HTTP client | Spring `RestClient` | (Spring 6.1+) |
| FHIR library | HAPI FHIR | 7.4.0 |
| JSON | Jackson | (via Spring Boot) |
| Retry | Spring Retry | |
| Health & metrics | Spring Boot Actuator + Micrometer + Prometheus | |
| Configuration | `application.yml` + `@ConfigurationProperties` | |
| Testing | JUnit 5, Spring Boot Test, WireMock | |

## 5. OpenHIM Mediator Integration

The OpenHIM mediator contract is implemented with plain Spring Boot components — no third-party mediator library.

### Custom Components

```
┌──────────────────────────────────────────────────────────┐
│           Spring Boot Application                         │
│                                                           │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ Embedded Tomcat   │  │ @RestController (InboundCtrl)│   │
│  │ port: 8082        │──│  POST /inbound               │   │
│  │                   │  │  POST /inbound/ebuzima        │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ MediatorRegistrar │  │ HeartbeatScheduler           │   │
│  │ (startup via      │  │ (@Scheduled, periodic POST   │   │
│  │  @PostConstruct)  │  │  to /mediators/{urn}/hb)     │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ RestClient        │  │ OpenHimResponseWrapper       │   │
│  │ (Collector +      │  │ (wraps response in openhim   │   │
│  │  Core API calls)  │  │  mediator format)            │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐    │
│  │ Spring Boot Actuator                              │    │
│  │  /actuator/health, /actuator/prometheus            │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

### OpenHIM Lifecycle

| Phase | Mechanism | Description |
|-------|-----------|-------------|
| **Registration** | `MediatorRegistrar` (`@PostConstruct` or `ApplicationReadyEvent`) | POST mediator descriptor to OpenHIM Core `/mediators` |
| **Heartbeat** | `HeartbeatScheduler` (`@Scheduled`) | Periodic POST to `/mediators/{urn}/heartbeat`; receives dynamic config |
| **Dynamic Config** | `DynamicConfigService` | Parses heartbeat response, updates runtime config (e.g., Collector URL) |
| **Response Wrapping** | `OpenHimResponseWrapper` | Wraps `@RestController` responses in `application/json+openhim` envelope |

## 6. Package Structure

```
org.openphc.cce.emitter/
├── CceEmitterAdaptorApplication.java             # @SpringBootApplication entry point
│
├── config/                                        # Spring configuration
│   ├── FhirConfig.java                            #   @Bean FhirContext.forR4() singleton
│   ├── RestClientConfig.java                      #   @Bean RestClient for Collector + OpenHIM Core
│   ├── RetryConfig.java                           #   Spring Retry configuration
│   ├── OpenHimProperties.java                     #   @ConfigurationProperties for openhim.*
│   └── CollectorProperties.java                   #   @ConfigurationProperties for cce.collector.*
│
├── controller/                                    # Spring MVC controllers
│   ├── InboundEventController.java                #   @RestController: POST /inbound, /inbound/fhir, etc.
│   └── HealthController.java                      #   Custom health info (supplements Actuator)
│
├── openhim/                                       # OpenHIM mediator integration
│   ├── MediatorRegistrar.java                     #   Registers mediator with OpenHIM Core on startup
│   ├── HeartbeatScheduler.java                    #   Periodic heartbeat to OpenHIM Core
│   ├── DynamicConfigService.java                  #   Applies dynamic config from heartbeat response
│   ├── OpenHimResponseWrapper.java                #   Wraps responses in application/json+openhim
│   └── model/
│       ├── MediatorDescriptor.java                #   Registration JSON model
│       ├── HeartbeatRequest.java                  #   Heartbeat request model
│       └── OpenHimResponse.java                   #   Mediator response envelope model
│
├── adaptor/                                       # Source system adaptors
│   ├── SourceAdaptor.java                         #   Interface: canHandle + adapt + getSourceIdentifier
│   ├── SourceAdaptorRegistry.java                 #   Finds correct adaptor (injected List<SourceAdaptor>)
│   ├── AbstractSourceAdaptor.java                 #   Base class with common FHIR + CloudEvents logic
│   └── ebuzima/
│       ├── EbuzimaSourceAdaptor.java              #   @Component: eBUZIMA JSON → FHIR R4
│       └── EbuzimaPayloadMapper.java              #   Field-level mapping logic
│
├── cloudevents/                                   # CloudEvents envelope construction
│   ├── CloudEventEnvelopeBuilder.java             #   Builds CloudEvents v1.0 JSON
│   ├── EventTypeNormalizer.java                   #   resourceType → org.openphc.cce.<resource>
│   └── EventIdGenerator.java                      #   Deterministic ID from source + sourceEventId
│
├── fhir/                                          # FHIR utilities
│   ├── FhirResourceParser.java                    #   HAPI FHIR parse + type detection
│   ├── FhirResourceValidator.java                 #   Structural validation before forwarding
│   └── PatientIdExtractor.java                    #   Extract patient UPID from FHIR resources
│
├── service/                                       # Business logic
│   ├── EventNormalizationService.java             #   Orchestrator: adapt → normalize → build CloudEvent
│   ├── CollectorForwardingService.java            #   @Retryable: POST to Collector via RestClient
│   └── CollectorResponseHandler.java              #   Parse Collector response, determine retry
│
├── model/                                         # DTOs
│   ├── CloudEventDto.java                         #   CloudEvents v1.0 output DTO
│   ├── InboundRequest.java                        #   Wraps incoming HTTP body + headers
│   ├── SourceMetadata.java                        #   sourceIdentifier, facilityId, sourceEventId
│   ├── TransformationResult.java                  #   Per-event success/failure detail
│   ├── BatchResult.java                           #   Aggregate result for multi-event payloads
│   └── CollectorResponse.java                     #   Response DTO from Collector
│
├── exception/                                     # Custom exceptions
│   ├── SourceNotRecognizedException.java
│   ├── SourceAdaptorException.java
│   ├── FhirMappingException.java
│   ├── PatientIdNotFoundException.java
│   ├── CollectorForwardingException.java
│   └── GlobalExceptionHandler.java                #   @ControllerAdvice for consistent error responses
│
└── util/
    └── JsonUtil.java                              #   Jackson helpers
```

**Estimated: ~28 source files** across 9 packages.

## 7. Key Interfaces

### 7.1 SourceAdaptor Interface

```java
public interface SourceAdaptor {
    boolean canHandle(InboundRequest request);
    List<CloudEventDto> adapt(InboundRequest request);
    String getSourceIdentifier();
}
```

### 7.2 InboundEventController

```java
@RestController
@RequestMapping("/inbound")
@RequiredArgsConstructor
public class InboundEventController {

    private final EventNormalizationService normalizationService;
    private final CollectorForwardingService forwardingService;
    private final OpenHimResponseWrapper responseWrapper;

    @PostMapping({"", "/ebuzima"})
    public ResponseEntity<?> handleInbound(
            @RequestBody String body,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest servletRequest) {
        // 1. Build InboundRequest from body + headers + path
        // 2. Normalize → List<CloudEventDto>
        // 3. Forward each to Collector
        // 4. Wrap in OpenHIM response format
    }
}
```

## 8. External Interfaces

### 8.1 Inbound (from OpenHIM Core)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **IN** | HTTP POST | `/inbound` | eBUZIMA payload (auto-detect) |
| **IN** | HTTP POST | `/inbound/ebuzima` | eBUZIMA-native JSON (explicit path) |

### 8.2 Outbound (to CCE Collector)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **OUT** | HTTP POST | `{collector-url}/v1/events` | CloudEvents v1.0 JSON with FHIR R4 payload |

### 8.3 Outbound (to OpenHIM Core)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **OUT** | HTTP POST | `{core-url}/mediators` | Registration on startup |
| **OUT** | HTTP POST | `{core-url}/mediators/{urn}/heartbeat` | Periodic heartbeat |

## 9. Error Handling Strategy

Errors are handled by `GlobalExceptionHandler` (`@ControllerAdvice`):

| Scenario | Action | HTTP Status |
|----------|--------|-------------|
| Unknown source system | Log + reject | 400 with `SOURCE_NOT_RECOGNIZED` |
| Source payload unparseable | Log + reject | 400 with `PAYLOAD_PARSE_ERROR` |
| FHIR mapping failure | Log + reject | 422 with `FHIR_MAPPING_ERROR` |
| Patient UPID not extractable | Log + reject | 400 with `PATIENT_ID_NOT_FOUND` |
| Collector returns 400 | Log + return error | 400 (non-retryable) |
| Collector returns 422 | Log + return error | 422 (non-retryable) |
| Collector returns 200 (duplicate) | Log + return success | 200 (idempotent) |
| Collector returns 500 | Retry with backoff (max 3) | 500 if all retries exhausted |
| Collector unreachable | Retry with backoff (max 3) | 502 if all retries exhausted |

## 10. Observability

### Actuator Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Application health (UP/DOWN) |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |
| `GET /actuator/prometheus` | Prometheus-formatted metrics |
| `GET /actuator/info` | Application metadata |

### Custom Metrics (Micrometer)

| Metric | Type | Description |
|--------|------|-------------|
| `cce.emitter.events.received` | Counter | Total events received by source |
| `cce.emitter.events.forwarded` | Counter | Events successfully forwarded to Collector |
| `cce.emitter.events.rejected` | Counter | Events rejected (mapping/validation failure) |
| `cce.emitter.events.duplicate` | Counter | Duplicate events (Collector returned 200) |
| `cce.emitter.collector.latency` | Timer | Collector forwarding latency |
| `cce.emitter.collector.retries` | Counter | Retry attempts to Collector |

### Structured Logging

SLF4J + Logback with structured JSON output. Key MDC fields: `correlationId`, `source`, `eventType`, `subject`.

## 11. Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| **Availability** | 99.9% uptime |
| **Latency** | < 500ms end-to-end (receive → forward) |
| **Throughput** | 100 events/sec sustained |
| **Stateless** | No local database; all state in OpenHIM and CCE platform |
| **Retry** | Max 3 retries with exponential backoff for Collector calls |
| **Max payload** | 1 MB (matching Collector limit) |
