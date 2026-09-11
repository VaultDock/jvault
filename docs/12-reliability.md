# 12. Reliability, synchronization and lifecycle

## 12.1 Ownership

Every field has exactly one owner. Where two systems could write, one wins by rule, and the rule
is written down.

| Data | Owner | Rule |
|---|---|---|
| Issue existence, key, id | **Jira** | jvault never creates an issue outside the outbox, never deletes one |
| Workflow status, resolution, sprint, rank | **Jira** | jvault reads only |
| Jira-placed field values | **Jira** | jvault writes on user action; Jira-side edits win on conflict |
| Surrogate text in Jira | **jvault** | jvault rewrites it if a Jira-side edit changes it; audited |
| Remote links with `globalId` prefix `jvault:` | **jvault** | Recreated by the reconciler if deleted in Jira |
| `jvault.*` issue properties | **jvault** | Overwritten on every sync |
| External content bytes and versions | **jvault** | Jira has no copy |
| Content ACLs, policies, audit | **jvault** | Not represented in Jira at all |
| Comment bodies | Depends on placement | `JIRA` → Jira owns; `EXTERNAL` → jvault owns the body, Jira owns the comment shell |
| Attachment bytes | Depends on placement | Same split |

The "jvault rewrites the surrogate if someone edits it in Jira" rule deserves a note: users *will*
edit placeholder text, usually by accident when editing the rest of a description. Silently
restoring it is right — the placeholder is jvault's, and a broken placeholder means a broken
link — but it must be audited and visible in the ticket's activity so nobody is confused about
why their edit vanished.

## 12.2 Writes: the outbox

Every Jira mutation is a `jira_outbox` row written in the same transaction as the domain change
(§4.3). The dispatcher:

1. Claims rows with `FOR UPDATE SKIP LOCKED`, ordered by `next_attempt_at`.
2. Groups by `issue_lane` and executes each lane **single-threaded**, which is what keeps us under
   Jira's per-issue write limit of 20 per 2 seconds (§0.3) no matter how many nodes are running.
3. Takes a token from a global bucket sized below Jira's burst and hourly point budgets.
4. Re-reads the actual values from the database, runs `EgressGuard`, calls Jira.
5. On success marks `SUCCEEDED`; on failure classifies the error and either reschedules with
   backoff-plus-jitter or marks `ABANDONED` and raises an operator alert.

Backoff follows Atlassian's own guidance (§0.3): 2 s base, doubling to ~30 s, jitter 0.7–1.3,
`Retry-After` respected as a floor. 429s do not consume the error-attempt budget.

Replay safety comes from `effect_key`: remote links upsert by `globalId`, property writes are
`PUT`, and comment/attachment creations record their effect key on success so a duplicate
delivery is skipped rather than duplicated.

## 12.3 The nine failure checkpoints

Creation crosses nine points where a failure leaves distinguishable state. Each has a defined
resolution, and a fault-injection test exists for each (FR-REL-3 AC1).

| # | Failure point | State left behind | Resolution |
|---|---|---|---|
| 1 | Validation fails | Nothing written | Error to caller; no side effects |
| 2 | Dedupe key insert conflicts | Existing ticket found | Return the existing ticket; no duplicate |
| 3 | Content encrypted but storage write fails | `content_part` = `PENDING_UPLOAD` | Retried by the worker; client may re-upload with the same handle; orphan GC removes partial objects |
| 4 | Storage write succeeds, metadata commit fails | Object with no metadata row | Orphan GC deletes objects with no row older than 24 h |
| 5 | **Content stored, Jira create fails** | Ticket `JIRA_PENDING`, content intact | Outbox retries; after exhaustion → operator queue. **Content is never lost, and the user's work is visible in jvault.** See §12.4.1 |
| 6 | **Jira create succeeds, response lost** | Ticket `AMBIGUOUS` | Ambiguity protocol, §12.4.2 |
| 7 | Jira created, link/property write fails | Issue exists, placeholder without a working link | Outbox retries; idempotent by `globalId`. Until it succeeds the surrogate renders a "link pending" note rather than a dead URL |
| 8 | Everything succeeds, response to the client lost | Complete | Idempotency-Key replay returns the original response |
| 9 | Kafka offset commit fails after completion | Complete, offset uncommitted | Redelivery finds a terminal state row; acknowledged, no work repeated |

## 12.4 The two cases named in the brief

