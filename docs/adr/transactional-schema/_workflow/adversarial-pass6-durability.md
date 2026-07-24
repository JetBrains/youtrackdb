# YTDB-382 — Adversarial pass 6: durability and crash safety (2026-06-10)

Verdict: 4 new findings — 0 BLOCKER, 1 MAJOR, 3 minor. The pass-5 resolutions
hold up well under crash-boundary replay: the F53/F58/F62 commit sequence is
crash-consistent at every instruction boundary I could construct, the F55
lazy-consult fix survives the same-name and fuzzy-checkpoint attacks aimed at
it, and the F57/F61 incomplete-tx scenarios leave nothing recovery can
misread. The one MAJOR is a seam the F55 attack uncovered underneath the
design's drop+recreate story: same-name file delete+create inside one atomic
operation never produces the FileDeleted/FileCreated record pair the decision
log's model assumes — it silently routes through an undocumented file-id
recycling branch, and the unstated reconciliation order (drops before creates
or the reverse) decides between that branch and a deterministic commit
failure.

Method: decision log read end to end, both pass-5 reports read including the
failed-attack lists; every claim below verified against the live tree
(`AbstractStorage`, `AtomicOperationBinaryTracking`, `AtomicOperationsManager`,
`AtomicOperationsTable`, `WOWCache`, `CASDiskWriteAheadLog`, `BTree`); symbol
questions (callers of `startStorageTx`, `restoreFileById`) resolved through
PSI. Each attack asks "the process dies at instruction boundary X; what does
recovery see?" against the real replay path, not the design's description of
it.

---

## U8: Same-name drop+create in one atomic operation never logs FileDeleted/FileCreated — it recycles the file id through a branch the decision log does not know exists, and the unstated reconciliation order decides between recycling and a deterministic commit failure [MAJOR]

**The scenario the design enables.** Drop an index and recreate one with the
same name in a single tx: drop+recreate during a migration, drop class `Foo`
(which drops its indexes, F4) plus recreate class `Foo` with the same
auto-named `Foo.prop` indexes, or D9's "index rename reads as drop+create"
applied to a same-name redefinition. Engine files are named by index name in
v1 (F27, D16 deferred): the dropped engine's `Foo.prop.cbt`/`.nbt`/`.ixs` and
the created engine's files collide on name. D3's reconciliation executes both
sides inside the commit's single atomic operation.

**What the machinery actually does — order A (drops applied first).**
`AtomicOperationBinaryTracking.deleteFile` records the id and stashes the
name (`deletedFiles.add` + `deletedFileNameIdMap.put`,
`AtomicOperationBinaryTracking:887`–`895`). A subsequent `addFile` with the
same name takes the recycle branch: it pulls the OLD file id back out of
`deletedFileNameIdMap`, removes it from `deletedFiles`, and marks the
`FileChanges` **`isNew = false`** (`:815`–`818`). Consequences, all verified:

- `commitChanges` logs a `FileDeletedWALRecord` only for ids still in
  `deletedFiles` (`:983`–`993`) and a `FileCreatedWALRecord` only when
  `isNew` (`:1001`–`1007`) — the recycled file produces **neither record**.
  The unit's WAL shape is just page records against the existing file id.
- The physical phase neither deletes nor creates the file (`:1082`–`1086`,
  `:1093`–`1105`); the new engine's pages overwrite the old file's pages 0, 1,
  2… in place. `addFile` resets the in-tx allocation horizon
  (`maxNewPageIndex = -1`, `:829`), so `BTree.create`'s explicit
  `allocatePageForWrite(fileId, 0/1)` (`BTree:196`–`211`) passes the
  allocation-floor check (`allocatePageForWrite:571`–`575`: floor =
  `maxNewPageIndex + 1` = 0) despite the old file being non-empty.
- The old file's length is never reclaimed: no delete, no truncate (`truncateFile`
  is the one non-WAL-safe op, F17 — not an option). A drop+recreate of a large
  index leaves the file at its old size with stale unreachable tail pages.
- Crash mid-physical-phase replays consistently: the file exists throughout
  (never deleted), so the F55 missing-file branch and its lazy consult never
  fire; page replay applies the same deltas to the same old-content baseline
  the forward path used, gated by page LSN (`restoreAtomicUnit:5697`,
  `:5775`). No crash hole — but a completely different WAL shape than the
  log's model predicts.

