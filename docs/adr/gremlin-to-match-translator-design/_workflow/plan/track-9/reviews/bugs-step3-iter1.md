<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: BG1, sev: should-fix, loc: MatchPatternBuilder.java:391, anchor: "### BG1 ", cert: C1, basis: "NOT path binds the generic V root class the walker site deliberately skips; live, unpinned, and divergent for a link whose collection has no schema class"}
  - {id: BG2, sev: should-fix, loc: ConnectiveStepSupport.java:68, anchor: "### BG2 ", cert: C2, basis: "edge-bearing where(...) emits one row per matching path; the step's new test fixture has exactly one match so it cannot witness the residual over-emission"}
  - {id: BG3, sev: should-fix, loc: GremlinStepWalker.java:648, anchor: "### BG3 ", cert: C3, basis: "where(P) back-reference filters now reach a path item keyed on a Gremlin label that is never a pattern alias; may turn over-emission into under-emission"}
  - {id: BG4, sev: suggestion,  loc: GremlinStepWalker.java:650, anchor: "### BG4 ", cert: C4, basis: "the path item stores the same SQLWhereClause instance the plan inputs hand the planner, against the planner's own defensive-copy policy"}
  - {id: BG5, sev: suggestion,  loc: GremlinStepWalker.java:611, anchor: "### BG5 ", cert: C5, basis: "the copy-not-mutate rationale in the Javadoc names a sharing hazard that does not exist; a maintainer will draw the wrong isolation conclusion"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 6}
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
  - {id: C11, verdict: PARTIAL,   anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED,   anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] The NOT site binds the generic `V` root class that the walker site deliberately skips

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java` (lines 381, 391-393)

**Issue**: the two halves of this fix disagree about the generic vertex class. `GremlinStepWalker.bindPathItemConstraints` drops a class equal to `WalkerContext.VERTEX_ROOT_CLASS` (`"V"`) before binding (`GremlinStepWalker.java:632-636`), and its Javadoc gives the reason: `V` excludes nothing a vertex hop can reach, so binding it buys a class check per candidate and no narrowing. `mergedTargetFilter` binds whatever `aliasClasses.get(alias)` holds, with no such guard. Every translated `not(...)` sub-traversal registers its hop target under `V`, so every one of them now emits a NOT path item carrying `class: V` where before this commit it carried no class at all.

The registration chain is unconditional. `GremlinPatternAssembler.appendFoldedHop:54` and `appendEdgeAsNode:99` call `ctx.addNode(targetAlias, WalkerContext.VERTEX_ROOT_CLASS)` on every hop, including hops inside a sub-walk; `SubTraversalPredicateAdapter.addNode:247` forwards that to the captured builder's `aliasClasses`; `buildNotExpression:365` hands the target alias to `mergedTargetFilter`, which reads that same map. So `not(__.out("knows"))` — no `hasLabel` anywhere — reaches the executor with a class constraint the user never wrote.

For ordinary vertex data the constraint is a no-op, which is why the existing `hasAge_notOutKnows_matchesNative` case still passes. Two consequences survive that:

1. **A divergence on a link whose collection has no schema class.** `MatchEdgeTraverser.targetClassName:480-482` reads the item's class and `matchesClassCached` evaluates it per candidate. When the RID's collection resolves to no schema class, `matchesClass:639-644` loads the record and returns `false` if it cannot be read. The anti-join then finds no match for that candidate and keeps the row, while native `not(out("knows"))` excludes any vertex with an outgoing `knows` edge regardless of what sits at the far end.
2. **The traverser's no-filter fast path is lost.** `executeTraversal:409` returns the raw stream only when the filter, the class and the RID are all null. With `class: V` bound, every translated anti-join candidate goes through the `filter(...)` wrapper instead.

**Evidence**: `SchemaClass.VERTEX_CLASS_NAME` is `"V"` (`SchemaClass.java:34`) and `WalkerContext.VERTEX_ROOT_CLASS` is the same string (`WalkerContext.java:219`), so the walker's skip and the NOT site's bind are reading one value and treating it two ways. The full chain is traced in `#### C1`.

