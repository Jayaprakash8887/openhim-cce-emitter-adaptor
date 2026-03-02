# CCE Emitter Adaptor — Data Dictionary

## 1. CloudEvents Output Envelope

The adaptor outputs CloudEvents v1.0-compliant JSON to the CCE Collector.

### 1.1 Field Definitions

| Field | Type | Required | Source | Description |
|-------|------|----------|--------|-------------|
| `specversion` | string | **Yes** — always `"1.0"` | Static | CloudEvents specification version |
| `id` | string (UUID) | **Yes** | Generated | Unique event identifier (`UUID.randomUUID()` or deterministic hash) |
| `source` | string (URI) | **Yes** | Adaptor | Source system identifier (e.g., `"rhie-mediator"`, `"ebuzima"`, `"smartcare"`) |
| `type` | string | **Yes** — **Collector-mandatory** | Adaptor | Normalized event type: `"org.openphc.cce.<resourcetype>"` (lowercase). **The only field the Collector validates.** |
| `subject` | string | Recommended | Extracted from FHIR | Patient UPID (`Patient/<upid>` or bare UPID). Used as Kafka partition key. |
| `time` | string (ISO-8601) | Recommended | Adaptor | Event creation timestamp in UTC |
| `datacontenttype` | string | Recommended | Static | Always `"application/fhir+json"` |
| `data` | object | Recommended | Transformed | FHIR R4 resource JSON (the payload) |
| `facilityid` | string | Optional | Header / payload | Facility FOSA ID from `X-Facility-Id` header or extracted from payload |
| `sourceeventid` | string | Optional | Header / payload | Source system's original event ID from `X-Source-Event-Id` header |
| `correlationid` | string | Optional | Header | Trace correlation ID from `X-Correlation-Id` header |
| `protocolinstanceid` | string | Optional | Usually null | Protocol instance — emitter normally does not set this |
| `protocoldefinitionid` | string | Optional | Usually null | Protocol definition — emitter normally does not set this |
| `actionid` | string | Optional | Usually null | Action — emitter normally does not set this |

### 1.2 Validation Rules

| Rule | Detail |
|------|--------|
| **Collector enforces only `type`** | The Collector validates that the `type` field is present and non-empty. All other fields pass through without server-side validation. |
| **Adaptor populates all practical fields** | Despite relaxed Collector validation, the adaptor should populate `id`, `source`, `type`, `subject`, `time`, `datacontenttype`, and `data` for correct downstream processing by the Compliance Service. |
| **Extension attributes are lowercase** | Per CloudEvents spec, custom extension attributes use `lowercase` without separators: `facilityid`, `sourceeventid`, `correlationid`, `protocolinstanceid`, etc. |
| **`type` normalization** | Pattern: `org.openphc.cce.<fhir-resource-type-lowercase>` (e.g., `org.openphc.cce.encounter`, `org.openphc.cce.observation`). The Collector does not enforce this pattern, but the Compliance Service's trigger matching depends on it. |

### 1.3 Sample CloudEvent

```json
{
  "specversion": "1.0",
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "source": "ebuzima",
  "type": "org.openphc.cce.encounter",
  "subject": "UPID-PAT-12345",
  "time": "2026-02-25T08:00:00.000Z",
  "datacontenttype": "application/fhir+json",
  "facilityid": "FAC-FOSA-001",
  "sourceeventid": "ebz-visit-9876",
  "correlationid": "trace-abc-123",
  "data": {
    "resourceType": "Encounter",
    "id": "enc-001",
    "status": "finished",
    "class": {"code": "AMB"},
    "subject": {"reference": "Patient/UPID-PAT-12345"},
    "period": {"start": "2026-02-25T08:00:00Z"}
  }
}
```

## 2. Inbound Request Model

### 2.1 HTTP Request (from OpenHIM Core)

| Element | Source | Description |
|---------|--------|-------------|
| **Body** | HTTP body | eBUZIMA JSON payload |
| **Content-Type** | Header | `application/json` |
| **X-Source-System** | Header (optional) | Source system identifier (`ebuzima`). Defaults to eBUZIMA if absent. |
| **X-Facility-Id** | Header (optional) | Facility FOSA ID |
| **X-Source-Event-Id** | Header (optional) | Source system's event ID |
| **X-Correlation-Id** | Header (optional) | Trace ID for cross-service correlation |
| **URL Path** | Request URI | Used for adaptor selection: `/inbound/ebuzima` or `/inbound` |

### 2.2 InboundRequest Fields

| Field | Type | Populated From | Description |
|-------|------|----------------|-------------|
| `body` | String | HTTP body | Raw request body |
| `headers` | Map<String, String> | HTTP headers (normalized to lowercase keys) | All request headers |
| `path` | String | `HttpServletRequest.getRequestURI()` | Request path |
| `metadata` | SourceMetadata | Extracted from headers + path | Structured source context |

### 2.3 SourceMetadata Fields

| Field | Type | Source | Description |
|-------|------|--------|-------------|
| `sourceIdentifier` | String | `X-Source-System` header or path segment | e.g., `"ebuzima"` |
| `facilityId` | String | `X-Facility-Id` header | Nullable |
| `sourceEventId` | String | `X-Source-Event-Id` header | Nullable |
| `correlationId` | String | `X-Correlation-Id` header | Nullable |
| `eventTime` | OffsetDateTime | `Instant.now(ZoneOffset.UTC)` | When the adaptor received the event |
| `sourcePath` | String | Request URI path | e.g., `/inbound/ebuzima` |

## 3. Configuration Properties

### 3.1 OpenHIM Properties

