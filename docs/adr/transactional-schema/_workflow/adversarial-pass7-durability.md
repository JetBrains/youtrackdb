# YTDB-382 — Adversarial pass 7: durability and crash safety (2026-06-10)

Verdict: 5 new findings — 0 BLOCKER, 4 MAJOR, 1 minor. This pass targeted the
text the pass-5/6 resolutions wrote into the D entries that no pass has
attacked: F53's deferred registry publication composed against the structural
machinery it defers, F67's id-keyed engine files pulled into v1, F66's
commit-time re-derivation source, the corrected F74 pin model and the D7
reaper protocol built on it, and F75's manifest discipline. The common root
this round: three accepted fixes (F53, F66, F71/F74) each name a mechanism
("commit-local references", "the record-operation set the tx retains", "reap
releases the pins") whose load-bearing input the live machinery does not
provide — id allocation reads the deferred registries, the retained operation
set has no field values for early-flushed records, and the tsMin holder cannot
be released from the reaper's thread.

Method: decision log read end to end, all four prior reports read including
both failed-attack lists; every claim verified against the live tree
(`AbstractStorage`, `AtomicOperationBinaryTracking`, `AtomicOperationsManager`,
`AtomicOperationsTable`, `WOWCache`, `FrontendTransactionImpl`,
`LinkCollectionsBTreeManagerShared`, `DatabaseExport`); reference-accuracy
questions (callers of `startToApplyOperations`, `resetTsMin`,
`registerCollection`) resolved through PSI find-usages. Each attack asks "the
process dies at instruction boundary X (or the routine failure fires at X);
what does recovery, or the next commit, see?" against the real code, not
the design's description of it.

---

## U12: Structural-id allocation reads the very registries F53 defers — every schema commit creating two or more collections or engines allocates duplicate ids, and F67's collision-free-base premise rides on the result [MAJOR]

**The seam.** F53 (folded into D3) defers all shared registry publication
("collections array, engine maps, config caches") to the post-`commitChanges`
success path, and has the commit's later steps resolve new structures through
commit-local references. But both structural-id allocators are *reads of those
same registries*, and they run early in reconciliation, not late:

- A new collection id is the first null slot of the shared `collections`
  array (`AbstractStorage.doAddCollection:4991`–`4997`); the array is updated
  only by `registerCollection` (`:5026`), which PSI confirms is called from
  exactly `doAddCollection` and the open-time `createCollectionFromConfig`
  (`:4941`). Defer `registerCollection` and the scan returns the same slot for
  every collection the commit creates.
- A new engine id is `indexEngines.size()` (`addIndexEngine:2786`); the list
  grows only at `indexEngines.add(engine)` (`:2812`), the exact registration
  F53 defers. Same staleness, same duplicate.

**What the duplicate does.** One class create allocates 8 collections in one
commit (F49), so this is the design's standard path, not a corner. With
duplicate collection id N, the second collection's
`linkCollectionsBTreeManager.createComponent(atomicOperation, N)` (`:5031`)
builds an id-named file (`FILE_NAME_PREFIX + collectionId`,
`LinkCollectionsBTreeManagerShared:92`) whose name collides with the first's;
`AtomicOperationBinaryTracking.addFile` throws on a same-name re-add within
one operation (`newFileNamesId` guard, `:808`–`811`). The commit fails
deterministically, rolls back cleanly (F16), and retries fail identically.
Engines fail the same way under F67's v1 id-keyed bases: duplicate engine id N
derives the same `N.cbt`/`N.nbt`/`N.ixs` names, and the second `addFile`
throws. The failure is loud, which is the only reason this is not a BLOCKER —
but two silent variants lurk one implementation choice away. If an implementer
"fixes" the throw by keeping registration eager for allocation's sake, F53's
phantom-registration hole reopens wholesale. And without F67's name collision
(or if only the config write survives a partial fix), the duplicate-id config
records overwrite each other inside the unit (`updateCollection` is keyed by
collection id, `:5028`–`5029`), leaving a durable config with one entry for
two collections; at reopen, one collection's files exist with no registry
entry, and records carrying its collection id resolve to the wrong collection.

**A fourth registry F53's list misses.** `createComponent` also publishes
into `fileIdBTreeMap` (`LinkCollectionsBTreeManagerShared:97`) during
reconciliation. A post-reconciliation commit failure (the routine D13
uniqueness abort) leaves a phantom `SharedLinkBagBTree` keyed by the booked
file id. The entry is self-healing on retry of the same name (`bookFileId`
re-books a negative entry at the same id, `WOWCache:739`–`740`), so the
exposure is bounded, but the F53 enumeration should own it explicitly.

**Evidence.** `AbstractStorage.doAddCollection:4991`–`4997`/`:5026`/`:5028`–
`5031`, `addIndexEngine:2786`/`:2811`–`2812`,
`LinkCollectionsBTreeManagerShared.createComponent:90`–`98`,
`AtomicOperationBinaryTracking.addFile:808`–`823`,
`WOWCache.bookFileId:732`–`757`. PSI: `registerCollection` callers.

**Affected entries.** D3 (F53 sentence), F53, F39 (the extracted
`doAddIndexEngine` carries the `size()` read with it), D16/F67 (the
collision-free-base premise), F29 (the allocator facts), F48/F49 (the batch
multiplies the duplicates).

**Resolution direction.** State a commit-local structural-id allocator
invariant in D3/F53: ids for collections and engines are drawn from a
commit-local allocator seeded from the shared registries at commit entry
(safe — schema commits are serialized by D7 + `stateLock.write`), unique
across the commit, and published together with the registries on the success
path; a failed commit leaks no durable trace, so its ids are reusable. Extend
the F53 PSI-audit instruction to say allocation sites are read sites too, and
add `fileIdBTreeMap` to the deferred-registry list. Regression test: one tx
creating two classes (16 collections, 2+ engines) → commit succeeds → restart
→ all collections and engines resolve.

---

## U13: The D20 migration dump is produced by the old binaries, so it is always a pre-manifest legacy dump — F63/F75's verification never covers the one migration path it was designed for [MAJOR]

**The gap.** F75 (folded into D20) hard-fails the import on a missing or
unparsable manifest "for any manifest-era dump", with legacy dumps
"distinguished by dump version, not manifest absence". But D20's own migration
procedure is "exporting the old database to JSON with the **old binaries** and
importing into a fresh database with the new binaries" — the new binaries
refuse to open the old format (the D14 version gate), so the exporter that
produces the migration dump is by definition a release without manifest
emission. Every D20 migration dump is a legacy dump under F75's version gate
(`EXPORTER_VERSION = 14` today, `DatabaseExport:59`, written at `:366`–`367`;
the manifest-era version starts only with this work). The import therefore
skips verification on exactly the dump the manifest was invented to protect.

**Failure replay.** Old-binary export crashes or is interrupted mid-stream
(the old exporter is the same streaming JSON writer with no terminal marker,
`exportSchema:449` ff.). The operator imports the truncated dump with the new
binaries: dump version says legacy, no manifest required, no verification —
the import completes whatever subset parses and reports success. Result: a
fresh, version-correct, silently incomplete database, which is verbatim the
F63 failure mode, alive on the primary upgrade path while being marked
resolved for every other path. The truncation must land at a record boundary
for the JSON to keep parsing, which is unlikely for a mid-write kill but easy
for an operator interruption between phases (schema exported, records not).

**Affected entries.** D20, F63, F75, D14 (the version gate forces the
old-binary export this finding rides on).

**Resolution direction.** Pick one and state it in D20: (a) backport manifest
emission to a terminal release of the old format and require exporting with
it (the YTDB-1099 backport precedent); (b) make the new import refuse legacy
dumps unless the operator passes an explicit unverified-import
acknowledgment flag, so the degradation is a logged, deliberate choice; (c)
have the import run a weak completeness check for legacy dumps (the dump's
JSON document must close cleanly — the current format is a single JSON
document, so truncation is detectable without a manifest). Option (c) is
cheap and catches the truncation case; (b) documents the residue.

---

## U14: F66's re-derivation source does not exist for early-flushed operations — deleted (and flush-processed updated) records have no field values left at commit, so the removes the accepted fix prescribes cannot be computed [MAJOR]

**The contradiction is inside the accepted text.** F66's resolution: at
commit, for each tx-created index, re-derive entries "from the tx's complete
record-operation set (which `FrontendTransaction` retains): inserts contribute
put(final values), deletes of committed rows contribute removes (composing
with population's puts inside `commitIndexes` — put then remove nets to
absent), updates contribute their delta." F66's own rejected-alternative
sentence, three lines down, states why the eager delete-flush exists:
"deleted records' field values are needed for key extraction **before
unload**". Both cannot hold. The record-operation set retains the record
*objects* (`recordOperations`, cleared only at tx end,
`FrontendTransactionImpl:999`), but not their extractable state: the
delete-flush comment pins the lifecycle ("execute it here because after this
operation record will be unloaded", `:482`), and the flush wipes the
per-property originals of every processed operation — `clearTrackData()` runs
at the end of each operation's callback processing (`:922`–`924`), and
processed operations are not re-presented at commit unless re-dirtied
(`:775`). So at commit time, for any operation drained by a mid-tx
`deleteRecord` flush, neither the deleted row's key values nor an updated
row's old values are in memory. Those are precisely the F66 shapes
(delete-then-createIndex; insert-delete-other-createIndex).

**What an implementer does, and what it costs.** Reading keys off the
unloaded/cleared record yields an exception or empty keys; swallowing either
durably writes the new index without the remove: the dangling
key→deleted-RID entry F66 exists to prevent, now silent. Re-reading the
committed row through the session read path re-enters `stateLock` under the
held write lock — the F54 self-deadlock, ending in an operator kill that
lands on the F55 recovery path. The only clean sources are a lock-free
committed-row point-read on the commit's atomic operation (a primitive F54
extracted only as a scan) or not needing old values at all.

**Resolution direction.** Flip the composition so no old value is ever
needed: the F54 population scan consults the tx's record-operation set and
*skips* RIDs the tx deleted or updated; re-derivation then contributes only
put(final values) for tx-created and tx-updated rows, all available in
memory. This was the second arm of the pass-6 C10 resolution direction
("have the population phase consult the tx's record-operation set"); the
accepted fold kept only the remove-based arm, which is the one without a key
source. D12's completeness invariant gains one sentence naming the key-source
rule. The F66 regression tests stay valid as written and would catch the
dangling-entry variant.

**Evidence.** `FrontendTransactionImpl.deleteRecord:480`–`487` (flush + the
`:482` comment), `preProcessRecordsAndExecuteCallCallbacks:767`–`811` (whole-
queue drain), `:775` (re-dirty filter), `:922`–`926` (`clearTrackData` +
counter pin), `clear():976`–`988` (tx-end unload of retained records).

**Affected entries.** D12 (completeness invariant), F66, F54, F32.

---

## U15: D7's reap protocol claims pin releases the machinery cannot deliver — the tsMin floor is thread-local and survives every cross-thread reap (silent, unbounded heap growth), and "full tx rollback" against a mid-commit-window tx races a live atomic operation [MAJOR]

**The claim under attack.** D7 (F71/F74 folds): "the reap path runs the
session's full tx rollback (ending the atomic operation — releasing the F74
pins: the `tsMin` snapshot floor, plus the WAL segment pin only if stranded
mid-commit-window) and then `releaseStranded(session)`." The F71 Phase-1
checkpoint flags only one prerequisite (the `AtomicOperationsManager`
thread-binding for ending the operation). Two more bindings make the claim
false as written.

