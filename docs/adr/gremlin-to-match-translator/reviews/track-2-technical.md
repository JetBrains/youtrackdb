# Track 2 — Technical Review (Iteration 1)

## Tooling note

mcp-steroid is **not reachable** in this session — `ToolSearch` returned no
matches for `select:mcp__mcp-steroid__steroid_list_projects`. All
reference-accuracy claims below are produced via grep / Read on the
worktree at `/home/sandra-adamiec/IdeaProjects/youtrackdb/.claude/worktrees/gremlin-to-match-translator`.
Findings whose reasoning depends on a symbol search are tagged with a
**reference-accuracy caveat** where appropriate.

---

## Part 1: Evidence Certificates

### Premises

#### Premise: `YTDBGraphImplAbstract.registerOptimizationStrategies` exists with the listed strategy ordering
- **Track claim**: "Registers the strategy in `YTDBGraphImplAbstract.registerOptimizationStrategies` between `YTDBGraphCountStrategy.instance()` and `YTDBGraphMatchStepStrategy.instance()` (D4)."
- **Search performed**: `grep -n` on `YTDBGraphImplAbstract.java`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphImplAbstract.java:68-79`.
- **Actual behavior**: Method exists. Registers in this order:
  1. `YTDBGraphStepStrategy.instance()`
  2. `YTDBGraphCountStrategy.instance()`
  3. `YTDBGraphMatchStepStrategy.instance()`
  4. `YTDBGraphIoStepStrategy.instance()`
  5. `YTDBQueryMetricsStrategy.instance()`
- **Verdict**: CONFIRMED.
- **Detail**: Insertion point matches the plan (between lines 75 and 76). Registered via `TraversalStrategies.GlobalCache.registerStrategies(...)`.

#### Premise: Four existing strategies at this site are all `ProviderOptimizationStrategy`
- **Track claim**: "alongside the four existing `ProviderOptimizationStrategy` instances on `YTDBGraphImplAbstract` (`YTDBGraphStepStrategy`, `YTDBGraphCountStrategy`, `YTDBGraphMatchStepStrategy`, `YTDBGraphIoStepStrategy`)" (plan, Component Map narrative).
- **Search performed**: `grep -n "extends \|implements "` on each of the four files.
- **Code location**:
  - `YTDBGraphStepStrategy.java:21-23` → `implements ProviderOptimizationStrategy`
  - `YTDBGraphCountStrategy.java:33-35` → `implements ProviderOptimizationStrategy`
  - `YTDBGraphMatchStepStrategy.java:59-61` → `implements ProviderOptimizationStrategy`
  - `YTDBGraphIoStepStrategy.java:13-15` → `implements TraversalStrategy.FinalizationStrategy`
  - `YTDBQueryMetricsStrategy.java:13-15` → `implements FinalizationStrategy`
- **Verdict**: WRONG.
- **Detail**: `YTDBGraphIoStepStrategy` is a **`FinalizationStrategy`**, not a `ProviderOptimizationStrategy`. There are only **three** `ProviderOptimizationStrategy` instances registered (Step, Count, MatchStep), plus **two** `FinalizationStrategy` instances (Io, QueryMetrics). The Component Map narrative and the plan's D4 cross-reference both rely on the "four ProviderOptimization" framing.

#### Premise: `MatchExecutionPlanner` has three existing constructors
- **Track claim**: "The three existing constructors stay untouched."
- **Search performed**: `grep -n "public MatchExecutionPlanner"` on the file.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:385-454`.
- **Actual behavior**: Three public ctors:
  1. `(Pattern, Map<String,String> aliasClasses)` — line 385.
  2. `(Pattern, Map<String,String> aliasClasses, Map<String, SQLWhereClause> aliasFilters)` — line 398.
  3. `(SQLMatchStatement)` — line 424.
- **Verdict**: CONFIRMED.
- **Detail**: The `(SQLMatchStatement)` ctor is the right pattern to mirror — it sets every "post-parse" field (`matchExpressions`, `notMatchExpressions`, `returnItems`, `returnAliases`, `returnNestedProjections`, `limit`, `skip`, `returnElements/Paths/Patterns/PathElements`, `returnDistinct`, `groupBy`, `orderBy`, `unwind`).

#### Premise: `groupBy`, `orderBy`, `unwind` are `final` instance fields
- **Track claim**: implicit — "field-by-field defensive-copies the inputs (mirroring the existing `(SQLMatchStatement)` ctor's pattern)".
- **Search performed**: Read of lines 282-287.
- **Code location**: `MatchExecutionPlanner.java:284-286`.
- **Actual behavior**:
  ```java
  private final SQLGroupBy groupBy;
  private final SQLOrderBy orderBy;
  private final SQLUnwind unwind;
  ```
- **Verdict**: CONFIRMED — but with implementation constraint.
- **Detail**: All three existing ctors set these `final` fields. The new `(MatchPlanInputs)` ctor MUST also set them in its body. Cannot rely on partial initialization or builder-style mutation.

#### Premise: `MatchExecutionPlanner.createExecutionPlan` returns `InternalExecutionPlan`, not `SelectExecutionPlan`
- **Track claim**: "Holds a `SelectExecutionPlan` ..." (Track 2 description) and "`SelectExecutionPlan` from `new MatchExecutionPlanner(inputs).createExecutionPlan(ctx, profiling, /*useCache=*/false)`".
- **Search performed**: Read of `createExecutionPlan` signature.
- **Code location**: `MatchExecutionPlanner.java:472-473`.
- **Actual behavior**:
  ```java
  public InternalExecutionPlan createExecutionPlan(
      CommandContext context, boolean enableProfiling, boolean useCache)
  ```
  Internally `result = new SelectExecutionPlan(context)` and then `return result;` — so the runtime type IS `SelectExecutionPlan`, but the declared return type is `InternalExecutionPlan`.
- **Verdict**: PARTIAL.
- **Detail**: `YTDBMatchPlanStep` should hold an `InternalExecutionPlan`, not a `SelectExecutionPlan`. Downcasting is unnecessary — the only API the boundary needs is `start()` (returns `ExecutionStream`), `close()`, `reset(ctx)`, and `getContext()`, all on `InternalExecutionPlan`. `SelectExecutionPlan`-specific calls would couple the boundary step to an implementation type.

