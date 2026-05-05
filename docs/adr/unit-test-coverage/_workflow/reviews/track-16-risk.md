# Track 16 — Risk Review (iter-1)

**Verdict:** PASS-with-fixes — 0 blocker / 4 should-fix / 5 suggestion / 0 skip.

**Tooling note:** mcp-steroid was reachable; `unit-test-coverage` project
matched the working tree. All reference-accuracy claims below are PSI-grounded
(`ReferencesSearch`, `ClassInheritorsSearch`, `MethodReferencesSearch`) over
all-scope. The current JaCoCo report at `.coverage/reports/youtrackdb-core/jacoco.xml`
was used to remeasure baselines.

---

## Part 1: Evidence Certificates

### CRITICAL PATH EXPOSURE

#### Exposure: Schema mutations through `SchemaShared` write lock
- **Track claim**: "Avoid coupling tests to internal SchemaShared synchronization
  — prefer public API for schema mutations" (Description, Constraints).
- **Critical path trace**:
  1. Entry: `SchemaProxy.createClass(name)` @ `SchemaProxy.java:70` →
     `SchemaShared.createClass(session, ...)` (delegated).
  2. `SchemaEmbedded.createClass(...)` calls
     `SchemaShared.acquireSchemaWriteLock(session)` @ `SchemaShared.java:414` —
     write lock + modificationCounter increment.
  3. Mutation runs against `classes` (HashMap) and `collectionsToClasses`
     (Int2ObjectOpenHashMap) under the lock; on `releaseSchemaWriteLock` either
     `saveInternal(session)` (when `AbstractStorage`) or `reload(session)`
     (when other storage) is invoked, then `version++` (line 441) and
     `snapshot = null` to invalidate the immutable snapshot.
  4. Concurrent readers go through `acquireSchemaReadLock()` @
     `SchemaShared.java:406`. Other call sites of the lock pair span
     `SchemaClassImpl`, `SchemaPropertyImpl`, `SchemaClassEmbedded`,
     `SchemaPropertyEmbedded`, `SchemaEmbedded` (38 acquire/release pairs in
     `SchemaShared.java` + 5 callers in 5 sibling files per `grep -rln`).
- **Blast radius**: A test that holds a schema write lock past an `@After`
  cascade-fails the next test method's `@Before createDatabase` lifecycle.
  Worse, a test that mutates the schema mid-iteration over its
  `getClasses()` snapshot would surface as a non-deterministic
  ConcurrentModificationException only under disk-mode CI.
- **Existing safeguards**:
  - Public API (`Schema.createClass`, `SchemaProperty.createIndex`, etc.) wraps
    every mutation in the lock — tests that go through `session.getMetadata().
    getSchema()` cannot leak unbalanced lock state.
  - `DbTestBase.afterTest` @ `DbTestBase.java:165` drops the database (and
    hence the `SchemaShared` instance) per test method, so even if a test
    leaks lock state, it does not propagate across methods.
  - 11 existing tests in `core/src/test/java/.../metadata/schema/` already
    drive `SchemaShared` indirectly through public API and have not flaked
    under the existing CI runs.
- **Residual risk**: LOW. The constraint already names the pitfall (don't
  reach into internal sync). The risk is only real if Track 16 introduces a
  test that holds a `db.executeInTx` callback and concurrently reads from a
  worker thread without the rollback-pattern carry-forward.

#### Exposure: Schema property index creation on critical-path
- **Track claim**: Tests for "index creation invariants" (orchestrator
  prompt) — `SchemaPropertyImpl.createIndex(INDEX_TYPE)` is invoked by
  Tracks 18+ as a fixture pivot.
- **Critical path trace**:
  1. Entry: `SchemaPropertyProxy.createIndex(INDEX_TYPE)` →
     `SchemaPropertyImpl.createIndexInternal(...)` →
     `IndexManager.createIndex(...)` (out of Track 16 scope).
  2. Failure mode: index name collision on per-method DBs is impossible
     because `DbTestBase` drops the in-memory DB per test, but a test that
     creates an index, then expects the schema reload to surface it without
     committing the surrounding tx, can observe stale state.
- **Blast radius**: Track 18 inherits Track 16's fixtures per the
  Interactions block. If Track 16's `createIndex(...)` fixture leaves the
  schema in an inconsistent state (index entity created, schema cache not
  yet refreshed), Track 18's index tests inherit a phantom failure that's
  hard to diagnose at distance.
