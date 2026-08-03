<!-- MANIFEST
findings: 9   severity: {blocker: 2, should-fix: 1, suggestion: 6}
index:
  - {id: TS1, sev: blocker, loc: ProjectionEquivalenceTest.java:202, anchor: "### TS1 ", cert: n/a, basis: "measured: the sub-walk arm never reaches the escape it names; with the escape disabled the whole translator test package stays green"}
  - {id: TS2, sev: blocker, loc: ByModulatorTranslatorTest.java:136, anchor: "### TS2 ", cert: n/a, basis: "measured: the changed predicate's own unit class is green with either predicate because its bodies skip applyStrategies, and a correct translation was lost unseen"}
  - {id: TS3, sev: should-fix, loc: ProjectionEquivalenceTest.java:192, anchor: "### TS3 ", cert: n/a, basis: "two independent escapes in one method, count arm first; measured masking of the second arm, and the count arm duplicates line 929"}
  - {id: TS4, sev: suggestion, loc: ProjectionEquivalenceTest.java:110, anchor: "### TS4 ", cert: n/a, basis: "three inline translator toggles restore a hardcoded false where the flag defaults to true; one has no try/finally; the class helper captures the original"}
  - {id: TS5, sev: suggestion, loc: ProjectionEquivalenceTest.java:87, anchor: "### TS5 ", cert: n/a, basis: "the Javadoc names the element-type assertion as the non-vacuity guard, but it cannot fail unless the boundary-count assertion failed first"}
  - {id: TS6, sev: suggestion, loc: ProjectionEquivalenceTest.java:128, anchor: "### TS6 ", cert: n/a, basis: "the only decline case in the class whose name says matchesNative, on a one-vertex fixture that cannot separate a property-level has from a vertex-level one"}
  - {id: TS7, sev: suggestion, loc: ProjectionEquivalenceTest.java:185, anchor: "### TS7 ", cert: n/a, basis: "a line-wrapped {@link} splits the FQN across the leading asterisk, so the reference resolves against a package and the rest renders as link text"}
  - {id: TS8, sev: suggestion, loc: ProjectionEquivalenceTest.java:25, anchor: "### TS8 ", cert: n/a, basis: "four new tests land outside every banner section of a sectioned file, and the class header's terminator list does not mention the element form"}
  - {id: TS9, sev: suggestion, loc: PropertiesStepRecogniser.java:74, anchor: "### TS9 ", cert: n/a, basis: "the gate's Javadoc names a third rewrite position it deliberately leaves to native, and no test pins that this shape declines"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [blocker] The sub-walk arm of the new escape test never reaches the escape it names

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `countConsumedAndSubWalkPropertiesForms_stillTranslate` (Javadoc line 183, method line 192, sub-walk arm lines 202-205)

**Issue**: The Javadoc says both arms are shapes "callers do write and would silently stop translating if the element-form decline were unconditional". Measurement says that holds for the count arm and not for the sub-walk arm.

`g.V().where(__.values("age"))` after strategy application is `TraversalFilterStep([PropertiesStep([age],property)])`. `TraversalFilterStepRecogniser` claims that shape at line 56 through `presenceKey` (line 92): a filter child that is exactly one single-key `PropertiesStep` of either return type maps straight to `key IS DEFINED`. The child never reaches `ctx.walkChild`, so it never reaches `SubTraversalPredicateAdapter` and never consults the escape. `not(__.values(k))` takes the same route through `NotStepRecogniser`'s `hasNot` case (line 52).

Measured, by disabling each escape separately at `c252146ba5` and rerunning:

| Mutation | Result |
|---|---|
| `SubTraversalPredicateAdapter.projectsReturnedPayload()` (line 184) returns `true` — sub-walk escape off | `ProjectionEquivalenceTest` 49/49, `GremlinProjectionRecogniserTest` 17/17, `TraversalFilterStepRecogniserTest` 7/7 green; the whole `...gremlin.translator.**` package green (32 classes, 634 tests) |
| `cursor.peek(0)` → `cursor.peek(1)` in `elementFormIsUnobserved` (line 81) — count escape off | 2 failures: `countConsumedAndSubWalkPropertiesForms_stillTranslate` at arm 1 and the pre-existing `countAfterValues_countsOnlyKeyBearers`, both `boundaryOn expected: 1 but was: 0` |

So the count escape is pinned twice over and the sub-walk escape is pinned nowhere.

The shape that does reach the escape is a connective child: `and(__.values(a), __.values(b))` routes through `AndStepRecogniser` → `ConnectiveStepSupport.walkAcceptedChildren` → `walkChild`, and `AndStepRecogniser` has no presence shortcut. Measured on a seed of Alice (name + age), Bob (name), and a third vertex (age + nick), `g.V().and(__.values("age"), __.values("name"))` translates (one boundary step) and returns all three vertices where native returns Alice alone; `where(__.and(...))` does the same. The escape's stated justification at `PropertiesStepRecogniser:64-67` — "Only the presence conjunct the projection contributes survives the commit" — does not hold on the one path that exercises it. That divergence reproduces unchanged at the parent commit `9b9d94d811`, so the step did not introduce it. A sub-walk arm on the path the escape actually serves would have surfaced it.

**Suggestion**: Point the arm at a shape that reaches the escape (`and(__.values("age"), __.values("name"))`, or `where(__.and(...))`), and expect it to fail — route the divergence itself to the bugs dimension. Add the premise the test currently assumes: after `applyStrategies()`, assert the child `PropertiesStep.getReturnType()` is `PROPERTY`, so a fork upgrade that stops rewriting cannot leave the arms green while covering neither escape. If the connective path is out of scope for this step, say in the Javadoc that only the count position is pinned.

### TS2 [blocker] The changed by-modulator predicate has no case in its own unit class, whose fixtures cannot see the change

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslatorTest.java`, positive controls at `keySide_valuesTraversal_unwrapsToProperty` (line 53) and `valueSide_propertyAggregates_resolvePropertyAggregate` (line 136); changed method `ByModulatorTranslator.isSingleValueProperty` (line 246)

**Issue**: `isSingleValueProperty` gates two call sites — the key side at line 124 and the value-side accumuland at line 187 — and the class that exists to pin it got no case for either. Measured, the class is green with the step's predicate and green with the pre-step predicate restored (10/10 both ways), because every case hands `translateKeyModulator` / `translateValueModulator` a hand-built `__.values("age")…` body that never passes through `applyStrategies()`. Production does not deliver those.

What production delivers, measured on the same commit:

- Key side: `by(__.values("age"))` arrives as a `ValueTraversal` (`value(age)`), resolved at line 116 before the `PropertiesStep` case is reached. The commit message's "only ever hand-written" is correct here.
- Value side: the group child of `g.V().group().by("name").by(__.values("age").count())` arrives as `[PropertiesStep([age],property), CountGlobalStep]`, because `AdjacentToIncidentStrategy` rewrites a `values(key)` that precedes a `CountGlobalStep` inside a child traversal too. On that body `translateValueModulator` returns `Optional.empty` at `c252146ba5` and `Optional[PropertyAggregate[COUNT, $g2m_v0.age]]` with the pre-step predicate; end to end the shape goes from one boundary step to zero, with both arms answering `[{Bob=0, Alice=1}]` either way.

A correct translation of a shape callers write was withdrawn, the answers agreed before and after, and no test moved. This is the same position the main line protects with its count escape, on the argument that one property element per value leaves a count unchanged.

**Suggestion**: Two cases in `ByModulatorTranslatorTest`, both built through `applyStrategies()` so the body matches what arrives: a key-side `by(__.properties("age"))` decline beside the existing line-53 control, and a value-side `by(__.values("age").count())` case stating the intended outcome for the post-strategy element form. Whether that outcome should be a decline or the same escape the main line grants is the technical reviewer's call; the test gap is that neither answer is pinned today.

### TS3 [should-fix] Two independent escapes share one test method, and the first arm masks the second

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `countConsumedAndSubWalkPropertiesForms_stillTranslate` (line 192)

**Issue**: The method asserts the count position first (lines 197-200) and the sub-walk position second (lines 202-205). Under the count-escape mutation in TS1 the method fails at the first `assertEquivalent`, so the second arm never runs — one failure signal for two independent branches of `elementFormIsUnobserved`, and the arm a reader most wants attributed is the one that gets dropped.

Arm 1 also duplicates `countAfterValues_countsOnlyKeyBearers` (line 929): same traversal, same expectation, and the older test adds the hand-computed native answer (`isEqualTo(2L)`) this one omits. Both failed together under the mutation, which is how the duplication surfaced.

**Suggestion**: One method per escape — `countConsumedPropertiesForm_stillTranslates` and `subWalkPropertiesForm_stillTranslates` — so each branch reports independently. Either drop arm 1 in favour of line 929 or keep it with a Javadoc pointer saying line 929 carries the native answer.

### TS4 [suggestion] Three inline translator toggles restore a hardcoded `false` where the flag defaults to `true`

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, lines 110-117, 140-147 and 177

**Issue**: `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to `true` (`GlobalConfiguration:1019-1028`, "True by default"), so `finally { setTranslatorEnabled(false); }` flips the flag rather than restoring it, and line 177 sets it to `false` with no `finally` at all. The class helper two screens down does it correctly: `assertEquivalentInternal` (lines 1022-1072) reads the current value into `original` and restores that.

Nothing fails today. Each block is the last statement in its method, and `DbTestBase` gives every method its own database, so the mutated `ContextConfiguration` dies with it. The cost is a trap for the next edit: an assertion appended after one of these blocks runs translator-off and passes without exercising the translator, which is the failure mode this track has now catalogued nine times.

**Suggestion**: A small `withTranslatorOn(Runnable)` helper next to `setTranslatorEnabled` (line 1074) that captures and restores the previous value, and use it in all three places. That also removes the three copies of the same six-line block.

### TS5 [suggestion] The element-type assertion cannot fail unless the assertion above it failed first

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `propertiesElementForm_declines_whileValuesStillTranslates` (Javadoc line 87, assertion lines 112-114)

**Issue**: The Javadoc says the three assertions are "one claim each and none is redundant", and names the element-type check as "what makes the equality non-vacuous". The order of failure is the other way round. A regression that projected the value would engage a boundary step, so `assertEquivalent(..., DECLINED, ...)` fails at `boundaryOn expected: 0` (line 1059) and the method stops there; line 112 never executes. On the path where line 112 does run, the traversal has already been proved untranslated, so the assertion restates that native `properties(key)` yields a `Property` — a TinkerPop fact, not a gate guard.

**Suggestion**: Keep the assertion, and correct the Javadoc to name the boundary-step count as the discriminator. The class's convention is that a decline case names its own discriminator, and this is the one place where the named one is dominated.

### TS6 [suggestion] The meta-property test is named for a match and asserts a decline, on a fixture that cannot locate the filter

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `metaPropertyFilterThroughProperties_matchesNative` (line 128)

**Issue**: Every other decline case in the class says so in its name — `valuesDedup_declinesToNative` (367), `dedupByName_declinesToNative` (456), `limit5Count_declinesToNative` (497), `keylessValueMapAndElementMap_decline` (828), `terminatorsAfterGroup_decline` (895), and the two new `_declines_while…` tests. This one reads as a parity case and asserts `Recognition.DECLINED`.

The fixture is one vertex whose only `acl` sits on the property. The distinction the test exists for — the trailing `has` reads the property element, not the vertex — needs a vertex carrying a top-level `acl=private` and no `friendWeight`; with that vertex absent, a translation that pushed the `has` onto the vertex is indistinguishable from one that dropped it. Nothing depends on this today because the shape declines, and the test is the guard for the day it stops declining.

**Suggestion**: Rename to `…_declinesToNative` and add the third vertex, so the fixture separates the two placements of the filter.

### TS7 [suggestion] A line-wrapped `{@link}` in the new test's Javadoc resolves against a package

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, lines 185-186

**Issue**: The reference is split across the leading asterisk as `{@link org.apache.tinkerpop.gremlin` / `* .structure.PropertyType#PROPERTY}`. Javadoc joins the lines and reads the whitespace as the reference-label boundary, so the reference is the package `org.apache.tinkerpop.gremlin` and `.structure.PropertyType#PROPERTY` becomes the link text.

**Suggestion**: `{@code PropertyType.PROPERTY}`, or import `PropertyType` and link it unqualified as `GremlinProjectionRecogniserTest` does.

### TS8 [suggestion] The four new tests sit outside every section of a sectioned file, and the class header does not mention them

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, class Javadoc line 25, new tests lines 84-206

**Issue**: The file is organised by banner comments — B1 slice-then-reduce (487-493), TC1/TC2 empty input (568-570), `by(key)` filtering (613), `valueMap`/`elementMap` tokens (802), terminators that read a projected value (847). The four new tests land between `values_absentVsPresentNull_matchNative` and `elementMap_matchNative` with no banner, and they are a coherent group: the element form versus the value form. The class Javadoc still lists the Track 6 terminators only, so neither `properties(key)` nor the by-modulator key appears in the header a reader starts from, and `metaPropertyFilterThroughProperties` is a filter shape in a file described as projection and aggregate terminators.

**Suggestion**: A banner section, `--- properties(key): the element form declines where the value is read ---`, holding all four, plus one clause in the class Javadoc.

### TS9 [suggestion] The third rewrite position the gate names is pinned by no test

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PropertiesStepRecogniser.java`, `elementFormIsUnobserved` Javadoc lines 73-78

**Issue**: The Javadoc names `values(key).count().is(gt(n))` as a position `AdjacentToIncidentStrategy` also rewrites, states that the walk declines a step later because `is(...)` has no recogniser, and asks whoever adds an `IsStep` recogniser to extend the gate. No test covers the shape: `is(P.gt(...))` appears in the Gremlin test tree only at `YTDBQueryMetricsStrategyTest:978`, over `values("age").is(...)` without a count.

**Suggestion**: One `assertEquivalent("g.V().values(age).count().is(gt(1))", Recognition.DECLINED, …)` line beside the new escape tests. It pins the claim the comment makes and fails the day an `IsStep` recogniser lands without the gate extension the comment requests.

## Evidence base
