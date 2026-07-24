# Gate verification — Track 7 Step 1 review-fix commit (iteration 1)

- **Fix commit**: `1063e1d987` "Harden failed-commit undo and commit-time schema reads"
- **Branch**: `transactional-schema`
- **Gate mode**: read-only, verdict-only; no Maven runs (another thread owns test execution);
  all verdicts derived from independent code re-reads, not from implementer/fixer reasoning.
- **Files in fix diff**: `DatabaseSessionEmbedded.java`, `SchemaShared.java`,
  `AbstractStorage.java`, `SchemaCommitReconciliationTest.java`.

---

## BG1 — slot-reuse create-undo destroyed the restored collection's structure — **VERIFIED**

### Premises

1. `undoReconciledCollections` (AbstractStorage.java:3435-3467) builds `droppedIds` from **all**
   `DroppedCollection` entries this reconciliation produced (`for (final var entry : dropped)`),
   then for each commit-created resolved id calls
   `revertCreatedCollectionStructure(realId, collection, droppedIds.contains((int) realId))`
   (AbstractStorage.java:3464).
2. `revertCreatedCollectionStructure` (AbstractStorage.java:3535-3585) on `slotReused == true`
   deletes **only** the created collection's own name-keyed data files, guarded by
   `collection.exists(atomicOperation)` (name-keyed: `PaginatedCollectionV2.exists` →
   `isFileExists(atomicOperation, getFullName())`, PaginatedCollectionV2.java:304-306), and
   returns before any id-keyed delete (`configuration.dropCollection(realId)`,
   `deleteComponentByCollectionId(realId)`).
3. `reconcileCollections` (AbstractStorage.java:3263-3340) processes **all drops before all
   creates**, and drops only committed-schema ids; on the failure path the committed schema is
   never promoted, so `droppedIds ⊆ committedIds` always holds and the discriminator is exact.

### Discriminator correctness across orders (probed)

- **Multiple drops + multiple creates**: `droppedIds` is the full drop set of this reconciliation;
  each created resolved id is tested against the whole set. A create landing in any this-commit
  freed slot is classified reused; a create landing in an append slot or pre-existing hole is
  classified fresh. Order-independent. ✓
- **Slot freed by an earlier committed transaction** (the closest reading of "dropped by a
  DIFFERENT reconciliation phase" — collection slots are only freed by `reconcileCollections`
  drops within a commit; the index-engine phase frees engine slots, never collection slots):
  `droppedIds` does not contain it → fresh-slot path → id-keyed
  `LinkCollectionsBTreeManagerShared.isComponentPresent(realId)` discriminator. Sound there,
  because the earlier committed drop durably deleted every id-keyed structure at that slot — the
  only possible owner of an id-named component after this rollback is the failed create itself
  (in-memory engine eager install). No survivor exists to damage. ✓
- **Drop happened, create never ran (throw between reconcile phases)**: the unresolved provisional
  has no entry in `getResolvedCollectionIds()`, the create-undo loop never visits the slot, and
  the drop-restore arm re-registers the original occupant. ✓
- **Create-then-drop of the same class inside the tx**: the tx-local schema no longer owns the
  provisional at commit; `reconcileCollections` skips the create (`ownedProvisionalIds` guard,
  AbstractStorage.java:3311-3320), so no undo arm ever sees it. ✓

### Alternative hypothesis probed and rejected: name-collision in the name-keyed cleanup

If the created collection's data-file *name* could equal the restored collection's, the
slot-reuse branch's `exists()`/`delete()` would destroy the restored data file. This cannot
happen: collection names are generated exclusively as `c_<counter>` by
`SchemaShared.nextCollectionName` (SchemaShared.java:1544-1549) under the schema write lock, the
counter is monotonic, skips squatted names via `getCollectionIdByName(candidate) != -1`, and only
advances durably on commit — so a committed collection named `c_<n>` implies the durable counter
is past `n`, and a tx-created candidate can never reuse a committed collection's name. The
metadata-write mutex excludes a concurrent schema tx from allocating the same candidate. The
restored (committed) collection's files are therefore never reachable through the created
collection's name. ✓

### Supporting premise verified: the id-named component "create" is an un-delete