### 12.4.1 Content stored, Jira creation fails

This is the *benign* direction, which is why step ordering puts content first (§3.5).

- The ticket sits in `JIRA_PENDING`. Content is stored, encrypted, ACL'd and visible in jvault.
- The outbox retries with backoff. Transient Jira problems resolve without anyone noticing.
- If retries are exhausted, the ticket enters an **operator queue** with the error class and the
  field errors, and the originating user (or the Kafka status API) sees `JIRA_PENDING` with a clear
  explanation.
- An operator can correct the cause — a required field, a permission, a project misconfiguration —
  and requeue. The content is not re-uploaded and the `contentRef` does not change, so any link
  already shared stays valid.
- Nothing is orphaned: content is referenced by a real ticket record that a human can see.

### 12.4.2 Jira creation succeeds but the response is lost — the ambiguity protocol

The genuinely hard case. Jira offers no idempotency key on issue creation (§0.13), so a timed-out
`POST /issue` leaves us unable to tell whether an issue exists.

Naïve retry risks duplicate tickets; naïve abandonment risks an orphaned issue with a broken
placeholder. Neither is acceptable, so:

**Before the call**, jvault records an ambiguity window: `attempt_started_at`, the correlation id,
and the target project. **Immediately after** a successful create, it writes the
`jvault.origin` issue property containing `{ticketRef, correlationId}` — a separate call, because
create-time properties are unverified (§0.9).

**On an unknown outcome** the ticket moves to `AMBIGUOUS` and the reconciler runs:

```
1. Search Jira:
     project = <target>
     AND created >= <attempt_started_at - 2m>
     AND created <= <now + 2m>
     AND reporter = <acting identity>
   (bounded, cheap, and uses only ordinary JQL — no entity-property indexing required, because
    an OAuth 2.0 integration cannot make properties JQL-searchable; see §0.9.)

2. For each candidate, GET /issue/{key}/properties/jvault.origin
   → a matching correlationId means this is our issue: adopt it, record the id and key,
     continue from step 10 of the creation path.

3. If the property is absent on every candidate (create succeeded but the property write did
   not), fall back to a field-level match: summary equality plus creation inside the window plus
   the acting identity. A single match is adopted, but flagged MATCHED_HEURISTICALLY and shown
   to an operator for confirmation.

4. No candidate after N sweeps (default 3, 60 s apart) → the create did not take effect.
   Return to JIRA_PENDING and retry the create.

5. Multiple ambiguous candidates → stop. Move to FAILED_NEEDS_OPERATOR, alert, and present both
   candidates. Guessing here is how duplicate incident tickets get merged badly at 3 a.m.
```

The protocol resolves the overwhelming majority of cases automatically in under two minutes, and
it fails to a human rather than to a guess. The residual risk — a duplicate created when Jira
accepted a write it never reported and the property write also failed and the summary was not
unique — is bounded, detectable in the duplicate-detection sweep (§12.6), and documented.

> If jvault is ever delivered as a Forge or Connect app (§10.5), entity properties become
> JQL-indexable and step 1 collapses to a single exact-match query. That is a meaningful argument
> for the app-distribution model, and it is why the option is kept open in the roadmap.

## 12.5 Reading Jira: webhooks plus reconciliation

Webhooks are an optimisation. Reconciliation is the guarantee.

**Webhooks.** Dynamic webhooks registered by the **integration identity** — not per user, because
the OAuth quota is 5 per user per tenant (§0.4). Events: `jira:issue_updated`,
`jira:issue_deleted`, `comment_*`, `attachment_*`. The reconciler re-registers before the 30-day
expiry (at day 21, so three failures still leave headroom).

Handling:
- Verify the request origin and shared secret before parsing.
- Deduplicate on the retry-stable webhook identifier (§0.4).
- Treat payloads as untrusted *hints*: never apply a payload's field values directly. Enqueue a
  refresh and re-read the issue from the API. A webhook body is an unauthenticated-in-practice
  statement about state; the API is the state.
- Ignore self-caused events by correlating against recently succeeded outbox effects, so jvault's
  own writes do not trigger a reconciliation loop.

**Reconciliation sweep.** Every 15 minutes, JQL `project IN (…) AND updated >= <watermark - 5m>`,
paged, comparing Jira state to jvault's. This catches: undelivered webhooks, the 25 MB payload
drop, project-deletion cascades that emit no `issue_deleted` (§0.4), and everything that happened
while jvault was down. The watermark overlaps deliberately.