**Refutation considered**: I checked whether the class on a NOT item feeds anything beyond the per-candidate check. `manageNotPatterns:935-950` builds a `MatchStep` chain straight from the expression's items, and `buildNotPatternPlan:1855-1870` takes its origin class from the *positive* pattern's `aliasClasses`, not from the NOT items — so the hash-anti-join build side is unchanged and the plan shape does not move (`#### C12`). I also checked whether `NotStepRecogniser` declines before a `V` registration can reach `buildNotExpression`: `edgeBearingNotCapturesUnsupportedOriginConstraints:139-145` inspects the *origin* alias only, and hop targets pass it untouched. Neither refutes the finding.

I also checked whether any test pins the behaviour in either direction, and none does. The new `NotStepRecogniserTest.edgeBearingChildWithTargetLabel_bindsTargetClassOnNotItem:145` and the new `PredicateTraversalEquivalenceTest.notChildTargetLabel_matchesNative:975` both use `hasLabel`, where `HasStepRecogniser` overwrites `V` with the real class before `buildNotExpression` runs. The plain `not(__.out(...))` case is untested for class content on either side.

**Suggestion**: skip `V` at the NOT site as well, so one rule governs both halves of the fix. `WalkerContext.VERTEX_ROOT_CLASS` is translator-package-private and `MatchPatternBuilder` sits in the executor package, so the cleanest placement is on the translator side: have the sub-walk stop registering the generic class for a hop target that no `hasLabel` re-typed, or strip `V` entries from the captured builder before `NotStepRecogniser.java:90-93` calls `buildNotExpression`. Whichever side takes it, add a `not(__.out("knows"))` case asserting the leaf item's `getClassName(null)` is null, so the two sites cannot drift apart again.

### BG2 [should-fix] An edge-bearing `where(...)` fragment still emits one row per matching path, and the new test's fixture cannot see it

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ConnectiveStepSupport.java` (lines 67-72); test `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PredicateTraversalEquivalenceTest.java` (lines 951-959)

**Issue**: this is a pre-existing defect, not one the diff introduces, but the diff adds an equivalence case for exactly the shape it lives on and picks a fixture that cannot witness it. `commitEdgeBearingChild:68-72` appends the child's hop into the *positive* pattern and merges its alias filters. Nothing re-pins the RETURN column and nothing sets `returnDistinct`, so the translation of `where(<edge-bearing child>)` is a MATCH join that emits one row per matching path, while native Gremlin's `where(...)` is a filter that emits the origin once.

Minimal reproducer on the fixture the step itself adds: `g.V(marko).where(__.out("knows"))`. Marko has two `knows` edges (`ModernGraphFixture.seed` lines 427-428), so the translated plan returns marko twice and native returns it once.

The new `whereFragmentPostHopFilter_pinnedOrigin_matchesNative:951` asserts that `g.V(marko, josh).where(__.out().has("name", "vadas"))` matches native, and it does — because exactly one of marko's three out-neighbours is named vadas. Change the predicate to one that matches two neighbours and the equality breaks. The pre-existing `EdgeTraversalEquivalenceTest.whereOutKnows_matchesNative:807-820` is blind for the same reason: its fixture gives alice and bob one outgoing `knows` edge each.

Before this step the shape over-emitted unconditionally, because the fragment's target predicate was discarded. After it, the shape over-emits only when several targets match. The step's own acceptance evidence therefore reads as "this shape now matches native" when the accurate statement is "this shape now matches native when at most one target matches".

**Evidence**: `WhereTraversalStepRecogniser.recognize:20-36` delegates to `ConnectiveStepSupport.commitPositiveFilterChild:80-91`, which routes an edge-bearing child to `commitEdgeBearingChild`. Neither touches `ctx.returnDistinct`, and `GremlinStepWalker.buildResult:537` passes `ctx.returnDistinct` straight through, so the plan carries no DISTINCT. Full trace in `#### C2`.

