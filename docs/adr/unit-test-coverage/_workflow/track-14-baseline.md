# Track 14 — DB Core & Config — Pre-Track Baseline

Coverage measurement performed at the start of Phase B Step 1 with
`./mvnw -pl core -am clean package -P coverage`. The plan-cited
figures (e.g., `core/db` 1,268 uncov / 66.5% line) are post-Track-7
and stale by six tracks; this document is the load-bearing remeasure
that subsequent steps target.

**Base commit:** `3a93854edd423b51707c037dd4e7f9ff4bba0c8e` (HEAD of
the `unit-test-coverage` branch immediately before Track 14
implementation began).

**JaCoCo report path:**
`.coverage/reports/youtrackdb-core/jacoco.xml`.

## Aggregate (whole `core` module)

- **Line coverage:** 75.1% (70 971 / 94 503 covered, 23 532 uncovered)
- **Branch coverage:** 65.8% (30 433 / 46 221 covered, 15 788 uncovered)
- **Packages:** 178

For comparison, the original plan baseline (Phase 1) was 63.6% line /
53.3% branch / 177 packages. Tracks 1–13 raised aggregate line
coverage by **+11.5 pp** and branch coverage by **+12.5 pp** while
adding one new package.

## Track 14 target packages

| Package | Line% | Branch% | Uncov | Total |
|---|---|---|---|---|
| `core/db` | 66.8% | 52.6% | 1 259 | 3 788 |
| `core/db/config` | 0.0% | n/a | 130 | 130 |
| `core/db/record` | 72.6% | 61.7% | 376 | 1 371 |
| `core/db/record/record` | 58.4% | 37.5% | 69 | 166 |
| `core/db/record/ridbag` | 84.0% | 68.3% | 24 | 150 |

For reference (out-of-scope, Track 15):

| Package | Line% | Branch% | Uncov | Total |
|---|---|---|---|---|
| `core/db/tool` | 61.0% | 49.9% | 889 | 2 278 |
| `core/db/tool/importer` | 59.4% | 48.7% | 73 | 180 |

`common/serialization` post-Track-12 baseline: **83.4% line / 62.9%
branch / 37 uncov / 223 total**. This is the corrected baseline
recorded in Track 12's strategy refresh — Track 14 does not target
`common/serialization`, but `MemoryStream` (still in
`core/serialization/`) re-measurement is queued for Track 14 Step 6
per Track 12 deferred item h.

## Per-class breakdown — `core/db` (1 259 uncov)

| Class | Line% | Branch% | Uncov | Track 14 target step |
|---|---|---|---|---|
| `DatabaseSessionEmbedded` | 68.6% (1465/2135) | 54.2% (672/1239) | 670 | Step 5 (ATTRIBUTES dispatcher) |
| `YouTrackDBInternalEmbedded` | 73.5% (427/581) | 59.4% (101/170) | 154 | (out of scope — engine internals; deferred) |
| `DatabasePoolAbstract` | 21.4% (27/126) | 13.6% (6/44) | 99 | Step 2 (dead-code pin — 1 dead subclass + 1 test subclass) |
| `DatabasePoolBase` | 0.0% (0/49) | 0.0% (0/14) | 49 | Step 2 (dead-code pin — 0 ctor refs, 0 subclasses) |
| `YouTrackDBImpl` | 61.7% (74/120) | 50.0% (9/18) | 46 | (boundary — `YourTracks` factory; partial via Step 5/6 fixtures) |
| `CachedDatabasePoolFactoryImpl` | 58.0% (40/69) | 46.2% (12/26) | 29 | Step 5 (live pool factory — 9 ctor refs) |
| `EntityFieldWalker` | 60.9% (42/69) | 40.9% (27/66) | 27 | (out of scope — owned by Track 15 `record/impl`) |
| `SharedContext` | 83.0% (117/141) | 50.0% (6/12) | 24 | (boundary — exercised by Step 5/6 lifecycle tests) |
| `DatabasePoolAbstract$Evictor` | 27.3% (9/33) | 10.0% (1/10) | 24 | Step 5 (extend `DatabasePoolAbstractEvictionTest`) |
| `YouTrackDBConfigBuilderImpl` | 57.4% (27/47) | 57.1% (8/14) | 20 | Step 6 (config builder) |
| `DatabasePoolBase$1` | 0.0% (0/20) | 0.0% (0/10) | 20 | Step 2 (anonymous inner of dead class) |
| `YouTrackDBConfigImpl` | 73.0% (46/63) | 37.0% (10/27) | 17 | Step 6 |
| `DatabaseStats` | 9.1% (1/11) | n/a | 10 | (deferred — accessor-only POJO; trivial sweep target) |
| `SessionListener` | 20.0% (2/10) | n/a | 8 | (deferred — listener interface default methods) |
| `ExecutionThreadLocal` | 52.9% (9/17) | 33.3% (2/6) | 8 | Step 5 (`@Category(SequentialTest)` constraint) |
| `SystemDatabase` | 87.9% (51/58) | 62.5% (10/16) | 7 | Step 6 (`SequentialTest` — system-DB isolation) |
| `HookReplacedRecordThreadLocal` | 0.0% (0/7) | 0.0% (0/2) | 7 | Step 2 (dead-code pin) |
| `DatabaseLifecycleListener` | 0.0% (0/7) | n/a | 7 | (interface default methods — partial via abstract pin) |
| `DatabaseLifecycleListenerAbstract` | 0.0% (0/6) | n/a | 6 | Step 2 (dead-code pin) |
| `HookReplacedRecordThreadLocal$1` | 0.0% (0/6) | 0.0% (0/2) | 6 | Step 2 (anonymous inner of dead class) |
| `DatabaseLifecycleListener$PRIORITY` | 0.0% (0/6) | n/a | 6 | (deferred — enum constants in interface) |

