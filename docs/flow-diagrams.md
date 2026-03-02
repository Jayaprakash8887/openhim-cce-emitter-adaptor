![alt text](image.png)# CCE Emitter Adaptor — Flow Diagrams

All diagrams use Mermaid notation.

## 1. End-to-End Event Flow

```mermaid
flowchart LR
    subgraph Sources
        EBZ[eBUZIMA EMR<br/>Custom JSON]
    end

    subgraph OpenHIM
        OHC[OpenHIM Core<br/>Channel Router]
    end

    subgraph "CCE Emitter Adaptor (Spring Boot)"
        CTRL[InboundEvent<br/>Controller]
        SA[EbuzimaSource<br/>Adaptor]
        MAPPER[eBUZIMA Payload<br/>Mapper]
        NORM[CloudEvent<br/>Builder]
        FWD["Collector<br/>Forwarding<br/>@Retryable"]
        WRAP[OpenHIM<br/>ResponseWrapper]
    end

    subgraph CCE
        COL[CCE Collector]
        KAFKA[Kafka<br/>cce.events.inbound]
    end

    EBZ -->|Custom JSON| OHC

    OHC -->|Route to mediator| CTRL
    CTRL --> SA
    SA --> MAPPER
    MAPPER --> NORM
    NORM --> FWD
    FWD -->|POST /v1/events| COL
    COL --> KAFKA

    FWD --> WRAP
    WRAP -->|application/json+openhim| OHC
```

## 2. Request Processing Sequence

```mermaid
sequenceDiagram
    participant OHC as OpenHIM Core
    participant Ctrl as InboundEventController
    participant Reg as SourceAdaptorRegistry
    participant SA as SourceAdaptor
    participant CE as CloudEventEnvelopeBuilder
    participant Fwd as CollectorForwardingService
    participant Col as CCE Collector
    participant Wrap as OpenHimResponseWrapper

    OHC->>Ctrl: POST /inbound/ebuzima (raw JSON)
    activate Ctrl

    Ctrl->>Ctrl: InboundRequest.from(body, headers, path)

    Ctrl->>Reg: findAdaptor(inboundRequest)
    Reg->>Reg: Iterate @Order-sorted adaptors
    Reg->>SA: canHandle(request) → true
    Reg-->>Ctrl: EbuzimaSourceAdaptor

    Ctrl->>SA: adapt(inboundRequest)
    activate SA
    SA->>SA: Parse eBUZIMA payload
    SA->>SA: Map to FHIR R4 (Encounter + Observations)
    SA->>CE: build(fhirJson, patientUpid, type, metadata)
    CE-->>SA: CloudEventDto
    SA-->>Ctrl: List<CloudEventDto> (2 events)
    deactivate SA

    loop Each CloudEvent
        Ctrl->>Fwd: forward(cloudEvent)
        activate Fwd
        Fwd->>Col: POST /v1/events
        Col-->>Fwd: 202 Accepted
        Fwd-->>Ctrl: CollectorResponse
        deactivate Fwd
    end

    Ctrl->>Ctrl: BatchResult.fromResults(...)
    Ctrl->>Wrap: wrap(batchResult, 202, orchestrations)
    Wrap-->>Ctrl: OpenHimResponse

    Ctrl-->>OHC: 202 (application/json+openhim)
    deactivate Ctrl
```

## 3. Adaptor Selection Flow

```mermaid
flowchart TD
    A[POST /inbound or /inbound/ebuzima] --> B{X-OpenHIM-ClientID<br/>matches configured<br/>eBUZIMA client ID?}

    B -->|Yes| C[EbuzimaSourceAdaptor]
    B -->|No / not set| G{URL Path?}

    G -->|/inbound/ebuzima| C
    G -->|/inbound| H{eBUZIMA payload<br/>detected?}

    H -->|Yes| C
    H -->|No| J[SourceNotRecognized<br/>Exception → 400]

    style C fill:#e1f5fe
    style J fill:#ffebee
```

## 4. eBUZIMA Payload Expansion

```mermaid
flowchart TD
    A[Incoming eBUZIMA JSON] --> B[EbuzimaPayloadMapper]

    B --> C[FHIR Encounter<br/>from visit data]
    B --> D[FHIR Observation 1<br/>from clinical obs]
    B --> E[FHIR Observation 2<br/>from vital signs]
    B --> F[FHIR Immunization<br/>if vaccination data present]

    C --> G[CloudEvent 1<br/>type: org.openphc.cce.encounter]
    D --> H[CloudEvent 2<br/>type: org.openphc.cce.observation]
    E --> I[CloudEvent 3<br/>type: org.openphc.cce.observation]
    F --> J[CloudEvent 4<br/>type: org.openphc.cce.immunization]

    G --> K[Forward each to Collector]
    H --> K
    I --> K
    J --> K
```

## 5. Collector Forwarding with Retry

```mermaid
sequenceDiagram
    participant Fwd as CollectorForwardingService
    participant Col as CCE Collector
    participant Log as Logger

    Fwd->>Col: POST /v1/events (attempt 1)

    alt 202 Accepted
        Col-->>Fwd: 202 + { status: accepted }
        Fwd->>Log: Event forwarded successfully
    else 200 Duplicate
        Col-->>Fwd: 200 + { status: duplicate }
        Fwd->>Log: Event is duplicate (idempotent)
    else 400 Client Error
        Col-->>Fwd: 400 + { error: ... }
        Fwd->>Log: Client error — no retry
        Fwd->>Fwd: throw CollectorClientException
    else 5xx / Timeout
        Col-->>Fwd: 503 Service Unavailable
        Fwd->>Log: Retry 1 of 3 (backoff 1s)

        Note over Fwd: @Retryable backoff: 1s

        Fwd->>Col: POST /v1/events (attempt 2)

        alt Success
            Col-->>Fwd: 202 Accepted
        else Still failing
            Col-->>Fwd: 503
            Fwd->>Log: Retry 2 of 3 (backoff 2s)

            Note over Fwd: Exponential backoff: 2s

            Fwd->>Col: POST /v1/events (attempt 3)

            alt Success
                Col-->>Fwd: 202 Accepted
            else Exhausted
                Col-->>Fwd: 503
                Fwd->>Fwd: @Recover → throw CollectorForwardingException
            end
        end
    end
```