**(a) The tsMin floor cannot be released from the reaper's thread, and the
existing cross-thread close path already proves it.** The floor lives in a
per-thread holder (`tsMinThreadLocal`, `AbstractStorage:367`), registered in
the global `tsMins` set at the owner's first `startStorageTx`
(`:4653`–`4658`). `resetTsMin` operates on `tsMinThreadLocal.get()`, the
*calling* thread's holder, and throws `IllegalStateException` when that
holder has no active tx (`:4679`–`4687`), so a reaper calling it releases
nothing and trips the guard. The code already knows this:
`FrontendTransactionImpl.close()` runs `resetTsMin()` only when
`storageTxThreadId == Thread.currentThread().threadId()` (`:954`–`956`), and
cross-thread close is a documented, supported path ("may be called
cross-thread during pool shutdown", `:130`–`132`). PSI confirms `close():955`
is the only tx-end caller of `resetTsMin`. So every cross-thread reap leaves
the owner thread's holder with `activeTxCount >= 1` and `tsMin` pinned at the
stranded snapshot floor permanently, because the count can never return to
zero on that thread, and `tsMin` only moves down (`Math.min`, `:4653`) until
a zero-count reset (`:4688`–`4696`). The pinned holder stays in `tsMins`, and
`computeGlobalLowWaterMark` (`:6899`, iterating `tsMins` at `:6954`–`6959`)
floors the eviction LWM forever: `evictStaleSnapshotEntries` (`:6452`–`6461`)
stops reclaiming anything newer, and the snapshot/visibility indexes grow
without bound for the process lifetime — even as the pooled owner thread
serves later transactions normally. The stale-tx monitor (`:435`, YTDB-550)
detects long-running holders but releases nothing. This is exactly the
invisible-symptom shape U10 named, relocated from the WAL to the heap, on the
path the design promotes from pool-shutdown corner to routine recovery, while
D12's migration guidance ("heap-bounded wall-clock advice") assumes the floor
releases at tx end.

**(b) The mid-commit-window arm is not a defined operation.** A tx "stranded
mid-commit-window" has its owner thread inside `AbstractStorage.commit`,
holding `stateLock` with the atomic operation live. `endAtomicOperation` is
parameter-based (`AtomicOperationsManager:258`), so a reaper *can* invoke it
cross-thread — against an operation another thread is actively executing. If
the owner is between `startTxCommit` (`commit:2293`) and `commitChanges`, the
reaper's rollback flips `rollbackInProgress` and transitions the table
IN_PROGRESS→ROLLED_BACK under the owner's feet; if the owner has already
written the durable end record inside `commitChanges` but not yet reached
`commitOperation` (`AtomicOperationsManager:341`→`349`), the reaper's
transition succeeds on a unit that is committed in the WAL, and the owner's
later `commitOperation` throws (`changeOperationStatus:525`–`534`), poisoning
the storage with a durable committed unit the table calls rolled back. The
state-machine guard and the dirty-page segment pins keep every variant loud
and restart-recoverable (restart replays the end-record-bearing unit; F53's
deferred publication means no in-memory state escaped), but nothing about it
is "releasing the WAL segment pin": the reap either races a live commit or
must wait for the storage-error/restart path that already owns this case.

**Affected entries.** D7 (reap bullet), F71, F74, D12 (heap-bounded-body
guidance), F61.

**Resolution direction.** Two pins in D7. First, the tsMin release needs a
keyed API: the tx carries a reference to its owner's `TsMinHolder` (it
already carries `storageTxThreadId`), and the reap path decrements/clears
that holder directly (with the same opaque-write discipline `resetTsMin`
uses) instead of going through the ThreadLocal — this joins the F71 Phase-1
checkpoint as a second verified prerequisite, and fixes the pre-existing
pool-shutdown leak as a side effect. Second, scope the reap to
between-operations stranding: the reaper must not end a tx whose owner thread
is inside the commit window (guard on a commit-window flag next to
`storageTxThreadId`); mid-commit stranding is the storage-error/restart
path's case, and D7's parenthetical should say so instead of claiming the
segment-pin release.

