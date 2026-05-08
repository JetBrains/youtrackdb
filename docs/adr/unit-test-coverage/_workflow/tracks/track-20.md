# Track 20: Storage Cache & WAL

## Description

Write tests for the write cache (WOWCache), read cache, double-write log,
and WAL components. These are complex concurrent subsystems — expect to
fall short of 85%/70% targets per Decision Record D4 in
`implementation-plan.md` (*Accept lower coverage for storage internals*).

> **Reconstruction note:** the original backlog body for Track 20 was in
> the recovery gap (see `implementation-plan.md` § Operational Notes →
> "Reconstruct-on-demand tracks"). The What/How/Constraints/Interactions
> block below is regenerated from the plan's `**Scope:**` indicator
> (~6 steps covering WOWCache lifecycle, read cache, double-write log,
> WAL segments, cache eviction, and verification), the design document's
> `## Testing Storage & Cache Components` section, the post-Track-7
> coverage baseline, and Track 19's accumulated patterns and findings.
> Phase A reviews (technical, risk, adversarial) refined per-package
> coverage targets, the Step 2 (CAS-WAL) test pattern, and the WOWCache
> concurrency-probe surface for Step 4. Phase B will re-measure
> per-package coverage on entry to absorb post-Track-19 drift before
> per-step decomposition is acted on.

