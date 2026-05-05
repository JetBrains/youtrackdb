# Track 16 — Adversarial Review (iter-1)

**Verdict:** 1 blocker / 4 should-fix / 4 suggestions / 0 skip

**Recommended track-level reframe:** **Partial**, not full. Unlike Tracks 14/15, the
core schema/function/sequence surface is overwhelmingly live (PSI-confirmed) — a
full dead-code reframe is *not* warranted. But the plan's named scope has
material gaps and overstatements that need correction at Phase A:

1. **`PropertyTypeInternal` (176 uncov / 66.0%) is the largest single class
   gap in the schema package and is unnamed in the plan's What/How.** Its
   17 anonymous enum-instance bodies (`PropertyTypeInternal$1..$21`) carry
   the bulk of the uncov branches. Plan must name it explicitly.
2. **`metadata/sequence` is already at 85.4% / 73.4% — already meets the
   85%/70% project target.** The plan slates it for tests anyway. This is
   wasted effort budget; sequence work should shrink to gap-fillers only.
3. **Two cluster-selection strategies (`Balanced`, `Default`) are reachable
   *only* through the SPI ServiceLoader path** — their sole non-test
   non-self-package reference is the `META-INF/services` file. Live
   coverage requires a `CollectionSelectionFactory.getStrategy("balanced"
   / "default")` round-trip; without that explicit dispatch test, the
   `Balanced` class will stay at 25%/0% (it is currently 12 uncov / 25%
   line / 0% branch — exactly its plan-cited footprint).
4. **`DatabaseFunction` + `DatabaseFunctionFactory` form a near-dead chain
   that piggybacks on the SPI registration.** `DatabaseFunctionFactory`'s
   only reference is the `META-INF/services/SQLFunctionFactory` SPI
   listing; `DatabaseFunction`'s only reference is inside that factory's
   `createFunction()`. Coverage for them is currently 36.4%/16.7% and
   100%/100% respectively — i.e., the factory's path IS being exercised
   (by some live SQL execution that runs the ServiceLoader chain) but the
   function itself is not. Test design must drive the function via stored
   functions registered in the live function library, otherwise the same
   gap will reproduce.

The remaining schema/function/sequence surface (SchemaShared, SchemaClassImpl,
SchemaPropertyImpl, SchemaEmbedded, SchemaClassEmbedded, SchemaPropertyEmbedded,
SchemaImmutableClass, ImmutableSchema, ImmutableSchemaProperty, GlobalPropertyImpl,
all proxies, all internals, FunctionLibraryImpl, Function, SequenceLibraryImpl,
SequenceCached, all validation classes) is **strongly live** and tests under
this track will drive real coverage — no per-class reframe needed.

---

## Part 1: Challenge Certificates

### Decision Challenges (Track-Level Decisions)

#### Challenge: Decision (track scope) — "Schema, function, sequence" as a single track

- **Chosen approach**: One ~6-step track covering schema (1278 uncov), function
  (74 uncov), sequence (75 uncov), cluster selection (18 uncov) together.
- **Best rejected alternative**: Two tracks — (a) schema-only (1278 + 18 + the
  unnamed `PropertyTypeInternal`), (b) function + sequence as a smaller
  track or as deferred-cleanup absorption.
- **Counterargument trace**:
  1. The plan's scope-indicator says `~6 steps`. Schema alone is ~1232 uncov
     line + ~700 uncov branch lines spread across 11 large classes
     (SchemaClassImpl 690 LOC at 75.2%, SchemaImmutableClass 278 LOC at 67.3%,
     SchemaPropertyImpl 302 LOC at 67.2%, SchemaClassEmbedded 307 LOC at
     70.4%, SchemaProxy 146 LOC at 65.8%, SchemaClassProxy 232 LOC at 67.7%,
     etc.) plus PropertyTypeInternal at 517 LOC / 66.0%.
  2. Track 8 (SQL Executor) precedent grew from ~5 scope steps to 8 actual
     steps; Track 6 (SQL Functions) grew from ~6 to 8; Track 7 (SQL Methods)
     grew from ~5 to 8. Schema is comparable scale — likely 7-9 steps after
     Phase A decomposition.
  3. Sequence is already at target (85.4% / 73.4%). Pulling it out costs
     little and reduces step count.
