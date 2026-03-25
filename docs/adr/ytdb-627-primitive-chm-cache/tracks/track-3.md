# Track 3: Concurrent Stress Testing

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
- [x] Track-level code review

## Base commit
`5fdd4ecbcd`

## Reviews completed
- [x] Technical
- [x] Risk

## Review decisions summary

Key decisions from reviews that affect step implementation:

1. **No ConcurrentTestHelper** (T1/R5): Use raw `ExecutorService` + `Future.get(30, SECONDS)`
   pattern, matching `AsyncReadCacheTestIT`. Gives faster failure detection and better
   diagnostics.
2. **clearFile is private — test removeByFileId at map level** (T2/T7): Single-file concurrent
   removal tested via the public `removeByFileId()` on `ConcurrentLongIntHashMap`. Integrated
   cache-level test uses `deleteStorage` for correctness verification.
3. **Separate concurrent test class** (T3): Create `ConcurrentLongIntHashMapConcurrentTest.java`
   in `core/src/test/.../internal/common/collection/`. Keeps the 1688-line single-threaded test
   file clean.
4. **Rehash test: minimal capacity + single section** (T4/R6): Use
   `new ConcurrentLongIntHashMap<>(4, 1)` to maximize rehash frequency. Bounded key space
   (~10K unique keys) to prevent OOM.
5. **Include compute() in operation mix** (T6/R1/R4): `compute()` is used as a virtual lock
   in `doLoad()`. Include slow-compute variant to force optimistic-read fallback.
6. **Iteration counts** (T5): ~100K-500K ops/thread for map-level tests (5-15s runtime),
   scaled down for integrated tests (~50K ops).
7. **Integrated tests are *IT** (R7): Cache-level stress tests use `*IT` suffix with
   `@Category(SequentialTest.class)` to avoid CI flakiness on PR builds.
8. **clearFile/doLoad race** (R2): Pre-existing race where `doLoad` re-inserts after
   `removeByFileId`. Split test scenarios: different-file (verify isolation) and same-file
   (expect re-insertion, verify second clear). Do NOT assert "zero entries" while concurrent
   loaders are active.
9. **Coverage gate N/A** (R8): Track 3 adds no production code. Coverage gate doesn't apply.

## Steps

- [x] Step 1: Map-level concurrent correctness tests — mixed operations + rehash
  > **What was done:** Created `ConcurrentLongIntHashMapConcurrentTest.java` with two test
  > methods: (1) mixed concurrent operations — 8 threads, 200K ops/thread, 12-way operation
  > mix (get/put/putIfAbsent/remove/conditional-remove/computeIfAbsent/compute/slow-compute)
  > on 10K key space; (2) rehash under concurrent access — 1-section map, capacity 4, 3
  > writers + 3 readers + 2 removers with CyclicBarrier for synchronized startup.
  >
  > **What was discovered:** Thread.sleep(1) in the slow-compute path was too expensive
  > (~30s wall-clock). Replaced with LockSupport.parkNanos(100µs) — test now runs in ~7s.
  > Review also identified that without a startup barrier, threads may not overlap during
  > rehash events, defeating the purpose of the rehash test.
  >
  > **What changed from the plan:** Expanded operation mix beyond plan (added putIfAbsent,
  > conditional remove). Used LockSupport.parkNanos instead of Thread.sleep. Added
  > CyclicBarrier (not in original plan). Added inline value assertions during concurrent
  > phase (not just post-run). Added rehash capacity growth assertion.
  >
  > **Key files:** `ConcurrentLongIntHashMapConcurrentTest.java` (new)

- [x] Step 2: Map-level removeByFileId concurrent test
  > **What was done:** Added three removeByFileId concurrent test methods: (1)
  > removeByFileId on target file with 4 concurrent workers on other files — validates
  > isolation and counts remaining entries; (2) removeByFileId + concurrent put + readers
  > on same file with single section for max contention — validates return values and
  > reader correctness during removal; (3) 8 threads each removing their own file
  > simultaneously — validates returned list size and map emptiness.
  >
  > **What was discovered:** Review identified that without concurrent readers in the
  > same-file test, the optimistic-read fallback during removeByFileId's rehash was
  > never exercised. Added reader threads and switched to single section. Also
  > strengthened assertions: validate removeByFileId return values (all must belong to
  > correct file), count remaining other-file entries, assert exact returned list size.
  >
  > **Key files:** `ConcurrentLongIntHashMapConcurrentTest.java` (modified)

- [x] Step 3: Integrated LockFreeReadCache concurrent stress test
  > **What was done:** Created `LockFreeReadCacheConcurrentTestIT.java` with two test
  > methods: (1) concurrent reads + writes with eviction — 4 readers + 4 writers,
  > 50K ops/thread, 4MB cache; (2) deleteFile after concurrent load — 8 readers
  > then sequential deleteFile for each file, verifying empty + consistent state.
  >
  > **What was discovered:** **Found a real concurrency bug in ConcurrentLongIntHashMap!**
  > `rehashTo()` wrote `capacity` before the new arrays, allowing an optimistic reader
  > to snapshot the new (larger) capacity with old (smaller) arrays → AIOOBE. Fix:
  > write arrays first, capacity last. This is exactly the BOOKKEEPER-4317 class of
  > bug this track was designed to catch.
  >
  > **What changed from the plan:** Used `deleteFile` per file instead of
  > `deleteStorage` (which requires `writeCache.files()` to return real data).
  > deleteFile cannot run while readers hold entries, so the test stops readers
  > first then deletes — different from the original plan's concurrent deletion
  > design. Review led to adding fileId/pageIndex assertions on CacheEntry, wrapping
  > clear() in finally blocks, and asserting non-empty before deletion.
  >
  > **Key files:** `LockFreeReadCacheConcurrentTestIT.java` (new),
  > `ConcurrentLongIntHashMap.java` (modified — bug fix in rehashTo)
