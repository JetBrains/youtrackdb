# Track 3: Concurrent Stress Testing

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/3 complete)
- [ ] Track-level code review

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

- [ ] Step 2: Map-level removeByFileId concurrent test
  > **Scope**: Add test methods to `ConcurrentLongIntHashMapConcurrentTest.java`:
  >
  > 1. **removeByFileId + concurrent get/put on different files**: One thread calls
  >    `removeByFileId(targetFileId)` while other threads read/write entries for different
  >    file IDs. Verify: all target-file entries removed, all other-file entries unaffected,
  >    no exceptions.
  >
  > 2. **removeByFileId + concurrent put on same file**: One thread calls
  >    `removeByFileId(fileId)` while another thread inserts entries for the same file.
  >    After both complete, verify map contains only entries inserted after removeByFileId
  >    (or is empty if removeByFileId ran last). Use barriers for controlled interleaving.
  >
  > 3. **Multiple concurrent removeByFileId for different files**: Several threads each
  >    call `removeByFileId` for their own file ID simultaneously. Verify each file's
  >    entries are fully removed and no cross-file interference.
  >
  > **Key files**: `ConcurrentLongIntHashMapConcurrentTest.java` (modified)

- [ ] Step 3: Integrated LockFreeReadCache concurrent stress test
  > **Scope**: Create `LockFreeReadCacheConcurrentTestIT.java` in
  > `core/src/test/.../internal/core/storage/cache/chm/` with
  > `@Category(SequentialTest.class)`. Two test methods:
  >
  > 1. **Concurrent reads + writes with eviction**: Multiple threads call
  >    `loadForRead`/`loadForWrite` on a small cache (forces eviction). Verify:
  >    no exceptions, no data corruption, returned `CachePointer` values are valid.
  >    Scale: 4MB cache, ~50K operations, 4 reader + 4 writer threads. Follow
  >    `AsyncReadCacheTestIT` pattern with `MockedWriteCache`.
  >
  > 2. **deleteStorage under concurrent load**: Pre-populate cache with entries from
  >    multiple files. Start reader threads on all files. Call `deleteStorage` (clears
  >    all). After readers stop, verify cache is empty. This tests the public API path
  >    through `clearFile` → `removeByFileId`. Note: readers may re-populate entries
  >    during deletion (documented race R2) — verify that after readers stop + a final
  >    clear, the cache is empty.
  >
  > Pattern: raw `ExecutorService` + `Future.get(30, SECONDS)`, `MockedWriteCache`,
  > `ByteBufferPool`. JUnit 4 with `@Category(SequentialTest.class)`.
  >
  > **Key files**: `LockFreeReadCacheConcurrentTestIT.java` (new)