- **Existing safeguards**: `SchemaShared.releaseSchemaWriteLock` calls
  `saveInternal(session)` or `reload(session)` to refresh the on-disk schema
  record before releasing — so a test that stays on the public API gets a
  consistent snapshot. The existing `SchemaClassImplTest` exercises
  `dropProperty` + recreate without leaking state, demonstrating the
  pattern works.
- **Residual risk**: MEDIUM. The risk surfaces only if Track 16 introduces
  helpers that *re-export internal SchemaShared methods* for fixture use
  by Tracks 17, 18, 22 (e.g., a `createPropertyWithIndex` helper that
  calls `acquireSchemaWriteLock` directly to skip transaction overhead).
  Mitigated by sticking to the public-API constraint.

#### Exposure: Cluster selection SPI loading and global state
- **Track claim**: "Cluster selection strategy dispatch" is in scope (How).
- **Critical path trace**:
  1. SPI registration: `META-INF/services/com.jetbrains.youtrackdb.internal.core.metadata.schema.CollectionSelectionStrategy`
     lists 3 implementations (RoundRobin, Default, Balanced).
  2. `CollectionSelectionFactory()` ctor (called once per `SchemaShared`
     instance, line 81) calls `lookupProviderWithYouTrackDBClassLoader(...)`
     and registers each strategy in a `Map<String, Class>` via
     `register(name, clz)`. Default class = `RoundRobinCollectionSelectionStrategy`.
  3. **Critical fact**: PSI find-usages confirms `CollectionSelectionFactory.
     getStrategy(...)` has **zero callers** anywhere in the project.
     `SchemaClassImpl` line 85–86 hardwires
     `new RoundRobinCollectionSelectionStrategy()` directly. There is no
     public API to switch the strategy at runtime — `setClusterSelection`,
     `setStrategy`, `setCollectionSelection` (on SchemaClass) — none exist.
  4. `getCollection(...)` is invoked via `SchemaImmutableClass.
     getCollectionForNewInstance(entity)` @ line 318 and via
     `SchemaClassProxy.getCollectionForNewInstance(entity)` @ line 39.
     Both reach the hardwired RoundRobin instance.
- **Blast radius**: `BalancedCollectionSelectionStrategy` (12 uncov lines)
  and `DefaultCollectionSelectionStrategy` (~6 uncov lines) are
  SPI-registered but unreachable through any production-side selector
  call. Driving them with happy-path tests would *appear* to lift
  coverage but would produce false confidence — the production paths into
  these classes do not exist.
- **Existing safeguards**: SPI loading at factory ctor is exercised by every
  `SchemaShared` instantiation; the `RoundRobin` strategy at SPI load time
  is hit by every `DbTestBase` lifecycle. So most of `RoundRobinCollectionSelectionStrategy`
  is already covered (4 uncov out of 16 lines = 75% line, branch 3/8 = 37.5%
  per JaCoCo).
- **Residual risk**: MEDIUM if Track 16 frames `BalancedCollectionSelectionStrategy`
  / `DefaultCollectionSelectionStrategy` as live-coverage targets. They
  are reachable only by direct instantiation (which any test could do) but
  no production caller routes through them. The pattern from Tracks 9–15 is
  to pin such surface as `*DeadCodeTest` shape pins + Track 22 deletion
  forwarding, not to drive them as live behavior.

#### Exposure: Function library `init()` and shared schema class registration
- **Track claim**: Function library tests cover "registration, lookup,
  removal, and persistence across session reload" (How).
- **Critical path trace**:
  1. `FunctionLibraryImpl.init(session)` @ line 164 creates the `OFunction`
     schema class (with index on `name`) lazily — invoked from
     `createFunction` (line 139) and the static `create(session)` factory
     (line 57).
  2. Production callers: 14 (PSI). Persistence path: `Function.save(session)`
     → tx commit → on next session open `FunctionLibraryImpl.load(session)`
     re-reads `OFunction` rows. Listeners: `onFunctionsChanged(session)` @
     line 233 fires `onFunctionLibraryUpdate` to all `SharedContext`
     listeners and closes the script-manager binding for the database.
  3. **No process-wide statics in `FunctionLibraryImpl`** — all state lives
     on the per-instance `ConcurrentHashMap<String, Function> functions`
     and `AtomicBoolean needReload`. The class uses `synchronized` on
     `createFunction`, `dropFunction`, and `update`.