**Refutation considered**: I checked whether the MATCH executor deduplicates rows on the RETURN projection without an explicit DISTINCT and found no such step in the plan assembly; the RETURN list is a single column keyed on the origin alias and every matching path produces its own row. I also checked whether the boundary re-pin inside the child leaks out and makes the target the RETURN column, which would change the shape rather than the multiplicity: `SubTraversalPredicateAdapter` buffers the child's re-pin and `commitEdgeBearingChild` never replays it, so the RETURN stays on the origin.

**Suggestion**: this belongs to step 5's residue triage under the track's rule that a translator-caused silent-wrong-multiset defect takes the fix exit or the decline exit. Two exits exist. Set `returnDistinct` when an edge-bearing child commits through `commitPositiveFilterChild`, which restores filter semantics at the cost of deduplicating rows the user may have wanted distinct for other reasons; or decline `WhereTraversalStep` with an edge-bearing child until the semantics are modelled. Independent of the exit chosen, widen `whereFragmentPostHopFilter_pinnedOrigin_matchesNative` to a predicate matching two of marko's out-neighbours, so the case stops asserting more than it tests.

### BG3 [should-fix] `where(P)` back-reference filters now reach a path item keyed on a Gremlin label that is never a pattern alias

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 648-651)

**Issue**: `WherePredicateStepRecogniser` translates `where(P)` and `where(startLabel, P)` into a comparison against `$matched.<gremlinLabel>.@rid` and puts it in `ctx.aliasFilters` under the boundary alias (`WherePredicateStepRecogniser.java:55`, via `GremlinPredicateAdapter.matchedAccess` at lines 482 and 503). The label in that accessor is the user's Gremlin `as(...)` label. The pattern node is named with a minted `$g2m_anon_N` alias, and the label-to-alias map never becomes a pattern alias: `WalkerContext.bindStepLabels:509-525` records it in `userLabelToAlias` and in `MatchPatternBuilder.registerUserLabel`, whose field Javadoc states it is "not consumed by `build()` yet" (`MatchPatternBuilder.java:60-64`).

Before this commit the mismatch was inert whenever the boundary was not the plan root: the filter sat in `aliasFilters` with no consumer and was silently dropped. `bindPathItemConstraints` now pushes it onto the hop's path item, where `MatchEdgeTraverser` evaluates it once per candidate against a `$matched` row whose keys are all `$g2m_anon_N`.

Concrete shape: `g.V().as("a").out().where(P.neq("a"))`. The `where` lands on the hop target, which is not the root, so this is exactly the class of filter the step set out to deliver to the executor. `$matched.a` resolves to nothing. Depending on how the comparison treats a null right-hand operand, the hop either keeps every candidate — the same over-emission as before, so the step's fix does not reach this shape — or rejects every candidate, which is a new under-emission on a default-on switch.

**Evidence**: the accessor is built from the raw label with no alias lookup (`GremlinPredicateAdapter.leftMatchedOperand:475-482` and `translateMatchedLabelPredicate:498-503`). `MatchEdgeTraverser.computeNext:264` keys the output row on `getEndpointAlias()`, which reads `item.getFilter().getAlias()` — the minted alias. Full chain in `#### C3`.

**Refutation considered**: I looked for a test that would already have caught this and found none. The only `where(P)` coverage is `WherePredicateStepRecogniserTest`, which asserts the emitted WHERE text structurally and uses single-node traversals (`g.V().as("a").where(P.eq("a"))` at line 41) where the boundary *is* the root — so the filter travels through `MatchPlanInputs.aliasFilters` to the root scan and never touches a path item. No equivalence suite covers a hop followed by `where(P)`. I also checked whether the planner rewrites `$matched.<label>` into the internal alias and found no such pass on the additive path: `buildPatterns` returns at `MatchExecutionPlanner.java:5621` before any alias assignment runs.

I could not settle which of the two outcomes the evaluation produces without running the shape, so the severity reflects the worse branch rather than a confirmed one.

