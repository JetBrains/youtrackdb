# Track 2 Risk Review (Iteration 1)

> **Tooling caveat.** mcp-steroid was not reachable in this session
> (`ToolSearch` returned no `steroid_*` tools). All symbol audits below
> use grep/find. Where polymorphism could hide a call site, this is
> annotated as "(grep-only — may miss polymorphic dispatch)" so the
> reviewer of any follow-up can re-validate via PSI.

## Part 1: Evidence Certificates

### Critical Path Exposure

#### Exposure: Strategy registered on every Gremlin traversal — runs across the entire Cucumber suite (~1900 scenarios)
- **Track claim**: Add `GremlinToMatchStrategy.instance()` to
  `YTDBGraphImplAbstract.registerOptimizationStrategies` so it runs on
  every traversal applied to a YTDB graph (plan §Integration Points,
  step file §3.3).
- **Critical path trace**:
  1. Entry: client `g.V().…toList()` invokes
     `Traversal.applyStrategies()` (TinkerPop) — invoked at least once
     per traversal lifecycle.
  2. TinkerPop dispatches each registered `ProviderOptimizationStrategy`
     in topological order. After Track 2, the registered set is SGS,
     CGS, NEW, MMS (all `ProviderOpt`) plus IOS and QMS (both
     `FinalizationStrategy`, run after).
  3. `GremlinToMatchStrategy.apply(traversal)` — Track 2 NEW.
  4. Steps 1–5 of step file §3.3: idempotency scan, start-step type
     check, `GremlinStepWalker.findTranslatablePrefix`, prefix
     translation, step-list mutation.
  5. If prefix accepted, `new MatchExecutionPlanner(MatchPlanInputs)
     .createExecutionPlan(ctx, profiling, /*useCache=*/false)` —
     `MatchExecutionPlanner.java:472`.
- **Blast radius**: any latent bug in NEW (NPE in walker, classloader
  shadow on a TinkerPop step type, mutation of an immutable HasContainer
  list, etc.) is exposed by every Cucumber scenario that runs through
  this code, including scenarios that should be no-ops (prefix == empty).
  The Cucumber suite is the regression net but is also the surface of
  attack.
- **Existing safeguards**:
  - The four extant `ProviderOpt` strategies (SGS, CGS, MMS, the
    soon-to-be NEW) all silently no-op on un-recognized shapes; the
    pattern works in practice.
  - TinkerPop's strategy chain wraps each strategy's `apply` in a
    try-block that propagates exceptions back to the caller — failures
    surface as test failures, not silent corruption.
  - The plan's invariant "for any traversal whose recognized prefix is
    empty, the strategy is a no-op" (plan §Invariants) is testable.
- **Residual risk**: MEDIUM. The empty-prefix path is the dominant case
  in the Cucumber suite — most scenarios start with `g.V()` followed by
  steps Track 2 doesn't yet recognize. A bug *in the empty-prefix path*
  (e.g. an idempotency-check NPE, or the translator constructing an
  IR before deciding to decline) breaks scenarios that should remain
  untouched. The mitigation is to implement steps 1–2 of `apply`
  (idempotency check + start-step type check) **without ever
  instantiating** `GremlinStepWalker` or any builder — fail-fast, then
  delegate the work.

#### Exposure: `MatchExecutionPlanner(MatchPlanInputs)` ctor adds a third null-`statement` code path
- **Track claim**: Add a fourth public constructor that field-by-field
  defensive-copies inputs without setting `statement` (mirroring the
  existing `(Pattern, aliasClasses, aliasFilters)` ctor).
- **Critical path trace**:
  1. `createExecutionPlan(ctx, profiling, useCache=false)` —
     `MatchExecutionPlanner.java:472`.
  2. Cache-read short-circuit at line 478: `if (useCache &&
     !enableProfiling && statement.executinPlanCanBeCached(session))`.
     Because `useCache=false`, the `&&` short-circuits before
     `statement` is dereferenced — safe.
  3. `buildPatterns(ctx)` at line 490 — `MatchExecutionPlanner.java:4378`
     short-circuits when `pattern != null`, so the `statement`-derived
     `matchExpressions`/`notMatchExpressions` are not re-built.
  4. Cache-write short-circuit at line 627: same guard pattern, safe
     when `useCache=false`.
- **Blast radius**: every direct or indirect call to
  `createExecutionPlan` after the ctor runs. The pre-existing
  `(Pattern, aliasClasses, aliasFilters)` ctor already exhibits the
  same null-`statement` property and is consumed by GQL today
  (`GqlMatchStatement.java:99-100` — confirmed: GQL passes
  `useCache=false`). The new ctor inherits the same precondition.
- **Existing safeguards**: GQL has been running this code path
  successfully (Track 1 episode reports 86 GQL tests pass). The
  null-`statement` pattern is tested in production via GQL.
- **Residual risk**: LOW for Track 2 itself, MEDIUM as a forward-looking
  hazard. The forward hazard: Phase 2 plans to add bytecode-keyed
  caching. If the implementer naively flips `useCache` to `true` for
  the Gremlin path, line 478's `statement.executinPlanCanBeCached`
  NPEs immediately. The new ctor should either set a sentinel
  statement or carry an explicit Javadoc warning about the precondition.

