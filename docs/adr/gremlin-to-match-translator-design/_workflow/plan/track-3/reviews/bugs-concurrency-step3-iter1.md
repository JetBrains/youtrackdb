<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 10, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

## Findings

No bug-dimension findings. The step's production logic is correct under single-threaded sequential reasoning: no logic errors, null-safety gaps, resource leaks, RID defects, or lifecycle violations survived refutation. The code is pure MATCH-IR AST construction with no I/O, resources, transactions, or RID parsing, so those categories are structurally inapplicable. Ten bug hypotheses were traced to ground and refuted; the reasoning is in `## Evidence base`.

No concurrency-triage-gap note is warranted: the only shared state introduced (static `MatchWhereBuilder` / recogniser singletons) is stateless, and `WalkerContext` is per-walk. See C10.

## Evidence base

#### C1 Edge label rendered as identifier rather than string literal — REFUTED
Hypothesis: `MatchEdgePathItems.edgeMethodItem` wraps the label in `new SQLBaseExpression(edgeLabel)`, and if that produced a bare identifier the executor's `outE(knows)` would resolve `knows` as a field reference rather than the label string, silently over/under-matching versus native. Checked `SQLBaseExpression(String)` (core/.../sql/parser/SQLBaseExpression.java:53): the constructor stores `"\"" + StringSerializerHelper.encode(string) + "\""`, i.e. a quoted string literal, matching the SQL `outE('knows')` form and the existing `SQLMatchPathItem.graphPath` label path (line 41). The `EdgeTraversalEquivalenceTest` end-to-end cases (`nonAdjacentOutEdgeFilter_returnsSameMultisetAsNative`) would fail on a mislabelled edge. Not a bug.

#### C2 `otherV()` wrongly claimed by the `instanceof EdgeVertexStep` peek — REFUTED
Hypothesis: if `EdgeOtherVertexStep` were a subclass of `EdgeVertexStep`, the peek-ahead's `next instanceof EdgeVertexStep closing` (EdgeStepRecogniser.java:138) would match `otherV()` and claim `bothE(L).has(...).otherV()`, which has no MATCH method and must decline. Verified the fork's class hierarchy via `javap` on gremlin-core-3.8.1-af9db90: `EdgeOtherVertexStep extends ScalarMapStep`, `EdgeVertexStep extends FlatMapStep` — disjoint hierarchies, no subtype relation. `otherV()` falls through to the catch-all `return false` (line 147). The `otherVClose_declines` unit test pins this. Not a bug. (Hierarchy fact from `javap`, not PSI — mcp-steroid unreachable this session; the fact is a JDK-verified bytecode read, so the caveat is nominal.)

#### C3 Edge filter double-applied via `ctx.edgeFilters` plus the assembler — REFUTED
Hypothesis: EdgeStepRecogniser writes the merged edge WHERE both to `ctx.edgeFilters.put(edgeAlias, edgeWhere)` (line 168) and hands the same clause to `appendEdgeAsNode`; if the walker's result-build also merged `edgeFilters` into the plan, the predicate would be applied twice or attached to a nonexistent vertex alias. Read `GremlinStepWalker.buildResult` (lines 252-276): it merges only `ir.aliasFilters()` and `ctx.aliasFilters` into `finalAliasFilters`; `ctx.edgeFilters` is never read at result-build. The filter reaches the plan exactly once, on the edge path item's `SQLMatchFilter`. The `edgeFilters` map is observational only (consumed by unit tests). Not a bug.

#### C4 Method-name dispatch broken by `SQLIdentifier(String)` — REFUTED
Hypothesis: `MatchEdgePathItems` builds the method name via `new SQLIdentifier(methodName)` where the existing `graphPath` instead sets `.value` directly; a divergent `getStringValue()` would miss the `graphMethods` dispatch set. Checked `SQLIdentifier(String)` → `setStringValue` (SQLIdentifier.java:37-40, 99-107): for a plain token like `outE` it stores the value verbatim, and `getStringValue()` returns `outE`. `SQLMethodCall.graphMethods` (line 30) contains `outE`/`inE`/`bothE`/`bothV`/`outV`/`inV`, and dispatch keys on `methodName.getStringValue()` (line 98). Names resolve correctly. Not a bug.

