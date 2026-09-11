# Decision log

Decisions taken during design review, with their consequences. Each supersedes the default
stated in [16. Open questions](16-open-questions.md).

---

## D1 — Support Jira Cloud **and** Jira Data Center from the MVP

**Decided:** both deployments in scope from the first release, not Cloud-first.

**Consequences**

- `JiraDeployment` and `RichTextCodec` are foundational abstractions, present from the first
  commit. No Jira call site may reference a deployment-specific detail.
- Two authentication paths in the MVP: Cloud 3LO (confidential client, no PKCE) and Data Center
  incoming application links (PKCE S256). See [10. Authentication](10-authentication.md).
- Two rich-text paths: ADF for Cloud (API v3), wiki markup for DC (API v2). The ADF model is the
  internal canonical form; the wiki codec is a lossy projection and is documented as such.
- Two integration-identity mechanisms: Cloud service account + scoped token, DC PAT of a service
  user. Both in the MVP.
- **Section-level `SPLIT` is unavailable on Data Center** and refused at configuration-validation
  time (§5.4). This is now a visible feature asymmetry between deployments rather than a
  "later" item, and needs saying to users explicitly.
- Capability flags (§13.2) become load-bearing in the MVP, not release 2.
- A compatibility matrix test suite runs against both a Cloud sandbox and a DC container
  (Jira 9.12 LTS and 10.x) in CI.
- **MVP estimate moves from 10–12 weeks to roughly 14–16 weeks.**

---

## D2 — On-premises infrastructure for content storage and key management

**Decided:** content bytes and encryption keys stay on customer-controlled infrastructure. No
cloud-managed KMS, no cloud object storage as the primary route.

**Consequences**

- **Key management.** Primary: **HashiCorp Vault Transit** (self-hosted), with **PKCS#11** for an
  HSM-backed root or for deployments that require hardware custody (Thales Luna, Entrust nShield,
  Utimaco). This moves HSM support from release 3 into the MVP. Vault Transit is recommended as
  the primary integration because it gives envelope encryption, key rotation and rewrap through a
  clean API, and can itself be sealed by an HSM — getting the operational ergonomics of a managed
  KMS without leaving the datacentre. Raw PKCS#11 remains available where hardware custody is
  mandated.
- **Storage.** MVP backends become **filesystem** and **CMIS**. The **S3 adapter still ships in
  the MVP**, because S3-compatible on-premises object storage (MinIO, Ceph RGW, NetApp StorageGRID,
  Dell ECS) is the most likely production target — the adapter is the same code, pointed at an
  internal endpoint. Azure Blob moves to a later release unless Azure Stack is in play.
- **Filesystem backend is now production-grade, not a development convenience.** It needs a
  shared POSIX-consistent volume across nodes, and the deployment guide must be explicit about
  NFS locking semantics (§8.3). This is a real operational risk and deserves a named owner.
- **Backup and recovery** is now the customer's responsibility end to end: no cross-region
  replication for free, no managed key replication. The escrow procedure in §9.8 becomes
  mandatory rather than conditional, and the quarterly restore drill is a release gate.
- `replicateTo` route replication (§8.2) becomes more important, since there is no provider-level
  durability guarantee underneath.
- Provider-side encryption as a second layer (§9.2) is only available where the on-prem store
  offers it (MinIO SSE-KMS does; a plain filesystem does not). Application-level encryption is
  therefore the *only* confidentiality layer on the filesystem route, which raises the stakes on
  the Phase 0 encryption spike.

---

## D3 — `INTERSECT` is the default content authorization mode

**Decided:** a user needs both a jvault grant **and** live Jira access to the issue.
`degradedGraceSeconds` stays at `0` by default.

**Consequences**

- Every content viewer must complete the Jira OAuth connection. The `409 jira-not-connected`
  response with a connect link (§6.1) is on the critical path for first-time users and needs to be
  a good experience, not an error page.
- A Jira outage denies content access with `503 authorization-dependency-unavailable`. Spaces
  that cannot tolerate this — incident response, most likely — set `degradedGraceSeconds`
  explicitly per space, with the audit trail that comes with it (§11.6).
