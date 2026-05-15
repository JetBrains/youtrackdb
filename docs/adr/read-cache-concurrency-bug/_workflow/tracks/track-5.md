# Track 5: Tighten `getFilledUpTo` access via gated helpers

## Description

Route the cross-component physical-size discovery channel through a
named, audit-grep-able helper set. The audit target lives on **two SPI
layers** (PSI-verified during Phase A iter-1 review): one direct
`WriteCache.getFilledUpTo` caller (`DiskStorage.backupPagesWithChanges`),
and five sites that reach it indirectly via
`StorageComponent.getFilledUpTo` → `AtomicOperation.filledUpTo` →
`AOBT.filledUpTo` → `WriteCache.getFilledUpTo`. Phase A picks the
helper shape per layer; the audit-grep target shifts from
"`WriteCache.getFilledUpTo` callers" to "named helper-set callers"
plus a `@Deprecated` annotation on `WriteCache.getFilledUpTo` itself
(a literal package-private downgrade is JLS-illegal on an interface
abstract method and would break the cross-package internal callers
in `…cache.chm` / `…atomicoperations` / `…disk` anyway — see
**Constraints** below). The track also absorbs the Track 4 Phase C
rename `loadOrAddPageForWrite` → `allocatePageForWrite` (see
**Track 4 Phase C deferral absorbed** below).

