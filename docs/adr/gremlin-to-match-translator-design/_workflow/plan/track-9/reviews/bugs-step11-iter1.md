<!-- MANIFEST
findings: 5   severity: {blocker: 1, should-fix: 2, suggestion: 2}
index:
  - {id: BG9,  sev: blocker,     loc: SubTraversalPredicateAdapter.java:311-313, anchor: "### BG9 ",  cert: C1, basis: "the not exemption the step documents as sound is unsound in composition: an edge-bearing not() inside an OR arm leaks its anti-join past the capture boundary and lands as a plan-level conjunct — g.V().or(not(out(a)).has(name,x), has(age,30)) returns [x] against native's [x, y], measured, one boundary step"}
  - {id: BG10, sev: should-fix,  loc: ConnectiveStepSupport.java:92-99, anchor: "### BG10 ", cert: C2, basis: "removing commitEdgeBearingChild left RecognitionContext.appendPattern with zero production callers, so the hasEdges flip two test javadocs name as the mechanism that makes the nested AND decline is unreachable and the claimed regression guard does not exist"}
  - {id: BG11, sev: should-fix,  loc: WhereTraversalStepRecogniser.java:38-39, anchor: "### BG11 ", cert: C3, basis: "the pure-filter arm the step re-certifies mis-keys a path-scoped where onto the current boundary — g.V().as(a).out(knows).where(as(a).has(age,30)) returns [] against native's [Bob]; the new test picks the one scope-label spelling where boundary and scope key coincide"}
  - {id: BG12, sev: suggestion,  loc: AndStepRecogniser.java:43-47, anchor: "### BG12 ", cert: C4, basis: "the pre-check comment claims the decline leaves the outer context untouched, but four adapter methods write straight through to the parent during the child walks that run before it; the new unit tests assert only the two channels that are captured"}
  - {id: BG13, sev: suggestion,  loc: ConnectiveStepSupport.java:55-62, anchor: "### BG13 ", cert: C5, basis: "commitPureFilterChild replays every captured alias class onto the parent with no boundary guard, while its sibling singleCapturedFilter declines exactly that case; the step makes it the sole commit path for all positive-filter children"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 5}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED,   anchor: "#### C11 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG9 [blocker] The `not` exemption is unsound in composition: an edge-bearing `not` inside an OR arm escapes the capture boundary and drops rows

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/SubTraversalPredicateAdapter.java` (lines 311-313); invariant asserted at `ConnectiveStepSupport.java:87-90`; write site `NotStepRecogniser.java:98`; consumer `ConnectiveStepSupport.java:126-148`

**Issue**: the step's central design assertion is that `not(t)` may keep its hop because "an anti-join emits its input at most once, so it never over-emits", and that with `where` / `filter` / `and` declining, "all four connective surfaces now agree that a hop inside a boolean filter stays native". Both halves fail. A hop inside a `not` inside an `or` does not stay native — it translates — and the failure mode is under-emission, which the over-emission argument never covers.

```
seed: y{name=y, age=30} --a--> t{name=t, age=1};  x{name=x, age=1}

g.V().or(__.not(__.out("a")).has("name", eq("x")), __.has("age", eq(30)))
  translator OFF  [x, y]
  translator ON   [x]          one boundary step — the shape translates
