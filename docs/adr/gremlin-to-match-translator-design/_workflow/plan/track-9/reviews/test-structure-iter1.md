<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 3, suggestion: 5}
index:
  - {id: TS1, sev: should-fix, loc: RepeatDeclineStrategyTest.java:197, anchor: "### TS1 ", cert: n/a, basis: "positive control is a different shape from every vetoed traversal, so no case rules out a vacuous zero-boundary-step pass"}
  - {id: TS2, sev: should-fix, loc: RepeatDeclineStrategyTest.java:237, anchor: "### TS2 ", cert: n/a, basis: "no case observes the veto itself; a recogniser decline and a veto decline are indistinguishable"}
  - {id: TS3, sev: should-fix, loc: RepeatDeclineStrategyTest.java:126, anchor: "### TS3 ", cert: n/a, basis: "repeatUntil case is a guard presented as a witness; RepeatUnrollStrategy never unrolls an until-terminated repeat"}
  - {id: TS4, sev: suggestion,  loc: RepeatDeclineStrategyTest.java:33, anchor: "### TS4 ", cert: n/a, basis: "class javadoc states three per-case invariants that three cases skip and one the helper checks on one arm only"}
  - {id: TS5, sev: suggestion,  loc: RepeatDeclineStrategyTest.java:168, anchor: "### TS5 ", cert: n/a, basis: "translatorAlreadyRemovedFromTheSource asserts an outcome that holds by construction, not the pre-check its name claims"}
  - {id: TS6, sev: suggestion,  loc: RepeatDeclineStrategyTest.java:270, anchor: "### TS6 ", cert: n/a, basis: "kill-switch save/restore in the helper, bare set in three tests, and two different handles to the same configuration"}
  - {id: TS7, sev: suggestion,  loc: RepeatDeclineStrategyTest.java:297, anchor: "### TS7 ", cert: n/a, basis: "shared String-multiset helper forces List.of(\"2\") for counts, carries a dead Vertex branch, and misnames two cases"}
  - {id: TS8, sev: suggestion,  loc: RepeatDeclineStrategyTest.java:237, anchor: "### TS8 ", cert: n/a, basis: "fifth hand-rolled copy of the same translator test harness in this package"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] The positive control is not the shape any vetoed case would have translated

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java`, method `handWrittenChainOfHopsStillTranslates` (line 197)

**Issue**: The suite's only positive control runs `g.V().out("knows").out("knows")`. No vetoed case runs that traversal. The four `times(2)` cases (lines 72, 85, 100, 110) run an unlabelled hop under a terminator, so the shapes `RepeatUnrollStrategy` hands the walker are `V().out().out().count()` and `V().out().out().values("name")`. Both differ from the control in the edge label and in the terminator.

"Zero boundary steps" holds for every reason the walker can decline, including "this shape was never a translation candidate". Ruling that out needs a control on the exact unrolled form, and the current control is one step removed from all four. It is also redundant with an existing pinned case: `EdgeTraversalEquivalenceTest.multiHopChain_recognizedViaTransparentBarrier` (line 169) already asserts one boundary step for the same traversal over the same three-vertex `knows` chain.

A second gap rides on the same assertion. Nothing witnesses that the kill-switch flip took effect on the on arm. `setTranslatorEnabled(true)` writes through one session handle and `withNonPolymorphicDefault` writes through another (TS6); if either write missed the configuration the traversal reads, every on-arm assertion in the class would pass with the translator switched off.

I did not measure whether the two unrolled forms translate. `VertexHopRecogniser`'s class javadoc states that a label-less hop translates, and `CountGlobalStep` and `PropertiesStep` both have registered recognisers (`GremlinStepWalker:170,179`), so the expectation is that they do. The point stands either way: the suite does not measure it, so a later recogniser change that made those shapes untranslatable would hollow out four cases silently and no test would fail.

**Suggestion**: Put the control inside `assertDeclinedAndEquals`, run under the same `setTranslatorEnabled(true)` as the on arm, so one assertion covers both gaps:

```java
setTranslatorEnabled(true);
var vetoOff = traversalSupplier.get().asAdmin();
vetoOff.setStrategies(vetoOff.getStrategies().clone()
    .removeStrategies(RepeatDeclineStrategy.class));
vetoOff.applyStrategies();
assertThat(countBoundarySteps(vetoOff))
    .as(scenario + ": without the veto this shape must translate, or the decline assertion is vacuous")
    .isEqualTo(1);
