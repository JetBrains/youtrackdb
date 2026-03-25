# Track 2: Integration into LockFreeReadCache and WTinyLFUPolicy

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [ ] Track-level code review (1/3 iterations)

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

- [x] Step 2: Core swap — data field type + LockFreeReadCache + WTinyLFUPolicy + tests
  > **What was done:** Atomic big-bang swap of `ConcurrentHashMap<PageKey, CacheEntry>`
  > to `ConcurrentLongIntHashMap<CacheEntry>` in LockFreeReadCache and WTinyLFUPolicy.
  > All call sites updated: doLoad(), silentLoadForRead(), addNewPagePointerToTheCache(),
  > releaseFromWrite(), clear(), clearFile(). WTinyLFUPolicy: constructor, onAccess(),
  > onAdd(), purgeEden(), assertConsistency() all migrated. WTinyLFUPolicyTest: all 12
  > test methods updated (PageKey → primitive API, mock stubs use hashForFrequencySketch).
  > clearFile() still uses per-page loop — removeByFileId deferred to Step 3.
  >
  > **What was discovered:** Review found critical bug: `N_CPU << 1` is not guaranteed
  > to be a power of two (e.g., 6 CPUs → 12), but `ConcurrentLongIntHashMap` requires
  > power-of-two section count. Fixed by wrapping with `ceilingPowerOfTwo()`. The plan's
  > review decision #5 incorrectly stated "constructor aligns to power of two internally"
  > — it only aligns per-section capacity, not section count.
  >
  > **Key files:** `LockFreeReadCache.java` (modified), `WTinyLFUPolicy.java` (modified),
  > `WTinyLFUPolicyTest.java` (modified)

- [x] Step 3: clearFile → removeByFileId integration
  > **What was done:** Replaced clearFile() per-page loop with
  > `data.removeByFileId(fileId)` bulk sweep. Post-removal processing (freeze,
  > onRemove, checkCacheOverflow) runs after segment locks released. Removed
  > `filledUpTo` parameter from clearFile and all callers (truncateFile,
  > closeFile, deleteFile, deleteStorage, closeStorage). Simplified deleteStorage
  > and closeStorage — no longer pre-collect filledUpTo into RawPairLongInteger
  > lists. Removed unused RawPairLongInteger and List imports.
  >
  > **What was discovered:** Removing the `filledUpTo` parameter was a natural
  > consequence — removeByFileId finds all entries by file regardless of page
  > count. The semantic change (remove ALL vs only 0..filledUpTo) is safe because
  > the cache only contains entries for pages that exist. Review confirmed no
  > new bugs; partial failure behavior during post-removal freeze is pre-existing.
  >
  > **Key files:** `LockFreeReadCache.java` (modified)

- [x] Step 4: CacheEntry interface cleanup + PageKey deletion
  > **What was done:** Removed `getPageKey()` from CacheEntry interface,
  > CacheEntryImpl, and CacheEntryChanges. Removed `pageKey` field and
  > `new PageKey(...)` allocation from CacheEntryImpl constructor. Removed
  > PageKey imports from all three files. Deleted `chm/PageKey.java`.
  > The `local.PageKey` used by WOWCache is unaffected.
  >
  > **Key files:** `CacheEntry.java` (modified), `CacheEntryImpl.java` (modified),
  > `CacheEntryChanges.java` (modified), `PageKey.java` (deleted)
