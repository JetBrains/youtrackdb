<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: CN3, sev: suggestion, loc: RepeatDeclineStrategy.java:240-251, cert: C4, anchor: "### CN3 ", basis: "veto wrapper hands a per-traversal handle in-place mutators onto the JVM-global strategy list; no live writer today, so hygiene rather than defect"}
  - {id: CN4, sev: suggestion, loc: AbstractMetadataUpdateCache.java:77-83, cert: C12, anchor: "### CN4 ", basis: "pre-existing: invalidate() stamps after clearing, so a compiling thread can cache a pre-DDL plan that survives the invalidation"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 2}
cert_index:
  - {id: C1, verdict: WRONG, anchor: "#### C1 "}
  - {id: C2, verdict: WRONG, anchor: "#### C2 "}
  - {id: C3, verdict: WRONG, anchor: "#### C3 "}
  - {id: C4, verdict: MATCHES, anchor: "#### C4 "}
  - {id: C5, verdict: WRONG, anchor: "#### C5 "}
  - {id: C6, verdict: WRONG, anchor: "#### C6 "}
  - {id: C7, verdict: WRONG, anchor: "#### C7 "}
  - {id: C8, verdict: WRONG, anchor: "#### C8 "}
  - {id: C9, verdict: WRONG, anchor: "#### C9 "}
  - {id: C10, verdict: WRONG, anchor: "#### C10 "}
  - {id: C11, verdict: WRONG, anchor: "#### C11 "}
  - {id: C12, verdict: MATCHES, anchor: "#### C12 "}
  - {id: C13, verdict: WRONG, anchor: "#### C13 "}
flags: [CONTRACT_OK]
-->

## Findings

