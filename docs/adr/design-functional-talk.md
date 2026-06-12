# Functional Plan Cache — Design Talk Script

**Estimated runtime:** 22–25 minutes at ~150 words per minute (with code pauses).

**Format:** continuous prose with embedded code blocks. Each code block has
a `[CODE — N s]` cue indicating roughly how long to pause for the audience
to read.

**Tone:** spoken English — contractions, "you" addressing the audience,
em-dashes for verbal pauses.

**How to use:** read the prose, pause on each `[CODE — N s]` cue while the
audience scans the snippet, give a one-sentence verbal summary of what the
code shows, then continue. Don't read code aloud — let people read it.

---

## [0:00 — Opening]

Thanks for the time. I want to walk you through a refactor we're proposing
for the SQL executor — specifically, how plans get cached and reused. The
problem is small and concrete: about six percent of CPU on our LDBC benchmark
goes to copying execution plans, every time we hit the cache. The fix touches
a lot of code — about a hundred and twenty files — but the core idea is
straightforward. I'll explain the problem first, then the shape of the
solution, then walk through the migration plan, and end on the risks. Then
questions.

---

## [1:30 — How execution works today]

Quick refresher on how a query runs in YouTrackDB. When you submit a SELECT,
three things happen.

**[CODE — 8 s]**

```
SQL text
   │  (parser)
   ▼
AST                — tree of SQLSelectStatement, SQLProjection,
                     SQLWhereClause, SQLOrderBy, ... (~100 nodes)
   │  (planner)
   ▼
ExecutionPlan      — chain of ExecutionStep:
                       FetchFromClass → Filter → Projection → OrderBy → Limit
   │  (engine)
   ▼
ExecutionStream    — pull-based, lazy decorator chain
```

The plan cache memoizes the planner output. Key is the SQL text, value is
the pre-built ExecutionPlan. When the same query comes back, we skip parsing
and planning entirely.

---

## [3:00 — The pain point]

But here's what actually happens on a cache hit today.

**[CODE — 10 s]**

```java
// YqlExecutionPlanCache.getInternal — today:
public ExecutionPlan getInternal(String sql, CommandContext ctx, ...) {
    ExecutionPlan cached = cache.getIfPresent(sql);
    if (cached == null) return null;
    return cached.copy(ctx);     // ← deep-copy entire plan + AST
}
```

```java
// SelectExecutionPlan.copyOn — today:
protected void copyOn(SelectExecutionPlan copy, CommandContext ctx) {
    ExecutionStepInternal lastStep = null;
    for (var step : this.steps) {
        var newStep = (ExecutionStepInternal) step.copy(ctx);  // each step
        newStep.setPrevious(lastStep);                          // re-wire
        if (lastStep != null) lastStep.setNext(newStep);
        lastStep = newStep;
        copy.getSteps().add(newStep);
    }
    // ... + recursive AST deep-copy through step.copy()
}
```

Roughly a hundred and thirty allocations per query, before we've even
started executing. Three specific hotspots show up in the LDBC profile.
The biggest, at three-point-six percent, is `toGenericStatement` doing a
full AST walk to produce a string. The next, at one-point-five, is the
recursive copy of SQL identifiers. The third, at one percent, is the
orchestration that copies the step chain. Total: about six percent.

Why do we copy? Because today's steps embed per-execution mutable state.

**[CODE — 10 s]**

```java
public abstract class AbstractExecutionStep implements ExecutionStepInternal {
    // Per-execution mutable state — written during start():
    protected CommandContext ctx;             // ← race if shared
    protected ExecutionStepInternal prev;     // ← topology
    protected ExecutionStepInternal next;     // ← topology
    protected boolean alreadyClosed;          // ← race if shared

    // Subclass-specific config (some final, some not)
    // e.g. SQLWhereClause whereClause, long limit, ...

    @Override
    public final ExecutionStream start(CommandContext ctx) {
        this.ctx = ctx;                       // WRITE — race
        return internalStart(ctx);
    }
}
```

Two threads running the same step instance would race on `this.ctx`. The
deep copy keeps each execution's state private — correct, but wasteful.

