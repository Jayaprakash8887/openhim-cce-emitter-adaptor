# CCE Emitter Adaptor — Release Notes v1.0.0

**Release Date:** March 9, 2026
**Branch:** `release-1.0.0`
**Artifact:** `cce-emitter-adaptor-1.0.0-SNAPSHOT.jar`
**Docker Image:** `cce-emitter-adaptor:1.0.0`

---

## 1. Overview

First production release of the **CCE Emitter Adaptor** — an OpenHIM mediator that receives FHIR R4 resource payloads from eBUZIMA EMR (via OpenHIM Core secondary route), wraps them in CloudEvents v1.0 envelopes, and forwards them to the CCE Collector Service.

## 2. Features

| Feature | Description |
|---------|-------------|
| **FHIR R4 Ingestion** | Receives individual FHIR resources (Encounter, Observation, Patient, RelatedPerson, Condition, etc.) via `POST /inbound` |
| **Patient & RelatedPerson Support** | Patient resources extract UPID from `Patient.identifier[]` (matching configured system URI `http://openphc.org/identifier/upid`) with `Patient.id` fallback. RelatedPerson extracts from `patient` reference. |
| **Source Routing** | Config-driven source resolution via `X-OpenHIM-ClientID` or `X-Source-System` headers |
| **CloudEvents v1.0** | Wraps FHIR resources in spec-compliant CloudEvents with CCE extensions (`facilityid`, `sourceeventid`, `correlationid`) |
| **Correlation ID from OpenHIM** | Uses `X-OpenHIM-TransactionID` header (set automatically by OpenHIM Core) as the preferred `correlationid`. Falls back to `X-Correlation-Id`, then adaptor-generated UUID. |
| **Non-POST Request Handling** | Gracefully acknowledges non-POST requests (GET, PUT, DELETE, PATCH, HEAD, OPTIONS) with 200 OK to avoid 405 errors in OpenHIM transaction log |
| **Collector Forwarding** | POSTs CloudEvents to CCE Collector with retry + exponential backoff (configurable max attempts) |
| **SocketTimeoutException Handling** | Specifically detects `SocketTimeoutException` (including when not wrapped in `ResourceAccessException`) and treats as retryable |
| **Global Error Handling** | Catch-all exception handler returns structured 500 `INTERNAL_ERROR` responses for unexpected failures |
| **OAuth2 Authentication** | Keycloak `client_credentials` token management for Collector auth (`CollectorTokenService`). Automatic caching and refresh. Falls back to static Bearer token for local dev. |
| **OpenHIM Lifecycle** | Automatic registration on startup, periodic heartbeat, `application/json+openhim` response envelope |
| **Facility Filter** | Config-driven facility allowlist (`cce.emitter.facility-filter.ids`). Empty list = all events pass; non-empty = only listed IDs admitted. Skipped events return `200 OK` with `status: "skipped"` — OpenHIM records the transaction as Completed (not Failed). `FacilityIdExtractor` resolves facility ID from any FHIR resource location field (`Encounter.location`, `locationReference[]`, or direct `location` reference); any `ResourceType/` prefix stripped generically (`Location/1302` and `Organization/1302` both → `1302`). Resources with no location info pass through unconditionally. Controlled via `FACILITY_FILTER_IDS` env var (comma-separated). |
| **Observability** | Micrometer/Prometheus metrics (7 custom metrics including `cce_emitter_events_filtered_total`), structured MDC logging (dev: human-readable, prod: JSON) |
| **Health Probes** | Kubernetes-compatible liveness (`/actuator/health/liveness`) and readiness (`/actuator/health/readiness`) |

## 3. Known Limitations (v1.0)

| Limitation | Detail |
|-----------|--------|
| **Bundle resources** | FHIR Bundle resources are silently ignored — only individual resources are processed |
| **No dynamic config** | OpenHIM heartbeat-based dynamic configuration is not consumed |
| **Single source** | Only eBUZIMA EMR is configured (additional sources require config changes only — no code changes) |