Prefix: `openhim`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `openhim.core.host` | String | `localhost` | OpenHIM Core hostname |
| `openhim.core.api-port` | int | `8080` | OpenHIM Core API port (HTTPS) |
| `openhim.core.username` | String | `root@openhim.org` | OpenHIM Core API username |
| `openhim.core.password` | String | — | OpenHIM Core API password |
| `openhim.mediator.urn` | String | `urn:mediator:cce-emitter-adaptor` | Unique mediator URN |
| `openhim.mediator.version` | String | `1.0.0` | Mediator version string |
| `openhim.mediator.name` | String | `CCE Emitter Adaptor` | Human-readable mediator name |
| `openhim.heartbeat.enabled` | boolean | `true` | Enable/disable heartbeat scheduler |
| `openhim.heartbeat.interval-seconds` | int | `10` | Heartbeat interval in seconds |

### 3.2 Collector Properties

Prefix: `cce.collector`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `cce.collector.url` | String | `http://localhost:5001` | Collector base URL |
| `cce.collector.events-path` | String | `/v1/events` | Events endpoint path |
| `cce.collector.timeout` | int | `5000` | HTTP connect + read timeout in ms |
| `cce.collector.retry.max-attempts` | int | `3` | Maximum retry attempts for 5xx/timeout |
| `cce.collector.retry.backoff-ms` | int | `1000` | Initial backoff delay in ms (doubles per retry) |

### 3.3 Server Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `server.port` | int | `8082` | Application HTTP port |
| `management.endpoints.web.exposure.include` | String | `health,info,prometheus,metrics` | Exposed Actuator endpoints |

## 4. OpenHIM Registration Descriptor

### 4.1 MediatorDescriptor Fields

| Field | Type | Description |
|-------|------|-------------|
| `urn` | String | `"urn:mediator:cce-emitter-adaptor"` |
| `version` | String | `"1.0.0"` |
| `name` | String | `"CCE Emitter Adaptor"` |
| `description` | String | Human-readable description |
| `defaultChannelConfig` | Array | Channel definitions for auto-provisioning |
| `endpoints` | Array | Mediator endpoint definitions |

### 4.2 Default Channel Config

```json
{
  "name": "CCE Emitter Adaptor",
  "urlPattern": "^/inbound.*$",
  "routes": [
    {
      "name": "CCE Emitter Adaptor Route",
      "host": "cce-emitter-adaptor",
      "port": 8082,
      "primary": true,
      "type": "http"
    }
  ],
  "allow": ["cce-role"],
  "type": "http"
}
```

## 5. Collector Response Model

### 5.1 Success (202 Accepted)

```json
{
  "data": {
    "id": "evt-uuid",
    "status": "accepted"
  }
}
```

### 5.2 Duplicate (200 OK)

```json
{
  "data": {
    "id": "evt-uuid",
    "status": "duplicate",
    "message": "Event already processed"
  }
}
```

### 5.3 Error (400 Bad Request)

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "Missing required field: type"
  }
}
```

## 6. OpenHIM Response Envelope

### 6.1 OpenHimResponse Fields

| Field | Type | Description |
|-------|------|-------------|
| `x-mediator-urn` | String | Mediator URN |
| `status` | String | `"Successful"` or `"Failed"` |
| `response` | Object | Primary response body |
| `response.status` | int | HTTP status code |
| `response.headers` | Object | Response headers |
| `response.body` | String | JSON-stringified response body |
| `response.timestamp` | String | ISO-8601 timestamp |
| `orchestrations` | Array | List of downstream calls made |

### 6.2 Orchestration Entry

| Field | Type | Description |
|-------|------|-------------|
| `name` | String | Descriptive name (e.g., `"Forward to CCE Collector"`) |
| `request.method` | String | HTTP method |
| `request.path` | String | Request URL path |
| `request.body` | String | Request body (stringified) |
| `request.timestamp` | String | When request was sent |
| `response.status` | int | Response HTTP status |
| `response.body` | String | Response body (stringified) |
| `response.timestamp` | String | When response was received |

## 7. Event Type Mapping

### 7.1 FHIR Resource → CloudEvent Type

| FHIR resourceType | CloudEvent `type` |
|--------------------|-------------------|
| Encounter | `org.openphc.cce.encounter` |
| Observation | `org.openphc.cce.observation` |
| Condition | `org.openphc.cce.condition` |
| Immunization | `org.openphc.cce.immunization` |
| MedicationAdministration | `org.openphc.cce.medicationadministration` |
| MedicationRequest | `org.openphc.cce.medicationrequest` |
| DiagnosticReport | `org.openphc.cce.diagnosticreport` |
| EpisodeOfCare | `org.openphc.cce.episodeofcare` |
| ServiceRequest | `org.openphc.cce.servicerequest` |
| Procedure | `org.openphc.cce.procedure` |

### 7.2 Source Adaptor → Source Identifier

| Adaptor | `source` field value |
|---------|---------------------|
| EbuzimaSourceAdaptor | `"ebuzima"` |

## 8. Metrics Reference

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `cce.emitter.events.received` | Counter | `source`, `path` | Total inbound events received |
| `cce.emitter.events.transformed` | Counter | `source`, `resource_type` | Events successfully transformed |
| `cce.emitter.events.forwarded` | Counter | `source` | Events forwarded to Collector |
| `cce.emitter.events.rejected` | Counter | `source`, `reason` | Events that failed transformation |
| `cce.emitter.collector.latency` | Timer | — | Collector forwarding latency |
| `cce.emitter.collector.retries` | Counter | — | Retry attempts to Collector |
