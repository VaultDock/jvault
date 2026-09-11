# 0. Verified Jira capabilities

Everything the design depends on, checked against Atlassian's own documentation in
September 2026, and — where marked **OBSERVED** — against a live Jira Cloud site
(`*.atlassian.net`, team-managed project, 2026-09-11). Observed beats documented where the two
disagree, and both are recorded so the difference stays visible.

Everything below was checked Design recommendations are kept out of this document deliberately —
if a claim is not here with a citation, it is not a capability claim.

## 0.1 Jira Cloud — OAuth 2.0 (3LO)

| Fact | Detail |
|---|---|
| Grant types | Authorization code (3LO) only. Implicit is not supported. Client credentials is **not** offered for 3LO apps. |
| Authorization endpoint | `https://auth.atlassian.com/authorize` with `audience=api.atlassian.com`, `client_id`, `scope`, `redirect_uri`, `state`, `response_type=code`, `prompt=consent`. |
| Token endpoint | `POST https://auth.atlassian.com/oauth/token`. |
| `state` | Documented as required for security; binds the authorization to the initiating user. |
| PKCE | **Not exposed** in the developer console for Cloud 3LO. An Atlassian staff answer (2024-05-21) states only the authorization code flow is supported; a later comment notes the capability exists behind a flag but is not available publicly. Feature request ECO-283. |
| Refresh tokens | Rotating. Require the `offline_access` scope. Each use issues a new refresh token valid 90 days; 90-day inactivity expiry; a 10-minute reuse leeway exists for breach detection. |
| Site discovery | `GET https://api.atlassian.com/oauth/token/accessible-resources` returns the sites the token can reach. The `id` is the **cloudid**. |
| API base URL | `https://api.atlassian.com/ex/jira/{cloudid}/{api-path}`. |

