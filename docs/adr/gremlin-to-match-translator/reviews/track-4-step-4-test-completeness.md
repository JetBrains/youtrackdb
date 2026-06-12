# Track 4, Step 4 — Test Completeness Review

Commit reviewed: `3c363fe6f0` (Translate folded HasContainers on the start step)

Production code under review:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java`
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategy.java`

Test files under review:
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/GremlinToMatchSmokeTest.java`
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategyTest.java`
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchTranslatorTest.java`

Tooling caveat: `mcp-steroid` was unreachable; references in this review come from `git show`, ripgrep, and direct file reads. Line numbers are accurate to the commit snapshot.

## Summary

The cases the plan explicitly named (`g.V().has(prop, eq(v))`, `g.V().has(prop, eq(v)).out(label)`, `g.V().hasLabel("Person")`, multi-class hasLabel decline) are all pinned in `EdgeTraversalEquivalenceTest`. The equivalence harness compares native vs. translated multisets so result-correctness is exercised, not just label-engagement.

However, `collectFolded` and `extractIdsFromPredicate` introduce a meaningful set of new branches in `StartStepRecogniser` (T.id intersection, T.label decline conditions, predicate-adapter decline propagation, empty-intersection writing `@rid IN []`, mixed multi-bucket folded containers) and the new tests under-sample those branches. There is also no dedicated `StartStepRecogniserTest` unit class — the cross-cutting unit-level surface is covered only by the integration-flavoured `GremlinToMatchTranslatorTest`, which tests the whole translator rather than the new method.

The most material gaps are concentrated around T.id-keyed folded containers (no test exercises this code path at all in any harness), the empty-intersection `@rid IN []` write, and start IDs combined with folded label/property predicates.

## Phase 1 Premises (selected)

- P1: `StartStepRecogniser.collectFolded` (`StartStepRecogniser.java:222-280`) walks `graphStep.getHasContainers()` and dispatches on key into one of three buckets: T.id (lines 236-250), T.label (lines 251-266), property (lines 267-276).
- P2: `StartStepRecogniser.extractIdsFromPredicate` (`StartStepRecogniser.java:288-314`) recognises `Compare.eq` (single ID) and `Contains.within` (collection of IDs); declines on every other predicate.
- P3: The `idConstraint` accumulator (line 224) starts populated from `startRids` if non-empty, otherwise empty Optional. Subsequent T.id HasContainers either initialise the constraint (line 242) or **intersect** with the existing constraint (lines 247-249).
- P4: A single-ID intersection result writes through `aliasRids` (line 159); multi-ID writes through `buildRidInExpression` → `aliasFilters` (line 161); an empty intersection writes `@rid IN []` (lines 152-163, comment "intersection wiped out the candidate IDs").
- P5: T.label HasContainer with `Compare.eq` + non-blank String value narrows `narrowedClass` (line 266); two contradictory T.label values decline (lines 260-265); blank/null/non-eq/non-string declines (lines 256-258).
- P6: Property HasContainer routes through `GremlinPredicateAdapter.INSTANCE.toBooleanExpression(predicate, key)` (line 268). `Optional.empty()` from the adapter declines the entire start step (lines 269-271).
- P7: Multiple property HasContainers AND-merge through `WHERE.and` (lines 272-276).

## Phase 2 Input Domain Table

For `StartStepRecogniser.collectFolded` and `recognize`:

| Parameter / state | Boundary values | Currently tested? | Evidence |
|---|---|---|---|
| `graphStep.getHasContainers()` size | empty, 1, multiple-same-bucket, multiple-mixed-buckets | YES empty (`V()`); YES single property (`V_has_name_Alice`); PARTIAL multiple-mixed (`V_hasLabel_Person_has_name_Alice` covers label+property; no T.id+label, no T.id+property, no two-property AND); NO multiple-same-bucket (two T.id, two property, two T.label same-name) | `EdgeTraversalEquivalenceTest:173-194` |
| `graphStep.getIds()` × T.id container interaction | start IDs empty + T.id eq/within; start IDs non-empty + T.id eq (intersect → 1); start IDs non-empty + T.id within (intersect → multi); start IDs non-empty + T.id with empty intersection | NONE — no test in any harness exercises a folded T.id container | grep over all test files for `T.id` / `hasId(` returns zero matches |
| Empty intersection collapse | `g.V(id1).hasId(id2)` (different IDs) producing `@rid IN []` | NO — code path at lines 156-163 with empty `ids` list is not executed by any test | grep "empty intersection\|IN \[\]" returns zero matches |
| T.label predicate type | `Compare.eq("X")`; `Compare.eq("")` (blank); `Compare.eq(null)`; `P.within("X","Y")`; `P.neq("X")`; non-string value | YES eq+non-blank (`V_hasLabel_Person`); YES `P.within(...)` decline (`V_hasLabel_Person_Place`); NO blank-string decline; NO non-eq-predicate decline; NO non-string-value decline | `EdgeTraversalEquivalenceTest:182-194` |
| Two T.label containers same alias | same name (idempotent merge); different name (contradictory decline) | NEITHER — multi-T.label folding into the same start step is unpinned in any test | none |
| Property predicate decline propagation | adapter returns `Optional.empty()` (e.g. `P.gt(null)`) on the start step | NO — adapter unit tests cover the empty case but no equivalence/integration test verifies that the whole traversal declines when a folded property predicate declines | `GremlinPredicateAdapterTest:117` covers the adapter; no decline-propagation test on `StartStepRecogniser` |
| Multiple property HasContainers | `has("a", v1).has("b", v2)` AND-merged | NO — no test covers two property HasContainers folded onto the same start step | `WHERE.and` call at line 275 has no test coverage |
| Polymorphism mode × folded label | `hasLabel("Person")` under `polymorphic=true` (matches subclasses); under `polymorphic=false` (exact match) | NO — `shapePolymorphic` factory is defined but not used by any of the new cases; only the session default (`null`, which resolves to `true`) is exercised | `EdgeTraversalEquivalenceTest:208-224` (factories), no callers |
| `hc.getPredicate() == null` | folded HasContainer with null predicate | NO — line 232 declines, untested | none |
| T.id `Compare.eq` value type | Identifiable, RID string, malformed string, unrecognised type | NO — `extractIdsFromPredicate` lines 290-298 untested entirely | none |
| T.id `Contains.within` value | `Collection`, non-Collection, empty Collection, Collection with one bad element | NO — lines 299-311 untested entirely | none |
| Start IDs + folded label | `g.V(id).hasLabel("Person")` (start IDs land in idConstraint, label narrows class) | NO — combined start-ID + folded-label start-step shape unpinned | grep returns zero hits |

## Phase 3 — Formal Claims

### CLAIM TC1: T.id-keyed folded HasContainer handling is entirely untested

`StartStepRecogniser.collectFolded` lines 236-250 and `extractIdsFromPredicate` lines 288-314 form a 27-line code block with at least 6 distinct branches (`Compare.eq` vs `Contains.within`, single-RID vs multi-RID toRecordId conversion, intersect into existing vs initialise from empty start IDs, non-Collection-value decline, bad-element-in-Collection decline, unrecognised BiPredicate decline). No test in `EdgeTraversalEquivalenceTest`, `GremlinToMatchSmokeTest`, or `GremlinToMatchTranslatorTest` exercises any of these branches. The plan's verification methodology distributes hasId coverage to "Step 6: HasLabelStep + HasIdStep recognisers" — but that distribution is for the **mid-chain** `hasId` recogniser, while `g.V().hasId(id)` immediately after the start folds via `YTDBGraphStepStrategy` into the **start step's** hasContainers, which is exactly the path Step 4 introduced.

### CLAIM TC2: Empty-intersection `@rid IN []` write is unverified end-to-end

The recogniser explicitly preserves an empty intersection set as `@rid IN []` (StartStepRecogniser.java:152-163, see comment "preserved as `@rid IN []`"). The implicit contract is that the planner returns zero rows for this filter. The closest existing test is `translates_singleIdLookup_nonExistentRid_returnsEmpty` in `GremlinToMatchSmokeTest:138-151`, but that exercises a single non-existent RID (a non-empty constraint that simply doesn't match any vertex), not an empty IN list (which is a parser-level edge case for `SQLInCondition`).

### CLAIM TC3: T.label decline branches are partially covered

T.label decline triggers (`StartStepRecogniser.java:256-258, 260-265`):
- `biPredicate != Compare.eq` — triggers for `P.within(...)` (multi-class) and `P.neq("X")` (negation)
- `value not instanceof String` — triggers for hand-built predicates with non-string label types
- `name.isBlank()` — triggers for `hasLabel("")` and whitespace-only label
- contradictory two-T.label-narrowings — triggers for `hasLabel("Person").hasLabel("Other")` folded onto the same start step

Only the first decline (`P.within(...)`) is pinned, via `V_hasLabel_Person_Place`. The blank-string, non-eq-non-within, and contradictory-narrowing declines are unpinned.

### CLAIM TC4: Predicate-adapter decline propagation on the start step is unpinned

`StartStepRecogniser.collectFolded` line 269-271 returns `null` from `collectFolded` when the predicate adapter returns `Optional.empty()`, which propagates as a `false` recogniser return at line 128 — the entire traversal then declines under D3 all-or-nothing. The adapter's own unit tests (`GremlinPredicateAdapterTest:117`) cover the empty path, but no test verifies that this empty bubbles up through the **start step's folded-container path** specifically. A regression that swallowed the adapter's empty (e.g. by treating it as "no contribution" rather than "decline") would not be caught: the recogniser would silently drop the predicate and the translated traversal would return more vertices than the native pipeline. This would surface as an equivalence-test failure on a case that uses such a predicate, but no such case exists in `EdgeTraversalEquivalenceTest`.

### CLAIM TC5: Multiple property HasContainers AND-merge is unverified

The `WHERE.and(propertyPredicate, translated.get())` call at line 275 only fires when two or more property HasContainers fold onto the same start step (e.g. `g.V().has("name", "Alice").has("age", gt(30))`). No test covers this — `EdgeTraversalEquivalenceTest`'s `V_has_name_Alice` and `V_has_age_gt_30` test single property folds; `V_hasLabel_Person_has_name_Alice` mixes label+property buckets so the property-side AND merge does not run.

### CLAIM TC6: Mixed-bucket combinations beyond label+property are untested

The folded-container walker handles three buckets (T.id, T.label, property). The cross-product of meaningful 2-bucket combinations is:
- label + property: pinned (`V_hasLabel_Person_has_name_Alice`)
- label + id: untested (`g.V(id).hasLabel("Person")` and `g.V().hasLabel("Person").hasId(id)`)
- id + property: untested (`g.V(id).has("name", "Alice")`)
- all three: untested

A regression that only fired on a specific bucket combination (e.g., a bug where `narrowedClass` is overwritten when the intersection-collapse path runs) would not surface from the existing case set.

### CLAIM TC7: Polymorphism mode × folded label narrowing is unexercised

Step 4's diff sets `effectiveClass = folded.narrowedClass` (StartStepRecogniser.java:143-144) and feeds it into `MatchClassFilters.classEq(effectiveClass)` only when `!polymorphic` (line 168). The code path through line 168 with `narrowedClass != null` only runs under non-polymorphic mode with a folded T.label container. Step 2 deliberately introduced `shapePolymorphic` (`EdgeTraversalEquivalenceTest:208-224`) and the `Person ← Employee ← Manager` class hierarchy fixture (`seedFixture` lines 237-238) for exactly this purpose. Step 4 wires no `shapePolymorphic` cases. The plan's Step 4 description does not strictly require polymorphism cases (those are listed under Step 6: HasLabelStep), but the new code path in StartStepRecogniser is reachable only in the non-polymorphic mode and stays uncovered.

### CLAIM TC8: No `StartStepRecogniserTest` unit class

Track 4 sibling tests (`GremlinPredicateAdapterTest`, `VertexStepRecogniserTest`, `NoOpBarrierRecogniserTest`) all have dedicated unit classes. The Step 4 changes are tested only at the integration level (via `GremlinToMatchTranslatorTest` which goes through the whole translator, and the equivalence harness which goes through the whole strategy pipeline). The unit-level surface — `collectFolded`, `extractIdsFromPredicate`, the FoldedStartState record — has no dedicated unit class, so failures in the new branches surface only at the integration level where the diagnostic loop is longer.

## Phase 4 — Refutation Checks

### TC1 (T.id folded handling)
- Could the path be unreachable? No — `g.V().hasId(rid)` is the canonical Gremlin idiom for "vertex by ID after a generic V() start" and is regularly emitted by Gremlin clients (LDBC IS-class queries do this).
- Could the behaviour be trivially correct? No — `extractIdsFromPredicate` has type-narrowing instanceof checks and `LinkedHashSet` insertion-order semantics; the intersection logic at lines 247-249 has no test coverage at all.
- Indirect coverage? The Cucumber suite likely runs some `hasId` shapes, but those exercise the **mid-chain** `HasStep` form (which Step 4 didn't touch); the folded-into-start form is only exercised when `YTDBGraphStepStrategy` folds a HasStep adjacent to V() — which the Cucumber suite does, but a regression in `extractIdsFromPredicate` would surface there as a generic "Cucumber failure" rather than a targeted diagnosis.
- VERDICT: CONFIRMED meaningful gap — Critical.

### TC2 (empty `@rid IN []`)
- Could the path be unreachable? No — `g.V(id1).hasId(id2)` with `id1 != id2` lands here. The diff's own comment explicitly documents this case.
- Trivially correct? Likely, but the planner's behaviour with `@rid IN []` is a parser-level concern (does it short-circuit, does it iterate the full class scan with a filter that always returns false) and is not pinned anywhere.
- Indirect coverage? None — see TC1.
- VERDICT: CONFIRMED meaningful gap — Recommended (semantically important; Critical if the planner is shown to mis-handle empty IN lists, but absent that evidence, Recommended).

### TC3 (T.label decline branches)
- Could the paths be unreachable? `hasLabel("")` is permitted by the TinkerPop API (compiles fine), is silly user input but realistic. Multi-T.label folds when `hasLabel("A").hasLabel("B")` are both adjacent to V() — `YTDBGraphStepStrategy.java:126` simply forwards every HasContainer onto the start step, so two T.label containers do reach `collectFolded`.
- Trivially correct? Each decline branch returns null, which propagates to `false`, which routes to native — that's the desired behaviour. A regression would translate where it shouldn't, producing wrong results.
- Indirect coverage? None of the four sub-branches has a test.
- VERDICT: CONFIRMED — Recommended for blank-string and contradictory-narrowing (most likely user inputs); Minor for non-eq-predicate and non-string-value (less likely but the test cost is low).

### TC4 (predicate-adapter decline propagation)
- Could the path be unreachable? No — `gt(null)` compiles fine; the adapter's decline contract is explicit.
- Trivially correct? The bubble-up from `Optional.empty()` → `null` → `false` is a 3-line chain; a regression that broke any link silently translates a query the adapter said "I can't translate".
- Indirect coverage? None — `GremlinPredicateAdapterTest` covers the adapter; `EdgeTraversalEquivalenceTest` doesn't include a `gt(null)` case.
- VERDICT: CONFIRMED — Recommended.

### TC5 (multi-property AND merge)
- Could the path be unreachable? No — `g.V().has("a", v1).has("b", v2)` is in every Gremlin tutorial; it's the dominant LDBC IC1 shape.
- Trivially correct? `WHERE.and(...)` is a builder call, but the AND-merge into an *existing* `propertyPredicate` non-null path (line 275) is the exact target of "uniform write contract" from the plan's §(3); a regression here is exactly the kind of bug the contract is meant to prevent.
- Indirect coverage? Cucumber may exercise this; but the diagnosis loop is long.
- VERDICT: CONFIRMED — Recommended.

### TC6 (mixed-bucket combinations beyond label+property)
- Could the paths be unreachable? `g.V(id).hasLabel("Person")` and `g.V(id).has("name", "Alice")` are both common — start with a known RID and narrow further.
- Trivially correct? The interaction between `idConstraint`-via-startRids (initialised at line 224) and a same-iteration label or property write is not exercised; a bug in iteration order or accumulator reset would surface here.
- Indirect coverage? Possibly via Cucumber but unspecific.
- VERDICT: CONFIRMED — Recommended.

### TC7 (polymorphism × folded label)
- Could the path be unreachable? No — `polymorphic=false` is a documented, supported configuration.
- Trivially correct? `effectiveClass = folded.narrowedClass` + `MatchClassFilters.classEq(effectiveClass)` is a 2-line interaction; the only way to verify the result-set narrows correctly (excludes subclasses) is to run it under both modes against the seeded class hierarchy. The hierarchy is in the fixture; the mode plumbing is in the harness; this gap is purely "no case lines them up".
- Indirect coverage? None.
- VERDICT: CONFIRMED — Recommended.

### TC8 (no StartStepRecogniserTest)
- Could the unit class be replaced by integration coverage? Partly — `GremlinToMatchTranslatorTest` exercises `translatePrefix` end-to-end. But specific failure modes inside `collectFolded` (e.g., "two T.label containers contradictory") are awkward to construct via Gremlin's public API because `YTDBGraphStepStrategy` doesn't normally fold two T.label containers; reaching that branch via the public path requires a fixture HasContainer injection.
- VERDICT: CONFIRMED but lower-impact — Suggestion.

## Findings

### Critical

#### TC1: T.id folded HasContainer handling has zero test coverage

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`, `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchTranslatorTest.java`
- **Production code**: `StartStepRecogniser.java:236-250` (T.id branch in `collectFolded`) and `StartStepRecogniser.java:288-314` (`extractIdsFromPredicate`)
- **Missing scenario**: No test exercises a folded `T.id`-keyed HasContainer at all. The dispatch into `extractIdsFromPredicate` (line 237), the `Compare.eq` single-RID path (lines 290-298), the `Contains.within` multi-RID path (lines 299-311), the unrecognised-BiPredicate decline (line 313), the intersection-with-existing-startRids path (lines 247-249), and the initialise-from-empty-startRids path (lines 241-242) are all uncovered.
- **Why it matters**: `g.V().hasId(rid)` is a frequent Gremlin idiom (folds via `YTDBGraphStepStrategy` into the start step's hasContainers as a T.id container). A regression in `extractIdsFromPredicate` — silently dropping the constraint, returning the wrong RID, or failing to intersect against startRids — would produce wrong results that the equivalence harness has no case to catch.
- **Evidence**: Input domain table row "graphStep.getIds() × T.id container interaction" — every cell "NONE".
- **Refutation considered**: Cucumber would catch a gross regression but not pinpoint the cause; `hasId` is in scope for Step 4 because folding turns it into a start-step concern even though the plan reserves the **mid-chain** `hasId` recogniser for Step 6.
- **Suggested tests**:
  ```java
  // EdgeTraversalEquivalenceTest cases
  shape("V_hasId_byRecordId",
        g -> g.V().hasId(g.V().has("name", "Alice").next().id()),
        Expected.RECOGNIZED),
  shape("V_with_id_and_hasId_intersect",
        g -> {
          var alice = g.V().has("name", "Alice").next().id();
          var bob = g.V().has("name", "Bob").next().id();
          return g.V(alice, bob).hasId(alice);  // intersect → {alice}
        },
        Expected.RECOGNIZED),
  shape("V_with_id_and_hasId_disjoint",
        g -> {
          var alice = g.V().has("name", "Alice").next().id();
          var bob = g.V().has("name", "Bob").next().id();
          return g.V(alice).hasId(bob);  // intersect → {} → no rows
        },
        Expected.RECOGNIZED),
  shape("V_hasId_within_multiple",
        g -> {
          var alice = g.V().has("name", "Alice").next().id();
          var bob = g.V().has("name", "Bob").next().id();
          return g.V().hasId(P.within(alice, bob));
        },
        Expected.RECOGNIZED),
  ```

  Plus a `GremlinToMatchTranslatorTest` unit case that pins the AST shape (`aliasRids` for single-element intersection, `aliasFilters` `@rid IN [...]` for multi-element, `@rid IN []` for empty intersection).

### Recommended

#### TC2: Empty-intersection `@rid IN []` write produces no end-to-end zero-row guarantee

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/GremlinToMatchSmokeTest.java`
- **Production code**: `StartStepRecogniser.java:152-163` and the comment at lines 152-154 documenting the intent
- **Missing scenario**: `g.V(id1).hasId(id2)` with `id1 != id2` — the intersection collapses the candidate ID set to empty, the recogniser writes `@rid IN []`, and the planner is expected to return zero rows.
- **Why it matters**: The diff's own comment makes this contract explicit ("matches the native pipeline's empty-result for impossible ID intersections"). Without a smoke test, a planner-side regression that mis-handles `IN []` (e.g., treats it as "no filter") would silently broaden the result set.
- **Evidence**: Input domain table row "Empty intersection collapse" — NO. The path at `buildRidInExpression` with an empty list produces an `SQLCollection` with zero entries; this collection is not exercised by `translateGV_multiIds_buildsAliasFilterWithRidInClause` (3 entries) or any other test.
- **Refutation considered**: `translates_singleIdLookup_nonExistentRid_returnsEmpty` covers a single non-existent RID, which is a different code path (`aliasRids` populated with `#999:0`, planner returns zero rows because that RID is unassigned). The empty-IN path is structurally different and unverified.
- **Suggested test**:
  ```java
  // GremlinToMatchSmokeTest
  @Test
  public void translates_disjointIdAndHasId_returnsEmptyViaEmptyRidInList() {
    session.createVertexClass("Person");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    // g.V(alice).hasId(bob.id()): disjoint → @rid IN [] → zero rows.
    var traversal = graph.traversal().V(alice.id()).hasId(bob.id());
    var admin = traversal.asAdmin();
    admin.applyStrategies();

    assertTrue(admin.getStartStep() instanceof YTDBMatchPlanStep<?, ?>);
    assertEquals(0, traversal.toList().size());
  }
  ```

#### TC3a: T.label `hasLabel("")` (blank string) decline is unpinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchTranslatorTest.java`
- **Production code**: `StartStepRecogniser.java:256-258` (`name.isBlank()` decline)
- **Missing scenario**: `g.V().hasLabel("")` folds a T.label container with `Compare.eq("")` into the start step; the recogniser should decline so the traversal stays native.
- **Why it matters**: A regression that dropped `name.isBlank()` would translate to `aliasClasses["$g2m_v0"] = ""` and the planner would either fail or produce surprising results. `name.isBlank()` is also true for whitespace-only strings — both paths uncovered.
- **Evidence**: Input domain table row "T.label predicate type" — "NO blank-string decline".
- **Refutation considered**: User input is unlikely but the API doesn't reject it; a misuse should fall back to native, not crash or return wrong results.
- **Suggested test**:
  ```java
  // GremlinToMatchTranslatorTest
  @Test
  public void translateGV_foldedHasLabelBlank_declines() {
    var admin = primeStartStep(graph.traversal().V().hasLabel(""));
    assertEquals(java.util.Optional.empty(), GremlinToMatchTranslator.translatePrefix(admin));
  }

  @Test
  public void translateGV_foldedHasLabelWhitespace_declines() {
    var admin = primeStartStep(graph.traversal().V().hasLabel("   "));
    assertEquals(java.util.Optional.empty(), GremlinToMatchTranslator.translatePrefix(admin));
  }
  ```

#### TC3b: Contradictory two-T.label-narrowings decline is unpinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchTranslatorTest.java`
- **Production code**: `StartStepRecogniser.java:260-265`
- **Missing scenario**: `g.V().hasLabel("Person").hasLabel("Other")` — both T.label containers fold onto the start step (per `YTDBGraphStepStrategy.java:126` adjacent-HasStep absorption). The recogniser should detect the contradiction and decline.
- **Why it matters**: The branch is reachable, distinct from the multi-class P.within branch (which is a single container), and a regression that overwrote `narrowedClass` instead of declining would translate to a class that excludes valid matches. The native pipeline returns zero rows for any two-class contradiction, which is correct; the translator's "decline rather than model contradictions" contract preserves that semantically.
- **Suggested test**:
  ```java
  @Test
  public void translateGV_foldedTwoContradictoryHasLabels_declines() {
    var admin = primeStartStep(graph.traversal().V().hasLabel("Person").hasLabel("Place"));
    // Verify both T.label containers folded onto the start step (defence-in-depth precondition).
    var graphStep = (com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep<?, ?>)
        admin.getStartStep();
    long labelContainers = graphStep.getHasContainers().stream()
        .filter(hc -> org.apache.tinkerpop.gremlin.structure.T.label.getAccessor().equals(hc.getKey()))
        .count();
    assertEquals(2, labelContainers);
    assertEquals(java.util.Optional.empty(), GremlinToMatchTranslator.translatePrefix(admin));
  }
  ```

#### TC4: Predicate-adapter decline propagation on the start step is unpinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`
- **Production code**: `StartStepRecogniser.java:269-271` (the `if (translated.isEmpty()) return null` branch)
- **Missing scenario**: `g.V().has("age", P.gt(null))` — adapter declines, recogniser propagates to a `false` return at line 128, traversal goes native.
- **Why it matters**: D3 all-or-nothing: when the adapter declines, the entire traversal must decline. A regression that swallowed `Optional.empty()` (e.g., translating to "no filter") would translate the traversal incorrectly without an obvious failure mode at any other test boundary.
- **Refutation considered**: The adapter's own unit tests cover empty; but the **bubble-up** through `collectFolded` and into `recognize`'s `false` return is a separate concern.
- **Suggested test**:
  ```java
  // EdgeTraversalEquivalenceTest
  shape("V_has_age_gt_null_declines",
        g -> g.V().has("age",
            org.apache.tinkerpop.gremlin.process.traversal.P.gt((Object) null)),
        Expected.DECLINED),
  ```

#### TC5: Multiple property-HasContainer AND-merge on start step is unverified

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`
- **Production code**: `StartStepRecogniser.java:272-276` — the `WHERE.and(propertyPredicate, translated.get())` branch only fires when `propertyPredicate != null` (i.e., second-or-later property HasContainer)
- **Missing scenario**: `g.V().has("name", "Alice").has("age", P.gt(0))` — two property HasContainers fold; the recogniser AND-merges them.
- **Why it matters**: The dominant LDBC IC1 query shape is multi-property filtering on a vertex; if AND-merge has a bug, the dominant production query is broken.
- **Suggested test**:
  ```java
  shape("V_has_name_Alice_and_has_age_gt_0",
        g -> g.V()
              .has("name", "Alice")
              .has("age", org.apache.tinkerpop.gremlin.process.traversal.P.gt(0)),
        Expected.RECOGNIZED),
  ```
  (The fixture vertices don't currently have `age` properties; either add `age` to the seeded Persons or pick another property the fixture already has.)

#### TC6: Mixed-bucket combinations beyond label+property are untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`
- **Production code**: `StartStepRecogniser.java:222-280` (the `for (HasContainer hc : graphStep.getHasContainers())` loop iterates across buckets in the order TinkerPop emits them)
- **Missing scenarios**:
  - `g.V(id).hasLabel("Person")` (start IDs + folded label)
  - `g.V(id).has("name", "Alice")` (start IDs + folded property)
  - `g.V().hasLabel("Person").hasId(id).has("name", "Alice")` (all three buckets)
- **Why it matters**: The accumulator state (`idConstraint`, `narrowedClass`, `propertyPredicate`) is updated per-iteration with no test for cross-bucket interactions.
- **Suggested tests**:
  ```java
  shape("V_id_with_hasLabel",
        g -> {
          var alice = g.V().has("name", "Alice").next().id();
          return g.V(alice).hasLabel("Person");
        },
        Expected.RECOGNIZED),
  shape("V_id_with_property",
        g -> {
          var alice = g.V().has("name", "Alice").next().id();
          return g.V(alice).has("name", "Alice");
        },
        Expected.RECOGNIZED),
  shape("V_hasLabel_hasId_has_name",
        g -> {
          var alice = g.V().has("name", "Alice").next().id();
          return g.V().hasLabel("Person").hasId(alice).has("name", "Alice");
        },
        Expected.RECOGNIZED),
  ```

#### TC7: Polymorphism mode × folded T.label narrowing is untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`
- **Production code**: `StartStepRecogniser.java:143-144, 167-168` — `effectiveClass` and the `!polymorphic` branch combine.
- **Missing scenarios**:
  - `g.V().hasLabel("Person")` under `polymorphic=true` against the `Person ← Employee ← Manager` hierarchy: must include Employee/Manager instances in the result.
  - Same shape under `polymorphic=false`: must exclude Employee/Manager (exact `@class = 'Person'`).
- **Why it matters**: The seedFixture (`EdgeTraversalEquivalenceTest:237-238`) intentionally creates the class hierarchy; the `shapePolymorphic` factory is intentionally available; Step 4 is the first place a `narrowedClass` flows into the polymorphic-vs-non-polymorphic decision and neither mode is tested.
- **Refutation considered**: The plan reserves polymorphism × hasLabel cases for Step 6 (HasLabelStep recogniser, mid-chain). Step 4 produces the same write through a different code path (folded T.label rather than mid-chain `YTDBHasLabelStep`); pinning both modes here would catch a regression specific to the start-step path that the Step 6 cases would miss.
- **Suggested tests**:
  ```java
  // Add Employee/Manager seed instances first (currently the hierarchy is created
  // but no instances of Employee/Manager are added).
  // In seedFixture(), after graph.addVertex(T.label, "Place", ...):
  //   graph.addVertex(T.label, "Employee", "name", "Eve");
  //   graph.addVertex(T.label, "Manager", "name", "Mallory");

  shapePolymorphic("V_hasLabel_Person_polymorphicTrue",
                   g -> g.V().hasLabel("Person"),
                   Expected.RECOGNIZED, true),
  shapePolymorphic("V_hasLabel_Person_polymorphicFalse",
                   g -> g.V().hasLabel("Person"),
                   Expected.RECOGNIZED, false),
  ```

### Minor

#### TC3c: Non-eq, non-string T.label predicate decline branches

- **Production code**: `StartStepRecogniser.java:256-258` — `biPredicate != Compare.eq` (non-eq) and `!(value instanceof String)` (non-string).
- **Missing scenario**: A hand-built `P.neq("Person")` on T.label, or a numeric value passed where a label is expected.
- **Why it matters**: Defence-in-depth; the canonical Gremlin builder produces only `Compare.eq` String predicates for `hasLabel(name)` and `Contains.within` for multi-class. A non-canonical caller (custom DSL) might construct a non-eq label predicate. Decline is the right behaviour but the path is uncovered.
- **Suggested test**: Unit-level (translator-level) test that injects a hand-built HasContainer with `P.neq("Person")` keyed `T.label.getAccessor()`. Lower priority than TC3a/TC3b because user-facing Gremlin doesn't normally produce these.

#### TC8: No dedicated `StartStepRecogniserTest` unit class

- **Missing scenario**: Many of the above gaps would be cleaner to write at the unit level — direct calls to `StartStepRecogniser.INSTANCE.recognize(step, ctx)` with synthetic `WalkerContext` (via `WalkerContextFixtures`) and synthetic `YTDBGraphStep` instances carrying hand-built HasContainers.
- **Why it matters**: Sibling recognisers all have unit classes (`VertexStepRecogniserTest`, `NoOpBarrierRecogniserTest`); Step 4 grew `StartStepRecogniser` substantially without adding a sibling unit test class. Branches like "two contradictory T.label" or "T.id Compare.eq with non-Identifiable, non-String value" are awkward to construct via the public Gremlin API.
- **Suggested test**: Create `StartStepRecogniserTest` and migrate the integration-flavoured cases in `GremlinToMatchTranslatorTest` that directly target the recogniser into it; cover branches that are inaccessible via the public API there. Lower-priority because the integration-level coverage gives some coverage signal already.

#### TC9: `hc.getPredicate() == null` decline branch

- **Production code**: `StartStepRecogniser.java:232-234`
- **Missing scenario**: A folded HasContainer whose predicate is null. Practically unreachable via the canonical Gremlin builder (HasContainer constructors set the predicate), so this is genuinely defensive code.
- **Why it matters**: Removable code if it's truly unreachable; if kept, a one-line unit test pinning it surfaces any future regression.
- **Suggested test**: Either remove the branch or add a unit test in a new `StartStepRecogniserTest` that injects a HasContainer with a null predicate.

## What is well-covered

- Single property fold: `V_has_name_Alice` (equivalence) and `startWithFoldedHasContainer_translatesToBoundaryStepWithMatchingResult` (smoke; result correctness pinned).
- Property-with-predicate fold: `V_has_age_gt_30` exercises a non-eq predicate folded onto the start.
- Folded property + chain target: `V_has_name_Alice_out_Knows` keeps the chain alive after the start-step fold (good multi-recogniser coverage).
- Single-class folded label: `V_hasLabel_Person` and `translateGV_startStepWithFoldedHasLabel_narrowsAliasClass` (translator-level alias-class assertion).
- Multi-class folded label decline: `V_hasLabel_Person_Place` pins the single-class-only contract.
- Strategy gate-lift: `apply_proceedsWhenHasContainersPresent` confirms the strategy no longer declines on non-empty hasContainers.
- Equivalence harness asserts result-multiset equality, not just label engagement, so RECOGNIZED cases pin Gremlin-vs-MATCH semantic parity.
- Step-6 territory is correctly avoided: no Step-4 case exercises chain-target hasLabel (`g.V().out(label).hasLabel("X")`).

## Suggested prioritised remediation

1. TC1 (Critical) — add T.id-folded-container coverage (4 equivalence cases + 1 translator-level shape pin) before merging.
2. TC2 (Recommended) — add empty-intersection `@rid IN []` smoke test (1 case).
3. TC4 (Recommended) — add `gt(null)` decline-propagation case (1 equivalence case).
4. TC5 (Recommended) — add multi-property AND-merge case (1 equivalence case).
5. TC6 (Recommended) — add mixed-bucket combinations (3 equivalence cases).
6. TC7 (Recommended) — add polymorphism × folded label cases (2 equivalence cases) and seed Employee/Manager instances.
7. TC3a, TC3b (Recommended) — add blank-string and contradictory-narrowing decline cases (2 translator-level cases).
8. TC8 (Suggestion) — open follow-up to add `StartStepRecogniserTest`.
9. TC3c, TC9 (Minor) — defer or close out via the new unit class.
