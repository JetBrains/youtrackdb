<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 4, suggestion: 4}
index:
  - {id: TB1, sev: should-fix, loc: EdgeTraversalEquivalenceTest.java:554, anchor: "### TB1 ", cert: C1, basis: "RECOGNIZED equivalence never asserts non-empty; both-empty seed regression passes vacuously"}
  - {id: TC1, sev: should-fix, loc: EdgeTraversalEquivalenceTest.java:504, anchor: "### TC1 ", cert: C4, basis: "edge-subclass label acceptance criterion untested; fixture creates no edge subclass"}
  - {id: TC2, sev: should-fix, loc: GremlinPredicateAdapter.java:108, anchor: "### TC2 ", cert: C5, basis: "null-value decline branch (P.eq(null)) untested"}
  - {id: TC3, sev: should-fix, loc: GremlinStepWalker.java:196, anchor: "### TC3 ", cert: C6, basis: "cursor-guard overrun branch (stepIndex > size) untested"}
  - {id: TB2, sev: suggestion,  loc: GremlinPredicateAdapterTest.java:34, anchor: "### TB2 ", cert: C2, basis: "accept-path tests assert operator+field but never the literal value operand"}
  - {id: TB3, sev: suggestion,  loc: MatchClassFiltersTest.java:27, anchor: "### TB3 ", cert: C3, basis: "string-contains assertion cannot distinguish @class='Person' from @class=Person"}
  - {id: TC4, sev: suggestion,  loc: MatchPatternBuilderTest.java:366, anchor: "### TC4 ", cert: C7, basis: "addEdgeAsNode BOTH-direction (bothE/bothV) switch arms untested"}
  - {id: TC5, sev: suggestion,  loc: EdgeStepRecogniserTest.java:249, anchor: "### TC5 ", cert: C8, basis: "EdgeStepRecogniser single-blank-edge-label decline branch untested"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 8}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
flags: [CONTRACT_OK]
-->

## Findings

### TB1 [should-fix] RECOGNIZED equivalence cases pass vacuously if the fixture ever seeds nothing

**File:** `EdgeTraversalEquivalenceTest.java`, `assertEquivalent` (line ~554), used by every RECOGNIZED case.

**Issue:** The helper asserts `boundaryOn == 1`, `boundaryOff == 0`, and `onIds.isEqualTo(offIds)`. It never asserts the result multiset is non-empty. If a seed helper stops persisting data (a `createVertexClass` / schema regression, a `commit()` that silently no-ops, a base-class rename), both the translator-on and translator-off runs return `[]`, `onIds == offIds` holds on two empty lists, and `boundaryOn == 1` still holds because the plan is built regardless of data — so the case goes green while verifying nothing.

**Evidence:** FALSIFIABILITY CHECK — the only false-green for an on-vs-off comparison is *both sides empty* (native is the reference, so an equal-but-wrong result is impossible). Every RECOGNIZED case seeds matching data, so all should be non-empty; only `nonPolymorphicBareHop_doesNotUndercountSubclassTargets` sharpens with a follow-up `containsExactly("Person")` — the other ~13 RECOGNIZED cases rely solely on the equality assertion. This is the track's headline acceptance fixture, so the vacuity gap sits on the load-bearing test.

**Missing behavior:** For a RECOGNIZED shape with a known-matching fixture, the result must be non-empty; otherwise the equivalence is untested.

**Suggested fix (single-line hardening in the helper, covers all cases):**
```java
if (expected == Recognition.RECOGNIZED) {
  assertThat(boundaryOn)
      .as(scenario + " (translator on) must engage exactly one boundary step").isEqualTo(1);
  assertThat(onIds)
      .as(scenario + ": a RECOGNIZED fixture must return a non-empty result "
          + "(else the multiset equality is vacuous)")
      .isNotEmpty();
}
```

### TB2 [suggestion] Predicate-adapter accept tests never assert the compared value

