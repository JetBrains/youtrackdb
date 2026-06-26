<!--
MANIFEST
dimension: crash-safety
step: 4.2
iteration: 1
verdict: PASS
review_range: abba0e178b~1..abba0e178b
evidence_base: "## Evidence base"
cert_index: { C1: "#### C1", C2: "#### C2", C3: "#### C3" }
flags: []
findings:
  - { id: CS1, sev: should-fix, anchor: "### CS1", loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassEmbedded.java:562-568", cert: C2, basis: "PSI: txLocal write-sites + setAbstract proxy routing; read of setAbstractInternal/resolveForWrite" }
  - { id: CS2, sev: suggestion, anchor: "### CS2", loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:1037-1057", cert: C3, basis: "Read of saveInternal/toStream firewall + storage-layer negative-id skip" }
-->

## Findings

### CS1 [should-fix] Sibling tx-local create path `setAbstract(false)` still eagerly self-commits a real collection

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassEmbedded.java` (line 562-568)

**What the step inverted, and what it left behind.** This step replaces the eager,
self-committing `session.addCollection` in `SchemaEmbedded.createCollections` with a
provisional `<= -2` allocation on the tx-local create path, so an in-tx `createClass`
writes nothing to storage during the tx body (I-A1, I-A2). The step's scope line names
"the eager self-committing `session.addCollection` ... and any sibling tx-local create
path." One sibling allocate-a-real-collection path was not inverted:
`SchemaClassEmbedded.setAbstractInternal` line 564 calls `database.addCollection(collectionName)`
when an existing abstract class is made concrete (`setAbstract(false)`).

**Crash scenario.** If a transaction does `schema.getClass("X").setAbstract(false)` on an
abstract class X and then the process crashes (or the transaction rolls back) before
commit, then a real storage collection `x_<n>` is left on disk as an orphan — the exact
stray-collection-on-rollback defect (Track 3 CS1) this step exists to close, on a sibling
path.

**Evidence (write-path trace).**
- `SchemaClassProxy.setAbstract` (line 247-250) routes through `resolveForWrite()` — the
  tx-local routing seam — with **no** `session.getTransactionInternal().isActive()` guard,
  unlike `SchemaClassEmbedded.addPropertyInternal` line 42, which still throws inside a tx.
  So `setAbstract(false)` reaches the tx-local class instance mid-transaction.
- `setAbstractInternal` line 562-564 (the concrete branch) computes
  `name_<nextCollectionIndex()>` and calls `database.addCollection(collectionName)`. That
  call is the per-operation self-committing micro atomic-op — it creates a durable real
  collection independent of the user transaction. There is no `txLocal` guard on this
  branch (contrast the `txLocal` early-return firewall in `SchemaShared.saveInternal` line
  1039, and the explicit `if (txLocal)` branches in `dropClassInternal` line 503).
- The path does **not** call `txState.markClassChanged(...)`, so even the in-memory side is
  not recorded for commit-time reconciliation. The eager `addCollection` is the only effect,
  and it is durable.

**Recovery impact.** After the crash/rollback there is no WAL record tying the orphan
collection to the aborted transaction (it was committed by its own micro atomic-op), so WAL
replay faithfully reconstructs the orphan. The committed schema never references it
(`X` stays abstract), so the collection is a permanent leak that also burns a
`<class>_<n>` name and advances `collectionCounter` divergently.

**Refutation considered.** (a) Could a tx-active guard upstream block this? No — `setAbstract`
is deguarded (routes via `resolveForWrite`, no `isActive()` throw), confirmed by reading the
proxy and the entry method. (b) Could this be out of scope and owned by a later track? The
track file's "Out of scope" enumerates rename (Track 6), index overlay (Track 5), freezer
(Track 7) — it does **not** carve out abstract↔concrete transitions, and the step's scope line
explicitly includes "any sibling tx-local create path." (c) Is it actually reachable / does
the test suite cover it? The step's new tests cover create, drop, and abstract-*create*, but
not the abstract→concrete *transition* under a tx, so the gap is untested.

**Suggestion.** Either invert this sibling path the same way (allocate a provisional id via
`txState.allocateProvisionalCollectionId()` and record `markClassChanged` when
`txLocal && !isSeedingTxSchemaState()`, letting the commit create the real collection), or —
if abstract↔concrete transitions are deliberately deferred to a later track — add an explicit
tx-active guard so `setAbstract(false)` cannot run inside a schema transaction yet, and note
the deferral in the track file's "Out of scope" list. A regression test that makes an abstract
class concrete inside a transaction, rolls back, and asserts the storage collection set is
unchanged would pin whichever choice is made.

### CS2 [suggestion] I-A2 firewall is pre-existing and depends on Step 3 resolving before promotion serializes

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 1037-1057)

**Observation, not a defect in this step.** The guarantee that a provisional `<= -2` id never
reaches durable bytes (I-A2) rests on the `txLocal` early-return in `SchemaShared.saveInternal`
(line 1039), which predates this step, plus the storage layer continuing to skip every negative
collection id (`AbstractStorage` lines 1526, 1570, 1739, 3901, 5596 use `< 0`; line 6800 uses
`>= 0`). This step's predicate split (`collectionId < 0` → `collectionId == ABSTRACT_COLLECTION_ID`)
was applied only to the in-memory `collectionsToClasses` sites, correctly leaving the storage
sites skipping all negatives. So within this step's scope a provisional id cannot serialize:
the tx body never reaches `SchemaClassImpl.toStream` (line 590-594, which writes `collectionIds`
verbatim into the record).

**Forward dependency to record.** The only path that serializes the tx-local schema is the
commit-time promotion (Step 3), which calls `toStream`. `SchemaClassImpl.toStream` line 594
serializes the raw `collectionIds` array; if a provisional id is still present in a class
instance when promotion's `toStream` runs, it lands in a durable schema record (the F58
silent-corruption case D2 warns about). Step 3 owns the provisional→real patch that must run
before that `toStream`. This step correctly does not implement it; this note exists so the
Step 3 reviewer treats "every provisional id is resolved before any `toStream` in the promotion
path" as a load-bearing crash-safety obligation, and so the dependency is not lost between steps.

**No action required in this step.**

## Evidence base

#### C1 — Core inversion is durable-safe within scope (CONFIRMED safe; one line)
The tx-local create path allocates a provisional `<= -2` id (`SchemaEmbedded.createCollections`
line 364, 383-387) and never calls `session.addCollection`; `txLocal` is set true only in
`SchemaShared.copyForTx` (PSI write-site search: sole write at `SchemaShared.java:228`), so the
committed (`txLocal=false`) path is unchanged and still durable; the seeding guard
(`!isSeedingTxSchemaState()`, flag set across the `copyForTx` re-parse at
`DatabaseSessionEmbedded.java:2475-2481`) excludes the seed from the provisional branch; the new
test `rolledBackInTransactionCreateLeavesNoCollectionOnDisk` pins I-A1. Survived refutation:
the only production caller of `allocateProvisionalCollectionId` is the new branch
(`SchemaEmbedded.java:384`, PSI), and the only storage write path (`addCollection`) is bypassed
on the provisional branch.

#### C2 — `setAbstract(false)` sibling path leaks a real collection on rollback (CONFIRMED as issue → CS1)
- Premise P1: `setAbstract` routes through `resolveForWrite()` (tx-local seam) with no tx-active
  guard — `SchemaClassProxy.java:247-250`, read directly; contrast `addPropertyInternal:42` which
  still throws inside a tx.
- Premise P2: the concrete branch calls `database.addCollection` eagerly — `SchemaClassEmbedded.java:564`,
  read directly; no `txLocal` branch, no `markClassChanged`.
- Write-path step (eager self-commit): `addCollection` is the per-operation self-committing
  atomic-op, so the collection is durable independent of the user tx. Crash/rollback after this
  call but before commit leaves the collection on disk.
- Recovery: WAL replays the micro atomic-op faithfully; the committed schema (class stays
  abstract) never references the collection → permanent orphan, divergent `collectionCounter`.
- Refutation A (upstream tx-active guard): REFUTED — proxy + entry method read, no guard on this
  path. Refutation B (out of scope / later track): REFUTED — track file "Out of scope" does not
  list abstract↔concrete; step scope line includes "any sibling tx-local create path". Refutation
  C (covered by tests): REFUTED — new tests cover create/drop/abstract-create, not the
  abstract→concrete transition under a tx.
- VERDICT: CONFIRMED as a should-fix scope gap. It is the same defect class the step closes for
  `createClass`, on a path the step did not invert. Severity should-fix (not blocker): it is a
  pre-existing path the step did not regress, and the primary create path it targets is fully
  safe; but it leaves I-A1's storage-byte-for-byte-unchanged guarantee violable through a sibling
  schema operation.

#### C3 — Provisional id cannot serialize in this step; firewall is pre-existing (CONFIRMED safe; one line)
`SchemaShared.saveInternal` early-returns on `txLocal` (line 1039, read directly) so the tx body
never serializes the tx-local schema; `SchemaClassImpl.toStream` (line 594) writes `collectionIds`
verbatim, reachable only via the commit-time promotion `toStream` (Step 3), which owns the
provisional→real resolution; storage sites keep skipping all negatives (`AbstractStorage` grep:
lines 1526/1570/1739/3901/5596 `< 0`, 6800 `>= 0`). Recorded as CS2 (suggestion) so the Step 3
reviewer carries the resolve-before-`toStream` obligation forward; no defect in this step.
