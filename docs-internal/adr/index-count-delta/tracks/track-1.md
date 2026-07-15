# Track 1: Delta holder infrastructure, engine changes, and O(1) counters

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review (2/3 iterations)

## Base commit
`5e40edc1d7`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step: Add `IndexCountDelta` and `IndexCountDeltaHolder` classes, wire into `AtomicOperation`
  - [x] Context: unavailable
  > **What was done:** Created `IndexCountDelta` (public mutable pair:
  > `totalDelta`, `nullDelta`) and `IndexCountDeltaHolder` (wraps
  > `HashMap<Integer, IndexCountDelta>` with `getOrCreate(engineId)` and
  > `getDeltas()` — mirroring `HistogramDeltaHolder`). Added
  > `getIndexCountDeltas()` / `getOrCreateIndexCountDeltas()` to the
  > `AtomicOperation` interface and implemented in
  > `AtomicOperationBinaryTracking` (lazily allocated `@Nullable` field,
  > same pattern as `histogramDeltas`). Added `IndexCountDeltaHolderTest`
  > with 10 tests covering lazy creation, identity stability, engine
  > isolation, unmodifiable view, self-containment, boundary IDs, and
  > net delta accumulation.
  > **Key files:** `IndexCountDelta.java` (new), `IndexCountDeltaHolder.java`
  > (new), `AtomicOperation.java` (modified), `AtomicOperationBinaryTracking.java`
  > (modified), `IndexCountDeltaHolderTest.java` (new)

- [x] Step: Add `approximateNullCount` field and O(1) counter methods to both engines
  - [x] Context: unavailable
  > **What was done:** Added `approximateNullCount` (`AtomicLong`) to both
  > engines. Changed `getTotalCount()` and `getNullCount()` from full
  > visibility-filtered scans to O(1) counter reads. Added
  > `addToApproximateEntryCount(long)` and `addToApproximateNullCount(long)`
  > to `BTreeIndexEngine` interface. Updated `load()` in both engines to
  > use visibility-filtered scans (SV: streaming count without materializing;
  > MV: separate svTree/nullTree scans). Updated `create()`, `clear()`, and
  > `buildInitialHistogram()` to manage both counters. Rewrote 5 stale
  > `BTreeEngineHistogramBuildTest` tests that tested the old scan-based
  > implementation. Added counter recalibration assertions to
  > `buildInitialHistogram` tests. Added `addTo*` accumulation and
  > independence tests.
  > **Key files:** `BTreeIndexEngine.java` (modified),
  > `BTreeSingleValueIndexEngine.java` (modified),
  > `BTreeMultiValueIndexEngine.java` (modified),
  > `BTreeEngineHistogramBuildTest.java` (modified)

- [x] Step: Replace eager counter mutations with delta accumulation in both engines
  - [x] Context: unavailable
  > **What was done:** Replaced all 8 direct `approximateIndexEntriesCount`
  > increment/decrement calls with delta accumulation on
  > `IndexCountDeltaHolder` via `atomicOperation.getOrCreateIndexCountDeltas()
  > .getOrCreate(id)`. Added `nullDelta` accumulation for null keys at the
  > same code points. For `BTreeMultiValueIndexEngine`, added `isNullKey`
  > boolean parameter to `doPut`/`doRemove`, passed from callers that already
  > branch on `key != null`. Fixed test fixtures to mock
  > `getOrCreateIndexCountDeltas()`. Added 8 delta accumulation tests
  > covering: new entry, null key, remove, tombstone resurrection, live-entry
  > update (no delta), and multi-value null/non-null routing.
  > **Key files:** `BTreeSingleValueIndexEngine.java` (modified),
  > `BTreeMultiValueIndexEngine.java` (modified),
  > `BTreeEngineHistogramBuildTest.java` (modified)

- [x] Step: Add `applyIndexCountDeltas()` to `AbstractStorage` commit path
  - [x] Context: unavailable
  > **What was done:** Added `applyIndexCountDeltas(AtomicOperation)` method
  > to `AbstractStorage`, called after `endTxCommit()` before
  > `applyHistogramDeltas()`. Iterates the delta holder, resolves engine
  > by ID, calls `addToApproximateEntryCount(delta.totalDelta)` and
  > `addToApproximateNullCount(delta.nullDelta)`. Wrapped in try-catch
  > with warning log (same resilience pattern). Committed the
  > `DuplicateUniqueIndexChangesTxTest` changes that verify rollback
  > safety — these now pass with the full delta pipeline.
  > **Key files:** `AbstractStorage.java` (modified),
  > `DuplicateUniqueIndexChangesTxTest.java` (modified)
