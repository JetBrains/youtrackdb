<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 2, suggestion: 6}
index:
  - {id: TS49, sev: should-fix, loc: UnionTraversalEquivalenceTest.java:772, anchor: "### TS49 ", cert: n/a, basis: "the DECLINED branch of assertEquivalent has a non-vacuity guard in one of five same-named copies, and that copy has zero DECLINED call sites; 73 DECLINED assertions across the other four compare native against native with no non-emptiness pin, 22 of them with no hand-written substitute either"}
  - {id: TS50, sev: should-fix, loc: EdgeTraversalEquivalenceTest.java:1153, anchor: "### TS50 ", cert: n/a, basis: "TS8/TS48 unclosed and grown: nine hand-rolled copies of the translator toggle under five helper names, eleven of countBoundarySteps, five of assertEquivalent+Recognition; ModernGraphFixture shows the extraction seam already exists in this package and TS49 is the divergence it caused"}
  - {id: TS51, sev: suggestion, loc: GremlinStepWalker.java:163, anchor: "### TS51 ", cert: n/a, basis: "TRANSPARENT_STEPS is private and mirrored by hand in 16 test classes, one of which diverges to Set.of(); the same range made POST_UNION_RECOGNISERS package-private and added a reflective test to pin it"}
  - {id: TS52, sev: suggestion, loc: OrderRangeStepRecogniserTest.java:34, anchor: "### TS52 ", cert: n/a, basis: "on/off equivalence coverage now sits in nine classes, two of them recogniser-unit classes that grew an end-to-end half; no index says which class owns which shape family"}
  - {id: TS53, sev: suggestion, loc: RepeatDeclineStrategyTest.java:683, anchor: "### TS53 ", cert: n/a, basis: "two opposite stances on kill-switch restore coexist in one package with only one documented, and GremlinPlanCacheTest's @Before has no @After counterpart"}
  - {id: TS54, sev: suggestion, loc: PredicateTraversalEquivalenceTest.java:47, anchor: "### TS54 ", cert: n/a, basis: "the literal $g2m_v0 is duplicated in 20 classes under two constant names, ORIGIN_ALIAS and BOUNDARY_ALIAS, with nothing saying they are the same alias"}
  - {id: TS55, sev: suggestion, loc: ProjectionEquivalenceTest.java:1, anchor: "### TS55 ", cert: n/a, basis: "1681 lines covering five behaviour areas that arrived in five separate steps; a clean split boundary exists at the properties(key) element-form block"}
  - {id: TS56, sev: suggestion, loc: RangeTypeGuardEquivalenceTest.java:684, anchor: "### TS56 ", cert: n/a, basis: "addTypedVertex depends on a mutable typesHub field a different seeder sets, with no guard; and tagsOf's javadoc still describes an edge-target branch the method does not have (TS48's second half)"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS49 [should-fix] The DECLINED branch's non-vacuity guard lives in the one copy that never uses it

**Files:**
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, `assertEquivalent` (line 772) — 22 `Recognition.DECLINED` call sites
- `.../ProjectionEquivalenceTest.java`, `assertEquivalentInternal` (line 1472) — 36 DECLINED call sites
- `.../PredicateTraversalEquivalenceTest.java`, `assertEquivalent` (line 1343) — 9 DECLINED call sites
- `.../EdgeTraversalEquivalenceTest.java`, `assertEquivalent` (line 1059) — 6 DECLINED call sites
- `.../NotStepRecogniserTest.java`, `assertEquivalent` (line 512) — 0 DECLINED call sites

**Issue.** Five classes in one package each define a private
`assertEquivalent(String, Recognition, Supplier)`. On the RECOGNIZED branch all
five assert the on-arm result is non-empty, and each explains in a comment why:
a decline makes both arms the native pipeline, so the multiset equality holds
over two empty lists and a seed regression would go green. On the DECLINED
branch the same reasoning applies with more force — both arms *are* native by
construction, so the equality cannot fail at all and the boundary count is the
only live assertion. Exactly one of the five copies guards it:

```java
// NotStepRecogniserTest.assertEquivalent, the DECLINED branch
assertThat(offIds)
    .as(scenario + ": a declined shape must still return a non-empty native result, else "
        + "the multiset equality below is vacuous")
    .isNotEmpty();
```

`NotStepRecogniserTest` has no DECLINED call site. The four copies that carry
all 73 of them have no such guard.

The per-call-site substitute is applied unevenly. `EdgeTraversalEquivalenceTest`
routes three of its six DECLINED cases through `assertNativeFanOut`, which pins
exact native row counts, and `PredicateTraversalEquivalenceTest` pins native
rows by hand at most of its nine (`whereFragmentPostHopFilter_…` says why:
"for a declined expectation `assertEquivalent` compares two runs that both
execute natively, so its multiset equality holds however the fixture drifts").
`UnionTraversalEquivalenceTest` has no `hasSize` or `containsExactly` anywhere
near any of its 22 — `unionThenLimit_declines`, `unionThenOrder_declines`,
`hopAfterUnion_declines`, `startPositionUnion_declines` and the rest seed a
three-vertex chain and assert only that the boundary count is zero. Its
`assertSameMultisetOnAndOff` helper, added this range and used at three
DECLINED sites, drops the boundary assertion too, so those three assert nothing
that can fail on an empty graph. `ProjectionEquivalenceTest`'s
`keylessValueMapAndElementMap_decline` is the same shape: four DECLINED calls
on a one-vertex fixture with no row pin.

This is the twelve-times-recorded vacuous-acceptance shape reproduced at
package scale, and it is a cross-step artefact — the guard was written into
`NotStepRecogniserTest` at step 10 and never propagated backwards to the four
copies that predate it.

**Suggestion.** Add the DECLINED-branch non-emptiness assertion on `offIds` to
the four copies, then delete the hand-written native pins it makes redundant.
If a DECLINED case is legitimately empty on both arms, give it the same opt-in
escape `ProjectionEquivalenceTest` already has for RECOGNIZED
(`Cardinality.MAY_BE_EMPTY`) rather than leaving the guard out of the helper.
`literalWithNoComparabilityBlock_declinesToNative` in
`RangeTypeGuardEquivalenceTest` is the model for the alternative when the
native answer really is empty: it runs a translating control on the same
fixture first, so the decline is attributable.

### TS50 [should-fix] The shared harness is still nine copies; `ModernGraphFixture` shows the seam was available

**Files:** all nine graph-backed classes under
`core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/`,
plus `.../translator/GremlinToMatchSmokeTest.java` and
`embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedTranslatorKillSwitchWitnessTest.java`.

**Issue.** Step 1's `TS8` recorded "fifth hand-rolled copy of the same
translator test harness in this package" as a suggestion; step 10's `TS48`
recorded the seventh. At the final tree it is:

| Helper | Copies | Divergence |
|---|---|---|
| `setTranslatorEnabled` / `setTranslatorFlag` | 9 | two names; four resolve the config through `graphSession()`, five through `session` |
| `countBoundarySteps` | 11 (10 in `core`, 1 in `embedded`) | two signatures (`List<?>` and `Traversal.Admin`), one returns `long` |
| `translatorEnabled()` | 5 | four classes inline the `getValueAsBoolean` read instead |
| a scoped toggle wrapper | 8 | five names: `withTranslator`, `withTranslatorOn`, `withTranslatorOff`, `withTranslatorRestored`, `withTranslatorDisabled` |
| `assertEquivalent` + `enum Recognition` | 5 | the divergence is `TS49` |
| `boundaryPlanText` | 3 | identical bodies |
| `planRootAlias` | 3 | two incompatible signatures — two take a `Supplier`, one takes plan text |
| `sortedIds` | 5 | identical bodies |
| `graphSession()` | 4 | identical bodies |

This range answers the question `TS8` left open, and the answer is that the
graph fixture converged while the harness did not. `ModernGraphFixture` is a new
package-private static seeder used by five classes, which settles that a shared
helper class in this package is acceptable and that a static entry point taking
`(YTDBGraph, DatabaseSessionEmbedded)` is enough to serve the whole suite. The
same shape would carry the toggle, the boundary counter and the plan-text
readers. Two of the copies carry javadoc explaining why the duplication is a
problem — `UnionTraversalEquivalenceTest.withTranslatorRestored` says "two
verbatim copies of a `finally` block is where the third copy forgets it" — and
`TS49` is that prediction coming true one level up, in the assertion driver
rather than the `finally`.

The two config handles deserve a note because a test author cannot see they
agree. `session.getConfiguration()` and
`graphSession().getConfiguration()` both resolve to
`storage.getContextConfiguration()` for the one database `DbTestBase` creates
per test method, so the two are the same object. Nothing in the tests says so,
and `RangeTypeGuardEquivalenceTest.assertAgreesWithNative`'s javadoc treats the
possibility that they differ as live ("a write that landed on a handle the
traversal does not read would leave both arms translated"). A single shared
setter removes the question.

**Suggestion.** Extract a package-private `TranslatorTestHarness` beside
`ModernGraphFixture` holding: `setTranslatorEnabled` / `translatorEnabled` on
one handle, `withTranslator(boolean, Runnable)`, `countBoundarySteps(List<?>)`,
`boundaryPlanText`, `planRootAlias(String)`, `sortedIds`, and one
`assertEquivalent` with a shared `Recognition` and the `Cardinality` opt-out.
Nine classes then delete their tails. Migrating `assertEquivalent` first closes
`TS49` as a side effect, which is the reason to do it in that order.

### TS51 [suggestion] `TRANSPARENT_STEPS` is mirrored by hand 16 times, and one mirror diverges

**Files:** `core/src/main/java/.../strategy/GremlinStepWalker.java:163`;
16 test classes under `.../translator/strategy/`, including
`WherePredicateStepRecogniserTest.java:39`,
`WhereTraversalStepRecogniserTest.java:40` and
`GremlinProjectionRecogniserTest.java:22`.

**Issue.** `GremlinStepWalker.TRANSPARENT_STEPS` is `private static final`, and
16 test classes hold their own `private static final Set<Class<?>> TRANSPARENT`
that they pass to `StepStreamCursor`. Step 10 narrowed the production set from
three classes to one, and the two mirrors whose javadoc now states the invariant
were edited with it:

> Mirrors `GremlinStepWalker`'s production transparency set. […] Keeping the two
> sets equal is what makes a decline observed here mean the same thing as a
> decline in production.

Fourteen mirrors carry no such statement, and one of them,
`GremlinProjectionRecogniserTest`, is `Set.of()` — an empty transparency set.
That is harmless in that class today, because its traversals are un-strategised
and carry no barrier, but it makes the invariant the two annotated copies assert
false as a package-wide claim, and a reader who trusts the annotation will
mis-read the fourteen silent copies as tracking production.

The inconsistency is sharper because the same range solved this problem for the
walker's other constant. `POST_UNION_RECOGNISERS` was made package-private
specifically so `everyPostUnionRecogniserStatesItsOwnPositionalAnswer` and
`recogniserOutsideThePostUnionAllowList_inheritsANonPositionalAnswer` could read
it, and the walker's javadoc says so: "The field is package-private for exactly
that test." `TRANSPARENT_STEPS` got the prose invariant and not the mechanism.

**Suggestion.** Make `TRANSPARENT_STEPS` package-private with the same one-line
javadoc rationale `POST_UNION_RECOGNISERS` carries, and have every mirror read
it instead of restating it — `private static final Set<Class<?>> TRANSPARENT =
GremlinStepWalker.TRANSPARENT_STEPS;`. That deletes 16 literals, closes `TS44`,
and makes `GremlinProjectionRecogniserTest`'s divergence either disappear or
become a deliberate, visible override.

### TS52 [suggestion] On/off equivalence coverage is spread across nine classes with no index

**Files:** `EdgeTraversalEquivalenceTest`, `PredicateTraversalEquivalenceTest`,
`ProjectionEquivalenceTest`, `UnionTraversalEquivalenceTest`,
`RangeTypeGuardEquivalenceTest`, `NotStepRecogniserTest` (line 41),
`OrderRangeStepRecogniserTest` (line 34), `GremlinStepWalkerTest`
(`assertDeclinesAndMatchesNative`, line 1508), `RepeatDeclineStrategyTest`
(`assertDeclinedAndEquals`, line 627).

**Issue.** Nine classes now run a traversal with the translator on and again
off and compare. Five are named `*EquivalenceTest`. Four are not, and two of
those are recogniser-unit classes that grew a second, differently-shaped half
during this track. Both say so in their own javadoc:

- `OrderRangeStepRecogniserTest` — "The last group runs end to end instead —
  measured translator-on / translator-off equivalence for the rule that a
  captured single-plan slice ends the walk."
- `NotStepRecogniserTest` — "The cases at the end of this class are the
  exception […] they are the tree's only end-to-end coverage of
  `not(has(...))`."

Each placement has a defensible local reason: the slice gate lives in the
walker's dispatch loop rather than in a recogniser, and the negated-range
boundary cases belong beside the recogniser branch that used to own the
decline. The cross-step effect is that a reader asking "where is the on/off
coverage for shape X" has nine files to grep, two of whose names promise
recogniser unit tests, and the sentence that would tell them lives inside the
class it points at rather than anywhere a search would start.

`NotStepRecogniserTest` is 603 lines, of which roughly 200 are the equivalence
half plus its own private `Recognition` / `assertEquivalent` / `sortedIds` /
`countBoundarySteps` / `graphSession` tail — a full second harness inside a
unit-test class. `OrderRangeStepRecogniserTest` is 1039 lines with the same
split and four assertion drivers of its own
(`assertClauseThenStepDeclines`, `assertOrderedSliceDeclines`,
`assertTranslatesAndMatchesNative`, `assertTranslatesAndMatchesNativeValues`).

**Suggestion.** Either move the two end-to-end halves into the
`*EquivalenceTest` family — a `CardinalityClauseEquivalenceTest` for the slice
and dedup gates, and the negated-range cases into
`RangeTypeGuardEquivalenceTest`, whose class javadoc already owns the guard's
mechanism and scoping — or add one paragraph to `EdgeTraversalEquivalenceTest`
(the oldest and the one a reader reaches first) listing which class owns which
shape family. The move is the better fix, because it also removes two of
`TS50`'s harness copies.

### TS53 [suggestion] Two opposite stances on restoring the kill-switch, one documented

**Files:** `RepeatDeclineStrategyTest.java:683` (`setTranslatorEnabled`) and
`:694` (`setPolymorphicByDefault`); `GremlinPlanCacheTest.java:31`
(`@Before enableTranslator`).

**Issue.** Every graph-backed class in this package that writes the kill-switch
does so inside a `try/finally` that restores it, except two.
`RepeatDeclineStrategyTest` states the reasoning for not restoring:

> No case restores it: the configuration belongs to the storage `DbTestBase`
> creates for this test method and drops in its `@After`, so the write cannot
> reach `GlobalConfiguration` or any later test in the same fork.

That is correct — `DbTestBase.beforeTest` names the database after the test
method, `afterTest` drops it, and `getConfiguration()` returns
`storage.getContextConfiguration()`. So both stances are safe and the
`finally` blocks in the other classes are belt and braces. But a reader
meeting the two stances side by side cannot tell which one is load-bearing
without reading `DbTestBase`, and the restoring majority reads as if the
non-restoring pair had forgotten something.

`GremlinPlanCacheTest` is the setup/teardown asymmetry in the same family: it
is the only class in the package with a `@Before`, it writes the kill-switch and
invalidates the process-shared-per-database plan cache there, and it has no
`@After`. Nothing breaks, for the same per-method-storage reason, but the
asymmetry is unexplained where `RepeatDeclineStrategyTest`'s is explained.

**Suggestion.** Pick one stance and state it once. The cheapest version: put
the per-method-storage argument into the shared harness `TS50` proposes, as the
javadoc on its `setTranslatorEnabled`, and drop the redundant `finally`
blocks — or keep them and add the two-sentence reason to
`GremlinPlanCacheTest.enableTranslator` so a reader of either class finds the
same answer.

### TS54 [suggestion] One alias literal, two constant names, 20 copies

**Files:** `"$g2m_v0"` appears as `BOUNDARY_ALIAS` in 17 classes and as
`ORIGIN_ALIAS` in `EdgeTraversalEquivalenceTest.java:52`,
`PredicateTraversalEquivalenceTest.java:47` and
`RangeTypeGuardEquivalenceTest.java:61`. `"$g2m_anon_0"` appears as
`FIRST_ANON_ALIAS` in 7.

**Issue.** The root scan's alias is spelled out 20 times under two names in the
same package, with nothing saying the two names denote the same thing. The
split has a rationale — the equivalence classes reason about the alias as the
*origin* of a hop and the recogniser classes as the *boundary* the row projects
onto — but both javadocs describe the same walker output
("The alias the walker mints for the root `V()` scan" versus "The alias the
walker mints for the root `V()` scan — the origin of every hop below it"), and
a reader comparing an equivalence case against a recogniser case has to check
the values to know they match.

**Suggestion.** Move both literals into the harness `TS50` proposes under one
name each, and keep the two readings as javadoc on the single constant rather
than as two constants. If the two readings are worth separate names, make one
an alias of the other so a rename cannot split them.

### TS55 [suggestion] `ProjectionEquivalenceTest` is 1681 lines over five behaviour areas

**File:** `core/src/test/java/.../strategy/ProjectionEquivalenceTest.java`
(+1103 lines this range).

**Issue.** The class now covers five areas that arrived in five separate steps:

1. the original projection and aggregate terminators (`values`, `valueMap`,
   `elementMap`, `select`, `count`, `mean`, `groupCount`, order, range, dedup);
2. the `properties(key)` element form and its two escapes, plus the
   meta-property placements;
3. the captured-child termination test (`subWalkValues*`, six methods);
4. `by(key)` as a filtering modulator and the three `ProductiveByStrategy`
   states;
5. `valueMap` token handling and the grouping-terminator gate.

Areas 2 and 3 alone are ~540 lines and share two fixtures
(`seedNameAgeNickGraph`, `seedMetaPropertyGraph`) plus two helpers
(`assertRewrittenToElementForm`, `firstPropertiesStepReturnType`) that no other
area uses. The section-comment banners are good and the class is navigable, but
five areas in one file means one `assertEquivalentInternal` has to serve all of
them, which is where the `Cardinality` opt-in and the ordered/unordered flag
came from.

**Suggestion.** Split at the banner between area 1 and area 2. Areas 2 and 3
become `PropertyElementFormEquivalenceTest`, taking
`seedNameAgeNickGraph`, `seedMetaPropertyGraph`,
`assertRewrittenToElementForm` and `firstPropertiesStepReturnType` with them;
areas 4 and 5 stay with area 1, which is where the `by(...)` modulator and the
map projections belong. Both halves then share the harness `TS50` proposes
rather than each carrying a copy.

### TS56 [suggestion] A fixture-ordering coupling with no guard, and a helper javadoc describing a branch that is not there

**File:** `core/src/test/java/.../strategy/RangeTypeGuardEquivalenceTest.java`,
`typesHub` (line 64), `addTypedVertex` (line 684), `tagsOf` (line 811).

**Issue, two parts.**

`typesHub` is a mutable instance field that `seedOneValueOfEachType` assigns and
`addTypedVertex` dereferences. `nanValue_isExcludedByBothArmsEvenThoughTheGuardAdmitsIt`
calls the seeder and then `addTypedVertex` directly, which is the only reason
the ordering holds; a later case that reaches for `addTypedVertex` on its own
gets an NPE with no message about the missing seed. The field's javadoc names
its role ("The hub every `Types` vertex hangs off") and not its precondition.
The class also carries three unrelated fixtures — `seedMixedTypeFixture` (nine
vertices, three classes), `seedOneValueOfEachType` (14 vertices plus the hub),
`seedIndexedBulkClass` (604 vertices with an index) — and only the third is
reachable from a single test.

`tagsOf`'s javadoc reads "Sorted `tag` values of the returned vertices (or of
the returned edges' target, for a shape that ends on a vertex step)". The body
casts every result to `Vertex` and reads `tag`; there is no edge-target branch.
This is the second half of `TS48`, unfixed.

**Suggestion.** Either give `addTypedVertex` an explicit hub parameter, or add
`Objects.requireNonNull(typesHub, "seedOneValueOfEachType must run first")` at
its head. Drop the parenthetical from `tagsOf`'s javadoc.

## Evidence base