## 4. Infrastructure Prerequisites

| Dependency | Version | Purpose |
|-----------|---------|---------|
| **OpenHIM Core** | v8.4.3+ | Receives eBUZIMA traffic and routes via secondary route |
| **CCE Gateway** | — | OAuth validation + routing to Collector |
| **CCE Collector** | — | Receives CloudEvents at `POST /v1/events` |
| **Docker Runtime** | 24.x+ | Container execution |

## 5. Docker Image

### Build

```bash
docker build -t cce-emitter-adaptor:1.0.0 .
```

### Image Details

| Property | Value |
|----------|-------|
| Base image | `eclipse-temurin:21-jre-jammy` |
| Runs as | Non-root `appuser` |
| Exposed port | `8082` |
| HEALTHCHECK | `curl -f http://localhost:8082/actuator/health/liveness` (30s interval, 15s start period) |

### Run

```bash
docker run -d \
  --name cce-emitter-adaptor \
  -p 8082:8082 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e OPENHIM_CORE_HOST=<openhim-core-host> \
  -e OPENHIM_USERNAME=<openhim-username> \
  -e OPENHIM_PASSWORD=<openhim-password> \
  -e CCE_COLLECTOR_URL=<collector-base-url> \
  -e KEYCLOAK_HOST=<keycloak-base-url> \
  -e KEYCLOAK_CLIENT_ID=<client-id> \
  -e KEYCLOAK_CLIENT_SECRET=<client-secret> \
  -e EBUZIMA_CLIENT_ID=<ebuzima-client-id> \
  cce-emitter-adaptor:1.0.0
```

## 6. Environment Variables

### Required (no defaults — must be set)

| Variable | Description |
|----------|-------------|
| `OPENHIM_CORE_HOST` | Hostname/IP of OpenHIM Core API |
| `OPENHIM_USERNAME` | OpenHIM Core API username |
| `OPENHIM_PASSWORD` | OpenHIM Core API password |
| `CCE_COLLECTOR_URL` | CCE Collector base URL (via Gateway) |
| `KEYCLOAK_HOST` | Keycloak base URL (e.g., `https://keycloak.cce.mdtlabs.org`). Required for OAuth2. |
| `KEYCLOAK_CLIENT_ID` | OAuth2 client ID for Keycloak `client_credentials` grant |
| `KEYCLOAK_CLIENT_SECRET` | OAuth2 client secret |
| `EBUZIMA_CLIENT_ID` | OpenHIM client ID for eBUZIMA EMR matching |

### Optional (have sensible defaults)

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | Set to `prod` for production |
| `SERVER_PORT` | `8082` | HTTP listen port |
| `OPENHIM_CORE_API_PORT` | `8080` | OpenHIM Core API port |
| `OPENHIM_HEARTBEAT_INTERVAL` | `10` | Heartbeat interval in seconds |
| `CCE_COLLECTOR_AUTH_TOKEN` | — | Static Bearer token (fallback when Keycloak is not configured) |
| `KEYCLOAK_REALM` | `cce` | Keycloak realm name |
| `CCE_COLLECTOR_EVENTS_PATH` | `/v1/events` | Collector endpoint path |
| `CCE_COLLECTOR_TIMEOUT` | `5000` | HTTP timeout in ms |
| `CCE_COLLECTOR_RETRY_MAX_ATTEMPTS` | `3` | Max retry attempts on 5xx/timeout |
| `CCE_COLLECTOR_RETRY_BACKOFF_MS` | `1000` | Initial backoff delay (doubles per retry) |
| `MEDIATOR_ENDPOINT_HOST` | `emitter-adaptor` | Hostname registered with OpenHIM Core |
| `FACILITY_FILTER_IDS` | — | Comma-separated FOSA facility IDs to allow (e.g. `"0030,0042,0099"`). Empty or unset = all events pass. |