**Order B (creates applied first).** If reconciliation applies the create-set
before the drop-set, `addFile` finds nothing in `deletedFileNameIdMap` and
calls `writeCache.bookFileId(name)`, which **throws** on a live name
(`WOWCache:742`–`744`: "File … has already been added to the storage").
Every same-name drop+recreate commit fails deterministically. The failure is
loud and rolls back cleanly (F53's deferred publication, F16's no-physical-
write rollback), but the capability D9 promises does not work.

**Why this is a design gap, not a code curiosity.** The decision log's
structural model is wrong for this shape in three places. F16/D10 model file
ops as "deleteFile records the id; addFile books a reservation; both logged in
commitChanges" — the recycle branch does neither. D3/D9/D12 specify where
creates land relative to `lockIndexes` and the allocation loop (F34) but never
the order of **drops versus creates within reconciliation**, which is exactly
the choice that flips between order A and order B. And F55/YTDB-1099's
prerequisite-track test plan (kill-mid-physical-phase, FileCreated consult) is
built entirely on the FileCreated-record model — it cannot cover the
drop+recreate shape, because that shape has no file records to consult. The
recycle branch itself appears to have no mainline production caller today
(PSI/grep found only the in-memory engine's eager-install path and tests
referencing the machinery), so the design would route a headline pattern onto
an essentially unexercised branch without a single decision-log word about it.

**Evidence.** `AtomicOperationBinaryTracking.addFile:804`–`829` (recycle,
`isNew=false`, horizon reset), `deleteFile:882`–`897`
(`deletedFileNameIdMap`), `commitChanges:983`–`1007` (record-shape
conditions), `:1082`–`1105` (physical order: deletes then creates),
`WOWCache.bookFileId:737`–`744` (live-name throw), `addFile(String,long):972`–
`984` (different-id collision throw), `allocatePageForWrite:565`–`582` (floor
logic), `BTree.create:192`–`221` (explicit page-0/1 init on the recycled
file).

