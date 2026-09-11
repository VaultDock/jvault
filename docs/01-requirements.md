# 1. Requirements specification

Requirement IDs are stable and referenced from the rest of the design and from tests.
Priority: **M** = MVP, **1** = release 1, **2** = release 2, **3** = later.
Every requirement has at least one acceptance criterion written so that it can be
turned directly into an automated test.

---

## 1.1 Glossary

| Term | Meaning |
|---|---|
| **Ticket record** | jvault's record binding one Jira issue to its content parts. Identified by a `ticketRef` (UUIDv7). |
| **Content part** | One addressable piece of ticket content: summary, description, body, a comment, an attachment, or a custom field value. |
| **Placement** | Where a content part's value lives: `JIRA`, `EXTERNAL`, or `SPLIT`. |
| **Surrogate** | The non-sensitive value written into Jira in place of externally-stored content. |
| **Content ref** | Opaque, stable identifier of an external content part; the basis of the permanent link. |
| **Integration identity** | The administrator-configured Jira identity used for unattended operations. |
| **Actor** | The human or service principal that originated a change, recorded independently of the integration identity. |
| **Space** | The unit of content authorization, one per (Jira deployment, project) by default. |

---

## 1.2 Functional requirements — web UI

**FR-UI-1 (M) — Reproduce the Jira create form for the configured instance.**
The UI renders project pickers, issue type pickers, and the field set returned by the
create metadata API for that project/issue type, honouring required flags, field types,
allowed values, and default values.

- *AC1* Given a project and issue type whose create screen contains fields of each supported
  schema type, when the create form loads, then every field returned by
  `GET /issue/createmeta/{project}/issuetypes/{issueTypeId}` is rendered with a control
  matching its `schema.type`/`schema.custom`, or with an explicit "unsupported field"
  notice naming the field.
- *AC2* Fields marked `required: true` block submission client-side with a field-level message.
- *AC3* Fields with `allowedValues` offer only those values; free text is rejected.
- *AC4* A user lacking Jira *Create Issues* permission on a project does not see that project
  in the picker, and a direct API attempt returns 403.
- *AC5* Create metadata is cached no longer than the configured TTL (default 10 minutes) and is
  invalidated when the admin triggers a refresh.

**FR-UI-2 (M) — Rich text description with ADF fidelity.**
Descriptions are authored in a rich-text editor and stored as ADF for Jira Cloud, or as
wiki markup for Data Center.

- *AC1* Paragraphs, headings, bold/italic/code marks, ordered and unordered lists, code
  blocks, block quotes, panels, tables, links and mentions round-trip through
  create → read → edit → read without loss.
- *AC2* Content the editor cannot represent is preserved verbatim on edit rather than dropped
  (round-trip safety test over a corpus of real ADF documents).
- *AC3* `@`-mentions resolve against Jira user search and serialise to ADF `mention` nodes.

**FR-UI-3 (M) — View and edit existing tickets.**
- *AC1* Editable fields are exactly those returned by the edit metadata API with a `set`
  operation available; all others render read-only.
- *AC2* A field whose value is externally placed renders the external value inline to an
  authorized user, and renders the surrogate plus an access-denied notice to an
  unauthorized one.
- *AC3* Saving sends only changed fields.

**FR-UI-4 (M) — Comments: add, edit, delete.**
- *AC1* Edit and delete controls appear only when the user's Jira permissions allow it
  (`Edit Own/All Comments`, `Delete Own/All Comments`), determined from Jira, not inferred.
- *AC2* A comment whose policy places it externally shows the external body to authorized
  users; Jira holds only the surrogate and link.
- *AC3* Deleting an externally-placed comment deletes the Jira comment and transitions the
  external part to `DELETED` under the retention policy, not immediately hard-deleted.

**FR-UI-5 (M) — Attachments: upload, download, delete.**
- *AC1* Uploads stream; a 1 GB file uploads without the server buffering it in memory
  (measured: peak heap growth < 64 MB during the transfer).
- *AC2* Externally-placed attachments never reach Jira; Jira receives a remote link only.
- *AC3* Download is authorized per request; a valid link with an unauthorized session
  returns 403 and emits an audit event.
- *AC4* Attachment deletion respects Jira permissions and jvault `DELETE` permission; the
  more restrictive wins.

**FR-UI-6 (1) — Preview.** Images, PDFs and plain text externally-placed content can be
previewed without downloading.
- *AC1* Previews are generated in an isolated worker with no outbound network access.
- *AC2* No plaintext of previewed content is written to durable local disk.
- *AC3* Preview requests are authorized identically to downloads.

