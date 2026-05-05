# Track 16 — Adversarial Review iter-2 Gate Verification

**Verdict:** PASS — 9 VERIFIED / 0 STILL OPEN / 0 REGRESSION / 0 new findings

**Partial-reframe sufficiency:** The partial reframe applied at iter-1 is
**sufficient**. PSI re-checks confirm the codebase shape that grounded the
findings; the orchestrator's iter-2 description corrects every accepted
finding without introducing regressions. No escalation to a wholesale
dead-code reframe is needed — the schema/function core remains
overwhelmingly live (`PropertyTypeInternal`: 2,816 refs; `SchemaShared`,
`SchemaPropertyImpl`, `SchemaClassImpl` all live in the hundreds), and the
small SPI-only / chain-from-SPI subset (`Balanced`, `Default`,
`CollectionSelectionFactory.getStrategy`, `IndexConfigProperty`,
`DatabaseFunction`/`Factory` chain) is correctly addressed by the
description's dead-code-pin block plus the live SPI round-trip pattern for
`DatabaseFunction.execute()`. The description's tightening of the
cluster-selection block (electing dead-code pinning rather than live SPI
dispatch for `Balanced`/`Default` because PSI shows
`CollectionSelectionFactory.getStrategy` itself has **0 refs**) is a
stronger response than A3 demanded but is consistent with the codebase
evidence and with the carry-forward dead-code-pin convention from Tracks
9–15.

**PSI preflight:** mcp-steroid reachable; project `unit-test-coverage`
open at the working tree path.

---

## Per-finding verification certificates

### Verify A1 [blocker]: `PropertyTypeInternal` named explicitly in What/How
- **Original issue**: Plan's What/How named SchemaShared, SchemaPropertyImpl,
  SchemaClassImpl, cluster-selection strategies, and schema proxies — but
  not `PropertyTypeInternal`, the largest single uncov class in the
  metadata/schema package (~176 outer + ~195 across enum-instance bodies).
- **Fix applied**: Step file's Description now contains a dedicated
  `**PropertyTypeInternal parameterized step**` bullet (track-16.md
  lines 33–44) calling out:
  - The ~176 uncov outer + ~195 uncov across `$10`–`$21` budget.
  - Baseline 66.0% / 62.7%.
  - Test method: `@RunWith(Parameterized.class)` tables grouped by
    logical family (numeric, collection, link, embedded, datetime/binary).
  - Existing test routes (`SchemaPropertyTypeConvertTest` 456 LOC / 54
    `@Test`, `TestSchemaPropertyTypeDetection` 270 LOC / 43 `@Test`)
    being extended rather than duplicated.
- **Re-check**:
  - Step-file location: track-16.md Description, second nested bullet
    under `core/metadata/schema` What.
  - PSI confirms `PropertyTypeInternal` exists at
    `core/src/main/java/.../internal/core/metadata/schema/PropertyTypeInternal.java`,
    is an enum, file is **2,177 lines** (matches the iter-1 finding's
    "2177-line enum file" claim verbatim), has **21 enum constants** —
    all 21 with anonymous-class instance bodies. (The iter-1 finding said
    "17 anonymous enum-instance subclasses" — the actual count is 21,
    so the description's "$10–$21" range is consistent with the higher
    count.)
  - The class has 2,816 references project-wide — strongly live, so the
    correct framing is live-drive parameterized testing (which the
    description prescribes), not dead-code pinning.
- **Regression check**: The Description's "no class in scope has 0
  production references" caveat in the opening paragraph is consistent
  with PSI; no new class was over-claimed as live.
- **Verdict**: VERIFIED.

### Verify A2 [should-fix]: Sequence already at target — collapse to gap-fill
- **Original issue**: `metadata/sequence` is at 85.4%/73.4% — already
  above the 85%/70% project gate. Plan slated full sequence work.