Sources: [OAuth 2.0 (3LO) apps](https://developer.atlassian.com/cloud/jira/platform/oauth-2-3lo-apps/),
[PKCE community thread](https://community.developer.atlassian.com/t/oauth-2-0-with-proof-key-for-code-exchange-pkce/80173).

**Consequence for the design:** we cannot rely on PKCE for Jira Cloud. jvault must be
a *confidential* client for Cloud — the code exchange happens server-side with a
client secret that never reaches the browser — and `state` must carry the full
CSRF/session binding burden. See [10. Authentication](10-authentication.md).

## 0.2 Jira Cloud — OAuth scopes

Classic scopes: `read:jira-work`, `write:jira-work`, `read:jira-user`,
`manage:jira-webhook`. Granular scopes exist for the resources we touch:
`read|write|delete:attachment:jira`, `read|write|delete:comment:jira`,
`read|write|delete:issue.remote-link:jira`.

Source: [Scopes for OAuth 2.0 (3LO) and Forge apps](https://developer.atlassian.com/cloud/jira/platform/scopes-for-oauth-2-3LO-and-forge-apps/).

## 0.3 Jira Cloud — rate limiting

Three independent mechanisms apply simultaneously:

1. **Hourly points quota.** Calls consume points by cost. Most apps share a global
   budget of 65,000 points/hour; high-usage apps may qualify for per-tenant pools.
2. **Burst (per-second) limits.** Defaults around 100 rps for GET/POST and 50 rps for
   PUT/DELETE, token-bucket, with per-endpoint exceptions.
3. **Per-issue write limits.** 20 operations per 2 seconds and 100 operations per
   30 seconds against a single issue.

Headers: `X-RateLimit-Remaining`, `X-RateLimit-NearLimit` (true below 20% capacity),
`Retry-After` on 429, `RateLimit-Reason` naming the limit that fired
(e.g. `jira-quota-global-based`, `jira-burst-based`).

> **OBSERVED — richer than documented.** A live site returns structured
> [IETF draft RateLimit](https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/)
> headers on **every** response, not only near the limit:
>
> ```
> RateLimit-Policy: "jira-burst-based";q=100;w=1
> RateLimit:        "jira-burst-based";r=348;t=1
> X-RateLimit-Limit: 350
> X-RateLimit-Remaining: 348
> ```
>
> `r` is the remaining allowance and `t` the seconds until it resets. That is strictly more useful
> than waiting for a 429: a client can pace itself from `r`/`t` and never be throttled at all.
> Note also that `X-RateLimit-Limit` (350) and the policy's `q` (100 per 1 s window) are different
> numbers describing different things — do not collapse them.
>
> **Consequence:** `JiraResponseClassifier` currently reads only `X-RateLimit-*` and
> `Retry-After`. Parsing `RateLimit` would let the outbox slow down *before* being told to. Atlassian's own guidance is
exponential backoff with jitter: 2 s base, doubling to ~30 s, jitter multiplier
0.7–1.3, ~4 attempts, `Retry-After` as a floor.

Source: [Rate limiting](https://developer.atlassian.com/cloud/jira/platform/rate-limiting/).

**Consequence:** the per-issue limit is the binding constraint for jvault, because a
single ticket creation performs several writes against one issue (create, set
properties, add remote link, add comment, upload attachments). Writes to one issue
must be serialised through a per-issue queue. See [12. Reliability](12-reliability.md).

## 0.4 Jira Cloud — webhooks

- **Dynamic webhooks** are registered via `POST /rest/api/3/webhook` and are available
  only to Connect apps and OAuth 2.0 apps. Quota: 100 per tenant for Connect apps,
  **5 per user per tenant** for OAuth 2.0 apps. Requires `manage:jira-webhook`.
- Dynamic webhooks **expire after 30 days**; the Extend webhook life API pushes the
  expiry out another 30 days and must be called periodically.
- JQL filters on dynamic webhooks are restricted to `issueKey`, `project`,
  `issuetype`, `status`, `priority`, `assignee`, `reporter`, `issue.property` and Epic
  custom fields, with only `=`, `!=`, `IN`, `NOT IN`.
- Delivery is **at-least-once** with up to 5 retries and randomised 5–15 minute
  backoff. A webhook identifier is stable across retries, which is what allows
  consumers to deduplicate.
- Payloads larger than **25 MB are not delivered**. Deleting a project does **not**
  cascade `issue_deleted` events.

Source: [Webhooks](https://developer.atlassian.com/cloud/jira/platform/webhooks/).

**Consequence:** webhooks are an optimisation, never a source of truth. The 5-per-user
OAuth quota also means jvault must register webhooks under the **integration
identity**, not per end user, and must back them with a reconciliation sweep.

## 0.5 Jira Cloud — issue creation metadata

`GET /rest/api/{2,3}/issue/createmeta` is **deprecated**. The replacements are:

- `GET /rest/api/3/issue/createmeta/{projectIdOrKey}/issuetypes`
- `GET /rest/api/3/issue/createmeta/{projectIdOrKey}/issuetypes/{issueTypeId}`

The per-issue-type response carries the field metadata jvault needs to render a form:
`required`, `schema`, `allowedValues`, `name`, `key`, `operations`. The announced
removal date (2024-06-03) slipped and no replacement date has been published, but the
deprecated form must not be used in new code.

> **OBSERVED.** The deprecated aggregate form still returns `200` on a live site — it has not
> been removed. The replacement endpoints work and return the documented shape. jvault uses only
> the replacements, which remains correct; the point is that "it will 404 soon" is not yet true
> and nothing should be timed around it.

Sources: [Create Issue Meta Endpoint Deprecation](https://community.developer.atlassian.com/t/create-issue-meta-endpoint-deprecation/75413),
[Issues API group](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/).

## 0.6 Jira — remote issue links

`POST /rest/api/{2,3}/issue/{issueIdOrKey}/remotelink` with an optional `globalId`.
If a remote link with that `globalId` already exists on the issue it is **updated**;
otherwise it is created. This makes remote-link writes naturally idempotent. `PUT`
replaces the resource — fields not supplied are nulled.

Sources: [Remote issue links (Cloud)](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-remote-links/),
[Creating remote issue links (DC)](https://developer.atlassian.com/server/jira/platform/creating-remote-issue-links/).

> **OBSERVED — verified empirically.** Posting the same `globalId` twice to a live site returns
> `201` then `200`, and the issue ends with **one** remote link, not two. Upsert confirmed.

**Consequence:** `globalId = "jvault:content:{contentRef}"` gives us a retry-safe,
self-deduplicating link write. This is the primary link placement mechanism, and the outbox can
replay it freely.

## 0.7 Jira — attachments

Uploads are `multipart/form-data`, the part name must be `file`, and the request must
carry the header `X-Atlassian-Token: no-check` or it is rejected as XSRF. Default
maximum file size is 1 GB, configurable by the Jira administrator; exceeding it
returns 413.

Attachments cannot be edited in place — only added and deleted.

Sources: [How to add an attachment using the REST API](https://support.atlassian.com/jira/kb/how-to-add-an-attachment-to-a-jira-cloud-issue-using-rest-api/),
[Issue attachments API group](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-attachments/).

## 0.8 Jira Cloud — Atlassian Document Format

REST API v3 represents rich text as ADF, a JSON tree: `{ "version": 1, "type": "doc",
"content": [...] }`, composed of block nodes, inline nodes and marks. It is used for
issue descriptions, comment bodies and textarea custom fields. A JSON schema is
published at `go.atlassian.com/adf-json-schema`. REST API v2 uses wiki markup instead.

Source: [Atlassian Document Format](https://developer.atlassian.com/cloud/jira/platform/apis/document/structure/).

**Consequence:** section-level ("split") placement inside a description is tractable,
because ADF is a structured tree with addressable nodes — unlike the wiki-markup
string that v2 and Data Center return. See [5. Content placement](05-content-placement.md).

## 0.9 Jira — entity (issue) properties

Apps can attach a JSON key/value store to issues. The value must be valid JSON,
maximum **32 KB**; keys are at most 255 characters. Properties are read and written
through `/rest/api/3/issue/{issueIdOrKey}/properties/{propertyKey}`.

Properties become **JQL-searchable only if indexed**, which on Cloud requires a
Connect `entityProperty` module or a Forge `jira:entityProperty` module declaring
index types (`number`, `text`, `string`, `date`). A plain OAuth 2.0 (3LO) integration
cannot make properties searchable.

Sources: [Entity properties (Cloud)](https://developer.atlassian.com/cloud/jira/platform/jira-entity-properties/),
[Issue properties API group](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-properties/).

**Consequence — important.** jvault cannot use `issue.property` JQL to find "the issue
I may have just created" unless the customer also installs a Forge/Connect app. The
crash-recovery design must therefore not depend on it. See
[12.4 Ambiguous creation](12-reliability.md).

> **TO VERIFY:** whether `POST /rest/api/3/issue` reliably accepts a top-level
> `properties` array at create time. Documentation implies it, but there are community
> reports of errors. *Test:* create an issue with `properties` against a sandbox site
> and read the property back. If it fails, jvault sets properties in a separate call
> immediately after create, which is the behaviour the design already assumes.

## 0.10 Jira Cloud — non-human identities

| Option | Status |
|---|---|
| **Atlassian service accounts** | Non-interactive org-level accounts for programmatic access. Created under Administration → Directory → Service accounts. Up to **5 free per organization**; more require Atlassian Guard. Tokens are **scoped** and expire between **1 and 365 days** (default 1 year); scopes are immutable after creation. Revocation takes up to 10 minutes to propagate. Explicitly intended for unattended integrations against `api.atlassian.com`. |
| **API tokens on a human account** | Basic auth with `email:api-token`. Expiry is now mandatory: 1–365 days, default 1 year. Tokens created before 2024-12-15 were force-expired between 2025-03-14 and 2026-05-12. |
| **Connect app (JWT)** | Acts as the app / addon user. |
| **Forge app** | `asApp()` executes with the app's own identity and scopes, independent of any user. |
| **OAuth 2.0 client credentials** | Not available for 3LO. |

Sources: [Manage API tokens for service accounts](https://support.atlassian.com/user-management/docs/manage-api-tokens-for-service-accounts/),
[Manage API tokens for your Atlassian account](https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/),
[Understanding JWT for Connect apps](https://developer.atlassian.com/cloud/jira/platform/understanding-jwt-for-connect-apps/),
[Forge Jira authentication](https://developer.atlassian.com/platform/forge/apis-reference/fetch-api-product.requestjira/).

## 0.11 Jira Data Center — OAuth 2.0 incoming application links

Jira DC can itself act as an OAuth 2.0 provider for *incoming* application links.

| Fact | Detail |
|---|---|
| Authorization endpoint | `{base}/rest/oauth2/latest/authorize` |
| Token endpoint | `{base}/rest/oauth2/latest/token` |
| Grants | Authorization code, and **authorization code with PKCE** (`code_challenge_method` of `plain` or `sha256`). Implicit and Resource Owner Password Credentials are explicitly not supported. |
| Access token lifetime | 7200 s (2 hours). |
| Refresh tokens | Supported; refreshing invalidates both the old access token and the old refresh token. |
| Scopes | `READ`, `WRITE`, `ADMIN`, `SYSTEM_ADMIN`, hierarchical (WRITE implies READ, and so on). Scopes are chosen when the incoming link is created. |
| Setup | Administrator creates an *incoming* application link and receives a client ID and client secret. |
| Plugin versions | Jira 9.12.14+ requires OAuth plugin 3.1.14+; Jira 10.1.0+ requires OAuth plugin 4.1.0+. |

Sources: [Jira OAuth 2.0 provider API](https://confluence.atlassian.com/adminjiraserver/jira-oauth-2-0-provider-api-1115659070.html),
[Setting up OAuth 2.0 integration](https://confluence.atlassian.com/jiracore/setting-up-oauth-2-0-integration-1005784173.html).

**Consequence:** Data Center gives us *more* OAuth hygiene than Cloud (PKCE), but a
much coarser scope model — `WRITE` is effectively "everything this user can change".
Least privilege on DC has to be enforced by the service user's Jira permissions, not by
scopes.

## 0.12 Jira Data Center — Personal Access Tokens

Available in Jira Core/Software 8.14+, Jira Service Management 4.15+ and
Confluence 7.9+. Created in the user profile UI or via `POST /rest/pat/latest/tokens`.
A PAT carries exactly the permissions of the user who created it. Expiry is optional
(a token can be perpetual); an "expires soon" status appears 5 days before expiry.
Users and administrators can revoke tokens, and administrators can bulk-revoke.
Used as `Authorization: Bearer <token>`.

Source: [Using Personal Access Tokens](https://confluence.atlassian.com/enterprise/using-personal-access-tokens-1026032365.html).

## 0.13 Summary of capability gaps that shape the design

| Gap | Where it bites | Design response |
|---|---|---|
| No PKCE on Cloud 3LO | Jira connect flow | Confidential client, server-side exchange, hardened `state`. §10.3 |
| No client credentials on Cloud 3LO | Background identity | Service account + scoped token, or Forge app. §10.5 |
| No idempotency key on issue create | Kafka replay, network timeouts | DB reservation + bounded reconciliation scan. §12.4 |
| Entity properties not JQL-searchable without an app | Crash recovery | Recovery scan by integration identity + creation window, not by property JQL. §12.4 |
| Dynamic webhooks expire in 30 days, 5 per user | Change capture | Integration-identity registration + scheduled extension + reconciliation sweep. §12.5 |
| Per-issue write limit 20/2 s | Ticket creation does 3–8 writes to one issue | Per-issue serialisation queue. §12.7 |
| Attachments immutable | Edit semantics | Delete-and-replace, with jvault holding true version history. §8.4 |
| DC scopes are coarse | Least privilege on DC | Permission-based least privilege via the service user. §13 |
