<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: BG1, sev: should-fix, loc: RepeatDeclineStrategy.java:77, anchor: "### BG1 ", cert: C1, basis: "root-only veto misses a repeat inside a source-bound sub-traversal; the child still translates and the hang returns"}
  - {id: BG2, sev: should-fix, loc: RepeatDeclineStrategy.java:73, anchor: "### BG2 ", cert: C2, basis: "no throw-safety net on a globally-registered strategy that now runs ahead of the netted one; kill-switch does not gate it"}
  - {id: BG3, sev: suggestion, loc: GremlinToMatchStrategy.java:250, anchor: "### BG3 ", cert: C3, basis: "new gate also fires on child traversals, silently narrowing translation of mid-traversal V() sub-traversals"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 5}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] The veto covers the root traversal only, while the translator gates on each traversal's own strategy list

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (lines 77, 92)

**Issue**: `apply` returns early for any non-root traversal and writes the vetoed strategy list onto the root only. `GremlinToMatchStrategy` is invoked on every traversal in the tree, and its new veto check reads *that traversal's* list. A `repeat(...)` that sits inside a sub-traversal carrying its own copy of the graph's strategies is therefore still translated after `RepeatUnrollStrategy` has flattened it — the non-termination this step exists to remove, one nesting level down.

The sub-traversals that reach this state are the ones built from the traversal source rather than from `__`:

```java
g.V().union(g.V().repeat(__.out()).times(8), __.out())
```

The root scan finds the `RepeatStep` and vetoes the root, so the root declines. The child was created by `GraphTraversalSource.V()`, so it carries a strategy list that still contains `GremlinToMatchStrategy`, and every one of the translator's gates passes for it.

**Evidence**: the chain, each link read rather than assumed (see `#### C1`):

1. `DefaultTraversal.applyStrategies` (gremlin-core 3.8.1 fork) runs `TraversalHelper.applyTraversalRecursively(strategy::apply, this)` once per strategy, so `apply` reaches the root and every descendant traversal.
2. `RepeatDeclineStrategy.apply:77` returns for `!isRoot()`, and the only mutation, `:92`, targets `traversal` — the root.
3. `TraversalParent.integrateChild` sets the child's parent, side effects and GValue manager, and never touches its strategies. A child keeps whatever list it was constructed with.
4. `DefaultTraversal.getGraph()` walks to the parent when its own graph is null or `EmptyGraph`, so `resolveSessionIfEnabled` returns a live session for children as well as roots.
5. `GremlinToMatchStrategy.hasVertexGraphStart` (`:380-383`) accepts any `GraphStep` whose `returnsVertex()` is true and does not test `isStartStep()`, so a mid-traversal `V()` passes.
6. `RepeatUnrollStrategy` is applied through the same recursion, so the child's `repeat` is unrolled into chained `VertexStep`s before the translator sees it.

**Refutation considered**: anonymous children are safe, but for the wrong reason. `__.repeat(__.out()).times(8)` is constructed by `DefaultTraversal()`, whose strategy list is the `EmptyGraph` global cache and never contains `GremlinToMatchStrategy` — so the child declines at the new gate in `GremlinToMatchStrategy:250`, not because `RepeatDeclineStrategy` reached it. The union test in `RepeatDeclineStrategyTest` uses the anonymous form and so passes through that accident rather than through the veto. I also checked whether the translator declines source-bound children for an unrelated reason and found none: the session gate, the start-step gate and the idempotency scan all pass.

Reachability is the reason this is not a blocker. Every `repeat` shape in the Cucumber suite and in the equivalence suites uses `__`, so the measured stall is fixed. The gap opens on a legal authoring style that `union`, `where`, `filter`, `local`, `not`, `choose` and `map` all accept, and its symptom is a hang rather than a wrong answer.

**Suggestion**: drop the `isRoot()` gate and let each traversal veto its own list. The recursion in `applyStrategies` already visits every descendant, so `hasStepOfAssignableClassRecursively` at each level vetoes exactly the traversals that both contain a `repeat` and carry the translator; anonymous children still return at the strategy-list check without resolving a session. Keep the recursive form of the scan rather than the flat `hasStepOfAssignableClass`, or a root whose `repeat` lives in an anonymous child stops being vetoed. Pushing the vetoed list down the subtree with `applyTraversalRecursively(t -> t.setStrategies(vetoed), traversal)` also closes the hole, but it hands children the root's `OptionsStrategy`, which `YTDBStrategyUtil.getConfigValue` reads off `traversal.getStrategies()` — a behaviour change beyond the veto. Add a case with a source-bound child to `RepeatDeclineStrategyTest` so the distinction is pinned.

### BG2 [should-fix] `RepeatDeclineStrategy.apply` runs without a throw-safety net ahead of the strategy that has one

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (lines 73-93)

