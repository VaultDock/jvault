# 11. Content authorization and delegation

## 11.1 Permissions

| Permission | Grants | Notes |
|---|---|---|
| `VIEW` | See that a part exists, its metadata, and its rendered preview | The weakest useful permission |
| `CREATE` | Add new parts to a ticket in this scope | |
| `EDIT` | Create a new version of a part | Does not imply `DELETE` |
| `DELETE` | Soft-delete a part; restore within retention | Hard purge is admin-only and blocked by legal hold |
| `DOWNLOAD` | Retrieve the bytes | **Not implied by `VIEW`** |
| `MANAGE_ACCESS` | Grant and revoke permissions on this scope | Required to delegate |

**`DOWNLOAD` is separate from `VIEW` on purpose.** The common real requirement is "this group may
read the incident narrative in the browser but must not take a copy of the evidence file onto a
laptop". Collapsing the two would make that impossible to express, and it is one of the more
frequently requested distinctions in systems that hold regulated content. Preview is gated by
`VIEW`; the raw stream by `DOWNLOAD`.

## 11.2 Inheritance and grants

Scopes form a three-level tree: **space → ticket → part**.

- A grant at a scope applies to that scope and everything beneath it.
- Effective permissions at a node are the **union** of all grants that reach it.
- There are **no explicit denies.** Deny rules require an evaluation order, evaluation order
  creates rules that are correct in isolation and wrong in combination, and the resulting
  "why can this person see this?" question becomes unanswerable. The tool for restricting a
  subtree is an **inheritance break**, not a deny.
- An **inheritance break** on a node stops inherited grants at that node; only grants made at or
  below it apply. Used for the genuinely exceptional part inside an otherwise open ticket.
- Grants may carry `expiresAt`. Expired grants are ignored by the evaluator and reaped by a job,
  so a time-boxed access does not require anyone to remember to remove it.

Evaluation is a single query plus a cache: walk the scope chain, collect grants for the caller's
principal set (user + groups + roles + service), stop at an inheritance break, union the
permissions.

## 11.3 How Jira permissions and vault permissions interact

This is the most consequential decision in the document, so the reasoning is set out in full.

Two failure modes are possible, and they pull in opposite directions:

- **jvault as a bypass of Jira.** Content that Jira would have hidden becomes reachable because
  jvault's ACL happens to be broader. The vault undermines the project's security scheme.
- **Jira as a bypass of jvault.** Content was externalised *precisely because* the Jira audience
  is too wide; if Jira access alone sufficed, externalising it achieved nothing.

Only intersection avoids both.

### Secure default: `INTERSECT`

```
allowed = vaultPermission(user, part) AND jiraCanBrowse(user, issue)
```

- `vaultPermission` comes from the ACL evaluation above.
- `jiraCanBrowse` is a **live check performed as the requesting user**, using *their* Jira
  connection — never the integration identity, which would defeat the check entirely. Implemented
  as `GET /rest/api/3/issue/{id}?fields=id`; `200` means access, `403`/`404` means none.
- Decisions are cached for 60 seconds keyed by `(userId, issueId)`, bounding both the Jira load
  and the revocation delay.

A consequence to accept deliberately: **a user with no Jira connection is denied under
`INTERSECT`.** The response is a `409 jira-not-connected` with a connect link rather than a bare
403, so the remediation is obvious. In practice, anyone working these tickets has Jira anyway.

### The configurable modes

| Mode | `allowed =` | When it is right |
|---|---|---|
| `INTERSECT` | vault **AND** Jira | **Default.** Neither system can be used to bypass the other. |
| `VAULT_ONLY` | vault only | Jira browse permission is deliberately broad (an all-company project) but vault content must reach a narrow audience. The intended mode for HR-style spaces. |
| `JIRA_ONLY` | Jira only | Low-sensitivity content externalised for size or retention reasons rather than confidentiality; the vault is storage, not a security boundary. |
| `UNION` | vault **OR** Jira | Effectively "whichever is more permissive wins". Almost always a mistake. |

`VAULT_ONLY` and `JIRA_ONLY` require an admin confirmation and write an audit event. `UNION`
requires a typed confirmation naming the space, writes a **high-severity** audit event, and is
listed permanently on the security dashboard. Modes are per space, set by an administrator,
reviewable in one screen — which matters, because "what is the exception list?" is the first
question any audit asks.

## 11.4 No privilege escalation

Enforced at grant time, server-side:

```
requestedPermissions ⊆ effectivePermissions(grantor, scope)
AND  MANAGE_ACCESS ∈ effectivePermissions(grantor, scope)
AND  (canDelegate(grantor's grant)  OR grantor is a space administrator)
AND  (MANAGE_ACCESS ∈ requested  →  grantor may delegate management, and depth < maxDepth)
```