### Strategy Ordering Correctness

#### Exposure: D4 ordering claim relies on TinkerPop's `applyPrior` partial order, but MMS does not declare NEW as a prior dependency
- **Track claim**: D4 (plan lines 251–273) asserts NEW runs "after
  `YTDBGraphStepStrategy` and `YTDBGraphCountStrategy`, before
  `YTDBGraphMatchStepStrategy`". The step file §3 says
  "configures `applyPrior()` to enforce the ordering programmatically".
- **Critical path trace**:
  1. `YTDBGraphMatchStepStrategy.applyPrior()` returns
     `Collections.singleton(YTDBGraphStepStrategy.class)` —
     `YTDBGraphMatchStepStrategy.java:147-149`. Only SGS is declared.
  2. `YTDBGraphCountStrategy.applyPrior()` returns
     `Collections.singleton(YTDBGraphStepStrategy.class)` —
     `YTDBGraphCountStrategy.java:110-112`. Only SGS.
  3. NEW is planned to declare `applyPrior = {SGS, CGS}`. Nothing
     in the existing codebase declares NEW as a prior of MMS.
  4. TinkerPop's partial-order resolution between NEW and MMS is
     therefore **ambiguous**: both have SGS as prior; neither
     constrains the other; resolution depends on TinkerPop's
     deterministic-but-implementation-defined tiebreaker (typically
     insertion order in `clone().addStrategies(...)` —
     `YTDBGraphImplAbstract.java:73-78`).
- **Blast radius**: if TinkerPop ever changes its tiebreaker (e.g. by
  switching from registration order to alphabetical, or by introducing
  parallel-strategy execution), MMS could run before NEW. Concrete
  failure mode: MMS's label-folder absorbs `MatchStep` content into a
  `YTDBGraphStep`'s `hasContainers`; if it runs first, NEW sees a
  `MatchStep` whose first child has been mutated (steps removed). NEW
  then either translates an inconsistent state or correctly declines —
  but the **boundary** between "translate" and "decline" depends on
  having the un-mutated `MatchStep`. This is fragile.
- **Existing safeguards**: registration-order in
  `YTDBGraphImplAbstract.registerOptimizationStrategies` happens to
  place NEW between CGS and MMS in the planned change. TinkerPop's
  current `DefaultTraversalStrategies` sort is stable on registration
  order when `applyPrior`/`applyPost` are ambiguous (grep-only — could
  not verify against the YTDB-forked TinkerPop sources without
  unzipping the jar; assumption tagged below).
- **Residual risk**: MEDIUM. The strategy ordering is a load-bearing
  invariant for D4's correctness claim, but the partial order is
  ambiguous. The mitigation is to declare NEW.applyPost = {MMS} in
  addition to NEW.applyPrior = {SGS, CGS} — `applyPost` flips the
  edge direction so NEW becomes a strict prior of MMS regardless of
  registration order.

#### Exposure: Strategy chain may run on cloned traversals (`union`, `match`, `optional`, etc. clone child traversals)
- **Track claim**: D7 — strategy must be idempotent. Idempotency check
  scans whole step list for `YTDBMatchPlanStep`.
