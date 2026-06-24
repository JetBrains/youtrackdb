<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Track 1: Polymorphic by-id `hasLabel` and count id-drop fix

## Purpose / Big Picture
After this track, `g.V(childId).hasLabel("Parent")` matches a `Child` and
`g.V(childId).hasLabel(X).count()` counts only the pinned vertex — the by-id Gremlin
path honors polymorphism and the id filter exactly as the class-scan path does.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Introduce the shared `YTDBLabelMatcher`, route both the by-id branch of
`YTDBGraphStep` and `YTDBHasLabelStep` through it so by-id `hasLabel` honors
polymorphism for vertices, edges, and multi-argument labels, and add the
`getIds().length == 0` guard to `YTDBGraphCountStrategy` so an id-bearing count stops
dropping the id. Extend `YTDBHasLabelProcessTest` with the count-honors-id, edge
by-id, and multi-argument by-id scenarios alongside the four existing (uncommitted, working-tree) methods.

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

All work is in the `core` module under
`com.jetbrains.youtrackdb.internal.core.gremlin`. The relevant files and their
current state:

- `traversal/step/sideeffect/YTDBGraphStep.java` — `elements()` has two branches. The
  by-id branch (when `this.ids.length > 0`) filters loaded elements with a single
  `HasContainer.testAll(element, this.hasContainers)`, an exact match that ignores
  the `polymorphic` field — this is the YTDB-1159 defect. The class-scan branch
  (no ids) builds a polymorphic SQL query via `YTDBGraphQueryBuilder` and only applies
  an exact label filter when `!polymorphic`; it is correct and stays untouched.
  `vertices()` and `edges()` both call `elements()`, so the fix covers edges with no
  extra branch.
- `traversal/step/filter/YTDBHasLabelStep.java` — `filter()` already does the correct
  polymorphic match: test the concrete `schemaClass.getName()`, return on match, else
  if `!polymorphic` return false, else walk `schemaClass.getAllSuperClasses()`. Its
  `predicates` list is OR-combined via `anyMatch`. A null schema class returns false
  (the guard the new helper must preserve). Non-YouTrackDB traversers fall back to
  `test(traverser.get().label())`.
- `traversal/strategy/optimization/YTDBGraphStepStrategy.java` — folds a `hasLabel`
  that directly follows a GraphStep into that step's `HasContainer` list (so the by-id
  branch ends up owning the label match); sets the `polymorphic` flag on the step.
  Read-only context for this track — not modified.
- `traversal/strategy/optimization/YTDBGraphCountStrategy.java` — `apply()` rewrites a
  two-step `GraphStep + CountGlobalStep` into a `YTDBClassCountStep`. The label-filter
  branch (`hasContainers.size() == 1 && isLabelFilter(...)`) lacks the
  `getIds().length == 0` guard the empty-containers branch has, so it drops the id —
  the Bug 2 defect.
- `traversal/step/map/YTDBClassCountStep.java` — read-only context; it already honors
  the polymorphic flag through `countClass(cl, polymorphic)`, confirming Bug 2 is an
  id-drop, not a polymorphism defect.

Concrete deliverables: a new `YTDBLabelMatcher` class; `YTDBGraphStep` and
`YTDBHasLabelStep` routed through it; the count-strategy guard; three new test
methods plus the four already-present (uncommitted, working-tree) ones.

Terminology: *by-id branch* / *class-scan branch* / *polymorphic label match* /
`hasLabel` *folding* are defined in design.md §"Core Concepts".

## Plan of Work

Land the matcher and the polymorphism fix before the count guard, so no intermediate
commit regresses a polymorphic by-id count (design.md §"Bug 2", Fix-order constraint).

1. Add `YTDBLabelMatcher` with a static `matches(Element, List<P<? super String>>,
   boolean polymorphic)` that lifts `YTDBHasLabelStep.filter()`'s logic verbatim:
   resolve the schema class once, OR each predicate against the concrete class name,
   and when polymorphic also against every superclass name; preserve the null-schema-
   class guard (return false) and the non-YouTrackDB `element.label()` fallback.
