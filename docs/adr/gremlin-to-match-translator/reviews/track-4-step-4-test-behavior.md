# Track 4, Step 4 — Test Behavior Review

Commit: `3c363fe6f0` "Translate folded HasContainers on the start step"

## Summary

Most of the test changes in this step are behavior-driven and would catch
real regressions. The equivalence harness pins multiset parity with
correct semantics (`containsExactlyInAnyOrderElementsOf`), the smoke
test pins both translation engagement and result correctness, and the
flipped declines move from negative pins to positive end-to-end pins.
However, three assertions are noticeably weaker than the production
behavior they exercise: the strategy gate-flip test asserts only
`assertNotSame`, the translator-level test pins only `aliasClasses` and
omits the load-bearing "no redundant filter" check that would
distinguish class-narrowing from a property-style translation, and one
new RECOGNIZED equivalence case (`V_has_age_gt_30`) trivially passes
because both pipelines return empty against the seed fixture. The
three-valued-logic compensation in the predicate adapter is not
exercised end-to-end at all in this step — it remains pinned only at
the unit level in the prior commit's `GremlinPredicateAdapterTest`.

## Findings

### Recommended

#### TB-1 — `translateGV_startStepWithFoldedHasLabel_narrowsAliasClass` pins only one of three observable changes

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchTranslatorTest.java`, method `translateGV_startStepWithFoldedHasLabel_narrowsAliasClass` (line 343)
- **Issue**: Shallow precision. The test asserts `result.isPresent()` and `aliasClasses.get("$g2m_v0") == "Person"` — but the production code does three observable things for this input:
  1. Calls `addNode(BOUNDARY_ALIAS, "Person", ...)` → asserts on `aliasClasses` (pinned).
  2. Skips the `classEq` filter under polymorphic-default (since `!polymorphic` is false at line 167–168 of `StartStepRecogniser.java`) → `aliasFilters` for the boundary alias should be **empty**. **Not pinned.**
  3. Skips the property-predicate path entirely (since `T.label` is dispatched out of `collectFolded`'s `else` branch at line 251–266) → `propertyPredicate` is `null`. **Not pinned.**
- **Evidence (FALSIFIABILITY CHECK)**: A regression that incorrectly routed the `T.label` HasContainer through the `else` branch (predicate adapter) instead of the dedicated `T.label` branch would emit a `WHERE name = 'Person'` filter (key="~label" being treated as a property name) AND still set `aliasClasses` to the default `V`. The test would fail on the `aliasClasses` check, so this specific regression is caught. **However**, a different regression — one that correctly narrows `aliasClasses` AND additionally writes a redundant property-style filter, OR one that incorrectly emitted `classEq` under polymorphic-default — would still pass: `aliasClasses["$g2m_v0"]` would still equal "Person".
- **Missing behavior**: Pin the empty-filter / empty-rids state explicitly so the assertion fails if any redundant artefact is added.
- **Suggested fix**:
  ```java
  @Test
  public void translateGV_startStepWithFoldedHasLabel_narrowsAliasClass() {
    var admin = primeStartStep(graph.traversal().V().hasLabel("Person"));

    var translation = GremlinToMatchTranslator.translatePrefix(admin).orElseThrow();
    var inputs = translation.inputs();

    assertEquals("Person", inputs.aliasClasses().get(BOUNDARY_ALIAS));
    // Polymorphic-default: narrowing happens via aliasClasses only — no
    // redundant @class filter, no property-style predicate, no rid constraint.
    assertTrue("polymorphic mode must not emit an alias filter", inputs.aliasFilters().isEmpty());
    assertTrue(inputs.aliasRids().isEmpty());
    // Pin the rest of the translation shape so a regression that altered the
    // boundary metadata (which the strategy splice consumes) surfaces here.
    assertEquals(1, translation.prefixStepCount());
    assertEquals(BOUNDARY_ALIAS, translation.boundaryAlias());
    assertEquals(BoundaryOutputType.ELEMENT, translation.outputType());
    assertEquals(Vertex.class, translation.returnClass());
  }
  ```

#### TB-2 — `apply_proceedsWhenHasContainersPresent` asserts only `assertNotSame`

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategyTest.java`, method `apply_proceedsWhenHasContainersPresent` (line 417)
- **Issue**: The post-condition assertion is `assertNotSame(startBefore, admin.getStartStep())`. The Javadoc and inline comment claim the test verifies that the strategy "spliced the boundary step", but the assertion only proves that "something replaced the original start step". A bug that replaced `startBefore` with a non-`YTDBMatchPlanStep` (e.g., a future refactor that accidentally swapped in a different step type) would still pass.
- **Evidence (FALSIFIABILITY CHECK)**: If `applyTranslation` were rewired to insert (say) an `IdentityStep` before the original `YTDBGraphStep` (instead of replacing it with a `YTDBMatchPlanStep`), the new start step would not be `startBefore` and `assertNotSame` would still pass. The existing splice-mechanics test at line 489–535 covers this for the `g.V()` shape, but this gate-flip test does not transitively reuse those assertions.
- **Missing behavior**: Pin that a `YTDBMatchPlanStep` is at index 0 and the step list has the expected size after splicing.
- **Suggested fix**:
  ```java
  // After applying the strategy:
  assertNotSame(startBefore, admin.getStartStep());
  assertEquals(YTDBMatchPlanStep.class, admin.getStartStep().getClass());
  assertEquals(1, admin.getSteps().size());
  ```

