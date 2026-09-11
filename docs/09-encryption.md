# 9. Encryption and key management

## 9.1 Transport

TLS 1.2 minimum, 1.3 preferred, on every hop: browser → jvault, jvault → Jira, → object storage,
→ Kafka, → KMS, → PostgreSQL. Certificate validation is never disabled, including in
non-production; a `trustAllCertificates` switch is not implemented, because every such switch
eventually reaches production. Internal service-to-service traffic uses mTLS where the platform
provides it (service mesh or SPIFFE identities).

HSTS, secure cookies, and a strict CSP on the SPA. Jira webhook endpoints verify the request
before doing anything with it (see [12.5](12-reliability.md)).

## 9.2 Application-level encryption vs storage-provider encryption

These solve different problems and we use both.

| | Storage-provider encryption (SSE-KMS, Azure CMK, volume encryption) | Application-level encryption (jvault) |
|---|---|---|
| Protects against | Lost disks, decommissioned hardware, raw media access | A compromised storage account, an over-permissioned bucket policy, a mis-scoped IAM role, a curious backend administrator, a mis-configured replication target |
| Who can read plaintext | Anyone the storage service authorises — including the storage service itself | Only a principal that can call **both** storage and KMS |
| Visible to the provider | Plaintext at the API boundary | Ciphertext only |
| Cost to us | A configuration flag | Real complexity: key management, rotation, framing, search limits |

The threat that motivates this project is *content ending up somewhere it should not*, and most
of those paths run through a storage credential rather than a stolen disk. Provider encryption
alone does not address them. So:

**jvault encrypts content before it reaches the `ContentStore`, and provider-side encryption is
additionally enabled with a different key where the backend offers it.**

Under [D2](decisions.md) this second layer is not always available: MinIO and most enterprise
object stores support SSE, a plain filesystem does not. On the filesystem route, application-level
encryption is the **only** confidentiality layer — which raises the stakes on the Phase 0
encryption spike and on volume-level encryption underneath. The storage principal and the KMS principal are
distinct, and neither can assume the other (FR-ENC-3).

## 9.3 Envelope encryption and framing

Per object version:

1. Generate a 256-bit **DEK** (`GenerateDataKey` — the KMS returns plaintext and wrapped forms).
2. Encrypt the content with AES-256-GCM in **frames** of 1 MiB.
3. Discard the plaintext DEK; store only the wrapped DEK in `content_version.wrapped_dek`.
4. Write a self-describing header, then the frames, to the object.

```
┌──────────────────────────────────────────────────────────────┐
│ magic "JVLT" │ fmt ver │ alg id │ frame size │ KEK id        │  header (authenticated
│ wrapped DEK                                                  │   via AAD, see below)
├──────────────────────────────────────────────────────────────┤
│ frame 0: nonce ‖ ciphertext ‖ tag                            │
│ frame 1: nonce ‖ ciphertext ‖ tag                            │
│ …                                                            │
│ frame n: nonce ‖ ciphertext ‖ tag   (final-frame flag set)   │
└──────────────────────────────────────────────────────────────┘
```

**Why frames rather than one GCM stream.** A single GCM ciphertext cannot be safely decrypted
incrementally: the authentication tag is only available at the end, so either you buffer the whole
object before releasing any of it — impossible for 1 GB files — or you emit unauthenticated
plaintext and hope. Framing lets us authenticate and release 1 MiB at a time, and it makes range
reads possible (decrypt only the frames the range touches).

**Two bindings, at different layers.** Frame position is bound by the library; object identity is
bound by us.

```
per-frame nonce  ← frame index and final-frame flag   (Tink, internal)
AAD              = contentRef ‖ 0x00 ‖ versionId ‖ 0x00 ‖ SHA-256(header)
```

Tink's `AesGcmHkdfStreaming` derives a per-segment key by HKDF and encodes the segment index and
a final-segment flag into each nonce, so frames cannot be reordered, duplicated or the object
truncated without detection. Our AAD adds what the library cannot know: *which object this is*.
Without it, someone with write access to the bucket could swap one object's bytes for another's
and every frame would still authenticate — the cryptography intact, the system lying.

The header carries **no separate MAC**. Its SHA-256 is bound into the AAD instead, so altering the
frame size, the key id, or the wrapped key makes every frame fail to authenticate. That is
stronger than a header MAC: it uses the same key as the payload, so a header cannot be lifted onto
a different object.

**We do not implement the frame construction ourselves.** jvault uses **Google Tink**'s
`subtle.AesGcmHkdfStreaming`, which takes a raw data key directly — composing cleanly with
envelope encryption — and is audited. This is a deliberate decision to spend a dependency instead
of a class of subtle, silent, catastrophic bugs.

**Spike resolved (implementation).** Tink streams with bounded heap and provides
`newSeekableDecryptingChannel`, which gives range-based partial decryption directly; the fallback
of decrypt-and-discard is not needed. The envelope header is skipped by an offset view over the
channel, so the object stays self-describing without disturbing the library's coordinate system.

**One consequence worth stating.** A ranged read authenticates only the frames it touches and
cannot verify the whole-object plaintext digest. Any API exposing ranged reads must say so — see
[8.5](08-storage.md).

## 9.4 Key hierarchy

```
KMS root key (per key ring)          ← never leaves the KMS/HSM
        │  wraps
    Data key (DEK), one per object version
        │  encrypts
    Content frames
```

Key rings are configured per classification and per sensitivity domain (`sec-restricted`,
`general`, `jira-tokens`, `quarantine`). A ring is the blast-radius boundary: compromising one
KEK exposes only the objects wrapped under it.

