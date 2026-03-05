# CCE Emitter Adaptor

## Overview

The **CCE Emitter Adaptor** is a generic [OpenHIM mediator](https://openhim.org/) built as a standard **Spring Boot 3.x** application. It is configurable for different source systems — currently configured for **eBUZIMA EMR**. It receives FHIR R4 resource payloads via OpenHIM Core (secondary route), wraps them in CloudEvents v1.0 envelopes, and forwards them to the CCE Collector. The input is any valid individual FHIR resource (e.g., Encounter, Observation). Bundle resources are silently ignored (out of scope for v1.0).

**No third-party mediator library is used** — the OpenHIM mediator contract (registration, heartbeat, response envelope) is implemented via custom Spring components.

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 21 LTS | Runtime |
| Spring Boot | 3.4.x | Application framework |
| Gradle | 8.x (Kotlin DSL) | Build tool |
| HAPI FHIR | 7.4.0 | FHIR R4 parsing & validation |
| Spring Retry | — | Retry with exponential backoff |
| Micrometer + Prometheus | — | Metrics & monitoring |
| WireMock | 3.9.x | Integration test stubs |

## Quick Start

```bash
# Prerequisites: Java 21, Docker

# 1. Start dependencies
docker compose up -d

# 2. Build
./gradlew clean build

# 3. Run
./gradlew bootRun --args='--spring.profiles.active=dev'

# 4. Test
curl -s http://localhost:8082/actuator/health | jq
# → { "status": "UP" }

# 5. Send a test event (FHIR Encounter)
curl -X POST http://localhost:8082/inbound \
  -H "Content-Type: application/json" \
  -H "X-OpenHIM-ClientID: ebuzima-emr-client" \
  -d '{"resourceType":"Encounter","id":"enc-001","status":"finished","subject":{"reference":"Patient/260225-0002-5501"},"period":{"start":"2026-02-25T08:00:00Z"}}'
# → 202 Accepted
```

## Architecture

```
eBUZIMA EMR → OpenHIM Core → Emitter Adaptor → CCE Collector → Kafka
                                    │
                                    ├── Source Adaptor (header-based routing)
                                    ├── FHIR resource → parse (ignore if Bundle)
                                    ├── CloudEvent v1.0 envelope
                                    ├── Forward to Collector (@Retryable)
                                    └── OpenHIM response wrapping
```

### Key Components

| Component | Description |
|-----------|-------------|
| `InboundEventController` | `@RestController` — receives POSTs from OpenHIM |
| `SourceAdaptorRegistry` | Auto-discovers `@Component` adaptors; routes to matching adaptor |
| `AbstractSourceAdaptor` | Base class — parses FHIR resources, builds CloudEvents |
| `CollectorForwardingService` | `@Retryable` — POSTs CloudEvents to Collector via `RestClient` |
| `MediatorRegistrar` | Registers with OpenHIM Core on startup |
| `HeartbeatScheduler` | `@Scheduled` — periodic heartbeat + dynamic config sync |
| `OpenHimResponseWrapper` | Wraps responses in `application/json+openhim` format |


## Project Structure

```
src/main/java/org/openphc/cce/emitter/
├── config/            # FhirConfig, RestClientConfig, properties
├── controller/        # InboundEventController
├── openhim/           # MediatorRegistrar, HeartbeatScheduler, ResponseWrapper
├── adaptor/           # SourceAdaptor interface, AbstractSourceAdaptor, Registry
├── cloudevents/       # CloudEventEnvelopeBuilder, EventIdGenerator
├── fhir/              # FhirResourceParser, PatientIdExtractor
├── service/           # EventProcessingService, CollectorForwardingService
├── model/             # DTOs (CloudEventDto, InboundRequest, etc.)
└── exception/         # Custom exceptions + GlobalExceptionHandler
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Overview](architecture-overview.md) | System context, tech stack, package structure, processing pipeline, security, deployment |
| [Developer Setup](developer-setup.md) | Build, run, Docker Compose |
| [Data Dictionary](data-dictionary.md) | Field definitions, configuration properties |
| [API Reference](api-reference.md) | All endpoints, request/response examples |
| [Flow Diagrams](flow-diagrams.md) | Mermaid sequence & flow diagrams |

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/inbound` | POST | Inbound FHIR resource (adaptor auto-selected via headers) |
| `/actuator/health` | GET | Health status |
| `/actuator/prometheus` | GET | Prometheus metrics |

## License

Internal — OpenPHC / CCE Project
