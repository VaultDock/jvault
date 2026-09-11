# 10. Authentication

Three distinct identity concerns, deliberately kept separate:

1. **Who is using jvault** — enterprise OIDC login. Governs jvault's own permissions.
2. **What jvault may do in Jira on this person's behalf** — a per-user Jira OAuth authorization.
3. **What jvault may do in Jira with nobody present** — an administrator-configured integration
   identity.

Conflating (1) and (2) is the most common mistake in integrations of this kind, so it is worth
being explicit: **Jira OAuth is not a login mechanism for jvault.** It is an authorization grant
for a downstream resource. A user is logged in to jvault whether or not Jira is connected, and
disconnecting Jira does not log them out.

## 10.1 Application login (OIDC)

Authorization code flow with PKCE against the enterprise IdP (Microsoft Entra ID, Keycloak, or
any compliant provider). Spring Security's OIDC client handles discovery, JWKS rotation, `nonce`
and `state`.

**Backend-for-Frontend.** The SPA holds no tokens. The backend keeps the ID/access/refresh tokens
server-side and issues an opaque session cookie: `HttpOnly`, `Secure`, `SameSite=Lax`,
`__Host-` prefix, path `/`. CSRF protection is a double-submit token on state-changing requests.

Session policy: idle timeout 30 min (configurable), absolute lifetime 12 h, id rotation on
privilege change and on step-up, server-side revocation so an administrator can terminate
sessions immediately, and a session list in the user's profile.

**Group and role mapping.** IdP group/role claims map to jvault roles through administrator-managed
rules:

```yaml
roleMappings:
  - claim: groups
    match: "SEC-IncidentResponse"
    grants: [ ROLE_VAULT_USER, ROLE_SEC_RESPONDER ]
  - claim: roles
    match: "jvault-admin"
    grants: [ ROLE_VAULT_ADMIN ]
  - claim: groups
    matchPattern: "^JIRA-PROJ-(?<project>[A-Z]+)-MEMBERS$"
    grants: [ ROLE_VAULT_USER ]
    spaceGrant: { projectFromGroup: "project", permissions: [VIEW, CREATE, EDIT, DOWNLOAD] }
```

Only claims from a validated token are trusted; group names submitted by the client are ignored.
Because tokens are long-lived relative to group changes, effective roles are re-resolved from the
IdP on a schedule (default every 5 minutes) via the token's groups claim or a Graph/Admin API
lookup where the claim is omitted for size (Entra ID does this above ~200 groups — a detail that
bites real deployments). Revocation therefore takes effect within the cache TTL without requiring
re-login (FR-AUTH-2 AC1).

**Service identities.** Machine clients obtain tokens from the same IdP using
`client_credentials` with `private_key_jwt` or mTLS client authentication — not a shared secret
where it can be avoided. jvault validates issuer, audience, signature and expiry, then maps the
client id to a `SERVICE` principal with its own grants. jvault issues no API keys of its own;
there is exactly one identity provider for the application.

## 10.2 Jira OAuth — the client registration model

One OAuth client (one client ID) is registered per **deployment/integration**, not per user.

What this means, precisely, because it is a frequent source of confusion:

- The client ID **identifies the application**, jvault. It is public information and appears in
  the authorization URL.
- It **grants nothing on its own.** No Jira data is reachable with a client ID.
- Access comes from a *user* completing the authorization and consenting; the resulting token
  carries **that user's** Jira permissions, intersected with the granted scopes.
- Every user's authorization is stored separately (`jira_connection`, §4.7) and used only for
  that user's requests.
- The client **secret** is a confidential credential, held in the secret manager, used only in
  the server-side token exchange, and never sent to a browser.

## 10.3 Connect Jira — the user flow

```
1. User clicks "Connect Jira".
2. Backend creates a state record: {stateId, sessionId, userId, deploymentId, nonce,
   codeVerifier?, redirectUri, createdAt, singleUse}. Returns the authorization URL.
3. Browser is redirected to Atlassian (Cloud) or the Jira base URL (Data Center).
4. User authenticates there and approves the requested scopes.
5. Atlassian/Jira redirects to jvault's registered callback with ?code=&state=.
6. Backend validates state (exists, unused, unexpired, bound to THIS session), then exchanges
   the code for tokens server-side using the client secret (+ code_verifier on Data Center).
7. Tokens are encrypted and stored against (user, deployment).
8. Cloud only: accessible-resources is called; if more than one site is available the user
   picks one and the cloudId is persisted.
9. User returns to where they were.
```

The user never sees, copies, pastes or imports a token. That is the whole point of step 6.

### Security requirements on this flow

