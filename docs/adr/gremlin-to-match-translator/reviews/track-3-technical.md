# Track 3 — Technical Review (iteration 1)

Track scope: edge traversal — `out`, `in`, `both`, `outE.inV`, `inE.outV`,
`bothE.otherV`, plus replacing the size-1 hard-decline gate with a step-recognition
walker and introducing anonymous intermediate vertex aliases.

Plan: `docs/adr/gremlin-to-match-translator/implementation-plan.md`
Step file: `docs/adr/gremlin-to-match-translator/tracks/track-3.md`

PSI tooling note: `mcp-steroid` MCP server was not invoked in this session; symbol
audits below used grep / Read / Glob. Reference-accuracy caveat: a missed polymorphic
caller of `MatchPatternBuilder.addEdge`, a missed override of `VertexStep`/`EdgeVertexStep`,
or a missed call site of the size-1 gate could change a finding's severity. Where such
risk is non-trivial I have flagged it inline.

---

## Part 1: Evidence Certificates

### Premise: `SQLMatchPathItem` exposes `outPath / inPath / bothPath` "static helpers"

- **Track claim**: "produces a `SQLMatchPathItem` via `SQLMatchPathItem.outPath / inPath / bothPath` helpers wrapped by `MatchPatternBuilder.addEdge`."
- **Search performed**: Read `core/.../internal/core/sql/parser/SQLMatchPathItem.java` end-to-end.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchPathItem.java:32-56`.
- **Actual behavior**: `outPath`, `inPath`, `bothPath` are **instance** methods (not static) that mutate `this.method` on a freshly-allocated `SQLMatchPathItem`. They take a single `SQLIdentifier edgeName` (one label, not a list). Internally they construct a `SQLMethodCall` whose `methodName.value = "out" / "in" / "both"` and add a single `SQLBaseExpression(edgeName)` parameter. The exact construction style:

  ```java
  private void graphPath(SQLIdentifier edgeName, String direction) {
    if (edgeName == null) { edgeName = new SQLIdentifier(-1); edgeName.value = "E"; }
    this.method = new SQLMethodCall(-1);
    this.method.methodName = new SQLIdentifier(-1);
    this.method.methodName.value = direction;
    var exp = new SQLExpression(-1);
    var sub = new SQLBaseExpression(edgeName.getStringValue());
    exp.mathExpression = sub;
    this.method.addParam(exp);
  }
  ```

- **Verdict**: PARTIAL — helpers exist and are reachable from `MatchPatternBuilder.addEdge`, but they are instance methods (already encapsulated by the existing builder, so the visible track-facing surface is `MatchPatternBuilder.addEdge`, not the parser helpers directly). More importantly: they take a **single** label, not a list; multi-label support cannot be expressed by calling them once.
- **Detail**: Track wording "MATCH supports edge-label `IN [...]` lists via the path-item filter mechanism" is incorrect — see the multi-label edge premise below.

### Premise: `MatchPatternBuilder.addEdge` exists with the signature the description implies

- **Track claim**: "produces a `SQLMatchPathItem` … wrapped by `MatchPatternBuilder.addEdge`."
- **Search performed**: Read `MatchPatternBuilder.java` end-to-end.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java:109-149`.
- **Actual behavior**: Signature is
  ```java
  public MatchPatternBuilder addEdge(
      String fromAlias, String toAlias, Direction dir,
      String edgeLabel, SQLWhereClause edgeFilter,
      SQLWhereClause whileCondition, Integer maxDepth)
  ```
  Direction is the builder's own `Direction { OUT, IN, BOTH }` enum (not the TinkerPop `Direction`). Single-string `edgeLabel` (line 113). `whileCondition`/`maxDepth` non-null **throw `UnsupportedOperationException`** (line 121-125) — the variable-depth path is not wired. `edgeFilter` is attached as the path item's **target-vertex** filter via `SQLMatchFilter.fromGqlNode(toAlias, null)` then `setFilter(edgeFilter)`; the parameter name "edgeFilter" is misleading — see the third premise below for impact.
- **Verdict**: CONFIRMED for the single-label, no-while-or-maxdepth shape. Multi-label edges and variable-depth shapes need additional work — see Findings T1 and T7.
- **Detail**: `addEdge` does not auto-register the endpoints in `aliasClasses`; only `addNode` does. Intermediate aliases produced by Track 3 must be registered separately via `addNode(intermediateAlias, "V", null, false)` if the planner is to receive a class hint for them. The current code path will leave intermediate aliases out of `aliasClasses` and the planner will treat them as unknown-class. This is acceptable for correctness (the Pattern is what drives execution) but degrades cost estimation — `estimateMethodFanOut` and `estimateAliasCardinality` use `aliasClasses` heavily for fan-out estimation.

### Premise: The "edge filter" parameter on `addEdge` is the place to put a multi-label `IN [...]`

