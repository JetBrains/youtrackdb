<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 1, suggestion: 3}
index:
  - {id: TX2, sev: should-fix, loc: "RepeatDeclineStrategyTest.java:9837 (diff)", anchor: "### TX2 ", cert: C2, basis: "post-lock() strategies-reference propagation copies the veto marker onto never-vetoed descendants; no test pins the state or the 'every production read happens before that' claim it rests on"}
  - {id: TX3, sev: suggestion, loc: "RepeatDeclineStrategyTest.java:9896 (diff)", anchor: "### TX3 ", cert: C3, basis: "the only guard on the JVM-global TraversalStrategies.GlobalCache entry documents a clone-and-add mechanism step 8 deleted, so it reads as obsolete"}
  - {id: TX4, sev: suggestion, loc: "EmbeddedTranslatorKillSwitchWitnessTest.java:11899 (diff)", anchor: "### TX4 ", cert: C4, basis: "new test closes the process-wide cached engine keyed on \".\", the key ShadedJarSmokeTest also uses, and asserts nothing about the engine it acquired"}
  - {id: TX5, sev: suggestion, loc: "DefaultSQLFunctionFactoryTest.java:11566 (diff)", anchor: "### TX5 ", cert: C5, basis: "SQLFunctionMean carries a mutable accumulator; its per-execution isolation rests on class-style registration that no test pins for this function"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 4}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
  - {id: C12, verdict: SCOPE, anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### TX2 [should-fix] The post-`lock()` strategies-reference copy is the one path by which the veto reaches a traversal that was never vetoed, and nothing pins it

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java`, methods `theVeto_doesNotLeakToASiblingOrToARepeatFreeChild` (diff line 9837) and `childWithoutARepeat_stillTranslates` (diff line 9802)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java`, `isVetoed` (diff lines 2649-2662) and `apply` (diff lines 2617-2647); read by `GremlinToMatchStrategy.apply` (diff lines 1662-1672)

**Issue**: The veto marker lives on the traversal's `TraversalStrategies` reference. `DefaultTraversal.lock()` overwrites every non-root traversal's reference with its parent's, so once a vetoed root finishes compiling, every descendant reads as vetoed — including a repeat-free child that translated correctly during the strategy pass. The production Javadoc states this and rests the safety argument on one sentence: "every production read happens before that, during the strategy pass." No test measures the post-lock state, and no test pins the claim.

The suite drives *around* the situation rather than into it. `theVeto_doesNotLeakToASiblingOrToARepeatFreeChild` uses `TraversalHelper.applyTraversalRecursively` instead of `applyStrategies`, and its own Javadoc gives the reason: "after a full `applyStrategies` every descendant of a vetoed root reads as vetoed and the case could not discriminate." `childWithoutARepeat_stillTranslates` does call `root.applyStrategies()` and asserts the child is unvetoed, but its root carries no repeat, so it exercises a tree the propagation never touches.

**Evidence**: CONTRACT `RepeatDeclineStrategy.isVetoed(t)` answers only for the traversal `apply` marked, and says nothing about any other traversal in the tree. TEST TRACE — thread count 1; shared state is the `TraversalStrategies` reference held by root and children; the sibling case reads `isVetoed` mid-pass by construction, the child case reads it post-pass under an unvetoed root. VERDICT: EXERCISED pre-`lock()`, NOT TESTED post-`lock()`.

The propagation is measured, not inferred from the Javadoc. Decompiling `DefaultTraversal.lock()` from the fork (`io/youtrackdb/gremlin-core/3.8.1-67860f6-SNAPSHOT`) shows offsets 15-54: for a non-root traversal whose parent is not a `VertexProgramStep`, `lock()` calls `this.setStrategies(parentTraversal.getStrategies())`. The same method recurses (offsets 126-146) only under a `VertexProgramStep` parent, so each descendant acquires the parent reference at its own `lock()` rather than in one sweep — which makes *when* each read happens relative to each lock a per-shape ordering fact rather than a single global one.

**Why it matters**: The failure mode is a silent coverage regression rather than a wrong answer. A second read of `isVetoed` on an already-locked tree reports every descendant of a vetoed root as vetoed, and those descendants stop translating. `GremlinToMatchStrategy`'s own Javadoc names a second read as a production situation — "A traversal's strategy chain can be applied more than once" is why it carries an idempotency gate on the boundary step. A clone of a compiled tree, a re-application, or any future `isVetoed` caller reaches the post-lock state, and the whole suite stays green while translation quietly withdraws from correct shapes. That is the same class of order-dependent shared-state leak `TX1` was raised for, moved from the JVM-global strategy set onto a per-tree reference the framework re-points.

**Suggested test**:

```java
/**
 * Pins the post-lock() state of the veto marker. DefaultTraversal.lock() copies the parent's
 * strategies reference into every non-root traversal, so a repeat-free child of a vetoed root
 * reads as vetoed once compilation has finished. The first assertion records that as a measured
 * fact rather than a Javadoc claim; the second pins the consequence the claim is defending —
 * a re-compiled repeat-free child must still translate, or the veto costs coverage on a shape
 * it was never meant to touch.
 */
@Test
public void afterLock_theVetoPropagatesToDescendants_butARecompiledChildStillTranslates() {
  seedKnowsChain();
  setTranslatorEnabled(true);

  GraphTraversal<Object, String> child = __.<Object>V().out("knows").values("name");
  var vetoedRoot = graph.traversal().V().repeat(__.out()).times(2).map(child).asAdmin();
  vetoedRoot.applyStrategies();

  assertThat(RepeatDeclineStrategy.isVetoed(vetoedRoot))
      .as("precondition: the root carries the repeat, so the veto fired")
      .isTrue();
  // Measured, not assumed: lock() copies the parent reference down. If TinkerPop ever stops
  // doing so, this assertion is the notice.
  assertThat(RepeatDeclineStrategy.isVetoed(child.asAdmin()))
      .as("after lock(), a repeat-free child reads as vetoed because it now holds the root's "
          + "strategies reference")
      .isTrue();

  // The claim the production Javadoc rests on: every production read happens before lock().
  // Compile the same repeat-free shape fresh and assert it still translates, so a future reader
  // added after lock() shows up here as a translation loss rather than as silence.
  var recompiled = __.<Object>V().out("knows").values("name");
  var plainRoot = graph.traversal().V().map(recompiled).asAdmin();
  plainRoot.applyStrategies();
  assertThat(countBoundarySteps(recompiled.asAdmin()))
      .as("a repeat-free child compiled on its own account must still translate")
      .isEqualTo(1);
}
```

### TX3 [suggestion] The only guard on the JVM-global strategy cache documents a mechanism step 8 deleted

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java`, method `veto_leavesTheProcessWideStrategyCacheIntact` (diff line 9908; Javadoc at 9896-9906)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java`, `apply` (diff line 2635) and `VetoedStrategies` (diff lines 2676-2724)

**Issue**: The test's Javadoc describes the veto as editing a copy: "The veto edits a copy of the strategy list, never the process-wide one", with the failure mode named as "a veto that lost its `clone()`". Step 8 removed the clone. `apply` now does `traversal.setStrategies(new VetoedStrategies(strategies))` and the wrapper never copies or edits the list it wraps. The assertions are correct and they close `TX1` — the rationale above them points at code that no longer exists.

**Evidence**: TEST TRACE for `veto_leavesTheProcessWideStrategyCacheIntact` — thread count 1; shared state is the process-wide `TraversalStrategies.GlobalCache` entry for the graph class; the case reads that entry directly, asserts it is not a `VetoedStrategies`, asserts the translator is still registered in it, asserts the vetoed traversal's list is instance-for-instance identical to it, then compiles a second traversal on the same graph and asserts it still translates. VERDICT: EXERCISED. The finding is confined to the stated rationale, not the assertions.

**Why it matters**: This test is the whole defence of a JVM-global mutable set that every graph and every thread in the fork compiles against, and the damage a regression here does outlives the test — the cache entry is keyed by graph class while each test's database is not. A maintainer reconciling comment against code finds a Javadoc describing a `clone()` the class does not have, and the cheapest reading is that the test guards a retired mechanism. Deleting it removes the only assertion in the suite that the shared set survives a veto.

**Suggested test**: no new case. Restate the existing Javadoc against the carrier that ships, naming the two failure modes the assertions actually catch — a carrier that mutates the wrapped list instead of wrapping the reference, and a carrier installed onto the cache entry instead of onto the traversal. Both are reachable from a one-line edit to `apply`, and both are what the four assertions detect.

### TX4 [suggestion] The new embedded witness closes a process-wide cached engine it shares with another test class

**File**: `embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedTranslatorKillSwitchWitnessTest.java`, method `boundaryStepPresenceMatchesTheKillSwitchTheForkReceived` (diff line 11899)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/api/YourTracks.java`, `instance(String)`; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphFactory.java`, `ytdbInstance` (lines 113-129)

**Issue**: `YourTracks.instance(".")` returns a process-wide instance cached per resolved path — `ytdbInstance` is `storagePathYTDBMap.compute(path, …)`, returning the cached manager whenever it `isOpen()`. The test takes it in a try-with-resources, so it closes the shared entry on the way out. `ShadedJarSmokeTest` acquires and closes the same `"."` key, and the module's surefire fork runs both plus `EmbeddedGraphFeatureTest` in one JVM. The test asserts nothing about the engine it acquired.

**Evidence**: CONTRACT `YourTracks.instance(path)` hands out shared process state; ownership is implicit and closing is destructive to every other holder. TEST TRACE — thread count 1; shared state is `YTDBGraphFactory.storagePathYTDBMap` at key `resolvePath(".")`; the test's only assertions are the boundary-step count and the printed kill-switch value. VERDICT: WEAK — order-safe today by accident of the cache's self-healing rather than by anything the test establishes.

Two things keep it green now, and I checked both rather than assuming them. `compute` replaces a closed entry with a fresh manager, so a stale closed instance left by the sibling test does not poison this one. And `EmbeddedGraphFeatureTest` reaches its graph through `GraphFeatureWorld`, whose `makeTestDirectory` derives a per-test-class path, so it never contends for `"."`. Neither property is asserted anywhere.

**Why it matters**: A future user of `"."` whose lifetime spans this test — a class-scoped fixture, a `@BeforeClass` engine, a lazily cached World — has its engine closed underneath it by this test's `close()`, and this test still passes because its own assertions are already satisfied by then. Ownership of shared process state that nothing pins is the shape that surfaces as an unrelated class failing later in the fork, which is exactly what a single-JVM `core`-style run makes hard to attribute.

**Suggested test**: no new case, and no concurrent test is warranted — the witness measures one compilation on one thread. Give the test an engine it owns instead of one it borrows: `YourTracks.instance("target/killswitch-witness")`. `DatabaseType.MEMORY` makes the directory a cache key and nothing more, so the change costs nothing and removes the shared key. Add one precondition assertion that the acquired manager is open, so a run that inherited a half-closed entry fails on the cause rather than on the boundary count.

### TX5 [suggestion] `SQLFunctionMean` carries a mutable accumulator whose per-execution isolation is unpinned for this function

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/DefaultSQLFunctionFactoryTest.java` (diff line 11566, the `ALL_NAMES` fixture) and `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMeanTest.java` (diff line 11604)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMean.java`, fields `sum` and `total` (diff lines 3597-3598); `DefaultSQLFunctionFactory.registerDefaultFunctions` (diff line 3537)

**Issue**: `SQLFunctionMean` holds a running `Number sum` and `int total` across calls. What keeps two executions apart is the registration style: `register(SQLFunctionMean.NAME, SQLFunctionMean.class)` registers a class, and `SQLFunctionFactoryTemplate.createFunction` instantiates a fresh object per call for a class entry while returning one shared object for an instance entry. The same method registers `SQLFunctionCoalesce` two lines above in the instance style, so both spellings are in front of a reader. Nothing pins which one `mean` uses.

**Evidence**: CONTRACT each `mean()` aggregation reduces its own input, so its accumulator must not be shared with another execution. TEST TRACE — `SQLFunctionMeanTest` constructs `new SQLFunctionMean()` in `@Before`, so it exercises the accumulator without touching the factory; `DefaultSQLFunctionFactoryTest` adds `mean` to `ALL_NAMES`, which drives name resolution, `hasFunction`, the name set and the map-size pin. The instance-freshness contract is pinned once and generically, by `countRegisteredAsClassReturnsFreshInstance` over `SQLFunctionCount`, with `coalesceRegisteredAsInstanceReturnsSameObject` as its positive control. VERDICT: WEAK — the mechanism is covered, this function is not.

Re-registering `mean` in the instance style leaves every assertion in the diff green: the four `ALL_NAMES` loops only need the name to resolve, and the freshness case names `count`.

**Why it matters**: A shared `SQLFunctionMean` accumulates across every query in the process, so the second query to call `mean()` reads the first one's running sum and divisor. Held honestly: I did not establish that a cached execution plan reuses one `SQLFunction` instance across concurrent executions — `SQLFunctionCall.execute` resolves through `SQLEngine.getFunction` per call rather than caching the instance in the AST — so I am not claiming a live concurrent-corruption path. The gap is that the isolation guarantee for a newly added stateful aggregate rests on a registration style no test names, and `SQLFunctionAverage` has carried the identical field shape long enough that the pattern reads as safe by habit.

**Suggested test**: pin the property for the whole family rather than one more function, so the next aggregate inherits the guard.

```java
/**
 * Every instance-registered function must be stateless. The factory hands one shared object to
 * every caller of an instance entry and a fresh object to every caller of a class entry, so an
 * accumulator-bearing function registered in the instance style would let two executions share
 * one running total. Reflecting over the live production map covers each function added later
 * without a per-function case: mean, avg, sum and count all carry mutable fields and all must
 * therefore be class entries.
 */
@Test
public void instanceRegisteredFunctionsCarryNoMutableState() {
  for (var entry : factory.getFunctions().entrySet()) {
    if (entry.getValue() instanceof Class<?>) {
      continue; // class entries get a fresh instance per createFunction call
    }
    for (var field : entry.getValue().getClass().getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      assertTrue(
          "Instance-registered function '" + entry.getKey() + "' carries the mutable field '"
              + field.getName() + "'; a shared instance would let two executions share it. "
              + "Register the class instead.",
          java.lang.reflect.Modifier.isFinal(field.getModifiers()));
    }
  }
}

/** mean is a running aggregate, so the factory must hand each execution its own instance. */
@Test
public void meanRegisteredAsClassReturnsFreshInstance() {
  var a = factory.createFunction(SQLFunctionMean.NAME, session);
  var b = factory.createFunction(SQLFunctionMean.NAME, session);
  assertNotSame("mean carries a running sum and divisor, so it must not be shared", a, b);
}
```

## Evidence base

#### C1 TX1 — the repeat veto edits a clone of the JVM-global `TraversalStrategies` set and nothing asserts the global set survived — REFUTED (closed by step 8 plus its test)

The step-1 finding no longer describes the shipped mechanism, and the invariant it asked for is now asserted directly.

Step 8 (`55da40dcdd`, "Carry the repeat veto without touching the strategy list") replaced `traversal.setStrategies(strategies.clone().addStrategies(Veto.instance()))` with `traversal.setStrategies(new VetoedStrategies(strategies))`. The wrapper forwards `iterator()`, `toList()`, `getStrategy`, `addStrategies`, `removeStrategies` and `toString` to the delegate and carries the veto in its own type, so no clone is taken, no element is added, and `TraversalStrategies.sortStrategies` never runs.

`RepeatDeclineStrategyTest.veto_leavesTheProcessWideStrategyCacheIntact` (diff 9908-9941) asserts the missing half of TX1 on four axes: the `TraversalStrategies.GlobalCache` entry for the graph class is not a `VetoedStrategies`, that entry still resolves `GremlinToMatchStrategy`, the vetoed traversal's list is instance-for-instance identical to the cache entry (`assertSameStrategiesInOrder`, identity rather than equality because `AbstractTraversalStrategy.equals` compares only the runtime class), and a second traversal compiled on the same graph after the veto still translates. `translatorOff_leavesTheStrategyListAndRequirementsUntouched` (10000-10027) adds the ordering half TX1 implied: a marked traversal's list must be the same objects in the same positions as an unmarked control's, which is the assertion a clone-and-re-sort carrier fails.

Two collateral guards were added at the same time. `theVetoCarrier_forwardsEveryOperationToTheListItWraps` drives the wrapper's mutators over a detached `clone()` and says why in its Javadoc — "exercising them through a live traversal would edit the JVM-global `GlobalCache` instance every other test in this fork compiles against." `applyingTheVetoTwice_wrapsTheStrategiesReferenceOnce` pins idempotency by reference identity. TX1 is closed; TX3 addresses the one residue, which is documentation rather than coverage.

#### C2 The veto marker reaches never-vetoed descendants at `lock()`, and no test pins it — CONFIRMED

Bytecode of `DefaultTraversal.lock()` offsets 15-54 shows `this.setStrategies(parentTraversal.getStrategies())` for any non-root traversal outside a `VertexProgramStep`; the suite's two child-scope cases either avoid the post-lock state by construction or exercise an unvetoed root. See `TX2`.

#### C3 The cache guard's Javadoc names a `clone()` the carrier no longer has — CONFIRMED

`veto_leavesTheProcessWideStrategyCacheIntact`'s Javadoc says the veto "edits a copy of the strategy list" and names "a veto that lost its `clone()`" as the failure mode; `apply` wraps the reference and takes no copy. See `TX3`.

#### C4 The embedded witness closes the `"."`-keyed process-wide engine it shares with `ShadedJarSmokeTest` — CONFIRMED

`YTDBGraphFactory.ytdbInstance` caches per resolved path in `storagePathYTDBMap`; both classes acquire `"."` in a try-with-resources and close it, in one surefire fork, with no assertion about ownership. See `TX4`.

#### C5 `mean`'s per-execution accumulator isolation is unpinned for this function — CONFIRMED

`SQLFunctionMean` holds `sum` and `total`; isolation comes from class-style registration, and the freshness contract is pinned only over `SQLFunctionCount`. See `TX5`.

#### C6 `VetoedStrategies.addStrategies` / `removeStrategies` forward verbatim to a delegate that in production is the shared `GlobalCache` singleton, so the wrapper adds a mutation path onto process-wide state — REFUTED

The premise holds and the conclusion does not. `GraphTraversalSource(Graph)` takes its strategies straight from `TraversalStrategies.GlobalCache.getStrategies(graph.getClass())` with no copy, and `DefaultTraversal` inherits that reference, so `traversal.getStrategies()` on a root really is the shared singleton — which is why the production Javadoc warns that the wrapper "must never mutate it on its own account."

The wrapper adds no exposure, because the two mutators behave identically wrapped and unwrapped. Unwrapped, a caller doing `traversal.getStrategies().addStrategies(X)` mutates the singleton. Wrapped, `VetoedStrategies.addStrategies` calls `delegate.addStrategies(X)` and mutates the same singleton. The delta is zero, so any hazard here predates the veto and belongs to TinkerPop's cache design rather than to this track. The clone-then-mutate idiom is likewise preserved: `VetoedStrategies.clone()` returns `new VetoedStrategies(delegate.clone())` and `DefaultTraversalStrategies.clone()` copies its `LinkedHashSet`, so `getStrategies().clone().removeStrategies(…)` mutates a copy exactly as it did before. No finding.

#### C7 `translatorAlreadyRemovedFromTheSource_needsNoVeto` reaches TinkerPop's in-place `removeStrategies` through `withoutStrategies` and could strip `GremlinToMatchStrategy` from the shared cache for the rest of the fork — REFUTED

The test's own Javadoc flags this path as "the one path in the class that reaches TinkerPop's in-place `removeStrategies` through `withoutStrategies`", and it asserts only the boundary count and the native result — so a global strip would leave the case green while silencing the translator for every later class. That made it worth measuring rather than reasoning about.

Bytecode says the removal lands on a copy. `TraversalSource.withoutStrategies` (default method, offsets 0-33) calls `clone()` first, then `removeStrategies` on the clone's strategies. `GraphTraversalSource.clone()` (offsets 8-18) assigns `clone.strategies = this.strategies.clone()`, and `DefaultTraversalStrategies.clone()` rebuilds its `LinkedHashSet`. The shared cache entry is untouched. No finding, and the test's Javadoc note is accurate about the API it reaches while the consequence it hints at does not follow.

#### C8 `ByModulatorPresence.WHERE` is a `private static final MatchWhereBuilder` shared by every thread compiling a traversal, and the "the builder is stateless" comment is load-bearing and untested — REFUTED

The comment at diff line 111 asserts statelessness without a test behind it, and the class is reached from every recogniser that consumes a `by(...)` modulator, so concurrent compilations share the object.

`MatchWhereBuilder` declares no instance fields. Every method in it — `op`, `isDefined`, `typeIn`, `and`, `not`, `wrap`, the `contains` family — allocates its AST nodes into locals and returns them, including the new `typeIn` this track adds (diff 3503-3516, which builds `names`, a fresh `SQLInCondition` and a fresh operator per call). Sharing the instance therefore publishes nothing. The same pattern is already established for `GremlinStepWalker.WHERE` and `GremlinPredicateAdapter.WHERE`. No finding, and no concurrent test is warranted for a type with no state to race on.

#### C9 `GremlinPlanCacheTest` seeds a process-wide plan cache and never clears it, so a later class observes a different cache — REFUTED

`cachedGuardedPlan_isNotServedToAnotherLiteralType` (diff 4623-4647) deliberately compiles a guarded plan, asserts it is cached, then compiles a second shape to prove it is not served the first plan. It clears nothing afterwards, which under a process-wide cache would change what a later class sees.

The cache is per-database, not process-wide. `GremlinPlanCache.instance(db)` returns `db.getSharedContext().getGremlinPlanCache()`, so the cache belongs to the shared context of one database. `DbTestBase.@Before` calls `createDatabase(dbType)` and `@After` calls `dropDatabase()` plus `youTrackDB.close()`, so every test method gets a fresh database, a fresh shared context and a fresh cache. Nothing survives the method that seeded it. No finding; the dispatch premise that the cache is a process-wide singleton does not hold.

#### C10 The kill-switch `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` is a global configuration written by many classes with inconsistent restore, per the step-1 `TS6` finding — REFUTED for this tree

`TS6` reported save/restore in the helper, bare sets in three tests, and two different handles to the same configuration. Two handles is the part that matters here: `GlobalConfiguration.<KEY>.setValue(v)` writes JVM-global state that outlives the class, while `session.getConfiguration().setValue(KEY, v)` writes storage-scoped state that does not.

Every write in this diff uses the storage-scoped handle. A grep over all added lines for `GlobalConfiguration\.[A-Z_]+\.setValue` returns nothing; each of the 40-odd write sites reads `<handle>.setValue(GlobalConfiguration.KEY, value)` where the handle is `session.getConfiguration()` or a local bound to it. `DatabaseSessionEmbedded.getConfiguration()` returns `storage.getContextConfiguration()`, and `DbTestBase` creates and drops the storage per test method, so the write cannot reach a later method or a later class.

The bare-set sites that remain are the nine in `RepeatDeclineStrategyTest` (diff 9763, 9804, 9839, 9875, 9910, 9958, 10002, 10162, 10198, plus `setPolymorphicByDefault` at 10224). Its `setTranslatorEnabled` Javadoc states the reason for not restoring — "the configuration belongs to the storage `DbTestBase` creates for this test method and drops in its `@After`, so the write cannot reach `GlobalConfiguration` or any later test in the same fork" — and that statement checks out against `getConfiguration()`'s body. Every other class in the diff wraps its flips in a `try` / `finally` that restores the original (`withTranslator`, `withTranslatorOn` / `withTranslatorOff`, `withTranslatorRestored`), which is belt over an already-scoped write. No finding.

#### C11 Step 8 moved the veto marker onto the traversal's `getSideEffects()`, so the review must check `profile()` / `cap()` exposure of the side-effect key, side-effect clone and merge semantics across children, and non-leakage to siblings — REFUTED (dispatch premise; scope correction)

The dispatch brief describes a mechanism the track rejected. Step 8's commit message and the class Javadoc both record `getSideEffects()` as the first candidate, measured and discarded on two grounds: any side-effect key flips `getTraverserRequirements()` from `[BULK, OBJECT]` to `[BULK, OBJECT, SIDE_EFFECTS]` and swaps the traverser generator, changing the native execution path on the translator-off arm; and a traversal shares one `TraversalSideEffects` instance with its direct children, so a boolean key at the root would veto every sibling. The shipped carrier is `VetoedStrategies`, a view over the traversal's own `TraversalStrategies` reference.

Two of the three named hazards therefore have no subject — there is no side-effect key to expose through `profile()` or `cap()`, and no side-effect clone or merge to reason about. The third, non-leakage to siblings, transfers to the new carrier and is covered: `theVeto_doesNotLeakToASiblingOrToARepeatFreeChild` asserts the repeat-bearing child and the root are vetoed while the repeat-free sibling is not, and it asserts the rejected carrier's premise on the way past — `root.getSideEffects()` is the same instance as both children's, which is the measurement that killed the side-effects channel. The carrier-specific analogues of the other two hazards are the process-wide cache (covered, `C1`) and the post-`lock()` reference copy (not covered, `TX2` / `C2`).

#### C12 Multi-threaded coverage sweep — SCOPE

The track adds no multi-threaded test, and none is warranted. A grep over every added line for `new Thread`, `Executors`, `ExecutorService`, `CountDownLatch`, `CyclicBarrier`, `Semaphore`, `Phaser`, `CompletableFuture`, `parallelStream`, `ConcurrentTestHelper`, `Thread.sleep`, `synchronized`, `volatile`, `ThreadLocal` and the atomics returns two hits, both in `RepeatDeclineStrategyTest`: the `java.util.concurrent.atomic.AtomicBoolean` import and the `armed` flag in `aThrowInsideTheVeto_declinesTheVetoInsteadOfAbortingCompilation`. That flag arms a throwing `getStrategies()` override on a single thread; an `AtomicBoolean` is heavier than the situation needs and costs nothing, so it produces no finding.

Nothing the track adds is concurrently mutable state a test could usefully contend on. The translator's per-compilation state lives in `WalkerContext` and `SubTraversalPredicateAdapter`, both allocated per walk. The two new static holders are stateless (`ByModulatorPresence.WHERE`, `C8`) or immutable (`GremlinStepWalker.POST_UNION_RECOGNISERS`, `POST_CARDINALITY_RECOGNISERS`, `NUMERIC_TYPE_NAMES`). `RepeatDeclineStrategy` is a stateless singleton whose one write targets the traversal being compiled. The one genuine cross-thread hazard in the area — an in-place edit of the shared strategy set raising `ConcurrentModificationException` in every thread holding `applyStrategies`' fail-fast iterator — is closed structurally by the wrapper rather than behaviourally, and the structural guard is asserted by `C1`'s identity check on the cache entry, which is the reliable way to pin it. A racing reproduction of that CME would be non-deterministic and would add nothing the identity assertion does not already give.

Three findings in this file are single-threaded shared-state hygiene (`TX2`, `TX3`, `TX4`) and one is per-execution state isolation (`TX5`), which is the shape this dimension owns in a suite that runs many classes in one JVM.
