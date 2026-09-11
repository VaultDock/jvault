# 5. Configurable content placement

## 5.1 The model

Every ticket is a **split document**. Each addressable part carries a placement:

| Placement | Jira holds | jvault holds |
|---|---|---|
| `JIRA` | the real value | nothing but a reference row |
| `EXTERNAL` | a surrogate plus a link | the real value, encrypted |
| `SPLIT` | the real value with sensitive sections replaced by markers and links | the extracted sections, encrypted |

Placement is a *policy* decision, not a user decision. Users may only override when the
resolved policy sets `allowOverride: true`, and even then only in the more-protective
direction: `JIRA → EXTERNAL` is always allowed if overrides are enabled, `EXTERNAL → JIRA`
never is. Loosening protection is an administrator action that leaves an audit trail.

## 5.2 Policy resolution

A policy's **selector** is a tuple `(deployment, project, issueType, partType, field)`; any
component may be `null` meaning "any". Specificity is a fixed bit weight, evaluated
most-specific-first:

| Component | Weight |
|---|---|
| `field` | 16 |
| `partType` | 8 |
| `issueType` | 4 |
| `project` | 2 |
| `deployment` | 1 |

The resolver selects the enabled policy with the highest specificity that matches. Ties are
impossible: the unique constraint on the selector tuple in
[4.5](04-data-model.md) makes two policies with the same selector unrepresentable, and two
policies with *different* selectors always have different weights unless they differ only in
which components are null — in which case the weights differ by construction.

If no policy matches, the **global default** applies. The shipped global default is:

```yaml
placement: JIRA          # nothing is externalised unless an administrator says so
classification: INTERNAL
```

We deliberately do **not** default to `EXTERNAL`. A system that silently moves content out of
Jira surprises people, and surprise is how link rot and shadow processes start. Externalisation
is opt-in, per project, by an administrator who knows why.

Resolution is a pure function `resolve(selectorContext, policySet) -> PlacementPlan`, covered by
a table-driven test enumerating every precedence combination (FR-CP-2).

## 5.3 Policy document

```yaml
id: sec-incident-description
selector:
  deployment: jira-cloud-prod
  project: SEC
  issueType: Incident
  partType: DESCRIPTION
placement: EXTERNAL
classification: RESTRICTED
storageRoute: obj-dc1-restricted
encryption:
  required: true
  keyRing: sec-restricted
surrogate:
  kind: PLACEHOLDER
  template: >-
    Incident details are stored in jvault and are not visible in Jira.
    Classification: {{classification}}. Open: {{link}}
linkPlacement: [DESCRIPTION_PLACEHOLDER, REMOTE_LINK]
allowOverride: false
```

### Surrogate templates

Only these tokens may appear (FR-CP-3):

`{{link}}`, `{{ticketRef}}`, `{{contentRef}}`, `{{classification}}`, `{{partType}}`,
`{{issueType}}`, `{{project}}`, `{{createdAt}}`, `{{actorDisplayName}}`, `{{sizeHuman}}`,
`{{mediaType}}`, `{{versionNo}}`.

Notice what is absent: there is no token that interpolates the original value. The template
engine has no general expression evaluation — it is a fixed substitution over an allow-list, so
a mis-authored template cannot leak content.

The one exception is `kind: DERIVED_SUMMARY`, which produces a summary *from* the content. It
is available only when:

1. the policy explicitly enables it,
2. a configured classifier passes the derived text, and
3. the derivation function is on the allow-list (`firstNWords`, `categoryLabel`,
   `staticFromFieldValue` over an enumerated field).

Free-form or model-generated summarisation is **not** offered. It cannot be shown safe, and the
whole point of externalising a field is that its text must not appear in Jira.

### Summary is special

Jira always requires `summary` and caps it at 255 characters. When `summary` is `EXTERNAL`, the
surrogate must still be a usable ticket title for people working in Jira. The recommended
default:

```
[{{classification}}] {{issueType}} {{ticketRefShort}} — details in jvault
```

This is deliberately uninformative. If a project needs informative titles, the correct answer is
to keep `summary` in Jira and externalise the body — not to derive a title from sensitive text.

## 5.4 Section-level placement (`SPLIT`)

Release 2. Feasible only where the field has a structured representation.

**Jira Cloud (ADF).** A description is an ADF tree (§0.8). A section is an addressable subtree.
Two selector strategies:

```yaml
sectionRule:
  strategy: MARKED_NODE        # authored in the jvault editor
  nodeType: panel
  panelType: warning
  markerAttr: jvault.sensitive
```

```yaml
sectionRule:
  strategy: HEADING_SCOPE      # everything under a heading whose text matches
  headingPattern: '^(Evidence|PII|Credentials)\b'
  levels: [2, 3]
```

Extraction replaces the subtree with a marker node — an ADF `panel` containing the surrogate
text and the link — and stores the original subtree as the external part's content, preserving
its ADF so it round-trips losslessly on edit.