`AtomicOperationBinaryTracking.addFile` (AtomicOperationBinaryTracking.java:844-895): a file name
present in `deletedFileNameIdMap` (deleted earlier in the same operation — exactly the dropped
collection's `global_collection_<id>.grb`) is re-added with `isNew = false`, and the eager
in-memory install (`readCache.addFile` + `eagerlyInstalledInCache`) runs **only** on the
`isNew` path. So on both engines the reused component entry is pure buffered intent that reverts
with the rollback, leaving the restored collection's component durable — the Javadoc's premise at
AbstractStorage.java:3515-3527 is accurate.

### Regression-test red check

Without the fix (pre-fix code path), the slot-reuse test's undo would take the id-keyed branch:
`isComponentPresent(realId)` is **true** after rollback (the restored component), so the cleanup
would durably run `configuration.dropCollection(realId)` +
`deleteComponentByCollectionId(realId)` inside a fresh committing atomic operation, on the
default in-memory test engine. The new probe
`linkBagComponentPresent(droppedCollectionId)` (SchemaCommitReconciliationTest.java, slot-reuse
test tail) would then read **false** → `assertTrue` fails. The test is genuinely red on
regression. ✓

**Verdict: VERIFIED.**

---

## CN27+BG2 — endTxCommit catch undid possibly-durable state — **VERIFIED**

### Premises

1. The commit finally's no-error branch wraps exactly one call, `endTxCommit(atomicOperation)`
   (AbstractStorage.java:3097), and gates the registry undo on
   `atomicOperation.isRollbackInProgress()` (AbstractStorage.java:3100-3113); the no-rollback
   shape leaves the publication standing, calls `setInError(e)`, logs, and rethrows.
2. `endTxCommit` (AbstractStorage.java:6427-6455) contains only: the pre-durability test hook
   (which ends the operation **with** the injected error before rethrowing → rollback flag set),
   `endAtomicOperation(atomicOperation, null)`, and the post-durability test hook (fires after a
   successful end → rollback flag false).

### Throw-site enumeration inside `endAtomicOperation(op, null)` (AtomicOperationsManager.java:279-460)

| Site | rollback flag at escape | durable? | Gate branch | Correct? |
|---|---|---|---|---|
| Hook A persist failure (caught, `rollbackInProgress()` set, `commitChanges` skipped, rethrown after inner-finally) | **true** | no (commitChanges never ran) | undo | ✓ shape (1) |
| `commitChanges` throw | false | **in-doubt** (WAL end record may have flushed before a mid-cache-apply failure) | poison + stand | ✓ shape (2) |
| `atomicOperationsTable.commitOperation` / `persistOperation` / `writeAheadLog.addEventAt` throw | false | yes (commitChanges returned) | poison + stand | ✓ conservative-correct |
| inner-finally `releaseLocks` / `operation.deactivate()` throw on success path | false | yes | poison + stand | ✓ conservative-correct |
| VM errors (OOM, LinkageError, StackOverflow) | n/a | n/a | escape the `IOException\|RuntimeException\|AssertionError` catch entirely | pre-existing stance, unchanged |

Key invariant verified: `rollbackInProgress == true` ⇒ nothing durable. The only two setters on
this path (inbound error — the pre-durability hook shape — and Hook A's catch) both fire strictly
**before** `commitChanges`, and the asserted guard at AtomicOperationsManager.java:360-362 pins
that a rolled-back operation never reaches `commitChanges`. So the undo branch can never fire on
a durable commit, and the poison branch covers everything else. The gate covers **every** throw
site funneling through the single `endTxCommit` call, not just the two hook shapes. ✓

### Known, documented residuals (not defects of this fix)

- `setInError` skips `AssertionError` (AbstractStorage.java:2063-2089): an assert escaping
  `endTxCommit` in a `-ea` run takes the poison branch without actually flipping error state.
  Deliberate, documented in the catch comment (AbstractStorage.java:3092-3095); production runs
  `-ea` off; the rethrow still fails the commit loudly.
- `publishReconciledIndexes` (AbstractStorage.java:3083) and the promotion sit after the gated
  call; promotion is guarded (log + `setInError`, AbstractStorage.java:3172-3186), the index
  publication is not. That site **pre-exists this fix** (verified present in `1063e1d987^`) and
  is outside the CN27+BG2 finding — noted for the orchestrator, not a regression.

### Regression-test red check

`endTxCommitFailureWithoutRollbackKeepsPublicationAndPoisonsStorage`: with the gate removed
(regression = unconditional undo), the undo would run after a **durable** commit — the created
collection would be de-registered and its (durable) structure durably deleted by the cleanup arm,
and the dropped collection re-registered. The test's
`assertFalse(namesAfter.contains(droppedCollectionName))` and the `added.isEmpty()` check both
fail, and `checkErrorState()` (AbstractStorage.java:6060-6070) would not throw →
`assertTrue(errorState)` fails. Red on regression along three independent assertions. ✓
(`getCollectionNames`/registry reads do not route through `checkErrorState` — its only
production call site is component-lock acquisition, AtomicOperationsManager.java:524 — so the
test's post-failure registry assertions are reachable.)

**Verdict: VERIFIED.**

---

## TQ1 — tests asserted only in-memory registry state — **VERIFIED**

1. `linkBagComponentPresent(int)` probe added (read-only atomic operation →
   `LinkCollectionsBTreeManagerShared.isComponentPresent`), asserting the restored collection's
   durable id-named component survives both the slot-reuse create-undo and the endTxCommit-shape
   undo.
2. `assertClassWritableAndReadable(String)` added (insert + read-back round-trip in fresh txs),
   asserting restored-collection usability, applied in both tests; the endTxCommit test
   additionally asserts the restored index serves a lookup for a freshly inserted row.
3. Red-on-regression traced for the slot-reuse probe (see BG1) and for the endTxCommit test's
   engine arms (see TQ2): the durable probes observe exactly the damage the registry-only
   assertions were blind to.

**Verdict: VERIFIED.**

---

## CN26+CN29 — weak-cache eviction / page-stamp fallback stale reads at commit — **VERIFIED**

### Premises

1. Three new fresh-committed-read scopes: commit-time `toStream` (AbstractStorage.java:2843-2846),
   `enrollReconciledIndexRecords` (AbstractStorage.java:2873-2878), promotion re-parse
   (AbstractStorage.java:3166-3170). The seed scope in `SchemaShared.copyForTx`
   (SchemaShared.java:289) pre-existed.
2. Inside a scope, every load path is anchored fresh: cache miss →
   `storage.readRecord(rid, getEffectiveReadAtomicOperation())` rides the scope's operation
   (DatabaseSessionEmbedded.java:3657-3662); cache hit → `refreshRecordFromFreshCommittedRead`
   re-fills the instance in place (DatabaseSessionEmbedded.java:2189-2207, 3680-3712); vanished
   record → evict + not-found. Dirty (tx-working-copy) instances and tx-record-set hits are
   exempt by design.
3. CN29's page-stamp fallback: `EntityImpl.rePopulateSourceBytes` resolves its re-read through
   `session.getEffectiveReadAtomicOperation()` (EntityImpl.java:3780-3790), so a stamp-invalidated
   re-read inside any of the new scopes rides the fresh operation, not the begin-time snapshot.
4. Promotion side effect verified: the commit's own operation is `deactivate()`d inside
   `endAtomicOperation`'s inner finally, so a pre-fix cache-miss promotion load via
   `getActiveTransaction().getAtomicOperation()` would hit an inactive operation on the disk
   profile; the scope's operation is active and its table snapshot is taken **after**
   `commitOperation`, so it observes the just-committed state. The healed-racer claim is
   structurally consistent.
5. The scope snapshot for promotion cannot lose entries to vacuum: `resetTsMin` fires only at
   frontend-transaction close (FrontendTransactionImpl.java:1014), which is after
   `applyCommitOperations` returns, so the begin-time `tsMin` pin is still held during all four
   scope uses; additionally the held `stateLock.writeLock` excludes any superseding commit.

**Verdict: VERIFIED** (strong-pinning alternative rejected in favor of scope-wrapping is a sound
trade: the refresh-in-place path preserves the one-instance-per-rid invariant without pinning the
whole schema record set strongly for the tx lifetime).

---

## CQ1+CN28 — assert-only non-reentrancy — **VERIFIED** (no regression)

1. Always-on `IllegalStateException` at DatabaseSessionEmbedded.java:3630-3635, with a correct
   rationale comment (a silently nested scope's finally would clear the field and strand the
   outer scope on the stale snapshot).
2. **Nesting probe** — the scope now has four callers; can two legitimately nest?
   - Seed (`copyForTx`) runs from `ensureTxSchemaState` at the tx's **first schema write**
     (DatabaseSessionEmbedded.java:3517-3548) — strictly before commit, sequential, and
     `ensureTxSchemaState` short-circuits on the existing `TX_SCHEMA_STATE_KEY`
     (DatabaseSessionEmbedded.java:3522-3525), so `copyForTx` can never re-run inside a
     commit-time scope (a schema-carry commit implies the state exists).
   - `toStream` scope and enrollment scope are strictly sequential inside
     `applyCommitOperations` (the first scope's lambda returns before the second starts; the
     schema-write-lock release in the finally sits **outside** the scope lambda).
   - Promotion scope runs after `endTxCommit`, sequential.
   - No consumer's lambda transitively calls `computeWithFreshCommittedReads`: the only
     call sites in production are the four listed (grep-verified); loads inside the lambdas go
     through `executeReadRecord`/`rePopulateSourceBytes`, which only **read** the scope field.
   - Sessions are single-threaded; the field is per-session.
   Conclusion: the always-on throw cannot fire on a legitimate path. No RG finding.
3. Exception safety: the scope's finally clears the field and deactivates the operation, so a
   throwing lambda cannot leak an active scope into the next caller.

**Verdict: VERIFIED.**

---

## CN31 — comments — **VERIFIED** (skip rationale sound)

1. The endTxCommit catch comment (AbstractStorage.java:3060-3096) now describes both failure
   shapes accurately — matches the traced `endAtomicOperation` behavior above, including the
   in-doubt durability of a `commitChanges` throw and the deliberate `setInError` AssertionError
   skip.
2. The vacuum-safety (tsMin) premise is documented on the scope API
   (DatabaseSessionEmbedded.java:3622-3627) and is factually correct: `startAtomicOperation`
   registers nothing thread- or table-side (AtomicOperationsManager.java:102-125 — snapshot
   capture only; `startToApplyOperations` is the registering call and never runs for the scope),
   and the consumers all run inside an active transaction whose `tsMin` pin persists until
   `FrontendTransactionImpl` closes.
3. **IndexHistogramManager skip**: the imprecise "…and tsMin tracking" phrase in the rebalance
   scan comment (IndexHistogramManager.java:1684-1686) pre-exists this track, sits in a file
   untouched by the step-1 diff, and nothing in the fix makes it *more* wrong. Deferring a
   suggestion-severity comment fix in an out-of-diff file is consistent with the repo's
   diff-scoped review discipline. Skip rationale sound.

**Verdict: VERIFIED.**

---

## TQ2 — endTxCommit test lacks index-engine arms — **VERIFIED**

1. `endTxCommitFailureAfterReconcileRunsRegistryUndo` now sets up an indexed class, drops the
   committed index `EndFailIndexed.name` and creates `EndFailIndexed.name2` inside the failing
   transaction, alongside the collection drop+create — so `indexOverlay` is non-null,
   `indexPlan` is non-null, and the catch's `undoSchemaCarryRegistryPublication` runs the engine
   create-undo, engine drop-restore, and membership-revert arms (AbstractStorage.java:3395-3407).
2. Assertions pin both arms (`getIndex("EndFailIndexed.name")` non-null,
   `getIndex("EndFailIndexed.name2")` null) plus durable usability (restored-index lookup serves
   a fresh row). On a regression that skips the engine arms, the dropped index stays missing
   (`assertNotNull` fails) or the phantom stays registered (`assertNull` fails). Red on
   regression. ✓

**Verdict: VERIFIED.**

---

## CQ2 — refresh retry-loop RNFE asymmetry — **VERIFIED**

Javadoc added at DatabaseSessionEmbedded.java:3673-3679. The justification is correct by
construction: the retry loop re-reads the **same immutable snapshot** through the same
`freshCommittedReadOperation`; only `OptimisticReadFailedException` (page-stamp invalidation
during byte extraction) is retried, and a not-found on a snapshot that already answered would be
a snapshot-stability bug that must escape loudly. The initial read's `RecordNotFoundException`
handling (return false → evict) remains the only intended not-found path.

**Verdict: VERIFIED.**

---

## CN30 — cache-miss eviction path relaxation — **VERIFIED**

Comment added at DatabaseSessionEmbedded.java:2197-2203 documenting the deliberate, bounded
relaxation of the one-instance-per-rid invariant: `localCache.deleteRecord(rid)` removes the
cache entry without unloading the instance, so a stale holder keeps a usable instance while a
later load materializes a fresh one — only for a rid the committed state no longer contains.
Matches the code exactly.

**Verdict: VERIFIED.**

---

## New RG findings

None. Specifically probed and cleared:

- **Same-name slot-reuse counterexample** (name-keyed delete hitting the restored collection's
  data file): impossible — counter-generated `c_<n>` collection names cannot collide between a
  tx-create and any committed collection (BG1 §alternative hypothesis).
- **Scope nesting on a legitimate path** tripping the new always-on throw: impossible — the four
  callers are strictly sequential and `ensureTxSchemaState` short-circuits (CQ1+CN28 §2).
- **Undo branch firing on a durable commit**: impossible — `rollbackInProgress == true` strictly
  implies `commitChanges` never ran (CN27+BG2 invariant table).

### Non-blocking observations for the orchestrator

1. `publishReconciledIndexes` (AbstractStorage.java:3083) throwing after a durable commit is an
   unguarded pre-existing site (present in the parent commit) — partial shared-map publication
   without error-state poisoning. Outside the step-1 finding set and untouched by the fix;
   consider filing for a later hardening pass.
2. The post-durability failure shape leaves the in-memory committed schema un-promoted and index
   phase-3 unpublished while the collection registry publication stands; the error-state poison
   makes this safe only because writes fail fast until reopen. This is the documented design of
   the fix, not a defect — recorded here so the divergence window is not rediscovered as a bug.

---

## Verdict summary

| Finding | Verdict |
|---|---|
| BG1 | VERIFIED |
| CN27+BG2 | VERIFIED |
| TQ1 | VERIFIED |
| CN26+CN29 | VERIFIED |
| CQ1+CN28 | VERIFIED |
| CN31 | VERIFIED |
| TQ2 | VERIFIED |
| CQ2 | VERIFIED |
| CN30 | VERIFIED |
