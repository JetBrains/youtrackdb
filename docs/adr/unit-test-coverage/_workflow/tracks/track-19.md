# Track 19: Storage Fundamentals

## Description

Write tests for storage subsystem components that are more testable
than the core cache/WAL/impl internals (storage config, memory
storage, filesystem, disk, collections, ridbag).

> **Reconstruction note:** the original backlog body for Track 19 was
> in a recovery gap (see `implementation-plan.md` § Operational Notes
> → "Reconstruct-on-demand tracks"). The What/How/Constraints/
> Interactions block below is regenerated from the plan's `**Scope:**`
> indicator, the design document's `## Testing Storage & Cache
> Components` section, and the Track-7 coverage baseline. Phase B will
> re-measure per-package coverage on entry to absorb post-Track-7
> drift before per-step decomposition is acted on.

> **What:**
> - Raise per-package coverage toward 85% line / 70% branch on the
>   testable storage clusters that aren't WAL/cache/impl-local
>   internals (those are Tracks 20–21). Aggregate gap on entry: ~1,200
>   uncovered lines across nine packages.
> - In-scope packages and their **post-Track-7 baseline** (line% /
>   branch% — `uncov / total` lines):
>   - `internal.core.storage` — **38.9% / 37.5%** (66 / 108) —
>     top-level constants/SPI shell.
>   - `internal.core.storage.config` — **62.5% / 47.1%** (359 / 957) —
>     **largest gap**; storage-configuration parsing/validation,
>     cluster-position registry, version-guard branches.
>   - `internal.core.storage.memory` — **61.8% / 57.6%** (117 / 306) —
>     in-memory storage engine.
>   - `internal.core.storage.fs` — **72.9% / 65.9%** (62 / 229) —
>     file-system wrappers; line target close.
>   - `internal.core.storage.disk` — **83.3% / 72.2%** (159 / 954) —
>     disk-storage wrapper; line target met, branch slightly under.
>   - `internal.core.storage.collection` — **94.3% / 78.9%** (48 /
>     847) — both targets met; trivial top-up only.
>   - `internal.core.storage.collection.v2` — **91.3% / 76.5%** (136 /
>     1 557) — both targets met; small absolute top-up.
>   - `internal.core.storage.ridbag` — **87.1% / 64.8%** (86 / 668) —
>     line met, **branch under target**.
>   - `internal.core.storage.ridbag.ridbagbtree` — **88.1% / 75.7%**
>     (273 / 2 285) — both targets met but a chunky absolute gap
>     remains.

> **How:**
> - At the **start** of Phase B, regenerate per-package coverage
>   numbers via `./mvnw -pl core -am clean package -P coverage` +
>   `coverage-analyzer.py` against the fresh
>   `.coverage/reports/youtrackdb-core/jacoco.xml`. The Description
>   above uses the post-Track-7 baseline; later tracks may have
>   incidentally lifted some of these packages.
> - **IT-coverage caveat:** `package -P coverage` does not reach
>   `post-integration-test`, so failsafe IT classes
>   (`LocalPaginatedCollectionV2TestIT`, `BTreeTestIT`,
>   `WOWCacheTestIT`, `FreeSpaceMapTestIT`, etc.) do not contribute
>   to the surefire JaCoCo baseline. Some apparent gaps in
>   `core.storage.collection.v2` / `core.storage.disk` may already be
>   IT-covered. Before adding a unit test for a v2 / disk branch,
>   sanity-check the matching `*TestIT` to see whether it already
>   exercises the path — if so, prefer extracting a page-level slice
>   over reimplementing the IT scenario as a unit test.
> - For each package, walk JaCoCo class-level uncovered-line
>   concentrations and prioritise the heaviest contributors first
>   (the JaCoCo HTML report under `.coverage/reports/youtrackdb-core/`
>   gives per-class detail).
> - Test pattern preference (per design § Testing Storage & Cache
>   Components):
>   - **Standalone JUnit 4** for fs wrappers, pure data-structures,
>     and `Externalizable` round-trips where state can be set up
>     directly via constructors. **NOT** suitable for
>     `core.storage.config` (see Step 1b) — the config package's only
>     production class needs a real `AbstractStorage`.
>   - **DbTestBase + AtomicOperation** for `core.storage.config`,
>     memory-storage, collection, and ridbag integration where a live
>     session and an active atomic operation are required
>     (modelled on `LocalPaginatedCollectionAbstract`).
>   - **Page-level pattern** (acquire `ByteBufferPool.acquireDirect()`
>     → wrap in `CachePointer` with `incrementReferrer()` → build
>     `CacheEntryImpl` → `acquireExclusiveLock` → operate →
>     `releaseExclusiveLock` → `decrementReferrer` in `finally`,
>     per `CollectionPageTest` / `RidbagBucketSimpleOpsTest`) for
>     collection/ridbag page-format tests that don't need a full
>     storage lifecycle.
> - Decompose into ~5 steps (one commit per step, fully tested):
>   1. **Storage top-level shell + config** — the two heaviest buckets
>      in the cluster, ~425 uncov lines combined. Two halves:
>      - **(1a) `core.storage` top-level** (66 uncov lines) —
>        standalone JUnit 4: `Externalizable` round-trips on
>        `PhysicalPosition` (52 uncov), `RawBuffer` equality/hash, and
>        small data-class top-ups (`RecordMetadata`, `RawPageBuffer`).
>        Existing host: `StorageReadResultTest` for sibling classes;
>        new class for `PhysicalPosition` round-trips.
>      - **(1b) `core.storage.config.CollectionBasedStorageConfiguration`**
>        (321 uncov lines, sole production class) — DbTestBase +
>        AtomicOperation, modelled on `LocalPaginatedCollectionAbstract`:
>        memory-mode `YouTrackDBImpl` in `@BeforeClass`, drive
>        `setProperty` / `preload*` / `recalculateLocale` /
>        `dropProperty` via
>        `atomicOperationsManager.executeInsideAtomicOperation`.
>        New test class needed (no existing test covers the area).
>   2. **Memory storage** — `core.storage.memory.*`; ~117 uncov lines.
>      Two halves:
>      - `MemoryFile` (6 uncov) and `DirectMemoryOnlyDiskCache`
>        (86 uncov — the largest in-package class; package-private
>        ctor) via direct construction in **same-package**
>        (`c.j.y.internal.core.storage.memory`) tests, extending
>        `MemoryFileClearTest`'s precedent.
>      - `DirectMemoryStorage`-level paths (engine lifecycle, drop
>        semantics) via DbTestBase memory mode — extend
>        `MemoryStorageDropTest` / `StorageDeleteErrorHandlingTest`.
>      - Note: `DirectMemoryStorage` itself has no usable
>        "direct-construction" path — PSI shows its only ctor caller
>        is `EngineMemory.createStorage`, and constructing it requires
>        a fully-built `YouTrackDBInternalEmbedded`.
>   3. **FS wrappers + disk storage** — ~220 uncov lines combined.
>      Two halves:
>      - **(3a) `core.storage.fs.*`** (62 uncov) — standalone with
>        temp directories. Extend `AsyncFileTest`'s pattern
>        (`buildDirectory` system-property + `FileUtils.deleteRecursively`).
>      - **(3b) `core.storage.disk.*`** (159 uncov) — split target.
>        Static helpers / utility nested classes via reflection,
>        extending `DiskStorageFileExtensionsTest` /
>        `DiskStorageValidateFileAndFetchBackupMetadataTest` /
>        `PeriodicRecordsGcTest`. Lifecycle branches (e.g.
>        `validateStorageDirty`, `restoreFromIncrementalBackup`,
>        `DiskStorage` ctor branches) require a disk-mode `DbTestBase`
>        (`-Dyoutrackdb.test.env=ci`) — standalone construction is not
>        viable (PSI: 1 ctor caller, `EngineLocalPaginated.createStorage`,
>        and 8 ctor parameters all requiring real storage components).
>   4. **Collections + ridbag** — ~543 uncov lines combined across four
>      packages. Two complementary patterns:
>      - **Page-level pattern** for `*Page` / `*Bucket` classes —
>        extension targets: `CollectionPageTest` /
>        `CollectionPageSimpleOpsTest` /
>        `CollectionPositionMapBucketOpsTest` for
>        `core.storage.collection`; `RidbagBucketSimpleOpsTest` /
>        `RidbagBucketEntryBulkOpsTest` / `RidbagEntryPointOpsTest` /
>        `EdgeKeyTest` / `LinkBagValueTest` for
>        `core.storage.ridbag.ridbagbtree`. New page tests only when
>        no existing host fits.
>      - **Full storage round-trip** via `DbTestBase` for the heaviest
>        non-page classes: `PaginatedCollectionV2` (~120 uncov in
>        `collection.v2`) and `SharedLinkBagBTree` /
>        `IsolatedLinkBagBTreeImpl` (~143 uncov in `ridbagbtree`).
>        Extend `LocalPaginatedCollectionAbstract` (or a sibling
>        subclass) where it fits; new classes only for sharing /
>        isolation tests if no existing host covers them.
>      - Note: per-package gates for `core.storage.collection.v2`
>        (91.3% / 76.5%) and `core.storage.ridbag.ridbagbtree`
>        (88.1% / 75.7%) are already met. Step 4's value is in the
>        absolute uncov line count, not the gate — page-level pattern
>        alone produces only marginal lift; the heavy contributors
>        require the round-trip half.
>   5. **Verification + top-up** — re-run coverage analyzer, fill any
>      per-package gates that still miss 85% line / 70% branch, write
>      `track-19-baseline.md` and the track episode.

> **Constraints:**
> - Tests must pass in both memory and disk modes
>   (`-Dyoutrackdb.test.env=ci` runs disk).
> - Disk-mode tests must use temp directories and ensure file handles
>   are closed before the temp tree is removed (Windows file-handle
>   release). Either `@Before` cleanup-then-recreate (per `AsyncFileTest`)
>   or `@After` `FileUtils.deleteRecursively` is acceptable as long as
>   the test closes its own files in `try/finally`.
> - JUnit 4 + `surefire-junit47` runner. Mark with
>   `@Category(SequentialTest.class)` whenever the test (a) keeps a
>   class-static `YouTrackDB` / `DatabaseSessionEmbedded` across
>   methods, (b) mutates `GlobalConfiguration` without per-method
>   save/restore, or (c) manipulates engine-level singletons
>   (per `SequentialTest` Javadoc; existing precedents:
>   `LocalPaginatedCollectionAbstract`, `LinkBagAtomicUpdateTest`,
>   `PaginatedCollectionV2OptimisticReadTest`,
>   `SharedLinkBagBTreeOptimisticReadTest`,
>   `RecordsGcReclamationTest`). Per-method `DbTestBase` subclasses
>   that build a fresh memory DB per test do not need the marker.
> - Page-level tests using `ByteBufferPool.acquireDirect()` MUST
>   release via `cachePointer.decrementReferrer()` in `finally`
>   (per `CollectionPageTest` precedent); skipping the release leaks
>   direct memory across the surefire parallel fork.
> - Extend existing storage test classes where scope fits; create new
>   classes only when no existing one covers the area
>   (per `implementation-plan.md` Constraint 6).
> - **Test-additive only.** Per the project's coverage-track pattern
>   (Tracks 14/15/18), Track 19 modifies zero production source. If a
>   test surfaces a production bug, write a falsifiable regression
>   test with the WHEN-FIXED marker convention and forward the fix to
>   Track 22 — do not modify production source in this track.
> - Spotless `apply` after every step; coverage gate on changed lines
>   (≥85% line / ≥70% branch) per CLAUDE.md and constraint table.
> - JaCoCo / testing exclusions per `implementation-plan.md`
>   Constraint 7 — none of Track 19's in-scope packages are excluded.

> **Interactions:**
> - **Depends on:** Track 1 (coverage measurement infrastructure).
> - **Provides for Track 20** (Storage Cache & WAL): the storage
>   config and disk-wrapper test scaffolding patterns.
> - **Provides for Track 21** (Storage B-tree & Impl): the page-level
>   test pattern + collection/ridbag round-trip patterns.
> - **Track 18 cross-track impact:** none — `core/index*` and the
>   Track 19 surface (`core/storage/{config,memory,fs,disk,collection,
>   ridbag}*`) do not overlap.

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/5 complete)
- [ ] Track-level code review

## Base commit
`141f874b6b`

## Reviews completed
- [x] Technical: PASS at iteration 2 (10 findings, all 10 accepted, 0 rejected). Iter-2 gate verified each fix via PSI; no regressions, no new findings.

## Steps

- [x] Step: Storage top-level shell + storage.config
  - [x] Context: info
  > **Risk:** low — default (purely test-additive; new tests for `core.storage` data classes plus a new per-method-lifecycle test class for `CollectionBasedStorageConfiguration` — no production source touched, no shared test infrastructure modified).
  >
  > **What was done:** Added two new test classes (~1,000 LOC, 63 tests, commit `72eeb9032f`):
  > - `PhysicalPositionTest` (446 lines, 31 tests) — standalone JUnit 4 covering `Externalizable` round-trip (writeExternal/readExternal preserves all four fields), `SerializableStream` round-trip (toStream/fromStream), equals/hashCode contract incl. cross-field discrimination, copyFrom semantics, toString format pin, `RecordMetadata` getter coverage. Targets the 52-uncov `PhysicalPosition` shape.
  > - `CollectionBasedStorageConfigurationTest` (~555 lines, 32 tests) — per-method `@Before`/`@After` lifecycle: each test creates a fresh memory DB, opens session, extracts `CollectionBasedStorageConfiguration` via reflection on `AbstractStorage.configuration`, runs assertions inside an `AtomicOperation`, then drops the DB. Covers property get/set/remove/clear, schemaRecordId, indexMgrRecordId, conflictStrategy, charset, dateFormat / dateTimeFormat, timeZone, locale (language+country), validation, uuid, recordSerializer / version, version, binaryFormatVersion, name, creationVersion, configurationUpdateListener pause/fire semantics. `tearDown` swallows `drop()`/`close()` exceptions so per-method cleanup always proceeds even if a test left the DB unopenable. Coverage-gate PASSED (100% line / 100% branch on 6 changed lines — diff is test-additive only).
  >
  > **What was discovered:**
  > - **Latent production deadlock in `CollectionBasedStorageConfiguration.setMinimumCollections`** (`core/src/main/java/.../CollectionBasedStorageConfiguration.java:323`): acquires `lock.writeLock()` then calls `getContextConfiguration()` which tries to acquire `lock.readLock()` on the same non-reentrant `ScalableRWLock`. The Javadoc on `readMinimumCollections` (lines 338-342) explicitly documents the reentrancy trap and works around it by accessing `configuration` directly — but `setMinimumCollections` itself fails to apply that workaround. Symptom observed during Phase B authoring: `JUnitTestListener` deadlock-watchdog dumps diagnostics and halts the surefire JVM after 900 s ("VM crash or System.exit called?", `Tests run: 0`). Documented in a comment block in the test class with a `WHEN-FIXED:` marker; no executable pin because the only available pin would leak a daemon thread spinning in `Thread.yield()` (the `ScalableRWLock` fast path does not respond to interrupt), burning CPU during following tests. **Forwarded to Track 22's deferred-cleanup queue** — fix: replace the `getContextConfiguration()` call inside `setMinimumCollections` (line 326) with a direct `configuration.setValue(...)` call mirroring `readMinimumCollections` (line 346).
  > - **Latent production bug in `CollectionBasedStorageConfiguration.removeProperty`** (`...:1257`): does not invalidate the in-memory `PROPERTIES` cache map. `dropProperty` (line 1738) removes from the persistent `btree` but does not call `properties.remove(name)`, unlike `doSetProperty`'s `properties.put(...)` (line 1095) and `clearProperties`'s `properties.clear()` (line 1247). Consequence: after `setProperty(k, v)` + `removeProperty(k)`, `getProperty(k)` still returns `v` until the cache is rebuilt by a reload. Pinned by `testRemovePropertyDoesNotInvalidateInMemoryCache` and `testRemovePropertyRemovesFromPersistentBtree`, both with `WHEN-FIXED:` markers that flip to the corrected assertions when the cache is invalidated. **Forwarded to Track 22.**
  > - **Class-static `@BeforeClass` + memory DB pattern is incompatible with surefire** when `-Dyoutrackdb.memory.directMemory.trackMode=true` is in the argLine (always, in `core/pom.xml:49`). Holding the storage reference until JVM shutdown causes the page tracker to detect unreleased pages and abort the JVM with `System.exit(1)` before any test runs (`Tests run: 0` despite the class loading successfully). The first attempt at `CollectionBasedStorageConfigurationTest` used `@BeforeClass`/`@AfterClass` (modelled on `LocalPaginatedCollectionAbstract`) and triggered this exact failure; the rewrite to per-method `@Before`/`@After` resolves it. The precedent works because it uses `DatabaseType.DISK`, not `MEMORY` — disk lifecycle releases pages incrementally, memory mode does not.
  > - **`testSetRecordSerializerVersion` poisons the DB**: setting the serializer version to 2 makes the DB unopenable on the next `session.init()` ("Persistent record serializer version is not support by the current implementation"). `tearDown`'s `drop()` reopens the DB internally and fails. Mitigated by capturing the original version in the test, asserting the round-trip, then restoring before tearDown — plus the tearDown swallows drop exceptions. Useful pattern for any test that mutates configuration in ways that affect openability.
  >
  > **What changed from the plan:**
  > - The plan prescribed `@BeforeClass` + `LocalPaginatedCollectionAbstract`-style class-static lifecycle for the config test. **Rejected** — see "Class-static + memory DB" finding above. Replaced with per-method `@Before`/`@After`. **Affects Step 2 (memory storage) and Step 4 (collections + ridbag) directly:** any DB-mode test (memory or disk) using a single class-static instance must verify it does not trip the page-tracker, otherwise default to per-method lifecycle. Disk-mode tests with a class-static lifecycle are acceptable per the precedent.
  > - The plan listed `setMinimumCollections` as one of the methods to exercise — **dropped** (deadlock pin only, no assertion-firing test). `getMinimumCollections` is exercised transitively by other tests (auto-init from locale tests).
  > - The `@Category(SequentialTest.class)` marker was DROPPED for the config test: per-method lifecycle creates an isolated DB per test, so nothing leaks across methods within the class. Surefire's `parallel=classes` still serializes the class against any other class running concurrently. The plan's blanket prescription to mark sequential is too aggressive for this pattern.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/PhysicalPositionTest.java` (new, 446 lines, 31 tests)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/config/CollectionBasedStorageConfigurationTest.java` (new, ~555 lines, 32 tests)
  >
  > **Critical context:** Two latent production bugs (the `setMinimumCollections` deadlock and the `removeProperty` cache-staleness) MUST land in Track 22's deferred-cleanup queue. Phase C of Track 19 (or Track 22's Phase A) needs to commit them to `implementation-backlog.md`'s Track-22 absorption block alongside the existing entries (Track-15/16/17/18 forwarded items). The per-method-lifecycle pattern for memory DB tests is a **cross-track signal to Step 2 / Step 4** — re-evaluate any precedent before reusing it. Step 5's coverage re-measurement will pick up `core.storage.config` and `core.storage` top-level numbers; expect substantial gains in both.

- [ ] Step: Memory storage — MemoryFile, DirectMemoryOnlyDiskCache, DirectMemoryStorage paths
  > **Risk:** low — default (purely test-additive; same-package tests for two directly-constructible classes plus DbTestBase extensions for the engine-level paths).
  >
  > **Scope:**
  > - `MemoryFile` (6 uncov) — standalone same-package tests, extending `MemoryFileClearTest`'s precedent.
  > - `DirectMemoryOnlyDiskCache` (86 uncov — the heaviest in-package class; **package-private** ctor) — same-package tests in `c.j.y.internal.core.storage.memory`.
  > - `DirectMemoryStorage`-level paths (engine lifecycle, drop semantics) via DbTestBase memory mode — extend `MemoryStorageDropTest` / `StorageDeleteErrorHandlingTest`.
  > - `@Category(SequentialTest.class)` only on tests that keep a class-static `YouTrackDB`.
  > - **Verification:** as Step 1.

- [ ] Step: FS wrappers + disk storage
  > **Risk:** low — default (purely test-additive; standalone fs tests plus reflection-based static-helper tests and a disk-mode DbTestBase shell).
  >
  > **Scope:** Two halves landed in one commit.
  > - **(3a) `core.storage.fs.*`** (62 uncov) — standalone with temp directories. Extend `AsyncFileTest` (its `buildDirectory` system-property + `FileUtils.deleteRecursively` cleanup). Close files in `try/finally`.
  > - **(3b) `core.storage.disk.*`** (159 uncov) — split target. (i) Static helpers / utility nested classes via reflection, extending `DiskStorageFileExtensionsTest` / `DiskStorageValidateFileAndFetchBackupMetadataTest` / `PeriodicRecordsGcTest`. (ii) Lifecycle branches that aren't reachable via static helpers (`validateStorageDirty`, `restoreFromIncrementalBackup`, ctor branches) via disk-mode `DbTestBase` (`-Dyoutrackdb.test.env=ci`). PSI: `DiskStorage` ctor has 1 prod caller and 8 parameters, so direct construction is not viable.
  > - **Verification:** as Step 1, plus run with `-Dyoutrackdb.test.env=ci` for the disk-mode tests.

- [ ] Step: Collections + ridbag (page-level + DbTestBase round-trip)
  > **Risk:** low — default (purely test-additive; extends well-established page-level / round-trip test hosts; no shared base-class modifications).
  >
  > **Scope:** ~543 uncov lines combined across `core.storage.collection`, `.collection.v2`, `.ridbag`, `.ridbag.ridbagbtree`. Two complementary patterns:
  > - **Page-level pattern** for `*Page` / `*Bucket` classes — extension targets: `CollectionPageTest` / `CollectionPageSimpleOpsTest` / `CollectionPositionMapBucketOpsTest` (`core.storage.collection`); `RidbagBucketSimpleOpsTest` / `RidbagBucketEntryBulkOpsTest` / `RidbagEntryPointOpsTest` / `EdgeKeyTest` / `LinkBagValueTest` (`core.storage.ridbag.ridbagbtree`). All buffer acquisitions release via `decrementReferrer` in `finally`.
  > - **Full storage round-trip** via `DbTestBase` for the heaviest non-page classes: `PaginatedCollectionV2` (~120 uncov in `collection.v2`) and `SharedLinkBagBTree` / `IsolatedLinkBagBTreeImpl` (~143 uncov in `ridbagbtree`). Extend `LocalPaginatedCollectionAbstract` (or a sibling subclass) where it fits; new classes only for sharing/isolation tests if no existing host covers them. Mark `@Category(SequentialTest.class)`.
  > - Per-package gates for `collection.v2` (91.3% / 76.5%) and `ridbag.ridbagbtree` (88.1% / 75.7%) are already met; this step's value is in the absolute uncov line count, not the gate.
  > - **Verification:** as Step 1.

- [ ] Step: Verification + top-up + Track-19 baseline + track episode
  > **Risk:** low — default (purely test-additive; gate verification, opportunistic top-ups, baseline write).
  >
  > **Scope:**
  > - Re-run `./mvnw -pl core -am clean package -P coverage` and `coverage-analyzer.py` to produce the post-Track-19 per-package coverage table.
  > - Identify any in-scope package whose gate (≥85% line / ≥70% branch) still misses; add focused top-up tests until it passes (or document the miss in the episode if the residual is fundamentally untestable from surefire).
  > - Write `track-19-baseline.md` (snapshot per Track-7 / Track-15 / Track-18 precedent).
  > - Write the track-19 episode (aggregate gain, per-package gates, cross-track signals to Tracks 20–22 if any surface, deferred items for Track 22's queue).
  > - **Verification:** full `./mvnw -pl core clean test` plus `coverage-gate.py` against the cumulative track diff.
