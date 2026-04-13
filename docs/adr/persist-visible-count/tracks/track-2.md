# Track 2: Persist visible counts in engine lifecycle + commit flow

## Progress
- [x] Review + decomposition
- [x] Step implementation (5/5 complete)
- [x] Track-level code review (1/3 iterations)

## Base commit
`2364c58b77`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Add `persistCountDelta` to `BTreeIndexEngine` and implement in both engines
  - [x] Context: unavailable
  > Add `persistCountDelta(AtomicOperation atomicOp, long totalDelta, long nullDelta)`
  > to the `BTreeIndexEngine` interface. No `throws IOException` — BTree
  > component operations wrap I/O exceptions into `RuntimeException`.
  >
  > **BTreeSingleValueIndexEngine** implementation: calls
  > `sbTree.addToApproximateEntriesCount(atomicOp, totalDelta)`. The single
  > `totalDelta` applies to the single tree (null entries are in the same BTree).
  >
  > **BTreeMultiValueIndexEngine** implementation: splits the delta across two
  > trees: `svTree.addToApproximateEntriesCount(atomicOp, totalDelta - nullDelta)`
  > for non-null entries, `nullTree.addToApproximateEntriesCount(atomicOp, nullDelta)`
  > for null entries.
  >
  > Unit tests: verify single-value forwards full delta, verify multi-value
  > splits delta correctly (mock or use real BTree from test infrastructure).
  >
  > **What was done:** Added `persistCountDelta(AtomicOperation, long totalDelta,
  > long nullDelta)` to `BTreeIndexEngine` interface. Single-value implementation
  > forwards full `totalDelta` to `sbTree.addToApproximateEntriesCount`. Multi-value
  > splits: `svTree` gets `totalDelta - nullDelta`, `nullTree` gets `nullDelta`.
  > No `throws IOException` — BTree component operations wrap I/O exceptions.
  > 9 unit tests with mock-based fixtures covering positive/negative/zero deltas,
  > all-null, no-null, and nullDelta-exceeds-totalDelta scenarios. All tests use
  > `verifyNoMoreInteractions` for strict mock verification.
  >
  > **Key files:**
  > - `core/.../index/engine/v1/BTreeIndexEngine.java` (modified)
  > - `core/.../index/engine/v1/BTreeSingleValueIndexEngine.java` (modified)
  > - `core/.../index/engine/v1/BTreeMultiValueIndexEngine.java` (modified)
  > - `core/.../index/engine/v1/BTreeEnginePersistCountDeltaTest.java` (new)

- [x] Step 2: Add `persistIndexCountDeltas` to `AbstractStorage` commit flow
  - [x] Context: unavailable
  > Add private method `persistIndexCountDeltas(AtomicOperation)` to
  > `AbstractStorage`. Mirror defensive checks from `applyIndexCountDeltas`:
  > skip engines with out-of-bounds IDs, null entries, or non-`BTreeIndexEngine`
  > instances (review finding T4/R1).
  >
  > For each valid engine+delta pair, call
  > `engine.persistCountDelta(atomicOp, delta.totalDelta, delta.nullDelta)`.
  >
  > Call `persistIndexCountDeltas(atomicOperation)` in the `commit()` method's
  > try block, after `commitIndexes()` (line ~2117) and before the catch clause
  > (line ~2118). This places it inside the WAL atomic operation — any failure
  > triggers the existing rollback path (design decision D2).
  >
  > **On failure semantics (review finding R2):** Per design decision D2, failure
  > rolls back the transaction. This is intentional — persisted counts must match
  > index data. The non-negative assert in BTree.addToApproximateEntriesCount
  > is the safety net; if it fires, it indicates a bug in delta accumulation
  > that should not be silently masked.
  >
  > Tests: verify `persistIndexCountDeltas` is called during commit (integration
  > test: insert entries, commit, read entry point page to verify persisted
  > count matches expected value).
  >
  > **What was done:** Added `persistIndexCountDeltas(AtomicOperation)` private
  > method to `AbstractStorage`, mirroring `applyIndexCountDeltas` defensive
  > checks (bounds, null, instanceof). Called inside the commit try block after
  > `commitIndexes()` and before the catch clause — within the WAL atomic
  > operation. Any failure triggers the existing rollback path (design decision
  > D2). No dedicated unit tests for this method — it is simple iteration code
  > and the full flow will be covered by Step 5 integration tests.
  >
  > **Key files:**
  > - `core/.../storage/impl/local/AbstractStorage.java` (modified)

