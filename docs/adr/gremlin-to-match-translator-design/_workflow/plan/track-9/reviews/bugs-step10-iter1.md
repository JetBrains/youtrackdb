<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: BG23, sev: should-fix, loc: WhereTraversalStepRecogniserTest.java:110-137, anchor: "### BG23 ", cert: C1, basis: "two decline guards in the file the step edited now decline at the WhereStartStep instead of at the mechanism their javadoc credits; mutation-proven — deleting the hasEdges decline from commitPositiveFilterChild leaves whereTraversalStep_edgeBearingChild_declines green while the TraversalFilterStep sibling fails"}
  - {id: BG24, sev: should-fix, loc: GremlinStepWalker.java:443-447, anchor: "### BG24 ", cert: C2, basis: "the complement the gate deliberately keeps — hop then slice — returns a different sequence than native for an order()-bearing traversal: order().by(name).out(knows).limit(3).values(name) is [AbeTarget1, AbeTarget2, ZedTarget2] on against [AbeTarget2, AbeTarget1, ZedTarget2] off, measured; the step's own test for the shape uses a bound equal to the full output and cannot see it"}
  - {id: BG25, sev: should-fix, loc: RangeGlobalStepRecogniser.java:50-55, anchor: "### BG25 ", cert: C3, basis: "the drop-on-absent carve-out and the test that pins it credit a presence conjunct the predicate never reads: order().by(name).values(name).limit(2) declines with the conjunct present, and limit(2).values(name) translates with no order prefix at all, so orderThenLimitThenValues_stillTranslates pins nothing sliceThenValues_stillTranslates does not"}
  - {id: BG26, sev: suggestion, loc: GremlinStepWalkerTest.java:900-910, anchor: "### BG26 ", cert: C4, basis: "assertDeclinesAndMatchesNative's javadoc rests the empty-against-empty case on 'the mutation below', and no mutation exists anywhere in the file — the same shape as step 11's BG10 on a branch with twelve recorded vacuous-acceptance instances"}
  - {id: BG27, sev: suggestion, loc: GremlinStepWalker.java:147-154, anchor: "### BG27 ", cert: C5, basis: "the priced surface is every WhereTraversalStep, not 'the labelled where(__.as(a)…) family, and only that family': TinkerPop inserts a WhereStartStep for a labelled start and for a labelled end alike, so the recogniser's accept path survives only for where(__.where(key, P))"}
  - {id: BG28, sev: suggestion, loc: WhereTraversalStepRecogniserTest.java:36-38, anchor: "### BG28 ", cert: C6, basis: "two test-local TRANSPARENT copies still carry WhereStartStep and WhereEndStep, contradicting the production set the step just changed; inert today only because those classes never appear at the outer level these cursors walk"}