| Control | Implementation |
|---|---|
| `state` | 256-bit random, server-side record, single-use (deleted on first use), 10-minute TTL, bound to the session id **and** the user id. A callback arriving on a different session is rejected. |
| `redirect_uri` | Exact string match against a configured allow-list. No wildcards, no path-prefix matching, no dynamic construction from request headers. |
| PKCE | **Data Center: used** (S256 — verified, §0.11). **Cloud: not available** (§0.1). The absence is recorded in `jira_connection.flow_used` and shown in the admin UI, so it is a visible, reviewable fact rather than a silent downgrade. When Atlassian exposes PKCE for Cloud 3LO, enabling it is a configuration change, not a redesign. |
| Client secret | Secret manager only. Never in the SPA bundle, never in a response, never in a log, never in an error. |
| Token storage | Encrypted with the `jira-tokens` key ring (§9.6), separate from content keys. |
| Scopes | Least privilege, listed in §10.4. Requested once; scope changes require re-consent, which the UI explains rather than silently failing. |
| Callback hardening | The callback endpoint is CSRF-exempt by necessity but compensates with the state binding; it accepts only `GET`, ignores unexpected parameters, and never reflects them. |

### Token refresh

Cloud refresh tokens **rotate**: each use invalidates the previous one and issues a new one
(§0.1). That makes concurrent refresh a correctness problem, not just an efficiency one — two
parallel refreshes can invalidate each other and leave the connection dead.

**Single-flight refresh.** A refresh takes a PostgreSQL advisory lock on
`hash(jira_connection.id)`. The winner refreshes and persists the new refresh token in the same
transaction as the access token; losers wait and re-read. Ten concurrent requests produce one
refresh call (FR-AUTH-6 AC1). Atlassian's 10-minute reuse leeway gives headroom if a rotation
result is lost in flight.

Refresh is attempted proactively at 80% of the access token's lifetime, so a user's request
rarely waits on it.

**Failure handling.** A refresh failure (revoked, expired past the inactivity window, consent
withdrawn, breach detection) sets `state = NEEDS_RECONNECT`, clears the stored tokens, surfaces a
"Reconnect Jira" prompt, and stops. It does not retry in a loop — repeated refresh attempts with
an invalidated rotating token look exactly like a token-theft replay and can get the integration
flagged.

**Disconnect** deletes the stored tokens, calls the provider's revocation endpoint where one
exists, sets `DISCONNECTED`, and writes an audit event. It does **not** delete content or
permissions — a user who disconnects Jira still owns their vault content, and reconnecting
restores their ability to act in Jira.

## 10.4 Scopes

**Jira Cloud** (granular preferred; §0.2):

| Purpose | Scope |
|---|---|
| Read issues, projects, metadata | `read:jira-work` |
| Create and edit issues | `write:jira-work` |
| User lookup for pickers | `read:jira-user` |
| Comments | `read:comment:jira`, `write:comment:jira`, `delete:comment:jira` |
| Attachments | `read:attachment:jira`, `write:attachment:jira`, `delete:attachment:jira` |
| Remote links | `read:issue.remote-link:jira`, `write:issue.remote-link:jira`, `delete:issue.remote-link:jira` |
| Refresh tokens | `offline_access` |
| Webhooks (integration identity only) | `manage:jira-webhook` |

**Jira Data Center**: `WRITE` for ordinary operation. DC's scopes are coarse and hierarchical
(§0.11), so `WRITE` means "everything this user may change". Least privilege on DC must therefore
be enforced by the *service user's Jira permissions* — project roles and permission schemes — not
by the OAuth scope. `ADMIN`/`SYSTEM_ADMIN` are never requested.

## 10.5 Background Jira connection

Kafka processing, reconciliation, webhook registration and scheduled jobs run with no user
present. They use an administrator-configured **integration identity**.

Two things this is not:

- The shared client ID is **not** a service account. It identifies the application and authorises
  nothing.
- **Client credentials grants are not supported** for Jira Cloud 3LO (§0.1). Any design that
  assumes "the app authenticates as itself with its client secret" is wrong for Jira Cloud.

### Options, by deployment