**File:** `GremlinPredicateAdapterTest.java`, `eq_mapsToEqualsOperator` / `neq_` / `lt_` / `lte_` / `gt_` / `gte_` / `stringValue_isAccepted` (lines ~34-70).

**Issue:** Each accept test asserts `condition.getOperator()` and, in a few, `renderLeft(condition)` (the field name). None assert the right operand. `MatchWhereBuilder.op(field, operator, value)` sets `left = fieldExpression(field)`, `right = value` (confirmed), and `renderLeft` renders only `getLeft()`. A regression that dropped the literal, substituted a constant, or swapped operands would still pass these unit tests.

**Evidence:** ASSERTION PRECISION CHECK — production value for `has("since", P.eq(2010))` is `since = 2010`; the test verifies `since` and `=` but not `2010`. Partly compensated: `EdgeTraversalEquivalenceTest` exercises `lt(2015)` / `gte(2015)` / `eq(2010)` values behaviorally (discriminating Bob@2010 from Carol@2020), so a systematic value bug is caught end-to-end. The `neq` / `gt` / `lte` value paths are pinned only by these unit tests, which skip the value — hence a precision improvement rather than a correctness hole.

**Suggested fix:**
```java
@Test
public void eq_rendersFieldOpValue() {
  var sb = new StringBuilder();
  translateScalar("since", P.eq(2010)).toString(new HashMap<>(), sb);
  assertThat(sb.toString()).isEqualTo("since = 2010");
}
```

### TB3 [suggestion] MatchClassFilters render assertion cannot catch an unquoted class name

**File:** `MatchClassFiltersTest.java`, `classEquals_rendersExactClassEqualityCondition` / `classEqualsWhere_wrapsConditionInWhereClause` (lines ~27, ~42).

**Issue:** Both assert `rendered.contains("@class")`, `.contains("=")`, `.contains("Person")` on `toString()`. A string-contains check on structured AST cannot distinguish the correct `@class = 'Person'` (string literal) from the wrong `@class = Person` (bare identifier / variable), since both contain the substring `Person`. The right operand comes from `stringLiteral(value)` → `SQLBaseExpression(value)`; a quoting regression there would render an identifier and silently change the predicate's meaning, undetected.