evidence_base: {section: "## Evidence base", certs: 15, matches: 6}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED,   anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED,   anchor: "#### C12 "}
  - {id: C13, verdict: REFUTED,   anchor: "#### C13 "}
  - {id: C14, verdict: REFUTED,   anchor: "#### C14 "}
  - {id: C15, verdict: REFUTED,   anchor: "#### C15 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG23 [should-fix] Two decline guards in the edited test file now fire at the scope-binding gate, not at the mechanism their Javadoc names

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WhereTraversalStepRecogniserTest.java` (lines 110-137, tests `whereTraversalStep_edgeBearingChild_declines` and `whereTraversalStep_declinedChild_declines`); production mechanism at `ConnectiveStepSupport.java:110-118`

**Issue**: the step converted one test in this file from ACCEPTED to DECLINE and left the two neighbouring decline tests alone. Both still pass, and both now pass for the new reason. `whereTraversalStep_edgeBearingChild_declines` says it exists because "the two spellings enter through different recogniser classes, and a gate added on only one of them would leave the over-emission live on the other" — that is exactly the guarantee it has stopped providing.

The path: `where(__.as("a").out("knows"))` builds a `WhereTraversalStep` whose child step list is `[WhereStartStep("a"), VertexStep]`. `WhereTraversalStepRecogniser` calls `ctx.walkChild`, which reaches `GremlinStepWalker.subWalk` and builds a cursor over the production `TRANSPARENT_STEPS`. `WhereStartStep` is no longer in that set and has no registry entry, so `dispatchAll` returns `false` at index 0. The adapter is marked DECLINE and `commitPositiveFilterChild` returns at its first line, before `adapter.hasEdges()` is read. The same argument covers `whereTraversalStep_declinedChild_declines`: the child is `[WhereStartStep("a"), CountGlobalStep]`, and the count never dispatches.

**Evidence** (`#### C1`): mutation-proven. Deleting the `adapter.hasEdges()` decline from `ConnectiveStepSupport.commitPositiveFilterChild` and running the class gives `Tests run: 7, Failures: 1` — the failure is `edgeBearingChild_declines:67`, the `TraversalFilterStep` spelling, which is the positive control showing the mutation bites. `whereTraversalStep_edgeBearingChild_declines` stays green. Measured in the `t9-step10` worktree at `1ee9a270df`; the mutation was reverted and the worktree confirmed clean.

**Refutation considered**: I checked whether the test-local `TRANSPARENT` set at lines 36-38, which still lists both scope classes, keeps the old behaviour alive for these cases. It does not — that set is only handed to the outer cursor built by `cursorAtWhereTraversal`, while the child sub-walk goes through `GremlinStepWalker.subWalk`, which reads the production field. I checked whether some other test still covers the edge-bearing gate on the `WhereTraversalStep` spelling: `grep` over `core/src/test/.../gremlin` finds `where(__.as(` in only two files, this one and `GremlinStepWalkerTest`, and the latter's new cases assert declines whose stated cause is the scope binding. So the coverage the Javadoc claims is gone from the tree, not merely from this test.

**Suggestion**: rewrite both Javadocs to say the decline now arrives from the scope-binding gate, and drop the "pinned separately" rationale, which no longer holds. If a `WhereTraversalStep`-spelling guard for the edge-bearing gate is still wanted, drive the child through a registry that recognises `WhereStartStep` (a no-op recogniser in the test fixture), so the walk reaches `commitPositiveFilterChild` and the `hasEdges` decline is the one under test.

### BG24 [should-fix] The ordering the gate keeps — hop then slice — returns a different sequence than native for an `order()`-bearing traversal

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 443-447, "Almost no suffix is exempt"); tests `OrderRangeStepRecogniserTest.hopThenLimit_stillTranslates:515` and `GremlinStepWalkerTest.walk_hopThenSlice_translates:1069`

**Issue**: the gate closes the ordering where the slice comes first and re-affirms the other one as safe — "A terminal slice is unaffected". The affirmation is too strong. A statement-level `LIMIT` changes the order in which the translated plan emits rows, and for a traversal that asked for an order the sequence is the answer:

```
seed: Zed --knows--> ZedTarget1, ZedTarget2;  Abe --knows--> AbeTarget1, AbeTarget2

g.V().order().by("name").out("knows").values("name")
  ON  [AbeTarget2, AbeTarget1, ZedTarget2, ZedTarget1]
  OFF [AbeTarget2, AbeTarget1, ZedTarget2, ZedTarget1]     agrees

g.V().order().by("name").out("knows").limit(3).values("name")
  ON  [AbeTarget1, AbeTarget2, ZedTarget2]
  OFF [AbeTarget2, AbeTarget1, ZedTarget2]                 one boundary step, sequence differs
```

The unsliced spelling agrees, so the `LIMIT` is what moves the rows. The multiset is preserved here because the reordering stayed inside one `ORDER BY` tie group, and nothing in the mechanism guarantees that: MATCH's `ORDER BY a.name` is a partial order over rows keyed on the target, and a bound that cuts inside a tie group picks an arbitrary member on each arm. Gremlin's `order()` is a stable sort and keeps input order among ties.

The argument that closed A1 reaches this shape verbatim. `RangeGlobalStepRecogniser`'s own post-union section says "MATCH promises no arrival order of its own" and declines a post-union slice because "the multi-plan boundary's positions are not native's". The single-plan post-hop slice rests on the same assumption and this step does not check it — `hopThenLimit_stillTranslates` sets the bound to three, the hop's full output, with the reason written into its Javadoc: "both arms return every row and the comparison does not rest on the two pipelines agreeing about which rows come first". The test is constructed so it cannot detect this.