**Issue**: the strategy is registered globally for `YTDBGraphEmbedded` and runs on every Gremlin compilation. On any traversal that carries a `repeat`, it calls `GremlinToMatchStrategy.resolveSessionIfEnabled`, which reaches `YTDBStrategyUtil.resolveYtdbSession` and `tx.readWrite()`. A `RuntimeException` from that call propagates out of `applyStrategies()` and aborts compilation. `GremlinToMatchStrategy.apply` catches the same exception from the same call and degrades to a decline (`:224-232`), and its class Javadoc states that rule as an invariant: any `RuntimeException` in `apply` must decline rather than abort compilation.

The kill-switch offers no protection. `resolveYtdbSession` opens the transaction before `resolveSessionIfEnabled` reads `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED`, so a `repeat`-bearing traversal takes the same path with the translator off.

**Evidence**: `YTDBStrategyUtil.resolveYtdbSession` calls `tx.readWrite()` unconditionally once the graph is a `YTDBGraph`. `YTDBTransaction` inherits TinkerPop's `AbstractTransaction.readWrite()`, which dispatches through `readWriteConsumerInternal`; under `Transaction.READ_WRITE_BEHAVIOR.MANUAL` — a supported, user-selectable mode wired at `YTDBTransaction:113-118` — that consumer throws `IllegalStateException` when no transaction is open. `doOpen()` (`:154-165`) is the other throw site: it calls `graph.getUnderlyingDatabaseSession()` and `activeSession.begin()`, both of which fail on a closed session.

**Refutation considered**: I checked whether the delta is observable. Under `MANUAL` the native pipeline throws the same `IllegalStateException` from the same helper when the traversal iterates, so a user calling `iterate()` sees one exception either way and TinkerPop's transaction-behaviour tests still pass. What changes is the throw site — `applyStrategies()` rather than step execution — and the loss of the decline path for every other `RuntimeException` the session resolution can raise. That is a narrower blast radius than the finding first suggested, which is why this is not a blocker; it is still a documented invariant the diff breaks on an always-on path, and the repair is a `try`/`catch (RuntimeException) { return; }` around the body.

**Suggestion**: wrap the body of `apply` in the same net the sibling strategy uses — catch `RuntimeException`, return without mutating, and leave `Error` and `AssertionError` to propagate. Declining the veto is safe: the translator's own gates then decide, exactly as they did before this step.

