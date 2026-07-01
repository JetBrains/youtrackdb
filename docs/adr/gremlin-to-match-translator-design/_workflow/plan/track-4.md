<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 4: Filtering — predicates + logical filters

## Purpose / Big Picture
After this track, the full Gremlin filtering surface translates: property/label/id predicates (`has` / `hasLabel` / `hasId`), the `P` / `Text` / `TextP` predicate algebra, and the step-level logical filters (`and` / `or` / `not` / `where`).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Merges predicate translation and the logical-filter steps into one reviewable filtering diff. Fills out the predicate adapter with the full `P` set, adds `HasStep` / `YTDBHasLabelStep` / `HasIdStep` recognisers plus `Text` / `TextP` translations via the new `SQLEndsWithCondition` / find-mode `SQLMatchesCondition` operators and the collate transform on `SQLContainsTextCondition` (D-TEXT-OPS). Bare presence forms `has(key)` / `hasNot(key)` recognise via `TraversalFilterStepRecogniser` / `NotFilterStepRecogniser` and emit `IS DEFINED` / `IS NOT DEFINED` (D-IS-DEFINED). Logical steps descend into their global children with asymmetric support: `AndStep` accepts pure-filter or edge-bearing children; `OrStep` requires all pure-filter; `NotStep` is one recogniser branching on `hasEdgeHops` (D9).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

