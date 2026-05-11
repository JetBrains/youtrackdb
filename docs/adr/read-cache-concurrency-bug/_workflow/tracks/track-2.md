# Track 2: Cache test coverage (functional + MT)

## Description

Add functional unit tests covering every branch of `WOWCache.loadOrAdd`
and the `LockFreeReadCache` wrappers, plus MT stress harnesses for
contention, eviction, and `EnsurePageIsValidInFileTask` idempotency.
Run the cache-classes coverage gate before closing the track.

> **What**:
> - Audit the existing test suite under
>   `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/**`.
>   Produce a coverage table by class/method.
> - Add functional unit tests for every branch of `WOWCache.loadOrAdd`:
>   load existing on disk, one-page extend, multi-page gap-fill,
>   `pageIndex == 0` for fresh file, very-high `pageIndex`,
>   file-deletion races, checksum verify on/off.
> - Add functional unit tests for `LockFreeReadCache.loadForRead` /
>   `loadOrAddForWrite`: cache hits, cache misses, eviction with dirty
>   and clean entries, pinned entries, write-back on eviction, two-tier
>   transitions.
> - Add functional unit tests for `DirectMemoryOnlyDiskCache.loadOrAdd`
>   (parallel branches in the in-memory engine).
> - Add MT stress tests:
>   1. Concurrent `loadOrAdd` for **different** `(fileId, pageIndex)` —
>      must not corrupt shared state; both threads observe consistent
>      `AsyncFile.size`.
>   2. Concurrent `loadOrAdd` for the **same** `(fileId, pageIndex)` —
>      segment lock serializes; one thread extends, the other observes
>      the result; both end up with the same `CachePointer`.
>   3. Reader at pageIndex `K-1` (existing) vs writer extending to `K` —
>      reader path is unaffected.
>   4. Eviction-vs-load: pin counts respected, dirty pages flushed
>      before eviction, no lost writes.
>   5. Flush worker concurrency: dirty page flushed while reader holds
>      read lock on the entry.
>   6. `EnsurePageIsValidInFileTask` idempotency (direct, no
>      executor-gating): call `WOWCache.writeValidPageInFile(intId,
>      pageIdx)` twice in sequence on a freshly-extended page and
>      assert exactly one underlying disk write occurs — the second
>      invocation must short-circuit on the existing
>      `getUnderlyingFileSize() <= pagePosition` guard in
>      `WOWCache.writeValidPageInFile` (the same guard
>      `EnsurePageIsValidInFileTask.run()` relies on via its
>      single-line delegation). This replaces the original "hold the
>      executor" formulation, which was infeasible against the
>      JVM-singleton `wowCacheFlushExecutor` without a production
>      seam.
> - Additional disk-engine MT scenario (closes a Track 1 gap):
>   7. `deleteFile` / `truncateFile` vs. concurrent `loadOrAdd` on the
>      disk engine — under continuous installer pressure, an in-flight
>      `WOWCache.loadOrAdd` either completes cleanly (the destructive
>      operation waited for `filesLock.writeLock`) or surfaces
>      `IllegalArgumentException` from the dispatch prelude (the
>      destructive operation already won). Mirrors today's in-memory
>      coverage in `DirectMemoryOnlyDiskCacheLoadOrAddTest`.
> - Add `loadIfPresent` MT coverage: extend `WOWCacheLoadIfPresentTest`
>   with (a) concurrent `loadIfPresent` vs eviction (re-probe after
>   eviction returns null without extending the file), (b) concurrent
>   `loadIfPresent` vs `loadOrAddForWrite` contention on the same key,
>   (c) the in-memory engine's `DirectMemoryOnlyDiskCache.loadIfPresent`
>   `UnsupportedOperationException`-throw smoke test.
> - Run `coverage-gate.py` as a smoke gate that the track is genuinely
>   tests-only: with all changes confined to `src/test/java/`, the
>   gate's "no changed Java files / skipping" verdict is the
>   pass-condition. Any non-skip result means accidental production-code
>   leakage and must be reverted.
>
> **How**:
> - Step ordering (provisional):
>   1. Coverage audit — tabulate existing tests; identify gaps. Output:
>      a short list of test files to add and existing files to extend.
>   2. `loadOrAdd` branch tests (WOWCache + DirectMemoryOnlyDiskCache).
>   3. ReadCache wrapper tests (loadForRead, loadOrAddForWrite).
>   4. MT stress harness for `loadOrAdd` contention (scenarios 1-3).
>   5. MT stress harness for eviction / flush / ensure-valid races
>      (scenarios 4-6).
> - Test infrastructure:
>   - Use `CountDownLatch` / `CyclicBarrier` (with `pool.invokeAll`
>     to guarantee every Future is in-hand before shutdown) for
>     deterministic coordination, mirroring the patterns Track 1
>     established in `DirectMemoryOnlyDiskCacheLoadOrAddTest`.
>     `ConcurrentTestHelper` from `test-commons` is acceptable only for
>     simple "all workers do the same thing" harnesses where no
>     inter-thread synchronization is required. **Avoid** sleeps and
>     timing-based assertions — they make tests flaky in CI.
>   - Build scenarios 4-5 (eviction / flush concurrency) on top of
>     `LockFreeReadCacheBatchingTest`'s `MockedWriteCache` (already
>     extended in Track 1 with `setFilledUpTo`, `storeCount`,
>     `loadCount`, `loadIfPresentCount`, `setLoadIfPresentReturnsNull`);
>     extend it further as needed (e.g., a `setStoreBlocks` toggle for
>     flush-worker concurrency, a `setLoadOrAddReturnsNull` toggle for
>     the fail-fast regression test).
>   - Scenario 6 (idempotency) is a single-threaded sequence assertion
>     against `WOWCache.writeValidPageInFile`; no executor gating, no
>     test-only seam required.
>   - For MT stress, commit to concrete bounds: 8 threads per scenario
>     (matching Track 1's `pool.invokeAll(16)` ceiling halved to fit
>     disk-engine flush-executor contention), 100-1000 iterations per
>     worker (tune empirically against the 60s per-`@Test` timeout
>     ceiling below), and emit one progress line per 100 iterations so
>     the `youtrackdb.test.inactivity.timeout.minutes` killer does not
>     fire during quiescent phases. Each MT `@Test` carries
>     `timeout = 60_000` so a hang fails fast rather than waiting for
>     the 15-min deadlock killer.
> - Coverage gate invocation pattern:
>   ```bash
>   ./mvnw -pl core clean package -P coverage
>   python3 .github/scripts/coverage-gate.py \
>     --line-threshold 85 --branch-threshold 70 \
>     --compare-branch origin/develop \
>     --coverage-dir .coverage/reports
>   ```
> - **What this track verifies vs. defers to Track 6**:
>   - Track 2 verifies invariants **I2** (extension under segment lock),
>     **I3** (loadOrAdd is total), and **I4** (segment lock serializes
>     contending allocators) at the cache level.
>   - Track 2 cannot verify **I1** (entryPoint as the only discovery
>     surface) because that invariant lives above the cache layer.
>     Track 6 closes that gap.
>
> **Constraints**:
> - **In-scope files**: tests under
>   `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/**`
>   and the in-memory engine's parallel test directory
>   (`core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/memory/**`).
> - **Out of scope**: integration tests on the full storage stack
>   (Track 6); integration tests against the SQL stack; **production-source
>   changes of any kind** — Track 2 does not add test-only seams,
>   constructor overloads, or `@VisibleForTesting` hooks to the cache
>   classes. Scenario 6's reformulation (direct
>   `writeValidPageInFile` idempotency assertion) is the canonical
>   example of avoiding a seam by narrowing the assertion.
> - New tests must not use the `@Deprecated` legacy primitives
>   (`WriteCache.allocateNewPage`, `WriteCache.load`,
>   `LockFreeReadCache.allocateNewPage`) — they are scheduled for
>   deletion in the write-side API collapse track and any new test
>   built on them would be torn down immediately afterwards. Use the
>   new primitives instead: `WriteCache.loadOrAdd` /
>   `WriteCache.loadIfPresent` at the cache layer; and
>   `LockFreeReadCache.loadForRead` / `LockFreeReadCache.loadOrAddForWrite`
>   at the read-cache wrapper layer.
> - **No flaky tests**: every MT test must use deterministic
>   coordination primitives (latches, barriers) — never `Thread.sleep`
>   for correctness, and timing-based checks must have generous
>   bounds (e.g., 30 s per scenario) to absorb CI noise.
> - Use `ytdb.testcontainer.debug.container=true`-style debug knobs
>   only as opt-in diagnostics; never make a test depend on them.
>
> **Interactions**:
> - Depends on Track 1 (the primitive must exist).
> - Independent of Tracks 3, 4, 5; can run in parallel with them.
> - Provides a safety net for the consumer migration in Tracks 3-4 — if
>   a migration step accidentally breaks cache behavior, Track 2 tests
>   surface it immediately.
> - Verifies invariants I2, I3, I4 (cache-level); leaves I1 to Track 6.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed
- [x] Technical: PASS at iteration 2 (10 findings + 1 iter-2 wording nit; 5 should-fix folded into the description (scenarios 6/7 rewrite, `MockedWriteCache` extensions signaled, `ConcurrentTestHelper` wording, coverage-gate rephrase, `loadIfPresent` MT bullet), 4 should-fix carried as decomposition guidance for the steps below, 4 suggestions accepted; iter-2 wording nit on the `EnsurePageIsValidInFileTask` guard location applied)
- [x] Risk: PASS at iteration 2 (7 findings + 1 iter-2 wording nit; 1 blocker on scenario 6 architecture mismatch resolved by reformulation to direct `writeValidPageInFile` idempotency assertion + production-source seam ban; 4 should-fix folded into the description (scenario 6 reformulation, MT stress budget, deprecated-API avoidance, `MockedWriteCache` reuse) plus 2 carried as decomposition guidance (engine bifurcation, ~10-item Track 1 carry-over checklist); 3 suggestions accepted; iter-2 wording nit on the Constraints allow-list applied)