```

On the four-vertex chain with `times(2)` the un-vetoed run enumerates a handful of paths, so it costs nothing. Two cases will need an exemption or a separate expectation: the `until` case (TS3) and the union case, which decline for recogniser reasons and are documented as guards. Passing the expected boundary count into the helper alongside `expected` handles both. Once the in-helper control exists, `handWrittenChainOfHopsStillTranslates` can be dropped in favour of the equivalent case already in `EdgeTraversalEquivalenceTest`.

### TS2 [should-fix] No case observes the veto, only its outcome

**File**: `RepeatDeclineStrategyTest.java`, method `assertDeclinedAndEquals` (line 237), applying to all six repeat-bearing cases

**Issue**: Every negative case asserts `countBoundarySteps(onAdmin) == 0`. That is the outcome of a decline, and it reads the same whether `RepeatDeclineStrategy` vetoed the traversal or a recogniser refused the shape. The class then cannot say which of its cases exercise the strategy under test. Two of them demonstrably do not (the union case says so in its javadoc at line 143; the `until` case does not, see TS3).

The file already contains the mirror assertion for the off arm: `translatorOff_leavesTheTraversalStrategyListUntouched` (line 214) checks that the translator stays in the traversal's own strategy list. The on-arm form is missing.

**Suggestion**: Add to the helper after `onAdmin.applyStrategies()`:

```java
assertThat(onAdmin.getStrategies().getStrategy(GremlinToMatchStrategy.class))
    .as(scenario + " (translator on): the veto must remove the translator from this traversal")
    .isEmpty();