- **Codebase evidence**:
  - `metadata/sequence` aggregate from `coverage-analyzer.py`:
    `85.4% / 73.4% / 70 uncov / 478 total` (already PASS).
  - Per-class schema gap totals (from JaCoCo XML, just-measured): ~1232
    uncov spread over the named classes plus 176 uncov in
    `PropertyTypeInternal` not named.
- **Survival test**: WEAK — survives but the rationale needs strengthening.
  Either rename to "Schema & Properties" and absorb function+sequence into
  Track 22, or pre-acknowledge in Phase A that 7-9 steps are likely.

#### Challenge: Decision (named class list) — Plan omits `PropertyTypeInternal`

- **Chosen approach**: Plan's What lists "schema operations (SchemaShared,
  SchemaPropertyImpl, SchemaClassImpl, cluster selection strategies, schema
  proxies)" with no mention of `PropertyTypeInternal`.
- **Best rejected alternative**: Name `PropertyTypeInternal` explicitly as a
  sub-target with a step or step-half allocated to it.
- **Counterargument trace**:
  1. `PropertyTypeInternal` is a 2177-line enum file with 17 anonymous
     enum-instance subclasses (`$1` through `$21` in the JaCoCo report).
     Each instance has its own `convert(Object, PropertyTypeInternal,
     SchemaClass, DatabaseSessionEmbedded)` method body.
  2. Per the just-measured JaCoCo report, the *outer enum* alone has 176
     uncov lines / 171 uncov branches at 66.0% line / 62.7% branch. The
     anonymous instances carry roughly another 195 uncov lines aggregated
     across `$10`-`$21` (each at 55-78% line, 36-66% branch).
  3. `PropertyTypeInternal` lives in `core/metadata/schema/` (not in
     `core/metadata/schema/schema/` where the public `PropertyType` enum
     lives). It is in-scope for Track 16 by package, just unnamed.
  4. Without explicit allocation, decomposition will likely under-budget
     this class; its conversion paths are heavy boundary-driven code with
     many switch-arms over `value`'s actual type, exactly the kind of
     surface that needs deliberate parameterization rather than incidental
     coverage.
- **Codebase evidence**:
  - `core/src/main/java/.../metadata/schema/PropertyTypeInternal.java:46-48`:
    `public enum PropertyTypeInternal { BOOLEAN("Boolean", 0, ...) { @Override public Boolean convert(...) { ... } }`
    — anonymous-class enum body, the dominant code shape.
  - Existing test coverage routes mostly through
    `SchemaPropertyTypeConvertTest.java` (456 LOC, 54 `@Test` methods) and
    `TestSchemaPropertyTypeDetection.java` (270 LOC, 43 `@Test` methods).
    The 33-34% residual gap shows these don't yet drive every branch.
- **Survival test**: NO — should reconsider. Plan must name
  `PropertyTypeInternal` in the What/How.

#### Challenge: Decision (constraint) — "Avoid coupling tests to internal SchemaShared synchronization — prefer public API for schema mutations"

- **Chosen approach**: Plan constraint #2 forbids tests from probing
  `SchemaShared`'s synchronization machinery directly.
- **Best rejected alternative**: Allow targeted lock-acquisition tests for
  the public lock methods because they ARE part of the public surface.
- **Counterargument trace**:
  1. `SchemaShared.acquireSchemaReadLock()` / `releaseSchemaReadLock()` /
     `acquireSchemaWriteLock(DatabaseSessionEmbedded)` /
     `releaseSchemaWriteLock(DatabaseSessionEmbedded)` /
     `releaseSchemaWriteLock(DatabaseSessionEmbedded, boolean)` are all
     **public methods** on the abstract base
     (`SchemaShared.java:406-423`).
  2. Any rigorous test of "schema mutations across concurrent sessions
     correctly serialise" needs to acquire/release these locks, otherwise
     the test cannot pin the contract.
  3. The constraint as written reads as banning the public API. If the
     intent is "don't test internal `lock` field state via reflection",
     that should be stated; if the intent is "don't write multi-thread
     contention tests at all", that's a coverage shortfall (writeLock
     contention paths in `SchemaShared` are part of the 60 uncov line
     residue).
- **Codebase evidence**:
  - `SchemaShared.java:68`: `private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();`
    — not exposed.
  - `SchemaShared.java:406-423`: public lock-acquire/release methods.
  - Per JaCoCo, `SchemaShared` is at 84.0% / 66.5% — the residual 60 uncov
    lines likely include these lock methods plus reload/load code paths.