- **Blast radius**: Function tests within `DbTestBase` are isolated via
  the per-method DB drop. The `synchronized` instance-level methods are
  safe.
- **Existing safeguards**: Existing `FunctionLibraryTest` (3 tests) drives
  the happy path through public API; `DbTestBase` drops the DB after
  each test. Listener side-effect (`getScriptManager().close(...)`) is
  global to the YouTrackDB instance — but `DbTestBase.afterTest` closes
  the YouTrackDB context, so the script-manager is reborn per test.
- **Residual risk**: LOW. Function library coverage gap (71 uncov lines /
  39 uncov branches) is inside `Function`, `FunctionLibraryImpl.load`,
  `onAfterFunctionDropped`, `validateFunctionRecord`, and the
  `RecordDuplicatedException`-wrap branch in `createFunction`. All
  reachable through public API.

#### Exposure: Sequence library — already passes the gate, but test surface is large
- **Track claim**: Sequence library tests cover "registration, lookup,
  removal, and persistence across session reload" (How).
- **Critical path trace**:
  1. JaCoCo baseline: `core/metadata/sequence` 85.4% line / 73.4% branch
     — **already meets the 85%/70% target.** Existing
     `DBSequenceTest` (35 tests, with two MT tests using
     `Executors.newFixedThreadPool(2)`) drives the bulk of the surface.
- **Blast radius**: Adding redundant tests to a package already at gate is
  a low-value use of Track 16's budget.
- **Existing safeguards**: `DBSequenceTest` extends nothing (standalone)
  with `@Category(SequentialTest)`, `@BeforeClass static youTrackDB`.
  Already exercises both the happy path and concurrent retry path.
- **Residual risk**: LOW for correctness; MEDIUM for **scope discipline**.
  The track may overshoot if step decomposition assigns ~1 step to
  sequence when a focused gap-filler around `DBSequence` (31 uncov),
  `SequenceCached` (11 uncov), `SequenceLibraryImpl` (10 uncov) suffices.

#### Exposure: SchemaProxy / SchemaClassProxy / SchemaPropertyProxy delegation surface
- **Track claim**: "Schema proxy delegation" is in-scope (How implicit; orchestrator prompt explicit).
- **Critical path trace**:
  1. `SchemaProxy` (96/146 = 65.8% line, 50 uncov),
     `SchemaClassProxy` (157/232 = 67.7%, 75 uncov),
     `SchemaPropertyProxy` (108/135 = 80.0%, 27 uncov).
  2. PSI confirms all three are live: `SchemaProxy` (4 prod refs),
     `SchemaClassProxy` (28 prod refs), `SchemaPropertyProxy` (10 prod
     refs). They wrap `SchemaShared` / `SchemaClassImpl` /
     `SchemaPropertyImpl` and inject a `DatabaseSessionEmbedded` via
     `assert session.assertIfNotActive()` before each call.
- **Blast radius**: Most uncovered lines in the proxies are the long
  delegation tail — methods that are mechanically `delegate.foo(session)`
  with the `assertIfNotActive` precondition. Such tests yield "shape"
  coverage (proxy returns the same value as delegate); they don't pin
  invariant behavior.
- **Existing safeguards**: Existing `SchemaClassImplTest` and
  `AlterClassTest` drive ~60% of `SchemaClassImpl` already, which means
  the proxy variants get exercised transitively. Direct proxy tests
  add little except boundary cases.
- **Residual risk**: MEDIUM for **test value**. The risk is that proxy
  tests pad the count without exposing real behavior — this is exactly
  the pattern the prior-track ephemeral-identifier sweep + falsifiable-
  regression discipline tries to avoid. Use the proxies' `assertIfNotActive`
  branch (i.e., calling without an active session) as a falsifiable pin
  candidate rather than testing delegation by isomorphism.

### UNKNOWNS & ASSUMPTIONS

#### Assumption: Plan-cited uncov figures
- **Track claim**: `core/metadata/schema` 1,278 uncov, 70.7%; function 74
  uncov 72.2%; sequence 75 uncov 84.3%; clusterselection 18 uncov 63.3%.
- **Evidence search**: JaCoCo XML at `.coverage/reports/youtrackdb-core/jacoco.xml`
  (Bash + Python parser).