## 7. OpenHIM Configuration (Manual Steps)

The mediator does **not** auto-provision an OpenHIM channel (`defaultChannelConfig` is empty by design). The following manual steps are required after deployment:

### 7.1 Verify Mediator Registration

1. Open OpenHIM Console → **Mediators** tab
2. Confirm `CCE Emitter Adaptor` (URN: `urn:mediator:cce-emitter-adaptor`) appears with heartbeat active
3. If not visible, check service logs for registration errors (non-fatal — the service still functions)

### 7.2 Add Secondary Route

On the **existing eBUZIMA channel** in OpenHIM Console:

1. Navigate to **Channels** → select the eBUZIMA channel
2. Go to **Routes** tab → **Add Route**
3. Configure:

| Field | Value |
|-------|-------|
| Name | `CCE Emitter Adaptor` |
| Host | `<emitter-adaptor-hostname>` (Docker service name or IP) |
| Port | `8082` |
| Path | `/inbound` |
| Primary | **No** (secondary route) |
| Type | `http` |

4. Save the channel

> **Important:** Setting `primary: false` ensures the adaptor receives a copy of each request without affecting the existing primary routing (SHR, etc.).

## 8. Health & Monitoring

### Health Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |

### Prometheus Metrics

Scrape endpoint: `GET /actuator/prometheus`

| Metric | Type | Description |
|--------|------|-------------|
| `cce_emitter_events_received_total` | Counter | Total inbound events (tags: `source`, `path`) |
| `cce_emitter_events_forwarded_total` | Counter | Events successfully forwarded (tag: `source`) |
| `cce_emitter_events_duplicate_total` | Counter | Duplicate events (Collector returned 200) |
| `cce_emitter_events_rejected_total` | Counter | Events rejected by Collector (4xx) |
| `cce_emitter_collector_latency_seconds` | Timer | Collector forwarding round-trip latency |
| `cce_emitter_collector_retries_total` | Counter | Retry attempts exhausted |
| `cce_emitter_events_filtered_total` | Counter | Events denied by facility filter (tags: `source`, `facility`, `reason`) |

### Logging

Production profile outputs **structured JSON** to stdout:

```json
{"timestamp":"2026-03-09T10:00:00.000Z","level":"INFO","logger":"o.o.c.e.service.InboundEventService","thread":"http-nio-8082-exec-1","correlationId":"corr-abc-123","source":"ebuzima","eventType":"Encounter","subject":"260225-0002-5501","message":"Event forwarded to Collector"}
```

Key MDC fields for log filtering: `correlationId`, `source`, `eventType`, `subject`.

## 9. Smoke Test (Post-Deployment)

```bash
# 1. Health check
curl -f http://<host>:8082/actuator/health/liveness
# Expected: {"status":"UP"}

# 2. Prometheus metrics accessible
curl -s http://<host>:8082/actuator/prometheus | head -5
# Expected: Prometheus text format output

# 3. Send a test FHIR Encounter
curl -X POST http://<host>:8082/inbound \
  -H "Content-Type: application/json" \
  -H "X-OpenHIM-ClientID: <ebuzima-client-id>" \
  -d '{"resourceType":"Encounter","id":"smoke-test-001","status":"finished","subject":{"reference":"Patient/SMOKE-TEST-PAT"},"period":{"start":"2026-03-09T00:00:00Z"}}'
# Expected: 202 Accepted with application/json+openhim response

# 4. Verify metrics incremented
curl -s http://<host>:8082/actuator/prometheus | grep cce_emitter_events_received
# Expected: cce_emitter_events_received_total{source="ebuzima",...} 1.0
```

## 10. Rollback

The service is stateless — no database migrations or persistent state to revert. To roll back:

1. Stop the container
2. Deploy the previous image version
3. Verify health endpoint returns `UP`

No data cleanup is required. The CCE Collector handles deduplication, so replayed events during rollback/redeploy are safe.
