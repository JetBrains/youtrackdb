# Track 3 Technical Review

## Findings

### Finding T1 [should-fix]
**Location**: Track 3 constraint "use ConcurrentTestHelper" + `ConcurrentTestHelper.java`
**Issue**: `ConcurrentTestHelper` has a hardcoded 30-minute timeout and is designed for N identical workers. The track's mixed reader/writer/remover scenarios are a better fit for direct `ExecutorService` + `Future.get(timeout)`, matching the existing `AsyncReadCacheTestIT` pattern.
**Proposed fix**: Use raw `ExecutorService` + `Future.get(30, SECONDS)` pattern. Do not use `ConcurrentTestHelper`.
**Decision**: Accepted.

### Finding T2 [should-fix]
**Location**: Track 3 "clearFile under concurrent load" + `LockFreeReadCache.clearFile()` (private method)
**Issue**: `clearFile()` is private. The public API paths (`deleteStorage`/`closeStorage`) clear ALL files, not a single file. To test concurrent single-file removal, must use `removeByFileId()` directly at the map level (which is public).
**Proposed fix**: Test `removeByFileId` at map level for concurrent correctness. Integration test uses `deleteStorage` for single-threaded post-condition verification.
**Decision**: Accepted. Restructures the integrated test.

### Finding T3 [suggestion]
**Location**: Track 3 "Map-level concurrent tests" test placement
**Issue**: Existing `ConcurrentLongIntHashMapTest.java` is 1688 lines of single-threaded tests. Concurrent stress tests warrant a separate class.
**Proposed fix**: Create `ConcurrentLongIntHashMapConcurrentTest.java` in the same package.
**Decision**: Accepted.

### Finding T4 [should-fix]
**Location**: Track 3 "Rehash under concurrent access" + `Section.rehashTo()`
**Issue**: Test needs small initial capacity and minimal section count to maximize rehash frequency and race windows.
**Proposed fix**: Use `new ConcurrentLongIntHashMap<>(4, 1)` (1 section, minimal capacity) for rehash stress tests.
**Decision**: Accepted.

### Finding T5 [suggestion]
**Location**: Track 3 overall — iteration count guidance
**Issue**: No guidance on iteration counts for <30s runtime target.
**Proposed fix**: ~100K-500K operations per thread for map-level tests (5-15s runtime), scaled down for integrated tests.
**Decision**: Accepted.

### Finding T6 [suggestion]
**Location**: Track 3 "Map-level concurrent tests" operation mix
**Issue**: `compute()` not mentioned in the concurrent operation mix, but it's used as a virtual lock in `LockFreeReadCache`.
**Proposed fix**: Include `compute()` alongside `get/put/remove/computeIfAbsent` in mixed operation tests.
**Decision**: Accepted.

### Finding T7 [blocker]
**Location**: Track 3 "clearFile under concurrent load" — no public API for single-file clear
**Issue**: `clearFile` is private; only bulk `deleteStorage`/`closeStorage` are public. Cannot test single-file concurrent removal at integrated level.
**Proposed fix**: Map-level `removeByFileId` for concurrent correctness; integration `deleteStorage` for correctness.
**Decision**: Accepted. Same resolution as T2.

## Summary
All findings accepted. The key restructuring: test `removeByFileId` concurrency at the map level (public API), and use `deleteStorage` for integrated-level correctness checks. Use raw `ExecutorService` pattern, separate test class for concurrent tests.
