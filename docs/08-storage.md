# 8. Storage backends

> **[D2](decisions.md):** content stays on customer infrastructure. Filesystem and CMIS are the
> MVP primaries; the **S3 adapter ships with them** because S3-compatible on-premises object
> storage (MinIO, Ceph RGW, NetApp StorageGRID, Dell ECS) is the most likely production target —
> the same adapter, pointed at an internal endpoint. Azure Blob follows in a later release unless
> Azure Stack is in play.

## 8.1 The SPI

```java
public interface ContentStore {
    Capabilities capabilities();

    /** Write bytes at a deterministic key. Must be safe to repeat with identical input. */
    StoredObjectRef put(PutRequest request) throws StorageException;

    /** Open a stream, optionally a byte range. */
    ContentStream open(StoredObjectRef ref, ByteRange range) throws StorageException;

    /** SOFT marks for later purge where supported; HARD removes bytes. */
    void delete(StoredObjectRef ref, DeleteMode mode) throws StorageException;

    /** Cheap existence/size/etag probe used by the integrity sweeper. */
    Optional<ObjectStat> stat(StoredObjectRef ref);

    /** Health and credential validity, called at startup and on a schedule. */
    HealthStatus health();
}

public record Capabilities(
    boolean nativeVersioning,
    boolean serverSideEncryption,
    boolean immutabilityLock,       // S3 Object Lock, Azure immutability policies
    boolean rangeReads,
    boolean conditionalWrites,      // if-none-match / if-match
    boolean checksumOnWrite,        // provider-verified checksum
    boolean softDelete,             // recycle bin / soft-delete retention
    long    maxObjectBytes
) {}
```

`PutRequest` carries the already-encrypted stream, the object key, the media type (generic
`application/octet-stream` for encrypted objects) and a minimal, non-sensitive metadata map.

## 8.2 Capability negotiation, and why jvault implements the differences itself

The four backends differ substantially:

| Capability | Filesystem | CMIS 1.1 | Amazon S3 | Azure Blob |
|---|---|---|---|---|
| Native versioning | no | yes (major/minor, document model) | yes (bucket versioning) | yes (blob versioning) |
| Server-side encryption | OS/volume level | repository-dependent | SSE-S3 / SSE-KMS | Microsoft- or customer-managed keys |
| Immutability / WORM | no | retention where the repository supports it | Object Lock | immutability policies |
| Range reads | yes | binding-dependent | yes | yes |
| Conditional writes | via atomic rename | limited | yes (`If-None-Match`) | yes (ETag conditions) |
| Soft delete | no | recycle bin, repository-dependent | versioning + delete markers | soft delete for blobs |
| Practical max object | filesystem limit | repository-dependent, often modest | 5 TB | ~190 TB block blob |
| Consistency | strong (local) | repository-dependent | strong read-after-write | strong |

**Decision: jvault provides versioning, integrity and retention itself, uniformly, and uses
native backend features only as defence in depth.**

The reason is behavioural consistency. If versioning meant "S3 version ids" on one route and
"CMIS minor versions" on another and "nothing at all" on the filesystem route, then every feature
above it — version history, restore, retention, legal hold, the audit trail — would behave
differently depending on where an administrator happened to point a policy. That is the kind of
difference that produces a compliance finding years later.

So: a new version is a **new object at a new key** (`{tenant}/{contentRef}/{versionId}`), recorded
in `content_version`. Native versioning stays enabled where available, because it protects against
mistakes below our layer, but jvault never depends on it.

Capabilities are still consulted where they change *behaviour that must not be faked*:

- `maxObjectBytes` — a route whose backend cannot hold a file is rejected at upload, not at write.
- `immutabilityLock` — a route claiming `RECORDS_MANAGED` retention requires it, or configuration
  validation fails. We will not pretend to offer WORM on a filesystem.
- `rangeReads` — absent, jvault streams from the beginning and discards the prefix, and logs a
  performance warning; correctness is preserved, throughput is not.
- `conditionalWrites` — absent, the writer falls back to write-then-verify via `stat`.

## 8.3 Backend notes

**Filesystem.** Under D2 this is a **production backend, not a development convenience**, so its constraints are load-bearing. Writes go to
a temporary file and are moved into place with an atomic rename, so a crash cannot leave a
partially-written object visible. Directory fan-out by the first two hex pairs of the content ref
keeps directory sizes sane. Requires a shared, POSIX-consistent volume when more than one node
runs. This is the single largest operational risk introduced by D2 and needs a named owner: NFS
locking semantics have caused more data-integrity incidents than almost anything else in this
category. Recommended order of preference for the shared volume: an S3-compatible object store
via the S3 adapter (avoiding the problem entirely) > a clustered filesystem with proper locking
(GFS2, CephFS) > NFSv4.1 with a verified locking configuration > single-node with a documented
availability ceiling.

**CMIS 1.1.** Apache Chemistry OpenCMIS, browser binding by default (JSON over HTTP; simpler to
proxy and debug than AtomPub). Objects are created as `cmis:document` with a content stream; the
document type and folder path come from route configuration. CMIS repositories are typically
records-management systems with their own retention, so the `RECORDS_MANAGED` retention mode hands
retention to the repository and jvault records that it has done so — jvault will not attempt to
delete an object the repository owns, and a `delete` in that mode raises
`retention-owned-by-repository` rather than silently doing nothing. Authentication varies by
repository (basic, OAuth bearer, Kerberos); the adapter takes a pluggable authentication provider.