And the really galling part is that `toGenericStatement` — the largest
hotspot — produces a string that nobody reads. Zero callers in production.
Dead code in the hot path.

---

## [5:30 — The proposal]

What we're proposing is to make execution steps stateless — pure functions.

**[CODE — 12 s]**

```java
// New interface — a step is a pure transformation:
public interface ExecutionStep {

    /**
     * Transforms an upstream stream into a downstream stream.
     * Per-execution state lives in local variables, not fields.
     */
    ExecutionStream execute(ExecutionStream upstream, CommandContext ctx);

    /** True if this step's configuration allows plan caching. */
    boolean canBeCached();

    /** EXPLAIN output. */
    String prettyPrint(int depth, int indent);

    /** Optional pre-execution hook (default no-op). */
    default void registerExpressions(CommandContext ctx) {}
}
```

```java
// SelectExecutionPlan — immutable, forward iteration:
public class SelectExecutionPlan {
    private final List<ExecutionStep> steps;       // List.copyOf
    private final String statement;

    public ExecutionStream start(CommandContext ctx) {
        // Phase 1: pre-register cross-step state (e.g. WHERE expressions)
        for (var step : steps) step.registerExpressions(ctx);

        // Phase 2: forward execute loop
        ExecutionStream current = ExecutionStream.empty();
        for (var step : steps) {
            current = step.execute(current, ctx);
        }
        return current;
    }
}
```

```java
// YqlExecutionPlanCache — zero copy:
public ExecutionPlan getInternal(String sql, CommandContext ctx, ...) {
    return cache.getIfPresent(sql);   // same object, every caller
}
```

That's the elevator pitch. Pure-function steps, immutable plans, shared
across threads. Allocation count goes from ~130 to ~13 per cache hit. The
dead `toGenericStatement` code goes away in phase one alone — clean
three-point-six percent, no semantic change.

The second benefit may matter more on parse-heavy workloads. We have steps
today that opt out of caching entirely — `canBeCached() = false` because
they hold mutable state. After the refactor, those steps become shareable,
and queries that currently re-plan from scratch on every invocation get
cached for the first time. PR 880's `IndexOrderedEdgeStep` is the headline
example.

---

## [7:30 — Step migration taxonomy]

The hard part is migrating around seventy-six step classes. They fall into
four patterns, each with its own migration recipe.

### Category 3.1 — Stream transformers (~45 classes, mechanical)

**[CODE — 10 s]**

```java
// FilterStep — BEFORE:
public class FilterStep extends AbstractExecutionStep {
    private final SQLWhereClause whereClause;

    @Override
    protected ExecutionStream internalStart(CommandContext ctx) {
        var resultSet = prev.start(ctx);                 // ← prev field
        return resultSet.filter(this::matches);
    }

    @Override
    public FilterStep copy(CommandContext ctx) {
        return new FilterStep(whereClause.copy(), ctx, ...);
    }
}
```

```java
// FilterStep — AFTER:
public class FilterStep implements ExecutionStep {
    private final SQLWhereClause whereClause;

    @Override
    public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
        return upstream.filter(this::matches);           // ← parameter
    }
    // No copy() method. No prev/next/ctx fields.
}
```

Three line changes per step: rename the method, replace `prev.start(ctx)`
with the `upstream` parameter, delete `copy()`. Find and replace, basically.
Filter, projection, order by, limit, distinct — about forty-five classes.

### Category 3.2 — Source steps (~20 classes)

These create a stream from a data source. They drain upstream for side
effects, then become the actual source.

**[CODE — 10 s]**

```java
// FetchFromClassExecutionStep — BEFORE:
@Override
protected ExecutionStream internalStart(CommandContext ctx) {
    if (prev != null) {
        prev.start(ctx).close(ctx);              // drain & discard
    }
    return createClassScanStream(ctx);
}

// FetchFromClassExecutionStep — AFTER:
@Override
public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
    upstream.consume(ctx);                       // drain & discard
    return createClassScanStream(ctx);
}
```

```java
// New default method on ExecutionStream:
default void consume(CommandContext ctx) {
    try { while (hasNext(ctx)) next(ctx); }
    finally { close(ctx); }
}
```