- **Critical path trace**:
  1. TinkerPop clones nested traversals (`union(__.…, __.…)`, `match`,
     `optional`, `where`, etc.) when applying global-child strategies.
     The cloned children carry their own strategy chain.
  2. For Track 2, only the top-level traversal contains the start
     `YTDBGraphStep`; cloned children typically start with `StartStep`
     or anonymous traversals. The plan's check #2 ("return immediately
     if the start step is not a `GraphStep`/`YTDBGraphStep`") covers
     this — anonymous `__.…()` traversals start with `StartStep`, not
     `GraphStep`.
  3. The idempotency scan (#1) and start-step check (#2) each cost
     O(N) per `apply` invocation. The Cucumber suite's deepest nested
     traversals have a few hundred total steps across all global
     children; total amortized cost is negligible.
- **Blast radius**: if either check is incorrect or omitted on a
  child traversal, the strategy could re-translate the child and
  produce a malformed step list. The blast radius is contained to the
  affected child traversal.
- **Existing safeguards**: TinkerPop's strategy chain skips
  `applyStrategies` on traversals already marked locked (assumption —
  see Assumption 4). The plan's idempotency scan is an additional
  defense.
- **Residual risk**: LOW for Track 2 (only `g.V()`/`g.V(ids)`
  recognized, no nested-children translation). Becomes MEDIUM for
  Tracks 5/6/10 where `not`, `optional`, `union` introduce child
  traversals — but those tracks own that risk.

### Performance Implications

#### Exposure: Per-call planning overhead with `useCache=false`
- **Track claim**: `useCache=false` is wired in step file §3.4 with
  caching deferred to Phase 2 (D5).
- **Critical path trace**:
  1. `MatchExecutionPlanner.createExecutionPlan` at line 472 runs the
     full pipeline: `buildPatterns` (line 490, mostly a no-op for our
     pre-built pattern), `splitDisjointPatterns` (line 492),
     `estimateRootEntries` (line 498 — does index size lookups),
     topological scheduling (lines 530–547),
     `manageNotPatterns` (line 550 — no-op for empty NOT list),
     projection-block handling (line 623).
  2. For `g.V()` (a single-node pattern with no filters),
     `estimateRootEntries` does one schema-class size lookup; the
     scheduler picks the single alias as root. The total cost is
     bounded: a handful of map lookups, one schema query, a few
     allocations.
  3. By contrast, the native pipeline for `g.V().toList()` walks the
     graph step iterator directly with zero planner overhead.
- **Blast radius**: an LDBC-style benchmark that runs the same
  short-shape query in a loop pays the planning cost on every
  iteration. For sub-millisecond queries, the overhead is a measurable
  fraction. The plan's Track 12 perf-baseline measurement is the
  designed mitigation.
- **Existing safeguards**: D5 and Track 12 explicitly call out the
  perf measurement; if regression is unacceptable, Phase 2 cache moves
  into Phase 1 with ESCALATE.
- **Residual risk**: MEDIUM as stated. Track 2 itself does not
  measure — it only wires `useCache=false`. The risk is that Track 12
  surfaces an unacceptable regression after several intervening
  tracks have been built atop the no-cache assumption. Suggestion: a
  cheap micro-benchmark inside Track 2 (single-shape `g.V()` 10k
  iterations) to surface gross regression early.

#### Exposure: `MatchPlanInputs` 17-field record + per-call defensive copy
- **Track claim**: record carries `Pattern`, `aliasClasses`,
  `aliasFilters`, `aliasRids`, `matchExpressions`,
  `notMatchExpressions`, `returnItems`, `returnAliases`,
  `returnNestedProjections`, `groupBy`, `orderBy`, `unwind`, `limit`,
  `skip`, `returnDistinct`, `returnElements`, `returnPaths`,
  `returnPatterns`, `returnPathElements` (per step file §1; design.md
  `MatchPlanInputs` class entry). The new ctor field-by-field
  defensive-copies. For `g.V()`, all collection fields are
  effectively empty.
- **Critical path trace**:
  1. Translator builds the record once per `apply` call (when prefix
     accepted). For Track 2's minimal `g.V()` translation, all
     `List`/`Map` fields are empty or single-entry.
  2. The new ctor copies each field. Empty `List.copyOf(emptyList)`
     is a constant-pool reuse in most JVMs; allocation cost is
     near-zero.
  3. Comparison: GQL's `GqlMatchStatement.buildPlan` calls the
     existing `(Pattern, aliasClasses, aliasFilters)` ctor with
     similarly small inputs; no perf complaint has surfaced there.
- **Blast radius**: low — record allocation + a few empty-collection
  copies per `apply` invocation. Even at 10k traversals/second this
  is sub-microsecond overhead.
- **Existing safeguards**: GQL's existing usage is the de-facto perf
  baseline.
- **Residual risk**: LOW. Not a meaningful concern.

#### Exposure: `YTDBStrategyUtil.isPolymorphic` calls `tx.readWrite()` — transaction-state mutation per `apply()`
- **Track claim**: the translator reads
  `YTDBStrategyUtil.isPolymorphic(traversal)` once per `apply()`
  (plan §Integration Points).
- **Critical path trace**:
  1. `YTDBStrategyUtil.isPolymorphic` —
     `YTDBStrategyUtil.java:29-46`. Calls `tx.readWrite()` before
     reading config.
  2. `tx.readWrite()` opens a transaction if none exists — a
     state-mutating side effect on the strategy hot path.
- **Blast radius**: every `apply()` invocation triggers transaction
  setup, even when NEW is going to no-op (empty prefix). This is the
  same pattern as the existing `YTDBGraphStepStrategy.apply` which
  also calls `isPolymorphic` first thing — pre-existing behavior.
- **Existing safeguards**: `tx.readWrite()` is idempotent (the
  transaction is reused if already open). The pattern is established
  in `YTDBGraphStepStrategy.apply` line 32.
- **Residual risk**: LOW. Pre-existing pattern, no incremental risk.
  Suggestion: defer the polymorphism lookup until the prefix-detection
  step decides to translate, so empty-prefix traversals don't pay
  for it — small optimization, not a blocker.

### Boundary Step Lifecycle

#### Exposure: `YTDBMatchPlanStep` lifecycle — clone(), close(), exception propagation, partial-stream consumption
- **Track claim**: `YTDBMatchPlanStep` extends `AbstractStep<Object,
  E>`, holds `SelectExecutionPlan`, opens stream lazily on first
  `processNextStart`, closes on traversal close (step file §3.5).
- **Critical path trace**:
  1. TinkerPop's `Traversal.applyStrategies` may produce a traversal
     that is later **cloned** (e.g. nested traversal reuse). Cloning
     calls `Step.clone()` on every step.
  2. `AbstractStep.clone()` is the inherited default — shallow copy.
     For `YTDBMatchPlanStep`, both clones would share the same
     `SelectExecutionPlan` reference. If both clones later iterate,
     they'd contend on the same plan instance, which holds an
     `ExecutionStream` — undefined behavior.
  3. `YTDBClassCountStep.clone()` (the analogous existing step) at
     `YTDBClassCountStep.java:79-86` overrides clone and resets the
     `done` flag — same pattern is required here.
  4. Partial-stream consumption: a downstream `LimitStep(5)` calls
     `processNextStart` 5 times then stops. The `ExecutionStream`
     remains open. TinkerPop calls `Step.close()` (defaulting to
     `CloseableIterator.closeIterator`) at end-of-traversal. If the
     close path is not implemented, the stream leaks (cursor on
     storage page).
  5. Exception propagation: if `ExecutionStream.next` throws
     mid-stream, TinkerPop wraps in `FastNoSuchElementException`?
     No — TinkerPop's `AbstractStep.processNextStart` propagates
     unchecked exceptions to the caller. The `try/finally` in the
     step's body must close the stream on exception.
- **Blast radius**:
  - Missing `clone()`: nested traversals that re-use the boundary
    step would either iterate twice (corrupted result) or throw
    `IllegalStateException` from a second `start()` on the plan.
  - Missing close on early termination: a leaked stream holds a
    cursor and disk-cache pin. Over many traversals, this grows.
  - Exception propagation without close: same leak.
- **Existing safeguards**: `YTDBClassCountStep` provides the clone
  pattern; `OnCloseExecutionStream` provides a close hook.
- **Residual risk**: MEDIUM. The step file §3.5 mentions "Closes the
  stream on traversal close" but doesn't specify clone semantics or
  exception-path close. Should be flagged as a step-level requirement.

#### Exposure: Concurrency — strategy is a singleton, but is `YTDBMatchPlanStep` safe across threads?
- **Track claim**: `GremlinToMatchStrategy` is a singleton (D7 pattern,
  step file §3.1).
- **Critical path trace**:
  1. The singleton has no mutable state — `apply()` reads from the
     traversal arg only. Safe across threads.
  2. `YTDBMatchPlanStep` is a per-traversal instance and is not
     shared across threads under normal Gremlin usage. TinkerPop's
     traversal lifecycle is thread-confined.
  3. The cached `SelectExecutionPlan` inside the boundary step is
     also thread-confined to the traversal's iteration thread.
- **Blast radius**: low — concurrency contracts mirror existing
  TinkerPop steps.
- **Existing safeguards**: TinkerPop's traversal API is documented
  thread-confined.
- **Residual risk**: LOW. The standard TinkerPop concurrency model
  applies.

### Rollback & Recovery

#### Exposure: post-merge rollback story for Track 2
- **Track claim**: not explicitly addressed in the step file.
- **Critical path trace**: if a hidden bug surfaces post-merge in
  `develop`, the strategy is permanently active because:
  1. Registration is hard-wired in
     `YTDBGraphImplAbstract.registerOptimizationStrategies` lines
     74-78.
  2. There is no global config flag to disable individual
     `ProviderOptimizationStrategy` instances.
- **Blast radius**: if NEW corrupts a critical traversal shape that
  the Cucumber suite did not catch, the only fix is a follow-up
  commit removing the registration line. No runtime kill-switch.
- **Existing safeguards**: the Cucumber suite (~1900 scenarios) is
  the regression net. PR-pipeline test count gate prevents silent
  test removal.
- **Residual risk**: MEDIUM. Without a runtime kill-switch (e.g. a
  `GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_ENABLED` flag), every
  bug discovery is a code-revert event. Suggestion: add a config
  flag (default true) that the strategy reads at the top of
  `apply()` and short-circuits to no-op when false. The cost is one
  configuration option and one boolean read per traversal.

### Testability & Coverage

#### Testability: unit-testing `MatchExecutionPlanner(MatchPlanInputs)` ctor without SQL parsing
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the new ctor is a field-by-field copy. A
  trivial unit test: pass a `MatchPlanInputs` with a hand-built
  `Pattern` (one node, no edges) and assert the resulting planner's
  `pattern`/`aliasClasses`/`aliasFilters` match. Track 1 already
  built `MatchPatternBuilder` which produces the same shape. No SQL
  parser needed.
- **Existing test infrastructure**:
  `MatchPatternBuilderTest.java` (Track 1 — verified present at
  `core/src/test/.../sql/executor/match/builder/MatchPatternBuilderTest.java`)
  provides ready-made fixtures.
- **Feasibility**: ACHIEVABLE.

#### Testability: unit-testing `GremlinToMatchStrategy` in isolation
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: a strategy unit test needs a `Traversal.Admin`
  with a `YTDBGraphStep` start. The existing `GraphStepStrategyTest`
  pattern (`core/src/test/.../GraphStepStrategyTest.java`) shows the
  pattern: create a `GraphBaseTest` subclass, build a traversal via
  `g.V()....asAdmin()`, call `traversal.applyStrategies()`, assert
  step list shape. This requires a real (in-memory) graph instance
  with schema. JUnit 4 + memory engine is well-established. The
  idempotency, start-step-not-graph, and empty-prefix branches are
  each testable as separate scenarios.
- **Existing test infrastructure**: `GraphBaseTest`,
  `GraphStepStrategyTest`, `GraphMatchStrategyTest`,
  `GraphCountStrategyTest`.
- **Feasibility**: ACHIEVABLE.

#### Testability: unit-testing `YTDBMatchPlanStep` in isolation
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the boundary step needs a constructed
  `SelectExecutionPlan` and a `Traversal.Admin` parent. The plan has
  to be a real plan that produces results — this implies a real
  database session. Mocking the plan is possible but the
  `processNextStart` body interacts with `ExecutionStream.hasNext/next`
  and projects result rows to `Vertex`/`Edge`/`Map` — covering the
  projection branches requires real `Result` objects from a real
  query. End-to-end integration tests through a real graph (the
  pattern already used in `GraphStepStrategyTest`) are the natural
  fit. Edge cases for clone/close are testable via reflection on
  the step.
- **Existing test infrastructure**: `GraphBaseTest` + the existing
  pattern.
- **Feasibility**: ACHIEVABLE for behavior; clone/close branches
  need explicit unit tests because end-to-end traversals don't
  exercise them.

#### Testability: identifying which Cucumber scenario regressed
- **Coverage target**: green-suite invariant.
- **Difficulty assessment**: Cucumber's progress plugin lists each
  scenario by name on failure. The plugin output (`progress` and
  `junit:target/cucumber.xml`) is configured in
  `YTDBGraphFeatureTest` line 35. A regression names the scenario
  cleanly. Bisection between baseline and HEAD is feasible.
- **Existing test infrastructure**: `YTDBGraphFeatureTest` (core)
  and `EmbeddedGraphFeatureTest` (embedded).
- **Feasibility**: ACHIEVABLE.

### Assumption Verifications

#### Assumption 1: "the four existing `ProviderOptimizationStrategy` instances on `YTDBGraphImplAbstract`" (plan line 138-139, 184-186)
- **Track claim**: plan describes SGS, CGS, MMS, IOS as the four
  `ProviderOpt` strategies, and QMS as the fifth `FinalizationStrategy`.
- **Evidence search**: grep for `ProviderOptimizationStrategy` and
  `FinalizationStrategy` across all five strategy classes.
- **Code evidence**:
  - `YTDBGraphStepStrategy.java:22-23` — `ProviderOptimizationStrategy`.
  - `YTDBGraphCountStrategy.java:34-35` — `ProviderOptimizationStrategy`.
  - `YTDBGraphMatchStepStrategy.java:60-61` — `ProviderOptimizationStrategy`.
  - **`YTDBGraphIoStepStrategy.java:14-15` — `FinalizationStrategy`,
    NOT `ProviderOptimizationStrategy`.**
  - `YTDBQueryMetricsStrategy.java:14-15` — `FinalizationStrategy`.
- **Verdict**: **CONTRADICTED.**
- **Detail**: There are **three** existing `ProviderOptimizationStrategy`
  instances (SGS, CGS, MMS), not four. IOS is a `FinalizationStrategy`
  alongside QMS. The plan's text in §Component Map ("**five strategies
  registered on Graph class** ... `YTDBGraphIoStepStrategy
  ProviderOptimization`") and the description of the registered
  strategies (plan lines 137-142 and 191-194) is incorrect. D4's
  ordering claim against IOS is irrelevant because IOS is a
  `FinalizationStrategy` and runs after all `ProviderOpt` strategies
  regardless. After Track 2's change there will be **four**
  `ProviderOpt` strategies — SGS, CGS, MMS, and the new
  `GremlinToMatchStrategy` (NEW).

#### Assumption 2: "Strategy ordering: NEW after CGS, before MMS" (D4)
- **Track claim**: NEW must run before MMS so that MMS's label-folder
  becomes a no-op for `g.V().match()` traversals NEW handles, and
  still applies as fallback when NEW declines.
- **Evidence search**: grep `applyPrior` on every existing
  `ProviderOpt` strategy.
- **Code evidence**:
  - `YTDBGraphCountStrategy.java:110-112` — `applyPrior =
    {YTDBGraphStepStrategy.class}`.
  - `YTDBGraphMatchStepStrategy.java:147-149` — `applyPrior =
    {YTDBGraphStepStrategy.class}`.
- **Verdict**: **UNVALIDATED.**
- **Detail**: TinkerPop resolves `ProviderOpt` ordering via the
  partial order induced by `applyPrior`/`applyPost`. Both CGS and
  MMS declare only SGS as prior. Neither declares the other. NEW is
  planned to declare `applyPrior = {SGS, CGS}` (per the strategy's
  ordinary semantics), but that does not order NEW before MMS — MMS
  has no constraint on NEW. The planned NEW–MMS ordering is purely
  registration-order tiebreaker. To make D4 robust, NEW should
  declare `applyPost = {YTDBGraphMatchStepStrategy.class}` in
  addition.

#### Assumption 3: "MatchExecutionPlanner public method signatures unchanged after Phase 1; Phase 1 adds **one** new public ctor" (plan §Invariants)
- **Track claim**: only the new `(MatchPlanInputs)` ctor is added.
- **Evidence search**: `grep -n "public MatchExecutionPlanner\|public InternalExecutionPlan"
  MatchExecutionPlanner.java`.
- **Code evidence**: existing public surface — three ctors at lines
  385, 398, 424; one method `createExecutionPlan` at line 472.
- **Verdict**: **VALIDATED** (assuming Track 2 follows the plan's
  text).
- **Detail**: the additive ctor's pattern (mirror the
  `(SQLMatchStatement)` ctor at line 424) is well-defined. The risk
  is purely procedural — a reviewer must verify Track 2's diff
  contains no other `MatchExecutionPlanner` modification.