**Amazon S3.** SDK v2 async client, multipart upload above 8 MiB, `x-amz-checksum-sha256` on write
so the service verifies the ciphertext digest independently of ours. SSE-KMS enabled with a
*different* key from the content KEK, giving two independent layers. Bucket policy denies
unencrypted transport and denies `s3:GetObject` to every principal except jvault's role. Versioning
and Object Lock on where the route requires them.

**Azure Blob Storage.** `azure-storage-blob` with block blobs, staged blocks for large uploads,
`Content-MD5` per block plus our own SHA-256 end to end. Customer-managed keys for service-side
encryption; blob versioning and soft delete enabled; immutability policies where the route requires
WORM. Authentication via workload identity (federated) rather than account keys — account keys are
a standing credential with full container access and should not exist in this system.

## 8.4 Versioning, and the attachment-immutability problem

Jira attachments cannot be edited — only added and deleted (§0.7). For Jira-placed attachments,
"editing" is therefore delete-and-re-add, and Jira's own history shows a deletion.

For vault-placed attachments jvault holds **true version history**: a new version is a new object,
the `contentRef` and therefore the Jira link are unchanged, and the version list is queryable and
individually authorized. This is a genuine improvement over Jira's behaviour and is worth
surfacing in the UI as such — it is one of the clearer user-visible benefits of externalisation.

Retention of old versions is per route: `keepAll`, `keepLast: N`, or `keepFor: P90D`. Legal hold
pins every version regardless.

## 8.5 Integrity

- SHA-256 of the **plaintext** is computed while streaming in and stored in `content_version`. It
  is the authoritative content identity.
- SHA-256 of the **ciphertext** is stored too, so the integrity sweeper can verify objects without
  decrypting them — and therefore without touching KMS, which matters when sweeping millions of
  objects.
- Every read verifies the authenticated-encryption tag per frame (§9.3) and the plaintext digest
  at the end of the stream. A range read verifies the frames it touches; the whole-object digest
  cannot be checked on a partial read, and the API documents that.
- A background sweeper re-verifies a sampled fraction of objects (default 1% per week, and 100% of
  anything not read in 12 months) and alerts on mismatch or absence. Mismatch sets
  `stored_object.state = 'MISSING'` and the part reports `content-integrity-error` rather than
  returning bytes.

## 8.6 Retention, deletion and legal hold

Three-stage lifecycle:

```
AVAILABLE → SOFT_DELETED (retention window) → PURGED (tombstone retained)
```

- Soft delete is reversible by a user with `DELETE` (or an admin) for the retention window.
- Purge is permanent, removes bytes from the backend, and leaves a tombstone row so the link
  returns `410 Gone` with an explanation rather than `404`, which would be indistinguishable from
  "never existed" and confusing for someone following an old Jira link.
- **Legal hold** blocks purge on every path, including the admin purge endpoint and the retention
  job. This is asserted by a test that attempts purge through every code path (FR-ST-6 AC1).
- Deleting a Jira issue does **not** purge content. It moves the ticket to `JIRA_ORPHANED` and
  starts the retention clock. Accidental Jira deletions happen; irreversible cascading deletes
  from them would be indefensible.
- Purging content does not delete the Jira issue. The remote links are removed and the surrogate
  is rewritten to a tombstone note, so the Jira issue stays coherent.

## 8.7 Backup and recovery

Three things must be restored together, and a restore is only valid if all three are consistent:

1. **PostgreSQL** — metadata, wrapped data keys, ACLs, audit. Continuous archiving (WAL) with
   point-in-time recovery. RPO ≤ 15 min.
2. **Object storage** — under [D2](decisions.md) there is no provider-level geo-replication to
   lean on, so durability is the customer's to arrange: route-level `replicateTo` to a second
   site, plus the store's own erasure coding or replication. Versioning protects against
   overwrite; site replication against site loss.
3. **KMS** — without the KEK the backups are noise. With an on-premises Vault/HSM this means
   Vault's own backup and unseal-key custody, or HSM key backup to a second appliance — and it
   means the escrow procedure below is **mandatory**, not conditional. Multi-region replica keys where the provider
   supports them; otherwise a documented, rehearsed key-escrow procedure. **Key deletion is the
   one truly unrecoverable event in this system**, so KEKs are configured with the maximum deletion
   window and deletion requires two approvers.

Restore order: KMS access → PostgreSQL → verify wrapped-key unwrap on a sample → object storage
→ integrity sweep over the restored window → release.

The restore drill (NFR-5, FR-ST-7) runs quarterly in a clean environment and is timed. A backup
procedure that has not been restored is a hypothesis, not a backup.

## 8.8 Migration between backends

Routes change: a project is reclassified, a region closes, a CMIS repository is retired. Migration
is a first-class operation because `contentRef` is stable and the link never changes:

1. Route is marked `DRAINING`; new writes go to the new backend.
2. A migration worker copies objects, verifies the ciphertext digest, writes a new
   `stored_object` row, and flips `content_version.storage_object_id` transactionally.
3. Old objects are soft-deleted after a confirmation window, then purged.

At no point does a user-visible link change, and at no point is content decrypted — the
migration moves ciphertext, so it needs storage credentials but not KMS access.
