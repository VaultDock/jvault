# 6. REST API

Base path `/api/v1`. OpenAPI 3.1 at `/api/v1/openapi.json`, generated from code; CI fails on
drift against [docs/api/openapi.yaml](api/openapi.yaml).

## 6.1 Cross-cutting rules

**Versioning.** The major version is in the path. Additive changes (new optional fields, new
endpoints, new enum values on *response*-only fields) ship in v1. Anything else is v2. Clients
must ignore unknown response fields; this is stated in the spec description and tested.

**Authentication.** Browser clients use the BFF session cookie plus a CSRF token
(double-submit). Machine clients use `Authorization: Bearer <token>` where the token is issued
by the enterprise IdP; jvault validates it as a resource server (issuer, audience, signature,
expiry) and maps scopes/roles to jvault permissions. There are no jvault-local API keys.

**Jira-backed operations** additionally require a connected Jira identity. If the caller has no
usable Jira connection, the response is:

```
409 Conflict
{ "type": "https://jvault.example/probs/jira-not-connected",
  "title": "Jira is not connected",
  "status": 409,
  "connectUrl": "/api/v1/jira/connections/start?deployment=..." }
```

**Idempotency.** `POST` endpoints that create resources accept `Idempotency-Key` (client-chosen,
≤ 255 chars). jvault stores key → (request fingerprint, response) for 24 h.

| Situation | Result |
|---|---|
| Same key, same fingerprint, completed | Original response replayed, `Idempotency-Replayed: true` |
| Same key, same fingerprint, in flight | `409 request-in-progress`, `Retry-After` |
| Same key, different fingerprint | `409 idempotency-key-reuse` |

**Concurrency.** jvault-owned resources return `ETag`; mutations accept `If-Match` and return
`412` on mismatch. Jira does not offer per-issue ETags, so for Jira-owned fields jvault performs
a read-modify-write with a `updated`-timestamp check and reports a `409 stale-jira-state` when
the issue changed underneath.

**Pagination.** Cursor-based:

```
GET /api/v1/tickets?spaceId=...&limit=50&cursor=eyJ...
→ { "items": [...], "nextCursor": "eyJ...", "hasMore": true }
```

`limit` defaults to 50, caps at 200. Cursors are opaque, signed, and encode the sort key plus a
snapshot marker so that concurrent inserts cannot cause an item to be skipped. Offset paging is
not offered. Endpoints that proxy Jira search translate Jira's `startAt`/`maxResults` into the
same cursor shape so clients see one paging model.

**Errors.** RFC 9457 `application/problem+json`:

```json
{
  "type": "https://jvault.example/probs/field-validation",
  "title": "The request failed validation",
  "status": 400,
  "instance": "/api/v1/tickets",
  "traceId": "00-4bf92f...-01",
  "errors": [
    { "field": "fields.customfield_10010", "code": "REQUIRED",
      "message": "Field is required by the Jira create screen." },
    { "field": "fields.summary", "code": "MAX_LENGTH", "limit": 255 }
  ]
}
```

Rules: `message` is a fixed string per `code` or a verbatim Jira message; it never contains
submitted values or stored content. `traceId` is the W3C trace id so support can correlate
without a payload. The problem `type` registry is published in the OpenAPI spec.

**Rate limiting.** jvault applies its own per-principal limits and returns `429` with
`Retry-After` and `RateLimit-*` headers. Jira's own 429s are absorbed by the outbox and surfaced
as `202 Accepted` with a status link rather than propagated.

## 6.2 Endpoint map

### Metadata (drives the dynamic form)

| Method | Path | Notes |
|---|---|---|
| GET | `/meta/deployments` | Configured Jira deployments visible to the caller |
| GET | `/meta/projects` | Projects where the caller may create issues |
| GET | `/meta/projects/{projectKey}/issuetypes` | From createmeta (§0.5) |
| GET | `/meta/projects/{projectKey}/issuetypes/{issueTypeId}/fields` | Field metadata + jvault placement annotation per field + form layout |
| GET | `/meta/fields/{fieldKey}/options` | Paged option lookup for large `allowedValues` |
| GET | `/meta/users/search` | Assignable user search, proxied |

The field metadata response annotates each field with its resolved placement so the UI can label
externally-stored fields before the user types anything into them — a small thing that prevents a
large class of user error.

### Tickets

| Method | Path | Notes |
|---|---|---|
| POST | `/tickets` | Create. `Idempotency-Key` recommended. `202` when the Jira write is queued, `201` when confirmed synchronously |
| GET | `/tickets/{ticketRef}` | Merged view: Jira fields + external parts the caller may see |
| GET | `/tickets/by-issue/{issueKey}` | Resolve from a Jira key |
| PATCH | `/tickets/{ticketRef}` | Partial update; only changed fields |
| POST | `/tickets/{ticketRef}/transitions` | Release 1 |
| GET | `/tickets/{ticketRef}/status` | Outbox/processing state for async creation |
| GET | `/tickets` | List/search within spaces the caller can see |

