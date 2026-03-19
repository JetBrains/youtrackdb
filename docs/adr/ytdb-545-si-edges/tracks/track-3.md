# Track 3: SharedLinkBagBTree write path with SI

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Base commit
`21e93550f6`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [ ] Step 1: Prefix lookup helper in SharedLinkBagBTree
  > Implement `findCurrentEntry(AtomicOperation, long ridBagId,
  > int targetCollection, long targetPosition)` that finds the current
  > (single) B-tree entry for a logical edge using prefix range search.
  >
  > **Approach**: Create search key `EdgeKey(ridBagId, tc, tp, Long.MIN_VALUE)`
  > and call `findBucket(searchKey)`. Since `ts` is the last comparison
  > component, the insertion point lands at the first entry with matching
  > `(ridBagId, tc, tp)` prefix. Check the entry at the insertion point —
  > if its first 3 fields match, that's the current entry. Return
  > `@Nullable RawPair<EdgeKey, LinkBagValue>` (null if not found).
  >
  > **Tests**: Empty tree returns null; single entry found; entry not found
  > (different ridBagId/tc/tp); boundary conditions (first/last entry in
  > bucket); entry after bucket split.
  >
  > **Files**: `SharedLinkBagBTree.java` (modified), test file (new or
  > modified)

- [ ] Step 2: SharedLinkBagBTree.put() with snapshot preservation
  > Modify `put()` to use `findCurrentEntry()` and preserve old versions
  > in the snapshot index before modifying the B-tree.
  >
  > **Approach**:
  > 1. Call `findCurrentEntry()` to find existing entry for the logical edge.
  > 2. If found and `oldTs != newTs` (different transaction):
  >    - Construct `EdgeSnapshotKey(getFileId(), ridBagId, tc, tp, oldTs)`
  >    - Call `atomicOp.putEdgeSnapshotEntry(snapshotKey, oldValue)`
  >    - Construct `EdgeVisibilityKey(oldTs, getFileId(), ridBagId, tc, tp)`
  >    - Call `atomicOp.putEdgeVisibilityEntry(visKey, snapshotKey)`
  >    - Remove old entry from B-tree
  >    - Insert new entry with `EdgeKey(..., newTs)` and new value
  > 3. If found and `oldTs == newTs` (same-transaction overwrite):
  >    - Update in place without snapshot preservation
  >    - Apply existing size-based optimization (updateValue if same length)
  > 4. If not found: insert new entry directly.
  >
  > **Key detail**: `newTs` comes from the EdgeKey parameter's `ts()` field
  > (caller constructs EdgeKey with commitTs). `componentId` = `getFileId()`.
  > Assert `newTs >= oldTs` for version monotonicity.
  >
  > **Tests**: New entry (no old version); update with newer ts (snapshot
  > preserved); same-ts overwrite (no snapshot); verify snapshot index
  > contains old value after put; verify visibility index entry created.
  >
  > **Files**: `SharedLinkBagBTree.java` (modified), test file (modified)

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