- `VAULT_ONLY` remains available per space and requires an admin confirmation plus an audit event.
- Live Jira permission checks are on the read path, so the 60-second decision cache and its hit
  rate become a monitored SLI.

---

## D4 — Service-account integration identity; no Forge/Connect app

**Decided:** Atlassian service account + scoped API token on Cloud, PAT of a dedicated service
user on Data Center. jvault is **not** distributed as an Atlassian app.

**Consequences**

- No Atlassian app distribution, marketplace listing, or site-installation approval. Standard
  deployment model.
- **Token rotation is manual and recurring.** Cloud service-account tokens expire within 1–365
  days and scopes are immutable after creation, so rotation means creating a new token, not
  editing one (§0.10). DC PAT expiry is optional but jvault requires one anyway. Expiry alerting
  at 30/14/7 days and refusal to start on an expired credential are MVP features, not niceties.
- **Entity properties are not JQL-searchable** (§0.9). The ambiguity protocol keeps its bounded
  JQL scan over the creation window plus a per-candidate property fetch (§12.4.2) rather than
  collapsing to an exact-match query. This is the main functional cost of D4 and it is acceptable:
  the scan is cheap, bounded, and fails to a human rather than a guess.
- Cloud is limited to 5 free service accounts per organization; more require Atlassian Guard.
  One per deployment is sufficient.
- Attribution: tickets show the service account as creator, with the originating actor recorded
  separately (§7.6). Unchanged from the original design.
- The Forge/Connect option stays documented in §10.5 as a future alternative. Nothing in this
  design precludes it.

---

## D5 — Support PostgreSQL, Microsoft SQL Server and Oracle

**Decided:** the metadata store must run on all three. PostgreSQL is no longer assumed.

**Consequences — this one reaches further than it looks.** The design used PostgreSQL features in
several load-bearing places. Each now needs a portable answer or a per-vendor one.

| Where | PostgreSQL assumption | Portable answer |
|---|---|---|
| **Outbox claim** | `SELECT … FOR UPDATE SKIP LOCKED` | Per-vendor SQL, not portable. Oracle has `FOR UPDATE SKIP LOCKED`; SQL Server uses `WITH (UPDLOCK, READPAST)` over a `TOP (n) … ORDER BY` CTE. All three can dequeue without two workers taking one row, but no single statement works on all three. **This is the sharpest vendor difference in the system** and it is isolated in one method of one class. |
| **Idempotent append** | `INSERT … ON CONFLICT DO NOTHING` | Rely on the unique constraint and catch `SQLIntegrityConstraintViolationException`, which JDBC standardises across vendors. Portable, and arguably clearer about intent. |
| **`payloadRef`, policy and mapping documents** | `jsonb` | Plain text columns holding JSON, parsed in the application. **We give up SQL-side JSON querying**, which the outbox never needed but an admin reporting screen might. Worth knowing before someone writes a query against it. |
| **Single-flight token refresh** | `pg_advisory_xact_lock` | A `resource_lock` table with `SELECT … FOR UPDATE` on a named row. Works on all three, costs one round trip, and is easier to reason about than an advisory-lock namespace. |
| **UUID keys** | `uuid` column | `CHAR(36)` holding the canonical string. Oracle has no native UUID and SQL Server's `uniqueidentifier` sorts differently from everyone else's. Costs 20 bytes a row against total portability and no surprises in index ordering. |
| **Booleans** | `boolean` | `SMALLINT` 0/1. Oracle had no SQL `BOOLEAN` before 23c. |
| **Timestamps** | `timestamptz` | Store UTC in a plain timestamp and convert at the boundary. SQL Server's `datetime2` has no time zone; relying on server time zones across three vendors is a bug waiting to happen. |
| **`RETURNING`** | Multi-row `RETURNING` | SQL Server uses `OUTPUT`; Oracle's `RETURNING INTO` does not return a multi-row result set to JDBC. The claim implementation differs per vendor accordingly. |
| **Audit partitioning** | Declarative range partitioning | All three partition, all three differently. Left to the deployment's DBA with a documented recommendation rather than baked into a migration. |
| **Migrations** | One Flyway script set | Per-vendor migration directories. One logical schema, three physical scripts, with a test asserting they stay in step. |

