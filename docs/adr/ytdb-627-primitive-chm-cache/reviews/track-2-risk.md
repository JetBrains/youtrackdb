# Track 2 Risk Review

## Findings

### Finding R1 [should-fix]
**Location**: `clearFile()` overflow check timing
**Issue**: With bulk `removeByFileId`, all entries are removed first, then overflow checks run. Current code interleaves per-entry removal with overflow checks. Changes flush timing.
**Decision**: Resolved — addressed in step 3 with explicit testing. The new timing is actually better (reduces cache pressure faster before checking overflow).

### Finding R2 [should-fix]
**Location**: `assertConsistency()` and `assertSize()` API adaptation
**Issue**: `assertConsistency()` uses `data.values()` (doesn't exist) and `data.get(cacheEntry.getPageKey())`. `assertSize()` has `long` vs `int` comparison.
**Decision**: Resolved — rewritten in step 2 using `forEachValue` + `data.get(fileId, pageIndex)`.

### Finding R3 [should-fix]
**Location**: `hashForFrequencySketch` not exposed publicly; test mock hash values will break
**Issue**: WTinyLFUPolicyTest sets up mock expectations using `new PageKey(1, 0).hashCode()`. After hash function change, stubs won't match.
**Decision**: Resolved — hash method added in step 1, test mocks updated in step 2.

### Finding R4 [blocker]
**Location**: `silentLoadForRead()` and `doLoad()` compute lambdas
**Issue**: Lambda receives `(long fileId, int pageIndex, CacheEntry entry)` but also captures outer-scope `fileId`/`pageIndex`. Mixing parameters risks silent correctness bugs.
**Proposed fix**: Consistently use outer-scope captured variables. Add comment explaining they're identical to compute key parameters.
**Decision**: Resolved — implementor guidance in step 2 description.

### Finding R5 [should-fix]
**Location**: `LockFreeReadCache.clear()` — needs `forEachValue` adaptation
**Issue**: `clear()` iterates `data.values()` to freeze entries and call `policy.onRemove()`. Must convert to `forEachValue`.
**Decision**: Resolved — updated in step 2.

### Finding R6 [should-fix]
**Location**: `clear()` + `forEachValue` lock ordering
**Issue**: Window between `forEachValue` (read-locked) and `data.clear()` (write-locked) where entries could be inserted. Pre-existing race, not a regression.
**Decision**: Acknowledged — note in code comments. No track change.

### Finding R7 [suggestion]
**Location**: `CacheEntryImpl.equals()`/`hashCode()` — hash value change
**Issue**: No `HashSet<CacheEntry>` or `HashMap<CacheEntry, ...>` usage found. Reference equality used by new map. Safe to change.
**Decision**: Verified safe. Rewritten in step 1.

### Finding R8 [should-fix]
**Location**: `WTinyLFUPolicyTest` rewrite scope
**Issue**: ~40+ lines of test code need updating. Mechanical but error-prone.
**Decision**: Resolved — dedicated scope in step 2. Helper methods to reduce duplication.

### Finding R9 [suggestion]
**Location**: `releaseFromWrite()` virtual lock pattern
**Issue**: `writeCache.store()` runs under StampedLock write lock. Same behavior as CHM segment lock. No regression.
**Decision**: Acknowledged — add code comment documenting intentional design.

### Finding R10 [should-fix]
**Location**: Step ordering — `CacheEntry` interface change atomicity
**Issue**: Removing `getPageKey()` from interface must happen after all callers updated, or compilation fails.
**Decision**: Resolved — step 4 is dedicated to interface cleanup + PageKey deletion.

### Finding R11 [suggestion]
**Location**: `clear()` under `forEachValue` read lock
**Issue**: Holding section read lock while doing CAS on entries is unnecessary since eviction lock is held.
**Decision**: Acceptable overhead. Not worth adding a specialized `drainAll()` method for this edge case.
