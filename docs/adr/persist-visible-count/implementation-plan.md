# Persist Approximate Index Entries Count

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Eliminate the O(n) full BTree scan that runs on every `load()` of
`BTreeSingleValueIndexEngine` and `BTreeMultiValueIndexEngine` during database
open. Instead, persist the visibility-filtered entry count on the BTree entry
point page and read it in O(1) on load.

This makes database open time independent of index size — critical for large
production databases where the current scan makes startup impractically slow.

### Constraints

- **No backward compatibility.** Databases are always started from scratch;
  existing databases are not opened with the new code.
- **WAL atomicity.** The persisted visible count must be part of the same WAL
  atomic operation as the index mutations. No partial state on crash.
- **Deferred delta pattern preserved.** Reuse the existing
  `IndexCountDelta`/`IndexCountDeltaHolder` infrastructure. Add persistence
  as a new step in the commit flow, not restructure the delta mechanism.
- **One field per entry point page.** A single `APPROXIMATE_ENTRIES_COUNT` (8-byte long)
  per BTree entry point. No validity flag (no backward compat). No separate
  null count field (multi-value engine derives null count from nullTree's own
  `APPROXIMATE_ENTRIES_COUNT`). The nullTree's persisted count tracks visible
  (non-tombstone) entries, matching the existing visibility-filtered semantics
  used by `buildInitialHistogram()`.

### Architecture Notes

#### Component Map

```mermaid
graph LR
    AS[AbstractStorage] -->|persistCountDelta| BIE[BTreeIndexEngine]
    BIE -->|addToApproximateEntriesCount| BT[BTree]
    BT -->|read/write| EP[EntryPointV3 page]
    AS -->|applyIndexCountDeltas| BIE
```

*Note: The diagram is simplified — `commitIndexes()` reaches the BTree
through `Index → Engine → BTree`, not directly from `AbstractStorage`.*

- **CellBTreeSingleValueEntryPointV3** — gains `APPROXIMATE_ENTRIES_COUNT`
  field at byte offset 41 (after `TREE_SIZE`), shifting `PAGES_SIZE` and
  `FREE_LIST_HEAD` by 8 bytes. Raw getter/setter. `init()` sets to 0.
- **CellBTreeSingleValue / BTree** — gains `getApproximateEntriesCount`,
  `setApproximateEntriesCount`, `addToApproximateEntriesCount` methods. Thin
  wrappers around entry point page I/O. Naming uses plural `EntriesCount`
  to match the `APPROXIMATE_ENTRIES_COUNT` page field. The existing
  `BTreeIndexEngine.addToApproximateEntryCount` should be renamed to
  `addToApproximateEntriesCount` for consistency.
- **BTreeIndexEngine** — gains `persistCountDelta(AtomicOperation, long, long)`.
  Each implementation maps total/null deltas to its tree(s).
- **BTreeSingleValueIndexEngine** — `load()` reads persisted count instead of
  scanning. `create()`, `clear()`, `buildInitialHistogram()` persist absolute
  counts.
- **BTreeMultiValueIndexEngine** — same as single-value but splits delta
  across `svTree` and `nullTree`. `load()` reads from both trees and
  composites.
- **AbstractStorage** — gains `persistIndexCountDeltas()` called between
  `commitIndexes()` and `endTxCommit()`.

#### D1: One APPROXIMATE_ENTRIES_COUNT field per entry point — no VALID flag, no NULL_APPROXIMATE_ENTRIES_COUNT

- **Alternatives considered**:
  - (a) Three fields: VALID flag + APPROXIMATE_ENTRIES_COUNT + NULL_APPROXIMATE_ENTRIES_COUNT
  - (b) Two fields: VALID flag + APPROXIMATE_ENTRIES_COUNT
  - (c) One field: APPROXIMATE_ENTRIES_COUNT only
