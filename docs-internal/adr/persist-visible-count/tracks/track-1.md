# Track 1: Entry point page extension + BTree visible count API

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
- [x] Track-level code review (1/3 iterations)

## Base commit
`c618a83c7d`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Add APPROXIMATE_ENTRIES_COUNT field to CellBTreeSingleValueEntryPointV3
  - [x] Context: unavailable
  > **What was done:** Inserted `APPROXIMATE_ENTRIES_COUNT` 8-byte long field
  > after `TREE_SIZE` in `CellBTreeSingleValueEntryPointV3`, shifting
  > `PAGES_SIZE_OFFSET` (to 49) and `FREE_LIST_HEAD_OFFSET` (to 53).
  > Added getter/setter. Updated `init()` to set it to 0. Removed legacy
  > `getFreeListHead()` backward-compat guard (`if head == 0 return -1`).
  > Added 9 unit tests covering: init defaults, round-trip, large values
  > with neighbor corruption checks, interleaved write-read independence,
  > shifted offset verification, `getFreeListHead(0)` regression guard,
  > negative values, and adjacent long field overlap at full bit-width.
  >
  > **Key files:**
  > - `core/.../singlevalue/v3/CellBTreeSingleValueEntryPointV3.java` (modified)
  > - `core/.../singlevalue/v3/CellBTreeSingleValueEntryPointV3Test.java` (new)

- [x] Step 2: Add visible count methods to CellBTreeSingleValue interface and BTree
  - [x] Context: unavailable
  > **What was done:** Added `getApproximateEntriesCount`,
  > `setApproximateEntriesCount`, `addToApproximateEntriesCount` to
  > `CellBTreeSingleValue` interface. Implemented in `BTree`: get uses
  > `executeOptimisticStorageRead` (matching `size()`), set/addTo use
  > `executeInsideComponentOperation` + `loadPageForWrite` (matching
  > `updateSize()`). Added BTree-level integration test in `BTreeTestIT`
  > covering set/get round-trip, additive delta semantics, and negative
  > delta decrement.
  >
  > **Key files:**
  > - `core/.../singlevalue/CellBTreeSingleValue.java` (modified)
  > - `core/.../singlevalue/v3/BTree.java` (modified)
  > - `core/.../singlevalue/v3/BTreeTestIT.java` (modified)

- [x] Step 3: Rename existing BTreeIndexEngine methods to plural EntriesCount
  - [x] Context: unavailable
  > **What was done:** Renamed `addToApproximateEntryCount` →
  > `addToApproximateEntriesCount` on `BTreeIndexEngine` interface, both
  > engine implementations, `AbstractStorage` call site, and
  > `BTreeEngineHistogramBuildTest`. Left `addToApproximateNullCount`
  > unchanged (already consistent naming). All 45 affected tests pass.
  >
  > **Key files:**
  > - `core/.../v1/BTreeIndexEngine.java` (modified)
  > - `core/.../v1/BTreeSingleValueIndexEngine.java` (modified)
  > - `core/.../v1/BTreeMultiValueIndexEngine.java` (modified)
  > - `core/.../AbstractStorage.java` (modified)
  > - `core/.../v1/BTreeEngineHistogramBuildTest.java` (modified)