Smaller residuals (≤ 5 uncov): `YouTrackDBImpl$StandaloneYTDBGraphTraversalSource` (5),
`StringCacheKey` (3 — `StringCache` itself is already 100%), various
`<2`-line items.

**Already at 100% / no uncov:** `StringCache` (19/19, 2/2), `CommandTimeoutChecker`
(31/31, 14/16), `DatabasePoolImpl` (55/56, 22/26 — 1 line / 4 branches
to close in Step 5), `DatabasePoolImpl$1`, `DatabaseSessionEmbeddedPooled`,
`DatabaseSessionEmbedded$ATTRIBUTES`, `DatabaseSessionEmbedded$ATTRIBUTES_INTERNAL`,
`DatabaseSessionEmbedded$STATUS`, `DatabaseSessionEmbedded$TransactionMeters`,
`SchedulerInternal`/`PooledSession`/`EntityPropertiesVisitor`/etc. (interface-only).

## Per-class breakdown — `core/db/config` (130 uncov, 100% dead)

| Class | Uncov | Disposition |
|---|---|---|
| `NodeConfiguration` | 36 | Dead — 0 refs outside package |
| `NodeConfigurationBuilder` | 26 | Dead — 0 refs outside package |
| `MulticastConfguration` | 25 | Dead — 0 refs outside package |
| `MulticastConfigurationBuilder` | 15 | Dead — 0 refs outside package |
| `UDPUnicastConfiguration` | 14 | Dead — 0 refs outside package |
| `UDPUnicastConfigurationBuilder` | 13 | Dead — 0 refs outside package |
| `UDPUnicastConfiguration$Address` | 1 | Dead — record component accessor |

**Step 1 outcome:** behavioural shape pinned via `DBConfigDeadCodeTest`
exercising every public ctor / public+protected setter / getter /
static `builder()` factory / `build()` arm. The test file is added
to the deferred-cleanup track absorption block so a future deletion
removes both the production package and the test file in one commit.

The 130 uncov lines are not expected to enter the "covered" column —
the WHEN-FIXED disposition is **delete** rather than **drive**.

## Per-class breakdown — `core/db/record` (376 uncov)