```

`y` is lost. Native reads the disjunction as `(no out-a AND name = x) OR (age = 30)`, and `y` passes on the second arm. The translation reads it as `(no out-a) AND (name = x OR age = 30)`, and `y` fails the conjunct.

Mechanism: `SubTraversalPredicateAdapter` captures every other contribution, but `addNotMatchExpression` forwards to the parent:

```java
@Override
public void addNotMatchExpression(SQLMatchExpression expression) {
  parent.addNotMatchExpression(expression);
}
```

`NotStepRecogniser:98` calls it while the OR arm's sub-walk is running, so the anti-join reaches the top-level `WalkerContext.notMatchExpressions` before the OR has decided anything. The arm's own `hasEdges` stays `false` — the hop went into the grandchild adapter and was consumed by `buildNotExpression`, never merged upward — so `collectOrExpressions` classifies the arm pure-filter, reads back only the captured `name = x`, and composes that one operand into the OR. `buildResult` then hands the leaked expression to `MatchPlanInputs.notMatchExpressions`, where the planner applies it conjunctively over the whole match.

The escape predates the step; what the step adds is the written claim that this class of shape cannot reach the translator. Whoever reads `anyEdgeBearing`'s Javadoc next will stop looking. The sentence is also self-contradictory as written: it exempts `not` from the decline in one clause and then counts `not` among four surfaces that all decline in the next.

**Evidence** (`#### C1`): measured, translator on against off, three-vertex fixture, boundary-step count read off `applyStrategies()`. `addNotMatchExpression`'s delegation is the only non-capturing contribution path on the adapter that a connective consumer does not re-read (grep-only: `mcp-steroid` PSI `execute_code` times out in this repository, so the caller sets under `core/src/main/.../gremlin` were established with `git grep`; `RecognitionContext` is package-private, which bounds the search to one package).

**Refutation considered**: I checked whether the leaked expression dies with a declined walk, which is what makes the same escape harmless in the AND and mixed-AND cases — it does, `dispatchAll` returns `false` on any DECLINE and `walk` discards the whole `WalkerContext` (measured: `g.V().and(__.not(__.out("a")), __.out("b"))` and `g.V().or(__.not(__.out("a")), __.has("age", 30))` both report zero boundary steps and match native). The OR case is different because the OR *accepts*: a bare `not(out(a))` arm captures no boundary filter, so `singleCapturedFilter` returns `null` and the OR declines, but adding any trailing filter step to the same arm supplies the one operand the OR needs and the arm passes. I checked whether the union fork could resurrect a discarded contribution — `UnionForkHostImpl.walkFork` calls `GremlinStepWalker.production().walk(forked)`, a fresh `WalkerContext` per arm, so it cannot.

**Suggestion**: capture NOT expressions in the adapter instead of forwarding them, and make each connective decide. Add a `capturedNotExpressions` buffer alongside `capturedAliasFilters`; have `commitPureFilterChild` forward the buffer to the parent (AND and the positive filters are conjunctive, so forwarding is correct there and `where(__.not(__.out("a")))` keeps translating); have `collectOrExpressions` return `null` for any child that produced one, next to the existing `hasEdges` check; have `NotStepRecogniser` decline a child that produced one, since a nested `not` is not expressible as a single detached expression. Pin `g.V().or(__.not(__.out("a")).has("name", "x"), __.has("age", 30))` as an equivalence case with the fan-out fixture above, because the shape returns the same multiset on any fixture where no vertex passes only the second arm.

Rewrite the `anyEdgeBearing` closing paragraph once the gate is in: `not` is the exception to the hop rule, and the surfaces agree on conjunctive composition rather than on keeping hops native.

### BG10 [should-fix] `appendPattern` lost its last production caller, so the nested-AND mechanism two test javadocs describe is unreachable

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ConnectiveStepSupport.java` (lines 92-99, the method that replaced the caller); dead surface at `RecognitionContext.java:215`, `WalkerContext.java:519`, `SubTraversalPredicateAdapter.java:326-335`; claims at `AndStepRecogniserTest.java:105-111` and `EdgeTraversalEquivalenceTest.java:777-782`

**Issue**: `commitEdgeBearingChild` was the only production caller of `RecognitionContext.appendPattern`. With it deleted, all three members of that surface are unreachable from any traversal — the interface method, `WalkerContext`'s implementation, and the `SubTraversalPredicateAdapter` override whose whole purpose is the `hasEdges` flip:

```java
var sourceHadEdges = captured.edgeCount() > 0;
capturedPattern.appendFrom(captured);
if (sourceHadEdges) {
  hasEdges = true;
}
```

The only remaining call is `SubTraversalPredicateAdapterTest:254`, a direct unit-test invocation.

Two rewritten test javadocs assert that flip is the load-bearing mechanism. `AndStepRecogniserTest.nestedAndOfOutHops_thenHas_declines`: "The inner combinator merges its edges into the middle adapter via `RecognitionContext#appendPattern`, which flips `hasEdges` — that classification is what the outer combinator reads to decline, so a regression that left the middle adapter classified pure-filter would translate the shape and drop the hops." `EdgeTraversalEquivalenceTest.nestedAndOfOutHops_thenHas_declinesAndMatchesNative` repeats it.

