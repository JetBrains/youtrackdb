# Track 2: Integration into LockFreeReadCache and WTinyLFUPolicy

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/4 complete)
- [ ] Track-level code review

## Base commit
`63344506774da6606d949ae50d6f81dce2a5222b`

## Reviews completed
- [x] Technical
- [x] Risk
- [x] Adversarial

## Review decisions summary

Key decisions from reviews that affect step implementation:

1. **data.values() missing** (T1/A3 — blocker): `ConcurrentLongIntHashMap` has no `values()`.
   `clear()` and `assertConsistency()` must use `forEachValue` to collect into a list, then
   iterate. `StorageException` is unchecked and propagates through Consumer.
2. **Step ordering for getPageKey() removal** (T2/R10 — blocker): All callers must be migrated
   to `getFileId()`/`getPageIndex()` before `getPageKey()` is removed from the interface.
   Interface cleanup is the final step.
3. **Compute lambda parameter mapping** (R4 — blocker): In `silentLoadForRead()` and `doLoad()`,
   the compute lambda receives `(long fileId, int pageIndex, CacheEntry entry)` but also
   captures outer-scope variables. Use the outer-scope captured variables consistently (they're
   guaranteed identical to compute key parameters). Add comment explaining why.
4. **Frequency sketch hash independence** (A2 — blocker): Do NOT truncate the murmur hash — it
   correlates with bucket position (lower 32 bits used for both). Use
   `Long.hashCode(fileId) * 31 + pageIndex` (independent from map hash, matches spirit of
   `Objects.hash`). Add as `public static int hashForFrequencySketch(long, int)` on the map.
5. **Section count must match CHM concurrency** (A9 — blocker): Current CHM uses
   `N_CPU << 1` concurrency level. New map constructor must pass `N_CPU << 1` as section count
   to match. The map constructor aligns to power of two internally.
6. **CacheEntryImpl equals/hashCode** (T5/A1): Rewrite to use `fileId`/`pageIndex` directly
   when the primitive fields are added.
7. **WTinyLFUPolicyTest rewrite** (T6/R8/A4): ~10 test methods, ~40+ PageKey references.
   Mock hash stubs must use the new `hashForFrequencySketch` method.
8. **clearFile overflow-check timing** (R1): With removeByFileId, all entries removed first,
   then overflow checks. Acceptable — reduces cache pressure faster. Test explicitly.
9. **Concurrent re-insertion race** (A7): Pre-existing race where `doLoad` can re-insert an
   entry for a file being cleared. Not a regression. Document precondition in code comment.
10. **Virtual-lock pattern verified** (A10/R9): `compute()` holds write lock during function
    execution. Absent key + null return = no-op. Same semantics as CHM.

## Steps

- [x] Step 1: CacheEntryImpl prep + hashForFrequencySketch
  > **What was done:** Added `long fileId` and `int pageIndex` fields to
  > `CacheEntryImpl` alongside existing `pageKey`. Updated `getFileId()`,
  > `getPageIndex()`, `equals()`, and `hashCode()` to use primitive fields
  > directly. Added `hashForFrequencySketch(long, int)` to
  > `ConcurrentLongIntHashMap` with 5 unit tests. Review fix: improved test
  > names, added documentation comment to `hashCode()`.
  >
  > **What was discovered:** `CacheEntryImpl.hashCode()` now differs from
  > `PageKey.hashCode()` (record-generated vs explicit formula). Not a
  > correctness issue — `CacheEntryImpl` is not used as a hash key in any
  > collection. The frequency sketch hash distribution change during migration
  > is transient and acceptable per D5.
  >
  > **Key files:** `CacheEntryImpl.java` (modified), `ConcurrentLongIntHashMap.java`
  > (modified), `ConcurrentLongIntHashMapTest.java` (modified)