2. Route `YTDBHasLabelStep.filter()` through `YTDBLabelMatcher.matches(...)` with its
   full predicate list — a behavior-preserving refactor, verified by the existing
   `YTDBHasLabelProcessTest` class-scan methods.
3. In `YTDBGraphStep.elements()` by-id branch, partition `hasContainers` by the
   `T.label.getAccessor().equals(container.getKey())` key test (not
   `YTDBGraphQueryBuilder.addCondition`, per design.md §"Bug 1"). Run non-label
   containers through `HasContainer.testAll`; run each label container's predicate
   through `YTDBLabelMatcher.matches` with the step's `polymorphic` flag, ANDing
   across label containers.
4. Add the `getIds().length == 0` condition to `YTDBGraphCountStrategy`'s label-filter
   branch so an id-bearing count falls through to normal by-id execution.
5. Extend `YTDBHasLabelProcessTest`: keep the four existing (uncommitted) methods; add
   count-honors-id (two same-class vertices, one pinned, assert `toList().size()` and
   `count()` agree at 1 for exact and polymorphic supertype labels — the brought-back
   reproduction), edge by-id (edge hierarchy, polymorphic 1 / non-polymorphic 0), and
   multi-argument by-id (`hasLabel("A","B")` on a subtype of `A`).

Invariants to preserve: non-polymorphic queries keep exact matching; the class-scan
branch is unchanged; `checkSize`'s `toList == count` equality holds on multi-vertex
data.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Behavioral acceptance criteria for the track:

- `gp().V(childId).hasLabel("Parent")` and `...hasLabel("Grandparent")` return the
  `Child`; `gp().V(childId).hasLabel("Child")` returns it too.
- `gn().V(childId).hasLabel("Parent")` returns nothing; `gn().V(childId).hasLabel("Child")`
  returns the `Child`.
- `gp().E(edgeId).hasLabel(superEdge)` returns the edge; the non-polymorphic form
  returns nothing.
- `gp().V(childId).hasLabel("A","B")`, where the vertex is a subtype of `A`, matches;
  the non-polymorphic form does not.
- `gp().V(childId).hasLabel("Child").count()` and
  `gp().V(childId).hasLabel("Parent").count()` return 1 with a second `Child` present;
  both agree with `toList().size()`.
- The existing class-scan and has-id methods still pass (the `YTDBHasLabelStep`
  refactor is behavior-preserving).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Often empty. -->

## Interfaces and Dependencies

In scope (files this track changes):
- `core/.../gremlin/traversal/step/filter/YTDBLabelMatcher.java` (new)
- `core/.../gremlin/traversal/step/filter/YTDBHasLabelStep.java`
- `core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java`
- `core/.../gremlin/traversal/strategy/optimization/YTDBGraphCountStrategy.java`
- `core/src/test/.../gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`

Out of scope:
- The class-scan branch of `YTDBGraphStep.elements()` and `YTDBGraphQueryBuilder`.
- `YTDBGraphStepStrategy` (folding behavior is relied on, not changed).
- `YTDBClassCountStep` (already polymorphism-correct; the count fix is in the strategy).

Signatures relevant to this track:
- `YTDBLabelMatcher.matches(Element element, List<P<? super String>> predicates, boolean polymorphic)` — new.
- `YTDBHasLabelStep` holds `List<P<? super String>> predicates` and `boolean polymorphic`.
- `YTDBGraphStep` holds `boolean polymorphic` and `List<HasContainer> hasContainers`;
  the by-id branch is in `elements(BiFunction getByIds, Function getElement)`.
- `HasContainer.getKey()`, `HasContainer.getPredicate()`, `T.label.getAccessor()`.
- `YTDBGraphCountStrategy` reads `step.getIds()`, `step.getHasContainers()`.

Inter-track dependencies: none — single track, the whole change. It sits below the
~12-file merge-candidate floor only because it is the complete change with no neighbor
to fold into (the argumentation gate's "whole change" case in `planning.md`
§Track descriptions).

Test execution: the scenario class runs only through the suite. Locally:
`./mvnw -pl core surefire:test@sequential-tests -Dtest=YTDBProcessTest
-Dgremlin.tests=com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBHasLabelProcessTest`
(after `test-compile`).
