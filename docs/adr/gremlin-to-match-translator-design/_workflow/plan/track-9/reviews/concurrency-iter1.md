<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: CN1, sev: should-fix, loc: RepeatDeclineStrategy.java:89, anchor: "### CN1 ", cert: C1, basis: "the veto and the translation each read the runtime kill-switch separately; a concurrent OFF->ON flip between the two reads skips the veto and translates the unrolled repeat, reinstating the non-terminating MATCH this step exists to prevent"}
  - {id: CN2, sev: suggestion, loc: RepeatDeclineStrategy.java:40, anchor: "### CN2 ", cert: C2, basis: "the Javadoc calls the cloned strategy set 'shared with other traversals'; it is the process-wide GlobalCache singleton every thread iterates, so an in-place edit would CME unrelated concurrent compilations"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

### CN1 [should-fix] The veto and the translation read the kill-switch separately, so a concurrent flip can skip the veto and translate the unrolled repeat

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (lines 89-92), with `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategy.java` (lines 241, 250)

**Issue.** One compilation pass reads `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` twice. `RepeatDeclineStrategy.apply` reads it at `:89` through `resolveSessionIfEnabled` and skips the veto when the flag is off; `GremlinToMatchStrategy.applyOrDecline` reads it again at `:241` and translates when the flag is on. Nothing ties the two reads to one value. The flag is shared mutable state that any thread can write while the pass is in flight: the enum's backing field is `private volatile Object value` (`GlobalConfiguration.java:1459`) with a public `setValue` (`:1636`), the per-storage override lives in a `ConcurrentHashMap` (`ContextConfiguration.java:51`) reached from `storage.getContextConfiguration()` and therefore shared by every session on the database (`DatabaseSessionEmbedded.java:4231-4235`), and `getValue` falls through from the override to the process-global value (`ContextConfiguration.java:90-96`). The flag's own description calls it a "runtime kill-switch" (`GlobalConfiguration.java:1019-1026`), so a mid-flight write is the documented use, not an abuse.

Read order decides the outcome, and one order fails open. OFF then ON means no veto and a translation. That is the path the step was written to close.

**Failure scenario.**

1. Thread T1 compiles `g.V().repeat(__.out()).times(8)`. `DefaultTraversal.applyStrategies` runs the decoration category first, so `RepeatDeclineStrategy.apply` reaches `:89`, reads the flag as `false`, and returns without vetoing.
2. Thread T2 turns the translator back on — `GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(true)` from an embedded application, or `contextConfiguration.setValue(...)` on the same storage. Both writes are immediately visible to T1 (volatile field; `ConcurrentHashMap` put).
3. T1 continues the same pass. `RepeatUnrollStrategy` (optimization category) rewrites the `repeat` into eight `VertexStep`s separated by `NoOpBarrierStep`s, and the `RepeatStep` is gone.
4. T1 reaches `GremlinToMatchStrategy.apply` (provider-optimization category). `resolveSessionIfEnabled` at `:241` now reads `true`. The self-honouring guard at `:250` finds the translator still in the traversal's strategy list, because step 1 removed nothing, so it passes.
5. The walker sees a plain eight-hop chain — `TRANSPARENT_STEPS` skips the barriers (`GremlinStepWalker.java:51-52`) — and folds it into one MATCH pattern. On the grateful-dead fixture that pattern has 2,505,037,961,767,380 paths, which is the stall Track 9 Step 1 diagnosed.

The reverse order is harmless: ON at `:89` vetoes, and a later OFF makes `GremlinToMatchStrategy` decline at its own session gate.

**Refutation considered.** I checked whether one thread could produce the divergence on its own. Nothing between the two reads writes the flag — the strategies that run in between are `RepeatUnrollStrategy` and the other registered optimizations — so the split needs a second thread, which is why sequential review cannot reach it. I checked whether the guard at `GremlinToMatchStrategy.java:250` recovers the case: it tests only whether the translator is still in the list, and step 1 left it there, so the guard passes. I checked whether a downstream recogniser could still decline: the whole premise of the class Javadoc (`RepeatDeclineStrategy.java:17-27`) is that the unrolled form is indistinguishable from a hand-written chain once `RepeatUnrollStrategy` has run, and `handWrittenChainOfHopsStillTranslates` pins that such a chain translates. I checked whether the config is per-session, which would confine the write: `getConfiguration()` returns the storage's `ContextConfiguration`, one object per database. I checked whether any flag value is cached for the duration of a compilation: it is not.

**Suggestion.** Drop the kill-switch gate at `:89` and let the veto fire whenever a `RepeatStep` is present and the translator is in the traversal's list. Removing the translator from a per-traversal clone carries no behaviour when the flag is off, because `GremlinToMatchStrategy` declines at its own session gate (`:241-244`) either way, and the veto then cannot be skipped by any interleaving. The change also removes work rather than adding it: the strategy no longer resolves a session or opens a transaction, which is the cost the comment at `:80-81` was avoiding. `translatorOff_leavesTheTraversalStrategyListUntouched` (`RepeatDeclineStrategyTest.java:406-421`) pins the current property and would need to move to asserting the observable behaviour — that the traversal runs natively and the unroll still applied — rather than the list contents. If the property has to stay for the measurement-control reason the test's Javadoc gives, the alternative is to resolve the flag once per compilation and carry the answer on the traversal, so both strategies decide from one read.

### CN2 [suggestion] The Javadoc understates how widely the cloned strategy set is shared, and the invariant that keeps the clone safe is unwritten

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (lines 40-44)

**Issue.** The paragraph explaining `clone().removeStrategies(...)` says "the set a traversal source hands out is shared with other traversals". The object is shared far more widely than that. `GraphTraversalSource(Graph)` stores `TraversalStrategies.GlobalCache.getStrategies(graph.getClass())` by reference with no clone, and `DefaultTraversal(TraversalSource)` copies that reference, so `traversal.getStrategies()` at `:85` is the single `DefaultTraversalStrategies` instance that `YTDBGraphImplAbstract.registerOptimizationStrategies` (`:70-90`) registered for `YTDBGraphEmbedded.class` — one object for every graph instance and every thread in the JVM. Its backing collection is a plain `LinkedHashSet` held in a non-final, non-volatile field, and `DefaultTraversal.applyStrategies` captures `this.strategies.iterator()` once and holds that fail-fast iterator for the whole compilation.

The consequence the comment does not state is the cross-thread one. An in-place `removeStrategies` on that object — the idiom this step started from, per the step description — does not only raise `ConcurrentModificationException` on the mutating thread. It structurally modifies a set that every other thread compiling a traversal against the same graph class is iterating at that moment, so unrelated queries on unrelated sessions fail too. A reader who takes "other traversals" to mean "other traversals from my source" can reintroduce the in-place edit in some future strategy and see a failure mode far wider than the one the comment predicts.

**Refutation considered.** I confirmed no in-place mutation exists today. `TraversalSource.withStrategies` and `withoutStrategies` clone the source before editing (verified in the fork's bytecode), so the `withoutStrategies` path that `translatorAlreadyRemovedFromTheSource_needsNoVeto` exercises never touches the singleton. A grep across `core`, `server`, and `embedded` main sources finds `GlobalCache` referenced only at `YTDBGraphImplAbstract.java:71` and `:73`, and no call of the form `getStrategies().addStrategies(...)` or `getStrategies().removeStrategies(...)`. The finding is about the comment and the unwritten invariant, not about a defect in the current code.

**Suggestion.** Say that the set is the process-wide `GlobalCache` singleton for the graph class, shared by every traversal on every thread, and name the consequence: an in-place edit raises `ConcurrentModificationException` in concurrently compiling threads, not only in the editing one.

## Evidence base

#### C1 CONFIRMED — the kill-switch is read twice in one pass and is writable by another thread between the reads (CN1)

`RepeatDeclineStrategy.java:89` and `GremlinToMatchStrategy.java:241` each call `resolveSessionIfEnabled`, which reads the flag at `GremlinToMatchStrategy.java:349-352`; `DefaultTraversal.applyStrategies` runs the decoration category before provider optimizations, so the two reads bracket `RepeatUnrollStrategy`. The flag is writable at runtime through `GlobalConfiguration.java:1636` (volatile backing field at `:1459`) and through `ContextConfiguration.setValue` (`:74-80`) on the per-storage config returned by `DatabaseSessionEmbedded.getConfiguration()` (`:4231-4235`), and `ContextConfiguration.getValue` (`:90-96`) falls through from the override to the global value, so either write vector reaches both readers.

#### C2 CONFIRMED — `traversal.getStrategies()` is the process-wide GlobalCache singleton, iterated concurrently by every compiling thread (CN2)

Bytecode of the pinned fork (`io.youtrackdb:gremlin-core:3.8.1-67860f6-SNAPSHOT`): `GraphTraversalSource(Graph)` calls `GlobalCache.getStrategies(graph.getClass())` and stores the result directly in `strategies`; `DefaultTraversalStrategies.traversalStrategies` is a `LinkedHashSet` in a plain field; `DefaultTraversal.applyStrategies` calls `this.strategies.iterator()` once and drives that iterator through the whole pass. `YTDBGraphImplAbstract.registerOptimizationStrategies:71` puts one such object into the cache for `YTDBGraphEmbedded.class`.

#### C3 REFUTED — the self-honouring guard reads the strategy list without a happens-before edge to the veto's write

**Claim.** `RepeatDeclineStrategy.apply` writes `DefaultTraversal.strategies` at `:92`, and `GremlinToMatchStrategy.applyOrDecline` reads it at `:250`. The field is neither `volatile` nor `final` (confirmed in the fork's field table). If the two ran on different threads, the reader could miss the veto.

**Check.** `DefaultTraversal.applyStrategies` obtains one iterator over the strategy set and calls `TraversalHelper.applyTraversalRecursively(strategy::apply, this)` for each entry in sequence on the calling thread. Both strategies are entries in that one iteration, ordered decoration-before-provider-optimization by `TraversalStrategies.sortStrategies`, which `addStrategies` applies at registration time. No executor, future, or thread is created anywhere under `core/.../internal/core/gremlin/` — the only concurrency primitives there are `ThreadLocal` (`YTDBGraphImplAbstract.java:97`, `:494`; `YTDBElementImpl.java:25`).

**Verdict.** REFUTED. Same thread, same call frame, program order. The comment at `RepeatDeclineStrategy.java:46-49` describes the mechanism correctly.

#### C4 REFUTED — `RepeatDeclineStrategy` carries instance state shared across every traversal on every thread

**Claim.** The strategy is a singleton in a static global cache, so any instance field would be shared process-wide and would need synchronization or confinement.

**Check.** `RepeatDeclineStrategy` declares one member, `private static final RepeatDeclineStrategy INSTANCE` (`:62`), and no instance fields. Its base class `AbstractTraversalStrategy` declares no fields at all (verified with `javap` against the pinned fork jar: constructor, `toString`, `hashCode`, `equals` only). `apply` uses locals exclusively.

**Verdict.** REFUTED. Stateless, and safely published through the `private static final` initializer plus the `YTDBGraphEmbedded` class-initialization edge.

#### C5 REFUTED — the cloned strategies object is published to the executing thread through a data race

**Claim.** `:92` constructs a fresh `DefaultTraversalStrategies` and publishes it by writing the non-volatile `DefaultTraversal.strategies`. A thread that executes the compiled traversal without a happens-before edge to that write could observe the stale singleton, or a partially constructed `LinkedHashSet`.

**Check.** No path in this codebase hands a compiled `Traversal.Admin` to another thread: the gremlin package creates no executor, future, or thread, and per-thread state is reached through `ThreadLocal` (`YTDBGraphImplAbstract.java:97`). The same publication shape already governs the pre-existing step-list swap in `GremlinToMatchStrategy.applyTranslation`, which writes the equally non-volatile `DefaultTraversal.steps`, and TinkerPop treats a `Traversal` as thread-confined throughout.

**Verdict.** REFUTED as a diff-introduced defect. The new write adds nothing to an exposure the surrounding framework already assumes away.

#### C6 REFUTED — `clone()` at `:92` reads the shared set while another thread mutates it

**Claim.** `DefaultTraversalStrategies.clone()` builds a `LinkedHashSet` and calls `addAll(this.traversalStrategies)`, iterating the process-wide singleton's set. A concurrent structural modification would corrupt the copy or raise `ConcurrentModificationException`.

**Check.** No writer exists. `TraversalSource.withStrategies` and `withoutStrategies` clone the source first (fork bytecode), `registerOptimizationStrategies` mutates a fresh clone and never the object it cloned from (`YTDBGraphImplAbstract.java:73-89`), and no call site in `core`, `server`, or `embedded` main sources edits a strategy list in place.

**Verdict.** REFUTED. The read is safe today. CN2 covers the undocumented invariant it depends on.

**Reference-accuracy caveat.** The "no writer exists" half of this check rests on a text search rather than a PSI find-usages: `steroid_execute_code` times out on this repository (cold Kotlin compilation exceeds the 60 s MCP limit), so symbol audits fell back to grep. The claims about the fork's internals are stronger than grep — they come from `javap` disassembly of the pinned `gremlin-core-3.8.1-67860f6-SNAPSHOT.jar` — but a caller of `addStrategies` / `removeStrategies` reached through an interface-typed variable in project code could have been missed.

#### C7 REFUTED — the change mutates the static `GlobalCache` after registration

**Claim.** `registerOptimizationStrategies` writes `TraversalStrategies.GlobalCache`, whose `GRAPH_CACHE` is a plain `HashMap` (confirmed in the fork's static initializer). A write after publication would race every reader.

**Check.** The only caller is the static initializer of `YTDBGraphEmbedded` (`:13-15`), which runs once under the JLS class-initialization lock; every thread that later resolves strategies for that class does so after an initialization edge. The diff adds one element to the list the initializer builds and introduces no later write. A residual hazard remains inside the vendored fork and predates this change: `GlobalCache.getStrategies` reads `GRAPH_CACHE` unsynchronized while a first touch of a *different* graph class (`TinkerGraph`, present on the test classpath) can put into the same `HashMap`, with no edge between them. The diff neither creates nor widens it.

**Verdict.** REFUTED as a diff-introduced defect; the residual is a pre-existing TinkerPop-fork concern outside this step's control.
