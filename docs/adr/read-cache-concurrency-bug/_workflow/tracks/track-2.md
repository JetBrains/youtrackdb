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
- [ ] Step implementation (3/6 complete)
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