> **What**:
> - **Audit-grep target reshape, not access modifier change.** Mark
>   `WriteCache.getFilledUpTo` `@Deprecated` with a Javadoc directing
>   future callers to the gated helpers; the helper set becomes the
>   single audit-grep entry surface for "who reads physical size from
>   outside the cache/AOBT internal core?". A literal package-private
>   downgrade is structurally impossible (JLS §9.4: abstract interface
>   methods are implicitly `public abstract`; callers live in 5+
>   different packages that Java sub-packages do not unify).
> - **Surviving external consumer set, split by SPI layer (PSI-verified
>   in iter-1):**
>   - **Layer A — direct `WriteCache.getFilledUpTo` caller (1 site):**
>     - `DiskStorage.backupPagesWithChanges` (method @ :1387, call @
>       :1404) — backup-snapshot iteration. NOTE: the `freezeWriteOperations`
>       at `DiskStorage.storeBackupDataToStream:1248` is unfrozen at
>       `:1262` BEFORE `backupPagesWithChanges` runs at `:1289`, so
>       this is **not** a storage-quiesced iteration — concurrent
>       writes can extend the file during the read. Correctness comes
>       from later WAL replay during restore, not from quiesce.
>   - **Layer B — sites that reach `getFilledUpTo` via
>     `StorageComponent.getFilledUpTo(atomicOperation, fileId)` (which
>     calls `op.filledUpTo` → `AOBT.filledUpTo` → `WriteCache.getFilledUpTo`
>     on the committed-file fall-through, 8 call sites across 6
>     method/file combinations, all PSI-verified by `ReferencesSearch`
>     on `StorageComponent.getFilledUpTo(AtomicOperation, long)`):**
>     - `CollectionPositionMapV2.create:141` — bootstrap emptiness
>       check (`if filledUpTo == 0`).
>     - `PaginatedCollectionV2.initCollectionState:2271` — same
>       bootstrap pattern.
>     - `PaginatedCollectionV2.open:396` — FSM-rebuild recovery scan
>       (`for pageIndex = 0; pageIndex < filledUpTo; pageIndex++`).
>     - `IndexHistogramManager.readSnapshotFromPage:1843` — defensive
>       HLL page-1 presence probe (`if filledUpTo > 1`). Track 4
>       explicitly forbade switching this to `loadIfPresent`
>       (`IndexHistogramManager.java:1841` carries the rationale
>       comment); it stays in the consumer set unconditionally.
>     - `FreeSpaceMap.updatePageFreeSpace:237` — growth-loop
>       pre-read before `for (i = filledUpTo; i <= target; i++)
>       loadOrAddPageForWrite(...)`; EP-less, runs under per-component
>       lock.
>     - `CollectionDirtyPageBitSet.{clear:146, nextSetBit:178,
>       ensureCapacity:213}` — three sites: emptiness/bounds reads at
>       `clear` and `nextSetBit`, growth-loop pre-read at
>       `ensureCapacity`. All EP-less, all under per-component lock.
> - **Documented internal callers (not migrated, no helper — they
>   represent the legitimate residual surface):**
>   - `LockFreeReadCache.doLoad:307` — pre-call file-size snapshot for
>     `markAllocated` flag computation.
>   - `AtomicOperationBinaryTracking.loadOrAddPageForWrite:565` —
>     allocation-floor classifier for the `isNew` slow path.
>   - `AtomicOperationBinaryTracking.filledUpTo:765` — committed-file
>     fall-through (the routing point for Layer B above).
>   - `WOWCache` / `DirectMemoryOnlyDiskCache` — interface implementers.
>   The track's Javadoc on `WriteCache.getFilledUpTo` enumerates this
>   set as documented exemptions.
> - **Helper-shape decision is per-layer (Phase A picks during
>   decomposition):**
>   - **Layer A (1 site)** — single named helper on `WriteCache`, e.g.,
>     `physicalSizeForBackupSnapshot(long fileId)`. The name calls out
>     the post-unfreeze read semantics so future readers do not assume
>     quiesce.
>   - **Layer B (8 call sites, 6 method/file combinations)** — choice
>     between (i) one helper + `PhysicalReadIntent` enum on
>     `StorageComponent`, e.g., `physicalSize(AtomicOperation, long,
>     PhysicalReadIntent)` with intents `BOOTSTRAP_EMPTINESS_CHECK`,
>     `RECOVERY_REBUILD`, `DEFENSIVE_PRESENCE`,
>     `EP_LESS_PURE_SIZING`, `GROWTH_LOOP_PRE_READ`; or (ii) 3-4 named
>     protected helpers (`physicalSizeForBootstrap`,
>     `physicalSizeForRecovery`, `physicalSizeUnderComponentLock`,
>     `physicalSizeBeforeGrowthLoop`). Phase A picks one based on
>     which produces clearer call sites — the 8 sites split across
>     three behavior shapes: emptiness predicate (CPMV2:141,
>     PCV2:2271), iteration upper bound (PCV2:396, CDPB:146, 178),
>     growth-loop pre-read (FSM:237, CDPB:213), defensive presence
>     probe (IHM:1843) (see iter-1 finding T9 + iter-2 gate-check
>     T10). Layer B helpers MUST route through
>     `atomicOperation.filledUpTo(fileId)` so the existing AOBT
>     placeholder side-effect at `:757-758` is preserved (registering
>     a `FileChanges` entry on first touch in a TX); bypassing AOBT
>     would change in-TX semantics for callers that depend on the
>     placeholder.
> - Add Javadoc to `WriteCache.getFilledUpTo`, the new helpers on
>   `WriteCache` / `StorageComponent`, and the `WriteCache` interface
>   header documenting the discovery contract: cross-TX readers route
>   through the logical surface (EP) when one exists; physical-size
>   reads from outside the documented internal set route through the
>   gated helper(s); the documented internal set is enumerated.
>
> **How**:
> - **Step 1 (Phase A decision)**: pick the per-layer helper shapes.
>   Layer A: confirm `physicalSizeForBackupSnapshot` (or equivalent)
>   as the single name. Layer B: pick between intent-enum and 2-3
>   named helpers. Decision records into Step 1's commit message and
>   the step file's `**Notes**` (Phase A appends as part of
>   decomposition).
> - **Step 2**: introduce the helper(s) on `WriteCache` and
>   `StorageComponent`. The Layer A helper body wraps `getFilledUpTo`
>   (`return getFilledUpTo(fileId);` — preserve the existing
>   `filesLock` read-lock acquisition + null-file safety inside the
>   WOWCache implementation). The Layer B helper body calls
>   `atomicOperation.filledUpTo(fileId)` so the AOBT placeholder
>   side-effect is preserved.
> - **Step 3**: migrate each surviving consumer to the new helper(s).
>   Per-consumer test suites pinning the behavior (PSI-derived in
>   iter-1, full list assigned in Phase A decomposition):
>   - DiskStorage:1404 → backup IT suites (`DiskStorageBackupTest`,
>     `LocalPaginatedStorageBackupTest`, `StorageBackup*MTStateTest`).
>   - CPMV2:141, PCV2:2271 → `CollectionPositionMapV2Test`,
>     `PaginatedCollectionV2Test` (existing emptiness coverage).
>   - PCV2:396 → FSM-rebuild reopen-after-crash tests (Phase A
>     decomposition enumerates concrete suite names).
>   - IHM:1843 → HLL-spill defensive-probe tests (Phase A
>     enumeration).
>   - FSM:237 → `FreeSpaceMapTest` growth-loop coverage (Phase A
>     enumeration).
>   - CDPB:{146, 178, 213} → `CollectionDirtyPageBitSetTest` (the
>     `ensureCapacity` growth-loop site needs a dedicated test if
>     not already covered — Phase A decomposition verifies).
> - **Step 4**: mark `WriteCache.getFilledUpTo` `@Deprecated` with a
>   contract-stating Javadoc; add `@SuppressWarnings("deprecation")`
>   on the documented-internal callers (LFRC, AOBT x2, WOWCache,
>   DirectMemoryOnlyDiskCache override sites). Build + tests green.
> - **Step 5 (rename — Track 4 Phase C deferral)**: IDE-driven Rename
>   refactoring `AtomicOperation.loadOrAddPageForWrite` →
>   `allocatePageForWrite` via mcp-steroid's change-signature recipe.
>   Preflight discipline:
>   1. `git status` shows a clean working tree.
>   2. Maven re-import after any recent POM edit
>      (`MavenProjectsManager.scheduleUpdateAllMavenProjects` or
>      `./mvnw -pl core compile -DskipTests` outside the IDE).
>   3. PSI find-usages run at all three declaration sites
>      (`AtomicOperation` SPI, `AtomicOperationBinaryTracking` impl,
>      `StorageComponent` wrapper) AND the direct SPI-typed call at
>      `PaginatedCollectionV2.java:2224`. Capture the pre-rename ref
>      count per site for the post-rename audit (Step 6 Verification
>      below). Includes the iter-1 / iter-2-gate-check observations:
>      ~43 AOBT-impl refs in tests, ~21 StorageComponent wrapper refs
>      across BTree / SLBB / CPMV2 / PCV2 / FSM / CDPB / IHM
>      allocator sites, 6+ SPI-direct refs.
>   4. Rollback: this step lands as its own commit. Pre-commit rollback
>      is `git reset --hard HEAD` + `git clean -fd` (the test class
>      rename is a create + delete pair). Post-commit rollback is
>      `git revert <commit>`.
> - **Verification**:
>   - PSI find-usages on `WriteCache.getFilledUpTo` should match the
>     documented internal set + Layer A helper body + LFRC.doLoad +
>     AOBT.{loadOrAddPageForWrite, filledUpTo} (production) plus the
>     existing AOBT test classes. Any new external production caller
>     after this track is a regression.
>   - PSI find-usages on the new helper(s) should match the surviving
>     consumer set exactly: Layer A helper → 1 caller; Layer B helper(s)
>     → 8 call sites (the 8 sites enumerated in `**What**`: CPMV2:141,
>     PCV2:{2271, 396}, IHM:1843, FSM:237, CDPB:{146, 178, 213}).
>     Extra or missing callers are findings.
>   - PSI find-usages on `allocatePageForWrite` (post-rename) should
>     enumerate every reference at all three declaration sites (SPI
>     interface, AOBT impl, StorageComponent wrapper) and on every
>     polymorphic caller; the IDE Rename engine handles all atomically.
>     The pre-rename PSI snapshot (Step 5 preflight item 3) sets the
>     expected count; no stragglers left at the old name
>     `loadOrAddPageForWrite` after the commit.
>
> **Constraints**:
> - **JLS §9.4**: `WriteCache.getFilledUpTo` is an abstract method on
>   a non-private interface; it is implicitly `public abstract` and
>   cannot be declared package-private or `protected`. This is the
>   root cause of the "non-public is structurally impossible" finding
>   from iter-1 (T1, R1, A1). The audit-grep contract is enforced by
>   `@Deprecated` + Javadoc + helper-set naming, not by access
>   modifier.
> - **AOBT placeholder side-effect**: `AOBT.filledUpTo:757-758`
>   registers a `FileChanges` entry as a side effect on first call
>   for a fileId in a TX. Layer B helpers MUST route through
>   `atomicOperation.filledUpTo(fileId)` to preserve this — bypassing
>   AOBT changes in-TX semantics for callers that depend on the
>   placeholder.
> - **AOBT `isNew` slow-path read**: `AOBT.loadOrAddPageForWrite:565`
>   directly calls `writeCache.getFilledUpTo(fileId)` inside the
>   per-component exclusive lock. This caller stays. Mark with
>   `@SuppressWarnings("deprecation")` to silence the new deprecation
>   warning.
> - **IHM HLL-spill discriminator**: `IHM.{writeSnapshotToPage:1929,
>   flushSnapshotToPage:1998}` call `op.filledUpTo` (NOT
>   `WriteCache.getFilledUpTo`); they are unaffected by Track 5's
>   `WriteCache.getFilledUpTo` changes. Track 5 does NOT narrow
>   `AtomicOperation.filledUpTo` — the SPI method stays accessible
>   to IHM.
> - **In-scope files**:
>   - `core/.../internal/core/storage/cache/WriteCache.java` (Javadoc +
>     `@Deprecated`).
>   - `core/.../internal/core/storage/cache/local/WOWCache.java`
>     (`@Override` + helper body for Layer A; `@SuppressWarnings` on
>     the implementer).
>   - `core/.../internal/core/storage/memory/DirectMemoryOnlyDiskCache.java`
>     (mirror Layer A helper).
>   - `core/.../internal/core/storage/disk/DiskStorage.java`
>     (backup consumer migration; method @ :1387, call @ :1404).
>   - `core/.../internal/core/storage/cache/chm/LockFreeReadCache.java`
>     (`@SuppressWarnings` on the documented-internal caller).
>   - `core/.../storage/impl/local/paginated/base/StorageComponent.java`
>     (Layer B helpers + Javadoc).
>   - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java`
>     (`@SuppressWarnings` on `:565` and `:765`).
>   - The 5 Layer B consumer call sites (CollectionPositionMapV2,
>     PaginatedCollectionV2, IndexHistogramManager,
>     CollectionDirtyPageBitSet).
>   - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperation.java`
>     (rename SPI declaration).
>   - `core/.../storage/impl/local/paginated/base/StorageComponent.java`
>     (rename wrapper method).
>   - Test files affected by the rename (see **Track 4 Phase C
>     deferral absorbed** below).
> - **Out of scope**: functional behavior changes; new WAL records;
>   widening the surviving-consumer set beyond what is enumerated
>   above; narrowing `AtomicOperation.filledUpTo`.
>
> **Interactions**:
> - Depends on Track 4 (write-side API collapse done — replay loops
>   no longer call `getFilledUpTo` via `internalFilledUpTo`, and the
>   rationale-comment markers are pinned by Track 4's per-site
>   commits — `grep -rn 'gated \`physicalSize\`-shaped helper'` in
>   `core/src/main` returns exactly the 5 Layer B sites today, the
>   existing audit-grep marker the new helpers replace).
> - Enables nothing downstream (this is the final cache-API hygiene
>   pass).
> - Verifies invariant **I1** is convention-enforceable — the gated
>   helper set is the audit-grep target; `@Deprecated` on
>   `WriteCache.getFilledUpTo` plus the documented internal-caller
>   exemptions document the residual surface; new external callers
>   trip a deprecation warning at compile time and a Javadoc-driven
>   audit at code-review time. I1's "not on the public discovery
>   path" reads as "not on the audit-grep target list", not as JLS
>   access narrowing.

