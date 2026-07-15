# Track 3 Risk Review

## Findings

### Finding R1 [should-fix]
**Location**: Track 3 Step 1 — optimistic read fallback path under sustained writes
**Issue**: When `tryOptimisticRead()` returns 0 (writer active), the fallback to read lock must remain correct. Test should force frequent fallbacks with sustained high write rates.
**Proposed fix**: Include a slow `compute()` variant that holds write lock longer, forcing readers through the read-lock fallback path consistently.
**Decision**: Accepted — fold into compute stress scenario.

### Finding R2 [should-fix]
**Location**: Track 3 "clearFile under concurrent load" — `clearFile()`/`doLoad()` race
**Issue**: Comment at line 630-634 documents pre-existing race: `doLoad` can re-insert entries after `removeByFileId` completes. Asserting "zero entries" while concurrent loaders run will flake.
**Proposed fix**: Split into: (1) clearFile with concurrent reads on different files (verify isolation); (2) clearFile with concurrent reads on same file (expect re-insertion, verify second clear works).
**Decision**: Accepted — important for test correctness.

### Finding R3 [should-fix]
**Location**: Track 3 — `removeByFileId` write lock hold time
**Issue**: `removeByFileId` + `rehashSameCapacity()` holds write lock for O(segment-capacity) work. Stress test should observe whether this causes unacceptable p99 read latency.
**Proposed fix**: Add latency measurement as optional observation, not hard assertion.
**Decision**: Noted — useful observation but not a hard test requirement. Defer to future optimization if needed.

### Finding R4 [suggestion]
**Location**: Track 3 — `compute()` holding segment write lock during I/O
**Issue**: `doLoad()` calls `writeCache.load()` inside `compute()` lambda, holding the segment write lock. This is broader than CHM's per-bin lock.
**Proposed fix**: Include slow-compute stress variant.
**Decision**: Accepted — aligns with R1.

### Finding R5 [suggestion]
**Location**: Track 3 — `ConcurrentTestHelper` usage
**Issue**: 30-minute timeout, not matching existing cache test patterns.
**Proposed fix**: Use raw `ExecutorService` + `Future.get(timeout)`.
**Decision**: Accepted — aligns with T1.

### Finding R6 [suggestion]
**Location**: Track 3 — rehash test key space bounds
**Issue**: Unbounded inserts could cause OOM during rehash stress test.
**Proposed fix**: Use bounded key space (~10K unique keys) with mix of inserts and removes.
**Decision**: Accepted.

### Finding R7 [blocker]
**Location**: Track 3 — test placement and CI flakiness
**Issue**: Integrated cache-level stress tests are nondeterministic. If named as unit tests, they run on every PR and may cause false CI failures.
**Proposed fix**: Map-level concurrent tests as regular unit tests (fast, reasonably deterministic). Integrated cache-level tests as `*IT` with `@Category(SequentialTest.class)`.
**Decision**: Accepted.

### Finding R8 [suggestion]
**Location**: Track 3 — coverage gate
**Issue**: Track 3 adds no production code; coverage gate is N/A.
**Proposed fix**: Note explicitly that coverage gate doesn't apply.
**Decision**: Noted.

## Summary
Key risks addressed: (1) R2 — clearFile race makes naive assertions flaky, split test scenarios; (2) R7 — integrated tests must be `*IT` for CI stability. All other findings accepted as test design improvements.