```

The assertion holds for all six repeat-bearing cases, because `RepeatDeclineStrategy.apply` replaces the root traversal's strategy list (`RepeatDeclineStrategy.java:184`) whenever it finds a `RepeatStep` and the kill-switch is on. Paired with TS1 it separates the two decline mechanisms per case: TS1 shows the shape would have translated, TS2 shows this strategy is what stopped it.

### TS3 [should-fix] The `until` case is a guard, and its javadoc reads as a witness

**File**: `RepeatDeclineStrategyTest.java`, method `repeatUntil_declinesAndMatchesNative` (line 126)

**Issue**: `RepeatUnrollStrategy` unrolls only when the until traversal is a `LoopTraversal` — that is, only for `times(n)`. I disassembled the fork's `gremlin-core-3.8.1-67860f6-SNAPSHOT.jar`: `apply` calls `getUntilTraversal()`, tests `instanceof LoopTraversal`, and branches straight to the exit when the test fails (offsets 100-106). `until(__.not(__.out("knows")))` is not a `LoopTraversal`, so the `RepeatStep` survives into the provider-optimization pass, and the walker registry has no `RepeatStep` recogniser. The case therefore declines with or without `RepeatDeclineStrategy`, exactly like the union case at line 150 — whose javadoc states its guard status plainly, in two sentences, and is the better model.

The class javadoc's third per-case invariant compounds this. It says `RepeatUnrollStrategy` is "still applied" on the case, and the helper checks list membership (line 248). On this case the strategy is registered and did not unroll anything.

**Suggestion**: Give the case the same caveat the union case carries — that it guards the recursive scan rather than witnessing the fix, and that the unroll does not fire on an `until`-terminated repeat. TS2's strategy-list assertion then upgrades it into a real witness for the veto, because the veto does fire here even though the unroll does not.

### TS4 [suggestion] The class javadoc claims more than the helper checks

**File**: `RepeatDeclineStrategyTest.java`, class javadoc (lines 33-42)

**Issue**: Three claims drift from the code.

- "Each case therefore asserts three things" — three of the nine cases (lines 168, 197, 214) bypass `assertDeclinedAndEquals` and assert none of the three.
- "`RepeatUnrollStrategy` still applied on both arms" — the helper computes `unrolledOn` only (line 248). The off arm is never checked.
- "still applied" — the helper checks that the strategy is present in the list, not that it unrolled anything. Only `translatorOff_leavesTheTraversalStrategyListUntouched` observes an actual unroll, through `noneMatch(step -> step instanceof RepeatStep)` (line 220).

**Suggestion**: Say what the helper does: the six shared-helper cases assert zero boundary steps on the on arm, the hand-computed multiset on both arms, and that the unroll strategy survives on the on arm. List the three standalone cases separately with their own purpose. If the "both arms" claim is the intended contract, add the off-arm check rather than editing the sentence.

### TS5 [suggestion] `translatorAlreadyRemovedFromTheSource_needsNoVeto` cannot fail on account of the strategy

**File**: `RepeatDeclineStrategyTest.java`, method `translatorAlreadyRemovedFromTheSource_needsNoVeto` (line 168)

**Issue**: The traversal source drops `GremlinToMatchStrategy` before the traversal is built, so "no boundary step" holds by construction whatever `RepeatDeclineStrategy` does. The javadoc claims the case "pins the cheap pre-check that keeps a repeat-bearing traversal from starting a transaction it has no use for", and nothing in the case observes session resolution or transaction start. The remaining assertion — that the native two-hop result is `{c, d}` — restates what four other cases already establish.

**Suggestion**: Either observe the claimed behaviour (assert that no transaction was begun on the graph session across the run, which is what the pre-check at `RepeatDeclineStrategy.java:178` buys), or rewrite the javadoc to say the case is a smoke test that the strategy tolerates a source with the translator already removed. The current name and javadoc promise a guarantee the assertions cannot detect.

### TS6 [suggestion] Two kill-switch conventions and two handles to the same configuration

**File**: `RepeatDeclineStrategyTest.java`, methods `setTranslatorEnabled` (line 270) and `withNonPolymorphicDefault` (line 281)

**Issue**: Three points, one root.

The class uses two restore conventions. `assertDeclinedAndEquals` reads the flag, flips it, and restores it in a `finally` (lines 240-258). `translatorAlreadyRemovedFromTheSource_needsNoVeto` (line 168), `handWrittenChainOfHopsStillTranslates` (line 197), and `translatorOff_leavesTheTraversalStrategyListUntouched` (line 214) set it and leave it set.

Neither convention leaks today, and it is worth recording why, because the track file's R9 note makes this flag look process-wide. `session.getConfiguration()` returns `storage.getContextConfiguration()` (`DatabaseSessionEmbedded.java:4231`), and `ContextConfiguration.setValue` writes a per-storage map, falling back to `GlobalConfiguration` only on read (`ContextConfiguration.java:73-95`). `DbTestBase` creates one database per test method and drops it in `@After`, so the storage that holds the flag dies with the test. No write reaches `GlobalConfiguration`, so nothing crosses into the rest of the shared `core` fork. The helper's restore is therefore ceremony and the three bare setters are safe — but a reader has to derive all of that to know which of the two conventions is the right one to copy.

The two configuration helpers also reach the same object by different routes. `setTranslatorEnabled` goes through the `DbTestBase.session` handle; `withNonPolymorphicDefault` goes through `((YTDBTransaction) graph.tx()).getDatabaseSession()`, after calling `tx.readWrite()`. Both land on the storage's `ContextConfiguration` because the graph opens the same database, so the effect is the same. The side effect is not: `readWrite()` opens a transaction the helper never closes, so the two non-polymorphic cases run inside a pre-opened read-write transaction and their polymorphic twins do not. The mode pair is meant to differ in one flag.

**Suggestion**: Use `session.getConfiguration()` in both helpers and drop the `tx.readWrite()` call and the `YTDBTransaction` cast. Pick one restore convention — either save-and-restore everywhere, or nowhere plus a one-line comment on `setTranslatorEnabled` recording that the flag lives on the per-test storage and dies with it.

### TS7 [suggestion] The shared result helper distorts three cases

**File**: `RepeatDeclineStrategyTest.java`, method `sortedStrings` (line 297)

**Issue**: Routing every case through a `List<String>` multiset produces three small distortions.

- The count cases compare against `List.of("2")` (lines 76, 83). A count is a long; comparing its `String.valueOf` form hides a type the assertion could state directly.
- The `Vertex` branch in `sortedStrings` is dead. Every traversal in the class ends in `.values("name")` or `.count()`, so no case returns an element.
- `repeatTimesElements_declinesAndMatchesNative_polymorphic` and its non-polymorphic twin (lines 100, 110) are named for elements and return property values.

**Suggestion**: Assert the count cases against `2L` directly and keep `sortedStrings` for the value cases; rename the two `Elements` cases to say `values`; drop the `Vertex` branch, or add the element-returning case the class javadoc's "element form" sentence implies and let that case exercise it.

### TS8 [suggestion] Fifth copy of the same translator test harness

**File**: `RepeatDeclineStrategyTest.java`, lines 56, 237, 270, 297, 306

**Issue**: `seedKnowsChain`, `assertDeclinedAndEquals`, `setTranslatorEnabled`, `sortedStrings`, and `countBoundarySteps` are a near-verbatim fork of `UnionTraversalEquivalenceTest`'s helpers (lines 580, 594, 657, 667, 685). The existing `assertEquivalent` already has a `Recognition.DECLINED` arm doing this class's job, plus the off-arm boundary check this copy dropped.

Five test classes in `translator/strategy` now carry their own `setTranslatorEnabled` and `countBoundarySteps` pair: `EdgeTraversalEquivalenceTest`, `PredicateTraversalEquivalenceTest`, `ProjectionEquivalenceTest`, `UnionTraversalEquivalenceTest`, and this one. Thirteen test classes reference the kill-switch constant. The drift is already visible — this copy improved on the parent by pinning an explicit expected multiset, and regressed on it by dropping the off-arm boundary assertion, and neither change reached the other four.

Reference-accuracy caveat: those two counts are grep over `core/src/test`, not PSI. `steroid_list_projects` confirms the IDE is open on this working tree, but `steroid_execute_code` timed out on the find-usages query, as it has throughout this branch. The counts are a floor for literal references; a reference through an alias or a helper indirection would not appear.

**Suggestion**: Extract the shared harness into a support class beside the existing `translator/step/BoundaryStepTestSupport`, or into a `GraphBaseTest` subclass for translator tests, and have the five classes call it. Track 9 is not the place to do the extraction across all five, so the smaller move for this step is to make this class extend the shared piece once it exists, and to record the intent in `## Surprises & Discoveries` so the follow-up has an owner.

## Evidence base