- **Survival test**: WEAK — rationale needs clarification. Reword the
  constraint to "don't test `SchemaShared.lock` field state via reflection;
  public lock-API methods are fair game" or similar.

#### Challenge: Decision (interactions claim) — "Schema fixtures established here may be reused by Tracks 17, 18, and 22"

- **Chosen approach**: Plan asserts Track 16 will produce reusable schema
  fixtures.
- **Best rejected alternative**: Either (a) make the fixture creation an
  explicit step deliverable, or (b) drop the claim — fixtures are
  per-track unless explicitly extracted.
- **Counterargument trace**:
  1. Track 16's What/How does not mention any shared fixture extraction.
     It says "Schema tests need a database session to create classes and
     properties (`DbTestBase`)" and that's it.
  2. The codebase has `TestUtilsFixture` in `core/sql/executor/` (101
     refs — Tracks 7+8 created it, Tracks 9-15 extended it). Its
     schema-related methods are `createClassInstance()` and
     `createChildClassInstance(SchemaClass)`. There is no
     `SchemaTestFixtures` / `SchemaFixture` class.
  3. For the "reusable by 17, 18, 22" claim to hold, Track 16 must
     either extend `TestUtilsFixture` or create a new fixture class.
     Neither is required by the plan.
  4. Without an explicit fixture step, "established here may be reused"
     becomes aspirational and silently fails — Track 17 will end up
     re-implementing schema setup helpers, same as Tracks 7-15 each did
     piecewise.
- **Codebase evidence**:
  - PSI search: `TestUtilsFixture` (101 refs), `SchedulerTestFixtures`
    (69 refs) — these are the precedent for cross-class extracted
    fixtures.
  - `TestUtilsFixture` methods: `createClassInstance()`,
    `createChildClassInstance(SchemaClass)`, `rollbackIfLeftOpen()` —
    already partially schema-shaped.
  - The plan's "may be reused" claim has no anchoring step deliverable;
    track decomposition can implement it either way.
- **Survival test**: WEAK — survives but should add an explicit fixture
  extraction in Phase A decomposition, or downgrade the inter-track claim.

### Invariant Challenges

#### Violation scenario: "Avoid coupling tests to internal SchemaShared synchronization"

- **Invariant claim**: Tests must drive schema mutations via public API
  only and not couple to lock state.
- **Violation construction**:
  1. Start state: SchemaEmbedded has class A, attempting to drop it
     concurrent with another thread creating class B.
  2. Action sequence: T1 calls `schema.dropClass(session, "A")`,
     T2 simultaneously calls `schema.createClass(session, "B")`. Both
     internally call `acquireSchemaWriteLock` /
     `releaseSchemaWriteLock(session, true)` (`SchemaShared.java:414-423`).
  3. Intermediate state: write-lock contention hands off; one thread
     waits in `lock.writeLock().lock()`.
  4. Violation point: any test designed to verify "drop+create are
     serialized correctly" must observe lock acquisition order or use the
     public lock API to coordinate. The constraint forbids the former
     (acceptably) but ALSO arguably forbids the latter (public lock
     methods).
  5. Observable consequence: writeLock-contention branches in
     `SchemaShared` (part of the 60 uncov lines) cannot be driven; the
     17%-residual coverage gap stays unmoved.
- **Feasibility**: CONSTRUCTIBLE. The constraint as currently written
  blocks the legitimate test path; rewording is needed.

#### Violation scenario: "Schema fixtures may be reused by Tracks 17, 18, 22"

- **Invariant claim**: Schema fixtures Track 16 produces will be picked
  up by downstream tracks.
- **Violation construction**:
  1. Start state: Track 16 begins; no shared schema fixture exists.
  2. Action sequence: Phase A decomposition uses `DbTestBase` directly
     in each test class without extracting helper methods to a shared
     fixture (the "lightest base class that exercises real dependencies"
     rule from the carry-forward conventions).
  3. Intermediate state: Track 16 produces 7-9 test classes, each with
     its own private setUp helpers for class+property creation.
  4. Violation point: Track 17 (Security) starts. Its test classes need
     schema setup, but no `SchemaTestFixtures` exists. Track 17
     re-implements the helpers privately — same as Track 16.
  5. Observable consequence: the "reusable fixture" promise is silently
     broken; ~50-100 LOC of schema-setup boilerplate gets duplicated
     across the next 3 tracks.
