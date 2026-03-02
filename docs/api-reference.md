# CCE Emitter Adaptor — API Reference

## 1. Overview

| Property | Value |
|----------|-------|
| **Base URL** | `http://<host>:8082` |
| **Protocol** | HTTP (HTTPS with Spring Boot `server.ssl.*`) |
| **Content Type** | `application/json` |
| **Response Format** | `application/json+openhim` (mediator response envelope) |

## 2. Inbound Event Endpoints

All inbound endpoints share the same controller logic (`InboundEventController`). This adaptor is dedicated to eBUZIMA.

### 2.1 POST /inbound

Generic inbound endpoint. Routes to `EbuzimaSourceAdaptor` when `X-Source-System: ebuzima` header is present or eBUZIMA payload is detected.

```
POST /inbound
```

### 2.2 POST /inbound/ebuzima

eBUZIMA clinical visit data (explicit path). Uses `EbuzimaSourceAdaptor`.

```
POST /inbound/ebuzima
```

---

### Request Format (all inbound endpoints)

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | `application/json` |
| `X-Source-System` | No | Source identifier: `ebuzima`. Defaults to eBUZIMA if absent. |
| `X-Facility-Id` | No | Facility FOSA ID |
| `X-Source-Event-Id` | No | Source system's original event ID |
| `X-Correlation-Id` | No | Cross-service trace ID |

**Body:** eBUZIMA-native JSON payload (clinical visit data, observations, immunizations, etc.).

### Response Format (all inbound endpoints)

**Status:** `202 Accepted`

**Content-Type:** `application/json+openhim`

**Body:**

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Successful",
  "response": {
    "status": 202,
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{\"eventsProcessed\":1,\"eventsAccepted\":1,\"eventsRejected\":0,\"eventsDuplicate\":0}",
    "timestamp": "2026-02-25T08:00:05Z"
  },
  "orchestrations": [
    {
      "name": "Forward to CCE Collector",
      "request": {
        "method": "POST",
        "path": "/v1/events",
        "body": "{\"specversion\":\"1.0\",\"type\":\"org.openphc.cce.encounter\",...}",
        "timestamp": "2026-02-25T08:00:04Z"
      },
      "response": {
        "status": 202,
        "body": "{\"data\":{\"status\":\"accepted\"}}",
        "timestamp": "2026-02-25T08:00:04.500Z"
      }
    }
  ]
}
```

---

## 3. Request & Response Examples

### 3.1 eBUZIMA Clinical Visit

**Request:**

```bash
curl -X POST http://localhost:8082/inbound/ebuzima \
  -H "Content-Type: application/json" \
  -H "X-Source-System: ebuzima" \
  -H "X-Facility-Id: FAC-FOSA-001" \
  -d '{
    "visitId": "ebz-visit-9876",
    "patientUpid": "UPID-PAT-12345",
    "visitDate": "2026-02-25T08:00:00Z",
    "facilityId": "FAC-FOSA-001",
    "visitType": "CLINICAL_VISIT",
    "status": "completed"
  }'
```

**Generated CloudEvent (sent to Collector):**

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
  "data": {
    "resourceType": "Encounter",
    "id": "enc-uuid-visit-kicukiro-001",
    "status": "finished",
    "class": {"code": "AMB"},
    "subject": {"reference": "Patient/UPID-PAT-12345"},
    "period": {"start": "2026-02-25T08:00:00Z"}
  }
}
```

### 3.2 eBUZIMA Visit with Multiple Resources

**Result:** When an eBUZIMA clinical visit includes observations and immunizations, the `EbuzimaPayloadMapper` produces multiple FHIR R4 resources. Each is wrapped in a separate CloudEvent and forwarded individually to the Collector (e.g., one `org.openphc.cce.encounter` + one or more `org.openphc.cce.observation`).

---

## 4. Error Responses

Error responses are wrapped in the OpenHIM mediator envelope with `"status": "Failed"`.