Same idea — replace prev field with parameter, add a small convenience
method to ExecutionStream.

### Category 3.3 — Stateful steps (the interesting one)

This is where it gets interesting. Six or seven classes have genuinely
per-execution state — hash sets, RID maps, traversal caches.

**[CODE — 15 s]**

```java
// HashJoinMatchStep / BackRefHashJoinStep — BEFORE:
public class BackRefHashJoinStep extends AbstractExecutionStep {
    @Nullable private Set<JoinKey> hashSet;        // ← race when shared
    @Nullable private Map<JoinKey, List<Result>> hashMap;

    @Override
    protected ExecutionStream internalStart(CommandContext ctx) {
        hashSet = buildHashSet(ctx);                // WRITE field
        var upstream = prev.start(ctx);
        return upstream.filter(row ->
            hashSet.contains(extractKey(row)));     // READ field in lambda
    }

    @Override
    public void close() {
        hashSet = null;                             // CLEAR field
        super.close();
    }
}
```

```java
// AFTER — local variable + closure capture + onClose:
public class BackRefHashJoinStep implements ExecutionStep {
    // No instance fields with execution state.

    @Override
    public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
        var builtSet = buildHashSet(ctx);           // LOCAL variable
        return upstream
            .filter(row -> builtSet.contains(...))  // lambda captures builtSet
            .onClose(c -> builtSet.clear());        // cleanup callback
    }
    // No close() override. No instance fields.
}
```

The trick: `builtSet` is a local on the stack frame of `execute()`. Each
call creates a fresh one. The lambda captures the reference. The onClose
clears it eagerly when the stream closes. Two concurrent executions can't
collide — they're two different stack frames with two different locals.

For MatchStep specifically, we add `withFreshCache` to EdgeTraversal:

**[CODE — 10 s]**

```java
// EdgeTraversal — new method:
public EdgeTraversal withFreshCache() {
    var copy = new EdgeTraversal(edge, out);
    copy.leftClass = this.leftClass;
    copy.leftFilter = this.leftFilter;       // share AST (immutable per §7)
    copy.leftRid    = this.leftRid;          // share AST
    copy.intersectionDescriptor = this.intersectionDescriptor;
    copy.acceptedCollectionIds  = this.acceptedCollectionIds;
    // cache = null — fresh per execution
    return copy;
}
```

```java
// MatchStep — AFTER:
public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
    var execEdge = edge.withFreshCache();    // small allocation per execution
    return upstream.flatMap(
        (row, c) -> createTraverser(row, execEdge).toStream(c));
}
```

One small allocation per match step per execution, much cheaper than
today's full plan deep-copy.

### Category 3.4 — Control-flow

`IfStep`, `ForEachStep`, `RetryStep`, the script-line steps. We leave them
alone. They live in script execution plans, which aren't cached, so the
migration buys nothing. Deliberate non-completion.

---

## [11:00 — The interesting subtleties]

Three things took the most thought.

### Subtlety 1: backward → forward initialization order

Today is backward-recursive — the terminal step starts first, asks its
predecessor for upstream, predecessor recurses, all the way to the source.
The new model is forward-iterative.

**[CODE — 12 s]**

```
BACKWARD (today, recursive)               FORWARD (after, iterative)

  Limit.start                                empty.execute → current
    ↓ prev.start                               ↓
  OrderBy.start                              source.execute(current, ctx)
    ↓ prev.start                               ↓ → current
  Filter.start                               filter.execute(current, ctx)
    ↓ prev.start                               ↓ → current
  FetchFromClass.start                       sort.execute(current, ctx)
    ← stream returns up                        ↓ → current
                                             limit.execute(current, ctx)
                                               ↓ → final stream
```

Both produce the same final stream chain. They initialize the steps in
opposite order. That matters in exactly one place.

`FilterStep`, today, registers its WHERE expression in the context **before**
it calls upstream:

**[CODE — 12 s]**