Per [D2](decisions.md) keys stay on-premises. Supported providers, in the order they are
recommended for this deployment: **HashiCorp Vault Transit** (self-hosted, primary),
**PKCS#11** (Thales Luna, Entrust nShield, Utimaco — where hardware custody is mandated, and as
Vault's own seal), and AWS KMS / Azure Key Vault, which remain implemented for deployments that
later relax the on-premises constraint but are **not used here**. The `KeyManagementService` interface is narrow —
`generateDataKey(keyRing)`, `unwrap(keyRing, wrapped)`, `rewrap(from, to, wrapped)`,
`describeKey` — which keeps the provider adapters small and testable.

## 9.5 Rotation

**The stored header goes stale, and that is fine.** After a rewrap, the wrapped key inside the
object still refers to the old KEK. The database is authoritative and the header is the
disaster-recovery fallback, so the read path takes the wrapped key from `content_version` (a
`KeyLocator` in the implementation) and uses the header's key only when recovering an object whose
metadata row is gone. The header's *hash* is still bound into the AAD either way, so it stays
authenticated even when its key field is stale.

**KEK rotation** is the cheap one, and it is the point of envelope encryption: rotating a KEK
requires re-wrapping data keys, not rewriting content. A rewrap job walks `content_version` in
batches, calls `rewrap`, and updates `wrapped_dek` and `kek_id` transactionally. A 10 TB corpus
rotates in hours with no object rewritten and no downtime. Default interval 365 days, or
immediately on suspected compromise.

**DEK rotation** happens naturally: every new version gets a new data key. Existing versions keep
theirs. Forcing DEK rotation across a corpus means rewriting every object; it is offered as an
admin operation for a confirmed key compromise, with a time estimate shown before it starts.

**Jira token keys** (`jira-tokens` ring) rotate on the same schedule but are separate, so a
content-key compromise does not yield Jira access and vice versa.

## 9.6 Separation of keys, content and configuration

Three trust domains, deliberately non-overlapping:

| Domain | Holds | Principal |
|---|---|---|
| **Content** | ciphertext objects | storage role — `PutObject`/`GetObject` on one prefix, no KMS |
| **Keys** | KEKs | KMS role — `GenerateDataKey`/`Decrypt` on named keys, no storage |
| **Configuration secrets** | Jira client secrets, Kafka credentials, DB passwords | secret-manager role — separate store, separate key |

The application holds the *union* at runtime, which is unavoidable, but each surrounding
component holds only its own: a compromised backup process, a leaked storage credential, a
snapshot of the configuration store, or an exfiltrated database dump each yield nothing usable
alone. IAM policy tests assert the separation (FR-ENC-3 AC1) rather than leaving it to a diagram.

## 9.7 Effect on search and indexing

This is the real cost of application-level encryption and it must be stated plainly:
**encrypted content is not searchable by Jira, not searchable by the storage provider, and not
searchable by jvault without additional machinery.**

Three options, in increasing order of capability and risk:

**(a) No content search — MVP default.** Metadata is searchable (project, issue type, filename
class, classification, dates, actor, ticket). Content is not. Users find content by finding the
Jira issue. For most incident and case workflows this is sufficient, and it leaks nothing.

**(b) Blind index — optional, per policy.** At ingest, normalise and tokenise the plaintext,
compute `HMAC-SHA256(indexKey, token)` for each distinct token, and store the resulting digests.
Equality and keyword search work; ranking, phrases, stemming and wildcards do not. The leak is
real and should not be glossed over: the index reveals token *frequency distribution*, which is
vulnerable to statistical analysis against a known corpus. Suitable for `CONFIDENTIAL`, not
recommended for `RESTRICTED` and above. The index key lives in its own key ring, separate from
content KEKs, so an index compromise does not decrypt anything.

**(c) Full-text index in a hardened store — release 3, opt-in.** A separate OpenSearch cluster
holding decrypted text, with its own encryption at rest, its own network isolation, and
document-level security mirroring the vault ACL. This gives real search and a real second copy of
the plaintext. It is a significant expansion of the blast radius and must be an explicit,
per-space administrative decision with its own risk acceptance.

**Previews** have the same shape as search: a preview is a derived plaintext artefact. Previews
are rendered on demand in the isolated worker (§3.3.8), cached encrypted under their own DEK with
a short TTL (default 15 minutes), and authorized identically to downloads. Preview cache entries
are purged when the content is deleted or its ACL changes.

## 9.8 Effect on recovery

Encryption changes what "restore" means, and the failure mode is total rather than partial:

- Restoring objects without the database gives you ciphertext with no wrapped keys — unrecoverable.
- Restoring the database without KMS access gives you wrapped keys you cannot unwrap — unrecoverable.
- Deleting or scheduling deletion of a KEK destroys every object under it, irreversibly, after the
  provider's waiting period.

Consequences, all mandatory in the deployment guide:

1. KEK deletion requires two approvers and uses the maximum deletion waiting period.
2. Multi-region replica keys, or a rehearsed escrow procedure, before any production data is stored.
3. The restore drill (§8.7) explicitly includes unwrapping a sampled data key — restoring bytes
   without proving you can decrypt them is not a restore.
4. A monitored alarm on KMS key-state changes, key-policy changes, and unusual `Decrypt` volume.
5. The self-describing object header (§9.3) records the KEK id, so an object recovered without the
   database can at least be *identified* and decrypted by an operator with KMS access — a partial
   safety net for the worst case.
