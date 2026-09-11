# 16. Assumptions, tradeoffs and open questions

## 16.1 Assumptions made (change any of these and tell us)

| # | Assumption | If wrong |
|---|---|---|
| A1 | jvault is a single-organisation internal system, not multi-tenant SaaS | Multi-tenancy touches the data model, key rings, storage key layout and every authorization query. It must be designed in from the start, not added |
| A0 | **The deployment has allow-listed outbound HTTPS to Atlassian through a forward proxy** (reading (a) of Q0) | If truly air-gapped, Jira Cloud is out of scope entirely and the MVP shrinks by roughly four weeks |
| A2 | Users of jvault generally have Jira accounts and can complete the Jira OAuth connection | The `INTERSECT` default becomes unworkable and `VAULT_ONLY` becomes the sensible default (§11.3) |
| A3 | An administrator can create a Jira service account (Cloud) or service user (DC) | Falls back to a bot account with 3LO and periodic re-consent, which is operationally worse (§10.5) |
| A4 | External content volume is in the low terabytes with objects usually under 100 MB | Changes storage tiering, preview strategy and the integrity-sweep budget, not the architecture |
| A5 | ~~A managed KMS is available~~ **Superseded by [D2](decisions.md):** an on-premises key manager (Vault Transit, or PKCS#11/HSM) is available and is in the MVP | If neither exists, key management becomes the project's critical path |
| A6 | PostgreSQL is acceptable as the single metadata store | Another store is possible but the outbox, advisory locks and `SKIP LOCKED` patterns would need replacing |
| A7 | Jira projects in scope do not rely heavily on ScriptRunner Behaviours | More projects need `delegated` parity mode, reducing the UI's value (§2.6) |
| A8 | Content is predominantly text and documents, not video or very large binaries | Preview and streaming strategy would change |
| A9 | English-language UI at first, i18n-ready | Adds effort but no structural change |

## 16.2 Tradeoffs taken, with the reasoning

**Content-first ordering (§3.5).** Content is stored before the Jira issue exists. This can leave
orphaned content when Jira creation fails permanently — cleaned up by the reconciler. The
alternative leaves a *visible broken placeholder in Jira*, which users see and cannot fix. Invisible
recoverable garbage beats visible broken state.

**Effectively-once, not exactly-once (§7.1).** Achieved with database constraints rather than
transactional coordination. Simpler, faster, and honest about what is actually guaranteed. The
residual duplicate risk is bounded, detected and documented.

**Application-level encryption (§9.2).** Costs search, preview complexity and a hard dependency on
KMS availability. Buys protection against the threat that actually motivates the project — content
reachable through a storage credential. Provider-side encryption alone does not address it.

**Intersection authorization by default (§11.3).** Requires every content viewer to hold Jira
access, which will occasionally be inconvenient. It is the only mode where neither system can be
used to bypass the other.

**No explicit denies in the ACL (§11.2).** Less expressive. But deny rules need an evaluation
order, and evaluation order makes "why can this person see this?" unanswerable — which is the
question every access review asks.

**jvault-implemented versioning across all backends (§8.2).** Ignores native capabilities we pay
for. Buys identical behaviour for version history, retention, legal hold and audit regardless of
where a policy points.

**Refusing `SPLIT` on Data Center (§5.4).** A capability gap between deployments. The alternative
is marker fences in wiki markup that break when someone edits the description in Jira — and a
broken fence means sensitive text committed to Jira. Failing unsafely is not an option here.

**No derived summaries from content by default (§5.3).** Jira ticket titles become
uninformative for externalised summaries, which users will dislike. Any mechanism that derives a
Jira-visible title from text deemed too sensitive for Jira is in tension with itself.

**~~Building Cloud first~~ — superseded by [D1](decisions.md).** Both deployments ship in the MVP.
The tradeoff taken instead: a larger MVP (14–16 weeks rather than 10–12) in exchange for never
retrofitting a deployment abstraction, and a visible feature asymmetry — section-level `SPLIT`
works on Cloud and is refused on Data Center — that must be communicated to users rather than
papered over.

**On-premises keys and storage ([D2](decisions.md)).** Gives up managed durability, managed key
replication, and provider-side encryption on the filesystem route. Buys the thing that motivated
the requirement: content and keys never leave customer control. The cost lands mostly on
operations — backup, restore drills, key escrow and shared-volume semantics all become the
customer's to get right, and the design says so rather than assuming a platform will cover it.

## 16.3 Known risks

| Risk | Impact | Mitigation |
|---|---|---|
| ADF editor fidelity is worse than expected | UI parity complaints; round-trip data loss | Phase 0 spike; round-trip corpus test as a merge gate |
| Jira rate limits bind harder than modelled under Kafka bursts | Ingestion lag | Per-issue lanes, global budget, priority classes; measured in the soak test |
| Atlassian deprecates or changes createmeta again | Form rendering breaks | Metadata access isolated behind one service; deprecation monitoring in the runbook |
| Cloud PKCE never arrives | `state` carries the full CSRF burden | Confidential client, hardened state, documented as a known gap (§10.3) |
| Service-account token rotation is manual and gets forgotten | Ingestion stops | Alerts at 30/14/7 days; refuse to start on an expired credential rather than dead-lettering everything |
| Users treat jvault as a document management system | Scope creep | Explicit non-goals (§1.13) |
| KMS key deleted | **Total, irreversible content loss** | Two-approver deletion, maximum waiting period, replica keys or escrow, key-state alarms (§9.8) |
| Behaviours-heavy projects force `delegated` mode widely | The UI's value drops sharply | Parity report and mode per project; measure adoption early |

## 16.4 Questions that materially affect the design

**Q1–Q4 are answered.** See the [decision log](decisions.md) for the decisions and their
consequences:

| | Question | Answer | Recorded as |
|---|---|---|---|
| Q1 | Jira deployment target | **Both Cloud and Data Center, from the MVP** | [D1](decisions.md) |
| Q2 | Storage and key platform | **On-premises** | [D2](decisions.md) |
| Q3 | Authorization default | **`INTERSECT`** | [D3](decisions.md) |
| Q4 | Forge/Connect app identity | **No — service account / PAT** | [D4](decisions.md) |

### Q0 (new, blocking) — what does "on-premises" mean for outbound connectivity?

D1 and D2 together create a conflict: **Jira Cloud requires outbound access to
`auth.atlassian.com` and `api.atlassian.com`, which a genuinely air-gapped network does not have.**
The three readings and their consequences are set out in full in the
[decision log](decisions.md#conflict-raised-by-d1--d2--needs-an-answer-before-phase-0-ends).

We are proceeding on reading **(a)** — on-premises data with allow-listed outbound HTTPS through a
forward proxy — because it is the only reading in which both decisions hold. If the answer is
**(b)**, truly air-gapped, then Jira Cloud is out, D1 collapses to Data Center only, and the MVP
gets roughly **four weeks shorter**. This needs answering before Phase 0 ends.

A related point worth putting in front of whoever approved the on-premises requirement: with Jira
Cloud in scope, content and keys stay on-premises but *ticket metadata placed in Jira* — summaries,
surrogates, custom field values — is by definition in Atlassian's cloud. That is exactly what
content placement policy governs, and it is the intended design. But "on-premises" sometimes
carries an expectation that nothing at all leaves, and the gap between those two readings is worth
closing explicitly rather than at an audit.

### Remaining questions (proceeding on the stated defaults)

**Q5 — Section-level splitting in the MVP, or field-level only?**
*Default:* field-level only in the MVP; `SPLIT` in release 2. Section splitting roughly doubles
the placement engine's complexity and its failure modes.

**Q6 — Compliance regime.** GDPR, HIPAA, PCI-DSS, ITAR, or an internal policy?
*Why it matters:* drives audit retention, right-to-erasure handling, and whether WORM storage is
mandatory. D2 has already settled the residency and key-custody half of this — content and keys
stay on customer infrastructure — which is the answer most regimes would push toward anyway. If
the driver is **ITAR or an equivalent export-control regime**, that would point strongly at
reading (b) of Q0 and would rule out Jira Cloud; worth confirming.
*Default:* GDPR-shaped — 7-year audit retention, erasure supported through purge, WORM available
via CMIS records-managed retention where required.

**Q7 — Expected scale.** Kafka messages per day, concurrent UI users, total content volume,
typical and maximum attachment size?
*Default:* 10k messages/day, 200 concurrent users, 5 TB, 10 MB typical / 1 GB maximum. The
architecture holds well past this; the numbers affect instance sizing and the integrity-sweep
budget, not the design. Under D2 they also size the on-premises storage and the key manager —
`Decrypt` throughput against a Vault cluster or HSM is a real ceiling in a way a cloud KMS is
not, which is why it is a Phase 0 spike.

**Q9 (new) — which on-premises storage is actually available?** A shared POSIX filesystem, an
S3-compatible object store (MinIO, Ceph, StorageGRID, ECS), or an existing CMIS records system?
*Why it matters:* this is now a production decision rather than a preference. The S3-compatible
route is materially safer than a shared filesystem (§8.3) and needs no new code.
*Default:* S3-compatible object storage as the primary route, filesystem retained for single-node
and development, CMIS where a records system already owns retention.

**Q10 (new) — Vault Transit or PKCS#11/HSM?**
*Default:* Vault Transit as the primary key manager, sealed by an HSM where hardware custody is
required. PKCS#11 direct is implemented but is the fallback, because Vault's rewrap API makes KEK
rotation (§9.5) an operation rather than a project.

**Q8 — Is "Sign in with Atlassian" a requirement?**
*Default:* no — enterprise OIDC only, for the reasons in §10.7. If it is wanted, it should be
federated through the enterprise IdP rather than trusted directly.

## 16.5 What we would build first if you said "start tomorrow"

The Phase 0 spikes, then the MVP skeleton in this order, because each one is load-bearing for what
follows:

1. `JiraSafePayload` / `EgressGuard` / `JiraWriteGateway`, with the ArchUnit rules and the canary
   suite — the leak boundary, before anything writes to Jira.
2. The outbox and its dispatcher with per-issue lanes and the ambiguity protocol.
3. `ContentStore` + `CryptoService` with the filesystem backend and a local KMS, so the whole
   content path is exercisable without cloud dependencies.
4. The placement policy engine as a pure function, with its table-driven precedence tests.
5. OIDC login and the Jira connect flow.
6. The createmeta-driven form, which is where the frontend risk sits.

Items 1, 2 and 4 are the ones that are painful to retrofit. Everything else can be added
incrementally without disturbing them.
