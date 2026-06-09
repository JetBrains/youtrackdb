<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Track 1: Shared MATCH IR builders + GQL adoption + `IS DEFINED` / `IS NOT DEFINED` builder factories

## Purpose / Big Picture
After this track, MATCH IR construction lives in one shared package that both the GQL front-end and the upcoming Gremlin translator call, and the translator has presence-operator factories (`IS DEFINED` / `IS NOT DEFINED`) ready to wire.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Foundation track: creates the shared `match/builder/` package consumed by both GQL and the upcoming Gremlin translator, and exposes `MatchWhereBuilder.isDefined` / `isNotDefined` factories wrapping the pre-existing `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` AST nodes (D-IS-DEFINED) — wiring only, no grammar / parser / evaluator changes. It ships independently of the translator because GQL adopts the builders on its own, so the track is an independently reviewable, independently mergeable PR even though it is below the ~12-file track floor.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation
The MATCH IR classes (`Pattern`, `PatternNode`, `PatternEdge`, `SQLMatchExpression`, `SQLMatchPathItem`, `SQLMatchFilter`, the `SQLBooleanExpression` hierarchy) already exist and are constructed inline today in two places: the SQL `MATCH` parser path and `GqlMatchStatement`. `GqlMatchStatement.buildPlan` builds a `PatternNode` per `SQLMatchFilter`, populates `aliasClasses` / `aliasFilters`, and uses two private helpers — `buildWhereClause(Map<String,Object>)` (AND-block of equality conditions) and `toLiteral(Object)` (Java value → `SQLExpression`).

The presence operators already exist in the grammar: `core/src/main/grammar/YouTrackDBSql.jjt` has `IsDefinedCondition` / `IsNotDefinedCondition` productions (≈ lines 2897-2913), with AST classes `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` under `internal/core/sql/parser/`. Their `evaluate` routes call `expression.isDefinedFor(...)`, the entity-presence primitive that separates *absent* from *present-with-null*. `isIndexAware()` is already `false`. Track 1 adds no grammar, AST, or evaluator code — only builder factories that construct these existing nodes.

New package: `internal/core/sql/executor/match/builder/`.

## Plan of Work
1. Create the shared builder package with three pure-helper classes over the existing IR:
   - `MatchPatternBuilder` — `addNode(alias, className, where, optional)`, `addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)`, `build()` returning a small `PatternIR` value (pattern + `aliasClasses` + `aliasFilters`) so callers don't assemble from separate getters.
   - `MatchWhereBuilder` — `eq` / `op` / `in` / `between` / `containsText` / `startsWith` / `endsWith` / `matchesRegex` / `and` / `or` / `not` / `wrap`, plus `isDefined(field)` / `isNotDefined(field)` (this track's presence factories).
   - `MatchLiteralBuilder` — `toLiteral(Object)`.
2. Add `isDefined` / `isNotDefined` factories: each constructs the existing `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition`, wires the `SQLExpression` child to point at `field` on the boundary alias, and returns it as a `SQLBooleanExpression`.
3. Refactor `GqlMatchStatement` onto the builders, behavior-preserving (D6): the `for` loop calls `MatchPatternBuilder.addNode(...)`; `buildWhereClause` becomes a chain of `MatchWhereBuilder.eq(field, MatchLiteralBuilder.toLiteral(value))` + `.and(...).wrap()`; `toLiteral` becomes a one-line delegate. Public API and existing GQL test assertions unchanged.
4. Builder unit tests: round-trip pins for the presence factories (`isDefined("foo")` renders `"foo is defined"`, `isNotDefined("foo")` renders `"foo is not defined"`); golden-string regression tests for the node/where/literal builders. The existing parser/evaluator are already SQL-tested, so this track tests only the builder wrappers.

Ordering: builders first, then the GQL refactor consumes them, then the presence factories (independent of GQL). The GQL refactor must keep GQL's tests green at each step.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. Empty at Phase 1. -->

## Validation and Acceptance
- The shared builders produce IR identical to what `GqlMatchStatement` built inline before the refactor — verified by GQL's existing test suite passing unchanged.
- `MatchWhereBuilder.isDefined(field)` / `isNotDefined(field)` construct the existing presence AST nodes and render to `"<field> is defined"` / `"<field> is not defined"`.
- No grammar, parser, AST, or evaluator file is modified.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references. Often empty. -->

## Interfaces and Dependencies
**In scope (new):** `internal/core/sql/executor/match/builder/MatchPatternBuilder.java`, `MatchWhereBuilder.java`, `MatchLiteralBuilder.java`, `PatternIR.java` (value class) + their unit tests.
**In scope (modified):** `GqlMatchStatement.java` (refactor `buildPlan` / `buildWhereClause` / `toLiteral` onto the builders).
**Out of scope:** the grammar (`YouTrackDBSql.jjt`), the AST classes `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` (consumed, not modified), `MatchExecutionPlanner`, every execution step.
**Inter-track dependencies:** supplies the builder package + presence factories to Track 2 (strategy skeleton uses `MatchPatternBuilder`), Track 4 (`isDefined` / `isNotDefined` for `has(key)` / `hasNot(key)`), and Track 5 (`hasProperty`-based presence check shares the same entity-presence primitive).
**Signatures:** `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` constructors and `isDefinedFor(...)` are the existing primitives the factories wrap.