```java
// FilterStep — today (backward order works):
@Override
protected ExecutionStream internalStart(CommandContext ctx) {
    ctx.registerBooleanExpression(whereClause.getBaseExpression());  // ← BEFORE
    var rs = prev.start(ctx);                                         // upstream
    return rs.filter(...);
}

// GlobalLetQueryStep upstream reads that registration to decide whether
// to materialize results:
if (ctx.getParentWhereExpressions().contains(whereWithLetVar)) {
    return materialize(stream);   // re-readable list
} else {
    return stream;                // single-use iterator (cheaper)
}
```

In forward order, `GlobalLetQueryStep` runs first. Sees empty registration.
Picks the iterator. Then `FilterStep` runs and tries to filter from a
drained iterator. Wrong query results, silent.

**The fix is a pre-registration phase:**

**[CODE — 10 s]**

```java
// SelectExecutionPlan.start — two-pass:
public ExecutionStream start(CommandContext ctx) {
    // Phase 1: register all cross-step expressions
    for (var step : steps) step.registerExpressions(ctx);

    // Phase 2: forward execute
    ExecutionStream current = ExecutionStream.empty();
    for (var step : steps) current = step.execute(current, ctx);
    return current;
}
```

```java
// FilterStep — explicit hook:
@Override
public void registerExpressions(CommandContext ctx) {
    ctx.registerBooleanExpression(whereClause.getBaseExpression());
}
```

We grepped the executor. FilterStep is the only one where this pattern
bites. Everything else is per-row in lambdas, or after upstream is drained.

### Subtlety 2: AST thread safety

Steps don't copy AST nodes; they reference them. The AST has lazy-cache
fields.

**[CODE — 12 s]**

```java
// SQLProjectionItem — today:
protected Boolean aggregate;          // ← lazy, written on first call
private Boolean cachedIsAll;

public boolean isAggregate(DatabaseSessionEmbedded session) {
    if (aggregate != null) return aggregate;          // read
    aggregate = expression.isAggregate(session);      // compute + WRITE
    return aggregate;
}
```

Today this is fine — deep-copy gives each execution its own AST. After the
refactor, two threads call `isAggregate` on the same shared node. On x86
this is benign (strong memory model). On ARM, thread B can see thread A's
reference to a `Collate` object before A finishes constructing it — unsafe
publication, NPE.

**The fix is two-layered:**

**[CODE — 12 s]**

```java
// SQLProjectionItem — after:
protected volatile Boolean aggregate;          // ← volatile = safe publication
private volatile Boolean cachedIsAll;

// Plus pre-computation at cache-put time:
public class YqlExecutionPlanCache {
    public void putInternal(String sql, ExecutionPlan plan, ...) {
        plan.precomputeLazyCaches(db);   // ← walks AST, fills lazy fields
        cache.put(sql, plan);             // now no thread races at runtime
    }
}
```

`volatile` gives publication semantics — when thread A writes, thread B
sees a fully-initialized object. Pre-computation goes further — it fills
the lazy fields on the planning thread, before any other thread can touch
the plan, so at runtime the field is read-only.

Volatile is the safety net for fields that genuinely need lazy
initialization at runtime — Collate strategy, for instance, depends on
session locale and can't be computed at plan time.

### Subtlety 3: runtime plan mutation

We have exactly one place in production that mutates a cached plan:
`MaterializedLetGroupStep` swaps the first step of a sub-plan per LET entry.

**[CODE — 12 s]**

```java
// MaterializedLetGroupStep — today (works because cache returned a copy):
var outerPlan = entry.fullQuery.createExecutionPlan(subCtx, profilingEnabled);
if (outerPlan instanceof SelectExecutionPlan selectPlan
    && selectPlan.getSteps().getFirst() instanceof SubQueryStep) {
    selectPlan.replaceFirstStep(listSource);   // ← MUTATES cached plan
}
new LocalResultSet(session, outerPlan);
```

```java
// AFTER — withFirstStepReplaced returns a new plan:
var outerPlan = entry.fullQuery.createExecutionPlan(subCtx, profilingEnabled);
if (outerPlan instanceof SelectExecutionPlan selectPlan
    && selectPlan.getSteps().getFirst() instanceof SubQueryStep) {
    outerPlan = selectPlan.withFirstStepReplaced(listSource);  // ← new derivative
}
new LocalResultSet(session, outerPlan);
```

