# Research Log — S1: Migrate FilterStep + ExpandStep to AnalyzedExpr

## Initial request

Implement **YTDB-916** (S1 — Migrate FilterStep + ExpandStep predicates to
`AnalyzedExpr`), the first executor slice of the umbrella **YTDB-901**
(Separate AST from logical IR in the query engine). The user gave the aim by
pointing at the two issues; the verbatim scope below is transcribed from them.

**Goal.** First executor sites consume `AnalyzedExpr` instead of
`SQLWhereClause`, validating the S0 substrate (YTDB-915, landed as commit
`87cbfc0b6d`) at scale.

**Scope (YTDB-916).**
- Extend S0 lowering with `SQLWhereClause → AnalyzedExpr`.
- `FilterStep` constructor takes `AnalyzedExpr`. Old constructor preserved
  during transition behind a deprecation note, then removed.
- `ExpandStep.pushDownFilter` becomes `AnalyzedExpr`.
- Planner sites updated: `DeleteEdgeExecutionPlanner.handleWhere`;
  `SelectExecutionPlanner.handleWhere`,
  `handleClassAsTargetWithIndexedFunction`, `executionStepFromIndexes`,
  `createParallelIndexFetch`, `tryPushDownFilterIntoExpand`, `handleExpand`.
- `matchesFilters` shim removed from both step classes once they consume the
  analyzed form.

**Acceptance.**
- All SELECT and DELETE unit tests green; LDBC JMH suite neutral on CCX33.
- `FilterStep.serialize` / `deserialize` round-trip through `AnalyzedExpr`.
- Per YTDB-901's per-child requirement: add a targeted JMH microbenchmark
  exercising the eval path(s) this slice touches, and name which path it
  covers, in addition to the LDBC SF1 neutrality gate.

**Non-goals.**
- Other `SQLWhereClause` sites: `UpsertStep`, `MaterializedLetGroupStep` (S9),
  `InvertedWhileHashJoinStep` (S13).
- Port `flatten` / `extractSubQueries` (S3, S4).

**Two obligation comments on YTDB-916 (carried into this slice).**
1. **Evaluator fast paths.** S0 ships slow-path-only on purpose. S1 must add
   two AST evaluation behaviors the analyzed evaluator lacks:
   (a) in-place comparison fast path — `SQLBinaryCondition.tryInPlaceComparison`
   compares an RHS constant against the entity's serialized form
   (`EntityImpl.isPropertyEqualTo` / `comparePropertyTo`), skipping property
   deserialization for `property <op> constant`; parity-equivalent (returns
   FALLBACK on any collation/coercion risk), guarded (left = single base
   identifier, right = early-calculated, row = `EntityImpl`);
   (b) boolean AND/OR short-circuit — S0's IR has no boolean AND/OR (compound
   predicates throw `UnsupportedAnalyzedNodeException`); WHERE is mostly AND/OR,
   so the analyzed evaluator must short-circuit like `SQLAndBlock` /
   `SQLOrBlock`. This is correctness too: eager eval of a later operand can
   throw where the AST short-circuits past it.
2. **Collation convergence (observable behavior change).**
   `SQLBinaryCondition.evaluate(Result, ctx)` applies the collate transform;
   `evaluate(Identifiable, ctx)` deliberately skips it. The analyzed layer
   exposes a single `Result`-shaped evaluator that always applies collation, so
   migrating a WHERE caller from the `Identifiable` overload makes a
   `ci`-collated `name = 'Foo'` start matching `'foo'`. `evaluate(Identifiable)`
   has ~12 production callers; `SQLWhereClause` is this slice, `SecurityEngine`
   is S7 (YTDB-922). Ask of S1: confirm the change is intended for every
   WHERE/`Identifiable` caller migrated, with test coverage on a `ci`-collated
   comparison; do not silently inherit the collation skip.

## Decision Log