**Evidence** (`#### C2`): measured translator-on against translator-off in the `t9-step10` worktree at `1ee9a270df`, six-vertex fixture, boundary-step count read off `applyStrategies()`. Bare `g.V().out(knows).limit(n)`, `skip(n)` and `range(1,2)` all agree in ordered form on both the one-hub and two-hub fixtures, so the divergence needs the `order()` prefix to appear; the row *set* stayed equal in every case I ran.

**Refutation considered**: I checked whether MATCH's row order tracks the scan order for the simple shapes, which would make the whole class benign — it does for `g.V().limit(2)`, `g.V().limit(2).values(name)`, `g.V().out(knows).limit(1|2|3)` and `g.V().out(knows).skip(1)`, all ordered-equal. I checked whether the divergence is the `order()` recogniser mis-keying the sort onto the target rather than the source, which would be a different and larger defect: on a fixture where sorting by source name and by target name give different sequences, `order().by(name).out(knows).values(name)` returns `[Zt, Yt, At]` on both arms, so the sort key is the source and the ranking is right. What is left is tie order under a slice. I checked whether this step introduced the shape — it did not; `hopThenSlice` translated before the step too, so the disposition is a step of its own rather than a fix inside this one.

**Suggestion**: record it as a measured divergence with a named owner rather than leaving `hopThenLimit_stillTranslates`'s Javadoc as the only place the hazard is written down. If it takes the decline exit, the narrow rule is a captured `ORDER BY` plus a slice on a boundary the sort does not totally order. If it takes the fix exit, the sort needs a tie-break on the boundary's RID so the translated order is total, which also makes `union(...).order().by(k).limit(n)` reachable — the recovery path the post-union section already names.

### BG25 [should-fix] The drop-on-absent carve-out credits a presence conjunct the predicate never reads

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (lines 50-55 and the guard at 138-143); test `OrderRangeStepRecogniserTest.orderThenLimitThenValues_stillTranslates:569`

**Issue**: the class Javadoc says the decline is keyed on whether the drop still has anything to remove — "Where a preceding step already contributed a presence conjunct into the pattern … there is nothing left to mis-count — which is why `g.V().order().by(k).limit(n).values(k)` stays translatable: `order().by(k)` writes `k IS DEFINED`, so the shaping drop is a no-op." The guard reads one boolean, `ctx.dropsRowsOnAbsentProperty()`, which is `shaping.dropOnAbsent()` and knows nothing about the pattern's conjuncts. Both directions of the claim fail:

```
seed: Carol, Alice, Bob (named);  one vertex with age=7 and no name

g.V().order().by("name").values("name").limit(2)   0 boundary steps — declines
g.V().limit(2).values("name")                      1 boundary step  — translates, no order prefix
```

The first has the conjunct and is declined anyway. The second has no conjunct, no `order()`, and is accepted. So the cited shape stays translatable because the range ran before the shaping was set, and the `IS DEFINED` conjunct explains why the answer is right, not why the recogniser accepts. The test built on the claim inherits it: `orderThenLimitThenValues_stillTranslates` opens "The decline is keyed on the projection's own drop, not on projecting at all … The fixture carries a name-less vertex precisely so the drop has something to bite on", and neither the `order().by(name)` prefix nor the name-less vertex changes its outcome. `sliceThenValues_stillTranslates` already pins everything it pins.

The code is safe in both directions — the guard is strictly more conservative than the rule the prose states. What is wrong is the written invariant and the guard that is supposed to hold it, which is the failure shape DR-S13 was raised to prevent.

**Evidence** (`#### C3`): measured in the `t9-step10` worktree at `1ee9a270df` on the four-vertex fixture the test itself seeds; boundary-step count read off `applyStrategies()`. Both arms return the same rows in every case, so the divergence is in the accept/decline decision only.

**Refutation considered**: I checked whether `order().by(k)` sets a shaping of its own that would explain the decline through some other route — `OrderGlobalStepRecogniser` calls only `ByModulatorPresence.requireModulatedProperty`, and `grep` over the strategy package finds `setResultShaping` written from `GremlinProjectionAssembler` alone. I checked whether the guard's placement after the no-op test is what the prose meant — it is not; that placement is documented separately and correctly in the inline comment at 138-140, and it governs `skip(0)`, not the conjunct case.