**FR-UI-7 (1) — Version history.** Users with `VIEW` see the version list of an external
part; opening a specific version re-authorizes.

**FR-UI-8 (M) — Connect Jira.** A "Connect Jira" control starts the OAuth flow; the UI shows
connection state, the connected account, granted scopes, expiry, and offers disconnect.
- *AC1* No token value is ever present in the DOM, `localStorage`, `sessionStorage`, or a
  non-`HttpOnly` cookie (asserted by an automated browser test).
- *AC2* When the user can reach multiple Jira Cloud sites, a site selection step is shown and
  the chosen `cloudid` is persisted.

---

## 1.3 Functional requirements — REST API

**FR-API-1 (M) — Versioned, documented API.** All endpoints under `/api/v1`; an OpenAPI 3.1
document is served at `/api/v1/openapi.json` and is generated from the running code.
- *AC1* CI fails if the committed spec differs from the generated one.
- *AC2* Every 4xx/5xx response validates against RFC 9457 `application/problem+json`.

**FR-API-2 (M) — Ticket create, read, update** with the same validation, authorization and
placement rules as the UI.
- *AC1* A conformance suite runs the same 40+ scenarios through the UI's backing calls, the
  public REST API, and the Kafka consumer, and asserts identical resulting Jira state and
  identical external storage state.

**FR-API-3 (M) — Idempotency.** `POST` endpoints that create resources accept an
`Idempotency-Key` header.
- *AC1* Replaying a request with the same key and the same body fingerprint within 24 h
  returns the original response and creates nothing new.
- *AC2* Same key, different fingerprint returns 409 `idempotency-key-reuse`.
- *AC3* A concurrent duplicate (same key, in flight) returns 409 `request-in-progress`, never
  a second Jira issue.

**FR-API-4 (M) — Structured errors that never leak content.**
- *AC1* For every error path, the response body contains no substring of any field value the
  caller submitted and no substring of any externally-stored content (property-based test
  with tagged canary strings).

**FR-API-5 (M) — Pagination.** Cursor-based, opaque cursors, `limit` capped at 200.
- *AC1* Paging a list that is mutated mid-iteration never skips a pre-existing item.

**FR-API-6 (M) — Content endpoints.** Metadata, download, versions, and (release 1) preview.
- *AC1* Range requests are supported and authorized per request.

**FR-API-7 (M) — Permission endpoints.** Read and modify grants on spaces, tickets and parts.

**FR-API-8 (1) — Admin configuration endpoints** for placement policies, storage backends and
routes, Kafka mappings, Jira deployments, and the integration identity.
- *AC1* Every mutation writes an audit event containing actor, before-hash and after-hash.
- *AC2* Policy changes are validated and rejected atomically; a rejected policy set leaves the
  active configuration untouched.

**FR-API-9 (1) — Service identities.** Machine clients authenticate with OAuth 2.0
client credentials (or `private_key_jwt`) against the enterprise IdP, never with a jvault-local
secret.

---

## 1.4 Functional requirements — Kafka

**FR-KAF-1 (1) — Consume configurable topics** and create Jira tickets from events.
- *AC1* Topics, consumer group, schema, mapping and target project/issue type are
  configuration, not code.

**FR-KAF-2 (1) — Schema validation.** Each configured topic declares a schema (Avro via
Schema Registry, or JSON Schema).
- *AC1* A message failing schema validation is routed to DLQ without any Jira call.
- *AC2* Validation failures never emit the message payload to logs.

**FR-KAF-3 (1) — Configurable event-to-field mapping**, including which parts are placed
externally.
- *AC1* A mapping expression referencing a missing optional path yields the configured default;
  a missing required path fails validation.
- *AC2* Mapping output is subject to the same placement policy engine as UI and REST.

**FR-KAF-4 (1) — Duplicate prevention.** Replay of the same message, or redelivery after a
consumer crash, must not create a second Jira ticket.
- *AC1* Given a message processed to completion, when the same offset is redelivered, then no
  Jira write occurs and the existing ticket reference is returned in the status API.
- *AC2* Given two *different* offsets carrying the same business dedupe key, then only one Jira
  issue exists and the second message is recorded as `DUPLICATE`.
- *AC3* Given a consumer killed (SIGKILL) between the Jira create call and the offset commit,
  when the consumer restarts, then exactly one Jira issue exists for that message.
  *(Chaos test, run at each of the 6 state-machine boundaries.)*

**FR-KAF-5 (1) — Retries, backoff, dead-lettering.** Transient failures retry with exponential
backoff via delay-tier retry topics; permanent failures dead-letter.
- *AC1* A 429 from Jira honours `Retry-After` and does not consume a retry attempt budget
  reserved for errors.