### Clarifications

The "≥ 5 surviving consumers of `WriteCache.getFilledUpTo`" wording
in D4 reads literally as five direct callers of the cache method.
The iter-1 PSI audit established the actual graph: 1 direct caller
(`DiskStorage.backupPagesWithChanges`) + 5 indirect callers via
`StorageComponent.getFilledUpTo` → `AtomicOperation.filledUpTo`. D4
itself anticipates this by saying helpers route through `WriteCache`
**and/or** `StorageComponent`; the Track 5 step file's per-site list
above resolves the ambiguity.

The "≥ 5" count remains accurate (5 indirect Layer B + 1 direct
Layer A = 6 total external consumers), but the **helper-set lives on
both SPI layers**, not just `WriteCache`.

## Track 4 Phase C deferral absorbed — rename `loadOrAddPageForWrite` → `allocatePageForWrite`

Track 4's Phase C review surfaced that the post-Step-2 AOBT-layer
contract is **allocator-only** on the disk engine (`pageIndex` below
the committed file size raises `IllegalStateException`), yet the
method name and the surrounding API still read as "load or add"
(total semantics). The AOBT Javadoc admits this explicitly: *"Despite
the historical name, this method does NOT load existing pages on the
disk engine."* The misleading name propagates from the cache-layer
primitive `WriteCache.loadOrAdd` (which IS total) through the AOBT
wrapper (allocator-only) and the `StorageComponent` wrapper to every
production call site.