## Steps

- [ ] Step: Coverage delta audit, Track 1 hygiene fixes, MockedWriteCache extensions, and fail-fast `IllegalStateException` regression test
  > **Risk:** medium — shared test infrastructure: extends
  > `LockFreeReadCacheBatchingTest.MockedWriteCache` (used by multiple
  > tests) and touches `LockFreeReadCacheConcurrentTestIT`. Tests-only,
  > no production-source changes.
  >
  > **Goal.** Build the test-infrastructure foundation the later steps
  > rely on, and absorb the trivial Track 1 deferred hygiene items so
  > they aren't sprinkled across subsequent steps.
  >
  > **Audit (episode deliverable only — no code).** Before writing
  > code, walk the six Track 1 step episodes in
  > `tracks/track-1.md` and produce in the step's
  > `**What was discovered**` field a **delta checklist** of every
  > Track 1 deferred test-hardening item, each mapped to (a) target
  > test file, (b) target Step in this track (1-6), (c) the Track 1
  > step episode that flagged it. Items to cover at minimum:
  > `verifyChecksums=true` parity on disk-engine load + extend +
  > gap-fill (→ Step 2); `verifyChecksums=true` parity on in-memory
  > load + gap-fill (only extend has partial coverage today; → Step
  > 3); in-memory `DirectMemoryOnlyDiskCache.loadIfPresent` UOE-throw
  > smoke test (→ Step 3); gap-fill intermediate-page accessibility
  > (→ Step 2); framePool leak accounting (→ Step 3); target-publish
  > stress on same target index (→ Step 3); truncate-vs-loadOrAdd
  > same-instance race on in-memory engine (→ Step 3); clear-race
  > iteration counter assertion (→ Step 3); fail-fast
  > `IllegalStateException` regression test requiring
  > `setLoadOrAddReturnsNull` mock toggle (→ this step); read-path
  > markAllocated boundary parity test fix (→ this step); the iter-2
  > documentation tweaks from Track 1 Step 4 episode item 7(a)(b)(c)
  > (→ this step); `WOWCache.loadOrAdd` MT defense-in-depth against I4
  > violations (→ Step 5); `WOWCacheLoadIfPresentTest` MT coverage
  > (→ Step 5). The audit also names the engine-bifurcation map:
  > MT scenarios 1-3 land on the **disk engine** only (in-memory
  > parallels already shipped in `DirectMemoryOnlyDiskCacheLoadOrAddTest`'s
  > 16-thread `pool.invokeAll` test, 60-iteration overlapping-gap-fill,
  > and `clear()`-race tests).
  >
  > **Track 1 hygiene fixes (code).**
  > - Hand-wrap `LockFreeReadCacheConcurrentTestIT.java:60`'s javadoc
  >   line to ≤100 chars after the Track 1 rename (Track 1 Step 4
  >   episode iter-2 item (a)).
  > - Drop the "matching the previous mock behavior" framing from
  >   `MockedWriteCache.filledUpToByFile`'s javadoc — replace with a
  >   self-contained description (Track 1 Step 4 iter-2 item (b)).
  > - Add a symmetric `storeCount == storesBefore` assertion to
  >   `testWriteLoadDoesNotFlagExistingPageAsNewlyAllocated` to pin
  >   that `releaseFromWrite(_, _, false)` is a no-op when the
  >   markAllocated flag is unset (Track 1 Step 4 iter-2 item (c)).
  > - Fix `testReadLoadDoesNotFlagPageAsNewlyAllocatedUnderProperFilledUpTo`
  >   to use extend-branch parameters (e.g., `filledUpTo=0,
  >   pageIndex=5`) so the test discriminates the read-vs-write
  >   contract rather than landing in the load-existing branch where
  >   no path sets the flag (Track 1 Step 4 iter-2 item (d)).
  >
  > **MockedWriteCache extensions.**
  > - Add `AtomicInteger loadOrAddCount` (counter for `loadOrAdd`
  >   invocations).
  > - Add `volatile boolean loadOrAddReturnsNull` + a
  >   `setLoadOrAddReturnsNull(boolean)` setter so a test can force
  >   the totality contract violation that Track 1's
  >   `LockFreeReadCache.doLoad` is supposed to surface as
  >   `IllegalStateException` (see assertion at the `loadOrAdd` call
  >   site).
  > - Add `volatile boolean storeBlocks` + a `setStoreBlocks(boolean)`
  >   setter on the `store(...)` method so Step 6's flush-worker
  >   concurrency scenarios can hold a store until the test releases
  >   it. The toggle is wired here so Step 4 and Step 6 can both
  >   consume it.
  >
  > **Fail-fast regression test.** Add a JUnit 4 test that flips
  > `setLoadOrAddReturnsNull(true)`, calls
  > `cache.loadOrAddForWrite(...)` for a fresh
  > `(fileId, pageIndex)`, and asserts that the read-cache layer
  > surfaces `IllegalStateException` (via Hamcrest's
  > `assertThrows`-style or JUnit 4 `expected =` annotation;
  > examine Track 1's existing tests for the local idiom).
  >
  > **Coverage gate smoke check.** Run
  > `./mvnw -pl core clean package -P coverage` plus
  > `python3 .github/scripts/coverage-gate.py
  > --line-threshold 85 --branch-threshold 70
  > --compare-branch origin/develop
  > --coverage-dir .coverage/reports` and verify the gate reports
  > "No changed Java files found. Skipping coverage gate." (or
  > equivalent skip verdict) — confirming this step stays tests-only.
  >
  > **Key files:** `LockFreeReadCacheBatchingTest.java`,
  > `LockFreeReadCacheConcurrentTestIT.java`.

- [ ] Step: `WOWCacheLoadOrAddTest` gap fill — `verifyChecksums=true` parity, intermediate gap-page accessibility, very-high pageIndex boundary, scenario 6 idempotency *(parallel with Step 3)*
  > **Risk:** low — tests-only, single test class
  > (`WOWCacheLoadOrAddTest`), not shared infrastructure.
  >
  > **Goal.** Close the disk-engine functional gaps Track 1's Step 2
  > deferred to this track.
  >
  > **Tests to add (all in `WOWCacheLoadOrAddTest`):**
  > - `verifyChecksums=true` parity on the load branch (after a flush,
  >   load an on-disk page with `verifyChecksums=true`; asserting no
  >   exception on a clean page and `StorageException` on a corrupted
  >   page, mirroring the legacy `WOWCache.load`-with-checksum
  >   behaviour).
  > - `verifyChecksums=true` parity on the one-page extend branch.
  > - `verifyChecksums=true` parity on the recovery-only gap-fill
  >   branch.
  > - Intermediate gap-page accessibility: after a gap-fill from
  >   `currentSize=2` to `target=10`, assert each intermediate
  >   pageIndex in `[2,10]` can be subsequently loaded via
  >   `loadOrAdd` (load branch) and returns a magic-stamped buffer
  >   matching today's `EnsurePageIsValidInFileTask` contract.
  > - Very-high `pageIndex` boundary (gap-fill against a pageIndex
  >   sized for ≥ `(Integer.MAX_VALUE / pageSize) - 2` if feasible
  >   without OOM; otherwise document the practical bound in the
  >   episode).
  > - Scenario 6: idempotency of `EnsurePageIsValidInFileTask` —
  >   single-threaded sequence assertion. Extend the file via
  >   `loadOrAdd(fileId, pageIdx, false)`; then call
  >   `wowCache.writeValidPageInFile(intId, pageIdx)` directly twice
  >   in sequence and assert exactly one underlying disk write
  >   occurs (the second call short-circuits on the
  >   `getUnderlyingFileSize() <= pagePosition` guard in
  >   `WOWCache.writeValidPageInFile`). Measure the disk write count
  >   via either `AsyncFile.size()` deltas or by reading the file
  >   size pre/post each invocation.
  >
  > **Test infrastructure.** Use the existing fixture pattern from
  > `WOWCacheLoadOrAddTest`. Single-threaded; no concurrency
  > primitives.
  >
  > **Key files:** `WOWCacheLoadOrAddTest.java`.

- [ ] Step: `DirectMemoryOnlyDiskCacheLoadOrAddTest` gap fill — `verifyChecksums=true` parity, truncate-vs-loadOrAdd same-instance race, target-publish stress, framePool leak accounting, iteration-counter assertion, and `DirectMemoryOnlyDiskCache.loadIfPresent` UOE smoke test *(parallel with Step 2)*
  > **Risk:** low — tests-only, single test class
  > (`DirectMemoryOnlyDiskCacheLoadOrAddTest`), not shared
  > infrastructure.
  >
  > **Goal.** Close the in-memory engine functional + MT gaps Track 1's
  > Step 3 deferred to this track.
  >
  > **Tests to add or refactor (all in
  > `DirectMemoryOnlyDiskCacheLoadOrAddTest`, plus the UOE smoke test
  > which may go into a sibling location):**
  > - `verifyChecksums=true` parity on the load branch.
  > - `verifyChecksums=true` parity on the gap-fill branch. (The
  >   existing test pins only the extend branch.)
  > - Truncate-vs-loadOrAdd **same-instance** race: replace the
  >   existing `deleteFile + addFile` rotation in
  >   `clearAndLoadOrAddRaceLeavesCacheConsistent` (or add a sibling
  >   test) with a `truncateFile`-only rotation so the
  >   `clearLock` discipline is actually exercised against
  >   `loadOrAdd` on the **same** `MemoryFile` instance.
  > - Target-publish stress: take the existing 16-thread
  >   `pool.invokeAll` install test and re-tune so all 16 threads
  >   target the **same** pageIndex (rather than distinct indices)
  >   — this stresses the `putIfAbsent` race and the
  >   `decrementReferrer`-on-loss path in `MemoryFile.installEmptyPage`.
  > - framePool leak accounting: snapshot the `framePool`'s
  >   acquired-frame count before and after the target-publish
  >   stress; assert no net growth. Falsifiable: deleting the
  >   loser-side `decrementReferrer` call surfaces here.
  > - Iteration-counter assertion on the existing
  >   `clear()`-vs-`loadOrAdd` race test: increment a counter
  >   inside the installer loop; assert the counter ≥ a minimum
  >   threshold so the test cannot silently no-op under scheduler
  >   drift (Track 1 Step 3 iter-2 item TB-9).
  > - `DirectMemoryOnlyDiskCache.loadIfPresent` UOE-throw smoke
  >   test (single assertion; may go into a new
  >   `DirectMemoryOnlyDiskCacheLoadIfPresentTest` class or into
  >   the existing test class — implementer chooses based on what
  >   fits the local style).
  >
  > **Test infrastructure.** Use `CountDownLatch` / `CyclicBarrier`
  > + `pool.invokeAll` per Track 1's pattern. 8 threads per MT
  > scenario; 100-1000 iterations per worker; `@Test(timeout =
  > 60_000)`; progress log per 100 iterations.
  >
  > **Key files:** `DirectMemoryOnlyDiskCacheLoadOrAddTest.java`,
  > possibly new `DirectMemoryOnlyDiskCacheLoadIfPresentTest.java`.

- [ ] Step: `LockFreeReadCache` wrapper functional test build-out — eviction-with-dirty/clean, pinned entries, write-back on eviction, two-tier transitions
  > **Risk:** medium — shared test infrastructure: extends
  > `LockFreeReadCacheBatchingTest`, which contains
  > `MockedWriteCache` (the mock used by multiple test classes).
  >
  > **Goal.** Build out the wrapper-level functional coverage Track 1
  > only smoke-tested. The MT scenarios 4-5 (eviction-vs-load with
  > flush-worker concurrency) move to Step 6; this step is
  > single-threaded functional coverage of the wrapper contract.
  >
  > **Tests to add to `LockFreeReadCacheBatchingTest` (or a new
  > sibling class if size warrants):**
  > - `LockFreeReadCache.loadForRead` cache hit (data.get fast path
  >   hits an existing entry); cache miss (data.compute lambda
  >   delegates to `writeCache.loadOrAdd`); read-path totality
  >   assertion (no markAllocated flag set on the miss path).
  > - `LockFreeReadCache.loadOrAddForWrite` cache hit / miss /
  >   markAllocated branch under extend; the markAllocated test
  >   parameters fixed in Step 1 cover the read-vs-write contract,
  >   so this step adds the **write-load extend / load-existing
  >   /** boundary tests pinning the contract exhaustively.
  > - Eviction with dirty entries: pin two dirty entries, force
  >   eviction by overflowing the cache; assert dirty entries are
  >   flushed-then-evicted (not silently dropped).
  > - Eviction with clean entries: same setup with clean entries;
  >   assert eviction happens without a flush call to
  >   `writeCache.store` (use `MockedWriteCache.storeCount`).
  > - Pinned entries: pin an entry via `acquirePin`; force eviction
  >   pressure; assert the pinned entry is retained.
  > - Write-back on eviction: simulate eviction of a dirty entry
  >   and assert `writeCache.store` was called exactly once before
  >   eviction.
  > - Two-tier transitions: load entries to fill the protected and
  >   probation tiers; verify a probation-tier hit promotes to
  >   protected and a stale protected entry demotes correctly.
  >
  > **Test infrastructure.** Build on `MockedWriteCache`. Single-
  > threaded; no concurrency primitives needed for these tests
  > (concurrency moves to Step 6).
  >
  > **Key files:** `LockFreeReadCacheBatchingTest.java`.

- [ ] Step: Disk-engine MT stress harness — scenarios 1-3 (different/same key contention, reader-vs-writer), scenario 7 (delete/truncate races), I4 negative defense, and `loadIfPresent` MT coverage
  > **Risk:** medium — touches the JVM-singleton
  > `wowCacheFlushExecutor` heavily under concurrent extend pressure.
  > A flaky or hung test starves every other WOWCache test in the
  > same Surefire fork; this is the highest-flakiness step in the
  > track. Override from `low` (tests-only single-class default)
  > because the JVM-singleton dependency makes this effectively
  > shared infrastructure.
  >
  > **Goal.** Add the disk-engine MT scenarios. In-memory parallels
  > are already covered by Track 1's
  > `DirectMemoryOnlyDiskCacheLoadOrAddTest` and are NOT duplicated
  > here.
  >
  > **Tests to add** — new MT test class (suggested name
  > `WOWCacheLoadOrAddConcurrentTest` or extend
  > `WOWCacheLoadIfPresentTest`; implementer chooses):
  > - **Scenario 1** (different keys): 8 threads each calling
  >   `LockFreeReadCache.loadOrAddForWrite(fileId, distinctPageIndex)`
  >   in parallel; assert no exceptions, `AsyncFile.size` is
  >   consistent post-run, every returned `CacheEntry` has a
  >   distinct `CachePointer`, no double-allocation.
  > - **Scenario 2** (same key): 8 threads contending on
  >   `(fileId, pageIndex=1)`; assert segment lock serializes — one
  >   thread takes the extend branch, the other seven take the
  >   read-fast-path; all observe the same `CachePointer`.
  > - **Scenario 3** (reader at K-1 vs writer extending to K):
  >   thread A holds a read on pageIndex K-1; thread B drives the
  >   extension to K; assert thread A's read is unaffected
  >   (`CachePointer` validity, no NPE).
  > - **Scenario 7** (`deleteFile` / `truncateFile` vs `loadOrAdd`
  >   on the disk engine): 8 installer threads in a tight
  >   `loadOrAddForWrite` loop; in parallel, periodically issue
  >   `wowCache.deleteFile(fid)` + `wowCache.addFile(...)` (or
  >   `truncateFile`); assert each installer call either succeeds
  >   cleanly (the destructive op waited for `filesLock.writeLock`)
  >   or surfaces `IllegalArgumentException` from the dispatch
  >   prelude — no other exception type.
  > - **I4 negative defense (cache-internal):** drive two threads
  >   directly into bare `WOWCache.loadOrAdd(fileId, samePageIndex)`
  >   (bypassing `LockFreeReadCache.data.compute`) and assert at
  >   least one observes the `IllegalStateException("allocated
  >   pageIndex … does not match")` from the extend branch's
  >   I4-sentinel throw. Pins that the cache-layer fast-fail wired
  >   in Track 1 Step 2 stays falsifiable.
  > - **`loadIfPresent` MT coverage:** (a) concurrent
  >   `loadIfPresent` vs eviction (re-probe after eviction returns
  >   null without extending the file); (b) concurrent
  >   `loadIfPresent` vs `loadOrAddForWrite` contention on the same
  >   key (both threads observe a consistent post-state — either
  >   loadIfPresent saw the entry and got the cached `CachePointer`,
  >   or loadIfPresent missed and the extend happened first).
  >
  > **Test infrastructure.** 8 threads per scenario; 100-1000
  > iterations per worker; `@Test(timeout = 60_000)`; one progress
  > log per 100 iterations; `pool.invokeAll` so every Future is
  > in-hand before shutdown; no `Thread.sleep` for correctness.
  > Tests run sequentially within the new test class (no
  > `@RunWith(Parallel)`) to avoid double-saturation of the
  > singleton flush executor.
  >
  > **Key files:** `WOWCacheLoadOrAddConcurrentTest.java` (new) or
  > extensions to `WOWCacheLoadIfPresentTest.java`.

- [ ] Step: Wrapper-level MT stress — scenarios 4-5 (eviction-vs-load, flush-worker concurrency) on `MockedWriteCache`
  > **Risk:** medium — shared test infrastructure: builds on
  > `MockedWriteCache` and its Step-1 extensions
  > (`setStoreBlocks` toggle). Tests-only.
  >
  > **Goal.** Add MT scenarios 4-5 using the mock-based read-cache
  > harness, where deterministic control is possible without
  > touching the JVM-singleton executor.
  >
  > **Tests to add** to `LockFreeReadCacheBatchingTest` (or a new
  > MT-focused sibling class):
  > - **Scenario 4** (eviction-vs-load): two threads each calling
  >   `loadForRead` / `loadOrAddForWrite` while the cache is sized
  >   small enough to force WTinyLFU eviction. Assert: pin counts
  >   respected (a pinned entry is not evicted while held); dirty
  >   pages flushed via `writeCache.store` before eviction; no
  >   lost writes (record final `MockedWriteCache.storeCount` and
  >   compare to the expected dirty count).
  > - **Scenario 5** (flush-worker concurrency): flip
  >   `setStoreBlocks(true)` so `MockedWriteCache.store(...)` holds
  >   on a `CountDownLatch` until the test releases it. Drive a
  >   dirty entry into the eviction path; while the store is
  >   pending, have a reader thread call `loadForRead` on the same
  >   `(fileId, pageIndex)`; assert the reader observes a
  >   consistent state (either the cached entry under read lock,
  >   or a fresh load after the entry is fully evicted) — no
  >   torn read, no double-flush.
  >
  > **Test infrastructure.** 8 threads per scenario; 100-1000
  > iterations per worker; `@Test(timeout = 60_000)`; progress log
  > per 100 iterations; `pool.invokeAll`; latch-coordinated
  > release of the blocking `store(...)` mock.
  >
  > **Key files:** `LockFreeReadCacheBatchingTest.java` (or a new
  > sibling class).
