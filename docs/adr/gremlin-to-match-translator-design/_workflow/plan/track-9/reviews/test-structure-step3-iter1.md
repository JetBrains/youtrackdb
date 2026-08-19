<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: TS1, sev: should-fix, loc: EdgeTraversalEquivalenceTest.java:920, anchor: "### TS1 ", cert: n/a, basis: "edgePathItemFilter_survivesTargetConstraintBinding names a merge rule its shape never reaches; the binding pass short-circuits on both path items"}
  - {id: TS2, sev: should-fix, loc: EdgeTraversalEquivalenceTest.java:884, anchor: "### TS2 ", cert: n/a, basis: "four cases name a non-root target but assert nothing about root selection; a heuristic change turns them green-and-silent, margin is one"}
  - {id: TS3, sev: should-fix, loc: EdgeTraversalEquivalenceTest.java:1064, anchor: "### TS3 ", cert: n/a, basis: "four same-named countBoundarySteps helpers split across two step types; three count a MultiPlanMatchStep as zero, so a DECLINED case there passes inverted"}
  - {id: TS4, sev: suggestion,  loc: PredicateTraversalEquivalenceTest.java:893, anchor: "### TS4 ", cert: n/a, basis: "section header lists a union-child case that lives in another class; fixture javadoc says two consumers where there are three"}
  - {id: TS5, sev: suggestion,  loc: GremlinStepWalkerTest.java:1077, anchor: "### TS5 ", cert: n/a, basis: "header says three preservation cases and ships three methods, but the rule-to-method mapping is not 1:1 and no exit condition is stated"}
  - {id: TS6, sev: suggestion,  loc: SQLMatchFilter.java:117, anchor: "### TS6 ", cert: n/a, basis: "new public setClassName documents a rewrite branch no caller reaches and no test covers; no SQLMatchFilterTest exists"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] `edgePathItemFilter_survivesTargetConstraintBinding` cannot reach the rule it is named after

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeTraversalEquivalenceTest.java`, method `edgePathItemFilter_survivesTargetConstraintBinding` (line 920)

**Issue**: The case runs `g.V().outE("knows").has("weight", 0.5d).inV()` and its javadoc calls it "the regression case for binding target constraints by merge rather than by overwrite". `bindPathItemConstraints` never touches either path item of that shape, so no merge happens and no overwrite could.

The trace:

- `GremlinPatternAssembler.appendEdgeAsNode` (line 88) builds two path items, `origin → e` and `e → t`, and registers exactly one class: `ctx.addNode(targetAlias, WalkerContext.VERTEX_ROOT_CLASS)` at line 99. The edge alias never enters `aliasClasses`.
- The `weight` predicate lands in `WalkerContext.edgeFilters` via `putEdgeFilter` (`WalkerContext.java:57`, `:452`). `bindPathItemConstraints` reads `aliasFilters` and `aliasClasses` and nothing else (`GremlinStepWalker.java:630-631`).
- Edge item: `edge.in.alias` is `e`; both lookups return null; the guard at line 637 hits `continue`.
- Closing item: `edge.in.alias` is `t`; nothing follows `inV()` so `aliasFilters.get(t)` is null, and `aliasClasses.get(t)` is `"V"`, which the `VERTEX_ROOT_CLASS` guard nulls out. Same `continue`.

The pass is a no-op on this traversal. This is also the second half of a contradiction the diff ships with itself: the `bindPathItemConstraints` javadoc says the three preservation rules are ones "the translator cannot currently build a traversal that reaches", and the new `GremlinStepWalkerTest` section header (line 1078) repeats it as "the preservation rules no traversal can reach". One of the two statements has to be wrong, and the trace says it is the equivalence case.

What the case does verify — an edge predicate on the edge-as-node form still returns the native multiset — is already verified by `nonAdjacentOutEdgeFilter_returnsSameMultisetAsNative` (line 192), which runs the same shape with `since` instead of `weight` and is described as "the headline correctness case". Any mutation of the new pass that broke an edge item's filter would fail line 192 as well.

**Suggestion**: Two options, and the second is worth more.

Rename and re-document as what it is — a smoke test that the new pass does not misfire on an edge-as-node shape carrying no target constraint. That keeps a useful guard and stops the file claiming end-to-end coverage of a rule the production code says is unreachable.

Or extend the shape so a target constraint exists and the merge branch runs:

```java
() -> graph.traversal().V(markoId).outE("knows").has("weight", 0.5d).inV().has("name", "vadas")
```

The trailing `has` puts a `WHERE` on `t`, so the closing item goes through the AND-compose path while the edge item stays untouched — which is the rule the name promises. Confirm it by locally replacing the merge with `bound.setFilter(where)` and checking the case goes red; if it stays green the shape still is not reaching the rule.

### TS2 [should-fix] Four cases name a non-root target and assert nothing that pins it

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeTraversalEquivalenceTest.java`, methods `postHopHasId_onNonRootTarget_returnsSameMultisetAsNative` (line 884) and `postHopHasLabel_onNonRootTarget_returnsSameMultisetAsNative` (line 903); `PredicateTraversalEquivalenceTest.java`, methods `postHopHas_pinnedOrigin_matchesNative` (line 909) and `whereFragmentPostHopFilter_pinnedOrigin_matchesNative` (line 951)