#### Premise: `useCache=false` skips the YqlExecutionPlanCache entirely
- **Track claim**: "`MatchExecutionPlanner.createExecutionPlan(ctx, profiling, /*useCache=*/false)`."
- **Search performed**: Read of `createExecutionPlan` body, lines 472-636.
- **Code location**: `MatchExecutionPlanner.java:478-483, 627-633`.
- **Actual behavior**: Both cache lookup (line 478) and cache store (line 627) gate on `useCache`. With `useCache=false` neither path runs. Furthermore, the cache lookup gates additionally on `statement.executinPlanCanBeCached(session)` — and `statement` is set ONLY in the `(SQLMatchStatement)` ctor. With `useCache=false` we are safe; but if a future maintainer flips it to `true`, the planner will NPE on `statement.executinPlanCanBeCached(...)` because `statement == null` for the new ctor.
- **Verdict**: CONFIRMED with latent fragility.
- **Detail**: The new ctor does not initialize `statement`. The plan correctly says "useCache=false in Phase 1," but the new ctor has no defense if a downstream caller (Track 11/12 boundary refinement, perf-baseline measurement, etc.) accidentally passes `useCache=true`. A null check on `statement` inside `createExecutionPlan` (or initializing `statement` to a sentinel) would harden this.

#### Premise: `MatchExecutionPlanner.buildPatterns` short-circuits on `pattern != null`
- **Track claim**: implicit — "`MatchPlanInputs` ... feed that IR directly to `MatchExecutionPlanner`."
- **Search performed**: Read of `buildPatterns`.
- **Code location**: `MatchExecutionPlanner.java:4378-4416`.
- **Actual behavior**: `if (this.pattern != null) { return; }` at line 4379. **This means the new ctor must populate `aliasFilters`, `aliasClasses`, `aliasRids` itself**, because `buildPatterns` is the only path that derives them from `matchExpressions`.
- **Verdict**: CONFIRMED.
- **Detail**: Same applies to `splitDisjointPatterns` (line 4185-4191): `if (this.subPatterns != null) { return; }` — so subPatterns is built from `pattern.getDisjointPatterns()` at runtime. No issue. But the implication for `MatchPlanInputs` is critical: the record must carry full alias maps; the planner won't fill them.

#### Premise: `MatchExecutionPlanner.handleProjectionsBlock` is internally invoked at ~line 624
- **Track claim**: "the planner already calls handleProjectionsBlock internally (`MatchExecutionPlanner.java:624`)" (D2 rationale).
- **Search performed**: `grep -n "handleProjectionsBlock"`.
- **Code location**: `MatchExecutionPlanner.java:623`.
- **Actual behavior**: `SelectExecutionPlanner.handleProjectionsBlock(result, info, context, enableProfiling);` — single internal call when `returnElements/Paths/Patterns/PathElements` are all false (the "custom RETURN" branch). When ANY of those flags is true, the older inline branch (lines 559-596) runs instead — projection / order / limit / skip are appended directly via separate steps, NOT via `handleProjectionsBlock`.
- **Verdict**: CONFIRMED but PARTIAL.
- **Detail**: The translator path will populate `returnItems` (custom projections) so it falls into the line-624 path. Fine for Track 2 — it produces no return items so the `returnItems.isEmpty()` case must still work. Re-reading: the else branch at lines 597-624 doesn't gate on emptiness; it iterates `returnItems` (empty list → empty projection) and unconditionally calls `handleProjectionsBlock`. That should be safe — but the projection chain WILL be appended. Worth a regression check: a fully empty `MatchPlanInputs` (no projections, no order/limit) still produces a runnable plan that emits raw match rows. Phase 1 Track 2 should validate this with a unit test.

#### Premise: `YTDBStrategyUtil.isPolymorphic(traversal)` exists and may return `null`
- **Track claim**: "Translator reads `YTDBStrategyUtil.isPolymorphic(traversal)`" (plan Integration Points).
- **Search performed**: Read of `YTDBStrategyUtil.java`.
- **Code location**: `YTDBStrategyUtil.java:29-46`.
- **Actual behavior**: Returns `Boolean` (boxed). Returns `null` if `traversal.getGraph().orElse(null)` is null (sub-traversals or detached traversals). `YTDBGraphStepStrategy.apply` (line 32-36) and `YTDBGraphCountStrategy.apply` (line 49-53) BOTH guard on `polymorphic == null` and return early.
- **Verdict**: CONFIRMED.
- **Detail**: Track 2 description does NOT explicitly call out the null-graph guard. The strategy must replicate this guard or it will NPE on `traversal.getGraph().orElseThrow()` calls deeper in the translator.

#### Premise: `YTDBGraphStep` exposes `getIds()`, `getHasContainers()`, `isVertexStep()`
- **Track claim**: implicit — "translate traversals starting with `g.V()` / `g.E()` ... with optional ID list".
- **Search performed**: Read of `YTDBGraphStep.java`.
- **Code location**: `YTDBGraphStep.java:32-184`.
- **Actual behavior**: `getIds()` is inherited from `GraphStep` → returns `Object[]`. `getHasContainers()` is overridden returning `unmodifiableList`. `isVertexStep()` returns `Vertex.class.isAssignableFrom(returnClass)`. `polymorphic` field has setter `setPolymorphic` but **no public getter**.
- **Verdict**: CONFIRMED with caveat.
- **Detail**: To know whether the step is polymorphic, the translator must read it via `YTDBStrategyUtil.isPolymorphic(traversal)`, NOT from `YTDBGraphStep`. The plan already says this, so it's compatible. But: if the translator ever needs per-step polymorphism (e.g. when `YTDBGraphStepStrategy` resolves traversal-level config differently than the strategy chain), there's no API. Phase-1 scope OK.

#### Premise: `aliasRids` maps each alias to a single `SQLRid`
- **Track claim**: Track 2 description includes `aliasRids` as a `MatchPlanInputs` field; tests will verify "`g.V(ids).toList()` returns the same vertices as RID-driven SQL `MATCH`".
- **Search performed**: Read of `MatchExecutionPlanner.java` field decl (line 307) and `estimateRootEntries` (lines 4775-4824).
- **Code location**: `MatchExecutionPlanner.java:307`.
- **Actual behavior**: `private Map<String, SQLRid> aliasRids;` — value is a single `SQLRid`, not a list. `estimateRootEntries` (line 4790-4794) returns `1L` for any alias with a non-null entry.
- **Verdict**: CONFIRMED with feasibility gap.
- **Detail**: `g.V(id1)` (single ID) maps cleanly to `aliasRids[alias] = SQLRid(id1)`. `g.V(id1, id2, id3)` (multi-ID) DOES NOT MAP — there is no list-of-RIDs slot. The translator must instead produce a `WHERE @rid IN [#x:y, ...]` clause via `MatchWhereBuilder.in("@rid", list)` and put it in `aliasFilters`. Track 2's claim "verifies `g.V(ids).toList()` returns the same vertices" is silent on the multi-ID encoding. Recommend Track 2 explicitly limit recognition to "no IDs OR exactly one ID" for the minimal scope, OR add a step that builds an `@rid IN [...]` filter for the multi-ID case.