#### Assumption 4: "`applyStrategies()` may be invoked more than once" (D7)
- **Track claim**: TinkerPop's `Traversal.applyStrategies()` is
  invoked at least once but may be invoked more than once in cloned
  traversals, in remote execution prep, in test harnesses, etc.
- **Evidence search**: grep for `applyStrategies` invocations in
  test code; grep for `clone()` on traversal in source.
- **Code evidence**: tests under
  `core/src/test/.../gremlin/Graph*StrategyTest.java` invoke
  `traversal.applyStrategies()` directly. No double-invocation in
  current tests, but the protection is for unknown future callers.
- **Verdict**: VALIDATED (by external TinkerPop documentation —
  `Traversal.applyStrategies()` body sets `this.locked = true`
  guarding double-application; behavior verifiable from upstream
  TinkerPop docs).
  *(grep-only — could not confirm against the YTDB-forked
  TinkerPop source without unzipping the jar.)*
- **Detail**: the idempotency check is cheap insurance — even if the
  framework already protects against double-application, having the
  check makes the strategy robust against future framework changes
  and against deliberate test-harness re-invocation.

#### Assumption 5: "MatchPatternBuilder is one-shot" (Track 1 episode)
- **Track claim**: Track 2 will need one builder per traversal
  translation.
- **Evidence search**: grep `built` flag handling in
  `MatchPatternBuilder.java`.