> **What:**
> - Raise per-package coverage on the storage **cache** and **WAL**
>   clusters with **concrete numeric targets per D4**, not aspirational
>   85%/70% with vague fallback. Per-package targets (committed at
>   decomposition; verified at Step 6):
>   - `cache.local` — target **~78% line / ~62% branch** on entry
>     (post-Track-7 baseline 68.5% / 55.2%). The 22-pp gap is bounded
>     by encryption / async-flush / checkpoint paths that are
>     IT-shadowed via `WOWCacheTestIT` (failsafe-only); aspirational
>     85% line is unreachable from surefire scope.
>   - `cache.local.doublewritelog` — target **~70% line / ~52% branch**
>     (baseline 50.2% / 20.9%). Branch ceiling driven by recovery /
>     segment-rotation / seal paths shadowed by `DoubleWriteLogGLTestIT`.
>   - `paginated.wal.cas` — target **~85% line / ~70% branch** on
>     `CASDiskWriteAheadLog` (baseline 76.5% / 59.6%). Achievable via
>     direct-construction precedent (`CASDiskWriteAheadLogCloseTest`).
>   - `paginated.wal` + `wal.common` + `wal.common.deque` — target
>     **~85% line / ~70% branch** (record types are pure POJOs;
>     MPSC deque branches via concurrency smoke tests).
>   - `cache` top-level + `cache.chm` + `cache.chm.readbuffer` +
>     `cache.chm.writequeue` — target **≥85% / ≥70%** (baselines
>     already 76.9% / 59.3% to 96.8% / 87.5%; absolute top-up only).
>   - `cache.local.aoc` — **0% accepted** (the sole class
>     `FileSegment` is dead code with zero project-wide references —
>     PSI-confirmed; forwarded to Track 22 for deletion alongside
>     Track 19's lockstep groups, NOT covered by new tests).
> - Aggregate gap on entry: ~1 476 uncovered lines across 11 packages
>   (post-Track-7 baseline; expect Track-19 incidental drift to be
>   re-measured at Phase B Step 1 start).

> **How:**
> - **At Phase B Step 1 start:** regenerate per-package coverage via
>   `./mvnw -pl core -am clean package -P coverage` +
>   `coverage-analyzer.py` against the fresh
>   `.coverage/reports/youtrackdb-core/jacoco.xml`. The targets above
>   are anchored to the post-Track-7 baseline; Track 19 produced
>   minimal incidental drift in this cluster (current
>   `cache.local` ≈ 68%, unchanged from baseline) but verify before
>   committing per-step strategy.
> - **Build cost discipline:** the coverage build is ~10 min per run.
>   For per-step iteration use `./mvnw -pl core test -Dtest=<NewClass>`
>   (~30 s); only invoke `package -P coverage` once per step at the
>   end to verify the gate. Track 19 ran ~7 coverage cycles total;
>   Track 20 should match (~6 step verifications + 1 Phase C run).
> - **IT-coverage caveat:** `package -P coverage` only runs surefire,
>   not failsafe. IT classes (`WOWCacheTestIT`, `DoubleWriteLogGLTestIT`,
>   `CASDiskWriteAheadLogIT`, `LockFreeReadCacheConcurrentTestIT`,
>   `AsyncReadCacheTestIT`, `WOWCacheFlushIT`, `WOWCacheDeleteTimeoutIT`)
>   contribute **zero** to the JaCoCo XML the analyzer reads. Some
>   apparent gaps in `cache.local` / `wal.cas` are IT-shadowed.
>   Before adding a unit test for a WOWCache or CAS-WAL branch,
>   sanity-check the matching `*TestIT` to see whether it already
>   exercises the path — prefer extracting a page-level slice over
>   reimplementing the IT scenario as a unit test.
> - **Existing direct-construction precedent for cache/WAL surefire
>   tests:**
>   - `WOWCacheNonDurableFileTrackingTest` — surefire, mocks
>     `LockFreeReadCache`, exercises WOWCache via direct construction.
>   - `WOWCacheFlushErrorTest` — surefire, same-package
>     (`storage.cache.local`) test placement to access package-private
>     helpers.
>   - `CASDiskWriteAheadLogCloseTest` — surefire, direct construction
>     of `CASDiskWriteAheadLog` with reflection on internals; registers
>     a test-only WAL record (ID 500). The pattern Track 20 Step 2
>     extends.
>   - `WALRecordsFactoryPageOperationTest`, `PageOperationTest`,
>     `AtomicUnitEndDBRecordTest`, `WALPageV2ChangesPortionTest` —
>     standalone WAL record round-trip tests. The pattern Track 20
>     Step 1 extends.
> - **Test pattern preferences:**
>   - **Standalone JUnit 4** for pure WAL records and helpers — LSN
>     equals/hashCode/compareTo, segment-id arithmetic, record
>     serialization round-trips, double-write-log record types,
>     read-buffer / write-queue data structures.
>   - **`ConcurrentTestHelper` smoke tests** (per design § Testing
>     Concurrency Primitives) for: (a) MPSC deque
>     (`MPSCFAAArrayDequeue`) — ≥4 producers + 1 consumer, < 5 s,
>     `CountDownLatch` synchronisation, no `Thread.sleep`; required
>     to exercise the CAS contention paths that single-threaded
>     standalone tests cannot reach. (b) Two-thread allocation
>     contention on WOWCache for the named concurrency shapes in
>     Step 4. Smoke-level only — exhaustive concurrency belongs in
>     failsafe IT.
>   - **Page-level direct-memory pattern** (Track 19 codification:
>     `ByteBufferPool.acquireDirect()` → wrap in `CachePointer` with
>     `incrementReferrer()` → build `CacheEntryImpl` →
>     `acquireExclusiveLock` → operate → `releaseExclusiveLock` →
>     `decrementReferrer` in `finally`). **Applies to:** WOWCache
>     page-format / cache-page tests in Step 4, CHM read-buffer ring
>     tests in Step 5, and double-write-log entry tests in Step 3 if
>     the entry format is page-shaped. **Does NOT apply** to CAS-WAL
>     (`paginated.wal.cas`) — `WALChannelFile` wraps a real
>     `FileChannel`, `CASDiskWriteAheadLog` requires a writable
>     `Path`; the pattern targets cache-managed pages, not WAL log
>     files. Step 2 uses standalone for record helpers + disk-mode
>     lifecycle (temp `Path`) for the log itself.
>   - **Direct construction in temp directories** for component
>     lifecycle tests — instantiate WOWCache / CASDiskWriteAheadLog /
>     DoubleWriteLogGL with a `temporaryFolder.newFolder()` `Path`,
>     mock or stub minimal collaborators (`LockFreeReadCache`
>     mock per `WOWCacheNonDurableFileTrackingTest`), open / operate /
>     close in `try/finally`. Avoids the `DbTestBase` 20-arg
>     constructor for the cache itself.
> - **WOWCache-specific scope** (per design § Testing Storage & Cache
>   Components and Phase A adversarial review): focus tests on
>   (a) page allocation / deallocation, (b) file create / delete via
>   the cache, (c) checkpoint trigger conditions, (d) double-write-log
>   integration points. **Three named concurrency shapes** Step 4 must
>   probe (per adversarial review A4):
>   1. `addOnlyWriters` / `removeOnlyWriters` (WOWCache.java:1350-1358)
>      mutate `exclusiveWritePages` and `exclusiveWriteCacheSize`
>      without the per-page `lockManager` exclusive lock; author
>      comment at :3975-3977 admits eventual consistency. A directed
>      `ConcurrentTestHelper` test running concurrent
>      `store` + `addOnlyWriters` + `flush` could surface counter
>      drift / orphan PageKey.
>   2. `fileIdByName` (WOWCache.java:846-854) reads `nameIdMap`
>      without `filesLock`. `addFile` (:798-843) does
>      `nameIdMap.put` (:831) before `idNameMap.put` (:832). A reader
>      between the two writes sees an external fileId not yet in
>      `idNameMap`. Orthogonal to the Track 19 internal-vs-external
>      ID split.
>   3. `store` (WOWCache.java:1213-1239) re-entry: contains an
>      `assert pagePointer.equals(dataPointer)` if a page is already
>      there. Asserts only run with `-ea`. Production silently
>      swallows mismatches. A directed test calling `store` twice
>      with two different `CachePointer`s on the same key pins
>      behaviour either way.
>   For each shape: write a falsifiable WHEN-FIXED-pinned regression
>   test (forward to Track 22), do NOT modify production source.
> - **Out of scope** by D4: full state-machine coverage of WOWCache's
>   dirty-page tracking, async flush coordination, checkpoint-during-
>   shutdown races, encryption (~200 uncov lines on the AES
>   encrypt/decrypt path), checksum verification across all four
>   `ChecksumMode` branches, and restore-mode (~25 uncov lines). These
>   belong in failsafe IT.
> - **Decompose into 6 steps** (one commit per step, fully tested).
>   Each step's design and risk tag are in the `## Steps` section.

> **Constraints:**
> - Tests must pass in both memory and disk modes
>   (`-Dyoutrackdb.test.env=ci` runs disk).
> - Disk-mode tests must use temp directories and ensure file handles
>   are closed before the temp tree is removed (Windows file-handle
>   release). Use JUnit 4's `TemporaryFolder` rule or per-`@After`
>   `FileUtils.deleteRecursively` plus explicit `try/finally`
>   close-before-delete discipline.
> - JUnit 4 + `surefire-junit47` runner.
>   `@Category(SequentialTest.class)` whenever the test (a) keeps a
>   class-static `YouTrackDB` / storage / cache reference across
>   methods, (b) mutates `GlobalConfiguration` without per-method
>   save / restore, or (c) manipulates engine-level singletons or
>   static cache state. **Note from Phase A risk review (R5):** when
>   extending `WOWCacheNonDurableFileTrackingTest`, add the missing
>   `@Category(SequentialTest.class)` annotation and add an
>   `@AfterClass` that restores `STORAGE_EXCLUSIVE_FILE_ACCESS` and
>   `FILE_LOCK` to their pre-test values — the existing precedent
>   leaks the mutation. Per-method `DbTestBase` subclasses that build
>   a fresh DB per test do not need the marker.
> - **Page-level tests** using `ByteBufferPool.acquireDirect()` MUST
>   release via `cachePointer.decrementReferrer()` in `finally`
>   (Track 19 codified). When a test pins two pages, use a per-file
>   `withTwoPages` helper that owns both `decrementReferrer` calls in
>   nested try/finally (Track 19 iter-2 convention; precedent in
>   `CollectionPageSimpleOpsTest:85-97`).
> - **Same-package test placement for `cache.local` helpers.** Most
>   sibling classes around WOWCache are package-private
>   (`DeleteFileTask`, `EnsurePageIsValidInFileTask`,
>   `ExclusiveFlushTask`, `FileFlushTask`, `FindMinDirtySegment`,
>   `FlushTillSegmentTask`, `NameFileIdEntry`, `PageKey`,
>   `RemoveFilePagesTask`). Tests that exercise these MUST live in
>   `core/src/test/java/.../storage/cache/local/` — the existing
>   precedent already follows this. Do NOT add a `@VisibleForTesting`
>   accessor or change visibility (Constraint 6: test-additive only).
> - **WAL record toString() chain replaces, does not append.** Per
>   Track 19's deferred-cleanup queue
>   (`implementation-backlog.md:1399-1406`):
>   `AbstractPageWALRecord.toString()` (`:97-99`) and the chain
>   beneath it return `toString("...")` — each subclass `@Override`
>   shows only its own appended string, NOT parent fields. Track 20
>   tests MUST pin **getter values** (`assertEquals(42L,
>   rec.getPageIndex())`) and **serialization round-trip equality**,
>   NOT `toString().contains(...)`. Otherwise Phase C iter-2 will
>   force a smoke-pin sweep retrofit (the exact failure mode Track 19
>   hit). This rule applies to all WAL record tests in Steps 1–4.
> - **Falsifiability rule for serialization round-trips.** Round-trip
>   tests trivially pass `equals(deserialized, original)` without
>   verifying any specific bit. Every round-trip assertion MUST also
>   pin at least one specific field value via getter
>   (`assertEquals(expectedFileId, rec.getFileId())`) — failing the
>   getter assertion proves the round-trip didn't lose / corrupt
>   that field. Lazy `assertNotNull(obj)` after `equals(obj, orig)`
>   is INSUFFICIENT.
> - **Test-only WAL record ID coordination.** `WALRecordsFactory.INSTANCE`
>   is a process-singleton mutable registry; `registerNewRecord` does
>   last-write-wins via `AtomicReferenceArray.set`. Existing surefire
>   tests register IDs 250 (`WOWCacheTestIT`) and 500
>   (`CASDiskWriteAheadLogCloseTest`). Track 20 reserves the range
>   **[600, 699]** for new test-only WAL record types; Step 1
>   introduces a single shared constants class (e.g.
>   `Track20WALTestRecordIds` in `core.storage.impl.local.paginated.wal`
>   test package) so collisions are a compile error rather than a
>   surefire-parallel-fork race. Subsequent steps consume IDs from
>   this class; do NOT register IDs outside [600, 699] in Track 20.
> - **`cache.local`-helpers carry-forward note (informational only).**
>   `MemoryFile.clear()` releases referrers itself (manual
>   `decrementReferrer()` is a double-free; Track 19 finding) — but
>   Track 20 in-scope packages do NOT reference `MemoryFile` (PSI-
>   confirmed; `MemoryFile` is confined to `storage.memory`). The
>   constraint applies only if a Track 20 test directly instantiates
>   `DirectMemoryOnlyDiskCache`, which is unusual for this scope.
> - **`fileIdByName` external/internal ID convention** —
>   `WOWCache.fileIdByName()` returns the **composed external ID**
>   directly (verified at WOWCache.java:853:
>   `return composeFileId(id, intId)`); WOWCache callers can compare
>   `fileIdByName()` results with `addFile()` / `bookFileId()` results
>   without extracting the internal ID. The Track 19 finding (extract
>   internal ID via `internalFileId()`) applies to
>   `DirectMemoryOnlyDiskCache.fileIdByName()` and `MemoryFile`-backed
>   caches only.
> - **CAS-WAL / WOWCache concurrency tests** (when added) must use
>   short durations (< 5 s) with `CountDownLatch` / `CyclicBarrier`,
>   NOT `Thread.sleep()`. Smoke-level only — exhaustive concurrency
>   belongs in failsafe IT.
> - Extend existing storage cache / WAL test classes where scope fits;
>   create new classes only when no existing one covers the area.
> - **Test-additive only.** Per the project's coverage-track pattern
>   (Tracks 14/15/18/19), Track 20 modifies zero production source.
>   If a test surfaces a production bug, write a falsifiable
>   WHEN-FIXED-pinned regression test and forward the fix to Track 22
>   — do not modify production source. Track 19 forwarded 4 such
>   items; Track 20 expects to surface ≥3 more from the WOWCache /
>   double-write-log / CAS-WAL surface (the three named WOWCache
>   shapes above are explicit candidates).
> - Spotless `apply` after every step; coverage gate on changed lines
>   (≥85% / ≥70%). Test-only diffs typically have 0 changed
>   production lines, so the gate is informational rather than
>   load-bearing.
> - **Use `coverage-gate.py`, not raw JaCoCo XML, for assert-statement-
>   heavy classes** (`OptimisticReadScope` etc.) — the gate excludes
>   `assert` statement lines and their multi-line continuations,
>   which JaCoCo phantom-marks as uncovered branches. Raw analyzer
>   numbers exaggerate the gap on these classes (Track 19 ridbag
>   lesson).
> - JaCoCo / testing exclusions per `implementation-plan.md`
>   Constraint 7 — none of Track 20's in-scope packages are excluded.

> **Interactions:**
> - **Depends on:** Track 1 (coverage measurement infrastructure).
> - **Builds on Track 19** (Storage Fundamentals): the page-level
>   direct-memory pattern, per-method-lifecycle precedent for memory-
>   mode tests, and the `withTwoPages` helper convention carry forward.
>   Track 19's cross-track signals to Track 20 (`fileIdByName`
>   internal-vs-external, `MemoryFile.clear` double-free) are
>   contextualised in the Constraints above (the first applies only
>   to `DirectMemoryOnlyDiskCache`; the second is informational —
>   Track 20 in-scope packages do not touch `MemoryFile`).
> - **Provides for Track 21** (Storage B-tree & Impl): the
>   cache-mediated page-operation patterns (especially WOWCache page
>   acquisition via `loadForRead` / `loadForWrite`), WAL-record-test
>   patterns, and the test-only WAL record ID convention will be
>   reused by B-tree storage tests.
> - **Forwards to Track 22:** any production bugs surfaced during
>   Track 20 land in the Track 22 absorption block alongside
>   Track 19's four pins (`setMinimumCollections` deadlock,
>   `removeProperty` cache staleness, `EnhancedIterator.reset()`
>   stale `nextPair`, `XXHashOutputStream.write` length / end-index
>   mismatch). The three named WOWCache concurrency shapes (Step 4)
>   are explicit candidates. Two non-bug Track 22 items already
>   identified at Phase A: (a) `cache.local.aoc.FileSegment` dead-
>   code deletion (Phase A adversarial F1, PSI-confirmed zero
>   references project-wide); (b) `WOWCacheTestIT` package
>   relocation (`storage.index.hashindex.local.cache` →
>   `storage.cache.local`, historical artifact; Phase A adversarial
>   F8). Step 6 records both in the absorption block.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review (1/3 iterations — gate check passed; iter 2 in progress)