The outer AND never reads the middle adapter's classification. The inner AND declines through the same new `anyEdgeBearing` gate, `dispatchAll` returns `false`, `subWalk` marks the middle adapter DECLINE, and `walkAcceptedChildren` returns `null` at the outcome check on line 42 — before any caller reaches `hasEdges`. (If `ConnectiveStrategy` flattened the nesting instead, there is no middle adapter at all and the claim is emptier still. Either way `appendPattern` has no production caller, so the flip cannot be reached from a traversal.) The named regression — "left the middle adapter classified pure-filter" — cannot make either test fail.

The already-recorded stale `commitEdgeBearingChild` mention at `SubTraversalPredicateAdapter.java:329` sits inside this now-dead method, which is why fixing the comment alone would not settle the question.

**Evidence** (`#### C2`): `git grep -n "appendPattern"` over `core/src/main/.../gremlin` at `7c8f694cde` returns three declarations, two Javadoc references, and one unrelated private helper in `GremlinPlanFingerprint` — no call site. At the base commit `d6e0920e5c` the two `commitEdgeBearingChild` call sites are `AndStepRecogniser:45` and `ConnectiveStepSupport:88`, confirming the three reachable production paths the step set out to close. (grep-only, per the PSI caveat in BG9; the package-private `RecognitionContext` bounds the search.)