| Class | Line% | Branch% | Uncov | Track 14 target step |
|---|---|---|---|---|
| `EntityLinkMapIml` (sic) | 70.5% (177/251) | 55.4% (56/101) | 74 | Step 4 |
| `EntityLinkListImpl` | 72.6% (154/212) | 64.5% (49/76) | 58 | Step 4 |
| `EntityLinkSetImpl` | 61.1% (88/144) | 38.5% (25/65) | 56 | Step 4 |
| `EntityEmbeddedListImpl` | 78.1% (143/183) | 72.2% (52/72) | 40 | Step 4 |
| `EntityEmbeddedMapImpl` | 78.0% (135/173) | 67.8% (59/87) | 38 | Step 4 |
| `EntityEmbeddedSetImpl` | 84.8% (112/132) | 79.7% (47/59) | 20 | Step 4 (residual) |
| `RecordElement` | 43.8% (14/32) | 50.0% (10/20) | 18 | (boundary — abstract base; partial via Step 4 fixtures) |
| `RecordMultiValueHelper` | 0.0% (0/16) | 0.0% (0/16) | 16 | Step 2 (dead-code pin) |
| `RecordOperation` | 50.0% (8/16) | 37.5% (3/8) | 8 | (small POJO — pick up via Step 4 traffic) |
| `EntityEmbeddedMapImpl$EntrySet` | 27.3% (3/11) | 0.0% (0/2) | 8 | Step 4 (inner) |
| `EntityEmbeddedMapImpl$EntryIterator` | 46.2% (6/13) | 0.0% (0/2) | 7 | Step 4 (inner) |
| `LinkTrackedMultiValue` | 75.0% (18/24) | 72.7% (16/22) | 6 | Step 4 (residual) |
| `RecordMultiValueHelper$MULTIVALUE_CONTENT_TYPE` | 0.0% (0/5) | n/a | 5 | Step 2 (enum in dead helper) |
| `EntityEmbeddedMapImpl$TrackerEntry` | 50.0% (5/10) | n/a | 5 | Step 4 (inner) |
| `EntityLinkMapIml$LinkEntrySet` | 50.0% (4/8) | n/a | 4 | Step 4 (inner) |
| `MultiValueChangeEvent` | 89.7% (26/29) | 50.0% (10/20) | 3 | Step 3 (POJO standalone) |

Smaller residuals (≤ 3 uncov): `EntityEmbeddedSetImpl$1` (3), `EntityLinkSetImpl$1` (2),
`EmbeddedTrackedMultiValue` (2), `CurrentStorageComponentsFactory` (1),
`EntityLinkMapIml$LinkEntryIterator` (1), `EntityLinkMapIml$LinkEntry` (1).

**Already at 100%:** `MultiValueChangeTimeLine` (5/5), `TrackedMultiValue` (43/43),
`MultiValueChangeEvent$ChangeType` (4/4), `RecordElement$STATUS` (5/5),
`ProxedResource` (4/4).

**Interface-only (no executable lines):** `StorageBackedMultiValue`,
`TrackedCollection`.

## Per-class breakdown — `core/db/record/record` (69 uncov)

| Class | Line% | Branch% | Uncov | Track 14 target step |
|---|---|---|---|---|
| `EntityHookAbstract` | 58.8% (30/51) | 44.4% (16/36) | 21 | Step 2 (dead-code pin — caveat: PSI-confirm no production subclass) |
| `RecordHookAbstract` | 0.0% (0/17) | 0.0% (0/8) | 17 | Step 2 (dead-code pin — caveat: PSI-confirm no production subclass) |
| `Vertex` | 31.2% (5/16) | 50.0% (8/16) | 11 | Step 3 (default methods reachable without session) |
| `Edge` | 40.0% (4/10) | 37.5% (3/8) | 6 | Step 3 (default methods) |
| `Entity` | 88.2% (45/51) | n/a | 6 | (boundary — interface default methods; partial via Step 3 traffic) |
| `Direction` | 44.4% (4/9) | 0.0% (0/4) | 5 | Step 3 (enum standalone) |
| `RID` | 0.0% (0/2) | n/a | 2 | Step 3 (parsing — cross-check `core/id/RID*Test` first) |
| `EmbeddedEntity` | 0.0% (0/1) | n/a | 1 | Step 3 (interface default method?) |

**Already at 100%:** `RecordHook$TYPE` (8/8), `RecordHook` (1/1).

**Interface-only (no executable lines):** `Identifiable`, `Blob`, `DBRecord`.

**Coverage credit overlap caveat:** Several `record/record`
default-method branches are double-counted with Track 15's
`record/impl` scope — when `EntityImpl` exercises a `Vertex` /
`Edge` default method, JaCoCo attributes the line both to
`record/record` (where the default lives) and contributes to
`record/impl`'s branch coverage indirectly. Step 3 records
attribution in its episode for Track 15's strategy refresh.

## Per-class breakdown — `core/db/record/ridbag` (24 uncov)

| Class | Line% | Branch% | Uncov | Track 14 target step |
|---|---|---|---|---|
| `LinkBag` | 84.5% (120/142) | 67.9% (38/56) | 22 | Step 4 (embedded↔B-tree conversion) |
| `LinkBagDeleter` | 85.7% (6/7) | 75.0% (3/4) | 1 | Step 4 (residual) |
| `LinkBagDelegate` | 0.0% (0/1) | n/a | 1 | (interface default method?) |

