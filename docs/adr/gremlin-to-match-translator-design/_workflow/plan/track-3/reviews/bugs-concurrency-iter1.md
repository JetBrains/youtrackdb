<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 11, matches: 0}
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
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
flags: [CONTRACT_OK]
-->

## Findings

No bugs found by single-threaded sequential reasoning. Every changed production
path was traced and each candidate defect was refuted; the reasoning is in
`## Evidence base`. Scope: logic errors, null safety, resource leaks, RID
handling, state/lifecycle. Concurrency (interleaving-dependent) defects are
deferred to `review-concurrency`; none were triaged for because the code carries
no shared mutable state (see C10), so no triage-gap note is raised.

Tooling caveat: mcp-steroid was not reachable this session, so every
reference-accuracy claim below rests on grep + full-file reads, not PSI
find-usages. Flagged inline as `(grep-only)` where a claim turns on a symbol
search.

## Evidence base

#### C1 REFUTED — index-driven walker never consumes an unvalidated step
Hypothesis: the for-each → cursor rewrite (`GremlinStepWalker.walk`, lines
173-200) plus multi-step claims could let a step advance the cursor without being
validated, corrupting the all-or-nothing guarantee.
Trace: the loop reads `steps.get(indexBefore)`, dispatches to the class-keyed
recogniser, and requires `ctx.stepIndex > indexBefore && ctx.stepIndex <=
steps.size()` (assert at 187, defensive decline at 197). In the only multi-step
claim, `EdgeStepRecogniser.recognize`, the probe window
(`EdgeStepRecogniser.java:112-142`) type-checks every step it walks past —
`NoOpBarrierStep` (114), `HasStep` routed through the adapter (119-131),
`EdgeVertexStep` as the close (132) — and any other step returns false (141). The
edge step itself is validated by the recogniser's own gates (81-104) before the
probe starts at `ctx.stepIndex + 1`. So the consumed span
`[edge_index, closing_index]` is fully validated and the cursor lands exactly one
past the close. No step in the span escapes validation.
Verdict: not a bug.

#### C2 REFUTED — no-mutation-on-decline holds in the edge recogniser
Hypothesis: a mid-peek decline (e.g. an untranslatable `has()`) could leave
`WalkerContext` partially mutated, tainting a later recogniser.
Trace: the entire peek accumulates into a local `ArrayList<SQLBooleanExpression>`
(`EdgeStepRecogniser.java:109`); no `ctx` field is touched until after
`closingDirection` is confirmed non-null (143-146). Alias minting (151-152),
`ctx.edgeFilters.put` (162), the assembler call (167-169), and `ctx.stepIndex =
probe` (173) all run only on the success path. Every early `return false`
(82/88/93/99/103/122/141/145) precedes the first mutation. `ctx` is left exactly
as found.
Verdict: not a bug.

#### C3 REFUTED — edge-as-node direction mapping is correct for all reachable combos
Hypothesis: mapping the closing `EdgeVertexStep` direction independently of the
edge direction could emit a wrong `outV/inV/bothV` and diverge from native.
Trace: TinkerPop `inV()`/`outV()`/`bothV()` construct `EdgeVertexStep` with
`Direction.IN`/`OUT`/`BOTH`; `otherV()` is a distinct `EdgeOtherVertexStep`,
declined at 141. `toBuilderDirection` (`GremlinPatternAssembler.java:101-107`) and
`edgeMethodName`/`closingVertexMethodName`
(`MatchPatternBuilder.java:214-229`) map each 1:1. Working through the
combinations — `outE.inV`, `inE.outV` (the folding targets) and the backward
`outE.outV`, `inE.inV` — MATCH edge methods mirror Gremlin edge methods exactly,
so both return the same endpoint with the same per-edge multiplicity. The
BOTH-direction edge-filter shape (`bothE.has.otherV`) is the one deliberately
declined (Decision Log), and the recogniser declines it via the
`EdgeOtherVertexStep` fall-through.
Verdict: not a bug.

#### C4 REFUTED — edge filter reaches the plan; `edgeFilters` map is observability-only
Hypothesis: `buildResult` merges `ir.aliasFilters()` with `ctx.aliasFilters` but
not `ctx.edgeFilters` (`GremlinStepWalker.java:255-256`), so an edge filter could
be dropped from the plan.
Trace: the edge `WHERE` is embedded directly in the pattern — `appendEdgeAsNode`
→ `MatchPatternBuilder.addEdgeAsNode` → `MatchEdgePathItems.edgeMethodItem` sets
it on the edge path item's `SQLMatchFilter` (`MatchEdgePathItems.java:56-58`),
which travels inside the copied `Pattern`. `ctx.edgeFilters` is written (162) but
read only by tests (grep-only: `grep -rn edgeFilters` shows no production reader
outside the write site). `EdgeTraversalEquivalenceTest` asserts multiset equality
for `outE(L).has(edgeProp).inV()` and passes per the Step 3 episode, so the filter
demonstrably reaches the executor. Not merging the map is therefore correct, not a
drop.
Note (out of bugs scope, for code-quality): the write-only `edgeFilters` field is
a latent maintainability trap — a future refactor that moves the filter off the
path item and relies on the map would silently drop it. Redundant state, not a
current defect.
Verdict: not a bug.

