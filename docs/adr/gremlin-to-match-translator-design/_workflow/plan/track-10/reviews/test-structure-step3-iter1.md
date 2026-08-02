<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: TS6, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:340, anchor: "### TS6 ", cert: n/a, basis: "the comment records the track's index-usage answer as 'pins where the index step sits', but two flat-string contains() on one plan rendering establish co-existence, not nesting"}
  - {id: TS7, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchStepUnitTest.java:2246, anchor: "### TS7 ", cert: n/a, basis: "newNoOpStep is a body-identical duplicate of the existing createEmptySubStep helper under a second name, outside the class's own helper section"}
  - {id: TS8, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchStepUnitTest.java:2144, anchor: "### TS8 ", cert: n/a, basis: "hand-rolled try/fail/catch guards the argument construction as well as the call, and introduces the only two catch blocks in a class whose expected-exception idiom is @Test(expected=)"}
  - {id: TS9, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/MatchStatementExecutionTest.java:87, anchor: "### TS9 ", cert: n/a, basis: "per-method fixture opens 1000 transactions for 1000 vertices where the sibling heavy class builds the same fixture in one executeInTx; three new tests pay it"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS6 [should-fix] The index-usage comment claims containment the assertion cannot check

`indexedQuerySurfacesPlanWithFetchFromIndexStep` carries the track's answer to the item-3 index-usage
question in a four-line comment at `YTDBQueryMetricsStrategyTest.java:340`:

> This is the only Gremlin-level index-usage assertion in the tree, so it pins where the index step
> sits, not just that one exists somewhere. […] an index step reachable only outside it would mean
> the prefetch itself scans the class.

The assertion under it is two independent `contains` calls against one flat string (`:344`):

```java
assertThat(listener.planPrettyInCallback)
    .as("the prefetched alias is read through the index, not by scanning the class")
    .contains("+ PREFETCH")
    .contains("+ FETCH FROM INDEX");
```

`planPrettyInCallback` is `executionPlan.prettyPrint(0, 2)` (`:1613`) — the whole plan rendered as one
string. Two substring checks on it establish that both tokens appear somewhere, in any order, at any
nesting depth. They cannot distinguish an index fetch inside the `+ PREFETCH` block from one rendered
above or below it, which is exactly the distinction the comment says the test pins and the
`.as()` label repeats. The negative sibling at `:301` is sound by construction — its
`.doesNotContain("+ FETCH FROM INDEX")` rules out the token everywhere, which implies ruling it out
inside the sub-plan — so only the positive half overclaims.

This matters more than an ordinary comment drift because `## Validation and Acceptance` names this
test as the place the index-usage question is "answered explicitly", and the comment is the written
form of that answer.

**Failure scenario.** A later translator change — Track 9's list-shaping terminators are the nearest
candidate — prepends an index-backed step to the same plan while the prefetch degrades to a class
scan. The rendering then reads `+ FETCH FROM INDEX` at top level and `+ PREFETCH` / `+ FETCH FROM
CLASS` below it. Both `contains` calls still hold, `containsStepOfType(…, FetchFromIndexStep.class)`
at `:337` also still holds because it walks the whole tree, and the suite reports that the prefetched
alias reads through the index while it is scanning the class.

**Suggestion.** The override this step just added makes the structural check available, and
`containsStepOfType` in this class already takes a `List<ExecutionStep>`:

```java
var prefetch = listener.planStepsInCallback.stream()
    .filter(MatchPrefetchStep.class::isInstance)
    .findFirst()
    .orElseThrow();
assertThat(containsStepOfType(prefetch.getSubSteps(), FetchFromIndexStep.class))
    .as("the prefetch sub-plan is what reads through the index")
    .isTrue();
```

Keep the string assertions if the rendered form is worth pinning, but let the structural one carry
the claim the comment makes.

### TS7 [should-fix] `newNoOpStep` duplicates the existing `createEmptySubStep` helper

`MatchStepUnitTest.java:2246` adds a stub-step factory whose body is identical to
`createEmptySubStep` at `:5258` — same `internalStart` returning `ExecutionStream.empty()`, same
`prettyPrint` returning `""`, same `copy` returning `this`. The class now holds two names for one
thing, and the new one sits mid-file among the tests rather than in the `// -- Helper methods --`
section at `:5175` where the older one lives with `createCommandContext`, `createTestPatternEdge`, and
the rest.

`createEmptySubStep` is an instance method and every `@Test` here is an instance method, so the six
new tests can call it directly; nothing forced the second copy.

**Failure scenario.** This track is already strengthening step fixtures around plan-close leaks —
step 2 added close-tracking to the boundary suites. The natural next move is to teach the shared stub
to record `close()` so a leak assertion can witness it. A maintainer finds `createEmptySubStep` under
the helper section, extends it there, and the six sub-step introspection tests keep calling the
silent duplicate at `:2246`. They then exercise a stub with different semantics from every other stub
in the class, and cannot witness the leak the strengthened stub was built to catch.

**Suggestion.** Delete `newNoOpStep` and call `createEmptySubStep(ctx)` in the six new tests. If a
static form is genuinely needed, make `createEmptySubStep` static instead and move the single call at
`:1795` over, so the class keeps one stub factory in one place.

### TS8 [suggestion] The hand-rolled `try`/`fail`/`catch` guards the argument construction too

The two immutability tests (`MatchStepUnitTest.java:2144` and `:2217`) assert rejection like this:

```java
try {
  subSteps.add(newNoOpStep(ctx));
  fail("the returned sub-step list should reject mutation");
} catch (UnsupportedOperationException expected) {
  // The snapshot is immutable, which is the point of the assertion.
}
```

Two problems, both cheap to fix together. The stub construction happens inside the guarded region, so
the catch covers more than the call under test. And these are the only two `catch` blocks in a
5443-line class that expresses expected exceptions with `@Test(expected = …)` in ten other places;
`org.junit.Assert.assertThrows` is available on JUnit 4.13.2 and is already imported in 99 test files
under `core/src/test/java`.

**Failure scenario.** `AbstractExecutionStep`'s constructor gains a guard that throws
`UnsupportedOperationException` — a profiling or registration precondition, say. The call to
`newNoOpStep(ctx)` then throws before `subSteps.add` is ever reached, the catch swallows it, `fail`
never runs, and both tests report green while asserting nothing about the snapshot's immutability.

**Suggestion.** Hoist the argument and use `assertThrows`, which narrows the guarded region to the
mutating call and matches the surrounding tree:

```java
var extra = createEmptySubStep(ctx);
assertThrows(
    "the returned sub-step list should reject mutation",
    UnsupportedOperationException.class,
    () -> subSteps.add(extra));
```

### TS9 [suggestion] The per-method fixture opens 1000 transactions where one would do

The placement question the dispatch raised resolves in the implementer's favour, for a reason they
did not give. `MatchStatementExecutionTest` already owns five `IndexedVertex` MATCH tests
(`:1389`–`:1449`) and builds that fixture in `beforeTest`, so the above-threshold test fits the
class's existing scope, and CLAUDE.md prefers extending an existing class over minting a new home.
`MatchStatementExecutionHeavyTest` was extracted for PIT minion flakiness, per its class Javadoc, and
no PIT configuration survives anywhere in the build (`grep pitest` over every `pom.xml` and workflow
returns nothing), so moving tests there today buys nothing. Splitting the below-threshold and
above-threshold pair across two classes would also cost the side-by-side reading that makes the two
Javadoc blocks work as one explanation.

The 32-minute runtime is worth attacking at the fixture rather than by moving tests.
`initEdgeIndexTest` at `:87` opens and commits a transaction per vertex:

```java
for (var i = 0; i < nodes; i++) {
  session.begin();
  var doc = session.newVertex("IndexedVertex");
  doc.setProperty("uid", i);
  session.commit();
}
```

`MatchStatementExecutionHeavyTest:36` builds the byte-identical fixture inside a single
`session.executeInTx`, so the shape is already proven in the tree.

**Failure scenario.** `beforeTest` runs per method, so all 156 methods pay 1000 commits each — about
12 s per test, and the three tests this step adds carry roughly 37 s of it. Plan-of-Work item 4 is
about to put `./mvnw -pl core test` behind a CI gate on every push, which makes that cost recurrent
rather than local, and every future test added to this class pays the same toll.

**Suggestion.** Fold the loop into one `session.executeInTx`, mirroring `MatchStatementExecutionHeavyTest:36`.
This changes commit granularity during setup, so it needs a full-class run to confirm no test in the
156 depends on the per-vertex commit boundaries. Out of scope for this step; worth a rostered entry
if item 4 lands the CI gate.

## Evidence base
