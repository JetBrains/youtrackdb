# Track 16 — Metadata Schema & Functions — Pre-Track Baseline

Coverage measurement performed at the start of Phase B Step 1 with
`./mvnw -pl core -am clean package -P coverage`. The plan-cited
figures (`core/metadata/schema` ≈ 1 278 uncov / `core/metadata/function`
≈ 74 uncov / `core/metadata/sequence` ≈ 75 uncov / `core/metadata/schema/clusterselection`
≈ 18 uncov) come from the original Phase 1 baseline and are stale by
fifteen tracks; this document is the load-bearing remeasure that
subsequent steps target.

**Base commit:** `707ba2b1111e63ac674ebbc9eea6beecc724fdd2` (HEAD of
the `unit-test-coverage` branch immediately before Track 16
implementation began — Phase B kickoff commit recorded as
`Record Phase B base commit for Track 16`).

**JaCoCo report path:**
`.coverage/reports/youtrackdb-core/jacoco.xml`.

## Aggregate (whole `core` module)

- **Line coverage:** 76.1% (71 934 / 94 503 covered, 22 569 uncov)
- **Branch coverage:** 66.7% (30 830 / 46 221 covered, 15 391 uncov)
- **Packages:** 178

For comparison, the original plan baseline (Phase 1) was 63.6% line /
53.3% branch / 177 packages. Tracks 1–15 raised aggregate line
coverage by **+12.5 pp** and branch coverage by **+13.4 pp** while
adding one new package.

## Track 16 target packages

| Package | Line% | Branch% | Uncov | Total |
|---|---|---|---|---|
| `core/metadata/schema` | 71.7% | 57.2% | 1 231 | 4 355 |
| `core/metadata/schema/clusterselection` | 63.3% | 31.2% | 18 | 49 |
| `core/metadata/function` | 73.3% | 45.8% | 71 | 266 |
| `core/metadata/sequence` | 85.4% | 73.4% | 70 | 478 |

For reference (out-of-scope-by-design — already at near-full coverage):

| Package | Line% | Branch% | Uncov | Total |
|---|---|---|---|---|
| `core/metadata/schema/schema` (interface package) | 98.3% | 100.0% | 1 | 58 |
| `core/metadata/schema/validation` (5 `Validation*Comparable` wrappers) | 100.0% | 100.0% | 0 | 20 |

The `schema/schema` 1-line residual is a single uncov line in `Collate`
(line coverage 2/3, no branches measured) — left to incidental sweep.
The `schema/validation` package is at full coverage.

### Plan-cited vs measured deltas

| Package | Plan-cited uncov | Measured uncov (Step 1) | Delta |
|---|---:|---:|---:|
| `core/metadata/schema` | ≈ 1 278 | **1 231** | −47 (closed by intervening tracks via traffic) |
| `core/metadata/schema/clusterselection` | ≈ 18 | **18** | 0 (unchanged) |
| `core/metadata/function` | ≈ 74 | **71** | −3 |
| `core/metadata/sequence` | ≈ 75 | **70** | −5 |

The largest plan-vs-measured delta is in `metadata/schema` (−47),
attributable to incidental traffic during Tracks 14–15 (`record/impl`
schema-class lookups exercising `SchemaShared.getClass(...)` paths).
The package still has **1 231 uncov / 4 355 total**, which dominates
Track 16's effective target denominator.

### Aggregate-package deltas vs the original Phase A figure

The original plan-file figure for `core/metadata/schema` was
"~1,278 uncov pre-review, **stale baseline — remeasure in Step 1**"
(per the Track 16 description). Step 1's remeasure confirms the
package is still the largest single uncov region under
`core/metadata/*` and consolidates the per-class targets below.

## Per-class breakdown — `core/metadata/schema` (1 231 uncov)