---

## U16: F75's manifest atomicity covers the manifest file but not the dump it vouches for, and the stream-variant's "unparsable when truncated" property is assumed, not specified [minor]

Two discipline details are missing from the D20 bullet, both deciding whether
manifest-visible implies dump-durable:

1. **Dump durability orders before manifest visibility.** "Temp + fsync +
   rename" fsyncs the manifest; nothing in the bullet fsyncs the dump file or
   the directory. After a power loss, the rename can be durable while the
   dump's tail bytes are still in the page cache — a well-formed manifest
   beside a dump with a zero-filled or truncated tail. The count verification
   then fires only by accident (JSON parse failure), not by contract, and a
   tail that damages only record *content* inside a parseable structure
   passes counts entirely. Pin: fsync the dump file(s), then write the
   manifest to a temp name in the same directory, fsync it, rename, fsync the
   directory. Cross-filesystem temp locations forfeit rename atomicity; same
   directory makes it a same-filesystem guarantee.
2. **The stream variant needs a self-validating tail.** For "the final
   section of the dump stream", the bullet assumes a truncated manifest
   section is unparsable. A plain JSON object tail truncated mid-way is
   indeed unparsable, but the property should be stated as a requirement
   (length or checksum trailer, or the manifest being the last keys of the
   single JSON document so any truncation breaks the document close), not
   inherited silently — the current exporter has no terminal marker to
   inherit from (`DatabaseExport.exportSchema:449` ff.).

