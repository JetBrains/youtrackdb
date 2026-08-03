<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 3, suggestion: 4}
index:
  - {id: TS30, sev: should-fix, loc: NotStepRecogniserTest.java:331, anchor: "### TS30 ", cert: n/a, basis: "engine-assumption pin buried in a code-pin test; a premise expiry reddens a test named for the decline"}
  - {id: TS31, sev: should-fix, loc: NotStepRecogniserTest.java:420, anchor: "### TS31 ", cert: n/a, basis: "~95 lines of the equivalence harness copied verbatim into a unit-test class; sixth copy in the package"}
  - {id: TS32, sev: should-fix, loc: NotStepRecogniserTest.java:40,  anchor: "### TS32 ", cert: n/a, basis: "reworded class javadoc routes readers to a class that carries no not(has(...)) equivalence case"}
  - {id: TS33, sev: suggestion, loc: NotStepRecogniserTest.java:272, anchor: "### TS33 ", cert: n/a, basis: "older test keeps the narrow boundary-step check the new helper's javadoc argues against"}
  - {id: TS34, sev: suggestion, loc: NotStepRecogniserTest.java:420, anchor: "### TS34 ", cert: n/a, basis: "helper region split in two with no banner; reader hits a private enum straight after the last @Test"}
  - {id: TS35, sev: suggestion, loc: GremlinPredicateAdapterTest.java:806, anchor: "### TS35 ", cert: n/a, basis: "two method names under-describe their assertion sets, so a failure report names the wrong claim"}
  - {id: TS36, sev: suggestion, loc: NotStepRecogniserTest.java:445, anchor: "### TS36 ", cert: n/a, basis: "assertEquivalent pins non-emptiness only on the RECOGNIZED path, so decline multiset equality can go vacuous"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS30 [should-fix] The engine-assumption pin rides inside a test named for the decline

**Location:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/NotStepRecogniserTest.java`, method `notWithCrossTypeRangeComparison_declinesAndAgreesWithNative`, lines 331-345.

**Issue.** The pin is legitimate, not a passenger. It is the only thing in the
tree that records the premise the whole decline rests on: with the translator
on, `has("name", lte(27))` returns 0 and `has("name", lte("z"))` returns 6, so
YouTrackDB's SQL comparator ranks every String above the Integer. The
String-comparand control does its job — the empty result cannot be read as a
broken clause. If the comparator's cross-type rule ever changes, the decline
becomes unnecessary and this is the assertion that says so. Keep it.

The container is wrong. The block sits inside a test whose name promises
`declinesAndAgreesWithNative`, so a comparator change reddens
`notWithCrossTypeRangeComparison_declinesAndAgreesWithNative` and sends the
next maintainer to `NotStepRecogniser`, where nothing is broken. Worse, the
pin's survival is tied to the decline's: whoever removes the decline (because
the engine was fixed) deletes this test whole and loses the record of why the
decline existed. The comment explains the current fact well but never names the
consequence of failure, so a reader who does find it has no instruction beyond
"retune the numbers".

**Proposed fix.** Extract lines 331-345 into their own `@Test` named for what
it pins, e.g. `crossTypeRangeComparison_sqlRanksStringAboveInteger`. Give it a
javadoc that says outright: this is an engine-assumption pin, not a code pin —
no change to `NotStepRecogniser` can redden it; if it reddens, the premise for
the range-comparison decline has changed and the decline itself should be
re-evaluated rather than the expected counts adjusted. Leave the native
`hasSize(6)` pin at lines 322-329 where it is; that one guards against the
decline test's own vacuity and belongs to the decline test.

### TS31 [should-fix] A sixth verbatim copy of the equivalence harness, landed in a unit-test class

**Location:** `NotStepRecogniserTest.java` lines 420-516 (`Recognition`,
`assertEquivalent`, `withTranslator`, `translatorEnabled`,
`setTranslatorEnabled`, `graphSession`, `sortedIds`, `countBoundarySteps`).

**Issue.** All eight members are near-verbatim copies of
`PredicateTraversalEquivalenceTest.java` lines 1090-1261; the differences are a
reworded assertion description and `isZero()` for `isEqualTo(0)`. Across the
package, `private enum Recognition` now appears in five classes and
`private void setTranslatorEnabled` in six (grep-only — PSI `execute_code`
times out in this repository, so this is a textual count, not a symbol count).

The placement compounds it. `NotStepRecogniserTest` is a unit-test class: every
other case drives the recogniser through a hand-built `WalkerContext` and a
`StepStreamCursor`. The five new cases seed a six-vertex graph, toggle a global
configuration flag, and execute traversals twice. The class javadoc had to grow
an exception clause to accommodate them, and the clause it was carved out of
names `PredicateTraversalEquivalenceTest` as the home for exactly this kind of
case. That class already has all eight helpers, so the cases would have cost
zero new harness there.

A plausible reason not to touch it: concurrent steps 10 and 11 are working in
neighbouring files and a shared edit invites a conflict. That justifies the
placement for this step; it does not justify leaving the duplication unrecorded.

**Proposed fix.** Pick one. Either move the five cases to
`PredicateTraversalEquivalenceTest` once the concurrent steps land, or keep
them here and extract the eight members into a package-private
`TranslatorEquivalenceSupport` (or a shared base class) that all six classes
call. If neither fits this step's budget, record the consolidation as a
track-11 backlog item so the seventh copy has somewhere to be refused.

### TS32 [should-fix] The reworded class javadoc points at coverage that is not there

**Location:** `NotStepRecogniserTest.java` lines 40-47 (class javadoc, rewritten
by this step).

**Issue.** The javadoc says end-to-end multiset equivalence for `hasNot(key)`,
`not(has(...))` and `not(out(...))` lives in `PredicateTraversalEquivalenceTest`
and `EdgeTraversalEquivalenceTest`. Two of the three check out:
`hasNotKeyPresence_matchesNative` at `PredicateTraversalEquivalenceTest:360`,
and the `not(out(...))` cases at `PredicateTraversalEquivalenceTest:1046` and
`EdgeTraversalEquivalenceTest:803`. The middle one does not. A grep for
`not(__.has` across `core/src/test` returns no end-to-end equivalence case in
either named class; the only ones in the tree are the five this step just added
to *this* class. The pointer sends a reader looking for `not(has(...))`
coverage to a class that has none, and on a branch with twelve recorded
could-not-fail tests, a false coverage pointer is the expensive kind of stale
comment.

The claim predates this step, but the step rewrote the three lines carrying it
and added the case that makes it wrong.

Second, smaller inaccuracy in the same javadoc: the carve-out calls the new
material "the negated-range-comparison cases", and one of the five
(`notWithEqualityPredicate_keepsTranslating`, line 378) is a negated-equality
control that exists to bound the decline.

**Proposed fix.** Drop `not(has(...))` from the "lives elsewhere" list and say
where it actually lives — this class, as the boundary cases for the decline.
Reword the carve-out to cover the equality control as well, e.g. "the decline's
boundary cases at the end of this class — which predicate families still
translate under `not(...)` and which do not".

### TS33 [suggestion] Two idioms for the same two operations, one screen apart

**Location:** `NotStepRecogniserTest.java`, `applyStrategies_hasAgeNotOut_engagesBoundaryStep`
(lines 272-293) against the new helpers at 480-516.

**Issue.** The older test toggles the translator through
`session.getConfiguration()` and counts boundary steps with
`YTDBMatchPlanStep.class::isInstance` inline; the new helpers use
`graphSession().getConfiguration()` and the `AbstractMatchPlanStep` supertype.
The config surfaces are equivalent — `DatabaseSessionEmbedded.getConfiguration()`
returns `storage.getContextConfiguration()`, and both sessions open the same
database, so both land on the same instance — so nothing is broken. The
boundary count is the part worth changing: the new helper's javadoc (lines
503-507) argues that counting only the single-plan subtype lets a multi-plan
translation satisfy a decline expectation, and the older test one screen above
still does exactly that.

**Proposed fix.** Rewrite `applyStrategies_hasAgeNotOut_engagesBoundaryStep` on
`withTranslator` and `countBoundarySteps`. It shrinks to four lines and the
class stops documenting two answers to the same question.

### TS34 [suggestion] The helper region is now split in two with no banner between

**Location:** `NotStepRecogniserTest.java` lines 420-550.

**Issue.** New helpers occupy 420-516 and the pre-existing ones 518-550, so a
reader looking for `contextWithRegistry` scrolls past a private enum, an
equivalence runner and a boundary counter to reach it. There is no
tests-end-here marker: the last `@Test` closes at 418 and `private enum
Recognition` starts at 421. The sibling file in this diff gets it right —
`GremlinPredicateAdapterTest` puts a `// Helpers.` banner at line 843 after its
last test, and the step's own new block at 743-841 sits under a matching
banner.

**Proposed fix.** Add the same `// Helpers.` banner before line 420 and move
the four pre-existing helpers up beside the new ones, so the class reads as
tests-then-helpers rather than tests-then-helpers-then-more-helpers.

### TS35 [suggestion] Two adapter test names under-describe what they assert

**Location:** `GremlinPredicateAdapterTest.java`,
`traversalHasRangeComparison_findsNestedLocalAndGlobalChildren` (lines 806-829)
and `predicateHasRangeComparison_falseForMembershipAndTextPredicates`
(lines 789-804).

**Issue.** The first asserts four things, and two of them are neither local nor
global children: a top-level `has()` (line 808) and a `has()` behind a hop
(line 824). The second adds a `null`-predicate case (line 801) that is neither
membership nor Text. Both extra cases carry `.as(...)` descriptions, so the
failure output is readable — but the surefire report shows the method name
first, and a reader who sees
`traversalHasRangeComparison_findsNestedLocalAndGlobalChildren` fail will look
for a nesting bug when the broken assertion was the behind-a-hop one. The same
name-versus-content drift is what makes the four-assertion form awkward: AssertJ
stops at the first failure, so a mutation that breaks three of the four shows
only one.

**Proposed fix.** Rename to what the sets actually cover —
`traversalHasRangeComparison_findsRangeAtEveryDepth` and
`predicateHasRangeComparison_falseForNonOrderingPredicates` — or split the
top-level and behind-a-hop assertions into a second method. Consider
`assertSoftly` for the four-way method so one run reports every broken arm.

### TS36 [suggestion] Non-emptiness is pinned on the translated path only, so a decline case can go vacuous

**Location:** `NotStepRecogniserTest.java`, `assertEquivalent` lines 445-457.

**Issue.** The `RECOGNIZED` branch asserts `onIds` is non-empty precisely
because the multiset comparison below it would otherwise pass on two empty
results. The `DECLINED` branch gets no such pin, so for
`notWithRangeComparisonBehindHop_declinesToNative` and
`notWithBetweenPredicate_declinesToNative` the multiset arm is unguarded: a
fixture that seeded nothing would leave both lists empty and both tests green
on the strength of the boundary-count assertion alone. The boundary count is
still a real assertion, so neither test is unfalsifiable — the cost is one of
two witnesses silently retiring. The step's author hit this for the cross-type
case and worked around it with a bespoke `hasSize(6)` outside the helper
(lines 322-329) rather than closing it in the helper.

Both shapes do return rows today (5 and 4 respectively on the modern graph), so
an unconditional pin would hold for every decline case in this class.

**Proposed fix.** Assert `offIds` is non-empty on the `DECLINED` path too, or
add an opt-in flag for decline cases that legitimately return nothing. Check the
five sibling copies' decline cases before making it unconditional — the change
belongs with the harness consolidation in TS31, and if it lands there the
bespoke `hasSize(6)` at 322-329 can go.

## Evidence base

## Reviewer notes

Four checks the dispatch asked for came back clean. Recording them so they do
not get re-run.

**The mutation record holds.** Each mutation reddens the tests named for it, on
the reading of the mutation points that makes the counts come out.
`M_B` = `predicateHasRangeComparison` always false gives exactly 6 in these two
classes: the two `predicateHasRangeComparison_*` true-side tests, the
`traversalHasRangeComparison_findsNested…` case, and the three decline cases.
`M_A` = `traversalHasRangeComparison` always true gives exactly the 2 named
(`traversalHasRangeComparison_falseWhenNoRangeComparisonPresent`,
`notWithEqualityPredicate_keepsTranslating`) plus wide collateral across the
class's other translated-`not()` cases — and, per the branch order at
`NotStepRecogniser.java:59-63`, *not* the two `hasNot` cases, which return
before the gate. `M_D` reddens `bareCrossTypeRangeComparison_…` because
`assertEquivalent`'s `RECOGNIZED` branch asserts `boundaryOn == 1`; the shape
would decline and the count would be 0. `M_C` reddens the `union` arm of the
nested-children test and nothing else — `__.and(…)` children are local,
`__.out("knows").has(…)` is a flat step list, and no case in
`NotStepRecogniserTest` reaches a global child, so the global-children branch
has no end-to-end witness. That is defensible for defence-in-depth detection
that runs before the walk, but it means the union assertion is the branch's
only pin.

**The three decline tests are falsifiable independently of the base-commit
run.** Two in-diff neighbours establish that the shapes translated before the
gate existed: `bareCrossTypeRangeComparison_…` proves `has(name, gt(27))` is
recognised, and the pre-existing `pureFilterChild_wrapsBoundaryPredicateInNot`
and `edgeBearingChildWithTargetFilter_attachesLeafWhere` prove the
`not(has(...))` and `not(out(...).has(...))` shapes are. So the reported
base-commit failure message ("expected 0 but was 1") is the message those tests
must have produced, and none of the three was already declining for an
unrelated reason.

**Deleting the `hasNot` equivalence test was right, for a stronger reason than
the one given.** The unfalsifiability argument checks out —
`hasNotPresenceKey` returns at `NotStepRecogniser.java:60` before the gate at
79, and `traversalHasRangeComparison(__.values(key))` is false either way, so
moving the gate above the presence branch would not change the answer. But the
decisive fact is that the coverage already exists end-to-end at
`PredicateTraversalEquivalenceTest#hasNotKeyPresence_matchesNative` (line 360),
so the deleted test would have been a third copy rather than a thin spot. The
step also left a cheap unit-level marker at
`GremlinPredicateAdapterTest:838-840`, which documents the desugar shape where
the detector can see it.

**The `git checkout` round trip left no damage.** `git diff --numstat` over the
step range reports `86 0` for `GremlinPredicateAdapter.java` — 86 insertions,
zero deletions, matching the implementer's figure exactly, and a zero deletion
count is itself the proof that nothing pre-existing was lost. Braces balance
(295/295), the three new imports (`Traversal`, `HasContainerHolder`,
`TraversalParent`) are present, and `java.util.List` and `javax.annotation.Nullable`
were already imported for the new private methods. The insertion sits between
`toConnective` and the `Compare`-mapping javadoc, inside the class.

**Isolation is sound and needs no change.** `DbTestBase.beforeTest` creates a
database named after the test method, so each of the five new cases gets its own
graph and `ModernGraphFixture.seed` cannot accumulate. The translator flag is
per-`ContextConfiguration` (`ContextConfiguration.setValue` writes an instance
map — no static mutation), and every toggle is restored in a `finally`. No
`Thread.sleep`, no ordering dependency, no shared fixture.

**One detection gap, out of this dimension's scope but worth a look from the
completeness reviewer.** `traversalHasRangeComparison` finds predicates through
`HasContainerHolder` only, so `not(values("age").is(P.gt(30)))` carries a range
comparison the detector reports as absent (`IsStep` holds its predicate
directly, not in a `HasContainer`). Whether that shape reaches the gate at all
depends on whether `IsStep` is in the recogniser registry — it is not in
`productionRegistry()` here — so it likely declines anyway. Flagging rather than
filing.
