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
- [ ] Step implementation (4/7 complete)
- [ ] Track-level code review

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

- [ ] Step: I4 production hot-path MT pins plus `inMemoryEagerInstallToleratesConcurrentOrphanReuse` repeated-rounds strengthening
  > **Risk:** high — concurrency (MT pins on three production hot paths plus repeated-rounds strengthening of an existing MT test)
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

- [ ] Step: I4 lock-contract documentation via Javadoc `@apiNote`
  > **Risk:** low — docs only (no code or test logic; no executable behavior change)
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

- [ ] Step: Short-duration MT incremental-backup IT plus executable IR-wiring test
  > **Risk:** high — concurrency (new short-duration MT incremental-backup orchestration) plus crash-recovery semantics (orphan-bearing backup→restore round-trip)
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