**Suggestion**: add one equivalence case — `g.V().as("a").out().where(P.neq("a"))` against the modern fixture — and let it decide. If it under-emits, decline `WherePredicateStep` whenever the boundary carries a minted alias that no user label maps onto, until `registerUserLabel` is wired through `build()`. If it over-emits, the shape is simply outside the step's fix and the decline is still the honest exit, because the translator returns a multiset native Gremlin does not.

### BG4 [suggestion] The bound path item stores the same `SQLWhereClause` instance the plan inputs hand the planner

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (line 650)

**Issue**: `bound.setFilter(existingWhere == null ? where : andWhere(existingWhere, where))` installs `where` — the value read out of `finalAliasFilters` — directly onto the path item. The same map object is then passed to `MatchPlanInputs.builder(...).aliasFilters(finalAliasFilters)` at line 535. One `SQLWhereClause` instance therefore has two consumers inside one plan: the path item that the traverser reads, and the alias map that the planner reads. The `andWhere` branch is only marginally better, since it wraps the two operands' `SQLBooleanExpression` nodes rather than copying them.

The planner treats that arrangement as a hazard everywhere else it arises. `buildNotPatternPlan:1861-1870` copies the origin filter with the comment "Copy the WHERE clause to prevent mutable filter corruption"; `buildHashJoinBranchPlan:1913-1921` repeats it; the NOT-IN strip at line 4647-4652 and the residual extraction at line 5049-5055 both deep-copy their sub-blocks, on the stated grounds that a planner AST rewrite on one side would corrupt the other and that a cached plan may re-execute with re-bound parameters.

I did not find a rewrite that reaches an additive-path alias filter today, so this is a suggestion rather than a defect. `rebindFilters` cannot reach it (`#### C8`), and the NOT-IN strip replaces the map entry rather than mutating the clause. The finding is that the new code opts out of a policy the surrounding file states three times, on a path where the two consumers are now permanently coupled.

**Evidence**: the two consumers and their shared instance are traced in `#### C4`. `SQLWhereClause` carries a mutable `baseExpression` with a public `setBaseExpression` and a lazily populated `flattened` cache (`SQLWhereClause.java:38-40, 490, 553`), so it is not an immutable value.

**Refutation considered**: I checked whether `MatchPlanInputs` deep-copies the filter map on the way in, which would decouple the two consumers, and it does not — `buildResult` passes the map by reference. I also checked whether the additive path runs any pass that mutates an alias filter in place and found none, which is why this is not ranked higher.

**Suggestion**: store `where.copy()` on the item, matching `buildNotPatternPlan:1870`. The cost is one AST copy per bound alias at translation time, paid once per compilation.

### BG5 [suggestion] The copy-not-mutate rationale in the Javadoc names a sharing hazard that does not exist

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 611-616, 644)

**Issue**: the Javadoc says the filter "is replaced by a copy rather than mutated, because a captured sub-walk fragment shares its filter objects with the parent pattern it was appended into". The stated mechanism does not hold, in either direction.

What a captured fragment shares with the parent is the `SQLMatchPathItem` itself: `MatchPatternBuilder.appendFrom:304` calls `dstNode.addEdge(srcEdge.item, dstTo)` with the source's item by reference, and `Pattern.copy:252` does the same. Since the item is shared, `item.setFilter(bound)` at line 652 propagates to every pattern holding it whether the filter was copied first or not. The copy changes nothing about that.

Nor do two distinct items ever share one `SQLMatchFilter`. `MatchPatternBuilder.addEdge:147-151` and both factories in `MatchEdgePathItems` build a fresh filter per item, and `SQLMatchPathItem.copy:252` deep-copies the filter, so the copy taken by `buildNotExpression:363` already owns its own filter block.

The copy is harmless and cheap. The comment is the problem: a maintainer reading it will conclude that `bindPathItemConstraints` isolates the parent pattern from a captured fragment, which it does not, and may build on that false isolation guarantee.

**Evidence**: the three sharing sites and their copy semantics are enumerated in `#### C5`.

