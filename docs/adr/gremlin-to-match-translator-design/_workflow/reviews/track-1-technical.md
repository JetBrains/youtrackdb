> Track 1 Phase A technical review — shared `match/builder/` package, GQL adoption,
> and `IS DEFINED` / `IS NOT DEFINED` factories. Full text-predicate translation
> (D-TEXT-OPS) and the plan cache belong to later tracks and are out of scope here.

# Track 1 — Technical Review

## Outcome: All findings resolved (decisions baked into step decomposition)

**No blockers.** 6 should-fix and 4 suggestion findings, all addressed via
step-level decisions documented in `tracks/track-1.md` Steps section.

## Findings

### T1 [should-fix] — RESOLVED in step decomposition
**Issue**: `SQLNotBlock.negate` defaults to `false`. A `MatchWhereBuilder.not(...)`
that constructs the block without `setNegate(true)` is silently a pass-through.
**Resolution**: Step 2 mandates `setNegate(true)` in `not(...)` plus a unit test
that asserts the produced block evaluates `!sub`.

### T2 [should-fix] — RESOLVED in step decomposition
**Issue**: `SQLNotInCondition` has no public setters; cross-package construction blocked.
**Resolution**: Step 2 implements `notIn(field, list)` via composition
`MatchWhereBuilder.not(in(...))` — produces NOT(IN ...) AST shape, avoids modifying
parser-package classes. Decision documented in step description; consistency with
parser-canonical NOT_IN AST is acceptable per the invariant rewording (T9 / A6).

### T3 [should-fix] — RESOLVED in step decomposition
**Issue**: `SQLInCondition` literal-list wrapping is ambiguous.
**Resolution**: Step 2 wraps the list as `SQLExpression(literalValue=list)` via a
`SQLMathExpression` adapter (option a from the review). This matches what
`GqlMatchStatement.toLiteral` already does for `List`/`Set` and is sufficient for
GQL parity. Translator's hot-path index awareness is a Phase 2 concern.

### T4 [should-fix] — RESOLVED in step decomposition
**Issue**: GQL anonymous alias prefix `"$c<N>"` (line 189 of `GqlMatchStatement`)
must be preserved by the new `MatchPatternBuilder` to keep
`GqlMatchStatementTest:75` (`row.getPropertyNames().contains("$c0")`) green.
**Resolution**: `MatchPatternBuilder.addNode(alias, ...)` does NOT generate
default aliases on `null` input — it requires the caller to pre-resolve. GQL's
`buildPlan` keeps its existing `effectiveAlias(rawAlias, anonymousCounter)`
helper; the translator (Track 3+) generates its own `$g2m_anon_N` aliases
similarly. Decoupled.

### T5 [should-fix] — RESOLVED in step decomposition (also covered by A3)
**Issue**: `toLiteral(null)` currently NPEs (falls through to `value.getClass()`).
"Extracted verbatim" preserves the bug.
**Resolution**: Step 1 extracts `toLiteral` verbatim BUT adds Javadoc:
"`null` not supported; throws `NullPointerException`. Caller's responsibility
to filter nulls upstream." Plus a unit test that asserts the NPE for
documentation purposes. Pure behavior-preserving extraction.

### T6 [should-fix] — RESOLVED in step decomposition
**Issue**: empty/single-element `and()`/`or()` semantics ambiguous.
**Resolution**: Step 2 specifies:
- empty `and()` / `or()`: throws `IllegalStateException` ("at least one operand
  required"). No tautology default.
- single-element `and(c)` / `or(c)`: returns `c` directly (no extra wrapper) —
  matches parser parity. Multi-element returns the appropriate block.
- Unit tests cover 0, 1, 2, 3+ cardinalities.

### T7 [suggestion] — ACCEPTED in step decomposition
**Issue**: `between` should use `SQLBetweenCondition` rather than `and(gte, lte)`
to match parser-emitted AST and benefit from index-aware planning.
**Resolution**: Step 2 uses `SQLBetweenCondition.setFirst/setSecond/setThird`.
Unit test asserts the resulting AST class is `SQLBetweenCondition`.

### T8 [suggestion] — ACCEPTED (resolves A2 blocker)
**Issue**: Decision on whether `buildWhereClause` and `toLiteral` are kept as
delegates or removed.
**Resolution**: Step 4 keeps `GqlMatchStatement.buildWhereClause` as a
package-private static delegate that calls `MatchWhereBuilder` internally.
17 GQL test call sites continue to compile unchanged. `GqlMatchStatement.toLiteral`
is `private` with no external callers — body delegated to
`MatchLiteralBuilder.toLiteral`, signature unchanged.

### T9 [suggestion] — ACCEPTED in step decomposition
**Issue**: Regression test specification was vague ("same step structure").
**Resolution**: Step 4 adds four prettyPrint fragment regression tests in
`GqlMatchStatementPlanPrettyPrintTest` over `plan.prettyPrint(0, 2)` for
representative GQL queries: (1) single-node anonymous, (2) multi-property AND
filter, (3) single-property WHERE (no spurious AND conjunct), (4) multi-filter
map cartesian product. Assertions use `contains` on plan fragments (same style
as `ExpandStepPrettyPrintTest`), not full-string golden equality.

### T10 [suggestion] — RESOLVED in step decomposition
**Issue**: `addEdge(fromAlias, toAlias, ...)` contract for missing `fromAlias`.
**Resolution**: Step 3 specifies implicit creation (matches
`Pattern.getOrCreateNode` semantics; the planner already supports it).
Unit test asserts a fresh `fromAlias` produces both nodes in the resulting Pattern.
