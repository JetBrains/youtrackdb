<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 4: Commit-time reconciliation and the schema-carrying commit lock (D1, D2, D3, D6, D9, D10, D19)

## Purpose / Big Picture
After this track, committing a transaction that changed the schema creates or
drops the matching collections and engines inside the commit's own atomic
operation, atomically with the record writes and recoverable from the WAL, while a
rolled-back or crashed-before-commit schema transaction leaves storage
byte-for-byte unchanged.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Make the commit compute the structural delta as a set difference over committed
versus tx-local collection-id sets, resolve provisional ids before any record
serializes, reconcile in the correct order through lock-free inner primitives under
a commit-local id allocator, take `stateLock.writeLock()` from the start under the
four-lock order, promote the tx-local schema into the existing shared instances with
one `forceSnapshot`, and convert the two remaining lock-based read sites to
snapshot-first so the whole-commit write lock never becomes a read outage.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-26T08:43Z [ctx=info] Review + decomposition complete
- [x] 2026-06-26T10:04Z [ctx=safe] Step 1 complete (commit 7c7a157efa)
- [x] 2026-06-26T12:52Z [ctx=info] Step 2 complete (commit 346e87ae9d)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- 2026-06-26T10:04Z Step 1 established the create/publish seam — the create
  primitives (`doAddIndexEngine`, `doCreateCollection`) build files and config
  inside the atomic operation and return the engine/collection unpublished;
  `publishIndexEngine`/`registerCollection` mutate the in-memory maps
  separately. Step 2's reconciliation and Track 5's overlay publish both consume
  this seam. `setIndexEngine`/`setCollection` grow-and-set, so Step 2's
  commit-local allocator must handle a reused hole id below the live size. A
  `@Test(timeout)` in the `AbstractStorage` package needs
  `db.activateOnCurrentThread()` as its first body statement (the session is
  thread-bound via a `ThreadLocal`; JUnit runs a timed body on a separate
  watchdog thread). See Episodes §Step 1.
- 2026-06-26T12:52Z Step 2 found two tx-local provisional-collection-id
  producers, not one: the create path (`SchemaEmbedded.createCollections`) and
  the abstract→concrete alter path (`SchemaClassEmbedded.setAbstractInternal`,
  PSI-confirmed tx-reachable). Both now allocate provisional `<= -2` ids via
  `TxSchemaState.allocateProvisionalCollectionId` and record `markClassChanged`.
  Step 3's reconciliation must resolve provisional ids from BOTH producers
  before any `toStream` in promotion. The not-resolved sentinel is
  `TxSchemaState.NO_RESOLUTION` (`Integer.MIN_VALUE`), disjoint from real
  (`>= 0`), abstract (`-1`), and provisional (`<= -2`); consumers must test
  against it, never against `-1`. The I-A2 durable-bytes half and the
  crash-before-commit variant are deferred to Step 3 (test breadcrumbs in
  place). See Episodes §Step 2.

## Decision Log
<!-- The track-canonical live decision carrier (D7). Seeded from the frozen
design.md D-records this track owns. -->

- 2026-06-26T10:04Z (dependency-reveal / re-decomposition) The Step 2 implementer
  found the reconciliation core could not run: the D2 provisional-collection-id
  *production* substrate was unassigned to any step. An in-tx `createClass` still
  allocates a durable real collection eagerly through the self-committing
  `session.addCollection` (`SchemaEmbedded.createCollections:359`), so no provisional
  id exists for the D9 set-difference to find or the patch list to resolve. Track 3's
  CS1 surprise had deferred this eager→provisional inversion to Track 4 (D2/D10), but
  Phase A's four-step decomposition folded only the consume-side into Step 2.
  Resolution (user-approved): split the production into a new Step 2 (eager→provisional
  inversion, the `collectionId < 0` → `-1` vs `<= -2` predicate split,
  `collectionsToClasses` provisional population, a `TxSchemaState` provisional→real
  carrier), renumbering the reconciliation core to Step 3, selective write to Step 4,
  and read-site conversions to Step 5. The new step also closes the Track 3 CS1
  stray-collection-on-rollback defect. No Decision Record changed; D2 already
  specifies provisional ids. See Concrete Steps §Step 2.
