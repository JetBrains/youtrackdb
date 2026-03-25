# Track 2 Adversarial Review

## Findings

### Finding A1 [should-fix]
**Target**: Decision D3 (Eliminate PageKey from CacheEntry interface)
**Challenge**: `CacheEntryImpl.equals()` and `hashCode()` delegate to `PageKey`. Plan doesn't explicitly address their rewrite.
**Evidence**: CacheEntryImpl.java lines 312-325
**Decision**: Resolved — step 1 rewrites equals/hashCode to use `fileId`/`pageIndex` directly.

### Finding A2 [blocker]
**Target**: Decision D5 (Frequency sketch keying)
**Challenge**: Truncating murmur hash to `int` correlates with bucket position (lower 32 bits used for both). `FrequencySketch.spread()` mitigates but doesn't eliminate correlation.
**Evidence**: `ConcurrentLongIntHashMap.hash()` returns `long`; lower 32 bits index within section. `FrequencySketch` applies seed-based re-hashing.
**Proposed fix**: Use `Long.hashCode(fileId) * 31 + pageIndex` (independent from map hash, matches spirit of `Objects.hash`). `FrequencySketch.spread()` provides additional decorrelation.
**Decision**: Resolved — step 1 adds `hashForFrequencySketch` with independent hash function.

### Finding A3 [blocker]
**Target**: Missing API: `data.values()` iteration
**Challenge**: `ConcurrentLongIntHashMap` has no `values()`. `clear()` and `assertConsistency()` iterate values. `clear()` throws `StorageException` (unchecked) from within loop.
**Evidence**: LockFreeReadCache.java line 540, WTinyLFUPolicy.java line 201
**Decision**: Resolved — collect via `forEachValue` into list, then iterate. `StorageException` propagates through Consumer since it's unchecked.

### Finding A4 [should-fix]
**Target**: Decision D3 — WTinyLFUPolicyTest rewrite scope
**Challenge**: "All existing tests must pass without modification (apart from PageKey references)" underestimates scope. 10+ test methods, ~40+ lines, mock hash stubs all need updating.
**Decision**: Resolved — explicit scope in step 2.

### Finding A5 [should-fix]
**Target**: Track scope
**Challenge**: Track combines 6 distinct change areas. Could be split to reduce blast radius.
**Evidence**: 6 areas listed under "How" in track description
**Decision**: Partially addressed — 4 steps with compilable intermediates. CacheEntryImpl prep first, interface cleanup last, core swap in middle. Track stays monolithic but step ordering minimizes risk.

### Finding A6 [suggestion]
**Target**: `size()` return type mismatch (`long` vs `int`)
**Challenge**: Widening promotion handles it correctly. Cache never exceeds `Integer.MAX_VALUE`.
**Decision**: No change needed. Documented for implementor awareness.

### Finding A7 [should-fix]
**Target**: Invariant "Eviction policy consistency" — concurrent re-insertion race
**Challenge**: With removeByFileId sweeping sections sequentially, a concurrent doLoad could re-insert an entry in an already-swept section. Pre-existing race (current per-page loop has same issue).
**Evidence**: LockFreeReadCache.java lines 618-652 — current clearFile also vulnerable
**Decision**: Acknowledged — pre-existing race. clearFile is called during file close/truncate/delete when no new loads should happen. Document precondition in code comment.

### Finding A8 [suggestion]
**Target**: Invariant "No allocation on read hit path"
**Challenge**: `boolean[1]` array allocation on miss path. Invariant should say "hit path" not "read path."
**Decision**: Acknowledged — invariant language is already correct ("read hit path" in plan).

### Finding A9 [blocker]
**Target**: Assumption: 16 sections sufficient for concurrent I/O workloads
**Challenge**: CHM uses `N_CPU << 1` concurrency level. 16 sections on 16-core machine = half the concurrency. `doLoad` compute holds section lock during disk I/O.
**Evidence**: LockFreeReadCache.java line 100 — `new ConcurrentHashMap<>(maxCacheSize, 0.5f, N_CPU << 1)`. ConcurrentLongIntHashMap DEFAULT_SECTION_COUNT = 16.
**Proposed fix**: Pass `N_CPU << 1` as section count to match existing concurrency level.
**Decision**: Resolved — step 2 passes `N_CPU << 1` as section count.

### Finding A10 [should-fix]
**Target**: Assumption: virtual-lock pattern works with new compute()
**Challenge**: Verified correct — compute() holds write lock during function execution. Absent key + null return = no-op. Same semantics as CHM.
**Decision**: Confirmed correct. Add code comment documenting the pattern.

### Finding A11 [suggestion]
**Target**: Steps may not produce compilable intermediate states
**Challenge**: Changing `data` type breaks all call sites simultaneously. No meaningful partially-migrated state.
**Evidence**: data field used by ~10 methods across 2 classes
**Decision**: Addressed — step 1 is purely additive (prep), step 2 is the atomic big-bang swap, step 3 is focused clearFile optimization, step 4 is interface cleanup. Each compiles.
