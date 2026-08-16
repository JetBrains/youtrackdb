<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: BG1, sev: should-fix, loc: GremlinStepWalker.java:513-519, anchor: "### BG1 ", cert: C1, basis: "adding TailGlobalStepRecogniser to POST_UNION_RECOGNISERS makes the in-loop gate admit a positional post-union step it previously refused, so the pre-fork look-ahead is now the only guard against a translated union(...).tail(n); postUnionSuffixTranslatable's own 'the look-ahead only ever declines shapes the in-loop gate would decline' claim is false as of this commit"}
  - {id: BG2, sev: should-fix, loc: UnionStepRecogniser.java:127-132, anchor: "### BG2 ", cert: C2, basis: "the new child gate closes only the listShapingOps half of the rule its own javadoc states; a child carrying accumulateMap (group / groupCount) still lands in the one ResultShaping the multi-plan boundary applies once over the concatenation, so union(__.out().groupCount(), __.in().groupCount()) merges both arms into one map where native returns one map per arm — pre-existing, not introduced here"}
  - {id: BG3, sev: suggestion, loc: UnionStepRecogniser.java:30-31, anchor: "### BG3 ", cert: C3, basis: "the class javadoc says fold() and tail(n) both 'decline as positional', which contradicts the same commit's FoldStepRecogniser section and POST_UNION_RECOGNISERS exclusion paragraph — fold declines on membership and is never asked the positional question"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 3}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: REFUTED,   anchor: "#### C4 "}
  - {id: C5,  verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6,  verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] The in-loop post-union gate now admits a positional step, leaving the look-ahead as the only guard

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 513-519); allow-list change at `:333-339`; stale claim at `:900-903`

**Issue**: The post-union rule has two conditions — allow-list membership and `selectsPositionally` — and only one of the two readers applies both. `postUnionSuffixTranslatable` (`:922-943`) checks membership and then the positional question. `dispatchAll`'s in-loop gate (`:517`) checks membership alone:

```java
if (ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser)) {
  return false;
}
```

Before this commit `TailGlobalStepRecogniser` was off the allow-list, so the in-loop gate refused a post-union `tail` on its own and the two readers agreed. This commit puts `tail` on the list with a `true` positional answer, and `TailGlobalStepRecogniser.recognize` has no self-check of its own. Trace `g.V().union(a, b).tail(3)` with the look-ahead removed: `hasUnionCarrier()` is true, `tail` is in the set, no cardinality clause is captured, no list-shaping op is captured yet, so the recogniser runs and appends a `TailListShapingOp(3)`. The next `peek()` is null, `dispatchAll` returns true, and `buildResult`'s multi-plan branch (`:1020-1030`) ships `ctx.shaping()` — window included — into `MultiPlanMatchStep`. That window would keep the last three rows of a branch-major concatenation where native keeps the last three of an interleaving.

`RangeGlobalStepRecogniser`, the only other positional member, holds the line twice: the look-ahead asks it through `selectsPositionally`, and `recognizePostUnion` re-checks with `followedByCount(cursor)` (`:257`) before appending anything. `tail` now has one guard where `range` has two.

The same change falsifies a documented invariant. `postUnionSuffixTranslatable`'s javadoc says "Fail-closed is preserved in both directions — the look-ahead only ever declines shapes the in-loop gate would decline" (`:902-903`). For `union(...).tail(3)` the look-ahead declines and the in-loop gate does not, so the sentence is now wrong about the very member this commit added.

Nothing ships a wrong answer today. `hasUnionCarrier()` is set only by `stashAcceptedChildren`, which `UnionStepRecogniser.recognize` reaches only after `host.postUnionSuffixTranslatable()` returns true (`UnionStepRecogniser.java:96-98`), so the look-ahead runs before every union accept without exception, and `postUnionFoldAndTail_decline` pins both `tail(3)` spellings.

**Evidence** (`#### C1`): both gate bodies read end to end; the allow-list diff; `TailGlobalStepRecogniser.recognize` (`:118-132`) read in full and compared against `RangeGlobalStepRecogniser.recognizePostUnion` (`:230-262`); the single writer of the union carrier traced from `stashAcceptedChildren` back to the look-ahead call.