#### TB-3 — `V_has_age_gt_30` equivalence case trivially passes (empty-vs-empty)

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`, parameterised case `V_has_age_gt_30` (line 174–177)
- **Issue**: The `seedFixture` (line 243–267) populates only the `name` property on every vertex; no vertex has an `age` property. Both the native pipeline and the translated pipeline return an empty list for `g.V().has("age", P.gt(30))`. The result-equivalence assertion `containsExactlyInAnyOrderElementsOf` is vacuously satisfied (`[].containsExactlyInAnyOrderElementsOf([])`). Only `boundaryStep_engagementMatchesExpected` keeps the case load-bearing — verifying the strategy claimed the shape — but nothing pins that the translated `gt` actually filters correctly.
- **Evidence (FALSIFIABILITY CHECK)**: A regression where the predicate adapter emitted an always-false expression (or always-true) for `gt` would still produce empty results because no `age` property exists. The test would not surface the bug. The mutation `gt → lt` would also pass.
- **Missing behavior**: Either (a) add an `age` property to a subset of seeded vertices so the case has non-empty distinct results that differ from the all-vertices run, or (b) replace the case with a property the fixture already populates (e.g., `has("name", P.gt("Alice"))` → returns Bob/Carol/David but not Alice) so the equivalence comparison is non-trivial.
- **Suggested fix**:
  ```java
  // In seedFixture, after addVertex calls:
  alice.property("age", 25);
  bob.property("age", 35);
  carol.property("age", 45);
  // david has no age property — exercises the IS-NOT-NULL implicit guard
  graph.tx().commit();

  // Then `V_has_age_gt_30` returns {bob, carol} on both pipelines —
  // a regression that flipped the operator or dropped the predicate would
  // change the multiset and fail the assertion.
  ```

#### TB-4 — Three-valued-logic predicate shapes are not exercised end-to-end in this step

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`, parameter list `cases()` (line 130)
- **Issue**: The plan §(2) calls out three-valued logic as mandatory ("`null != "x"` is true in Gremlin but `field <> 'x'` excludes nulls in SQL — the adapter must add `IS NULL OR …` for `neq`, `without`, `notContaining`, etc."). The new equivalence cases added in this step exercise only `eq` (string), `gt` (numeric, against an absent property — see TB-3), `hasLabel` (single-class and multi-class). None of the negative/null-inclusion shapes (`neq`, `without`, `notContaining`, `notStartingWith`) are in the equivalence harness. End-to-end coverage of the IS-NULL-OR compensation lives only in the predicate adapter's unit tests (`GremlinPredicateAdapterTest`, prior commit `f98b84f093`), which assert on rendered SQL strings rather than result-multiset parity.
- **Evidence (BEHAVIOR TRACE)**: A regression that drops the `WHERE.or(WHERE.isNull(field), …)` wrapping in `GremlinPredicateAdapter.toBooleanExpression` (lines 184–185, 220–221, 251–256) would render syntactically valid SQL — unit tests pinning the rendered string would fail, but the equivalence harness in this step never invokes that code path. With the predicate adapter wired into `StartStepRecogniser` for the first time in this step, the wiring point is exactly where end-to-end three-valued cases would catch a divergence between the production native pipeline (which adds the IS NOT NULL guard at `YTDBGraphQueryBuilder.java:79-89`) and the translated pipeline.
- **Missing behavior**: At least one folded-HasContainer equivalence case exercising a negative-form predicate against a seed fixture that mixes property-absent and property-set vertices.
- **Suggested fix**:
  ```java
  // Add to cases() (and seed an age property as in TB-3, leaving david's age unset):
  shape(
      "V_has_name_neq_Alice",
      g -> g.V().has("name", P.neq("Alice")),
      Expected.RECOGNIZED),
  // david would surface here only if the IS-NULL-OR compensation is present,
  // because david has no name in the suggested seed; without the guard,
  // SQL `name <> 'Alice'` would exclude david while Gremlin would include him.
  ```
  (Alternatively, defer this to step 5 — the `HasStep` recogniser step explicitly schedules `neq` cases per the plan §(verification methodology). If deferred, document the deferral in the step description so it does not slip past track close.)