- **Code evidence**: Track 1 episode (plan lines 442-448) confirms
  the one-shot design — `addNode/addEdge/build` after `build()`
  throws `IllegalStateException`.
- **Verdict**: VALIDATED.
- **Detail**: Track 2's `GremlinPatternAssembler` must instantiate a
  fresh `MatchPatternBuilder` per traversal; this is consistent with
  the singleton `GremlinToMatchStrategy` having no shared mutable
  state.

#### Assumption 6: "g.V() / g.V(ids) execution semantics match the un-translated path"
- **Track claim**: integration test asserts result-set equality.
- **Evidence search**: grep `getElementsByIds` in `YTDBGraphStep` and
  compare against MATCH RID semantics.
- **Code evidence**: `YTDBGraphStep.java:99-103` — when `ids` non-empty,
  iterates `graph.vertices(ids)` and filters in-memory by
  `HasContainer.testAll`. MATCH with `RID` constraint resolves to
  `FetchFromRidsStep` (or the equivalent prefetch) — produces the
  same vertices, same order modulo deduplication.
- **Verdict**: VALIDATED for the smoke-test case (`g.V()`,
  `g.V(ids)` with no `has` filters).
- **Detail**: order may differ because MATCH's planner-driven
  ordering and TinkerPop's iterator-driven ordering use different
  traversal patterns. The integration test should compare result
  sets as **sets**, not lists, or use a stable sort key. The plan
  text "produces the same vertices" is order-agnostic; the test
  implementation must reflect that.