**Refutation considered**: I checked whether the walker's list-shaping gate catches the escape instead — it does not for the bare spelling, because `tail` appends the first op and the gate only fires on the *next* step, of which there is none. I checked whether the counted spelling escapes too: `union(...).tail(3).count()` passes the look-ahead, `tail` is admitted in-loop and appends, the drain latch arms (`:587-590`), and `count` is then refused by `mayFollowListShaping` — so that spelling declines through a second independent gate and is not at risk. I checked whether a transparent `NoOpBarrierStep` between `tail` and `count` could desynchronise the look-ahead's `peek(ahead + 1)` from dispatch: both skip transparent steps, and a desynchronisation would resolve the successor to an unregistered class and decline, which is the safe direction.

**Suggestion**: make the in-loop gate ask the same two questions the look-ahead asks, so the property holds by construction rather than by call order.

```java
// Post-union suffix gate (see POST_UNION_RECOGNISERS). Membership is necessary, not sufficient:
// a member that selects rows by position may only stand ahead of an immediate count(), which is
// the second condition postUnionSuffixTranslatable applies before the fork. Applying it here too
// keeps the in-loop gate no weaker than the look-ahead, so a future change to the fork path
// cannot leave a positional window riding the concatenation.
if (ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser)) {
  return false;
}
```

Either extend that branch with the positional check, or give `TailGlobalStepRecogniser.recognize` the `followedByCount` self-check its sibling has. Then correct `postUnionSuffixTranslatable`'s "in both directions" sentence, which is what a reader will trust when deciding whether the look-ahead may be moved or short-circuited.

### BG2 [should-fix] The new union child gate closes half of the rule its javadoc states: a `group` / `groupCount` arm still merges across the concatenation

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionStepRecogniser.java` (lines 127-132); rule statement at `:40-53`

**Issue**: The gate reads one component of `ResultShaping`:

```java
if (!childResult.shaping().listShapingOps().isEmpty()) {
  return Outcome.DECLINE;
}
```

The rule the class javadoc states above it is broader: "Both land in one `ResultShaping` that the multi-plan boundary applies once over the whole concatenation". `accumulateMap` is a second component with exactly that property. `AbstractMatchPlanStep.openShapedPayloads` (`:372-376`) branches on it before any list-shaping op runs, and `accumulatedGroupMapSource()` drains the whole projection stream into one map and emits one traverser.

Concrete shape: `g.V().union(__.out("knows").groupCount(), __.in("knows").groupCount())`. Each arm walks as its own top-level fork, `GroupCountStepRecogniser` reaches `GremlinAggregateAssembler.configureGroupCount`, which passes its three gates (no captured cardinality clause, no captured `GROUP BY`, boundary alias pinned by the prefix), resolves a bare `groupCount()` key to `@rid` (`resolveGroupKey:287-293` returns `aliasRecordAttribute(alias, "@rid")` rather than null), and calls `ctx.setResultShaping(ResultShaping.NONE.withAccumulateMap(true))` plus `pinBoundary(boundary, MAP, Vertex.class)`. Both arms produce that identical shaping, `listShapingOps()` is empty on both, so the new gate passes and `agreedShaping.equals(...)` passes too — record equality over two `withAccumulateMap(true)` instances is true, unlike the per-recognition op instances the javadoc's third paragraph relies on. The union accepts, `buildResult` ships `accumulateMap = true` on the multi-plan carrier, and the boundary drains both arms' grouped rows into one map. Native runs a `GroupCountStep` barrier inside each child and emits one map per arm.

This predates the commit — nothing in the diff changed the `accumulateMap` path, and DR-T3 scopes item 4 to the list-shaping carrier deliberately. It belongs here because the gate and its javadoc are the place a reader will go to ask "is the union child-shaping path closed?", and after this commit the answer reads yes while one shaping component is still open. If closing it is out of step scope, the honest cheap move is to say so at the gate.

**Evidence** (`#### C2`): `configureGroupCount` (`GremlinAggregateAssembler.java:230-254`) and `resolveGroupKey` (`:285-295`) read in full; `ResultShaping`'s eight components read in full; `AbstractMatchPlanStep.openShapedPayloads` / `applyListShaping` (`:372-396`) read in full; `UnionStepRecogniser.recognize` read end to end for any second gate on child shaping. Static analysis only — the shape was not executed (see the method caveat).

**Refutation considered**: I checked whether some other gate declines a MAP-output arm before the shaping comparison — the child loop has three declines above the new gate (nested union, null or multi-plan child result) and one below (output type, return class, shaping equality), and none of them reads `accumulateMap` or `outputType == MAP`. I checked whether the arms would disagree and decline by accident the way the fold arms do: they would not, because `withAccumulateMap(true)` produces two equal records where the op recognisers produce two distinct instances, which is the precise hazard the javadoc's third paragraph raises against the contract comparison. I checked whether `groupCount()` needs a preceding `values(k)` to resolve a key, which would make the shape unreachable off a bare hop: it does not — the no-`by` route falls through to the `@rid` attribute.

