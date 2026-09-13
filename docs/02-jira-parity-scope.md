# 2. Jira UI parity — explicit scope

"Reproduce Jira ticket creation" is the single largest source of scope risk in this
project, because Jira's create dialog is not driven only by REST-visible configuration.
Part of it is driven by server-side code (workflow validators), part by client-side app
code (Behaviours, Forge/Connect field UIs) that has no read API at all.

This document draws the line.

## 2.1 The parity principle

> jvault reproduces everything Jira exposes through a supported read API, and fails
> *loudly and correctly* on everything it does not, by surrendering to Jira's own
> validation and presenting Jira's own error message against the right field.

Three tiers:

- **Tier A — Reproduced.** jvault renders and validates it locally.
- **Tier B — Delegated validation.** jvault renders a best-effort control, submits, and maps
  Jira's rejection back to the field. The user sees a correct error, just later than in Jira.
- **Tier C — Not reproduced.** jvault renders read-only or refuses, and offers "Open in Jira"
  as the escape hatch.

Tier B is the honest answer for most of Jira's dynamic behaviour, and it is why the
error-mapping layer (§2.5) is a first-class component rather than an afterthought.

## 2.2 Tier A — reproduced

| Capability | Source API | Notes |
|---|---|---|
| Project list | `GET /rest/api/3/project/search` | Filtered to projects where the user holds *Create Issues*. |
| Issue types per project | `GET /issue/createmeta/{projectIdOrKey}/issuetypes` | Includes sub-task types; hierarchy respected. |
| Field set, required flags, allowed values, defaults | `GET /issue/createmeta/{projectIdOrKey}/issuetypes/{issueTypeId}` | The deprecated aggregate `createmeta` is not used (§0.5). |
| Editable field set on an existing issue | `GET /rest/api/3/issue/{key}/editmeta` | Fields without a `set` operation render read-only. |
| Summary, description, environment | create/edit APIs | Description as ADF on Cloud (§0.8). |
| System fields: priority, labels, components, fix/affects versions, due date, assignee, reporter, security level, parent | createmeta + dedicated pickers | Assignee via `GET /user/assignable/search`; components/versions via project APIs. |
| Standard custom field types | createmeta `schema` | See the type matrix in §2.3. |
| Comments: list, add, edit, delete | `/issue/{key}/comment` | Visibility restrictions (role/group) supported. |
| Attachments: list, add, delete, download | `/issue/{key}/attachments`, `/attachment/content/{id}` | `X-Atlassian-Token: no-check`, part name `file` (§0.7). |
| Watchers, votes | dedicated endpoints | Release 1. |
| Transitions and screen fields on transition | `GET /issue/{key}/transitions?expand=transitions.fields` | Release 1. |
| Issue links and sub-task creation | `/issueLink`, create with `parent` | Release 2. |
| Permission-aware affordances | `GET /mypermissions` | Controls are hidden, and the server re-checks. |

### 2.3 Custom field type matrix

| `schema.custom` / `schema.type` | Control | Tier |
|---|---|---|
| `textfield`, `textarea`, `url`, `readonlyfield` | text input / rich text | A |
| `float`, `number` | numeric input with `allowedValues` where present | A |
| `datepicker`, `datetime` | date/time picker, timezone-aware | A |
| `select`, `radiobuttons` | single select from `allowedValues` | A |
| `multiselect`, `multicheckboxes` | multi select | A |
| `cascadingselect` | two-level dependent select | A |
| `labels` | tag input with `GET /label` suggestions | A |
| `userpicker`, `multiuserpicker` | user search | A |
| `grouppicker`, `multigrouppicker` | group search | A |
| `project`, `version`, `multiversion` | project/version pickers | A |
| `people`, `sd-*` (JSM) | best-effort control | B |
| Forge / Connect custom field types | raw value display, read-only | C |
| `Assets`/CMDB object fields | not rendered | C |
| Proforma / request forms | not rendered | C |

## 2.4 Tier C — cannot be reproduced through supported APIs

This is the important list. Each entry names *why* and *what we do instead*.

### 2.4.1 Field Behaviours (ScriptRunner, JMWE, JSU and similar)

**Why not.** Behaviours run inside Jira's own create dialog as app-injected client-side logic:
hiding fields, making them required conditionally, filtering option lists, setting values
reactively. There is no public API that enumerates them, and their effects are not present in
`createmeta`.

**Alternative.** Three options, in order of preference:
1. **Delegated validation (Tier B).** Submit, catch Jira's rejection, map to fields. Behaviours
   that only *validate* are handled correctly this way.