**Issue**: Every assertion in these four is on the result multiset and the boundary-step count. Neither observes which alias the planner rooted at. If root selection ever prefers the hop's target, the target's filter reaches the executor through `MatchPlanInputs.aliasFilters` instead of through the path item, the multiset stays correct, the boundary count stays 1, and all four go green while witnessing nothing about the code this step added. That is the same shape as the union case this step already had to relocate.

The margin is documented and it is one. `whereFragmentPostHopFilter_pinnedOrigin_matchesNative`'s javadoc records the arithmetic: a pinned origin scores at its RID count (2) and a filtered target at half the class count (3, over six vertices), and it notes explicitly that "a three-RID origin ties and passes only by tie-break". `ModernGraphFixture`'s javadoc carries the same arithmetic in prose — "two pinned RIDs beat six vertices' worth of target estimate". So a shared fixture's vertex count is load-bearing for one consumer's discrimination, recorded only in a comment, with three consumers and no mechanical guard. A scoring change or a fixture edit degrades the witness silently in both places.

**Suggestion**: `PredicateTraversalEquivalenceTest` already has the instrument. `boundaryPlanText` (line 1076) returns `plan.prettyPrint(0, 2)`, and lines 97-100 and 119-123 already use it to pin plan shape alongside a multiset comparison:

```java
assertThat(boundaryPlanText(...))
    .as("polymorphic hasLabel re-types the boundary node — the plan fetches from Person")
    .contains("FETCH FROM CLASS Person")
    .doesNotContain("FETCH FROM CLASS V ");
```

Add the analogous root pin to each of the four, asserting the plan roots at the pinned or filtered origin rather than the hop target — read the exact literal off one `prettyPrint` run rather than guessing it. For the two cases in `EdgeTraversalEquivalenceTest`, either port `boundaryPlanText` across or move those two cases into the class that already carries it. Whichever pin you choose, phrase the `.as(…)` so the failure names the consequence: the case has stopped witnessing the path-item binding.

### TS3 [should-fix] Four `countBoundarySteps` helpers, same name and signature, two different step types

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeTraversalEquivalenceTest.java` (line 1064), `PredicateTraversalEquivalenceTest.java` (line 1142), `ProjectionEquivalenceTest.java` (line 566), `UnionTraversalEquivalenceTest.java` (line 820)

**Issue**: Three of the four count `YTDBMatchPlanStep`; `UnionTraversalEquivalenceTest` counts the common supertype `AbstractMatchPlanStep`. That divergence is what made a real union witness read as a decline in `PredicateTraversalEquivalenceTest`, and this step routed around it by moving the case rather than closing it. The trap is still armed for the next author.

A shape spliced as `MultiPlanMatchStep` counts as zero in the three narrow classes. Added there with `Recognition.RECOGNIZED` it fails loudly (0 against an expected 1), which is fine. Added with `Recognition.DECLINED` it passes while asserting the exact opposite of what happens, and the multiset comparison still runs on both arms, so nothing else in the helper catches it. Four helpers with one name and one signature also give a reader no signal that the difference exists — you have to open all four to find it.

**Suggestion**: One line in each of the three narrow classes' `DECLINED` branch closes the hole without touching their `Recognition` semantics:

```java
assertThat(onAdmin.getSteps())
    .as(scenario + " (translator on) must decline — no boundary step of any kind")
    .noneMatch(s -> s instanceof AbstractMatchPlanStep<?, ?>);
