# Consistency Review — read-cache-concurrency-bug

**Phase 2 step 1.** Verifies design ↔ code ↔ plan alignment by reading
the actual codebase. Tooling: mcp-steroid PSI find-usages /
find-implementations / type-hierarchy via `steroid_execute_code`,
backed by file Reads at the cited locations.

## Verdict

PASS after two iterations. Iteration 1 raised 10 findings (1 blocker,
5 should-fix, 4 suggestions), all accepted, all fixed. Gate
verification (iteration 2) confirmed all 10 fixes applied correctly
and surfaced 2 new findings (CR11 should-fix, CR12 suggestion) — both
fixed in iteration 2; PSI re-verification confirms.

## Part 1: Verification Certificates

### DESIGN ↔ CODE

#### Ref: `WriteCache` interface
- **Document claim**: Plan §Component Map: `WriteCache` exposes `load`,
  `allocateNewPage`, public `getFilledUpTo`. After fix it gains a total
  `loadOrAdd`.
- **Search**: PSI `findClass` + method enumeration.
- **Code location**: `core/.../internal/core/storage/cache/WriteCache.java`
- **Actual signature/role**: `int allocateNewPage(long)`,
  `CachePointer load(long, long, ModifiableBoolean, boolean)`,
  `long getFilledUpTo(long)` — all currently `public` (interface
  methods) with no modifier.
