# Track 2 — Adversarial Review (Iteration 1)

**Reviewer role:** devil's advocate against the chosen approach for Track 2:
strategy skeleton + boundary step + minimal `g.V()`/`g.V(ids)` translation.

**Tooling caveat:** mcp-steroid was unreachable in this session (no
`steroid_*` tool schemas were available via ToolSearch). All symbol claims
below are grep- and Read-grounded. Where polymorphic dispatch or override
chains matter, this is called out per challenge as a *reference-accuracy
caveat*. Counter-evidence that depends on a single, named symbol was
verified by direct file reads.

Inputs read:

- `docs/adr/gremlin-to-match-translator/implementation-plan.md`
- `docs/adr/gremlin-to-match-translator/tracks/track-2.md`
- `docs/adr/gremlin-to-match-translator/design.md`
- `core/.../sql/executor/match/MatchExecutionPlanner.java`
- `core/.../sql/executor/SelectExecutionPlanner.java`
- `core/.../gremlin/traversal/strategy/optimization/{YTDBGraphStepStrategy,YTDBGraphCountStrategy,YTDBGraphMatchStepStrategy}.java`
- `core/.../gremlin/YTDBGraphImplAbstract.java`, `YTDBGraphEmbedded.java`
- `core/.../gremlin/traversal/strategy/YTDBStrategyUtil.java`
- `core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java`
- `core/.../gremlin/YTDBGraphQueryBuilder.java`
- `core/.../sql/executor/match/builder/MatchPatternBuilder.java`
- `core/.../gql/parser/GqlMatchStatement.java`

---

## Part 1 — Challenge Certificates

### Decision Challenges

#### Challenge: Decision D1 — Integration via `ProviderOptimizationStrategy`

- **Chosen approach**: register `GremlinToMatchStrategy` as a global
  `ProviderOptimizationStrategy` on `YTDBGraphEmbedded`. Every traversal
  built through `g.V()…` goes through the translator's prefix detector
  whether the user asked for it or not.
- **Best rejected alternative**: explicit entry point on
  `YTDBGraphTraversalSourceDSL` (e.g. `g.matchPattern(…)`).
- **Counterargument trace**:
  1. The strategy is registered statically via
     `YTDBGraphEmbedded`'s static initializer
     (`YTDBGraphEmbedded.java:13-15` — `static { registerOptimizationStrategies(YTDBGraphEmbedded.class); }`),
     and `registerOptimizationStrategies` adds the strategy globally per
     `Graph` class
     (`YTDBGraphImplAbstract.java:68-79`). There is **no per-test or
     per-traversal opt-out mechanism in the codebase** — neither
     `withoutStrategies` nor `removeStrategies` is used anywhere in
     `core/src/test/java/.../gremlin/` (verified via grep).
  2. `YTDBGraphStep.elements()`
     (`YTDBGraphStep.java:90-133`) already implements an optimized
     `g.V()` scan that calls `YTDBGraphQueryBuilder.build()`
     (`YTDBGraphQueryBuilder.java:160-188`). For `g.V().toList()` the
     native path is **already** a single SELECT against the `V` class
     hierarchy.
  3. Replacing this with the MATCH pipeline pays:
     `buildPatterns` + `splitDisjointPatterns` + `estimateRootEntries`
     (cardinality lookup) + topological-sort (vacuous for one node) +
     prefetch decision + `MatchFirstStep` construction (which itself
     creates a SELECT statement at
     `MatchExecutionPlanner.java:1869` and calls
     `select.createExecutionPlan` to obtain a sub-plan) + the empty
     projection block. With D5 ("no plan cache in Phase 1") this
     overhead is paid on every call.
  4. An explicit entry point (`g.matchPattern(...)`) limits exposure
     to traversals the user opts in. The argument that "only a strategy
     captures pre-existing client code" actually argues against the
     strategy approach in Phase 1: the chosen approach silently
     **degrades** every existing `g.V()…` traversal that the prefix
     detector recognizes — even when the original native path was
     already optimal.
- **Codebase evidence**:
  `YTDBGraphStep.java:99-124` shows the native `g.V()` path is a single
  SQL SELECT (with optional class union, GraphQueryBuilder.build).
  `MatchExecutionPlanner.java:1859-1877` shows the MATCH path for a
  single-node pattern still creates a child `SQLSelectStatement` and
  wraps it in a `MatchFirstStep` — it does **not** beat the native
  path; it duplicates the native scan inside an extra step.
- **Survival test**: **WEAK**. The strategy approach survives the
  Cucumber-coverage argument (no client code change, every traversal
  exercised), but the silent-capture risk + the no-cache-in-Phase-1
  decision (D5) compound: every translated single-node `g.V()` carries
  unconditional planning overhead with no upside. The plan
  acknowledges this in D5 ("perf-baseline track quantifies the cost"),
  which is a deferred admission, not a defense. **The minimal scope
  for Track 2 should explicitly NOT translate bare `g.V()` / `g.V(ids)`
  — let those keep using `YTDBGraphStep` directly. Translation should
  start at the simplest case where MATCH actually adds value (e.g.
  `g.V().out()` in Track 3).** This makes the smoke test in Track 2
  the strategy-engagement plumbing itself, not a value-negative
  translation.

#### Challenge: Decision D2 — Additive `(MatchPlanInputs)` ctor

- **Chosen approach**: add a public record `MatchPlanInputs` with **17
  fields** (`Pattern`, 3 alias maps, 2 expression lists, 3 return-item
  lists, 3 SQL clauses, 2 SQL paginations, 1 distinct flag, 4 return-mode
  flags) plus a corresponding `MatchExecutionPlanner(MatchPlanInputs)`
  ctor that field-by-field defensive-copies.
- **Best rejected alternative**: synthesise a `SQLMatchStatement` AST
  node from the translator and pass it through the existing
  `MatchExecutionPlanner(SQLMatchStatement)` ctor
  (`MatchExecutionPlanner.java:424-454`).
