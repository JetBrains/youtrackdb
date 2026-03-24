# Track 1: ConcurrentLongIntHashMap — Core Data Structure

## Progress
- [x] Review + decomposition
- [ ] Step implementation (4/5 complete)
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

- [x] Step 3: compute(), remove(), conditional remove()
  > **What was done:** Implemented compute() with full ConcurrentHashMap semantics (4 branches),
  > remove() returning previous value, conditional remove with reference equality (==), and
  > backward-sweep compaction in removeAt() — eliminates tombstones by shifting displaced
  > entries back to fill gaps. 19 new tests (64 total) including all compute branches, probe
  > chain integrity after removal, drain-all-entries in non-sequential order, single-entry
  > removal with reinsert, compute resize, function-throws consistency, (0,0) key lifecycle.
  >
  > **What was discovered:** Bugs & concurrency review verified the backward-sweep algorithm
  > (isBetween circular distance formula) is correct with no issues. Code quality review
  > noted the terminology should be "backward-sweep compaction" not "tombstone cleanup"
  > since the algorithm prevents tombstones entirely. removeAt does not need hashMix
  > parameter — it recomputes hash for each candidate entry in the sweep.
  >
  > **Key files:** `ConcurrentLongIntHashMap.java` (modified), `ConcurrentLongIntHashMapTest.java` (modified)

- [x] Step 4: removeByFileId() with same-capacity rehash compaction
  > **What was done:** Implemented removeByFileId(long) sweeping each section linearly
  > under write lock. Matching entries collected into returned List<V> (deferred consumer
  > model). After sweep, always performs same-capacity rehash to restore probe chain
  > integrity. 12 new tests (76 total) covering: only target file removed, correct values
  > returned, empty map, non-existent fileId, compaction with interleaved entries,
  > reinsertion after removal, many entries across sections, fileId=0 edge case,
  > remove-all-then-fill to threshold, consecutive calls, repeated cycles, idempotent
  > double-removal.
  >
  > **What changed from the plan:** Plan specified conditional compaction (tombstone ratio
  > threshold). Implemented unconditional same-capacity rehash instead — simpler and
  > always correct since removeByFileId uses bulk nullification (not backward-sweep per
  > entry), leaving gaps that must be compacted. The cost is acceptable since
  > removeByFileId is called on file close/truncate/delete, not a latency-sensitive path.
  >
  > **Key files:** `ConcurrentLongIntHashMap.java` (modified), `ConcurrentLongIntHashMapTest.java` (modified)

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
