# Track 2: Tests for index tombstone GC

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (1/3 iterations)

## Base commit
`08fe533746`

## Reviews completed
- [x] Technical

## Steps
- [x] Step: Fix test infrastructure and add missing tests
  - [x] Context: unavailable
  > **What was done:** Added snapshot cleanup in `afterMethod` (T1). Added
  > `testTombstonesAtExactLwmArePreserved` (T2) and
  > `testNoGhostResurrectionAfterGC` (T3). Added explanatory comment on weak
  > `isLessThan` assertion (T4). Review fixes: guarded `indexEngineNameMap`
  > access with `stateLock.writeLock()` (BC1), strengthened assertions with
  > pre-condition checks and lower-bound thresholds (TB2/TB3/TB6), added
  > demoted marker identity spot-check (TB4), renamed
  > `testGCRunsAtMostOncePerInsert` to match actual behavior (TB1), added
  > mixed entry types test (TC1) and sort order verification test (TC5).
  >
  > **What was discovered:** GC triggers during the initial insertion phase
  > itself — bucket overflows during tombstone/marker insertion cause GC on
  > already-inserted entries. This means `tombstonesBefore < FILL_COUNT` and
  > markers may already be demoted before the live entry insertion phase.
  > Assertions were adjusted to accommodate this behavior.
  >
  > **Key files:** `BTreeTombstoneGCTest.java` (modified)

- [x] Step: Commit test file and verify coverage
  - [x] Context: unavailable
  > **What was done:** Ran coverage with `BTreeTombstoneGCTest` only. Initial
  > result: 84.4% line / 80.0% branch — line coverage 0.6% below threshold.
  > Added 2 edge-case tests for `getIndexSnapshotByEngineName` null-return and
  > `hasActiveIndexSnapshotEntries` false-return paths for unknown engines.
  > After review, added 2 more tests: `getNullIndexSnapshotByEngineName`
  > unknown engine and `$null` suffix path in `hasActiveIndexSnapshotEntries`.
  > Also added positive assertion for known engine name. Final coverage: 95.6%
  > line / 90.0% branch — well above thresholds.
  >
  > **What was discovered:** The remaining uncovered lines (4/90) are defensive
  > paths: BTree non-CompositeKey fallback (3 lines) and one null-return in
  > AbstractStorage. These are extremely unlikely paths in production — the
  > non-CompositeKey path requires a BTree used with non-versioned keys,
  > which doesn't happen for index engines.
  >
  > **Key files:** `BTreeTombstoneGCTest.java` (modified)
