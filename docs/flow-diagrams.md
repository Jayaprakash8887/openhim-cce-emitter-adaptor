# CCE Emitter Adaptor — Flow Diagrams

All diagrams use Mermaid notation.

## 1. End-to-End Event Flow

```mermaid
flowchart LR
    subgraph Sources
        RHIE[RHIE<br/>FHIR R4]
        EBZ[eBUZIMA<br/>Custom JSON]
        SC[SmartCare]
        CHW[CHW App]
        LAB[Lab]
    end

    subgraph OpenHIM
        OHC[OpenHIM Core<br/>Channel Router]
    end

    subgraph "CCE Emitter Adaptor (Spring Boot)"
        CTRL[InboundEvent<br/>Controller]
        REG[SourceAdaptor<br/>Registry]
        SA[SourceAdaptor<br/>adapt]
        NORM[CloudEvent<br/>Builder]
        FWD[Collector<br/>Forwarding<br/>@Retryable]
        WRAP[OpenHIM<br/>ResponseWrapper]
    end

    subgraph CCE
        COL[CCE Collector]
        KAFKA[Kafka<br/>cce.events.inbound]
    end

    RHIE -->|FHIR JSON| OHC
    EBZ -->|Custom JSON| OHC
    SC --> OHC
    CHW --> OHC
    LAB --> OHC

    OHC -->|Route to mediator| CTRL
    CTRL --> REG
    REG --> SA
    SA --> NORM
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
    A[POST /inbound/**] --> B{X-Source-System<br/>header present?}

    B -->|"ebuzima"| C[EbuzimaSourceAdaptor<br/>@Order 10]
    B -->|"smartcare"| D[SmartCareSourceAdaptor<br/>@Order 20]
    B -->|"chw"| E[ChwAppSourceAdaptor<br/>@Order 30]
    B -->|"lab"| F[LabSystemSourceAdaptor<br/>@Order 40]
    B -->|not set| G{URL Path?}

    G -->|/inbound/ebuzima| C
    G -->|/inbound/smartcare| D
    G -->|/inbound/chw| E
    G -->|/inbound/lab| F
    G -->|"/inbound or /inbound/fhir"| H{Body contains<br/>resourceType?}

    H -->|Yes| I[RhieSourceAdaptor<br/>@Order 100<br/>FHIR Passthrough]
    H -->|No| J[SourceNotRecognized<br/>Exception → 400]

    style C fill:#e1f5fe
    style D fill:#e1f5fe
    style E fill:#e1f5fe
    style F fill:#e1f5fe
    style I fill:#e8f5e9
    style J fill:#ffebee
```

## 4. FHIR Bundle Expansion

```mermaid
flowchart TD
    A[Incoming FHIR JSON] --> B{resourceType?}

    B -->|Bundle| C[Extract entries]
    B -->|Single Resource| D[Wrap as single CloudEvent]

    C --> E[entry 0: Encounter]
    C --> F[entry 1: Observation]
    C --> G[entry 2: Observation]

    E --> H[CloudEvent 1<br/>type: org.openphc.cce.encounter]
    F --> I[CloudEvent 2<br/>type: org.openphc.cce.observation]
    G --> J[CloudEvent 3<br/>type: org.openphc.cce.observation]

    D --> K[CloudEvent<br/>type: org.openphc.cce.encounter]

    H --> L[Forward each to Collector]
    I --> L
    J --> L
    K --> L
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

    SA_RHIE[RhieSourceAdaptor]
    SA_EBZ[EbuzimaSourceAdaptor]
    SA_SC[SmartCareSourceAdaptor]
    SA_CHW[ChwAppSourceAdaptor]
    SA_LAB[LabSystemSourceAdaptor]

    CE[CloudEventEnvelopeBuilder]
    ETN[EventTypeNormalizer]
    PIE[PatientIdExtractor]
    FRP[FhirResourceParser]
    IDG[EventIdGenerator]

    REG_HB[MediatorRegistrar]
    HB[HeartbeatScheduler]
    DC[DynamicConfigService]

    FC[FhirConfig<br/>@Bean FhirContext]
    RC[RestClientConfig<br/>@Bean RestClient]

    CTRL --> NORM
    CTRL --> FWD
    CTRL --> WRAP

    NORM --> REG
    REG --> SA_RHIE
    REG --> SA_EBZ
    REG --> SA_SC
    REG --> SA_CHW
    REG --> SA_LAB

    SA_RHIE --> CE
    SA_RHIE --> PIE
    SA_EBZ --> CE
    SA_EBZ --> PIE

    CE --> ETN
    CE --> IDG

    FWD --> RC
    REG_HB --> RC
    HB --> RC
    HB --> DC

    SA_RHIE --> FC
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
        A1[RHIE]
        A2[eBUZIMA]
        A3[SmartCare]
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

    A1 --> OHC
    A2 --> OHC
    A3 --> OHC

    OHC -->|"Route /inbound/**"| EA
    EA -->|"Register + heartbeat"| OHC_API
    EA -->|"POST /v1/events"| COL
    COL --> KAFKA
```