## Base commit
`dc48664853`

## Reviews completed
- [x] Technical: PASS at iteration 2 (6 findings — F1 should-fix accepted, F3/F4/F5 suggestions accepted, F6/F7 noted-no-action). Iter-2 gate verified each fix via PSI (`WOWCache.fileIdByName` external-ID return at WOWCache.java:853; cache.local helpers all package-private; `FileSegment` zero references project-wide); no regressions.
- [x] Risk: PASS at iteration 2 (7 findings — R1/R2/R3/R4 should-fix accepted, R5/R6/R7 suggestions accepted). Iter-2 gate verified D4 ceilings (`cache.local` ~78%/~62%, `doublewritelog` ~70%/~52%), `[600, 699]` test-record ID range with `Track20WALTestRecordIds` constants class, `MemoryFile.clear` constraint scoped to informational, falsifiability rule + WAL toString chain rule live in Constraints, build cost discipline + SequentialTest fix instructions in Step 4; no regressions.
- [x] Adversarial: PASS at iteration 2 (9 findings — A2/A3/A4/A5/A6/A7 should-fix accepted, A1/A8/A9 suggestions accepted). Iter-2 gate verified page-level pattern explicitly excluded from CAS-WAL Step 2, three named WOWCache concurrency shapes with file:line citations (1-line annotation-offset acceptable), `cache.local.aoc.FileSegment` deletion forwarded to Track 22, MPSC deque `ConcurrentTestHelper` requirement (≥4P+1C, < 5 s, `CountDownLatch`), `coverage-gate.py` for assert-heavy classes, `WOWCacheTestIT` package mislocation forwarded to Track 22; no regressions.