## Context and Orientation
Track 3 left a `GremlinPredicateAdapter` skeleton (it translated only the `has(...)` inside edge-filter chains). Track 4 makes it the full chokepoint between TinkerPop's predicate algebra and `SQLBooleanExpression`. The mapping is mostly mechanical (`Compare.eq/neq/gt/gte/lt/lte` → YTDB operators 1:1, `Contains.within/without` → `SQLInCondition` / `SQLNotInCondition`), but several corner cases require care and are spelled out in design §"Predicate translation":
- **NULL semantics:** `P.eq(null)` / `P.neq(null)` rewrite to `field IS NULL` / `field IS NOT NULL` (YTDB's `=`/`!=` return FALSE on null operands, diverging from TP).
- **Singleton-collection equality:** `P.eq([a])` / `P.neq([a])` (size-1 literal) **decline** under D3 — `QueryOperatorEquals` auto-unboxes singletons against scalars, and field cardinality is unknown at translation time in schema-less/mixed classes. Size 0 and ≥2 translate normally.
- **`between`:** Gremlin `between` is right-exclusive `[lo, hi)`; YTDB `SQLBetweenCondition` is closed `[lo, hi]`. Translate to `AND(>=lo, <hi)`, never `SQLBetweenCondition`. `inside`/`outside` are open both ends → `AND(>lo, <hi)` / `OR(<lo, >hi)`.

`hasLabel(label)` is usually folded by `YTDBGraphStepStrategy` into the start step's `hasContainers`, so the start recogniser pins `aliasClasses[a]`. `has(key)` / `hasNot(key)` desugar (TinkerPop 3.8.1) to `TraversalFilterStep(__.values(key))` / `NotStep(__.values(key))` — neither lands on `HasStep`, so they bypass `HasStepRecogniser` and route through the new `TraversalFilterStepRecogniser` / `NotFilterStepRecogniser` Case A, emitting `IS DEFINED` / `IS NOT DEFINED` (not `IS NULL`, which over-matches null-valued properties).

The step-level logical filters (`AndStep` / `OrStep` / `NotStep` / `WhereTraversalStep` / `WherePredicateStep`) are the `ConnectiveStrategy` form — each child carries a sub-traversal of arbitrary recognized steps, translated by a **sub-walker** over the same registry with a `SubWalkerContext` inheriting the parent `boundaryAlias`.

## Plan of Work
1. **Full predicate adapter** (`GremlinPredicateAdapter`): the complete `P` set (`Compare`, `Contains`, composite `P.and/or/not`, `inside`/`outside`/`between` decompositions), the NULL and singleton-collection rules above, and custom-predicate decline (`getBiPredicate()` not `Compare`/`Contains`/`Text`).
2. **`HasStep` family recognisers:** `HasStepRecogniser` (unpacks `HasContainer`s for `has(key,value)` / `has(key,predicate)` / `has(label,key,value)`; multiple containers AND together); `HasLabelStepRecogniser` (the YTDB `YTDBHasLabelStep` subclass, plus non-folded `hasLabel`); `HasIdStepRecogniser` (single → `aliasRids[a]`, multi → `@rid IN [...]`).
3. **Presence forms:** `TraversalFilterStepRecogniser` Case A (`has(key)` → `MatchWhereBuilder.isDefined`) and `NotFilterStepRecogniser` Case A (`hasNot(key)` → `isNotDefined`), both via Track 1's factories (D-IS-DEFINED).
4. **`Text` / `TextP` translation** (D-TEXT-OPS): `containing` / `notContaining` → `SQLContainsTextCondition` (+ collate transform, making SQL `CONTAINSTEXT` collation-aware too); `startingWith` → range `field >= p AND field < p⁺` via `MatchWhereBuilder.startsWith` (index-aware, collation-respecting; declines on empty `p` or undeclared/schema-less property); `endingWith` → new `SQLEndsWithCondition` AST node; `regex` → find-mode flag on `SQLMatchesCondition`. New AST nodes report `isIndexAware()==false`; `regex` stays case-sensitive.
5. **Logical filters:** `AndStepRecogniser` (pure-filter children AND-composed into `where`; edge-bearing children append pattern fragments / NOT expressions; mixed supported) and `OrStepRecogniser` (all-children-pure-filter only — composes via `MatchWhereBuilder.or`; declines if any child carries edges, with the `hasEdges` flag propagating recursively). Two separate files so the asymmetry (AND distributes, OR does not) is visible in code. `NotStepRecogniser` — one recogniser branching on `hasEdgeHops(subTraversal)`: pure-filter → `MatchWhereBuilder.not(...)` into `where`; edge-bearing → `SQLMatchExpression` appended to `notMatchExpressions` (pre-validates the first NOT alias exists in the positive pattern, else declines). `WhereTraversalStep` (positive counterpart) and `WherePredicateStep` (`$matched.<label>` references).
6. **`SubTraversalPredicateAdapter`** + sub-walker dispatch; its `decline_doesNotCommitPartialStateToOuterContext` unit test is the canonical no-mutation-on-decline pin.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- `has(key,value)` / `has(key,predicate)` / `has(label,key,value)` / `hasLabel` / `hasId` (single + multi) translate to the same multiset as native.
- `eq(null)` / `neq(null)` against a null-valued property match native; `eq([a])` / `neq([a])` (size-1) decline and fall back to native; `eq([a,b])` / `eq([])` translate and match native.
- `between` returns the right-exclusive multiset (no off-by-one on the high bound); `inside` / `outside` match native.
- `containing` / `startingWith` / `endingWith` / `regex` (and `not*` variants) translate and match native; `startingWith` uses an index range scan; collation-aware predicates honor `default` (case-sensitive) and `ci` (case-insensitive) properties; non-String fields decline.
- `has(key)` → `IS DEFINED`, `hasNot(key)` → `IS NOT DEFINED`; both match native on absent and present-with-null properties (distinct from `IS NULL`).
- `and` (pure / edge-bearing / mixed children), `or` (pure-filter children; edge-bearing child declines), `not` (both shapes), `where(traversal)`, `where(P)` translate or decline per design; every decline leaves context unmutated.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope (new):** full `GremlinPredicateAdapter`; `HasStepRecogniser`, `HasLabelStepRecogniser`, `HasIdStepRecogniser`, `TraversalFilterStepRecogniser`, `NotFilterStepRecogniser`, `AndStepRecogniser`, `OrStepRecogniser`, `NotStepRecogniser`, `WhereTraversalStepRecogniser`, `WherePredicateStepRecogniser`; `SubTraversalPredicateAdapter` + `SubWalkerContext`; `SQLEndsWithCondition` AST node + find-mode flag on `SQLMatchesCondition` (D-TEXT-OPS); predicate-equivalence / NULL / collection / logical-combinator tests.
**In scope (modified):** `SQLContainsTextCondition` (collate transform); `MatchWhereBuilder` (`startsWith` / `endsWith` / `matchesRegex` bodies if stubbed in Track 1); the registry registration sites.
**Out of scope:** projections / labels / dedup / order / aggregates / union (Tracks 5–6); the singleton-collection schema-aware rewrite and edge-bearing OR (Phase 2 — design §"Out of scope"); grammar changes (the new operators are AST + evaluator only, reachable programmatically).
**Inter-track dependencies:** depends on Track 3 (predicate-adapter skeleton, `GremlinPatternAssembler`, the equivalence fixture) and Track 1 (`isDefined` / `isNotDefined`, `MatchWhereBuilder`). Supplies the full predicate algebra to Track 5 (by-modulator value resolution reuses it).
**Signatures:** `P.getBiPredicate()`; `HasContainer{key, predicate}`; `QueryOperatorEquals.equals` (lines 63-69 unbox, 71-73 null short-circuit); `MatchExecutionPlanner.manageNotPatterns` (first-NOT-alias precondition).

## Invariants & Constraints
<!-- Combined per-track invariants + constraints (conventions-execution.md §2.1 §14).
Added by workflow migration (#1145). Strategic invariants/constraints for this track remain
in implementation-plan.md § High-level plan (Architecture Notes) and this track's ## Decision
Log — the conservative migration retained the plan Architecture Notes rather than folding them here. -->

## Base commit
<!-- Phase B records the HEAD SHA here at session start; Phase C reads it to compute the
cumulative track diff (conventions-execution.md §2.1 §15). Added by workflow migration (#1145). -->