- *AC2* After the configured attempts are exhausted the message lands in DLQ exactly once.

**FR-KAF-6 (1) — DLQ records contain no sensitive content.**
- *AC1* Given a source message containing a canary secret in a field configured as `EXTERNAL`,
  when processing fails at any stage, then the DLQ record contains no occurrence of the
  canary; the original message is stored in the encrypted quarantine and referenced by id.

**FR-KAF-7 (1) — Processing status and correlation.** Every message gets a correlation id,
propagated to logs, traces, audit, the Jira issue property, and a status endpoint.
- *AC1* `GET /api/v1/ingestion/messages/{correlationId}` returns the state, attempt count, last
  error class (not error content), and resulting `ticketRef` when created.

**FR-KAF-8 (1) — Kafka authn/authz.** TLS plus SASL/SCRAM or mTLS; the consumer principal holds
read on source topics and write on retry/DLQ only.

**FR-KAF-9 (1) — Background identity.** Kafka-originated Jira operations use the integration
identity and never an interactive user session. The originating actor from the event is
recorded separately.
- *AC1* Given an event carrying `actor.email`, when the ticket is created, then the Jira issue
  records the integration identity as creator, and the actor is recorded in the jvault audit
  trail and in the configured actor surface (issue property and/or a visible field).

---

## 1.5 Functional requirements — content placement

**FR-CP-1 (M) — Per-field placement policy** for summary, description, body, comments,
attachments and custom fields.

**FR-CP-2 (M) — Scoping by project and issue type with deterministic precedence.**
- *AC1* For any (project, issue type, field) triple, exactly one policy resolves; the resolver
  is a pure function and is covered by a table-driven test including every precedence tie.
- *AC2* Two policies that would tie are rejected at configuration validation time.

**FR-CP-3 (M) — Placeholders for Jira-required fields.** When Jira requires a field whose value
is external, an approved surrogate is written.
- *AC1* Summary is always present and ≤ 255 characters.
- *AC2* A surrogate is produced only from a template using allow-listed tokens
  (ticket ref, classification label, issue type, timestamp, static text, link). No token
  can interpolate original content unless the policy explicitly enables a
  `derivedSummary` that has passed the configured classifier.

**FR-CP-4 (2) — Section-level placement.** Selected sections of a description or body are
placed externally while the remainder stays in Jira.
- *AC1* On Jira Cloud, sections are identified as ADF subtrees; extracting and re-inserting a
  section is lossless for the surrounding document.
- *AC2* Where the target representation is unstructured (Data Center wiki markup), section
  splitting is refused at configuration time with a clear message rather than attempted.

**FR-CP-5 (M) — No leakage of external content into Jira.**
- *AC1* Every outbound Jira payload passes an egress check; a payload containing content whose
  normalised hash matches an `EXTERNAL` part of the same ticket is rejected and the operation
  fails closed.
- *AC2* An architecture test asserts that no class outside `jira.gateway` invokes the Jira HTTP
  client.

**FR-CP-6 (M) — No leakage into logs, telemetry or errors.**
- *AC1* A canary-string test drives every code path (create, edit, comment, upload, failure,
  timeout, Kafka DLQ) and asserts the canary appears in none of: application logs, access logs,
  OpenTelemetry span attributes, metric labels, HTTP error bodies, DLQ records, or the Jira
  issue.

---

## 1.6 Functional requirements — links

**FR-LNK-1 (M) — Links point at a stable jvault endpoint** of the form
`{baseUrl}/c/{contentRef}`.
- *AC1* The link is unchanged by: editing content, adding a version, migrating the object to a
  different storage backend, or archiving it.

**FR-LNK-2 (M) — Links are not capabilities.**
- *AC1* An anonymous request to a valid link returns 401 and redirects to login; it never
  returns content, a redirect to storage, or any metadata that reveals content.
- *AC2* An authenticated but unauthorized request returns 403 and reveals nothing beyond the
  existence-neutral message. *(Existence is itself hidden by returning 404 when the caller
  lacks `VIEW` on the containing space — see [11. Authorization](11-authorization.md).)*
- *AC3* No response ever contains a storage URL, presigned URL, bucket name, container name,
  CMIS object id, or filesystem path.

**FR-LNK-3 (M) — Placement in Jira.** Links are attached as remote issue links using
`globalId = jvault:content:{contentRef}`, and additionally as description placeholder text or a
comment where the policy requires it.
- *AC1* Re-running link attachment is idempotent — the remote link count does not grow.

