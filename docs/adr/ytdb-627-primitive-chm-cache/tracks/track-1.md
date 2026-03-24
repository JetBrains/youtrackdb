# Track 1: ConcurrentLongIntHashMap — Core Data Structure

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/5 complete)
- [ ] Track-level code review

## Base commit
`a3bf557`

## Reviews completed
- [x] Technical
- [x] Risk
- [x] Adversarial

## Review decisions summary

Key decisions from reviews that affect step implementation:

1. **compute() null-return semantics** (T2/R1 — blocker): Must match ConcurrentHashMap.compute()
   contract. Absent key + null return = no-op. Present key + null return = removal. Add unit tests.
2. **compute() passes caller-supplied keys** (R8 — blocker): For absent keys, pass the
   caller's fileId/pageIndex to the remapping function, NOT array contents.
3. **Conditional remove uses reference equality** (T7 — blocker): `remove(fileId, pageIndex, expected)`
   uses `==` not `equals()` for value comparison. Add test.
4. **removeByFileId tombstone compaction** (T3/R4/A7): After bulk sweep, if tombstones exceed
   threshold, do same-capacity rehash. Add unit test.
5. **Configurable segment count** (R7/A10): Constructor takes segment count parameter. Default 16.
6. **Composition over inheritance** (A11/T6): Section has-a StampedLock, not extends.
7. **forEachValue(Consumer)** (T5/A5): Add value-only iteration alongside forEach.
8. **Write ordering** (A2): In put(), value written before key fields. Document in code.
9. **Functional interfaces nested** (T8): Nest LongIntFunction, LongIntObjConsumer,
   LongIntKeyValueFunction inside ConcurrentLongIntHashMap.
10. **hashForFrequencySketch moved to Track 2** (A13/T4): Not in Track 1 scope.
11. **removeByFileId last** (A4): Implement after all base operations pass tests.

## Steps

- [x] Step 1: Class skeleton, Section with StampedLock composition, hash function, and get()
  > **What was done:** Created `ConcurrentLongIntHashMap<V>` with class skeleton, Section
  > inner class (StampedLock composition), Murmur3 hash mixing both key fields, get() with
  > optimistic read + read-lock fallback, size/isEmpty/capacity, three nested functional
  > interfaces, and Apache 2.0 attribution header. 22 unit tests covering constructors,
  > get on empty map, hash distribution (fileIds and pageIndices separately), edge cases
  > (zero/negative/extreme keys), and alignToPowerOfTwo with overflow boundary.
  >
  > **What was discovered:** Optimistic read correctness requires snapshotting all mutable
  > fields (array refs + capacity) to locals after the stamp, before the probe loop — reading
  > fields directly would allow stale reads to pass validation after a concurrent resize.
  > Also, alignToPowerOfTwo overflows to Integer.MIN_VALUE for n > 2^30 — added an explicit
  > guard. LongIntKeyValueFunction type param renamed from V to T to avoid shadowing the
  > outer class type parameter.
  >
  > **Key files:** `ConcurrentLongIntHashMap.java` (new), `ConcurrentLongIntHashMapTest.java` (new)

- [x] Step 2: put(), putIfAbsent(), computeIfAbsent()
  > **What was done:** Implemented put/putIfAbsent/computeIfAbsent with auto-resize
  > (66% fill factor, capacity doubling). Write ordering: value before keys. Null value
  > rejection. 23 new tests (45 total) covering roundtrips, replacement, probe chains
  > (same fileId/different pageIndex), negative keys, exact resize threshold, resize
  > via computeIfAbsent, many-pages-same-file pattern, function-throws consistency.
  >
  > **What was discovered:** Code review identified 3x probe loop duplication —
  > extracted into `probeForKey()` returning bitwise-encoded result. Also found that
  > `findEmptySlot` redundantly recomputed the hash — now passes hashMix through
  > insertAt. Added rehash overflow guard for capacity >= 2^30 and defensive assertion
  > for probe loop invariant (firstEmpty must be non-negative).
  >
  > **Key files:** `ConcurrentLongIntHashMap.java` (modified), `ConcurrentLongIntHashMapTest.java` (modified)

- [ ] Step 3: compute(), remove(), conditional remove()
  > Implement mutation operations:
  > - `compute(long fileId, int pageIndex, LongIntKeyValueFunction<V> fn)` with:
  >   - Passes caller-supplied fileId/pageIndex to remapping function (R8)
  >   - Null return on absent key = no-op (R1/T2)
  >   - Null return on present key = removal (R1/T2)
  >   - Holds segment write lock during function execution
  > - `remove(long fileId, int pageIndex)` — returns removed value or null
  > - `remove(long fileId, int pageIndex, V expected)` — conditional remove using
  >   reference equality `==` (T7), returns boolean
  > - Backward-sweep tombstone cleanup after individual removal
  > - Unit tests: compute on absent key with null return (no-op), compute on present key
  >   with null return (removal), compute with side effects under lock, remove returning
  >   previous value, conditional remove with same reference (succeeds), conditional remove
  >   with equals-but-different reference (fails), probe-through-tombstone after removal (R9)
  >
  > **Key files:** `ConcurrentLongIntHashMap.java`, `ConcurrentLongIntHashMapTest.java`

- [ ] Step 4: removeByFileId() with tombstone compaction
  > Implement bulk removal:
  > - `removeByFileId(long fileId)` — linear sweep per segment under write lock
  > - Collects removed entries into a list, returns `List<V>` after lock release
  >   (deferred consumer model — caller processes entries outside segment lock)
  > - After sweep, if tombstone ratio exceeds threshold (usedBuckets - size > capacity/4),
  >   perform same-capacity rehash for compaction (T3/R4/A7)
  > - Unit tests: removeByFileId with interleaved entries from multiple files (verify only
  >   target file removed), removeByFileId returns correct entries, tombstone compaction
  >   triggers correctly, usedBuckets == size after compaction, removeByFileId on empty
  >   map, removeByFileId for non-existent fileId
  >
  > **Key files:** `ConcurrentLongIntHashMap.java`, `ConcurrentLongIntHashMapTest.java`

- [ ] Step 5: resize, shrink, clear, forEach, forEachValue, and remaining unit tests
  > Complete the API and comprehensive testing:
  > - Explicit `shrink()` method (available but not called automatically)
  > - `clear()` — reset all sections
  > - `forEach(LongIntObjConsumer<V>)` — iterates all entries under read locks
  > - `forEachValue(Consumer<V>)` — value-only iteration (T5/A5)
  > - Doc comment on `compute()` warning about lock-held execution (T9)
  > - Unit tests: resize with many entries (verify all entries survive), shrink reduces
  >   capacity, clear resets size to 0, forEach visits all entries, forEachValue visits
  >   all values, concurrent rehash + get correctness (basic — full stress in Track 3),
  >   large map with many sections, edge case: single-section map
  >
  > **Key files:** `ConcurrentLongIntHashMap.java`, `ConcurrentLongIntHashMapTest.java`