**Evidence:** ASSERTION PRECISION CHECK — the assertion is a substring match, not a structural one. `MatchClassFilters` currently has no production caller in this track (its first caller is Track 4's folded `hasLabel`), so this is latent, but the test is the only guard on the helper until then.

**Suggested fix:** assert the typed shape rather than the rendered substring —
```java
var cond = (SQLBinaryCondition) MatchClassFilters.classEquals("Person");
assertThat(cond.getLeft().toString()).isEqualTo("@class");
assertThat(cond.getOperator()).isInstanceOf(SQLEqualsOperator.class);
// render the right operand and assert it is the quoted string literal 'Person'
var rhs = new StringBuilder();
cond.getRight().toString(new HashMap<>(), rhs);
assertThat(rhs.toString()).isEqualTo("'Person'");
```

### TC1 [should-fix] Edge-subclass label polymorphism is never exercised

**File:** `EdgeTraversalEquivalenceTest.java` (fixture helpers, ~line 504); no edge-subclass case exists.
**Production code:** `EdgeStepRecogniser` / `VertexStepRecogniser` single-label path → `MatchPatternBuilder.addEdge` / `addEdgeAsNode` edge label matching.

**Missing scenario:** No test creates an edge subclass (e.g. a `CloseFriend` edge class extending `knows`) and checks that `g.V().out("knows")` includes subclass-of-`knows` edges the same number of times translator-on as native. The track's Validation and Acceptance explicitly requires "edge-subclass labels behave as native `out()`", and Plan of Work item 9 called for a "Knows/Likes/Follows (with edge subclasses ...) graph". The fixture uses only flat `knows`/`likes` edges.

**Why it matters:** If native Gremlin `out("knows")` traverses subclass edges polymorphically while the translated `outE('knows')` matches the label exactly (or vice-versa), the two multisets diverge — an undercount or overcount that every current case misses because no edge subclass is ever present. This is the edge-side analogue of the vertex-subclass undercount that `nonPolymorphicBareHop_...` already pins.

**Refutation considered:** Vertex-subclass polymorphism is covered (`nonPolymorphicBareHop_...`), but edge-label polymorphism is a distinct axis with no coverage. Grep-only (mcp-steroid unreachable): I could not cheaply confirm YTDB MATCH edge-label match semantics, so the divergence direction is unconfirmed — the finding is the untested acceptance criterion, whichever way the behavior falls.

**Suggested test:**
```java
@Test
public void edgeSubclassLabel_behavesAsNativeOut() {
  session.command("CREATE CLASS CloseFriend EXTENDS knows");
  var alice = graph.addVertex(T.label, "Person", "name", "Alice");
  var bob = graph.addVertex(T.label, "Person", "name", "Bob");
  alice.addEdge("CloseFriend", bob); // subclass-of-knows edge
  graph.tx().commit();
  var aliceId = alice.id();
  assertEquivalent(
      "g.V(alice).out(knows) over a CloseFriend subclass edge",
      Recognition.RECOGNIZED,
      () -> graph.traversal().V(aliceId).out("knows"));
}
```

### TC2 [should-fix] GremlinPredicateAdapter null-value decline branch is untested

**File:** `GremlinPredicateAdapterTest.java` (decline section, ~line 96).
**Production code:** `GremlinPredicateAdapter.toFilter` — `if (value == null) return null;` (line ~108, `GremlinPredicateAdapter.java`).

**Missing scenario:** `has("k", P.eq(null))` produces a `Compare` predicate whose `getValue()` is null, reaching the `value == null` guard. No test drives it. `unsupportedValueType_declines` covers an unrenderable *type*, not a null *value*.

**Why it matters:** The guard is what keeps a null comparand from reaching `MatchLiteralBuilder.toLiteral`. If it regressed, a null value would either throw inside the (later) try/catch and decline anyway, or render as `field = null` — a different set-membership semantic (present-null vs decline). The branch guards a real divergence and has no regression pin.

**Refutation considered:** The value-null path is distinct from the type-unsupported path (evaluated before the `try`), so `unsupportedValueType_declines` does not cover it. Reachable via the public `P.eq(null)` API.

**Suggested test:**
```java
@Test
public void nullComparisonValue_declines() {
  assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.eq(null))))
      .as("a null comparison value must decline, not render field = null")
      .isNull();
}
```

### TC3 [should-fix] Walker cursor-guard overrun branch is untested

**File:** `GremlinStepWalkerTest.java` (walker-guard section, ~line 527).
**Production code:** `GremlinStepWalker.walk` guard `assert ctx.stepIndex > indexBefore && ctx.stepIndex <= steps.size()` plus `if (ctx.stepIndex <= indexBefore || ctx.stepIndex > steps.size()) return null;` (line ~196, `GremlinStepWalker.java`).

**Missing scenario:** `walk_recogniserClaimsWithoutAdvancing_tripsCursorAssert` covers the *no-advance* half (`stepIndex <= indexBefore`). No test covers the *overrun* half (`stepIndex > steps.size()`) — a recogniser that advances the cursor past the end of the list.

**Why it matters:** The guard has two failure modes; only one is pinned. A multi-step recogniser (`EdgeStepRecogniser` sets `stepIndex = probe`) that mis-counts and overruns would skip a step the walk never validated, producing a plan from an unverified suffix. Under `-ea` the overrun should trip the same `AssertionError`; that path is unexercised, so a future edit that weakened the upper bound would not be caught.

**Refutation considered:** In current code `EdgeStepRecogniser`'s `probe` cannot exceed `steps.size()`, so the branch is defensive — but it is an explicit guard on load-bearing walker infrastructure (Step 1 was risk:high) and the no-advance twin is tested, making the asymmetry a genuine gap.

**Suggested test:**
```java
@Test
public void walk_recogniserOverrunsCursor_tripsGuard() {
  StepRecogniser overrunning =
      (step, ctx) -> { ctx.stepIndex = ctx.traversal.getSteps().size() + 1; return true; };
  var walker = new GremlinStepWalker(Map.of(GraphStep.class, overrunning));
  assertThatThrownBy(() -> walker.walk(graph.traversal().V().asAdmin()))
      .as("a recogniser that advances past the end of the step list trips the guard")
      .isInstanceOf(AssertionError.class);
}
```

### TC4 [suggestion] addEdgeAsNode BOTH-direction arms have no test

**File:** `MatchPatternBuilderTest.java`, `addEdgeAsNode_*` tests (~line 366).
**Production code:** `MatchPatternBuilder.edgeMethodName` / `closingVertexMethodName` and `MatchEdgePathItems` — `case BOTH -> "bothE"` / `"bothV"`.

**Missing scenario:** The `addEdgeAsNode` tests exercise OUT (`outE`/`inV`) and IN (`inE`/`outV`) only. The `Direction.BOTH → bothE / bothV` switch arms are never rendered. They are currently unreachable in production (the `otherV` close declines in `EdgeStepRecogniser`), so this is documentation-level coverage of a live builder capability, not a correctness hole.

**Why it matters:** The builder advertises a BOTH capability that nothing verifies; if a later track wires `bothE`-as-node, the untested switch arm is the first thing it depends on.

**Suggested test:**
```java
@Test
public void addEdgeAsNode_bothDirection_rendersBothEThenBothV() {
  var ir = new MatchPatternBuilder()
      .addEdgeAsNode("from", "e0", "t0", Direction.BOTH, "Knows", Direction.BOTH, null).build();
  var edge = renderItem(ir.pattern().aliasToNode.get("from").out.iterator().next().item);
  var vertex = renderItem(ir.pattern().aliasToNode.get("e0").out.iterator().next().item);
  assertTrue(edge.startsWith(".bothE("));
  assertTrue(vertex.startsWith(".bothV("));
}
```

### TC5 [suggestion] EdgeStepRecogniser single-blank-edge-label decline branch is untested

**File:** `EdgeStepRecogniserTest.java` (decline section, ~line 249).
**Production code:** `EdgeStepRecogniser.recognize` — `if (edgeLabel == null || edgeLabel.isBlank()) return false;` (line ~108, `EdgeStepRecogniser.java`).

**Missing scenario:** `EdgeStepRecogniserTest` covers label-less (`length 0`) and multi-label (`length 2`) declines, but not the single-blank-label branch (`length 1`, value `"  "`). `VertexStepRecogniserTest.blankSingleLabel_declines` covers the mirror branch via a Mockito mock; the edge recogniser has no counterpart.

**Why it matters:** Symmetric defensive branch with no pin. The DSL rejects a blank label at construction, so a mock is required, exactly as the vertex test does.

**Suggested test:**
```java
@Test
public void blankSingleEdgeLabel_declines() {
  var admin = graph.traversal().V().asAdmin();
  var ctx = contextWithStartBoundary(admin);
  @SuppressWarnings("unchecked")
  VertexStep<?> blank = mock(VertexStep.class);
  when(blank.returnsEdge()).thenReturn(true);
  when(blank.getLabels()).thenReturn(java.util.Collections.emptySet());
  when(blank.getEdgeLabels()).thenReturn(new String[] {"  "});
  assertThat(EdgeStepRecogniser.INSTANCE.recognize(blank, ctx)).isFalse();
  assertContextUnmutated(ctx);
}
```

## Evidence base

#### C1 [CONFIRMED] TB1 — `assertEquivalent` asserts on/off equality but not non-emptiness; both-empty seed regression is a false green on the headline fixture. Only `nonPolymorphicBareHop_...` sharpens with `containsExactly`.

#### C2 [CONFIRMED] TB2 — `MatchWhereBuilder.op` sets right=value (verified); tests render only `getLeft()`, so the literal operand is never asserted. Value behavior is partly covered end-to-end by `EdgeTraversalEquivalenceTest` (lt/gte/eq), leaving neq/gt/lte value paths unit-only → suggestion.

#### C3 [CONFIRMED] TB3 — `contains("Person")` on `toString()` cannot separate `@class = 'Person'` from `@class = Person`; the string-literal quoting from `SQLBaseExpression` is unverified. Helper has no production caller in this track.

#### C4 [CONFIRMED] TC1 — Acceptance line "edge-subclass labels behave as native `out()`" and Plan-of-Work item 9's edge-subclass fixture are unimplemented; the seed helpers create only flat `knows`/`likes` edges, so edge-label polymorphism (a distinct axis from the covered vertex-subclass case) is unverified. Divergence direction unconfirmed (grep-only).

#### C5 [CONFIRMED] TC2 — `toFilter` `value == null` guard reachable via `P.eq(null)`; no test drives it. Distinct from `unsupportedValueType_declines` (type path, inside the try/catch). Without it a null comparand renders `field = null` (wrong membership) or throws.

#### C6 [CONFIRMED] TC3 — Cursor guard has two arms (`<= indexBefore`, `> size`); only the no-advance arm is tested. Overrun arm (a recogniser advancing past list end) is unpinned; defensive today but on risk:high walker infra with a tested twin.

#### C7 [CONFIRMED] TC4 — `addEdgeAsNode` tests cover OUT and IN; the `Direction.BOTH → bothE/bothV` switch arms in `edgeMethodName`/`closingVertexMethodName` are never rendered. Unreachable in Track 3 (otherV-close declines) → suggestion.

#### C8 [CONFIRMED] TC5 — `EdgeStepRecogniser` blank-single-label decline (`length 1`, blank) untested; label-less and multi-label are covered, and `VertexStepRecogniser` covers the mirror branch via a mock. Defensive → suggestion.

#### C9 [REFUTED] Claim: `multipleHasSteps_andMergeIntoOneEdgeFilter` (unit) is shallow — it asserts only `edgeFilters.containsKey(FIRST_EDGE_ALIAS)` and `stepIndex`, never that both `HasContainer`s survived the AND-merge, so a merge that dropped one predicate would pass. Refutation: `EdgeTraversalEquivalenceTest.nonAdjacentEdgeFilter_andMergesMultipleHasSteps` discriminates behaviorally — Alice→Bob (since=2010, weight=5) and Alice→Carol (since=2010, weight=9), filter `since=2010 AND weight=5`. Dropping `weight=5` makes the translated run yield {Bob, Carol} vs native {Bob}, tripping the multiset equality. The AND-merge is behaviorally pinned; the unit shallowness is compensated → not reported.

#### C10 [REFUTED] Claim: no test verifies a `has(...)` after the closing `inV()` filters the target vertex (Validation and Acceptance line: "claimed by the regular node-side path, not the edge recogniser"). Refutation: node-side `has` is Track 4 scope (Interfaces and Dependencies § Out of scope), so in Track 3 such a trailing `HasStep` has no recogniser and the whole traversal declines. The edge recogniser's non-greedy stop at the close is already pinned by the exact `ctx.stepIndex` assertions (e.g. `== 4` for `outE.has.inV` from index 1), which prove it consumes edge+has+close and no more. Low value in this track → not reported.

#### C11 [REFUTED] Claim: the headline edge-filter case cannot detect a filter-applied-to-wrong-element bug (edge WHERE landing on the target vertex). Refutation: in `nonAdjacentOutEdgeFilter_returnsSameMultisetAsNative` the target vertices (Bob, Carol) carry no `since` property, so a wrong-element `since < 2015` on the target matches nothing → translated {} vs native {Bob}, caught. It is additionally pinned structurally by `MatchPatternBuilderTest.addEdgeAsNode_edgeFilter_attachesToEdgePathItem` (`assertSame` on the edge path item's filter). Well covered → not reported.