## Part 2: Findings

### Finding R1 [should-fix]
**Certificate**: Assumption 1 (plan strategy categorization), Exposure
"D4 ordering claim relies on TinkerPop's `applyPrior` partial order".
**Location**: plan §Architecture Notes / Component Map (lines 73–84,
137–142), plan §D4 (lines 251–273); track-2 step file §3.6.
**Issue**: The plan asserts there are **four** existing
`ProviderOptimizationStrategy` instances on `YTDBGraphImplAbstract`
including `YTDBGraphIoStepStrategy`. Code shows that
`YTDBGraphIoStepStrategy` is a **`FinalizationStrategy`**, not a
`ProviderOptimizationStrategy`
(`YTDBGraphIoStepStrategy.java:14-15`). The factual error does not
break Track 2's mechanics directly, but it weakens D4's ordering
analysis: D4 reasons about IOS as a `ProviderOpt` peer when it isn't.
After Track 2 there will be four `ProviderOpt` strategies (SGS, CGS,
MMS, NEW) plus two `FinalizationStrategy` (IOS, QMS). The categorization
should be corrected so reviewers can verify the partial-order
correctness against the right peer set.
**Proposed fix**: Update the plan text in §Component Map and §D4 to
list only three pre-existing `ProviderOpt` strategies (SGS, CGS, MMS)
and to acknowledge IOS as a `FinalizationStrategy`. Update the Mermaid
component map subgraph label correspondingly. No code change required
in Track 2 itself — but the analysis underpinning D4 is sharper after
the fix.