**FR-LNK-4 (1) — Lifecycle.** Archived content keeps a resolvable link that reports the archived
state to authorized users. Purged content returns 410 Gone with a tombstone record.

---

## 1.7 Functional requirements — storage

**FR-ST-1 (M) — Pluggable backends:** filesystem, CMIS 1.1, Amazon S3, Azure Blob Storage.
**FR-ST-2 (M) — Routing policy** selects a backend by project, issue type, classification and
part type.
**FR-ST-3 (M) — Metadata in PostgreSQL, bytes in the backend.** Storage keys carry no
content-derived or filename-derived information.
- *AC1* An operator with read access to the bucket alone can learn nothing beyond object size
  and creation time (asserted by inspecting keys and object metadata in an integration test).

**FR-ST-4 (M) — Integrity.** SHA-256 is computed on ingest and verified on every read.
- *AC1* Corrupting a stored object causes reads to fail with `content-integrity-error` and an
  alert, never to return corrupted bytes.

**FR-ST-5 (1) — Versioning** is provided uniformly by jvault regardless of backend capability.
**FR-ST-6 (1) — Retention, deletion and legal hold.** Soft delete with configurable retention,
then hard delete; legal hold blocks hard delete.
- *AC1* A part under legal hold cannot be hard-deleted by any API path, including admin purge.

**FR-ST-7 (2) — Backup and restore.** Documented and rehearsed procedure that restores DB and
objects to a consistent point, including KMS access.
- *AC1* A restore drill recovers a ticket's content end to end in a clean environment.

---

## 1.8 Functional requirements — encryption

**FR-ENC-1 (M) — TLS 1.2+ (prefer 1.3) for all transport**, including to Jira, storage, Kafka,
KMS and the database.
**FR-ENC-2 (M) — Application-level envelope encryption** for external content, with per-object
data keys wrapped by a KMS key.
- *AC1* Objects at rest are indistinguishable from random to a reader without KMS access.
- *AC2* Ciphertext is authenticated; a single flipped bit anywhere fails decryption.
- *AC3* Frames cannot be reordered, duplicated or truncated without detection.

**FR-ENC-3 (M) — Keys are separate from content and from application secrets.**
- *AC1* The storage principal cannot call KMS; the KMS principal cannot read storage
  (asserted by IAM policy tests).

**FR-ENC-4 (1) — Key rotation.** KEK rotation re-wraps data keys without rewriting content;
DEK rotation occurs on write of a new version.
- *AC1* After KEK rotation, previously stored content remains readable and newly written content
  uses the new KEK.

**FR-ENC-5 (M) — Fail closed.** If KMS is unavailable, reads and writes of encrypted content
fail; nothing is ever stored unencrypted as a fallback.

---

## 1.9 Functional requirements — authentication

**FR-AUTH-1 (M)** Application login uses OpenID Connect authorization code + PKCE against the
enterprise IdP (Entra ID, Keycloak). Application API authorization uses OAuth 2.0.
**FR-AUTH-2 (M)** IdP groups/roles map to jvault roles by administrator-managed rules.
- *AC1* Removing a user from an IdP group removes the corresponding jvault privileges within the
  configured cache TTL (default ≤ 5 minutes) without requiring re-login.

**FR-AUTH-3 (M)** Browser sessions are cookie-based, `HttpOnly`, `Secure`, `SameSite=Lax`, with
idle and absolute timeouts, rotation on privilege change, and server-side revocation.
**FR-AUTH-4 (M)** Jira authorization is separate from application login: a user is logged in to
jvault whether or not Jira is connected.
**FR-AUTH-5 (M)** "Connect Jira" performs the full authorization-code exchange server-side; the
user never handles a token.
- *AC1* `state` is single-use, bound to the session, expires in ≤ 10 minutes, and is verified
  before the code exchange.
- *AC2* `redirect_uri` is exact-matched against an allow-list.
- *AC3* PKCE is used where the deployment supports it (Data Center: yes; Cloud: when Atlassian
  exposes it) and its absence is not a silent downgrade — the effective flow is recorded.

**FR-AUTH-6 (M)** Jira tokens are stored encrypted, per user, per deployment; refresh is
single-flight; rotation replaces the stored refresh token atomically.
- *AC1* Ten concurrent requests on an expired token produce exactly one refresh call.
- *AC2* A failed refresh marks the connection `NEEDS_RECONNECT` and surfaces a reconnect prompt
  rather than retrying in a loop.