- [ ] Step 2: Core swap — data field type + LockFreeReadCache + WTinyLFUPolicy + tests
  > **Files:** `LockFreeReadCache.java`, `WTinyLFUPolicy.java`,
  > `WTinyLFUPolicyTest.java`
  >
  > This is the atomic big-bang swap — changing `data` type breaks all call sites
  > simultaneously, so all must be updated in one commit.
  >
  > **LockFreeReadCache changes:**
  > - Replace `data` field: `ConcurrentHashMap<PageKey, CacheEntry>` →
  >   `ConcurrentLongIntHashMap<CacheEntry>`
  > - Update constructor: `new ConcurrentLongIntHashMap<>(maxCacheSize, N_CPU << 1)`
  >   (review decision #5 — match CHM concurrency level)
  > - `doLoad()` (line 217): remove `new PageKey(...)`, use `data.get(fileId, pageIndex)`
  >   and `data.compute(fileId, pageIndex, fn)`. In compute lambda, use outer-scope
  >   captured variables consistently (review decision #3). Add comment explaining
  >   lambda parameters are identical to captured variables.
  > - `silentLoadForRead()` (line 159): same pattern as doLoad
  > - `addNewPagePointerToTheCache()` (line 307): use
  >   `data.putIfAbsent(entry.getFileId(), entry.getPageIndex(), entry)`
  > - `releaseFromWrite()` (line 349): use
  >   `data.compute(entry.getFileId(), entry.getPageIndex(), fn)`. Add comment
  >   documenting virtual-lock pattern (review decision #10).
  > - `clear()` (line 540): collect entries via `data.forEachValue()` into a list,
  >   then iterate for freeze/onRemove/clear. `StorageException` is unchecked and
  >   propagates through Consumer (review decision #1).
  > - `clearFile()` (line 625): temporarily keep per-page loop pattern using
  >   `data.remove(fileId, pageIndex)` — removeByFileId deferred to step 3.
  >   Remove `new PageKey(...)` allocation.
  > - `size()` returns `long` from new map — auto-widens safely (review decision T4).
  >
  > **WTinyLFUPolicy changes:**
  > - Constructor: accept `ConcurrentLongIntHashMap<CacheEntry>` instead of
  >   `ConcurrentHashMap<PageKey, CacheEntry>`
  > - `onAccess()` (line 58): replace `cacheEntry.getPageKey().hashCode()` with
  >   `ConcurrentLongIntHashMap.hashForFrequencySketch(cacheEntry.getFileId(), cacheEntry.getPageIndex())`
  > - `onAdd()` (line 83): same hash replacement
  > - `purgeEden()` (lines 110-111, 121, 137): replace `getPageKey().hashCode()` for
  >   frequency comparisons, replace `data.remove(victim.getPageKey(), victim)` with
  >   `data.remove(victim.getFileId(), victim.getPageIndex(), victim)`
  > - `assertConsistency()` (line 201): replace `data.values()` iteration with
  >   `forEachValue` collection pattern. Replace `data.get(cacheEntry.getPageKey())`
  >   with `data.get(cacheEntry.getFileId(), cacheEntry.getPageIndex())`
  > - `assertSize()` (line 197): `data.size()` returns `long` — comparison auto-widens
  >
  > **WTinyLFUPolicyTest changes:**
  > - Replace all `ConcurrentHashMap<PageKey, CacheEntry>` with
  >   `ConcurrentLongIntHashMap<CacheEntry>`
  > - Replace all `data.put(new PageKey(f, p), entry)` with `data.put(f, p, entry)`
  > - Replace all `data.remove(new PageKey(f, p), entry)` with
  >   `data.remove(f, p, entry)`
  > - Replace all mock stubs `admittor.frequency(new PageKey(f, p).hashCode())` with
  >   `admittor.frequency(ConcurrentLongIntHashMap.hashForFrequencySketch(f, p))`
  > - Remove PageKey import
  >
  > **Compiles, all tests pass.** Every changed call site updated atomically.

- [ ] Step 3: clearFile → removeByFileId integration
  > **Files:** `LockFreeReadCache.java`
  >
  > Replace the per-page loop in `clearFile()` with `data.removeByFileId(fileId)`.
  > Process returned `List<CacheEntry>` entries under eviction lock: for each entry,
  > call `freeze()` (throws `StorageException` if in use — preserving current failure
  > mode), `policy.onRemove()`, `cacheSize.decrementAndGet()`, and
  > `writeCache.checkCacheOverflow()`.
  >
  > This changes the removal pattern from O(filledUpTo) hash probes (most misses) to
  > O(segment-capacity) linear sweep per segment. All entries removed in bulk, then
  > processed sequentially (review decision #8 — overflow check timing change is
  > acceptable).
  >
  > Add code comment documenting: (a) entries processed after segment lock release
  > to avoid StampedLock reentrancy deadlock, (b) concurrent re-insertion precondition
  > — clearFile assumes no concurrent doLoad for the same fileId (review decision #9).
  >
  > **Compiles, all tests pass.** Focused single-method optimization.

- [ ] Step 4: CacheEntry interface cleanup + PageKey deletion
  > **Files:** `CacheEntry.java`, `CacheEntryImpl.java`,
  > `CacheEntryChanges.java`, `PageKey.java` (deleted)
  >
  > - Remove `getPageKey()` from `CacheEntry` interface (line 111)
  > - Remove `getPageKey()` from `CacheEntryImpl` (lines 328-330)
  > - Remove `pageKey` field from `CacheEntryImpl` (line 57), remove
  >   `new PageKey(...)` from constructor (line 77)
  > - Remove `getPageKey()` from `CacheEntryChanges` (lines 223-225)
  > - Remove `PageKey` imports from CacheEntry, CacheEntryImpl, CacheEntryChanges
  > - Delete `chm/PageKey.java`
  >
  > All callers were migrated in steps 1-3 so no `getPageKey()` references remain.
  >
  > **Compiles, all tests pass.** Pure cleanup/deletion step.