**Suggestion**: rewrite the carve-out paragraph to say what the guard does — a slice accepted *before* the projection is sound because both arms take `k` rows and then drop, and the conjunct case is not distinguished. Then either give `orderThenLimitThenValues_stillTranslates` a discriminator (a shape that declines when the `order()` prefix is removed, which needs the conjunct to be readable and is the fix exit rather than a test change) or retire it as a duplicate of `sliceThenValues_stillTranslates`.

### BG26 [suggestion] A test Javadoc rests its case on a mutation that is not in the file

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java` (lines 900-910, `whereWithSelfComparingEndLabel_declinesAndReturnsNativeRows` and the `assertDeclinesAndMatchesNative` helper)

**Issue**: the case runs on a fixture where both arms are empty, and its Javadoc concedes the multiset assertion carries nothing: "Both arms are empty once the walk declines, so the boundary-step assertion is what carries this case — the mutation below is what proves it." Nothing below the sentence is a mutation. The helper takes an `expectRows` flag, and this call passes `false`, which is the switch that turns the non-empty guard off. A reader auditing the case for vacuity is pointed at a proof the tree does not contain.

This is the shape step 11's BG10 recorded, on a branch with twelve recorded vacuous-acceptance instances and a Decision Log entry saying a decline assertion needs a positive control of the same shape, measured rather than derived. The positive control does exist — `whereWithEndLabel_declinesAndReturnsNativeRows` passes `expectRows=true`, and `walk_plainWhereChild_stillTranslates` holds the accepting side — so the gap is the citation, not the coverage.

**Evidence** (`#### C4`): read of the committed file; `grep` for `mutat` in the test file returns only this sentence.

**Refutation considered**: I checked whether "the mutation below" could name the `expectRows=false` argument or the helper's own non-empty guard, read loosely — neither is a mutation, and the helper's guard is what the `false` disables. I checked whether a mutation lives in a sibling file, since the implementer plainly ran one; `grep` over `core/src/test/.../translator` finds no such case.

**Suggestion**: replace the dangling clause with what was actually done — name the mutation that was run and its result, or drop the sentence and let the boundary-step assertion stand on its own with the `expectRows=true` sibling cited as the positive control.

### BG27 [suggestion] The priced surface is every `WhereTraversalStep`, not only the `where(__.as(a)…)` spelling

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 147-154)

**Issue**: the transparency-set Javadoc prices the change as "the labelled `where(__.as(a)…)` family, and only that family". The class it is reasoning about is inserted more widely than that. `WhereTraversalStep.configureStartAndEndSteps` inserts a `WhereStartStep` in two arms — a variable start step becomes one carrying the label, and a child whose *end* step is labelled gets one inserted with a `null` label — and `GraphTraversal.where(Traversal)` builds a `WhereTraversalStep` only when the child has a variable location at all. Every ordinary `WhereTraversalStep` child therefore carries a scope step, including the `where(__.out().as("b"))` spelling that has no `as(a)` at its start. The residue where `WhereTraversalStepRecogniser` can still accept is a child whose sole variable comes from a `WherePredicateStep` key, spelled `where(__.where("a", P.eq("b")))`, which is not a shape anyone writes.

The Javadoc's three measured bullets are correct and the decline is the right call for each. What the sentence understates is that the recogniser is now near-unreachable, so a reader deciding whether some later change to `WhereTraversalStepRecogniser` matters will over-estimate its reach. One incidental consequence worth recording rather than losing: step 11's BG11, the confirmed mis-keying of a path-scoped `where` onto the current boundary, is unreachable through this class while the decline stands.

**Evidence** (`#### C5`): read of the decompiled `WhereTraversalStep.configureStartAndEndSteps` from `gremlin-core-3.8.1-67860f6-SNAPSHOT.jar`, the version `pom.xml:114` pins, plus a measured probe — `g.V().as("a").where(__.as("a").has("name","Hub"))` reports zero boundary steps and matches native.

**Refutation considered**: I checked whether the recogniser is dead outright, which would make this a removal question rather than a wording one — `WherePredicateStepRecogniser` does handle a start key and can accept, so the exotic spelling above still reaches an ACCEPTED. I checked whether the connective-shared commit path dies with it: `ConnectiveStepSupport.commitPositiveFilterChild` is still reached from `TraversalFilterStepRecogniser` and `AndStepRecogniser`, so nothing is left with zero production callers the way `appendPattern` was.