### D1: Fold bind-parameter lowering into S1 (2026-07-01) [ctx=safe]
S1 lowers bind parameters (`?` / `:name`) instead of inheriting S0's throw. A
new `Param` IR variant carries the parameter's identity (the recommended shape:
a nullable `name` plus `paramNumber`), and the evaluator resolves it at eval
time by the same raw lookup the AST uses.
- **Why:** Bind params are the single dominant lowering blocker on realistic
  queries (LDBC: 37/64 WHEREs touch a bind param; 4 of 9 top-level FilterStep
  WHEREs are bind-param-blocked *alone*). Folding them in moves the lowerable
  top-level LDBC fraction from ~1/9 to ~5/9 — the difference between an analyzed
  FilterStep path that is near-dead on production (all parameterized) queries
  and one that actually runs. The cost is contained: S0 already built the
  `AnalyzedAstAccess.inputParam` read-seam, resolution is
  `getValue(ctx.getInputParameters())`, the evaluator already holds `ctx`, and
  the `tryInPlaceComparison` fast path wants an early-calculated RHS (a resolved
  param is exactly that). One new sealed variant + one lowering arm + one
  evaluator arm + tests — nothing like the subquery/multi-segment machinery.
- **Alternatives rejected:** (a) keep S0's deferral and rely on the fallback —
  rejected: leaves S1's runtime footprint near-zero on real queries, so the
  targeted JMH benchmark and the whole "validate at scale" goal rest on
  synthetic WHEREs only; (b) do bind params as a separate S1.5 follow-up —
  viable, but it is the highest-payoff-per-effort item and the fast path is
  cleaner to land with it than to retrofit. Deferred to the user only as a
  sequencing call; the user chose to fold it in.