- **Counterargument trace**:
  1. `SQLMatchStatement` is JJTree-generated and lives in
     `internal/core/sql/parser/` which CLAUDE.md flags as off-limits
     for editing — but it can be **constructed** from outside (it
     exposes setters used by the parser). The plan calls this "awkward"
     but does not show that constructing a `SQLMatchStatement` AST
     externally is impractical: GQL's
     `GqlMatchStatement.buildPlan` (`GqlMatchStatement.java:99`) already
     bypasses the AST ctor by calling the 3-arg ctor — but that is a
     choice, not a forced move.
  2. The 17-field record is itself a small surface, but it tightens
     the coupling between every Track that touches the planner
     (Tracks 4/5/7–9/11 per D2 risks/caveats) and the record's exact
     field set. Each new return-mode or grouping primitive will mean a
     record-shape change. The existing `(SQLMatchStatement)` ctor is
     parser-versioned and stable.
  3. Track 1's discoveries already weaken D2's stated rationale:
     Track 1's episode says "addEdge does NOT register target
     class/filter — multi-call API contract" and "MatchWhereBuilder
     uses reflection on parser-internal SQLInCondition.operator". Both
     reveal that the AST already has friction the shared builders are
     papering over. Adding a 17-field record is a third such layer.
- **Codebase evidence**:
  - `MatchExecutionPlanner.java:284-286` — `groupBy`, `orderBy`,
    `unwind` are `private final`. The new ctor must initialize all
    three at construction; the record + delegating-ctor approach
    works. So this objection is procedural only.
  - `MatchExecutionPlanner.java:478` and `:629` both call
    `statement.executinPlanCanBeCached(session)` and
    `statement.getOriginalStatement()`. The 3-arg ctor leaves
    `statement = null`. This is a **pre-existing latent NPE** that
    the Track 2 ctor inherits. It is mitigated only by the
    `useCache=false` discipline, which is a soft contract.
- **Survival test**: **WEAK**. The decision survives, but the
  rationale is weaker than presented:
  (a) the rejected alternative — synthesise `SQLMatchStatement` —
  is dismissed without a constructive demonstration that it cannot
  work;
  (b) the same NPE risk affects both ctors and remains untreated;
  (c) every consumer Track now depends on the record's field-shape,
  raising the cost of any additive field. **At minimum the rationale
  should explicitly enumerate the `statement == null` NPE risk and
  add a guard / assertion (or a non-null sentinel `statement`) inside
  `createExecutionPlan` so the contract is enforced rather than
  documented.**

#### Challenge: Decision D4 — Strategy ordering

- **Chosen approach**: insert `GremlinToMatchStrategy` between
  `YTDBGraphCountStrategy` and `YTDBGraphMatchStepStrategy` in
  `YTDBGraphImplAbstract.registerOptimizationStrategies`
  (`YTDBGraphImplAbstract.java:73-78`); declare ordering via
  `applyPrior`.
- **Best rejected alternative**: replace `YTDBGraphMatchStepStrategy`
  entirely (the plan listed this as alternative (c)).