**Affected entries.** F75, D20, F63.

**Resolution direction.** Extend the F75 bullet in D20 with the fsync
ordering (dump durable before manifest visible; same-directory temp;
directory fsync) and the stream-variant tail requirement. One sentence each.

---

## Attacks run that produced no new finding

- **Reaper rollback unpinning the segment of a COMMITTED-but-not-PERSISTED
  unit (the sharpest U15 variant).** Foreclosed by the table's state machine:
  `rollbackOperation` requires IN_PROGRESS and throws otherwise
  (`AtomicOperationsTable:390`–`393`, `changeOperationStatus:525`–`534`), so
  a committed unit cannot be transitioned out of the cut bounds by a stray
  cross-thread end; the IN_PROGRESS-with-durable-end-record window is the U15
  (b) case and stays loud.
- **F73's early-materialization breaking the later FileCreated replay.** The
  FileCreated branch no-ops when the file already exists by name
  (`restoreAtomicUnit:5642`–`5647`), so a file materialized by the
  pending-create consult replays idempotently, as the F73 fold claims.
- **F74's corrected premise.** Re-verified by PSI: `startToApplyOperations`
  is called only from the two `*InsideAtomicOperation` wrappers and
  `AbstractStorage.startTxCommit:4710`; read-only txs never reach the table.
  The commit-window pin model the U15 attack builds on is the corrected one.