| Class | Line% | Branch% | Uncov | Track 16 target step |
|---|---|---|---|---|
| `PropertyTypeInternal` (outer enum) | 66.0% (341/517) | 62.7% (288/459) | 176 | Step 4 / Step 5 (parameterized `convert`) |
| `SchemaClassImpl` | 75.2% (519/690) | 55.0% (164/298) | 171 | Step 3 |
| `SchemaPropertyImpl` | 67.2% (203/302) | 60.0% (99/165) | 99 | Step 2 |
| `SchemaImmutableClass` | 67.3% (187/278) | 57.4% (70/122) | 91 | Step 3 |
| `SchemaClassEmbedded` | 70.4% (216/307) | 60.8% (79/130) | 91 | Step 3 |
| `SchemaClassProxy` | 67.7% (157/232) | 39.6% (72/182) | 75 | Step 6 (interface-dispatch) |
| `SchemaEmbedded` | 74.7% (180/241) | 62.7% (79/126) | 61 | Step 6 |
| `SchemaShared` | 84.0% (316/376) | 66.5% (113/170) | 60 | Step 6 (lock API) |
| `ImmutableSchemaProperty` | 69.4% (136/196) | 69.9% (95/136) | 60 | Step 2 (immutability shape) |
| `SchemaProxy` | 66.4% (97/146) | 44.6% (50/112) | 49 | Step 6 (interface-dispatch) |
| `SchemaPropertyEmbedded` | 84.1% (206/245) | 74.3% (52/70) | 39 | Step 2 |
| `ImmutableSchema` | 62.1% (54/87) | 68.2% (15/22) | 33 | Step 6 |
| `SchemaPropertyProxy` | 80.0% (108/135) | 43.6% (48/110) | 27 | Step 6 (interface-dispatch) |
| `IndexConfigProperty` | 0.0% (0/13) | 0.0% (0/0) | 13 | **Step 1 (this step) — dead-code pin** |
| `SchemaShared$CollectionIdsAreEmptyException` | 0.0% (0/1) | 0.0% (0/0) | 1 | (boundary — pinned via Step 6 traffic if reachable) |
| `SchemaClassInternal` | 87.5% (7/8) | 75.0% (3/4) | 1 | Step 3 (interface default method) |
| `GlobalPropertyImpl` | 100.0% (20/20) | 0.0% (0/0) | 0 | (already covered) |

### `PropertyTypeInternal` anonymous-instance subclass body residuals

The 21 anonymous enum-instance bodies under `PropertyTypeInternal`
(JaCoCo rows `$1` … `$21`) carry the bulk of the residual uncov
beyond the outer enum (~195 uncov across the 21 inner classes).
The largest are:

| Class | Line% | Branch% | Uncov |
|---|---|---|---|
| `PropertyTypeInternal$13` | 64.9% (48/74) | 44.2% (23/52) | 26 |
| `PropertyTypeInternal$11` | 72.2% (52/72) | 65.7% (23/35) | 20 |
| `PropertyTypeInternal$12` | 63.0% (34/54) | 54.2% (13/24) | 20 |
| `PropertyTypeInternal$15` | 55.8% (24/43) | 44.4% (8/18) | 19 |
| `PropertyTypeInternal$16` | 55.8% (24/43) | 44.4% (8/18) | 19 |
| `PropertyTypeInternal$17` | 60.0% (24/40) | 36.8% (7/19) | 16 |
| `PropertyTypeInternal$14` | 67.4% (31/46) | 40.5% (15/37) | 15 |
| `PropertyTypeInternal$21` | 65.6% (21/32) | 56.2% (9/16) | 11 |
| `PropertyTypeInternal$10` | 73.3% (22/30) | 54.5% (6/11) | 8 |
| `PropertyTypeInternal$20` | 69.2% (9/13) | 44.4% (4/9) | 4 |
| `PropertyTypeInternal$1` … `$9` (smaller residuals) | 70-90% | 40-80% | 1-3 each |

Steps 4 + 5 (parameterized `convert(...)`) target these directly.

## Per-class breakdown — `core/metadata/schema/clusterselection` (18 uncov)

| Class | Line% | Branch% | Uncov | Disposition |
|---|---|---|---|---|
| `BalancedCollectionSelectionStrategy` | 25.0% (4/16) | 0.0% (0/10) | 12 | **Step 1 — dead-code pin (lockstep delete)** |
| `CollectionSelectionFactory` | 82.6% (19/23) | 75.0% (3/4) | 4 | **Step 1 — dead-code pin (`getStrategy(String)` + SPI loop)** |
| `DefaultCollectionSelectionStrategy` | 50.0% (2/4) | 0.0% (0/0) | 2 | **Step 1 — dead-code pin (lockstep delete)** |
| `RoundRobinCollectionSelectionStrategy` | 100.0% (6/6) | 100.0% (2/2) | 0 | Live (`SchemaClassImpl` direct `new`); confirmed 100% — no Step 1 work needed |