## Step 1 spot-check of existing tests for inert-test bugs

Per Track 12's lesson, three existing `core/db/*Test.java` classes
were inspected for inert-test bugs (missing `@Test` annotation,
identity-based `assertEquals(byte[], byte[])`, dead body):

| Test class | LOC | `@Test` count | Inert tests? |
|---|---|---|---|
| `StringCacheTest.java` | 32 | 2 | None — both annotated, real assertions. (Expected step-2 extension target.) |
| `DBRecordLazyListTest.java` | 87 | 1 | None — single happy-path `test()`; assertions intact. |
| `DatabasePoolImplTest.java` | 588 | 11 | None — comprehensive lifecycle / concurrent / regression coverage with strong assertions. |

**Outcome:** zero repairs needed. The Track 12 inert-converter-test
finding does not generalise to the `core/db` test surface; the
existing tests are healthy.

## Phase B step targeting — derived per-step uncov budget

Using the per-class data above, the rough Track 14 step-by-step
uncov budget is:

| Step | Target classes | Approx uncov delta |
|---|---|---|
| Step 1 (this step) | `db/config` package + spot-check | 130 dead — pinned only, no live coverage delta |
| Step 2 | `DatabasePoolBase` + `DatabasePoolBase$1` + `DatabasePoolAbstract` partial + helper / hook-abstract pins + `StringCache` extension | ~120 dead pinned + small live delta |
| Step 3 | `Direction`, `RID` parsing residual, `Vertex` / `Edge` / `Identifiable` default methods, `MultiValueChangeEvent` / `TimeLine` POJO | ~25–30 |
| Step 4 | `EntityLinkMapIml` / `EntityLinkListImpl` / `EntityLinkSetImpl` + Embedded variants + `LinkBag` conversion | ~250–300 |
| Step 5 | `DatabaseSessionEmbedded.set` / `setCustom` ATTRIBUTES dispatcher + pool path (`DatabasePoolImpl` 1 line / 4 branches, `DatabasePoolAbstract$Evictor`, `CachedDatabasePoolFactoryImpl`) | ~80 (ATTRIBUTES dispatcher) + ~55 (pool live path) |
| Step 6 | `YouTrackDBConfigImpl` / `Builder`, `SystemDatabase`, `CommandTimeoutChecker` residual, `MemoryStream` re-measure | ~50 + Track 12 deferred item h re-eval |

**Acceptance bands** (per Phase A R6, refined here):

- `core/db` aggregate: target ≥ 75% line / ≥ 62% branch (from
  66.8%/52.6%) by Step 6 verification — 1 259 uncov reduced by
  ~330–390 to ≤ 925.
- `core/db/config`: stays at 0% live — every line is pinned dead;
  the deletion item lands in the deferred-cleanup track. Phase A's
  D4-style acceptance applies here.
- `core/db/record`: target ≥ 80% line / ≥ 70% branch by Step 4
  completion (from 72.6% / 61.7%).
- `core/db/record/record`: target ≥ 70% line / ≥ 55% branch by Step 3
  completion (from 58.4% / 37.5%) — modest because most uncov is
  dead-code pinned in Step 2.
- `core/db/record/ridbag`: target ≥ 92% line / ≥ 80% branch by Step 4
  completion (from 84.0% / 68.3%).

These acceptance bands are reassessed at Step 6 verification — if
the live-coverage delta exceeds the bands, the residual is recorded
in Step 6's episode for the deferred-cleanup track absorption block.

---

## Post-track measurement (Step 6 verification)

Coverage measurement performed at the end of Phase B Step 6 with
`./mvnw -pl core -am clean package -P coverage` (build time 9 m 56 s,
exit 0). Coverage gate vs `origin/develop` PASSED — 100.0% line / 100.0%
branch on changed production lines (trivially, since Track 14 is purely
test-additive).

### Aggregate (whole `core` module) — post-track

- **Line coverage:** 75.9% (71 703 / 94 503 covered, 22 800 uncov)
- **Branch coverage:** 66.4% (30 685 / 46 221 covered, 15 536 uncov)
- **Packages:** 178 (unchanged)

Track 14 contribution to aggregate: **+0.8 pp line / +0.6 pp branch**
(75.1% → 75.9% line; 65.8% → 66.4% branch). Tracks 1–14 cumulative:
+12.3 pp line / +13.1 pp branch from the original Phase 1 baseline of
63.6% / 53.3%.

