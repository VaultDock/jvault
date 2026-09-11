# jvault — Jira integration with configurable external content placement

**Status: design agreed (see [decision log](docs/decisions.md)). Implementation started.**

jvault creates and manages Jira tickets from a web UI, a REST API and Kafka events,
while letting administrators decide, per project and issue type, which parts of a
ticket live in Jira and which live in an external, encrypted, access-controlled store.
Jira keeps a non-sensitive placeholder plus a stable link; the real content never
touches Jira, Jira's index, jvault's logs, telemetry, error responses, or Kafka
dead-letter records.

## Read in this order

| # | Document | What it settles |
|---|---|---|
| — | [**Decision log**](docs/decisions.md) | Decisions taken at review (D1–D4) and the one conflict they raise. **Read this first — it supersedes defaults stated elsewhere.** |
| 0 | [Verified Jira capabilities](docs/00-verified-capabilities.md) | What Atlassian's docs actually say, with citations. Everything else builds on this. |
| 1 | [Requirements specification](docs/01-requirements.md) | Numbered requirements with testable acceptance criteria. |
| 2 | [Jira parity scope](docs/02-jira-parity-scope.md) | What we reproduce, what we cannot, and the fallback for each gap. |
| 3 | [Architecture](docs/03-architecture.md) | Components, responsibilities, technology stack, deployment topology. |
| 4 | [Data model](docs/04-data-model.md) | Entities, schema, state machines, invariants. |
| 5 | [Content placement policy](docs/05-content-placement.md) | Policy language, precedence, surrogates, leak prevention. |
| 6 | [REST API](docs/06-rest-api.md) | Endpoints, idempotency, errors, pagination. OpenAPI outline in [docs/api/openapi.yaml](docs/api/openapi.yaml). |
| 7 | [Kafka integration](docs/07-kafka.md) | Topics, schemas, mapping DSL, retries, DLQ, processing guarantees. |
| 8 | [Storage backends](docs/08-storage.md) | The SPI, capability negotiation, versioning, integrity, retention. |
| 9 | [Encryption and key management](docs/09-encryption.md) | Envelope encryption, framing, rotation, effect on search and recovery. |
| 10 | [Authentication](docs/10-authentication.md) | App login (OIDC), Jira user OAuth, background identity, manual credentials. |
| 11 | [Content authorization](docs/11-authorization.md) | Permissions, delegation, and how Jira and vault permissions combine. |
| 12 | [Reliability and lifecycle](docs/12-reliability.md) | Ownership, sync, partial failure, reconciliation, recovery, audit. |
| 13 | [Jira Cloud vs Data Center](docs/13-cloud-vs-datacenter.md) | Side-by-side integration comparison and recommendation. |
| 14 | [Sequence diagrams](docs/14-sequence-diagrams.md) | UI / REST / Kafka creation, login, OAuth, refresh, protected access. |
| 15 | [Implementation plan](docs/15-implementation-plan.md) | MVP and subsequent releases, with exit criteria. |
| 16 | [Assumptions, tradeoffs, open questions](docs/16-open-questions.md) | What we assumed, what we traded, what we need answered. |

Examples: [policy config](docs/examples/placement-policies.yaml),
[Kafka mapping](docs/examples/kafka-mappings.yaml),
[Kafka messages](docs/examples/kafka-messages.md).

## The one-paragraph architecture