**Refutation considered**: I searched for any construction path that hands one `SQLMatchFilter` instance to two path items and found none across `MatchPatternBuilder`, `MatchEdgePathItems`, and `SQLMatchPathItem.copy`. If such a path is added later the copy becomes load-bearing, which is an argument for keeping the copy — not for keeping the stated reason.

**Suggestion**: keep `existing.copy()` and rewrite the sentence to say what the copy actually buys: the bound filter is a fresh object, so nothing that later holds a reference to the pre-bind filter observes the binding. State separately that the *item* is shared with the builder's pattern and that `setFilter` is visible through every pattern holding it, since that is the property the surrounding paragraph depends on.

## Evidence base

#### C1 The NOT path binds `V` because every hop target is registered under it, and no guard removes it — CONFIRMED

`GremlinPatternAssembler.appendFoldedHop:53-55` and `appendEdgeAsNode:97-100` register each hop target with `WalkerContext.VERTEX_ROOT_CLASS`; `SubTraversalPredicateAdapter.addNode:247` writes that into the captured builder's `aliasClasses`; `MatchPatternBuilder.mergedTargetFilter:381` reads it and lines 391-393 bind it, while `GremlinStepWalker.bindPathItemConstraints:632-636` strips it. `MatchEdgeTraverser.targetClassName:480-482` then applies it per candidate, and `executeTraversal:409` loses its no-filter fast path. Confirmed as an issue.

#### C2 An edge-bearing `where(...)` fragment translates to a join with no DISTINCT — CONFIRMED

`WhereTraversalStepRecogniser.recognize:20-36` → `ConnectiveStepSupport.commitPositiveFilterChild:80-91` → `commitEdgeBearingChild:68-72`, which appends the hop to the positive pattern and merges alias filters without re-pinning the RETURN column or setting `returnDistinct`; `GremlinStepWalker.buildResult:535-537` forwards `ctx.returnDistinct` unchanged. On `ModernGraphFixture` marko has two `knows` edges (lines 427-428), so `g.V(marko).where(__.out("knows"))` returns two rows against native's one. Confirmed as an issue, pre-existing.

#### C3 `where(P)` emits `$matched.<gremlinLabel>`, which is never a pattern alias — CONFIRMED

`GremlinPredicateAdapter.leftMatchedOperand:475-482` and `translateMatchedLabelPredicate:498-503` build the accessor from the raw Gremlin label; `WalkerContext.bindStepLabels:509-525` records the label only in `userLabelToAlias` and `MatchPatternBuilder.registerUserLabel`, which `build()` does not consume (`MatchPatternBuilder.java:60-64`); `MatchEdgeTraverser.computeNext:258-264` keys the row on `item.getFilter().getAlias()`, the minted `$g2m_anon_N`. Confirmed as an issue; which of the two wrong multisets results is unresolved and stated in the finding.

#### C4 One `SQLWhereClause` instance serves both the path item and the plan inputs — CONFIRMED

`GremlinStepWalker.bindPathItemConstraints:648-650` stores `finalAliasFilters.get(alias)` on the item; `buildResult:535` passes the same map to `MatchPlanInputs`. The planner copies in the four analogous places (`MatchExecutionPlanner.java:1870`, `1921`, `4653`, `5058`). Confirmed as a policy deviation.

#### C5 No two path items share one `SQLMatchFilter`, so the copy does not do what the comment says — CONFIRMED

`MatchPatternBuilder.addEdge:147-151`, `MatchEdgePathItems.edgeMethodItem` and `vertexMethodItem` each build a fresh `SQLMatchFilter`; `SQLMatchPathItem.copy:252` deep-copies the filter; `appendFrom:304` and `Pattern.copy:250-257` share the *item*, which `item.setFilter` mutates regardless of the copy. Confirmed as a comment defect.

#### C6 `assert existing != null` followed by `existing.copy()` dereferences null under `-da` — REFUTED

**Claim.** `bindPathItemConstraints:641-644` asserts the item's filter is non-null and then calls `existing.copy()` unconditionally. With assertions disabled — the production default — a path item carrying no filter block would throw `NullPointerException` inside translation rather than declining to the native pipeline.

