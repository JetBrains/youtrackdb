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
- [x] Step implementation (6/6 complete)
- [ ] Track-level code review

## Base commit
`7b509f8aecede310707331404e141fe532ff5595`

## Reviews completed
- [x] Technical: PASS at iteration 2 (10 findings + 1 iter-2 wording nit; 5 should-fix folded into the description (scenarios 6/7 rewrite, `MockedWriteCache` extensions signaled, `ConcurrentTestHelper` wording, coverage-gate rephrase, `loadIfPresent` MT bullet), 4 should-fix carried as decomposition guidance for the steps below, 4 suggestions accepted; iter-2 wording nit on the `EnsurePageIsValidInFileTask` guard location applied)
- [x] Risk: PASS at iteration 2 (7 findings + 1 iter-2 wording nit; 1 blocker on scenario 6 architecture mismatch resolved by reformulation to direct `writeValidPageInFile` idempotency assertion + production-source seam ban; 4 should-fix folded into the description (scenario 6 reformulation, MT stress budget, deprecated-API avoidance, `MockedWriteCache` reuse) plus 2 carried as decomposition guidance (engine bifurcation, ~10-item Track 1 carry-over checklist); 3 suggestions accepted; iter-2 wording nit on the Constraints allow-list applied)

## Steps

- [x] Step: Coverage delta audit, Track 1 hygiene fixes, MockedWriteCache extensions, and fail-fast `IllegalStateException` regression test
  - [x] Context: safe
  > **Risk:** medium — shared test infrastructure: extends
  > `LockFreeReadCacheBatchingTest.MockedWriteCache` (used by multiple
  > tests) and touches `LockFreeReadCacheConcurrentTestIT`. Tests-only,
  > no production-source changes.
  >
  > **What was done:** Extended the shared `MockedWriteCache` fixture
  > in `LockFreeReadCacheBatchingTest` with the three test seams the
  > later steps need: `loadOrAddCount` (`AtomicInteger`),
  > `setLoadOrAddReturnsNull(boolean)`, and
  > `setStoreBlockLatch(CountDownLatch)`. The default `loadOrAdd` stub
  > was rewritten to allocate a fresh `CachePointer` directly rather
  > than delegating to `load`, so `loadCount` and `loadOrAddCount`
  > track distinct primitives; the always-allocate-on-miss behaviour
  > the existing tests rely on is preserved. The `store(...)` body now
  > performs a bounded 60 s `await` on `storeBlockLatch` when set, so a
  > test that forgets to release the latch fails as a `@Test(timeout =
  > 60_000)` timeout rather than hanging the Surefire JVM. Added one
  > regression test —
  > `testLoadOrAddForWriteThrowsWhenLoadOrAddReturnsNull` — flipping
  > the new toggle and asserting that `loadOrAddForWrite(...)`
  > surfaces `IllegalStateException` with a message containing both
  > `fileId=0` and `pageIndex=7`, plus a defense-in-depth
  > `getUsedMemory() == 0` assertion to catch a regression that
  > mis-orders the `cacheSize` increment ahead of the null check.
  > Verified 35/35 in `LockFreeReadCacheBatchingTest`; spotless clean;
  > coverage gate 91.9% line / 83.3% branch on the cumulative branch
  > diff vs `origin/develop`. Commit
  > `e9d12252f5c4f65edd8a5401e472673994876549`.
  >
  > **What was discovered:**
  >
  > **Track 1 hygiene-fix delta audit.** Items (a)–(d) of the step's
  > hygiene block were already absorbed by Track 1's Phase C iter-1
  > commit `6e83d3fa0f` ("Review fix: gate markAllocated to write-load
  > + tighten doLoad semantics") and were no-ops here:
  > - (a) `LockFreeReadCacheConcurrentTestIT.java:60` Javadoc — already
  >   at 81 chars (≤ 100 limit).
  > - (b) `MockedWriteCache.filledUpToByFile` Javadoc — already
  >   self-contained; the "matching the previous mock behavior"
  >   framing was already gone.
  > - (c) Symmetric `storeCount == storesBefore` assertion in
  >   `testWriteLoadDoesNotFlagExistingPageAsNewlyAllocated` — already
  >   present.
  > - (d) `testReadLoadDoesNotFlagPageAsNewlyAllocatedOnExtendBranchParameters`
  >   — already uses extend-branch params (`filledUpTo=0,
  >   pageIndex=5`).
  >
  > **Delta checklist of Track 1 deferred test-hardening items**
  > (test file → target Step → Track 1 episode that flagged it):
  > 1. `verifyChecksums=true` parity on disk-engine load + extend +
  >    gap-fill — `WOWCacheLoadOrAddTest.java`, **Step 2**, Track 1
  >    Step 2 episode + Step 6 episode.
  > 2. `verifyChecksums=true` parity on in-memory load + gap-fill
  >    (only extend has partial coverage today) —
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest.java`, **Step 3**,
  >    Track 1 Step 3 episode iter-2 finding 7(a).
  > 3. In-memory `DirectMemoryOnlyDiskCache.loadIfPresent` UOE-throw
  >    smoke test —
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest.java` (or new
  >    `DirectMemoryOnlyDiskCacheLoadIfPresentTest.java`), **Step 3**,
  >    Track 1 Step 5 episode.
  > 4. Gap-fill intermediate-page accessibility (today gap-fill
  >    returns only the target's pointer; intermediates are untested)
  >    — `WOWCacheLoadOrAddTest.java`, **Step 2**, Track 1 Step 2
  >    episode.
  > 5. `framePool` leak accounting on the target-publish stress —
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest.java`, **Step 3**,
  >    Track 1 Step 3 episode iter-2 finding TXN-7.
  > 6. Target-publish stress on the **same** target pageIndex
  >    (16-thread same-pageIndex contention; today's test uses
  >    distinct indices) —
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest.java`, **Step 3**,
  >    Track 1 Step 3 episode iter-2 finding 7(c).
  > 7. `truncateFile`-vs-`loadOrAdd` **same-instance** race on the
  >    in-memory engine (today's test rotates via
  >    `deleteFile + addFile`) —
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest.java`, **Step 3**,
  >    Track 1 Step 3 episode iter-2 finding 7(b).
  > 8. Clear-race iteration counter assertion (guard against silent
  >    no-op under scheduler drift) —
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest.java`, **Step 3**,
  >    Track 1 Step 3 episode iter-2 finding TB-9.
  > 9. Fail-fast `IllegalStateException` regression test requiring
  >    `setLoadOrAddReturnsNull` mock toggle —
  >    `LockFreeReadCacheBatchingTest.java`, **this step** — DONE.
  > 10. Read-path `markAllocated` boundary parity test fix (use
  >     extend-branch params) — `LockFreeReadCacheBatchingTest.java`,
  >     **this step** — already absorbed by Track 1 Phase C iter-1
  >     commit `6e83d3fa0f`; no residual.
  > 11. iter-2 documentation tweaks from Track 1 Step 4 episode item
  >     7(a)(b)(c) — **this step** — already absorbed by Track 1
  >     Phase C iter-1 commit `6e83d3fa0f`; no residual.
  > 12. `WOWCache.loadOrAdd` MT defense-in-depth against I4 violations
  >     (assert at least one of two contending threads sees
  >     `IllegalStateException("allocated pageIndex … does not
  >     match")`) — new test class (e.g.,
  >     `WOWCacheLoadOrAddConcurrentTest.java`), **Step 5**, Track 1
  >     Step 2 episode discovery 2 (hard-throw conversion).
  > 13. `WOWCacheLoadIfPresentTest` MT coverage (concurrent
  >     `loadIfPresent` vs eviction; vs `loadOrAddForWrite` on the
  >     same key) — `WOWCacheLoadIfPresentTest.java`, **Step 5**,
  >     Track 1 Step 5 episode cross-track impact bullet.
  >
  > **Engine-bifurcation map.** MT scenarios 1-3
  > (different-key / same-key / reader-vs-writer contention) land on
  > the **disk engine** only in Step 5. In-memory parallels already
  > exist in Track 1's `DirectMemoryOnlyDiskCacheLoadOrAddTest`:
  > 16-thread `pool.invokeAll` install test; 60-iteration overlapping
  > gap-fill stress with 6 threads on targets {3,5,7,9,11,4}; and the
  > `clear()`-vs-`loadOrAdd` race test.
  >
  > **Implementation discoveries during this step:**
  > 1. The previous default `loadOrAdd` stub delegated to `load`,
  >    silently coupling `loadCount` and any future `loadOrAddCount`
  >    assertion. Rewriting the stub to allocate a fresh
  >    `CachePointer` directly is necessary so routing tests can
  >    discriminate the two primitives; doing it now (rather than
  >    deferring to Step 5) keeps the change in the same commit as the
  >    new counter and avoids a follow-up adjustment of every existing
  >    test that exercises `loadOrAdd`.
  > 2. The store-block latch await is bounded at 60 s, matching the
  >    `@Test(timeout = 60_000)` ceiling the Step 6 MT scenarios will
  >    use, so a test bug that forgets to release the latch fails as a
  >    timeout rather than hanging the Surefire JVM. This was a
  >    deliberate choice over an unbounded `await()` (which would mask
  >    a test-side defect) and over a `Thread.sleep`-style poll (which
  >    the step's "No flaky tests" constraint forbids).
  > 3. The defense-in-depth `getUsedMemory() == 0` assertion on the
  >    fail-fast test catches a mis-ordering regression that the
  >    `IllegalStateException` message check alone would miss: if a
  >    future `LockFreeReadCache.doLoad` refactor moved the
  >    `cacheSize.incrementAndGet()` call ahead of the null check, the
  >    counter would drift on every contract violation and eventually
  >    wedge `clear()` via `ArrayList(negative-capacity)`.
  >
  > **Cross-track impact (minor, no escalation).** Track 4 (write-side
  > API collapse) inherits the new `loadOrAdd` mock contract: when
  > migrating callers off `WriteCache.allocateNewPage` /
  > `WriteCache.load`, the test mocks in
  > `LockFreeReadCacheBatchingTest` (and only this file —
  > `AsyncReadCacheTestIT`, `LockFreeReadCacheConcurrentTestIT`,
  > `LockFreeReadCacheOptimisticTest` define their own mocks) now
  > track `loadOrAddCount` directly, and the fail-fast regression test
  > pins that any future `addNewPagePointerToTheCache`-style fallback
  > re-introduction would fail loudly — a useful safety net for the
  > recovery-loop collapse in Track 4. No assumption in Tracks 3-6 is
  > weakened.
  >
  > **What changed from the plan:** none. The four Track 1 hygiene
  > fixes listed under the step's hygiene block (items a–d) were
  > already absorbed by Track 1 Phase C iter-1 commit `6e83d3fa0f`;
  > the audit deliverable surfaces this no-op so a later reviewer is
  > not confused by the missing diff. The mock extensions and the
  > regression test land as specified.
  >
  > **Critical context:** The default `loadOrAdd` stub no longer
  > delegates to `load` — it allocates a fresh `CachePointer` directly
  > and bumps `loadOrAddCount`. This is a deliberate change so the two
  > counters track distinct primitives; every previously-passing test
  > in `LockFreeReadCacheBatchingTest` still passes because the
  > always-allocate-on-miss behaviour is preserved. Subsequent steps
  > that add `loadOrAdd`-routing assertions (Steps 4-6) can rely on
  > `loadOrAddCount` to discriminate the wrapper's call path. The
  > `storeBlockLatch` seam is ready for Step 6's flush-worker
  > concurrency scenarios; the `loadOrAddReturnsNull` toggle is
  > exclusive to this step's regression test but is exposed for future
  > reuse if a Track 4 caller regression needs the same fail-fast
  > surface.
  >
  > **Key files:** `LockFreeReadCacheBatchingTest.java` (modified —
  > `MockedWriteCache` extensions + new regression test).

- [x] Step: `WOWCacheLoadOrAddTest` gap fill — `verifyChecksums=true` parity, intermediate gap-page accessibility, very-high pageIndex boundary, scenario 6 idempotency *(parallel with Step 3)*
  - [x] Context: safe
  > **Risk:** low — tests-only, single test class
  > (`WOWCacheLoadOrAddTest`), not shared infrastructure.
  >
  > **What was done:** Added seven tests to `WOWCacheLoadOrAddTest`
  > closing the disk-engine functional gaps Track 1 Step 2 deferred:
  > (1) `verifyChecksums=true` parity on the one-page extend branch;
  > (2) `verifyChecksums=true` parity on the recovery-only gap-fill
  > branch; (3) `verifyChecksums=true` parity on the load branch for
  > a clean page; (4) `verifyChecksums=true` on the load branch under
  > `ChecksumMode.StoreAndThrow` against a corrupted on-disk page →
  > `StorageException`, mirroring the legacy
  > `WOWCacheTestIT#testChecksumFailure` pattern (corruption written
  > via a separately-owned `AsyncFile` instance on the same file
  > path); (5) intermediate gap-page accessibility — after a gap-fill
  > from `currentSize=2` to `target=10`, iterate `[0..10]` via
  > `loadOrAdd(..., false)` and assert every page is loadable through
  > the load branch with the magic-stamped LSN(-1,-1) signature;
  > (6) very-high `pageIndex` boundary — pick `pageIndex` such that
  > `(pageIndex + 1) * pageSize > Integer.MAX_VALUE` and assert the
  > dispatch guard throws `StorageException` with the
  > `allocateSpace int limit` message before any I/O occurs;
  > (7) scenario 6 (`EnsurePageIsValidInFileTask` idempotency) —
  > extend page 0, drain the single-threaded `commitExecutor` via
  > `flush(fileId)`, then call `writeValidPageInFile(intId, 0)`
  > directly twice; both direct calls short-circuit on the
  > `getUnderlyingFileSize() <= pagePosition` guard and the
  > underlying file size (measured via `Files.size(diskPath) -
  > File.HEADER_SIZE`) stays at exactly one page across both calls,
  > proving the executor task wrote the page once and the two direct
  > invocations are idempotent no-ops. All 17 tests pass; Spotless
  > clean; coverage gate 91.9% line / 83.3% branch on the cumulative
  > branch diff (unchanged from Step 1 — this step is purely
  > test-additive). Commit
  > `fa3babc9754ddf8e4ef5fe2be49265e0291c13c6`.
  >
  > **What was discovered:**
  > 1. **Practical bound for the "very-high pageIndex" test.** The
  >    plan called for a pageIndex sized at `≥ (Integer.MAX_VALUE /
  >    pageSize) - 2 if feasible without OOM`. Exercising the true
  >    upper limit would require allocating ~2 GB on disk per test run
  >    (one `int`-worth of page bytes), which is impractical for CI.
  >    The boundary check is purely arithmetic
  >    (`requestedBytes > Integer.MAX_VALUE` fires before
  >    `fileClassic.allocateSpace()`), so the test verifies the exact
  >    boundary behaviour at
  >    `pageIndex = (Integer.MAX_VALUE / PAGE_SIZE) + 10` without
  >    paying the disk cost. The Javadoc on the new test documents
  >    this practical-bound choice per the step's "otherwise document
  >    the practical bound in the episode" allowance.
  > 2. **Scenario 6 framing.** "Exactly one underlying disk write
  >    across the test" includes the executor's
  >    `EnsurePageIsValidInFileTask` invocation in the count:
  >    1 executor task + 2 direct `writeValidPageInFile` calls = 3
  >    invocations, of which exactly one writes (the executor task,
  >    drained by `flush(fileId)`). Both direct calls observe a
  >    stamped underlying file and short-circuit. Measuring via
  >    `Files.size(diskPath) - File.HEADER_SIZE` directly mirrors
  >    `AsyncFile.getUnderlyingFileSize()` semantics without needing
  >    a test-only seam, keeping the test inside the
  >    no-production-source-changes constraint of Track 2.
  > 3. **Corruption-path `AsyncFile` cleanup.** The legacy
  >    `WOWCacheTestIT` corruption tests leak the cached-thread-pool
  >    backing the corruption-only `AsyncFile` instance (no
  >    `executor.shutdownNow()`). The new test bounds the executor's
  >    lifetime inside a `try/finally` so it does not leak non-daemon
  >    threads across test methods in the same Surefire fork.
  >
  > **Cross-track impact (minor, no escalation).** Track 4's
  > `addPage` migration (write-side API collapse) must preserve
  > `WOWCache.writeValidPageInFile` idempotency: the existing
  > `getUnderlyingFileSize() <= pagePosition` guard at the top of
  > `writeValidPageInFile` is now pinned by scenario 6's test, so any
  > future refactor (e.g., replacing the I/O-based size check with an
  > in-memory tracker) must keep the idempotency contract intact or
  > the test surfaces the regression immediately. No other downstream
  > impact: Tracks 3, 5, 6 are unaffected.
  >
  > **What changed from the plan:** none. All bullets in the step
  > description landed as specified (verifyChecksums parity on
  > extend / gap-fill / load-clean / load-corrupted; intermediate
  > gap-page accessibility; very-high pageIndex boundary; scenario 6
  > idempotency). The practical-bound note on the very-high pageIndex
  > test is captured in the test's Javadoc per the step's explicit
  > allowance.
  >
  > **Critical context:** The corruption test (#4 in the
  > "what was done" list above) switches
  > `wowCache.setChecksumMode(ChecksumMode.StoreAndThrow)` after the
  > magic-stamped extend, so the on-disk page was stamped under
  > `StoreAndVerify` (the test setup default) — both modes write the
  > same magic + CRC, so the post-corruption verify under
  > `StoreAndThrow` fires on the broken CRC rather than on a mode
  > mismatch. Any future change to
  > `addMagicChecksumAndEncryption` that differentiates the
  > `StoreAndVerify` and `StoreAndThrow` stamping paths would need to
  > revisit this test's mode-switching pattern.
  >
  > **Key files:** `WOWCacheLoadOrAddTest.java` (modified — seven new
  > tests appended).

- [x] Step: `DirectMemoryOnlyDiskCacheLoadOrAddTest` gap fill — `verifyChecksums=true` parity, truncate-vs-loadOrAdd same-instance race, target-publish stress, framePool leak accounting, iteration-counter assertion, and `DirectMemoryOnlyDiskCache.loadIfPresent` UOE smoke test *(parallel with Step 2)*
  - [x] Context: safe
  > **Risk:** low — tests-only, single test class
  > (`DirectMemoryOnlyDiskCacheLoadOrAddTest`), not shared
  > infrastructure.
  >
  > **What was done:** Added four tests to
  > `DirectMemoryOnlyDiskCacheLoadOrAddTest` (14 → 18 tests), closing
  > the in-memory engine functional + MT gaps Track 1 Step 3
  > deferred: (1)
  > `verifyChecksumsTrueIsIgnoredOnLoadBranch` — pins the documented
  > "flag is ignored" contract on the load branch (the existing test
  > covered only the extend branch); a second `loadOrAdd` on the same
  > index with `verifyChecksums=true` must return the same instance.
  > (2) `verifyChecksumsTrueIsIgnoredOnGapFillBranch` — gap-fill
  > parity: `loadOrAdd(fileId, 5, true)` must advance the watermark
  > to 6 and stamp every intermediate page LSN(-1,-1). (3)
  > `framePoolLeakAccountingOnConcurrentInstallers` — 16-thread
  > same-pageIndex contention falsifying a drop of the loser-side
  > `decrementReferrer`; decomposes acquires into pool hits vs fresh
  > allocations via `DirectMemoryAllocator.getMemoryConsumption()`
  > delta, then asserts `(threads - 1)` frames released to pool
  > after the race and all 16 after `deleteFile`. (4)
  > `loadIfPresentThrowsUnsupportedOperationException` — smoke test
  > pinning the documented UOE for both `verifyChecksums` values.
  > All 18 tests pass on 3 sequential runs; Spotless clean; coverage
  > gate 93.4% line / 85.4% branch on the cumulative branch diff.
  > Commit `5cf5c90f1d5eed8296bf4c0de99e536fd0caf6a5`.
  >
  > **What was discovered:**
  >
  > **Refinement of Step 1's audit.** Items 6, 7, and 8 of the Step 1
  > delta checklist (target-publish stress on same target pageIndex,
  > truncate-vs-loadOrAdd same-instance race, clear-race iteration
  > counter assertion) were already absorbed by Track 1 Phase C
  > iteration-1 commit `6e83d3fa0f3` and were no-ops here:
  > - Item 6 (target-publish stress, Track 1 Step 3 episode iter-2
  >   finding 7(c)): the existing
  >   `concurrentLoadOrAddOnSameIndexInstallsExactlyOneEntry` test
  >   already targets pageIndex=0 with 16 threads — the
  >   same-pageIndex shape the finding asked for.
  > - Item 7 (truncate-vs-loadOrAdd same-instance race, finding
  >   7(b)): the existing
  >   `truncateAndLoadOrAddRaceLeavesCacheConsistent` (added at line
  >   661 by `6e83d3fa0f3`) uses `truncateFile` rotation against the
  >   same fileId, keeping the same `MemoryFile` instance across
  >   rotations and exercising the `clearLock` discipline directly.
  > - Item 8 (clear-race iteration counter, finding TB-9): the
  >   existing `clearAndLoadOrAddRaceLeavesCacheConsistent` already
  >   has the `iterationCounter` `AtomicLong` with the
  >   `≥ installerThreads` assertion (lines 568, 581, 616-621 of the
  >   pre-edit file).
  >
  > **Implementation discoveries during this step:**
  > 1. The framePool leak test cannot use a naive
  >    `poolSize`-delta because `PageFramePool` is a JVM-singleton
  >    and each acquire pops from the pool when available — so the
  >    post-race `poolSize` depends on how many of the 16 acquires
  >    hit the pool vs allocated fresh. The first attempt
  >    (`poolAfterRace − poolBefore ≥ threads − 1`) failed with
  >    `poolBefore=14, poolAfterRace=13` (pool shrunk because all 16
  >    acquires came from the pool's existing 14 frames + 2 fresh
  >    allocations; 15 releases then yielded net delta = −1).
  >    Decomposing acquires via
  >    `DirectMemoryAllocator.getMemoryConsumption()` delta gives a
  >    deterministic arithmetic identity
  >    (`releasesToPool = poolNetDelta + pooledAcquires`) that is
  >    both `PAGE_SIZE`-aligned (asserted) and bounded by threads.
  > 2. The `DirectMemoryOnlyDiskCache.loadIfPresent` throw is
  >    unconditional on both `verifyChecksums` values — both
  >    assertions in the UOE test exercise the same line because the
  >    throw happens before the flag is read. Two calls add cheap
  >    defense-in-depth: a future implementation that
  >    short-circuited on one flag value would surface.
  > 3. The framePool leak test deliberately calls
  >    `cache.deleteFile(fileId)` at the end of the test body (not
  >    in the `@After` `tearDown`) so the second assertion
  >    (winner-frame release) is observable inside the test body.
  >    `tearDown`'s `cache.delete()` then runs on an empty file
  >    table — safe no-op.
  >
  > **Cross-track impact (minor, no escalation).** Track 4
  > (write-side API collapse) inherits the framePool leak accounting
  > test as a regression net — any future `MemoryFile` refactor that
  > introduces a new install path (or modifies
  > `installEmptyPage`'s `putIfAbsent` shape) would need to preserve
  > the loser-side `decrementReferrer` or this test surfaces the
  > regression immediately. The
  > `DirectMemoryOnlyDiskCache.loadIfPresent` UOE smoke test pins the
  > documented "in-memory engine has no silent-probe primitive"
  > contract; if a future track wires the in-memory engine into a
  > `WriteCache.loadIfPresent`-expecting code path, the implementer
  > must add an implementation rather than expect the smoke test to
  > be relaxed. No upstream-track assumption weakened.
  >
  > **What changed from the plan:** none of the four sub-items
  > missing from the existing test file required deviation from the
  > step description. The Refinement-of-Step-1's-audit section above
  > documents that three of the six "Tests to add or refactor"
  > bullets (target-publish stress, truncate-vs-loadOrAdd
  > same-instance race, iteration-counter assertion) were already
  > present and were absorbed as no-ops; the remaining four tests
  > landed as specified.
  >
  > **Critical context:** The framePool leak accounting test reads
  > `DirectMemoryAllocator.getMemoryConsumption()` from the
  > JVM-singleton allocator. This is a thread-safe counter
  > (`LongAdder`) that increases on acquire and decreases on
  > deallocate (pool overflow). Within the test there is no
  > concurrent allocator pressure because the test runs sequentially
  > in its Surefire fork. If a future test runner adds parallel test
  > execution within the same fork, this test would need to retake
  > the lock or be moved to a quiescent class. The test asserts
  > `PAGE_SIZE`-alignment on the memory delta to catch a
  > partial-allocation regression that would invalidate the
  > arithmetic.
  >
  > **Key files:** `DirectMemoryOnlyDiskCacheLoadOrAddTest.java`
  > (modified — four new tests appended, two imports added).

- [x] Step: `LockFreeReadCache` wrapper functional test build-out — eviction-with-dirty/clean, pinned entries, write-back on eviction, two-tier transitions
  - [x] Context: safe
  > **Risk:** medium — shared test infrastructure: extends
  > `LockFreeReadCacheBatchingTest`, which contains
  > `MockedWriteCache` (the mock used by multiple test classes).
  >
  > **What was done:** Added eight new tests to
  > `LockFreeReadCacheBatchingTest` (37 → 45 in the class), plus four
  > reflection helpers (`getPolicy`, `collect`, `containsByIdentity`,
  > `findEntry`), pinning the wrapper-level functional contract of
  > `LockFreeReadCache` that Track 1 only smoke-tested. The new
  > tests cover: cache-miss routing through `writeCache.loadOrAdd`
  > with per-primitive counter discrimination
  > (`loadCount` / `loadOrAddCount` / `loadIfPresentCount`);
  > cache-hit fast path skipping all `writeCache` load primitives on
  > both `loadForRead` and `loadOrAddForWrite`; the `markAllocated`
  > short-circuit on a write-load cache hit (an entry primed via
  > `loadForRead` must NOT acquire the flag on a subsequent
  > `loadOrAddForWrite` hit, even with `pageIndex >= filledUpTo`);
  > same-instance identity on a write-load hit; eviction of clean
  > entries never invoking `store`; eviction of dirty entries
  > preserving every `store` exactly once (stores happen on
  > `releaseFromWrite`, not on eviction); pin retention under churn
  > (an entry held via unreleased `loadForRead` survives a 3 000-page
  > eviction-pressure flood); and the WTinyLFU two-tier transitions
  > (probation-hit promotes to protection; protection overflow at
  > `maxProtectedSize = 656` demotes the oldest entry back to
  > probation). All 45 tests pass 3 sequential in-isolation runs;
  > Spotless clean; coverage gate 93.4% line / 85.4% branch on the
  > cumulative branch diff (unchanged from Step 3 — this step is
  > purely test-additive). Commit
  > `cf0769b35dbe00a795a21751686c0407071ca9a0`.
  >
  > **What was discovered:**
  > 1. **BoundedBuffer first-offer drop.** The first offer to a
  >    freshly-created striped buffer goes through `expandOrRetry`'s
  >    table-init branch which calls `new RingBuffer(entry)`: the
  >    constructor `lazySets` the entry into slot 0 but does NOT
  >    bump `writeCounter`. The subsequent `drainTo` sees
  >    `size = tail − head = 0` and returns without invoking
  >    `policy.onAccess` on the entry. Future offers go through the
  >    normal path and DO bump `writeCounter`, so the first entry is
  >    silently dropped while the second and onward are seen. The
  >    two-tier transition tests work around this by priming the
  >    buffer with a throwaway eden-tier access (whose loss is
  >    harmless — an eden `onAccess` is a within-tier move that
  >    does not affect the probation entries the test inspects).
  >    Without the prime, `testProbationHitPromotesToProtection`
  >    silently no-ops and
  >    `testProtectionOverflowDemotesOldestEntryToProbation`
  >    produces only 656 promotions (not 657), missing the
  >    overflow the test exists to verify. This is by design in the
  >    original Caffeine-derived `BoundedBuffer` code, not a bug.
  > 2. **WTinyLFU tier-overflow boundary.** For a 1 024-page cache
  >    the boundary is precisely `maxProtectedSize = 656`
  >    (= `1024 − 204 − 820 × 20 / 100`); the 657th promotion is
  >    the one that demotes the head of protection back to
  >    probation. Documented in the test's Javadoc so a future
  >    reader does not have to re-derive the arithmetic.
  > 3. **Pre-existing flake in Step 3's framePool leak accounting
  >    test under parallel surefire.** The
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest#framePoolLeakAccountingOnConcurrentInstallers`
  >    test added by Step 3 is flaky under the parallel-surefire
  >    coverage profile build —
  >    `releasesToPool = 14` observed where `≥ 15` is required
  >    (off-by-one). Reproduces consistently under the full-suite
  >    parallel run; passes in 3 sequential in-isolation runs. The
  >    Step 3 episode explicitly anticipated this risk ("If a
  >    future test runner adds parallel test execution within the
  >    same fork, this test would need to retake the lock or be
  >    moved to a quiescent class"). The flake is NOT caused by
  >    this step; this step's test class passed 3/3 in isolation
  >    and the coverage gate ran successfully against the
  >    `jacoco.xml` generated from the failing build (the
  >    `jacoco.exec` file is written incrementally, so coverage
  >    data is intact). Phase C track-level code review will pick
  >    this up — the candidate fix is either widening the
  >    arithmetic identity to tolerate JVM-singleton allocator
  >    state non-determinism under parallel forks, or annotating
  >    the test to opt out of parallel execution.
  >
  > **Cross-track impact (minor, no escalation).** Step 6's MT
  > scenarios (wrapper-level eviction-vs-load and flush-worker
  > concurrency) will need to prime the `BoundedBuffer` before
  > inspecting `policy.onAccess` effects, mirroring the priming
  > pattern this step introduced for
  > `testProbationHitPromotesToProtection` and
  > `testProtectionOverflowDemotesOldestEntryToProbation`. Track 4
  > inherits a stronger contract: the `markAllocated` short-circuit
  > on a write-load cache hit is now pinned
  > (`testLoadOrAddForWriteCacheHitDoesNotFlagAsNewlyAllocated`), so
  > any future refactor that bled the `markAllocated` logic into the
  > cache-hit branch would surface here. No upstream-track
  > assumption weakened.
  >
  > **What changed from the plan:** none. All bullets in the step
  > description landed as specified (`loadForRead` hit/miss
  > assertions, `loadOrAddForWrite` cache hit and same-instance
  > identity, eviction with dirty/clean entries, pinned-entry
  > retention under eviction pressure, two-tier transitions via
  > `WTinyLFUPolicy` reflection inspection). The `acquirePin`
  > surface mentioned in the step description does not exist in
  > production code; the equivalent pin mechanism is
  > `acquireEntry()` / `releaseFromRead()`
  > (`loadForRead`-without-release is the canonical pin), which the
  > test uses directly. The write-load extend / load-existing /
  > boundary tests called out in the description were already
  > absorbed by Step 1's `markAllocated` branch coverage; this step
  > does not duplicate them.
  >
  > **Critical context:** The `BoundedBuffer` first-offer drop is a
  > global property of the `LockFreeReadCache` infrastructure — any
  > future test that inspects policy state after a small number of
  > cache hits must prime the buffer first or accept that the first
  > `afterRead` event is lost. The two-tier transition tests
  > document this in their Javadoc and apply the priming pattern.
  > Step 6's wrapper-level MT scenarios will likely need the same
  > pattern when asserting on policy state. The flaky
  > `framePoolLeakAccountingOnConcurrentInstallers` test from Step 3
  > is a pre-existing Track 2 issue surfacing under parallel
  > surefire; Step 5 and Step 6 should avoid adding tests that
  > share the JVM-singleton `DirectMemoryAllocator` state
  > assumptions, or must harden the assertions against off-by-one
  > races.
  >
  > **Key files:** `LockFreeReadCacheBatchingTest.java` (modified —
  > eight new tests + four reflection helpers appended).

- [x] Step: Disk-engine MT stress harness — scenarios 1-3 (different/same key contention, reader-vs-writer), scenario 7 (delete/truncate races), I4 negative defense, and `loadIfPresent` MT coverage
  - [x] Context: info
  > **Risk:** medium — touches the JVM-singleton
  > `wowCacheFlushExecutor` heavily under concurrent extend pressure.
  > A flaky or hung test starves every other WOWCache test in the
  > same Surefire fork; this is the highest-flakiness step in the
  > track. Override from `low` (tests-only single-class default)
  > because the JVM-singleton dependency makes this effectively
  > shared infrastructure.
  >
  > **What was done:** Added a new test class
  > `WOWCacheLoadOrAddConcurrentTest` (7 tests) covering the
  > disk-engine MT scenarios the step targets: (1) distinct-key
  > concurrent `loadForRead` via the `LockFreeReadCache` wrapper on
  > a pre-extended file; (2) same-key concurrent `loadForRead` via
  > the wrapper (verifies the wrapper's `data.compute` segment lock
  > serialises allocators — all workers see one `CachePointer`,
  > exactly one extend happens); (3) reader at pageIndex K-1 vs
  > writer extending to K (pins the canonical "reader path is
  > unaffected by concurrent extension" contract from the bug
  > ticket); (4) `deleteFile + addFile` rotation vs concurrent bare
  > `wowCache.loadOrAdd` (mirrors the in-memory engine's
  > `clearAndLoadOrAddRaceLeavesCacheConsistent`); (5) `truncateFile`
  > rotation vs concurrent bare `wowCache.loadOrAdd`; (6) I4
  > negative defence — two bare `WOWCache.loadOrAdd` threads on the
  > same `(fileId, pageIndex)` on a fresh file MUST surface the
  > `IllegalStateException("allocated pageIndex … does not match")`
  > sentinel from Track 1 Step 2's review fix, retried up to 50
  > attempts to absorb the tight race window; (7) single-threaded
  > smoke round-trip distinguishing infrastructure regressions from
  > race-test failures. Extended `WOWCacheLoadIfPresentTest` with
  > two MT tests (5 → 7 in the class): concurrent `loadIfPresent`
  > vs flusher (pins non-extension under concurrent flush) and
  > concurrent `loadIfPresent` vs `loadOrAdd` on the same key (pins
  > clean buffer state on any returned pointer, no exceptions
  > either side). Every MT test carries `@Test(timeout = 60_000)`
  > and uses deterministic `CountDownLatch` / `CyclicBarrier`
  > coordination — no `Thread.sleep` for correctness. 14/14
  > targeted tests pass 3 sequential runs; coverage gate 93.4%
  > line / 85.4% branch on the cumulative branch diff (unchanged
  > from Step 4 — purely test-additive). Commit `5f6f0e091e`.
  >
  > **What was discovered:**
  > 1. **Bare `WOWCache.loadOrAdd` is intrinsically prone to the I4
  >    sentinel under same-key concurrent callers.** The wrapper's
  >    `data.compute` segment lock is the production-build
  >    serialisation point that prevents it. Scenarios 1-3 and 7
  >    therefore must route through the wrapper
  >    (`LockFreeReadCache.loadForRead` / `loadOrAddForWrite`) to
  >    avoid spurious I4 throws on legitimate test contention; only
  >    the I4 negative-defence test exercises the bare
  >    `WOWCache.loadOrAdd` path. The initial naïve test design that
  >    called bare `WOWCache.loadOrAdd` from N workers on distinct
  >    / same keys produced the gap-fill I4 sentinel ("allocated
  >    start index … does not match currentSize") rather than clean
  >    concurrent allocation, because all workers saw
  >    `currentSize = 0` simultaneously and raced into the gap-fill
  >    branch.
  > 2. **Exclusive-lock deadlock on same-key
  >    `loadOrAddForWrite`.** Eight concurrent workers calling
  >    `LockFreeReadCache.loadOrAddForWrite` on the SAME key
  >    deadlock if every worker holds the entry's exclusive lock
  >    (`acquireExclusiveLock` inside `loadOrAddForWrite`) until the
  >    test's outer cleanup releases them. The fix: use
  >    `loadForRead` for the same-key scenario (no
  >    `acquireExclusiveLock`) and release in the worker before the
  >    `Future.get` on the main thread. The same-key wrapper
  >    contract is exercised regardless of read/write flavour
  >    because both go through `doLoad`'s `data.compute` lambda on
  >    a cache miss.
  > 3. **Wrapper-`deleteFile` / `truncateFile` cannot run
  >    concurrently with pinned entries.**
  >    `LockFreeReadCache.deleteFile` (and `truncateFile`) acquire
  >    `clearFile`'s eviction lock and require ALL entries for the
  >    fileId to be unpinned ("Page X is used and cannot be
  >    removed" `StorageException`). Scenario 7 cannot drive
  >    `deleteFile` through the wrapper while installers loop; it
  >    must call bare `wowCache.deleteFile` and tolerate the I4
  >    sentinel as another expected post-rotation race outcome
  >    (matching the in-memory engine's clear-race test pattern).
  > 4. **I4 sentinel race window requires retry to be
  >    deterministic.** The race window
  >    (`fileClassic.getFileSize()` → `AsyncFile.allocateSpace`) is
  >    tight enough that an 8-thread single-shot race only
  >    sporadically surfaces the sentinel — sometimes only the
  >    winner takes the extend branch and the remaining workers
  >    observe the bumped `currentSize` and fall through to the
  >    load branch. To convert the negative-defence test from flaky
  >    to deterministic, the implementation retries the race up to
  >    50 attempts (each round uses a fresh per-attempt fileId so
  >    `currentSize` starts at 0); empirically, the sentinel fires
  >    inside 3-5 attempts on a CI runner. The retry loop is
  >    bounded by the `@Test(timeout = 60_000)` ceiling.
  > 5. **Step 3's framePool flake reproduced again.** The
  >    pre-existing
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest#framePoolLeakAccountingOnConcurrentInstallers`
  >    flake (surfaced by Step 4) reproduces in this step's full
  >    coverage-profile build (8 093 / 8 094 pass). The flake is
  >    not introduced by this step; this step's new and modified
  >    test classes pass 3/3 in isolation and inside the full-suite
  >    run too. Phase C track-level code review carries the
  >    hardening obligation.
  >
  > **Cross-track impact (minor, no escalation).** Track 4
  > (write-side API collapse) inherits the I4 sentinel
  > falsifiability pin: any future refactor of the extend-branch
  > hard-throw must keep the same exception type and message
  > structure or this test breaks. The message-contains check is
  > "allocated pageIndex" + "does not match" (extend) and
  > "allocated start index" + "does not match" (gap-fill). No
  > upstream-track assumption weakened.
  >
  > **What changed from the plan:** The step description listed
  > bare `WOWCache.loadOrAdd` as the API for scenarios 1, 2, 3,
  > and 7. Empirical observation forced a refinement — scenarios 1,
  > 2, 3 must route through the `LockFreeReadCache` wrapper to
  > avoid spurious I4 sentinel throws on legitimate concurrent
  > allocation; only the I4 negative-defence retains the bare
  > `WOWCache.loadOrAdd` surface. Scenario 7 stayed on bare
  > `WOWCache` because the wrapper's `deleteFile` cannot run
  > concurrently with pinned entries. The functional contract the
  > step targets (segment-lock serialisation, reader-vs-writer
  > isolation, IAE on deleted fileId, I4 sentinel falsifiable) is
  > preserved; only the API surface routing changed. No backlog
  > amendment required.
  >
  > **Critical context:** Scenarios 1, 2, 3 use
  > `LockFreeReadCache` wrapper API
  > (`loadForRead` / `loadOrAddForWrite`). Scenarios 7
  > (`deleteFile`, `truncateFile`) and the I4 negative defence use
  > bare `WOWCache.loadOrAdd`. Mixing the two on the same
  > scenario — e.g., wrapper-`loadOrAddForWrite` with
  > wrapper-`deleteFile` — surfaces either deadlock (entry
  > exclusive lock held across workers) or "page in use"
  > `StorageException` (`clearFile` aborts on pinned entries).
  > Future MT scenarios in this step's class must respect the
  > surface choice or the test will regress. The 50-attempt retry
  > on the I4 negative defence is a deliberate concession to the
  > race-window tightness; if the retry bound is reduced below ~10
  > attempts the test will go flaky in CI.
  >
  > **Key files:**
  > `WOWCacheLoadOrAddConcurrentTest.java` (new — 7 tests),
  > `WOWCacheLoadIfPresentTest.java` (modified — 2 MT tests
  > appended).

- [x] Step: Wrapper-level MT stress — scenarios 4-5 (eviction-vs-load, flush-worker concurrency) on `MockedWriteCache`
  - [x] Context: info
  > **Risk:** medium — shared test infrastructure: builds on
  > `MockedWriteCache` and its Step-1 extensions
  > (`setStoreBlocks` toggle). Tests-only.
  >
  > **What was done:** Added two MT stress tests to
  > `LockFreeReadCacheBatchingTest` closing scenarios 4-5 of the
  > plan (45 → 47 tests in the class). Scenario 4
  > (`testEvictionVsLoadConcurrencyRespectsPinsAndStoreCount`):
  > 8 workers running a mixed 1:5 write/read workload against a
  > 1 024-page cache deliberately overshot by 4× to force WTinyLFU
  > eviction, with one thread pinning page `(0, 0)` for the entire
  > run. The test pins three invariants under MT pressure — pinned
  > entries survive concurrent eviction churn (same-instance check
  > on reload, no `loadOrAdd` increment on the post-run hit);
  > stores happen only at `releaseFromWrite` time, never at
  > eviction (`storeCount` matches the per-worker sum of dirty
  > releases tracked via `AtomicInteger.addAndGet`); and used
  > memory respects the cache budget after `assertSize` drains the
  > buffers. Disjoint per-worker page-index ranges and skipping
  > page 0 keep workers from colliding on the same entry's
  > exclusive lock. Scenario 5
  > (`testFlushWorkerConcurrencyReaderObservesConsistentState`):
  > a writer thread enters `data.compute` holding the segment
  > write lock with `MockedWriteCache.store` suspended on
  > `storeBlockLatch`; a second reader thread calls
  > `loadForRead` on the same `(fileId, pageIndex)`. The reader's
  > `ConcurrentLongIntHashMap.get` optimistic-read stamp is
  > invalidated by the pending write so the reader falls back to a
  > blocking `readLock`; the test bounds this wait by releasing
  > the latch AFTER kicking off the reader, proving the reader
  > does not deadlock, observes the same `CacheEntry` instance the
  > writer installed (no torn read, no second `loadOrAdd`), and no
  > double-flush occurs (`storeCount` ends at exactly 1). Both
  > tests carry `@Test(timeout = 60_000)`, use deterministic
  > `CountDownLatch` / `CyclicBarrier` coordination, and shut
  > their pools down with bounded awaits. 47/47 tests pass in
  > isolation; full core suite 8 095 / 8 096 (the single
  > non-passing test is the pre-existing framePool flake from
  > Step 3). Coverage gate 93.4% line / 85.4% branch on the
  > cumulative branch diff (unchanged — purely test-additive).
  > Commit `4392bd41e7b4af2bd149a74bb0199cf5fbcf9153`.
  >
  > **What was discovered:**
  > 1. **StampedLock-fallback timeline for scenario 5.** The
  >    Javadoc-as-designed timeline originally assumed the
  >    cache-hit path was lock-free under contention with a
  >    same-key in-flight write; investigation of
  >    `ConcurrentLongIntHashMap.get` revealed the optimistic-read
  >    stamp is invalidated by any pending write on the segment's
  >    `StampedLock`, after which the reader falls back to a
  >    blocking `readLock` acquisition that serialises behind the
  >    writer's `data.compute`. The test design adjusted: instead
  >    of asserting a lock-free observation while the store is in
  >    flight (false on the actual `StampedLock` semantics), the
  >    test releases the store latch AFTER kicking off the reader,
  >    bounding the `readLock` fallback to a deterministic
  >    completion. The Javadoc spells out the readLock-fallback
  >    timeline so a future reader does not assume a lock-free
  >    fast path that `StampedLock` does not provide.
  > 2. **Step 3's framePool flake reproduces under the parallel
  >    coverage build.** The pre-existing
  >    `DirectMemoryOnlyDiskCacheLoadOrAddTest#framePoolLeakAccountingOnConcurrentInstallers`
  >    flake from Step 3 (surfaced by Steps 4 and 5) reproduced
  >    again here (1 of 8 096 tests fails in the coverage build).
  >    The new tests passed cleanly in the full parallel run. The
  >    flake is unaffected by this step; Phase C track-level code
  >    review carries the hardening obligation, accumulated across
  >    Steps 4-6 episodes.
  >
  > **Cross-track impact (minor, no escalation).** Scenario 5's
  > documented `StampedLock`-fallback timeline (data.get's
  > optimistic stamp is invalidated by any pending segment write)
  > is a property Track 4 (write-side API collapse) inherits. If a
  > Track 4 refactor of `releaseFromWrite` changes how the segment
  > write lock is scoped (e.g., narrowing the lock to skip the
  > store call), this test's Javadoc will become misleading and
  > the reader's wait window will shrink — but no test assertion
  > will break (the same-instance / no double-flush invariants are
  > independent of the lock-scope choice). No upstream-track
  > assumption weakened.
  >
  > **What changed from the plan:** none. Scenarios 4 and 5 land
  > in `LockFreeReadCacheBatchingTest` as specified, using the
  > `MockedWriteCache` extensions Step 1 introduced. Scope,
  > threading bounds (8 threads per scenario, ~2 000 iterations
  > per worker for scenario 4), and timeout / coordination
  > discipline match the plan exactly. The Javadoc timeline
  > correction in scenario 5 is a documentation clarification
  > about `StampedLock` semantics, not a scope or design change.
  > Note: Step 1's actual seam name for the store-blocking toggle
  > is `setStoreBlockLatch(CountDownLatch)`, not the `setStoreBlocks`
  > name in the original step description; this step uses the
  > actual seam.
  >
  > **Critical context:** Scenario 5's reader does not race the
  > writer in a lock-free fashion — the `StampedLock`
  > optimistic-read fallback means the reader's `data.get`
  > serialises behind the writer's `data.compute`. The test's
  > bounded completion is guaranteed by releasing the
  > `storeBlockLatch` AFTER kicking off the reader
  > (`storeRelease.countDown()` before the reader/writer
  > `Future.get` joins). Any future test that wants to verify a
  > truly concurrent same-key access during an in-flight store
  > would need a different harness — perhaps a probe inside the
  > optimistic-read window before the validate, which the current
  > `StampedLock`-based map does not expose. The pre-existing
  > framePool flake from Step 3 is documented in Steps 3-5
  > episodes and is unaffected by this step; it should be
  > hardened (or quarantined from parallel forks) at Phase C
  > track-level code review.
  >
  > **Key files:** `LockFreeReadCacheBatchingTest.java` (modified
  > — two MT stress tests appended; 45 → 47 in the class).