#### C5 REFUTED — multi-ID `@rid IN` survives a following hop's boundary re-pin
Hypothesis: `g.V(id1,id2).out(L)` could lose the start-node RID filter when the
hop re-pins the boundary.
Trace: `StartStepRecogniser` puts the filter in `ctx.aliasFilters` under
`$g2m_v0` (`StartStepRecogniser.java:162`). `rePinBoundaryToTarget`
(`GremlinPatternAssembler.java:109-120`) clears only the three RETURN lists and
re-points `boundaryAlias`; it never touches `aliasFilters`. `$g2m_v0` remains the
origin of the first edge in the pattern, and `buildResult` merges its filter into
`finalAliasFilters`. The filter is preserved and still bound to the start node.
Verdict: not a bug.

#### C6 REFUTED — Compare-predicate absent/null semantics match native Gremlin
Hypothesis: translating `has(edgeProp, eq/neq/lt/gt/…)` to `field OP literal`
could over- or under-match on absent or null-valued edge properties versus
native.
Trace: TinkerPop `HasContainer.test` on an edge evaluates `property.isPresent() &&
testValue(...)`, so an absent property fails every `Compare`. YTDB's SQL
comparison against a missing/null field yields a non-true result, so `field OP
literal` likewise excludes absent rows. Both sides exclude the absent case for
eq/neq/lt/lte/gt/gte. The absent-vs-null divergence the design flags applies to
presence forms (`has(key)`/`hasNot(key)`/`eq(null)`), which the adapter declines
(`GremlinPredicateAdapter.java:86-110`), routing the whole traversal to native.
The in-scope Compare subset is faithful.
Verdict: not a bug.

#### C7 REFUTED — reserved-`$` scan and predicate-key guard decline, never throw
Hypothesis: a null or `$`-prefixed user label / property key could NPE or emit a
wrong non-throwing result where native throws.
Trace: `hasReservedPrefixLabel` null-guards each label before `startsWith`
(`GremlinStepWalker.java:238`), so `as((String) null)` declines rather than
NPE-ing. `GremlinPredicateAdapter.toFilter` declines null/blank/`~`/`$` keys
(86-94) and null predicates/values (98/108), and wraps `MatchLiteralBuilder`
in try/catch (112-116). Every unrecognised shape returns null → whole-traversal
decline. No throw escapes to mistranslate.
Verdict: not a bug.

#### C8 REFUTED — RID normalisation dedups and declines faithfully
Hypothesis: `normaliseIds` could produce a multiset that differs from native
`g.V(ids)` or mishandle a RID shape.
Trace: `StartStepRecogniser.normaliseIds` (207-228) declines (returns null) on any
unconvertible id and on any duplicate, keyed on `RidKey(collectionId, position)`
to collapse different `RecordIdInternal` subtypes carrying the same rid. Duplicate
decline is correct: a set-semantics `@rid IN` cannot reproduce native's
one-emission-per-occurrence. `toRecordId` declines blank strings (which
`RecordIdInternal.fromString` would otherwise coerce to the `#-1:-1` placeholder)
and catches malformed-string exceptions (247-258). Empty input maps to `List.of()`
(the `g.V()` case), distinct from the null decline. RID format handling is sound.
Verdict: not a bug.

#### C9 REFUTED — barrier consumption never double-dispatches or skips validation
Hypothesis: `NoOpBarrierStep` handled by both the edge recogniser's in-window skip
and the registered `NoOpBarrierRecogniser` could double-consume or skip a step.
Trace: a barrier inside the `outE…inV` window is consumed by the edge recogniser's
probe (`EdgeStepRecogniser.java:114-117`) and folded into the consumed span, so
the walker never re-dispatches it. A barrier between hops is consumed by
`NoOpBarrierRecogniser` (advances the cursor by one, mutates nothing else). The two
paths cover disjoint positions; both type-check the step first. A trailing barrier
is harmless because a prior hop already pinned the boundary (terminator invariant
intact).
Verdict: not a bug.

#### C10 REFUTED — no shared mutable state, so no concurrency-triage gap
Hypothesis: the shared singletons (`*Recogniser.INSTANCE`, the static
`MatchWhereBuilder WHERE`, `GremlinStepWalker.PRODUCTION_INSTANCE`) executed
concurrently across Gremlin threads could race.
Trace: the recognisers declare no mutable instance fields (grep-only: only
`INSTANCE`, `*_PREFIX`, and the static `WHERE` remain after filtering); `MatchWhereBuilder`
is documented and verified stateless (returns fresh AST, no fields). The walker's
only field is an immutable `Map.copyOf` registry. All mutable state lives in
`WalkerContext`, allocated fresh per `walk()` call and thread-confined. With no
shared mutable state on the changed paths, there is nothing for `review-concurrency`
to analyse here — no triage-gap note is warranted.
Verdict: not a bug (and no concurrency triage gap).

#### C11 REFUTED — terminator-invariant assert cannot spuriously crash a real query
Hypothesis: `NoOpBarrierRecogniser` returns true without pinning the boundary; if
it were the only recogniser to run, the walker's boundary assert
(`GremlinStepWalker.java:214`) would raise an `AssertionError` that the strategy's
RuntimeException-only net does not catch, crashing the query instead of declining.
Trace: the boundary stays unpinned only if no node-producing recogniser ran, which
requires a traversal with no `GraphStep`/`VertexStep` — e.g. a barrier-only step
list. A real provider-strategy traversal is rooted by `g.V()`/`g.E()` (a
`GraphStep` at index 0), and `LazyBarrierStrategy` injects barriers between steps,
never as the sole/leading step. The shape that would trip the assert is
unreachable from real input; the assert is doing its intended job of catching a
recogniser-logic bug, and under `-da` the paired defensive decline (216) keeps
production safe regardless.
Verdict: not a bug.
