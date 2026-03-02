# CCE Emitter Adaptor

## Overview

The **CCE Emitter Adaptor** is an [OpenHIM mediator](https://openhim.org/) built as a standard **Spring Boot 3.x** application. It receives clinical events from multiple source systems (RHIE, eBUZIMA, SmartCare, CHW App, Lab), transforms them into CloudEvents v1.0 with FHIR R4 payloads, and forwards them to the CCE Collector.

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

# 5. Send a test event
curl -X POST http://localhost:8082/inbound/fhir \
  -H "Content-Type: application/json" \
  -d '{"resourceType":"Encounter","status":"finished","subject":{"reference":"Patient/PAT-001"}}'
# → 202 Accepted
```

## Architecture

```
Source System → OpenHIM Core → Emitter Adaptor → CCE Collector → Kafka
                                    │
                                    ├── Source Adaptor (per source)
                                    ├── FHIR R4 mapping
                                    ├── CloudEvent v1.0 envelope
                                    ├── Forward to Collector (@Retryable)
                                    └── OpenHIM response wrapping
```

### Key Components

| Component | Description |
|-----------|-------------|
| `InboundEventController` | `@RestController` — receives POSTs from OpenHIM |
| `SourceAdaptorRegistry` | Auto-discovers `@Component` adaptors; first `canHandle()` match wins |
| `SourceAdaptor` implementations | Per-source transformers (RHIE, eBUZIMA, SmartCare, CHW, Lab) |
| `CollectorForwardingService` | `@Retryable` — POSTs CloudEvents to Collector via `RestClient` |
| `MediatorRegistrar` | Registers with OpenHIM Core on startup |
| `HeartbeatScheduler` | `@Scheduled` — periodic heartbeat + dynamic config sync |
| `OpenHimResponseWrapper` | Wraps responses in `application/json+openhim` format |

## Collector Validation

The CCE Collector validates **only the `type` field** as mandatory. All other CloudEvent fields are adaptor-populated for downstream correctness but are not enforced at the Collector boundary.

## Project Structure

```
src/main/java/org/openphc/cce/emitter/
├── config/          # FhirConfig, RestClientConfig, properties
├── controller/      # InboundEventController
├── openhim/         # MediatorRegistrar, HeartbeatScheduler, ResponseWrapper
├── adaptor/         # SourceAdaptor interface + implementations
├── cloudevents/     # CloudEventEnvelopeBuilder, EventTypeNormalizer
├── fhir/            # FhirResourceParser, PatientIdExtractor
├── service/         # EventNormalizationService, CollectorForwardingService
├── model/           # DTOs (CloudEventDto, InboundRequest, etc.)
├── exception/       # Custom exceptions + GlobalExceptionHandler
└── util/            # JsonUtil
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Overview](docs/architecture-overview.md) | System context, tech stack, package structure |
| [High-Level Design](docs/high-level-design.md) | Subsystem decomposition, processing pipeline |
| [Low-Level Design](docs/low-level-design.md) | Detailed class implementations |
| [Developer Setup](docs/developer-setup.md) | Build, run, Docker Compose |
| [Data Dictionary](docs/data-dictionary.md) | Field definitions, configuration properties |
| [API Reference](docs/api-reference.md) | All endpoints, request/response examples |
| [Flow Diagrams](docs/flow-diagrams.md) | Mermaid sequence & flow diagrams |

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/inbound` | POST | Generic inbound (adaptor auto-selected) |
| `/inbound/fhir` | POST | FHIR passthrough |
| `/inbound/ebuzima` | POST | eBUZIMA source |
| `/inbound/smartcare` | POST | SmartCare source |
| `/inbound/chw` | POST | CHW App source |
| `/inbound/lab` | POST | Lab system source |
| `/actuator/health` | GET | Health status |
| `/actuator/prometheus` | GET | Prometheus metrics |

## License

Internal — OpenPHC / CCE Project