**Suggestion**: decide whether the surface comes back. If the semi-join lands later and the AND edge-bearing path is restored, keep `appendPattern` and mark it explicitly as reserved for that work, with the two test javadocs rewritten to state the mechanism that actually fires today (the inner combinator's own decline propagating through `walkAcceptedChildren`). If it is not coming back soon, delete the interface method and both implementations along with the unit test that is now its only caller, which also removes the stale comment at line 329.

### BG11 [should-fix] The path-scoped `where` the step re-certifies applies its filter to the wrong alias

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WhereTraversalStepRecogniser.java` (lines 38-39); scope key discarded at `GremlinStepWalker.java:124-125`; new test at `WhereTraversalStepRecogniserTest.java:104-120`

**Issue**: `WhereTraversalStep` is the spelling that carries a scope key, and the recogniser never reads it. `WhereStartStep` and `WhereEndStep` are in `TRANSPARENT_STEPS`, so the cursor skips them and the scope key goes with them; the sub-walk keys the child's filter on `ctx.boundaryAlias()`, which after a hop is the hop target rather than the labelled node.

```
seed: Alice{age=30} --knows--> Bob{age=40}

g.V().as("a").out("knows").where(__.as("a").has("age", eq(30)))
  translator OFF  [Bob]
  translator ON   []           one boundary step — the shape translates
```

The `age = 30` predicate lands on Bob (40) instead of on Alice (30), and the query silently returns nothing.

The defect predates the step, and the step narrows it: the edge-bearing scoped `where` now declines, so only the pure-filter half is still exposed. The reason it belongs on this review is the new test the step added to this recogniser. `whereTraversalStep_edgeBearingChild_declines` drives `g.V().as("a").where(__.as("a").out("knows"))`, where the scope label and the current boundary are the same node, and its javadoc says it exists because "the two spellings enter through different recogniser classes, and a gate added on only one of them would leave the over-emission live on the other". It reads as coverage of the path-scoped spelling while picking the one alias arrangement that cannot witness a scope-key bug. The commit javadoc states the defect as contract: "Pure-filter children merge into the boundary alias `WHERE`."

**Evidence** (`#### C3`): measured, translator on against off, two-vertex fixture, boundary-step count read off `applyStrategies()`. `TRANSPARENT_STEPS` membership read at `GremlinStepWalker:124-125`; no reader of a scope key exists in the strategy package (grep-only, per the PSI caveat in BG9).

**Suggestion**: decline when the child's `WhereStartStep` carries a scope key that does not resolve to the current boundary. `RecognitionContext.resolveUserLabel` already maps a Gremlin label to its internal alias, so the check is available without new plumbing; re-keying the captured filters onto the resolved alias is the fuller fix and can follow. Change the new test's positive control to a scope label distinct from the boundary — `g.V().as("a").out("knows").where(__.as("a").out("created"))` — so the case discriminates the two aliases, and add the measured pure-filter shape above as an equivalence case.

### BG12 [suggestion] The AND pre-check comment overstates what the decline preserves

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/AndStepRecogniser.java` (lines 43-47)

**Issue**: the comment reads "a mixed AND must leave the outer context untouched when one arm is edge-bearing". The pre-check delivers that for the two channels the adapter captures — alias filters and pattern fragments — and the reordering is right. Four other channels are already written before the check runs, because `walkAcceptedChildren` on line 39 drives every child first and the adapter forwards them to the parent: `addNotMatchExpression` (line 311), `markRidBearing` (321), `bindParam` (316), and the two alias minters (239, 243). By the time `anyEdgeBearing` answers, a NOT arm has appended an anti-join to the top-level context and the anonymous-alias counter has advanced.

Nothing survives today, because every DECLINE discards the enclosing walk. That is the property doing the work, and it is not the property the comment names — which matters because BG9 is what happens when a sibling connective accepts instead of declining.

The three rewritten unit tests assert `ctx.aliasFilters` empty and `getNumOfEdges()` zero, so they cover exactly the two captured channels and none of the four forwarded ones.

**Evidence** (`#### C4`): forwarding methods read directly in `SubTraversalPredicateAdapter`; DECLINE-discards-the-walk read at `GremlinStepWalker:317-319` (top level) and `:478-479` (sub-walk); measured in the two decline probes cited under C1.

**Suggestion**: say what holds. "Check every child before committing any of them: a mixed AND must commit nothing to the outer pattern or filters when one arm is edge-bearing. The child walks above have already forwarded any NOT expression, RID marking, bound parameter, and minted alias to the parent; those survive only if the enclosing walk translates, and a DECLINE here discards it." If a test is wanted for the gap, assert `ctx.notMatchExpressions` empty after `g.V().and(__.not(__.out("a")), __.out("b"))` declines through the production walk rather than through the recogniser in isolation.

### BG13 [suggestion] `commitPureFilterChild` replays non-boundary alias classes with no guard

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ConnectiveStepSupport.java` (lines 55-62)

**Issue**: the second loop replays every entry in the child's `registeredAliasClasses()` onto the parent through `ctx.addNode`, whatever alias it names:

```java
for (var entry : adapter.capturedPattern().registeredAliasClasses().entrySet()) {
  ctx.addNode(entry.getKey(), entry.getValue());
}
```

Its sibling `singleCapturedFilter` guards the same read and declines when the alias is not the boundary: "A pure-filter OR child should only re-type the boundary; any other alias is inexpressible as a boolean operand on this node." The AND and positive-filter path has no equivalent, so a non-boundary entry would register a node the parent's pattern has no edge to.

No shape reaches it today. Every `addNode` on a non-boundary alias is a hop target, and a hop always calls `addEdge` first (`GremlinPatternAssembler.appendFoldedHop`), which flips `hasEdges` and now sends the child down the decline. The gap is one recogniser away: any future step that registers a node without an edge would be committed silently into a disconnected pattern rather than declined.

The step raises the stakes on this method without changing it — it is now the sole commit path for every accepted AND arm and every accepted positive-filter child.

**Evidence** (`#### C5`): both loops read in `ConnectiveStepSupport`; the addNode-without-addEdge audit covers the `addNode` callers in the strategy package (grep-only, per the PSI caveat in BG9).

**Suggestion**: mirror the sibling's guard, as a decline rather than a silent skip.

```java
for (var entry : adapter.capturedPattern().registeredAliasClasses().entrySet()) {
  // A pure-filter child re-types only the boundary; a hop target always arrives with an addEdge
  // that flips hasEdges and sends the child down the decline, so any other alias here means a
  // recogniser registered a node with no edge to reach it.
  assert boundary.equals(entry.getKey())
      : "pure-filter child re-typed non-boundary alias " + entry.getKey();
  ctx.addNode(entry.getKey(), entry.getValue());
}
```

Returning an `Outcome` from `commitPureFilterChild` so the caller can decline is the stricter alternative and costs a signature change at two call sites.

## Evidence base

#### C1 CONFIRMED — an edge-bearing `not` inside an OR arm reaches a translated plan and drops rows

Claim survived. Measured `g.V().or(__.not(__.out("a")).has("name", eq("x")), __.has("age", eq(30)))` on a three-vertex fixture: translator off `[x, y]`, translator on `[x]`, one boundary step. Refutation attempts (leak dies with a declined walk; union fork resurrects it) are recorded in the finding body; both were checked and neither covers the accepting-OR case.

#### C2 CONFIRMED — `RecognitionContext.appendPattern` has no production caller after the step

Claim survived. `git grep` over `core/src/main/.../gremlin` at `7c8f694cde` finds declarations only; the sole call is `SubTraversalPredicateAdapterTest:254`. The decline of `and(and(out(a), out(b)), has(age))` therefore comes from the inner combinator's own gate, not from the middle adapter's `hasEdges`.

#### C3 CONFIRMED — a path-scoped pure-filter `where` filters the wrong alias

Claim survived. Measured `g.V().as("a").out("knows").where(__.as("a").has("age", eq(30)))` on a two-vertex fixture: translator off `[Bob]`, translator on `[]`, one boundary step.

#### C4 CONFIRMED — four adapter channels write to the parent before the AND pre-check runs

Claim survived. `addNotMatchExpression`, `markRidBearing`, `bindParam`, and both alias minters delegate to `parent` in `SubTraversalPredicateAdapter`; `walkAcceptedChildren` drives every child before `anyEdgeBearing` is consulted.

#### C5 CONFIRMED — `commitPureFilterChild` lacks the boundary guard its sibling applies

Claim survived. `singleCapturedFilter` returns `null` on a non-boundary re-type; `commitPureFilterChild` replays it. Unreachable today because every non-boundary `addNode` is preceded by an `addEdge`.

#### C6 REFUTED — "the AND pre-check still lets a half-committed pattern escape on some decline path"

The claim was that reordering the check ahead of the commit loop might not cover every exit, leaving a pattern fragment or an alias filter on the parent when the AND then declines.

Traced every exit in `AndStepRecogniser.recognize`. The wrong-class exit and the null-boundary exit precede all child work. `walkAcceptedChildren` returns `null` on the first non-ACCEPTED child, and its own writes stay inside each child's adapter buffers — `capturedAliasFilters`, `capturedEdgeFilters`, `capturedPattern` — none of which reach the parent. `anyEdgeBearing` is a pure read over the adapter list. `commitPureFilterChild` runs only after the gate, for every arm or for none. The two rewritten unit tests assert both channels clean on a mixed AND, and the probe of `g.V().and(__.not(__.out("a")), __.out("b"))` shows zero boundary steps end to end.

Verdict: the ordering is correct for the pattern and filter channels. The residual channels are BG12, which is a comment-accuracy finding and not a partial commit.

#### C7 REFUTED — "a fourth path still reaches the old edge-bearing commit behaviour"

The claim was that `commitEdgeBearingChild`'s three reachable paths might not be the complete set, leaving a positive filter that still appends a captured hop.

Enumerated every way a captured child's hop can reach a parent's positive pattern. `commitEdgeBearingChild` is deleted. `ctx.appendPattern` has no production caller (C2). `NotStepRecogniser` reads `capturedPattern()` but routes it into a detached `SQLMatchExpression`, never the positive pattern — the deliberate exception. `commitPureFilterChild` copies alias classes only, and reaches a non-boundary one only for a hop target, which flips `hasEdges` and takes the decline (C5). `collectOrExpressions` declines any edge-bearing child at line 137. At the base commit the two literal call sites were `AndStepRecogniser:45` and `ConnectiveStepSupport:88`, the latter reached from both `WhereTraversalStepRecogniser:39` and `TraversalFilterStepRecogniser:77` — the three reachable paths the step reports, and all three now decline.

Verdict: complete. (grep-only, per the PSI caveat in BG9.)

#### C8 REFUTED — "the decline is a write the sub-walk adapter swallows rather than a real decline of the walk"

The claim, following the Step 9 finding that the adapter swallows `setLimit`, `setSkip`, `setOrderBy`, `setReturnDistinct`, `setGroupBy` and `setResultShaping`, was that `commitPositiveFilterChild` returning DECLINE might be absorbed somewhere and leave the traversal translated without the filter — an under-emission or over-emission with no decline.

`Outcome.DECLINE` is a return value on the dispatch path, not a context write, so the swallow list does not apply. `dispatchAll` returns `false` on the first DECLINE at line 364; the top-level `walk` turns that into `null` at line 317, and `subWalk` marks the adapter DECLINE at line 478. Every consumer of a declined adapter re-declines: `walkAcceptedChildren:42`, `commitPositiveFilterChild:111`, `NotStepRecogniser:64`, `collectOrExpressions` through `walkAcceptedChildren`. `UnionForkHostImpl.walkFork` runs a fresh top-level walk per arm and propagates the `null`. Measured end to end: `g.V().and(__.out("a"), __.out("b"))` and `g.V().and(__.not(__.out("a")), __.out("b"))` both splice zero boundary steps and return native's multiset.

Verdict: real decline, all the way out.

#### C9 REFUTED — "an edge-bearing `not` over-emits"

The claim was the over-emission half of the `not` exemption: that a detached anti-join might fan out the way an appended positive hop does.

It does not. `NotStepRecogniser` routes an edge-bearing child into `buildNotExpression` and appends the result to `notMatchExpressions`, which the planner applies as a filter over the positive match. The child's hop never enters the positive pattern, so it contributes no rows. Measured: `g.V().or(__.not(__.out("a")), __.has("age", 30))` and `g.V().and(__.not(__.out("a")), __.out("b"))` both agree with native.

Verdict: the over-emission argument in the `anyEdgeBearing` Javadoc is sound. The exemption still fails, on the under-emission side the argument does not address — BG9.

#### C10 REFUTED — "`assertNativeFanOut`'s final assertion compares two constants and can never fail"

The claim was that `assertThat(expectedJoinRows).isGreaterThan(expectedFilterRows)` in `EdgeTraversalEquivalenceTest` is tautological, since both operands are literals supplied at the call site.

It is a constant comparison, but it is not dead. The failure it guards is the maintenance move where someone removes the fan-out edge from the fixture, watches the two `hasSize` assertions fail, and repairs them by editing the expected counts down. The literal check fires on that edit and not on the fixture edit alone. The Javadoc's phrasing — "fails loudly if a later fixture edit removes the fan-out" — names the fixture edit as the trigger when the `hasSize` pair is what catches that, but the guard itself is real.

Verdict: not a defect. Worth one word in the Javadoc if the file is touched again.

#### C11 REFUTED — "the union fork host leaks a declining prefix's contributions into a sibling arm's plan"

The claim was that since `addNotMatchExpression` and `markRidBearing` escape the capture boundary, and `UnionForkHostImpl` re-walks the recognised prefix once per arm, a prefix containing a NOT could accumulate its expression across arms or survive an arm's decline.

`walkFork` builds a fresh `DefaultGraphTraversal`, clones the prefix and suffix steps into it, and calls `GremlinStepWalker.production().walk(forked)`. That constructs a new `WalkerContext` per arm at line 301, so no state crosses arms and a declining arm's context is discarded whole. `stashAcceptedChildren` moves only completed `MatchPlanInputs`.

Verdict: contained.
