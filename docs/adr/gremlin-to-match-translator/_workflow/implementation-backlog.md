# Gremlin-to-MATCH Translator — Track Details

## Track 3: Edge traversal — `out`, `in`, `both`, `outE.inV`, `inE.outV`, `bothE.otherV`, plus non-adjacent edge filtering

> **What**:
> - Extend the recognized step set with edge-traversal patterns. Add
>   handlers in `GremlinStepWalker` for the following Gremlin shapes:
>   - `VertexStep` with direction OUT/IN/BOTH and edge labels — produces
>     a `SQLMatchPathItem` via the `SQLMatchPathItem.outPath/inPath/bothPath`
>     helpers wrapped by `MatchPatternBuilder.addEdge`.
>   - `EdgeVertexStep` (the `inV()`/`outV()` after an `outE(label)` /
>     `inE(label)`) — when **adjacent** to the edge step, the chain is
>     pre-folded by TinkerPop's `IncidentToAdjacentStrategy` and the
>     recognizer sees `out(label)` / `in(label)`. When **not adjacent**
>     (intervening `HasStep`s — see below), `EdgeStepRecogniser` claims
>     the chain through multi-step consumption.
>   - `bothE(label).otherV()` — same two cases (adjacent / non-adjacent).
>   - **Non-adjacent edge filtering** (`outE(L).has(...).inV()` and
>     analogues, including chained `has`): `EdgeStepRecogniser` mints a
>     translator-private edge alias (`$g2m_edge_N` from
>     `anonEdgeAliasGenerator`), peeks forward through every adjacent
>     `HasStep`, AND-merges their predicates into
>     `ctx.edgeFilters[$g2m_edge_N]`, and consumes the closing
>     `EdgeVertexStep` (or `otherV`-form `VertexStep`) that targets a
>     fresh `$g2m_anon_M` vertex alias. The merged filter is parked on
>     `SQLMatchPathItem.filter` — the IR already supports edge filters,
>     so no executor or planner change is needed. Bare `outE(L)`
>     (without a paired vertex hop) and user-facing `outE(L).as("e")`
>     stay out-of-scope (see design.md Out-of-scope table).
>   - Multiple edge labels (`out("knows", "follows")`) — MATCH path-item
>     with `IN [...]` edge filter; MATCH supports edge-label `IN` lists
>     via the path-item filter mechanism.
>   - Anonymous intermediate vertex and edge aliases — generated under
>     private prefixes (`$g2m_anon_N` for vertices, `$g2m_edge_N` for
>     edges) that are unique within the produced pattern and cannot
>     collide with user-provided labels (`$` prefix is reserved — see
>     "Anonymous alias generation" in design.md).
>   - Chain-target polymorphism: every chain target inherits the
>     `polymorphicQuery` flag set by `StartStepRecogniser`. When
>     non-polymorphic, augment each chain target's `aliasFilters` with
>     `@class = 'V'` (via the shared `MatchClassFilters` helper extracted
>     in this track). The flag is read from `WalkerContext.polymorphic`,
>     which `StartStepRecogniser` pins at first claim.
> - **Gate replacement**: replace the current `traversal.getSteps().size() > 1`
>   hard-decline gate with a step-recognition walker that classifies each
>   step as recognized/unrecognized and declines the entire traversal only
>   when at least one unrecognized step is present (D3 all-or-nothing).
>   The walker becomes the load-bearing entry point for Tracks 4-10.
> - **Walker refactor to index-driven iteration** (D10): switch the
>   walker loop from `for (Step step : steps)` to
>   `while (ctx.stepIndex < steps.size())` so a recognizer can consume
>   multiple steps in one claim via `ctx.stepIndex += consumedSteps`.
>   `EdgeStepRecogniser` is the first multi-step recognizer; the
>   single-step recognizers shipped before Track 3 (StartStepRecogniser
>   in Track 2) are unchanged — when a recognizer does not advance the
>   index, the walker defaults to the usual `++`.
>
> **How**:
> - Step ordering (provisional):
>   1. Walker refactor to index-driven iteration with the
>      strictly-increasing-stepIndex assertion (D10). Existing
>      single-step recognizer (StartStepRecogniser) covered by the
>      `if (notAdvanced) stepIndex++` fallback.
>   2. Simple direction handlers for `VertexStep` (OUT, IN, BOTH) emitting
>      one path-item per step.
>   3. Adjacent `outE`+`inV` (and `inE`+`outV`) — already folded by
>      `IncidentToAdjacentStrategy`; verify the recognizer sees the
>      folded shape with an integration test.
>   4. **Non-adjacent edge filtering via `EdgeStepRecogniser`** —
>      peek-ahead consuming the edge step, every adjacent `HasStep`,
>      and the closing vertex step. Delegate `HasStep` predicate
>      translation to the same `GremlinPredicateAdapter` Track 4
>      builds, with a flag indicating the edge-alias is the current
>      filter target.
>   5. Multi-label edge filter via `IN [...]` path-item filter.
>   6. Anonymous intermediate alias generators (vertex and edge) under
>      their private prefixes, uniqueness scoped to the in-progress
>      pattern.
>   7. Extract `MatchClassFilters.classEq(...)` helper from the duplicated
>      `buildClassEqExpression` logic in `StartStepRecogniser`; refactor
>      the start-step recognizer to use it; pin polymorphism flag on
>      `WalkerContext.polymorphic` at first claim.
>   8. `VertexStepRecogniser` reads `WalkerContext.polymorphic` and
>      applies `MatchClassFilters.classEq("V")` to every chain target
>      when non-polymorphic. Add unit tests for both flag values.
>   9. Replace the size-1 decline gate with the recognition walker;
>      correctness tests vs SQL `MATCH` for one-hop, two-hop, three-hop,
>      mixed-direction, multi-label, anonymous-intermediate, and
>      edge-side-filter patterns.
> - Reuse `MatchPatternBuilder.addEdge` from Track 1 (which exposes the
>   optional `edgeAlias` / `edgeFilter` parameters); the walker drives it
>   via `GremlinPatternAssembler`. The
>   `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX` convention is intentionally
>   not reused — it is package-private to `internal/core/sql/executor/match`
>   and `MatchExecutionPlanner.assignDefaultAliases` does not run on the
>   translator's path (consistency review CR4: `buildPatterns`
>   short-circuits when `pattern != null`, which is always the case for
>   the `MatchPlanInputs` ctor).
>
> **Constraints**:
> - **In-scope files**: `GremlinStepWalker` (index-driven iteration
>   refactor + strictly-increasing-stepIndex assertion),
>   `GremlinPatternAssembler`, `EdgeStepRecogniser` (new) — all in
>   `internal/core/gremlin/translator/`. Plus the strategy class
>   (replacing the size-1 gate), the new `MatchClassFilters` helper
>   class (extracted from `StartStepRecogniser`), `WalkerContext`
>   (adding the `polymorphic` field, `edgeFilters` map, and
>   `anonEdgeAliasGenerator`), and any reusable helpers added to
>   `MatchPatternBuilder`.
> - **Predicate adapter skeleton lands in Track 3** because
>   `EdgeStepRecogniser` needs to translate edge-side `has(...)`
>   container predicates into `SQLBooleanExpression`. The skeleton
>   covers `Compare.eq/neq/gt/gte/lt/lte`, `Contains.within/without`,
>   and `P.between(lo, hi)` — enough for the LDBC edge-filter shapes
>   (IC2's `creationDate <= maxDate`, IC8's `creationDate IN range`).
>   Track 4 extends the same adapter with the rest of the `P` variants,
>   the `Text`/`TextP` translation, the `HasStep` family handlers, and
>   the `HasLabel`/`HasId`/`HasNot` step-level shapes — i.e. Track 4
>   adds the **step handlers** plus the **node-side** filter shapes on
>   top of the adapter Track 3 bootstraps.
> - **Out of scope**: full predicate set + node-side has* step
>   handlers (Track 4), logical filters (Track 5), step labels / dedup
>   (Track 6), projections (Track 7). Bare `outE(L)` terminator and
>   user-facing `as("e")` on edge steps stay out-of-Phase-1.
> - Result sets must match the equivalent SQL `MATCH` queries identically
>   (same rows, same execution-plan step types in the same order),
>   including under non-polymorphic mode where chain targets must
>   restrict to direct-class instances, and including the edge-side
>   filter shapes.
> - All anonymous aliases must be locally unique within a single produced
>   pattern.
>
> **Interactions**:
> - Depends on Track 2 (the strategy + boundary step + `MatchPlanInputs`
>   ctor must be in place; this track replaces the size-1 decline gate).
> - Enables Tracks 4-10 — they all extend the walker on top of the
>   recognition machinery this track introduces. The `MatchClassFilters`
>   helper extracted here is reused by Track 4's `hasLabel` recognizer
>   (third call site for `@class = '<name>'` filter construction).