### BG3 [suggestion] The new translator gate also fires on child traversals and silently narrows translation

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategy.java` (line 250)

**Issue**: `traversal.getStrategies().getStrategy(GremlinToMatchStrategy.class).isEmpty()` is written as a per-traversal veto check, but it reads a list that child traversals do not share with their root. An anonymous child's list is the `EmptyGraph` global cache, which never contains the strategy, so every anonymous sub-traversal now declines at this line. Before the diff, a sub-traversal starting with a mid-traversal `V()` passed the session gate (`getGraph()` walks to the parent) and the start-step gate, and could be translated in its own right — for example the children of `g.union(__.V(), __.V().out("knows"))`, the shape `UnionTraversalEquivalenceTest:425` exercises.

**Evidence**: `DefaultTraversal.getStrategies()` returns its own field with no parent walk, while `DefaultTraversal.getGraph()` does walk to the parent. The two disagree for every child, and the gate keys on the one that does not walk. `TraversalParent.integrateChild` never copies strategies down, so the disagreement is the normal case rather than an edge case.

**Refutation considered**: declining is semantically safe, so no wrong answer follows — the cost is that a sub-traversal the translator used to fold now runs natively. `startPositionUnion_declines` asserts a decline on the root's step list and would not observe a change in the child, so no existing test pins the old behaviour. I could not construct a case where child translation was load-bearing for correctness.

**Suggestion**: decide which behaviour is intended and state it at the gate. If the translator is meant to work at root level only, add an explicit `traversal.isRoot()` decline with a comment, which makes the intent legible and stops the strategy-list read from standing in for it. If child translation is meant to keep working, the veto has to be expressed in something both parent and child can see — which is the same asymmetry BG1 turns on, so the two are best fixed together.

## Evidence base

#### C1 BG1 — a `repeat` inside a source-bound sub-traversal survives the veto — CONFIRMED

Survived the refutation check. `applyTraversalRecursively` reaches every descendant (verified in the gremlin-core 3.8.1 fork bytecode of `DefaultTraversal.applyStrategies` and `TraversalHelper.applyTraversalRecursively`); `integrateChild` leaves the child's strategy list alone; `getGraph()` walks to the parent so the session gate passes; `hasVertexGraphStart` accepts a mid-traversal `GraphStep`. Anonymous children are covered only by the accident described in BG3.

#### C2 BG2 — the new strategy aborts compilation where the old path declined — CONFIRMED

Survived the refutation check, with the observable delta narrowed to the throw site under `MANUAL` read-write behaviour and to the loss of the decline path for other `RuntimeException`s from session resolution.

#### C3 BG3 — the strategy-list gate narrows translation for child traversals — CONFIRMED

Survived the refutation check. Semantically safe, undocumented, and untested; recorded because BG1's repair depends on the same asymmetry.

#### C4 Removing the translator leaves three strategies naming an absent class in `applyPrior()` — REFUTED

`DefaultTraversalStrategies.removeStrategies` re-runs `TraversalStrategies.sortStrategies` on the reduced set, and `YTDBGraphStepStrategy`, `YTDBGraphCountStrategy` and `YTDBGraphMatchStepStrategy` all name `GremlinToMatchStrategy` in their own `applyPrior()`. The claim was that the topological sort would then either throw or mis-order the survivors.

Checked the fork's `TraversalStrategies.sortStrategies` bytecode. Both dependency-edge lambdas (`lambda$sortStrategies$2` and `lambda$sortStrategies$3`) guard the `MultiMap.put` with `strategyClasses.contains(...)` against the set of classes actually present, so an edge pointing at a removed class is dropped rather than recorded. No throw, no cycle, no reordering of the remaining strategies.

Verdict: REFUTED — not a real bug.

#### C5 The clone-and-replace leaks the veto to sibling traversals from the same source — REFUTED

`RepeatDeclineStrategy:92` calls `strategies.clone().removeStrategies(...)`. The claim was that `clone()` might be shallow enough that `removeStrategies` mutates the set the traversal source shares, so one `repeat` query would disable the translator for every later traversal spawned from the same `g`.

Checked `DefaultTraversalStrategies.clone` in the fork: it copies the `traversalStrategies` set into a fresh `LinkedHashSet` before returning, so `removeStrategies` on the clone cannot reach the original. `setStrategies` then rebinds only this traversal's field.

Verdict: REFUTED — the code does the right thing, and the Javadoc's account of why is accurate.

#### C6 The recursive scan misses `until(...)`, `by(...)` modulators, or sub-traversals reached through `TraversalParent` — REFUTED

The claim was that `TraversalHelper.hasStepOfAssignableClassRecursively(RepeatStep.class, traversal)` covers too little of the step tree to satisfy the all-or-nothing decline.

Checked the two-argument overload in the fork's bytecode. It delegates to the `Scope`-taking form with a null scope, and a null scope makes both the local-children branch and the global-children branch unconditional, so the recursion covers `by(...)` and `where(...)` modulators (local) as well as `union`, `branch`, `choose`, `local` and `repeat` bodies (global). The `until(...)` and `emit(...)` forms need no special handling at all — they are properties of a `RepeatStep`, which the top-level `getSteps()` pass already matches by `isAssignableFrom`. Recursion depth follows the step tree, which is finite and acyclic; every other TinkerPop strategy recurses over the same structure.

Verdict: REFUTED — the scan is the right shape for the constraint. What is missing is coverage, not reach: no test drives a `repeat` inside a `by(...)` modulator, which is a test-suite observation rather than a defect.

#### C7 The kill-switch-off arm is no longer left exactly as it was — REFUTED

The claim was that the new strategy perturbs the control arm, either by editing the strategy list or by opening a transaction the off arm would not otherwise open.

Read the gate order at `RepeatDeclineStrategy:77-92`. With the switch off, `resolveSessionIfEnabled` returns null and `setStrategies` is never reached, so the list is untouched — which `translatorOff_leavesTheTraversalStrategyListUntouched` also pins. On the transaction question: `resolveYtdbSession` does call `tx.readWrite()` before the flag is read, but `GremlinToMatchStrategy.applyOrDecline:241` already made that call unconditionally for every traversal on the off arm, and both calls happen inside the same `applyStrategies()` invocation with only TinkerPop's own optimization strategies in between. No new transaction is opened and none is opened earlier in any observable sense.

Verdict: REFUTED — constraint 1 holds. (The same call is still the throw site BG2 records; that is a separate concern from whether the control arm moves.)

#### C8 The removal cannot take effect, or raises `ConcurrentModificationException` — REFUTED

The claim was that replacing the strategy list mid-pass either breaks the iteration in `applyStrategies` or fails to stop the translator, making the whole mechanism inert.

Read the `applyStrategies` bytecode. The iterator is taken once from the `strategies` field before the loop, so `setStrategies` rebinds the field without disturbing the in-flight iteration: no `ConcurrentModificationException`, and the translator is still invoked in the same pass. That is exactly what the re-read at `GremlinToMatchStrategy:250` is for, and the two halves fit. Had the strategy instead mutated the live set in place, the Javadoc's `ConcurrentModificationException` prediction would have been correct — `DefaultTraversalStrategies` backs the set with a `LinkedHashSet`.

Verdict: REFUTED — the mechanism works as documented for the root traversal. Its limit is the scope of the replacement, which BG1 records.
