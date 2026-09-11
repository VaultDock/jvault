# 13. Jira Cloud vs Jira Data Center

Everything in this table is sourced in [0. Verified capabilities](00-verified-capabilities.md).

## 13.1 Integration comparison

| Concern | Jira Cloud | Jira Data Center |
|---|---|---|
| **User authorization** | OAuth 2.0 (3LO), authorization code only | OAuth 2.0 incoming application link, authorization code **and PKCE** |
| **Authorize endpoint** | `https://auth.atlassian.com/authorize` (`audience=api.atlassian.com`) | `{base}/rest/oauth2/latest/authorize` |
| **Token endpoint** | `https://auth.atlassian.com/oauth/token` | `{base}/rest/oauth2/latest/token` |
| **PKCE** | Not exposed publicly (ECO-283) | **Supported** (`plain`, `sha256`) |
| **Access token lifetime** | ~1 hour | 7200 s (2 h) |
| **Refresh tokens** | Rotating; `offline_access`; 90-day inactivity; 10-min reuse leeway | Supported; refresh invalidates both old tokens |
| **Client credentials** | Not available | Not available |
| **Scope model** | Fine-grained (`read:comment:jira`, `write:attachment:jira`, …) | Coarse: `READ` ⊂ `WRITE` ⊂ `ADMIN` ⊂ `SYSTEM_ADMIN` |
| **Least privilege lever** | OAuth scopes **and** Jira permissions | Jira permissions only — scopes are too coarse to matter |
| **API base** | `https://api.atlassian.com/ex/jira/{cloudid}/…` | `{base}/rest/api/2/…` |
| **Multi-instance** | `accessible-resources` → cloudid; a user may reach several sites | One base URL per deployment; no site selection |
| **Rich text** | ADF (API v3) | Wiki markup (API v2) |
| **Section-level split** | Feasible — ADF is a tree | **Refused** — unstructured markup, unsafe fences (§5.4) |
| **Create metadata** | `createmeta/{project}/issuetypes[/{id}]`; aggregate form deprecated | `createmeta` still available; watch the version's deprecation state |
| **Remote links** | `POST /issue/{key}/remotelink`, upsert by `globalId` | Same semantics |
| **Entity properties** | 32 KB; JQL-searchable only via a Connect/Forge module | 32 KB; index configurable server-side, so JQL search is achievable without an app |
| **Webhooks** | Dynamic, 30-day expiry, 5 per user per tenant for OAuth apps, restricted JQL, at-least-once with 5 retries | Admin-registered, no expiry, full JQL, delivery depends on instance configuration |
| **Rate limits** | Documented and enforced: points/hour, burst rps, per-issue 20/2 s | Configurable per instance; often none by default |
| **Background identity** | **Service account + scoped token** (recommended); bot account + 3LO; Forge/Connect app | **PAT of a service user** (recommended); OAuth service user |
| **Manual credential** | API token with basic auth; expiry now mandatory 1–365 days | PAT; expiry optional (we require one) |
| **App model** | Forge / Connect, `asApp()` identity | Server-side plugins (P2) |
| **Availability** | Atlassian-operated; jvault must tolerate its rate limits and outages | Customer-operated; often behind a private network, sometimes air-gapped |
| **Network** | Public internet egress required | Frequently internal only — which is often the reason the deployment exists |

## 13.2 What this means for the design

**Abstraction boundary.** A `JiraDeployment` interface with two implementations. The differences
are concentrated in four places, not smeared across the codebase:

1. **Auth strategy** — token endpoints, PKCE, refresh semantics, site selection.
2. **Document format** — ADF serializer vs wiki-markup serializer, behind one `RichTextCodec`.
3. **Metadata source** — the createmeta variant and its response shape.
4. **Capability flags** — `supportsSplitPlacement`, `supportsDynamicWebhooks`,
   `supportsScopedTokens`, `supportsPkce`, `hasEnforcedRateLimits`.

Everything above those four is deployment-agnostic, including the whole placement, storage,
encryption and authorization stack.

**Capability flags are load-bearing, not cosmetic.** Configuration validation consults them:
a `SPLIT` policy on a Data Center deployment is rejected at save time (§5.4), a route claiming
records-managed retention is rejected on a backend without immutability (§8.2). The system refuses
configurations it cannot honour rather than degrading silently.

**Data Center is not the easier target.** It is easier in some respects — PKCE, no rate limits by
default, server-side property indexing, simple PAT identity — and harder in others: wiki markup
kills section-level placement, coarse scopes mean the service user's permissions are the *only*
least-privilege control, and air-gapped instances rule out cloud KMS and cloud object storage,
pushing the deployment toward filesystem or CMIS storage and an on-premises HSM.

**Version support.** Target Data Center **9.12 LTS and 10.x**, with the OAuth plugin versions
noted in §0.11 (Jira 9.12.14+ → plugin 3.1.14+; Jira 10.1.0+ → plugin 4.1.0+). A startup
compatibility probe records the instance version and enabled plugins and refuses to start against
an unsupported combination rather than failing mysteriously at first use.

## 13.3 Decision: both, from the MVP

**[D1](decisions.md) settles this: Cloud and Data Center are both in scope from the first
release.** `JiraDeployment` and `RichTextCodec` therefore exist from the first commit — which is
the right sequencing regardless, because retrofitting a deployment abstraction onto a Cloud-only
implementation means touching every Jira call site.

Build order within the MVP still favours Cloud, for a reason that survives the decision: Cloud's
constraints are strictly harder (enforced rate limits, no PKCE, no JQL-searchable properties
without an app, 30-day webhook expiry), so code written against Cloud satisfies Data Center
comfortably. ADF is likewise the more demanding rich-text target — a wiki-markup codec is
straightforward once an ADF model exists, and the reverse is not. So: implement Cloud first
*behind the abstraction*, then add the DC implementation, rather than implementing Cloud and then
discovering the abstraction.

### What the team must accept as a consequence

1. **A visible feature asymmetry.** Section-level `SPLIT` works on Cloud and is refused on Data
   Center (§5.4). This is a deliberate safety decision, not a gap to be closed later, and it needs
   saying to users in the product rather than only in this document.
2. **Two auth paths in the MVP**, with opposite PKCE positions — DC uses it, Cloud cannot.
3. **Two integration-identity mechanisms** — Cloud service account token and DC service-user PAT
   ([D4](decisions.md)) — with different expiry and rotation behaviour to alert on.
4. **A CI compatibility matrix** running against a Cloud sandbox and DC 9.12 LTS and 10.x
   containers from the start, not added when the first DC bug appears.
5. **The Q0 connectivity question** ([decision log](decisions.md)) must be answered first: an
   air-gapped deployment cannot reach Atlassian, which would remove Cloud from scope entirely.