- **Scope note:** this expands YTDB-916 beyond its written scope (which
  inherited S0's bind-param deferral). Warrants a note on YTDB-916.
- **Risks/Caveats:** must mirror the named-param name-first-then-number fallback
  exactly (drift surface, pinned by a parity test); does NOT remove the fallback
  — multi-segment paths, `@rid`, context vars, subqueries, `MATCHES` stay
  un-lowerable. Interacts with the still-open `Param` IR-shape and serialization
  questions.
- **Implemented in:** (planning-time; carried to the S1 plan).

### D2 (parity fact, not yet a choice): AST resolves a comparison-RHS bind param to the raw bound value (2026-07-01) [ctx=safe]
`SQLBinaryCondition.evaluate(Result)` → `right.execute(Result, ctx)` →
`SQLBaseExpression.execute` (line 185-186) → `inputParam.getValue(params)`.
`getValue` returns `params.get(key)` raw (named: name-first then number
fallback; positional: number). `toParsedTree` coercion is confined to
`bindFromInputParams`, a distinct sub-expression-position path never taken by a
scalar comparison. So the `Param` evaluator arm resolves by the same raw lookup;
no coercion to replicate.

### D3: Fallback = planner-level split (Option B), single-class FilterStep (2026-07-01) [ctx=safe]
When a WHERE cannot be lowered, S1 falls back to the AST via a planner-level
split, not a clean cutover. A single `filterStepFor(SQLBooleanExpression, ctx,
timeout, profiling)` factory attempts the lowering and returns an analyzed
`FilterStep` on success or the legacy `SQLWhereClause`-based step on
`UnsupportedAnalyzedNodeException`. `FilterStep` holds *either* an `AnalyzedExpr`
*or* a `SQLWhereClause` (never both); `filterMap` branches on which is set.
`ExpandStep.pushDownFilter` gets the same treatment.
- **Why:** the S0 subset (even with AND/OR + bind params + Group-1) cannot cover
  a real WHERE — multi-segment paths, `@rid`, context vars, subqueries, and
  `MATCHES` stay out of subset and are later-slice work — so a pure-`AnalyzedExpr`
  FilterStep is impossible in S1. B centralizes cleanly: `createWhereFrom(...)`
  is the existing seam, so ~10 `new FilterStep`/`createWhereFrom` sites collapse
  to `filterStepFor(...)`. FilterStep filters the index-*residual*
  (`IndexSearchDescriptor.getRemainingCondition()`), which skews toward the
  leftover simple comparisons/AND-blocks S1 lowers well. Single-class satisfies
  "FilterStep constructor takes `AnalyzedExpr`" while keeping the fallback in one
  class.
- **Alternatives rejected:** (A) dual-carry — FilterStep always holds the AST
  even when analyzed; no less coupling than B and carries dead state; (C)
  escape-hatch IR node wrapping an un-lowered `SQLBooleanExpression` — the only
  option that literally removes the AST from FilterStep, but re-introduces
  AST-inside-IR coupling (the exact thing YTDB-901 removes) and dents the S0
  sealed-variant / I3 cleanliness; (D) widen lowering — not a fallback; LDBC
  shows un-lowered cases are operand-blocked, not condition-type-blocked, so
  widening barely moves the rate.
- **Consequence (acceptance amended):** YTDB-916's "old constructor preserved
  then removed" and "`matchesFilters` shim removed from both step classes" are
  **superseded** — B needs the AST path as the fallback, so the `SQLWhereClause`
  constructor and `matchesFilters` are retained as the documented fallback,
  marked for retirement as later slices widen lowering. Noted on YTDB-916.
- **Risks/Caveats:** two evaluation paths (analyzed vs AST) must stay
  result-equivalent on the predicates both can run; the factory must cover both
  the `createWhereFrom` sites and the two direct-`SQLWhereClause` sites
  (`info.whereClause` at ~1992, `outerWhere` at ~3470) plus DeleteEdge; serialize
  must tag which form the step carries. True fallback *rate* needs a runtime
  probe (FilterStep sees residuals, not source WHEREs).
- **Implemented in:** (planning-time; carried to the S1 plan).

### D4: AND/OR IR representation — extend `BinaryOperator`, lazy short-circuit (2026-07-01) [ctx=safe]
Boolean `AND`/`OR` are added as `BinaryOperator` enum constants (not a dedicated
boolean node). The lowerer recurses `SQLAndBlock`/`SQLOrBlock` and left-folds
each block's `subBlocks` into nested `BinaryOp(AND/OR, …)`. The evaluator's
`visitBinaryOp` gains a `case AND, OR` arm that evaluates **lazily**: dispatch
the left operand, cast to `Boolean`, and for `AND` return `false` without
dispatching the right when left is false (mirror for `OR`).
- **Why:** consistent with the IR already modeling `NOT` as a `UnaryOperator`
  (an op, not a `Not` node) — `AND`/`OR` as ops is symmetric. No new sealed
  variant (no I3 break across the transform framework), and extending the enum
  makes the exhaustive `switch (binaryOp.op())` in `visitBinaryOp` fail to
  compile until the arm is added (an operator-level I3 analog). Lowering is a
  plain structural recursion — the parser already nests `AND` inside `OR`
  correctly, so no precedence fold. Left-fold preserves list order:
  `[a,b,c]` → `BinaryOp(AND, BinaryOp(AND, a, b), c)` evaluates a, b, c in order,
  matching `SQLAndBlock`'s iteration.
- **Lazy-eval is a HARD requirement, not an optimization.** The `AND`/`OR` arm
  must NOT reuse the eager "evaluate both operands then combine" shape that
  `evaluateArithmetic` / `evaluateComparison` use. Short-circuit is the
  correctness obligation from YTDB-916 — eager evaluation of a later operand can
  throw where the AST short-circuits past it (`SQLAndBlock`/`SQLOrBlock` stop at
  the first decisive sub-block).
- **Edge parity:** a 1-element block lowers to its single element (no wrapper);
  an empty `AndBlock` → `Const(true)`, empty `OrBlock` → `Const(false)`, matching
  the AST's vacuous semantics. Operands cast to `Boolean` as `NOT` does; a
  non-Boolean operand is a lowering-contract violation (ClassCastException, not
  truthiness coercion).
- **Alternatives rejected:** dedicated n-ary boolean node (`BoolOp(op, List)`) —
  maps directly to the later optimizer passes that manipulate conjunct/disjunct
  lists (S4 `FlattenPass`, S12 `MergeUsingAndPass`), but costs a new sealed
  variant now and breaks the `NOT`-as-op consistency; the list-manipulation cost
  is minor and mitigable (flatten normalizes arbitrary boolean structure anyway;
  collecting conjuncts from a left-fold is a trivial helper), so it does not
  outweigh Option 1's simplicity for S1's evaluation-only need.
- **Risks/Caveats:** if S4/S12 turn out to strongly prefer n-ary, switching is a
  contained-but-real refactor (new variant + re-do lowerer fold + evaluator arm
  + any pass); flagged for those slices to revisit.
- **Implemented in:** (planning-time; carried to the S1 plan).

### D5: Serialization — minimal source-WHERE bridge for S1; vestigial plan-serialization removal deferred to a new cleanup slice (2026-07-01) [ctx=safe]
S1's analyzed `FilterStep`/`ExpandStep` satisfy the YTDB-916 serialize/deserialize
round-trip with a **minimal bridge**: the step serializes the **source WHERE** it
was lowered from (reusing the existing `SQLWhereClause` structured serialize) plus
a tag recording it carried the analyzed form; `deserialize` reconstructs the WHERE
and re-runs the D3 `filterStepFor(...)` factory, which re-lowers to the analyzed
step (or the fallback). No IR wire format is added.
- **Why:** the execution-plan/step serialize/deserialize round-trip is dead
  production code (see Surprises: `InternalExecutionPlan` defaults throw, zero
  production callers via PSI + whole-repo sweep, EXPLAIN uses `toResult`/
  `prettyPrint`, the plan cache reuses live objects via `copy(ctx)`, `server`/
  `driver` ship no plans, fork-base vestige). Building a clean IR wire format
  invests in a path nothing consumes; S16/S17 widen IR lowering/evaluation, not
  serialization. The bridge reuses `SQLWhereClause.serialize` (already structured
  and param-by-reference via `SQLInputParameter`) and the D3 factory, so it adds
  almost no code and keeps the round-trip test green through the transition.
- **Alternatives rejected:** (A) add `AnalyzedExpr` serialize/deserialize + IR
  toString — over-investment in a dead path with no consumer; (C) narrow removal
  of serialize from `FilterStep`+`ExpandStep` now + amend YTDB-916 acceptance —
  in-charter but leaves sibling steps' dead serialize intact and makes any plan
  holding a `FilterStep` un-round-trippable (only tests hit it, but it introduces
  an inconsistency); the honest fix is the repo-wide removal below, not a per-step
  amputation.
- **Companion action (filed as YTDB-1185):** a separate cleanup issue to remove the
  vestigial execution-plan serialize/deserialize repo-wide (all
  `ExecutionStepInternal` subclasses + `SelectExecutionPlan`/`ExecutionStepInternal`/
  `InternalExecutionPlan` + their round-trip tests), linked to YTDB-901 and
  YTDB-916, dependency-noting it lets S15 (YTDB-930) drop the round-trip acceptance.
  Mirrors the S16/S17 handling of the umbrella gap. Once it lands, S1's bridge is
  deleted with the rest of the machinery.
- **Risks/Caveats:** the bridge means the analyzed step retains the source
  predicate (as text or structured form) for serialize + `prettyPrint` (EXPLAIN)
  rendering only — a display/serialize-scoped retention, NOT the D3-rejected
  dual-carry evaluation coupling (evaluation runs off the `AnalyzedExpr`). The
  plan picks text-vs-structured and keeps the retention display/serialize-scoped,
  removing it with the cleanup slice. Re-lowering on deserialize relies on
  lowering determinism (holds — pure structural recursion).
- **Implemented in:** (planning-time; carried to the S1 plan + the new cleanup slice).

## Surprises & Discoveries

- 2026-07-01 [ctx=safe] The S0 lowering subset is tiny relative to a real WHERE
  clause. The lowerer covers single-segment comparisons, `+ - * /`, `NOT`,
  method-call `FuncCall`, and parenthesis grouping; it throws
  `UnsupportedAnalyzedNodeException` on everything else. A real WHERE can hold
  any of 19 `SQLBooleanExpression` subclasses — `SQLAndBlock`, `SQLOrBlock`,
  `SQLInCondition`/`SQLNotInCondition`, `SQLBetweenCondition`, `SQLContains*`
  (5 variants), `SQLInstanceofCondition`, `SQLIs(Not)NullCondition`,
  `SQLIs(Not)DefinedCondition`, `SQLMatchesCondition`, `SQLParenthesisBlock` —
  plus multi-segment paths (`p.name`, D6-R throws), bind parameters (throw),
  subqueries (throw), and `levelZero` shapes incl. `any()`/`all()` (D18 throws).
  So a clean cutover of `FilterStep` to a bare `AnalyzedExpr` is not possible
  without a fallback for the un-lowerable majority. This is the central design
  question (see Open Questions).
- 2026-07-01 [ctx=safe] The `BinaryOperator` IR enum has no boolean AND/OR
  (`PLUS, MINUS, STAR, SLASH, EQ, NE, LT, LE, GT, GE`). S1's `SQLWhereClause`
  lowering must add boolean AND/OR to the IR and short-circuit them in the
  evaluator (S0 design D16; the AND/OR short-circuit obligation was explicitly
  deferred to "the first slice with a live consumer", which is this one).
- 2026-07-01 [ctx=safe] Collation-convergence likely does NOT bite S1's exact
  scope. `FilterStep.filterMap` and `ExpandStep`'s push-down filter both call
  `matchesFilters(Result, ctx)` → `SQLBooleanExpression.evaluate(Result, ctx)`,
  the overload that ALREADY applies collation. The collation-skipping
  `evaluate(Identifiable)` overload is reached by the `matchesFilters(Identifiable)`
  callers — MATCH-path (`MatchMultiEdgeTraverser`, `SQLMatchPathItem`,
  `MatchEdgeTraverser`) and `DeltaBuilder` — none of which are in S1's scope
  (FilterStep + ExpandStep + SELECT/DELETE planner). So migrating FilterStep to
  the analyzed evaluator (which applies collation) preserves its current
  behavior; the observable case-insensitive shift the YTDB-916 comment warns of
  targets the Identifiable-path callers deferred to S7 (SecurityEngine) and
  later. S1 should still add a `ci`-collated regression test to lock parity.
  NEEDS PSI/code confirmation that `evaluate(Result)` on `SQLBinaryCondition`
  applies collate and that no S1-scoped site uses the Identifiable overload.

- 2026-07-01 [ctx=safe] **Umbrella coverage of the deferred predicate shapes —
  partial, with a real gap.** Surveyed all 16 YTDB-901 children (S0–S15). The
  deferred shapes S1 punts map as: multi-segment paths / identifier resolution →
  **S10 (YTDB-925)** (`SQLBaseIdentifier`/`SQLSuffixIdentifier` → `Var`,
  range-table binding); subqueries → **S3 (YTDB-918)** (extractSubQueries pass);
  flatten (DNF) + Lucene text operator → **S4 (YTDB-919)**. But the **boolean
  condition families are unassigned**: no child issue charters lowering `IN` /
  `NOT IN`, `BETWEEN`, the five `CONTAINS*`, `INSTANCEOF`, the four IS-family
  (`IS [NOT] NULL/DEFINED`), or `MATCHES` into IR nodes + lowerer arms +
  evaluator arms. The umbrella is organized by executor-site migration (S2,
  S5–S9, S13) and optimizer-rewrite port (S3, S4, S12, S14) plus identifier
  resolution (S10) — none is "widen the analyzed expression's boolean-predicate
  coverage." Also unclear-owner: `@rid` / `@this` / context vars (`$parent`,
  `$current`, `$matched`, `$depth`) and top-level function calls incl.
  `any()`/`all()` — S10 is column/class identifier resolution, which does not
  obviously include self/context references.
  **Consequence:** S15 (YTDB-930, cleanup) explicitly removes
  `SQLWhereClause.matchesFilters` and "the per-condition polymorphic evaluation
  overrides (`SQLContainsCondition`, `SQLInOperator`, etc.)" and requires "no
  references to deleted AST evaluation methods remain," and S12 (YTDB-927,
  index-aware) ports `isIndexAware`/`findIndex` over conditions like `IN` /
  `BETWEEN` — both *depend* on those condition families being lowered, but no
  slice *produces* that lowering. So the FilterStep AST fallback (D3) has **no
  chartered path to retirement**: absent a new slice, `IN`/`BETWEEN`/`CONTAINS`/
  `IS`/`MATCHES` WHERE predicates run the fallback forever, and S15 cannot delete
  the AST evaluators. The D3 / YTDB-916-note framing "retired as later slices
  widen lowering" is currently unbacked by any issue.