### Finding R2 [blocker]
**Certificate**: Assumption 2 ("Strategy ordering NEW–MMS"),
Exposure "D4 ordering correctness".
**Location**: plan §D4 (lines 251–273); track-2 step file §3.6
("Configures `applyPrior()` to enforce the ordering programmatically").
**Issue**: D4 requires NEW to run **strictly before** MMS so that
NEW gets first crack at `g.V().match(...)` and MMS becomes a fallback
label-folder. The plan claims this is "enforced programmatically" via
`applyPrior()`. Code evidence: MMS's `applyPrior()` returns only
`{YTDBGraphStepStrategy.class}` (`YTDBGraphMatchStepStrategy.java:147-149`).
Nothing in the existing code declares NEW as a prior of MMS. NEW
declaring `applyPrior = {SGS, CGS}` does **not** order NEW before MMS
— TinkerPop's strategy partial-order resolution falls back to a
tiebreaker (typically registration order). This is fragile: a
TinkerPop-fork upgrade that changes the tiebreaker reorders the
strategies and breaks D4 silently.
**Proposed fix**: Add to the step file §3.6 a requirement that
`GremlinToMatchStrategy.applyPost()` returns
`Set.of(YTDBGraphMatchStepStrategy.class)`. `applyPost` flips the edge
direction in the partial-order graph and forces NEW to run strictly
before MMS regardless of registration order. Add a unit test in
`GraphMatchStrategyTest` that runs the full strategy chain and asserts
the final step list shape proves NEW ran before MMS (e.g. assert that
when NEW translates a `g.V().match(...)`, no MMS-folded
`hasContainers` appear on the resulting `YTDBGraphStep`).

### Finding R3 [should-fix]
**Certificate**: Exposure "`YTDBMatchPlanStep` lifecycle".
**Location**: track-2 step file §3.5.
**Issue**: The step file specifies that `YTDBMatchPlanStep` "Closes
the stream on traversal close" but does not specify three lifecycle
edge cases that are load-bearing for correctness:
1. **`clone()`**: `AbstractStep.clone()` is shallow — a cloned
   boundary step would share the `SelectExecutionPlan` and any
   in-progress `ExecutionStream` with the original. The existing
   `YTDBClassCountStep.clone()` (at
   `YTDBClassCountStep.java:79-86`) is the established pattern: deep-
   copy state-bearing fields and reset progress flags. Without an
   override, nested traversals that re-use the boundary step
   produce undefined behavior.
2. **Exception path**: if `ExecutionStream.next` throws mid-stream,
   the step must close the stream via `try/finally`. Otherwise a
   cursor + disk-cache pin leaks per failing traversal.
3. **Partial-stream consumption** (downstream `LimitStep`,
   `RangeGlobalStep`): TinkerPop calls `Traversal.close()` at end-of-
   iteration; the boundary step must implement `close()` to forward
   the call to the underlying stream.
**Proposed fix**: Expand step file §3.5 to specify (a) `clone()`
override resetting per-iteration state, (b) `try/finally` around
`ExecutionStream.next` calls in `processNextStart`, and (c)
`close()` override that closes the stream. Reference
`YTDBClassCountStep` and `OnCloseExecutionStream` as the patterns to
follow. Add unit tests covering each lifecycle path.

### Finding R4 [should-fix]
**Certificate**: Exposure "post-merge rollback story".
**Location**: track-2 step file §3.6 (registration), plan §Constraints.
**Issue**: Track 2 wires the strategy directly into
`YTDBGraphImplAbstract.registerOptimizationStrategies` — a
hard-coded line. There is no runtime kill-switch. If a hidden bug
escapes the Cucumber suite (which has known scenario gaps —
`@RemoteOnly`, `@MultiProperties`, etc., excluded by
`YTDBGraphFeatureTest.java:20-30`) and surfaces post-merge in
production, the only mitigation is a code revert. For a feature that
exposes itself on **every** traversal, a config-flag fallback is
proportionate insurance.
**Proposed fix**: Add a `GlobalConfiguration` boolean flag
(suggested name: `QUERY_GREMLIN_TO_MATCH_ENABLED`, default `true`).
The strategy reads it once at the top of `apply()` (after the cheap
`isPolymorphic` lookup, which already touches the database session)
and short-circuits to no-op when disabled. The cost is one boolean
read per traversal. Document the flag in the design.md "Strategy
idempotency" section as the runtime kill-switch. The flag also
enables A/B comparison in the LDBC perf-baseline track (Track 12).