**Suggestion**: widen the sentence to "every `WhereTraversalStep`, which is every `where` whose child carries a scope label at either end", and add one line noting the recogniser's accept path now survives only for the `where(__.where(key, P))` spelling, so the next reader knows what the class is still for.

### BG28 [suggestion] Two test-local transparent-step copies still carry the classes the step removed

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WhereTraversalStepRecogniserTest.java` (lines 36-38) and `WherePredicateStepRecogniserTest.java` (lines 35-37)

**Issue**: both files keep a private `TRANSPARENT` set that duplicates the pre-change production one, `WhereStartStep` and `WhereEndStep` included. They are inert today because the cursors built from them walk the outer traversal, where neither class appears. They now say the opposite of what production says, in the two files most likely to be read by whoever revisits this decision, and a future test that walks a child list through the local set would silently restore the behaviour the step removed.

**Evidence** (`#### C6`): `grep` for `WhereStartStep|WhereEndStep` over `core/src` returns these two sets plus Javadoc mentions; the tests pass either way, which is what makes the staleness silent.

**Refutation considered**: I checked whether either set feeds a child sub-walk, which would make this a live defect rather than a stale copy — `ctx.walkChild` routes to `GremlinStepWalker.subWalk`, which builds its cursor from the production field, and neither test constructs a cursor over a child step list.

**Suggestion**: reduce both to `Set.of(NoOpBarrierStep.class)`, matching production, in the same commit that changed it.

## Evidence base

#### C1 CONFIRMED — the two `WhereTraversalStep` decline guards no longer reach their stated mechanism

Claim survived. Mutation run in the `t9-step10` worktree at `1ee9a270df`: with the `adapter.hasEdges()` decline removed from `ConnectiveStepSupport.commitPositiveFilterChild`, `WhereTraversalStepRecogniserTest` reports `Tests run: 7, Failures: 1`, the single failure being `edgeBearingChild_declines:67` (the `TraversalFilterStep` spelling, the positive control). `whereTraversalStep_edgeBearingChild_declines` stays green. The declined-child case shares the root cause by construction — the child list is `[WhereStartStep, CountGlobalStep]` and `dispatchAll` returns at index 0. Mutation reverted; `git status --porcelain` clean.

#### C2 CONFIRMED — a statement-level `LIMIT` reorders the translated arm's rows under an `order()` prefix

Claim survived. Measured on a six-vertex two-hub fixture: `g.V().order().by("name").out("knows").limit(3).values("name")` returns `[AbeTarget1, AbeTarget2, ZedTarget2]` translator-on against `[AbeTarget2, AbeTarget1, ZedTarget2]` translator-off, one boundary step; the same shape without the slice agrees in ordered form on both arms. Row sets agreed in every measured case, so the finding is stated as an order divergence with a set risk that is argued rather than reproduced.

#### C3 CONFIRMED — the drop-on-absent guard does not read the presence conjunct its Javadoc credits

Claim survived. Measured on the four-vertex fixture `orderThenLimitThenValues_stillTranslates` seeds: `g.V().order().by("name").values("name").limit(2)` reports zero boundary steps (declines with the conjunct present) and `g.V().limit(2).values("name")` reports one (translates with no `order()` prefix). Both arms return identical rows in each case, so the divergence is confined to the accept/decline decision.

#### C4 CONFIRMED — the cited mutation is absent from the test file

Claim survived. `GremlinStepWalkerTest:906` reads "the mutation below is what proves it"; `grep` for `mutat` over the file returns that sentence only, and over `core/src/test/.../translator` returns no mutating case.

#### C5 CONFIRMED — every ordinary `WhereTraversalStep` child carries a scope step

Claim survived. `WhereTraversalStep.configureStartAndEndSteps`, disassembled from the pinned `gremlin-core-3.8.1-67860f6-SNAPSHOT` jar, inserts a `WhereStartStep` when the start step is a variable start step and, failing that, when the end step is labelled; `GraphTraversal.where(Traversal)` routes a child with no variable location to `TraversalFilterStep` instead. The surviving accept path needs a child whose only variable is a `WherePredicateStep` key.

#### C6 CONFIRMED — two stale transparent-step copies remain in the test tree

Claim survived. `WhereTraversalStepRecogniserTest:36-38` and `WherePredicateStepRecogniserTest:35-37` still list both scope classes; the production field is now `Set.of(NoOpBarrierStep.class)`.