#### Premise: `MatchPatternBuilder.PatternIR` does NOT include `aliasRids`
- **Track claim**: "construct a `MatchPlanInputs` (Pattern + alias maps + return/order/limit metadata) via the shared builders".
- **Search performed**: Read of `MatchPatternBuilder.java`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java:38-42`.
- **Actual behavior**:
  ```java
  public record PatternIR(
      Pattern pattern,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters) {
  }
  ```
  Three fields. No `aliasRids`. No setter for RIDs in the builder API.
- **Verdict**: PARTIAL.
- **Detail**: Track 2 must EITHER extend `MatchPatternBuilder` with an `addRid(alias, SQLRid)` method (and add `aliasRids` to `PatternIR`), OR populate `aliasRids` on the translator side (outside the shared builder). The plan description doesn't pick one. Track 1's track-1.md refactor is closed — extending the builder is now a Track 2 task. Recommend Track 2 step list explicitly include "extend MatchPatternBuilder with addRid + extend PatternIR with aliasRids".

#### Premise: `applyPrior()` returns a `Set<Class<? extends ProviderOptimizationStrategy>>` controlling pre-ordering
- **Track claim**: "Configures `applyPrior()` to enforce the ordering programmatically."
- **Search performed**: Read of existing strategies' `applyPrior()` returns.
- **Code location**: `YTDBGraphCountStrategy.java:109-112`, `YTDBGraphMatchStepStrategy.java:146-149`.
- **Actual behavior**: Both existing strategies declare `applyPrior() = Collections.singleton(YTDBGraphStepStrategy.class)`. They do NOT declare a relative order between Count and MatchStep. The TinkerPop `TraversalStrategies` infrastructure topologically sorts using `applyPrior()` and `applyPost()` declared on each strategy.
- **Verdict**: CONFIRMED with critical gap.
- **Detail**: To force "GremlinToMatch runs AFTER Count" — declare `applyPrior() ⊇ {YTDBGraphCountStrategy.class}` on the new strategy. To force "GremlinToMatch runs BEFORE MatchStepStrategy" — there are TWO ways: (a) declare `applyPost() = {YTDBGraphMatchStepStrategy.class}` on the new strategy; (b) add `GremlinToMatchStrategy.class` to `YTDBGraphMatchStepStrategy.applyPrior()` set. The plan claims this can be done "via `applyPrior()` alone on the new strategy" which is **insufficient** — `applyPrior` only constrains who runs BEFORE us, not who we precede. Track 2 must also use `applyPost()` OR modify `YTDBGraphMatchStepStrategy.applyPrior()` (a one-line change in an existing file, not zero changes).

#### Premise: `MatchExecutionPlanner` `Vertex` class lookup uses `SchemaClass.VERTEX_CLASS_NAME = "V"`
- **Track claim**: implicit — `g.V()` translates to MATCH on the vertex root class.
- **Search performed**: `grep -n "VERTEX_CLASS_NAME"`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/record/record/Vertex.java:38` and many usages including `YTDBGraphCountStrategy.java:71`.
- **Actual behavior**: `SchemaClass.VERTEX_CLASS_NAME = "V"`. `YTDBGraphCountStrategy` uses this constant when no class filter is provided (line 70-72: "g.V().count() → polymorphic V scan").
- **Verdict**: CONFIRMED.
- **Detail**: Track 2's `g.V()` translation must either (a) set `aliasClasses[alias] = "V"` and `polymorphic=true`, or (b) decline if no class filter — letting native execution handle bare `g.V()`. The plan description is ambiguous ("If the prefix is non-trivial (≥1 step beyond the start scan-with-ids case)"). For a bare `g.V().toList()`, what does "non-trivial" mean? If it includes the bare-scan case, `aliasClasses[alias] = "V"` works. If not, the integration test "verifies `g.V().toList()` produces the same vertices" measures only fallback, not strategy engagement — which contradicts the "smoke test" intent.

#### Premise: `createSelectStatement` requires either `targetClass` or `targetRid` to be non-null
- **Track claim**: implicit — single-node patterns are translatable.
- **Search performed**: Read of `createSelectStatement` lines 4348-4362.
- **Code location**: `MatchExecutionPlanner.java:4348-4362`.
- **Actual behavior**: The `SQLFromItem` is built with EITHER `setRids(...)` or `setIdentifier(...)`. If both are null, `SQLFromItem` is left empty — `prefetchStm` becomes `SELECT FROM (empty)` which will fail at execution.
- **Verdict**: CONFIRMED.
- **Detail**: Track 2 MUST default `aliasClasses[alias]` to `"V"` (vertex root) when `g.V()` has no label filter, otherwise the planner produces an unrunnable scan. This is consistent with `YTDBGraphCountStrategy`'s default. The plan needs to state this explicitly in the step decomposition.

#### Premise: `AbstractStep.processNextStart` signature requires returning a `Traverser.Admin`
- **Track claim**: "On `processNextStart`, drives the plan's `ExecutionStream`, pulls one `Result`, projects it to the configured output type, and wraps it in a `Traverser`."
- **Search performed**: Read of `YTDBClassCountStep.java`.
- **Code location**: `YTDBClassCountStep.java:34-49`.
- **Actual behavior**: Method signature: `protected Traverser.Admin<Long> processNextStart() throws NoSuchElementException`. `FastNoSuchElementException.instance()` is the canonical "no more rows" signal. Traverser is generated via `traversal.getTraverserGenerator().generate(payload, step, bulk)`.
- **Verdict**: CONFIRMED.
- **Detail**: The boundary step must throw `FastNoSuchElementException.instance()` (not the slower `NoSuchElementException`) when the underlying `ExecutionStream.hasNext()` returns false. The plan description doesn't call this out — easy to overlook.

