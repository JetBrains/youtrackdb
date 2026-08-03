<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: TX1, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java:406, anchor: "### TX1 ", cert: C1, basis: "veto edits a clone of the JVM-global TraversalStrategies set; nothing asserts the global set survives, and core runs 4 test classes per JVM"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED,   anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED,   anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED,   anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED,   anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### TX1 [should-fix] No test pins that the veto leaves the JVM-global strategy cache intact

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java`, method `translatorOff_leavesTheTraversalStrategyListUntouched` (line 406)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (lines 85-92)

**Issue.** `traversal.getStrategies()` at line 85 returns the process-wide
`DefaultTraversalStrategies` instance that `TraversalStrategies.GlobalCache`
holds for `YTDBGraphEmbedded.class` — the same object every graph instance and
every thread in the JVM compiles against. Line 92 keeps the edit local by
cloning before removing. That `clone()` is the only thing standing between a
per-traversal veto and a JVM-wide one, and no test asserts it. Every assertion
in the new suite still passes if the clone is dropped.

**Evidence.** CONTRACT: *the veto removes the translator from one traversal and
from nothing else.* TEST TRACE:

- Tests: all nine methods in `RepeatDeclineStrategyTest`. Thread count: 1.
  Synchronization: none needed. Shared resource under test: the strategy set
  reached through `traversal.getStrategies()`.
- The suite reads the traversal-local reference only. `assertDeclinedAndEquals`
  (line 429) asserts `boundaryOn == 0`, `unrolledOn == true`, and result
  equality. A globally stripped translator satisfies all three: no boundary
  step, unroll still registered, native results identical.
- `translatorOff_leavesTheTraversalStrategyListUntouched` is the one case whose
  name claims the invariant, and it runs the translator-off arm, which returns
  at `RepeatDeclineStrategy.java:89-91` before reaching the clone. It exercises
  the path that cannot corrupt anything.
- `handWrittenChainOfHopsStillTranslates` (line 389) would catch a global strip,
  but only when scheduled after a veto case. Neither the class,
  `GraphBaseTest`, nor `DbTestBase` declares `@FixMethodOrder`, so JUnit 4 falls
  back to `MethodSorters.DEFAULT` — a method-name hash, not source order.

VERDICT: NOT TESTED.

Reference facts, all read from the `io.youtrackdb:gremlin-core:3.8.1-67860f6`
bytecode:

- `GraphTraversalSource(Graph)` stores `GlobalCache.getStrategies(graph.getClass())`
  by reference, with no clone.
- `DefaultTraversal(TraversalSource)` stores `source.getStrategies()` by
  reference, with no clone.
- `DefaultTraversalStrategies.traversalStrategies` is a plain `LinkedHashSet`;
  `removeStrategies` mutates it in place and returns `this`.
- `DefaultTraversalStrategies.clone()` allocates a fresh `LinkedHashSet` and
  copies, so `RepeatDeclineStrategy.java:92` is correct as written.
- `GlobalCache.getStrategies` writes `GRAPH_CACHE` once, through the
  `Class.forName` that fires `YTDBGraphEmbedded`'s `static { registerOptimizationStrategies(...) }`;
  afterwards it only reads. The cached set therefore has no writer at all once
  the class is initialised.

**Why it matters.** `core/pom.xml:400-403` runs the `default-test` execution
with `<parallel>classes</parallel>` and `threadCountClasses=4` — four test
classes per JVM, one fork. `RepeatDeclineStrategyTest` is neither under
`gremlintest/**` nor tagged `@Category(SequentialTest)`, so it runs beside three
other classes that are compiling traversals against the same cached strategy
set at the same moment. Today every access to that set is a read, so the suite
is safe single-threaded and needs no threads of its own. Drop the `clone()` and
the picture inverts twice over: `removeStrategies` iterates and mutates a
`LinkedHashSet` that up to three sibling classes are iterating concurrently
(`ConcurrentModificationException`, or a silently corrupted set), and the
surviving damage outlives the test — the cache entry is keyed by graph class and
persists for the JVM, while each test's database does not. Every gremlin test
scheduled afterwards in that fork would run natively and pass, because passing
natively is what most of them assert. The failure would surface as a
translator-coverage regression with no failing test pointing at it.

This is the invariant the whole decline design rests on, it is one line of
production code, and it is the exact shape of the fork-wide stall this track was
opened to chase. Pin it.

**Suggested test.**

```java
/**
 * The veto edits a copy of the strategy list, never the process-wide one.
 * {@code graph.traversal()} hands every traversal the exact {@code TraversalStrategies}
 * instance that {@code TraversalStrategies.GlobalCache} holds for the graph class, shared by
 * every graph and every thread in the JVM, so a veto that lost its {@code clone()} would strip
 * the translator for the rest of the process — including the three sibling test classes
 * surefire runs beside this one. Reading the cache directly, and then compiling a second
 * repeat-free traversal on the same graph, pins that independently of method order.
 */