**Jira Data Center (wiki markup).** The description is an unstructured string. Marker-fence
splitting (`{jvault}...{jvault}`) is technically possible but fragile: any edit in Jira's own
editor can break the fences, and a broken fence means sensitive text is committed to Jira. We
therefore **refuse** `SPLIT` on wiki-markup deployments at configuration-validation time with an
explicit message (FR-CP-4 AC2), rather than shipping a mechanism that fails unsafely.

## 5.5 Attachments and comments

**Attachments.** `EXTERNAL` attachments never reach Jira in any form. The Jira issue gets a
remote link per attachment, titled with the (non-sensitive) surrogate name, and optionally one
comment listing them. The original filename lives encrypted in jvault (§4.2), because filenames
leak.

**Comments.** An `EXTERNAL` comment still needs a Jira comment to exist — otherwise the
conversation has holes and Jira notifications break. jvault posts a Jira comment containing the
surrogate and the link, and stores the body externally. Editing the body creates a new external
version and rewrites the Jira comment's surrogate only if the surrogate text changed (it usually
has not — avoiding a pointless Jira write is what keeps us inside the per-issue rate limit).

**Custom fields.** Only fields whose Jira type can hold the surrogate are eligible: text, textarea,
URL. A `select` field cannot be externalised, because its value must be one of Jira's allowed
values and no allowed value can be a placeholder. Configuration validation rejects such policies.

## 5.6 Preventing leakage

Five layers, each independently sufficient for a class of mistake:

**1. Type boundary.** External values are carried in `SensitiveValue`, a wrapper whose
`toString()` returns `«redacted:{partId}»`. `JiraWriteGateway` accepts only `JiraSafePayload`,
constructible only by `EgressGuard`. ArchUnit enforces both (§3.3.3).

**2. Content-hash egress check.** Before every Jira write the guard normalises each string in the
payload (Unicode NFKC, whitespace collapse, case fold) and compares its hash, plus the hashes of
its shingled substrings above a length threshold, against the hashes of the ticket's `EXTERNAL`
parts. A match aborts the write. This catches the realistic failure — a refactor that passes the
wrong variable — rather than only the theoretical one.

**3. Classifier.** For classifications at or above a configured threshold, payloads run through
pattern detectors (credentials, keys, national identifiers, configurable regex packs). A detection
aborts the write and raises an audit event. Tuned to be advisory below `RESTRICTED` and blocking
at or above it, because a false positive that blocks an incident ticket is its own kind of outage.

**4. Observability allow-lists.** Logging uses a Logback masking converter plus an MDC key
allow-list; OpenTelemetry span attributes and metric labels go through a filter that drops any
key not on the allow-list. Free-form values cannot reach telemetry by accident, only by editing
the allow-list.

**5. Error-response discipline.** `problem+json` bodies are built from error *codes* and
identifiers. A property-based test submits canary strings through every failure path and asserts
they appear nowhere (FR-CP-6 AC1).

### Kafka dead-letter records

This is the sharpest edge, because the natural implementation — "write the failed message to the
DLQ so someone can inspect it" — publishes sensitive source data to a new topic with different
retention and different ACLs.

**Design:** the DLQ record contains *no payload*.

```json
{
  "correlationId": "01J8Z...",
  "source": { "topic": "security.alerts.v1", "partition": 3, "offset": 918273 },
  "mappingId": "sec-incident-v2",
  "failure": { "stage": "JIRA_CREATE", "code": "JIRA_FIELD_VALIDATION", "attempts": 5 },
  "fieldErrors": [ { "field": "customfield_10010", "code": "REQUIRED" } ],
  "quarantineRef": "01J8Z...",
  "occurredAt": "2026-09-11T10:04:22Z"
}
```

The original message is written to the **encrypted quarantine** — the same storage and
encryption path as ordinary content, with an ACL restricted to a dedicated
`jvault-incident-responder` role, its own retention (default 14 days), and an audit event on
every read. Replay reads from quarantine, not from the DLQ topic.

### Sensitive content already in the incoming topic

jvault cannot retroactively protect a topic it does not own. What we can do, and what the
deployment guide must require:

- The source topic is treated as a **sensitive data store** in its own right: TLS + SASL/mTLS,
  ACLs limited to the producer and jvault's consumer principal, and retention reduced to the
  minimum the producers need (recommended ≤ 72 h) rather than the cluster default.
- Recommend that producers **do not put sensitive payloads on the topic at all** and instead
  send a reference the producer has already stored in jvault via the REST API. This inverts the
  problem and is the only structurally clean answer; it is Phase 2 work (a producer SDK) but the
  architecture should not preclude it.
- If sensitive payloads must be on the topic, recommend producer-side envelope encryption with a
  key jvault can unwrap, so the broker holds ciphertext.
- jvault never republishes source payloads — not to retry topics (which carry only the
  quarantine reference and mapping id), not to the DLQ, not to logs.
- Consumer-lag dashboards and message browsers used by operators must not be pointed at these
  topics; the runbook says so explicitly, and the topic's ACLs enforce it.