#### C5 Closing-hop direction mapped wrong — REFUTED
Hypothesis: the closing-vertex method could be emitted with the wrong direction, turning `outE(L).inV()` into `outE(L).outV()` (the far vs near vertex). Traced `EdgeVertexStep.getDirection()`: `inV()`→IN, `outV()`→OUT, `bothV()`→BOTH (TinkerPop `GraphTraversal`). `toBuilderDirection` maps identity, and `closingVertexMethodName` maps IN→`inV`, OUT→`outV`, BOTH→`bothV` (MatchPatternBuilder.java:223-229). `outE(OUT).inV(IN)` → `outE.inV`; `inE(IN).outV(OUT)` → `inE.outV`. Correct, and pinned by `addEdgeAsNode_rendersOutEThenInVMethodCalls` / `addEdgeAsNode_inEdgeDirection_rendersInEThenOutV`. Not a bug.

#### C6 `bothE(L).inV()/outV()/bothV()` claimed but divergent from native — REFUTED
Hypothesis: `bothE(L).has(x).inV()` does not fold (only `bothE().otherV()`→`both()` folds), so the edge recogniser claims it with a BOTH edge and an inV/outV/bothV close; if MATCH's edge-relative vertex semantics differed from Gremlin's, the result multiset would diverge. Traced concrete two-vertex graphs (A--knows-->B): native `V().bothE(knows).inV()` yields {B,B}; the MATCH edge-as-node pattern over all origins yields {B,B}. `outV` yields {A,A} both ways. inV/outV/bothV are edge-relative (head/tail/both endpoints of the edge) identically in both engines; only `otherV` is traversal-relative and correctly declined. No divergence. (No dedicated test for the `bothE.inV` claim path — a test-coverage gap owned by the test-quality dimension, not a bug.)

#### C7 No-mutation-on-decline violated (early alias mint / ctx write) — REFUTED
Hypothesis: a decline after partial peek-ahead could leave a minted alias, an `edgeFilters` entry, an appended node, or an advanced cursor. Read EdgeStepRecogniser: every validation and the entire peek-ahead run before the first mutation; `nextEdgeAlias()`/`nextAnonVertexAlias()`, `edgeFilters.put`, `appendEdgeAsNode`, and `ctx.stepIndex = probe` all execute only after `closingDirection` is confirmed non-null (lines 156-179). VertexStepRecogniser delegates on the `returnsEdge()` branch before any of its own mutations. `assertContextUnmutated` across ten decline tests pins boundary, return lists, cursor, `edgeFilters`, and the next-alias counters. Not a bug.

#### C8 Cursor off-by-one or list overrun in the peek-ahead — REFUTED
Hypothesis: `ctx.stepIndex = probe` could skip a step the walk never validated or leave the closing hop for re-dispatch. Traced: `probe` starts at `stepIndex + 1`, advances past each barrier/has/closing step, and after the `EdgeVertexStep` branch does `probe++; break`, so it lands exactly one past the closing hop; `ctx.stepIndex = probe` then points at the first unconsumed step. The walker's cursor-advance guard (`ctx.stepIndex > indexBefore && ctx.stepIndex <= steps.size()`, GremlinStepWalker.java:187) bounds it. The consumed-step assertions in `outEdgeFilterChain...` (stepIndex 4), `unfilteredEdgeChain...` (3), and `interleavedBarrier...` (5) confirm exact counts. Not a bug.

#### C9 Null dereference on `getEdgeLabels()` / step labels — REFUTED
Hypothesis: `edgeLabels.length` or `edgeStep.getLabels()` could NPE. `VertexStep.getEdgeLabels()` returns a non-null `String[]` (empty for `outE()`), so the `length != 1` guard is safe, and `edgeLabels[0]` is reached only after the length check with a following null/blank guard (lines 103-110). The walker's reserved-prefix scan null-guards individual labels (`label != null`, GremlinStepWalker.java:238), the Step-1 fix for `as((String) null)`. `nonVertexStep_declines` and `nullBoundary_declines` cover the defensive gates. Not a bug.

#### C10 Shared static builders / singletons carry mutable state — REFUTED
Hypothesis (concurrency-triage screen): `EdgeStepRecogniser.WHERE`, `GremlinPredicateAdapter.WHERE`, and the recogniser/adapter `INSTANCE` singletons are static and shared across all translating threads; mutable state would be a shared-state hazard requiring a triage-gap note. Read `MatchWhereBuilder` (class Javadoc line 39 plus a full field scan): no instance fields, every method returns a fresh AST node, mutates nothing. The recognisers and adapter are equally field-free; `WalkerContext` is allocated per `walk()` call. No shared mutable state, so no interleaving hazard and no concurrency-triage-gap note is owed. Not a bug.