```java
// SelectExecutionPlan — new method:
public SelectExecutionPlan withFirstStepReplaced(ExecutionStep replacement) {
    var newSteps = new ArrayList<>(steps);
    newSteps.set(0, replacement);
    return new SelectExecutionPlan(newSteps, statement, profilingEnabled);
}
```

The cached plan stays pristine. The derivative is ephemeral — it doesn't
go back into the cache (its first step holds materialized data and would
fail `canBeCached()` anyway). One call site changes; well-tested.

---

## [15:00 — Removed dead code]

While we're refactoring, we delete some other things that have been dead.

`toGenericStatement` we already discussed — zero callers, biggest hotspot.
Phase one removes it for a clean three-point-six percent.

`sendTimeout` is a propagation chain that walks backward through previous
pointers.

**[CODE — 8 s]**

```java
// AbstractExecutionStep — today:
public void sendTimeout() {
    if (prev != null) prev.sendTimeout();        // walk backward
}

// LimitExecutionStep, SkipExecutionStep, DistinctExecutionStep override:
@Override public void sendTimeout() { /* no-op */ }
```

Three steps override it as deliberate no-ops. The two places that actually
call it — `OrderByStep`, `AggregateProjectionCalculationStep` — call it
inside their accumulation loops without breaking out afterward. Real
timeout enforcement happens at the stream layer. The backward chain is an
artifact, not a feature.

`reset()` is never called on cached plans. `prev`/`next` go away from
steps. `setSteps()` has no callers. All gone.

---

## [16:30 — Implementation plan]

We deliver this in five phases. Each phase compiles and passes tests on
its own.

**[CODE — 10 s]**

```
Phase 1: Dead code + scaffolding
         - delete toGenericStatement (3.6% CPU win, zero risk)
         - new ExecutionStep interface alongside old
         - SelectExecutionPlan.start with fallback for un-migrated steps
         - cache skips copy when all steps migrated

Phase 2: ~45 stream transformers (mechanical)

Phase 3: ~20 source steps + MaterializedLetGroupStep call site

Phase 4: 7 stateful steps (the careful one)
         - hash joins, MatchStep, IndexOrderedEdgeStep
         - EdgeTraversal.withFreshCache

Phase 5: Cleanup
         - remove legacy fallback, sendTimeout, reset, prev/next
         - volatile + pre-computation on AST
         - deserialize → constructor
         - final on remaining fields
```

Each phase is its own PR. If something goes sideways, we stop after any
phase and we're still in a working, faster-than-today state.

---

## [18:00 — Risks]

Three risks worth flagging.

The FilterStep correctness bug — the silent one — is the biggest landmine.
The pre-registration phase fixes it. We have explicit tests for the
GlobalLetQuery materialization path. But "silent wrong results in specific
query shapes" is exactly the kind of bug that escapes generic test suites.
Targeted tests before phase four ships.

The ARM concurrency bug on AST lazy fields is the second silent risk.
Volatile plus pre-computation gives defense in depth. Concurrent-execution
stress tests on M-series and Graviton, compared to x86.

The deserialize API break is internal-only.

**[CODE — 10 s]**

```java
// BEFORE — instance method mutates this:
public class FetchFromIndexStep extends AbstractExecutionStep {
    protected IndexSearchDescriptor desc;     // ← not final
    public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
        ExecutionStepInternal.basicDeserialize(fromResult, this, session);
        desc = new IndexSearchDescriptor(...);
    }
}

// AFTER — deserializing constructor:
public class FetchFromIndexStep implements ExecutionStep {
    private final IndexSearchDescriptor desc;  // ← truly final
    public FetchFromIndexStep(Result fromResult, DatabaseSessionEmbedded session) {
        var index = session.getSharedContext().getIndexManager()
            .getIndex(fromResult.getProperty("indexName"));
        this.desc = new IndexSearchDescriptor(index, ...);
    }
}

// Reflection call site changes from newInstance() + deserialize()
// to getConstructor(Result.class, session.class).newInstance(...)
```

