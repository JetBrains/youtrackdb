# Track 6: Integration regression test

## Description

End-to-end concurrent-insert workload that reproduces the original poison
cascade: open a fresh disk-mode storage with `checksumMode=StoreAndThrow`,
create a class with an indexed string property, run N parallel transactions
inserting into the class via `executeInTx` / `autoExecuteInTx`. Assert no
`IllegalStateException`, no `StorageException("Page Y is broken")`, no
"Internal error happened in storage" cascade, and that all committed records
are readable on reopen.

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

## Phase C deferrals absorbed (from Track 4 review fan-out)

The Track 4 Phase C track-level code review surfaced several deferred
findings that fit naturally within this track's end-to-end /
integration-MT charter. They are absorbed here as additional scenarios
beyond the original poison-cascade test:

- **CS1 — partial-flush-orphan recovery hazard**. The structural fix
  collapses the per-component reuse-or-extend probes at BTree, SLBB,
  CPMV2, PCV2 and the AOBT-level wrapper enforces an allocator-only
  contract on the disk engine. A torn-write between a prior TX's
  WAL flush and its `AsyncFile.allocateSpace` extension (or a
  `EnsurePageIsValidInFileTask` that stamps the file while the WAL
  is still buffered) can leave a physical page beyond
  `entryPoint.pagesSize`. The next allocator on the same component
  sees `pageIndex = pagesSize + 1 < writeCache.getFilledUpTo(fileId)`
  and trips the AOBT `IllegalStateException("allocation-only;
  pageIndex N is below allocationFloor M")`. Track 4's structural fix
  trades silent reuse for loud failure. Track 6 must intentionally
  exercise this path so we either confirm the bounded-leak acceptance
  with evidence or gather data motivating a future
  `reuseOrphanPageForWrite` SPI (or recovery-time `fileTruncate` to
  `entryPoint.pagesSize * pageSize` at storage open).
- **HLL-spill crash-then-second-spill recovery**. The IHM page-1
  discriminator introduced in Track 4 Step 2 routes the first spill
  through `loadOrAddPageForWrite` and subsequent spills through
  `loadPageForWrite` based on `op.filledUpTo(fileId) > 1`. A crash
  between the first spill's WAL flush and the next session must
  replay the page-1 allocation, then a follow-up mutation must hit
  the existing-page branch without tripping the allocator-only
  contract. No current test exercises both branches end-to-end.
- **`StorageBackupMTStateTest` `@Ignore` resurrection**. The
  collapsed `DiskStorage.restoreFromIncrementalBackup` loop is
  covered at the unit level by the mocked `testUpdatePageRecordGap
  FillReplaySingleLoadCall` and end-to-end (single-thread) by
  `StorageBackupTest`. The MT path through `IncrementalBackupThread`
  is unreachable in CI today because of the `@Ignore`. Resurrection
  closes the only MT-recovery gap in the cumulative diff.
- **I4 per-component MT pins**. Today I4 ("per-component locks
  serialise concurrent allocators that share a `fileId`") rests
  entirely on code inspection — the Phase A audit verified each
  production allocator enters under `executeInsideComponentOperation`
  / `calculateInsideComponentOperation`. A per-component MT test
  (two TXes concurrently calling `op.loadOrAddPageForWrite(fileId,
  sameKnownIndex)` on the same component instance) pins the audit's
  conclusion: passes today; fails loudly if a future change drops
  the lock. Five sites: `IHM.flushSnapshotToPage` vs
  `IHM.writeSnapshotToPage`; `BTree.create`; `SLBB.splitRootBucket`
  (two-page recipe is the original bug's trigger); `CPMV2.allocate`;
  `PCV2.allocateNewPage`. Plus: strengthen
  `LoadOrAddPageForWriteTest.inMemoryEagerInstallToleratesConcurrent
  OrphanReuse` contention window (the existing single-release
  `CountDownLatch(1)` start gate often lets one thread complete
  before the other reaches the cache primitive — a `CyclicBarrier`
  inside the operation, or a repeated-rounds shape, would tighten
  the contention window).

Phase A of Track 6 picks the step decomposition. Suggested ordering:
the original poison-cascade test first, then the I4 per-component
MT pin step (the most load-bearing pre-merge gate), then CS1,
StorageBackupMTStateTest, HLL-spill recovery as separate steps.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