#### Premise: `clone()` semantics for stateful steps
- **Track claim**: not addressed in plan.
- **Search performed**: Read of `YTDBClassCountStep.clone()`.
- **Code location**: `YTDBClassCountStep.java:78-86`.
- **Actual behavior**: `clone()` resets `done = false` so a cloned step starts fresh.
- **Verdict**: GAP IN PLAN.
- **Detail**: `YTDBMatchPlanStep` will hold an `InternalExecutionPlan` and possibly an `ExecutionStream` mid-iteration. `clone()` is invoked by TinkerPop in several scenarios (subgraph traversal cloning, remote-execution prep, test harnesses) and the cloned step should NOT share an in-flight `ExecutionStream` with the original. A safe `clone()` is non-trivial: either deep-copy the plan via `InternalExecutionPlan.copy(ctx)` or invalidate the stream and rely on lazy `start()` on first `processNextStart()`. The plan currently says "Holds a `SelectExecutionPlan` and a configured boundary output type" — no clone strategy. Recommend adding lazy-start semantics: hold the plan but don't open the stream until the first `processNextStart()`; `clone()` resets the stream to null and the plan to a fresh copy.

#### Premise: `BasicCommandContext` is the construction pattern for non-SQL planners
- **Track claim**: implicit — the strategy creates a CommandContext to feed the planner.
- **Search performed**: Read of `GqlMatchStatement.buildPlan` (line 98).
- **Code location**: `GqlMatchStatement.java:98`.
- **Actual behavior**: `var commandContext = new BasicCommandContext(ctx.session());` then `planner.createExecutionPlan(commandContext, false, false);`. The session is bound at planning time.
- **Verdict**: CONFIRMED but with binding caveat.
- **Detail**: For a Gremlin traversal, the database session must be obtained from `traversal.getGraph().orElseThrow().tx().getDatabaseSession()` (cf. `YTDBClassCountStep.getDatabaseSession`). It is bound when the strategy applies (i.e., when `apply()` runs), but iteration may happen on a different thread or after the session has been refreshed. `GqlExecutionPlan.start(session)` rebinds via `sqlPlan.getContext().setDatabaseSession(session)`. `YTDBMatchPlanStep` should follow the same pattern: call `setDatabaseSession(...)` on the plan's context at the start of `processNextStart()` before opening the stream. Track 2 description does not mention session rebinding.

### Edge cases

#### Edge case: `g.V()` on a graph that's not a `YTDBGraphImplAbstract`
- **Trigger**: User constructs a TinkerPop traversal against a non-YTDB graph (e.g. a TinkerGraph instance for testing) but the traversal somehow reaches the strategy.
- **Code path trace**:
  1. Strategy registration via `TraversalStrategies.GlobalCache.registerStrategies(YTDBGraphImpl.class, …)` — keyed on the YTDB graph class. TinkerPop only applies these strategies to traversals whose `Graph` implementation matches.
  2. So in normal use, the strategy is never invoked on non-YTDB graphs.
- **Outcome**: The strategy is never called. Safe.
- **Track coverage**: Implicitly safe; no explicit coverage needed.

#### Edge case: `traversal.getGraph().isPresent() == false` (sub-traversal / detached)
- **Trigger**: A nested sub-traversal (`__.has(...)`) that's a child of `where`/`not`/`optional` is handed to `apply()` — its parent is not `EmptyStep`.
- **Code path trace**:
  1. `apply(traversal)` invoked.
  2. `traversal.getParent()` returns the wrapping step (not `EmptyStep`).
  3. Strategy proceeds; calls `YTDBStrategyUtil.isPolymorphic(traversal)` → `traversal.getGraph().orElse(null)` → may be null for sub-traversals.
  4. If null, the helper returns null. Plan must short-circuit.
- **Outcome**: Per the plan, the strategy must short-circuit. Per the description, "Returns immediately if the start step is not a `GraphStep`/`YTDBGraphStep`" — sub-traversals typically start with `StartStep` or similar, NOT `GraphStep`, so the start-step check IS sufficient for most sub-traversals. But the description does not include the parent-traversal guard that `YTDBGraphCountStrategy` uses (`getParent() instanceof EmptyStep` — line 44).
- **Track coverage**: NO — the plan should explicitly add either (a) the start-step `instanceof GraphStep` check (already mentioned), or (b) the parent-traversal guard from Count, OR both. Even if (a) is sufficient for current TinkerPop versions, (b) is more conservative and matches existing convention.

#### Edge case: `g.V()` with no IDs, no label, no filter
- **Trigger**: `g.V().toList()`.
- **Code path trace**:
  1. `YTDBGraphStepStrategy` runs first → `g.V()` → `YTDBGraphStep(returnClass=Vertex.class, ids=[], hasContainers=[])`, `polymorphic` set.
  2. `YTDBGraphCountStrategy` runs (no count step, no-op).
  3. `GremlinToMatchStrategy.apply` runs.
  4. Idempotency check passes (no `YTDBMatchPlanStep`).
  5. Start step is `YTDBGraphStep` ✓.
  6. Walker scans for prefix. Only one step. "Trivial" or "non-trivial"?
- **Outcome (depending on plan interpretation)**:
  - If translator processes bare `g.V()` → produces single-node MATCH with `aliasClasses[alias] = "V"` (vertex root). Plan executes scan over V class. OK.
  - If translator declines bare `g.V()` (per "non-trivial" wording) → no-op, native scan runs. Smoke test passes but doesn't actually verify strategy engagement.
- **Track coverage**: AMBIGUOUS — the plan must state explicitly which interpretation applies. Recommend translating bare `g.V()` (set class to "V") so the smoke test is meaningful and the boundary step actually exercises.

#### Edge case: `g.V(id1, id2, id3)` (multi-ID)
- **Trigger**: User passes multiple RIDs.
- **Code path trace**:
  1. `YTDBGraphStep.ids = [id1, id2, id3]`.
  2. Strategy reads `step.getIds()` → 3 RIDs.
  3. Translator must produce a single-alias pattern. `aliasRids` only holds ONE RID per alias.
  4. To handle 3 RIDs, build a `WHERE @rid IN [#a:b, #c:d, #e:f]` clause and put it in `aliasFilters`.
  5. Alternative: just `aliasClasses[alias] = "V"` and a `WHERE` filter.
- **Outcome**: Without the `@rid IN [...]` translation, multi-ID `g.V(ids)` would silently degrade to single-ID (taking only `ids[0]`) or hard-fail. The plan claims test parity with "RID-driven SQL `MATCH`" but doesn't say which encoding.
- **Track coverage**: NO — neither the description nor the IR builder API has explicit support for "RID list".

