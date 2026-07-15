# Track 1: Extract `checkVisibility()` in IndexesSnapshot

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (1/3 iterations — all findings resolved)

## Base commit
`2d6eb20c33`

## Reviews completed
- [x] Technical

## Steps

- [x] Step: Extract snapshot lookup helper and add `checkVisibility()` method
  - [x] Context: unavailable
  > **What was done:** Extracted `lookupSnapshotRid()` from
  > `emitSnapshotVisibility()` as a package-private method returning
  > `@Nullable RID`. Refactored `emitSnapshotVisibility()` to delegate to
  > it (now emits the original B-tree key instead of snapshot-derived key).
  > Added `checkVisibility()` public method encapsulating the full visibility
  > decision. Added 15 unit tests covering all branches: committed, in-progress,
  > phantom paths for each RID type, plus boundary, cross-key leak, and
  > single-value key tests.
  >
  > **Key files:** `IndexesSnapshot.java` (modified),
  > `IndexesSnapshotVisibilityFilterTest.java` (modified)

- [x] Step: Refactor `visibilityFilterMapped()` to delegate to `checkVisibility()`
  - [x] Context: unavailable
  > **What was done:** Replaced 39 lines of inline visibility logic in
  > `visibilityFilterMapped()`'s mapMulti lambda with 3-line delegation to
  > `checkVisibility()`. Both the stream path and future direct path now share
  > `checkVisibility()` as the single source of truth. Added 2 keyMapper tests
  > verifying non-identity key mapping on both the committed and snapshot
  > fallback paths. All 81 regression tests pass (53 visibility filter +
  > 7 SI get + 21 SI unique).
  >
  > **Key files:** `IndexesSnapshot.java` (modified),
  > `IndexesSnapshotVisibilityFilterTest.java` (modified)