**What I checked.** Whether a filterless item can enter a `Pattern` at all. `Pattern.addExpression:68-70` reads `item.filter.getAlias()` for every item it ingests, so an item with a null filter throws there, long before any binding pass. The only other way an item enters a pattern is `MatchPatternBuilder.appendFrom:299-306`, which copies edges out of a pattern that `addExpression` already populated. Both translator-side factories attach a filter unconditionally: `addEdge:161` and both methods in `MatchEdgePathItems`.

**Verdict.** REFUTED. The precondition cannot be violated by any construction path in the tree, so the assert documents an invariant rather than guarding a reachable dereference. Both the `-ea` and `-da` outcomes are loud failures, not silent wrong answers, so the pattern also fails safe if the invariant is ever broken.

#### C7 Union children share path items, so the second child's bind AND-composes the first child's filter — REFUTED

**Claim.** `bindPathItemConstraints` mutates items in place and `Pattern.copy` shares them. If the union fork hands each child a pattern built from shared prefix items, child two would read child one's bound filter as its "existing" WHERE and AND-compose on top, producing an over-restrictive plan and an under-large multiset — the mirror of the defect the step fixes.

**What I checked.** `UnionForkHostImpl.walkFork:74-88` clones each prefix *step* (`step.clone()`) into a fresh `DefaultGraphTraversal` and calls `GremlinStepWalker.production().walk(forked)`. Each child therefore runs a complete walk with its own `WalkerContext`, its own `MatchPatternBuilder`, and its own freshly constructed `SQLMatchPathItem`s. Nothing is shared across arms except the immutable recogniser registry.

**Verdict.** REFUTED. Per-child re-walk means no item instance is reachable from two children's patterns, so the binding runs exactly once per item. The added `UnionTraversalEquivalenceTest.unionChildPostHopFilter_returnsSameMultisetAsNative` case would have caught the shared-item shape had it existed: with sharing, the unfiltered second child would have inherited the first child's `hasId` and returned two rows instead of four.

#### C8 `MatchExecutionPlanner.rebindFilters` overwrites the freshly bound item filters — REFUTED

**Claim.** `rebindFilters:6012-6022` does `item.getFilter().setFilter(aliasFilters.get(alias))`, an unconditional overwrite, and `SQLMatchFilter.setFilter(null)` clears rather than leaves alone. Running after the walker's binding, it would null every edge-alias predicate and reintroduce the step's own defect class.

**What I checked.** Both call sites. The one at line 5677 sits after the additive-path early return at line 5621, which fires whenever `this.pattern != null` — the condition every `MatchPlanInputs` translation meets. The one at line 2064 iterates `matchExpressions`, which the `MatchPlanInputs` compact constructor normalises to an empty list on the additive path, so its loop body never executes there.

**Verdict.** REFUTED. Neither call site reaches a translated pattern's items. The track file recorded this as T34; reading the code confirms it rather than taking it on trust.

#### C9 Reverse edge traversal applies the target's newly bound filter to the source — REFUTED

**Claim.** The planner's topological schedule may traverse an edge backwards when the syntactic target is the cheaper root. If the reverse traverser still read `item.getFilter()`, the target's freshly bound WHERE and class would be evaluated against the *source* vertex — a silent wrong result on exactly the shapes the step's new tests exercise, since a pinned or filtered target is precisely what wins root selection.

**What I checked.** `MatchReverseEdgeTraverser` overrides all three accessors: `targetClassName:52-55` returns `edge.getLeftClass()`, `targetRid:58-61` returns `edge.getLeftRid()`, and `getTargetFilter:64-67` returns `edge.getLeftFilter()`. Those three are set by the planner from the *source* alias's entries in `aliasClasses` / `aliasFilters` / `aliasPinnedRids` (`MatchExecutionPlanner.java:2074-2078`, and the same triple at 1957-1959 and 1971-1973 on the hash-join build side). The item's own filter is unread in reverse mode.