**Affected entries.** D3 (intra-reconciliation order unstated), D9 ("drop+
create" framing), D12, D15 (changed-index set drives create/drop), F16/D10
(file-op model), F27, F48 (file-count envelope: recycled re-creates add no
files), F55/YTDB-1099 (test plan blind spot).

**Resolution direction.** Pin in D3 that reconciliation applies engine/file
**drops before creates** (making the recycle branch the deterministic path for
same-name drop+recreate), and document the recycle semantics in F16's model:
no WAL file records, file id reuse, new content overlaid on the old-content
baseline, old file length retained (space reclaimed only when the name is not
recreated in the same tx). Extend the YTDB-1099 test plan with a
drop+recreate-same-name kill-mid-physical-phase scenario asserting replay
equivalence with the forward state, and an order-B regression guard (a
same-name drop+create tx must not throw from `bookFileId`). Note that the
deferred D16 (id-keyed engine file bases, YTDB-1066) dissolves this seam by
making the colliding names distinct — worth a cross-reference on both sides.

---

## U9: The F55 lazy-consult fix as worded drops three replay details the current branch depends on [minor]

The accepted F55 resolution reads: "when a page record references a missing
file, scan the buffered unit forward for a `FileCreatedWALRecord` with that
file id, materialize the file at that point …; no pending create found means a
genuinely broken WAL, throw as today." Three details of the real branch must
survive into the implementation, and the wording covers none of them:

1. **The `restoreFileById` fallback is load-bearing and must be kept.** Today's
   missing-file branch first tries `writeCache.restoreFileById(fileId)`
   (`AbstractStorage:5658`, `:5732`), which resurrects a file deleted by a
   *later already-applied unit* from its persisted negative name-id entry
   (`WOWCache:2397`–`2414`) — the established suffix-replay path
   ("Previously deleted file … new empty file was added"). "Throw as today"
   compresses [restoreFileById → throw] into [throw]; an implementer replacing
   the branch with [consult → throw] breaks deleted-file resurrection. The two
   lookups compose in either order: a name with a persisted negative entry
   re-books the **same** internal id (`bookFileId:739`–`740`), so a pending
   FileCreated for that id and the negative entry materialize identically.
2. **The non-durable skips stay first.** All three replay branches start by
   consulting `deletedNonDurableFileIds` (`:5628`, `:5638`, `:5653`); the
   consult must run after that gate. Self-consistent today because
   `commitChanges` never logs `FileCreatedWALRecord` for non-durable files
   (`:1001` — `isNew && !nonDurable`), so the scan finds nothing for them; the
   gate keeps it that way if the record-shape ever changes.
3. **Match on internal ids.** The existing branches normalize record file ids
   before use (`writeCache.externalFileId(writeCache.internalFileId(fileId))`,
   `:5676`, `:5752`) because records can carry a different storage-id in the
   high bits after backup/restore. The forward scan must compare
   `internalFileId(pageRecord.getFileId())` against
   `internalFileId(fileCreated.getFileId())` and materialize through the same
   `readCache.addFile(name, id, writeCache)` path the FileCreated branch uses
   (`:5643`–`5646`), or id-mismatched units restore inconsistently on a
   restored-from-backup storage.

Also note (from U8): the consult never fires for same-name drop+recreate units
— they carry no file records at all — so the YTDB-1099 test matrix should not
expect consultable units from that pattern.

**Affected entries.** F55, D10, YTDB-1099.

**Resolution direction.** Amend the F55 resolution text in the decision log to
the three-step branch [non-durable skip → pending-create consult (internal-id
match) → `restoreFileById` fallback → throw], and carry the three pins into
YTDB-1099's spec.

---

## U10: The commit's atomic operation opens at transaction BEGIN, so a schema tx pins WAL segment cuts for its whole body — and a stranded session pins them forever unless the reaper rolls the tx back [minor]

PSI-verified: the only production caller of `AbstractStorage.startStorageTx`
is `FrontendTransactionImpl.beginInternal:185` — the frontend tx's atomic
operation is created when the user transaction **begins**, not at commit.
`startAtomicOperation` captures `writeAheadLog.activeSegment()` and registers
the operation `IN_PROGRESS` at that segment
(`AtomicOperationsManager:109`–`122`, `AtomicOperationsTable.startOperation:366`).
Both cut bounds count `IN_PROGRESS` operations
(`getSegmentEarliestOperationInProgress:415`,
`getSegmentEarliestNotPersistedOperation:447`–`448`), and the fuzzy checkpoint
clamps its cut floor to them (`makeFuzzyCheckpoint:4329`–`4333`); the full
checkpoint refuses outright while an operation is open (`flushAllData:4509`,
per F57).

Consequence: from the moment a schema tx begins, no WAL segment at or above
its begin segment can be cut until the tx ends — through the entire user-code
body, not just the commit window F57's envelope describes. Concurrent data
commits proceed (the D7 mutex does not block them) and their WAL volume
accumulates uncut; a crash mid-body then replays a WAL whose length is
proportional to all traffic since the schema tx began (restore always starts
at `writeAheadLog.begin()`, `restoreFromBeginning:5501`). This shape is
pre-existing for any long data tx; the design makes it the recommended
migration pattern (one long schema tx instead of today's thousands of
micro-txs with cut opportunities between them) and D7/F61 explicitly
contemplate schema txs held open across arbitrary user code.

The F61 interaction is the sharper half: F61's resolution releases the **D7
mutex** on session close/reap, but the WAL pin is released only by ending the
**atomic operation**. The standard close path rolls the tx back, which does
both — the resolution just needs to say so, because a reaper that released the
mutex without driving the tx rollback would leave the operation `IN_PROGRESS`
in the table forever: segment cuts pinned, full checkpoints refusing,
unbounded WAL growth until restart, with schema DDL otherwise proceeding
normally (the visible symptom F61 fixed is gone, the invisible one remains).

**Evidence.** `FrontendTransactionImpl.beginInternal:185` (PSI caller set),
`AtomicOperationsManager.startAtomicOperation:109`–`122`,
`AtomicOperationsTable:366`/`:415`/`:440`–`457`,
`AbstractStorage.makeFuzzyCheckpoint:4311`–`4333`, `flushAllData:4509`,
`restoreFromBeginning:5501`.

**Affected entries.** F57 (envelope starts at begin, not commit), F61/D7 (reap
must end the atomic operation, not only release the mutex), D5 (long
interactive schema txs carry a WAL-retention cost beyond lock contention).

**Resolution direction.** Add one sentence to F61's D7 bullet: the
session-close/reap path must run the full tx rollback (ending the atomic
operation and releasing the WAL pin), not merely release the mutex. Extend
F57's envelope note: WAL retention and checkpoint deferral run from tx begin,
so migration guidance should keep schema txs short-lived in wall-clock terms
even though they are single commits.

---

## U11: F63's manifest is only as strong as its own write discipline; pin manifest-last emission and hard-require it at import [minor]

F63's accepted resolution verifies the import against "a manifest
(class/index/record counts emitted by export, checked by import)". Two
durability details of the manifest itself are unpinned, and both decide
whether the check actually fires when it matters:

1. **Emission order and atomicity.** The counts are only known at export end,
   so the manifest must be emitted strictly last and atomically (write to a
   temp name, fsync, rename — or as the final section of the single dump
   stream). An export interrupted mid-way must be incapable of leaving a
   well-formed manifest; otherwise a truncated bundle paired with a complete-
   looking manifest passes verification of whatever subset survived. The
   current exporter is a streaming JSON writer with no terminal marker
   (`DatabaseExport.exportSchema:449` ff.), so this is net-new behavior to
   specify, not existing behavior to inherit.
2. **Missing-manifest behavior at import.** If the import treats an absent
   manifest as "legacy export, skip verification", then the truncated-export
   case (crash before manifest emission) silently degrades to today's
   unverified import — the exact "silently incomplete" outcome F63 exists to
   prevent, now on the export side instead of the import side. The import must
   hard-fail on a missing or unparsable manifest for any export produced by a
   manifest-emitting version (a format/version field in the dump header
   distinguishes legacy bundles).

**Affected entries.** F63, D20.

**Resolution direction.** One bullet in D20: manifest is emitted last and
atomically by export; import refuses to run (or refuses to report success)
without verifying it; legacy pre-manifest dumps are distinguishable by dump
version, not by manifest absence.

---

## Attacks run that produced no new finding

- **FileDeleted + FileCreated for the same name inside one unit (the headline
  attack on the F55 lazy consult).** Structurally impossible. Within one
  atomic operation, delete-then-create of the same name takes the recycle
  branch (U8) and logs neither record; create-before-delete throws at
  `bookFileId` (`WOWCache:742`). Across units, the forward path's name
  uniqueness (`bookFileId` throws on a live name; file-creating commits are
  serialized under `stateLock.writeLock`) guarantees a FileCreated's name is
  never live at its replay point unless an earlier record in the same WAL
  already deleted it — which strict in-order replay applies first. The
  collision I set out to construct (lazy consult materializing a file whose
  name is still held by a not-yet-replayed FileDeleted in the same unit, which
  would throw "already exists but has different id", `WOWCache:977`–`984`)
  cannot be reached.
- **Unit replayed from a fuzzy-checkpoint midpoint with a missing prefix.**
  `restoreFrom` creates a unit list on first sight of any
  `OperationUnitRecord` (`computeIfAbsent`, `:5556`–`5560`) and replays the
  suffix at the end record — so prefix-less replay is real. It is safe, and
  stays safe under the F55 fix: (a) the operation remains `IN_PROGRESS` until
  `commitChanges` returns, i.e. until the **entire physical phase** completes
  (`AtomicOperationsManager:341`–`349`), and both cut bounds count
  `IN_PROGRESS`/`COMMITTED` (`AtomicOperationsTable:447`), so a unit's start
  segment is only ever cut after its files exist and its pages are applied;
  (b) dirty pages pin the unit's start segment until flushed
  (`WOWCache.updateDirtyPagesTable:917`–`943`, `makeFuzzyCheckpoint:4314`);
  (c) file records trail page records inside the unit, so any surviving
  page record's FileCreated also survives — the forward scan never needs the
  cut prefix; (d) re-application is idempotent via the page LSN gates
  (`:5697`, `:5775`) and the by-name/by-id existence guards (`:5632`,
  `:5642`).
- **Segment cut racing the physical phase (end record durable, files not yet
  created).** Foreclosed twice over: the operation is still `IN_PROGRESS` in
  the table through the whole physical phase (persist/commit transitions fire
  only after `commitChanges` returns, `AtomicOperationsManager:341`–`352`),
  and during a schema commit the fuzzy checkpoint cannot even start
  (`makeFuzzyCheckpoint:4284` takes `stateLock.readLock`; D19 holds the write
  lock).
- **F57's populated-class rejection at commit time.** The bound check runs
  inside the open atomic operation; rejection throws into the rollback path,
  which never reaches `commitChanges` (`AtomicOperationsManager:320`, assert
  `:338`–`341`) — no end record, no physical writes. Page-op records already
  flushed mid-operation (engine-create pages at component boundaries) are
  discarded by recovery because the unit has no end record
  (`restoreFrom:5538`–`5546` applies only on `AtomicUnitEndRecord`). Crash
  *during* the check is the same case. Clean in both arms (reject or accept),
  whatever Phase 1 decides.
- **F61 kill mid-schema-tx: what is in the WAL from the incomplete tx.**
  Nothing replayable. The atomic operation opens at begin (U10) but
  `AtomicUnitStartRecord` emission is lazy (`walUnitStarted`,
  `emitWalUnitStart` on first flushed record, `commitChanges:989`/`1002`), and
  under D1 the tx body performs no component operations — all structural and
  record work lands inside the commit window. A mid-body kill leaves either no
  records at all or a start-only fragment with no end record; recovery skips
  it. Verified that nothing in the new design logs structural records outside
  the tx's single atomic operation: the six de-guarded self-commit paths
  (F3/F4/F21/F26/F46) all become in-memory metadata mutations during the body.
- **F53/F58/F62 commit-sequence crash walk.** Every boundary lands in one of
  two recovery classes. Before the end record becomes durable (reconcile,
  patch, `lockIndexes`, position loop, `commitEntry` serialization,
  `commitIndexes`, the WAL phase of `commitChanges`): the unit has no end
  record, recovery discards it, the disk is byte-for-byte pre-tx, and all
  deferred in-memory state died with the process — nothing to unwind. After
  the end record (physical phase, registry publication, promotion, overlay
  publish, trailing `forceSnapshot`): the WAL unit is complete; recovery
  replays it (F55 fix, plus U8's recycle shape which has no missing-file
  window), and the next open rebuilds collections/engines from the config
  records and the schema from the per-class records — all written by page
  records **inside the same unit**, so the rebuilt registries cannot disagree
  with what replay applied. The post-`endTxCommit` in-memory publication steps
  are crash-irrelevant (process memory is gone; durable state is already
  complete). A non-crash publication failure leaves shared in-memory state
  stale until restart — that is F52/F53 concurrency territory, with no
  durability content.
- **F59 root-record write atomicity across paths.** The root entity, the
  per-class records, the index-manager record, and the per-index entities all
  ride the one commit atomic operation in every path: a normal schema tx is
  one unit; D18 genesis is two txs but each is internally one unit, and the
  inter-tx window (classes committed, users absent) is the operator-deletes
  case pass 5 already closed; D20 import is many txs, each one unit, with the
  manifest (F63 + U11) owning cross-tx completeness. No path serializes the
  root record in a different unit than the class records whose link-set/global-
  property changes dirtied it.
- **Per-index entity vs engine id at restart.** The per-index entity does not
  persist `indexId` (verified: no map/stream field; `IndexAbstract` resolves
  the engine at load by name via `storage.addIndexEngine`/
  `loadExternalIndexEngine`, `:196`/`:240`/`:305`); the durable engine id
  lives in `IndexEngineData` inside the storage configuration, written through
  the same atomic operation during reconciliation. No F58-style
  patch-before-serialize obligation exists on the index side.
- **Two units touching the same file name interleaved in the WAL.** File-
  creating commits hold `stateLock.writeLock` (D19; today's structural
  self-commits likewise), and data commits hold the read lock for their whole
  logging window — so a file-creating unit's records are contiguous and
  serialized against any other file-creating unit. Per-unit buffering in
  `restoreFrom` (`operationUnits` map) would handle interleaving anyway.
- **Rollback flag on `AtomicUnitEndRecord`.** `commitChanges` is unreachable
  on rollback (`AtomicOperationsManager:320`/`:338`), so no end record with
  meaningful rollback semantics is ever logged for the design's commit path;
  `restoreFrom` applying units unconditionally on their end record is
  consistent with that invariant.