- 2026-06-26T12:52Z (scope-up / review-driven) Step 2's step-level review
  (BC1/CS1) expanded the step from the create path alone to also invert the
  tx-local abstract→concrete alter path (`SchemaClassEmbedded.setAbstractInternal`),
  PSI-confirmed reachable inside a transaction and carrying the same I-A1
  stray-collection-on-rollback exposure. Both producers now route through the
  provisional seam. No other track clearly owned the alter path, so completing
  it here keeps the I-A1 invariant whole for tx-local collection allocation.
  See Episodes §Step 2.

#### D1 (commit facet): Storage reconciles structure at commit
- **Alternatives considered**: keep storage-leading (the enablement facet's rejected alternative).
- **Rationale**: at commit, storage diffs the committed metadata against current structure and creates or drops the matching collections and engines inside the commit's own atomic operation, so the structural change is atomic with the record writes.
- **Risks/Caveats**: reconciliation runs while the commit already holds the write lock, so it must use lock-free inner primitives (D3).
- **Implemented in**: this track (Track 3 supplies the enablement)
- **Full design**: design.md §"Commit-time reconciliation"

#### D2: Provisional collection ids, resolved at commit
- **Alternatives considered**: allocate real collection ids eagerly at create time.
- **Rationale**: a new collection carries a provisional negative id during the tx (mirroring temp RIDs); at commit, storage creates the real collection and patches every reference before any record serializes. The provisional range is `<= -2`, disjoint from the abstract-class marker `-1`, because the schema layer tests `collectionId < 0` (not `== -1`) in 11+ places. The in-memory maps treat provisional ids as pending-real (reverse map populated, uniqueness validated) while file/storage sites keep skipping negatives.
- **Risks/Caveats**: the commit-time patch list has five items — the class id-list, the inserted records' RIDs, the `collectionsToClasses` reverse-map re-key, the provisional→real resolution, and the changed-class records' property values re-pointed before `commitEntry`. Skipping the property-value re-point durably writes provisional ids, and the class loses its collections at the next open (the F58 silent-corruption case). The engine-id allocator (`indexEngines.size()`) is a second allocation axis, separate from the collection-id allocator, and follows the identical commit-local-seed discipline (D10). A multi-class, multi-index commit resolves every provisional id to its real id first, then re-keys `collectionsToClasses` and re-points property values, so cross-class references settle before any record serializes (A3).
- **Implemented in**: this track
- **Full design**: design.md §"Commit-time reconciliation"

#### D3: Commit ordering — structural reconciliation before record allocation
- **Alternatives considered**: allocate record positions then create structure.
- **Rationale**: index-engine creation must land before `lockIndexes` (which locks each tx index's engine through `IndexAbstract.acquireAtomicExclusiveLock` → `getIndexEngine`, so the engine must already be created and registered), and collection creation before the record-position-allocation loop (a record can only get a position once its collection exists). Reconciliation and population call the lock-free inner primitives (`doAddCollection` / `dropCollectionInternal`, and new `doAddIndexEngine` / `doDeleteIndexEngine(atomicOperation, …)` extracted from the public methods), never the public structural methods, which re-acquire the non-reentrant `stateLock` the commit already holds and self-deadlock.
- **Risks/Caveats**: index population is a lock-free internal scan feeding `doPut`, all on the commit's atomic operation, emitting zero additional WAL units; a nested batch transaction would re-enter `stateLock` and make the build durable independently of the commit. **Engine-lookup re-entry (T1/T2):** the commit's index-apply path `lockIndexes` → `IndexAbstract.acquireAtomicExclusiveLock` → `getIndexEngine(int)` re-acquires `stateLock.readLock()`, which on the non-reentrant `ScalableRWLock` busy-spins forever once the schema-carrying commit (D19) holds the write lock. That is a self-deadlock on every schema-or-index commit carrying at least one index operation (the write-lock-branch set per I-U5). The commit window must reach a lock-free engine resolver (a `doGetIndexEngine(int)` reading `indexEngines.get(id)` without `stateLock`, mirroring the existing lock-free `doGetAndCheckCollection`), and the track must enumerate every `stateLock.readLock()`-taking method reachable from the commit body under the write lock and confirm each is replaced by a lock-free variant. The earlier "resolves by id and throws on a missing one" gloss was wrong: a missing engine loops on `InvalidIndexEngineIdException` retry, so the load-bearing hazard is the read-lock re-entry, not a propagated throw.
- **Implemented in**: this track
- **Full design**: design.md §"Commit-time reconciliation"

#### D6: Commit-time delta via the diff approach, from existing tx tracking
- **Alternatives considered**: a separate intent list of structural ops.
- **Rationale**: the transaction already carries the changed records and per-property dirty marks; per-property dirty tracking governs which records are written (the D14 write-amplification win).
- **Risks/Caveats**: the structural create/drop set is NOT derived from the changed-record set — a dropped class's record is deleted, so it carries no per-property signal, and a diff over the changed-record set would drop nothing. The structural set uses D9's set difference instead. **Selective write and the F59 guard (R2/A2), hosted here per Track 2's G2 hand-off:** the write-amplification win writes only the changed per-class records plus the root record when its non-link payload changes. The inherited `SchemaShared.toStream` serializes every live class today, so this track adds the selective write keyed on `getChangedClasses()` (or relies on the record-layer dirty-mark suppression of unchanged-record writes). Omitting the root write when its payload changed is the F59 root-omission regression: a committed property-create restarts into a null `globalRef` and the reverted `collectionCounter` regenerates colliding collection names (design.md I-U1). The F59 guard test lands in this track.
- **Implemented in**: this track
- **Full design**: design.md §"Commit-time reconciliation"

#### D9: Diff over collection ids and index definitions, not class names
- **Alternatives considered**: diff by class name (breaks on rename).
- **Rationale**: collection id is the stable structural identity. A create is a collection id in the tx-local set absent from the committed set; a drop is the reverse; the diff is a set difference over the committed in-memory `SchemaShared` versus the tx-local one. A rename keeps its collection ids, so it is structurally inert. Indexes diff by index identity from the index-manager record.
- **Risks/Caveats**: the predicate must distinguish abstract (`-1`) from provisional (`<= -2`) at the in-memory map sites.
- **Implemented in**: this track
- **Full design**: design.md §"Commit-time reconciliation"

#### D10: Structural revertibility via the existing atomic-operation WAL; no pool
- **Alternatives considered**: a page-reuse / deletion pool (its only correctness benefit is already free; its performance benefit needs a new WAL-logged clear-and-reinit op because `truncateFile` is not crash-safe).
- **Rationale**: file create/delete is buffered intent applied only in `commitChanges`, which rollback skips, so a rolled-back or crashed-before-commit tx leaves files byte-for-byte unchanged. A committed delete is permanent and redone from the WAL after a crash. A failed commit publishes nothing into the shared registries until after `commitChanges` succeeds, and draws collection/engine ids from a commit-local allocator seeded under the write lock, so a failed commit leaves no phantom registration and frees its ids to reuse.
- **Risks/Caveats**: the crash-recovery half is conditional on the F55 lazy-consult replay fix (Track 1). The commit-local allocator's seed read must sit inside the `stateLock.writeLock()` window so it excludes the non-commit engine registrars (`rebuild`, `loadExternalIndexEngine`, `recreateIndexes`) that run under `stateLock.write` alone (the F88 pin). **In-memory registry publication must trail the atomic op (A1/R1):** the lock-free creation primitives publish into the live in-memory registries synchronously inside the atomic operation (`doAddCollection` → `registerCollection`/`setCollection` writes `collections`/`collectionMap`; the `addIndexEngine` lambda writes `indexEngineNameMap`/`indexEngines`), and the WAL revert undoes the on-disk file create but not those Java maps. Reusing the primitives verbatim leaves a phantom registration on a failed commit, the case this decision forbids. The commit path must split file/engine creation plus id allocation (inside the atomic op, WAL-reverted) from registry publication (deferred to the post-`commitChanges` success path, or undone in the failure `finally`), mirroring the existing `deleteIndexEngine` discipline that already defers its in-memory map mutation to after the atomic op.
- **Implemented in**: this track (+ Track 1 prerequisite for the crash-recovery half)
- **Full design**: design.md §"Commit-time reconciliation"

#### D19: Schema-carrying commits take the write lock from the start; pure-data commits keep the read-lock fast path
- **Alternatives considered**: a mid-commit read→write upgrade (the F33 interleaving window).
- **Rationale**: the commit decides at entry whether the tx carries schema or index changes (the same unified signal that engaged the D7 mutex and built the diff) and takes `stateLock.writeLock()` from the start, so reconciliation runs under the exclusive lock with no upgrade and no window. A pure-data commit keeps the `readLock()` fast path and today's concurrency. An index-only tx takes the write-lock branch even though it never touched the schema chokepoint.
- **Risks/Caveats**: a schema commit excludes concurrent data commits for its duration, bounded by the low schema-change rate. The lock-based reader set blocks once for the whole commit; the two remaining lock-based read sites (`YTDBGraphImplAbstract.createVertexWithClass`, `SQLMatchStatement.getLowerSubclass`) convert to snapshot-first so the hot paths stay unaffected. The four-lock order is mutex → `SchemaShared.lock` → index-manager lock → `stateLock.writeLock`, and both metadata write locks are taken before `stateLock` to keep the order acyclic.
- **Implemented in**: this track
- **Full design**: design.md §"The schema-write mutex and lock order"

#### D8 (promotion facet): Promote the tx-local schema into the existing shared instances
- **Alternatives considered**: adopt the tx-local objects directly into the shared structure.
- **Rationale**: at commit the tx-local schema is promoted by re-parsing the just-committed per-class records into the existing shared `SchemaShared` instances (new classes constructed bound to the shared owner, dropped classes removed, edges re-resolved by name), never by adopting the tx-local objects whose `final owner` is the dead tx-local instance. One single trailing `forceSnapshot` invalidates the shared snapshot. Promotion and the `forceSnapshot` run under `SchemaShared.lock.writeLock()`, acquired before `stateLock.writeLock()`.
- **Risks/Caveats**: never two separate publish-then-invalidate pairs (F62); listeners (`onSchemaUpdate`, and `onIndexManagerUpdate` when the changed-index set is non-empty) fire after the locks release.
- **Implemented in**: this track
- **Full design**: design.md §"The tx-local schema view and transactional enablement", §"The schema-write mutex and lock order"

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 2 (3 findings, 3 accepted — T1 blocker `lockIndexes`/`getIndexEngine` readLock re-entry self-deadlock under the D19 write lock; T2 should-fix D3 mechanism correction; T3 suggestion read-site enumeration).
- [x] Risk: PASS at iteration 2 (4 findings, 4 accepted — R1 should-fix phantom in-memory registration on failed commit; R2 should-fix F59/D6 selective-write hosting; R3/R4 suggestions enumeration + I-A4 fault seam).
- [x] Adversarial: PASS at iteration 2 (4 findings, 4 accepted — A1 blocker phantom registration; A2 should-fix `SchemaShared.toStream` selective write; A3 should-fix engine-id axis + multi-class re-key ordering; A4 suggestion scope, accepted as decomposition guidance). Ran on the session-default model: D14 pins Fable 5 at `full` but Fable was unavailable; verdicts rest on PSI re-checks, so the degradation does not weaken the gate.

## Context and Orientation
`AbstractStorage.commit` today takes `stateLock.readLock()` and runs the data-record
commit; structural work (collection/index create/drop) happens outside the user
transaction in per-operation self-commits. The temp-RID resolution path
(`ChangeableRecordId.setCollectionAndPosition`, guarded by
`assertIdentityChangedAfterCommit`) already rewrites a temp RID to its persistent
form in place at commit — the template the provisional-collection-id resolution
extends with a prior step. The atomic-operation machinery already buffers file
create/delete as intent applied only in `commitChanges` (which rollback skips), so
structural revertibility is free once reconciliation runs inside the commit.

Public structural methods (`addCollection`, `addIndexEngine`, `dropCollection`,
`deleteIndexEngine`) take `stateLock.writeLock()`; the lock-free inner primitives
(`doAddCollection`, `dropCollectionInternal`) exist for collections, but engines
need `doAddIndexEngine` / `doDeleteIndexEngine(atomicOperation, …)` extracted from
the inlined public bodies. New collection ids come from the first null slot of the
shared `collections` array and new engine ids from `indexEngines.size()`; deferring
registry publication makes those live allocators stale, so the commit needs a
commit-local allocator seeded inside the write lock. Two lock-based read sites
remain on the per-record and per-MATCH paths (`createVertexWithClass`,
`getLowerSubclass`); both convert to snapshot-first.

This track builds on Track 3's tx-local view and engaged mutex, Track 2's per-class
records, and Track 1's clean replay. It is where the dependency inversion completes:
schema changes made transactionally now become real structure.

## Plan of Work
The commit's work, in sequence:

1. Branch at entry on the unified schema-or-index signal: a schema-carrying commit
   takes `stateLock.writeLock()` from the start under the four-lock order; a
   pure-data commit keeps the read-lock fast path.
2. Compute the structural delta as a set difference over committed versus tx-local
   collection-id sets (D9), not from the changed-record set.
3. Extract `doAddIndexEngine` / `doDeleteIndexEngine` from the public methods, and add
   a lock-free engine resolver (`doGetIndexEngine`) for the commit window, so both
   reconciliation and the `lockIndexes` index-apply path run lock-free under the held
   write lock with no `stateLock.readLock()` re-entry.
4. Reconcile in order — engines before `lockIndexes`, collections before the
   record-position-allocation loop — drawing ids from a commit-local allocator
   seeded inside the write lock.
5. Resolve provisional ids through the five-item patch list (collection ids; the
   engine-id allocator follows the same commit-local-seed discipline), re-pointing the
   changed-class records' property values before `commitEntry`. Write only the changed
   per-class records plus the root record when its non-link payload changed — the D6
   write-amplification win and its F59 root-omission guard.
6. Defer in-memory shared-registry publication (`collections` / `collectionMap` /
   `indexEngines` / `indexEngineNameMap`) to the post-`commitChanges` success path, or
   undo it in the failure `finally`, so a failed commit leaves no phantom registration.
7. Promote the tx-local schema by re-parsing the committed per-class records into
   the existing shared instances, publish the index overlay (the overlay machinery
   is Track 5), and fire one trailing `forceSnapshot`.
8. Convert the two lock-based read sites to snapshot-first.

Ordering constraints (load-bearing): engine creation before lookup-by-id, collection
creation before record-position allocation, provisional→real resolution (including
property values) before serialization, registry publication only after
`commitChanges`, the commit-local allocator seed inside the write-lock window, and
promotion under `SchemaShared.lock` before `stateLock`.

## Concrete Steps

1. Extract the lock-free commit-window primitives in `AbstractStorage`: pull `doAddIndexEngine` / `doDeleteIndexEngine(atomicOperation, …)` out of the public `addIndexEngine` / `deleteIndexEngine` bodies, add a lock-free `doGetIndexEngine(int)` that reads `indexEngines.get(id)` without taking `stateLock` (mirroring the existing lock-free `doGetAndCheckCollection`), and split the collection/engine create primitives so id-allocation plus file/engine creation is separable from in-memory registry publication (`collections` / `collectionMap` / `indexEngines` / `indexEngineNameMap`). Public methods stay behavior-preserving wrappers; unit tests confirm the wrappers preserve behavior and that `doGetIndexEngine` resolves while a write lock is held. — risk: high (crash-safety/durability: `AbstractStorage` storage-component primitives; concurrency: a new lock-free read of shared mutable state)  [x] commit: 7c7a157efa
2. Produce D2 provisional collection ids in the tx-local create path: replace the eager, self-committing `session.addCollection` in `SchemaEmbedded.createCollections` (and any sibling tx-local create path) with provisional `<= -2` sentinel allocation, so an in-tx `createClass` carries a provisional id instead of a durable real collection; split the `collectionId < 0` predicate into abstract `-1` vs provisional `<= -2` across the in-memory map sites (`SchemaShared.checkCollectionCanBeAdded`, `addCollectionClassMap`, `getClassByCollectionId`, and the other ~11 `< 0` sites), keeping file/storage sites skipping all negatives; populate `collectionsToClasses` with provisional ids as pending-real (reverse map populated, uniqueness validated); carry the provisional→real id mapping on `TxSchemaState`. Closes the Track 3 CS1 stray-collection-on-rollback defect — a rolled-back in-tx create now leaves no collection on disk. This is the D2 production substrate the reconciliation core consumes. (Depends on Step 1.) — risk: high (architecture: inverts the eager structural collection allocation, the storage-leads dependency inversion's tx-local half; crash-safety/durability: a rolled-back or crashed-before-commit in-tx create must leave no stray collection; concurrency: the tx-local predicate-split sites)  [x] commit: 346e87ae9d
3. Implement the commit-time reconciliation core in `AbstractStorage.commit`: branch at entry on the unified schema-or-index signal to take `stateLock.writeLock()` from the start under the four-lock order (mutex → `SchemaShared.lock` → index-manager lock → `stateLock.writeLock`); compute the D9 set-difference structural delta over committed versus tx-local collection-id sets; route `lockIndexes` and the index-apply path through the lock-free `doGetIndexEngine`; reconcile engines before `lockIndexes` and collections before the record-position-allocation loop via the lock-free primitives, drawing collection and engine ids from a commit-local allocator seeded inside the write lock; resolve provisional ids through the patch list with the multi-class resolve-then-re-key ordering; defer in-memory registry publication to the post-`commitChanges` success path (undo on the failure path); promote the tx-local schema by re-parsing the committed per-class records into the shared instances with one trailing `forceSnapshot`. Covers I-A1, I-A2, I-A3, I-A4, I-P1, I-C1. (Depends on Steps 1 and 2.) — risk: high (concurrency: four-lock order and lock-free reconciliation under the held write lock; crash-safety/durability: commit reconciliation, WAL revertibility, recovery; architecture: completes the storage-leads dependency inversion)  [ ]
4. Add the selective per-class write keyed on `getChangedClasses()` so a schema commit writes only the changed per-class records plus the root record when its non-link payload changed (the D6 write-amplification win), and add the F59 root-omission guard. Covers I-U1 (one-record-per-changed-class plus the F59 property-create-then-restart regression). (Depends on Step 3.) — risk: high (crash-safety/durability: schema-record write path; F59 is a silent cross-restart corruption)  [ ]
5. Convert the two lock-based hot read sites to snapshot-first (`YTDBGraphImplAbstract.createVertexWithClass`, `SQLMatchStatement.getLowerSubclass`) and enumerate the remaining `SchemaShared.lock`-based hot reads to confirm only these two would stall behind the commit write lock. Covers I-U5 (schema commit holds the write lock for its whole duration; a data commit runs concurrently on the read-lock path; an index-only tx serializes as schema-carrying). (Depends on Step 3; *(parallel with Step 4)* — disjoint files.) — risk: high (performance hot path: the per-vertex-create and per-MATCH-step read paths)  [ ]

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

### Step 1 — commit 7c7a157efa, 2026-06-26T10:04Z [ctx=safe]
**What was done:** Extracted the lock-free commit-window primitives out of the
public structural methods on `AbstractStorage`, keeping the public methods as
behavior-preserving wrappers with no call-site changes. Added `doGetIndexEngine(int)`
(a lock-free engine resolver mirroring `doGetAndCheckCollection`; `getIndexEngine`
now delegates to it inside the read lock), `doAddIndexEngine` + `publishIndexEngine`
+ `setIndexEngine` (the create-then-publish split for engines), `doDeleteIndexEngine`
(the atomic-op delete half), and `doCreateCollection` (the create-then-publish split
for collections, with `registerCollection` as the publish half). A same-package
white-box test `AbstractStorageCommitPrimitivesTest` pins wrapper create/register/drop
behavior and the load-bearing case that `doGetIndexEngine` resolves while the calling
thread holds `stateLock.writeLock()`.

**What was discovered:** The collection-side publish (`registerCollection`) ran
before the WAL-reverted config/component work in the original `doAddCollection`,
but it is safely reorderable: the collection id is fixed at `collectionPos` before
that work, and `addCollection` guards the duplicate-name case before the atomic
operation, so the split moves publication last with no behavior change. A vertex
class names its collections `<class>_<counter>` (e.g. `collpublishprobe_9..16`), not
the bare class name. Step-level review (bugs-concurrency, crash-safety,
test-concurrency; PASS at iteration 2) left two findings intentionally unfixed: BC1
(the pre-lock `checkIndexId` in `getIndexEngine` is now redundant with the under-lock
check in `doGetIndexEngine`, but harmless, since the under-lock check closes a TOCTOU
window) and TX2 (a negative control / true multi-threaded race test, deferred to
Step 2).

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageCommitPrimitivesTest.java` (new)

**Critical context:** The new primitives are additive; no consumer wires the
create/publish split or the lock-free resolver into the commit yet — that is Step 2's
reconciliation core. The seam it must use: call the create primitive inside the atomic
operation and publish (`publishIndexEngine`/`registerCollection`) only on the
post-`commitChanges` success path (undo in the failure `finally`), so a failed commit
leaves no phantom in-memory registration (D10, A1/R1). `setIndexEngine`'s
grow-with-gap branch (id > size) and `doCreateCollection`'s null-name no-op branch are
unexercised by the public path and exist for Step 2's commit-local allocator, which
may hand back a reused hole id below the live size. Timed tests in this package must
call `db.activateOnCurrentThread()` first (thread-bound session under `@Test(timeout)`).

### Step 2 — commit 346e87ae9d, 2026-06-26T12:52Z [ctx=info]
**What was done:** Inverted the eager, self-committing collection allocation in the
tx-local schema paths. `SchemaEmbedded.createCollections` and
`SchemaClassEmbedded.setAbstractInternal` (the abstract→concrete switch) now allocate a
provisional `<= -2` collection id from a per-transaction allocator on `TxSchemaState`
when `txLocal && !isSeedingTxSchemaState()`, recording the class as changed; the
committed/non-tx/seeding path keeps the real-id `addCollection`. Added
`ABSTRACT_COLLECTION_ID` (-1), `PROVISIONAL_COLLECTION_ID_CEILING` (-2),
`isProvisionalCollectionId`, and `NO_RESOLUTION` (`Integer.MIN_VALUE`) to
`SchemaShared`/`TxSchemaState`. Split the `collectionId < 0` predicate at the six
in-memory reverse-map sites to skip only the abstract marker and treat `<= -2` as
pending-real; file/storage sites still skip all negatives. Added the provisional→real
carrier on `TxSchemaState` (`allocateProvisionalCollectionId`, `recordResolvedCollectionId`,
`getResolvedCollectionId`, `getResolvedCollectionIds`). Covers I-A1 (a rolled-back in-tx
create or abstract→concrete alter leaves no collection on disk) and I-A2 (a provisional
id is exposed only in the tx-local view).

**What was discovered:** This step was created mid-Phase-B by splitting the original
Step 2 — the reconciliation core depended on a D2 provisional-id production substrate no
step produced (see Decision Log 2026-06-26T10:04Z). Two eager self-commit call sites
existed, not one: the create path and the abstract→concrete alter path
(`setAbstractInternal`). The alter path is reachable inside a transaction (PSI:
`setAbstract` → `resolveForWrite` → `rebindToTxLocal`, no tx-active guard), so it carried
the same I-A1 stray-collection-on-rollback exposure; the step-level review
(bugs-concurrency, crash-safety, test-crash-safety; PASS at iteration 2) flagged it
(BC1/CS1) and the fix routed it through the same provisional seam with its own rollback
regression test (`rolledBackInTransactionSetAbstractFalseLeavesNoCollectionOnDisk`). The
make-non-abstract branch never registered into `collectionsToClasses` even on the eager
path, so the inversion swaps only the allocation source plus `markClassChanged`, with no
other behavior change.

**What changed from the plan:** The BC1/CS1 review expanded the step from the create path
alone to also cover the abstract→concrete alter path (`setAbstractInternal`), since both
are tx-local collection allocators with the same I-A1 exposure and no other track clearly
owned the alter path. This gives Step 3 two provisional-id producers to resolve, not one.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (modified)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaEmbedded.java` (modified)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassEmbedded.java` (modified)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java` (modified)

**Critical context:** Step 3 (reconciliation core) must resolve every provisional id from
BOTH producers (`createCollections` and `setAbstractInternal`) by calling
`recordResolvedCollectionId`, then patch every provisional reference (class id-lists,
`collectionsToClasses` re-key, changed-class records' property values) before any
`toStream` in the promotion path. Consumers must test resolution against
`TxSchemaState.NO_RESOLUTION` (or `getResolvedCollectionIds().containsKey`), never against
`-1`. The I-A2 durable-bytes half (commit → reload → real id survives, no `<= -2` on disk)
and the crash-before-commit variant are deferred to Step 3's reconciliation plus the
crash-restore harness; the Step 2 tests carry breadcrumbs to those.

## Validation and Acceptance
- One transaction creates two classes (16 collections, 2+ engines) plus records,
  commits, and restarts; every collection and engine resolves, each class's records
  re-point to the real collection ids with no provisional id surviving, and cross-class
  references settle correctly (I-A3, A3).
- A transaction creates a class and an index, then rolls back; no collection or
  engine files exist and no registry entry is left. Repeat with a crash injected
  after the transaction body and before commit; recovery leaves the same clean state
  (I-A1, leaning on Track 1).
- The positive drop: create a class with data and an index and commit; in a second
  transaction drop the class and commit; the collection files, engine files, and
  registry entries are gone and the data is unreadable, confirmed across a restart.
  A drop-detection built from the changed-record set fails this (I-A1, D9).
- A transaction creates a class and inserts records, then commits and restarts; the
  class resolves to its collections and each record resolves to its class — no
  provisional id reached durable bytes (I-A2).
- A transaction creates a property on a class and commits, then restarts; the class's
  `globalRef` resolves non-null and no two collections share a generated name — the F59
  root-omission guard, since the root record must be written whenever its non-link
  payload changes. A one-class change writes exactly one per-class record, plus the root
  record only when its payload changed — the D6 write-amplification win (I-U1, R2/A2).
- Force a commit to fail at apply (fault injected at or after `commitChanges`, not
  before); the in-memory registries (`collections` / `collectionMap` / `indexEngines` /
  `indexEngineNameMap`) carry no entry for the failed commit's structures, nothing is
  registered on disk, and the next commit reuses the ids (I-A4, A1/R1).
- A schema commit holds the write lock for its whole duration with no observable
  upgrade; a data commit runs concurrently with other data commits on the read-lock
  path; an index-only tx is serialized as a schema-carrying commit (I-U5).
- A schema commit, a data-path `reload`, and an `IndexManagerAbstract.load` run
  concurrently with no interleaving deadlock (I-C1).
- A schema-change commit updates the shared class instances in place (not replaced by
  tx-local instances), invalidates the snapshot exactly once, and notifies
  `MetadataUpdateListener` consumers (I-P1).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
- **Failed-commit registry cleanliness (I-A4, R4).** The fault point is a Mockito spy or
  a `@VisibleForTesting` seam on the commit, fired at or after `commitChanges` (Track 1
  settled its replay regression on the `RestoreAtomicUnit*` Mockito harness). Failing
  before `commitChanges` does not exercise the deferred-publication window. If no
  reusable fault hook exists, decompose a small testability seam rather than improvising
  one per test.
- **Crash-before-commit (I-A1).** Leans on Track 1's `ensureFileForReplay`; reuses the
  `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore pattern.
- **Read-site enumeration (D19, T3/R3).** Decomposition confirms only the two named
  `SchemaShared.lock`-based hot reads (`createVertexWithClass`, `getLowerSubclass`)
  remain non-snapshot; every other production `getSchema()` reader must be already
  snapshot-routed, off the commit-contended hot path, or itself a schema-write path.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
- **In scope**: `AbstractStorage.commit` (the schema-carry branch, the four-lock
  acquisition, reconciliation, the commit-local id allocator, promotion, the trailing
  `forceSnapshot`); the `doAddIndexEngine` / `doDeleteIndexEngine(atomicOperation, …)`
  extraction plus a lock-free `doGetIndexEngine(int)` resolver for the commit window
  (T1); the deferred / failure-reverting in-memory registry publication for the create
  primitives (A1/R1); the selective per-class write in `SchemaShared.toStream` keyed on
  `getChangedClasses()` (the D6 write-amplification win and its F59 root-omission guard);
  the provisional-id sites (the `collectionsToClasses` reverse-map re-key and the
  `collectionId < 0` vs `<= -2` predicate split); the two snapshot-first read
  conversions (`YTDBGraphImplAbstract.createVertexWithClass`,
  `SQLMatchStatement.getLowerSubclass`); commit/rollback/crash/concurrency tests.
- **Out of scope**: the index overlay's contents and the commit-time index build
  (Track 5 — this track publishes the overlay but the overlay machinery and the build
  scan are Track 5's); the freezer gate and the mutex permit handshake (Track 7);
  collection-name generation and rename (Track 6).
- **Inter-track dependencies**: depends on Track 1 (clean replay for the crash-recovery
  half of I-A1), Track 2 (per-class records to write and re-parse at promotion), and
  Track 3 (the tx-local view to diff and promote, and the engaged mutex to acquire the
  four locks under). Track 5 builds its commit-time engine creation and overlay publish
  on this track's reconciliation; Track 6's rename rides this track's commit-only
  application; Track 7 hardens the locking this track establishes.
- **Signatures**: `doAddIndexEngine(atomicOperation, …)` / `doDeleteIndexEngine(atomicOperation, …)`
  extracted from the public methods, and a lock-free `doGetIndexEngine(int)` for the
  commit window; a commit-local structural-id allocator (collection and engine axes)
  seeded inside the write lock; the schema-carry branch reading the unified
  schema-or-index signal at commit entry.

## Base commit
1dd9c0424f40e7aa9ec90858f6eb4b235f3a2c5f