**Shape of the answer.** A `SqlDialect` abstraction with three implementations, over **Spring
JDBC**. Everything genuinely portable stays in shared code; the handful of statements that cannot
be are named, isolated, and individually tested.

**Why not jOOQ.** jOOQ is the better multi-dialect SQL library on the merits, and it does render
`FOR UPDATE SKIP LOCKED` per dialect — including SQL Server's `UPDLOCK, READPAST` emulation. But
its Open Source Edition is Apache-2.0 *only for open-source databases*; Oracle and SQL Server
require a commercial licence
([jooq.org/legal/licensing](https://www.jooq.org/legal/licensing)), and this decision puts both in
scope. Adopting it would mean buying jOOQ Professional or Enterprise. That is a reasonable thing
to do — it is a good product and the licence is modest next to Oracle's — but it is a purchasing
decision, not an engineering one, so it is recorded here rather than made silently.

Spring JDBC carries the same licence as the rest of the stack, costs nothing per engine, and is
already what the architecture commits to — so it introduces no second data-access idiom. It also
does **not** attempt to generate the dequeue SQL, which is a feature here rather than a
limitation: an ORM or query builder papering over the differences would hide exactly the
skip-locked semantics correctness depends on. Its exception translation is a genuine saving,
removing the per-dialect duplicate-key detection we would otherwise hand-write three times.

**If the licence is bought later**, the migration is contained: `SqlDialect` has one method whose
implementations would change, and the row mapper and schema stay as they are.

**Verification burden, stated plainly.** Three databases means three times the integration
testing, and the two commercial engines need licensed images that cannot run in every CI
environment. Any dialect not exercised against a real instance is **unverified code**, and this
repository will say so per dialect rather than implying all three are equally proven.

---

## Conflict raised by D1 + D2 — needs an answer before Phase 0 ends

**Jira Cloud requires outbound internet access. A genuinely air-gapped network cannot reach
`auth.atlassian.com` or `api.atlassian.com`, so "Jira Cloud in the MVP" and "air-gapped" cannot
both be literally true.**

Three readings, and they lead to different builds:

| Reading | What it means | Impact |
|---|---|---|
| **(a) On-premises data, controlled egress** — *assumed default* | Content and keys never leave the datacentre. The application has allow-listed outbound HTTPS to Atlassian through a forward proxy. | Works as designed. Adds: explicit proxy configuration, an egress allow-list (`auth.atlassian.com`, `api.atlassian.com`), and proxy-aware HTTP clients throughout. Modest work. |
| **(b) Truly air-gapped** | No outbound connectivity at all. | **Jira Cloud is impossible.** D1 collapses to Data Center only, which removes ADF, removes the Cloud auth path, and removes the Cloud service account — and makes the MVP *smaller*, not larger. |
| **(c) Two separate installations** | One internet-connected installation integrating Jira Cloud, one air-gapped installation integrating Jira DC, sharing a codebase. | Both deployment implementations needed (as D1 says), plus a build that runs with no outbound egress at all and an offline-capable configuration path. Largest scope. |

> **Evidence for (a), 2026-09-11.** A live Jira Cloud site was reached successfully from a
> development machine using an Atlassian API token, at the user's direction. That does not prove
> the *production* deployment has the same egress, but it settles that the Cloud path is wanted
> and reachable in at least one environment. Reading (b), a true air gap, now looks unlikely.
> **Still to confirm:** whether the production on-premises deployment has allow-listed egress to
> `api.atlassian.com`, and whether it goes through a forward proxy — `CloudDeployment` already
> takes a gateway base URL for that case.

**Proceeding on (a)** until told otherwise, because it is the reading in which both stated
decisions hold. If the answer is (b), tell us — it removes roughly four weeks of work rather than
adding any.

Related, under every reading: an on-premises deployment integrating Jira **Cloud** means content
stays on-prem while *ticket metadata* (summaries, surrogates, custom field values placed in Jira)
is by definition in Atlassian's cloud. That is the intended design — it is what content placement
policy controls — but it is worth stating plainly to whoever approved the on-premises requirement,
because "on-premises" sometimes carries an expectation that nothing at all leaves.
