# Persist Approximate Index Entries Count — Architecture Decision Record

## Summary

Eliminates the O(n) full BTree visibility-filtered scan that ran on every
`load()` of `BTreeSingleValueIndexEngine` and `BTreeMultiValueIndexEngine`
during database open. Instead, persists the approximate visible entry count
on each BTree's entry point page (`APPROXIMATE_ENTRIES_COUNT` field) and
reads it in O(1) on load. Makes database startup time independent of index
size.

## Goals

- **O(1) load()**: Replace the per-index full scan with a single page read.
  Achieved as planned.
- **WAL atomicity**: Persisted count must be updated within the same WAL
  atomic operation as index mutations. Achieved — `persistIndexCountDeltas`
  runs between `commitIndexes()` and `endTxCommit()`.
- **Preserve deferred delta pattern**: Reuse existing `IndexCountDelta` /
  `IndexCountDeltaHolder` infrastructure. Achieved — persistence is a new
  step in the commit flow, not a restructuring.

No goals were descoped or changed during execution.

## Constraints

- **No backward compatibility**: Databases are always started from scratch.
  No VALID flag or migration needed.
- **One field per entry point page**: Single `APPROXIMATE_ENTRIES_COUNT`
  (8-byte long). Multi-value engine derives null/non-null split from
  separate trees' fields.
- **Deferred delta pattern preserved**: No eager per-operation updates.

No constraints were relaxed during execution. One new implicit constraint
emerged: zero-delta guards are needed in `persistCountDelta` to avoid
unnecessary page writes on the commit path when a transaction touches an
index but the net visible count change is zero (e.g., insert + remove of
the same key).

## Architecture Notes

### Component Map

```mermaid
graph LR
    AS[AbstractStorage] -->|persistCountDelta| BIE[BTreeIndexEngine]
    BIE -->|addToApproximateEntriesCount| BT[BTree]
    BT -->|read/write| EP[EntryPointV3 page]
    AS -->|applyIndexCountDeltas| BIE
```

- **CellBTreeSingleValueEntryPointV3**: `APPROXIMATE_ENTRIES_COUNT` at byte
  offset 41, after `TREE_SIZE`. Shifted `PAGES_SIZE` to 49, `FREE_LIST_HEAD`
  to 53. Raw getter/setter with non-negative assert.
- **CellBTreeSingleValue / BTree**: `get`/`set`/`addTo` methods wrapping
  entry point page I/O. `get` uses optimistic read, `set`/`addTo` use
  component write operation.
- **BTreeIndexEngine**: `persistCountDelta(AtomicOperation, long, long)` —
  single-value forwards full `totalDelta`; multi-value splits across
  `svTree` (totalDelta - nullDelta) and `nullTree` (nullDelta). Zero-delta
  calls are skipped.
- **AbstractStorage**: `persistIndexCountDeltas()` called inside the commit
  try block after `commitIndexes()`. Mirrors `applyIndexCountDeltas`
  defensive checks.

### Decision Records

#### D1: One APPROXIMATE_ENTRIES_COUNT field per entry point — no VALID flag, no NULL_APPROXIMATE_ENTRIES_COUNT

- **Implemented as planned**. Zero-initialized bytes are correct for new
  empty indexes. Multi-value engine's separate trees provide the null/non-null
  split naturally. Single-value null count starts at 0 on load, recalibrated
  by `buildInitialHistogram()`.

#### D2: Persist within atomic operation (between commitIndexes and endTxCommit)

- **Implemented as planned**. Entry point page is already dirty from
  `TREE_SIZE` updates during `commitIndexes`, so the additional write has
  minimal I/O cost. Failure rolls back the entire transaction — this is
  intentional per the design (counts must match data).

#### D3: Zero-delta guards on persistCountDelta (new — emerged during track-level code review)

- **Rationale**: Performance review identified that unconditional
  `addToApproximateEntriesCount` calls execute `loadPageForWrite` + WAL
  record even when the delta is zero, adding unnecessary overhead on the
  commit path. Guarding with `!= 0` skips the page write entirely.
- **Applied to**: Both engine implementations. Multi-value guards
  `nonNullDelta` and `nullDelta` independently.

### Invariants

- After a committed transaction, the persisted `APPROXIMATE_ENTRIES_COUNT`
  on each entry point page equals the count of visibility-filtered entries
  in the corresponding BTree.
- After `load()`, `approximateIndexEntriesCount` equals the sum of persisted
  counts across the engine's trees.
- After `create()` and `clear()`, `APPROXIMATE_ENTRIES_COUNT` is 0 on all
  entry point pages.
- `APPROXIMATE_ENTRIES_COUNT` is always updated within the same WAL atomic
  operation as the BTree mutations — never outside.

### Non-Goals

- Backward compatibility with existing databases.
- Persisting the null count separately for single-value indexes.
- Changing the deferred delta pattern to eager per-operation updates.
- Optimizing `buildInitialHistogram()` — it still does a full scan for
  histogram construction.

## Key Discoveries

- **Legacy backward-compat guard removed**: `getFreeListHead()` had a guard
  (`if head == 0 return -1`) for databases created before the free list
  feature. Shifting the offset made this guard incorrect (would read from
  the middle of the new field). Removed safely since no backward
  compatibility is required.

- **Naming inconsistency fixed**: The existing
  `BTreeIndexEngine.addToApproximateEntryCount` (singular) was inconsistent
  with the new `APPROXIMATE_ENTRIES_COUNT` page field (plural). Renamed to
  `addToApproximateEntriesCount` for consistency across the API.

- **clear() + persistIndexCountDeltas ordering**: `clear()` resets the
  persisted base to 0 during `commitIndexes()`. The `IndexCountDeltaHolder`
  is NOT reset on clear — `persistIndexCountDeltas` adds only deltas from
  replayed entries after the clear, yielding the correct final count. This
  ordering dependency is implicit and relies on `commitIndexes()` always
  running before `persistIndexCountDeltas()`.

- **buildInitialHistogram() persistence ordering**: The
  `setApproximateEntriesCount` call is placed before `mgr.buildHistogram()`
  so that count persistence is not conditional on histogram construction
  success. If the histogram build fails, the recalibrated count is still
  persisted.

- **Multi-value buildInitialHistogram must persist independently**: The
  multi-value engine must call `setApproximateEntriesCount` on both `svTree`
  (with `nonNullCount`) and `nullTree` (with `nullCount`) separately — not
  just the total. Getting these swapped would silently corrupt persisted
  counts.
