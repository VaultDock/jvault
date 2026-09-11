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

Java 21, Maven multi-module. `mvn test` — 68 tests, all green.

| Module | Contains | State |
|---|---|---|
| `jvault-domain` | Placement policy engine, `SensitiveValue`, surrogate rendering | **Done for MVP scope** |
| `jvault-jira` | Egress guard, `JiraSafePayload`, gateway interfaces, architecture rules | **Boundary done**; transport not started |

Built first, deliberately, because these are the two things
[15. Implementation plan](docs/15-implementation-plan.md) identifies as expensive to retrofit —
and neither depends on the unanswered Q0 connectivity question.

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
- **No transport yet.** `JiraHttpClient` and `JiraWriteGateway` are interfaces with no
  implementation, which is why the boundary can be tested without a Jira instance.

### Next

Per [15. Implementation plan](docs/15-implementation-plan.md), in order: the outbox and its
per-issue dispatcher lanes with the ambiguity protocol, then `ContentStore` + `CryptoService`
against the filesystem backend and a local key manager, then the two `JiraDeployment`
implementations.