- [x] Step 3: Rewrite engine `load()` methods to read persisted counts
  - [x] Context: unavailable
  > **BTreeSingleValueIndexEngine.load()** (lines 147-175): Replace the
  > visibility-filtered scan (lines 164-173) with:
  > `approximateIndexEntriesCount.set(sbTree.getApproximateEntriesCount(atomicOp))`,
  > `approximateNullCount.set(0)` (corrected by `buildInitialHistogram()`).
  > Remove the `indexesSnapshot.visibilityFilterMapped()` call and partition
  > logic from load.
  >
  > **BTreeMultiValueIndexEngine.load()** (lines 181-219): Replace the two
  > visibility-filtered scans (lines 197-214) with:
  > `long svCount = svTree.getApproximateEntriesCount(atomicOp)`,
  > `long nullCount = nullTree.getApproximateEntriesCount(atomicOp)`,
  > `approximateIndexEntriesCount.set(svCount + nullCount)`,
  > `approximateNullCount.set(nullCount)`.
  > Remove the scan logic from load.
  >
  > Tests: integration test verifying counts survive clean restart — insert
  > entries (including nulls for multi-value), commit, close database, reopen,
  > verify `getTotalCount` and `getNullCount` return correct values without
  > scanning. Test both engine types.
  >
  > **What was done:** Replaced O(n) visibility-filtered scans in both engine
  > load() methods with O(1) reads from persisted APPROXIMATE_ENTRIES_COUNT.
  > Single-value reads sbTree count, sets null count to 0 (per D1: recalibrated
  > by buildInitialHistogram). Multi-value reads svTree and nullTree counts
  > independently. Added non-negative assert guards on loaded counts. Updated
  > field comments to reflect the new initialization mechanism. Net: -45 lines,
  > +12 lines of production code.
  >
  > **Key files:**
  > - `core/.../index/engine/v1/BTreeSingleValueIndexEngine.java` (modified)
  > - `core/.../index/engine/v1/BTreeMultiValueIndexEngine.java` (modified)

- [x] Step 4: Update `clear()` and `buildInitialHistogram()` to persist counts
  - [x] Context: unavailable
  > **clear() — both engines:** After `doClearTree()` completes (review finding
  > R5: call after clear, not before), call
  > `setApproximateEntriesCount(atomicOp, 0)` on each tree. For single-value:
  > `sbTree.setApproximateEntriesCount(atomicOp, 0)`. For multi-value:
  > `svTree.setApproximateEntriesCount(atomicOp, 0)` and
  > `nullTree.setApproximateEntriesCount(atomicOp, 0)`.
  >
  > **Ordering note (review finding T2):** `setApproximateEntriesCount(0)` in
  > `clear()` resets the persisted base. `IndexCountDeltaHolder` is not reset
  > on clear — `persistIndexCountDeltas` adds only deltas from replayed entries
  > after the clear, yielding the correct final count.
  >
  > **buildInitialHistogram() — both engines:** Call
  > `setApproximateEntriesCount(atomicOp, count)` immediately after the scan
  > completes, alongside the existing `AtomicLong.set()` calls, BEFORE
  > `mgr.buildHistogram()` (review finding R3).
  >
  > For single-value (lines 565-566): add
  > `sbTree.setApproximateEntriesCount(atomicOp, total)` where `total` is the
  > visibility-filtered count already computed.
  >
  > For multi-value (lines 600-601): add
  > `svTree.setApproximateEntriesCount(atomicOp, nonNullCount)` and
  > `nullTree.setApproximateEntriesCount(atomicOp, nullCount)` (review finding
  > T3: must distinguish both trees).
  >
  > **What was done:** Added `setApproximateEntriesCount(atomicOp, 0)` calls to
  > clear() in both engines after doClearTree completes. Added
  > `setApproximateEntriesCount(atomicOp, count)` calls to buildInitialHistogram()
  > in both engines after scan completes, before mgr.buildHistogram(). Multi-value
  > correctly persists svTree and nullTree counts independently (nonNullCount and
  > nullCount). Added verify() calls to 6 existing histogram/clear tests to assert
  > the new persistence calls happen with correct values.
  >
  > **Key files:**
  > - `core/.../index/engine/v1/BTreeSingleValueIndexEngine.java` (modified)
  > - `core/.../index/engine/v1/BTreeMultiValueIndexEngine.java` (modified)
  > - `core/.../index/engine/v1/BTreeEngineHistogramBuildTest.java` (modified)

- [x] Step 5: Integration tests — persist-through-restart and delta persistence
  - [x] Context: unavailable
  > End-to-end integration tests covering the full lifecycle:
  >
  > 1. **Count survives restart (single-value):** Create index, insert entries
  >    (including null key), commit, close DB, reopen, verify getTotalCount
  >    returns correct value without scanning.
  >
  > 2. **Count survives restart (multi-value):** Create index, insert both null
  >    and non-null entries, commit, close DB, reopen, verify both
  >    getTotalCount and getNullCount are correctly restored from persisted
  >    counts on both trees (review finding T5).
  >
  > 3. **Delta accumulation across multiple transactions:** Insert entries in TX1,
  >    commit, insert more in TX2, commit, verify persisted count reflects
  >    cumulative delta. Then remove some entries in TX3, commit, verify count
  >    decremented correctly.
  >
  > 4. **Clear + re-insert:** Insert entries, commit, clear index in new TX,
  >    re-insert fewer entries, commit, verify persisted count matches
  >    re-inserted count (not original count).
  >
  > 5. **buildInitialHistogram recalibration:** After restart, trigger
  >    buildInitialHistogram, verify persisted count is updated to match scan.
  >
  > Test scope (review finding R6): Tests verify clean restart persistence.
  > WAL crash recovery is guaranteed by the existing WAL mechanism (entry point
  > page writes within atomic operation) — no crash injection needed.
  >
  > **What was done:** Created `BTreeEnginePersistedCountIT` with 5 end-to-end
  > integration tests exercising the full lifecycle through the database API:
  > single-value count survives restart, delta accumulation across multiple
  > transactions, multi-value null/non-null counts survive restart independently,
  > clear + rebuild yields correct count, empty index reports zero count. Tests
  > use reflection to access BTreeIndexEngine from the index to verify counts.
  >
  > **Key files:**
  > - `core/.../index/engine/v1/BTreeEnginePersistedCountIT.java` (new)