- **Rationale**: No backward compatibility means no need for a VALID flag
  (zero-initialized bytes are correct for a new empty index). Multi-value
  engine has separate svTree and nullTree — each tree's own APPROXIMATE_ENTRIES_COUNT
  gives the null/non-null split naturally. Single-value null count (always
  0 or 1) is not worth a dedicated field.
- **Risks/Caveats**: Single-value engine's null count starts at 0 on load,
  temporarily inaccurate by at most 1 until `buildInitialHistogram()`
  recalibrates. The null key is stored as `CompositeKey(null, version)` in
  the main BTree file (`.cbt`), not in the BTree's internal null bucket
  file (`.nbt`), so deriving it cheaply on load is not possible.
- **Implemented in**: Track 1, Track 2

#### D2: Persist within atomic operation (between commitIndexes and endTxCommit)

- **Alternatives considered**:
  - (a) Persist within atomic operation (WAL-protected) — chosen
  - (b) Persist in a separate atomic operation after commit
  - (c) Persist on close/checkpoint only, scan on crash recovery
  - (d) Eager per-operation updates (PaginatedCollectionV2 pattern)
- **Rationale**: Option (a) gives crash safety with minimal change —
  entry point page is already dirty from TREE_SIZE updates during
  commitIndexes. Options (b) and (c) add recovery complexity. Option (d)
  would require restructuring the delta mechanism. The deferred approach
  also coalesces multiple operations into one page write per engine per
  transaction.
- **Risks/Caveats**: If `persistIndexCountDeltas` fails, the transaction
  rolls back. This is correct (counts must match data) but differs from the
  current post-commit pattern where delta failure is non-fatal.
- **Implemented in**: Track 2

#### Invariants

- After a committed transaction, the persisted `APPROXIMATE_ENTRIES_COUNT` on each entry
  point page equals the count of visibility-filtered entries in the
  corresponding BTree.
- After `load()`, `approximateIndexEntriesCount` equals the sum of persisted
  `APPROXIMATE_ENTRIES_COUNT` values across the engine's trees.
- After `create()` and `clear()`, `APPROXIMATE_ENTRIES_COUNT` is 0 on all entry point
  pages of the engine.
- `APPROXIMATE_ENTRIES_COUNT` is always updated within the same WAL atomic operation as
  the BTree mutations — never outside.

#### Non-Goals