- **WAL retention extending past the commit window until the unit's pages
  flush.** Real, but it is the pre-existing dirty-page-table mechanism
  (`makeFuzzyCheckpoint` clamps to dirty-page LSNs) every large commit has
  today; the operations-table pin itself releases at end-record durability
  (`persistOperation` fires on the WAL flush event,
  `AtomicOperationsManager:350`–`352`), which is what D12's corrected
  sentence describes. No design-introduced delta.
- **F68's promotion re-parse failing after the commit is durable.** The
  re-parse runs under the F52/F64 locks after `endTxCommit`; a failure leaves
  shared in-memory state stale until restart, but the durable state is
  complete and the restart load path re-parses the same bytes — the same
  loud, restart-clean envelope the pass-6 walk established for non-crash
  publication failures. (The "round-trip check" wording oversells: a parse
  that *succeeds* on F58-style wrong bytes detects nothing, but F58's
  serialize-after-patch invariant is what actually prevents those bytes, so
  no separate defect.)
- **F67's "no legacy engines can exist under D20" vs backup/restore.**
  Restoring a backup of an old-format database produces an old-format
  database; the D14 schema-version gate fires at the subsequent open, before
  any engine file is touched. Engine file bases derive from the persisted
  `IndexEngineData.indexId` (storage-config data, stable across
  backup/restore), not from WOWCache file ids, so the F73 internal-id
  high-bits concern does not reach base derivation.
- **Same-tx same-name drop+recreate under F67 v1.** Dissolved as designed:
  the recreate allocates a new engine id, hence a distinct file base; the
  unit logs standard FileDeleted/FileCreated records and is a consultable
  YTDB-1099 case. (U12's duplicate-id seam is the one residual path back to
  a same-name collision; with the commit-local allocator it closes.)
- **Long schema-tx body writing WAL records before the commit window.**
  Under D1 the body performs no component operations (all six de-guarded
  self-commit paths become in-memory mutations), so a mid-body kill leaves
  no replayable records (pass-6 ground re-confirmed against the F65/F66
  folds; the enqueue and overlay machinery is heap-only).
- **D12's populated-class boundary (reject vs accept) crash-cleanliness.**
  Pass-6 dry-listed both arms; the F74 correction changes when the table
  registration happens but not the rollback path's no-end-record property.
  Premises unchanged, not re-tilled.
- **Genesis two-tx crash boundary, F59 root-record atomicity, per-class vs
  root record tearing.** Pass-5/6 dry-listed; no pass-6 resolution changed
  their premises; not re-tilled.