---

## Track 4: Filtering + predicates

> **What**:
> - Implement `GremlinPredicateAdapter` translating TinkerPop `P<T>`
>   instances into `SQLBooleanExpression` subtrees via
>   `MatchWhereBuilder`:
>   - `Compare.eq` → `op(field, EQ, lit)`; `neq` → `NE`; `gt`, `gte`,
>     `lt`, `lte` → corresponding operator.
>   - `Contains.within` → `in(field, list)`; `Contains.without` →
>     `notIn(field, list)`.
>   - `P.between(lo, hi)` → `between(field, lo, hi)`.
>   - `P.inside(lo, hi)` → `and(op(field, GT, lo), op(field, LT, hi))`.
>   - `P.outside(lo, hi)` → `or(op(field, LT, lo), op(field, GT, hi))`.
>   - `Text.containing` (and `TextP.containing`) →
>     `containsText(field, substring)`.
>   - `Text.startingWith` / `endingWith` (and the equivalent `TextP.*`
>     plus `TextP.regex`) → recognizer declines; native pipeline
>     handles them. See design.md § Predicate translation for the
>     case-sensitivity / `matches`-vs-`find` divergence rationale.
>   - `P.and(...)` / `P.or(...)` / `P.not(...)` → recursive composition
>     via `MatchWhereBuilder.and/or/not`.
> - Implement four `has*` step handlers in `GremlinStepWalker`:
>   - `HasStep` — for each `HasContainer` invokes the predicate adapter,
>     ANDs all containers, attaches the result as the `where` of the
>     current `SQLMatchFilter` via `MatchPatternBuilder`.
>   - `HasStep` with a `T.label` container that's not yet folded into
>     the graph step — translates to a class constraint on the current
>     node.
>   - `YTDBHasLabelStep` — class names go into `aliasClasses`;
>     `polymorphicQuery=false` adds a `class IN [...]` refinement.
>   - `HasStep` with `hasId(...)` — RID constraint on `SQLMatchFilter`.
>     **Single-ID** routes through `MatchPlanInputs.aliasRids` (planner's
>     `SELECT FROM #X:Y` fast path). **Multi-ID** routes through
>     `aliasFilters` with a hand-built `WHERE @rid IN [...]` clause —
>     same routing the prior track's `g.V(ids)` path discovered, because
>     `aliasRids` is single-RID-per-alias by the MATCH SQL grammar. `@rid`
>     is `SQLRecordAttribute`, not `SQLIdentifier`, so the IN AST is built
>     by hand.
>   - `HasStep` with `hasNot(key)` — translates to `field IS NOT DEFINED`
>     via `MatchWhereBuilder.isNotDefined(field)` using the new YTDB SQL
>     operator added in Track 1 (see plan.md D-IS-DEFINED and design.md
>     "Phase 1 dependency: `IS DEFINED` / `IS NOT DEFINED` operators").
>     Symmetric: `has(key)` → `MatchWhereBuilder.isDefined(field)`.
>     **Not** `IS NULL` / `IS NOT NULL` — those over-match against
>     properties stored with literal `null` value (TP `hasNot` would
>     not match such properties because TP `Property.isPresent()`
>     returns `true` for them — confirmed against
>     `YTDBElementImpl.readFromEntity` + `EntityImpl.getPropertyAndChooseReturnValue`
>     during PR #1038 review).
> - Lift the cached `SQLInCondition.operator` reflection helper into a
>   shared helper in `match.builder/`. The multi-ID `hasId` site becomes
>   the third call site for that helper (Track 1's `MatchWhereBuilder`
>   and Track 2's translator are the first two); duplicating it again
>   is the wrong move.
>
> **How**:
> - Step ordering (provisional):
>   1. `GremlinPredicateAdapter` skeleton + every `P` variant.
>   2. `HasStep` handler.
>   3. `HasLabelStep` / `YTDBHasLabelStep` handler with polymorphism.
>   4. `HasIdStep` (single-ID via `aliasRids`, multi-ID via
>      `aliasFilters @rid IN [...]`); lift the `setInOperator` reflection
>      helper into `match.builder/`.
>   5. `HasNotStep` + `HasStep` presence form using
>      `MatchWhereBuilder.isDefined` / `isNotDefined` (depends on
>      Track 1 landing the SQL operator first).
>   6. Comprehensive predicate-equivalence tests vs SQL `MATCH` with
>      explicit `WHERE`, including multi-`has` AND combinations.
>      Regression tests MUST pin the null-valued vs absent distinction:
>      a vertex with `foo` set to literal `null` is dropped by
>      `hasNot(foo)` (matches native TP, not the legacy `IS NULL`
>      semantics).
>
> **Constraints**:
> - **In-scope files**: `GremlinPredicateAdapter`, `GremlinStepWalker`
>   (handlers), `MatchWhereBuilder` / `match.builder/` (lifted reflection
>   helper), strategy / translator integration glue.
> - **Out of scope**: logical-combinator filter steps (`and`, `or`, `not`,
>   `where` — Track 5); step labels / optional / dedup (Track 6).
> - The `SQLInCondition.operator` reflection helper must live in one
>   place after this track; no new duplicated copies introduced.
> - Result sets must match equivalent SQL `MATCH` with explicit `WHERE`
>   clauses identically.
>
> **Interactions**:
> - Depends on Track 3 (recognition walker + edge-traversal handlers
>   are the substrate the `has*` handlers attach to).
> - Depends on Track 1 for `MatchWhereBuilder.isDefined` /
>   `isNotDefined` factories and the `SQLIsDefinedCondition` /
>   `SQLIsNotDefinedCondition` AST nodes (D-IS-DEFINED). Steps 1-4
>   of this track can land before Track 1 finishes the operator work;
>   step 5 (`HasNotStep`) is the gating step.
> - Enables Track 5 (logical combinators that contain filter
>   sub-traversals reuse the predicate adapter).
> - Track 7 reuses the same `EntityImpl.hasProperty(key)` primitive
>   for `valueMap`/`values`/`select.by` projection presence
>   classification — no separate helper.

---

## Track 5: Logical filters — `and`, `or`, `not`, `where`

> **What**:
> - Implement TinkerPop's logical filter steps (which contain
>   sub-traversals) by descending into their global children:
>   - **`AndStep` / `OrStep` (the `ConnectiveStrategy` form)** —
>     barrier-style steps with N sub-traversals; each child runs through
>     a sub-walker against the same recognizer registry as the top-level
>     walk (fresh `SubWalkerContext` inheriting the parent's
>     `boundaryAlias`). The two recognizers diverge on what they accept:
>     - `AndStepRecogniser` accepts any mix of pure-filter and
>       edge-bearing children. Pure-filter children become
>       `SQLBooleanExpression`s AND-composed into the boundary alias's
>       WHERE; edge-bearing children contribute pattern fragments that
>       append to the parent's `MatchPlanInputs` (MATCH IR composes them
>       by implicit AND).
>     - `OrStepRecogniser` requires all children pure-filter. Any
>       edge-bearing child (transitively) declines the OR under D3 —
>       no MATCH IR OR exists at the pattern-fragment level. Phase 2
>       path documented in design.md Out-of-scope ("OR over edge-bearing
>       sub-traversals" row).
>     Two separate recognizer files so the asymmetry is visible in
>     code.
>   - **`NotStep`** — single `NotStepRecogniser` registered under
>     `NotStep.class`; internal branch on `hasEdgeHops(subTraversal)`
>     selects between the two MATCH IR slots:
>     - *Edge-bearing sub-traversal* (`not(__.out("knows"))`,
>       `not(__.out("knows").has("city","NY"))`) — translates to a new
>       `SQLMatchExpression` added to
>       `MatchPlanInputs.notMatchExpressions` (consistency review CR1;
>       routed through Track 2's new ctor). The first alias of the NOT
>       pattern must already exist in the positive pattern (planner
>       constraint, see `MatchExecutionPlanner.manageNotPatterns`); the
>       recognizer pre-validates this against `ctx.boundaryAlias` and
>       declines under D3 if it fails.
>     - *Pure-filter sub-traversal* (`not(__.has(...))`, `hasNot(key)`
>       desugar) — translates inline to `MatchWhereBuilder.not(...)` on
>       the current node's `where`.
>     Both branches share the no-mutation-on-decline contract — the
>     sub-traversal is translated through a pure function first; `ctx`
>     is mutated only on success.
>   - **`WhereTraversalStep`** — same as `NotStep` but positive: a
>     sub-traversal that must yield ≥1 result for the current row to
>     pass. Translated as either an inline filter (when the sub-traversal
>     is pure filter) or as an additional `SQLMatchExpression` linked to
>     existing aliases.
>   - **`WherePredicateStep`** — `where(P.eq("a"))` style; compares two
>     step-labels. Translates to a `where` clause referencing
>     `$matched.a` accessors.
>
> **How**:
> - Step ordering (provisional):
>   1. `AndStep` / `OrStep` recursive walker — sub-traversal as
>      `SQLBooleanExpression` subtree.
>   2. `NotStepRecogniser` — one recognizer for both shapes, internal
>      branch on `hasEdgeHops(subTraversal)`: pure-filter →
>      `MatchWhereBuilder.not(...)`; edge-bearing → append
>      `SQLMatchExpression` to `MatchPlanInputs.notMatchExpressions`.
>   3. `WhereTraversalStep` — pure-filter inline path + pattern-emit
>      path.
>   4. `WherePredicateStep` — `$matched.<label>` accessor in the where.
>   5. Equivalence tests for each combinator including negated edge
>      patterns (`not(out("knows"))`) and where-with-step-label
>      (`where("a", P.eq("b"))`).
> - Recognition for each combinator's children must be recursive: if any
>   child is unrecognized, the whole enclosing traversal declines (D3).
>
> **Constraints**:
> - **In-scope files**: `GremlinStepWalker` (logical-step handlers),
>   `GremlinPatternAssembler` (NOT pattern emission), the predicate
>   adapter (reused).
> - **Out of scope**: step labels / optional / dedup (Track 6),
>   projections (Track 7).
> - The first alias of every `notMatchExpressions` entry must exist in
>   the positive pattern (planner constraint).
> - Equivalence vs SQL `MATCH` `WHERE NOT (...)` and nested
>   `MATCH ... WHERE ... AND ...` patterns must be exact.
>
> **Interactions**:
> - Depends on Track 4 (predicate adapter + `has*` step handlers — the
>   sub-traversals walked here are recursive instances of the same
>   primitives).
> - Enables Track 6 (step labels and `optional` interact with the
>   WhereTraversalStep / NotStep alias resolution).

---

## Track 6: Step labels + dedup

> **What**:
> - Add the recognized-step set: `as(label)`, `dedup()` /
>   `dedup(labels...)`.
>   - **`as(label)`** — walker reads `Step.getLabels()` of every step it
>     visits and propagates the label to the most recent `SQLMatchFilter`
>     via `MatchPatternBuilder.alias(...)` updates. Default aliases
>     (when a node has none) are generated under the translator's own
>     private prefix (e.g. `$g2m_anon_N`); see Track 3 for the rationale
>     (consistency review CR4).
>   - **`DedupStep` (no labels)** — translates to `info.distinct = true`
>     on the `QueryPlanningInfo`, materialized as a
>     `DistinctExecutionStep` by `handleProjectionsBlock`.
>   - **`DedupStep(labels...)`** — translates to a projection over those
>     labels followed by `DISTINCT`. If the labels reference step labels
>     not surfaced by the traversal's projection, the step is
>     unrecognized and the whole traversal declines under D3.
>   - **`OptionalStep`** — deferred to Phase 2 (D8); any traversal
>     containing it declines under D3 in Phase 1.
>
> **How**:
> - Step ordering (provisional):
>   1. `as`-label propagation — walker reads `Step.getLabels()` and
>      pushes the label to the most recent node via
>      `MatchPatternBuilder.alias(...)`.
>   2. `DedupStep` (no labels) — set `info.distinct = true`.
>   3. `DedupStep(labels...)` — projection-then-distinct, with decline
>      semantics for labels not in the projection.
>   4. Parity tests vs SQL: labels survive through the boundary into
>      downstream `select`; dedup over (a) full row, (b) single label,
>      (c) multiple labels; traversals containing `OptionalStep` decline.
>
> **Constraints**:
> - **In-scope files**: `GremlinStepWalker`, `GremlinPatternAssembler`,
>   strategy / translator integration glue.
> - **Out of scope**: optional (Phase 2 — D8), projections (Track 7),
>   order/pagination (Track 8), aggregations (Track 9), union (Track 10).
> - Default aliases must use the translator's private prefix; do not
>   reuse `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX` (package-private
>   to the executor, and `assignDefaultAliases` does not run on this
>   path).
>
> **Interactions**:
> - Depends on Track 5 (NOT pattern emission and where-traversal alias
>   binding precede `as`-label propagation).
> - Enables Track 7 (projections reference labels declared via `as`).

---

## Track 7: Projections — `select`, `values`, `valueMap`, `elementMap`, `project`

> **What**:
> - Implement `GremlinProjectionAssembler` to populate the
>   `QueryPlanningInfo.projection` slot from the following Gremlin
>   terminal/intermediate shapes:
>   - **`SelectStep(label1, label2, ...)`** — produces a `SQLProjection`
>     whose `SQLProjectionItem`s reference `$matched.<label>` accessors.
>     `SelectOneStep(label)` is the single-label form. Output type:
>     `Map<String,Object>` for multi-label, single value for
>     single-label.
>   - **`PropertiesStep` / `PropertyMapStep` (`values(keys...)`,
>     `valueMap(keys...)`)** — projection over property names of the
>     currently-bound node. `values(name)` projects `currentAlias.name`.
>     `valueMap(name1, name2)` projects a sub-map.
>   - **`ElementMapStep`** — projection over all properties of the bound
>     element (the schema-driven map form). Mapped to `SELECT * FROM ...`
>     semantics in MATCH.
>   - **`ProjectStep(keys...).by(...)`** — composite map projection.
>     Each `by(...)` modulator is a sub-traversal evaluated in the
>     context of the current binding. Translated to one
>     `SQLProjectionItem` per key, with the modulator's value computed
>     by recursing into the sub-traversal (only if the sub-traversal is
>     a pure filter/property chain — otherwise the whole traversal
>     declines under D3).
> - Pin the boundary step output type per the traversal's terminal step:
>   `Map` for `select` / `project` / `valueMap` / `elementMap`, value
>   type for `values(singleKey)`, full record for no-projection
>   traversals. (This subsumes part of the retired Track 11.)
> - **Distinguish absent vs null-valued properties at the projection
>   layer** (design.md "Track 7 commitment"). YTDB's TP wrapper exposes
>   the two states differently (null-valued surfaces as
>   `Property.isPresent() == true`; absent surfaces as
>   `VertexProperty.empty()`), and `Result.getProperty` on the query
>   side collapses them to `null` regardless. To match native TP
>   `valueMap` / `values` / `select.by` semantics, the projection
>   layer queries the underlying entity directly via
>   `EntityImpl.hasProperty(key)` (the same primitive Track 1 uses
>   for the `IS DEFINED` / `IS NOT DEFINED` operators) and classifies:
>   absent → omit the key (for `valueMap`/`elementMap`) or drop the
>   row (for `values`); null-valued → include the key with `null`
>   value (for `valueMap`) or emit a traverser carrying `null` (for
>   `values`). Regression tests MUST pin both cases.
>
> **How**:
> - Step ordering (provisional):
>   1. `GremlinProjectionAssembler` skeleton — populates
>      `QueryPlanningInfo.projection` directly.
>   2. `SelectStep` / `SelectOneStep` — `$matched.<label>` accessors.
>   3. `values` / `valueMap` — property-name projection over current
>      alias, with `EntityImpl.hasProperty(key)` presence classification
>      (absent vs null-valued — see "What" bullet on absent-vs-null).
>   4. `elementMap` — schema-driven map / `SELECT *`, same presence
>      classification rule applied per included property.
>   5. `project(...).by(...)` — composite projection with sub-traversal
>      recursion (pure-filter-or-property only); mid-chain decline for
>      complex `by`. `by("key")` against an absent vs present-with-null
>      `key` follows the same classification rule.
>   6. Equivalence tests vs explicit SQL `RETURN`, including the
>      decline-on-unknown-label edge case. Regression tests for the
>      absent-vs-null projection commitment: vertex with `foo=null`
>      surfaces in `valueMap` with `{foo: [null]}` (or per TP's
>      cardinality wrapping); vertex with `foo` absent surfaces without
>      the `foo` key. Same fixture for `values(foo)` and for
>      `select(...).by("foo")`.
>
> **Constraints**:
> - **In-scope files**: `GremlinProjectionAssembler`,
>   `GremlinStepWalker` (terminal-step handlers), `MatchLiteralBuilder`
>   (consumed for literal coercion), strategy / translator integration
>   glue.
> - **Out of scope**: order/pagination (Track 8), aggregations (Track
>   9), union (Track 10).
> - `select` / `project` over a label that is not in the IR must
>   decline the whole traversal under D3 — no silent empty-result.
> - Output-type pinning lives in this track for projection terminals;
>   Tracks 8/9 pin their own.
>
> **Interactions**:
> - Depends on Track 6 (label propagation must be in place — projections
>   reference `as` labels).
> - Depends on Track 1's `EntityImpl.hasProperty(key)` exposure for the
>   absent-vs-null classification rule (same primitive landed for
>   `IS DEFINED` / `IS NOT DEFINED` operators — D-IS-DEFINED). No
>   separate helper needed.
> - Enables Track 8 (order/pagination is layered on top of projection).

---

## Track 8: Order + pagination — `order().by`, `limit`, `skip`, `range`

> **What**:
> - Recognize `OrderGlobalStep` and `RangeGlobalStep` plus their
>   by-modulators:
>   - **`OrderGlobalStep` with `by(key, Order.asc/desc)`** — produces a
>     `SQLOrderBy` with one entry per `by` modulator. `Order.asc` /
>     `Order.desc` map directly. `Order.shuffle` declines (no MATCH
>     equivalent; whole traversal falls back to native under D3).
>     Multiple `by(...)` modulators produce a multi-key sort.
>   - **`RangeGlobalStep(low, high)`** with `low ≥ 0`, `high > 0` —
>     `SQLSkip(low)` + `SQLLimit(high - low)`. Half-open intervals
>     handled per the convention used in MATCH.
>   - **`limit(n)`** and **`skip(n)`** are `RangeGlobalStep` variants in
>     modern TinkerPop — handled by the same path.
>
> **How**:
> - Step ordering (provisional):
>   1. `OrderGlobalStep` handler producing `SQLOrderBy`; map asc/desc;
>      decline on `Order.shuffle`.
>   2. `RangeGlobalStep` handler producing `SQLSkip` + `SQLLimit`;
>      `limit(n)` / `skip(n)` route through the same path.
>   3. Equivalence tests vs SQL `ORDER BY` / `SKIP` / `LIMIT` including
>      multi-key ordering and ordering by a property requiring projection
>      (verifies `handleProjectionsBeforeOrderBy` behavior).
>
> **Constraints**:
> - **In-scope files**: `GremlinStepWalker` (order/range handlers),
>   `GremlinProjectionAssembler` (sort-key projection plumbing), strategy
>   / translator integration glue.
> - **Out of scope**: aggregations (Track 9), union (Track 10).
> - Half-open interval semantics must match SQL `SKIP` / `LIMIT`
>   conventions exactly.
> - `Order.shuffle` is unrecognized; under D3 the whole traversal
>   declines.
>
> **Interactions**:
> - Depends on Track 7 (ordering may require projection of sort keys
>   first).
> - Enables Track 9 (aggregations layer on top of order/pagination).

---

## Track 9: Aggregations — `count`, `sum`, `min`, `max`, `mean`, `group`, `groupCount`

> **What**:
> - Recognize aggregation steps; each maps to a combination of
>   `SQLProjection` aggregate items + `SQLGroupBy`:
>   - **`CountGlobalStep`** — `RETURN count(*)` with empty group-by.
>     Output type: scalar `Long`.
>   - **`SumGlobalStep` / `MinGlobalStep` / `MaxGlobalStep` /
>     `MeanGlobalStep`** — require the prior step to be a
>     property-extraction step (`values(key)`) so we know which field to
>     aggregate. Translates to `RETURN sum(currentAlias.key)` etc. with
>     empty group-by.
>   - **`GroupStep` (= `group()`)** — without `by`, produces
>     `Map<element, list-of-elements>`; the no-by form mirrors
>     `group().by(__.identity()).by(__.fold())`. Translates to
>     `GROUP BY currentAlias` + `RETURN currentAlias, list($currentMatch)`.
>     Using `list($currentMatch)` (an element-identity accumulator)
>     rather than `collect(*)` keeps the value side a list of the
>     elements themselves, matching Gremlin's fold semantics. `collect(*)`
>     would gather all projected fields of the result row, which is a
>     different shape.
>   - **`GroupStep.by(key)`** — `GROUP BY currentAlias.key` + accumulator.
>   - **`GroupCountStep`** — `GROUP BY` + `RETURN count(*)`.
> - Pin the boundary output type to the aggregate result type (scalar
>   for count/sum/min/max/mean; `Map` for group/groupCount). (This
>   subsumes part of the retired Track 11.)
>
> **How**:
> - Step ordering (provisional):
>   1. `CountGlobalStep` — `RETURN count(*)` with empty group-by; pin
>      boundary output to scalar `Long`.
>   2. `SumGlobalStep` / `MinGlobalStep` / `MaxGlobalStep` /
>      `MeanGlobalStep` — coupled with the preceding `values(key)`;
>      decline if no property-extraction precedes them.
>   3. `GroupStep` (no `by`, with `by(key)`) — `GROUP BY` +
>      `list($currentMatch)` / aggregate accumulator; pin boundary
>      output to `Map`.
>   4. `GroupCountStep` — `GROUP BY` + `RETURN count(*)`.
>   5. Aggregate equivalence tests vs SQL; group-by equivalence;
>      empty-result handling (count of empty match → 0, not absence).
>
> **Constraints**:
> - **In-scope files**: `GremlinStepWalker` (aggregate handlers),
>   `GremlinProjectionAssembler`, strategy / translator integration glue.
> - **Out of scope**: union (Track 10).
> - Aggregation steps that are not preceded by the appropriate property-
>   extraction step must decline the whole traversal under D3.
> - Empty-result semantics must match SQL exactly (count of empty match
>   returns 0, not an empty stream).
>
> **Interactions**:
> - Depends on Track 8 (order/pagination must be in place; ordering may
>   compose with grouping).
> - Enables Track 10 (union of aggregating sub-traversals must
>   type-check on the aggregate result type).

---

## Track 10: Union — concatenation of multiple translated patterns

> **What**:
> - Handle `UnionStep` with N global child traversals.
> - Each child sub-traversal is independently inspected. If all children
>   are fully translatable as standalone pattern matches that produce the
>   same boundary output type, the strategy translates each child to its
>   own `SelectExecutionPlan` and the boundary step **concatenates** their
>   result streams (Gremlin union semantics).
> - Implementation: a `MultiPlanMatchStep` variant of `YTDBMatchPlanStep`
>   that holds N plans and iterates them in order. If any child fails to
>   translate fully, the `UnionStep` is unrecognized and under D3
>   all-or-nothing the entire enclosing traversal declines (D8).
> - Type-check before concatenation: all children must agree on output
>   type. `union(__.values("a"), __.out("knows"))` mixes `String` and
>   `Vertex` — types diverge — decline.
>
> **How**:
> - Step ordering (provisional):
>   1. `UnionStep` handler — recursively translate each child to its own
>      `MatchPlanInputs` / `SelectExecutionPlan`.
>   2. `MultiPlanMatchStep` — sibling of `YTDBMatchPlanStep` that holds
>      N plans and concatenates their result streams.
>   3. Output-type compatibility check across children; decline on
>      divergent types or on any child that fails to translate;
>      fallback-stays-native test.
>
> **Constraints**:
> - **In-scope files**: `GremlinStepWalker` (UnionStep handler),
>   `MultiPlanMatchStep` (new, sibling of `YTDBMatchPlanStep`), strategy
>   / translator integration glue.
> - **Out of scope**: Cucumber green + perf baseline (Track 12).
> - Children with divergent boundary output types must decline; do not
>   coerce.
> - Children that contain any unrecognized step must decline the whole
>   enclosing traversal under D3.
>
> **Interactions**:
> - Depends on Track 9 (children may be aggregating sub-traversals; the
>   type-check needs the aggregate output types pinned by Track 9).
> - Enables Track 12 (Cucumber + LDBC measurement runs against the full
>   recognized set including union).

---

## Track 12: Cucumber green + perf baseline

> **What**:
> - Run the full TinkerPop Cucumber feature suite (`YTDBGraphFeatureTest`
>   in `core` and `EmbeddedGraphFeatureTest` in `embedded`) end-to-end
>   with the strategy registered. All scenarios that passed before must
>   pass after; investigate and fix any new failure with track-level
>   review.
> - Document, per spec category, the test scenarios that exercise each
>   translated step. This is a regression-prevention catalogue, not a
>   marketing list.
> - Run the existing SQL LDBC SNB JMH benchmarks (`jmh-ldbc/`) on
>   `develop` (HEAD before branch) and the branch HEAD as a sanity check
>   that the additive `MatchPlanInputs` ctor does not regress the SQL
>   `MATCH` path. Capture the delta in a benchmark report on the branch
>   (worktree only — do not commit).
> - **Build a Gremlin-equivalent JMH benchmark suite** under `jmh-ldbc/`:
>   port each LDBC SNB read query (IS1–IS7, IC1–IC13) to its Gremlin
>   form, mirroring the existing SQL benchmark structure
>   (`LdbcSingleThreadICBenchmark`, `LdbcMultiThreadICBenchmark`,
>   `LdbcSingleThreadISUltraFastBenchmark`, `LdbcMultiThreadISBenchmark`,
>   `LdbcSingleThreadICSlowBenchmark`, `LdbcSingleThreadICUltraSlowBenchmark`).
>   Gremlin variants get a `Gremlin` suffix and reuse `LdbcBenchmarkState`
>   (which already exposes `YTDBGraphTraversalSource`). Each benchmark
>   method exists in two flavours — translator-off (native TinkerPop
>   pipeline) and translator-on (translated through the strategy) —
>   gated by setting `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` per-fork.
> - Compare per-query mean times across **three modes** (SQL baseline,
>   Gremlin native, Gremlin translated) in the benchmark report. The
>   Gremlin-on vs. Gremlin-off delta is the load-bearing measurement of
>   the translator's value; the SQL row provides an absolute reference
>   point ("how close does Gremlin-translated get to native SQL?"). The
>   aim is **a measured number, not a pass/fail gate** — cache is on per
>   D5, so the measured delta is the steady-state regression budget the
>   Phase 1 implementation must fit inside.
> - If the Gremlin on/off comparison shows large wins, document which
>   queries benefited and why (likely candidates: queries with multi-hop
>   patterns where MATCH's cost-based scheduler picks better starting
>   points than TinkerPop's left-to-right execution).
> - If the comparison shows acceptable performance overall, finalize.
> - If the comparison shows unacceptable regressions
>   (Gremlin-translated slower than Gremlin-native on representative
>   queries), ESCALATE: the cache key may be missing a discriminator,
>   or `MatchExecutionPlanner` may be hitting a planner-side path that
>   the translator's IR shape exercises differently than SQL `MATCH`.
>
> **How**:
> - Step ordering (provisional):
>   1. Full Cucumber re-run for both `YTDBGraphFeatureTest` and
>      `EmbeddedGraphFeatureTest`; investigate and fix any new failure.
>   2. Per-spec-category scenario catalogue mapping translated steps to
>      the scenarios that exercise them.
>   3. SQL LDBC SNB JMH baseline (`develop` vs branch HEAD) on the
>      single-threaded and multi-threaded suites; capture per-query
>      mean-time deltas in a worktree-only report.
>   4. Port LDBC queries to Gremlin alongside their SQL benchmarks. Each
>      query that maps onto the recognized set gets a Gremlin
>      benchmark; queries that hit unrecognized steps (e.g. those
>      requiring `repeat()` or `path()`) are documented as
>      "decline-only" and run natively only. Run the Gremlin suite
>      twice (translator off / on) on the branch HEAD; capture the
>      three-mode comparison.
> - Under D3 all-or-nothing, the Cucumber pass rate measures the
>   strategy's recognize-and-decline correctness (every traversal whose
>   step set isn't fully recognized must run natively unmodified); the
>   Gremlin on/off benchmark delta measures the strategy's
>   recognize-and-translate value on the subset that does fully translate.
> - JMH invocation per project conventions: use
>   `-f 3 -wi 3 -w 10s -i 10 -r 30s` (or the project's standard
>   comparison parameters), not minimal params.
> - The Gremlin benchmark suite uses `LdbcBenchmarkState`'s existing
>   `YTDBGraphTraversalSource` accessor (`graph.traversal()`); the
>   parameter curator (`ParameterCurator`) is shared across SQL and
>   Gremlin variants — same fixture parameters for both modes ensures
>   the comparison is apples-to-apples. The translator kill-switch is
>   set at JMH `@Setup(Level.Trial)` so each fork runs in one mode
>   only; the report concatenates fork outputs into a single comparison
>   table.
>
> **Constraints**:
> - **In-scope files**: new Gremlin benchmark classes under
>   `jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/ldbc/`
>   (one per single/multi × IS/IC × fast/slow combination, mirroring
>   the SQL set); scenario catalogue (worktree document, do not commit
>   per project conventions for design/plan docs); benchmark report
>   (worktree-only).
> - **Out of scope**: Phase 2 cache implementation (only ESCALATE if the
>   Gremlin-on / Gremlin-native delta forces it). Adding LDBC queries
>   that don't exist in the SQL suite (we mirror the existing 20 read
>   queries — IS1–IS7, IC1–IC13).
> - Cucumber suite test count must not decrease (count after Phase 1 ≥
>   count before Phase 1; PR title carries `[no-test-number-check]`
>   only if intentional test consolidation occurred).
> - LDBC report is informational, not a pass/fail gate.
> - Gremlin benchmarks use the same dataset as SQL (LDBC SF 1 via the
>   existing dump artifact), so per-fork dataset preparation is shared.
>
> **Interactions**:
> - Depends on Track 10 (the recognized set must be complete; Track 11
>   is retired as `[~]` under D3 all-or-nothing).
> - Enables nothing downstream — this is the final hardening track for
>   Phase 1.