### 4.1 Source Not Recognized (400)

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Failed",
  "response": {
    "status": 400,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"error\":{\"code\":\"SOURCE_NOT_RECOGNIZED\",\"message\":\"No adaptor found for source: unknown\"}}",
    "timestamp": "2026-02-25T08:00:05Z"
  },
  "orchestrations": []
}
```

### 4.2 FHIR Mapping Error (422)

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Failed",
  "response": {
    "status": 422,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"error\":{\"code\":\"FHIR_MAPPING_ERROR\",\"message\":\"Failed to map eBUZIMA payload to FHIR Encounter\"}}",
    "timestamp": "2026-02-25T08:00:05Z"
  },
  "orchestrations": []
}
```

### 4.3 Patient ID Not Found (400)

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Failed",
  "response": {
    "status": 400,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"error\":{\"code\":\"PATIENT_ID_NOT_FOUND\",\"message\":\"No patient reference found in Observation\"}}",
    "timestamp": "2026-02-25T08:00:05Z"
  },
  "orchestrations": []
}
```

### 4.4 Collector Forwarding Failure (502)

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Failed",
  "response": {
    "status": 502,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"error\":{\"code\":\"COLLECTOR_FORWARDING_ERROR\",\"message\":\"All retries exhausted for event a1b2c3d4\"}}",
    "timestamp": "2026-02-25T08:00:10Z"
  },
  "orchestrations": [
    {
      "name": "Forward to CCE Collector (attempt 1/3)",
      "request": {"method": "POST", "path": "/v1/events"},
      "response": {"status": 503, "body": "Service Unavailable"},
      "timestamp": "2026-02-25T08:00:06Z"
    }
  ]
}
```

---

## 5. Actuator Endpoints

Spring Boot Actuator endpoints exposed for operations.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/health` | GET | Overall health status |
| `/actuator/health/liveness` | GET | Kubernetes liveness probe |
| `/actuator/health/readiness` | GET | Kubernetes readiness probe |
| `/actuator/info` | GET | Application info (name, version) |
| `/actuator/prometheus` | GET | Prometheus metrics scrape endpoint |
| `/actuator/metrics` | GET | All available metrics list |
| `/actuator/metrics/{metricName}` | GET | Specific metric detail |

### Health Response

```json
{
  "status": "UP",
  "groups": ["liveness", "readiness"]
}
```

### Prometheus Metrics (excerpt)

```
# HELP cce_emitter_events_received_total Total inbound events received
# TYPE cce_emitter_events_received_total counter
cce_emitter_events_received_total{source="ebuzima"} 42.0

# HELP cce_emitter_collector_latency_seconds Collector forwarding latency
# TYPE cce_emitter_collector_latency_seconds summary
cce_emitter_collector_latency_seconds_count 42.0
cce_emitter_collector_latency_seconds_sum 8.456
```

---

## 6. OpenHIM Core API Calls (Outbound)

Calls made by the adaptor to OpenHIM Core for mediator lifecycle.

### 6.1 Registration

```
POST https://<openhim-core>:8080/mediators
Authorization: Basic <base64(username:password)>
Content-Type: application/json

{
  "urn": "urn:mediator:cce-emitter-adaptor",
  "version": "1.0.0",
  "name": "CCE Emitter Adaptor",
  "description": "Transforms source system events into CloudEvents for CCE Compliance pipeline",
  "defaultChannelConfig": [...],
  "endpoints": [...]
}
```

**Response:** `201 Created` (first registration) or `200 OK` (update).

### 6.2 Heartbeat

```
POST https://<openhim-core>:8080/mediators/urn:mediator:cce-emitter-adaptor/heartbeat
Authorization: Basic <base64(username:password)>
Content-Type: application/json

{
  "uptime": 3600000
}
```

**Response:** `200 OK` with optional `config` object for dynamic configuration.

---

## 7. Downstream Call (Outbound)

### Forward to CCE Collector

```
POST <collector-url>/v1/events
Content-Type: application/json

{
  "specversion": "1.0",
  "id": "...",
  "source": "...",
  "type": "org.openphc.cce.encounter",
  ...
}
```

**Success:** `202 Accepted`
**Duplicate:** `200 OK`
**Validation Error:** `400 Bad Request` (missing `type`)
**Server Error:** `5xx` → retried with exponential backoff (max 3 attempts)
