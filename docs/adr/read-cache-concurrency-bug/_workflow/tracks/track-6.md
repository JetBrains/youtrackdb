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
> - Depends on Track 7 (the recovery-time orphan-truncation pass for
>   EP-equipped components — Track 6's CS1 scenario asserts the
>   post-replay invariant Track 7 establishes; verifying CS1 against
>   pre-Track-7 code would produce the bare availability impact
>   instead of the recovered-and-writable state the structural fix
>   targets).
> - Independent of Track 5 (which is API hygiene only).
> - Verifies invariants **I1**, **I4**, and **I6** end-to-end and
>   confirms the bug-as-reported (the symptom that motivated this
>   work) is resolved.

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
  `entryPoint.pagesSize`. Without the Track 7 recovery-time truncate
  pass, the next allocator on the same component would see
  `pageIndex = pagesSize + 1 < writeCache.getFilledUpTo(fileId)` and
  trip `IllegalStateException("allocation-only; pageIndex N is below
  allocationFloor M")`. **With Track 7 in place**, the storage-open
  pass restores `physical == logical` post-replay, so Track 6's CS1
  step drives the partial-flush path on each of the four EP-equipped
  components, restarts the storage, asserts post-replay physical =
  logical (invariant I6), and asserts the next TX completes without
  `IllegalStateException`. Pin both the truncate-needed and
  no-op-clean-shutdown branches so a future change that breaks the
  recovery pass fails loudly.
- **HLL-spill crash-then-second-spill recovery**. The IHM page-1
  discriminator introduced in Track 4 Step 2 routes the first spill
  through `allocatePageForWrite` and subsequent spills through
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
  *(See Phase A review refinement 1 below: the actual fix keeps
  `@Ignore` in place — the test body is a 90-minute stress-soak,
  incompatible with CI runtime budgets — and introduces a NEW
  short-duration MT test class instead.)*