**Suggestion**: either widen the gate to the stream-level components, or record the narrowing at the gate so the next reader knows it is deliberate.

```java
// A child that registered a list-shaping stage declines the union outright, before and
// independently of the contract comparison below — see the class javadoc's "A child that
// shapes a list declines the union" for why agreement is not the question here.
//
// Scope: this reads listShapingOps only. accumulateMap has the same property — one shaping
// applied once over the concatenation — so union(__.out().groupCount(), __.in().groupCount())
// still merges both arms into one map where native returns one per arm. That predates this
// gate and is tracked separately; widening the condition here is the fix when it is taken up.
if (!childResult.shaping().listShapingOps().isEmpty()) {
  return Outcome.DECLINE;
}
```

Add `union(__.out(k).groupCount(), __.in(k).groupCount())` to `UnionTraversalEquivalenceTest` in whichever direction the decision goes — as a decline beside `unionArmCarryingAListShapingStage_declines`, or as an executed equivalence case if the merge turns out to agree with native for a reason this read missed.

### BG3 [suggestion] The union javadoc says `fold()` declines as positional, which the same commit's other two sites contradict

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionStepRecogniser.java` (lines 30-31)

**Issue**: The sentence reads "{@code order()} after a union declines (no post-concat sort in this cut), {@code fold()} and {@code tail(n)} decline as positional". Two other sites added in the same commit say the opposite about `fold`. `FoldStepRecogniser`'s new section (`:44-55`) says it "is kept off the path by the first of them: it is absent from {@code GremlinStepWalker.POST_UNION_RECOGNISERS}, so the second condition … is never asked". `POST_UNION_RECOGNISERS`' exclusion paragraph (`GremlinStepWalker.java:320-331`) says the same, and `foldIsNotOnThePostUnionAllowList_andStatesNoPositionalAnswer` asserts that `FoldStepRecogniser` declares no `selectsPositionally` at all.

The consequence is small but concrete: a reader who takes this sentence at face value looks for a `selectsPositionally` override on `FoldStepRecogniser`, finds none, and has to reconstruct which of the two accounts is right. The two terminators decline through different conditions, and that difference is the recorded design decision of this step.

**Evidence** (`#### C3`): the three sites read side by side within the commit diff; the assertion in the new walker test read in full.

**Refutation considered**: I checked whether "positional" is being used loosely here to mean "for the ordering reason" rather than "through the `selectsPositionally` gate" — the `tail(n)` half of the same sentence means the gate, since `tail` is a member that answers `true`, so the clause covers both terminators with one mechanism word and only one of them goes through that mechanism.

**Suggestion**: split the two.

```
 * ... {@code order()} after a union declines (no post-concat sort in this cut), {@code tail(n)}
 * declines through the positional gate, {@code fold()} declines through the membership gate
 * before that question is asked, and every other step class declines too. {@code
 * GremlinStepWalker.POST_UNION_RECOGNISERS} argues each membership and the one recorded exclusion.
```

## Evidence base

METHOD CAVEAT: this review is static. No traversal was executed and no test was run, so every divergence claim is derived from reading the recognition path, the shaping record, and the boundary base's application order end to end rather than from a translator-on / translator-off measurement. Symbol questions were answered by grep over `core/src/{main,test}/java` followed by full reads of the returned files rather than by PSI find-usages; the mcp-steroid server was reachable but no finding here rests on a "these are all the callers" claim — BG1 and BG3 rest on reading two gate bodies and three javadoc sites, and BG2 rests on a reachability chain (union child fork → `GroupCountStepRecogniser` → `configureGroupCount` → `accumulateMap`) where each link was read in full. BG2's end-to-end runtime behaviour is the one claim a measurement would upgrade from derived to observed.

SCOPE: the diff is nine files, of which four carry executable change — three one-line `selectsPositionally` overrides, three new entries in `POST_UNION_RECOGNISERS`, and one four-line gate in `UnionStepRecogniser`. The rest is javadoc and tests. The reachable post-union step combinations were enumerated rather than sampled: for each of `{unfold, reverse, tail}` × `{last, followed by count, followed by dedup, followed by range, followed by another shaper}` and for each post-concat op followed by a shaper, the look-ahead's verdict, the in-loop gates' verdicts, and the runtime application order were traced. Only the two spellings in `#### C7` reach a translated result with a shaping op on the multi-plan carrier.