### CN3 [suggestion] The veto wrapper exposes in-place mutators on the JVM-global strategy list, and the track added a per-compilation read of it

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (lines 240-251), with `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 366-373)

**Issue**: `VetoedStrategies` wraps the object `traversal.getStrategies()` returns. For a root traversal that object is the process-wide `TraversalStrategies.GlobalCache` entry for the graph class — one instance shared by every graph and every thread in the JVM, backed by a plain `LinkedHashSet`. The wrapper's `addStrategies` and `removeStrategies` forward to it verbatim, so anything that reaches those two methods through a traversal's strategies handle edits that shared set in place. That is the exact failure `CN2` named at step 1: `applyStrategies` holds a fail-fast iterator over the set for the whole compilation, so an in-place edit raises `ConcurrentModificationException` in every thread compiling against the graph class at that moment, not only in the editing one, and a `removeStrategies(GremlinToMatchStrategy.class)` would silently disable translation for the rest of the process.

No production caller reaches those mutators today, so this is not a live defect. Two things make it worth naming anyway. First, the guarantee now rests entirely on the absence of a caller rather than on the wrapper holding a private copy, and nothing pins that: `veto_leavesTheProcessWideStrategyCacheIntact` asserts the cache is not itself a `VetoedStrategies` and that the vetoed traversal reads the same strategies in the same order, both of which pass identically whether the wrapper points at the global list or at a clone of it. The test class Javadoc also states the veto "edits a copy of the strategy list, never the process-wide one", which is not what the code does — it wraps the process-wide one and edits nothing.

Second, this track widened the exposure. `GremlinStepWalker.walk` now resolves `ProductiveByStrategy` through `traversal.getStrategies().getStrategy(...)` on every translated compilation, which is an unsynchronized read-iteration of that same shared `LinkedHashSet`. Before this track the walker never touched the strategy list, so the window in which an in-place edit would be observed as a `ConcurrentModificationException` grew rather than shrank.

**Evidence**: `RepeatDeclineStrategy.apply` at line 173 installs `new VetoedStrategies(strategies)` where `strategies` came straight from `traversal.getStrategies()` with no `clone()`. Disassembly of the pinned fork (`io.youtrackdb:gremlin-core:3.8.1-67860f6-SNAPSHOT`) confirms the aliasing chain: `GraphTraversalSource(Graph)` takes its `strategies` field from `GlobalCache.getStrategies(graph.getClass())`, and `DefaultTraversal(TraversalSource)` passes `source.getStrategies()` by reference into the three-argument constructor. `DefaultTraversalStrategies` holds a `LinkedHashSet` and its `addStrategies` / `removeStrategies` mutate it in place. The interleaving: thread T1 compiles any traversal against `YTDBGraphEmbedded` and is inside the `applyStrategies` strategy loop, holding the set's iterator; thread T2 reaches `VetoedStrategies.removeStrategies` through some future caller on its own vetoed traversal; the forwarded call mutates the shared set; T1's next `strategyIterator.next()` throws.

**Refutation considered**: I checked whether the forwarding is a regression against `develop` — it is not. Without the wrapper, `traversal.getStrategies()` returns the same global instance directly, so the same call would have the same effect; the wrapper is transparent here. I then checked every route that could reach the mutators. `TraversalSource.withStrategies` and `withoutStrategies` both call `source.clone()` first, and `GraphTraversalSource.clone()` reassigns `strategies = strategies.clone()`, so those two user-facing paths mutate a private copy — this closes the concrete hazard the step-8 test Javadoc raised about `withoutStrategies`. `YTDBGraphImplAbstract.registerOptimizationStrategies` also clones `getStrategies(Graph.class)` before adding, and runs once from `YTDBGraphEmbedded`'s static initialiser. A repo-wide grep for `.addStrategies(` and `.removeStrategies(` across `core`, `embedded` and `server` finds no other production call site. That is why this is graded `suggestion` and not `should-fix`.

**Suggestion**: Two cheap options, either of them enough. Wrap `strategies.clone()` instead of `strategies` at line 173, which makes the wrapper's mutators structurally unable to reach the shared set and costs one `LinkedHashSet` copy per repeat-bearing compilation (the path already accepted a full clone plus sort before step 8, so the budget is there). Or keep the alias and make the two mutators throw `UnsupportedOperationException`, which turns the unwritten invariant into an enforced one and would surface any future caller at once rather than as a `ConcurrentModificationException` in an unrelated thread. If the alias stays as-is, add the one assertion that discriminates it — `assertThat(vetoed.getStrategies()).isNotSameAs(cached)` is not the right shape, so assert on the delegate — and fix the test Javadoc's "edits a copy" wording, which currently describes a design the code does not implement.

### CN4 [suggestion] The plan cache stamps its invalidation timestamp after clearing, so a concurrent DDL can leave a pre-DDL plan cached

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/AbstractMetadataUpdateCache.java` (lines 77-83), read by `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategy.java` (lines 451-458)

**Issue**: Pre-existing, and outside this track's diff — both files are unchanged in `5bc1247..HEAD`. Reported because the track brief directed this pass at plan-cache publication and no other dimension reasons about interleaving.

`GremlinToMatchStrategy.buildPlan` guards the cache put with `if (GremlinPlanCache.getLastInvalidation(session) < planningStart)`, where `planningStart` is captured before the walk. The guard's intent is that a DDL landing between the schema read and the put keeps the stale plan out of the shared per-database cache. `invalidate()` clears first and stamps second:

```java
public void invalidate() {
    if (cache != null) {
      cache.invalidateAll();
    }
    lastInvalidation.set(System.nanoTime());
}
```

Between those two statements the cache is empty but the timestamp still reads pre-DDL, so a compiling thread passes the guard and installs a plan built against the old schema into a cache the DDL has already emptied. Nothing re-checks entries against the timestamp afterwards — `getCached` is a plain Guava `getIfPresent` — so that plan is served to every later query of the shape until the next invalidation.

**Evidence**: Thread A captures `planningStart = T0` at `GremlinToMatchStrategy.java:277`, translates against schema `S_old`, and builds its plan. Thread B commits a DDL at `T1 > T0`; `onSchemaUpdate` calls `invalidate()` and enters `cache.invalidateAll()`. A now reads `getLastInvalidation()`, which still returns the pre-`T1` value, evaluates `< T0` as true, and calls `GremlinPlanCache.put(fingerprint, plan)`. B then executes `lastInvalidation.set(T1)`. The cache holds a plan compiled against `S_old` with `lastInvalidation` already past it. The window is the duration of `invalidateAll()` over the whole cache plus the gap to the `set`, so it widens with cache size rather than being a single-instruction race. The same `invalidate()` body serves `YqlExecutionPlanCache`, so the exposure is not Gremlin-specific.

**Refutation considered**: I checked whether the guard is sound in the other direction and it is — a DDL that stamps before A's read makes `getLastInvalidation() < planningStart` false and A correctly refuses to cache. I checked whether Guava's `Cache` could supply the missing ordering: it gives safe publication of the value but says nothing about the separate `AtomicLong`, so it does not help. I checked whether the value could be rescued at read time, and it cannot: `getInternal` compares only `COMMAND_TIMEOUT`, never the entry's age against `lastInvalidation`. I confirmed the files are unchanged in the review range with `git log`, which is why this is graded `suggestion` rather than `should-fix` — the grading reflects ownership, not impact.

**Suggestion**: Stamp before clearing. `lastInvalidation.set(System.nanoTime()); if (cache != null) { cache.invalidateAll(); }` closes both directions: a thread that reads the timestamp after the set refuses to cache, and a thread that already put an entry before the set has it removed by the following `invalidateAll()`. One line, and it fixes the YQL cache in the same edit. Since the file is not this track's, routing it out as a follow-up with the interleaving above attached is also a fine disposition.

## Evidence base

#### C1 CN1 cannot re-form on the veto channel step 8 landed — WRONG (claim refuted; the finding is closed)

The claim under test was that step 8's rewrite could reinstate `CN1`, where the veto and the translation each read the runtime kill-switch and an OFF→ON flip between the two reads skips the veto and translates an unrolled repeat.

Refuted, and refuted structurally rather than by timing argument: there is now exactly one production reader of the flag. A repo-wide grep for `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` across `core`, `embedded` and `server`, excluding tests, returns four hits — two Javadoc mentions, the enum declaration in `GlobalConfiguration.java:1019`, and the single read at `GremlinToMatchStrategy.java:358-360` inside `resolveSessionIfEnabled`. `RepeatDeclineStrategy.apply` (lines 166-176) reads no configuration at all: it runs `TraversalHelper.hasStepOfAssignableClassRecursively(RepeatStep.class, traversal)`, tests `strategies instanceof VetoedStrategies`, and installs the wrapper. Marking is therefore unconditional, and `GremlinToMatchStrategy.applyOrDecline` consults `isVetoed` at line 267 before doing any translation work. With one read there is no pair of reads to disagree, so no interleaving of flag flips can leave a repeat-bearing traversal unmarked. The class Javadoc's "Why the kill-switch is not consulted" section states this and the code matches it.

One residual, and it is not an interleaving: the `catch (RuntimeException e)` at line 176 degrades a failed scan to "no veto", which is fail-open with respect to the non-termination the strategy exists to prevent. Single-threaded, so `review-bugs` owns it if anyone does.

#### C2 CN2 cannot re-form on the veto channel step 8 landed — WRONG (claim refuted; the finding is closed)

The claim under test was that step 8's rewrite could reinstate `CN2`, where an in-place edit of the process-wide `GlobalCache` strategy set raises `ConcurrentModificationException` in unrelated concurrent compilations.

Refuted. `apply` performs one field write, `traversal.setStrategies(new VetoedStrategies(strategies))` at line 173, and the wrapper contributes no strategy of its own, so `iterator()` yields the wrapped list unchanged and `TraversalStrategies.sortStrategies` never runs. The set behind the `GlobalCache` entry is neither added to, removed from, nor reordered. I confirmed the two claims the Javadoc rests on by disassembling the pinned fork: `DefaultTraversalStrategies` holds a plain `LinkedHashSet`, and its `clone()` builds a fresh `LinkedHashSet(size)` and `addAll`s into it, so even the pre-step-8 clone-and-add form was copying rather than editing in place. `setStrategies` reaches exactly one traversal, because each traversal holds its own strategies reference until `lock()` runs.

The alias that survives — the wrapper points at the shared set and forwards mutators to it — is a separate matter, carried as `CN3`. It is not `CN2` re-forming, because nothing in the veto path calls those mutators.

#### C3 The three side-effect hazards step 8's plan named apply to the landed change — WRONG (premise refuted; the channel is not `getSideEffects()`)

The brief describes step 8 as moving the repeat-veto marker onto `traversal.getSideEffects()` and names three hazards to clear: side-effect keys surfacing through `profile()` / `cap()`, side-effect clone and merge semantics across child traversals, and leakage to sibling traversals.

The premise does not hold for the code that shipped. Step 8's commit `55da40dcdd` is titled "Carry the repeat veto without touching the strategy list", and its message records that `getSideEffects()` "was the first candidate and it fails twice, measured rather than argued": any side-effect key at all flips `getTraverserRequirements()` from `[BULK, OBJECT]` to `[BULK, OBJECT, SIDE_EFFECTS]` and swaps the traverser generator, which changes the native execution path even with the translator off, and a traversal shares one `TraversalSideEffects` instance with its direct children, so a boolean key at the root would veto every sibling. The marker that landed is the `VetoedStrategies` view of the traversal's `TraversalStrategies` reference, and `RepeatDeclineStrategy`'s Javadoc carries the rejection under "Channels that were measured and rejected".

The three hazards therefore have no surface to bite. Their strategy-reference analogues do, and I audited each: `clone()` is the one non-transparent method and it keeps the veto on the copy (line 254), which errs toward declining; per-traversal isolation holds because each traversal has its own reference until `lock()`; and the observability question that `profile()` / `cap()` stood for has no analogue, because a wrapper's type is not a queryable key. The step-8 test also pins the rejected channel's premise directly, asserting root and children share one side-effects instance and that the marker leaves `getTraverserRequirements()` equal to the control's.

#### C4 The veto wrapper aliases the JVM-global strategy list and forwards in-place mutators to it — MATCHES (carried as CN3)

#### C5 The step-10 fold latch or its walker state is shared across traversals or reused across compiles — WRONG (claim refuted)

The claim under test was that the fold latch, the `cursor.position()` reads around `peek()`, or the child-scope-boundary overload put per-walk state somewhere shared.

Refuted; every piece is per-walk and reached only from a single thread's compilation. The latch itself is `private boolean atTraversalStart` on `WalkerContext` (line 146), and `WalkerContext` is constructed inside `GremlinStepWalker.walk` for each walk. `StepStreamCursor`, whose `position()` the latch reads before and after `peek()`, is likewise constructed per walk and per `walkChild`. `SubTraversalPredicateAdapter.atTraversalStart()` answers a hard `false` and `setAtTraversalStart` is an empty body, so a child walk cannot write the parent's latch even though both share `dispatchAll`. `UnionForkHostImpl` is constructed per walk; its one non-final field, `prefixSnapshot`, is a lazily-filled memo on that per-walk object.

I also checked the enclosing singleton, since `walkFork` re-enters `GremlinStepWalker.production()`. That instance holds one field, `private final Map<Class<?>, StepRecogniser> recognisers`, and `walk` keeps all mutable state in locals. A sweep of every changed production file for non-final fields turned up only per-instance state on per-walk or per-invocation objects: `SubTraversalPredicateAdapter`'s three, `UnionForkHostImpl`'s memo, `WalkerContext`'s eight, `MatchPatternBuilder.built`, `SQLMatchFilter.items`, and `SQLFunctionMean`'s two (see C8). `POST_UNION_RECOGNISERS` widened from private to package-private for a test, but it is a `Set.of(...)`, so the wider visibility cannot become a write.

#### C6 `GremlinPlanFingerprint`'s BG29 change introduced shared render state — WRONG (claim refuted)

The claim under test was that the fingerprint's new `toString(NO_PARAMS, scratch)` rendering of alias filters reads or memoises something shared, so two concurrent compilations could interleave into one buffer or one parameter map and produce a corrupted or colliding key.

Refuted. `NO_PARAMS` is `Collections.emptyMap()`, immutable and read-only. `fingerprint` allocates its `StringBuilder` per call, and `appendRendered` allocates a fresh `scratch` `StringBuilder` inside each invocation rather than reusing a field — there is no static or instance buffer anywhere in the class, which has no instance state at all and a private constructor. The AST side is a read too: `SQLWhereClause.toString(params, builder)` delegates to `baseExpression.toString(params, builder)` and touches no field. The one memoising accessor on that class, the non-volatile `flattened` cache behind `flatten(ctx, schemaClass)`, is not reachable from `toString`; its callers are `SelectExecutionPlanner`, `UpsertStep` and `TraversalPreFilterHelper`, all at plan-build time on per-compilation ASTs.

Publication of the resulting key and plan is safe. `GremlinPlanCache` extends `AbstractMetadataUpdateCache`, whose storage is a Guava `Cache`, so the `put` that publishes a freshly-built plan and its AST establishes the happens-before a later thread's `getIfPresent` needs. That covers the non-final field writes the new AST factories perform before publication — `ProjectionExpressionFactories.propertyMethodCall` assigning `call.methodName` and `modifier.methodCall`, and `SQLMatchFilter.setClassName` appending to `items`.

#### C7 `ShapeClassifier`'s new aggregate arm reads or memoises shared state — WRONG (claim refuted)

The claim under test was that routing `mean` / `median` / `mode` / `variance` / `stddev` / `percentile` to `K0_NONE` touches memoised or otherwise shared classifier state that concurrent compilations could race on.

Refuted. `aggregateShapeForCall` is a pure `switch` over `call.getName()` returning an enum constant. A grep over `ShapeClassifier.java` for static declarations finds no mutable static and no static collection of any kind, so the class carries no cache to race on and the change adds none.

#### C8 `SQLFunctionMean`'s accumulator state is shared across concurrent queries — WRONG (claim refuted)

The claim under test was the classic stateful-aggregate race: `SQLFunctionMean` carries `private Number sum` and `private int total`, and if the function factory hands out one shared instance then two concurrent `mean(...)` aggregations interleave into one accumulator and both return a wrong number. The track both created the class and routed Gremlin `mean()` onto it, and Gremlin plans are cached, so the shape was worth chasing.

Refuted at two independent points. `DefaultSQLFunctionFactory` registers the class object, `register(SQLFunctionMean.NAME, SQLFunctionMean.class)`, not an instance — contrast `register(SQLFunctionCoalesce.NAME, new SQLFunctionCoalesce())` two lines below, which is the shared-instance form used for stateless functions. `SQLFunctionFactoryTemplate.createFunction` branches on that: an `SQLFunction` value is returned as-is, a `Class` value goes through `clazz.newInstance()`, so every lookup of `mean` yields a fresh accumulator. Independently, `SQLFunctionCall.getAggregationContext(ctx)` calls `SQLEngine.getFunction(...)` and wraps the result in a new `FuncitonAggregationContext` per aggregation, and `SQLEngine.getFunctionOrNull` caches nothing — it walks the factories and returns `factory.createFunction(...)` directly. So neither the cached plan's AST nor the engine holds an accumulator that two executions could share. This matches `SQLFunctionAverage`, whose identical field shape has been in use on the same mechanism.

#### C9 `ByModulatorPresence`'s static builder is shared mutable state — WRONG (claim refuted)

The claim under test was that the new `private static final MatchWhereBuilder WHERE = new MatchWhereBuilder()` in `ByModulatorPresence` shares an expression builder across every concurrent compilation, and that its "the builder is stateless" comment might be wrong.

Refuted; the comment is accurate. A field grep over `MatchWhereBuilder.java` returns nothing — the class declares no instance and no static field, only methods, so `WHERE.wrap(WHERE.isDefined(key))` allocates fresh AST nodes and mutates nothing shared. The same holds for the type guard the track added there: `typeIn` builds a new `SQLInCondition` and a new expression list per call, and reads `GremlinPredicateAdapter.NUMERIC_TYPE_NAMES`, which is a `List.of(...)`. `comparabilityBlock` returns either that immutable list or a fresh `List.of(...)`.

#### C10 `bindPathItemConstraints`'s in-place path-item mutation is observable by a concurrent reader — WRONG (claim refuted)

The claim under test was that the new pass mutates `SQLMatchPathItem`s that another thread can reach, since the method's own Javadoc says the items are mutated in place and that "the planner takes the pattern by reference, so these are the objects the executor reads".

Refuted; the object graph is confined to one compilation at the moment of the write. The `Pattern` and its path items come from the `MatchPatternBuilder` that `WalkerContext` allocates per walk, so at `bindPathItemConstraints` time nothing outside the compiling thread holds a reference. Publication afterwards goes through the Guava-backed plan cache, which supplies the ordering (see C6). At execution the MATCH traverser reads the bound filter rather than writing it. The one AST accessor that memoises, `SQLWhereClause.flatten`, has no caller on the path-item execution path, and the pass installs `where.copy()` rather than the map's own clause, so it adds no new aliasing of its own.

#### C11 Concurrent executions of one cached plan share mutable AST — WRONG (claim refuted for this track's surface)

The claim under test was that a cache hit hands several threads the same plan objects, so any AST write during execution would be a race.

Refuted for the surface this track touches. `GremlinPlanCache.putInternal` stores `internal.copy(copyCtx)` and `getInternal` returns `result.copy(ctx)`, so each execution gets its own plan and the cached instance is only ever read. Reads from several threads at once are fine as long as `copy()` does not write the source, and the AST accessors reached from a MATCH plan's execution are read-only: `matchesFilters` delegates to `baseExpression.evaluate`, and the memoising `flatten` path belongs to `SelectExecutionPlanner` at plan-build time. `MatchExecutionPlanner`'s own in-place writes — `rebindFilters` at lines 6015-6019 and the `item.setFilter(new SQLMatchFilter(-1))` at line 6348 — all run during planning, on the per-compilation AST, before anything is cached.

#### C12 `invalidate()` stamps after clearing, so the plan-cache DDL guard has a window — MATCHES (carried as CN4)

#### C13 `GremlinStepWalker.production()` carries per-walk state on a shared singleton — WRONG (claim refuted)

The claim under test was that `PRODUCTION_INSTANCE`, being a static singleton re-entered recursively from `UnionForkHostImpl.walkFork`, holds state that two concurrent compilations or two nested walks could corrupt.

Refuted. The instance holds one field, `private final Map<Class<?>, StepRecogniser> recognisers`, populated from `PRODUCTION_RECOGNISERS`, itself a static final map of recogniser singletons. `walk` keeps the context, cursor and fork host in locals, so recursion through `walkFork` creates a fresh set each time rather than reusing the caller's. The recogniser singletons themselves declare no fields — the non-final-field sweep in C5 covers this — and take all per-walk state through the `StepCursor` and `RecognitionContext` parameters. The two static builders the walker holds, `WHERE` and the recogniser registry, are stateless and immutable respectively.