- **Fix applied**: Description now contains an explicit acknowledgement
  (track-16.md lines 104–112): "fresh measurement: 85.4% line / 73.4%
  branch — already above the project 85%/70% gate. Collapses to a
  half-step alongside function library: gap-filler targeting `DBSequence`
  (31 uncov, 78.2% line — biggest in package), `SequenceLibraryProxy`
  (6 uncov, 60.0% line)..."
- **Re-check**: The half-step gap-fill collapse is named and its targets
  (`DBSequence` + `SequenceLibraryProxy`) are exactly the per-class
  outliers identified in iter-1. The description also explicitly says
  Track 16 will extend `DBSequenceTest` (26 `@Test` / 967 LOC) rather
  than authoring new sequence test classes — consistent with the
  shrink-to-gap-fillers recommendation.
- **Regression check**: No work claim deletions that would orphan a real
  gap. The description still preserves the live-drive plan for
  `SequenceLibraryImpl` / `SequenceCached` residual edges, which is
  appropriate scope for a half-step.
- **Verdict**: VERIFIED.

### Verify A3 [should-fix]: Cluster-selection SPI dispatch design
- **Original issue**: `Balanced` and `Default` cluster-selection
  strategies are reachable only through the SPI ServiceLoader path;
  direct `new ...Strategy()` constructor tests would not exercise the
  SPI loop. A3 requested explicit `CollectionSelectionFactory.
  getStrategy(name)` round-trip tests.