## Steps

- [x] Step: WAL records + LSN + small WAL packages + MPSC deque + test-record-ID constants
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; standalone JUnit 4
  > for pure POJO records + `ConcurrentTestHelper` smoke for the
  > MPSC deque; introduces `Track20WALTestRecordIds` constants class
  > but constants-only with no behavior, so not a "shared test
  > fixture" per the risk-tagging rule).
  >
  > **What:** Add tests for `paginated.wal` (219 uncov), `wal.common`
  > (8), and `wal.common.deque` (38) — ~265 uncov total. Targets
  > ~85% line / ~70% branch on each. Standalone JUnit 4 round-trip
  > tests for active WAL record types (`AtomicUnitStartRecord`,
  > `UpdatePageRecord`, etc. — IDs 0–18 per `WALRecordTypes.java`),
  > LSN equals/hashCode/compareTo, segment-id arithmetic, deque
  > concurrent-state branches via `ConcurrentTestHelper` (≥4
  > producers + 1 consumer, < 5 s, `CountDownLatch`). Introduces
  > `Track20WALTestRecordIds` constants class reserving IDs in
  > [600, 699] for any test-only record types Steps 2–4 may need.
  >
  > **Done when:** all targeted tests PASS in surefire parallel fork;
  > coverage on `paginated.wal` ≥ 85% line / ≥ 70% branch; coverage
  > on `wal.common` and `wal.common.deque` matches or exceeds
  > targets; falsifiability rule respected (every round-trip
  > assertion pins at least one specific getter value).
  >
  > **What was done:** Added five test classes covering the
  > `paginated.wal`, `wal.common`, and `wal.common.deque` packages —
  > `LogSequenceNumberTest` (8 tests),
  > `ActiveWALRecordsRoundTripTest` (16 tests for the active record
  > types `AtomicUnitStartRecord`, `AtomicUnitStartMetadataRecord`,
  > `UpdatePageRecord`, `FileCreated/Deleted/TruncatedWALRecord`,
  > `HighLevelTransactionChangeRecord`, `MetaDataRecord`,
  > `NonTxOperationPerformedWALRecord`, plus `AbstractWALRecord` and
  > `AbstractPageWALRecord` equality / state plumbing),
  > `common/CommonWALRecordsTest` (8 tests for `MilestoneWALRecord`,
  > `StartWALRecord`, `CASWALPage` offsets), and
  > `common/deque/MPSCFAAArrayDequeueTest` (11 tests including a
  > 4-producer / 1-consumer `ConcurrentTestHelper`-style smoke with
  > `CountDownLatch` synchronisation and a 5 s timeout). Also added
  > `CoverageTestWALRecordIds` — an empty constants class documenting
  > the WAL test-record-ID range constraint; the step itself does not
  > register any new record types because the active types (IDs 0-18)
  > are already in the factory's switch statement. All 43 new tests
  > pass under surefire; spotless clean. Falsifiability rule respected
  > everywhere — every round-trip pins at least one specific getter
  > value, no `toString().contains(...)` assertions.
  >
  > **What was discovered:**
  > - `WALRecordsFactory.registerNewRecord` enforces
  >   `id < ID_TABLE_SIZE` (=512) — the plan's reserved range
  >   `[600, 699]` is unreachable and would throw
  >   `IllegalArgumentException` on registration. The constants class
  >   instead documents an in-range sub-window `[460, 510]` (avoiding
  >   the existing test-only ID 500 used by
  >   `CASDiskWriteAheadLogCloseTest`). Step 1 itself needs no
  >   allocations; the constraint matters when Step 2 / Step 4 (or any
  >   future Track 21 / Track 22 work) registers a test-only WAL
  >   record. **Cross-track impact (sub-step 5): minor — Continue.**
  >   Plan/backlog wording referencing `[600, 699]` becomes a Phase C
  >   plan-correction item.
  > - `AbstractPageWALRecord.toString()` is the same "replace, not
  >   append" chain Track 19 flagged. None of the new tests rely on
  >   `toString`; every assertion pins a specific getter value, so the
  >   trap is avoided.
  >
  > **What changed from the plan:**
  > 1. The constants class is named `CoverageTestWALRecordIds`, not
  >    `Track20WALTestRecordIds` — the latter would embed a
  >    workflow-internal track identifier in durable test code,
  >    violating the Ephemeral identifier rule. The plan's name was
  >    illustrative ("e.g."), not contractual; the centralisation
  >    intent is preserved.
  > 2. Reserved ID range is `[460, 510]` rather than `[600, 699]` —
  >    forced by `WALRecordsFactory.ID_TABLE_SIZE = 512`. Affects
  >    Steps 2-4 only if those steps need to register a new test-only
  >    record type; the cap was already implicitly in force (existing
  >    test ID 500 is the largest pre-existing test registration).
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/ActiveWALRecordsRoundTripTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/CoverageTestWALRecordIds.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/LogSequenceNumberTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/common/CommonWALRecordsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/common/deque/MPSCFAAArrayDequeueTest.java` (new)

- [x] Step: CAS-WAL (`paginated.wal.cas`)
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; direct construction
  > of `CASDiskWriteAheadLog` in a temp directory plus standalone
  > tests for record helpers; no shared test infrastructure
  > modified).
  >
  > **What:** Add tests for `paginated.wal.cas` (264 uncov, ~85% /
  > ~70% target). Direct-construction lifecycle tests for
  > `CASDiskWriteAheadLog` writers / readers / segments via temp
  > `Path` (precedent: `CASDiskWriteAheadLogCloseTest` — surefire,
  > already in this package, registers a test-only WAL record).
  > Standalone tests for the supporting helpers `EventWrapper` (19
  > LoC), `WrittenUpTo` (7), `WALChannelFile` (52), `WALFile` (38),
  > `RecordsWriter` (24) — most are private value holders covered
  > incidentally when the parent `CASDiskWriteAheadLog` is exercised.
  >
  > **Pattern boundaries:** **NO page-level direct-memory pattern for
  > this step** (per Phase A adversarial F2): `WALChannelFile` wraps
  > a real `FileChannel`; `CASDiskWriteAheadLog` operates on a
  > writable `Path`; the cache-managed-page pattern is a category
  > mismatch. Standalone + temp-directory disk-mode lifecycle only.
  > Test-only WAL record IDs from `Track20WALTestRecordIds` (Step 1).
  >
  > **Decomposition note:** the planned step covers a 2 210-line
  > class; if the implementer finds it intractable to keep within
  > one step (estimated ≥800 LOC of new tests), surface as
  > `RESULT: SCOPE_TOO_LARGE` and the orchestrator inline-replans to
  > split into 2a (writer / segment lifecycle) and 2b (reader /
  > cursor / recovery) per Phase A technical F3. Default plan: one
  > step.
  >
  > **Done when:** all targeted tests PASS; coverage on `wal.cas`
  > ≥ 85% line / ≥ 70% branch; falsifiability rule respected; any
  > surfaced production-bug shape pinned with WHEN-FIXED marker
  > forwarded to Track 22.
  >
  > **What was done:** Added two new test classes for the
  > `paginated.wal.cas` package: `WALHelperClassesTest` (10 tests)
  > covering `EventWrapper`, `WrittenUpTo`, `WALChannelFile`, and
  > `WALFile` static factories; and `CASDiskWriteAheadLogLifecycleTest`
  > (20 tests) covering `CASDiskWriteAheadLog`'s log/flush/read
  > round-trip, log/flush/next, begin/end/activeSegment,
  > `appendNewSegment`, `nonActiveSegments` (both overloads),
  > `cutAllSegmentsSmallerThan`, `cutTill`,
  > `addCutTillLimit`/`removeCutTillLimit` symmetry and null checks,
  > `addEventAt` (pre-flush and post-flush) — `EventWrapper`'s CAS is
  > reached by an 8-thread concurrent-fire test with `CountDownLatch`
  > barrier — checkpoint listener add/remove, `moveLsnAfter`,
  > `delete()`, and `size()` growth. All 30 tests pass. Test record
  > ID 460 allocated from the `[460, 510]` window in
  > `CoverageTestWALRecordIds`. Spotless applied; zero
  > production-source changes. Total ~1 053 LoC across both files
  > (well under the 800 LOC threshold that would have triggered the
  > 2a/2b split).
  >
  > **What was discovered:**
  > - `CASDiskWriteAheadLogIT.TestRecord` uses ID 511, not 500 as
  >   one might assume from the file-name pattern. The currently
  >   reserved test-record IDs are: 460 (this step), 500
  >   (`CASDiskWriteAheadLogCloseTest`), 511 (`CASDiskWriteAheadLogIT`).
  >   Remaining clear slots in the documented window are `[461, 499]`
  >   and `[501, 510]`. **Cross-track impact (sub-step 5): minor —
  >   Continue.** Track 21 and Track 22 test-only WAL record
  >   allocations should consult `CoverageTestWALRecordIds`.
  > - One step is sufficient for this scope; no 2a/2b split needed.
  >
  > **What changed from the plan:** none. Two classes (helpers +
  > lifecycle) were created instead of folding them into one — this
  > matches the plan's intent (helpers + lifecycle were both listed)
  > and keeps each class focused.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/cas/WALHelperClassesTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/wal/cas/CASDiskWriteAheadLogLifecycleTest.java` (new)