- **I4 per-component MT pins**. Today I4 ("per-component locks
  serialise concurrent allocators that share a `fileId`") rests
  entirely on code inspection — the Phase A audit verified each
  production allocator enters under `executeInsideComponentOperation`
  / `calculateInsideComponentOperation`. A per-component MT test
  (two TXes concurrently calling `op.allocatePageForWrite(fileId,
  sameKnownIndex)` on the same component instance) pins the audit's
  conclusion: passes today; fails loudly if a future change drops
  the lock. Five sites: `IHM.flushSnapshotToPage` vs
  `IHM.writeSnapshotToPage`; `BTree.create`; `SLBB.splitRootBucket`
  (two-page recipe is the original bug's trigger); `CPMV2.allocate`;
  `PCV2.allocateNewPage`. Plus: strengthen
  `AllocatePageForWriteTest.inMemoryEagerInstallToleratesConcurrent
  OrphanReuse` contention window (the existing single-release
  `CountDownLatch(1)` start gate often lets one thread complete
  before the other reaches the cache primitive — a `CyclicBarrier`
  inside the operation, or a repeated-rounds shape, would tighten
  the contention window).

## Phase C deferrals absorbed (from Track 7 review fan-out)

Track 7's Phase C review surfaced three IT-coverage gaps that fold
naturally into this track's existing CS1 and `StorageBackupMTStateTest`
scopes. Plan-file commit `a6d3fe770c` records the absorption; this
section mirrors it into the step file.

- **`ChecksumMode` coverage matrix on CS1**. Track 7's
  `TruncateOrphansAfterRecoveryIT` runs only under `ChecksumMode.Off`.
  CS1's partial-flush-orphan scenarios should exercise both
  `ChecksumMode.Off` and `ChecksumMode.StoreAndThrow` so the
  "recovery pass never reads orphan bodies" claim is pinned under the
  production CI default. The `WITHOUT_CHECKSUM` magic stamp is
  accepted under StoreAndThrow without CRC verification
  (`WOWCache.verifyMagicChecksumAndDecryptPage` at `:3577-3614`), so
  the existing fabrication helper transfers unchanged — the value of
  the matrix is to fail loud if a future change introduces an eager
  orphan-page read pre-shrink.
- **Multi-value engine `.nbt` null-tree orphan IT**. Track 7's unit
  tests pin the `BTreeMultiValueIndexEngine` wrapper's svTree +
  nullTree dispatch via mocks, but no IT exercises a real `.nbt` file
  with an orphan tail (NOTUNIQUE index accumulating null-keyed
  entries that grow the null-bucket past one page). Add an IT
  scenario alongside the CS1 expansion.
- **Executable `postProcessIncrementalRestore` wiring test**. Track 7
  ships a source-text wiring sentinel
  (`DiskStorageRestoreOrchestratorWiringTest`) pinning the
  `executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)`
  call shape after `flushAllData()`. Track 6's short-duration
  incremental-backup IT (see Phase A refinement below) is the natural
  home for an executable IR test that drives an orphan-bearing source
  through `DiskStorage.incrementalRestore` and asserts
  `physical == logical` on the destination — retiring the source-text
  sentinel once executable coverage lands.

## Phase A review refinements (iteration 1 consensus)

Three Phase A reviews (technical, risk, adversarial) at iteration 1
produced 1 blocker + 14 should-fix findings with substantial overlap
across reviewers. The findings inform the step decomposition below and
refine the deferral framings above. Consensus refinements:

1. **`StorageBackupMTStateTest` is not "resurrected"** (blocker A4,
   should-fix T4 + R2). The `@Ignore` was added in 2016 during the
   OrientDB JUnit-4 migration; the test body sleeps **90 minutes** in
   30-second increments (`StorageBackupMTStateTest.java:128-134`).
   Removing `@Ignore` as-is would break CI runtime budgets. Reframe:
   keep the original `@Ignore` in place (it's a stress-soak test, not
   a unit/IT test); add a NEW short-duration MT incremental-backup
   IT (≤ 120 s wall, deterministic orchestration) that drives the
   collapsed `restoreFromIncrementalBackup` loop and the executable
   IR-wiring assertion.
2. **CS1 is not "drive on all four components"** (should-fix T1 + R5 +
   A2). `TruncateOrphansAfterRecoveryIT` already covers `.pcl`, `.cpm`,
   `.cbt` under `ChecksumMode.Off` with deterministic fabrication via
   `RandomAccessFile` + `MAGIC_NUMBER_WITHOUT_CHECKSUM`. The actual
   gaps are (a) `ChecksumMode.StoreAndThrow` parameterization across
   existing scenarios; (b) `.nbt` (MV-engine null-tree) new file
   shape; (c) `.bbt` (SLBB) new file shape; (d) zero-byte orphan
   helper (extends file length without writing magic bytes) for the
   production-equivalent shape of "crash between
   `AsyncFile.allocateSpace` and `EnsurePageIsValidInFileTask`". The
   step decomposition reflects extension+parameterization, not four
   parallel re-implementations.
3. **I4 MT test shape is split** (should-fix T3 + R3 + A3). The
   production-shape allocator races (CPMV2.allocate, PCV2.allocateNewPage,
   IHM `flushSnapshotToPage` vs `writeSnapshotToPage`) ARE reachable
   from concurrent session-level TXes — these get a repeated-rounds
   MT step. `BTree.create` runs once per index lifecycle and is not
   reachable from concurrent TXes through normal session code; drop
   it. `SLBB.splitRootBucket` is structurally similar — the two-page
   recipe runs inside `executeInsideComponentOperation`, so a
   same-instance two-TX test would either see thread B's
   `sameKnownIndex` go stale (clean IllegalStateException) or fail
   ambiguously without the lock. Encode I4 for `BTree` and `SLBB` as
   Javadoc `@apiNote` contract on `WriteCache.loadOrAdd` /
   `AtomicOperation.allocatePageForWrite` plus an audit reference,
   not as an MT test.
4. **Original poison-cascade adds a deterministic white-box
   reproducer** (should-fix R1 + A1). The high-level
   concurrent-insert workload reproduces the bug at a low rate
   (microseconds-wide race window between `AsyncFile.allocateSpace`
   and the in-memory `putIfAbsent`); keep it as a smoke test. Pair it
   with a deterministic white-box reproducer that races two threads
   against `WOWCache.loadOrAdd` under a `CyclicBarrier` synchronizing
   them at the `allocateSpace` / `putIfAbsent` boundary. Add a
   positive-evidence assertion (e.g., a debug counter on the
   `WriteCache` shows `> N` allocator calls actually occurred) so the
   test fails loud if a future change weakens it.
5. **Verification protocol uses cherry-pick + capped threads** (T2 +
   R4 + A7). Verify "fail on develop" by cherry-picking the new test
   commit(s) onto `origin/develop` (commit SHA recorded), running ≥
   10 consecutive times, requiring ≥ 90% reproduction rate. Cap the
   thread count via `Math.min(availableProcessors() * 2, 16)` so dev
   workstations and CI runners produce comparable timing profiles.
   Calibration evidence (numbers from the actual reverted-baseline
   run) goes verbatim into the step's commit message. Steps run in
   isolation via `-Dtest=…` to avoid coupling with the pre-existing
   `LocalPaginatedCollectionV2TestIT.testAddManyRecords` flake
   recorded in Track 5's retrospective.
6. **HLL-spill is split** (should-fix A5). Crash-then-second-spill
   end-to-end is a multi-step orchestration (HLL threshold control,
   WAL flush coordination, crash simulation, reopen, second-spill
   drive). Split into (a) a unit-level discriminator test that mocks
   `op.filledUpTo()` and pins the `> 1 ? load : allocate` branch
   logic; (b) a fabrication-style IT mirroring
   `TruncateOrphansAfterRecoveryIT`'s pattern that pre-fabricates the
   page-1 state on `.ihm`, reopens, drives the second spill, and
   asserts no `IllegalStateException`.
7. **`inMemoryEagerInstallToleratesConcurrentOrphanReuse`
   strengthening uses repeated-rounds, not in-production
   `CyclicBarrier`** (suggestion T5 + R6). A `CyclicBarrier` inside
   the production allocator path is not feasible without test-only
   hooks; a repeated-rounds shape (run the contention ~100-1000
   times in a loop, fresh `fileId` per round, fail on first round
   that produces `IllegalStateException`) is the canonical fix.
   Combined with the I4 production hot-path MT step.

The remaining suggestion-class findings (T6 on `ConcurrentTestHelper`
wording, T7 on `.nbt` NOTUNIQUE recipe, T8 on extracting a per-mode
helper rather than `@Parameterized`, T9 on verifying backup-carries-
orphans, R7 on optional LinkBag-driven sibling, R8 on
fast-feedback test profile placement, R9 on per-component
decomposition) are folded into the individual step descriptions
below without standalone refinement notes.

## Progress
- [x] Review + decomposition
- [x] Step implementation (7/7 complete)
- [ ] Track-level code review (1/3 iterations + gate-check complete; iter-2 pending — TX1 STILL OPEN on softened HLL-spill assertion)

## Base commit
`76d2939f0e88ee41c964d53abab70a3d0470a8e0`

## Reviews completed
- [x] Technical: PASS at iteration 1 (10 findings: 4 should-fix accepted + 6 suggestions folded into step descriptions)
- [x] Risk: PASS at iteration 1 (9 findings: 5 should-fix accepted + 3 suggestions accepted + 1 suggestion deferred with downstream check)
- [x] Adversarial: PASS at iteration 2 (iter-1 7 findings: 1 blocker + 5 should-fix accepted + 1 suggestion deferred; iter-2 gate verification surfaced A8 should-fix and A9 suggestion — both applied to Step 1 and the historical deferrals section respectively, see commit context for details)

## Steps

- [x] Step: Original poison-cascade smoke test scaffolding plus deterministic white-box reproducer
  - [x] Context: info
  > **Risk:** high — concurrency (introduces two MT test patterns: high-level concurrent-insert workload and synchronized two-thread `WOWCache.loadOrAdd` race)
  >
  > **What was done:** Added a new JUnit 4 test class `LoadOrAddPoisonCascadeRegressionTest` under `core/src/test/.../storage/impl/local/` with two `@Test` methods. The high-level smoke test opens a fresh disk-mode storage with `checksumMode=StoreAndThrow`, drives ≥ `THREADS` (capped to `min(cores * 2, 16)`) parallel sessions each doing 100 indexed-class inserts via `executeInTx`, asserts no poison-cascade exception fires, reopens the storage, and verifies all `THREADS * ITERATIONS` records remain queryable (count match plus unique-index lookup for one known key). The deterministic white-box reproducer runs 100 rounds of two threads on a `CyclicBarrier(2)` racing through `LockFreeReadCache.loadOrAddForWrite` on `(fileId, pageIndex=0)` with `verifyChecksums=true`, asserting both observe the same `CacheEntry` and `CachePointer` instances and that the file's allocator extended exactly once per round. To support positive-evidence assertions, instrumented `WOWCache.loadOrAddExtendBranch` and `WOWCache.loadOrAddGapFillBranch` with `LongAdder` counters and exposed two read-only accessors. Phase C step-level dim-review fan-out ran 9 reviewers at iter-1; the implementer applied 9 consolidated findings (rename to `*Test` suffix so surefire defaults pick it up, split combined positive-evidence floor into independent extend / gap-fill assertions, cause-chained exception aggregation with poison-cascade-vs-flake shape classification, both `CacheEntry` and `CachePointer` identity pin on white-box rounds, reopen sanity floor + unique-index probe + clean-shutdown gap-fill ceiling, helper extraction across both tests, deprecation hygiene on the `WOWCache.getFilledUpTo` call, plus imports / `{@link}` cleanups). Gate-check iter-1 PASSed on 6/7 dimensions; iter-2 fixed the misplaced `@SuppressWarnings("deprecation")` (JLS 9.6.4.5 lexical scope — moved onto the helper that actually carries the deprecated call). Iter-2 BC gate-check PASS.
  >
  > **What was discovered:** (1) The originally-planned `gap-fill == 0L` ceiling on the smoke test does not hold empirically — gap-fill fires once under heavy concurrent inserts across multiple files (cluster `.pcl`, position map `.cpm`, index `.cbt`, plus the IHM page-1 discriminator path) due to benign cross-component snapshot-window races between `LFRC.loadOrAddForWrite`'s `filledUpTo` read and `WOWCache.loadOrAdd`'s own size read. The assertion is tightened to a small upper bound (`<= max(THREADS/4, 4)`) that still catches a cascading regression while tolerating the observed shape. The `WOWCache.loadOrAddGapFillBranchInvocations` field Javadoc claim of "stays at 0 in healthy production workloads" is now too aspirational — worth weakening to "should not scale with the workload" in a future hygiene pass. (2) `CachePointer` exposes no public `referrersCount` accessor, so the Phase A plan's third invariant ("referrersCount increments correctly") is pinned indirectly via the `CacheEntry` and `CachePointer` identity checks plus the DirectMemory track-mode leak detector that runs in tear-down; documented in the white-box helper Javadoc. (3) Surefire defaults pick up `*Test.java` only — the originally-named `*IT.java` would have been silently skipped under `./mvnw -pl core clean test`. Rename to `LoadOrAddPoisonCascadeRegressionTest` aligns with the precedent set by `FreezeAndDBRecordInsertAtomicityTest`, `AbstractStorageDeadlockFixTest`, `EdgeSnapshotLifecycleTest` in the same package. (4) PSI `resolved.isDeprecated` returns false on an impl-class override whose SPI interface declares `@Deprecated` — a caveat for any future audit script walking deprecated-call coverage across the `WriteCache` / `WOWCache` split (check `findSuperMethods()` as well).
  >
  > **What changed from the plan:** None structural. The plan's positive-evidence framing already anticipated that exact counts would be "calibrated during implementation" — the M2 split (independent extend floor + gap-fill ceiling) replaces the originally-drafted combined floor with assertions that pin the right invariant shape.
  >
  > **Key files:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` (modified — 2 `LongAdder` fields + 2 increment statements at the end of the extend / gap-fill branches + 2 read-only `public` accessors marked test-only in Javadoc); `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/LoadOrAddPoisonCascadeRegressionTest.java` (new — 2 `@Test` methods plus 7 private helpers).
  >
  > **Critical context:** Cross-track signals for the remaining Track 6 steps: (a) Step 4 (IHM HLL-spill discriminator IT) is the natural home for positive-evidence on the `loadOrAddGapFillBranchInvocations` counter — this step uses it only as a regression ceiling, not as a structural pin. (b) Subsequent test classes in this package should prefer the `*Test.java` suffix unless they're genuinely long-duration ITs belonging in `ci-integration-tests` failsafe `<includes>`. (c) The IHM page-1 discriminator path from Track 4 Step 2 routes follow-up spills through `loadPageForWrite`, contributing to the cross-component snapshot-window observation above; Step 4's unit-level discriminator test is the load-bearing pin on the `op.filledUpTo > 1 ? load : allocate` branch logic.


  >
  > **What:** New JUnit 4 test class under `core/src/test/.../storage/impl/local/`
  > containing (a) a high-level concurrent-insert smoke test (≥ 16 TXs
  > inserting into an indexed class on a `CollectionPositionMapV2`-backed
  > cluster under `checksumMode=StoreAndThrow`, capped to `Math.min(
  > availableProcessors() * 2, 16)` threads, ≥ 100 iterations per thread,
  > asserts no `IllegalStateException("Page X:Y was allocated in other
  > thread")` / `StorageException("Page Y is broken")` / "Internal error
  > happened in storage"; reopens storage and verifies all committed
  > records readable); (b) a deterministic white-box MT test racing two
  > threads against `WOWCache.loadOrAdd(fileId, pageIndex,
  > verifyChecksums=true)` under a `CyclicBarrier(2)` synchronizing them
  > at the `AsyncFile.allocateSpace` / `putIfAbsent` boundary, ≥ 100
  > rounds, fresh fileId per round to avoid carry-over; asserts both
  > threads observe the same `CachePointer`, `referrersCount` increments
  > correctly, and the second caller does not see a partially-stamped
  > page.
  >
  > **Positive-evidence assertion:** instrument debug counters on
  > `WOWCache.loadOrAddExtendBranch` and `WOWCache.loadOrAddGapFillBranch`
  > (the actual file-extending branches that the deterministic reproducer's
  > `CyclicBarrier(2)` synchronizes against) that the smoke test reads
  > post-completion; assert the combined count is `>= insertCount` to
  > prove the allocator path was actually exercised. Without this, a
  > future regression that makes inserts hit a different path would
  > silently "pass" the absence-of-symptom smoke test. PSI-verified at
  > Phase A iteration 2: `WOWCache.allocateNewPage` does NOT exist —
  > the original Phase A draft cited it speculatively.
  >
  > **Verification protocol:** cherry-pick the test commit onto
  > `origin/develop` (commit SHA captured in the step's commit message);
  > run ≥ 10 consecutive times under
  > `./mvnw -pl core clean test -Dtest=<NewClass>`; require ≥ 90%
  > reproduction (per-test failure rate). Numbers go verbatim into the
  > commit message. Then verify pass on this branch with the same
  > command.
  >
  > **Key files:** new test class in `core/src/test/.../storage/impl/local/`
  > (final path picked during implementation); uses pattern from
  > `FreezeAndDBRecordInsertAtomicityTest` (canonical
  > `Executors.newFixedThreadPool` + `CountDownLatch` shape); references
  > `AllocatePageForWriteTest` for the two-thread race pattern;
  > `WOWCache.loadOrAdd` (already public method) for the white-box probe.

- [x] Step: `ChecksumMode.StoreAndThrow` matrix on existing CS1 scenarios plus zero-byte orphan helper
  - [x] Context: info
  > **Risk:** medium — test infrastructure (extends an existing IT with a new mode axis and a new fabrication shape)
  >
  > **What was done:** Extended `TruncateOrphansAfterRecoveryIT` with a 2-dimensional matrix over the four existing scenarios. Each scenario body extracted into a private `runXxxScenario` helper accepting `(ChecksumMode, OrphanFabricator)`; `makeConfigWithChecksumOff` replaced with `makeConfig(ChecksumMode)`. Added a zero-byte `OrphanFabricator` that extends the file via `RandomAccessFile.setLength` without writing magic bytes — the production-equivalent shape for "JVM crash after `AsyncFile.allocateSpace` but before `EnsurePageIsValidInFileTask` ran". Original 4 tests grew to 14 `@Test` methods: `.pcl`, `.cpm`, `.cbt` under `ChecksumMode.{Off, StoreAndThrow}` × `{magic-stamped, zero-byte}` fabrication (12 tests), plus clean-shutdown no-op under both modes (2 tests). All 14 pass under `-Dtest=TruncateOrphansAfterRecoveryIT` in 38 s; full `-P coverage` profile reports 89.3% line / 84.8% branch on changed production lines (no production changes, so the matrix value is regression detection).
  >
  > **What was discovered:** The Phase A iter-1 refinement framing ("recovery pass never reads orphan bodies pre-shrink") is empirically validated by the `StoreAndThrow` axis — under `StoreAndThrow`, an eager orphan-page load would surface as a `StorageException` from the CRC mismatch on a zero-byte page, but the suite passes, confirming `AbstractStorage.truncateOrphansAfterRecovery` + `StorageComponent.verifyAndTruncateOrphans` read only the EP page and dispatch a shrink that does not touch the tail. The `MAGIC_NUMBER_WITHOUT_CHECKSUM` stamp is accepted by `WOWCache.verifyMagicChecksumAndDecryptPage` without a CRC comparison (`WOWCache.java:3643-3650`), so the matrix value is regression detection. Note: `pcv2.verifyAndTruncateOrphans` dispatch covers both `.pcl` and `.cpm` via the siblings hook — the `.cpm` test pins the same orchestrator code path the `.pcl` test exercises, they are not independent dispatches.
  >
  > **What changed from the plan:** None. Implemented exactly the sibling-`@Test`-per-mode pattern the step prescribed, with the zero-byte fabrication helper as a second `OrphanFabricator` factory. Test names use a 2D coordinate shape `…Magic{Stamped|Zero}Byte…Under{ChecksumOff|StoreAndThrow}`.
  >
  > **Key files:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/TruncateOrphansAfterRecoveryIT.java` (modified, no production-code changes).
  >
  > **Critical context:** The private `OrphanFabricator` functional interface plus its two factory methods (`magicStampedOrphanFabricator`, `zeroByteOrphanFabricator`) are the intended hook for **Step 3** (SLBB `.bbt` and MV-engine `.nbt` scenarios) and **Step 7** (incremental-backup IT — the plan explicitly cites "the IT helper from the second step above" for the zero-byte fabricator). If a third IT outside this class reaches for the same shape, promote to a small package-private utility.


  >
  > **What:** Extend `TruncateOrphansAfterRecoveryIT` with a per-method
  > helper `runScenario(ChecksumMode mode, ...)` and add a sibling
  > `@Test public void …UnderStoreAndThrow()` alongside each existing
  > `…UnderChecksumOff()` method (no `@RunWith(Parameterized.class)` —
  > would clash with `@Category(SequentialTest.class)`). Add a second
  > fabrication helper `fabricateZeroByteOrphanPages(file, count)`
  > that extends the file via `RandomAccessFile.setLength(size + count *
  > pageSize)` without writing magic bytes — the production-equivalent
  > shape for "JVM crash after `AsyncFile.allocateSpace` but before
  > `EnsurePageIsValidInFileTask` ran". Run each existing CS1 scenario
  > (`.pcl`, `.cpm`, `.cbt`, clean-shutdown no-op) under both
  > ChecksumMode values and both fabrication shapes.
  >
  > **Key files:** `core/src/test/.../impl/local/TruncateOrphansAfterRecoveryIT.java`
  > (extend); no production-code changes.

- [x] Step: SLBB `.bbt` and multi-value engine `.nbt` orphan-truncation IT scenarios
  - [x] Context: warning
  > **Risk:** medium — test infrastructure (adds two new file shapes to the existing IT pattern)
  >
  > **What was done:** Extended `TruncateOrphansAfterRecoveryIT` with two new scenario groups covering the last two recovery-pass dispatch surfaces previously uncovered at the IT level: `SharedLinkBagBTree` (Group 3 of `AbstractStorage.truncateOrphansAfterRecovery` — the `LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans` iteration delegate over the private `fileIdBTreeMap`) and the nullTree leg of `BTreeMultiValueIndexEngine.verifyAndTruncateOrphans` (svTree leg already pinned by the `.cbt` scenarios). Each shape gets 4 `@Test` methods covering the full `ChecksumMode × fabrication-shape` matrix (Off / StoreAndThrow × magic-stamped / zero-byte) using the existing `OrphanFabricator` interface and its two factory helpers from the prior step. The class-level Javadoc was updated from "four file shapes" to "six file shapes" with the new entries documenting which dispatch surfaces each scenario pins. Test count grew from 14 to 22; all pass under `./mvnw -pl core test -Dtest=TruncateOrphansAfterRecoveryIT` in ~61 s.
  >
  > **What was discovered:** (1) The step description's "SLBB `.bbt`" and "MV-engine `.nbt`" file extensions are colloquial. The actual SLBB on-disk extension is `.grb` (`LinkCollectionsBTreeManagerShared.FILE_EXTENSION`) with prefix `global_collection_<clusterId>`. The actual MV-engine null sub-tree data file is `<index>$null.cbt` — the `BTreeMultiValueIndexEngine.NULL_BUCKET_FILE_EXTENSION` constant `.nbt` refers to a single-page null-bucket sibling that never grows past one page in BTree's design, so it is not a viable orphan-truncation target. Test names and Javadoc were aligned to the production component identities (SharedLinkBagBTree, multi-value null sub-tree) instead of the incorrect extensions; this preserves grep-ability against the real production surfaces. (2) Every paginated collection (every cluster created via `createClass`) implicitly creates one SLBB file at `addCollection()` time, so the SLBB scenario does not need to insert any edges — the pristine EP-and-root two-page footprint is sufficient. (3) An SLBB freshly created via `SharedLinkBagBTree.create()` has `pagesSize=1` stored in its EntryPoint (the "1" is the highest occupied data-page index, not the data-page count). The recovery pass's `targetBytes` formula `max(pageSize, (logicalPages + 1) * pageSize)` therefore yields 2 pages, matching the EP-and-root footprint. This contract is documented at `SLBB.java:124-126` and is load-bearing for the SLBB scenario. (4) Inserting 800 null-keyed entities reliably forces the `<index>$null.cbt` file past its EP-and-root two-page footprint.
  >
  > **What changed from the plan:** None structural. The step's "SLBB `.bbt`" / "MV-engine `.nbt`" naming was treated as colloquial; actual extensions verified at implementation time and reflected in test names. The step also did not specify the ChecksumMode × fabrication-shape matrix; followed the pattern set by Step 2 (4 tests per shape) for consistency — 8 new tests total.
  >
  > **Key files:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/TruncateOrphansAfterRecoveryIT.java` (modified, no production-code changes).
  >
  > **Critical context:** Cross-step signals for the remaining Track 6 steps and Phase C reviewers: (a) The two extension-name mismatches (`.bbt` → `.grb`, `.nbt` → `<index>$null.cbt`) are likely to surface in Step 7's plan text ("the IT helper from the second step above"); the `OrphanFabricator` interface and two factory helpers are unchanged and directly reusable. (b) Tests that need to disambiguate the MV-engine null sub-tree from the svTree's plain `<index>.cbt` sibling should filter on `endsWith("$null.cbt")`, not `endsWith(".cbt")` alone. (c) The recovery pass's `targetBytes` formula for SLBB depends on `pagesSize=1` being the highest occupied data-page index post-`create()`; future changes to `SLBB.init()` must preserve this contract.


  >
  > **What:** Extend `TruncateOrphansAfterRecoveryIT` with two new
  > scenario groups. **SLBB `.bbt`:** create a class with a LinkBag
  > property; insert enough RIDs to grow the SLBB past one bucket
  > page; close cleanly; fabricate orphan pages on the `.bbt` file;
  > reopen; assert truncate and that the next LinkBag insertion
  > completes without `IllegalStateException`. Run under both
  > `ChecksumMode.Off` and `StoreAndThrow`. **MV-engine `.nbt`:**
  > create a NOTUNIQUE index on a String property; insert ~500-1000
  > entities with `null` for that property (calibrate during
  > implementation to force `.nbt` past one bucket page); close;
  > fabricate orphan pages on the `.nbt` file via the magic-stamped
  > helper; reopen; assert truncate.
  >
  > **Key files:** `core/src/test/.../impl/local/TruncateOrphansAfterRecoveryIT.java`
  > (extend); reference `BTreeMultiValueIndexEngine` for the wrapper's
  > svTree + nullTree dispatch and the null-bucket file extension.

- [x] Step: IHM HLL-spill discriminator unit test plus fabrication-style recovery IT
  - [x] Context: safe
  > **Risk:** medium — test infrastructure plus crash-recovery semantics (no live concurrency; static orphan setup)
  >
  > **What was done:** Added two new test classes under `core/src/test/.../core/index/engine/` pinning the `IndexHistogramManager` HLL-spill page-1 discriminator. The unit test `IndexHistogramManagerSpillDiscriminatorTest` (6 `@Test` methods) drives both `writeSnapshotToPage` (via public `flushIfDirty(AtomicOperation)`) and the private `flushSnapshotToPage` (via no-arg `flushIfDirty()` with stubbed `AtomicOperationsManager.executeInsideAtomicOperation` + `executeInsideComponentOperation`) along the `op.filledUpTo > 1` branch with a mocked `AtomicOperation` and real `ByteBufferPool`-backed `CacheEntry`s so the real `HistogramStatsPage.writeSnapshot` and `writeHllToPage1` byte writes execute. `Mockito.verify` confirms which page-1 method fires (`loadPageForWrite` vs `allocatePageForWrite`). The IT `IndexHistogramSpillRecoveryIT` mirrors `TruncateOrphansAfterRecoveryIT`'s fabrication pattern: opens disk storage under `ChecksumMode.StoreAndThrow`, creates a NOTUNIQUE String index, populates 500 rows, ANALYZEs to materialize `.ixs`, closes cleanly, magic-stamps one orphan page on `.ixs`, reopens + inserts + ANALYZEs again, asserts no `IllegalStateException`.
  >
  > **What was discovered:** (1) The IHM `.ixs` file is excluded from Track 7's recovery-time orphan-truncation pass (EP-less component), so a fabricated orphan page persists across reopen and the post-recovery `filledUpTo(fileId) > 1` contract is permanent on that file — this is the structural property the IT leverages, mirroring the plan's Non-Goals position that "EP-less components and `IndexHistogramManager` deliberately excluded". (2) `AtomicOperation.loadPageForWrite` uses `(long fileId, long pageIndex, int pageCount, boolean verifyChecksum)` — the `pageCount` slot is `int`, not `long`, so Mockito `verify`/stubs must use `anyInt()` / `eq(1)` not `anyLong()` / `eq(1L)`; the initial draft used `anyLong()` and produced a compile error. (3) `CacheEntryImpl` and `CachePointer` take `int pageIndex`, even though the `AtomicOperation` surface and `StorageComponent` helpers consistently use `long pageIndex` — the conversion happens at the cache-construction boundary. (4) `HistogramStatsPage.writeSnapshot` needs a real buffer to avoid NPE on `setIntValue` / `setBinaryValue`, so the unit test allocates real `ByteBufferPool`-backed pages rather than pure Mockito mocks; this pattern was already canonical in `HistogramStatsPageHllSpillTest` and is reused here. (5) `flushSnapshotToPage`'s body is wrapped in BOTH `executeInsideAtomicOperation` AND `executeInsideComponentOperation` — the initial single-layer stub left the locked-op consumer unrun and Mockito reported "zero interactions with this mock"; the fix stubs both layers. (6) Cross-track forward-looking note: if a future track adds an EP / EP-like metadata page to IHM (today the plan's Non-Goals deliberately excludes IHM from the recovery-time truncation pass), the IT's fabrication assumption would break — the orphan page would be truncated on reopen, post-reopen `filledUpTo` would return 1, and the discriminator's existing-page arm would no longer be exercised. The IT should be reconsidered alongside any such change.
  >
  > **What changed from the plan:** None structural. The IT's "drives a follow-up spill" framing in the step description is interpreted as "the follow-up flush exercises the same `writeSnapshotToPage` body whose discriminator branch is the load-bearing surface"; deterministic `hllOnPage1=true` triggering at the session level would require very specific `MAX_BOUNDARY_BYTES` + bucket configuration and is not necessary because the unit test pins the branch logic directly — the IT's value is the end-to-end lifecycle (open + flush + close + reopen + flush) under a fabricated post-recovery file-size shape and `StoreAndThrow` checksum mode.
  >
  > **Key files:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexHistogramManagerSpillDiscriminatorTest.java` (new — 6 `@Test` methods); `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexHistogramSpillRecoveryIT.java` (new — fabrication-style recovery IT under `ChecksumMode.StoreAndThrow`).
  >
  > **Critical context:** Cross-step signals for the remaining Track 6 steps and Phase C reviewers: (a) The IHM page-1 discriminator at `IndexHistogramManager.java:1928` (write-path) and `:1997` (flush-path) is now pinned at both the unit and IT level; Step 5 (I4 production hot-path MT pins) does NOT need to re-cover the spill path — it should focus on the same-instance two-TX races on `CollectionPositionMapV2.allocate`, `PaginatedCollectionV2.allocateNewPage`, and `IndexHistogramManager` `flushSnapshotToPage` vs `writeSnapshotToPage` per the original step description. (b) The `ByteBufferPool` + `CacheEntryImpl` real-buffer pattern (from `HistogramStatsPageHllSpillTest`, reused here) is the canonical way to unit-test methods that read/write page bytes without a full storage fixture; future tests that need to verify the contents of a page on a mocked `AtomicOperation` should follow the same pattern. (c) The IT uses `ChecksumMode.StoreAndThrow`, mirroring `TruncateOrphansAfterRecoveryIT`'s matrix axis — if a future change introduces an eager orphan-page read on the IHM open path, this IT would surface a checksum-mismatch `StorageException` under `StoreAndThrow` rather than silently passing.


  >
  > **What:** Two test classes. **Unit:**
  > `IndexHistogramManagerSpillDiscriminatorTest` mocks
  > `AtomicOperation.filledUpTo(fileId)` and drives both
  > `flushSnapshotToPage` and `writeSnapshotToPage` along the
  > `op.filledUpTo > 1 ? loadPageForWrite : allocatePageForWrite`
  > branch; pins the discriminator logic without crash simulation.
  > **IT:** `IndexHistogramSpillRecoveryIT` builds an IHM with HLL
  > state on page 0, drives the first spill to page 1 via real
  > production calls, closes cleanly, then fabricates the recorded
  > state by extending the `.ihm` file (or whatever extension IHM
  > uses; confirm in implementation) so the post-replay path will
  > observe `op.filledUpTo > 1`; reopens; drives a follow-up spill;
  > asserts the existing-page branch fires without
  > `IllegalStateException`.
  >
  > **Key files:** new test classes in
  > `core/src/test/.../core/index/engine/`; references
  > `IndexHistogramManager.java:1928` (write-path discriminator) and
  > `:1997` (flush-path discriminator).

- [x] Step: I4 production hot-path MT pins plus `inMemoryEagerInstallToleratesConcurrentOrphanReuse` repeated-rounds strengthening
  - [x] Context: info
  > **Risk:** high — concurrency (MT pins on three production hot paths plus repeated-rounds strengthening of an existing MT test)
  >
  > **What was done:** Added a new JUnit 4 MT test class `ProductionAllocatorConcurrencyMTTest` under `core/src/test/.../storage/impl/local/paginated/atomicoperations/` with three `@Test` methods that pin the per-component-lock invariant on three production hot paths sharing a `fileId`. The CPMV2 round drives two concurrent `PaginatedCollectionV2.allocatePosition` calls on a freshly-created class's PCV2 instance, capturing each worker's `PhysicalPosition.collectionPosition` and asserting `hasSize(2).doesNotHaveDuplicates()` per round. The PCV2 round drives two concurrent `PCV2.createRecord` calls with a payload sized at `CollectionPage.MAX_RECORD_SIZE + 1` so the splitting loop forces at least one `allocateNewPage` per call. The IHM round drives two concurrent `IndexHistogramManager.buildHistogram` calls (with empty key streams + `totalCount=0` hitting the `nonNullCount < histogramMinSize` early-exit at `IHM.java:755` so both workers unconditionally reach `writeSnapshotToPage` at `:1908` and contend the `acquireExclusiveLockTillOperationComplete` site without any CAS gate). Each `@Test` runs 100 rounds under `CyclicBarrier(2)` with a fresh component instance per round; storage uses `checksumMode=StoreAndThrow` and the class runs under `@Category(SequentialTest.class)`. Also strengthened `AllocatePageForWriteTest.inMemoryEagerInstallToleratesConcurrentOrphanReuse`: replaced the single-release `CountDownLatch(1)` start gate with a 100-round shape using `CyclicBarrier(2)` and a fresh fileId per round, asserting the same orphan-reuse invariants (referrer balance, pointer identity, physical size) on every round. All 25 affected tests pass; coverage gate is PASSED at 91.5% line / 89.1% branch on the cumulative branch diff.
  >
  > **What was discovered:** (1) Freshly-created classes get 8 default clusters from the schema layer's polymorphic-id allocation, not 1; the PCV2 resolver picks the first cluster id (the MT lock invariant holds on any single PCV2 instance regardless of sibling clusters). (2) **Phase C surfaced a structural test-effectiveness gap on the original IHM pin (5 of 6 reviewers agreed):** the original two-worker race against `flushIfDirty(op)` and `flushIfDirty()` was short-circuited by the `DIRTY_MUTATIONS.compareAndSet(observed, 0L)` CAS at `IndexHistogramManager.java:932-934` and `:959-961` — only the CAS-winner ever entered the lock-acquiring body; the loser fast-returned without acquiring any per-component lock. The original Javadoc tried to rescue this with a "cross-round lock leak" defense (round N's lock state leaking to round N+1's CAS-winner) but that defense was structurally incorrect: each round provisions a fresh class → fresh index → fresh IHM instance, and the per-component lock is held on `this` of `StorageComponent`, so round N's IHM lock object is unrelated to round N+1's. A regression dropping the lock on either flush path would NOT have been caught. The Phase B iter-2 review fix replaced the IHM @Test body with two concurrent `buildHistogram` calls (no CAS gate; both threads deterministically reach `writeSnapshotToPage` via the `nonNullCount < histogramMinSize` early-exit), and pinned positive-evidence via a `completions` `AtomicInteger` asserting both workers ran the lock-acquiring body to completion. (3) `EntityImpl.RECORD_TYPE` is the canonical production constant for the document record-type byte — the iter-3 review fix replaced two test-local `(byte) 'd'` magic literals with the production constant rather than introducing a test-local field. (4) Production code reach for the IHM pin: `buildHistogram` is called from `BTreeSingleValueIndexEngine` / `BTreeMultiValueIndexEngine.buildInitialHistogram` (reached from `IndexAbstract.buildHistogramAfterFill`), so the lock contract this test pins matches the production-reachable lock-acquisition path. (5) The CPMV2 case Javadoc was corrected to make the lock identity explicit — the test pins the PCV2-wrapping-CPMV2 lock contract (workers enter via `PCV2.allocatePosition` which wraps `CPMV2.allocate` inside its `calculateInsideComponentOperation`); a regression dropping the lock on `CPMV2.allocate` alone (called from outside any PCV2 lock window) would NOT be detected by this test. (6) `PCV2_LARGE_PAYLOAD_BYTES` is now derived from `CollectionPage.MAX_RECORD_SIZE + 1` rather than a hardcoded 16 KiB — the original Javadoc's "65536-byte page" rationale was factually wrong (`GlobalConfiguration.DISK_CACHE_PAGE_SIZE` defaults to 8 KiB), and deriving the constant keeps the test stable against future page-size overrides.
  >
  > **Phase C step-level dim review.** Iteration 1 spawned 6 reviewers in parallel (CQ, BC, TB, TC, TX, TS) against commit `9d384315c8` and surfaced 4 should-fix findings + 8 named minors. After dedup and synthesis: F1 (IHM CAS short-circuit — 5/6 reviewers agreed, the load-bearing finding); F3 (negative-only assertions on CPMV2/PCV2 worker results); F4 (`resolveHistogramManagerByIndexName` swallowed exceptions); F5 (`taggedFailure` hid root exception type in aggregator); F6 (unstable worker IDs); F7 (factually-wrong PCV2 payload Javadoc); F8 (CPMV2 vs PCV2 lock identity in class Javadoc). Iter-1 implementer commit `fde893f201` applied all 7 fixes — the F1 fix replaced the IHM test body to drive `buildHistogram` (no CAS gate, both workers race the lock-acquisition site), F3 added `ConcurrentLinkedQueue<Long>` per worker + `hasSize(2).doesNotHaveDuplicates()` assertions for CPMV2/PCV2 + `completions == 2` for IHM, F4 rethrew the engine-resolution exception as `AssertionError` with the original cause chained, F5 appended `getCause()` rendering to `buildAggregatedAssertionError`'s message, F6 replaced the shared `AtomicInteger workerIdGen` with construction-order `final int workerId = w;` capture, F7 derived the constant from production, F8 spelled out the PCV2-wrapping-CPMV2 lock identity. Iter-2 gate-check fan-out: BC / TB / TC / TX / TS PASS; CQ FAIL on the lone CQ9 (minor: `(byte) 'd'` magic byte without explanation — not in the iter-1 fix scope). Iter-3 fix commit `6fdad227c3` applied CQ9 by importing the production `EntityImpl.RECORD_TYPE` constant. Iter-3 gate-check: CQ PASS. Final gate-check fan-out at iter-3: 6/6 PASS, zero new findings.
  >
  > **Deferred to future work (recorded here so a future-track reader sees the categories):**
  >
  > - **F2 — `ChecksumMode.Off` matrix on the MT pins.** Track 7's `TruncateOrphansAfterRecoveryIT` adopted a `ChecksumMode.{Off, StoreAndThrow}` × magic-stamped/zero-byte matrix per Phase A refinement #2. The I4 MT pins ship under `StoreAndThrow` only. Phase A refinement #2 names CS1 specifically (the recovery-pass orphan-read claim is what changes under `Off` vs `StoreAndThrow`); the I4 lock contract is upstream of `WOWCache.loadOrAdd`'s `verifyMagicChecksumAndDecryptPage` branch, so the lock-drop regression would surface identically under both modes. Deferred deliberately; can revisit if a future regression motivates `Off` parity.
  > - **F9 — IOException swallowed inside no-arg `flushIfDirty()`.** Became moot after F1: the IHM @Test no longer exercises that path, so the no-arg-flush asymmetry is out of scope for this test class. The contract itself remains in production code (`IHM.java:964-969`).
  > - **F10 — Aggregator pattern harmonisation.** Three coexisting aggregator shapes (`AllocatePageForWriteTest`'s `AtomicReference<Throwable>` pair; the new MT class's `ConcurrentLinkedQueue<Throwable>` + `taggedFailure` + `buildAggregatedAssertionError`; Step 1's `LoadOrAddPoisonCascadeRegressionTest` shape). Harmonising would mean extracting a shared test utility under `test-commons` or similar — cross-class refactor outside Step 5 scope.
  > - **F11 — Pool lifecycle helper extraction.** Three `@Test` methods replicate the same `Executors.newFixedThreadPool(2)` ribbon. DRY improvement, not a bug.
  > - **F12 — `runOrphanReuseRound` timeout drain.** If `future1.get(30s)` throws `TimeoutException`, the round's `finally` runs `realCache.deleteFile(fileId)` while `future2`'s worker may still be in flight. Pool reuse may carry leftover bumps into next round. Best-effort hardening; the existing `pool.shutdownNow()` + `awaitTermination` ribbon serialises at the lock layer, so no data-race concern, only diagnostic noise on the rare timeout path.
  >
  > **What changed from the plan:** The plan's test-class name `I4ProductionHotPathMTTest` was changed to `ProductionAllocatorConcurrencyMTTest` per the ephemeral identifier rule (`I4` is a workflow plan-invariant label, forbidden in durable content). The plan's "IHM `flushSnapshotToPage` vs `writeSnapshotToPage`" prescription was satisfied during the iter-2 review fix by replacing the original `flushIfDirty` race (CAS-gated, structurally weak) with two concurrent `buildHistogram` calls (no CAS gate, both threads reach `writeSnapshotToPage`); the underlying lock contract being pinned is the same, but the driver surface differs from the plan text.
  >
  > **Key files:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/ProductionAllocatorConcurrencyMTTest.java` (new — 3 `@Test` methods + 7 private helpers + 1 record-type constant import); `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AllocatePageForWriteTest.java` (modified — replaced the existing single-release `CountDownLatch(1)` shape on `inMemoryEagerInstallToleratesConcurrentOrphanReuse` with a 100-round `CyclicBarrier(2)` harness).
  >
  > **Critical context:** Cross-step signals for Steps 6, 7, and Phase C reviewers: (a) Step 6 (Javadoc `@apiNote` on `WriteCache.loadOrAdd` / `AtomicOperation.allocatePageForWrite`) should reference the new test class name `ProductionAllocatorConcurrencyMTTest` (not `I4ProductionHotPathMTTest`) when citing the load-bearing regression gates for the per-component-lock invariant. (b) The `resolveSinglePcv2ForClass` helper pattern (iterate `getCollectionInstances()` and match by `getId() == collectionId`) is reusable for Step 7's incremental-backup IT if it needs a PCV2 handle from a session. (c) The `buildHistogram`-as-IHM-pin observation (no CAS gate; empty key stream + `totalCount=0` hits the `nonNullCount < histogramMinSize` early-exit; both threads contend `writeSnapshotToPage`'s lock) is reusable for any future test that needs to pin the IHM per-component lock without depending on session-level workload tuning. (d) The `ConcurrentLinkedQueue<Long>` per-worker result collection + `hasSize(2).doesNotHaveDuplicates()` positive-evidence pattern is reusable for Step 7's MT incremental-backup IT if it grows a concurrent-inserts source-side arm.


  >
  > **What:** New test class `I4ProductionHotPathMTTest` with three
  > MT pins, each using a repeated-rounds shape (≥ 100 rounds, fresh
  > component instance per round, `CyclicBarrier(2)` for start
  > synchronization, fail on first round that produces
  > `IllegalStateException` in either thread): (a) `CollectionPositionMapV2.allocate`
  > — two TXes from one session pool inserting into the same cluster
  > drive the entry-point page write lock; (b)
  > `PaginatedCollectionV2.allocateNewPage` — two TXes appending to
  > the same paginated collection via the state-page write lock; (c)
  > `IndexHistogramManager` `flushSnapshotToPage` vs
  > `writeSnapshotToPage` on a shared IHM instance under
  > `AtomicOperationsManager.acquireExclusiveLockTillOperationComplete`.
  > Plus: modify `AllocatePageForWriteTest.inMemoryEagerInstallToleratesConcurrentOrphanReuse`
  > to use the same repeated-rounds shape (≥ 100 rounds with a fresh
  > `realCache` fileId per round under a `CyclicBarrier(2)`),
  > replacing the existing single-release `CountDownLatch(1)` start
  > gate.
  >
  > **Key files:** new MT test class in
  > `core/src/test/.../core/storage/impl/local/paginated/atomicoperations/`;
  > modify
  > `AllocatePageForWriteTest.java:788` (existing test); references
  > `CollectionPositionMapV2.java:225-287`,
  > `PaginatedCollectionV2.java:2288-2304`,
  > `IndexHistogramManager.java:1879-1932`.

- [x] Step: I4 lock-contract documentation via Javadoc `@apiNote`
  - [x] Context: info
  > **Risk:** low — docs only (no code or test logic; no executable behavior change)
  >
  > **What was done:** Added `@apiNote` Javadoc blocks to two SPI methods documenting the per-component-lock contract that allocator-shape callers must satisfy before sharing a `fileId` across concurrent transactions: (a) `WriteCache.loadOrAdd(long, long, boolean)` — the cache-layer primitive, with the contract scoped to the extend / gap-fill branches (the load branch covered by the per-page `lockManager` shared lock); (b) `AtomicOperation.allocatePageForWrite(long, long)` — the AOBT-layer wrapper, cross-referencing the cache primitive's `@apiNote`. Both blocks name the production-reachable hot paths (`CollectionPositionMapV2.allocate` wrapped by `PaginatedCollectionV2.allocatePosition`, `PaginatedCollectionV2.allocateNewPage`, and `IndexHistogramManager.writeSnapshotToPage`) pinned by `ProductionAllocatorConcurrencyMTTest`, and call out the lifecycle-only callers (`BTree.create`, `SharedLinkBagBTree.splitRootBucket`) that are not reachable from concurrent session-level transactions and therefore rely on the contract via audit rather than an MT regression gate.
  >
  > **What was discovered:** `AtomicOperationsManager` exposes the lock as `executeInsideComponentOperation` / `calculateInsideComponentOperation`, which both internally call `acquireExclusiveLockTillOperationComplete`; spelling out the public facade plus the underlying acquire method gives readers a complete grep target for "where is this lock taken?". The existing `AsyncFile.shrink` `@apiNote` in the same package set the placement convention (after `@param`/`@throws`, last block tag), which the new blocks follow.
  >
  > **What changed from the plan:** None. Per the ephemeral-identifier rule, the Javadoc text cites the concrete test class name `ProductionAllocatorConcurrencyMTTest` rather than plan-invariant labels.
  >
  > **Key files:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java` (modified — `@apiNote` block on `loadOrAdd`); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperation.java` (modified — `@apiNote` block on `allocatePageForWrite`).
  >
  > **Critical context:** The two `@apiNote` blocks are the durable home for the per-component-lock contract; design.md and the Phase A audit notes in `_workflow/` will be removed in the Phase 4 cleanup. Phase C reviewers entering the allocator SPI surface will now see the contract inline rather than reverse-engineering it from call sites. The `ProductionAllocatorConcurrencyMTTest` cross-reference is the load-bearing regression gate on the production-reachable subset; the lifecycle-only carve-out for `BTree.create` / `SharedLinkBagBTree.splitRootBucket` is documented as relying on audit, not MT pin.


  >
  > **What:** Add `@apiNote` Javadoc blocks documenting the
  > per-component-lock invariant on (a)
  > `WriteCache.loadOrAdd(fileId, pageIndex, verifyChecksums)` —
  > "Callers MUST hold the per-component exclusive lock (via
  > `AtomicOperationsManager.executeInsideComponentOperation` or
  > `acquireExclusiveLockTillOperationComplete`) for any `(fileId,
  > pageIndex)` they intend to allocate-or-reuse; concurrent callers
  > without the lock may race the allocator path and trip
  > `IllegalStateException`."; (b)
  > `AtomicOperation.allocatePageForWrite(fileId, knownIndex)` —
  > mirror the same invariant. Reference the Phase A audit conclusion
  > and the three production hot-path MT pins from the prior step as
  > the load-bearing regression gates for callers that fall under the
  > "production reachable from concurrent TXes" subset; document
  > `BTree.create` and `SLBB.splitRootBucket` as covered by the
  > Phase A audit alone (callers run once per component lifecycle and
  > are not reachable from concurrent TXes through normal session
  > code).
  >
  > **Key files:**
  > `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java`
  > (interface),
  > `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperation.java`
  > (interface).