### Finding R5 [should-fix]
**Certificate**: Exposure "`MatchExecutionPlanner(MatchPlanInputs)`
ctor adds a third null-`statement` code path".
**Location**: track-2 step file §1; plan §D2.
**Issue**: The new ctor leaves `MatchExecutionPlanner.statement`
null (mirroring the pre-existing `(Pattern, aliasClasses,
aliasFilters)` ctor). The current `createExecutionPlan` method
short-circuits the cache reads/writes when `useCache=false`, so
no NPE occurs at line 478 or 627 (`MatchExecutionPlanner.java`).
However, this is a forward-looking trip-wire: Phase 2 plans
bytecode-keyed plan caching for the Gremlin path. If a future
implementer flips `useCache` to `true` without first ensuring
`statement` is non-null, line 478's
`statement.executinPlanCanBeCached(session)` NPEs immediately and
silently breaks every translated traversal.
**Proposed fix**: Add to the new ctor's Javadoc (and to design.md
§D2) an explicit precondition: "this ctor leaves `statement` null;
callers MUST pass `useCache=false` until a Phase 2 cache redesign
populates `statement` or refactors the cache guards to use a non-
null sentinel". Optionally, add a defensive `assert statement !=
null || !useCache` at the start of `createExecutionPlan` —
preserves current behavior, fails fast in a future regression.

### Finding R6 [suggestion]
**Certificate**: Exposure "Per-call planning overhead with
`useCache=false`".
**Location**: track-2 step file §3.4; plan §D5.
**Issue**: Track 12 is the designed perf-baseline measurement, but
Track 12 sits at the end of a 12-track chain. If `g.V().toList()`
in tight loops shows a meaningful regression, the discovery happens
late. Track 2 wires the no-cache path and is the ideal point to
surface gross regression early — a single-shape micro-benchmark
takes ~10 lines and runs in seconds.
**Proposed fix**: Add a one-shot micro-benchmark step in Track 2
(a JUnit test marked `@Ignore` by default, or a tiny dev-time JMH
fixture) that measures `g.V().toList()` over 10k iterations on the
modern dataset, comparing strategy-on vs strategy-off (using the
config flag from R4). The output is a printed delta, not a gate.
Provides early warning before Track 12 confirms the picture at full
LDBC scale.

### Finding R7 [suggestion]
**Certificate**: Exposure "Strategy registered on every Gremlin
traversal — runs across the entire Cucumber suite".
**Location**: track-2 step file §3.3 (`apply` flow steps 1–5).
**Issue**: The `apply` method's checks 1 and 2 (idempotency scan,
start-step type check) are O(N) and O(1) respectively, both fast.
But the plan's step ordering ("1. idempotency, 2. start step, 3.
walker, …") could be misread as "always run the walker". A
defensive implementation should never instantiate `GremlinStepWalker`
or any builder when checks 1 or 2 fail. For empty-prefix traversals
(the dominant case in the Cucumber suite), this is the difference
between "near-zero overhead" and "small but measurable allocation
per scenario".
**Proposed fix**: Annotate step file §3.3 explicitly: "checks 1 and
2 must complete before any builder/walker allocation". Add a unit
test that asserts no allocation overhead when the strategy declines
(e.g. count `MatchPatternBuilder.<init>` invocations via a
test-only counter or a debug breakpoint).

### Finding R8 [suggestion]
**Certificate**: Assumption 6 ("g.V() / g.V(ids) result-set parity").
**Location**: track-2 step file §4 ("Verifies via integration test
that `g.V().toList()` produces the same vertices as the un-translated
path").
**Issue**: MATCH and the native pipeline produce the same set of
vertices but not necessarily in the same order — MATCH's
planner-driven scheduling and TinkerPop's iterator-driven
materialization use different orderings. A naive
`assertEquals(list1, list2)` will fail intermittently. Track 1's
episode warned about exactly this kind of non-determinism for
multi-pattern golden tests.
**Proposed fix**: Specify in step file §4 that the smoke test
compares result **sets**, not lists, or sorts both sides by RID
before comparison. Codify this as a helper in
`GraphBaseTest` if not already present.

## Summary

- **Counts by severity**: 1 blocker, 4 should-fix, 3 suggestion.
- **Most important findings**:
  - **R2 (blocker)**: D4's "NEW must run before MMS" ordering is
    asserted but the partial-order constraints that would enforce it
    don't exist — `applyPrior` from MMS doesn't reference NEW.
    Mitigation: add `applyPost = {MMS}` to NEW's strategy declaration.
  - **R3 (should-fix)**: `YTDBMatchPlanStep` lifecycle gaps —
    `clone()`, exception-path close, and downstream-`Limit` close
    semantics are all unspecified. Established pattern in
    `YTDBClassCountStep` covers `clone()`.
  - **R4 (should-fix)**: no runtime kill-switch — adding a config
    flag is cheap insurance for a feature that runs on every
    traversal.
  - **R5 (should-fix)**: `(MatchPlanInputs)` ctor inherits the
    pre-existing null-`statement` trip-wire; document the
    precondition explicitly so a Phase 2 caching change does not
    silently NPE.
  - **R1 (should-fix)**: factual correction — IOS is a
    `FinalizationStrategy`, not a `ProviderOptimizationStrategy`.
- **Contradicted assumption**: **Assumption 1** — the plan's
  classification of `YTDBGraphIoStepStrategy` as a
  `ProviderOptimizationStrategy` is contradicted by code at
  `YTDBGraphIoStepStrategy.java:14-15`. After Track 2 there will be
  four `ProviderOpt` strategies (SGS, CGS, MMS, NEW), not five.