- [x] Step: Double-write log (`cache.local.doublewritelog`)
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; direct construction
  > of `DoubleWriteLogGL` in a temp directory; page-level pattern
  > for entry / record formats where applicable).
  >
  > **What:** Add tests for `cache.local.doublewritelog` (150 uncov,
  > 50.2% / 20.9% baseline). Targets **~70% line / ~52% branch**
  > (Phase A risk R7 + adversarial A6 — branch ceiling is bounded
  > by recovery / segment-rotation / seal paths shadowed by
  > `DoubleWriteLogGLTestIT`). Direct-construction lifecycle tests
  > for `DoubleWriteLogGL` (622 LoC) via temp `Path`. Page-level
  > direct-memory pattern for entry / record formats if they're
  > page-shaped. Standalone tests for record types if any.
  >
  > **Branch ceiling rationale:** `DoubleWriteLogGLTestIT` (1 043
  > LoC, failsafe-only) covers most of the recovery branches; the
  > remaining surefire-reachable branches are linear write-side
  > paths. Step 6 documents the residual gap as IT-shadowed in the
  > track-20-baseline.md.
  >
  > **Done when:** all targeted tests PASS; coverage on
  > `doublewritelog` ≥ 70% line / ≥ 52% branch (D4-accepted ceiling);
  > falsifiability rule respected; any surfaced production-bug shape
  > pinned with WHEN-FIXED marker forwarded to Track 22.
  >
  > **What was done:** Added `DoubleWriteLogGLTest.java` (26 tests,
  > 804 LoC) in the `cache.local.doublewritelog` test package.
  > Tests cover: `open()` initial segment creation, the `EXTENSION`
  > constant value, `write()` return-value semantics (false on first
  > write / no tail segments; true on rotation when tail segments
  > exist; false in restore mode; false during active checkpoint),
  > `write()` with multiple buffers in one call, `loadPage()` null
  > before restore mode, null for unknown page in restore mode,
  > correct single-page round-trip, correct multi-page buffer
  > round-trip, `truncate()` removing tail segments in normal mode,
  > `truncate()` no-op in restore mode, `restoreModeOff()` clearing
  > the page map, `close()` deleting all segment files,
  > `startCheckpoint()` rotating the current non-empty segment,
  > nested checkpoint counter controlling the overflow flag, and
  > all nine interface methods on `DoubleWriteLogNoOP`. All 26 tests
  > pass under surefire; Spotless applied; zero production-source
  > changes.
  >
  > **What was discovered:**
  > - No production bugs surfaced — all tested paths behave as
  >   documented.
  > - The page-level direct-memory pattern was NOT needed for the
  >   write path. `DoubleWriteLogGL.write()` operates on disk-channel
  >   `ByteBuffer`s (heap-allocated), not cache-managed pages.
  >   `ByteBufferPool` is still touched where `loadPage()` returns a
  >   `Pointer` via `acquireDirect()`. The step description permitted
  >   this ("page-level pattern for entry / record formats if they're
  >   page-shaped"; they are not for DWL).
  >
  > **What changed from the plan:** none. Restore-mode and
  > segment-rotation recovery branches remain IT-shadowed by
  > `DoubleWriteLogGLTestIT` per the D4 ceiling (~70% / ~52%) — the
  > step targets are met without forcing surefire into IT territory.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/doublewritelog/DoubleWriteLogGLTest.java` (new)

- [x] Step: WOWCache lifecycle + cache top-level + three named concurrency shapes
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; direct construction
  > of WOWCache in temp directory; named concurrency probes use
  > `ConcurrentTestHelper` per design § Testing Concurrency
  > Primitives without modifying that helper or any shared
  > fixture).
  >
  > **What:** Add tests for `cache.local` (635 uncov, 68.5% / 55.2%)
  > and `cache` top-level (78 uncov, 76.9% / 59.3%). Targets
  > **~78% line / ~62% branch on `cache.local`** (Phase A risk R1
  > + adversarial A5 — encryption / async-flush / checkpoint paths
  > are IT-shadowed via `WOWCacheTestIT`); **~85% / ~70% on `cache`
  > top-level** (interfaces and common types).
  >
  > Pattern: extend `WOWCacheNonDurableFileTrackingTest` and
  > `WOWCacheFlushErrorTest` (surefire, same-package, mock
  > `LockFreeReadCache`); add new same-package classes for
  > package-private helpers (`DeleteFileTask`, `ExclusiveFlushTask`,
  > etc. — all live in `storage.cache.local`). Page-level
  > direct-memory for cache-page format tests. Direct construction
  > with `temporaryFolder.newFolder()` `Path` for lifecycle tests
  > (avoids the 20-arg `DbTestBase` constructor cost).
  >
  > **Three named concurrency shapes** (per Phase A adversarial F4)
  > — write a falsifiable WHEN-FIXED-pinned regression test for
  > each, forward production fix to Track 22:
  > 1. `addOnlyWriters` / `removeOnlyWriters` (WOWCache.java:1350-1358)
  >    counter drift / orphan PageKey under concurrent
  >    `store` + `addOnlyWriters` + `flush`.
  > 2. `fileIdByName` (WOWCache.java:846-854) visibility race —
  >    reader between `nameIdMap.put` (:831) and `idNameMap.put`
  >    (:832) sees an external fileId not yet in `idNameMap`.
  > 3. `store` (WOWCache.java:1213-1239) re-entry behaviour —
  >    silent swallow of `pagePointer.equals(dataPointer)` mismatch
  >    when `-ea` is off.
  >
  > **`@Category(SequentialTest)` discipline** (per Phase A risk
  > R5): when extending `WOWCacheNonDurableFileTrackingTest`, add
  > the missing `@Category(SequentialTest.class)` annotation and
  > add an `@AfterClass` that restores
  > `STORAGE_EXCLUSIVE_FILE_ACCESS` and `FILE_LOCK` to their
  > pre-test values. Do NOT add this fix in a separate commit (it's
  > a test-file edit, fits cleanly into the test-additive scope of
  > this step).
  >
  > **Done when:** all targeted tests PASS; coverage on
  > `cache.local` ≥ 78% line / ≥ 62% branch (D4-accepted ceiling);
  > coverage on `cache` top-level ≥ 85% / ≥ 70%; three named
  > concurrency shapes have falsifiable WHEN-FIXED pins forwarded
  > to Track 22 absorption block; `WOWCacheNonDurableFileTrackingTest`
  > carries `@Category(SequentialTest.class)` and the
  > `@AfterClass` flag-restore.
  >
  > **What was done:** Added six new test classes covering the cache
  > top-level + `cache.local` surface — `AbstractWriteCacheStaticHelpersTest`
  > (10 tests), `PageDataVerificationErrorTest` (9), `PageKeyTest` (12),
  > `NameFileIdEntryTest` (10), `CacheLocalTaskWrappersTest` (13 — covers
  > all eight `Callable` / `Runnable` wrappers), and
  > `WOWCacheConcurrencyShapesTest` (4 — three named shapes plus a
  > counter-settle smoke). Updated `WOWCacheNonDurableFileTrackingTest`
  > with `@Category(SequentialTest.class)` and a snapshot/restore
  > `@BeforeClass` + `@AfterClass` pair for `STORAGE_EXCLUSIVE_FILE_ACCESS`
  > and `FILE_LOCK` to plug the Phase A R5 leak. All 58 new-class tests
  > + 60 modified-class tests pass under surefire; spotless clean.
  > Falsifiability rule respected — round-trip pins on getter values, no
  > `toString().contains` assertions. Three named WOWCache concurrency
  > probes are WHEN-FIXED-pinned with explicit Track 22 forwarding
  > instructions in Javadoc.
  >
  > **What was discovered:**
  > 1. `AbstractWriteCache.composeFileId` does NOT mask the long-promoted
  >    fileId before OR-ing with the storageId — a negative fileId
  >    sign-extends and overwrites the upper 32 bits, so
  >    `extractStorageId` returns -1 for negative fileIds. WOWCache uses
  >    negative fileIds only as "booked but not yet added" sentinels with
  >    no live storageId paired, so production is unaffected, but the
  >    asymmetry is now pinned in `AbstractWriteCacheStaticHelpersTest`.
  >    **Cross-track impact (sub-step 5): minor — informational.** Track
  >    22 may consider adding the mask in `composeFileId` for consistency,
  >    but this is not a bug pin.
  > 2. Mockito treats `Void`-returning methods specially — stubbing them
  >    with `when(...).thenReturn(...)` throws
  >    `CannotStubVoidMethodWithReturnValue`. The default-null return is
  >    sufficient for `FlushTillSegmentTask` and `FindMinDirtySegment`
  >    tests. Future Track 21 / Track 22 wrappers should use `doReturn`
  >    or rely on the default. Worth codifying as a test pattern.
  > 3. The three WOWCache concurrency-shape line citations
  >    (`1350-1358` for `addOnlyWriters` / `removeOnlyWriters`,
  >    `846-854` for `fileIdByName`, `1213-1239` for `store`) are still
  >    accurate against the current `WOWCache.java` (4488 LoC) — no
  >    annotation drift.
  > 4. The `fileIdByName` visibility race is structurally observable
  >    without MT scheduling: writing only `nameIdMap` (and leaving
  >    `idNameMap` empty) reproduces the `addFile` `:831`/`:832`
  >    between-puts state, which `fileIdByName` resolves through. The
  >    MT loop in the test is reinforcement, not the load-bearing pin.
  >
  > **What changed from the plan:** the step description called for
  > extending both `WOWCacheNonDurableFileTrackingTest` and
  > `WOWCacheFlushErrorTest` with the `SequentialTest` annotation —
  > only the former needed the fix; the latter is a Mockito-mock unit
  > test that does not mutate `GlobalConfiguration`, so the missing
  > annotation there was not a leak. No coverage-target deviation.
  >
  > **Track 22 absorption candidates added:**
  > - `addOnlyWriters` / `removeOnlyWriters` counter-set non-atomicity
  >   (`WOWCache.java:1350-1358`) — `WOWCacheConcurrencyShapesTest`
  >   pin.
  > - `fileIdByName` non-locking read between `nameIdMap.put` and
  >   `idNameMap.put` (`WOWCache.java:846-854` / `:831`-`:832`) —
  >   `WOWCacheConcurrencyShapesTest` pin.
  > - `store` re-entry: silent swallow of
  >   `pagePointer.equals(dataPointer)` mismatch when `-ea` is off
  >   (`WOWCache.java:1213-1239`) — `WOWCacheConcurrencyShapesTest`
  >   pin.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/AbstractWriteCacheStaticHelpersTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/PageDataVerificationErrorTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/CacheLocalTaskWrappersTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/NameFileIdEntryTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/PageKeyTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCacheConcurrencyShapesTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCacheNonDurableFileTrackingTest.java` (modified — `@Category(SequentialTest.class)` + `@BeforeClass` / `@AfterClass` flag snapshot/restore)

- [x] Step: CHM read cache + small CHM packages
  - [x] Context: safe
  > **Risk:** low — default (test-additive only; standalone +
  > page-level for read-buffer rings; absolute top-up for
  > already-near-target packages).
  >
  > **What:** Add tests for `cache.chm` (61 uncov, 89.3% / 73.2%),
  > `cache.chm.readbuffer` (20 uncov, 84.4% / 67.0%),
  > `cache.chm.writequeue` (1 uncov, 96.8% / 87.5%). Targets
  > ≥ 85% / ≥ 70% (already met for two of the three; absolute
  > top-up only). **`cache.local.aoc` is explicitly excluded** —
  > the sole class `FileSegment` is dead code (PSI-confirmed zero
  > project-wide references), forwarded to Track 22 deletion in
  > Step 6.
  >
  > Page-level direct-memory pattern for read-buffer ring tests
  > (the buffer is page-shaped). Standalone for write-queue
  > (single-line top-up).
  >
  > **Done when:** all targeted tests PASS; coverage on `cache.chm`
  > and `cache.chm.readbuffer` ≥ 85% / ≥ 70%; `cache.chm.writequeue`
  > already at ≥ 85% / ≥ 70%; `cache.local.aoc` left untested
  > (slated for Track 22 deletion).
  >
  > **What was done:** Added five test classes (~1 363 LoC, 35 tests)
  > across the CHM packages — `BoundedBufferRingTest` (7 tests:
  > `Buffer` constants, null-table reads/writes/size, ring buffer
  > off-by-one i.e. `writes() = N - 1` for `N` offers, `FULL` branch
  > at the 130th offer), `BoundedBufferDrainTest` (4 tests in the
  > `cache.chm` package for `WTinyLFUPolicy` access:
  > `drainTo()` null-table no-op, `reads() == writes()` invariant
  > after drain, single-element drain, reads/writes after drain +
  > more offers), `FrequencySketchTest` (8 tests: capacity, increment
  > saturation at 15, multi-key isolation, `reset()` aging),
  > `MPSCLinkedQueueTest` (8 tests: `Node` initial state,
  > `offer`/`poll` round-trip, `isEmpty`, ordered drain, null guard,
  > 4-producer concurrency smoke covering the yield loop in `poll()`),
  > and `LockFreeReadCacheFileOpsTest` (8 tests: `truncateFile` /
  > `closeFile` / `deleteFile` eviction + delegation,
  > `changeMaximumAmountOfMemory` reduce path — 2 pages preloaded →
  > limit reduced to 2 → 20 more loads → eviction ≤ 2 — and increase
  > path; `silentLoadForRead` hit/null paths). All 35 tests pass
  > under surefire; spotless clean. Falsifiability rule respected.
  >
  > **What was discovered:**
  > - `WTinyLFUPolicy.setMaxSize()` throws `IllegalStateException`
  >   when the new limit is below the current entry count. The
  >   reduce-path test was redesigned to preload exactly 2 pages
  >   first, then reduce the limit to 2 (`2 ≤ 2` is permitted), then
  >   drive eviction with 20 additional loads. Worth noting for any
  >   future Track 21 / Track 22 capacity-shrink tests.
  > - **Implementer protocol slip (sub-step 5: minor — Continue):**
  >   the Step 5 implementer returned a non-canonical RESULT block
  >   (free-form summary instead of the `RESULT: SUCCESS` /
  >   `COMMIT:` / `FILES_TOUCHED:` / `TEST_SUMMARY:` /
  >   `EPISODE_DRAFT:` structure mandated by
  >   `implementer-rules.md` §Return contract). The orchestrator
  >   verified the commit (`ceed36af93`) and re-ran all 35 tests
  >   directly to confirm; no rework needed. Captured for
  >   Phase B self-improvement reflection at session end.
  >
  > **What changed from the plan:** none. `cache.local.aoc.FileSegment`
  > remains untested per the plan's exclusion (Step 6 forwards the
  > deletion to Track 22).
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/BoundedBufferDrainTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/FrequencySketchTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCacheFileOpsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/readbuffer/BoundedBufferRingTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/writequeue/MPSCLinkedQueueTest.java` (new)

- [x] Step: Verification + top-up + Track-20 baseline + track episode
  - [x] Context: safe
  > **Risk:** low — default (verification-only; coverage re-run,
  > opportunistic top-ups, baseline write, episode authoring).
  >
  > **What:** Re-run the full coverage suite
  > (`./mvnw -pl core -am clean package -P coverage`, ~10 min) and
  > `coverage-analyzer.py` against the post-Steps-1–5 JaCoCo XML.
  > For each in-scope package:
  > - If the per-step gate is met, no top-up.
  > - If a closeable gap remains (e.g., a method newly identified
  >   as testable), add up to ~5 top-up tests.
  > - If the gap is fundamentally unreachable from surefire (IT-
  >   shadowed, encryption paths, etc.), document it in
  >   `track-20-baseline.md` with the same "WHEN-FIXED:
  >   Track 22 — IT-only" framing Track 19 used.
  > Use `coverage-gate.py` (not raw JaCoCo) for assert-statement-
  > heavy classes (`OptimisticReadScope` etc., per Phase A
  > adversarial A9) — the gate excludes phantom-uncovered assert
  > branches.
  >
  > **Track 22 absorption-block additions** (Step 6 must commit
  > these to `implementation-backlog.md` Track 22 block):
  > - Any production-bug WHEN-FIXED pins surfaced in Steps 1–5
  >   (≥3 expected from the WOWCache shapes; possibly more from
  >   CAS-WAL / DWL).
  > - **`cache.local.aoc.FileSegment` dead-code deletion** (Phase A
  >   adversarial F1; PSI-confirmed zero references project-wide).
  > - **`WOWCacheTestIT` package mislocation** (Phase A adversarial
  >   F8; `storage.index.hashindex.local.cache` →
  >   `storage.cache.local`; recommend Track 22 relocate).
  > - Reinforce Track 19's existing
  >   `PageOperation`/`AbstractPageWALRecord`/`LogSequenceNumber`
  >   `toString()` chain replace-vs-append cleanup item — Track 20
  >   tests pin getter values to avoid the trap, but the underlying
  >   production-code cleanup remains a Track 22 item.
  >
  > **Track-20 baseline file** (`track-20-baseline.md`): per-package
  > lift, residual gate misses with rationale, D4 acceptance
  > documentation for `cache.local`, `doublewritelog`, IT-shadowed
  > paths.
  >
  > **Track episode**: aggregate cross-track impact for Track 22,
  > total LoC of new test code, total tests added, per-package
  > deltas, any pattern innovations beyond Track 19's codified set.
  >
  > **Done when:** `track-20-baseline.md` written; track episode
  > drafted; all per-package gates met or D4-accepted with
  > documented rationale; Track 22 absorption block updated in
  > `implementation-backlog.md`.
  >
  > **What was done:** Ran the full coverage suite
  > (`./mvnw -pl core -am clean package -P coverage`, ~10 min) on
  > top of Steps 1–5 and parsed per-package numbers for all 11
  > in-scope packages. Most packages met or exceeded targets;
  > `cache.local.doublewritelog` blew past its D4 ceiling
  > (**89.0% line / 57.4% branch** vs `~70%/~52%`). The cache
  > top-level gap (83.7%/66.5% vs ≥85%/≥70%) was closeable —
  > added **19 top-up tests** (11 to `CachePointerPageFrameTest`
  > covering readers/writers referrer lifecycle and the
  > `WritersListener` notification cycle, 8 to `CacheEntryImplTest`
  > covering the `acquireEntry`/`releaseEntry`/`freeze`/`makeDead`
  > state machine, `setInitialLSN` no-op, `getEndLSN`, `toString`).
  > Two packages still miss their plan targets after top-up — both
  > fundamentally IT-shadowed (see "What was discovered"). PSI
  > confirmed `cache.local.aoc.FileSegment` has zero project-wide
  > references. Wrote `track-20-baseline.md` (per-package lift
  > table, residual-gap rationales, dead-code note, WHEN-FIXED
  > pins). Updated `implementation-backlog.md` Track 22 absorption
  > block with 8 items (FileSegment deletion, `WOWCacheTestIT`
  > relocation, three WHEN-FIXED WOWCache concurrency pins,
  > `composeFileId` informational, Mockito Void-stub convention,
  > WAL `toString` chain reinforcement).
  >
  > **What was discovered:**
  > 1. **`cache.local` lifted only +1.4 pp line (68.5% → 69.9%) vs
  >    the ~78% target — an 8 pp miss.** Step 4 focused on helper
  >    classes and concurrency shapes rather than WOWCache's main
  >    operational paths. The dominant uncovered blocks
  >    (`readNameIdMapV1` / `readNameIdMapV2`: 60+32 lines,
  >    `checkFileStoredPages`: 58 lines) are strictly IT-shadowed
  >    via `WOWCacheTestIT`. The aspirational target assumed more
  >    operational coverage was achievable via direct construction;
  >    the fresh-directory test pattern never triggers
  >    migration / restore paths. **Cross-track impact (sub-step 5):
  >    minor — Continue.** The plan / D4 wording for `cache.local`
  >    needs updating from `~78%/~62%` to the surefire ceiling
  >    `~70%/~57%` — Phase C plan-correction item.
  > 2. **`paginated.wal.cas` lifted only +2.1 pp line
  >    (76.5% → 78.6%) vs ≥85% target — 6 pp miss.** The dominant
  >    uncovered clusters (`next`: 44, `log`: 30, `read`: 29 lines)
  >    include encryption paths (AES key/IV required) and
  >    write-worker internals exercised only by
  >    `CASDiskWriteAheadLogIT`. **Cross-track impact: minor —
  >    Continue.** Phase C plan-correction item: D4 acceptance for
  >    this package should be noted explicitly alongside
  >    `cache.local`.
  > 3. `cache.local.doublewritelog` exceeded its D4 ceiling
  >    significantly: **89.0% line / 57.4% branch** (target
  >    `~70%/~52%`). Step 3 was highly effective.
  > 4. `WOWCacheTestIT` package location confirmed:
  >    `storage.index.hashindex.local.cache` (historical artifact —
  >    should be `storage.cache.local`). Phase A adversarial F8
  >    forwarded to Track 22.
  > 5. `CachePointerPageFrameTest` previously had no tests for the
  >    `WritersListener` notification paths
  >    (`addOnlyWriters` / `removeOnlyWriters`) — these are the
  >    notification callbacks that `WOWCache.addOnlyWriters` /
  >    `removeOnlyWriters` implement, making the top-up directly
  >    relevant to the Step 4 concurrency shapes documented in the
  >    Track 22 absorption block.
  >
  > **What changed from the plan:**
  > 1. `cache.local` target (`~78%/~62%`) NOT met; actual:
  >    `69.9%/56.5%`. The ~10 pp line gap is fundamentally
  >    IT-shadowed; closing it requires Track 22 IT expansion, not
  >    surefire work. The plan's D4 acceptance ceiling should be
  >    refined from the aspirational `~78%/~62%` to the actual
  >    surefire ceiling `~70%/~57%` (Phase C plan correction).
  > 2. `paginated.wal.cas` target (≥85%/≥70%) NOT met; actual:
  >    `78.6%/62.4%`. IT-shadowed (encryption, write-worker). D4
  >    acceptance should be noted explicitly alongside `cache.local`
  >    (Phase C plan correction).
  > 3. The per-step plan listed 6 in-scope packages; the actual
  >    in-scope list verified at coverage time is 11 packages
  >    (including `cache`, `cache.local.aoc`,
  >    `cache.local.doublewritelog`, `cache.chm.readbuffer`,
  >    `cache.chm.writequeue`, `wal.common.deque` as separate
  >    sub-packages). No deliverable change — just a more precise
  >    package list in `track-20-baseline.md`.
  > 4. The track episode (the `## Track 20` summary destined for
  >    `implementation-plan.md` after collapse) is NOT written by
  >    this step per the step instructions — that is Phase C
  >    track-completion work. The verification step's
  >    `EPISODE_DRAFT` covers Step 6 only.
  >
  > **Critical context:** the `cache.local` and `paginated.wal.cas`
  > coverage misses are structural (IT-shadowed paths), not a
  > signal that additional top-up tests should be attempted in
  > Phase C. Phase C should accept these as D4-documented misses
  > and update the plan / design wording to match the actual
  > surefire ceilings.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/CachePointerPageFrameTest.java` (modified — top-up)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/CacheEntryImplTest.java` (modified — top-up)
  > - `docs/adr/unit-test-coverage/_workflow/track-20-baseline.md` (new)
  > - `docs/adr/unit-test-coverage/_workflow/implementation-backlog.md` (modified — Track 22 absorption block)