| Deployment | Mechanism | Assessment |
|---|---|---|
| **Cloud** | **Atlassian service account + scoped API token** (§0.10) | **Recommended.** A real non-human identity, org-managed, scoped, revocable, with 1–365 day token expiry. No human's employment status is load-bearing. Limits: 5 free per org; scopes are immutable after creation (rotation means creating a new token, not editing one); revocation takes up to 10 minutes. |
| **Cloud** | Dedicated "bot" Atlassian account + OAuth 3LO with `offline_access` | Works, and gives per-scope least privilege. But the rotating refresh token carries a 90-day inactivity expiry and a bounded absolute lifetime, so an idle integration silently dies and someone must interactively re-consent. Acceptable as a fallback; operationally worse than a service account. |
| **Cloud** | **Forge or Connect app**, acting via `asApp()` / JWT (§0.10) | **Strongest identity model.** The app is the actor, with its own scopes, no account, no token expiry to babysit — and it unlocks JQL-indexed entity properties (§0.9), which would simplify crash recovery considerably. Cost: jvault must be built and distributed as an Atlassian app and installed on the site, which is a different delivery model and a different approval conversation. Proposed as a release-3 option, not MVP. |
| **Cloud** | An employee's personal account | **Avoid.** It breaks when they change teams or leave, it attributes machine actions to a person, and it usually carries far more permission than the integration needs. Only acceptable as a time-boxed pilot, and jvault's admin UI warns when the configured identity resolves to a licensed human account. |
| **Data Center** | **PAT of a dedicated service user** (§0.12) | **Recommended.** Simple, supported since Jira 8.14, permissions exactly those of the service user, revocable individually or in bulk. Expiry is optional — we require one anyway and alert 14 days ahead, because perpetual credentials age badly. |
| **Data Center** | OAuth 2.0 incoming app link with a service user | Viable, and gives revocation at the link level. But it still needs an interactive authorization once, and DC's coarse scopes mean it offers little over a PAT. |

### Setup, in the administrator's words

1. Create a dedicated Jira identity — a Cloud service account, or a DC service user named
   something like `svc-jvault`.
2. Grant it **exactly** the project permissions it needs: *Browse Projects*, *Create Issues*,
   *Edit Issues*, *Add Comments*, *Create Attachments*, *Link Issues*, and *Modify Reporter* only
   if reporter attribution is wanted.
3. Create a scoped token (Cloud) or PAT (DC) with an explicit expiry.
4. Paste it once into jvault's admin UI. jvault validates it against Jira immediately, records the
   resolved account and its permissions, and stores the credential in the secret manager.
5. jvault alerts at 30, 14 and 7 days before expiry, and refuses to start Kafka processing on an
   expired credential rather than dead-lettering everything.

### Attribution

Issues and comments created by background processing show the **integration identity** as creator
— which is accurate. The originating actor is recorded separately (§7.6): authoritatively in
`ticket_record.origin_actor`, in the `jvault.origin` issue property, and optionally in a visible
field or the opening comment. `SET_REPORTER_IF_PERMITTED` can additionally set Jira's Reporter to
a matching Jira account where permission allows.

## 10.6 Manual credentials — the optional alternative

Administrator-enabled, off by default, visually distinct from the OAuth flow in the UI.

Where it applies: DC Personal Access Tokens; Cloud Atlassian API tokens used with basic auth
(email + token, §0.10). Note that Atlassian API tokens now **must** expire, 1–365 days
(§0.10) — a perpetual manual credential is no longer possible on Cloud, which is a helpful
constraint.

Requirements:

- Enabling the feature is an explicit admin action with an audit event and an on-screen
  explanation of what is given up (no per-scope limitation, no revocation by the user, weaker
  attribution).
- Credentials are validated against Jira before being accepted; an invalid credential is rejected
  at entry rather than discovered at first use.
- Stored in the secret manager, referenced by handle, encrypted under the `jira-tokens` ring,
  never logged, never returned by any API — the UI shows only the identity it resolves to and its
  expiry.
- Rotation is a replace-then-verify operation with no downtime: the new credential is validated
  before the old handle is released.
- Removal revokes upstream where the API allows, deletes the secret, and audits.
- jvault refuses to accept a credential that resolves to an account with Jira administrator
  rights, unless an administrator overrides with an explicit acknowledgement.

## 10.7 What Jira OAuth is not

If a "Sign in with Atlassian" experience is ever requested, the honest position is:

- Atlassian's OAuth 2.0 (3LO) is an **authorization** protocol for Jira APIs. It is not an OIDC
  provider for your enterprise, and it does not carry your enterprise's groups, roles or
  conditional-access policy.
- It is possible to *identify* the user behind a 3LO token — `GET /me` or the accessible-resources
  response yields an Atlassian account id and profile — and to use that as a login identity for a
  small deployment.
- That would make **Atlassian the identity provider for jvault**, which means: no enterprise MFA
  policy, no conditional access, no group-based authorization, no lifecycle management when
  someone leaves, and an authorization model dependent on a third party's account directory.
- For a system whose purpose is holding content too sensitive for Jira, that is the wrong trade.

**Recommendation: enterprise OIDC for application login, Jira OAuth for Jira access only.** If a
"Sign in with Atlassian" convenience is genuinely wanted, offer it as an *additional* authentication
method federated **through** the enterprise IdP (Entra ID and Keycloak can both broker Atlassian as
an upstream provider), so that jvault continues to trust exactly one issuer.