#### Edge case: `g.V(unknownId)` — RID that doesn't exist in the database
- **Trigger**: User passes a stale RID.
- **Code path trace**:
  1. Translator builds `aliasRids[alias] = SQLRid(unknownId)`.
  2. `estimateRootEntries` returns `1L` for the alias (line 4792).
  3. Plan executes; `MatchFirstStep` scans by RID; record-not-found exception or empty stream.
- **Outcome**: TinkerPop's native `g.V(unknownId)` typically raises `NoSuchElementException` or returns an empty iterator depending on the Gremlin version. MATCH's behavior may differ. The plan does not call out this parity check.
- **Track coverage**: PARTIALLY — "Verifies `g.V(ids).toList()` returns the same vertices" implies a parity comparison, but only on valid IDs. Recommend explicitly testing the not-found case.

#### Edge case: Re-application of strategy after first apply
- **Trigger**: TinkerPop applies strategies twice (clone, test re-apply).
- **Code path trace**:
  1. First apply: `[YTDBGraphStep, …]` → `[YTDBMatchPlanStep, …]`.
  2. Second apply: `[YTDBMatchPlanStep, …]`.
  3. Idempotency check scans step list for `YTDBMatchPlanStep` → found → return.
- **Outcome**: Per the plan, no-op. ✓.
- **Track coverage**: YES — explicitly addressed in D7. But: the plan claim "scan the entire step list" is correct only if `YTDBMatchPlanStep` is comparable by class (no subclassing concerns). `MultiPlanMatchStep` (Track 10) extends `YTDBMatchPlanStep` (per design.md), so an `instanceof YTDBMatchPlanStep` check covers both. ✓.

#### Edge case: `g.V().toList()` on an empty graph
- **Trigger**: Database has no vertex records.
- **Code path trace**:
  1. Translator → `MATCH {class: V, as: a} RETURN a`.
  2. `estimateRootEntries(V)` → 0 (approximateCount).
  3. Lines 519-524: "if any non-optional alias has zero estimated records, the query is guaranteed to produce no results" → chains an `EmptyStep` and returns immediately.
  4. Boundary step receives an empty plan; `processNextStart()` → stream is empty → `FastNoSuchElementException`.
- **Outcome**: Same result as native (empty list). ✓ (assuming the boundary step correctly bridges empty streams).
- **Track coverage**: Implicit; should be covered by the smoke test on an empty test fixture.

#### Edge case: Non-existent class reference in alias
- **Trigger**: `g.V().hasLabel("DoesNotExist").toList()` (only relevant once Track 4 lands hasLabel folding, but the plan says hasContainers are absorbed BEFORE strategy runs because Track 2 runs after Step strategy).
- **Code path trace**: Track 2's recognized set is "just YTDBGraphStep start (with optional ID list)". `hasLabel` is in `hasContainers` after Step strategy folds it. Track 2 either ignores `hasContainers` (decline because "non-trivial → ≥1 step beyond start" might exclude this) or reads them and produces `aliasClasses[alias] = "DoesNotExist"`. If the latter, `estimateRootEntries` line 4802-4805 throws `CommandExecutionException("class not defined")`.
- **Outcome**: Native `g.V().hasLabel("DoesNotExist")` returns empty list. MATCH would throw. **Behavior divergence**.
- **Track coverage**: Track 2's description does not mention reading `hasContainers` from `YTDBGraphStep`. If Track 2's prefix is **just** the start step (no class folding), the absorbed `hasLabel` MUST be passed through to the post-prefix native steps somehow — but Step strategy already absorbed it INTO the YTDBGraphStep. Replacing that step with `YTDBMatchPlanStep` LOSES the hasContainers unless we read them. **This is a real concern** — the plan should clarify whether Track 2 reads `getHasContainers()` from `YTDBGraphStep` and if not, what happens to absorbed has-containers. Recommend Track 2 either (a) decline if `hasContainers` is non-empty (defer to Track 4), or (b) read them and include label folding even in Track 2.

### Integration Points