A Java 21 / Spring Boot backend and a React + TypeScript SPA, supporting Jira Cloud and Jira
Data Center from the first release, with content and keys held on customer-controlled
infrastructure. Every ticket is a
**split document**: a `TicketRecord` in PostgreSQL binds one Jira issue to a set of
`ContentPart`s, each of which is placed in Jira, in external storage, or split
between them by an admin-configured policy. A single `JiraWriteGateway` is the only
code path that may talk to Jira, and it accepts only payloads that the policy engine
has already sanitised. All Jira mutations go through a transactional outbox so that
Kafka, Jira and storage never need a distributed transaction: the system is
at-least-once end to end, with database-backed deduplication making Jira ticket
creation effectively-once. External content is encrypted client-side with per-object
data keys wrapped by an on-premises key manager (Vault Transit, or PKCS#11 for HSM custody),
stored through a pluggable `ContentStore` SPI (filesystem, CMIS, S3-compatible, Azure Blob), and reachable only through an authenticated
jvault endpoint that re-checks authorization on every request — including previews,
range requests and version history.

## Conventions used in these documents

- **VERIFIED** — stated in current Atlassian documentation, with a citation in
  [docs/00-verified-capabilities.md](docs/00-verified-capabilities.md).
- **RECOMMENDED** — our design decision. Not an Atlassian capability claim.
- **TO VERIFY** — plausible but not confirmed; must be tested against a real
  instance before it is depended upon. Each one names the test.


---

## Implementation status

Java 21, Maven multi-module. `mvn test` — 347 tests green, 5 skipped.

> **There is no user interface yet.** Everything built so far is backend. The React SPA is
> still unstarted, and its first dependency is the ADF-editor spike in
> [15. Implementation plan](docs/15-implementation-plan.md) Phase 0.

> **The 5 skipped are the PostgreSQL outbox integration tests.** They are wired into `mvn test`
> and run wherever Testcontainers can reach Docker, but they did **not execute** on the machine
> this was built on: Docker Desktop's `~/.docker/run/docker.sock` there is a redirector that
> answers docker-java's `/info` with HTTP 400, so every Testcontainers discovery strategy is
> rejected. The `docker` CLI follows the redirect; docker-java does not. Until that suite has
> actually run somewhere, treat the PostgreSQL dialect as **reviewed but not yet proven** — the
> same status the other two dialects carry, and the reason `SqlDialect` reports its verification
> state rather than leaving it to a comment.

| Module | Contains | State |
|---|---|---|
| `jvault-domain` | Placement policy engine, `SensitiveValue`, surrogate rendering | **Done for MVP scope** |
| `jvault-jira` | Egress guard, `JiraSafePayload`, deployment abstraction, HTTP client, response classifier | **Boundary, transport and classification done**; request-body mapping not started |
| `jvault-outbox` | Outbox, per-issue dispatcher lanes, backoff, rate limiting, ambiguity protocol | **Done for MVP scope**; PostgreSQL adapter not started |
| `jvault-crypto` | Envelope encryption, KMS port, self-describing object header, key rotation | **Done for MVP scope**; Vault Transit and PKCS#11 adapters not started |
| `jvault-storage` | `ContentStore` SPI, capability negotiation, filesystem backend | **Filesystem done**; CMIS and S3 not started |
| `jvault-persistence` | Outbox JDBC adapter, three SQL dialects, per-vendor migrations | **PostgreSQL written, unrun here**; SQL Server and Oracle unverified |
| `jvault-content` | Ticket creation and amendment, content service, surrogates, payload assembler | **Create, comment and attach done**; edit and delete not started |
| `jvault-ingest` | Event mapping, processing state machine, deduplication, dead-lettering | **Processing core done**; Kafka client adapter, schema registry and retry topics not started |
| `jvault-authz` | Permissions, inheritance, delegation, Jira-permission combination | **Decision logic done**; persistence adapter and decision cache not started |

Built in this order deliberately: these are the pieces
[15. Implementation plan](docs/15-implementation-plan.md) identifies as expensive to retrofit,
and none of them depends on the unanswered Q0 connectivity question.

### What is guaranteed so far, and by what

| Guarantee | Enforced by |
|---|---|
| Exactly one policy resolves for any context; ties are impossible | Power-of-two selector weights + unique-selector validation. `PolicySelectorSpecificityTest` proves all 32 selector shapes score distinctly |
| A surrogate cannot leak content | `SurrogateToken` is a closed allow-list and `SurrogateRenderer` has no expression evaluation — there is no slot for the original value |
| A request may tighten placement, never loosen it | `Placement.moreProtective`, applied in one place in `PlacementResolver` |
| Unhonourable policies never become active | `PolicySet.of` validates on construction; an invalid set is unrepresentable |
| Every Jira write has been checked | `JiraSafePayload`'s package-private constructor; `JiraWriteGateway` accepts nothing else |
| Only one place can reach Jira | ArchUnit: nothing outside `jira.gateway` may depend on `JiraHttpClient` |
| Externalised content cannot reach Jira | `ContentHashIndex` — whole-value hash, literal containment, word shingles, all after Unicode/case/whitespace normalisation |
| Failure paths carry no content | `EgressViolation` has no field capable of holding content; canary tests assert its absence in messages, violations and payload `toString` |
| Jira's per-issue write limit is never breached | Entries are grouped into `issueLane`s and each lane runs one-at-a-time; `PerIssueRateLimiter` uses sliding windows, so the boundary burst a fixed window would allow cannot happen |
| Effects within a ticket cannot overtake each other | A lane stops at its first failure or deferral and returns the rest to the queue |
| A throttle never exhausts the retry budget | Attempts are consumed at the start of a try and refunded on 429; `Retry-After` is a floor on the next attempt, never a replacement for backoff |
| A leak is never retried | An egress violation abandons the entry — retrying a leak is still a leak — and the gateway is never reached |
| An ambiguous creation is never guessed | The entry is held `IN_FLIGHT`; `AmbiguityResolver` adopts on a correlation-property match, falls back to a flagged summary heuristic, and hands indistinguishable candidates to an operator |
| A storage credential alone yields nothing | Content is encrypted before it reaches any `ContentStore`; an end-to-end test reads the raw file off disk and asserts the plaintext, the filename and the host are all absent |
| Storage keys carry no content | `ObjectKey` rejects any component that is not an opaque identifier, so a filename cannot reach a key even by accident |
| Tampering with stored bytes is detected | Tink binds frame index and the final-frame flag into each nonce; jvault binds object identity and the header hash into the AAD. Bit flips, truncation, frame reordering, cross-object splicing and object substitution all fail to authenticate |
| Nothing is ever stored unencrypted | A key-manager outage fails the write closed; a test asserts not a byte is emitted |
| KEK rotation does not rewrite content | Rewrap updates the database; the object is untouched. A test rotates a ring, shows the header's now-stale key failing, and the rewrapped key opening the very same bytes |
| One logical schema across three engines | `MigrationParityTest` compares the column sets, the unique constraint and the indexes across all three migration scripts, so a column added to one and forgotten in another is a red build rather than a production incident |
| An unproven dialect announces itself | `SqlDialect.verifiedByIntegrationTests()` is a claim about evidence, not aspiration; `DialectDetector.verificationNotice` warns at startup when an unverified dialect is selected |
| **The whole path holds together** | `EndToEndTicketCreationTest` runs policy, encryption, filesystem storage, outbox and dispatcher with every real component except the Jira transport, and asserts the canary appears in none of: the Jira field map, the outbox rows, the bytes on disk, or the payload the gateway receives |
| A replay creates nothing | The dedupe-key reservation is atomic; a second identical command returns the first ticket, stores no content and enqueues no effects |
| An externalised comment keeps its Jira shell | Skipping the Jira comment would leave holes in the conversation and silently break notifications, watchers and mentions, so the shell exists and carries the surrogate |
| An attachment reaches Jira in no form | Only a remote link, whose title is built from part type, size and media type. The filename is a `SensitiveValue` and appears in no surrogate, no storage key, no link title and nothing on disk |
| Large content never sits in memory | Ciphertext spools to a temporary file and streams into the backend; an 8 MB attachment is covered by test, and the spool is emptied afterwards |
| Possession of a link grants nothing | Authorization is decided per request from the caller's current principals; knowing a content reference changes nothing, and a decision never sticks across requests |
| Neither system can bypass the other | `INTERSECT` is the default: a Jira user without a vault grant is denied, and a vault grant without Jira access is denied. The other three modes exist but declare that they need explicit acknowledgement |
| A Jira outage is not a denial | An unavailable dependency returns `UNAVAILABLE`, not `DENY`, so a user is told the check cannot be made rather than that they have lost access. A per-space grace window may reuse a previously granted decision — never manufacture one, never for writes |
| Nobody can give away more than they hold | Enforced at grant time and covered by a generative test over random permission sets, because a user who could grant `DOWNLOAD` to their own group has just granted it to themselves |
| Revoking a delegation revokes what it produced | Grants record the authority they were made under, so revocation cascades — leaving the children is how access outlives its reason, invisibly |
| A dead-letter record cannot carry a payload | `DeadLetterRecord` has no payload field and `DeadLetterPublisher` has no overload accepting one, so the "just include the message for debugging" shortcut requires changing an interface — a conversation rather than an accident. The original goes to the encrypted quarantine and is referenced by id |
| Mapping configuration is not executable | A closed set of four value sources and a restricted path syntax, with no scripting engine. Administrator-authored configuration stored in a database cannot become a code-execution surface |
| Two kinds of duplicate are distinguished | The offset check catches redelivery after a crash or rebalance; the business-key check catches republication on a new offset. A system with only one of them either duplicates tickets on replay or redoes work on every rebalance |
| Ambiguity stays rare | `JiraOperation.isIdempotent()` decides what an unknown outcome means. A timed-out remote-link upsert or property `PUT` is merely retryable; only a create, comment or attachment is genuinely ambiguous, so the recovery protocol is reserved for the cases that need it |
| A request that never left is not ambiguous | The HTTP client distinguishes a connection that was never established from a response that never arrived, and only the latter can have taken effect |
| Deployments admit what they cannot do | `JiraDeployment.Capabilities` records section-splitting, PKCE, property search and rate limits per deployment, so configuration validation refuses a policy the target cannot honour |

### Known limits of what is built

- **Short values are not hash-matched.** Below 8 normalised characters the hash check produces
  more false positives than it prevents leaks, so short secrets are the classifier's job. The
  tradeoff is asserted in a test so it stays visible rather than becoming folklore.
- **The hash check stops a mistake, not an adversary.** Someone who paraphrases content can get
  it past the guard. It is aimed at the realistic failure — a refactor passing the wrong variable.
- **The classifier pack is deliberately small.** A noisy classifier gets disabled, and a disabled
  classifier protects nothing. Blocking is reserved for `RESTRICTED` and above; below that,
  detections annotate without blocking.
- **`SPLIT` placement is modelled but not implemented.** Section extraction arrives with the ADF
  codec.
- **`LocalKeyManagementService` is development-only** and says so in its own javadoc: its
  key-encryption keys live in the same heap as the plaintext they protect, which defeats the
  separation that makes envelope encryption worth doing. It exists so the whole content path is
  exercisable in a unit test with no external dependency — a suite that needs a running Vault to
  check a round-trip stops being run. Production is Vault Transit or PKCS#11 (D2), not started.
- **Ranged reads authenticate only the frames they touch** and cannot verify the whole-object
  plaintext digest. Any API exposing them must say so.
- **No transport and no database yet.** `JiraHttpClient`, `JiraWriteGateway`, `JiraIssueSearch`
  and `OutboxRepository` are ports with no production adapter. That is why the entire dispatch
  path — lanes, backoff, rate limiting, egress, ambiguity — is testable without a Jira instance
  or a running Postgres, and it is worth keeping that property.
- **The in-memory `OutboxRepository` reproduces two contract properties that matter**: `append`
  is idempotent on `(ticketRef, effectKey)` and `claim` never hands the same entry to two callers.
  The PostgreSQL adapter gets these from a unique constraint and `FOR UPDATE SKIP LOCKED`; a fake
  without them would let tests pass while the real system double-wrote to Jira.
- **Lanes are processed sequentially within one dispatcher pass.** They are independent and
  designed to be the unit of parallelism, but nothing runs them in parallel yet.

### Next

Per [15. Implementation plan](docs/15-implementation-plan.md), in order: the PostgreSQL adapter
for the outbox and the content metadata schema, then the S3-compatible and CMIS backends, then the
two `JiraDeployment` implementations.

**Q0 is narrower than it first looked.** Only the *Cloud* transport depends on it: under D1 both
deployments are in scope, and under every reading of Q0 the Data Center transport is needed —
readings (a) and (c) need both, reading (b) needs Data Center alone. The HTTP client, the response
classifier and the deployment abstraction are shared by both and are built. What Q0 still gates is
whether a Cloud deployment is configured at all, and whether it needs a forward proxy —
`CloudDeployment` already accepts a gateway base URL for exactly that.
