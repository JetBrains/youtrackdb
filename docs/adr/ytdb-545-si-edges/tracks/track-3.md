# Track 3: SharedLinkBagBTree write path with SI

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/4 complete)
- [ ] Track-level code review

## Base commit
`21e93550f6`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Prefix lookup helper in SharedLinkBagBTree
  > **What was done:** Added `findCurrentEntry(AtomicOperation, long ridBagId,
  > int targetCollection, long targetPosition)` to `SharedLinkBagBTree`.
  > Searches with `EdgeKey(ridBagId, tc, tp, Long.MIN_VALUE)` via `findBucket`,
  > checks the entry at the insertion point, and falls back to the right
  > sibling's first entry when the insertion point falls at the bucket
  > boundary. Extracted `checkEntryPrefix` helper for the 3-field match check.
  > 19 tests covering empty tree, boundary ts values, non-matching prefixes,
  > tombstones, first/last entries, post-split lookups (200-500 entries), and
  > each individual field-mismatch branch.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified),
  > `SharedLinkBagBTreeFindCurrentEntryTest.java` (new)

- [x] Step 2: SharedLinkBagBTree.put() with snapshot preservation
  > **What was done:** Modified `put()` to detect cross-transaction updates
  > via `findCurrentEntryInternal` prefix lookup. When an existing entry has
  > a different ts, the old version is preserved in snapshot + visibility
  > indexes via `preserveInSnapshot`, then removed via `removeEntryByKey`,
  > before the standard insert logic runs. Same-ts overwrites skip snapshot
  > preservation. Extracted `findCurrentEntryInternal` from `findCurrentEntry`
  > for use within write operations (no `executeReadOperation` wrapper).
  > Added `crossTxReplacement` flag to override `put()` return value.
  > 8 tests covering new inserts, cross-tx updates (snapshot verification,
  > tree size, multiple sequential updates), same-ts overwrites, independent
  > edge preservation, tombstone preservation, and post-split cross-tx updates.
  >
  > **What was discovered:** Cross-transaction updates must be tested across
  > separate atomic operations (matching production semantics). Within a single
  > uncommitted atomic operation, `findCurrentEntryInternal` cannot find
  > entries written by a previous `put()` call because B-tree page reads
  > within `calculateInsideComponentOperation` don't see uncommitted writes
  > from the same operation. This is correct behavior — in production, the
  > initial insert is always committed before a cross-tx update happens.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified),
  > `SharedLinkBagBTreePutSITest.java` (new)

- [ ] Step 3: SharedLinkBagBTree.remove() with tombstones
  > Modify `remove()` to create tombstone entries instead of physically
  > deleting, preserving old values in the snapshot index.
  >
  > **Approach**:
  > 1. Call `findCurrentEntry()` to find the existing entry.
  > 2. If found and `oldTs != newTs`:
  >    - Preserve old entry in snapshot index (same as put)
  >    - Remove old entry from B-tree
  >    - Insert tombstone: `EdgeKey(ridBagId, tc, tp, newTs)` with
  >      `LinkBagValue(counter, secCollId, secPos, tombstone=true)`
  >    - Do NOT change tree size (remove + insert = net zero)
  >    - Return old value (before tombstone)
  > 3. If found and `oldTs == newTs` (same-transaction remove):
  >    - No snapshot preservation needed
  >    - Replace entry with tombstone in place
  >    - Return old value
  > 4. If not found: return null (nothing to remove).
  >
  > **Key detail**: `newTs` comes from the EdgeKey parameter's `ts()`.
  > The remove() signature stays the same — it receives EdgeKey with
  > commitTs from the caller. Return value is the old LinkBagValue
  > (not the tombstone).
  >
  > **Tests**: Remove existing entry (tombstone created, old value in
  > snapshot); remove non-existent entry (returns null); same-ts remove
  > (no snapshot); verify tree size unchanged after tombstone; verify
  > snapshot index contains old value.
  >
  > **Files**: `SharedLinkBagBTree.java` (modified), test file (modified)

- [ ] Step 4: IsolatedLinkBagBTreeImpl write path wiring + integration tests
  > Wire `IsolatedLinkBagBTreeImpl.put()` and `remove()` to use
  > `atomicOperation.getCommitTs()` instead of `0L`, and add integration
  > tests for the full SI write path.
  >
  > **Approach**:
  > - `put()`: Replace `new EdgeKey(linkBagId, rid.getCollectionId(),
  >   rid.getCollectionPosition(), 0L)` with `...getCommitTs())`
  > - `remove()`: Same replacement for EdgeKey construction
  > - Remove TODO comments from Track 1
  > - `clear()`: No changes needed — already delegates to `bTree.remove()`
  >   which now creates tombstones automatically
  >
  > **Integration tests** (through IsolatedLinkBagBTreeImpl):
  > - Put new edge → verify in B-tree with correct ts
  > - Put existing edge with new tx → verify old value in snapshot index
  > - Remove edge → verify tombstone in B-tree, old value in snapshot
  > - Same-tx put+put → verify no snapshot entry for intermediate write
  > - Same-tx put+remove → verify tombstone, no snapshot entry
  > - clear() → verify tombstones for all entries
  > - Multiple edges → verify each has independent snapshot preservation
  >
  > **Files**: `IsolatedLinkBagBTreeImpl.java` (modified), test file (new
  > or modified)
