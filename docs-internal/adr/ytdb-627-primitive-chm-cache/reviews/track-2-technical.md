# Track 2 Technical Review

## Findings

### Finding T1 [blocker]
**Location**: `LockFreeReadCache.clear()` (line 540), `WTinyLFUPolicy.assertConsistency()` (line 201)
**Issue**: Both iterate `data.values()` which doesn't exist on `ConcurrentLongIntHashMap`. The new map only provides `forEach(LongIntObjConsumer)` and `forEachValue(Consumer<V>)`.
**Proposed fix**: Use `forEachValue` to collect into a list, then iterate. `StorageException` is unchecked (extends `BaseException` → `RuntimeException`), so it propagates through the consumer.
**Decision**: Resolved — both sites rewritten to use `forEachValue` + list collection pattern in step 2.

### Finding T2 [blocker]
**Location**: Step ordering for `getPageKey()` removal
**Issue**: The lambda body in `releaseFromWrite` uses `cacheEntry.getPageKey()` to extract fileId/pageIndex. If `getPageKey()` is removed before all callers are migrated, compilation fails.
**Proposed fix**: Remove `getPageKey()` + delete `PageKey` as the very last step, after all callers are updated.
**Decision**: Resolved — step 4 handles interface cleanup after all callers migrated.

### Finding T3 [should-fix]
**Location**: `ConcurrentLongIntHashMap` — `hash()` is package-private `long`, frequency sketch needs public `int`
**Issue**: Track 1's `hash()` is package-private and returns `long`. The frequency sketch needs a public `int` hash. Using truncated murmur would correlate with bucket position (see A2).
**Proposed fix**: Add `public static int hashForFrequencySketch(long, int)` using an independent hash.
**Decision**: Resolved — added in step 1 using `Long.hashCode(fileId) * 31 + pageIndex`.

### Finding T4 [should-fix]
**Location**: `WTinyLFUPolicy.assertSize()`, `assertConsistency()`
**Issue**: `ConcurrentLongIntHashMap.size()` returns `long` vs CHM's `int`. Comparisons with `int` counters auto-widen safely.
**Decision**: Acknowledged — no code change needed, documented for implementor awareness.

### Finding T5 [should-fix]
**Location**: `CacheEntryImpl.equals()` and `hashCode()` (lines 312-325)
**Issue**: Both delegate to `PageKey` which is being removed. Must be rewritten to use `fileId` and `pageIndex` directly.
**Decision**: Resolved — rewritten in step 1 as prep.

### Finding T6 [should-fix]
**Location**: `WTinyLFUPolicyTest` — ~10 test methods with ~40+ lines of PageKey usage
**Issue**: Extensive test rewrite needed — map construction, put/remove calls, and admittor mock hash stubs all reference PageKey.
**Decision**: Resolved — included in step 2 scope.

### Finding T7 [should-fix]
**Location**: `silentLoadForRead()` compute lambda
**Issue**: Mixed usage of lambda parameters vs captured outer variables for fileId/pageIndex.
**Proposed fix**: Use outer-scope captured variables consistently (guaranteed identical to compute key).
**Decision**: Resolved — implementor guidance added to step 2 description.

### Finding T8 [suggestion]
**Location**: `LockFreeReadCache` constructor, section count
**Issue**: Current CHM concurrency level is `N_CPU << 1`, new map defaults to 16 sections.
**Decision**: Resolved — pass `N_CPU << 1` as section count (see A9).

### Finding T9 [suggestion]
**Location**: `clearFile()` bulk-then-process pattern
**Issue**: If `freeze()` fails mid-iteration of removeByFileId results, remaining entries are already removed from map but won't get `onRemove()`. Same issue exists in current code but with narrower window.
**Decision**: Acknowledged — `freeze()` failure throws fatal `StorageException`. Documented as pre-existing behavior.

### Finding T10 [suggestion]
**Location**: `CacheEntryChanges.getPageKey()` removal
**Issue**: Confirmed safe — no external callers beyond the chm package.
**Decision**: No action needed.