- **Verdict**: MATCHES (today's shape; `loadOrAdd` is post-fix).

#### Ref: `LockFreeReadCache` package path
- **Document claim**: Backlog Track 1 in-scope file:
  `core/.../internal/core/storage/cache/local/twoq/LockFreeReadCache.java`.
- **Search**: PSI short-name lookup.
- **Code location**:
  `core/.../internal/core/storage/cache/chm/LockFreeReadCache.java`
- **Actual signature/role**: FQN is `…storage.cache.chm.LockFreeReadCache`
  (package `chm`, NOT `local/twoq`).
- **Verdict**: MISMATCHES → CR1.

#### Ref: `WOWCache` package path
- **Document claim**: Backlog Track 1 says
  `core/.../internal/core/storage/cache/local/twoq/WOWCache.java`.
- **Search**: PSI short-name lookup.
- **Code location**: `core/.../internal/core/storage/cache/local/WOWCache.java`
- **Actual signature/role**: FQN is `…storage.cache.local.WOWCache`
  (package `local`, NOT `local/twoq`).
- **Verdict**: MISMATCHES → CR1.

#### Ref: `DirectMemoryOnlyDiskCache` package path
- **Document claim**: Plan/backlog references
  `core/.../internal/core/storage/cache/...DirectMemoryOnlyDiskCache.java`.
- **Search**: PSI short-name lookup.
- **Code location**:
  `core/.../internal/core/storage/memory/DirectMemoryOnlyDiskCache.java`
- **Actual signature/role**: FQN is
  `…storage.memory.DirectMemoryOnlyDiskCache` (under `storage.memory`,
  NOT `storage.cache`).
- **Verdict**: MISMATCHES → CR1.

#### Ref: `StorageComponent` package path
- **Document claim**: Plan implies `core/.../paginated/StorageComponent.java`.
- **Search**: PSI short-name lookup.
- **Code location**:
  `core/.../internal/core/storage/impl/local/paginated/base/StorageComponent.java`
- **Actual signature/role**: FQN is `…paginated.base.StorageComponent`
  (extra `base` segment).
- **Verdict**: MISMATCHES → CR1.

#### Ref: `AbstractStorage` package path
- **Document claim**: Track 4 lists `AbstractStorage.java` with
  `restoreAtomicUnit`.
- **Search**: PSI short-name lookup.
- **Code location**: `core/.../internal/core/storage/impl/local/AbstractStorage.java`
- **Actual signature/role**: FQN is `…storage.impl.local.AbstractStorage`
  (under `local`, NOT `local/paginated`).
- **Verdict**: PARTIAL → CR1.

#### Ref: `StorageComponent.addPage` signature
- **Document claim**: Plan §D3, design class diagram: "delete
  `StorageComponent.addPage`"; signature implied as `addPage(fileId)`.
- **Search**: PSI method enumeration.
- **Code location**: `…/StorageComponent.java:167-171`
- **Actual signature/role**:
  `protected CacheEntry addPage(@Nonnull AtomicOperation, long fileId)` —
  takes `(AtomicOperation, long)`, not `(long)`.
- **Verdict**: PARTIAL → CR3.

#### Ref: `AtomicOperation.addPage`
- **Document claim**: Plan §D3 / design "Why `addPage` is deletable":
  delete `AtomicOperation.addPage(fileId)` no-pageIndex signature.
- **Search**: PSI find-method + reference search.
- **Code location**: `…/atomicoperations/AtomicOperation.java`
  (interface) — `CacheEntry addPage(long)`; impl in
  `AtomicOperationBinaryTracking.java:342`.
- **Actual signature/role**: `public CacheEntry addPage(long fileId)`.
  1 production caller (`StorageComponent.addPage` at line 170) plus 15
  test callers.
- **Verdict**: MATCHES.

#### Ref: `AtomicOperationBinaryTracking.internalFilledUpTo`
- **Document claim**: Plan/design state this is the "prediction
  wrapper" inside the commitChanges path, dropped with Track 4.
- **Search**: PSI method enumeration + grep.
- **Code location**: `…/AtomicOperationBinaryTracking.java:517` (private,
  called from `addPage:355` and `filledUpTo:514`).
- **Actual signature/role**:
  `private long internalFilledUpTo(long fileId, FileChanges changesContainer)`
  — combines `writeCache.getFilledUpTo` with `pageChangesMap`-derived
  predicted size.
- **Verdict**: MATCHES.

#### Ref: `AtomicOperationBinaryTracking.commitChanges` do/while reconciliation
- **Document claim**: Plan §D3 / design "Why addPage is deletable":
  commitChanges has a do/while that calls `readCache.allocateNewPage`
  until pageIndex matches.
- **Search**: grep do/while in AtomicOperationBinaryTracking.
- **Code location**: `…/AtomicOperationBinaryTracking.java:858-864`.
- **Actual signature/role**: Inside `commitChanges`,
  `do { ... cacheEntry = readCache.allocateNewPage(fileId, writeCache,
  fileStartLSN); } while (cacheEntry.getPageIndex() != pageIndex);`. The
  surrounding call uses `readCache.loadForWrite(...)`.
- **Verdict**: MATCHES (today's shape).

#### Ref: `AbstractStorage.restoreAtomicUnit` do/while reconciliation
- **Document claim**: Plan §D3: `restoreAtomicUnit` contains do/while
  loops that get collapsed in Track 4.
- **Search**: grep in AbstractStorage.
- **Code location**: `…/AbstractStorage.java:5394-5400` (UpdatePageRecord
  branch) and `:5465-5471` (PageOperation branch).
- **Actual signature/role**:
  `do { ... cacheEntry = readCache.allocateNewPage(fileId, writeCache,
  null); } while (cacheEntry.getPageIndex() != pageIndex);` — two
  branches as the backlog claims.
- **Verdict**: MATCHES.

#### Ref: `DiskStorage.restoreFromIncrementalBackup` do/while
- **Document claim**: Plan §D3 / Track 4: `restoreFromIncrementalBackup`
  has a do/while reconciliation.
- **Search**: grep in DiskStorage.
- **Code location**: `…/DiskStorage.java:1821-1827`.
- **Actual signature/role**: Same shape as `restoreAtomicUnit`.
- **Verdict**: MATCHES.

#### Ref: `DiskStorage.backupPagesWithChanges` line ~1404
- **Document claim**: Backlog Track 3:
  `DiskStorage.backupPagesWithChanges:1404` — only legitimate
  physical-size consumer.
- **Search**: grep in DiskStorage + line read.
- **Code location**: Method signature at `…/DiskStorage.java:1387`; the
  `getFilledUpTo` call inside the method is at line 1404.
- **Actual signature/role**: `private LogSequenceNumber
  backupPagesWithChanges(...)`. Line 1404 reads `final var filledUpTo
  = writeCache.getFilledUpTo(fileId);`.
- **Verdict**: PARTIAL → CR8.

#### Ref: `EnsurePageIsValidInFileTask` idempotency
- **Document claim**: Plan/design: `writeValidPageInFile` writes only
  if `getUnderlyingFileSize() <= pagePosition`.
- **Search**: file Read at the cited path.
- **Code location**: `…/cache/local/EnsurePageIsValidInFileTask.java`
  (calls `writeCache.writeValidPageInFile`);
  `…/cache/local/WOWCache.java:3455-3500`.
- **Actual signature/role**:
  `if (file.getUnderlyingFileSize() <= pagePosition) { ... write
  magic-stamped buffer ... }`.
- **Verdict**: MATCHES.

#### Ref: `wowCacheFlushExecutor` location
- **Document claim**: design.md: "single-threaded
  `wowCacheFlushExecutor` (`YouTrackDBEnginesManager.java:231`)".
- **Search**: file Read at the cited line.
- **Code location**: `…/YouTrackDBEnginesManager.java:231`.
- **Actual signature/role**:
  `wowCacheFlushExecutor =
  ThreadPoolExecutors.newSingleThreadScheduledPool("YouTrackDB Write
  Cache Flush Task", storageThreadGroup);`
- **Verdict**: MATCHES.

#### Ref: `WriteCache.getFilledUpTo` callers
- **Document claim**: Plan §D4 / design: only legitimate external
  consumer is `DiskStorage.backupPagesWithChanges`.
- **Search**: PSI `MethodReferencesSearch` on `WriteCache.getFilledUpTo`.
- **Code location**: 2 production callers, 8 test callers.
- **Actual signature/role**: Production: (1)
  `AtomicOperationBinaryTracking.java:527` (inside `internalFilledUpTo`,
  removed by Track 4); (2) `DiskStorage.java:1404`
  (`backupPagesWithChanges`, gated by Track 5).
- **Verdict**: MATCHES — once Track 4 lands, only the backup site
  remains.

#### Ref: `StorageComponent.getFilledUpTo` call sites — count
- **Document claim**: Plan §D2 line 114 "17 production call sites".
  Plan Track 3 line 248 "17 production callers". design.md "17
  production call sites". design.md table totals: pure-sizing 10 +
  reuse-or-extend 7 = 17.
- **Search**: PSI `MethodReferencesSearch` on
  `StorageComponent.getFilledUpTo(AtomicOperation, long)`.
- **Code location**: 16 production callers (no test callers).
- **Actual signature/role**: Backlog enumerates 9 pure-sizing + 7
  reuse-or-extend = 16, matching PSI exactly.
- **Verdict**: MISMATCHES → CR2.

#### Ref: `StorageComponent.addPage` call site count
- **Document claim**: Plan §D3, Track 4: 20 production call sites; "9
  sites inside `create()` / `init()` / `createEmptyStatsPage()`" + "11
  sites inside reuse-or-extend probes".
- **Search**: PSI `MethodReferencesSearch` on
  `StorageComponent.addPage(AtomicOperation, long)`.
- **Code location**: 20 production callers, including the recursive
  call from inside `StorageComponent.loadOrAddPageForWrite:155`.
- **Actual signature/role**: 20 prod refs total, including the
  internal call. The 19 external sites split as fresh-file vs probe.
- **Verdict**: PARTIAL → CR3.

#### Ref: `StorageComponent.loadOrAddPageForWrite` — already exists
- **Document claim**: Plan §Integration Points: "canonical write-side
  helper for storage components after Track 4". Wording implies
  introduction.
- **Search**: PSI find-method + references.
- **Code location**: `…/StorageComponent.java:149-158`. 2 production
  callers today (`IndexHistogramManager:1884`, `:1919`).
- **Actual signature/role**: `protected CacheEntry
  loadOrAddPageForWrite(@Nonnull AtomicOperation, long fileId, long
  pageIndex)` — current implementation: `loadPageForWrite` then fall
  back to `addPage` if null.
- **Verdict**: PARTIAL → CR3.

#### Ref: Per-component logical-size accessors (design.md table)
- **Document claim**: 6 component classes with `getPagesSize()` or
  `getFileSize()` methods.
- **Search**: PSI per-class method enumeration.
- **Code location/Actual**:
  - `…singlevalue.v3.CellBTreeSingleValueEntryPointV3` →
    `getPagesSize / setPagesSize` ✓
  - `…singlevalue.v1.CellBTreeSingleValueEntryPointV1` →
    `getPagesSize / setPagesSize` ✓
  - `…multivalue.v2.CellBTreeMultiValueV2EntryPoint` →
    `getPagesSize / setPagesSize` ✓
  - `…ridbag.ridbagbtree.EntryPoint` →
    `getPagesSize / setPagesSize` ✓
  - `…collection.v2.MapEntryPoint` →
    `getFileSize / setFileSize` ✓
  - `…index.versionmap.MapEntryPoint` →
    `getFileSize / setFileSize` ✓
- **Verdict**: MATCHES (all 6 classes/methods exist exactly as claimed).

#### Ref: `CollectionDirtyPageBitSet`, `FreeSpaceMap`,
  `IndexHistogramManager` logical-size accessors
- **Document claim**: design.md acknowledges these three are "not yet
  confirmed in the table"; Phase A will audit.
- **Search**: PSI method enumeration on each class for
  `getPagesSize / getFileSize`.
- **Code location**: All three classes exist; none defines
  `getPagesSize` or `getFileSize` directly.
- **Verdict**: MATCHES (reality matches the design's explicit caveat).

#### Ref: `LockFreeReadCache.data` — segment-locked map
- **Document claim**: design class diagram:
  `data: SegmentedMap~PageKey, CacheEntry~`.
- **Search**: PSI find-class on `ConcurrentLongIntHashMap` + read
  class header.
- **Code location**: `…/LockFreeReadCache.java:62`
  `private final ConcurrentLongIntHashMap<CacheEntry> data;`.
- **Actual signature/role**: Map javadoc: "Uses segmented
  open-addressing with StampedLock per segment".
- **Verdict**: PARTIAL → CR9.

### PLAN ↔ CODE

#### Ref: Backlog call-site lines (all 17 enumerated lines)
- **Document claim**: Backlog Track 3 + Track 4 enumerates specific
  lines.
- **Search**: read each line directly.
- **Verification**: All enumerated line numbers correct as of HEAD.
- **Verdict**: MATCHES.

### DESIGN ↔ PLAN

#### Ref: Workflow diagrams
- **Document claim**: Three sequence diagrams (write-side allocation,
  recovery gap-fill, cross-TX read).
- **Trace**: All three describe the post-fix shape; today's code
  legitimately differs (today's allocation is deferred to commit and
  uses do/while reconciliation). Design's intro paragraph (lines
  41-47) makes this explicit.
- **Verdict**: MATCHES (post-fix shape).

#### Ref: design.md spelling of `EnsurePageIsValidInFileTask`
- **Document claim**: design.md uses `EnsureValidPageInFileTask` on
  line 164.
- **Code location**: Class is named `EnsurePageIsValidInFileTask`.
- **Verdict**: MISMATCHES → CR7.

### GAPS

#### Gap: AtomicOperation.loadOrAddPageForWrite — interface change not called out
- **Document**: design class diagram shows
  `AtomicOperation.loadOrAddPageForWrite`, but Track 4 description does
  not enumerate adding it.
- **Code location**: Today, `AtomicOperation` interface has
  `addPage(long)` and `loadPageForWrite(...)`, but no
  `loadOrAddPageForWrite`.
- **Verdict**: ASPIRATIONAL → CR4.

#### Gap: ReadCache interface change (`loadForWrite` → `loadOrAddForWrite`)
- **Document**: design class diagram shows `LockFreeReadCache` with
  `loadForRead` + `loadOrAddForWrite`. Plan calls the post-fix wrapper
  "loadOrAddForWrite" but does not explicitly say "rename".
- **Code location**: `ReadCache.java:61` — `loadForWrite(...)` exists.
- **Verdict**: ASPIRATIONAL → CR5.

#### Gap: in-memory engine `DirectMemoryOnlyDiskCache` is also a `ReadCache`
- **Document**: Plan/backlog treats `DirectMemoryOnlyDiskCache` as a
  `WriteCache` only.
- **Code location**: PSI shows it implements both `ReadCache` and
  `WriteCache`.
- **Verdict**: PARTIAL → CR6.

#### Invariant: I1
- **Verdict**: ASPIRATIONAL — Tracks 3/4/5 implement.

#### Invariant: I3
- **Verdict**: ASPIRATIONAL — Track 1 implements.

#### Invariant: I4
- **Verdict**: ENFORCED (broadly) — concrete lock names per component
  should be confirmed during Phase A → CR10.

#### Invariant: I5
- **Verdict**: ENFORCED.

---

## Part 2: Findings

### Finding CR1 [should-fix] — APPLIED
**Certificate**: Refs `LockFreeReadCache`, `WOWCache`,
  `DirectMemoryOnlyDiskCache`, `StorageComponent`, `AbstractStorage`
  package paths.
**Issue**: Multiple in-scope file paths in the plan and backlog were
  wrong.
**Fix applied**: Updated paths in plan §Constraints and backlog Tracks
  1/4/5.

### Finding CR2 [blocker] — APPLIED
**Certificate**: Ref `StorageComponent.getFilledUpTo` call site count.
**Issue**: Stated count of 17 (and "10 pure-sizing") was off by one.
  Actual count is 16 (9 + 7).
**Fix applied**: Replaced 17→16 and (10 sites)→(9 sites) in plan §D2,
  plan Track 3, design §"Allocation discovery surface" intro and table,
  plus the backlog Track 3 heading.

### Finding CR3 [should-fix] — APPLIED
**Certificate**: Ref `StorageComponent.addPage` call site count and
  Ref `loadOrAddPageForWrite` already exists.
**Issue**: "20 addPage callers" double-counted a self-call;
  `loadOrAddPageForWrite` already exists today.
**Fix applied**: Plan and design now distinguish "19 external + 1
  internal recursive call"; backlog Track 4 explicitly enumerates the
  rewire of the existing `loadOrAddPageForWrite` body.

### Finding CR4 [should-fix] — APPLIED
**Certificate**: Gap on `AtomicOperation.loadOrAddPageForWrite`.
**Issue**: design class diagram showed the method on
  `AtomicOperation`; plan didn't say it needed adding.
**Fix applied**: Backlog Track 4 §What now lists adding the method to
  `AtomicOperation` interface and implementing in
  `AtomicOperationBinaryTracking` as the first bullet.

### Finding CR5 [should-fix] — APPLIED
**Certificate**: Gap on `ReadCache.loadForWrite` rename.
**Issue**: Plan/design used the post-fix name `loadOrAddForWrite`
  without flagging the rename.
**Fix applied**: Backlog Track 1 §What now contains an explicit rename
  bullet; in-scope files include `ReadCache.java`.

### Finding CR6 [should-fix] — APPLIED
**Certificate**: Gap on `DirectMemoryOnlyDiskCache` dual interface.
**Issue**: Track 1's wording didn't explain the in-memory engine's
  dual implementation.
**Fix applied**: Backlog Track 1 §What now states: "this single class
  implements both `ReadCache` and `WriteCache`, so the new ReadCache
  wrappers and the WriteCache primitive live side-by-side; update both
  API surfaces in lockstep."

### Finding CR7 [suggestion] — APPLIED
**Certificate**: Ref design.md spelling at line 164.
**Issue**: One occurrence of `EnsureValidPageInFileTask` (wrong order).
**Fix applied**: design.md line 164 now reads `EnsurePageIsValidInFileTask`.

### Finding CR8 [suggestion] — APPLIED
**Certificate**: Ref `DiskStorage.backupPagesWithChanges:1404`.
**Issue**: Ambiguous — `:1404` is the call site, method is at `:1387`.
**Fix applied**: Plan and backlog now read "method @ :1387,
  `getFilledUpTo` call @ :1404".

### Finding CR9 [suggestion] — APPLIED
**Certificate**: Ref `LockFreeReadCache.data` type.
**Issue**: Class diagram referenced `SegmentedMap<PageKey,
  CacheEntry>` — neither type exists.
**Fix applied**: Class diagram now reads
  `data: ConcurrentLongIntHashMap~CacheEntry~`; followed by an
  explanatory bullet noting there is no `PageKey` class.

### Finding CR10 [suggestion] — APPLIED
**Certificate**: Invariant I4.
**Issue**: I4 named "BTree mutex, position-map mutex" without citing
  concrete lock fields.
**Fix applied**: Added Phase A audit note in design §Concurrency
  model: implementer must pin the concrete lock-field name per
  component before migrating Track 4.

---

### Finding CR11 [should-fix] — APPLIED (iteration 2)
**Certificate**: Gate verification re-scan after CR1 fix.
**Issue**: Plan §Constraints (line 30) and backlog Track 4
  §Constraints listed `storage/impl/local/atomicoperations/...` for
  `AtomicOperation` and `AtomicOperationBinaryTracking`. Actual paths
  require a `paginated/` segment:
  `storage/impl/local/paginated/atomicoperations/...`. PSI verified
  the actual paths.
**Fix applied**: Added `paginated/` segment in plan §Constraints and
  backlog Track 4 §Constraints.

### Finding CR12 [suggestion] — APPLIED (iteration 2)
**Certificate**: Gate verification re-scan after CR2 fix.
**Issue**: Plan Track 3 prose listed `SharedLinkBagBTree` as a
  sizing-site host alongside BTree, CollectionPositionMapV2, etc. PSI
  showed all 3 SharedLinkBagBTree `getFilledUpTo` references (lines
  922, 927, 1050) are inside `splitNonRootBucket` /
  `splitRootBucket` — probe sites, Track 4 scope.
**Fix applied**: Removed `SharedLinkBagBTree` from Track 3 plan prose;
  added a clarifying parenthetical that its three references are
  probe sites handled by Track 4.

---

## Summary

The plan and design are substantively correct. All 12 findings were
accepted and fixed across two iterations. Iteration 1 closed the
original review's 10 findings; iteration 2 closed the 2 new findings
the gate raised. Phase 2 step 1 (Consistency) PASSES.
