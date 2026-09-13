# 15. Phased implementation plan

> **Revised for [D1 and D2](decisions.md).** Jira Cloud *and* Data Center are both in the MVP,
> and content and keys stay on customer infrastructure. Net effect on the MVP: **14–16 weeks
> rather than 10–12**. D1 adds a second deployment implementation and a second rich-text codec;
> D2 pulls HSM/Vault key management and the CMIS backend forward from release 2–3, but removes
> the Azure backend and cloud-KMS work from the critical path.

Estimates assume a team of 4–5 engineers (2 backend, 1 frontend, 1 full-stack, plus part-time
platform/security). They are ranges, not commitments, and the Phase 0 spikes exist precisely
because two of them could move the later numbers.

---

## Phase 0 — De-risking spikes (2 weeks, before committing to the plan)

Three unknowns can change the plan materially, so they are resolved first.

| Spike | Question | Outcome that changes the plan |
|---|---|---|
| ~~**ADF editor**~~ **RESOLVED — use TipTap** | Can `@atlaskit/editor-core` be used standalone in a Vite app, at an acceptable bundle size? | **Measured, and it cannot.** See the numbers below. The fallback becomes the plan: TipTap/ProseMirror plus a jvault ADF serializer |
| ~~**Streaming encryption**~~ **RESOLVED** | Does the chosen library stream with bounded heap and support the partial decryption our range reads need? | **Answered in implementation:** Tink's `subtle.AesGcmHkdfStreaming` takes a raw data key (composing with envelope encryption) and provides `newSeekableDecryptingChannel`, so ranged reads decrypt only the segments they touch. No decrypt-and-discard fallback needed (§9.3) |
| ~~**Jira sandbox conformance**~~ **DONE for Cloud** | Does `POST /issue` accept `properties` at create time? What do createmeta responses look like? What rate-limit behaviour is observed? | **Answered against a live site:** create-time properties work, remote-link upsert on `globalId` confirmed, the deprecated aggregate `createmeta` is still alive, and structured `RateLimit` headers arrive on every response. See §0.3, §0.5, §0.6, §0.9. Still outstanding for Data Center. |
| **Egress reality check** | Can the on-premises deployment reach `auth.atlassian.com` and `api.atlassian.com` through a forward proxy, or is it genuinely air-gapped? | Resolves the [D1+D2 conflict](decisions.md). Reading (b) — truly air-gapped — removes Jira Cloud entirely and makes the MVP **smaller** by roughly 4 weeks |
| **On-prem key manager** | Vault Transit vs PKCS#11 against the actual hardware: does the chosen encryption library integrate cleanly, and what is `GenerateDataKey`/`Decrypt` latency under load? | Drives the KMS adapter choice and whether data-key caching is needed on the read path |

### ADF editor spike — measured, 2026-09-13

Both options installed and built for production with Vite.

| | `@atlaskit/editor-core` 228 | TipTap + StarterKit |
|---|---|---|
| Licence | Apache-2.0 | MIT |
| npm packages installed | **1164** (286 of them `@atlaskit/*`) | 59 |
| `node_modules` | 758 MB | 19 MB |
| Production build | **fails** on a default Vite setup | clean |
| **JS shipped, gzipped** | **4.42 MB** | **0.16 MB** |
| Largest chunk, gzipped | 1.66 MB | 168 kB |
| React | pinned to **18 only** | 18 or 19 |
| Node built-ins | `events`, `buffer` externalised — runtime breakage without polyfills | none |

**Twenty-eight times more JavaScript, for the same job.** And the weight is not the editor: the
bundle pulls in `embedded-confluence-bundle` (379 kB gzipped), `AgentProfileCard` and
`issue-like-data-table-view` — Atlassian product surface this application will never show. Two
further signals of a package meant for use inside Atlassian's own build rather than outside it:
its shipped CSS contains a selector strict parsers reject (`…:after ::selection`), and it imports
Node built-ins that only resolve under a polyfilled bundler.

**Decision: TipTap + ProseMirror, with a jvault ADF serializer.** The cost is real and was already
accepted in §2.4.4 — no Atlassian media nodes, no smart-link unfurling, no exotic panel semantics.
The mapping itself is structural rather than a parsing problem, because ProseMirror's document
model is a tree and so is ADF, and ADF publishes a JSON schema to validate the output against.

Deliverable: three short written findings and any correction to this design.

---

## MVP — "One project, one path, end to end" (14–16 weeks)

**Goal:** a security-style project where descriptions, comments and attachments are stored
externally, encrypted, linked from Jira, and reachable only by authorized users — created through
the UI and the REST API.

**Scope**

| Area | In | Out |
|---|---|---|
| Jira | **Cloud and Data Center** (D1); one deployment configured of each | Multi-site, multi-deployment in one installation |
| Auth | OIDC login (Entra ID or Keycloak); Jira Cloud 3LO connect with site selection and refresh; **DC incoming app link with PKCE**; **integration identity for both** (Cloud service account token, DC service-user PAT) with expiry alerting | Service identities for API clients, manual credentials |
| UI | Create/view/edit; createmeta-driven form; ADF description; comments; attachments; Connect Jira | Previews, version history, transitions, admin UI |
| Placement | Whole-field `JIRA`/`EXTERNAL` for description, comments, attachments, text custom fields. **`SPLIT` refused on DC at config-validation time** (D1) | `SPLIT` on Cloud, derived summaries |
| Rich text | ADF codec (Cloud) **and** wiki-markup codec (DC) behind one `RichTextCodec` | — |
| Storage | **Filesystem, CMIS, and S3-compatible** (on-premises endpoints) (D2) | Azure Blob |
| Encryption | Envelope, framed streaming, **Vault Transit primary + PKCS#11** (D2) | Rotation automation, blind index |
| Authorization | Full permission set, space/ticket/part grants, `INTERSECT` default | Delegation UI, break-glass, access review |
| API | Tickets, comments, attachments, content, permissions; OpenAPI; idempotency; problem+json | Admin endpoints, ingestion status |
| Reliability | Outbox, ambiguity protocol, audit, rate limiting | Webhooks, reconciliation sweeps |
| Kafka | — | All of it |

