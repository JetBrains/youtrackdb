# Consistency Review — Gremlin-to-MATCH Translator

## Outcome: PASS

Two iterations. All findings resolved or acknowledged.

## Iteration 1 — Initial review

### Verification certificates

The reviewing sub-agent verified all code references in the plan and design
document against the actual codebase. Existing constructs referenced:

- `MatchExecutionPlanner` constructors (3 existing) — exist, signatures
  match, but the minimal `(Pattern, aliasClasses, aliasFilters)` ctor sets
  `notMatchExpressions`, `aliasRids`, `matchExpressions` to immutable empty
  collections.
- `MatchExecutionPlanner.createExecutionPlan` — exists; internally calls
  `SelectExecutionPlanner.handleProjectionsBlock` at line 624 on the
  no-RETURN-flags branch.
- `SelectExecutionPlanner.handleProjectionsBlock` — exists, `public static`.
- `Pattern`, `PatternNode`, `PatternEdge`, `Pattern.aliasToNode`,
  `Pattern.addExpression` — all exist with documented roles.
- `SQLMatchPathItem.outPath/inPath/bothPath` — exist, instance methods.
- `YTDBStrategyUtil.isPolymorphic` — exists.
- `YTDBGraphImplAbstract.registerOptimizationStrategies` — exists; registers
  5 strategies (4 ProviderOpt + 1 FinalizationStrategy).
- `manageNotPatterns` — exists; constraint on first-alias-must-exist
  confirmed.
- `CartesianProductStep` / `splitDisjointPatterns` — exist.
- `QueryPlanningInfo` fields — all referenced fields exist.
- `ExecutionStream` API — `hasNext(ctx)/next(ctx)/close(ctx)`; no `pullOne`.
- `GqlMatchStatement.toLiteral` — exists.
- `GqlMatchStatement.buildWhereClause` — exists, but called from
  `GqlMatchVisitor.visitNodePattern`, NOT from `GqlMatchStatement.buildPlan`.
- `GqlMatchStatement.buildPlan` — exists; reads `filter.getFilter()` directly.
- `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX` — exists, package-private,
  declared on `MatchExecutionPlanner` (not `SQLMatchStatement`).
- `MatchExecutionPlanner.assignDefaultAliases` — `private`, called only
  from `buildPatterns` which short-circuits when `pattern != null`.

### Findings

- **CR1 [should-fix]**: Track 5 lacked `notMatchExpressions` injection flag.
- **CR2 [should-fix]**: External `handleProjectionsBlock` call would
  double-append projection steps.
- **CR3 [should-fix]**: Track 1 mis-located `buildWhereClause` call site.
- **CR4 [should-fix]**: `DEFAULT_ALIAS_PREFIX` package-private and
  `assignDefaultAliases` does not run on translator's path.
- **CR5 [suggestion]**: Plan said "four existing strategies" but registration
  has five.
- **CR6 [suggestion]**: `MultiPlanMatchStep` missing from class diagram.
- **CR7 [suggestion]**: `pullOne()` label in sequence diagram is not the
  actual `ExecutionStream` method.

User decision: ACCEPT all 7. CR2's resolution = new additive
`MatchExecutionPlanner(MatchPlanInputs)` ctor; planner handles projection
block internally; no external call.

## Iteration 2 — Gate verification

All 7 ACCEPTED findings VERIFIED. Two minor new suggestions surfaced and
applied:

- **CR8 [suggestion]**: Plan said "two existing constructors" but
  `MatchExecutionPlanner` has three.
- **CR9 [suggestion]**: `DEFAULT_ALIAS_PREFIX` is on `MatchExecutionPlanner`,
  not `SQLMatchStatement`.

Both fixed in the same iteration.

## Outcome

Plan and design document are consistent with the actual codebase. Single
modification to `MatchExecutionPlanner` introduced by Phase 1 (additive
`(MatchPlanInputs)` ctor + `MatchPlanInputs` record) is documented in D2
and Track 2. No existing public API is altered.

Ready for structural review.