- **Code evidence**: actual baseline:
  - `core/metadata/schema`: 3,123/4,355 line = 71.7% (1,232 uncov)
  - `core/metadata/schema/clusterselection`: 31/49 line = 63.3% (18 uncov)
  - `core/metadata/schema/validation`: 20/20 = 100.0% (0 uncov, 0 branches)
  - `core/metadata/function`: 195/266 line = 73.3% (71 uncov)
  - `core/metadata/sequence`: 408/478 line = 85.4% (70 uncov) — **already
    meets gate**.
- **Verdict**: VALIDATED-with-correction. Plan figures are within ~5% of
  actual; no track-restructuring change needed, but the baseline-remeasure
  rule (Tracks 9, 10, 14, 15 carry-forward) applies. Sequence is
  already at gate — Step 1 must remeasure and decide whether to skip
  sequence or focus on the 11-line `SequenceCached` and 10-line
  `SequenceLibraryImpl` gaps.

#### Assumption: Cluster selection strategies are reachable as live behavior
- **Track claim**: "cluster selection strategy dispatch" is testable as
  live coverage (How — implicit).
- **Evidence search**: PSI `ReferencesSearch` on
  `BalancedCollectionSelectionStrategy`, `DefaultCollectionSelectionStrategy`,
  and `MethodReferencesSearch` on `CollectionSelectionFactory.getStrategy`.
- **Code evidence**:
  - `BalancedCollectionSelectionStrategy`: 1 ref (the SPI service file).
  - `DefaultCollectionSelectionStrategy`: 1 ref (the SPI service file).
  - `RoundRobinCollectionSelectionStrategy`: 4 refs — SPI file + 2 in
    `SchemaClassImpl.java` (the `new RoundRobinCollectionSelectionStrategy()`
    field initializer at line 86) + factory default.
  - `CollectionSelectionFactory.getStrategy(...)` has **zero callers**.
  - No public API on `SchemaClass` exposes a `setClusterSelection(name)`
    that would route through `getStrategy`. `grep -rn "setClusterSelection\|setCollectionSelection\|setStrategy"` returns zero in `core/src/main` and
    `server/src` for the SchemaClass surface (`CollectionBasedStorageConfiguration.
    setCollectionSelection` is unrelated — storage-level).
- **Verdict**: CONTRADICTED. Two of the three SPI-registered strategies
  (Balanced, Default) are unreachable through any production code path
  with the current public API. Tracks 9–15 carry-forward says: pin via
  `*DeadCodeTest` shape pins + forward to Track 22 for deletion. Driving
  them as live coverage is a Track-9-style anti-pattern.

#### Assumption: `IndexConfigProperty` is a live class
- **Track claim**: Schema package coverage gap (1,232 uncov) folds in
  `IndexConfigProperty` (13 uncov, 0% line).
- **Evidence search**: PSI `ReferencesSearch` on `IndexConfigProperty`
  + per-method `ReferencesSearch` on its members.
- **Code evidence**: 0 prod refs, 0 test refs, 0 method refs, 0 ctor
  refs anywhere in the project. The class is fully orphaned. (48 lines.)
- **Verdict**: CONTRADICTED. `IndexConfigProperty` is dead-code per
  `*DeadCodeTest` precedent (Tracks 14, 15) and should be pinned for
  Track 22 deletion, not driven for coverage.

#### Assumption: Track 16's fixtures are reusable by Tracks 17, 18, 22
- **Track claim**: "Schema fixtures established here may be reused by
  Tracks 17, 18, and 22" (Interactions).
- **Evidence search**: Backlog gap in plan — Tracks 18, 19, 20, 21 are
  "reconstruct-on-demand" per Operational Notes; their fixture demand
  cannot be fully introspected without re-deriving the backlog at
  their Phase A.
- **Code evidence**: Track 17's scope (security: authn, authz, tokens)
  needs `OUser`, `ORole`, `OSecurityPolicy` — these are
  `metadata/security` schema, not `metadata/schema/clusterselection`.
  Track 18's scope (index lifecycle, queries, iterators) does need
  `SchemaProperty.createIndex(...)` — Track 16's tests will exercise
  that surface but the helper isn't formally hoisted.