Ten step classes affected, two reflection call sites updated. Mechanical
but real work.

The biggest non-technical risk is the change size. A hundred and twenty
files. We mitigate by phasing — each phase ships independently.

---

## [19:30 — Threading model in one picture]

Before I close, the concurrency picture.

**[CODE — 12 s]**

```
Thread A                    Cached Plan                     Thread B
────────                    ───────────                     ────────
plan.start(ctxA)   ──────►  FetchFromClass    ◄──────       plan.start(ctxB)
                            Filter
ExecutionStream a           Projection                      ExecutionStream b
(decorator chain a)         OrderBy                         (decorator chain b)
                            Limit
ctxA.statsMap[step]                                         ctxB.statsMap[step]
                                  ▲
                          SHARED, IMMUTABLE
                          - final fields only
                          - AST: read-only
                            structural fields,
                            volatile lazy caches
```

Each `start()` allocates ~13 stream decorators on its own call stack.
Local variables in stateful step `execute()` methods live on those stacks
and are never observed by other threads. The plan and its steps are
read-only. `StepStats` for profiling lives in the per-thread
`CommandContext`, not in the step — same step instance as the key, but
two different maps in two different `CommandContext` objects.

No mutexes. No synchronization. Threading is solved by the model.

---

## [21:00 — Take-aways]

To wrap up.

The hot path is dead code. The single largest CPU hotspot we've been
carrying builds a string nobody reads. Phase one alone removes it.

Stateful steps are the real concurrency cost. Six classes contain all the
genuinely-mutable state. Sixty-five-plus other classes are mechanical
migrations.

Threading is solved by the model, not by locks. We don't add a single
mutex. Steps become pure functions, per-execution state lives on the call
stack of execute, and `CommandContext` is naturally per-thread. Sharing is
safe by construction.

And the biggest performance win may not be the six percent. It's
plan-cache eligibility for query shapes that currently bypass the cache
entirely because of mutable state in their steps. Index-ordered MATCH is
the headline today; other steps could follow.

Happy to take questions.

---

## Speaker tips

- **Pace:** ~150 words/min plus ~3 min of code-pause cues gives ~22–25 min
  total. If the room is patient, linger on the stateful-step before/after
  ([category 3.3]) — that's the heart of the design.
- **Pauses:** code blocks have explicit `[CODE — N s]` cues. Don't read
  code aloud. Let people read it. Add a one-sentence verbal summary after
  the pause: *"Notice the field is gone in the After version."*
- **Hardest section to deliver verbally:** AST thread safety. If the
  audience doesn't know the ARM memory model, sketch on a whiteboard:
  field = address + contents, both can desynchronize.
- **Likely pushback and the answers:**
  - *"Six percent is small."* — point to plan-cache eligibility (closing).
  - *"Refactor is too big."* — point to phasing, and phase one's
    independence.
  - *"Why functional, not just immutable + non-copy?"* — the functional
    shape is the *consequence* of "shared + stateless steps", not a
    separate decision. Once steps don't carry topology, the plan must
    drive iteration; once the plan drives iteration, the natural step
    signature is `execute(upstream, ctx) → stream`.
  - *"Why a List, not a linked list?"* — topology must live outside steps;
    `List.copyOf` is cheap to copy-on-write; sequential read favors arrays;
    LinkedList's only advantage (middle insert) is unused.
  - *"Why volatile AND pre-compute?"* — pre-compute eliminates the race;
    volatile catches fields that can't be pre-computed (locale-dependent)
    and serves as safety net for missed pre-computation.
- **Don't pause for questions mid-talk.** Say "I'll come to that" and
  trust that the answer is in the Risks or Take-aways section. Most
  questions are.

---

## One-liner if you have 30 seconds

> *We're spending 6% of CPU deep-copying plans on every cache hit because*
> *steps embed mutable state. We make steps pure functions, plans*
> *immutable, and the cache returns the same object to every caller. The*
> *biggest hotspot is dead code we can delete in phase 1. The biggest*
> *performance win may not even be the 6% — it's that index-ordered MATCH*
> *queries become cacheable for the first time.*