- **Counterargument trace**:
  1. Reading `YTDBGraphMatchStepStrategy.java:73-140`: this strategy's
     entire job is to **fold leading has-label predicates from a
     `MatchStep`'s first global child into the preceding
     `YTDBGraphStep`**. It only fires when the traversal is
     `[YTDBGraphStep, MatchStep]` and the match's first global child
     starts with `HasStep`/`YTDBHasLabelStep`.
  2. The new translator's longer-term goal includes translating
     `g.V().match(...)` end-to-end (Track 5 and beyond). When the new
     translator handles `match(...)`, this folding is moot — the
     translator builds the Pattern directly from the match step's
     children. When the new translator declines, the existing
     `MatchStep` is preserved, and the label-folder still applies.
  3. The plan's risk note says "Cucumber test runs that exercised the
     old label folder must still pass". But **the only way the old
     folder runs is when the new translator declines**. With Track 2's
     scope (only bare `g.V()` / `g.V(ids)` recognized), the old folder
     runs on every `g.V().match(...)` traversal — the new strategy
     never fires for them. So D4's ordering is benign in Track 2 but
     becomes **load-bearing** later.
  4. The deeper concern: `YTDBGraphMatchStepStrategy.applyPrior()`
     returns `{YTDBGraphStepStrategy.class}` (line 147-149). It does
     **not** declare it must run after `GremlinToMatchStrategy`.
     `YTDBGraphCountStrategy.applyPrior()` likewise returns
     `{YTDBGraphStepStrategy.class}` only. Three sibling strategies
     all declaring the same single prior creates a partial order
     where any topological sort is admissible. TinkerPop's
     `TraversalStrategies` resolution is order-deterministic given a
     fixed strategy set, but **registration order from the addStrategies
     call is the only tiebreaker** when applyPrior/applyPost don't
     constrain. The plan acknowledges this ("Configures `applyPrior()`
     to enforce the ordering programmatically") but does not specify
     which strategies the new one's `applyPrior` must include.
     Specifically: to ensure
     `Count → GremlinToMatch → MatchStep`, the new strategy's
     `applyPrior` must include **both**
     `YTDBGraphStepStrategy.class` AND `YTDBGraphCountStrategy.class`,
     and **`YTDBGraphMatchStepStrategy` must be modified to add
     `GremlinToMatchStrategy.class` to its `applyPrior`** — otherwise
     the resolver may schedule the new strategy after the match-step
     folder (which would mean the folder runs on the original
     traversal, attaches has-containers to the graph step, and the
     new translator then sees a `[YTDBGraphStep, MatchStep]` shape
     where the match step's first global child has been pruned).
- **Codebase evidence**:
  `YTDBGraphMatchStepStrategy.java:147-149` and
  `YTDBGraphCountStrategy.java:109-112` both return `singleton(YTDBGraphStepStrategy.class)`.
  Neither references the new strategy.
- **Survival test**: **WEAK**. The chosen ordering can be enforced
  *only* by also modifying `YTDBGraphMatchStepStrategy.applyPrior()` to
  include `GremlinToMatchStrategy.class`. The plan's "1-line change"
  to `YTDBGraphImplAbstract` plus "applyPrior on the new strategy" is
  insufficient: registration order is not a binding constraint when
  `applyPrior` declarations leave a partial order. **The plan must
  either (a) also modify `YTDBGraphMatchStepStrategy` (admit a second
  edit to a previously "untouched" file) or (b) use `applyPost` on
  the new strategy to forward-declare the dependency.** Either way the
  invariant "the only modification to existing strategies is one
  insertion in `registerOptimizationStrategies`" needs revising.

#### Challenge: Decision D7 — Idempotency by step-list scan

- **Chosen approach**: scan the entire step list looking for any
  `YTDBMatchPlanStep` instance on every `apply()` invocation.
- **Best rejected alternative**: attach a sentinel marker to the
  `Traversal.Admin`'s strategies/sideEffects/locked-state on first
  application, then short-circuit when the sentinel is present.
- **Counterargument trace**:
  1. `Traversal.applyStrategies()` is called eagerly during
     traversal-source construction in many tests
     (`GraphStepStrategyTest.java:23` — `traversal.applyStrategies();`)
     and is also called internally by TinkerPop on cloned and
     remote-prepared traversals. The plan's own argument for
     idempotency relies on "TinkerPop may invoke
     `applyStrategies()` more than once".
  2. A linear scan over `getSteps()` is O(N) per call. For a typical
     traversal (single digits) this is cheap — that is the plan's
     argument. **But** the scan happens in **every** strategy in the
     chain in addition to the new one: `YTDBGraphStepStrategy`,
     `YTDBGraphCountStrategy`, `YTDBGraphMatchStepStrategy`,
     `YTDBGraphIoStepStrategy`, the new strategy, and
     `YTDBQueryMetricsStrategy`. The first three already do their own
     re-application guards (`YTDBGraphStepStrategy.apply` checks
     `current instanceof GraphStep` and early-out conditions). Adding
     a sixth scan is acceptable if the cost is genuinely O(N), but
     the scan in the new strategy needs to walk into nested global
     children (sub-traversals of `union`, `match`, `not`,
     `optional`) to be safe — otherwise re-translation in a cloned
     sub-traversal whose wrapper was re-applied silently falls
     through.
  3. A simpler alternative: short-circuit if `getStartStep() instanceof
     YTDBMatchPlanStep`. The plan rejects this (D7 risks/caveats: "If
     `YTDBMatchPlanStep` is somewhere other than position 0 (because
     user wrapped it), naïve detection fails. Detector must scan the
     entire step list, not just the start step."). **But** "user
     wrapped it" is a contradiction: the user does not construct
     `YTDBMatchPlanStep` directly — only the strategy does. The only
     way `YTDBMatchPlanStep` ends up at non-zero position is if the
     strategy itself put it there (e.g. translated a non-leading
     prefix, which Track 2 explicitly does not do — the prefix
     starts at the root step).
- **Codebase evidence**: no codebase evidence supports the "user wrapped
  it" scenario. `Traversal.Admin.addStep(int, Step)` exists, but no
  YouTrackDB code path moves an `YTDBMatchPlanStep` to a non-zero
  index. Existing strategies also assume the GraphStep is at the
  start (`YTDBGraphStepStrategy.java:97-99` walks from
  `getStartStep()`).
- **Survival test**: **WEAK**. The full-list scan is defensive
  programming against a scenario that the plan itself creates only by
  positing it. The simpler check (`getStartStep() instanceof
  YTDBMatchPlanStep`) is sufficient given the construction discipline.
  **The risk inverts**: a full-list scan that descends into
  sub-traversals' global children might miss a clone that re-wraps a
  translated traversal as an `OptionalStep` body. The plan does not
  specify whether the scan recurses; the design.md phrase "scan the
  step list once for any `YTDBMatchPlanStep` instance" is ambiguous.
  **Recommend: tighten to "the strategy short-circuits iff the start
  step is a `YTDBMatchPlanStep`"** — this is the construction-
  consistent guard and matches existing strategy-style guards.

#### Challenge: Decision (implicit) — `INSTANCE` field vs static instance

- **Chosen approach**: per design.md class diagram, `GremlinToMatchStrategy`
  has a `-INSTANCE GremlinToMatchStrategy` field with `+instance()` accessor,
  mirroring the existing strategies (e.g.
  `YTDBGraphMatchStepStrategy.java:63` `private static final
  YTDBGraphMatchStepStrategy INSTANCE = new YTDBGraphMatchStepStrategy();`).
- **Best rejected alternative**: make it a true singleton enum
  (`enum GremlinToMatchStrategy { INSTANCE; ... }`).
- **Counterargument trace**:
  1. The existing four strategies all use the same `INSTANCE`-field
     idiom because they were ported from OrientDB. Consistency with
     the team's local style is a real value.
  2. An enum singleton would give serialization safety and prevent
     reflective construction; but no test or code path constructs
     strategies reflectively, and TraversalStrategies are not
     serialized in this project.
- **Survival test**: **YES**. Local consistency outweighs minor
  textbook benefits. No finding.

### Invariant Challenges

#### Violation scenario: "MatchExecutionPlanner existing public method signatures unchanged"

- **Invariant claim**: the only modification to `MatchExecutionPlanner`
  in Phase 1 is one new public ctor; no existing ctor or method is
  altered.
- **Violation construction**:
  1. Start state: the 3-arg ctor at
     `MatchExecutionPlanner.java:398-415` has the comment "Defensive
     copy: aliasFilters may be immutable (e.g. Map.of() from GQL).
     detectNotInAntiJoin() mutates this map to strip NOT IN
     conditions." It defensive-copies `aliasFilters` only.
     `aliasClasses` is stored by reference and is also mutated
     downstream (`MatchExecutionPlanner.java:4520`, `:4665`, `:4678`
     all do `aliasClasses.put(...)`).
  2. Action sequence: Track 2 introduces the new
     `MatchExecutionPlanner(MatchPlanInputs)` ctor. To match the
     existing 3-arg ctor's defensive-copy posture, the new ctor must
     defensive-copy `aliasFilters` AND `aliasClasses` AND `aliasRids`
     (currently `Map.of()` in 3-arg ctor — i.e. immutable, **and**
     `aliasRids` is never mutated, so a `Map.of()` is fine; but if
     Track 4 starts populating `aliasRids` via `MatchPlanInputs`, the
     new ctor must defensive-copy it because of pattern-trace symmetry
     with the others).
  3. Intermediate state: if the new ctor *fails* to defensive-copy
     `aliasClasses`, the planner mutates the translator's internal
     map (e.g. via the inferred-while-class branch at line 4520).
     A subsequent call from the translator re-using the same map
     (e.g. union of two patterns inside `MultiPlanMatchStep`)
     observes the mutated state.
  4. Violation point: this is a behavioral regression that does not
     change the **signature** of any existing method, but mutates
     state across an API boundary that the 3-arg ctor's comment
     implies is a defensive boundary. The invariant claim is about
     signatures, but the spirit of "additive purely" is broader. The
     plan's wording "field-by-field defensive-copies the inputs
     (mirroring the existing `(SQLMatchStatement)` ctor's pattern)"
     refers to the 4-arg ctor, not the 3-arg one — and the
     `(SQLMatchStatement)` ctor copies AST nodes with `.copy()`, not
     map structures, so the "mirror" is imprecise.
  5. Observable consequence: subtle bug; cross-call state pollution;
     hard to surface without a multi-pattern union test (Track 10).
- **Feasibility**: CONSTRUCTIBLE with caveats. The exact regression
  is gated on Track 10 introducing a multi-plan reuse scenario, but
  the seeds are sown in Track 2. The invariant claim should be
  strengthened to "no existing public method signature is altered AND
  no input passed to the new ctor is mutated by the planner".

#### Violation scenario: "Cucumber suite test count after ≥ before"

- **Invariant claim**: the count of passing Cucumber scenarios after
  Phase 1 is at least the count before.
- **Violation construction**:
  1. Start state: the strategy is registered in `YTDBGraphEmbedded`'s
     static initializer, so every Cucumber scenario goes through it.
  2. Action sequence:
     (a) Track 2 enables the strategy with the
     "minimal `g.V()` / `g.V(ids)`" recognized set.
     (b) A Cucumber scenario `g.V().toList()` is built. The new
     strategy fires, finds the start step is a `YTDBGraphStep`, builds
     a single-node `MatchPlanInputs` with `aliasClasses = {a: "V"}`
     (the V default at `YTDBGraphQueryBuilder.java:111`), and
     constructs an empty `MatchPatternBuilder`.
     (c) The planner's `createExecutionPlan` runs. Because
     `returnElements/Paths/Patterns/PathElements` are all `false`
     (Track 2 doesn't set any of them) and `returnItems` is empty,
     control flows into the `else` branch at
     `MatchExecutionPlanner.java:597-624`, which builds an empty
     `SQLProjection` with `items = []` and `returnDistinct = false`.
     (d) `handleProjectionsBlock` is invoked
     (`SelectExecutionPlanner.java:320`). Because `info.distinct == false`,
     `info.groupBy == null`, `info.aggregateProjection == null`,
     control reaches the `else` branch at line 363, which calls
     `handleProjections(...)`. `handleProjections` at line 759 only
     emits a `ProjectionCalculationStep` if `info.projection != null`.
     The empty projection has `items.isEmpty()` so
     `SQLProjection.calculateSingle` at line 146-149 returns the input
     row unchanged.
     (e) The plan's output rows are MATCH result rows shaped as
     `{<aliasName>: <vertex>}` — i.e. a `Result` with property
     `<alias>` set to a vertex.
  3. Intermediate state: `YTDBMatchPlanStep` receives these rows. Per
     Track 2's plan: "On `processNextStart`, drives the plan's
     `ExecutionStream`, pulls one `Result`, projects it to the
     configured output type, and wraps it in a `Traverser`."
  4. Violation point: the boundary step extracts the vertex via
     `result.getProperty(<alias>)` (or equivalent). The plan does not
     specify how the boundary step knows the alias name. If it
     hardcodes a default name (`a`, or
     `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX + "0"` —
     **package-private**, see CR4 in the plan, so cannot be referenced
     from `internal/core/gremlin/translator/step/`), the test breaks.
     If it derives the alias from the prefix-translation result, the
     plan must surface that alias to the boundary — which is not in
     the design.md class diagram for `YTDBMatchPlanStep` (it has only
     `plan` and `outputType` fields).
  5. Observable consequence: every `g.V().toList()` Cucumber
     scenario fails because the boundary step emits empty traversers
     (or NPEs trying to extract the vertex). Test count decreases.
- **Feasibility**: CONSTRUCTIBLE. The boundary-step alias-extraction
  contract is missing from Track 2's plan. The design.md class
  diagram on lines 118-127 lists `plan` and `outputType` — no alias
  carrier. **Plan must add a `String aliasToExtract` (or a
  `Function<Result, ?>` extractor) field to `YTDBMatchPlanStep`.**

#### Violation scenario: "Strategy is a no-op when prefix is empty"

- **Invariant claim**: "For any traversal whose recognized prefix is
  empty (the start step is not a translatable `g.V()`/`g.E()`), the
  strategy is a no-op — the traversal is unmodified."
- **Violation construction**:
  1. Start state: a traversal `g.E().toList()` exists. Track 2's
     plan says "g.V() / g.E()" but the step file scopes to
     `g.V() / g.V(ids)` only.
  2. Action sequence: `GremlinToMatchStrategy.apply` runs.
     Per the plan's step (2): "Returns immediately if the start step
     is not a `GraphStep`/`YTDBGraphStep`". `g.E()` produces a
     `YTDBGraphStep` whose `returnClass` is `Edge`. The plan does
     **not** say the start step must also be a vertex step.
  3. Intermediate state: the strategy attempts to translate `g.E()`
     as `MATCH {class: E, as: a}`. `aliasClasses = {a: "E"}`.
  4. Violation point: `MatchExecutionPlanner.estimateRootEntries`
     (`MatchExecutionPlanner.java:4796-4807`) calls
     `schema.existsClass(className)`. `E` exists in the YTDB schema.
     But the result row contains an `Edge`, not a `Vertex`, and the
     plan's "boundary output type (initially `Vertex`)" hard-codes
     vertex. The boundary step casts to Vertex and throws.
  5. Observable consequence: every `g.E()` Cucumber scenario fails.
     Test count decreases.
- **Feasibility**: CONSTRUCTIBLE. The plan/track-2.md's terse "g.V() /
  g.E()" mention in the higher-level summary collides with the
  step-file's narrower "minimal `g.V()` / `g.V(ids)`" scope, and the
  hardcoded `outputType = Vertex` confirms only V is supported.
  **Track 2 must explicitly decline `g.E()` (and any `YTDBGraphStep`
  whose `returnClass != Vertex.class`) until edges are scoped — or
  parameterise the boundary's outputType at translation time.**

#### Violation scenario: "Re-applying strategy is a no-op"

- **Invariant claim**: "Re-applying the strategy to a traversal that
  already contains `YTDBMatchPlanStep` is a no-op (verified by a unit
  test that calls `apply()` twice and asserts step list equality)."
- **Violation construction**:
  1. Start state: a translated traversal
     `[YTDBMatchPlanStep, …native steps…]`.
  2. Action sequence: a TinkerPop wrapper (e.g. an external traversal
     source decorating this traversal as a sub-traversal of
     `repeat`) clones it and applies strategies again.
  3. Intermediate state: the strategy chain re-runs.
     `GremlinToMatchStrategy.apply` is called. Step (1) of its logic
     scans for `YTDBMatchPlanStep`. Because the boundary step is at
     position 0, even the start-step check would find it.
  4. Violation point: the plan says "scan the entire step list, not
     just the start step". If the scan is only over `getSteps()` and
     **does not recurse into global children** of nested steps
     (e.g. a `RepeatStep` whose `getRepeatTraversal()` contains the
     translated boundary step), the outer strategy chain re-applies
     to the *outer* traversal whose start step is the
     `RepeatStep`-or-similar — sees no `YTDBMatchPlanStep` at any top-
     level position — and translates the outer traversal afresh.
     But that outer traversal does not start with `YTDBGraphStep`, so
     in Track 2's narrow scope the strategy bails at step (2) anyway.
  5. Observable consequence: in Track 2 specifically, the violation
     does **not** materialise because the start-step check rejects
     non-`YTDBGraphStep` traversals. The recursion concern resurfaces
     in later Tracks (3+) where edge/optional steps may host
     translated sub-traversals.
- **Feasibility**: INFEASIBLE in Track 2 scope, CONSTRUCTIBLE later.
  Track 2 should record this as a "deferred concern when sub-traversal
  translation lands" rather than a current invariant.

### Assumption Challenges

#### Assumption test: "polymorphism flag is read once per `apply()`"

- **Claim**: `YTDBStrategyUtil.isPolymorphic(traversal)` returns the
  per-traversal effective polymorphism setting.
- **Stress scenario**: a test calls
  `g.with(YTDBQueryConfigParam.polymorphicQuery, false)` to override
  per-traversal, then issues `g.V().toList()`. Per
  `YTDBStrategyUtil.java:38-45`, `getConfigValue(polymorphicQuery,
  traversal)` returns the override; if absent it falls back to
  `GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT`.
- **Code evidence**:
  - `YTDBStrategyUtil.java:29-46` reads `OptionsStrategy` from the
    traversal's strategies. This works when the strategy chain still
    contains `OptionsStrategy`. After `applyStrategies()` runs,
    finalization may have stripped or transformed `OptionsStrategy`.
    Actually checking `YTDBStrategyUtil.getConfigValue` — it calls
    `traversal.getStrategies().getStrategy(OptionsStrategy.class)`
    which returns whatever is in the strategies registry, regardless
    of whether it has applied yet.
  - `YTDBGraphStepStrategy.java:32-36` shows the same call returns
    `null` if the graph cannot be obtained from the traversal —
    in which case the strategy bails.
- **Verdict**: HOLDS for Track 2's call sites. **FRAGILE** if the
  new strategy is invoked from a sub-traversal that does not have a
  graph reference (`traversal.getGraph().orElse(null) == null`),
  which happens for sub-traversals whose `Traversal.Admin.parent` is
  set but whose graph is not propagated. Later tracks must mirror
  the `YTDBGraphStepStrategy.apply` early-return for `polymorphic == null`.

#### Assumption test: "boundary step is `AbstractStep<Object, E>`"

- **Claim**: extending `AbstractStep<Object, E>` is sufficient. The
  plan says "extending `AbstractStep<Object, E>`. Holds a
  `SelectExecutionPlan` and a configured boundary output type".
- **Stress scenario**: TinkerPop's `Step` requires
  `Iterator<Traverser.Admin<E>> processNextStart()` and
  `Set<Step.Requirements>` semantics. A boundary that emits
  arbitrary objects (Vertex, Edge, Map, scalar) is generic in `E`,
  but the strategy's `traversal.addStep(0, step)` must satisfy the
  generic signature of the next downstream step. Specifically, in
  `g.V().toList()`, the next step is `EmptyStep` and the traversal
  end-type is `Vertex`. Replacing `YTDBGraphStep<Vertex, Vertex>` with
  `YTDBMatchPlanStep<Object, Vertex>` requires the start-step semantics
  flag — TinkerPop's `Step.startStep` typically applies to the very
  first step in a chain. `AbstractStep` does not implement
  `Bypassing`, `Generating`, or other markers; that is fine.
- **Code evidence**:
  `YTDBGraphStep.java:32` shows `extends GraphStep<S, E>` — the
  GraphStep base class implements traverser-spawning semantics
  particular to start steps. Replacing it with a plain `AbstractStep`
  loses that. In particular, `GraphStep.processNextStart` allocates
  one traverser per element via `getTraversal().getTraverserGenerator()`.
  The boundary step must replicate this contract; the plan does not
  say so explicitly.
- **Verdict**: FRAGILE. Track 2 must either (a) extend `GraphStep`,
  inheriting its start-step traverser allocation, or (b) explicitly
  implement the traverser-spawning logic and document its parity.
  Otherwise iterating over `[YTDBMatchPlanStep, EmptyStep]` may yield
  zero traversers (no upstream to seed `processNextStart`).

#### Assumption test: "the translator can carry alias names to the boundary"

- **Claim**: implicit — that `YTDBMatchPlanStep.processNextStart` can
  extract the vertex from a `Result` whose property name matches the
  pattern's alias.
- **Stress scenario**: Track 2's `g.V()` translation needs to invent
  an alias (no user-given `as("a")`). Per the design,
  `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX = "$YOUTRACKDB_DEFAULT_ALIAS_"`
  is `static final` with **package-private** visibility
  (`MatchExecutionPlanner.java:248`). The new translator package
  `internal/core/gremlin/translator/` is a different package, so it
  cannot reference the constant. CR4 (consistency review) addressed
  this by reserving a translator-private prefix
  (`$g2m_anon_N`) — see Track 3's description. Track 2 must either
  use the translator-private prefix (consistent with Track 3) or
  duplicate the constant.
- **Code evidence**:
  `MatchExecutionPlanner.java:248` — DEFAULT_ALIAS_PREFIX is
  package-private. `MatchPatternBuilder.addNode` requires a
  non-blank alias. Track 2 must invent one.
- **Verdict**: HOLDS provided Track 2 explicitly states the
  translator-private prefix. **The step-file does not mention this**
  — it should. (Track 3's step-file does, per the plan checklist
  excerpt.)

#### Assumption test: "iteration order is irrelevant for Cucumber scenarios"

- **Claim**: implicit — that translated `g.V()` produces the same
  vertex set as native `g.V()` regardless of order.
- **Stress scenario**: a Cucumber scenario asserts an exact ordered
  list. TinkerPop's reference `g.V().toList()` over the MODERN
  dataset returns vertices in insertion order (TinkerGraph's
  `LinkedHashMap`-backed vertex map). YouTrackDB's native
  `YTDBGraphStep` iterates classes via
  `YTDBGraphQueryBuilder.build()` which produces `SELECT FROM V`
  — cluster-ordered. The MATCH path produces records via
  `MatchFirstStep` which wraps a `SELECT FROM V` plan — also cluster-
  ordered. **Identical** assuming both paths use the same FROM
  clause.
- **Code evidence**:
  `YTDBGraphStep.java:116-117` calls
  `query.execute(session).stream()` — i.e. SELECT execution.
  `MatchExecutionPlanner.java:1869-1875` calls
  `select.createExecutionPlan(context, profilingEnabled)` — same
  SELECT path.
- **Verdict**: HOLDS for the V-class default case. FRAGILE if any
  Cucumber scenario uses `g.V().has(T.label, ...)` and the schema
  hierarchy walks in a different order (the strategy translates
  to `aliasClasses = {a: "Person"}` while native goes through
  `YTDBGraphQueryBuilder.buildClassList`'s class-hierarchy union
  with potentially different output ordering for the multi-class
  case). **Recommend the smoke test in Track 2 verify equality of
  *element sets*, not lists, until ordering parity is independently
  established.**

### Scope Challenges

#### Assumption test: "Track 2's scope is right-sized"

- **Claim**: ~6 steps covering strategy class, idempotency, step
  walker skeleton, boundary step, V/V(ids) recognition, registration
  + smoke tests + Cucumber green.
- **Stress scenario**: the listed 6-step scope **understates** the
  surface area:
  - The `MatchPlanInputs` record (17 fields) + the additive
    `MatchExecutionPlanner` ctor + the field-by-field defensive copy
    is its own well-defined commit (mirrors a Track 1-style
    foundational refactor).
  - The strategy-ordering fix that requires modifying
    `YTDBGraphMatchStepStrategy.applyPrior` (per Challenge D4) is
    a separate commit.
  - The `YTDBMatchPlanStep` boundary step (alias extraction +
    `GraphStep` start-step semantics) is itself a deep concern with
    its own tests.
  - The "smoke test for `g.V()` / `g.V(ids)`" is value-questionable
    given the D1 challenge (replacing optimal native scans with the
    MATCH pipeline).
- **Verdict**: **BREAKS** at the scope-and-value boundary. **Either
  shrink Track 2 to "wire the strategy with a no-op decline policy +
  introduce `MatchPlanInputs` ctor + smoke-test-strategy-engagement"
  (defer minimal V translation to Track 3), or expand to address
  the strategy-ordering invariant fix.** Either rebalances the work.

#### Assumption test: "expanding scope to `g.V().has(label)` would validate prefix detection on a non-trivial case"

- **Claim**: Track 2 only does `g.V()` / `g.V(ids)`. If we expanded to
  also catch `g.V().has(label)` (where label is folded by
  `YTDBGraphStepStrategy` into the graph step's `hasContainers`), we
  would exercise the prefix detector on a class-constrained scan.
- **Stress scenario**: `YTDBGraphStepStrategy` produces a
  `YTDBGraphStep` whose `hasContainers` includes `T.label = "Person"`.
  The new strategy reads these and builds
  `aliasClasses = {a: "Person"}`. This is the **first case where
  the MATCH planner has a real class to base estimateRootEntries on
  and the translation is value-positive** vs. the bare V path.
- **Code evidence**: `YTDBGraphStepStrategy.java:122-128` folds
  has-containers into the graph step. Reading them back is
  straightforward via `YTDBGraphStep.getHasContainers()`.
- **Verdict**: BREAKS the scope-minimality argument. **Recommend
  expanding Track 2's scope to also cover `g.V().has(T.label, …)`
  / `g.V().hasLabel(…)` (where the label is already folded into the
  graph step's hasContainers by `YTDBGraphStepStrategy`).** This is a
  cheap addition (the hasContainers are already available; the
  predicate-for-non-label rejected case is already a decline by
  Track 4), and it gives Track 2 a non-trivial validation case.

### Simplification Challenges

#### Assumption test: "boundary step needs a custom `AbstractStep`"

- **Claim**: introducing `YTDBMatchPlanStep extends AbstractStep<Object, E>`
  as a new custom step is necessary.
- **Stress scenario**: TinkerPop already provides `StartStep`,
  `LambdaFlatMapStep`, and `InjectStep`. None natively wrap an
  arbitrary `Iterator<Result>` and project per-row. But the existing
  `YTDBGraphStep` already wraps an iterator (lines 46-47:
  `setIteratorSupplier`). A boundary step could reuse the
  `setIteratorSupplier` idiom in `GraphStep` rather than re-implement
  the start-step semantics in `AbstractStep`.
- **Code evidence**:
  `YTDBGraphStep.java:46-47` —
  `this.setIteratorSupplier(() -> isVertexStep() ? (Iterator<E>) this.vertices() : (Iterator<E>) this.edges());`.
  This reuses `GraphStep`'s built-in traverser-spawning over the
  supplied iterator.
- **Verdict**: WEAK as currently designed. **Recommend the boundary
  step extend `GraphStep<Object, E>` (reusing TinkerPop's start-step
  traverser-spawning) and supply an iterator that pulls from the
  `SelectExecutionPlan`'s `ExecutionStream` and projects rows.** This
  is fewer LOC, parallel to the existing `YTDBGraphStep`, and
  inherits start-step semantics for free.

---

## Part 2 — Findings

### Finding A1 [blocker]
**Certificate**: Violation scenario "Cucumber suite test count after ≥ before" + Assumption test "the translator can carry alias names to the boundary"
**Target**: Invariant "Cucumber suite test count after ≥ before" + Track 2 step file
**Challenge**: Track 2's design for `YTDBMatchPlanStep` (per design.md
class diagram lines 118-127 and step file lines 41-46) does not
specify how the boundary step extracts the matched vertex from a
`Result` whose property name is a translator-invented alias. The
field set (`plan`, `outputType`) is incomplete. Result: every
translated `g.V()` returns empty / wrong-type traversers, breaking
every Cucumber scenario that goes through the new strategy.
**Evidence**: `MatchExecutionPlanner.java:597-624` — empty-projection
path produces rows with `result.getProperty("<alias>")` carrying the
vertex; the boundary step must know the alias. `design.md:118-127`
omits any alias/extractor field on `YTDBMatchPlanStep`.
**Proposed fix**: Add a `String aliasToExtract` (or, more flexibly,
`Function<Result, ?> rowProjector`) field to `YTDBMatchPlanStep`;
populate it during translation. Document the contract in the step
file. Track 2's smoke test must assert non-empty equal-set output
against native `g.V().toList()`.

### Finding A2 [blocker]
**Certificate**: Challenge D4 — Strategy ordering
**Target**: Decision D4 + Invariant "MatchExecutionPlanner / existing
strategies signatures unchanged"
**Challenge**: D4 claims ordering can be enforced solely by
`applyPrior` on the new strategy. But `YTDBGraphMatchStepStrategy.applyPrior()`
returns `singleton(YTDBGraphStepStrategy.class)` only; it has no
constraint relative to the new strategy. With a partial order, the
TinkerPop resolver may schedule
`MatchStepStrategy → GremlinToMatchStrategy`, which lets the existing
label-folder mutate the match step before the translator sees it.
The plan asserts "no edits to existing strategies" but this cannot
co-exist with the desired ordering without also modifying
`YTDBGraphMatchStepStrategy.applyPrior` (or using `applyPost` on the
new strategy).
**Evidence**:
- `YTDBGraphMatchStepStrategy.java:147-149`
- `YTDBGraphCountStrategy.java:109-112`
- `YTDBGraphImplAbstract.java:73-78` (registration order is not
  binding via TinkerPop's `applyPrior`/`applyPost` resolver)
**Proposed fix**: Either (a) modify `YTDBGraphMatchStepStrategy` to
add `GremlinToMatchStrategy.class` to its `applyPrior`, AND modify
`YTDBGraphCountStrategy` likewise; the plan's invariant needs to
record this second edit; OR (b) on the new strategy, declare both
`applyPrior` (containing `YTDBGraphStepStrategy.class,
YTDBGraphCountStrategy.class`) and `applyPost` (containing
`YTDBGraphMatchStepStrategy.class`). Add a deterministic-ordering
test that verifies the resolved order through `traversal.getStrategies()`.

### Finding A3 [should-fix]
**Certificate**: Challenge D1 — Integration via `ProviderOptimizationStrategy` + Scope Challenge
**Target**: Decision D1 + scope of Track 2
**Challenge**: Translating bare `g.V()` and `g.V(ids)` is value-
negative in Phase 1 (no plan cache, native path is already a
single optimised SELECT). The smoke test would degrade rather than
demonstrate. Expanding scope minimally to cover
`g.V().has(T.label, …)` / `g.V().hasLabel(…)` (where the label is
already folded into the graph step's hasContainers by
`YTDBGraphStepStrategy`) would exercise the prefix detector on a
class-constrained scan and avoid the regression of the bare V case.
**Evidence**:
- `YTDBGraphStep.java:90-133` — bare V path is a single SELECT.
- `MatchExecutionPlanner.java:1859-1877` — single-node MATCH path
  wraps the same SELECT in a `MatchFirstStep`, paying planning
  overhead with no upside.
- `YTDBGraphStepStrategy.java:122-128` — has-containers are folded
  into the graph step before the new strategy sees the traversal.
**Proposed fix**: Either (a) re-scope Track 2 to "strategy-engagement
plumbing only — explicitly decline `g.V()` / `g.V(ids)` in this
track; defer minimal translation to Track 3 alongside `g.V().out()`",
OR (b) widen Track 2 to also recognise the
`YTDBGraphStep.hasContainers[T.label]` case so the smoke test runs
on a non-trivial pattern. State the choice in the step file with
explicit rationale.

### Finding A4 [should-fix]
**Certificate**: Assumption test "boundary step is `AbstractStep<Object, E>`" + Simplification Challenge
**Target**: Track 2 design for `YTDBMatchPlanStep`
**Challenge**: Extending `AbstractStep<Object, E>` rather than
`GraphStep<Object, E>` discards TinkerPop's start-step traverser-
spawning semantics. The existing `YTDBGraphStep` reuses
`GraphStep`'s `setIteratorSupplier` idiom (line 46-47) precisely to
get traverser allocation for free. The new boundary step is the
direct analogue and can do the same.
**Evidence**:
- `YTDBGraphStep.java:32-48` — `extends GraphStep<S, E>` +
  `setIteratorSupplier`.
- design.md:118-127 — `YTDBMatchPlanStep` extends `AbstractStep`
  (per the class diagram and step file).
**Proposed fix**: Make `YTDBMatchPlanStep extends GraphStep<Object, E>`,
supply an iterator that lazily drives the `SelectExecutionPlan`'s
`ExecutionStream`, project rows to the boundary output type inside
the iterator. This inherits start-step semantics without re-
implementation and matches the existing pattern.

### Finding A5 [should-fix]
**Certificate**: Violation scenario "Strategy is a no-op when prefix is empty"
**Target**: Track 2 step file (acceptance criteria for the start-step check)
**Challenge**: The step file says the strategy returns immediately
"if the start step is not a `GraphStep`/`YTDBGraphStep`". For Track 2
with `outputType = Vertex` hardcoded, this admits `g.E()` traversals
where `YTDBGraphStep.returnClass == Edge`, leading to a runtime
class-cast in the boundary step.
**Evidence**:
- `YTDBGraphStep.java:50-52` — `isVertexStep()` distinguishes V from E.
- step file line 43 — "boundary output type (initially `Vertex`)".
**Proposed fix**: Add the explicit guard "decline if
`!startStep.isVertexStep()`" (i.e. only V is in scope this track) to
the step file's apply-method specification, and add a unit test that
`g.E().toList()` is unchanged after the strategy runs.

### Finding A6 [should-fix]
**Certificate**: Violation scenario "MatchExecutionPlanner existing public method signatures unchanged"
**Target**: Invariant "MatchExecutionPlanner existing public method signatures unchanged"
**Challenge**: The invariant is about signatures, but the spirit is
"existing behavior is unchanged for existing callers". The new
`MatchPlanInputs` ctor must defensive-copy not just `aliasFilters`
(which the 3-arg ctor does today) but also `aliasClasses` (mutated
at lines 4520, 4665, 4678) — otherwise translator state is shared
with the planner across calls and union-of-patterns reuse (Track 10)
will see corrupted state. Additionally, the persistent
`statement == null` NPE risk in `createExecutionPlan` at lines 478
and 629 is inherited verbatim by the new ctor.
**Evidence**:
- `MatchExecutionPlanner.java:411-414` — current 3-arg ctor
  defensive-copies `aliasFilters` only.
- `MatchExecutionPlanner.java:4520, 4665, 4678` — `aliasClasses.put(...)`.
- `MatchExecutionPlanner.java:478, 629` — `statement.executinPlanCanBeCached`,
  `statement.getOriginalStatement()`.
**Proposed fix**: (a) Specify in the step file that the new ctor
defensive-copies all three alias maps. (b) Strengthen the invariant
wording to "no input passed to the new ctor is mutated by the
planner". (c) Add an assertion or guard in `createExecutionPlan`
that `useCache` cannot be true when `statement == null`, making
the contract enforced.

### Finding A7 [suggestion]
**Certificate**: Challenge D7 — Idempotency by step-list scan
**Target**: Decision D7
**Challenge**: A full-list scan to detect `YTDBMatchPlanStep` is
defensive against a scenario that does not exist in the codebase.
The simpler check (`getStartStep() instanceof YTDBMatchPlanStep`) is
sufficient because the strategy itself is the only constructor of
`YTDBMatchPlanStep`, and Track 2 places it at position 0. The full-
list scan also raises a question about recursing into nested global
children of sub-traversal-bearing steps, which design.md does not
clarify.
**Evidence**: Cross-strategy convention — `YTDBGraphStepStrategy.java:97-99`
walks from `getStartStep()` and does not recurse. No code path moves
`YTDBMatchPlanStep` to a non-zero index.
**Proposed fix**: Replace the full-list scan with a start-step
instanceof check and document the construction discipline that
`YTDBMatchPlanStep` only appears at position 0 in Track 2's scope.

### Finding A8 [suggestion]
**Certificate**: Assumption test "iteration order is irrelevant for Cucumber scenarios"
**Target**: Track 2 smoke-test design
**Challenge**: The smoke test's "produces the same vertices as the
un-translated path" wording could be interpreted as ordered list
equality. Cluster ordering parity is *plausible* (both paths
execute `SELECT FROM V`) but not formally established for
multi-class hierarchy unions or for the `polymorphic=false` branch
of `YTDBGraphStep.elements`.
**Evidence**:
- `YTDBGraphStep.java:119-124` — `polymorphic=false` adds an
  in-stream `filter` that does not reorder; ordering parity holds.
- `YTDBGraphQueryBuilder.java:175-186` — multi-class case wraps
  per-class queries in `UNIONALL`; whether MATCH's `MatchFirstStep`
  uses the same union path needs verification.
**Proposed fix**: In Track 2's smoke test, assert *set* equality
(unordered) for `g.V().toList()` parity. Defer ordered-equality to
a later track that explicitly establishes it.

### Finding A9 [suggestion]
**Certificate**: Challenge D2 — Additive `(MatchPlanInputs)` ctor
**Target**: Decision D2 rationale
**Challenge**: D2 dismisses "synthesise a `SQLMatchStatement`" with
"semantic mismatch and the AST class is JJTree-generated, awkward
to construct manually". Neither claim is grounded with a concrete
demonstration. A constructive sketch would either rule the
alternative in or strengthen the rationale for the 17-field record.
**Evidence**: `MatchExecutionPlanner.java:424-454` — the
`(SQLMatchStatement)` ctor uses `.copy()` on AST nodes and
straightforward setter access. The AST nodes have public setters
(by JJTree convention).
**Proposed fix**: In D2's "Alternatives considered" section, add a
one-paragraph constructive sketch of the `SQLMatchStatement` route
showing why it is inferior (e.g. "would require AST node ctors not
exposed", "would carry vestigial fields like `originalStatement`
that need synthesised values"). If the constructive sketch reveals
the route works, reconsider.

### Finding A10 [suggestion]
**Certificate**: Assumption test "polymorphism flag is read once per `apply()`"
**Target**: Track 2 step file (apply-method behavior on `polymorphic == null`)
**Challenge**: The step file does not say what the strategy does
when `YTDBStrategyUtil.isPolymorphic(traversal) == null` (the "no
graph in traversal" case that `YTDBGraphStepStrategy.apply`
handles by early-returning). Without a parallel check, the new
strategy attempts to translate without knowing the polymorphism
mode.
**Evidence**:
- `YTDBStrategyUtil.java:29-46`
- `YTDBGraphStepStrategy.java:32-36`
**Proposed fix**: Mirror the parent's early-return: "if
`isPolymorphic(traversal) == null`, return immediately". State this
in the step file's apply-method spec.
