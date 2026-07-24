<!--
MANIFEST
dimension: bugs-concurrency
prefix: BC
target: Track 2, Step 1 — per-class schema records (commit 9eaeb3781e)
iteration: 1
counts: { blocker: 0, should-fix: 1, suggestion: 2 }
evidence_base: present
cert_index: present
flags: []
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-should-fix--stale-temp-rid-leaks-into-the-shared-schemaclassimpl-on-a-failed-schema-save"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:684-695
    cert: C1
    basis: psi+trace
  - id: BC2
    sev: suggestion
    anchor: "#bc2-suggestion--tostream-mutates-shared-state-and-writes-records-under-the-read-lock"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:662-705
    cert: C2
    basis: psi+trace
  - id: BC3
    sev: suggestion
    anchor: "#bc3-suggestion--fromstream-loads-each-linked-record-with-no-null-or-dangling-link-guard"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:540-546
    cert: C3
    basis: trace
-->

## Findings

### BC1 [should-fix] — Stale temp RID leaks into the shared `SchemaClassImpl` on a failed schema save

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 684-695)
- **Issue**: `toStream` binds a new class's temporary record id onto the shared,
  committed `SchemaClassImpl` instance *before* the enclosing transaction commits.
  When that commit fails and rolls back, the temp id is never promoted to a
  persistent position, but `SchemaClassImpl.recordId` keeps pointing at the dead
  temp id. The next save takes the `else` branch (`getRecordId() != null`) and
  calls `session.load(c.getRecordId())` on a record that never persisted, so the
  schema save fails permanently until the schema is reloaded from disk.
- **Evidence**: `saveInternal` (`SchemaShared.java:874`) runs `toStream` inside its
  own `executeInTx`. Inside `toStream`, the new-class arm
  (`SchemaShared.java:684-690`) does `classRecord = session.newInternalInstance();
  c.setRecordId(classRecord.getIdentity());` — `c` is one of `classes.values()`
  (line 672, 682), i.e. the live shared impl, not a tx-local copy (the tx-local
  copy is Track 3). `newInternalInstance` (`DatabaseSessionEmbedded.java:974-991`)
  builds an `EntityImpl` over a fresh `ChangeableRecordId` (temp position
  `COLLECTION_POS_INVALID`) and registers it `CREATED`. The persistent position is
  written into that same `ChangeableRecordId` in place only at a *successful*
  commit. `executeInTxInternal` → `commitInternal` rolls back on any
  `RuntimeException` (`DatabaseSessionEmbedded.java:3187-3213`); rollback does not
  revert the plain `recordId` field on the shared impl. On the next
  `saveInternal`, line 692 (`session.load(c.getRecordId())`) loads a temp id that
  has no record. The happy path the new `PerClassSchemaRecordTest` exercises never
  hits this; there is no failed-save regression test.
- **Refutation considered**: Checked whether the field is reverted on rollback —
  it is a bare in-memory field on the shared `SchemaClassImpl`, mutated
  unconditionally inside the tx body, with no rollback hook (PSI:
  `setRecordId` is called only at `SchemaShared.java:559` (load rebind) and
  `:689`). Checked whether `toStream` runs before the tx so a failure is pre-mutation
  — no, `saveInternal` wraps `toStream` in `executeInTx`, so the mutation precedes
  the commit. Checked whether a retry path reloads and rebinds — only `reload()`
  rebinds via `fromStream` (`:360`, `:440`), and `saveInternal` does not reload on
  failure, it rethrows.
- **Suggestion**: Do not mutate the shared impl until the write is known durable.
  Bind `recordId` from a tx commit-success callback, or capture the new id into a
  local and only `setRecordId` after `executeInTx` returns normally; alternatively
  guard the `else` reuse branch so a non-persistent `recordId`
  (`!recordId.isPersistent()`) is treated as "allocate a fresh record" rather than
  `load`. Add a failed-save regression test (force the inner tx to throw, then
  re-save and assert the class still serializes).

### BC2 [suggestion] — `toStream` mutates shared state and writes records under the read lock

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 662-705)
- **Issue**: `toStream` is declared to take `lock.readLock()` (line 663) yet
  performs writes that mutate shared and persistent state: `c.setRecordId(...)`
  (689), `classLinks.add(...)` / `classLinks.remove(...)` (690, 701), record
  allocation (688), and `droppedRecord.delete()` (703). The read lock advertises a
  read-only contract that the body violates. It is not a live race today, but it is
  a latent footgun: if any future caller invokes `toStream` while holding only the
  read lock (or under the inverted model in Tracks 3/4), the shared `recordId`
  writes and link-set mutations become unsynchronized.