- **Fix applied**: Description's iter-2 reframe goes **further** than A3
  demanded by electing dead-code pinning for the cluster-selection trio
  (track-16.md lines 51–63):
  - `BalancedCollectionSelectionStrategyDeadCodeTest`
  - `DefaultCollectionSelectionStrategyDeadCodeTest`
  - `CollectionSelectionFactoryDeadCodeTest`
  All three lockstep-deletion-forwarded to Track 22 with WHEN-FIXED
  markers. `RoundRobin` stays as live coverage (it has 3 prod refs
  through `SchemaClassImpl:85`'s direct instantiation).
- **Re-check**:
  - PSI re-check: `BalancedCollectionSelectionStrategy` — 1 SPI ref, 0
    prod, 0 test. `DefaultCollectionSelectionStrategy` — 1 SPI ref, 0
    prod, 0 test. `RoundRobinCollectionSelectionStrategy` — 1 SPI ref,
    3 prod, 0 test. `CollectionSelectionFactory.getStrategy(String)` —
    **0 refs project-wide**.
  - This evidence supports the stricter dead-code-pin route: not only
    are the two strategies SPI-only-reachable (per A3), but the SPI
    factory's `getStrategy` dispatch entry-point has no callers at all,
    so there is no live path even via the factory. Dead-code pinning
    is the correct response to A3's underlying coverage-shortfall
    concern.
  - Description appropriately distinguishes `RoundRobin` (live, 3 prod
    refs, no pin) from the dead trio (pin + lockstep delete).
- **Regression check**: The description still correctly identifies the
  third entry in the SPI service file as the `RoundRobin.getName()`
  registration, matching the codebase. No new false-dead claims; no
  live class mistakenly pinned.
- **Verdict**: VERIFIED. The orchestrator's stricter response is sound
  and PSI-grounded.

### Verify A4 [should-fix]: `DatabaseFunction` round-trip via SELECT
- **Original issue**: `DatabaseFunction.execute()` is at 36.4% line /
  16.7% branch because the only path that creates a `DatabaseFunction`
  is `DatabaseFunctionFactory.createFunction()` (1 SPI ref). Direct
  constructor tests would bypass the SPI loop.
- **Fix applied**: Description (track-16.md lines 91–101) now says:
  *"To exercise `DatabaseFunction.execute()` live, register a stored
  function via `library.createFunction(...)` and invoke it as `SELECT
  myFn(args)` — direct constructor calls would be test-only and bypass
  the SPI loop."*
  The How block (lines 144–149) repeats this: "The `DatabaseFunction.
  execute()` path is reachable only via `SELECT myFn(args)` after the
  function is registered through `library.createFunction(...)`; direct
  construction yields test-only coverage that does not exercise the SPI
  loop."
- **Re-check**:
  - PSI: `DatabaseFunction` — 1 prod ref (DatabaseFunctionFactory
    `new DatabaseFunction(f)` site). `DatabaseFunctionFactory` — 1 SPI
    ref only. The chain-from-SPI shape holds exactly as A4 described.
  - Description correctly identifies the round-trip: registration via
    `FunctionLibrary.createFunction` → SQL engine `SELECT` lookup →
    `DatabaseFunctionFactory.createFunction(name)` → `new
    DatabaseFunction(f)` → `execute(...)`.
  - The "SPI dispatch overlaps Track 6 (`CustomSQLFunctionFactory`) —
    coordinate via Track 22 deferred-cleanup queue" callout is a useful
    cross-track hygiene addition.
- **Regression check**: The description does not over-claim that
  `DatabaseFunctionFactory` itself can be dead-code pinned (correctly —
  it is reachable through the live SQL function dispatch chain, even if
  it has only the SPI ref textually).
- **Verdict**: VERIFIED.

### Verify A5 [should-fix]: Drop fixture-reuse claim
- **Original issue**: Plan asserted "Schema fixtures established here
  may be reused by Tracks 17, 18, and 22" with no anchoring step
  deliverable. A5 said either commit the fixture step or drop the claim.
- **Fix applied**: Description's Interactions section (track-16.md
  lines 200–209) now reads: *"**No fixture extraction is committed at
  Track 16 time.** The pre-review claim 'Schema fixtures established
  here may be reused by Tracks 17, 18, and 22' is dropped per A5/R4:
  there is no anchoring step deliverable, no concrete `SchemaTestFixtures`
  class today, and Tracks 17/18/22 owners have not committed to specific
  fixture consumption. Track 16 may discover patterns that later tracks
  adopt by analogy, but any DRY hoist for cross-track reuse goes to
  Track 22's deferred-cleanup queue."*
- **Re-check**: The text drops the claim explicitly, names A5/R4 as the
  basis, and re-routes any future DRY hoist to Track 22 — the correct
  remediation per A5's option (b).
- **Regression check**: No downstream track depends on the dropped
  claim's enforcement (Tracks 17/18/22 can still reuse patterns by
  analogy). The deferred-cleanup queue is the right destination for
  future cross-track fixture extraction.
- **Verdict**: VERIFIED.

### Verify A6 [suggestion]: 7-9 actual steps acknowledgement (FYI only)
- **Original issue**: Suggestion only — no plan modification required.
  Track 16 will likely grow from `~6 scope` to 7–9 actual steps under
  Phase A decomposition (precedent: Tracks 6, 7, 8 each grew similarly).
- **Fix applied**: No plan modification was required. The description
  implicitly acknowledges the larger surface by enumerating six
  major work-buckets (live schema drives, `PropertyTypeInternal`
  parameterized step, dead-code pin block for cluster-selection trio +
  `IndexConfigProperty`, `RoundRobin` live confirmation, function
  library + `DatabaseFunction` live SPI round-trip, sequence half-step,
  and the should-be-final / boundary sweep), which signals 7–9 step
  decomposition without changing the Scope indicator.
- **Re-check**: Description framing leaves Phase A free to decompose
  organically; no scope indicator change is needed (it is non-binding
  per CLAUDE.md).
- **Regression check**: None — suggestion only.
- **Verdict**: VERIFIED.

### Verify A7 [suggestion]: Reword `SchemaShared` synchronization constraint
- **Original issue**: Constraint #2 ("avoid coupling tests to internal
  SchemaShared synchronization") was ambiguous — readable as banning
  the public lock-API methods (`acquireSchemaReadLock`,
  `releaseSchemaReadLock`, `acquireSchemaWriteLock`,
  `releaseSchemaWriteLock`) which would block legitimate coverage of
  the writeLock-contention paths.
- **Fix applied**: Description (track-16.md lines 150–160) now says:
  *"`SchemaShared` lock discipline (R7 working note): the
  `acquireSchemaReadLock` / `releaseSchemaReadLock` /
  `acquireSchemaWriteLock(session)` / `releaseSchemaWriteLock(session)`
  / `releaseSchemaWriteLock(session, save)` methods ARE part of the
  public lock API and are fair game for direct testing. The underlying
  `ReentrantReadWriteLock` field is private and must not be probed via
  reflection."*
  The Constraints block (track-16.md lines 193–198) restates this
  succinctly: *"The `SchemaShared` public lock-API methods are fair
  game; the private `ReentrantReadWriteLock` field is not (no
  reflection probes)."*
- **Re-check**:
  - PSI confirms five public lock-API methods on `SchemaShared`:
    `acquireSchemaReadLock()`, `releaseSchemaReadLock()`,
    `acquireSchemaWriteLock(DatabaseSessionEmbedded)`,
    `releaseSchemaWriteLock(DatabaseSessionEmbedded)`,
    `releaseSchemaWriteLock(DatabaseSessionEmbedded, boolean)`.
  - PSI confirms `lock` field is `private final ReentrantReadWriteLock`.
  - Reword precisely matches the field-vs-public-method split A7
    requested.
- **Regression check**: The reword does not open the door to inappropriate
  internal probing — it explicitly names "no reflection probes" as the
  remaining boundary.
- **Verdict**: VERIFIED.

### Verify A8 [suggestion]: JaCoCo exclusions confirmed clean
- **Original issue**: Confirmation only — `metadata/**` is not in any
  JaCoCo / surefire exclusion list.
- **Fix applied**: No action required.
- **Re-check**: `pom.xml:1064-1067` JaCoCo report excludes only
  `internal/core/sql/parser/*.class`, `internal/core/gql/parser/gen/*.class`,
  `api/gremlin/*.class`. No `metadata/**` exclusion. `pom.xml:1084-1087`
  (XML reporting copy) is identical. `core/pom.xml:308-310` surefire
  excludes `gremlintest/**` only.
- **Regression check**: None — confirmation finding.
- **Verdict**: VERIFIED.

### Verify A9 [suggestion]: Inert-test spot-check confirmed clean
- **Original issue**: Confirmation only — `SchemaClassImplTest`,
  `CaseSensitiveClassNameTest`, `SchemaPropertyTypeConvertTest`,
  `DBSequenceTest`, `FunctionLibraryTest` all have matching `@Test`
  and `public void` counts; no Track 12-style inert-test bug.
- **Fix applied**: Description (track-16.md lines 168–171) records the
  spot-check result so Phase A doesn't redo it: *"Adversarial review
  iter-1 already spot-checked `SchemaClassImplTest`,
  `CaseSensitiveClassNameTest`, `SchemaPropertyTypeConvertTest`,
  `DBSequenceTest`, `FunctionLibraryTest` — all clean (A9)."*
- **Re-check**: The note is correctly recorded with finding ID for
  traceability. Phase A Step 1 still spot-checks 2–3 *additional*
  existing test classes (the description preserves this requirement),
  which is appropriate hygiene without redoing already-completed work.
- **Regression check**: None — confirmation finding.
- **Verdict**: VERIFIED.

---

## New findings

None. The description's iter-2 reframe is internally consistent, PSI-grounded,
and addresses every iter-1 finding. The orchestrator's choice to elect
dead-code pinning for the cluster-selection trio (rather than the live SPI
dispatch test that A3 originally proposed) is justified by the additional
PSI evidence that `CollectionSelectionFactory.getStrategy` itself has 0
callers — making live dispatch impossible without test-only entry points.

## Counts

- VERIFIED: 9 (A1, A2, A3, A4, A5, A6, A7, A8, A9)
- STILL OPEN: 0
- REGRESSION: 0
- New findings: 0

**Gate result: PASS.**