- **Verdict**: PARTIALLY VALIDATED. Track 17's reuse claim is weak;
  Track 18's reuse claim is real but requires Track 16 to **resist
  premature DRY hoist** — establishing a `SchemaTestFixtures` helper
  before Track 18 lands would be over-design and is the classic
  trap noted in the prior-track CQ retros.

#### Assumption: PropertyTypeInternal's per-enum-constant inner classes are tractable
- **Track claim**: "property type validation" is in-scope (How).
- **Evidence search**: JaCoCo baseline + source-file size.
- **Code evidence**: `PropertyTypeInternal.java` is 2,177 lines; the
  enum has ~22 constants (BOOLEAN, INTEGER, SHORT, LONG, FLOAT, DOUBLE,
  DATETIME, STRING, BINARY, EMBEDDED, EMBEDDEDLIST, EMBEDDEDSET,
  EMBEDDEDMAP, LINK, LINKLIST, LINKSET, LINKMAP, BYTE, TRANSIENT, DATE,
  CUSTOM, DECIMAL, LINKBAG, ANY) each emitted as a synthetic inner class
  (`PropertyTypeInternal$11`, `$12`, ...) by javac. JaCoCo baseline
  shows `PropertyTypeInternal` outer at 66.0% line (176 uncov) plus
  inner classes `$10`–`$21` aggregating another ~150 uncov lines. The
  `convert(Object, ...)` overload per type is the dominant uncovered
  surface.
- **Verdict**: VALIDATED-with-warning. `PropertyTypeInternal.convert`
  per-type round-trips are highly testable (well-defined input/output)
  but the surface is large (~22 types × ~5 input shapes = ~110 test
  methods minimum to lift coverage materially). A focused per-type
  parameterized table is the right shape; a one-test-per-shape sweep
  would balloon Track 16's step count.

### TESTABILITY & COVERAGE

#### Testability: Schema property/class operations
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: Schema mutations require a live database
  session; existing tests use `DbTestBase` and reach 75–84% on
  `SchemaClassImpl`, `SchemaShared`, `SchemaPropertyEmbedded`. Lifting
  to 85% needs the alter-class edge cases (rename collisions, abstract
  toggle, super-class chain, custom attributes), createProperty(...) +
  createIndex(...) + dropProperty round-trips with index pre-existing.
- **Existing test infrastructure**: `DbTestBase` @ `DbTestBase.java:25`
  + `BaseMemoryInternalDatabase` (used by `SchemaClassImplTest`).
  Existing 11 schema test classes provide patterns to follow without
  fixture hoist.
- **Feasibility**: ACHIEVABLE. The path is "extend existing tests"
  per plan constraint #6.

#### Testability: PropertyTypeInternal type conversion (large enum surface)
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: 22 enum constants × per-type `convert(...)`
  overrides + outer-class dispatch. Each constant's `convert` has
  type-specific branches (null, primitive, String, BigDecimal, Date,
  Number, etc.). Standalone testable.
- **Existing test infrastructure**: `TestSchemaPropertyTypeDetection`,
  `SchemaPropertyTypeConvertTest` already exist — extend them, don't
  create N new files.
- **Feasibility**: ACHIEVABLE-with-budget-warning. A parameterized
  table-driven test class (one per enum constant or per dimension) is
  the right shape; ~3 enum constants per Step would fit a 5–7-step
  envelope without ballooning.

#### Testability: Cluster selection strategy dispatch
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: `RoundRobinCollectionSelectionStrategy` and
  `BalancedCollectionSelectionStrategy` have non-trivial uncovered
  branches (multi-collection dispatch in RoundRobin, refresh-loop in
  Balanced). Direct standalone tests via `new XCollectionSelectionStrategy()`
  + a stub `SchemaClass` are feasible. `BalancedCollectionSelectionStrategy.
  getCollection` requires a `DatabaseSessionEmbedded.getApproximateCollectionCount(int)`
  call — testable through `DbTestBase` with two collections on one class
  (which is already exercised by the existing `BalancedCollectionSelectionStrategy`
  via class abstract→concrete toggle path in `SchemaClassImplTest`).
- **Existing test infrastructure**: None for cluster selection
  specifically.
- **Feasibility**: ACHIEVABLE for `RoundRobinCollectionSelectionStrategy`;
  DIFFICULT for `BalancedCollectionSelectionStrategy` because it has no
  production caller — driving it as live coverage is structurally
  unsound. PIN-AS-DEAD is the right shape for the latter.