**Exit criteria**

1. The three-way conformance suite (§6.3) passes for UI and REST paths.
2. The canary-leak suite (FR-CP-6 AC1) passes across every code path.
3. Fault injection at the nine checkpoints (§12.3) resolves without duplicates or orphans.
4. A link shared with an unauthorized user returns 403 and audits the attempt.
5. A 1 GB attachment uploads, encrypts, stores, downloads and verifies within the NFR budgets.
6. A restore drill recovers a ticket end to end, including an unwrap against the on-premises key
   manager — restoring bytes without proving you can decrypt them is not a restore.
7. The compatibility matrix passes against a Cloud sandbox **and** DC 9.12 LTS and 10.x
   containers, with identical resulting behaviour except the documented `SPLIT` asymmetry.
8. A `SPLIT` policy targeting a DC deployment is rejected at save time with a clear message.

**Why this scope.** With D1 and D2 the MVP is no longer the narrowest possible surface — it
carries two deployments and on-premises key management from day one. That is the right call given
both are load-bearing and expensive to retrofit, but it means the schedule has less slack and the
Phase 0 spikes matter more. It proves the hard parts — the leak boundary, the outbox, the ambiguity
protocol, encryption, and intersection authorization — on the narrowest possible surface. Kafka
is deliberately excluded: it multiplies the failure modes without proving anything the REST path
does not already prove.

---

## Release 1 — "Event-driven and operable" (8–10 weeks)

- Kafka ingestion: mappings, schema validation, retry tiers, DLQ without payload, encrypted
  quarantine, dedupe, correlation and status API.
- (Integration identity moved into the MVP by D1/D4.)
- Attribution: `jvault.origin` property, optional reporter override.
- Webhooks: dynamic registration under the integration identity, renewal at day 21, deduplication.
- Reconciliation: 15-minute sweep, nightly deep sweep, orphan GC, duplicate detection.
- Admin UI: placement policies, storage routes, spaces and permission modes, Kafka mappings,
  integration identity, audit browser.
- Previews (isolated worker) and version history.
- Service identities for API clients.
- Azure Blob backend.
- Operational readiness: dashboards, the alert list in §12.9, runbooks for each failure scenario.

**Exit criteria:** the conformance suite passes for all three entry points; a chaos test
SIGKILLs the consumer at each of the six state boundaries with exactly one issue created each
time; a 24-hour soak at target throughput holds lag under 5 minutes.

---

## Release 2 — "Breadth" (8–10 weeks)

- (Data Center and CMIS moved into the MVP by D1/D2.)
- **Section-level `SPLIT`** for ADF, with the marker and heading-scope strategies; remains refused
  on wiki-markup deployments.
- CMIS records-managed retention mode and repository-owned deletion semantics.
- Transitions, issue links, sub-tasks, watchers.
- Bulk operations with per-item outcomes.
- Delegation UI, break-glass, quarterly access review export.
- Key rotation automation: KEK rewrap job, scheduled rotation, rotation dashboard.
- Retention automation, legal hold, backend migration tooling.
- Optional blind-index search, per policy.

---

## Release 3 — "Depth and options" (ongoing)

- Forge or Connect app distribution as an alternative integration identity, which also makes
  entity properties JQL-searchable and simplifies the ambiguity protocol (§12.4.2).
- Multi-deployment and multi-site support in one installation.
- Full-text search in a hardened index, opt-in per space (§9.7c).
- Producer SDK so Kafka producers store content in jvault and publish references instead of
  payloads — the structurally clean answer to sensitive data on topics (§5.6).
- BYOK / on-premises HSM via PKCS#11.
- e-Discovery export, litigation hold workflow.
- Migration tooling for existing Jira content into the vault.

---

## Sequencing rationale

Three things are built in the MVP that a schedule-driven plan would defer, and each is deferred
at real cost:

1. **The egress boundary and the canary-leak suite.** Retrofitting a leak boundary onto a
   codebase that already writes to Jira from six places is a rewrite, and the intervening period
   is one where a leak is a live possibility.
2. **The three-way conformance suite.** It is what makes "consistent behaviour across all entry
   points" a property rather than an intention. Built after the third entry point exists, it
   mostly documents the divergence instead of preventing it.
3. **The `JiraDeployment` and `RichTextCodec` abstractions.** D1 settles this: both deployments
   are in scope, so both abstractions exist from the first commit. Introducing them after a
   Cloud-only implementation would mean touching every Jira call site.
4. **The on-premises KMS adapter.** D2 makes key management a first-class MVP concern rather than
   a configuration detail. Data-key latency against Vault or an HSM is on the content read path,
   so it must be measured early — this is the one place where an on-premises key manager behaves
   materially differently from a cloud KMS at scale.