Create request:

```json
{
  "deploymentId": "…", "projectKey": "SEC", "issueTypeId": "10004",
  "fields": {
    "summary": "Suspicious egress from build agent",
    "description": { "format": "adf", "value": { "version": 1, "type": "doc", "content": [] } },
    "priority": { "id": "2" },
    "customfield_10010": "prod-eu-1"
  },
  "placementOverrides": { "description": "EXTERNAL" },
  "attachments": [ { "uploadId": "01J8…", "fileName": "capture.pcap" } ],
  "origin": { "actor": { "type": "USER", "id": "…" } }
}
```

`placementOverrides` is honoured only where the resolved policy sets `allowOverride: true` and
only in the protective direction (§5.1); otherwise `422 placement-override-forbidden`.

### Comments

| Method | Path |
|---|---|
| GET | `/tickets/{ticketRef}/comments` |
| POST | `/tickets/{ticketRef}/comments` |
| GET/PATCH/DELETE | `/tickets/{ticketRef}/comments/{commentRef}` |

### Attachments and uploads

Large files use a three-step upload so that a failed create never leaves a half-written object
and so the client can resume:

| Method | Path | Notes |
|---|---|---|
| POST | `/uploads` | Declare size, media type, checksum → `uploadId`, chunk size |
| PUT | `/uploads/{uploadId}/chunks/{n}` | Idempotent per chunk |
| POST | `/uploads/{uploadId}/complete` | Verifies SHA-256, returns a handle usable for 24 h |
| POST | `/tickets/{ticketRef}/attachments` | Binds uploads to a ticket, or accepts small multipart directly |
| DELETE | `/tickets/{ticketRef}/attachments/{partRef}` | |

### Externally stored content

| Method | Path | Notes |
|---|---|---|
| GET | `/content/{contentRef}` | Metadata: size, media type, classification, versions, permissions summary |
| GET | `/content/{contentRef}/download` | Streams; `Range` supported; authorized per request |
| GET | `/content/{contentRef}/preview` | Rendered preview (release 1) |
| GET | `/content/{contentRef}/versions` | |
| GET | `/content/{contentRef}/versions/{versionNo}/download` | Re-authorized independently |
| PUT | `/content/{contentRef}` | New version |
| DELETE | `/content/{contentRef}` | Soft delete under retention |
| POST | `/content/{contentRef}/restore` | Within retention |

`GET /c/{contentRef}` (outside `/api/v1`) is the **stable human link** written into Jira. It
authenticates, authorizes, and then redirects to the UI viewer or streams, depending on `Accept`.
It is a permanent URL by contract (FR-LNK-1) and is therefore deliberately *not* versioned.

### Permissions

| Method | Path |
|---|---|
| GET | `/permissions/{scopeType}/{scopeId}` |
| POST | `/permissions/{scopeType}/{scopeId}/grants` |
| PATCH/DELETE | `/permissions/{scopeType}/{scopeId}/grants/{grantId}` |
| POST | `/permissions/{scopeType}/{scopeId}/inheritance-break` |
| GET | `/permissions/effective?principalId=&scopeType=&scopeId=` | Debugging aid: effective permission plus the reason chain |

`effective` returning the *reason* is worth the effort — "why can this person see this" is the
question every access review asks, and answering it from the audit log alone is painful.

### Jira connection

| Method | Path |
|---|---|
| GET | `/jira/connections` |
| POST | `/jira/connections/start` → `{ authorizationUrl }` |
| GET | `/jira/connections/callback` | Browser redirect target |
| GET | `/jira/connections/{id}/sites` | Cloud accessible-resources |
| POST | `/jira/connections/{id}/site` | Select `cloudId` |
| DELETE | `/jira/connections/{id}` | Disconnect; revokes upstream where supported |

### Ingestion status

| Method | Path |
|---|---|
| GET | `/ingestion/messages/{correlationId}` |
| GET | `/ingestion/messages?state=DEAD_LETTERED&…` |
| POST | `/ingestion/messages/{correlationId}/replay` | Replays from encrypted quarantine |

### Administration

`/admin/placement-policies`, `/admin/storage-backends`, `/admin/storage-routes`,
`/admin/kafka-mappings`, `/admin/jira-deployments`, `/admin/integration-identity`,
`/admin/spaces`, `/admin/key-rings`, `/admin/audit`. All mutations audited with before/after
hashes; policy updates validated as a set and applied atomically.

## 6.3 Consistency across entry points

The conformance suite (FR-API-2 AC1) is the mechanism that keeps this promise honest. One
scenario table is executed three times — through the UI's backing calls, through the public REST
API, and through a synthetic Kafka message — and each run asserts the same resulting Jira issue,
the same external objects, the same ACLs and the same audit events (modulo origin and actor).
If the three paths ever diverge, the suite fails; this is the single most valuable test in the
project and should be built in the MVP, not retrofitted.