#### Testability: Schema proxies (delegation surface)
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: Each proxy method is `assertIfNotActive() →
  delegate.foo()`. Driving every method through `DbTestBase` adds
  delegation noise; the load-bearing pin is the
  "session-not-active throws" branch (Tracks 9, 11 carry-forward —
  pin the *contract*, not the *isomorphism*).
- **Existing test infrastructure**: None proxy-specific.
- **Feasibility**: ACHIEVABLE-with-warning. The number of methods
  (~100 across three proxies) makes parameterized
  `assertIfNotActive` pins the right shape; a method-by-method
  delegation test would yield low-quality coverage.

#### Testability: Function library + Sequence library
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: Both already have substantial tests
  (`FunctionLibraryTest` 3 tests; `DBSequenceTest` 35 tests).
  Function gap is 71 lines (load reload path, onAfter*Dropped tx-custom-
  data path, validateFunctionRecord regex rejection, the
  RecordDuplicatedException → FunctionDuplicatedException wrap branch).
  Sequence is already at gate.
- **Existing test infrastructure**: `FunctionLibraryTest.java` (DbTestBase),
  `DBSequenceTest.java` (standalone + `@Category(SequentialTest)` +
  `@BeforeClass`).
- **Feasibility**: ACHIEVABLE for function; sequence is already there
  — re-measure first, only add tests for the ~30 uncov lines if
  cheap.

### ROLLBACK & RECOVERY

#### Exposure: Test-additive only — minimal rollback surface
- **Track claim**: Tracks 5–15 are purely test-additive; Track 16
  inherits the same posture per plan constraint #4 (Non-Goals:
  modifying production code).
- **Critical path trace**: All step commits add tests + (if dead-code
  found) `*DeadCodeTest` shape pins; production fixes go to Track 22
  via WHEN-FIXED markers.
- **Blast radius**: Per-step revert is trivial (`git revert`). The
  main rollback risk is fixture-hoist commits that span multiple test
  files — but the plan constraint #4 ("Existing test classes
  preferred") and the prior-track lessons (CQ12 from Track 15:
  fixture-hoist-only-when-3+-call-sites) bound this.
- **Existing safeguards**: PR #1022 rulebook fix (`git clean -fd`
  → snapshot-and-diff) is in this branch's `implementer-rules.md`
  per the Track 15 episode and the step file's preflight.
- **Residual risk**: LOW.

---

## Part 2: Findings

### Finding R1 [should-fix]
**Certificate**: Assumption "Cluster selection strategies are reachable
as live behavior" (CONTRADICTED).

