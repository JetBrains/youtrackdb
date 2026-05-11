# Read-cache concurrency bug — Track Details

## Track 4: Write-side API collapse + residual read-side migration

> **What**:
> - Add `loadOrAddPageForWrite(long fileId, long pageIndex) → CacheEntry`
>   to the `AtomicOperation` interface and implement in
>   `AtomicOperationBinaryTracking`. Today the interface has
>   `addPage(long)` and `loadPageForWrite(...)` but no
>   `loadOrAddPageForWrite`.
> - Rewire `StorageComponent.loadOrAddPageForWrite(AtomicOperation,
>   fileId, pageIndex)` (which already exists at
>   `StorageComponent.java:149` as a `loadPageForWrite`-then-`addPage`
>   fallback with 2 prod callers in `IndexHistogramManager`) to
>   delegate to `atomicOperation.loadOrAddPageForWrite(fileId,
>   pageIndex)` instead of falling back to `addPage`.
> - Delete `StorageComponent.addPage` and `AtomicOperation.addPage`.
> - Migrate the 19 external `addPage` call sites to
>   `loadOrAddPageForWrite(fileId, knownIndex)`. (PSI shows 20 total
>   references on `StorageComponent.addPage`; one is the recursive call
>   from inside `StorageComponent.loadOrAddPageForWrite` itself, which
>   the rewire above removes. Two of the 19 are growth-loop probes
>   previously labelled as Track 3 pure-sizing reads — counted within
>   the 19 here, with the additional `getFilledUpTo` read at
>   `FSM:227` / `CDPB:194` collapsing alongside the `addPage` deletion;
>   see growth-loop bullet below.)
>   Approximate split — Phase A confirms the exact partition:
>   - ~9 sites inside `create()` / `init()` / `createEmptyStatsPage()` —
>     fresh-file sequential allocation at pageIndex 0 or 1.
>   - ~8 sites inside reuse-or-extend probes — `entryPoint.pagesSize + 1`
>     is the target.
>   - 2 sites inside growth-loops (`for (i = filledUpTo; i ≤ required;
>     i++) addPage(...)`) absorbed from the retired Track 3 — see
>     growth-loop bullet below.
> - Delete the reuse-or-extend probe blocks themselves at every site
>   (the cache absorbs orphans uniformly via Track 1's `loadOrAdd`):
>   `BTree.allocateNewPage`, `SharedLinkBagBTree.{splitNonRootBucket,
>   splitRootBucket}`, `CollectionPositionMapV2.allocate`,
>   `PaginatedCollectionV2.allocateNewPage`. Replace each
>   `if (pageSize < filledUpTo - 1) { reuse } else { extend }` block
>   with a single `loadOrAddPageForWrite(fileId, pagesSize + 1)` call.
> - Collapse growth-loops at `FreeSpaceMap.updatePageFreeSpace:227` and
>   `CollectionDirtyPageBitSet.ensureCapacity:194` — both read
>   `getFilledUpTo` and call `addPage` repeatedly to extend the file.
>   These sites were previously listed under Track 3 as pure-sizing
>   reads; the retired-Track-3 Phase A audit reclassified them as
>   probe-shaped (the `filledUpTo` value is the loop-start index, not
>   a sizing read). Replace the loop body with a single
>   `loadOrAddPageForWrite(fileId, knownIndex)` per required page, using
>   the same primitive as the other probe sites. Since both components
>   are EP-less, there is no `entryPoint.setPagesSize` follow-up — the
>   `loadOrAdd` totality is sufficient.
> - Migrate the lone pure-sizing read at `BTree.doAssertFreePages:1389`
>   to `entryPoint.getPagesSize() + 1` (post-Track-1 the BTree probe
>   sites already load the EP page; `doAssertFreePages` loads a fresh
>   entry-point page once at method entry, wraps it in
>   `CellBTreeSingleValueEntryPointV3`, and uses the getter). This is
>   a test-only assertion path (`-ea` only via
>   `BTree.assertFreePages:1373`), so the added page-pin cost is
>   inconsequential in production builds.
> - Add rationale-bearing inline comments at the 4 stay-on-physical
>   sites (no code change beyond the comment + javadoc): name the
>   contract that justifies physical-size access in each location.
>   These sites read `getFilledUpTo` via the gated helper Track 5
>   introduces.
>   - `CollectionPositionMapV2.create:136` — fresh-file emptiness check;
>     EntryPoint lives on the page being checked (chicken-and-egg).
>   - `PaginatedCollectionV2.initCollectionState:2256` — same fresh-file
>     pattern.
>   - `PaginatedCollectionV2.open:391` — FSM-rebuild recovery scan;
>     logical bookkeeping was lost, physical-by-design.
>   - `IndexHistogramManager.readSnapshotFromPage:1833` — defensive
>     physical-presence probe for the optional HLL page (guards against
>     partial crash between page-0 and page-1 writes in the same atomic
>     op). Phase A of Track 4 picks between "stay on `getFilledUpTo`
>     with comment + Track 5 gated helper" and "migrate to
>     `WriteCache.loadIfPresent(fileId, 1)` + null check" — both
>     preserve the defensive intent.
> - Add a rationale-bearing inline comment at
>   `CollectionDirtyPageBitSet.{clear:141, nextSetBit:168}` — pure-sizing
>   bounds checks with no logical surface available (CDPB has no
>   EntryPoint per PSI). Stay on `getFilledUpTo` via Track 5's gated
>   helper; per-component lock + Track 1 totality is what upholds I1
>   for these reads.
> - Collapse the do/while reconciliation loops:
>   - `AtomicOperationBinaryTracking.commitChanges` — single
>     `loadOrAddForWrite` per allocated pageIndex.
>   - `AbstractStorage.restoreAtomicUnit` — `UpdatePageRecord` and
>     `PageOperation` branches each collapse to a single
>     `loadOrAddForWrite` call (gap-fill handled by `loadOrAdd`).
>   - `DiskStorage.restoreFromIncrementalBackup` — same shape.
> - Drop `AtomicOperationBinaryTracking.internalFilledUpTo` and the
>   pageIndex-prediction logic. `pageChangesMap` keys on the actual
>   pageIndex returned by `loadOrAddForWrite`.
> - After all migration steps land, delete
>   `LockFreeReadCache.allocateNewPage`, `WOWCache.allocateNewPage`,
>   `DirectMemoryOnlyDiskCache.allocateNewPage`, and
>   `WriteCache.allocateNewPage` from the interface — these are
>   reachable only through callers Track 4 just removed.
> - Add a Java `assert false` (with a descriptive message) to the
>   defensive totality fallback in `WOWCache.loadOrAddLoadBranch`
>   where `loadFileContent` returns null. Per the method's existing
>   Javadoc the path is dead code today (dispatch prelude routes any
>   pageIndex past `currentSize` to extend or gap-fill), so the
>   assertion surfaces a regression in test runs (`-ea`) without
>   runtime cost in production. Surfaced by Track 2 Phase C
>   track-level code review (test-crash-safety dimension).
>
> **How**:
> - Step ordering (provisional):
>   1. `AtomicOperationBinaryTracking.commitChanges` collapse +
>      `internalFilledUpTo` removal. Includes all `pageChangesMap`
>      keying changes.
>   2. `AbstractStorage.restoreAtomicUnit` collapse —
>      `UpdatePageRecord` branch.
>   3. `AbstractStorage.restoreAtomicUnit` collapse —
>      `PageOperation` branch + `DiskStorage.restoreFromIncrementalBackup`.
>   4. BTree + SharedLinkBagBTree probe deletion + addPage migration
>      (~5 sites + 4 fresh-file) plus the
>      `BTree.doAssertFreePages:1389` pure-sizing migration in the
>      same component (sharing the EntryPoint-load pattern with the
>      neighbouring probe sites).
>   5. CollectionPositionMapV2 + PaginatedCollectionV2 + remaining
>      components (FreeSpaceMap, IndexHistogramManager,
>      CollectionDirtyPageBitSet) — probe deletion + addPage migration
>      (~10 sites including init) plus the 2 growth-loop collapses
>      (`FreeSpaceMap.updatePageFreeSpace:227`,
>      `CollectionDirtyPageBitSet.ensureCapacity:194`).
>   6. Inline rationale comments at the 4 stay-on-physical sites
>      (`CollectionPositionMapV2.create:136`,
>      `PaginatedCollectionV2.{open:391, initCollectionState:2256}`,
>      `IndexHistogramManager.readSnapshotFromPage:1833`) and the 2
>      EP-less CDPB pure-sizing reads (`clear:141`, `nextSetBit:168`).
>      These callers stay on `getFilledUpTo` and become consumers of
>      Track 5's gated helper. Phase A picks between "comment-only" and
>      "comment + `loadIfPresent` migration" for the IHM defensive
>      probe.
>   7. Delete `addPage` from `StorageComponent` and `AtomicOperation`.
>      Delete `allocateNewPage` from `WriteCache`, `LockFreeReadCache`,
>      and both concrete cache implementations.
> - Each per-component step replaces the probe block with:
>   ```java
>   var newPageIndex = entryPoint.getPagesSize() + 1;
>   var entry = atomicOperation.loadOrAddPageForWrite(fileId, newPageIndex);
>   entryPoint.setPagesSize(newPageIndex);
>   ```
>   The `entryPoint.setPagesSize` is the WAL-tracked logical bump
>   (existing `SetPagesSizeOp`).
> - For fresh-file sites (`create()` / `init()`), the target pageIndex
>   is known statically (0 or 1); migration is a direct replacement of
>   `addPage(...)` with `loadOrAddPageForWrite(fileId, 0)` (or 1).
> - **Replay-loop collapse**: today's loop runs
>   `readCache.allocateNewPage` repeatedly until the returned pageIndex
>   matches the WAL record's pageIndex. With `loadOrAdd` doing
>   gap-fill internally, the loop becomes a single
>   `loadOrAddForWrite(fileId, recordedPageIndex)` call — the cache
>   gap-fills if needed, returns the requested entry directly.
>
> **Constraints**:
> - **In-scope files**:
>   - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java`
>   - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperation.java` (interface)
>   - `core/.../storage/impl/local/AbstractStorage.java`
>     (`restoreAtomicUnit` and helpers — note: at the `local/` package
>     level, not under `local/paginated/`)
>   - `core/.../storage/disk/DiskStorage.java`
>     (`restoreFromIncrementalBackup` only)
>   - `core/.../storage/impl/local/paginated/base/StorageComponent.java`
>     (under `paginated/base/`, not directly under `paginated/`)
>   - `core/.../storage/cache/chm/LockFreeReadCache.java`,
>     `core/.../storage/cache/local/WOWCache.java`,
>     `core/.../storage/memory/DirectMemoryOnlyDiskCache.java` (final
>     `allocateNewPage` deletions; bodies were already redirected in
>     Track 1)
>   - `core/.../storage/cache/WriteCache.java` (interface
>     `allocateNewPage` deletion)
>   - All concrete component classes named in the call-site table
>     (BTree singlevalue v3, SharedLinkBagBTree, CollectionPositionMapV2,
>     PaginatedCollectionV2, FreeSpaceMap, IndexHistogramManager,
>     CollectionDirtyPageBitSet).
> - **Out of scope**: cache classes (Track 1 already shipped them);
>   `getFilledUpTo` access modifier and gated helpers (Track 5); WAL
>   classes; DoubleWriteLog.
> - WAL format unchanged — page extension stays implicit. The
>   `SetPagesSizeOp` / `SetFileSizeOp` records continue to carry the
>   logical-size bump; no `AddPage*` record is introduced.
> - **Crash safety**: each replay-loop collapse must preserve today's
>   semantics for the three scenarios documented in design.md
>   §"Crash safety" (TX in-flight, TX committed task-not-run, TX
>   committed task-ran-fully). The "task partially ran" case is
>   foreclosed by the FIFO + monotonic-submission executor model
>   (`wowCacheFlushExecutor` is single-threaded; per-component lock
>   serializes allocators); the orthogonal torn-write / writeback
>   durability gap is tracked separately as
>   `ISSUE-ensurevalidpagetask-torn-write.md`.
> - The deletion of `allocateNewPage` requires that no test code calls
>   it directly (Phase A audits this; refactor tests if needed).
>
> **Interactions**:
> - Depends on Track 1 (the cache primitive must exist before
>   migration starts).
> - Independent of Track 2 (parallel — Track 2 tests catch regressions
>   in real time).
> - Enables Track 5 (final `getFilledUpTo` lockdown — Track 5's helper
>   set is shaped by Track 4's per-site rationale comments) and Track 6
>   (regression test must run against the post-migration code path).
> - Verifies invariants **I1** + **I4** at the source-code level for
>   the EP-equipped components after the BTree pure-sizing migration
>   and the probe collapses land; the EP-less stay-on-physical sites
>   are pinned by D4's gated-helper surface (Track 5).

---

## Track 5: Tighten `getFilledUpTo` access via gated helpers

> **What**:
> - After Track 4 lands, downgrade `WriteCache.getFilledUpTo` from
>   `public` to package-private (or otherwise non-public).
> - Surviving external consumer set (≥ 5, per D4 revision):
>   - `DiskStorage.backupPagesWithChanges` — storage-quiesced backup
>     iteration (method @ :1387, `getFilledUpTo` call @ :1404).
>   - `CollectionPositionMapV2.create:136` — bootstrap-time emptiness
>     check; EntryPoint lives on the page being checked.
>   - `PaginatedCollectionV2.initCollectionState:2256` — same
>     bootstrap pattern.
>   - `PaginatedCollectionV2.open:391` — FSM-rebuild recovery scan;
>     logical bookkeeping was lost.
>   - `IndexHistogramManager.readSnapshotFromPage:1833` — defensive
>     physical-presence probe for the optional HLL page 1. If Track 4
>     Phase A switched this to `WriteCache.loadIfPresent(fileId, 1)`,
>     skip this entry; otherwise route through the gated helper.
>   - `CollectionDirtyPageBitSet.{clear:141, nextSetBit:168}` —
>     EP-less pure-sizing reads under per-component lock.
> - Phase A picks the helper shape — Phase 1 candidates:
>   - **One helper + intent enum**: `WriteCache.physicalSize(long
>     fileId, PhysicalReadIntent intent)` where the enum lists the
>     surviving intents (`BACKUP_QUIESCED`,
>     `BOOTSTRAP_EMPTINESS_CHECK`, `RECOVERY_REBUILD`,
>     `DEFENSIVE_PRESENCE`, `EP_LESS_PURE_SIZING`). Lowest API surface;
>     enum constant carries the audit-grep signature.
>   - **2-3 named helpers**: e.g.,
>     `forEachPageDuringQuiesce(fileId, visitor)`,
>     `physicalSizeUnderComponentLock(fileId)`,
>     `physicalSizeForBootstrap(fileId)`. More explicit names; smaller
>     enum-coupling.
>   Phase A picks one and adapts the consumer migrations accordingly.
> - Add javadoc to `WriteCache` and `StorageComponent` documenting the
>   discovery contract: cross-TX readers route through the logical
>   surface when one exists; physical-size reads route through the
>   gated helper(s) above, each with a contract-stating javadoc on
>   the helper or enum constant.
>
> **How**:
> - Step 1 (Phase A decision): pick the helper shape (one-helper-with-
>   enum vs 2-3 named helpers). Confirm with the user if the decision
>   is non-obvious from the surviving consumer set.
> - Step 2: introduce the helper(s) on `WriteCache` (or
>   `StorageComponent`, depending on which class exposes the
>   audit-grep target). Implementation re-routes to the existing
>   in-memory size read (`AsyncFile.size` / `AsyncFile.allocateSpace`
>   bookkeeping).
> - Step 3: migrate each surviving consumer to the new helper(s).
>   Verify backup tests, FSM-rebuild recovery tests, and the
>   IndexHistogramManager defensive path still behave correctly.
> - Step 4: change `WriteCache.getFilledUpTo` access to package-private.
>   Build + tests green.
> - **Verification**: PSI find-usages on `WriteCache.getFilledUpTo`
>   should show no production callers outside the cache package after
>   this track. Test mocks that live in the same package context are
>   acceptable. PSI find-usages on each new helper should match the
>   D4-revision surviving consumer set exactly — extra or missing
>   callers are findings.
>
> **Constraints**:
> - **In-scope files**:
>   - `core/.../internal/core/storage/cache/WriteCache.java`
>   - `core/.../internal/core/storage/cache/local/WOWCache.java`
>   - `core/.../internal/core/storage/memory/DirectMemoryOnlyDiskCache.java`
>   - `core/.../internal/core/storage/disk/DiskStorage.java` (the
>     backup consumer; method @ :1387, call @ :1404)
>   - `core/.../storage/impl/local/paginated/base/StorageComponent.java`
>     (if the audit-grep helper surface lives there)
>   - The surviving consumer call sites (CollectionPositionMapV2,
>     PaginatedCollectionV2, IndexHistogramManager,
>     CollectionDirtyPageBitSet).
>   - Any cache test class still using the old public method.
> - **Out of scope**: functional behavior changes; new WAL records;
>   widening the surviving-consumer set beyond what D4's revision
>   names.
>
> **Interactions**:
> - Depends on Track 4 (write-side API collapse done — replay loops
>   no longer call `getFilledUpTo` via `internalFilledUpTo`, and the
>   rationale-comment locations are pinned by Track 4's per-site
>   commits).
> - Enables nothing downstream (this is the final cache-API hygiene
>   pass).
> - Verifies invariant **I1** is enforceable at compile time —
>   external code cannot regress to a non-gated
>   `getFilledUpTo`-based discovery channel.

---

## Track 6: Integration regression test

> **What**:
> - End-to-end concurrent-insert workload that reproduces the original
>   poison cascade on a fresh disk-mode storage with
>   `checksumMode=StoreAndThrow`.
> - Test scaffolding:
>   1. Open a fresh `YouTrackDB` instance with `EngineLocalPaginated`
>      and `checksumMode=StoreAndThrow`.
>   2. Create a class with an indexed string property (canonical
>      trigger uses `CollectionPositionMapV2`-backed cluster, where
>      pageIndex 1 is the first bucket page).
>   3. Run N parallel transactions (≥ 16; threads = available
>      processors × 2 to maximize contention) inserting into the
>      class via `executeInTx` / `autoExecuteInTx`.
>   4. Assert no `IllegalStateException("Page X:Y was allocated in
>      other thread")`, no `StorageException("Page Y is broken in
>      file …")`, no "Internal error happened in storage" cascade.
>   5. Reopen the storage and assert all committed records are
>      readable.
> - The test must **fail on develop** (against pre-fix code) and
>   **pass on the new code**. Commit message includes the verification
>   protocol.
>
> **How**:
> - Step ordering (provisional):
>   1. Write the test scaffolding. Verify the "fail on develop"
>      direction by running the test against the unmodified develop
>      branch (or by temporarily reverting Track 1 / Track 4 changes
>      in a scratch worktree).
>   2. Verify the "pass on new code" direction. Confirm reopen-and-read
>      semantics. Add to the integration suite (`ci-integration-tests`
>      profile).
> - Workload tuning: the canonical trigger from the handoff is "concurrent
>   inserts on a freshly-built class backed by CollectionPositionMapV2,
>   where multiple TXs race for `pageIndex == 1`". The threshold for
>   reliable reproduction on develop is empirical; aim for ≥ 90%
>   reproduction rate across 10 consecutive runs on a clean checkout
>   before declaring the test load-bearing.
> - The test extends an existing JUnit 4 base class in `core` (matching
>   the existing concurrency tests like
>   `FreezeAndDBRecordInsertAtomicityTest`); it runs under the standard
>   `./mvnw -pl core clean test` invocation and the
>   `ci-integration-tests` profile.
>
> **Constraints**:
> - **In-scope files**: a new test class under
>   `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/...`
>   (exact location confirmed in Phase A based on existing concurrency
>   tests' placement).
> - **Out of scope**: SQL-level tests; cluster-level tests; tests
>   targeting other engines.
> - The test must use `ConcurrentTestHelper` patterns (or equivalent)
>   for deterministic thread coordination.
> - `checksumMode=StoreAndThrow` is mandatory — `Off` masks the
>   magic-check leg of the bug.
>
> **Interactions**:
> - Depends on Track 1 (the cache primitive must be in place).
> - Depends on Track 4 (the discovery-surface change must be in
>   place — verifying just Track 1's cache-level fix doesn't prove the
>   end-to-end race is gone, because the race vector lives in the
>   discovery surface, not just the cache install. Track 4 absorbed
>   the read-side migration that was originally Track 3.).
> - Independent of Track 5 (which is API hygiene only).
> - Verifies invariants **I1** and **I4** end-to-end and confirms the
>   bug-as-reported (the symptom that motivated this work) is resolved.