2. **jvault form rules.** An admin-authored rule set in jvault (`when field X = Y, require Z`)
   that intentionally mirrors the important behaviours. Explicitly a duplicate of configuration
   that must be maintained in two places — we recommend using it only for a handful of
   high-traffic forms, and we surface a warning in the admin UI saying so.
3. **Open in Jira.** For projects flagged `parity: delegated`, jvault creates a minimal issue
   (summary + required fields + vault links) and deep-links the user into Jira to complete it.

Behaviours that *reactively set or hide* fields cannot be reproduced by any of these; option 3
is the only faithful answer for those projects.

### 2.4.2 Workflow validators and conditions on the create transition

**Why not.** Custom validators execute server-side at transition time. `transitions?expand=
transitions.fields` reveals the screen's fields, not the validator logic or its messages.

**Alternative.** Tier B. jvault maps `400` responses from create/transition into field-level
errors (§2.5) and shows unmapped validator messages as a form-level error verbatim.

### 2.4.3 Screen layout: tabs, field order, field descriptions per context

**Why not.** Jira Cloud's create metadata returns a field *set*, not the screen's tab structure
or display order, and field help text per configuration context is not reliably exposed.

**Alternative.** jvault stores an admin-authored **form layout** per (project, issue type):
ordered sections, column spans, and help text. A sensible generated default is used until an
admin customises it (system fields first in Jira's conventional order, then custom fields
alphabetically). This is a real divergence from Jira and must be communicated to users.

### 2.4.4 Rich-text editor parity

**Why not (fully).** Jira's editor includes Smart Links that unfurl server-side, inline media
rendered from Jira's Media API, emoji sets, `/`-commands, and collaborative editing. The ADF
*format* is documented, but the editing experience is a Jira product surface.

**Alternative.** TipTap/ProseMirror with a jvault ADF serializer — chosen by measurement, not
preference: the Atlassian editor ships twenty-eight times more JavaScript for the same job
(docs/15-implementation-plan.md). Accept in exchange:
- Smart Links are entered and stored as ordinary links; Jira unfurls them on its side.
- Inline media uploaded through jvault becomes an attachment plus a link, not a Jira media node,
  unless the part is Jira-placed and we upload through the attachment API first.
- No collaborative editing. Concurrent edit is handled by optimistic concurrency, not merge.

### 2.4.5 Attachment previews and thumbnails for Jira-placed attachments

Jira generates its own thumbnails. jvault renders previews only for vault-placed content
(FR-UI-6) and links out to Jira for Jira-placed attachments.

### 2.4.6 Issue security levels and field-level security

Security level *is* settable through `createmeta`'s `security` field where the user is
permitted, so setting it is Tier A. What is Tier C is *field-level* security provided by
third-party apps, which is invisible to the API.

### 2.4.7 Bulk creation beyond Jira's own limits

`POST /issue/bulk` accepts a bounded batch (50). jvault's bulk endpoints chunk to that limit and
report per-item results; there is no all-or-nothing bulk semantics available, so jvault's bulk
API is explicitly partial-success with a per-item outcome list.

### 2.4.8 Anything Jira Service Management-specific

Customer portals, request types, approvals, SLAs, Assets object fields, and Proforma forms use
separate APIs with their own semantics. Out of scope; "Open in Jira" for JSM projects.

## 2.5 The error-mapping layer

Because Tier B carries so much of the parity burden, mapping Jira's errors back onto form
fields is a named component with its own test corpus.

Jira returns, on a failed create:

```json
{ "errorMessages": ["..."], "errors": { "customfield_10010": "Field 'x' is required." } }
```

Mapping rules:
- `errors` keys map directly to form fields → field-level error.
- `errorMessages` entries are matched against an admin-extensible pattern table to attach them to
  a field where possible; unmatched entries become a form-level error shown verbatim.
- Jira's message text is passed through **unmodified** but is treated as untrusted: rendered as
  text, never as HTML, and never logged together with the submitted payload.
- Every unmapped `errorMessage` increments a metric with the message *shape* (normalised, values
  stripped) so that the pattern table can be improved from production data without logging
  content.

## 2.6 Parity mode per project

Each (deployment, project) carries a `parityMode`:

| Mode | Behaviour |
|---|---|
| `full` (default) | Render the full form in jvault. Suitable for projects without Behaviours or exotic apps. |
| `assisted` | Render the form, but display a persistent notice that some validations only occur on submit. |
| `delegated` | jvault collects only vault-placed content and the required minimum, creates a stub issue, and deep-links to Jira for the rest. |

An admin-facing **parity report** inspects a project (installed apps visible via
`GET /rest/api/3/applicationrole` / marketplace app list where permitted, presence of Behaviours
is *not* detectable) and recommends a mode. Detection is heuristic; the mode is ultimately an
administrator's decision.