#### C1 The in-loop post-union gate admits a positional member the look-ahead refuses — CONFIRMED

`dispatchAll:517` tests membership only; `postUnionSuffixTranslatable:932-941` tests membership and then `selectsPositionally` with a `CountGlobalStepRecogniser` successor requirement. `TailGlobalStepRecogniser.recognize` appends unconditionally, where `RangeGlobalStepRecogniser.recognizePostUnion` re-applies `followedByCount` before appending. Adding `tail` to the allow-list therefore moves the guard for `union(...).tail(n)` from two independent gates to one, and falsifies the "the look-ahead only ever declines shapes the in-loop gate would decline" sentence at `:902-903`. Raised as BG1.

#### C2 A `group` / `groupCount` union arm still merges across the concatenation — CONFIRMED

`configureGroupCount` sets `accumulateMap` and pins `MAP` for a bare `groupCount()` off a hop (its key resolves to `@rid`, not null). The new child gate reads `listShapingOps()` alone, and the contract comparison passes because two `withAccumulateMap(true)` records compare equal. `openShapedPayloads` branches on `accumulateMap` before any list-shaping op, so the boundary drains the whole concatenation into one map against native's one map per arm. Pre-existing rather than introduced. Raised as BG2.

#### C3 The union javadoc's decline mechanism for `fold` contradicts two sites in the same commit — CONFIRMED

`UnionStepRecogniser:30-31` attributes both `fold()` and `tail(n)` to the positional gate; `FoldStepRecogniser:44-55`, `GremlinStepWalker:320-331` and `foldIsNotOnThePostUnionAllowList_andStatesNoPositionalAnswer` all place `fold`'s decline at membership, before the positional question is asked. Raised as BG3.

#### C4 The post-union list-shaping op is dropped between the walk result and the boundary step — REFUTED

CLAIM: `postUnionUnfoldAndReverse_translateAndCarryTheirStage` asserts only that `walked.shaping().listShapingOps()` has size 1 on the `TranslationResult`. Over `ELEMENT` payloads both stages are pass-throughs, so if the strategy's multi-plan splice dropped the shaping the equivalence half would still pass and the stage would silently never run.

REFUTATION: the shaping reaches the step. `buildResult`'s multi-plan branch passes `ctx.shaping()` into `TranslationResult.multiPlan` (`GremlinStepWalker.java:1023-1029`), and `GremlinToMatchStrategy.replaceAllStepsWithBoundary` passes `translation.shaping()` into the seven-argument `MultiPlanMatchStep` constructor (`:569-577`), which forwards it to `super(...)`. `AbstractMatchPlanStep.applyListShaping` then reads `shaping.listShapingOps()` on every open. The test's coverage gap is real but the code path is not broken, so there is nothing to raise on the production side.

#### C5 Post-concat ops and list-shaping ops apply in the wrong relative order — REFUTED

CLAIM: `unfold` and `reverse` on the allow-list newly admit `union(...).count().unfold()`, `union(...).dedup().unfold()` and `union(...).count().reverse()`. If the boundary applied the list-shaping stage before the post-concat reductions, all three would return a different answer from native, which applies the reduction where the user wrote it.

REFUTATION: the order is post-concat first. `MultiPlanMatchStep.startPlanStream()` builds the concatenator and wraps it with one `PostConcatStreams` decorator per op in recognised order (`:337-341`); the base's `openShapedPayloads()` then runs over that already-decorated stream and applies the list-shaping ops last (`AbstractMatchPlanStep:372-396`). Traced for each reachable pair: `count().unfold()` takes the `isPushDownCountOnly` branch, sums the children's `RETURN count(*)` rows, projects one `SCALAR` payload, and `UnfoldListShapingOp` passes a `Long` through as one — which is what native's `unfold()` over a `Long` does. `dedup().unfold()` dedups the concatenation, projects elements, and expands each vertex to itself. `count().reverse()` passes the `Long` through the non-reversible arm. All three agree with native.

#### C6 A post-union shaper ahead of a post-concat op ships a `count(*)` over pre-stage rows — REFUTED

CLAIM: the track's technical review records this shape as E2 — a list-shaper captured post-union, then a `count` whose `configurePostUnionCount` appends `PostConcatOp.Count` and calls `setResultShaping(NONE)`, wiping the stage and returning the concatenation's row count where native counts one drained payload. Adding three shapers to the allow-list is exactly what makes the first half reachable.

