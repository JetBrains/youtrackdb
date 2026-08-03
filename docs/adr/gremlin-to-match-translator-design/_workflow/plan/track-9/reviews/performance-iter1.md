<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: PF1, sev: should-fix, loc: RepeatDeclineStrategy.java:82, anchor: "### PF1 ", cert: C5, basis: "decline keys on RepeatStep, not on chain depth; a hand-written n-hop chain reaches the same MATCH path enumeration, and the new GremlinStepWalker Javadoc asserts that shape is safe"}
  - {id: PF2, sev: should-fix, loc: GremlinToMatchStrategy.java:250, anchor: "### PF2 ", cert: C6, basis: "the veto re-read makes the translator root-only for every traversal — measured: a child's own strategy list never carries a provider strategy during the strategy pass"}
  - {id: PF3, sev: suggestion, loc: RepeatDeclineStrategy.java:92, anchor: "### PF3 ", cert: C7, basis: "19 us per declining compile, all of it TraversalStrategies.sortStrategies, producing an order nothing reads in that pass"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 3}
cert_index:
  - {id: C0-caveat, verdict: CONTEXT, anchor: "#### C0-caveat "}
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONTEXT, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [should-fix] The decline keys on the `repeat(...)` spelling, so the same path blow-up stays reachable through a hand-written chain

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (line 82), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 104-123), `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java` (line 197)

**Issue**: The runaway cost is MATCH producing one row per distinct path where the Gremlin pipeline merges traversers into bulks at each `NoOpBarrierStep`. That property belongs to the n-hop chain. `RepeatDeclineStrategy` filters on `RepeatStep`, which is the syntax that produced the chain, so `g.V().out().out().out().out().out().out().out().out().count()` still translates and still asks MATCH for the same row count that made `repeat(__.out()).times(8)` hang.

The diff's own premise is what makes this deductive rather than speculative. `RepeatDeclineStrategy`'s Javadoc states that the unrolled form is "byte-for-byte the shape a hand-written n-hop chain produces and no recogniser can tell the two apart". If no recogniser can tell them apart, the walker builds the same pattern, the planner builds the same plan, and the plan enumerates the same 2,505,037,961,767,380 paths on the grateful-dead fixture. `handWrittenChainOfHopsStillTranslates` pins the chain path as intended behaviour and asserts exactly one boundary step for a two-hop chain; nothing caps the hop count above two. A grep over `translator/` found no depth, pattern-size, or fan-out guard (see the reference-accuracy caveat in `#### C0-caveat` below).

The Javadoc added to `GremlinStepWalker` asserts the opposite of the finding. It says the barrier a chain carries "carries no meaning the MATCH pattern has to preserve ... so skipping it costs nothing", and then separates the chain's barrier from the unrolled repeat's barrier "even though the two are the same class with the same size". The only difference it offers is provenance. Bulking bounds the cost in both cases, so the answer set survives the skip and the cost bound does not. A reader who takes that paragraph at face value will conclude the chain path is safe.

**Evidence**: `#### C5`.