```

Better, hoist a single counter over `AbstractMatchPlanStep` into a package-private helper beside `ModernGraphFixture` — this diff already establishes that pattern for shared fixtures — and let each class keep only its own `Recognition` enum and its own extra pins. Either way, a case that splices a multi-plan boundary should never be able to satisfy a `DECLINED` expectation.

### TS4 [suggestion] Two comments in the diff contradict the code shipped beside them

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PredicateTraversalEquivalenceTest.java`, section header (line 893); `ModernGraphFixture.java`, class javadoc (line 11)

**Issue**: The section header says its cases pin the predicate reaching the executor "directly, inside a union child, inside a `where(...)` fragment, and on a `not(...)` sub-traversal". The section holds four cases and none of them is a union child — that one is `UnionTraversalEquivalenceTest#unionChildPostHopFilter_returnsSameMultisetAsNative` (line 122), moved there by this step. Someone auditing coverage of the union arm reads the header, looks in this file, and finds nothing.

`ModernGraphFixture`'s javadoc says the graph is "shared by the two equivalence fixtures that need it". Three classes call `seed` from eight sites: `EdgeTraversalEquivalenceTest` (885, 904, 921), `PredicateTraversalEquivalenceTest` (910, 926, 952, 976), `UnionTraversalEquivalenceTest` (123).

CLAUDE.md is direct about this: "Stale or contradictory comments are worse than no comments."

**Suggestion**: Drop "inside a union child" from the section header and add a pointer to `UnionTraversalEquivalenceTest#unionChildPostHopFilter_returnsSameMultisetAsNative`, mirroring the pointer that case already carries back to this class. In the fixture javadoc, write "the three equivalence suites" or drop the count — a count in a comment on a shared helper goes stale on the next consumer.

### TS5 [suggestion] The unreachable-contract tests read well; the rule-to-method mapping does not line up

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, section at lines 1077-1147

**Issue**: The header does the important thing — it states the rules are unreachable rather than untested, and says why they still need pinning ("these cover what the binding must NOT do, which a regression would otherwise break silently"). A reader will not mistake this for a coverage gap. Two smaller gaps remain.

The header says "the three preservation cases" and the section ships three methods, so a reader assumes one method per rule. The mapping is not that. `bindPathItemConstraints_bindsTarget_andLeavesUnlistedEdgeAliasAlone` (line 1089) covers the positive path plus the leave-unlisted-aliases-alone rule. `bindPathItemConstraints_andComposesExistingWhere_andKeepsExistingClass` (line 1115) covers two rules in one method — AND-compose and do-not-overwrite-class — which also means one failure message has to serve two distinct regressions. `bindPathItemConstraints_skipsGenericVertexRootClass` (line 1139) covers a rule the production javadoc states outside the three-bullet list.

Nothing states the exit condition. These tests exist only because no traversal reaches the rules; when one does, the right move is to promote the case to an equivalence test, and no reader is told that.

**Suggestion**: Split the AND-compose method into two, so each rule fails with its own message. Add one sentence to the section header: when a traversal shape reaches one of these rules, promote it to an equivalence case and delete the unit test — these exist only because no shape does today.

### TS6 [suggestion] New public `setClassName` documents a branch nothing reaches and nothing tests

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchFilter.java`, method `setClassName` (line 117)

**Issue**: No `SQLMatchFilterTest` exists anywhere in the repository, so this new public method is covered only through its two callers. Its javadoc states a two-branch contract: "the first item that already carries a class name is rewritten, otherwise a new item carrying only the class is appended." The rewrite branch has no caller and no test.

Both call sites guard on the class already being absent — `GremlinStepWalker.java:645` and `MatchPatternBuilder.java:391`, each `className != null && …getClassName(null) == null`. `getClassName` returns non-null whenever any item carries a non-null `className`, so the guard is exactly the negation of the rewrite branch's condition. Under both callers the loop cannot fire.

This is documentation quality rather than a defect: a reader takes the javadoc as a live two-branch contract, and a front-end author writing against this public parser-AST method has no test showing either arm.

**Suggestion**: Add a small `SQLMatchFilterTest` with two cases — append onto an alias-only filter, rewrite onto a class-carrying one — so the documented contract is real for the next caller, which may not carry the guard. If you would rather not add the class, narrow the javadoc to the branch that runs and say the rewrite arm mirrors `setFilter` for symmetry and currently has no caller.

## Evidence base
