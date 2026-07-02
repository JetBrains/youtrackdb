<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: BC1, sev: suggestion, loc: TxSchemaState.java:257, anchor: "### BC1 ", cert: C1, basis: "provisional-id allocator asserts the wrong bound; >32766 tx-created collections in one tx fail at insert with a misleading RID error"}
  - {id: BC2, sev: suggestion, loc: AbstractStorage.java:2504, anchor: "### BC2 ", cert: C2, basis: "create+insert+drop of the same class in one tx makes the commit-time rewrite throw on an unresolved provisional id; verify this is the intended loud failure"}
  - {id: BC3, sev: suggestion, loc: RecordIdInternal.java:204, anchor: "### BC3 ", cert: C3, basis: "latent -2,-2 null-sentinel collision in RID serialize/deserialize now that -2 is a live collection id; no production caller today"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 3}
cert_index:
  - {id: C1, verdict: MATCHES, anchor: "#### C1 "}
  - {id: C2, verdict: PLAUSIBLE, anchor: "#### C2 "}
  - {id: C3, verdict: MATCHES, anchor: "#### C3 "}
  - {id: C4, verdict: WRONG, anchor: "#### C4 "}
  - {id: C5, verdict: WRONG, anchor: "#### C5 "}
  - {id: C6, verdict: WRONG, anchor: "#### C6 "}
  - {id: C7, verdict: WRONG, anchor: "#### C7 "}
  - {id: C8, verdict: WRONG, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### BC1 [suggestion] Provisional-id allocator guards the wrong exhaustion bound; the real limit surfaces as a misleading RID error

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (line 256-265), with `core/src/main/java/com/jetbrains/youtrackdb/internal/core/id/RecordIdInternal.java` (line 178-189)

**Issue**: Step 4 makes a record id carry a provisional collection id, and the RID collection id serializes as a short (`fromStream` reads `readShort` / `getAsShort`; `checkCollectionLimits` now floors at `Short.MIN_VALUE`). But `allocateProvisionalCollectionId` decrements `nextProvisionalCollectionId` from `-2` with the only guard being `assert isProvisionalCollectionId(allocated)` (that is, `allocated <= -2`). That assert catches integer wraparound past `Integer.MIN_VALUE`, not the `-32768` floor the RID short actually imposes. After ~32766 tx-created (or abstract-to-concrete-altered) collections in one transaction the allocator hands out `-32769`; the class create succeeds, and the failure only surfaces when an entity is inserted into that class: `assignAndCheckCollection` returns `-32769`, `addRecordOperation` calls `setCollectionAndPosition(-32769, pos)`, and `checkCollectionLimits` throws `DatabaseException("RecordId cannot support collection id smaller than -32768")`. The message points at RID serialization, not the real cause (too many classes created in one transaction), and the allocator docstring (TxSchemaState.java:75-76) claims the space effectively reaches `Integer.MIN_VALUE`, which now contradicts the short-width constraint Step 4 introduces.

**Evidence**: Allocator at TxSchemaState.java:257 (`nextProvisionalCollectionId--`) with the `<= -2`-only assert at 262-263; short floor at RecordIdInternal.java:179 and the short read in `fromStream` at RecordIdInternal.java:82. Insert path: `assignAndCheckCollection` (DatabaseSessionEmbedded.java:3034) -> `addRecordOperation` (FrontendTransactionImpl.java:538) -> `ChangeableRecordId.setCollectionAndPosition` -> `checkCollectionLimits`.

**Refutation considered**: See cert C1. The trigger (>32766 classes in a single transaction) is extreme and the failure is loud with no corruption, so this is a robustness/diagnosability gap, not a data-safety bug.

**Suggestion**: Add an explicit floor check in `allocateProvisionalCollectionId` (fail when `allocated < Short.MIN_VALUE`) with an error that names the cause (too many collections created in one transaction), and correct the docstring so the effective provisional space is stated as the short range, not the int range.

### BC2 [suggestion] create + insert + drop of the same class in one transaction makes the commit-time rewrite throw on an unresolved provisional id

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2502-2507)