**Location**: Track 16 description line "cluster selection strategy
dispatch" + cluster-selection scope `~5 steps covering ... cluster
selection ...` (plan checklist).

**Issue**: `BalancedCollectionSelectionStrategy` (12 uncov, 25.0% line)
and `DefaultCollectionSelectionStrategy` (~6 uncov) have **zero
production callers**. `CollectionSelectionFactory.getStrategy(name)`
itself has zero callers (PSI). `SchemaClassImpl` line 85–86 hardwires
`new RoundRobinCollectionSelectionStrategy()` directly — there is no
public API on `SchemaClass` to switch strategies at runtime. Driving
these as live coverage would (a) yield false confidence — the test
exercises a code path no production caller can reach; (b) duplicate
the Tracks 9–15 anti-pattern of pursuing live coverage on classes
PSI confirms are dead.

**Proposed fix**: At Step 1 baseline, run PSI all-scope
`ReferencesSearch` on the three cluster-selection strategies + the
factory's `getStrategy` method. If the audit confirms the finding (it
will), reframe the cluster-selection step to:
- Drive `RoundRobinCollectionSelectionStrategy` happy-path + multi-
  collection dispatch (already partly covered) as live behavior.
- Pin `BalancedCollectionSelectionStrategy`,
  `DefaultCollectionSelectionStrategy`, and
  `CollectionSelectionFactory.getStrategy` as `*DeadCodeTest` shape
  pins forwarded to Track 22 with `// WHEN-FIXED: Track 22 — delete
  X if no public API to invoke` markers.
- Document the SPI service file's three entries as test-only-reachable
  (one test verifying SPI loads without exception is sufficient — pin
  the shape, don't try to lift the per-strategy branch coverage).

### Finding R2 [should-fix]
**Certificate**: Assumption "`IndexConfigProperty` is a live class"
(CONTRADICTED).

**Location**: Track 16 description bullet `core/metadata/schema (1,278
uncov, 70.7%)`.

**Issue**: `IndexConfigProperty.java` (48 lines, 0% line coverage,
13 uncov) has zero callers and zero references anywhere in the
project (PSI all-scope on the class + per-method + per-ctor). It is a
fully-orphaned class. Driving it for coverage is the same anti-
pattern as R1.

**Proposed fix**: Pin `IndexConfigProperty` via `IndexConfigPropertyDeadCodeTest`
+ forward to Track 22 deletion (lockstep with the class — no
dependent live surface). Apply the Tracks 14, 15 dead-code reframe
pattern.

### Finding R3 [should-fix]
**Certificate**: Exposure "Sequence library — already passes the
gate".

**Location**: Track 16 description bullet `core/metadata/sequence (75
uncov, 84.3%)` + scope indicator `~6 steps covering ... sequence
library ...`.

**Issue**: `core/metadata/sequence` is already at 85.4% line / 73.4%
branch — **above** the 85%/70% target. Allocating a full step here
would be misuse of Track 16's budget. The 70 uncov lines are
distributed across `DBSequence` (31), `SequenceCached` (11),
`SequenceLibraryImpl` (10), and split across tx-error retry paths
that the existing `DBSequenceTest` already partly exercises.

**Proposed fix**: At Step 1 baseline, remeasure and merge the
sequence work into a single-pass extension of `DBSequenceTest` (or
new `SequenceLibraryImpTest` for the proxy + library boundaries) —
do not allocate a dedicated step. The scope indicator `~6 steps`
should reduce to ~5 if sequence collapses to a half-step alongside
function library.

### Finding R4 [should-fix]
**Certificate**: Exposure "Schema property index creation on critical-
path" + Assumption "Track 16's fixtures are reusable by Tracks 17,
18, 22" (PARTIALLY VALIDATED).

**Location**: Track 16 description Interactions block: "Schema
fixtures established here may be reused by Tracks 17, 18, and 22".

**Issue**: Premature fixture extraction is a known anti-pattern from
Tracks 7, 8, 12, 13, 15 (CQ-tier deferrals). If Track 16 hoists a
`SchemaTestFixtures` helper for Track 18's anticipated use, the
helper's API will likely not match Track 18's actual needs (its
backlog is reconstruct-on-demand per Operational Notes — not
introspectable now). Track 17's reuse claim is also weak: security
metadata (`metadata/security`) is a different package set, only
co-incidentally overlapping with `OUser`/`ORole` schema.

**Proposed fix**: Drop the "may be reused by Tracks 17, 18, 22"
language from Track 16's Interactions or downgrade it to "Track 16
may discover patterns that later tracks adopt — but no fixture hoist
is required at Track 16 time". Apply the standard Tracks 5–15
discipline: 3+ call sites in Track 16's own scope before extracting.
DRY hoist for cross-track reuse goes to Track 22's deferred-cleanup
queue, not into Track 16.

### Finding R5 [suggestion]
**Certificate**: Exposure "SchemaProxy / SchemaClassProxy /
SchemaPropertyProxy delegation surface".

**Location**: Track 16 description bullet `schema proxies` +
orchestrator prompt's note about "many delegating methods that
mechanically need testing but yield low-quality coverage".

**Issue**: Driving every proxy method via DbTestBase to lift the
delegation tail produces N tests for N methods that all assert
"delegate returns the same value." The load-bearing branch in each
proxy is the `assert session.assertIfNotActive()` precondition — a
falsifiable pin that catches both API misuse and proxy regression.

**Proposed fix**: Frame proxy step as: (a) drive a parameterized
`@Theory`/`@Parameterized` table of "proxy method on inactive
session throws"; (b) target boundary cases unique to each proxy
(e.g., `SchemaClassProxy.createProperty(...)` re-wraps the returned
`SchemaPropertyImpl` in a `SchemaPropertyProxy`); (c) skip
isomorphic delegation tests. This yields high-value coverage
inside the same method count.

### Finding R6 [suggestion]
**Certificate**: Testability "PropertyTypeInternal type conversion".

**Location**: Track 16 description bullet `core/metadata/schema (1,278
uncov, 70.7%)` — uncov is dominated by `PropertyTypeInternal` (176
outer + ~150 inner classes).

**Issue**: A test-per-shape sweep across 22 enum constants × ~5
input shapes balloons Track 16 beyond the ~6-step envelope.

**Proposed fix**: Use a `@RunWith(Parameterized.class)` table
keyed by `(PropertyTypeInternal, input value, expected output,
expected exception)` — one test class per logical group (numeric,
collection, link, embedded, datetime). Three to four parameterized
classes (one per group) is the right step granularity, not 22.
This pattern was successfully used in Track 12 for serializer
tests and Track 13 for binary-comparator dispatch.

### Finding R7 [suggestion]
**Certificate**: Exposure "Schema mutations through SchemaShared
write lock".

**Location**: Constraint "Avoid coupling tests to internal
SchemaShared synchronization — prefer public API for schema
mutations".

**Issue**: The constraint is sound but doesn't surface the *symptom*
to watch for. Tests that hold a `SchemaShared` reference past the
end of an `executeInTx` callback or that call `acquireSchemaWriteLock`
directly will pass on the developer's box but flake under disk-mode CI
(`-Dyoutrackdb.test.env=ci`).

**Proposed fix**: At Step 1 baseline, add a Phase A working-note
reminding the implementer to: (a) prefer `session.executeInTx(...)`
over `session.begin(); ...; session.commit();` for schema mutations
that need their snapshot to be visible to a subsequent assertion;
(b) NOT to call `SchemaShared.acquireSchemaWriteLock` from tests;
(c) to re-fetch `session.getMetadata().getSchema()` after schema
mutations rather than caching the reference. Codify as a comment in
the step file's "How" section.

### Finding R8 [suggestion]
**Certificate**: Exposure "Schema property index creation on
critical-path".

**Location**: Track 16 description scope indicator + How bullet
"... index creation".

**Issue**: Tests that create indexes via `SchemaProperty.createIndex(...)`
within a `DbTestBase` lifecycle leave the index entity in the
per-method DB. The `@After` drops the DB so cross-test leakage is
impossible, but a test that *expects to see the index in a snapshot
read after the createIndex call* must observe the post-release-write-
lock state — which `SchemaShared.releaseSchemaWriteLock` triggers
either `saveInternal` (AbstractStorage) or `reload` depending on
storage type. In disk mode (`-Dyoutrackdb.test.env=ci`) the path
hits `saveInternal`; in memory mode the path differs subtly.

**Proposed fix**: When writing index-creation tests, immediately
`session.getMetadata().getSchema().reload()` (public API) before
asserting the index is visible — or use `session.getIndexManagerInternal().
getIndex(session, name)` which goes through the live Schema, not
the snapshot. Add this pattern to Step 1's working notes alongside
R7.

### Finding R9 [suggestion]
**Certificate**: Assumption "Plan-cited uncov figures" (VALIDATED-
with-correction).

**Location**: Track 16 description, all four bullets with uncov
counts.

**Issue**: The plan's uncov figures are off by ~5%, well within the
expected staleness window from prior tracks but worth restating
post-Track-15. `core/metadata/sequence` in particular is already at
gate (85.4% / 73.4%) — see R3.

**Proposed fix**: Step 1 must produce `track-16-baseline.md` with
fresh JaCoCo numbers per the Tracks 9, 10, 14 carry-forward
("Baseline remeasurement at Step 1"). Note the corrected sequence
status explicitly.

---

## Risk-tagging signal for Phase B

Per `risk-tagging.md` (referenced by the workflow), step-level risk
tags drive whether Phase B runs the dimensional code review. Track 16's
risk shape is moderate but not deep:

- **High-risk areas warranting `risk: high` step tag** during Phase A
  decomposition: the cluster-selection step (R1 reframe is load-bearing
  — a wrong-framing here will cascade into Track 22's queue and the
  step-level dimensional review must catch it); the `PropertyTypeInternal`
  parameterized step (R6 — large surface, easy to mis-shape).
- **Medium-risk** signals: schema proxies (R5), index creation
  (R8), schema fixtures (R4).
- **Low-risk**: function library, sequence library, validation classes.

The cluster-selection-and-dead-code step is the single highest-leverage
risk surface in Track 16 — flag it `risk: high` so Phase B runs the
step-level dimensional review (CQ + TB + TC at minimum) before
implementer hand-off.