REFUTATION: the walker's list-shaping gate refuses the second half before the count recogniser runs. `dispatchAll:531-538` fires on a captured op and `mayFollowListShaping` admits only a per-payload shaper or a drain, neither of which `CountGlobalStepRecogniser` is, so `union(...).unfold().count()`, `union(...).reverse().count()` and `union(...).tail(3).count()` all return `false` at the gate — the recogniser is never dispatched and `setResultShaping(NONE)` is never reached. The same gate refuses `union(...).unfold().dedup()` and `union(...).unfold().limit(2)`. `union(...).fold().count()`, the review's original E2, additionally fails membership at `:517` because `FoldStepRecogniser` was deliberately left off the list. The three new members close E2 rather than open it.

#### C7 A post-union `tail(n)` can ship a positional window today — REFUTED

CLAIM: BG1's escape is live, not latent — some suffix lets `tail` reach the fork and complete the walk.

REFUTATION: the two conditions cannot both hold. The look-ahead admits `tail` only when the next step resolves to `CountGlobalStepRecogniser`, and the drain latch (`:587-590`) arms the moment `tail` appends, so that following `count` is refused by `mayFollowListShaping` and the whole walk declines. The complementary spelling — `tail` last — is what the look-ahead refuses, and the look-ahead runs on every path that sets the union carrier (`UnionStepRecogniser.recognize:96-98` guards the only call to `stashAcceptedChildren`). `postUnionFoldAndTail_decline` pins both. The finding stands as a single-guard and stale-invariant concern, which is why BG1 is should-fix rather than blocker. Only `union(...).unfold()`, `union(...).reverse()` and their compositions reach a translated multi-plan result carrying a shaping op.

#### C8 The mid-loop decline strands work the earlier arms did — REFUTED

CLAIM: the new gate returns `DECLINE` from inside the child loop after `host.walkFork(...)` already ran for that arm and possibly for earlier arms, so per-arm state (minted aliases, built plan inputs, positional parameters) is left behind on the parent context or on the host.

REFUTATION: nothing is stranded. `walkFork` builds a fresh `DefaultGraphTraversal` and runs a full top-level walk with its own `WalkerContext` (`UnionForkHostImpl:74-82`), so an arm's contributions live in the discarded child context rather than on the parent; the accumulated `childInputs` / `childParams` / `childCacheEligible` lists are method locals; and `stashAcceptedChildren` is reached only after the loop completes. The new gate sits beside two pre-existing mid-loop declines (`childResult == null`, `isMultiPlan()`) with the same exit shape, so it introduces no pattern the loop did not already have. A declined union declines the whole walk, and `GremlinToMatchStrategy` leaves the traversal on the native pipeline.

#### C9 The new tests reference symbols that do not exist or index the wrong steps — REFUTED

CLAIM: the two test files gain code with no matching imports or helpers — `Arrays.stream` and `Assert.assertNotNull` in `GremlinStepWalkerTest`, `seedLongKnowsChain()` and `Map.entry` in `UnionTraversalEquivalenceTest` — and the positional test reads steps by hard-coded index off traversals that never had `applyStrategies()` applied.

REFUTATION: every symbol resolves. `GremlinStepWalkerTest` already imports `java.util.Arrays` (`:23`), `org.junit.Assert` (`:45`), `ListShapingOp` (`:13`), `ResultShaping` (`:14`) and `mock` (`:5`); `UnionTraversalEquivalenceTest` adds `java.util.Map` in this diff (`:11`) and `seedLongKnowsChain()` exists at `:799` with three pre-existing callers. The indices are right for an un-strategised admin: `V().unfold()` is `[GraphStep, UnfoldStep]`, `V().values("name").reverse()` is `[GraphStep, PropertiesStep, ReverseStep]`, `V().tail(2)` is `[GraphStep, TailGlobalStep]`. `ListShapingOp` has one abstract method, so `upstream -> upstream` is a valid lambda, and `ResultShaping.withListShapingOps` exists at `ResultShaping.java:106`.

#### C10 `ReverseListShapingOp` mutates the payload it was handed — REFUTED

CLAIM: the diff newly puts `reverse` on the post-union path, where payloads come from the concatenated child streams. `reverseValue` calls `Collections.reverse(elements)` in place, so a collection-shaped payload backed by record state would be reversed under the reader.

REFUTATION: the list is freshly allocated. `IteratorUtils.asList(payload)` returns a new mutable `ArrayList` — the op's own comment says so and native `ReverseStep` uses the same two lines — so the in-place reverse touches a copy. The op is also stateless across pulls and across `apply` calls, which is what the `ListShapingOp` javadoc's new clone paragraph requires of a shared instance.