- Backward compatibility with existing databases
- Persisting the null count separately for single-value indexes (always
  0 or 1, corrected by `buildInitialHistogram()`; multi-value derives it
  from nullTree's own APPROXIMATE_ENTRIES_COUNT)
- Changing the deferred delta pattern to eager per-operation updates
- Optimizing `buildInitialHistogram()` scan — it still does a full scan for
  histogram construction; persisting counts only eliminates the `load()` scan

## Checklist

- [x] Track 1: Entry point page extension + BTree visible count API
  > Add `APPROXIMATE_ENTRIES_COUNT` field (8-byte long) to
  > `CellBTreeSingleValueEntryPointV3` at byte offset 41 (after
  > `TREE_SIZE`), shifting `PAGES_SIZE` and `FREE_LIST_HEAD` by 8 bytes.
  > Add raw getter/setter. Update `init()` to set it to 0.
  >
  > Add three methods to `CellBTreeSingleValue` interface and implement in
  > `BTree`: `getApproximateEntriesCount(AtomicOperation)`,
  > `setApproximateEntriesCount(AtomicOperation, long)`,
  > `addToApproximateEntriesCount(AtomicOperation, long)`. These wrap entry point page
  > read/write using the existing `loadPageForRead`/`loadPageForWrite`
  > pattern (same as `size()` and `updateSize()`). The multi-value engine
  > reuses `CellBTreeSingleValue` for both `svTree` and `nullTree`, so
  > these interface additions serve both engines.
  >
  > Unit tests for entry point page field and BTree visible count
  > read/write/add operations.
  >
  > **Scope:** ~3 steps covering entry point page field, BTree API methods,
  > unit tests
  >
  > **Track episode:**
  > Added APPROXIMATE_ENTRIES_COUNT 8-byte long field to entry point page
  > at offset 41, shifting PAGES_SIZE and FREE_LIST_HEAD by 8 bytes.
  > Removed legacy backward-compat guard in getFreeListHead(). Added
  > get/set/addTo ApproximateEntriesCount to CellBTreeSingleValue interface
  > and BTree using existing optimistic-read / page-write patterns. Renamed
  > addToApproximateEntryCount → addToApproximateEntriesCount on
  > BTreeIndexEngine for consistency. Added non-negative assert guards at
  > page and BTree levels. No plan deviations or cross-track impact.
  >
  > **Step file:** `tracks/track-1.md` (3 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected.

- [x] Track 2: Persist visible counts in engine lifecycle + commit flow
  > Add `persistCountDelta(AtomicOperation, long totalDelta, long nullDelta)`
  > to `BTreeIndexEngine` interface. Implement in
  > `BTreeSingleValueIndexEngine` (applies `totalDelta` to `sbTree`) and
  > `BTreeMultiValueIndexEngine` (splits: `totalDelta - nullDelta` to
  > `svTree`, `nullDelta` to `nullTree`).
  >
  > Add `persistIndexCountDeltas(AtomicOperation)` to `AbstractStorage`,
  > called inside the `commit()` method's try block after `commitIndexes()`
  > and before the catch clause — so that any failure triggers the existing
  > rollback path.
  > Iterates `IndexCountDeltaHolder` and calls `persistCountDelta` on each
  > engine. Failure rolls back the transaction (unlike the post-commit
  > `applyIndexCountDeltas` which is non-fatal).
  >
  > Update engine `load()` methods to read persisted counts from entry point
  > pages instead of scanning:
  > - Single-value: `approximateIndexEntriesCount = sbTree.getApproximateEntriesCount()`,
  >   `approximateNullCount = 0` (corrected by `buildInitialHistogram()`)
  > - Multi-value: `approximateIndexEntriesCount = svTree.getApproximateEntriesCount() + nullTree.getApproximateEntriesCount()`,
  >   `approximateNullCount = nullTree.getApproximateEntriesCount()`
  >
  > Verify `create()` requires no change — BTree `init()` already sets
  > `APPROXIMATE_ENTRIES_COUNT` to 0 (covered by Track 1).
  > Update `clear()` — call `setApproximateEntriesCount(atomicOp, 0)` on each tree.
  > Update `buildInitialHistogram()` — call `setApproximateEntriesCount(atomicOp, count)`
  > after recalibrating from scan.
  >
  > Integration tests: visible count survives restart, delta persistence is
  > WAL-protected, clear/rebuild correctly reset persisted count.
  >
  > **Depends on:** Track 1
  > **Scope:** ~5-6 steps covering engine interface, AbstractStorage commit
  > flow, load() rewrite, create/clear/buildInitialHistogram updates,
  > integration tests
  >
  > **Track episode:**
  > Added persistCountDelta to BTreeIndexEngine + both implementations with
  > zero-delta guards to avoid unnecessary page writes. Added
  > persistIndexCountDeltas to AbstractStorage commit flow inside the WAL
  > atomic operation (design decision D2). Replaced O(n) visibility-filtered
  > scans in both engine load() methods with O(1) reads from persisted
  > APPROXIMATE_ENTRIES_COUNT. Updated clear() and buildInitialHistogram() to
  > persist counts to entry point pages. 50 tests: 9 mock-based unit tests,
  > 35 existing histogram tests (6 strengthened), 6 integration tests
  > (restart, delta accumulation, multi-value, clear+rebuild, empty, rollback).
  > No plan deviations or cross-track impact.
  >
  > **Step file:** `tracks/track-2.md` (5 steps, 0 failed)

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