**Step 1 outcome:** behavioural shape pinned via four
`*DeadCodeTest` classes:

1. `IndexConfigPropertyDeadCodeTest` — pins shape (modifiers,
   constructor, all five getters, self-recursive `copy()` identity vs
   field-level reference equality).
2. `BalancedCollectionSelectionStrategyDeadCodeTest` — pins shape
   (modifiers, `interface CollectionSelectionStrategy` impl, `NAME ==
   "balanced"`, length-1 short-circuit, multi-cluster `min(approxCount)`
   selection via Mockito stubs, two-arg form delegation).
3. `DefaultCollectionSelectionStrategyDeadCodeTest` — pins shape
   (modifiers, impl, `NAME == "default"`, two-arg "always-first-cluster"
   contract, four-arg form's surprising-but-true ignore-of-`selection`
   parameter).
4. `CollectionSelectionFactoryDeadCodeTest` — pins shape (constructor
   wires `RoundRobin` as default class, SPI loop registers all three
   keys, registry maps each key to the correct `Class`, `getStrategy`
   returns fresh instances per call, unknown / null keys fall back to
   round-robin default, `unregisterAll` exposes default-only behaviour,
   SPI service file membership, classloader visibility).

The 4 + 12 + 2 + (4 of 23) = **18 uncov lines** in this package are
not expected to enter the "covered" column — they are pinned dead-
LOC and the WHEN-FIXED disposition is **delete** (lockstep group:
Balanced + Default strategies + `getStrategy(String)` dispatcher +
SPI-registry plumbing + the corresponding service-file entries).
`IndexConfigProperty` (13 uncov) deletes solo. The Step 1 dead-code
pins drive each class's public surface, so the live-LOC denominator
shifts: the dead-code pinning will move these from uncov to "covered
behaviour pinned for delete" — JaCoCo will report higher line %
post-Step-1 even though no production logic was added.

## Per-class breakdown — `core/metadata/function` (71 uncov)

| Class | Line% | Branch% | Uncov | Track 16 target step |
|---|---|---|---|---|
| `Function` | 74.7% (71/95) | 62.5% (15/24) | 24 | Step 7 (record round-trip via `IdentityWrapper`) |
| `FunctionLibraryImpl` | 82.2% (97/118) | 50.0% (15/30) | 21 | Step 7 (live drive + extension of `FunctionLibraryTest`) |
| `DatabaseFunction` | 36.4% (8/22) | 16.7% (1/6) | 14 | Step 7 (SPI dispatch via stored function + `SELECT myFn(...)`) |
| `FunctionUtilWrapper` | 25.0% (2/8) | 0.0% (0/10) | 6 | Step 7 (POJO standalone) |
| `FunctionLibraryProxy` | 60.0% (9/15) | 0.0% (0/0) | 6 | Step 7 (interface-dispatch via `FunctionLibrary`) |
| `FunctionDuplicatedException` | 100.0% (2/2) | 0.0% (0/0) | 0 | (already covered) |
| `DatabaseFunctionFactory` | 100.0% (6/6) | 100.0% (2/2) | 0 | (already covered) |

## Per-class breakdown — `core/metadata/sequence` (70 uncov)

The package is **already above the project gate** at 85.4% line /
73.4% branch. Track 16 collapses sequence into a half-step (per
Phase A R3 collapse) targeting the residual edges:

| Class | Line% | Branch% | Uncov | Track 16 target step |
|---|---|---|---|---|
| `DBSequence` | 78.2% (111/142) | 58.3% (28/48) | 31 | Step 7 (extend `DBSequenceTest`: tx-error retry, ordered/cached boundary, `next() == start + N*increment` invariant) |
| `SequenceCached` | 89.5% (94/105) | 83.3% (60/72) | 11 | Step 7 (cache exhaustion / refill edges) |
| `SequenceLibraryImpl` | 89.2% (83/93) | 73.1% (19/26) | 10 | Step 7 (registration / reload / drop edges) |
| `SequenceLibraryProxy` | 60.0% (9/15) | 0.0% (0/0) | 6 | Step 7 (interface-dispatch via `SequenceLibrary`) |
| `DBSequence$SEQUENCE_TYPE` | 54.5% (6/11) | 0.0% (0/3) | 5 | Step 7 (enum standalone) |
| `DBSequence$CreateParams` | 94.0% (47/50) | 50.0% (7/14) | 3 | Step 7 (CreateParams round-trip) |
| `SequenceHelper` | 81.8% (9/11) | 100.0% (4/4) | 2 | Step 7 (residual edge) |
| `SequenceOrdered` | 97.0% (32/33) | 95.5% (21/22) | 1 | (boundary — picked up via DBSequence traffic) |
| `SequenceOrderType` | 90.9% (10/11) | 66.7% (2/3) | 1 | (boundary) |
| `SequenceLibraryAbstract` / `SequenceLibraryImpl$1` | 100% / 100% | — | 0 | (already covered) |