@Test
public void veto_leavesTheProcessWideStrategyCacheIntact() {
  seedKnowsChain();
  setTranslatorEnabled(true);

  var vetoed = graph.traversal().V().repeat(__.out()).times(2).count().asAdmin();
  vetoed.applyStrategies();
  assertThat(countBoundarySteps(vetoed))
      .as("precondition: the repeat-bearing traversal declines, so the veto did fire")
      .isZero();

  assertThat(
          TraversalStrategies.GlobalCache
              .getStrategies(graph.getClass())
              .getStrategy(GremlinToMatchStrategy.class))
      .as("the veto must not remove the translator from the JVM-global strategy cache")
      .isPresent();

  var later = graph.traversal().V().out("knows").out("knows").asAdmin();
  later.applyStrategies();
  assertThat(countBoundarySteps(later))
      .as("a later traversal on the same graph must still translate")
      .isEqualTo(1);
}
```

Add `import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;`.
Renaming `translatorOff_leavesTheTraversalStrategyListUntouched` to say
*traversal's own list* would stop it reading as coverage for the global one.

## Evidence base

#### C1 The veto's non-mutation of the shared `TraversalStrategies` set is unpinned — CONFIRMED

Survived refutation. Basis for TX1: the shared set is reachable from
`traversal.getStrategies()` by reference, `removeStrategies` is an in-place
mutator, and no assertion in the suite reads
`GlobalCache.getStrategies(...)`.

#### C2 `RepeatDeclineStrategy` is a process-wide singleton, so concurrent `apply()` needs a multi-threaded test — REFUTED

Claim: the strategy is registered once as a static singleton
(`RepeatDeclineStrategy.java:62`, `YTDBGraphImplAbstract.java:83`) and
`YTDBGraphImplAbstract` carries a `ThreadLocal<ThreadLocalState>`
(line 97-98), so one graph serves many threads and two of them can run
`apply()` at the same instant. That looks like shared mutable state.

Refuted. The class declares no instance fields — only the `private static final
INSTANCE`. `apply()` reads three things: `traversal.isRoot()`,
`TraversalHelper.hasStepOfAssignableClassRecursively(...)` over the traversal's
own step list, and the strategy set. It writes exactly one thing:
`traversal.setStrategies(...)`, which `DefaultTraversal.setStrategies` compiles
to a single `putfield` on the traversal. A traversal under `applyStrategies()`
is confined to the compiling thread, so that write is unshared. The strategy set
is read-only after class initialisation (see C1's reference facts). Two threads
running `apply()` concurrently touch no common mutable location.

Consequence: the absence of a concurrent-compilation test is correct, not a gap.
A `ConcurrentTestHelper` test spinning N threads through
`graph.traversal().V().repeat(...)` would add runtime and prove nothing the
single-threaded cases do not already establish. TX1's suggested test is
single-threaded on purpose: the invariant that makes concurrency a non-issue is
cheaper and more reliably checked by reading the cache directly than by racing
threads at it.

Caveat: the "no other in-place mutator" half of this rests on grep over
`*/src/main/java` (`removeStrategies` / `addStrategies` / `.setStrategies(`),
which returned `YTDBGraphImplAbstract.java:75` (inside the static registration
builder, applied to a fresh clone of `Graph.class`'s set),
`UnionForkHostImpl.java:80` (a reference assignment, not a set mutation), and
`RepeatDeclineStrategy.java:92`. PSI find-usages was the right instrument here;
`steroid_execute_code` timed out against this repository at a 300 s budget, as
it has before, so the enumeration is textual. A reflective or method-reference
call site would be missed. The TinkerPop-side facts in C1 come from `javap` on
the resolved jar and carry no such caveat.

#### C3 `withoutStrategies` in `translatorAlreadyRemovedFromTheSource_needsNoVeto` mutates the global set and poisons parallel siblings — REFUTED

Claim: `translatorAlreadyRemovedFromTheSource_needsNoVeto` (line 360) calls
`graph.traversal().withoutStrategies(GremlinToMatchStrategy.class)`, and
`TraversalSource.withoutStrategies` compiles to
`clone().getStrategies().removeStrategies(...)` — an in-place removal. If
`GraphTraversalSource.clone()` shared the strategies reference, this test would
strip the translator JVM-wide on every run, breaking three sibling classes under
`parallel=classes`.

Refuted. `GraphTraversalSource.clone()` does
`clone.strategies = this.strategies.clone()` before returning, and
`DefaultTraversalStrategies.clone()` allocates a fresh `LinkedHashSet`. The
removal lands on a private copy. This test is safe as written, and it is worth
recording that it was checked: it is the one place in the new suite that reaches
a TinkerPop in-place mutator directly.

#### C4 The suite's configuration writes leak into later tests in the same fork — REFUTED

Claim: `setTranslatorEnabled` (line 462) writes
`QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` through
`session.getConfiguration()`, and three cases —
`translatorAlreadyRemovedFromTheSource_needsNoVeto`,
`handWrittenChainOfHopsStillTranslates`,
`translatorOff_leavesTheTraversalStrategyListUntouched` — set it without a
`finally` restore. `withNonPolymorphicDefault` writes a second flag through a
different handle (`tx.getDatabaseSession().getConfiguration()`). Either could
outlive the test.

Refuted. `DatabaseSessionEmbedded.getConfiguration()` (line 4231-4234) returns
`storage.getContextConfiguration()`, so the flag is scoped to one storage.
`DbTestBase.beforeTest` names the database after the running test method and
`createDatabase` builds it fresh; `afterTest` drops it and closes the
`YouTrackDBImpl`. The storage, and its `ContextConfiguration`, do not survive the
method. The two handles resolve to the same storage — `GraphBaseTest.openGraph()`
opens on the same `dbPath` and `databaseName` the `session` field uses — so the
flags also apply to the graph's traversals as intended. The missing restores are
therefore harmless, and the class correctly stays out of
`@Category(SequentialTest)`: its writes are per-database, not process-wide, which
is the distinction `core/pom.xml:412-417` draws.

#### C5 The unsynchronized `GlobalCache.GRAPH_CACHE` `HashMap` is a hazard this diff introduces — REFUTED

Claim: `GlobalCache.GRAPH_CACHE` and `GRAPH_COMPUTER_CACHE` are plain
`HashMap`s. Under `parallel=classes` with four threads, one thread's
`registerStrategies` put can race another thread's `containsKey` / `get`. The
diff adds a strategy that reads this cache, so it widens the exposure.

Refuted as in-scope. Every `graph.traversal()` call has always gone through
`GlobalCache.getStrategies`, so the read volume is unchanged by this diff; the
new reads at `RepeatDeclineStrategy.java:85-86` and
`GremlinToMatchStrategy.java:66` are against the already-resolved
`TraversalStrategies` object, not the map. The map write happens once per graph
class, ordered behind `Class.forName` and the `LOADED` `ConcurrentHashMap`
key-set guard, which supplies the happens-before edge for threads that observe
`LOADED.contains(...) == true`. This is upstream TinkerPop behaviour, unchanged
here, and outside the step's diff.