**Impact**: A query that does not terminate, on a feature that ships enabled (`QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults true). The Cucumber suite carries no chain deep enough to reach it, so the suite completing in 17 s is not evidence that the shape is bounded — it is evidence that the suite does not contain it.

**Suggestion**: Two moves fit inside this step, one does not.

1. Correct the `GremlinStepWalker` Javadoc. "Skipping it costs nothing" holds for the answer set alone. State that the transparency rule carries no cost bound for either barrier, and that the repeat decline bounds one spelling of the shape.
2. Record the hand-written-chain residual with a named destination, which is the discipline `## Plan of Work` item 4 already imposes on this track.
3. The bound itself — decline above k recognised hops, or above an estimated fan-out product computed from the class and edge statistics the planner already reads — is a design decision rather than a step-1 patch. A depth gate is the cheap version and k in the 3-4 range would leave every shape the branch's equivalence suites exercise still translating. The prior question is whether MATCH pays a path-enumeration cost the native pipeline avoids at *any* depth, because that decides whether a k exists.

### PF2 [should-fix] The veto re-read disables the translator for every child traversal, not only for vetoed ones

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategy.java` (lines 245-252)

**Issue**: The new check reads the traversal's *own* strategy list:

```java
if (traversal.getStrategies().getStrategy(GremlinToMatchStrategy.class).isEmpty()) {
  return;
}
```

A child traversal's own list never carries a provider strategy during the strategy pass. TinkerPop pushes the parent's strategies down in `DefaultTraversal.lock()`, which runs after every strategy has been applied; until then a child holds the list its constructor gave it, which comes from `EmptyGraph` and knows nothing about strategies registered for the graph class. Measured on the same gremlin-core build the project depends on, with a probe strategy registered for the graph: every child reported `strategiesHasProbe=false` while reporting `graphPresent=true`. So the child's session resolves, the child reaches the new check, and the check always vetoes.

The effect is a strictly root-only translator, and the diff says nothing about it. Two consequences pull in opposite directions and the diff distinguishes neither:

- It withdraws translation from child traversals that qualified before. `UnionTraversalEquivalenceTest:425` exercises `g.union(__.V(), __.V().out("knows"))`, where the root declines on its start step and both children start at a vertex `GraphStep` over a resolvable session — the pre-diff gating cascade admits them. Those children now run natively.
- It is also the only thing closing the child-side blow-up. `g.union(__.V().repeat(__.out()).times(8).count(), __.identity())` puts the catastrophic shape inside a child. `RepeatDeclineStrategy` finds the `RepeatStep` recursively and vetoes the *root*, but `setStrategies` replaces the root's list only, so the root veto never reaches that child. The broad check is what stops it. The class Javadoc credits the root veto.

**Evidence**: `#### C6`.

**Impact**: Silent loss of MATCH coverage for every child-scoped recognised shape, on a default-on optimization whose entire value is that recognised shapes route through MATCH. Size unmeasured, and unmeasurable from the diff, because no test pins child-level translation in either direction.

**Suggestion**: Decide which of the two behaviours is intended and make the code say it.

- If root-only is intended, gate on `traversal.isRoot()` in `applyOrDecline` and keep the strategy-list re-read for the veto. That states the scope, is cheaper than a 23-element list scan, and stops the veto mechanism from carrying a second unrelated meaning.
- If child translation should survive, the veto has to consult the root's list rather than the child's — reachable through `getParent().asStep().getTraversal()` — or `RepeatDeclineStrategy` has to push the reduced list onto the children it already walks.

Either way, add a case that pins the child-side repeat shape (`g.union(__.V().repeat(__.out()).times(2), __.identity())`, root start-step not a `GraphStep`) so the hole above stays closed for a reason the tests record.

### PF3 [suggestion] The veto's `clone().removeStrategies(...)` spends 19 us re-sorting a list nothing reads in that pass

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (line 92)

**Issue**: `DefaultTraversalStrategies.removeStrategies` calls `TraversalStrategies.sortStrategies` whenever it removes anything, which rebuilds the category dependency map and re-runs the topological sort over the whole list. Measured at 19.05 us/op on a 23-strategy list, against 0.72 us for the clone on its own — the sort is essentially the whole cost. Twenty-three is the production size: TinkerPop's `Graph.class` defaults plus the seven strategies `YTDBGraphImplAbstract.registerOptimizationStrategies` adds.

The resulting order is never read during that compilation. `DefaultTraversal.applyStrategies` takes `this.strategies.iterator()` before the first strategy runs, which is the same fact the class Javadoc relies on when it explains why `GremlinToMatchStrategy` has to re-read the list. Only list membership matters here, and the sort is what membership costs.

**Evidence**: `#### C7`.

**Impact**: 19 us added to compiling each repeat-bearing traversal, plus the maps, sets and lists the sort allocates. Invisible against a deep repeat. Visible against a shallow one: `g.V(id).repeat(__.out()).times(1)` against a pinned start executes in the same order of magnitude as its own strategy sort. At 2,000 repeat-bearing compilations per second the sort is roughly 4% of one core.

**Suggestion**: Memoize the reduced list, or say in the Javadoc that the cost is accepted. Nearly every traversal from one graph shares the single `TraversalStrategies` instance registered in `TraversalStrategies.GlobalCache`, so a lookup keyed on that instance collapses the sort to once per distinct source. Keeping it leak-free is easy: cache only when the traversal's strategies are identity-equal to the graph's registered object, and fall through to the clone otherwise, so `withStrategies(...)` and `withoutStrategies(...)` sources pay today's cost and leave no reference behind. The class Javadoc's "Cost when the translator is off" section accounts for the scan and not for this, which is the part a later reader is most likely to be surprised by.

## Evidence base

#### C0-caveat — reference accuracy

PSI was unavailable. `steroid_list_projects` reports the IDE open on `/home/sandra-adamiec/IdeaProjects/youtrackdb`, matching the working tree, but `steroid_execute_code` timed out at 240 s on a `ReferencesSearch` over `RepeatDeclineStrategy` and `resolveSessionIfEnabled` — the cold-kotlinc timeout this repository reproduces. Symbol claims below fall back to grep plus an end-to-end read of each returned site. Two claims depend on a caller search and are bounded rather than established: "no hop-count or fan-out guard exists anywhere in `translator/`" (PF1) and "`RepeatDeclineStrategy` is registered at exactly one site and `resolveSessionIfEnabled` has exactly one new caller" (grep returned `YTDBGraphImplAbstract:83` and `RepeatDeclineStrategy:89`). Claims about TinkerPop's own dispatch are read from bytecode (`javap -c` over `gremlin-core-3.8.1-67860f6-SNAPSHOT.jar`) and from measurements against that same jar, so they do not rest on grep.

#### C1 — per-compilation cost of the recursive `RepeatStep` scan — REFUTED

The claim under test: the scan at `RepeatDeclineStrategy:82` runs on every traversal compiled against the graph, so it is a tax on the common path.

PREMISE P1: `RepeatDeclineStrategy.apply` is on the compilation path for every Gremlin traversal, once per compilation. `DefaultTraversal.applyStrategies` runs strategies only when `isRoot()` (or the parent is a `VertexProgramStep`), and for each strategy calls `TraversalHelper.applyTraversalRecursively(strategy::apply, this)` — verified in bytecode. So `apply` is invoked once for the root and once per child traversal, and the `isRoot()` guard at line 77 returns immediately for children.

PREMISE P2: `TraversalHelper.hasStepOfAssignableClassRecursively(Class, Admin)` walks the step list, recurses into local and global children, short-circuits on the first match, and allocates no lambda — the single-`Class` overload calls `Class.isAssignableFrom` directly (bytecode offsets 32-41). Its allocations are one iterator per step list visited plus whatever `getLocalChildren()` / `getGlobalChildren()` return for parent steps.

COST TRACE, measured against `gremlin-core-3.8.1-67860f6-SNAPSHOT`, traversals built on `EmptyGraph`, 0.5-2M iterations after an equal warm-up:

| shape | scan | TinkerPop's own dispatch walk for one strategy |
|---|---|---|
| `g.V().out().values("name")` | 0.018 us | 0.019 us |
| eight-hop chain | 0.033 us | — |
| `has` + `where(child)` + `union(2 children)` + `order().by` + `select` | 0.750 us | 0.643 us |
| `g.V().repeat(__.out()).times(8).count()` (early hit) | 0.023 us | — |

The dispatch walk column matters because `applyTraversalRecursively` walks the whole tree for the new strategy whether or not its body does anything, so the honest per-compilation cost is the sum. Baseline for the same branchy traversal, build plus `applyStrategies()` on TinkerGraph: 55.99 us/op. TinkerGraph's strategy list is shorter than YouTrackDB's and does no session resolution or schema read, so the real denominator is larger.

SCALE CHECK. Short traversal: 0.037 us against a compile measured in tens of us — under 0.1%. Branchy traversal: 1.39 us against 55.99 us — 2.5%, and lower against a YouTrackDB compile. Per query, not per record; no I/O, no lock, no page. VERDICT: NEGLIGIBLE. The gate ordering the Javadoc claims credit for is real and is what earns this: the scan runs before session resolution, and `YTDBStrategyUtil.resolveYtdbSession` calls `tx.readWrite()`, so a non-repeat traversal opens no transaction on this strategy's account.

#### C2 — strategy-list clone on the common path — REFUTED

The claim under test: a clone per compilation would be a real regression on short traversals.

Read of `RepeatDeclineStrategy.apply`: the gates run `isRoot()` (line 77), the recursive scan (line 82), the strategy-list membership check (line 86), and `resolveSessionIfEnabled` (line 89) before line 92 clones. A traversal with no `RepeatStep` returns at line 83 and never reaches the clone, never resolves a session, and never opens a transaction. Nothing between line 77 and line 83 allocates beyond the iterators C1 accounts for. VERDICT: the clone is on the declining path only, as claimed. No finding.

#### C3 — the veto re-read placed ahead of the O(1) start-step gate — REFUTED

The claim under test: `GremlinToMatchStrategy:250` inserts an O(strategies) scan in front of the O(1) start-step gate that the surrounding comment is explicitly ordered to protect, so traversals that would have declined in constant time now pay a list walk first.

The ordering inversion is real. `DefaultTraversalStrategies.getStrategy` is a linear scan over a `LinkedHashSet` calling `Class.isAssignableFrom` per entry, measured at 0.141 us/op for a hit near the end of a 23-strategy list. It sits between the session gate and `hasVertexGraphStart`.

SCALE CHECK. Only the root reaches line 250: every child that gets that far is vetoed there anyway (see `#### C6`), and every child that does not have a session returns at line 242. One root per compilation gives 0.141 us per query against a compile measured in tens of us — under 0.5%, once per query, no allocation beyond one `Optional`. VERDICT: NEGLIGIBLE as a cost. The ordering is worth a line-move for consistency with the comment two lines below it, and PF2 supersedes the question by proposing a different gate at that position.

#### C4 — children paying the scan on every compilation — REFUTED

The claim under test: the recursive scan re-runs for every child traversal as well as the root, multiplying C1 by the child count.

`apply` returns at line 77 for a non-root traversal, and `Traversal.Admin.isRoot()` is `getParent() instanceof EmptyStep` — one interface call and one `instanceof`. `applyTraversalRecursively` is pre-order, so the root is visited before any child and the root's scan has already covered the whole tree by the time a child is reached. The child cost is the tree walk TinkerPop performs for every registered strategy, already charged in C1's second column. VERDICT: no multiplication. The `isRoot()` guard does what its comment says.

#### C5 — a hand-written n-hop chain reaches the same MATCH path enumeration — CONFIRMED

Same step shape as an unrolled repeat by the diff's own statement, no depth cap anywhere in `translator/`, `handWrittenChainOfHopsStillTranslates` pins the chain as translating; MATTERS NOW, since the shape is expressible today against a default-on switch. Backs PF1.

#### C6 — the veto re-read makes the translator root-only — CONFIRMED

Probe strategy registered for the graph class and run through `applyStrategies` on `gremlin-core-3.8.1-67860f6-SNAPSHOT`: every child reported its own strategy list without the provider strategy (`strategiesHasProbe=false`) while reporting `graphPresent=true`, so the child resolves a session, reaches line 250 and is always vetoed; `DefaultTraversal.lock()` is where the parent's list is pushed down and it runs after the strategy pass. MATTERS AT SCALE for the coverage half, MATTERS NOW for the hole it closes. Backs PF2.

#### C7 — 19 us per declining compile, spent on a sort nothing reads — CONFIRMED

`clone() + removeStrategies` measured at 19.05 us/op on a 23-strategy list against 0.72 us for the clone alone, and `DefaultTraversal.applyStrategies` captures the strategy iterator before the first strategy runs, so the re-sorted order is not iterated in that pass; MATTERS AT SCALE on shallow-repeat workloads. Backs PF3.

#### C8 — measurement provenance

All timings come from three standalone harnesses compiled against
`~/.m2/repository/io/youtrackdb/gremlin-core/3.8.1-67860f6-SNAPSHOT/gremlin-core-3.8.1-67860f6-SNAPSHOT.jar`
(the `gremlin.version` the root POM pins) plus `tinkergraph-gremlin` at the same version, each with a warm-up loop equal in size to the measured loop, on the review machine. They are wall-clock loops rather than JMH, so treat them as order-of-magnitude figures: the 19 us and 0.14 us numbers are separated by two orders of magnitude and the conclusions do not turn on either being accurate to better than a factor of two. No suite was re-run; the 1930-scenario 17 s result is taken as established per the dispatch.