Notes:

- A grantor cannot grant what they do not hold. A user with `VIEW` + `MANAGE_ACCESS` can grant
  `VIEW`, never `DOWNLOAD`.
- Granting `MANAGE_ACCESS` itself is depth-limited (default max depth 2) so delegation chains stay
  auditable.
- `acl_grant.granted_by` records provenance, so revoking a delegated grant cascades to everything
  granted under it (§4.6).
- A property-based test generates random grantor permission sets and requested grants and asserts
  the subset property always holds (FR-AZ-4 AC1). This is exactly the kind of rule that survives
  the first implementation and is broken by the fourth refactor, so it is tested structurally.

## 11.5 Every request is authorized

Authorization is not a route filter; it is a call in the content service that every access path
must pass through. The paths that must each be independently covered:

metadata read · preview · thumbnail · full download · **range request** · version list ·
historical version download · export · replay of a quarantined message · search result inclusion.

Two are easy to get wrong and are called out explicitly:

- **Range requests.** A second range request on an established stream is a new authorization
  decision. There is no session-level "already authorized for this object".
- **Search results.** Filtering happens *before* results are assembled, not after — a result count
  or a facet that reveals the existence of inaccessible content is itself a leak.

**Possession of a link grants nothing** (FR-LNK-2). There are no signed URLs, no capability
tokens, no presigned storage links in the default configuration. If a deployment enables
presigned downloads for throughput, it is per route, requires acknowledgement, is limited to
short expiry, is refused for content above a configured classification, and is audited at issue
time.

**Existence hiding.** A caller without `VIEW` on the containing space receives `404`, not `403`,
so that probing content refs cannot confirm what exists. A caller who can see the space but lacks
permission on the part receives `403` — at that point existence is already known and a clear
error is more useful than a confusing one.

## 11.6 Change, revocation and degraded dependencies

**Group membership changes.** Sessions store the subject id only — never a frozen permission set.
Every request resolves permissions from the current principal set, with a short cache. Removing
someone from an IdP group takes effect within the role cache TTL (default 5 min, §10.1) without
re-login. An admin "revoke now" action busts all caches for a principal immediately, for the case
where 5 minutes is 5 minutes too long.

**Revoked Jira access.** Under `INTERSECT`, the next live check fails and access stops within the
60-second decision cache. Under `VAULT_ONLY` it has no effect — which is the intended behaviour
for that mode and must be understood when choosing it.

**Jira unavailable.** `INTERSECT` cannot be evaluated. Default behaviour is **fail closed**: the
request is denied with `503 authorization-dependency-unavailable`, clearly distinguished from a
permission denial so users do not think they have lost access.

Fail-closed during a Jira outage is correct but harsh — a security team locked out of incident
evidence because Jira is down is a real operational risk, and incident response is exactly when
this content is needed. So `degradedGraceSeconds` (per space, default `0`) permits, for a bounded
window, the use of a **last-known-good** cached Jira decision for a user who was previously
allowed. It never manufactures a decision for a user with no cached allow, it never extends to
write operations, every such decision is audited as `DEGRADED_ALLOW`, and a banner tells the user
the system is running degraded. Spaces holding the most sensitive content leave it at `0`.

**IdP unavailable.** Existing sessions continue until their next role refresh; the refresh failure
is retried with backoff and roles are held (not cleared) for a bounded window, after which
sessions are downgraded to no roles. New logins fail. Denials are audited with a distinct reason
code.

## 11.7 Administrative delegation

- **Global administrator** — manages deployments, storage backends, key rings, policies. Cannot
  read content by virtue of the role; reading content requires a grant like anyone else, and any
  such grant is audited as a privileged access. Admin-as-implicit-reader is how audit findings
  happen.
- **Space administrator** — full `MANAGE_ACCESS` within one space, may delegate.
- **Delegate** — holds `MANAGE_ACCESS` from a space administrator, bounded by depth, revocable as
  a unit.

Break-glass: a configured emergency role can grant itself temporary access to a space. It requires
two approvers, is time-boxed (default 4 h), raises a high-severity alert at the moment of use, and
is reviewed in the access-review report. There is no silent super-user.

## 11.8 Access review

A quarterly export per space lists every principal with effective access, the grant chain that
produced it, and last-access timestamps — the last of these being the one that actually drives
cleanup, since "nobody has opened this in 14 months" is the most persuasive argument for removing
a grant. Stale grants are flagged; expired ones are already gone.