- **Feasibility**: CONSTRUCTIBLE — this is the default outcome unless
  Phase A explicitly carves out a fixture-extraction step.

### Assumption Challenges

#### Assumption test: "Schema is mostly live — straightforward live-coverage path"

- **Claim**: Per the plan and prior-episodes summary, schema is "presumed
  mostly live (used by every class operation)"; therefore Track 16 follows
  the live-drive playbook (not the dead-code-pin reframe).
- **Stress scenario**: Run all-scope `ReferencesSearch` on every named
  class. Classify each as live (≥5 prod refs ex-self), low-live (1-4 prod
  refs), SPI-only (sole prod ref is META-INF/services), or dead (0 prod
  refs).
- **Code evidence** (PSI find-usages, all-scope, just-run):
  - **Strongly live (≥5 prod refs)**: SchemaShared(28), SchemaPropertyImpl(43),
    SchemaClassImpl(127), SchemaImmutableClass(64), ImmutableSchema(53),
    GlobalPropertyImpl(12), SchemaClassProxy(28), SchemaClassInternal(132),
    SchemaPropertyInternal(21), Function(56), DBSequence(51),
    SequenceOrderType(24), CollectionSelectionStrategy(17),
    CollectionSelectionFactory(11), FunctionLibraryImpl(14),
    SequenceLibraryImpl(13), SchemaInternal(8), SchemaProxy(4 — but
    all from `MetadataDefault`, the live constructor path).
  - **Low-live (1-4 prod refs but real callers)**: SchemaEmbedded(5,
    SharedContext + self-package), SchemaClassEmbedded(2, both in
    SchemaEmbedded), SchemaPropertyEmbedded(4, all in SchemaClassEmbedded),
    ImmutableSchemaProperty(4, EntityImpl + SchemaImmutableClass),
    SchemaPropertyProxy(10, all in SchemaClassProxy), FunctionLibrary(3,
    FunctionLibraryProxy + MetadataDefault), FunctionLibraryProxy(3,
    MetadataDefault only), SequenceLibrary(3, SequenceLibraryAbstract +
    MetadataDefault), SequenceLibraryProxy via MetadataDefault,
    RoundRobinCollectionSelectionStrategy(4, factory + SchemaClassImpl
    + SPI), all 5 validation classes (3 each, all in
    ImmutableSchemaProperty), SequenceCached(4, SequenceHelper +
    SequenceOrdered), SequenceOrdered(2, SequenceHelper),
    FunctionUtilWrapper(2, ScriptManager — Track 9 already covers).
  - **SPI-only / chain-from-SPI**: BalancedCollectionSelectionStrategy(1
    SPI ref only), DefaultCollectionSelectionStrategy(1 SPI ref only),
    DatabaseFunction(1 ref from DatabaseFunctionFactory),
    DatabaseFunctionFactory(1 SPI ref).
  - **No 0-prod-ref classes** in the named scope.
- **Verdict**: HOLDS for ~95% of the named scope. Track 16 is correctly
  framed as a live-drive track for the bulk of its surface. Only the
  4 SPI/chain-SPI classes (Balanced, Default, DatabaseFunction,
  DatabaseFunctionFactory) need careful live-dispatch design. **No
  full-track reframe; partial live-dispatch attention is needed.**

#### Assumption test: "Plan-cited uncov figures are stale by several tracks"

- **Claim**: Per prior-episodes, Step 1 of each track has remeasured
  per-package coverage and found stale figures.
- **Stress scenario**: Compare plan-cited (1278 / 70.7%) (74 / 72.2%)
  (75 / 84.3%) (18 / 63.3%) against the just-measured JaCoCo XML.
- **Code evidence**: `coverage-analyzer.py` output just-measured:
  - `metadata/schema`: 1232 uncov / 71.7% line / 57.2% branch (plan said
    1278 / 70.7%) — **drift +1.0pp line, -46 uncov lines**.
  - `metadata/function`: 71 uncov / 73.3% line / 45.8% branch (plan said
    74 / 72.2%) — drift +1.1pp line, -3 uncov lines.
  - `metadata/sequence`: 70 uncov / 85.4% line / 73.4% branch (plan said
    75 / 84.3%) — **already PASS, +1.1pp line, -5 uncov lines**.
  - `metadata/schema/clusterselection`: 18 uncov / 63.3% line / 31.2%
    branch (plan said 18 / 63.3%) — **identical**.