**FR-AUTH-7 (1)** Background operations use an administrator-configured integration identity.
**FR-AUTH-8 (2)** Manually supplied API tokens / PATs are an optional, admin-enabled alternative
with explicit storage, validation, rotation and removal, clearly distinguished in the UI from
the OAuth flow.
- *AC1* Entering a manual credential requires an explicit admin acknowledgement and writes an
  audit event; the credential is validated against Jira before being saved.

---

## 1.10 Functional requirements — content authorization

**FR-AZ-1 (M)** Permissions: `VIEW`, `CREATE`, `EDIT`, `DELETE`, `DOWNLOAD`, `MANAGE_ACCESS`.
`DOWNLOAD` is not implied by `VIEW`.
**FR-AZ-2 (M)** Grants to users, IdP groups, jvault roles and service principals; inheritance
space → ticket → part, with an inheritance-break flag.
**FR-AZ-3 (M)** Default combination with Jira is **intersection**: a user needs both the jvault
permission and live Jira access to the issue.
- *AC1* A user who can browse the Jira issue but has no jvault grant is denied.
- *AC2* A user with a jvault grant who loses Jira access is denied within the decision cache TTL.
- *AC3* Switching a space to a non-default mode requires an explicit admin confirmation and
  writes a high-severity audit event.

**FR-AZ-4 (M)** No privilege escalation: a grantor may grant only permissions they themselves
hold, and only where they hold `MANAGE_ACCESS`.
- *AC1* Property-based test: for random principal/permission/scope combinations, the resulting
  grant set is always a subset of the grantor's effective set.

**FR-AZ-5 (M)** Every content request is authorized — metadata, preview, thumbnail, download,
range request, version list, historical version.
**FR-AZ-6 (1)** Delegation is depth-limited and revocable; revoking a delegation revokes grants
made under it.
**FR-AZ-7 (M)** Degraded dependencies fail closed by default, with an explicit, audited,
time-boxed grace mode.

---

## 1.11 Functional requirements — reliability and lifecycle

**FR-REL-1 (M)** Defined ownership of every field between Jira and jvault.
**FR-REL-2 (M)** All Jira mutations flow through a transactional outbox with at-least-once
delivery and bounded retries.
**FR-REL-3 (M)** Content-stored-but-Jira-create-failed and Jira-created-but-response-lost are
both recoverable without operator data entry.
- *AC1* Injecting a failure at each of the 9 defined checkpoints leaves the system in a state
  the reconciler resolves within one sweep interval, with no orphaned content and no duplicate
  issue.

**FR-REL-4 (1)** Webhook ingestion plus periodic reconciliation keeps jvault consistent with
Jira-side changes.
**FR-REL-5 (1)** Orphan detection and cleanup for objects with no metadata and metadata with no
object.
**FR-REL-6 (M)** Append-only audit trail for authentication, authorization decisions (including
denials), content access, policy change, and every Jira write.
**FR-REL-7 (M)** Jira rate limits are respected proactively, including the per-issue write limit.

---

## 1.12 Non-functional requirements

| ID | Requirement | Acceptance criterion |
|---|---|---|
| NFR-1 | p95 ticket create (UI, no attachments) ≤ 2.5 s excluding Jira latency beyond p95 | Load test at target concurrency |
| NFR-2 | Attachment throughput ≥ 50 MB/s per node, streaming, constant memory | Load test with 1 GB files |
| NFR-3 | Kafka steady-state throughput ≥ 50 msg/s per consumer instance; lag alarm at > 5 min | Soak test 4 h |
| NFR-4 | Availability 99.5% for read paths; degraded-but-safe behaviour when Jira is down | Chaos test with Jira blackholed |
| NFR-5 | RPO ≤ 15 min, RTO ≤ 4 h | Restore drill |
| NFR-6 | All secrets from a secret manager; none in images, env files in VCS, or logs | Secret scanning in CI; image scan |
| NFR-7 | Horizontal scale: stateless API and workers; no node affinity | Kill any pod under load, no failed requests beyond retry |
| NFR-8 | Supported Jira: Cloud, and Data Center 9.12+/10.x per §13 | Compatibility matrix test suite |
| NFR-9 | Accessibility WCAG 2.2 AA for the UI | axe-core audit in CI, zero criticals |
| NFR-10 | Every dependency SBOM'd; no known critical CVEs at release | CI gate |

---

## 1.13 Explicit non-goals

Migrating existing Jira content into the vault (a separate tool); replacing Jira's UI for
workflow administration; acting as a general-purpose DMS; Jira Service Management request
forms, Assets/CMDB fields and Proforma; Jira plans/boards/sprint management; e-mail
ingestion; mobile native clients.