**Deep sweep.** Nightly, over tickets not verified in 24 h: confirm the issue still exists, the
`jvault:` remote links are present, and the surrogates are intact. Repairs are queued as outbox
rows.

## 12.6 Lifecycle events

| Event | Response |
|---|---|
| Issue deleted in Jira | Ticket → `JIRA_ORPHANED`; content retained under the retention policy (default 90 days), then archived. Never an immediate purge |
| Issue moved to another project | Space membership re-evaluated; if the new project's policy or ACL differs, the ticket is flagged for admin review rather than silently re-permissioned |
| Comment deleted in Jira | External body → `SOFT_DELETED` under retention; the `contentRef` survives so any link reports a clear state |
| Attachment deleted in Jira | Only meaningful for Jira-placed attachments; the metadata row is marked deleted |
| Vault part deleted in jvault | Remote link removed, surrogate rewritten to a tombstone, content soft-deleted |
| Content archived | Link resolves and reports `ARCHIVED`; retrieval may be slower (cold storage tier) |
| Content purged | Link returns `410 Gone` with a tombstone; the Jira surrogate is rewritten to say so |
| Project deleted in Jira | Detected by sweep (no webhook, §0.4). Space → read-only, content retained, admin alerted |

**Duplicate-detection sweep.** Hourly, looking for two tickets in one space with the same dedupe
key, or two Jira issues carrying the same `jvault.origin.correlationId`. Findings go to an
operator queue with a merge action — never an automatic merge.

**Orphan cleanup.** Objects with no `content_version` row older than 24 h are deleted. Metadata
rows whose object is `MISSING` raise an alert rather than being deleted, because losing the record
of a loss is worse than the loss.

## 12.7 Concurrency

**Within jvault.** Optimistic locking (`version` column) on ticket records and content parts;
`412` on a conflicting `If-Match`. For the UI this surfaces as "this ticket changed while you were
editing", with a diff.

**Against Jira.** Jira offers no per-issue ETag or conditional update. jvault therefore:
- serialises its own writes per issue through the outbox lane (which also serves the rate limit);
- performs read-modify-write with a check on the issue's `updated` timestamp, reporting
  `409 stale-jira-state` when it changed underneath;
- sends **field-level** updates (`update` operations rather than whole-`fields` replacement), so
  two concurrent editors touching different fields do not clobber each other;
- resolves genuine conflicts by the ownership table (§12.1): Jira wins on Jira-owned fields,
  jvault wins on surrogates, and the event is audited.

## 12.8 Rate limits

A `JiraRateLimiter` per deployment, composed of: a global token bucket below the documented burst
limits, a point-cost estimator against the hourly quota, and the per-issue lane serialisation.
`X-RateLimit-NearLimit` triggers pre-emptive slowing; `RateLimit-Reason` is recorded as a metric
label so it is visible *which* limit is binding — the three behave differently and conflating them
makes tuning guesswork.

Non-urgent work (reconciliation sweeps, webhook renewal, deep verification) runs at a lower
priority and yields budget to user-facing and Kafka work.

## 12.9 Observability

**Metrics.** Outbox depth and age by state; Jira call latency, error rate and throttle rate by
operation; Kafka lag, retries and DLQ rate by mapping; content read/write throughput; encryption
and KMS latency; authorization decisions by outcome and reason; `AMBIGUOUS` ticket count.

**Traces.** One trace from HTTP or Kafka ingress through policy, storage, KMS, outbox and Jira.
Span attributes pass through an allow-list (§5.6) — content never reaches the tracing backend.

**Logs.** Structured JSON, masked, identifier-only. Correlation id and trace id on every line.

**Alerts that matter** (the short list — long alert lists get ignored): integration-identity
credential expiring; any `AMBIGUOUS` ticket older than 10 minutes; outbox rows older than 15
minutes; DLQ rate above baseline; KMS error rate above zero; egress-guard rejections above zero
(this one is a *security* alert, not a reliability one); authorization `DEGRADED_ALLOW` in use;
webhook registration within 7 days of expiry.

**Audit.** Hash-chained, append-only, partitioned by month, exported to the SIEM (§4.9). Denials
are audited as prominently as successes: a rising denial rate against one `contentRef` is what a
shared link looks like from the inside.