#### C7 REFUTED — "the gate omits a statement-level clause, and the omission produces a wrong answer"

The claim was that `capturedCardinalityClause` names three of the statement-level slots `buildResult` fills and leaves `orderBy` and `groupBy` out, so the generalisation the track adopted should predict a fourth and fifth face of the same defect.

The slot inventory is right and the consequence is not. `grep` for `setLimit(`, `setSkip(` and `setReturnDistinct(` over the strategy package finds exactly two writers, `RangeGlobalStepRecogniser` and `DedupGlobalStepRecogniser`, so the gate is complete over the clauses it names. `ORDER BY` is cardinality-neutral: it changes which rows a later clause selects only through a sequence, and measured on a fixture built so that sorting by source name and by target name disagree, `g.V().order().by("name").out("knows").values("name")` returns `[Zt, Yt, At]` on both arms — the sort key is the source alias and the ranking matches native. `GROUP BY` flips the boundary output type to `MAP`, and the successors that could ride past it either have no recogniser or decline through `hasGrouping`; `group().by(name)` and `groupCount().by(name)` differ between arms only in map iteration order, which `Map.equals` does not observe.

Verdict: no wrong answer from the omission. The residue is the tie-order effect under a slice, which is BG24 and is reported against the shape rather than against the clause list.

#### C8 REFUTED — "`values(k).dedup()` returns duplicates, because `DISTINCT` ranges over (entity, value)"

The claim was that the allow-list's soundness argument — `configureSingleKeyValues` emits the boundary entity column before the value, so `RETURN DISTINCT` cannot collapse two elements sharing a value — turns into a defect in the opposite ordering, where native `dedup()` runs on the projected strings and does collapse them.

The projection detail is right; the ordering is unreachable. `DedupGlobalStepRecogniser.recognize` declines when `ctx.boundaryOutputType() != BoundaryOutputType.ELEMENT`, with a comment naming this exact case, so a `dedup()` behind any value, map or scalar projection never sets `returnDistinct`.

Verdict: the allow-list's `DISTINCT` argument holds in the direction it is made, and the mirror is already closed elsewhere.

#### C9 REFUTED — "a post-union slice escapes the new drop-on-absent guard"

The claim was that `recognizePostUnion` returns before the `ctx.dropsRowsOnAbsentProperty()` check, so `union(...).values(k).limit(n).count()` would capture a `PostConcatOp.Range` over rows the drop has not removed.

The suffix cannot form. `POST_UNION_RECOGNISERS` holds only count, range and dedup, so no projection can claim a step after a union carrier exists, and a projection inside a child belongs to that child's own plan. `postUnionSuffixTranslatable` applies the same allow-list as a pre-fork look-ahead.

Verdict: the guard's placement on the single-plan branch is complete for the shapes that reach it.

#### C10 REFUTED — "the gate leaks across the sub-walk boundary in one direction or the other"

The claim was that `SubTraversalPredicateAdapter` reads `limit()`, `skip()` and `returnDistinct()` from the parent while swallowing their setters, so either a child's own slice arms the parent's gate or a parent's captured clause declines every step of a child sub-walk.

Neither reaches. The setters are swallowed, so a child's `limit(1)` cannot arm anything on the parent. In the other direction, a sub-walk only runs from a recogniser that already passed the gate, and the three allow-listed projections never call `walkChild`, so no combinator can start a child walk while the parent holds a clause. `UnionForkHostImpl` calls `walk(forked)`, which builds a fresh `WalkerContext` per arm.

Verdict: the two contexts are correctly separated for the three clauses.

#### C11 REFUTED — "an allow-listed projection contributes a pattern conjunct after a slice"

The claim was that `PropertiesStepRecogniser` reaches `ByModulatorPresence.requireProjectedProperty` on the `contributePresenceConjunct` arm, which would write `k IS DEFINED` into the pattern after a `LIMIT` was captured — the precise defect the allow-list excludes `select(...).by(key)` for.

The arm is gated on `!ctx.projectsReturnedPayload()`, and `WalkerContext.projectsReturnedPayload()` returns `true` unconditionally; only `SubTraversalPredicateAdapter` answers `false`. A captured child never holds a parent clause (see C10).