## Step 1 spot-check of existing tests for inert-test bugs

Per Track 12's lesson, the adversarial review iter-1 already
spot-checked five existing `core/metadata/*Test.java` classes for
inert-test bugs (missing `@Test` annotation, identity-based
`assertEquals(byte[], byte[])`, dead body). Step 1 re-verifies that
finding via PSI inspection of the same files:

| Test class | LOC | `@Test` count | Inert tests? |
|---|---|---|---|
| `SchemaClassImplTest` | 322 | 16 | None — every method annotated, real assertions, `@Before`/`@After` lifecycle. |
| `CaseSensitiveClassNameTest` | 158 | 6 | None — clean. |
| `SchemaPropertyTypeConvertTest` | 456 | 54 | None — parameterized over `PropertyTypeInternal` arms. |
| `DBSequenceTest` | 967 | 26 | None — comprehensive coverage of sequence types, transaction / increment / boundary / cache cases. |
| `FunctionLibraryTest` | (≈300 LOC) | 11 | None — clean. |

**Outcome:** zero repairs needed. Adversarial review's A9 finding
(all five clean) is confirmed at Phase B Step 1.

## Phase B step targeting — derived per-step uncov budget

Using the per-class data above, the rough Track 16 step-by-step
uncov budget is:

| Step | Target classes | Approx uncov delta |
|---|---|---|
| Step 1 (this step) | `IndexConfigProperty` + cluster-selection trio + SPI service file | 13 + 4 + 12 + 2 = 31 dead — pinned only, no live coverage delta in the "covered logic" sense, but JaCoCo will move the 31 lines to "covered" via the pinning tests |
| Step 2 | `SchemaPropertyImpl` / `SchemaPropertyEmbedded` / `ImmutableSchemaProperty` / `GlobalPropertyImpl` | ~150–200 |
| Step 3 | `SchemaClassImpl` / `SchemaClassEmbedded` / `SchemaImmutableClass` + `Internal` / `Proxy` boundary | ~200–250 |
| Step 4 | `PropertyTypeInternal` numeric + datetime/binary families | ~80–120 |
| Step 5 | `PropertyTypeInternal` collection + link + embedded families | ~80–120 |
| Step 6 | `SchemaShared` + `ImmutableSchema` + cluster-selection live + Schema proxy boundary | ~80–120 |
| Step 7 | Function library + DBSequence half-step + final verification | ~70–100 |

**Acceptance bands** (per Phase A R6, refined here):

- `core/metadata/schema` aggregate: target ≥ 80% line / ≥ 65% branch
  (from 71.7% / 57.2%) by Step 7 verification — 1 231 uncov reduced
  by ~600 to ≤ 630.
- `core/metadata/schema/clusterselection`: target stays at 63%
  effective live (the 18 uncov are dead-pinned at Step 1; the
  factory's live `getCollectionSelectionFactory()` consumer trail
  has 0 callers, so live delta is zero). The deletion item lands in
  the deferred-cleanup track. Phase A's D4-style acceptance applies
  here.
- `core/metadata/function`: target ≥ 85% line / ≥ 70% branch (from
  73.3% / 45.8%) by Step 7 verification — 71 uncov reduced by ~30 to
  ≤ 41.
- `core/metadata/sequence`: target ≥ 90% line / ≥ 80% branch (from
  85.4% / 73.4%) by Step 7 verification — 70 uncov reduced by ~30 to
  ≤ 40. Already above the 85%/70% project gate; Track 16's half-step
  is gap-filling.

These acceptance bands are reassessed at Step 7 verification — if
the live-coverage delta exceeds the bands, the residual is recorded
in Step 7's episode for the deferred-cleanup track absorption block.