#### Integration: Strategy registration in `YTDBGraphImplAbstract`
- **Plan claim**: "1-line change — `GremlinToMatchStrategy.instance()` added to the strategy list at the position dictated by D4." (between Count and MatchStep).
- **Actual entry point**: `YTDBGraphImplAbstract.java:68-79`.
- **Caller analysis**: `registerOptimizationStrategies(Class)` is invoked by graph subclass static initializers. (`grep -rn "registerOptimizationStrategies" core/src/main` not run in this review — but the method is `public static` and named for explicit invocation; standard registration pattern.)
- **Breaking change risk**: Inserting a new strategy in the middle of the list does NOT change observable behavior — `TraversalStrategies` topologically sorts via `applyPrior`/`applyPost`. The list-position is cosmetic. **However**, the plan claims `applyPrior()` alone enforces the "between Count and MatchStep" ordering, which is FALSE (see Premise on `applyPrior()`). The actual ordering enforcement requires either `applyPost()` on the new strategy OR a one-line modification of `YTDBGraphMatchStepStrategy.applyPrior()`. The "1-line change" is therefore really 2 changes (registration + match-step's prior set).
- **Verdict**: MISMATCHES.

#### Integration: `MatchExecutionPlanner` new ctor
- **Plan claim**: "Adds the corresponding additive constructor `MatchExecutionPlanner(MatchPlanInputs)` that field-by-field defensive-copies the inputs (mirroring the existing `(SQLMatchStatement)` ctor's pattern). The three existing constructors stay untouched."
- **Actual entry point**: `MatchExecutionPlanner.java:385-454` (existing ctors).
- **Caller analysis**: Existing ctors called from `GqlMatchStatement.buildPlan` (line 99) and `SQLMatchStatement.createPlan(...)` (parser-driven path). Plan does not modify these.
- **Breaking change risk**: Adding a new public ctor is purely additive — zero risk to existing callers. ✓.
- **Verdict**: MATCHES.
- **Detail**: But the new ctor must initialize `final groupBy/orderBy/unwind` — implementation must be careful here. AND it must NOT initialize `statement` (no SQL AST), so any code path that touches `this.statement` MUST guard on `statement != null`. Currently lines 478, 632 do `statement.executinPlanCanBeCached(...)` gated on `useCache=true`. Safe for Track 2 (useCache=false). Latent bug for future tracks.

#### Integration: `YTDBStrategyUtil.isPolymorphic` consumption
- **Plan claim**: "Translator reads `YTDBStrategyUtil.isPolymorphic(traversal)`."
- **Actual entry point**: `YTDBStrategyUtil.java:29`.
- **Caller analysis**: Existing callers — `YTDBGraphStepStrategy.apply` line 32, `YTDBGraphCountStrategy.apply` line 49. Both guard on null and bail. `grep -rn "isPolymorphic"` not run, but the helper is small and the call pattern is established.
- **Breaking change risk**: None — pure read.
- **Verdict**: MATCHES.

#### Integration: Boundary step `AbstractStep<Object, E>` extension
- **Plan claim**: "extending `AbstractStep<Object, E>`. Holds a `SelectExecutionPlan`..."
- **Actual entry point**: `AbstractStep` (TinkerPop). Existing model: `YTDBClassCountStep extends AbstractStep<S, Long>` (line 14).
- **Caller analysis**: Steps are wired via `TraversalHelper.replaceStep` / `traversal.addStep(idx, ...)`. Plan says "Replaces the prefix steps with one new `YTDBMatchPlanStep`" — implementation pattern mirrors `YTDBGraphStepStrategy.rebuildTraversal` (line 116-117).
- **Breaking change risk**: None — new step class.
- **Verdict**: MATCHES with caveats — `AbstractStep<Object, E>` (input type Object) is unconventional; typical TinkerPop pattern is `AbstractStep<S, E>` and starts steps use `S = Object`. Need to confirm the boundary step is treated as a START step (no upstream traverser) since it replaces the GraphStep. Likely needs `start = true` or to throw on any incoming traverser (no upstream).

---

## Part 2: Findings

### Finding T1 [should-fix]
**Certificate**: Premise "Four existing strategies at this site are all `ProviderOptimizationStrategy`" — verdict WRONG.
**Location**: Track 2 plan description (Component Map narrative paragraphs), implementation-plan.md lines 138-143 ("alongside the four existing `ProviderOptimizationStrategy` instances on `YTDBGraphImplAbstract`").
**Issue**: `YTDBGraphIoStepStrategy` is a `FinalizationStrategy`, not a `ProviderOptimizationStrategy`. There are only THREE PO strategies (Step, Count, MatchStep) plus TWO Finalization strategies (Io, QueryMetrics). The framing "fifth strategy registered on the same Graph class, `YTDBQueryMetricsStrategy`, is a `FinalizationStrategy` and runs after all `ProviderOpt`s" is therefore incomplete — IoStep is the same.
**Proposed fix**: Update the plan's Component Map narrative and Track 2 description to "alongside the three existing `ProviderOptimizationStrategy` instances (`YTDBGraphStepStrategy`, `YTDBGraphCountStrategy`, `YTDBGraphMatchStepStrategy`); two `FinalizationStrategy` instances (`YTDBGraphIoStepStrategy`, `YTDBQueryMetricsStrategy`) run after all `ProviderOpt`s and are unaffected by the new strategy's ordering." The mermaid diagram in the plan should also be corrected — `IOS` is shown as `ProviderOptimization` (it should be FinalizationStrategy).

### Finding T2 [blocker]
**Certificate**: Premise "`applyPrior()` returns a Set<Class<? extends ProviderOptimizationStrategy>> controlling pre-ordering" — gap.
**Location**: Track 2 description, "Configures `applyPrior()` to enforce the ordering programmatically." Plan D4 rationale.
**Issue**: `applyPrior()` declares strategies that must run BEFORE us. It cannot enforce that we run BEFORE another strategy. To position GremlinToMatchStrategy "between Count and MatchStep", the new strategy must EITHER declare `applyPost() = {YTDBGraphMatchStepStrategy.class}` OR `YTDBGraphMatchStepStrategy.applyPrior()` must be modified to include `GremlinToMatchStrategy.class`. The plan's "1-line change" claim conflicts with reality if option (b) is chosen, and the plan is silent on option (a).
**Proposed fix**: Track 2's step list must include an explicit step for "declare strategy ordering: `applyPrior() = {YTDBGraphStepStrategy.class, YTDBGraphCountStrategy.class}`, `applyPost() = {YTDBGraphMatchStepStrategy.class}`". OR explicitly say "modify `YTDBGraphMatchStepStrategy.applyPrior()` to add `GremlinToMatchStrategy.class`". The choice should be a Decision Record-level decision (D4 rephrase), not buried. Recommend option (a) (using `applyPost` on the new strategy) because it keeps changes localized to the new file — no edit to existing strategies. Add a unit test that asserts via `TraversalStrategies.GlobalCache.getStrategies(YTDBGraphImpl.class)` the actual application order matches the intended order.

### Finding T3 [blocker]
**Certificate**: Premise "`MatchPatternBuilder.PatternIR` does NOT include `aliasRids`" — verdict PARTIAL.
**Location**: Track 2 description, "construct a `MatchPlanInputs` (Pattern + alias maps + return/order/limit metadata) via the shared builders".
**Issue**: The shared `MatchPatternBuilder` does not produce `aliasRids` — `PatternIR` has only `pattern`, `aliasClasses`, `aliasFilters`. For `g.V(id)` translation, Track 2 must either extend the builder or populate `aliasRids` outside it. Track 1 closed the builder API; whichever path Track 2 takes is a non-trivial decision that shapes downstream tracks (Track 4 will route `hasId(...)` through this same path).
**Proposed fix**: Track 2's step decomposition must include "extend `MatchPatternBuilder` with `addRid(alias, SQLRid)` and add `aliasRids` field to `PatternIR`". Update the builder Javadoc with the new method's contract and unit-test it. Note for downstream tracks: the GQL refactor doesn't use RIDs today, so this extension is additive and behavior-preserving for GQL.

### Finding T4 [should-fix]
**Certificate**: Edge case "`g.V(id1, id2, id3)` (multi-ID)" — track coverage NO.
**Location**: Track 2 description, "Verifies `g.V(ids).toList()` returns the same vertices as RID-driven SQL `MATCH`".
**Issue**: `aliasRids` holds a single RID per alias. Multi-ID `g.V(ids)` does not have a clean encoding via `aliasRids` alone. Track 2 description is silent on the multi-ID case. Without explicit handling, the boundary step would either drop IDs after the first or emit incorrect results. Estimating cost is also affected: a single RID gives `1L` cardinality; an `@rid IN [...]` filter requires the planner to estimate via the `where` clause, which may not produce the same prefetch decisions.
**Proposed fix**: Pick one of:
- (a) Limit Track 2's recognition to "no IDs OR exactly one ID"; defer multi-ID to a later track (or to Track 4 alongside `hasId`). Add an explicit test for `g.V(id1, id2)` that verifies the strategy DECLINES (native fallback runs).
- (b) Translate multi-ID to a `WHERE @rid IN [...]` filter via `MatchWhereBuilder.in("@rid", ridList)`, and document that the cost estimate will use the `where`-clause estimator instead of `aliasRids` (cardinality = list size, capped by class scan).
Recommend (a) for Track 2's minimal scope; (b) lands cleanly in Track 4 with `hasId(...)` unification.

### Finding T5 [should-fix]
**Certificate**: Edge case "`g.V()` on a graph that's not a `YTDBGraphImplAbstract`" + "sub-traversal" — track coverage gap on parent guard.
**Location**: Track 2 description, "Returns immediately if the start step is not a `GraphStep`/`YTDBGraphStep`".
**Issue**: The start-step check is a sufficient first-pass filter for top-level traversals, but `YTDBGraphCountStrategy` also guards on `traversal.getParent() instanceof EmptyStep` (line 44) — only run on root traversals. Without this guard, the strategy fires on sub-traversals (e.g. inside `where(__.has(...))`) and may attempt translation. Even though the start step of a sub-traversal is typically `StartStep`, not `GraphStep`, the parent-guard is the canonical convention in this project.
**Proposed fix**: Track 2's idempotency-check step should ALSO add a parent-traversal guard (`if (!(traversal.getParent() instanceof EmptyStep)) return;`). Justification: defense in depth, alignment with existing conventions, and protection against future Gremlin DSL extensions where a `GraphStep` somehow appears nested.

### Finding T6 [should-fix]
**Certificate**: Edge case "Non-existent class reference in alias" + Premise "`createSelectStatement` requires either targetClass or targetRid to be non-null".
**Location**: Track 2 description, "the recognized set is just the `YTDBGraphStep` start (with optional ID list)".
**Issue**: After `YTDBGraphStepStrategy` runs, the start step is `YTDBGraphStep` with `hasContainers` possibly populated (label or property filters). Track 2's translator must EITHER (a) read these `hasContainers` and translate them, OR (b) decline if `hasContainers` is non-empty so they don't get silently dropped when the prefix is replaced by `YTDBMatchPlanStep`. The current description says "All other steps end the prefix", implying steps AFTER the start, but `hasContainers` are NOT separate steps — they live inside `YTDBGraphStep`. Replacing the step without reading them = data loss.
**Proposed fix**: Add explicit handling. Recommend option (b) for Track 2 minimal scope: if `step.getHasContainers()` is non-empty, the strategy declines (full no-op, native fallback). Track 4 (filtering + predicates) will then add the `hasContainers`-reading path. Document this in the step description with a unit test that asserts `g.V().has("name", "Alice")` is NOT translated by Track 2 (still in native path).
**Reference-accuracy caveat**: This conclusion depends on the assumption that `YTDBGraphStepStrategy.rebuildTraversal` always absorbs HasContainers into `YTDBGraphStep` when `hasLabel` follows the start step. Confirmed by reading the strategy (lines 119-128). PSI find-usages would confirm there are no other absorption paths.

### Finding T7 [should-fix]
**Certificate**: Premise "`MatchExecutionPlanner.createExecutionPlan` returns `InternalExecutionPlan`, not `SelectExecutionPlan`".
**Location**: Track 2 description, "Holds a `SelectExecutionPlan`". Design.md class diagram shows `plan SelectExecutionPlan`.
**Issue**: The boundary step should hold an `InternalExecutionPlan` (the declared return type of `createExecutionPlan`). Holding `SelectExecutionPlan` requires either an unsafe downcast or a type assertion that's only true today. `InternalExecutionPlan` exposes everything the boundary needs (`start()`, `close()`, `reset(ctx)`, `getContext()`).
**Proposed fix**: Change Track 2 description and design.md class diagram to use `InternalExecutionPlan`. Update step decomposition to `private InternalExecutionPlan plan;` in the boundary step.

### Finding T8 [should-fix]
**Certificate**: Premise "`AbstractStep.processNextStart`" — `FastNoSuchElementException` convention.
**Location**: Track 2 description, "On `processNextStart`, drives the plan's `ExecutionStream`, pulls one `Result`, ... wraps it in a `Traverser`."
**Issue**: TinkerPop's iteration contract requires throwing `FastNoSuchElementException.instance()` (not the slow `NoSuchElementException`) when the step is exhausted. Existing project precedent (`YTDBClassCountStep.processNextStart`) uses the fast variant. The plan does not specify this.
**Proposed fix**: Add to Track 2 step description: "`processNextStart` must throw `FastNoSuchElementException.instance()` when `ExecutionStream.hasNext(ctx)` returns false." Add a unit test asserting the boundary step terminates iteration cleanly on empty match. (The fast variant is in `org.apache.tinkerpop.gremlin.process.traversal.util`.)

### Finding T9 [should-fix]
**Certificate**: Premise "`clone()` semantics for stateful steps" — gap in plan.
**Location**: Track 2 description — silent on clone/reset semantics.
**Issue**: TinkerPop invokes `clone()` on steps in several scenarios (`Traversal.clone()`, sub-traversal reuse in `repeat`/`union`, remote-execution prep, test harnesses). A boundary step holding an in-flight `ExecutionStream` cannot be naively cloned — sharing the stream produces interleaved `next()` calls and crashes. The existing precedent (`YTDBClassCountStep.clone()`) explicitly resets `done = false` to allow re-execution.
**Proposed fix**: Specify lazy stream initialization: `YTDBMatchPlanStep` holds the `InternalExecutionPlan` and a nullable `ExecutionStream` field. `processNextStart()` opens the stream lazily on first call (and rebinds the database session via `plan.getContext().setDatabaseSession(...)` per `GqlExecutionPlan.start`'s precedent). `clone()` returns a copy with `stream = null` so the clone re-opens fresh. `reset(...)` closes the current stream and nulls it. Add unit tests for: (a) successful re-iteration after `reset()`; (b) clone produces independent results.

### Finding T10 [should-fix]
**Certificate**: Premise "`BasicCommandContext` is the construction pattern" — binding caveat.
**Location**: Track 2 description — silent on session binding.
**Issue**: When the strategy applies, it constructs the planner with a `CommandContext` carrying the session obtained from `traversal.getGraph().tx().getDatabaseSession()`. The `SelectExecutionPlan` retains a reference to that context. If iteration happens on a different thread, with a refreshed session, or after a `reset()`, the cached context's session may be stale. `GqlExecutionPlan.start(session)` re-binds via `sqlPlan.getContext().setDatabaseSession(session)`. The boundary step needs the same pattern.
**Proposed fix**: Add to Track 2 step description: "`processNextStart` rebinds the active session via `plan.getContext().setDatabaseSession(graphSession)` before opening the stream (mirrors `GqlExecutionPlan.start`)." Test: iterate the same boundary step twice with `reset()` between iterations on a session that gets re-activated; results should be identical.

### Finding T11 [should-fix]
**Certificate**: Edge case "`g.V()` with no IDs, no label, no filter" — ambiguous.
**Location**: Track 2 description, "If the prefix is non-trivial (≥1 step beyond the start scan-with-ids case), invokes the translator..."
**Issue**: The "non-trivial" wording is unclear. For `g.V().toList()` (just one step, no IDs), is the prefix trivial (no-op) or non-trivial (translate to `MATCH {class: V, as: a}`)? The integration test "verifies `g.V().toList()` produces the same vertices as the un-translated path" measures only equivalence, not engagement — if the strategy declines, the test still passes via fallback, defeating the smoke-test intent.
**Proposed fix**: Rephrase the recognition condition explicitly. Recommend: "Translate the prefix if the start step is `YTDBGraphStep` AND `hasContainers.isEmpty()` AND (any of: `getIds().length >= 1` for V(ids) parity test, OR the smoke-test feature flag is set)." Alternatively: always translate single-step `g.V()` (set `aliasClasses[alias] = "V"`, polymorphic by config) — that gives the smoke test real teeth. Add an explicit assertion in the test that the traversal's step list contains a `YTDBMatchPlanStep` after `applyStrategies()`.

### Finding T12 [suggestion]
**Certificate**: Premise "`useCache=false` skips the YqlExecutionPlanCache entirely" — latent fragility.
**Location**: New `MatchExecutionPlanner(MatchPlanInputs)` ctor.
**Issue**: The new ctor leaves `statement` field null. `createExecutionPlan` references `statement.executinPlanCanBeCached(session)` (line 478, 632) only when `useCache=true`. Track 2 always passes `useCache=false`, so this is safe today — but a future maintainer might enable caching for translated plans (Phase 2 per spec) and trigger an NPE. Defense-in-depth suggests guarding the access.
**Proposed fix**: In Track 2's new ctor, either:
- (a) Add Javadoc on the new ctor explicitly stating `statement == null` after construction and that callers MUST pass `useCache=false` until Phase 2 cache work lands. Reference issue YTDB-???.
- (b) Modify `createExecutionPlan` to add a null-guard: `if (useCache && this.statement != null && !enableProfiling && statement.executinPlanCanBeCached(session)) { ... }`. This is a one-line change in an existing public method — minor surface increase, but defends against future bugs.
Recommend (a) for Track 2 minimum invasion, (b) deferred to Phase 2.

### Finding T13 [suggestion]
**Certificate**: Premise "`YTDBStrategyUtil.isPolymorphic(traversal)` exists and may return null".
**Location**: Track 2 description — does not call out the null-graph guard.
**Issue**: The strategy must guard on `null` from `isPolymorphic(traversal)` to avoid NPEs in detached / sub-traversal scenarios. Existing strategies (Step, Count) do this explicitly. Track 2 description omits it.
**Proposed fix**: Add to Track 2's apply() pseudo-flow: "If `YTDBStrategyUtil.isPolymorphic(traversal) == null` return immediately (graph not accessible — sub-traversal or detached)."

### Finding T14 [suggestion]
**Certificate**: Premise "`MatchExecutionPlanner.handleProjectionsBlock` is internally invoked at ~line 624" — verdict CONFIRMED but PARTIAL.
**Location**: D2 rationale, Track 2 description.
**Issue**: The `handleProjectionsBlock` call site is reached only when `returnElements && returnPaths && returnPatterns && returnPathElements` are ALL false. Track 2's minimal `MatchPlanInputs` will have all four false (no $elements/$paths/etc semantics). Good. BUT: `returnItems` will also be empty for the bare `g.V()` smoke test. The else branch (line 597-624) iterates an empty list — OK, but the resulting `SQLProjection(emptyItems, false)` followed by `handleProjectionsBlock` with empty projection: needs verification that the planner handles "no projection items, no group/order/limit" gracefully.
**Proposed fix**: Add a unit test for this corner case — `MatchPlanInputs` with empty `returnItems` and no group/order/limit should produce a runnable plan whose stream emits raw match rows. If the planner doesn't handle empty projection cleanly, Track 2 must populate `returnItems` with `$matched.alias` (i.e., a default projection of the single bound alias).

### Finding T15 [suggestion]
**Certificate**: Edge case "`g.V(unknownId)` — RID that doesn't exist".
**Location**: Track 2 description test list.
**Issue**: Native Gremlin behavior for `g.V(unknownId)` varies — in modern TinkerPop it raises an exception or returns empty depending on version. MATCH's behavior with `aliasRids[a] = unknownRid` may diverge (likely empty). Without an explicit parity test, this divergence could go unnoticed.
**Proposed fix**: Add a test case `g.V(nonExistentRid).toList()` that asserts the same outcome as the native path (whatever that is). If outcomes diverge, document the divergence explicitly and decide whether to special-case the not-found path.

---

## Summary

- **Blockers:** 2 (T2, T3)
- **Should-fix:** 9 (T1, T4, T5, T6, T7, T8, T9, T10, T11)
- **Suggestions:** 4 (T12, T13, T14, T15)

T2 (strategy ordering enforcement) and T3 (PatternIR/MatchPatternBuilder lacks `aliasRids`) are blockers because the track cannot proceed without a concrete decision on each. T1 (FinalizationStrategy mis-categorization) is documentation-level but affects the plan's mermaid diagram correctness. The remaining should-fix findings are concrete implementation gaps that must be added to the step decomposition before execution.

Key codebase realities that the plan should reflect:
- `MatchExecutionPlanner.createExecutionPlan` returns `InternalExecutionPlan`, not `SelectExecutionPlan`.
- `groupBy/orderBy/unwind` in the planner are `final`; the new ctor MUST set them.
- `buildPatterns` short-circuits on `pattern != null` (good — confirms IR ownership invariant).
- `aliasRids` holds a single RID per alias; multi-ID `g.V(ids)` needs a different encoding.
- `YTDBGraphIoStepStrategy` is a `FinalizationStrategy`, not `ProviderOptimizationStrategy`.
- `applyPrior()` alone cannot enforce "before MatchStepStrategy" — needs `applyPost()` too.