Verdict: on the main line the projection writes RETURN columns and shaping only, which is what the allow-list claims. `grep` for `ByModulatorPresence.` confirms `PropertyMapStepRecogniser` and `ElementMapStepRecogniser` reach it from nowhere.

#### C12 REFUTED — "an allow-listed projection consumes a trailing step, so a `count()` slips past the gate"

The claim was that if `PropertiesStepRecogniser` swallowed a following `count()` as part of its own shape, `g.V().limit(2).values(k).count()` would translate with the count applied on the wrong side of the `LIMIT`.

It only peeks. `capturedSuccessorDrop` and `elementFormIsUnobserved` read `cursor.peek(0)` and `peek(1)` and never `take()` past the projection's own step; `PropertyMapStepRecogniser` and `ElementMapStepRecogniser` take one step each. Measured: `g.V().limit(2).values("name").count()` and `g.V().limit(2).count()` both report zero boundary steps and match native.

Verdict: the gate sees every post-slice step.

#### C13 REFUTED — "`WalkerContext.dropsRowsOnAbsentProperty()` can dereference an unpinned shaping"

The claim was that the new one-line accessor returns `shaping.dropOnAbsent()` with no null guard, on a field a terminator is documented to pin.

The field is initialised to `ResultShaping.NONE` at its declaration (`WalkerContext.java:133`) and the only writer is `setResultShaping`, whose parameter is `@Nonnull`. `SubTraversalPredicateAdapter` overrides the accessor with a constant.

Verdict: no null path.

#### C14 REFUTED — "hop then slice returns a different row set than native"

The claim was the stronger form of BG24: that MATCH's row order diverging from the native traverser order would make the slice select different rows, not merely order them differently.

Measured across two fixtures and eight shapes — `g.V().limit(2)`, `g.V().limit(2).values(name)`, `g.V().limit(2).valueMap(name)`, `g.V().skip(2).values(name)`, `g.V().out(knows).limit(1|2|3)`, `g.V().out(knows).skip(1|2)`, `g.V().out(knows).range(1,2)` — every one agreed with native in ordered form, including the bounds that cut inside a single source's three-way fan-out.

Verdict: the set-level form is not reproduced. BG24 is filed as the order divergence that was measured, with the set risk stated as an argument from the tie-group mechanism rather than as a measurement.

#### C15 REFUTED — "`RETURN DISTINCT` plus an allow-listed map projection collapses rows native keeps"

The claim was that the allow-list's entity-column argument is written for `values(k)` and may not carry to `valueMap` / `elementMap`, where two distinct elements with identical maps would collapse under `DISTINCT` while native `dedup()` on elements keeps both.

`configurePropertyMap` appends the boundary entity column first for the same reason `configureSingleKeyValues` does, and omits it from the emitted map, so the `DISTINCT` ranges over (entity, columns…) in both. `dedupThenValues_stillTranslates` measures the `values` half on a fixture holding two differently-identified vertices sharing a name.

Verdict: the membership holds for all three allow-listed projections.

**Reference-accuracy caveat.** `mcp-steroid` PSI was not used: this repository's `steroid_execute_code` has a recorded history of exceeding the 60 s MCP timeout on kotlinc cold start, and the symbol questions here were small and package-bounded. Every "no other caller" and "only writer" statement above rests on `git grep` over `core/src` plus a read of each returned site; the package-private visibility of `RecognitionContext`, `StepRecogniser` and the recogniser singletons bounds those searches to one package, so the negatives are tight but not PSI-established.

**Scope notes.** BG20 does not interact with this diff: the gate leaves post-hop `has` comparisons untouched and keeps `hop then slice` translatable, so the shape step 15 owns is unchanged here. DR-S14's two-family claim was tested and holds — BG3, BG7 and the `returnDistinct` sibling all route through `capturedCardinalityClause` and `dropsRowsOnAbsentProperty`, while BG8 is a one-line edit to `TRANSPARENT_STEPS` that shares no mechanism with them; a reader who generalised the clause rule over BG8 would predict nothing true about it. No concurrency-triage gap: the diff introduces no shared mutable state and no locks. All measurements ran in the `.claude/worktrees/t9-step10` worktree at `1ee9a270df`, which was left clean; the `translator.**` package runs green there at 683 tests, 0 failures.