**Verdict.** REFUTED. The reverse traverser reads planner-supplied left constraints, not the path item, so the binding cannot misapply. The target's own constraint still reaches it, through `aliasFilters` at the root scan.

#### C10 `setClassName` rewrites a different item than `getClassName` reads, so the guard can bind twice — REFUTED

**Claim.** `SQLMatchFilter` stores its attributes across a list of `SQLMatchFilterItem`s and both accessors scan that list. If `setClassName:117-128` rewrote a different item than `getClassName:141-160` reads, the `getClassName(null) == null` guard at `GremlinStepWalker.java:645` and `MatchPatternBuilder.java:391` could append a second class item and leave two class names in one filter block.

**What I checked.** Both loops select the first item whose `className` field is non-null, and both fall through to the same append-a-new-item branch when no item carries one. `getClassName` returns a value on every branch inside `if (item.className != null)`, so a non-null `className` field always yields a non-null read. The representation matches `fromAliasAndClass:65-78`, which puts the alias on one item and the class on a second.

**Verdict.** REFUTED. The reader and the writer agree on which item holds the class, so the guard makes `setClassName` reach only its append branch at both call sites, exactly once per filter.

#### C11 The three unit tests pin the three stated preservation rules, with two gaps — PARTIAL

The step's contract names three merge-preservation rules the translator cannot reach end-to-end, and asks whether the package-private unit tests pin them. Rule by rule:

- **AND-compose with the WHERE the item already carries.** Pinned by `bindPathItemConstraints_andComposesExistingWhere_andKeepsExistingClass:1115`. The test builds the item's own WHERE through `addEdge`'s `edgeFilter` parameter, which `MatchPatternBuilder.addEdge:149-151` lands on the target-vertex filter, then binds a second clause for the same alias and asserts both field names survive. The assertion is a substring check over the rendered generic statement, so an accidental OR composition, or a swapped operand order, would also pass. Adequate as a regression net for the drop; weak as a check on the composition operator.
- **Leave an item whose alias is in neither map untouched.** Pinned by `bindPathItemConstraints_bindsTarget_andLeavesUnlistedEdgeAliasAlone:1089`, which puts an edge WHERE on the `e` alias, supplies maps naming only `t`, and asserts the edge item keeps `weight` and gains no class. This is the rule that protects the `outE(L).has(p, v).inV()` predicate, and the end-to-end `edgePathItemFilter_survivesTargetConstraintBinding` case covers the same ground through the executor.
- **Do not overwrite a class the item already carries.** Pinned by the same test at line 1115, which pre-sets `Employee` on the item and asserts `Person` from the map does not replace it.

Two gaps. First, the `V`-skip rule is pinned at the walker site (`bindPathItemConstraints_skipsGenericVertexRootClass:1139`) and unpinned at the NOT site, where the opposite behaviour is live — see BG1. Second, the copy-not-mutate rule stated in the Javadoc is unpinned, and per BG5 it protects nothing, so the absent test is the smaller half of that problem.

Overall the unit tests do the job the contract asks of them for the three named rules. The contract's coverage is narrower than the Javadoc's list of properties.

#### C12 Binding `class: V` on a NOT item changes the anti-join's plan shape — REFUTED

**Claim.** A class on a NOT path item might feed the hash-anti-join build side, so the newly bound `V` would change which scan the planner picks and, through it, which rows the anti-join materialises.

**What I checked.** `manageNotPatterns:918-965` builds a `MatchStep` per NOT item from a synthetic `PatternEdge` whose endpoints carry only aliases; the item's filter travels with the step but feeds no plan choice. `buildNotPatternPlan:1855-1870` reads `aliasClasses.get(originAlias)` — the *positive* plan's map — for the origin scan, and chains the already-built `MatchStep`s unchanged. `canUseHashJoin` likewise takes the positive maps.

**Verdict.** REFUTED. The NOT item's class is consumed only by `MatchEdgeTraverser.targetClassName` at candidate-filter time, so the plan shape is identical with and without the `V` binding. BG1's impact is confined to the per-candidate check and its two consequences, which is why it is a should-fix rather than a blocker.
