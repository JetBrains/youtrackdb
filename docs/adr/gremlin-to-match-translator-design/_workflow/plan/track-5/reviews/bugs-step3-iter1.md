<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: BG1, sev: should-fix, loc: "NotStepRecogniser.java:77-90; MatchPatternBuilder.java:316-375", anchor: "### BG1 ", cert: "C-boundary-filter", basis: "edge-bearing NOT accepts sub-walks that captured boundary-alias WHERE or re-types but buildNotExpression emits a bare origin and never merges supplemental filters for the origin alias, so not(has(...).out(...)) translates with a filterless NOT anti-join and returns the wrong multiset vs native"}
overall: FAIL
evidence_base: {section: "## Evidence base", certs: 5, matches: 4}
cert_index:
  - {id: C-boundary-filter, verdict: CONFIRMED, anchor: "#### C-boundary-filter "}
  - {id: C-branch-order, verdict: MATCHES, anchor: "#### C-branch-order "}
  - {id: C-build-not, verdict: MATCHES, anchor: "#### C-build-not "}
  - {id: C-origin-guard, verdict: MATCHES, anchor: "#### C-origin-guard "}
  - {id: C-effective-boundary, verdict: MATCHES, anchor: "#### C-effective-boundary "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

GATE VERDICT: FAIL

Step-3 bugs review (`164ac087ab..1d0e346011`). mcp-steroid unreachable; symbol audits used diff read + direct source inspection `(grep-only)`.

## Findings

### BG1 [should-fix]
**Certificate**: C-boundary-filter
**Location**: `NotStepRecogniser.recognize` edge-bearing path (NotStepRecogniser.java:77-90); `MatchPatternBuilder.buildNotExpression` / `mergedTargetFilter` (MatchPatternBuilder.java:316-375).
**Issue**: Edge-bearing logical NOT accepts sub-walks where a filter or re-type was captured on the NOT origin alias (the outer boundary), but the detached `SQLMatchExpression` drops that constraint.

After `hasEdgeHops`, the recogniser calls `buildNotExpression(boundary, adapter.capturedAliasFilters())`. `buildNotExpression` always sets a bare NOT origin (`SQLMatchFilter.fromAliasAndClass(originAlias, null)` — MatchPatternBuilder.java:325-326) and `mergedTargetFilter` only runs for each hop's **target** alias (MatchPatternBuilder.java:336-337). Any entry in `supplementalAliasFilters` keyed on `originAlias` / `boundary` is never attached. Boundary re-types captured via `addNode(boundary, L)` land in `capturedPattern.registeredAliasClasses()` and are likewise omitted from the NOT AST.

`manageNotPatterns` forbids an inline filter on the NOT origin item (MatchExecutionPlanner.java:766-771), so the executor contract requires a bare origin — but a sub-walk shape like `not(__.has("city", P.eq("NYC")).out("knows"))` **needs** the city predicate on the NOT sub-traversal start. The recogniser should decline (same defense-in-depth class as the A6 origin guard), not accept and emit a filterless `NOT {as:p}.out('knows')`.

**Failure scenario**: Graph with `A(age=30, city=NYC)` knowing `B`, and `C(age=30, city=Boston)` knowing `D`. Query `g.V().has("age", 30).not(__.has("city", P.eq("NYC")).out("knows"))`. Native keeps `C` (inner traversal empty at Boston). A translated filterless NOT anti-join drops both `A` and `C` because each has an outgoing `knows` edge — wrong multiset.

The pinned acceptance shapes are unaffected: `has(age).not(out(knows))` keeps the age filter on the positive alias; `not(out(knows).has(city))` attaches the filter to the hop target via `effectiveBoundaryAlias` and `mergedTargetFilter` on the leaf alias (NotStepRecogniserTest.edgeBearingChildWithTargetFilter_attachesLeafWhere).
**Refutation considered**: (1) Could boundary filters merge into the positive pattern elsewhere? Only `ctx.aliasFilters` on the outer walk merge in `buildResult` (GremlinStepWalker.java:314-316); sub-walk captures stay in the adapter until `buildNotExpression`, and the edge-bearing NOT path never calls `putAliasFilter` for those captures on the outer context. (2) Could `has().out()` be classified pure-filter? `hasEdges()` flips on `addEdge` (SubTraversalPredicateAdapter.java:216-217); sequential `has` then `out` is edge-bearing. (3) Could the planner attach origin filters later? `manageNotPatterns` rejects `exp.getOrigin().getFilter() != null`; attaching the dropped filter at plan time would throw, not recover. **Verdict: CONFIRMED.**
**Suggestion**: Before `buildNotExpression`, decline when the captured sub-walk carries origin-side constraints the bare-origin NOT contract cannot express — e.g. `adapter.capturedAliasFilters().containsKey(boundary)` or `adapter.capturedPattern().registeredAliasClasses().containsKey(boundary)`. Add a regression test that `not(__.has("city").out("knows"))` declines (or, if executor support arrives later, that equivalence holds).

## Evidence base

#### C-boundary-filter: edge-bearing NOT drops origin-alias supplemental filters and re-types (BG1)
- CONFIRMED-as-issue: `buildNotExpression` emits bare origin and merges `supplementalAliasFilters` only onto hop targets; `not(__.has(...).out(...))` accepts but mistranslates. See BG1 body.

#### C-branch-order: hasNotPresenceKey runs before walkChild; PropertiesStep VALUE/PROPERTY both accepted
- **Hypothesis**: Branch order could misclassify `hasNot(key)` as logical NOT or vice versa.
- **Refutation**: `hasNotPresenceKey` runs at NotStepRecogniser.java:52-56 before `walkChild` (NotStepRecogniser.java:63). Logic mirrors `TraversalFilterStepRecogniser.presenceKey` (PropertiesStep with `PropertyType.VALUE` or `PROPERTY`, single non-reserved key). Tests `hasNot_valuesChild_contributesIsNotDefined` and `hasNot_propertiesChild_contributesIsNotDefined` pass; `NotStepRecogniserTest` green `(test run)`. **Verdict: MATCHES.**

#### C-build-not: buildNotExpression satisfies manageNotPatterns bare-origin contract for supported shapes
- **Hypothesis**: `buildNotExpression` might attach filters to the NOT origin item or fail to attach leaf filters.
- **Refutation**: Origin is `fromAliasAndClass(originAlias, null)` with no `setFilter` (MatchPatternBuilder.java:325-326). Single-hop leaf filters merge via `mergedTargetFilter` (MatchPatternBuilderTest.buildNotExpression_singleHop_attachesLeafFilterNotOrigin, buildNotExpression_mergesSupplementalAliasFilters). Branching fragments throw and are caught as DECLINE (MatchPatternBuilderTest.buildNotExpression_branchingFragment_throws). **Verdict: MATCHES for in-scope shapes; origin-side supplemental filters are the BG1 gap.**

#### C-origin-guard: positivePatternHasAlias pre-validates NOT origin before emit
- **Hypothesis**: Edge-bearing NOT could emit a detached expression when the origin alias is absent from the positive pattern.
- **Refutation**: NotStepRecogniser.java:78-80 calls `ctx.positivePatternHasAlias(boundary)` on the outer `WalkerContext.patternBuilder` (WalkerContext.java:338-340). `SubTraversalPredicateAdapter` delegates to parent (SubTraversalPredicateAdapter.java:255-257). Unit test `edgeBearingChild_originAbsentFromPositivePattern_declines` pins decline. **Verdict: MATCHES.**

#### C-effective-boundary: pinBoundary records hop target for chained filters without re-pinning outer boundary
- **Hypothesis**: `not(out().has(...))` could attach the property filter to the parent boundary instead of the hop target.
- **Refutation**: `SubTraversalPredicateAdapter.pinBoundary` sets `effectiveBoundaryAlias` (SubTraversalPredicateAdapter.java:281-285); `boundaryAlias()` returns it when non-null (SubTraversalPredicateAdapter.java:173-174). `VertexHopRecogniser` calls `appendFoldedHop` → `rePinBoundaryToTarget`, which invokes `pinBoundary` on the adapter (swallowed on outer boundary/RETURN). `HasStepRecogniser` keys on `ctx.boundaryAlias()` inside the sub-walk, so post-hop filters land on the hop target. `NotStepRecogniserTest.edgeBearingChildWithTargetFilter_attachesLeafWhere` asserts leaf alias `$g2m_anon_0` carries the city predicate. **Verdict: MATCHES.**