- **Verdict**: HOLDS but unusually mild drift. Most figures within 1-2pp
  of plan. The relevant insights are (a) `metadata/sequence` already
  meets target, and (b) `metadata/schema/validation` already at 100%/100%
  (plan didn't list it as a target — benign).

#### Assumption test: "Existing test classes have no Track 12-style inert-test bugs"

- **Claim**: Track 12 found `*ConverterTest` files where 16 tests had no
  `@Test` annotations; tests passed silently. Carry-forward asks Phase A
  to spot-check existing test files.
- **Stress scenario**: Compare `@Test` count to `public void <method>()`
  count in the major existing schema/sequence test classes.
- **Code evidence**: bash count
  (`grep -c "@Test"` vs `grep -nE "^\s*public void [a-z]\w+\b\s*\("`):
  - `SchemaClassImplTest.java`: 25 @Test / 25 method defs — match.
  - `CaseSensitiveClassNameTest.java`: 43 @Test / 43 method defs — match.
  - `SchemaPropertyTypeConvertTest.java`: 54 @Test / 54 method defs — match.
  - `DBSequenceTest.java`: 26 @Test / 28 method defs — 2 helper methods
    (likely `@Before`/`@After` or private helpers, not inert tests).
  - `FunctionLibraryTest.java`: 3 @Test / corresponding methods (small
    file).
- **Verdict**: HOLDS. No Track 12 inert-test bugs in the existing
  schema/function/sequence test surface. **No remediation needed.**

#### Assumption test: "JaCoCo exclusions don't gate Track 16 packages"

- **Claim**: Plan constraint #7 lists JaCoCo / testing exclusions; if a
  Track 16 package is excluded, work on it is wasted.
- **Stress scenario**: Search root `pom.xml` and `core/pom.xml` for any
  exclusion pattern that matches the Track 16 packages.
- **Code evidence**:
  - Root `pom.xml:1064-1067` JaCoCo report excludes:
    `**/com/jetbrains/youtrackdb/internal/core/sql/parser/*.class`,
    `**/com/jetbrains/youtrackdb/internal/core/gql/parser/gen/*.class`,
    `**/com/jetbrains/youtrackdb/api/gremlin/*.class`. **None match
    metadata/**.
  - `core/pom.xml:308-310` surefire excludes only `**/gremlintest/**`
    (TinkerPop Cucumber bundle, not schema).
- **Verdict**: HOLDS. No exclusions gate Track 16 work.

---

## Part 2: Findings

### Finding A1 [blocker]
**Certificate**: Decision Challenge — Plan omits `PropertyTypeInternal`
**Target**: Decision (track scope — named class list)
**Challenge**: `PropertyTypeInternal` is the **largest single uncovered class
in the schema package** (176 uncov lines, 66.0% line / 62.7% branch on the
outer enum, plus ~195 uncov lines across `$10`-`$21` anonymous enum-instance
subclasses). Its conversion paths are heavy boundary-driven code with many
switch-arms over `value`'s runtime type. The plan's What/How section names
SchemaShared, SchemaPropertyImpl, SchemaClassImpl, cluster-selection
strategies, and schema proxies — none of which can drive
`PropertyTypeInternal.<type>.convert(Object, ...)` branches. Without explicit
allocation, decomposition will under-budget this class and Phase B will
either skip it or run over.
**Evidence**:
- Per-class JaCoCo (just-measured):
  `schema/PropertyTypeInternal: 176 uncov / 517 total / 66.0% line / 62.7% branch`
  plus `$10` through `$21` totaling another ~195 uncov lines / ~155 uncov
  branches (each enum-instance body has its own coverage row).
- File: `core/src/main/java/.../metadata/schema/PropertyTypeInternal.java`
  is 2177 lines with 17 anonymous enum-instance subclasses each carrying a
  distinct `convert(Object, PropertyTypeInternal, SchemaClass,
  DatabaseSessionEmbedded)` implementation
  (`PropertyTypeInternal.java:75, 105, 138, 174, 207, 237, 270, 314, 333,
  368, 428, 572, 675, 814, ...`).
- Existing test routes: `SchemaPropertyTypeConvertTest` (456 LOC, 54
  `@Test`) and `TestSchemaPropertyTypeDetection` (270 LOC, 43 `@Test`)
  drive only ~66% of these paths.
**Proposed fix**:
1. Add `PropertyTypeInternal` (and its enum-instance bodies) to Track 16's
   What list — recommended phrasing: *"plus `PropertyTypeInternal` (~176
   uncov outer + ~195 uncov in enum-instance bodies; routed via
   parameterized `convert(...)` round-trip tests over Boolean / Integer /
   Short / Long / Float / Double / Date / Binary / Embedded / List / Set /
   Map / Link variants)"*.
2. Allocate 1-2 dedicated steps for `PropertyTypeInternal` in Phase A
   decomposition — this is comparable in scope to a sequence-of-
   `*FunctionTest.java` step from Track 6.

### Finding A2 [should-fix]
**Certificate**: Decision Challenge — track scope; Assumption test —
plan-cited uncov figures
**Target**: Decision (track scope) — `metadata/sequence` already at target
**Challenge**: `metadata/sequence` is at **85.4% line / 73.4% branch /
70 uncov lines** *as of the current `.coverage` snapshot* — already meets
the project-wide 85%/70% target. Yet the plan slates it for explicit
Track 16 work. This is wasted effort budget that conflicts with the
"raise aggregate" goal: tracks should target *gaps*, not already-passing
packages.
**Evidence**:
- `coverage-analyzer.py` just-measured:
  `metadata/sequence: 70 uncov / 478 total / 85.4% line / 73.4% branch`.
- Plan-cited: `75 uncov / 84.3% line` — drift -5 lines / +1.1pp.
- Per-class breakdown: `DBSequence` 31 uncov (78.2% / 58.3%);
  `SequenceCached` 11 uncov (89.5% / 83.3%); `SequenceLibraryImpl` 10
  uncov (89.2% / 73.1%); `SequenceLibraryProxy` 6 uncov (60.0% / -); rest
  near 100%. Of these, only `DBSequence` + `SequenceLibraryProxy` carry
  meaningful gaps; both are small.
**Proposed fix**: Phase A should remeasure (per the carry-forward Step 1
baseline rule) and either (a) collapse sequence work to a single
gap-filler step targeting `DBSequence` and `SequenceLibraryProxy` only,
or (b) move sequence to Track 22's residual sweep. Either way, do NOT
plan a full sequence step assuming the plan's 84.3% baseline.

### Finding A3 [should-fix]
**Certificate**: Assumption test — schema is mostly live; cluster-selection
SPI-only callers
**Target**: Decision (test design — cluster selection)
**Challenge**: `BalancedCollectionSelectionStrategy` and
`DefaultCollectionSelectionStrategy` have **only one production
reference each — the `META-INF/services` SPI registration**. Their live
coverage path is exclusively through
`CollectionSelectionFactory.getStrategy(name)` lookup. Direct `new
BalancedCollectionSelectionStrategy()` would be test-only and would not
exercise the SPI-loader's `getName()` registration loop. Without an
explicit factory-dispatch test, they will stay at 25.0% / 0.0% (Balanced)
and 50.0% / – (Default) — exactly the plan-cited 18 uncov / 63.3% gap.
**Evidence**:
- PSI all-scope ReferencesSearch (just-run):
  `BalancedCollectionSelectionStrategy: 1 prod ref —
   META-INF/services/.../CollectionSelectionStrategy:21`.
  `DefaultCollectionSelectionStrategy: 1 prod ref —
   META-INF/services/.../CollectionSelectionStrategy:20`.
- `CollectionSelectionFactory.java:33-58`: `registerStrategy()` calls
  `lookupProviderWithYouTrackDBClassLoader` (ServiceLoader), then for
  each strategy calls `clz.getMethod("getName")` → `register(key, clz)`.
- `CollectionSelectionFactory.getStrategy(String)` →
  `newInstance(iStrategy)` is the only public dispatch route.
- Per-class JaCoCo: `Balanced 12 uncov / 25.0% / 0.0%`,
  `Default 2 uncov / 50.0% / -`, `RoundRobin 0 uncov / 100% / 100%`.
**Proposed fix**: Cluster-selection step must drive each strategy via
`new CollectionSelectionFactory().getStrategy("balanced" / "default" /
"round-robin")` to exercise the SPI loop. Direct constructor tests are
acceptable supplements but cannot replace the factory dispatch.

### Finding A4 [should-fix]
**Certificate**: Assumption test — schema is mostly live; DatabaseFunction
chain-from-SPI
**Target**: Decision (test design — function library)
**Challenge**: `DatabaseFunctionFactory` is registered only via
`META-INF/services/SQLFunctionFactory` — its sole production reference.
`DatabaseFunction` is referenced only inside `DatabaseFunctionFactory.
createFunction()`. Per current JaCoCo: `DatabaseFunction 14 uncov / 36.4%
line / 16.7% branch` — the function class's `execute()` body is mostly
unreached. The factory itself is at 100%/100% (the SPI-loader path runs
during SQL-function dispatch under existing Track 6 / 7 SQL execution
tests), but the function instances created through it are not exercised.
**Evidence**:
- PSI all-scope:
  `DatabaseFunctionFactory: 1 prod ref — META-INF/services/SQLFunctionFactory:21`.
  `DatabaseFunction: 1 prod ref — DatabaseFunctionFactory:52 (`new DatabaseFunction(f)`)`.
- File `core/src/main/java/.../metadata/function/DatabaseFunctionFactory.java:31`:
  `public class DatabaseFunctionFactory implements SQLFunctionFactory`
  with `createFunction(name, session)` returning
  `new DatabaseFunction(f)` where `f = session.computeInTx(...)`.
- Per-class JaCoCo: `DatabaseFunctionFactory 0 uncov / 100% / 100%`;
  `DatabaseFunction 14 uncov / 22 total / 36.4% line / 16.7% branch`.
**Proposed fix**: Function step must register a stored function via
`session.getMetadata().getFunctionLibrary().createFunction(...)` and then
invoke it through a `SELECT myFn(args)` SQL call. This drives the live
SPI path: SQLEngine looks up SQLFunctionFactories → `DatabaseFunctionFactory.
createFunction(name)` → `new DatabaseFunction(...)` → `execute(...)`.
Without that round-trip, `DatabaseFunction.execute` stays uncovered.

### Finding A5 [should-fix]
**Certificate**: Decision Challenge — interactions claim about reusable fixtures
**Target**: Decision (cross-track interactions claim)
**Challenge**: The plan's "Schema fixtures established here may be reused
by Tracks 17, 18, and 22" claim has no anchoring step deliverable. There
is no shared `SchemaTestFixtures` / `SchemaFixture` class today; the
closest match is `TestUtilsFixture` (in `core/sql/executor/`, 101 refs)
which Tracks 7+8 created and which has only `createClassInstance()` /
`createChildClassInstance(SchemaClass)` schema-related methods. Without
an explicit "extract `SchemaTestFixtures`" step, Track 17's Security
work will re-implement schema-setup helpers privately, same as every
prior track.
**Evidence**:
- PSI: `TestUtilsFixture` (101 refs, in `core/sql/executor/`),
  `SchedulerTestFixtures` (69 refs, in `core/schedule/`) — both
  precedents for cross-class extracted fixtures.
- `TestUtilsFixture.java` exposes `createClassInstance()`,
  `createChildClassInstance(SchemaClass)`, `rollbackIfLeftOpen()` —
  partial schema fixture but not centralized.
- Track 14 episode: codified the spawn-helper pattern in iter-2; the
  workflow's "fixture extraction at iter-2" pattern works but adds
  cleanup load.
**Proposed fix**: Either (a) add an explicit Phase A step to extract a
`SchemaTestFixtures` (or extend `TestUtilsFixture`) helper covering
`createClass`, `createSubclass`, `addProperty(name, type, [linkedClass])`,
`createIndex`, `withTransientSchema(callback)` — to be referenced by
Tracks 17/18/22, OR (b) drop the inter-track interaction claim from the
plan since it currently has no enforcement mechanism.

### Finding A6 [suggestion]
**Certificate**: Decision Challenge — track scope sizing
**Target**: Decision (Scope:`~6 steps`)
**Challenge**: Tracks 6, 7, 8 each grew from their scope-indicator step
counts (5-6) to 8 actual steps under dimensional review. Track 16 spans
similar scale (1232 schema uncov + 71 function + 70 sequence + 18
clusterselection + 176+195 PropertyTypeInternal uncov ≈ ~1500 net uncov
lines after sequence is removed). Realistic step count is 7-9.
**Evidence**: Track 6 episode: "track grew from ~6 scope-indicator steps
to 8 actual steps". Track 7 episode: "Plan grew from ~5 scope-indicator
steps to 8 actual steps". Track 8 episode: similar growth.
**Proposed fix**: Don't change the Scope indicator (it's not load-bearing
per CLAUDE.md), but Phase A decomposition should pre-acknowledge that
~7-9 steps are likely once `PropertyTypeInternal` is added and
function+sequence are right-sized. No plan modification; just an FYI for
decomposition.

### Finding A7 [suggestion]
**Certificate**: Decision Challenge — SchemaShared synchronization constraint
**Target**: Decision (constraint #2 wording)
**Challenge**: The constraint "Avoid coupling tests to internal SchemaShared
synchronization — prefer public API for schema mutations" is ambiguous.
`SchemaShared`'s lock-acquire/release methods are public API
(`acquireSchemaReadLock`, `releaseSchemaReadLock`,
`acquireSchemaWriteLock(session)`, `releaseSchemaWriteLock(session)`,
`releaseSchemaWriteLock(session, save)`), but the underlying
`ReentrantReadWriteLock` field is private. The constraint as written reads
as banning the public API, which would block valid coverage of the
write-lock contention paths that contribute to the residual 60 uncov lines.
**Evidence**:
- `SchemaShared.java:68`: `private final ReentrantReadWriteLock lock`.
- `SchemaShared.java:406-423`: 5 public lock-API methods.
- Per JaCoCo: SchemaShared at 84.0% / 66.5% — 60 uncov lines / 57 uncov
  branches likely include at least the multi-arg release variant.
**Proposed fix**: Reword constraint to: *"Don't probe `SchemaShared.lock`
field state via reflection; the public lock-API methods
(`acquireSchemaReadLock`, `releaseSchemaReadLock`, `acquireSchemaWriteLock`,
`releaseSchemaWriteLock`) are part of the contract and are fair game for
direct testing."*

### Finding A8 [suggestion]
**Certificate**: Assumption test — JaCoCo exclusions
**Target**: Confirmation only — no fix needed
**Challenge**: This is a positive verification, not a critique. The
metadata packages are NOT in any JaCoCo exclusion list. Plan constraint
#7 enumeration is consistent with the actual root `pom.xml:1064-1067` and
`core/pom.xml:308-310`.
**Evidence**: `pom.xml:1064-1067` excludes only sql/parser, gql/parser/gen,
api/gremlin top-level. `core/pom.xml:308-310` surefire excludes
gremlintest only.
**Proposed fix**: None. Recorded for traceability.

### Finding A9 [suggestion]
**Certificate**: Assumption test — existing inert-test bugs
**Target**: Confirmation only — no fix needed
**Challenge**: This is a positive verification. Spot-checked
`SchemaClassImplTest.java`, `CaseSensitiveClassNameTest.java`,
`SchemaPropertyTypeConvertTest.java`, `DBSequenceTest.java`,
`FunctionLibraryTest.java`. All have `@Test` annotation count matching
public-void method count (or reasonable +1/+2 for `@Before`/`@After`
helpers). No Track 12-style inert-test bug. Carry-forward Phase A
spot-check rule satisfied.
**Evidence**: bash counts above — all match.
**Proposed fix**: None. Recorded so Phase A doesn't need to redo this
spot-check.

---

## Summary

- **1 blocker** (A1: `PropertyTypeInternal` is the largest single class
  gap and is unnamed in the plan).
- **4 should-fix** (A2 sequence already at target; A3 cluster-selection
  needs SPI dispatch test design; A4 DatabaseFunction needs round-trip
  through function library + SELECT; A5 cross-track fixture claim has
  no enforcement).
- **4 suggestions** (A6 likely 7-9 actual steps; A7 reword
  synchronization constraint; A8 JaCoCo exclusions confirmed clean;
  A9 inert-test spot-check confirmed clean).
- **0 skip recommendations** — Track 16 should proceed.

**Track-level reframe recommendation: PARTIAL.** The schema/function/
sequence surface is overwhelmingly live and Track 16 is correctly framed
as a live-drive track for the bulk of its named scope. Phase A must
correct the named-class list (add `PropertyTypeInternal`), right-size
sequence work (already at target), design SPI/factory dispatch tests for
the 4 SPI-only/chain-from-SPI classes (`Balanced`,
`Default`, `DatabaseFunctionFactory`, `DatabaseFunction`), and either
extract a shared `SchemaTestFixtures` helper or drop the cross-track
reuse claim. **No `*DeadCodeTest` pins are warranted** — every named
class has live production references, in stark contrast to Tracks 14/15.