### Minor

#### TB-5 — Smoke test boundary-step check is `anyMatch`, not "exactly one boundary at index 0"

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/GremlinToMatchSmokeTest.java`, method `startWithFoldedHasContainer_translatesToBoundaryStepWithMatchingResult` (line 327)
- **Issue**: The engagement assertion uses `admin.getSteps().stream().anyMatch(s -> s instanceof YTDBMatchPlanStep<?, ?>)`. This passes if a `YTDBMatchPlanStep` exists anywhere in the step list — including a regression that left dead steps around the boundary. The result-correctness pin (`assertEquals(List.of("Alice"), names)`) would still surface most regressions because dead steps would change the iteration output, but a dead step that happens to be a no-op (e.g., a stale `IdentityStep`) would slip past both assertions.
- **Evidence (FALSIFIABILITY CHECK)**: A regression where the splice inserted the boundary step but failed to remove the original `YTDBGraphStep` — leaving `[YTDBMatchPlanStep, YTDBGraphStep]` — would pass `anyMatch` and might also pass the iteration check if the boundary supplier short-circuits before the trailing step runs. Not a likely regression but cheap to pin.
- **Missing behavior**: Pin step count and boundary position.
- **Suggested fix**:
  ```java
  assertEquals(1, admin.getSteps().size());
  assertTrue(admin.getStartStep() instanceof YTDBMatchPlanStep<?, ?>);
  ```

#### TB-6 — `V_hasLabel_Person_Place` decline case does not exercise the `Contains.within` decline path with a non-degenerate result-multiset

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`, case `V_hasLabel_Person_Place` (line 191–194)
- **Issue**: The case is correctly pinned as `DECLINED` (predicate is `P.within(...)`, not `Compare.eq`, so `collectFolded` returns null). The assertion shape is right — `boundaryStep_engagementMatchesExpected` will fail if the recogniser starts translating multi-class. However, the user's review prompt asks specifically: "would the test catch a regression that translates `hasLabel("Person", "Place")`?" Yes — both assertions fail (Berlin would be missing from the translated multiset because the recogniser would see only one of the two predicates). But the case carries no comment documenting *why* the result-multiset comparison would catch a partial-translation regression. The fixture happens to cover this (Berlin is a Place vertex, line 247) — but a future refactor that drops Berlin from the seed would silently weaken this case.
- **Suggested fix**: Add a comment to the seed fixture explaining that Berlin's presence as the only Place is load-bearing for the multi-class hasLabel decline check:
  ```java
  // Berlin (the only Place vertex) is load-bearing for the
  // V_hasLabel_Person_Place decline case — a regression that incorrectly
  // translated multi-class hasLabel to single-class would drop Berlin from
  // the translated result and fail the equivalence assertion.
  var berlin = graph.addVertex(T.label, "Place", "name", "Berlin");
  ```

## Notes

- The flipped declines (`startWithHasContainers_isDeclined` →
  `startWithFoldedHasContainer_translatesToBoundaryStepWithMatchingResult`,
  `apply_declinesOnNonEmptyHasContainers` →
  `apply_proceedsWhenHasContainersPresent`,
  `translateGV_startStepWithHasContainers_declines` →
  `translateGV_startStepWithFoldedHasLabel_narrowsAliasClass`) are
  semantically the right inversions — the previous tests pinned a
  "must decline" gate, the new tests pin a "must proceed and produce
  output X" contract. The findings above tighten the assertion shape
  but the inversions themselves are sound.
- The non-polymorphic + folded `T.label` write path (line 167–168 of
  `StartStepRecogniser.java`) is a new code path that is not exercised
  by any test in this step — neither the unit tests (which use the
  default polymorphic mode) nor the equivalence harness (which adds no
  `shapePolymorphic(... false)` case for folded HasContainers). This
  is missing-coverage, not a precision flaw, and would normally fall
  outside this review's scope; flagging because the gate-lift in this
  step is precisely what makes that path reachable.