**Issue**: `rewriteProvisionalRecordCollectionIds` looks each record's provisional collection id up in `txSchemaState.getResolvedCollectionId(...)` and throws `StorageException("... resolved no real collection ...")` when the lookup returns `NO_RESOLUTION`. A resolution is recorded only for a collection that reconciliation actually creates. If a transaction creates class C (allocating a provisional id), inserts a record of C, then drops C in the same transaction, reconciliation creates nothing for C, so the still-present record operation carries an unresolved provisional id and the whole commit fails here. The throw itself is the correct defensive behavior (it stops a provisional id from reaching durable bytes) and this is not a Step-4 regression (the pre-Step-4 path also failed, via `doGetAndCheckCollection(-1)` after the INVALID collection-override missed the dropped class). What is worth confirming is whether this sequence is reachable and whether a whole-commit `StorageException` is the intended outcome, versus the drop cancelling the record operation so the commit succeeds with the record simply gone.

**Evidence**: NO_RESOLUTION throw at AbstractStorage.java:2503-2506; resolution map populated only by `reconcileCollections` for created collections; drop path is de-guarded to ride the tx (Track 3). I did not confirm whether `dropClass` of a tx-created class purges that class's in-tx record operations, so reachability is unverified.

**Refutation considered**: See cert C2. If `dropClass` already removes the tx-created class's record operations, the scenario cannot arise and this finding is void; otherwise the commit fails loudly, which is safe but a rough edge.

**Suggestion**: Confirm the create+insert+drop-in-one-tx path either purges the record operations (commit succeeds, record gone) or fails with an error that names the drop-of-a-class-with-uncommitted-rows cause rather than a generic reconciliation message; add a test pinning whichever semantic is chosen.

### BC3 [suggestion] Latent -2,-2 null-sentinel collision in RID serialize/deserialize now that -2 is a live collection id

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/id/RecordIdInternal.java` (line 191-213)

**Issue**: `serialize(RID, DataOutput)` encodes a null RID as `(collection = -2, pos = -2)`, and `deserialize` returns `null` when it reads exactly `(-2, -2)`. Step 4 makes `-2` a legitimate collection id on live RID objects for the first time (`PROVISIONAL_COLLECTION_ID_CEILING == -2`), and a new record's temp position can also be exactly `-2` (`newRecordsPositionsGenerator` decrements). So a `#-2:-2` provisional record id collides with the null sentinel. PSI confirms `RecordIdInternal.serialize` and `RecordIdInternal.deserialize` have no production callers today (test-only, `RecordIdTest`), so there is no live corruption path in this diff. The finding is that Step 4 removes the property that formerly made the sentinel safe (before this step no record ever carried collection `-2`), so the "a provisional-carrying RID never passes through `serialize`/`deserialize`" invariant is now load-bearing and undocumented; a later change that wires a nullable-RID field through these methods would silently drop a provisional record id as null.

**Evidence**: Null sentinel written at RecordIdInternal.java:193-194 and matched at :204; provisional ceiling `-2` at SchemaShared.java:108; temp position source `newRecordsPositionsGenerator--` at FrontendTransactionImpl.java:538. PSI `ReferencesSearch` over both static methods returned only `RecordIdTest` (no production callers).

**Refutation considered**: See cert C3. Not currently reachable (no production caller), and the primary RID wire format (`toStream`/`fromStream`, short-based) has no `-2,-2` sentinel, so durable bytes are unaffected. Flagged because the precondition is newly true.

**Suggestion**: Either document the invariant at the `serialize`/`deserialize` declarations (must not be used for a RID that can carry a provisional collection id), or narrow the sentinel now that `-2` is a real collection id (for example key null on a position that can never be a valid temp position, or an explicit boolean flag byte).

## Evidence base

#### C1 [MATCHES] Allocator exhaustion assert vs the RID short floor
Confirmed real: the allocator's `<= -2` assert (TxSchemaState.java:262) does not guard the `Short.MIN_VALUE` floor the RID short serialization enforces (RecordIdInternal.java:179, :82); >~32766 tx-created collections in one transaction produce `-32769`, and insertion throws a misleading `checkCollectionLimits` `DatabaseException`. Loud, no corruption; robustness/diagnosability gap.

#### C2 [PLAUSIBLE] Unresolved provisional id on create+insert+drop-in-one-tx
Reported as plausible: `rewriteProvisionalRecordCollectionIds` throws on `NO_RESOLUTION` (AbstractStorage.java:2503-2506) for a record whose tx-created class was dropped in the same transaction (no reconciliation resolution). Reachability hinges on whether `dropClass` purges the tx-created class's record operations, which I did not verify; the throw is safe defensive behavior, so at worst this is a rough edge, not corruption.