This track absorbs the rename because Track 5's existing charter is
the post-Track-4 API hygiene pass.

**Recommended new name:** `allocatePageForWrite(fileId, pageIndex)` —
parallel naming with the existing `loadPageForWrite(fileId, pageIndex,
pageCount, verifyChecksum)`. The `<verb>PageForWrite` shape makes
load-vs-allocate intent explicit at every call site.

**Scope (PSI-verified during Phase A iter-1):**
- `AtomicOperation.loadOrAddPageForWrite` (SPI interface declaration,
  1 site + Javadoc + `{@link}` cross-refs) → `AtomicOperation.allocatePageForWrite`.
  PSI find-usages on the SPI method: 6 references.
- `AtomicOperationBinaryTracking.loadOrAddPageForWrite` (impl, 1
  declaration + Javadoc). PSI find-usages on the AOBT impl: 43
  references (40 in tests, 2 self-refs + Javadoc).
- `StorageComponent.loadOrAddPageForWrite` (the
  `core/.../paginated/base/StorageComponent.java` protected wrapper).
  PSI find-usages: 21 production call sites split across BTree (4 —
  including `BTree.create` 3-site triplet + `allocateNewPage`), SLBB (5),
  CPMV2 (3), PCV2 (2), FSM (2), CDPB (2), IHM (3 — including the
  HLL-spill discriminator's allocate branch at `:1932`, `:2001`).
- **Total rename surface**: the three declaration sites carry their
  own PSI ref sets; ReferencesSearch returns are call-site dependent
  on whether overriding-methods are included. A conservative count
  pulled by iter-1 reviewers: ~43 AOBT-impl refs (mostly in tests) +
  ~21 StorageComponent wrapper refs (all production allocator sites)
  + 6+ SPI-direct refs including a confirmed direct caller at
  `PaginatedCollectionV2.java:2224` that bypasses the wrapper. Step 5
  preflight item 3 below captures the exact pre-rename PSI count for
  before/after audit. The "~109 textual occurrences across 18 files"
  estimate from Track 4 includes textual matches in Javadoc /
  comments that the IDE Rename engine catches via "Search in
  comments and strings"; the count is non-load-bearing because the
  IDE refactor handles every site atomically.
- **Test class rename**: `LoadOrAddPageForWriteTest` →
  `AllocatePageForWriteTest` (file + class header + every test-method
  narration that names the AOBT method). PSI on AOBT impl shows 30
  references inside `LoadOrAddPageForWriteTest`; 13 more across
  `AtomicOperationBinaryTrackingWALSkipTest`,
  `AtomicOperationSnapshotProxyTest`, `FlushPendingOperationsTest`,
  `PageOperationAccumulationLifecycleTest`, `RegisterPageOperationTest`.
- Javadoc `{@link AtomicOperation#loadOrAddPageForWrite}` cross-
  references — IDE Rename enumerates and updates them atomically.

**Why the IDE refactor (not raw `Edit`):**
- 100+ references across ~18 production + test files at Track-4 tip;
  raw `grep + Edit` would miss polymorphic dispatch through the SPI
  interface (different code paths see different `AtomicOperation`
  implementations) and would skip Javadoc `{@link}` references. The
  rename also touches a direct SPI-typed call at
  `PaginatedCollectionV2.java:2224` that does NOT go through the
  `StorageComponent` wrapper — confirming the IDE refactor is the
  only safe path (raw grep on `StorageComponent.loadOrAddPageForWrite`
  alone would silently miss this site).
- Per project `CLAUDE.md` § MCP Steroid → Refactoring: "Renames,
  moves, signature changes, … and any refactor that touches more
  than one reference site route through the IDE refactoring engine
  via mcp-steroid, not raw Edit."
- Phase A of Track 5 verifies mcp-steroid is reachable at session
  start (it was, per the SessionStart preflight). If a future
  resume session finds mcp-steroid unreachable, surface to the user
  and pause Step 5 — do NOT fall back to `git grep + Edit` for the
  rename.
- The Step 5 preflight discipline in `**How**` above (clean working
  tree, Maven re-import, PSI count verification, rollback story) is
  the operational checklist for invoking the rename.

**Note on naming asymmetry**: the new name `allocatePageForWrite`
captures the disk-engine contract (allocator-only post-Track-4-Step-2,
`pageIndex` below the committed file size raises
`IllegalStateException`) but under-describes the in-memory engine
behavior, where AOBT still eagerly installs on a non-fresh page (the
`eagerlyInstalledInCache` path Track 4 Step 5a preserved). Step 5's
commit message includes a Javadoc note on the AOBT impl spelling out
the cross-engine asymmetry: "allocator-only on disk; eager-install
total on in-memory". The `LoadOrAddPageForWriteTest` →
`AllocatePageForWriteTest` rename carries forward the same Javadoc
note in the class header so test-suite readers see the asymmetry up
front.

**Plan-correction commits introducing this deferral:**
- `9dff2ac2e3` (Track 6 scope expansion + Non-Goals unit-level
  backlog).
- The plan correction adding this Track 5 rename absorption (this
  commit, recorded in Track 4's track-completion summary).

## Progress
- [x] Review + decomposition
- [ ] Step implementation (4/6 complete)
- [ ] Track-level code review

## Base commit
`15b6800f5a`

## Reviews completed
- [x] Technical: PASS at iteration 3 (9 iter-1 findings + 1 iter-2 blocker T10 + 1 iter-2 should-fix T11; all addressed by iter-3 step-file rewrite — JLS reframe, Layer A / Layer B consumer split with PSI-verified 8 Layer B sites, AOBT placeholder side-effect constraint, count language relaxed)
- [x] Risk: PASS at iteration 3 (8 iter-1 findings + R10 mirror of T10; all addressed — `@Deprecated` mechanism, two-layer helper-shape, IDE rename preflight discipline, per-consumer test suites enumerated)
- [x] Adversarial: PASS at iteration 3 (8 iter-1 findings + A9 mirror of T10 + A10 cosmetic; D4/D5 wording polish — A6/A7 — deferred non-blocking to future plan correction; all other findings addressed in step file)

## Phase A helper-shape decision (recorded for the implementer)

- **Layer A**: single named helper `WriteCache.physicalSizeForBackupSnapshot(long fileId)`. The name calls out the post-unfreeze snapshot semantics so future readers do not assume quiesce. Body wraps the existing `WriteCache.getFilledUpTo(fileId)`; preserves the `filesLock` read-lock acquisition and null-file safety inside the WOWCache implementation.
- **Layer B**: single helper `StorageComponent.physicalSize(AtomicOperation, long, PhysicalReadIntent)` with a nested `PhysicalReadIntent` enum (5 constants: `BOOTSTRAP_EMPTINESS_CHECK`, `RECOVERY_REBUILD`, `DEFENSIVE_PRESENCE`, `EP_LESS_PURE_SIZING`, `GROWTH_LOOP_PRE_READ`). 8 sites across 4 behavior shapes — enum scales better than 4 named helpers and each enum constant carries the audit-grep signature. Body calls `atomicOperation.filledUpTo(fileId)` so the AOBT placeholder side-effect at `:757-758` is preserved.

## Steps

- [x] Step 1: Introduce Layer A helper `WriteCache.physicalSizeForBackupSnapshot`
  - [x] Context: safe
  > **Risk:** medium — multi-file logic, modifies the `WriteCache` interface
  > contract that has two production implementers (`WOWCache`,
  > `DirectMemoryOnlyDiskCache`) plus test-mock impls; not on a HIGH-risk
  > category (no concurrency / durability / public-API / hot-path triggers).
  >
  > **What was done:** Added `WriteCache.physicalSizeForBackupSnapshot(long fileId)` —
  > a named, audit-grep-able alias for `getFilledUpTo` intended as the single
  > cross-component entry point for the post-unfreeze incremental-backup snapshot
  > reader. `WOWCache` and `DirectMemoryOnlyDiskCache` both implement it as thin
  > delegators wrapping their existing `getFilledUpTo` bodies; the disk-engine
  > wrapper re-enters the `filesLock` read-lock + null-file safety path through
  > the inner call. The five in-tree test mocks of `WriteCache`
  > (`LockFreeReadCacheOptimisticTest`, `LockFreeReadCacheConcurrentTestIT`,
  > `AsyncReadCacheTestIT`, `LockFreeReadCacheFileOpsTest`,
  > `LockFreeReadCacheBatchingTest`) gained matching delegator overrides so they
  > continue to compile. Two new test classes
  > (`WOWCachePhysicalSizeForBackupSnapshotTest`,
  > `DirectMemoryOnlyDiskCachePhysicalSizeForBackupSnapshotTest`) pin "helper
  > agrees with `getFilledUpTo` for the same `fileId`" across fresh-file,
  > one-extend, multi-extend, and (disk-only) deleted-file branches; 7/7 pass.
  > Spotless applied. Core tests: 171/172 (one pre-existing flaky MT test,
  > `WOWCacheLoadOrAddConcurrentTest#bareLoadOrAddOnSameKeySurfacesI4Sentinel`,
  > failed under the wider cache-layer set but passes in isolation — known
  > race-window asymmetry unrelated to this delegator addition).
  >
  > **What was discovered:** `WriteCache` has five in-tree mock implementations
  > in addition to the two production engines; adding any new abstract method to
  > the interface forces parallel updates across all seven. The same surface
  > will be touched by Step 6's rename refactor.
  >
  > **Critical context:** Pre-commit ephemeral-identifier gate caught a workflow-
  > internal invariant label in the `WriteCache` Javadoc draft and required a
  > rewrite that restates the discovery contract in repo-anchored prose. Future
  > steps on this branch authoring Javadoc on the same helper set should expect
  > to do the same — the durable-content rule applies to every Javadoc block this
  > track writes.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCache.java`
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCachePhysicalSizeForBackupSnapshotTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCachePhysicalSizeForBackupSnapshotTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/{AsyncReadCacheTestIT,LockFreeReadCacheBatchingTest,LockFreeReadCacheConcurrentTestIT,LockFreeReadCacheFileOpsTest,LockFreeReadCacheOptimisticTest}.java` (mock delegator additions)

- [x] Step 2: Migrate `DiskStorage.backupPagesWithChanges` to the Layer A helper
  - [x] Context: safe
  > **Risk:** medium — single-file call-site swap, but on a critical-path
  > (incremental backup); no HIGH-risk category (the helper body is a thin
  > delegator with no behavioral change). Backup IT suites cover the
  > behavior.
  >
  > **What was done:** Single-line call-site swap at `DiskStorage.java:1404` —
  > `writeCache.getFilledUpTo(fileId)` → `writeCache.physicalSizeForBackupSnapshot(fileId)`.
  > Post-edit PSI find-usages confirm the cache method's remaining production
  > caller set is exactly the documented internal set
  > (`LockFreeReadCache.doLoad:307`, `AtomicOperationBinaryTracking:565` and
  > `:765`) plus the new helper body in both engines; the new helper itself
  > has its single expected caller. `StorageBackupTest` ran 14/14 green;
  > `StorageBackupMTStateTest` remains `@Ignore`d per the Track 4 deferral.
  > No Track 4 rationale-comment marker was present at this site (markers
  > were reserved for indirect Layer B sites), so nothing needed to be dropped.
  >
  > **What was discovered:** PSI enumeration of the `StorageBackup*` class
  > set surfaced four candidates — `StorageBackupTest` (the actual functional
  > backup suite exercising `backupPagesWithChanges`), `StorageBackupMTStateTest`
  > (currently `@Ignore`d), `StorageBackupMTIT` (integration test, out of
  > scope for unit runs), and `StorageBackupTestWithLuceneIndex` (its
  > `@Test` annotations at `:64` and `:117` are commented out so the suite
  > contributes no live cases). The plan's placeholder names
  > `DiskStorageBackupTest` and `LocalPaginatedStorageBackupTest` do not
  > exist verbatim — the canonical suite name is `StorageBackupTest`.
  > Cross-track for Track 6: the commented-out `@Test` annotations in
  > `StorageBackupTestWithLuceneIndex` are relevant to the
  > `StorageBackupMTStateTest` `@Ignore`-resurrection bullet — if that
  > test is being un-skipped to cover the collapsed
  > `restoreFromIncrementalBackup` loop, the parallel Lucene-indexed case
  > may need un-commenting too. Worth flagging during Track 6 Phase A.
  >
  > **Critical context:** A stray background Maven coverage build (a prior
  > spawn's leftover) collided with the foreground coverage build on the
  > `BTreeReadMethodsTest` target directory, poisoning the first JaCoCo run
  > with 86 errors. After killing both processes and re-running clean,
  > the gate passed. Future spawns on this branch must keep a single Maven
  > cycle in flight per the project's same-worktree parallel-mvn rule.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/disk/DiskStorage.java`

- [x] Step 3: Introduce Layer B helper `StorageComponent.physicalSize` + `PhysicalReadIntent` enum
  - [x] Context: safe
  > **Risk:** medium — adds a protected method + nested enum to
  > `StorageComponent` (a load-bearing abstract base for storage
  > components). No HIGH-risk category triggers (the helper body
  > delegates to `atomicOperation.filledUpTo(fileId)`, identical to the
  > existing `StorageComponent.getFilledUpTo` semantics; the new enum
  > is an audit-grep marker, not a behavioral input).
  >
  > **What was done:** Added the Layer B gated entry point on
  > `StorageComponent`: a public nested enum `PhysicalReadIntent` with
  > five Javadoc-named constants (`BOOTSTRAP_EMPTINESS_CHECK`,
  > `RECOVERY_REBUILD`, `DEFENSIVE_PRESENCE`, `EP_LESS_PURE_SIZING`,
  > `GROWTH_LOOP_PRE_READ`) and a protected helper
  > `physicalSize(AtomicOperation, long, PhysicalReadIntent)` that
  > delegates to `op.filledUpTo(fileId)`. The intent argument is unused
  > at runtime and anchors audit-grep. Rewrote the class header Javadoc
  > to spell out the cross-TX discovery contract (logical surface
  > preferred via EntryPoint; physical surface via this helper otherwise)
  > and pointed readers at the cache-layer
  > `WriteCache.physicalSizeForBackupSnapshot` for the parallel
  > non-`StorageComponent` caller. Added `StorageComponentPhysicalSizeTest`
  > with two tests: a Mockito-based delegation pin that iterates every
  > enum constant and asserts `AtomicOperation.filledUpTo` is called
  > once with the right fileId, and a real-AOBT pin that verifies the
  > `fileChanges` placeholder is registered on first call (reflection
  > because AOBT is package-private to a different package). Targeted
  > suites green (this test 2/2, `StorageComponentOptimisticReadTest`
  > 21/21, `LoadOrAddPageForWriteTest` 22/22). Coverage on 232 changed
  > lines: 94.0% line / 81.2% branch.
  >
  > **What was discovered:** `AtomicOperationBinaryTracking` is
  > package-private to `…paginated.atomicoperations` — the new test
  > cannot reference it by type from `…paginated.base` and reaches the
  > class through `Class.forName(...)` plus reflection on the private
  > `fileChanges` field. Any future Layer B test that wants to exercise
  > the real AOBT three-arm `filledUpTo` body from outside the
  > atomic-operations package faces the same constraint — reflection
  > is the door. Tests living inside the AOBT package (e.g.,
  > `LoadOrAddPageForWriteTest`) avoid the reflection cost.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/base/StorageComponent.java`
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/base/StorageComponentPhysicalSizeTest.java` (new)

- [x] Step 4: Migrate Layer B consumer call sites (8 sites across 6 files)
  - [x] Context: safe
  > **Risk:** medium — multi-file logic across 6 production files in the
  > `core` module; each site is a 1-2 line call-site swap from
  > `getFilledUpTo(op, fileId)` to `physicalSize(op, fileId, intent)`
  > with the intent picked per site. No HIGH-risk category triggers
  > (no behavioral change; existing test suites already pin the
  > behaviors).
  >
  > **What was done:** Migrated all eight Layer B consumer call sites
  > from `StorageComponent.getFilledUpTo(op, fileId)` to
  > `physicalSize(op, fileId, PhysicalReadIntent.<INTENT>)` across
  > `CollectionPositionMapV2.create:141` (BOOTSTRAP_EMPTINESS_CHECK),
  > `PaginatedCollectionV2.initCollectionState:2271`
  > (BOOTSTRAP_EMPTINESS_CHECK), `PaginatedCollectionV2.open:396`
  > (RECOVERY_REBUILD), `IndexHistogramManager.readSnapshotFromPage:1843`
  > (DEFENSIVE_PRESENCE), `FreeSpaceMap.updatePageFreeSpace:237`
  > (GROWTH_LOOP_PRE_READ), and the three `CollectionDirtyPageBitSet`
  > sites (`clear:146` + `nextSetBit:178` → EP_LESS_PURE_SIZING;
  > `ensureCapacity:213` → GROWTH_LOOP_PRE_READ). Dropped the literal
  > `gated 'physicalSize'-shaped helper` rationale-anchor phrase at the
  > six sites that carried it (CPMV2, PCV2 ×2, CDPB ×2, IHM) and
  > tightened the prose to a brief intent-anchored explanation. CDPB's
  > `ensureCapacity` and `nextSetBit` kept their core-invariant
  > comments with the marker phrase replaced. FSM gained an explicit
  > "pre-read fixes starting point against current physical state"
  > line mirroring the `GROWTH_LOOP_PRE_READ` Javadoc on the helper.
  > PSI verification: 8 production callers on `physicalSize`,
  > 0 remaining `StorageComponent.getFilledUpTo` callers in
  > `core/src/main` (the only hit is the Javadoc `{@link}` inside
  > `StorageComponent.java` itself). 293/293 targeted tests pass.
  >
  > **What was discovered:** `LocalPaginatedCollectionV2TestIT` carries
  > a pre-existing 9-error cascade rooted in `testAddManyRecords` — an
  > async flush task (`WOWCache.flushWriteCacheFromMinLSN` →
  > `AsyncFile.getUnderlyingFileSize`) hits a `NoSuchFileException` on
  > the `.fsm` file after a prior test's cleanup, poisons the storage
  > error-state, and cascades into 8 downstream tests failing at
  > `checkErrorState`. The cascade reproduces on the base commit
  > without any Track 5 changes; it disappears when `testAddManyRecords`
  > runs in isolation. **Cross-track impact for Track 6 (CS1 / I4
  > MT pins):** the LPCV2 IT suite's async-flush vs test-cleanup race
  > window should be factored into the new MT pin design — quiesce
  > the flush worker before asserting file state, or isolate the new
  > tests in their own class to avoid the shared-storage cleanup
  > window. Worth flagging in Track 6 Phase A.
  >
  > **Critical context:** Step 5's `@Deprecated` round and Step 6's IDE
  > rename are unaffected by this step's edits — the only remaining
  > `StorageComponent.getFilledUpTo` hit in `core/src/main` is its own
  > Javadoc `{@link}`, and its callers in tests are untouched by this
  > step.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/CollectionPositionMapV2.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/PaginatedCollectionV2.java` (2 sites)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/FreeSpaceMap.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/CollectionDirtyPageBitSet.java` (3 sites)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexHistogramManager.java` (1 site — the defensive probe at `:1843`; the HLL-spill discriminator calls use `op.filledUpTo` directly and are out of scope)

- [ ] Step 5: `@Deprecated` `WriteCache.getFilledUpTo` + `@SuppressWarnings` on documented internal callers
  > **Risk:** low — annotation + Javadoc changes with no behavioral
  > impact. No HIGH or MEDIUM triggers (per `risk-tagging.md` §"LOW-risk
  > default"). The intra-module audit-grep contract is convention-based;
  > this step makes the deprecation warning machine-checkable.
  >
  > Mark `WriteCache.getFilledUpTo(long)` `@Deprecated(forRemoval = false)`
  > with a Javadoc directing future callers to
  > `WriteCache.physicalSizeForBackupSnapshot` (Layer A) or
  > `StorageComponent.physicalSize(op, fileId, intent)` (Layer B). The
  > Javadoc enumerates the documented internal callers (`LFRC.doLoad`,
  > `AOBT.{loadOrAddPageForWrite, filledUpTo}`, the new Layer A helper
  > body in `WOWCache` / `DirectMemoryOnlyDiskCache`) — these stay and
  > carry `@SuppressWarnings("deprecation")` so the deprecation warning
  > does not noise-up the build.
  >
  > Build with `-Xlint:deprecation` (or check the existing Maven
  > warning baseline) and confirm only the documented internal-caller
  > sites trigger the deprecation warning post-suppression.
  >
  > **Files:**
  > - `core/.../internal/core/storage/cache/WriteCache.java` (Javadoc + annotation)
  > - `core/.../internal/core/storage/cache/chm/LockFreeReadCache.java` (`@SuppressWarnings` on the `doLoad:307` caller)
  > - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java` (`@SuppressWarnings` on `:565` and `:765` — both inside the existing method scope, so a single annotation at method level may suffice; Phase B picks the minimum scope)
  > - `core/.../internal/core/storage/cache/local/WOWCache.java` (`@SuppressWarnings` on the Layer A helper body + the legacy `getFilledUpTo` impl)
  > - `core/.../internal/core/storage/memory/DirectMemoryOnlyDiskCache.java` (parallel)

- [ ] Step 6: IDE rename `AtomicOperation.loadOrAddPageForWrite` → `allocatePageForWrite`
  > **Risk:** high — SPI surface change with ~100+ polymorphic references
  > across 18 files; the `AtomicOperation` interface dispatches to the
  > AOBT impl which is allocator-only on disk and total on in-memory.
  > Triggers Architecture / cross-component coordination (large blast
  > radius, polymorphic dispatch, Javadoc `{@link}` references). IDE
  > Rename engine is the only safe path; raw `Edit` or `grep` would
  > silently miss the direct SPI-typed call at
  > `PaginatedCollectionV2.java:2224` plus Javadoc cross-references.
  >
  > Run via `mcp-steroid` change-signature recipe with the preflight
  > discipline in `## Description` `**How**` Step 5: clean working
  > tree, Maven re-import after any recent POM edit, capture the
  > pre-rename PSI ref counts at all three declaration sites
  > (`AtomicOperation` SPI, `AtomicOperationBinaryTracking` impl,
  > `StorageComponent` wrapper) plus the direct SPI call at
  > `PaginatedCollectionV2.java:2224`. Test class rename
  > `LoadOrAddPageForWriteTest` → `AllocatePageForWriteTest` (file
  > rename + class header + test-method narration).
  >
  > Add a Javadoc note on `AtomicOperationBinaryTracking.allocatePageForWrite`
  > (post-rename) spelling out the cross-engine asymmetry: "allocator-only
  > on disk; eager-install total on in-memory". Carry the same note on
  > the renamed test class header so test-suite readers see the asymmetry
  > up front.
  >
  > Build + run the full core test suite (`./mvnw -pl core clean test`).
  > Post-rename PSI find-usages on `allocatePageForWrite` should match
  > the captured pre-rename count from the preflight; PSI find-usages
  > on `loadOrAddPageForWrite` should return zero (no stragglers).
  >
  > **Files:** ~18 via IDE rename, including:
  > - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperation.java`
  > - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java`
  > - `core/.../storage/impl/local/paginated/base/StorageComponent.java`
  > - 19 production allocator call sites (BTree, SLBB, CPMV2, PCV2,
  >   FSM, CDPB, IHM) — the `StorageComponent` wrapper callers (21
  >   PSI refs).
  > - Direct SPI-typed caller at `PaginatedCollectionV2.java:2224`.
  > - Test class rename + 40+ test method refs across
  >   `LoadOrAddPageForWriteTest` (becomes `AllocatePageForWriteTest`),
  >   `AtomicOperationBinaryTrackingWALSkipTest`,
  >   `AtomicOperationSnapshotProxyTest`,
  >   `FlushPendingOperationsTest`,
  >   `PageOperationAccumulationLifecycleTest`,
  >   `RegisterPageOperationTest`.