- **Evidence**: PSI find-usages on `SchemaShared.toStream` returns exactly one
  caller, `saveInternal` (`SchemaShared.java:874`), which is reached only while the
  schema write lock is held (`releaseSchemaWriteLock` →
  `saveInternal` at `modificationCounter == 1`, inside the
  `acquireSchemaWriteLock` region). Acquiring the read lock while already holding
  the write lock of the same `ReentrantReadWriteLock` is the supported reentrant
  downgrade-acquire, so today the body runs effectively exclusive and there is no
  concurrent `toStream`. Hence "suggestion", not a present bug.
- **Refutation considered**: Confirmed via PSI there is no second caller and no
  concurrent read-path invocation (`metadata:schema` loads the persisted record,
  it does not call `toStream`). Confirmed `readLock`-while-holding-`writeLock` on
  `ReentrantReadWriteLock` does not deadlock.
- **Suggestion**: Either take the write lock in `toStream` to match what the body
  does, or add a `assert lock.isWriteLockedByCurrentThread()` precondition (the
  pattern already used in `nextCollectionIndex`, `:883`) so the real invariant —
  "callers hold the write lock" — is documented and enforced rather than masked by
  a read-lock that lies about the contract.

### BC3 [suggestion] — `fromStream` loads each linked record with no null-or-dangling-link guard

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 540-546)
- **Issue**: `fromStream` walks the root's `"classes"` link set and does
  `storedClasses.add(session.load(link.getIdentity()))` for every link with no
  guard. A dangling link (a link whose target record is absent) makes `session.load`
  throw and aborts the whole schema load, which surfaces as a database that will not
  open. The mirror it copies, `IndexManagerAbstract.load`
  (`IndexManagerAbstract.java:191-205`), has the same shape, so the format is
  self-consistent; the concern is the absence of any defensive handling for a
  partially-written or externally-damaged link set, which a foundational on-disk
  format change makes more consequential.
- **Evidence**: Drops are atomic within a single save — `toStream` does
  `classLinks.remove(rid)` then `droppedRecord.delete()` in the same tx
  (`:701-703`), so a *cleanly committed* root never links a deleted record; that is
  why this is a suggestion, not a confirmed bug. The exposure is to a link set left
  inconsistent by a path outside this commit's atomic unit (mixed-version bytes,
  manual record surgery, a future non-atomic writer).
- **Refutation considered**: Verified the in-scope create/drop paths keep the link
  set and the per-class records mutually consistent inside one atomic transaction,
  so no in-scope flow produces a dangling link. Verified the `classLinks == null`
  case is already handled (`:542`). The residual risk is strictly out-of-the-happy-
  path and matches the pre-existing index-manager behavior.
- **Suggestion**: Optional — if a linked record fails to load, log the offending
  RID and either skip it or raise the same `ConfigurationException` /
  export-import redirect the version gate uses, so a damaged link set yields a
  diagnosable message rather than a raw load exception. Out of scope to fix here if
  the team wants to keep byte-for-byte parity with `IndexManagerAbstract.load`.

## Evidence base

#### C1 — Stale temp RID leaks on failed save (basis: psi+trace) [CONFIRMED]

Survived refutation: shared-impl `recordId` is set inside the tx body before
commit and is never reverted on rollback (PSI: `setRecordId` callers =
`SchemaShared.java:559`, `:689` only); the reuse branch then `load`s a
non-persistent id. Backed by the `newInternalInstance` → `ChangeableRecordId`
in-place-promotion trace and the `commitInternal` rollback path
(`DatabaseSessionEmbedded.java:3187-3213`).

#### C2 — Read-lock-while-mutating in `toStream` (basis: psi+trace) [CONFIRMED as latent-only]

Survived refutation as a latent concern, not a present race: PSI find-usages gives
`saveInternal` as the sole caller, reached under the schema write lock, so the
reentrant read-lock acquire runs exclusive today. No deadlock
(`ReentrantReadWriteLock` permits read-acquire while holding write). Downgraded to
suggestion on that basis.

#### C3 — Unguarded linked-record load in `fromStream` (basis: trace) [CONFIRMED as out-of-happy-path]

Survived refutation only for the out-of-band/damaged-link-set case: the in-scope
create/drop flow keeps the link set and per-class records consistent inside one
atomic tx, and the `null` link set is handled. Matches `IndexManagerAbstract.load`
shape, so the residual exposure is pre-existing and format-wide; suggestion only.

### Reference-accuracy note

All caller/usage claims (`SchemaShared.toStream`, `SchemaClassImpl.toStream`/
`getRecordId`/`setRecordId`) are PSI-backed via mcp-steroid find-usages against the
open `transactional-schema` project (`ReferencesSearch`, project scope). No grep
fallback was used for any load-bearing reference claim.