- [x] Step: Short-duration MT incremental-backup IT plus executable IR-wiring test
  - [x] Context: info
  > **Risk:** high — concurrency (new short-duration MT incremental-backup orchestration) plus crash-recovery semantics (orphan-bearing backup→restore round-trip)
  >
  > **What was done:** Added a new JUnit 4 IT class `StorageBackupMTRestoreIT` under `core/src/test/.../paginated/` with three `@Test` methods covering the remaining gaps in the incremental-backup → restore round trip without resurrecting the 90-minute `@Ignore` `StorageBackupMTStateTest`. (1) The MT test drives 4 concurrent inserter threads against a periodic incremental-backup thread for a bounded 20 s contention window under `CyclicBarrier`/`stop`-flag coordination, then closes the source, restores a fresh target, and asserts source == target via `DatabaseCompare` plus no inserter surfaced any non-retry exception. Positive-evidence asserts (Phase C iter-1) ensure the contention window actually exercised the allocator: `WOWCache.getLoadOrAddExtendBranchInvocations()` delta ≥ `INSERTER_THREADS` and `IncrementalBackupWorker.completedBackups` ≥ 2 (at least one mid-contention plus one final post-stop snapshot). (2) The happy-path consistency check populates a source, takes full + incremental backups, restores into a target, and confirms every restored `.pcl` is page-aligned (`onDiskBytes == HEADER_SIZE + physicalPages * pageSize`) and at least one `.pcl` grew past the 2-page EP+root footprint; explicitly documented as a smoke test rather than a structural pin. (3) **New Phase C iter-1 addition — the load-bearing executable pin:** `truncateOrphansAfterRecoveryFiresOnReopenAfterTargetSideFabrication` fabricates 4 orphan pages on the restored target's largest BackupClass `.pcl` via `RandomAccessFile.setLength`, reopens via `YourTracks.instance` + `openSession`, captures the size immediately after reopen (before any non-recovery TX can re-extend the file), and asserts the orphan tail was truncated back to `sizeBeforeFabrication`. Both `AbstractStorage.open()` and `DiskStorage.postProcessIncrementalRestore` invoke the same `executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)`, so this pins the recovery-time-truncation semantics by structural equivalence; the source-text sentinel `DiskStorageRestoreOrchestratorWiringTest` is retained as a complementary call-shape pin (its retirement was floated in the original plan but rejected once Phase C revealed the executable replacement cannot non-vacuously cover the IR-side path through the public API). Class runs under `@Category(SequentialTest.class)` with `ChecksumMode.StoreAndThrow` (Track 6 mandate); wall time ~56 s for all 3 `@Test` methods.
  >
  > **What was discovered:** (1) The original Step 7 commit's IR-wiring assertion `onDiskBytes == HEADER_SIZE + physicalPages * pageSize` was structurally tautological — `physicalPages` came from `WOWCache.physicalSizeForBackupSnapshot(fileId) = file.getFileSize() / pageSize` where `AsyncFile.size` is initialised from the on-disk file size during `initSize()`, so both sides of the equation traced back to the same source. Three reviewers (code-quality, test-behavior, test-completeness) independently flagged this as a **blocker** — the test gave false confidence that it pinned the `truncateOrphansAfterRecovery` dispatch regression direction. The fix added a target-side orphan-fabrication test that pins the equivalent open()-side dispatch with a strict shrink-back assertion. (2) Freshly-created classes get 8 default clusters from the schema layer's polymorphic-id allocation, only one of which holds the workload. `findFirst()` against `wowCache.files()` non-deterministically picks an empty cluster `.pcl` most of the time; an empty cluster (`logicalPages==0`) with fabricated orphans (`physicalBytes>pageSize`) trips the recovery pass's corruption-guard branch at `StorageComponent.verifyAndTruncateOrphans` and logs a WARN-and-skip rather than truncating. The fix selects the largest BackupClass `.pcl` by `physicalSizeForBackupSnapshot` instead. (3) `DatabaseSessionEmbedded` does NOT implement `DatabaseSession` — it extends `ListenerManger<SessionListener>` and implements `AutoCloseable` only. The shared `openSession` helper has to use the concrete `DatabaseSessionEmbedded` return type to match `YouTrackDBImpl.open`. (4) The Apache license header on the original Step 7 commit was malformed (three nested `*` columns, body cut off mid-license); sibling tests in the same package (`StorageBackupMTIT.java`, `StorageBackupMTStateTest.java`) have no header — local convention is no header, so the header was dropped rather than fixed. (5) `WOWCache.verifyMagicChecksumAndDecryptPage` only runs on read, not on the truncate path, so zero-byte orphan fabrication is accepted under `ChecksumMode.StoreAndThrow` (no CRC check on the truncate side); the fabricated orphans are truncated successfully on reopen.
  >
  > **Phase C step-level dim review.** Iteration 1 spawned 9 reviewers in parallel (CQ, BC, TB, TC, CS, TY, PF, TX, TS) against commit `425a212656`. CS returned zero in-scope findings; the other 8 surfaced 26 raw findings with ~50% cross-dimension overlap. After dedup and synthesis: 10 in-scope merged findings (1 blocker M1 — IR-wiring assertion tautology, agreed by CQ+TB+TC and partially by TY; 9 should-fix covering positive evidence M2, Javadoc-vs-code M3, stop-blocking sleep M4, field rename M5, FQN consistency M6, malformed header M7, ChecksumMode mandate M8, schema dedup M9, workload-size floor M10). 12+ minor items deferred to a future hardening pass (extended file-type coverage `.cpm`/`.cbt`/`.grb`, restore-chain edge cases, payload-size variety, magic constants, seeded `Random`, etc.). Iter-1 implementer commit `f87d64abd0` applied all 10 fixes — load-bearing changes are: M1 added the new target-side fabrication `@Test` and rescoped test-2's Javadoc to a happy-path smoke check; M2 wired the extend-branch + completed-backups positive-evidence counters; M8 introduced a `makeConfig(ChecksumMode)` + `openSession` helper set propagating `StoreAndThrow` through all three tests; M9 extracted `createBackupSchema` for the three call sites. Gate-check fan-out at iter-2 across the 8 dimensions with open findings (CS skipped — had no iter-1 findings): 8/8 PASS, zero new findings, zero regressions. Loop ended at iter-1 fix + iter-2 gate-check verification.
  >
  > **Deferred to a future hardening pass (recorded here for any reader entering this test class):** extend the IR-wiring fabrication pattern to the other EP-equipped file types (`.cpm`, `.cbt`, `.grb` — currently only `.pcl` is pinned); add boundary coverage for the restore chain length (single full backup, deep ≥5-incremental chain); vary record payload size to exercise the page-split allocator path (`CollectionPage.MAX_RECORD_SIZE + 1`); promote magic constants (`Thread.sleep(50)`, `5`-second drain timeout, `200`/`100` commit counts) to named constants; seed `Random` for reproducibility on the single-threaded tests; consider a `ConcurrentLinkedQueue<Throwable>` aggregator pattern matching the Step 5 retrospective (currently uses `AtomicReference` first-failure-wins).
  >
  > **What changed from the plan:** The plan said "Once executable coverage is in place, delete `DiskStorageRestoreOrchestratorWiringTest`." The sentinel is intentionally KEPT — Phase C revealed the executable assertion cannot non-vacuously cover the IR-side `postProcessIncrementalRestore` dispatch through the public API (because `AbstractStorage.open()` truncates orphans before `backup()` can ship them). The two tests are complementary: the source-text sentinel pins the literal `executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)` call shape in `postProcessIncrementalRestore`, and the new executable test pins the structural orphan-truncation semantics via the open() dispatch. The original Step 7 test-2's "pins the IR-wiring" framing was rescoped to "happy-path consistency check" with explicit Javadoc disclaim. The class Javadoc's "Why this is NOT a generic orphan-injection IT" block remains accurate for the IR-side direction; the new test exploits the equivalent open()-side direction that the original block did not consider.
  >
  > **Key files:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/StorageBackupMTRestoreIT.java` (new across the original Step 7 commit + iter-1 Review fix commit — 3 `@Test` methods + 4 helper classes + helper methods including `makeConfig(ChecksumMode)`, `openSession`, `createBackupSchema`, `findLargestPclFor`); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` (Step 1 baseline — `getLoadOrAddExtendBranchInvocations` accessor consumed by this test).
  >
  > **Critical context:** Cross-track signals for Phase C track-level review and any future track entering this test surface: (a) The test-side cluster-selection pattern (filter by class-name prefix + select by `physicalSizeForBackupSnapshot` rather than `findFirst()`) is reusable for any future test that needs to pick "the data cluster" of a freshly-created class for raw-file fabrication — the schema layer's 8-default-clusters polymorphic allocation makes `findFirst()` non-deterministic, and an empty `.pcl` with fabricated orphans trips the corruption-guard skip-and-warn branch. Worth recording in `CLAUDE.md` under `### Recipes` if it surfaces in another test. (b) `DatabaseSessionEmbedded` does NOT implement the API-level `DatabaseSession` interface; helpers that need to receive an opened session from `YouTrackDBImpl.open` must use the concrete `DatabaseSessionEmbedded` type. (c) The corruption-guard branch in `StorageComponent.verifyAndTruncateOrphans` (`logicalPages==0 && physicalBytes>pageSize` → WARN+skip) is the same defense that any future orphan-fabrication test must avoid; pick a real workload-bearing component instance.


  >
  > **What:** Keep `StorageBackupMTStateTest`'s `@Ignore` in place
  > (90-minute stress-soak test, not a CI test). Add a NEW test class
  > `StorageBackupMTRestoreIT` (or similar — final name picked in
  > implementation) covering two concerns. **(1) Short-duration MT
  > incremental-backup recovery:** drive concurrent inserts on a
  > source storage during an active incremental-backup-then-restore
  > cycle (≤ 120 s wall under `@Category(SequentialTest.class)`);
  > assert no allocator exception on the source during inserts;
  > assert `DatabaseCompare` between source and restored target after
  > the cycle. Exercises the collapsed `DiskStorage.restoreFromIncrementalBackup`
  > loop's MT path. **(2) Executable IR-wiring (replaces
  > source-text sentinel `DiskStorageRestoreOrchestratorWiringTest`):**
  > populate a source storage; fabricate orphan pages on a `.pcl`
  > file using the IT helper from the second step above; take an
  > incremental backup; restore into a fresh target; assert the
  > target's `.pcl` file size equals the logical horizon (orphans
  > truncated by `postProcessIncrementalRestore` → `truncateOrphansAfterRecovery`);
  > assert next TX on target completes without `IllegalStateException`.
  > Pre-flight: verify that `backup()` carries orphan pages forward
  > by reading the backup contents — if `backup()` filters by
  > logical pageSize, the IT becomes vacuous and the recipe needs to
  > fabricate orphans on the source AFTER the backup is taken but
  > BEFORE the restore runs (i.e., on the restore-target file's
  > pre-allocated extent rather than the backup payload). Once
  > executable coverage is in place, delete
  > `DiskStorageRestoreOrchestratorWiringTest`.
  >
  > **Key files:** new test class in
  > `core/src/test/.../core/storage/impl/local/paginated/`; references
  > `DiskStorage.postProcessIncrementalRestore` at the
  > `flushAllData()` + `truncateOrphansAfterRecovery` wiring;
  > delete-on-success
  > `core/src/test/.../core/storage/disk/DiskStorageRestoreOrchestratorWiringTest.java`.