#### C3 [MATCHES] Newly-true precondition for the -2,-2 null-sentinel collision
Confirmed latent: PSI shows `serialize`/`deserialize` are test-only (no production caller), so no live path exists, but Step 4 makes `#-2:-2` a legitimate live RID for the first time, removing the property that kept the sentinel safe. The load-bearing invariant is now undocumented.

#### C4 [WRONG] Forward-scan direction for a provisional collection is mishandled
Hypothesis: `RecordIteratorCollection.initialize` special-cases only the backward branch for a provisional id (calls `moveTxIdBackward` directly), so a forward scan of a provisional collection might skip the tx phase or hit the null storage iterator. Checked: for the forward branch, `moveTxIdForward` sets `nextTxId` from the tx records first, and only if it is null does `initStorageIterator` run, which returns early for a provisional id (storageIterator stays null); `hasNext` then terminates cleanly after the tx phase. The backward branch needs the explicit `moveTxIdBackward` precisely because the non-provisional backward path defers the tx phase until storage exhausts, and with no storage that phase would never fire. Both directions are correct; the asymmetry is necessary, not a bug. Verdict: not a bug.

#### C5 [WRONG] Plan-cache bypass leaks a provisional plan across sessions or leaves a stale entry
Hypothesis: `YqlExecutionPlanCache` get/put bypass keyed on `db.getTxSchemaState()` could still let a provisional-shaped plan enter the shared cache or serve a stale one. Checked: `put` is bypassed for the whole schema/index tx (line 99-104), so a plan built during the tx never enters the cache; `get` is bypassed too (line 140-148), so during the tx the session always re-plans against its tx-local snapshot; `FetchFromClassExecutionStep.canBeCached()` returning false on a provisional scan set (consulted in production via `SelectExecutionPlan`/`DeleteExecutionPlan`) is a redundant second guard; and `onSchemaUpdate`/`onIndexManagerUpdate` invalidate the whole cache at commit, flushing any pre-tx entry that a committed schema change would stale. `getTxSchemaState()` is session-local and the Guava cache is internally thread-safe, so no shared-state race is introduced. Verdict: not a bug.

#### C6 [WRONG] A provisional id reaches computeCommitWorkingSet (assert false-positive or prod failure)
Hypothesis: a provisional collection id could survive to `computeCommitWorkingSet`, tripping the new assert under `-ea` or failing `doGetAndCheckCollection` in production. Checked: `rewriteProvisionalRecordCollectionIds` (line 2679) runs over a copy of every record operation before the working-set build (line 2684), and no code between them adds a record operation; the schema and index records enrolled earlier live in real (metadata) collections, not provisional ones; and a pure-data commit (schemaContext == null) cannot contain a tx-created class, so it cannot carry a provisional id. The only escape is an unresolved provisional id, which throws in the rewrite (that is finding BC2), never reaching the assert. Verdict: not a bug.

#### C7 [WRONG] order by @rid over a mixed provisional/real scan set produces a wrong order
Hypothesis: a polymorphic `order by @rid` scan whose set mixes a real parent collection with a provisional subclass collection orders rows incorrectly. Checked: negative provisional ids sort before positive real ids (ascending) or after (descending) consistently in both `FetchFromClassExecutionStep` and `RecordIteratorCollections` (which re-sorts and reverses for backward), so the within-transaction order is coherent; after commit the reconciled real ids replace the provisional ones and the next query re-plans. Exact cross-collection rid order for uncommitted provisional rows is not a documented guarantee, and the tests assert set membership/counts, not order. Verdict: not a bug (semantic nuance only).

#### C8 [WRONG] Link fields pointing at a tx-created record are not rewritten from provisional to real
Hypothesis: the commit rewrites only a record's own identity, so a link field in another record that points at a tx-created (provisional) record keeps the provisional id. Checked: the collection-id rewrite goes through `ChangeableRecordId.setCollectionAndPosition`, the same identity-change-listener machinery the pre-existing temp-position rewrite uses at the allocation loop (AbstractStorage.java:2740); a link that holds the record's `ChangeableRecordId` instance sees the change, and the position-rewrite pass runs before serialization (`commitEntry` at line 2753), so any propagation that worked for temp positions works for the collection id too. The collection dimension is now rewritten by the same mechanism rather than resolved late via the INVALID collection-override. Verdict: not a bug (reuses existing, tested machinery).