### Track 14 target packages — pre/post comparison

| Package | Pre (Step 1) | Post (Step 6) | Δ uncov | Acceptance |
|---|---|---|---|---|
| `core/db` | 66.8% / 52.6% (1 259) | **71.6% / 57.1% (1 077)** | −182 | ✗ band ≥ 75% / ≥ 62% (line −3.4 pp, branch −4.9 pp) |
| `core/db/config` | 0.0% / n/a (130) | **95.4% / 100.0% (6)** | −124 | (target was "stay 0% live"; behavioural shape pin drove dead-code methods) |
| `core/db/record` | 72.6% / 61.7% (376) | **92.0% / 80.0% (110)** | −266 | ✓ band ≥ 80% / ≥ 70% — **exceeded** |
| `core/db/record/record` | 58.4% / 37.5% (69) | **89.2% / 76.4% (18)** | −51 | ✓ band ≥ 70% / ≥ 55% — **exceeded** |
| `core/db/record/ridbag` | 84.0% / 68.3% (24) | **87.3% / 78.3% (19)** | −5 | ✗ band ≥ 92% / ≥ 80% — conversion paths storage-IT only |

`core/db` falls 3.4 pp / 4.9 pp short of the Step 1 acceptance band
because `DatabaseSessionEmbedded` still has 636 uncov lines (was 670;
Step 5 ATTRIBUTES dispatcher work closed only 34 of the 670). The
remaining 636 uncov in this 2 135-line class dominate the package
aggregate. Closing that gap is out-of-scope for Track 14 by design —
the per-track `D2` override and the coverage-gate framing in the
Track 14 description constrained Step 5 to the dispatcher branches,
not the whole class. The residual is forwarded to the deferred-cleanup
track as "DatabaseSessionEmbedded coverage residual" for opportunistic
sweep absorption.

`core/db/config` rose to 95.4% / 100.0% live coverage (vs the
plan's "stays at 0% live" — the dead-code pin actually drives every
public-method branch). Net effect on the deferred-cleanup queue is
unchanged: the package is still queued for deletion; the 124 newly-
covered lines simply mean the pre-deletion shape is now load-bearing
under JaCoCo measurement, which the existing `DBConfigDeadCodeTest`
guards.

### Per-class breakdown — Step 5 + Step 6 target classes

| Class | Pre (Step 1) | Post (Step 6) | Outcome |
|---|---|---|---|
| `DatabaseSessionEmbedded` | 68.6% / 54.2% / 670 uncov | **70.2% / 56.4% / 636 uncov** | Step 5 ATTRIBUTES dispatcher closed −34 uncov |
| `YouTrackDBConfigImpl` | 73.0% / 37.0% / 17 uncov | **98.4% / 74.1% / 1 uncov** | Step 6 — exceeded; 1 residual line is a dead branch in `setParent`'s null-attributes guard |
| `YouTrackDBConfigBuilderImpl` | 57.4% / 57.1% / 20 uncov | **100% / 100% / 0 uncov** | Step 6 — full surface |
| `CommandTimeoutChecker` | 100% / 87.5% / 0 uncov | **100% / 93.8% / 0 uncov** | Step 6 — closed 1 of 2 residual branches; 1 remaining is the `period <= 0` ScheduledExecutor edge that requires bypassing the public CTOR |
| `SystemDatabase` | 87.9% / 62.5% / 7 uncov | **98.3% / 93.8% / 1 uncov** | Step 6 — exceeded; 1 residual line is the `context.isMemoryOnly() ? MEMORY : DISK` branch unreachable when DbTestBase already chose memory mode |
| `ExecutionThreadLocal` | 52.9% / 33.3% / 8 uncov | **100% / 100% / 0 uncov** | Step 5 — full surface |
| `CachedDatabasePoolFactoryImpl` | 58.0% / 46.2% / 29 uncov | **100% / 92.3% / 0 uncov** | Step 5 — full surface; 2 residual branches in cleanup-sweep edge cases |

### `MemoryStream` — Track 12 deferred item h re-measurement

Track 12's deferred item h queued a re-measurement of
`core/serialization/MemoryStream` after Track 14's CRUD-driven workload
(`RecordId*` callers were expected to drive the residual record-id
paths). Post-Step-6 measurement:

| Class | Pre (Track 12) | Post (Step 6) | Note |
|---|---|---|---|
| `MemoryStream` | (≈37 uncov / `core/serialization` root 78 uncov) | **62.3% / 58.0% / 69 uncov of 183 total** | Track 14 CRUD did not drive the residual `RecordId*` paths as expected — 69 uncov lines remain |

The residual concentrates in the `MemoryStream` deprecated record-id
constructor path and the `setSize / available / readableBytes` legacy
helpers. The Track 12 deferred item h is **kept open** in the deferred-
cleanup absorption block — paired with Track 15's `record/impl` work,
since `EntityImpl` exercising `RecordId.fromStream(MemoryStream)` is the
remaining production caller that would drive the residual.

### Step 6 sub-deliverables — outcomes

- Extended `YouTrackDBConfigImplTest` from 4 → 28 `@Test` methods
  (24 new): full `fromApacheConfiguration` 7-arm dispatcher coverage,
  full `toApacheConfiguration` 6-attribute round-trip, listener wiring,
  internal builder methods (`setSecurityConfig`, `fromContext`,
  `addGlobalUser`), public no-arg ctor surface, all eight `setParent`
  branches plus the protected ctor's null-listener fallback. Coverage:
  Builder 57.4% / 57.1% → 100% / 100%; Impl 73.0% / 37.0% → 98.4% / 74.1%.
- Extended `CommandTimeoutCheckerTest` from 2 → 9 `@Test` methods
  (7 new): worker-thread isolation invariants per risk review's R7
  finding, disabled-mode CTOR with `timeout = 0` and `timeout = -1`
  (close + null-timer tolerance), per-command timeout overriding the
  default, registered-thread-only interrupt targeting via a bystander
  worker, `endCommand` unregistration before timeout fires, idempotent
  `endCommand` + `startCommand` re-registration, and `close()` cancels
  the periodic timer. Coverage: 100% / 87.5% → 100% / 93.8%.
- Added `SystemDatabaseTest` (13 `@Test` methods, 370 LOC,
  `@Category(SequentialTest)` per risk review's R8 finding): lazy-init
  on first access, server-id stability across `init()` cycles
  (`browseClass` "row already exists" branch), execute / query /
  executeInDBScope / executeWithDB callback delegators with assertions
  on session identity / transaction context, `getSystemDatabase()`
  returns the same instance, late-mutation invariant on the captured-
  at-ctor enabled flag, public-constants pin, malformed-SQL recovery
  pin, and a falsifiable-regression pin for the latent shape where
  `openSystemDatabaseSession()` does not populate `serverId` when the
  OSystem DB already exists from a previous run. Coverage: 87.9% / 62.5%
  → 98.3% / 93.8%.

### Step 6 deferred items added to the deferred-cleanup absorption block

- **`DatabaseSessionEmbedded.setCustom` latent NPE** (Step 5
  discovery, recorded in Step 6 episode): when `iValue == null` and
  `name` is non-clear, `customValue.isEmpty()` runs on null. Pinned via
  `setCustomNonClearNameNullValueThrowsNpePinningLatentBug`; deferred-
  cleanup options are (a) treat as remove or (b) guard
  `customValue == null` before `.isEmpty()`.
- **`DatabaseSessionEmbedded` TIMEZONE backward-compat comment lag**
  (Step 5): the upper-case-then-original-string retry succeeds for
  already-correctly-cased ids but the comment claims lowercase
  acceptance. Pinned as observed shape; deferred-cleanup item is to
  either tighten the retry or drop the misleading comment.
- **`DatabaseSessionEmbedded` `setCustom` Object stringification via
  `"" + iValue`** (Step 5): refactor candidate, no behaviour change.
- **`SystemDatabase.openSystemDatabaseSession` does not populate
  `serverId` when DB already exists** (Step 6): falsifiable shape
  pinned; deferred-cleanup options are (a) move `checkServerId` out of
  `init()` so it's always called on first open, or (b) eagerly call
  `init()` in the ctor when `enabled`.
- **`MemoryStream` deprecated record-id paths** (Track 12 deferred
  item h carried forward): 69 of 183 lines remain uncovered;
  deferred-cleanup re-evaluation paired with Track 15's `record/impl`
  scope.
- **`DatabaseSessionEmbedded` overall coverage residual**: 636 uncov
  lines remain after Step 5's targeted ATTRIBUTES dispatcher work
  (closed 34 of 670). The remaining residual is out-of-scope-by-design
  per the Track 14 description's coverage-gate framing for this class;
  forwarded to deferred-cleanup for opportunistic sweep absorption.