- **Track claim**: "Multiple edge labels (`out("knows", "follows")`) → MATCH path-item with `IN [...]` edge filter; MATCH supports edge-label `IN` lists via the path-item filter mechanism."
- **Search performed**: Read `SQLMatchPathItem`, `SQLMatchFilter`, `SQLMatchFilterItem`, `SQLMethodCall` (call-site of the path-item method's `params`), and `SQLFunctionMove.execute` (the runtime evaluator for the `out` / `in` / `both` graph functions). Read the JJTree grammar for `OutPathItem` / `InPathItem` / `BothPathItem`.
- **Code locations**:
  - Grammar: `core/src/main/grammar/YouTrackDBSql.jjt:3583-3648` — only **one** identifier between the dashes is parseable.
  - Runtime: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/SQLFunctionMove.java:39-70` — labels are extracted from `iParameters` via `MultiValue.array(...)`. The function is **variadic**.
  - Builder: `MatchPatternBuilder.java:127-135` — the `edgeFilter` parameter populates `toFilter.setFilter(edgeFilter)`, which is the **target vertex's** `where:` block, not an edge filter.
- **Actual behavior**: There is no "edge filter" slot on `SQLMatchPathItem`. The only filter slot is the **target vertex** filter (`SQLMatchFilter.filter`). To express `out("knows", "follows")` against the IR, the SQLMethodCall's `params` list must hold **two** `SQLExpression` entries (one per label). The runtime evaluator handles this naturally because the `out`/`in`/`both` graph functions are variadic. Calling `outPath(SQLIdentifier)` once then bolting an extra `SQLExpression` onto `pathItem.method.params` is the cleanest path; alternatively the builder bypasses the helper and constructs the `SQLMethodCall` directly.
- **Verdict**: WRONG — the `IN [...]` edge filter mechanism does not exist. The track's wording must be corrected.
- **Detail**: This affects multi-label edges across `out`, `in`, `both`, `outE`, `inE`, `bothE`. Suggested fix: extend `MatchPatternBuilder.addEdge` to accept `List<String> edgeLabels` (or `String[]`) and append one `SQLExpression(new SQLBaseExpression(label))` per label to `pathItem.method.params`. The single-label call-site keeps a `String edgeLabel` overload that delegates.

### Premise: TinkerPop's `VertexStep` carries direction + edge labels + return class

- **Track claim**: "`VertexStep` with direction OUT/IN/BOTH and edge labels."
- **Search performed**: Decompiled `org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep` from `gremlin-core-3.8.1-af9db90-SNAPSHOT.jar` via `javap -p`.
- **Code location**: `~/.m2/repository/io/youtrackdb/gremlin-core/3.8.1-af9db90-SNAPSHOT/gremlin-core-3.8.1-af9db90-SNAPSHOT.jar!org/apache/tinkerpop/gremlin/process/traversal/step/map/VertexStep.class`.
- **Actual behavior**: Public surface includes `getDirection()` (`org.apache.tinkerpop.gremlin.structure.Direction`), `getEdgeLabels()` (`String[]`), `getReturnClass()` (`Class<E extends Element>` — `Vertex.class` for `out`/`in`/`both`, `Edge.class` for `outE`/`inE`/`bothE`), `returnsVertex()`, `returnsEdge()`. Constructor takes `(Traversal.Admin, Class<E>, Direction, String...)`.
- **Verdict**: CONFIRMED.
- **Detail**: The walker can branch on `step.getReturnClass() == Edge.class` to distinguish `outE`/`inE`/`bothE` from `out`/`in`/`both`. The `Direction` enum is TinkerPop's, not the builder's — translation layer must map between them.

### Premise: TinkerPop's `EdgeVertexStep` exists and pairs with a preceding `VertexStep` of `Edge` class

- **Track claim**: "`EdgeVertexStep` (the `inV()`/`outV()` after an `outE(label)` / `inE(label)`) — composes with the preceding `VertexStep` of `Edge` class."
- **Search performed**: `javap -p` on `EdgeVertexStep` and `EdgeOtherVertexStep` from the gremlin-core jar; bytecode read of `GraphTraversal.otherV()` to confirm which step class `__.otherV()` produces.
- **Code locations**: `~/.m2/.../gremlin-core/.../EdgeVertexStep.class`, `EdgeOtherVertexStep.class`, `GraphTraversal.class`.
- **Actual behavior**:
  - `EdgeVertexStep` is constructed with `(Traversal.Admin, Direction)` — single Direction arg. `getDirection()` returns it. Used by `inV()`, `outV()`, `toV(Direction)`.
  - `EdgeOtherVertexStep` (separate class!) is constructed with `(Traversal.Admin)` — no Direction arg. Used by `otherV()`.
  - `GraphTraversal.otherV()` bytecode shows it instantiates `org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep` directly, not `EdgeVertexStep(BOTH)`.
- **Verdict**: PARTIAL — `EdgeVertexStep` exists for `inV()`/`outV()` but `bothE().otherV()` produces `EdgeOtherVertexStep`, NOT `EdgeVertexStep`. The track's "`bothE(label).otherV()` — handled like the directional `bothE.inV` chain but with bidirectional edge" implies a single class, but two distinct TinkerPop classes must be recognised.
- **Detail**: Walker pseudocode must dispatch on `instanceof EdgeOtherVertexStep` separately from `instanceof EdgeVertexStep`. See Finding T2.

### Premise: The size-1 hard-decline gate lives where the track says it does

- **Track claim**: "the strategy currently declines any traversal whose `getSteps().size() > 1`. As this track's recognized step set lands, that hard-decline gate must be replaced with a step-recognition walker."
- **Search performed**: Read `GremlinToMatchStrategy.apply` and `GremlinToMatchTranslator.translatePrefix` end-to-end.
- **Code location**: The size-1 gate is **NOT** in the strategy. It lives in the translator: `GremlinToMatchTranslator.java:156`:
  ```java
  if (traversal.getSteps().size() > 1) {
    return Optional.empty();
  }
  ```
- **Actual behavior**: `GremlinToMatchStrategy.apply` does not size-gate the traversal. It checks idempotency, vertex-graph-start, empty-hasContainers, kill-switch, then delegates to `translator.apply(traversal)`. The size-1 gate is in `translatePrefix`, **after** the start-step / hasContainers gates, and gates the recognised-set check. So when the gate is replaced with a walker, the change is local to `translatePrefix` (or to a new walker class invoked from there) and the strategy itself does not need to change.
- **Verdict**: PARTIAL — the gate exists but in the translator, not the strategy. Track wording "the strategy currently declines …" should read "the translator currently declines …". Minor doc polish, not a substantive issue.
- **Detail**: Strategy's "Pre-existing predicates on the start step" gate (line 160) **does** affect Track 3. Once HasStep / hasLabel handlers land in Track 4, they will need to relax this gate (or the translator will pre-check and route the start-step's hasContainers as if they were leading `has(...)` steps). For Track 3's pure `g.V().out(...)` shape this gate is fine.

### Premise: Pattern auto-creates intermediate nodes via `getOrCreateNode`

- **Track claim**: "Anonymous intermediate vertex aliases — generated by the translator under a private prefix (e.g. `$g2m_anon_N`) chosen to be unique within the produced pattern."
- **Search performed**: Read `Pattern.addExpression` and `Pattern.getOrCreateNode`; cross-checked `MatchPatternBuilder.addEdge` (which calls `pattern.addExpression`).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/Pattern.java:65-92`.
- **Actual behavior**: `Pattern.addExpression` calls `getOrCreateNode(expression.origin)` and then for each `item`, `getOrCreateNode(item.filter)`. The lookup is by `filter.getAlias()`. So if `MatchPatternBuilder.addEdge(from, to, ...)` is called with a fresh `to` alias, the Pattern lazily creates the `PatternNode`. The `MatchPatternBuilder.addNode` map (`pattern.aliasToNode`) is the same map `Pattern` uses internally (line 76 of builder), so registering an alias via `addNode` and then calling `addEdge` reuses the node correctly.
- **Verdict**: CONFIRMED for the topology side. **NOT CONFIRMED** that intermediate aliases get a class entry — see "Edge case: chained `g.V().out().out()` leaves intermediate alias without class" below.

### Premise: `MatchExecutionPlanner` short-circuits `buildPatterns` when `pattern != null`

- **Track claim**: "`MatchExecutionPlanner.assignDefaultAliases` re-assignment never runs on the translator's path (consistency review CR4 — `buildPatterns` short-circuits when `pattern != null`, which is always the case for the `MatchPlanInputs` ctor)."
- **Search performed**: Read `MatchExecutionPlanner.buildPatterns` and the `(MatchPlanInputs)` ctor.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:480-516, 4433-4471`.
- **Actual behavior**: `MatchExecutionPlanner(MatchPlanInputs)` ctor sets `this.pattern = inputs.pattern()` (line ~485). `buildPatterns` first line: `if (this.pattern != null) { return; }`. So `assignDefaultAliases`, the `addAliases` loop, the `rebindFilters` call, and the class-inference walk are **all skipped** for the translator's path.
- **Verdict**: CONFIRMED.
- **Detail**: This is a load-bearing fact: the translator owns full responsibility for populating `aliasClasses` and `aliasFilters` — the planner will not lift class info out of the IR/AST. The test plan must verify intermediate-alias class info is supplied where it matters, or accept the cost-estimation degradation.

### Premise: `boundaryAlias` drives row projection in `YTDBMatchPlanStep`

- **Track claim** (implicit): the boundary step extracts the matched element by alias from each row.
- **Search performed**: Read `YTDBMatchPlanStep.projectElement`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java:278-284`.
- **Actual behavior**: `projectElement(row, graph)` calls `row.getVertex(boundaryAlias)`. So the `TranslationResult.boundaryAlias` is critical for chains: for `g.V().out("knows")`, the **target** node alias must be the boundary alias (Gremlin emits the target of `out`), not the source.
- **Verdict**: CONFIRMED.
- **Detail**: Track 3 description does not explicitly say which alias becomes the boundary alias for a chain. The implementation must wire "the alias of the **last** node in the walked chain" into `TranslationResult.boundaryAlias`. The translator's existing single-node Phase 1 path is degenerate (only one alias exists). See Finding T3.

---

### Edge case: chained `g.V().out().out()` leaves intermediate alias without a class entry

- **Trigger**: `g.V().out("knows").out("created")` — two-hop traversal, intermediate vertex anonymous, no `as()` label.
- **Code path trace**:
  1. Translator walks `[YTDBGraphStep, VertexStep(OUT, "knows"), VertexStep(OUT, "created")]`.
  2. Allocates aliases: `$g2m_v0` (start), `$g2m_anon_1` (after first hop), `$g2m_v_last` (terminal).
  3. Calls `builder.addNode("$g2m_v0", "V", null, false)`.
  4. Calls `builder.addEdge("$g2m_v0", "$g2m_anon_1", OUT, "knows", null, null, null)`.
  5. Calls `builder.addEdge("$g2m_anon_1", "$g2m_v_last", OUT, "created", null, null, null)`.
  6. `builder.build()` returns `(pattern, aliasClasses, aliasFilters)` with `aliasClasses == {"$g2m_v0": "V"}`. The intermediate alias `$g2m_anon_1` is **NOT** in `aliasClasses` — `addEdge` does not register it (see `MatchPatternBuilder.java:99-103` Javadoc). `$g2m_v_last` is also missing.
  7. `MatchExecutionPlanner.estimateRootEntries` consumes `aliasClasses` to compute root cardinalities. Aliases without a class entry get `Long.MAX_VALUE` (line ~889 of planner) — they are not eligible as scheduler roots and cannot be prefetched.
- **Outcome**: Topology is correct → results are correct. But cost estimation is degraded: the scheduler picks the only known-class alias (`$g2m_v0`) as root and cannot reverse-traverse from `$g2m_v_last` even when that would be cheaper (e.g. when `$g2m_v_last` has a strong filter from a future Track 4 `has` step). For Track 3 alone (no filters), this is acceptable; combined with Track 4 it becomes a planner-quality regression.
- **Track coverage**: NOT addressed. The track description does not specify whether intermediate aliases should be `addNode(alias, "V", null, false)`-registered.
- **Recommended fix**: Have the walker call `builder.addNode(intermediateAlias, "V", null, false)` before each `addEdge` (idempotent on the `addNode` side; merge-not-replace handles re-registration). Same for the terminal alias. Alternatively, narrow the class to the schema-declared edge target type when the edge label is fixed and a unique target type can be inferred from the schema (Phase 2 polish; out of scope for Phase 1 minimum but the track should document the choice).

### Edge case: optional + chain (interaction with future Track 6)

- **Trigger**: `g.V().out("knows").out("created")` planned via Track 3, then Track 6 introduces `optional(__.out("created"))` and the user writes `g.V().optional(__.out("knows").out("created"))`.
- **Code path trace**:
  1. Track 3 produces a chain `$g2m_v0 -- knows --> $g2m_anon_1 -- created --> $g2m_v_last`.
  2. Track 6 marks `$g2m_anon_1` and `$g2m_v_last` as optional via `addNode(alias, …, optional=true)`.
  3. `Pattern.validate()` (line 115-130 of Pattern.java) enforces that **optional nodes must be right-terminal** (`node.out.size() == 0`) and have `node.in.size() > 0`. With the chain shape, `$g2m_anon_1` has one outgoing edge, violating the constraint.
- **Outcome**: `Pattern.validate()` throws `CommandSQLParsingException`. This is Track 6's problem, but Track 3 must produce a Pattern shape that does not preclude future optional handling.
- **Track coverage**: Track 6 will need to detect this constraint; Track 3's responsibility is to pick alias placement so the **last** node in a chain is the only candidate that can be marked optional. The current "last alias is the boundary alias" already aligns with this. Document in the Track 3 description.

### Edge case: `bothE("knows").otherV()` walker pairing

- **Trigger**: `g.V().bothE("knows").otherV()`.
- **Code path trace**:
  1. Walker sees `[YTDBGraphStep, VertexStep(BOTH, "knows", returnClass=Edge), EdgeOtherVertexStep]`.
  2. The walker's pairing logic must recognise that `EdgeOtherVertexStep` follows a `VertexStep<Edge>` of direction BOTH and translate the pair into a single `addEdge(from, to, BOTH, "knows", …)` call.
- **Outcome**: If walker checks only `instanceof EdgeVertexStep` (per the literal track wording "handled like the directional `bothE.inV` chain"), `bothE.otherV()` declines and the whole traversal declines. If walker checks both `EdgeVertexStep` and `EdgeOtherVertexStep`, the pairing succeeds.
- **Track coverage**: Track wording is ambiguous. The implementation must check both classes.
- **Recommended fix**: Track description amended to call out `EdgeOtherVertexStep` as a distinct recognised class with its own pairing rule (always BOTH, no direction param to read). Walker code dispatches on either subclass.

### Edge case: `outE` without trailing `inV` / `outV` / `otherV`

- **Trigger**: `g.V().outE("knows")` — terminal edge step, no follow-up vertex step.
- **Code path trace**:
  1. Walker sees `[YTDBGraphStep, VertexStep(OUT, "knows", returnClass=Edge)]`.
  2. The walker's pairing logic expects a follow-up `EdgeVertexStep`. With no follow-up, the walker has two choices: (a) decline (treat as unrecognised), or (b) translate as a terminal edge boundary.
- **Outcome**: Track 3 scope is `outE.inV` / `inE.outV` / `bothE.otherV` — all paired forms. Terminal `outE` (no follow-up) is **out of scope**. Walker must decline cleanly. Track description should make this explicit.
- **Track coverage**: NOT addressed.
- **Recommended fix**: Track 3 description: "Terminal edge steps without a paired vertex step (`g.V().outE(...)`, `g.V().inE(...)`, `g.V().bothE(...)`) are unrecognised in this track. The walker declines the whole traversal under D3 all-or-nothing. Edge-bearing boundaries are deferred to a later track (or to Phase 2)."

### Edge case: walker recognises a step but translation produces a contract violation

- **Trigger**: Walker classifies all steps as recognised, but the resulting `prefixStepCount` mismatches the actual traversal step count (e.g. a counting bug after `outE.inV` pairing — the pair consumes two steps but gets counted as one).
- **Code path trace**:
  1. Translator returns `TranslationResult(prefixStepCount=N_buggy, …)`.
  2. `GremlinToMatchStrategy.applyTranslation` validates `prefixStepCount <= traversal.getSteps().size()` (line 277 of strategy). The contract guard catches `>`.
  3. But it does NOT catch `<`. If `prefixStepCount` is too low, the splice loop removes too few steps and leaves orphan native steps after the boundary.
- **Outcome**: Silent semantic divergence — the boundary step plus dangling native steps run sequentially, producing wrong rows.
- **Track coverage**: NOT addressed in track. The contract guard's asymmetry is a Track 2 carryover that becomes load-bearing once `prefixStepCount > 1` is possible.
- **Recommended fix**: Strengthen the contract guard in `applyTranslation` to assert the **exact** equality `prefixStepCount == preStepCount` (after subtracting the start step? — depends on counting convention) for all-or-nothing. Or have the translator return a sentinel "consumed full traversal" flag and the strategy reads `traversal.getSteps().size()` itself. Either way, the asymmetric guard becomes a bug attractor as the recognised set grows.

### Edge case: empty edge-label list (`out()` with no labels)

- **Trigger**: `g.V().out()` — no edge labels, traverses all edges regardless of class.
- **Code path trace**:
  1. `VertexStep.getEdgeLabels()` returns empty `String[]`.
  2. `MatchPatternBuilder.addEdge(..., edgeLabel, ...)` is called with `edgeLabel=null` (or empty).
  3. Builder line 134: `edgeLabel != null && !edgeLabel.isBlank() ? new SQLIdentifier(edgeLabel) : null;` — produces `null`.
  4. `pathItem.outPath(null)` — `graphPath` line 33-36: `edgeName = new SQLIdentifier(-1); edgeName.value = "E";` — defaults to base edge class `E`.
- **Outcome**: Correct — matches all edges of class `E` (the polymorphic root).
- **Track coverage**: Implicitly handled by the existing builder. Tests should cover this case.

---

### Integration: Walker replaces the size-1 gate in `translatePrefix`

- **Plan claim**: "the strategy currently declines any traversal whose `getSteps().size() > 1`. As this track's recognized step set lands, that hard-decline gate must be replaced with a step-recognition walker."
- **Actual entry point**: `GremlinToMatchTranslator.translatePrefix(Traversal.Admin)` line 137-230. The size-1 gate is line 156-158.
- **Caller analysis**: `translatePrefix` is referenced as a method handle from `GremlinToMatchStrategy.INSTANCE` (strategy line 124-125). One production call site. Two test files reference the strategy / translator (`GremlinToMatchTranslatorTest.java`, `GremlinToMatchStrategyTest.java`).
- **Breaking change risk**: LOW for production callers (single call site). HIGH for tests: any test that relies on `translatePrefix` declining a size>1 traversal needs to be updated to use a now-unrecognised step (e.g. `g.V().repeat(__.out())` or `g.V().sack()`).
- **Verdict**: MATCHES — the integration point is local. Track must specify how the walker gets invoked (one method per recognised step? a single dispatcher? a visitor over a step-recogniser registry?) since Tracks 4-10 will all extend the same walker.
- **Detail**: Recommend the track decompose into a step file that establishes the `StepRecogniser` (or equivalent) abstraction first, then per-step recognisers. Tracks 4-10 add new recognisers without touching the walker core.

### Integration: `MatchPatternBuilder.addEdge` + intermediate-alias `addNode`

- **Plan claim** (implicit from the "anonymous intermediate vertex aliases" scope item): the builder is already adequate.
- **Actual entry point**: `MatchPatternBuilder.addEdge` line 109-149.
- **Caller analysis**: One production caller today (`GqlMatchStatement` does NOT call `addEdge` — only `addNode`). Track 3's translator becomes the second caller. The `addEdge` API is currently single-label-only (third premise above) and does not auto-register `aliasClasses`.
- **Breaking change risk**: NONE for adding multi-label support if added as an overload. MEDIUM if the existing single-label signature is replaced rather than overloaded.
- **Verdict**: CALLERS AT RISK if the builder needs to grow a multi-label API mid-track without preserving the single-label signature.
- **Detail**: Cleanest extension is an additional overload: `addEdge(fromAlias, toAlias, dir, List<String> edgeLabels, edgeFilter, whileCondition, maxDepth)` that constructs the SQLMethodCall's `params` list with one entry per label. The single-label signature delegates with `List.of(label)`.

### Integration: Boundary alias / RETURN projection wiring for chains

- **Plan claim** (implicit): `TranslationResult.boundaryAlias` is the alias the boundary step extracts from each row.
- **Actual entry point**: `YTDBMatchPlanStep.projectElement` line 278-284 calls `row.getVertex(boundaryAlias)`.
- **Caller analysis**: Strategy passes `translation.boundaryAlias()` to the boundary step ctor.
- **Breaking change risk**: NONE for the wiring; HIGH for test correctness if the wrong alias is chosen.
- **Verdict**: MATCHES — but Track 3 must specify which alias is the boundary alias. For Gremlin semantics, the **last** alias in the walked chain is the boundary alias (target of the final hop); for `g.V().outE("knows")` (deferred per the edge-case above) it would be the edge alias.
- **Detail**: `MatchPlanInputs.returnItems` and `returnAliases` must also reference the same boundary alias so the planner's projection emits a `boundaryAlias AS boundaryAlias` row shape. Today's Phase 1 path uses one alias for both; Track 3 must update to the chain's terminal alias.

---

## Part 2: Findings

### Finding T1 [should-fix]
**Certificate**: Premise: The "edge filter" parameter on `addEdge` is the place to put a multi-label `IN [...]`.
**Location**: Track 3 description — the bullet "Multiple edge labels (`out("knows", "follows")`) → MATCH path-item with `IN [...]` edge filter; MATCH supports edge-label `IN` lists via the path-item filter mechanism."
**Issue**: The claim is technically wrong on two counts: (1) `SQLMatchPathItem` exposes no edge-label filter slot — the only filter slot is the **target vertex** filter; (2) the YQL grammar's `OutPathItem`/`InPathItem`/`BothPathItem` accepts only **one** identifier between dashes. Multi-label edges work at runtime because the `out`/`in`/`both` graph functions are variadic (`SQLFunctionMove` extracts labels via `MultiValue.array`); the IR construction for multi-label must populate the `SQLMethodCall.params` list with N `SQLExpression`s, NOT add an `IN [...]` filter.
**Proposed fix**:
1. Reword the track bullet: "Multiple edge labels (`out("knows", "follows")`) → MATCH path-item whose `SQLMethodCall.params` list carries one `SQLExpression(SQLBaseExpression(label))` per label. The runtime graph functions (`out`/`in`/`both`/`outE`/`inE`/`bothE`) are variadic and consume the labels through `SQLFunctionMove.execute` → `MultiValue.array`."
2. Extend `MatchPatternBuilder.addEdge` with an overload that takes `List<String> edgeLabels` (or `String[]`) and appends the params; the single-string overload delegates with `List.of(label)` (single-label backward compat).
3. Add a unit test on the builder that asserts the resulting `SQLMethodCall.getParams().size() == labels.size()` and that each param's `mathExpression` carries the expected base expression.

### Finding T2 [should-fix]
**Certificate**: Premise: TinkerPop's `EdgeVertexStep` exists and pairs with a preceding `VertexStep` of `Edge` class; Edge case: `bothE("knows").otherV()` walker pairing.
**Location**: Track 3 description — "`bothE(label).otherV()` — handled like the directional `bothE.inV` chain but with bidirectional edge."
**Issue**: TinkerPop's `__.otherV()` produces `EdgeOtherVertexStep`, a **separate** class from `EdgeVertexStep`. A walker that only checks `instanceof EdgeVertexStep` (the literal reading of "handled like the directional `bothE.inV` chain") will silently decline `bothE().otherV()` and the whole traversal will fall back to native execution.
**Proposed fix**: Track description amended to call out `EdgeOtherVertexStep` as its own recognised class:
- "`bothE(label).otherV()` — `EdgeOtherVertexStep` (a class distinct from `EdgeVertexStep`) is paired with the preceding `VertexStep<Edge>` of direction BOTH. Direction is implicit (always BOTH); no `getDirection()` to read on this step."
Walker dispatches on either `EdgeVertexStep` or `EdgeOtherVertexStep`; pairing logic differs only in how direction is sourced (direction arg vs implicit BOTH).
A test must explicitly cover `g.V().bothE("knows").otherV()` against the equivalent SQL `MATCH {…}.both('knows'){…}` to pin the parity.

### Finding T3 [should-fix]
**Certificate**: Integration: Boundary alias / RETURN projection wiring for chains; Premise: `boundaryAlias` drives row projection in `YTDBMatchPlanStep`.
**Location**: Track 3 description — anonymous intermediate aliases bullet.
**Issue**: The track describes anonymous intermediate alias generation but does NOT specify which alias becomes the `TranslationResult.boundaryAlias` for a chain. Gremlin's `g.V().out("knows")` emits the **target** of the `out` hop; the boundary step calls `row.getVertex(boundaryAlias)`, so the boundary alias must be the chain's terminal alias. The current Phase 1 single-node code is degenerate (one alias) and does not exercise this distinction. Tracks 4-10 will all need to know which alias is the boundary.
**Proposed fix**: Track description amended with: "Boundary alias selection: the alias of the **last** walked node (the alias the chain ends at) becomes the `TranslationResult.boundaryAlias`. The same alias is the sole entry in `MatchPlanInputs.returnItems` / `returnAliases`. For a chain `g.V().out("knows").out("created")` the boundary alias is the terminal `$g2m_v_last` alias, not the start `$g2m_v0`."
Add a unit test that asserts, for a 2-hop traversal, the `TranslationResult.boundaryAlias()` equals the alias used in the **second** `addEdge`'s `toAlias`.

### Finding T4 [suggestion]
**Certificate**: Edge case: chained `g.V().out().out()` leaves intermediate alias without a class entry; Premise: `MatchExecutionPlanner` short-circuits `buildPatterns` when `pattern != null`.
**Location**: Track 3 description — anonymous intermediate aliases bullet.
**Issue**: `MatchPatternBuilder.addEdge` does NOT register endpoints in `aliasClasses`. The planner's `addAliases` walk that infers classes from the AST is **skipped** under the `MatchPlanInputs` ctor. For two-hop chains, the intermediate alias and the terminal alias have no class entry, which degrades cost estimation in `estimateRootEntries` and `estimateMethodFanOut` (they get `Long.MAX_VALUE` / fallback fan-out). Correctness is preserved (the Pattern drives execution) but the scheduler may pick a worse root than necessary.
**Proposed fix**: Track description add: "For each chain alias (start, intermediates, terminal), the walker registers `addNode(alias, 'V', null, false)` so `aliasClasses` carries the polymorphic vertex root for every node in the chain. This gives the cost estimator a defined fan-out factor (computed against `V`'s schema fan-out) for each hop. When polymorphic=false on the start, the `@class = 'V'` filter rides on the start alias only — intermediate hops keep the polymorphic class hint so the scheduler is free to reverse-traverse if Track 4 later attaches a stronger filter to a downstream alias."
Add a unit test asserting `aliasClasses.containsKey(intermediateAlias)` and `.equals("V")` for a 2-hop chain.

### Finding T5 [should-fix]
**Certificate**: Edge case: walker recognises a step but translation produces a contract violation.
**Location**: `GremlinToMatchStrategy.applyTranslation` line 277 (`prefixStepCount > preStepCount` guard).
**Issue**: The contract guard is asymmetric — it catches `prefixStepCount` too **large**, but not too **small**. With Track 3 introducing multi-step recognition, an off-by-one bug in step counting (e.g. counting `outE.inV` as one when it should be two) produces a `prefixStepCount` smaller than the actual recognised range; the splice loop removes too few steps, and the boundary step plus orphan native steps run sequentially, silently emitting wrong rows.
**Proposed fix**: Strengthen the guard to `prefixStepCount != preStepCount` under D3 all-or-nothing (the translator either consumes the entire traversal or returns `Optional.empty()`, so under the new walker `prefixStepCount` should always equal the step count). Alternatively, have the translator return a `consumeAll()` flag rather than a count, and the strategy reads `traversal.getSteps().size()` itself.
Add a unit test that injects a fixture translator returning a too-small `prefixStepCount` and asserts the strategy declines (logs WARN, leaves traversal unchanged) rather than producing a half-spliced step list.

### Finding T6 [suggestion]
**Certificate**: Edge case: `outE` without trailing `inV` / `outV` / `otherV`.
**Location**: Track 3 description — terminal edge step coverage.
**Issue**: Track 3's bullets cover paired forms (`outE.inV`, `inE.outV`, `bothE.otherV`) but do not say what happens when an edge step is terminal (`g.V().outE("knows")` with no follow-up). The walker must classify terminal edge steps as unrecognised and decline the whole traversal, otherwise the boundary step has no defined output type for an emitted edge.
**Proposed fix**: Track description add a line: "Terminal edge steps (`g.V().outE(...)`, `g.V().inE(...)`, `g.V().bothE(...)` with no follow-up `inV`/`outV`/`otherV`) are unrecognised in this track. The walker declines the whole traversal under D3. Edge-bearing boundaries are deferred (Phase 2)."
Add a regression test asserting `g.V().outE("knows")` declines (assertion: traversal step list unchanged after `applyStrategies()`).

### Finding T7 [suggestion]
**Certificate**: Premise: `MatchPatternBuilder.addEdge` exists with the signature the description implies.
**Location**: `MatchPatternBuilder.addEdge` line 121-125 — `whileCondition / maxDepth` non-null throws `UnsupportedOperationException`.
**Issue**: Track 3 does NOT touch variable-depth, but the un-implemented slot is fragile in the face of the walker. If a future per-step recogniser registers `repeat()` (Phase 2) and forgets to wire `whileCondition`, the `addEdge` call throws `UnsupportedOperationException` mid-walk, the strategy's top-level `try { … } catch (RuntimeException) { log.warn; }` (strategy line 174-185) catches it, and the warning surfaces in production. Track 3's walker scaffold should establish the discipline: the walker pre-checks shape constraints (e.g. variable-depth absent) before calling builder methods that throw.
**Proposed fix**: Track description add: "The walker pre-checks each recognised step's shape against the builder's supported surface. Unsupported sub-shapes (variable-depth `outE`, `whileCondition`/`maxDepth` on hops) are unrecognised and decline cleanly rather than reaching the builder's `UnsupportedOperationException`."
No code change in Track 3; the discipline note is for downstream tracks.

### Finding T8 [suggestion]
**Certificate**: Premise: The size-1 hard-decline gate lives where the track says it does.
**Location**: Track 3 description — "Gate replacement" section.
**Issue**: The track says "the strategy currently declines any traversal whose `getSteps().size() > 1`." The gate is in the **translator** (`GremlinToMatchTranslator.translatePrefix` line 156), not the strategy. Substantive impact is nil (the replacement still happens at the right code site), but readers seeking the gate by reading the strategy will not find it.
**Proposed fix**: One-word edit in the track description: "the **translator** currently declines any traversal whose `getSteps().size() > 1`."

### Finding T9 [suggestion]
**Certificate**: Integration: Walker replaces the size-1 gate in `translatePrefix`.
**Location**: Track 3 description — "Gate replacement" + "load-bearing entry point for Tracks 4-10".
**Issue**: The track defers the walker abstraction to step-decomposition time, but Tracks 4-10 will all extend it. The risk is that Track 3 ships an ad-hoc walker (one big `if/else` chain) and each subsequent track touches the same monolith, creating merge churn and making the "decline-the-whole-traversal" contract hard to audit per-step. Establishing a `StepRecogniser` (or visitor) abstraction in Track 3 has compounding payoff across Tracks 4-10.
**Proposed fix**: Step-decomposition guidance for Track 3: first step introduces `StepRecogniser` (interface with `recognise(Step) → Optional<RecognitionResult>` semantics, plus a registry / chain-of-responsibility), second-and-subsequent steps add per-shape recognisers (one-hop direction, paired edge+vertex, multi-label), and Track 3 lands with the registry seeded by Track 3's own recognisers. Tracks 4-10 add new recognisers without touching the walker core.

### Finding T10 [suggestion]
**Certificate**: Edge case: optional + chain (interaction with future Track 6).
**Location**: Track 3 description — verification list.
**Issue**: `Pattern.validate()` enforces optional nodes are right-terminal with at least one incoming edge. Track 3's chain shape automatically satisfies this **only if** the boundary alias is the chain's terminal alias (Finding T3). Track 6 will mark a chain's terminal alias as optional; if Track 3's chain shape were ever inverted (boundary alias = source), Track 6 would hit `CommandSQLParsingException` from `Pattern.validate()`.
**Proposed fix**: Track 3 description add a note in the verification section: "Chain orientation aligns with `Pattern.validate()`'s right-terminal optional constraint: the terminal alias is the only candidate that may be marked optional in a future track. Tests in this track assert the terminal alias is `addNode`-registered before any future-track optional flag could land."

---

## Summary

- 10 findings.
- Severity: 0 blocker, 4 should-fix (T1, T2, T3, T5), 6 suggestion (T4, T6, T7, T8, T9, T10).
- Recommended next step: amend the Track 3 description with the corrections from T1, T2, T3, T5; carry T9 into step decomposition (split the walker abstraction into its own first step); accept T4/T6/T7/T8/T10 as polish items.

No `skip` recommendation. Track 3 is core to the plan: it lifts the recognised set from "bare g.V()" to "edge-traversal patterns", which is what every subsequent track depends on. The corrections are local to track wording and step decomposition; they do not require a Decision Record change.
