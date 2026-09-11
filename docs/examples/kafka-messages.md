# Example Kafka messages and processing rules

## 1. Security alert → incident ticket with externalised narrative and evidence

**Topic** `security.alerts.v1`, **key** `alert-8f21c` (keyed so revisions of one alert stay
ordered on a single partition).

**Headers**

```
X-Correlation-Id: 01J8ZQK5M3T4X9YV2A0B7CDEFG
X-Schema-Subject: security.alerts.v1-value
X-Producer:       edr-bridge/2.4.1
```

**Value**

```json
{
  "alert": {
    "id": "alert-8f21c",
    "revision": 1,
    "severity": "HIGH",
    "title": "Unexpected outbound connection from build agent",
    "narrative": "Host build-agent-07 opened a TLS connection to 203.0.113.44:443 at 09:41Z.\nProcess: /opt/ci/runner (pid 21884).\nCredential material observed in the process environment: AWS_SECRET_ACCESS_KEY=…",
    "source": { "hostname": "build-agent-07", "sensor": "edr-eu-3" },
    "evidence": [
      { "filename": "capture-0941.pcap", "contentType": "application/vnd.tcpdump.pcap", "data": "1MOyoQIABAAA…" }
    ]
  },
  "reporter": { "email": "soc-analyst@example.com", "displayName": "SOC Analyst" },
  "detectedAt": "2026-09-11T09:41:12Z"
}
```

**Processing**

| Step | Outcome |
|---|---|
| Dedupe | `dedupeKey = alert-8f21c\|1`. Not seen → proceed. |
| Schema | Valid against `security.alerts.v1-value`. |
| Mapping | `summary = "[HIGH] Unexpected outbound connection from build agent"`; `priority = 2`; `customfield_10010 = "build-agent-07"`. |
| Placement | Policy `sec-incident-description` → `description` is `EXTERNAL`, classification `RESTRICTED`. Evidence attachments → `EXTERNAL`. |
| Content | Narrative and pcap encrypted (AES-256-GCM framed, DEK wrapped by `sec-restricted` KEK) and written to route `obj-dc1-restricted`. |
| Egress guard | Jira payload contains only summary, priority, custom field and surrogate. Hash check passes. Classifier finds no secret material in the payload. |
| Jira | Issue `SEC-4471` created by the integration identity. |
| Links | Remote link `globalId=jvault:content:01J8ZQ…` for the narrative, one per evidence file. |
| Property | `jvault.origin = {"channel":"KAFKA","actor":{"type":"EXTERNAL","id":"soc-analyst@example.com"},"correlationId":"01J8ZQK5…"}`. |

**What Jira ends up holding**

```
Summary:     [HIGH] Unexpected outbound connection from build agent
Priority:    High
Source host: build-agent-07
Description: Incident details are stored in jvault and are not visible in Jira.
             Classification: RESTRICTED. Open: https://jvault.example.com/c/01J8ZQ…
Web links:   Incident narrative · Evidence: capture-0941.pcap
```

The AWS secret in `alert.narrative` never reaches Jira, Jira's index, jvault's logs, the trace,
or — if processing had failed — the DLQ.

---

## 2. Revision of the same alert (update, not a new ticket)

Same topic and key, `revision: 2`, narrative amended.

`dedupeKey = alert-8f21c|2` — a *different* key, so this is not a duplicate. The mapping declares
`correlation.parentKey: "$.alert.id"`, so jvault resolves the existing ticket for `alert-8f21c`
and applies an **update**: a new content version for the narrative (the link is unchanged), and a
Jira comment noting the revision. No second issue.

> Design note: whether a revision updates or creates is a mapping decision
> (`onExistingKey: UPDATE | CREATE | IGNORE`), not a system-wide rule. Incident feeds want
> `UPDATE`; audit-event feeds usually want `CREATE`.

---

## 3. Replay after a consumer crash

The consumer is SIGKILLed after `POST /rest/api/3/issue` returns 201 but before the state row is
updated and the offset committed.

On restart the partition is re-read from the last committed offset:

1. `kafka_message_state` has a row at state `CONTENT_STORED` for `(topic, partition, offset)` —
   not terminal, so this is a resume, not a redelivery.
2. `ticket_record` for that dedupe key is in state `AMBIGUOUS` (the outbox row was `IN_FLIGHT`).
3. The reconciler runs the ambiguity protocol (§12.4): it searches Jira for issues created by the
   integration identity in the ambiguity window and matches `jvault.origin.correlationId`.
4. `SEC-4471` matches → the ticket is adopted, state `ACTIVE`, processing continues from `LINKED`.
5. Offset committed. **One issue exists.**

---

## 4. Schema violation → DLQ without payload

Producer emits `severity: "SEV-1"` where the schema enumerates `CRITICAL|HIGH|MEDIUM|LOW`.

Validation fails before any mapping, any content write and any Jira call. The original message is
written to the encrypted quarantine; the DLQ record carries no payload:

```json
{
  "correlationId": "01J8ZR2P7Q8W…",
  "source": { "topic": "security.alerts.v1", "partition": 1, "offset": 550312 },
  "mappingId": "sec-incident-v2",
  "failure": { "stage": "SCHEMA_VALIDATION", "code": "ENUM_VIOLATION", "attempts": 1 },
  "fieldErrors": [ { "field": "alert.severity", "code": "ENUM_VIOLATION" } ],
  "quarantineRef": "01J8ZR2P7Q8W…",
  "occurredAt": "2026-09-11T09:44:01Z"
}
```

`fieldErrors` names the *field* and the *violation class*; it does not include the offending
value, because the offending value is source data and may be sensitive. An operator with the
`jvault-incident-responder` role can read the quarantined original through the API, and that read
is audited.

---

## 5. Jira rate limit during a burst

One hundred alerts arrive in ten seconds. The outbox dispatcher's global token bucket keeps
jvault under Jira's burst limits; Jira nonetheless returns `429` with `Retry-After: 8` and
`RateLimit-Reason: jira-quota-global-based`.

- The outbox row is rescheduled to `now + 8s`; the attempt does **not** count against the
  error-retry budget (FR-KAF-5 AC1).
- The Kafka message stays in `CONTENT_STORED`; content is already safely stored and encrypted.
- No DLQ, no duplicate, no operator involvement.
- `jvault_jira_throttle_seconds_total` rises, which is the signal to review the point budget.

---

## 6. Non-sensitive feed with everything in Jira

**Topic** `build.failures.v1`, mapping `build-failure-v1`, no external placement at all.

```json
{ "build": { "id": "ci-88213", "pipeline": "platform/api", "branch": "main",
             "failedStage": "integration-test", "logUrl": "https://ci.example.com/88213" },
  "triggeredBy": { "email": "dev@example.com" } }
```

Everything maps to ordinary Jira fields; `placement` resolves to `JIRA` from the global default,
so no content store, no encryption, no links. The same code path, the same dedupe guarantees, the
same audit trail — this is what "one behaviour, three entry points" buys: the non-sensitive case
is not a separate system.