## 6. OpenHIM Registration & Heartbeat Lifecycle

```mermaid
sequenceDiagram
    participant App as Spring Boot App
    participant Reg as MediatorRegistrar
    participant HB as HeartbeatScheduler
    participant Core as OpenHIM Core API
    participant DC as DynamicConfigService

    Note over App: ApplicationReadyEvent

    App->>Reg: @EventListener → register()
    Reg->>Core: POST /mediators (descriptor JSON)

    alt Registration OK
        Core-->>Reg: 201 Created
        Reg->>Reg: Log success
    else Registration fails
        Core-->>Reg: Error
        Reg->>Reg: Log warning (non-fatal)
    end

    Note over HB: @Scheduled (every 10s)

    loop Heartbeat cycle
        HB->>Core: POST /mediators/{urn}/heartbeat
        alt Config update available
            Core-->>HB: 200 + { config: {...} }
            HB->>DC: applyConfig(config)
            DC->>DC: Update Collector URL, timeouts, etc.
        else No config change
            Core-->>HB: 200
        end
    end
```

## 7. Error Handling Flow

```mermaid
flowchart TD
    A[Inbound Request] --> B{Parse OK?}

    B -->|No| C[GlobalExceptionHandler<br/>400/500]
    B -->|Yes| D{Adaptor found?}

    D -->|No| E[SourceNotRecognizedException<br/>→ 400 SOURCE_NOT_RECOGNIZED]
    D -->|Yes| F{FHIR mapping OK?}

    F -->|No| G[FhirMappingException<br/>→ 422 FHIR_MAPPING_ERROR]
    F -->|Yes| H{Patient ID found?}

    H -->|No| I[PatientIdNotFoundException<br/>→ 400 PATIENT_ID_NOT_FOUND]
    H -->|Yes| J{Collector accepts?}

    J -->|202 OK| K[Success → 202 with orchestrations]
    J -->|200 Dup| L[Duplicate → still 202]
    J -->|400 Client| M[CollectorClientException<br/>→ include in batch result]
    J -->|5xx × 3| N[CollectorForwardingException<br/>→ 502 COLLECTOR_FORWARDING_ERROR]

    C --> O[OpenHimResponseWrapper<br/>wraps error in mediator envelope]
    E --> O
    G --> O
    I --> O
    K --> O
    L --> O
    M --> O
    N --> O

    O --> P[Return to OpenHIM Core]

    style K fill:#e8f5e9
    style L fill:#fff3e0
    style E fill:#ffebee
    style G fill:#ffebee
    style I fill:#ffebee
    style N fill:#ffebee
```

## 8. Component Dependency Graph

```mermaid
flowchart TD
    CTRL[InboundEventController]
    NORM[EventNormalizationService]
    REG[SourceAdaptorRegistry]
    FWD[CollectorForwardingService]
    WRAP[OpenHimResponseWrapper]

    SA_EBZ[EbuzimaSourceAdaptor]
    MAPPER[EbuzimaPayloadMapper]

    CE[CloudEventEnvelopeBuilder]
    ETN[EventTypeNormalizer]
    PIE[PatientIdExtractor]
    FRP[FhirResourceParser]
    IDG[EventIdGenerator]

    REG_HB[MediatorRegistrar]
    HB[HeartbeatScheduler]
    DC[DynamicConfigService]

    FC["FhirConfig<br/>@Bean FhirContext"]
    RC["RestClientConfig<br/>@Bean RestClient"]

    CTRL --> NORM
    CTRL --> FWD
    CTRL --> WRAP

    NORM --> REG
    REG --> SA_EBZ

    SA_EBZ --> MAPPER
    SA_EBZ --> CE
    SA_EBZ --> PIE

    CE --> ETN
    CE --> IDG

    FWD --> RC
    REG_HB --> RC
    HB --> RC
    HB --> DC

    SA_EBZ --> FC
    FRP --> FC

    style CTRL fill:#bbdefb
    style WRAP fill:#bbdefb
    style FWD fill:#c8e6c9
    style REG fill:#fff9c4
    style REG_HB fill:#e1bee7
    style HB fill:#e1bee7
```

## 9. Deployment Topology

```mermaid
flowchart LR
    subgraph "External Sources"
        A2[eBUZIMA EMR]
    end

    subgraph "OpenHIM"
        OHC[OpenHIM Core<br/>:5000/:5001]
        OHC_API[Core API<br/>:8080]
        OHC_CON[Console<br/>:9000]
    end

    subgraph "CCE Emitter Adaptor"
        EA[Spring Boot<br/>:8082]
    end

    subgraph "CCE Platform"
        GW[CCE Gateway]
        COL[CCE Collector]
        KAFKA[Kafka]
    end

    A2 --> OHC

    OHC -->|"Route /inbound/**"| EA
    EA -->|"Register + heartbeat"| OHC_API
    EA -->|"POST /v1/events"| COL
    COL --> KAFKA
```