- 2026-07-01 [ctx=safe] S1's own deferral of the condition families is
  legitimate — the issue's scope bullets never enumerated condition-type
  lowering, and S0 built only comparison + arithmetic operators. The gap is at
  the *umbrella* level, not S1's.
- 2026-07-01 [ctx=safe] **Collation-convergence confirmed by PSI + code — S1's
  collation work is a regression test, not a behavior change.** Four facts. (1)
  `SQLBinaryCondition.evaluate(Result)` applies collate
  (`SQLBinaryCondition.java:101-108`: fetch left-then-right collate, transform
  both operands); `evaluate(Identifiable)` does not (line 46 comment "the
  existing overload never applies collation", no collate code). (2) PSI
  find-usages over `evaluate(Identifiable)` + all overrides (23 override
  targets) + `SQLWhereClause.matchesFilters(Identifiable)` returns 16 distinct
  call sites — **none** in `FilterStep`, `ExpandStep`, `SelectExecutionPlanner`,
  or `DeleteEdgeExecutionPlanner`. The non-AST-internal top-level `Identifiable`
  entry points are `SecurityEngine` (S7/YTDB-922), `DeltaBuilder`, and
  `SQLMatchPathItem` (MATCH); the rest are AST-internal tree-walk recursion
  (`SQLAndBlock`/`SQLOrBlock`/`SQLNotBlock`/`SQLParenthesisBlock`/`SQLContains*`/
  `SQLCaseExpression`/`SQLExpression`/`SQLWhereClause`) that inherits whichever
  overload the top-level entry chose. (3) `FilterStep.filterMap`
  (`FilterStep.java:72`) and `ExpandStep` push-down (`ExpandStep.java:134`) both
  enter via `matchesFilters(Result)` → `evaluate(Result)`, so their whole
  tree-walk stays on the collation-applying path. (4) The S0 analyzed evaluator
  applies the same collate: `AnalyzedExprEvaluator.evaluateComparison`
  (lines 225-235) fetches left-then-right collate and transforms both, mirroring
  the `Result` path. So migrating `FilterStep`/`ExpandStep` to the analyzed
  evaluator preserves collation; S1 adds a `ci`-collated regression test to lock
  parity, and the observable case-insensitive shift the YTDB-916 comment warns of
  belongs to the `Identifiable`-path callers (S7+), not S1. Closes the "NEEDS
  PSI/code confirmation" caveat on the earlier collation entry.
- 2026-07-01 [ctx=safe] **The AST in-place comparison fast path already exists
  (YTDB-628, commit `a8d7204611`), so obligation #1(a) is a port, not a
  from-scratch build.** `SQLBinaryCondition` already carries
  `tryInPlaceComparison` (both the `Result` and `Identifiable` overloads use it),
  backed by `EntityImpl.isPropertyEqualTo` / `comparePropertyTo` and
  `InPlaceResult`. The S0 analyzed evaluator is slow-path-only by design
  (`AnalyzedExprEvaluator` class comment line 47; `evaluateComparison` evaluates
  both operands then compares, no in-place branch). S1's obligation #1(a) reuses
  the existing `EntityImpl` in-place primitives from the analyzed evaluator's
  comparison arm — guarded on left = single base identifier (a `Var`), right =
  early-calculated constant, row = `EntityImpl`, FALLBACK on any
  collation/coercion risk. Load-bearing for the "LDBC JMH neutral" acceptance:
  without it the analyzed `FilterStep` deserializes where the AST `FilterStep`
  did in-place comparison — a per-record hot-path regression.
- 2026-07-01 [ctx=safe] **Plan-cache reuse model: live objects + copy-per-execution,
  so the `Param`-by-reference constraint is a live-object concern (satisfied by D1),
  not a serialization one.** `YqlExecutionPlanCache` is a Guava
  `Cache<String, InternalExecutionPlan>` keyed by statement text
  (`YqlExecutionPlanCache.java:26`). It stores **live plan objects**, not
  serialized bytes: `put` caches `internal.copy(ctx)` (line 104) and `get`
  returns `result.copy(ctx)` (line 137), the prepared-statement model where the
  same statement text reuses the cached plan across executions with different
  bind values. So the hot reuse path is `FilterStep.copy(ctx)`
  (`FilterStep.java:120`), not serialize. Correctness requirement: the cached
  step must hold the param *identity*, so each execution's `.copy(ctx)`
  re-resolves against that execution's `ctx.getInputParameters()`. D1's `Param`
  (name + number, resolves at eval time) satisfies this exactly, mirroring the
  AST's `SQLInputParameter`. `AnalyzedExpr` is a sealed set of **immutable
  records** (`AnalyzedExpr.java:26` — `Var`/`Const`/`BinaryOp`/`UnaryOp`/
  `FuncCall`; `Param` would be a 6th), so the analyzed `FilterStep`'s copy can
  **share** the IR reference with no value-baking risk — cleaner than the mutable
  AST's deep `whereClause.copy()`.
- 2026-07-01 [ctx=safe] **Execution-plan serialize/deserialize has no current
  production consumer — it is test-and-plumbing only.** PSI find-usages:
  `SelectExecutionPlan.serialize` has **0** project call sites;
  `ExecutionStepInternal.serialize`/`deserialize` (11 override targets each) are
  called only by tests (`*StepTest`, incl. `FilterStepTest`), the internal
  sub-plan walk (`SelectExecutionPlan`, `ExecutionStepInternal` self-recursion),
  and one step-internal deserialize (`FetchFromIndexStep`). No plan-cache
  persistence, distributed-execution, or remote path serializes a full plan (the
  cache stores live objects, above). So the YTDB-916 acceptance "`FilterStep`
  serialize/deserialize round-trip through `AnalyzedExpr`" is a
  self-consistency + test-green requirement, not a hot-path wire format — the
  serialization design choice is not perf-constrained.
- 2026-07-01 [ctx=safe] `AnalyzedExpr` has **no wire format today** (S0 shipped
  none — grep: no `serialize` in the `analyzed` package). `SQLWhereClause`
  already has a structured recursive serialize (`SQLWhereClause.java:557` →
  `baseExpression.serialize`), and `SQLInputParameter.serialize`
  (`SQLInputParameter.java:184`) emits the param's own identity fields, never a
  resolved value — so serializing the source WHERE is inherently param-by-reference.
- 2026-07-01 [ctx=safe] **Confirmed definitively: the execution-plan/step
  serialize/deserialize round-trip is dead production code (vestigial from the
  OrientDB fork's distributed-query era).** Full evidence: (1)
  `InternalExecutionPlan.serialize`/`deserialize` default-throw
  `UnsupportedOperationException` (`InternalExecutionPlan.java:65-72`); (2) PSI +
  whole-repo (`core`/`server`/`embedded`/`driver`) sweep finds **no** production
  caller of a top-level plan `serialize` — only the internal recursive walk
  (`ExecutionStepInternal.java:208`, `SelectExecutionPlan`) that a top-level
  serialize would drive, plus steps serializing their own sub-fields; (3) EXPLAIN
  uses `toResult` + `prettyPrint` (`ExplainResultSet.java:56-57`), not serialize;
  (4) the plan cache stores live objects reused via `copy(ctx)` (above); (5) the
  remote `driver` and `server` modules have no plan-serialize/deserialize
  references — no distributed plan shipping; (6) git traces the methods to the
  package-rename fork-base commit, not a live feature; `SingleOpServerExecutionPlan`
  does not even override them. Only `*StepTest` classes exercise the round-trip.
  **Consequence for open question 2:** the "build a clean IR wire format" option
  (A) is over-investment — S16/S17 widen IR lowering/evaluation, not a wire
  format, and nothing consumes one. Removal is legitimate but cross-cutting
  (~11+ step classes + plan classes + their round-trip tests) and out of S1's
  "migrate FilterStep+ExpandStep" charter; it belongs in a separate umbrella
  cleanup issue (mirrors the S16/S17 handling of the coverage gap), which would
  also let S15/cleanup drop the round-trip acceptance. Decision pending user steer
  (minimal source-WHERE bridge for S1 + file cleanup issue, vs. narrow
  FilterStep/ExpandStep serialize removal in S1 + amend acceptance).
- 2026-07-01 [ctx=safe] **Strategic payoff of the umbrella (out of S1 scope, filed
  as YTDB-1186): immutable IR enables share-not-copy plan caching.** The plan cache
  (`YqlExecutionPlanCache`) reuses live plans via `copy(ctx)`, which deep-copies the
  predicate subtree inside each step (`FilterStep.copy` → `whereClause.copy()` →
  recursive `SQLBooleanExpression.copy` + the `flattened` memo) because the AST is
  mutable. `AnalyzedExpr` is immutable records with params resolved at eval time, so
  an IR-carrying step's `copy` can **share** the IR reference — eliminating the
  predicate deep-copy on every cache hit and removing the AST's lazy re-derivation.
  This is NOT automatic: the slices deliver the enabling condition (immutable IR in
  steps), but no slice charters reworking the cache/`copy` semantics — it is
  orthogonal to "separate AST from IR." Precondition: the AST fallback must be
  retired (S15/S16/S17), else the cache keeps the deep-copy path alive. Split into
  two sizes: **(A)** share-not-copy predicates + IR-canonical cache form — filed as
  **YTDB-1186** (depends on YTDB-901; relates to YTDB-916); **(B)** the blueprint/
  runtime execution-model split to eliminate the step-skeleton copy entirely (steps
  stay stateful — `ctx`/`prev`/`next`/profiling/close/iterator state — so each
  execution still needs its own step objects) — noted as a larger follow-on that
  YTDB-1186 enables but does not deliver.

## Open Questions

- 2026-07-01 [ctx=safe] **RESOLVED — who owns boolean-condition-family lowering?**
  Filed two new slices under YTDB-901 and wired dependencies:
  **S16 (YTDB-1183)** — extend analyzed lowering to the remaining WHERE
  boolean-condition families (`IN`/`NOT IN` literal-list, `BETWEEN`, the five
  `CONTAINS*`, `INSTANCEOF`, the four IS-family, `MATCHES`); depends on S0, S1.
  **S17 (YTDB-1184)** — lower self-references (`@rid`/`@this`), context vars
  (`$parent`/`$current`/`$matched`/`$depth`), and top-level functions incl.
  `any()`/`all()`; depends on S0, S1, S10. Added `S12 depends on S16` (index-aware
  needs `IN`/`BETWEEN` represented) and `S15 depends on S16 + S17` (cleanup
  removes the per-condition AST evaluators). This gives the D3 FilterStep AST
  fallback a chartered retirement path: S16 + S17 lower the shapes that today
  route to the fallback, so the "retired as later slices widen lowering" framing
  in D3 and the YTDB-916 note is now backed by real issues.
- 2026-07-01 [ctx=safe] **Fallback strategy for un-lowerable WHERE clauses
  (central design question).** How does `FilterStep` reach the issue's end state
  ("constructor takes `AnalyzedExpr`, old constructor removed, `matchesFilters`
  shim removed") when most WHERE shapes cannot lower? Candidate shapes: (A)
  dual-carry — FilterStep holds both the lowered `AnalyzedExpr` (when it lowers)
  and the `SQLWhereClause` (fallback + serialize); (B) planner-level split — the
  planner tries to lower and emits an analyzed step on success, the legacy step
  otherwise; (C) escape-hatch IR node wrapping an un-lowered `SQLBooleanExpression`
  so `AnalyzedExpr` can represent any WHERE and FilterStep goes pure-analyzed;
  (D) broadly extend lowering (unrealistic for one slice). Needs user steer.
- 2026-07-01 [ctx=safe] **AND/OR IR representation.** Extend `BinaryOperator`
  with `AND`/`OR` and left-fold the n-ary `SQLAndBlock`/`SQLOrBlock` into nested
  `BinaryOp`, or add a dedicated (possibly n-ary) boolean IR node? Short-circuit
  semantics must match `SQLAndBlock`/`SQLOrBlock` either way.
- 2026-07-01 [ctx=safe] **RESOLVED (D5; YTDB-1185 filed) — Serialization/plan-cache.**
  Reframed: the plan cache reuses live objects via `copy(ctx)`, not serialize, so
  the `Param`-by-reference constraint is satisfied by D1's eval-time resolution;
  and the plan/step serialize/deserialize round-trip is dead production code (see
  Surprises). S1 takes the minimal source-WHERE bridge (serialize the source WHERE
  + tag, re-lower on deserialize) to keep the round-trip test green; the vestigial
  plan-serialization machinery is removed repo-wide by the new cleanup slice
  **YTDB-1185** (relates to YTDB-901 / YTDB-916 / YTDB-930), which then lets S15
  drop the round-trip acceptance and S1 delete its bridge.

## Adversarial gate record
