# Functional Execution Steps
## Zero-Copy Plan Cache for the YouTrackDB SQL Executor

A design walkthrough.

Audience: engineers familiar with YTDB's SQL executor (steps, plans,
`CommandContext`, the WAL is *not* relevant here). Sections are self-contained
so an outsider can follow if they read in order.

---

## TL;DR

We change the SQL execution model from **stateful, deep-copied plans** to
**pure-function steps in immutable plans**, so the plan cache can store and
return the same object across concurrent executions without copying.

**Why:** LDBC IS1 (8 threads) profile shows **~6% CPU** spent in defensive
copying on every plan cache hit. The largest single hotspot
(`SimpleNode.toGenericStatement`, ~3.6%) is also **dead code** — the string it
produces is never read.

**Side effect:** MATCH queries that today opt out of plan caching entirely
(`canBeCached() = false` on `IndexOrderedEdgeStep`) become cacheable, which is
likely a much bigger win than the 6% on parse-heavy workloads.

**Cost:** ~76 step classes touched, ~120 files, 5 phases of incremental
delivery, each phase compiles and passes tests on its own.

---

## How YouTrackDB executes a query (background)

A query goes through three phases:

```
SQL text
  │
  ▼
Parser  ──────►  AST  (~100s of nodes for a typical MATCH)
  │
  ▼
Planner ──────►  ExecutionPlan
                 = ordered chain of ExecutionStep instances
                   (FetchFromClass → Filter → Projection → OrderBy → Limit)
  │
  ▼
plan.start(ctx) ──────►  ExecutionStream
                         = chain of stream decorators (filter/map/sort/...)
                         = pull-based, lazy
```

The **plan cache** (`YqlExecutionPlanCache`, Guava-backed) memoizes the plan:
key = SQL text, value = `ExecutionPlan`. A cache hit skips parsing + planning,
which together can dominate query latency on simple lookups.

---

## What an ExecutionStep is today

A doubly-linked list embedded in the steps themselves:

```java
abstract class AbstractExecutionStep implements ExecutionStepInternal {
    // Per-execution mutable state:
    protected CommandContext ctx;             // execution context
    protected ExecutionStepInternal prev;     // upstream neighbor
    protected ExecutionStepInternal next;     // downstream neighbor
    protected boolean alreadyClosed;

    // Immutable configuration (varies by subclass):
    // e.g. SQLWhereClause whereClause, long limit, ...

    @Override
    public final ExecutionStream start(CommandContext ctx) {
        this.ctx = ctx;
        return internalStart(ctx);            // overridden per step
    }

    protected abstract ExecutionStream internalStart(CommandContext ctx);
}
```

Execution model is **backward-recursive**: the terminal step is started first;
it asks its predecessor for upstream via `prev.start(ctx)`; the predecessor
recurses upward; the source step finally produces a stream that returns up
the call stack.

---

## Why deep-copy on every cache hit

Two threads sharing the same `FilterStep` instance would race on:
- `this.ctx` — written in `start()`, read in `internalStart()`
- `prev`/`next` — set during plan construction; today the same instance can
  appear in only one chain at a time
- Subclass mutable fields — see the gory examples in the next slide

The cache copies the plan eagerly to keep each execution's state private:

```
cache.get("SELECT ...")
  → plan.copy(ctx)
    → for each step: step.copy(ctx) → re-wire prev/next
    → deep-copy AST nodes recursively (SQLWhereClause.copy(),
      SQLProjection.copy(), SQLOrderBy.copy(), ...)
  → return cloned plan with fresh state
```

Per cache hit, this is ~130 allocations. Three hot AST traversals show up in
the profile as separate functions (`toGenericStatement`,
`SQLSuffixIdentifier.copy`, `SelectExecutionPlan.copyOn`).

---

## The profile (LDBC IS1, 8 threads, sf 1)

| Hotspot                              |  CPU %  | What happens                                     |
|--------------------------------------|---------|--------------------------------------------------|
| `SimpleNode.toGenericStatement`      | **3.6%**| Full AST traversal building an unused string     |
| `SQLSuffixIdentifier.copy`           |  1.5%   | Recursive deep-copy of AST leaf nodes            |
| `SelectExecutionPlan.copyOn`         |  1.0%   | Orchestrates copying step chain + AST            |

`toGenericStatement` is the most striking number: **a 3.6% hotspot that does
nothing**. The string it builds is never read by any production code path.
`getGenericStatement()` has zero callers.

---

## The target model

```java
// Step interface — a pure function:
interface ExecutionStep {
    ExecutionStream execute(ExecutionStream upstream, CommandContext ctx);
    boolean canBeCached();
    String prettyPrint(int depth, int indent);
}

// Plan — immutable list of steps:
class SelectExecutionPlan {
    private final List<ExecutionStep> steps;       // List.copyOf

    public ExecutionStream start(CommandContext ctx) {
        ExecutionStream current = ExecutionStream.empty();
        for (var step : steps) {
            current = step.execute(current, ctx);  // forward loop
        }
        return current;
    }
}

// Cache — zero copies:
ExecutionPlan getInternal(String sql, ...) {
    return cache.getIfPresent(sql);                // same object
}
```

Three structural shifts:
1. Steps lose `prev`/`next`/`ctx`/`alreadyClosed` — they become stateless.
2. Plan execution goes from **backward recursion** to **forward iteration**.
3. Cache returns the same object; multiple threads share it.

---

## Where the savings come from

| Component                                     | Before   | After |
|-----------------------------------------------|----------|-------|
| `toGenericStatement` AST string build         | 1 walk   | **0** (removed entirely) |
| Step instances (copy)                         | 10–15    | **0** (shared) |
| AST node deep copies                          | 100+     | **0** (shared) |
| `EdgeTraversal` per MatchStep                 | (in copy)| 1 lightweight wrapper per execution |
| Stream decorators                             | 10–15    | 10–15 (unchanged) |
| **Total allocations per cache hit**           | **~130** | **~13** |

Plus: `IndexOrderedEdgeStep` (PR #880) and similar gain `canBeCached() = true`,
unlocking plan caching for query shapes that currently re-plan from scratch
on every invocation.

---

## Step migration: four categories

The hard part is migrating ~76 step classes. They fall into four patterns:

| Category                    | Count | Migration | Difficulty |
|-----------------------------|-------|-----------|------------|
| 3.1 Stream transformers     | ~45   | Mechanical: `internalStart(ctx)` → `execute(upstream, ctx)` | Trivial |
| 3.2 Source steps            | ~20   | Drain upstream via `upstream.consume(ctx)`, then create own stream | Easy |
| 3.3 Stateful steps          |   6–7 | Move instance fields to local variables in `execute()`, capture in stream closures | Hard |
| 3.4 Control-flow steps      |    4  | Leave as-is — script plans, never cached | Zero |

The next slides drill into each.

---

## 3.1 Stream transformers — mechanical

The vast majority. They take an upstream stream, transform it, return it.

```java
// FilterStep — BEFORE:
protected ExecutionStream internalStart(CommandContext ctx) {
    var resultSet = prev.start(ctx);
    return resultSet.filter(this::matches);
}

// FilterStep — AFTER:
public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
    return upstream.filter(this::matches);
}
```

The change is rote:
1. Rename `internalStart` → `execute`, add `upstream` parameter.
2. Replace `prev.start(ctx)` with `upstream`.
3. Delete the `copy()` method.

Applies to: `FilterStep`, `ProjectionCalculationStep`, `OrderByStep`,
`LimitExecutionStep`, `SkipExecutionStep`, `DistinctExecutionStep`,
`UnwindStep`, `ExpandStep`, `BatchStep`, ~36 others.

---

## 3.2 Source steps — drain then create

Source steps **create** a stream from a data source (a class scan, an index
scan, a list of RIDs). They don't have a real upstream — but they call
`prev.start(ctx).close(ctx)` anyway, to drain steps that exist for side effects
(e.g. `GlobalLetExpressionStep` writing variables into `ctx`).

```java
// FetchFromClassExecutionStep — BEFORE:
protected ExecutionStream internalStart(CommandContext ctx) {
    if (prev != null) {
        prev.start(ctx).close(ctx);          // drain & close
    }
    return createClassScanStream(ctx);
}

// FetchFromClassExecutionStep — AFTER:
public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
    upstream.consume(ctx);                    // new convenience method
    return createClassScanStream(ctx);
}
```

`ExecutionStream.consume(ctx)` is a new `default` method:

```java
default void consume(CommandContext ctx) {
    try { while (hasNext(ctx)) next(ctx); }
    finally { close(ctx); }
}
```

Applies to: `FetchFromClassExecutionStep`, `FetchFromIndexStep`,
`FetchFromRidsStep`, `EmptyStep`, `CartesianProductStep`, `ParallelExecStep`,
~14 others.

---

## 3.2b Eager-drain-with-data (sub-pattern)

A subset of source-like steps **read data from upstream** before becoming a
source. `IndexOrderedEdgeStep` (PR #880, single-source mode) and
`MaterializedLetGroupStep` are the canonical examples.

```java
// IndexOrderedEdgeStep single-source mode — AFTER:
public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
    if (!upstream.hasNext(ctx)) {
        upstream.close(ctx);
        return ExecutionStream.empty();
    }
    var sourceRow = upstream.next(ctx);    // capture data
    upstream.close(ctx);                    // single-source: ≤1 row guaranteed
    return processSourceRow(sourceRow, ctx); // become source parameterized by sourceRow
}
```

This is neither a pure transformer (3.1) nor a discard-upstream source (3.2).
Worth calling out as a distinct pattern in the design.

The multi-source variant of `IndexOrderedEdgeStep` collects **all** upstream
rows into a `sourceMap` before scanning the index — same pattern, larger
local state.

---

## 3.3 Stateful steps — fields to closures

Six classes today (seven after PR #880) hold per-execution mutable instance
fields: hash sets, RID maps, traversal caches. These are the **interesting**
migrations because their state cannot be made `final` without thought.

The recipe: move instance fields to local variables inside `execute()`, capture
them in stream closures, and use `stream.onClose()` for cleanup.

```java
// HashJoinMatchStep — BEFORE:
@Nullable private Set<JoinKey> hashSet;            // shared = race
@Nullable private Map<JoinKey, List<Result>> hashMap;

protected ExecutionStream internalStart(CommandContext ctx) {
    hashSet = buildHashSet(ctx);                    // write to instance field
    return prev.start(ctx).filter(row -> hashSet.contains(...));
}

public void close() { hashSet = null; hashMap = null; super.close(); }
```

```java
// HashJoinMatchStep — AFTER:
public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
    var builtSet = buildHashSet(ctx);               // local variable
    return upstream
        .filter(row -> builtSet.contains(...))      // closure captures builtSet
        .onClose(c -> builtSet.clear());            // cleanup
}
// No instance fields. No close() override.
```

Each call to `execute()` creates a fresh `builtSet` on its own stack frame.
Concurrent executions never see each other's set. The `.onClose()` callback
runs when the resulting stream is closed, propagating cleanup up the decorator
chain.

---

## 3.3 (cont.) — MatchStep with a cache

`MatchStep` holds an `EdgeTraversal` whose `cache` field (a `HashMap` with no
synchronization) is written during traversal. Sharing it across threads = data
race + correctness bug (cache key depends on `CommandContext` variables that
differ per execution).

Solution: a per-execution copy of the traversal that **shares all immutable
config but has a fresh empty cache**.

```java
// EdgeTraversal:
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

// MatchStep — AFTER:
public ExecutionStream execute(ExecutionStream upstream, CommandContext ctx) {
    var execEdge = edge.withFreshCache();
    return upstream.flatMap((row, c) -> createTraverser(row, execEdge).toStream(c));
}
```

Cost: one tiny allocation per `MatchStep` per execution. Compare with today's
full plan deep-copy.

---

## 3.4 Control-flow steps — leave alone

`IfStep`, `ForEachStep`, `RetryStep`, `ScriptLineStep` live in
`ScriptExecutionPlan` / `IfExecutionPlan`, not in `SelectExecutionPlan`. They:
- have a different execution model (loop, retry, branch),
- are typically not cached (`canBeCached() = false` for script plans),
- gain nothing from migration.

They stay on `ExecutionStepInternal`. The migration is **deliberately
incomplete here** — pragmatism, not oversight.

---

## Backward recursion → forward iteration

Today: `lastStep.start(ctx)` → recursive `prev.start(ctx)` upstream → source
step produces stream → returns through the call chain.

Tomorrow: `for (step : steps) current = step.execute(current, ctx);`

```
BACKWARD (today, recursive)               FORWARD (after, iterative)

  Limit.start                                source.execute(empty, ctx)
    ↓ prev.start                               ↓ current
  OrderBy.start                              filter.execute(current, ctx)
    ↓ prev.start                               ↓ current
  Filter.start                               sort.execute(current, ctx)
    ↓ prev.start                               ↓ current
  FetchFromClass.start                       limit.execute(current, ctx)
    ← stream returns up                        ↓ current → result
```

Both produce identical stream chains. They differ only in **the order
in which steps' setup code runs**.

---

## The pivot's hidden trap: FilterStep ↔ GlobalLetQueryStep

`FilterStep.internalStart()` registers its WHERE expression in `ctx`
**before** calling `prev.start(ctx)`. Why? Because `GlobalLetQueryStep`
upstream reads `ctx.getParentWhereExpressions()` to decide whether to
**materialize** its result into a `List` (vs. a single-use iterator).

```java
// FilterStep (today):
protected ExecutionStream internalStart(CommandContext ctx) {
    ctx.registerBooleanExpression(whereClause.getBaseExpression());  // BEFORE
    var rs = prev.start(ctx);                                         // upstream
    return rs.filter(...);
}
```

In **backward** order this works: FilterStep registers, then upstream sees the
registration. In **forward** order it breaks: GlobalLetQueryStep runs first,
sees an empty registration list, picks single-use iterator, and produces
**wrong query results** when FilterStep later filters from an exhausted iterator.

This is a *silent correctness bug*, not a crash.

---

## Solution: a pre-registration phase

Two-phase `start()`:

```java
public ExecutionStream start(CommandContext ctx) {
    // Phase 1: pre-register everything (cheap, side-effect only)
    for (var step : steps) {
        step.registerExpressions(ctx);
    }

    // Phase 2: forward execute loop
    ExecutionStream current = ExecutionStream.empty();
    for (var step : steps) {
        current = step.execute(current, ctx);
    }
    return current;
}
```

```java
// ExecutionStep interface:
default void registerExpressions(CommandContext ctx) {
    // no-op for ~75 of 76 steps
}

// FilterStep override:
@Override public void registerExpressions(CommandContext ctx) {
    ctx.registerBooleanExpression(whereClause.getBaseExpression());
}
```

After Phase 1 the entire `ctx` is set up before any step's `execute()` runs.
Cross-step communication via `ctx` no longer depends on init order.

---

## Are there other backward-order dependencies?

Grep all `ctx.set*` / `ctx.register*` inside `internalStart()`:

| Step | Call site | Affected? |
|------|-----------|-----------|
| `FilterStep.registerBooleanExpression` | **before** `prev.start()` | **Yes** — solved above |
| `GlobalLetExpressionStep.ctx.setVariable` | **after** drain | No |
| `MatchPrefetchStep.ctx.setVariable` | **after** drain | No |
| `ProjectionCalculationStep.ctx.setSystemVariable(VAR_CURRENT)` | per-row in lambda | No (in stream, not init) |
| `IndexOrderedEdgeStep.setSystemVariable(VAR_INDEX_ORDERED_PRE_SORTED)` (PR #880) | eager in `execute()`, before returning stream | No — natural in forward order |
| Any other `ctx.set*` | per-row in stream lambda | No |

**`FilterStep` is the only one needing the pre-registration phase.**
`IndexOrderedEdgeStep`'s flag actually works *more naturally* in forward order
than in backward (where the comment in OrderByStep needs rewriting after
migration).

---

## AST thread safety: volatile + pre-computation

Steps after migration are stateless. But the **AST nodes** they reference
have **lazy-cache fields** that get written during execution:

```java
// SQLProjectionItem (today):
public boolean isAggregate(DatabaseSessionEmbedded session) {
    if (aggregate != null) return aggregate;
    aggregate = computeAggregate(session);  // race when shared across threads
    return aggregate;
}
```

On x86 this is benign (strong memory model). On ARM, thread A's write may not
be visible to thread B — silent, architecture-dependent bug.

Two complementary defenses:
1. **Pre-compute** lazy fields at plan-cache-put time, before threads can race
   on them. Most lazy fields are pure functions of the AST, computable eagerly.
2. **`volatile`** on the field as a safety net for fields that genuinely
   compute lazily at runtime (`SQLOrderByItem.collateStrategy` depends on
   session locale).

---

## Lazy-field inventory

| Field                                | Class                | Pre-computable? | Strategy |
|--------------------------------------|----------------------|-----------------|----------|
| `cachedIsAll`                        | `SQLProjectionItem`  | Yes (syntactic) | pre-compute + volatile |
| `aggregate`                          | `SQLProjectionItem`  | Yes (registry lookup) | pre-compute + volatile |
| `excludes`                           | `SQLProjection`      | Yes (AST scan)  | pre-compute + volatile |
| `cachedIsExpand`                     | `SQLProjection`      | Yes             | pre-compute + volatile |
| `flattened` (DNF)                    | `SQLWhereClause`     | Yes             | pre-compute + volatile |
| `collateStrategy`, `stringCollator`  | `SQLOrderByItem`     | **No** — locale-dependent | volatile only (benign race) |
| `previewPlan`                        | `LetQueryStep`       | No — EXPLAIN-only | volatile only |

Cost: one memory barrier per read/write on a `Boolean`/object reference.
Negligible vs. eliminating ~120 allocations per execution.

A new javadoc contract on `SimpleNode` makes the rule explicit:
> *Structural fields (set by the parser) must NOT be mutated after plan
> construction. Only volatile lazy-cache fields may be written during
> execution; their writes are benign races that always produce the same value.*

---

## Plan construction: chain → builder + freeze

Today the planner mutates the plan incrementally:

```java
result.chain(new FetchFromClassExecutionStep(...));
result.chain(new FilterStep(...));      // sets prev/next pointers
result.chain(new ProjectionCalculationStep(...));
```

Tomorrow: build a plain `ArrayList`, then freeze:

```java
var steps = new ArrayList<ExecutionStep>();
steps.add(new FetchFromClassExecutionStep(...));
steps.add(new FilterStep(...));
steps.add(new ProjectionCalculationStep(...));
// ...
var plan = new SelectExecutionPlan(steps, statement.getOriginalStatement());
//        ↑ ctor does List.copyOf — immutable from here on
```

`prev`/`next` pointers vanish from steps. `lastStep` field vanishes from the
plan. `chain()`, `setSteps()`, `replaceFirstStep()` are removed from the API.

---

## Why a `List`, not a linked list

Today's model **was** a linked list — `prev`/`next` were embedded in the steps.
The functional model removes them, and chooses an external `List<ExecutionStep>`
in the plan instead. Reasons:

- **Topology must live outside steps.** A shared step cannot carry topology in
  its fields — two concurrent executions would need different topologies in
  the same instance. `List<ExecutionStep>` puts topology in the plan, leaving
  the step a stateless function.
- **Read-heavy, sequential access pattern.** `start()` does `for (step : steps)`
  from index 0 to N. ArrayList wins on cache locality and per-element overhead.
- **Cheap copy-on-write.** `withFirstStepReplaced()` (next slide) is one
  ArrayList copy + one new plan allocation.
- **No middle inserts.** Plan is immutable post-construction.
  LinkedList's only advantage (O(1) middle insert) isn't used.

---

## Runtime plan mutation: the only real case

One spot mutates a *cached* plan today: `MaterializedLetGroupStep` swaps
`SubQueryStep` with `ListSourceStep` per LET entry, via
`SelectExecutionPlan.replaceFirstStep()`. It works today only because
`cache.get()` returns a copy.

Replacement in the new model:

```java
public SelectExecutionPlan withFirstStepReplaced(ExecutionStep replacement) {
    var newSteps = new ArrayList<>(steps);
    newSteps.set(0, replacement);
    return new SelectExecutionPlan(newSteps, statement, profilingEnabled);
}
```

**Important call-site change:** today `replaceFirstStep` mutates in place; the
caller continues using the same variable. After migration, callers must use
the *returned* derivative:

```java
var patched = selectPlan.withFirstStepReplaced(listSource);
new LocalResultSet(session, patched)   // not selectPlan
```

The derivative is ephemeral — it does **not** go back into the plan cache (it
holds materialized data; `canBeCached()` would return false anyway).

Other call sites: `chain()` during planning and during `deserialize()` —
both build-phase patterns, both replaced by `steps.add()` + final ctor.

---

## Plan close lifecycle

Today closing a result set propagates close in two directions:

```
LocalResultSet.close()
  → stream.close(ctx)      // through the stream decorator chain
  → executionPlan.close()  // through lastStep → prev → ... step chain
```

The functional model drops the second:

```
LocalResultSet.close()
  → stream.close(ctx)      // through the decorator chain only
```

Stream decorators (16 wrapper types in the codebase) propagate close down to
source streams that release index cursors and record iterators. Stateful
steps register cleanup via `stream.onClose(callback)`:

```java
return upstream.filter(...).onClose(c -> builtSet.clear());
```

`executionPlan.close()` becomes a no-op (or the call is removed from
`LocalResultSet`).

---

## Removed infrastructure

The migration is also a chance to delete dead code.

| Removed                          | Why                                                           |
|----------------------------------|---------------------------------------------------------------|
| `genericStatement` field + getter/setter | **Dead code.** No production caller. ~3.6% CPU savings. |
| `sendTimeout()` propagation chain | **Dead signal.** Default propagates backward; 3 overrides are explicit no-ops; producer-side calls don't break out. Real timeout enforcement uses `TimeoutException` + stream-level `timedOut` flag, both unaffected. |
| `reset()` on cached plans         | **Never called.** Only `InsertExecutionPlan` and `UpdateExecutionPlan` (neither cached) call it. `GqlExecutionPlan.reset()` has zero callers. |
| `prev` / `next` / `setPrevious` / `setNext` | No linked list. |
| `copy()` on every step            | No copying. |
| `copy(CommandContext)` on plan    | No copying. |
| `setStatement()` post-construction| `statement` is a final ctor param. |
| `setSteps()` (Select/Script/If)   | No callers in main code. |

---

## `sendTimeout()` deserves its own slide

It looks load-bearing. It isn't.

```java
// AbstractExecutionStep:
public void sendTimeout() {
    if (prev != null) prev.sendTimeout();   // walk backward
}

// LimitExecutionStep, SkipExecutionStep, DistinctExecutionStep override:
@Override public void sendTimeout() { /* no-op */ }
```

The three no-op overrides intentionally **block** the signal. That signal,
walking backward, is meant to tell the source step "we're timing out, stop
producing." But:
- No source step does anything with the signal.
- `OrderByStep` and `AggregateProjectionCalculationStep` *call* `sendTimeout()`
  inside their accumulation loops, but **don't break out of the loop afterward**.

Real timeout enforcement is at the **stream** layer:
- `TimeoutException` thrown by `AccumulatingTimeoutStep` / `TimeoutStep`
  unwinds the call stack.
- `timedOut = true` in `ExpireResultSet` / `TimeoutResultSet` makes
  `hasNext()` return `false`.

Both keep working unchanged. The backward chain is an artifact, not a feature.

---

## Deserialization: pattern shift to deserializing constructor

`deserialize()` is the one place where the "fields can be `final`" promise
collides with reality: today it's an instance method that mutates fields:

```java
// FetchFromIndexStep — today:
protected IndexSearchDescriptor desc;       // not final
public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    ExecutionStepInternal.basicDeserialize(fromResult, this, session);
    // ... read properties ...
    desc = new IndexSearchDescriptor(...);   // write to field
}
```

The migration replaces the instance method with a **deserializing constructor**:

```java
// FetchFromIndexStep — after:
private final IndexSearchDescriptor desc;   // truly final
public FetchFromIndexStep(Result fromResult, DatabaseSessionEmbedded session) {
    this.subSteps = ExecutionStepInternal.readSubSteps(fromResult, session);
    var index = session.getSharedContext().getIndexManager()
        .getIndex(fromResult.getProperty("indexName"));
    this.desc = new IndexSearchDescriptor(index, condition, additionalRange, null);
    // ...
}
```

Reflection call site changes from `newInstance() + deserialize()` to
`getConstructor(Result.class, session.class).newInstance(...)` — same dispatch
mechanism, different signature.

10 step classes affected, plus `SelectExecutionPlan`, `ScriptExecutionPlan`,
and the `InternalExecutionPlan` interface default.

---

## Profiling stays correct because of `CommandContext`

A natural worry: if two threads share the same step instance, do their
profile counts collide?

No. Profile statistics live in `CommandContext`, not in the step:

```java
class CommandContext {
    private IdentityHashMap<ExecutionStep, StepStats> statsMap = ...;

    public StepStats getStats(ExecutionStep step) { return statsMap.get(step); }
}
```

- Thread A executes the plan with `ctxA` → writes to `ctxA.statsMap`.
- Thread B executes the same plan with `ctxB` → writes to `ctxB.statsMap`.
- Both use the same step instance as the *key*, but they write to different
  maps living in different `CommandContext` objects.

No collision, no synchronization needed. `getCost()` changes from reading
`this.ctx` (gone) to taking `ctx` as a parameter:

```java
public long getCost(CommandContext ctx) {
    var stats = ctx.getStats(this);
    return stats != null ? stats.getCost() : 0L;
}
```

`profilingEnabled` stays as a final field on the step (used by
`prettyPrint()` in EXPLAIN), without the `setProfilingEnabled()` setter.

---

## Concurrency model in one slide

After migration:

```
Thread A                     Cached Plan                Thread B
────────                     ───────────                ────────
plan.start(ctxA)  ──────►   FetchFromClass             ◄─────  plan.start(ctxB)
                            Filter                     
ExecutionStream a           Projection                 ExecutionStream b
(decorator chain a)         OrderBy                    (decorator chain b)
                            Limit
ctxA.statsMap[step]                                    ctxB.statsMap[step]
                            ↑
                     SHARED, IMMUTABLE
                     - final fields only
                     - AST is read-only +
                       volatile lazy caches
```

Each `start()` allocates ~13 stream decorators on its own call stack. Local
variables in stateful step `execute()` methods live on those stacks and are
never observed by other threads. The plan and its steps are read-only.

---

## Coexistence with in-flight PRs

Three PRs intersect the migration:

| PR | Title | Status | Impact |
|----|-------|--------|--------|
| #863 | Lazy RID-only iteration for MATCH | merged | None — per-execution traverser, already isolated |
| #880 | Index-ordered MATCH (`IndexOrderedEdgeStep`) | **open** | New stateful step (§3.3); `canBeCached()` flips false → true after migration; `OrderByStep` gets `primaryKeySortedInput`/`indexOrderedUpstream` immutable config |
| #946 | Back-reference hash join (`BackRefHashJoinStep`, `Correlated…`, `InvertedWhile…`) | merged | Three new stateful steps, all category 3.3, mutable fields move to closures |

**PR #880 is the headline beneficiary.** Today its step has
`canBeCached() = false` — a single `false` in the chain makes the entire
plan ineligible for caching. After migration the step becomes shareable
(no mutable fields, `EdgeTraversal.cache` isolated per execution via
`withFreshCache()`, `Index` reference safe to share with `MetadataUpdateListener`
invalidating the cache on schema change). MATCH queries with index-ordered
traversal get cached for the first time.

---

## What `IndexOrderedEdgeStep` looks like today (PR #880)

```java
public class IndexOrderedEdgeStep extends AbstractExecutionStep {
    // All final — already pure config:
    private final String sourceAlias;
    private final String targetAlias;
    private final Index index;                    // resolved at plan time
    private final EdgeTraversal edge;              // has mutable cache
    private final SQLWhereClause targetFilter;     // immutable AST per §7
    // ... 10 more final fields ...

    @Override public boolean canBeCached() { return false; }   // ← will flip

    @Override public IndexOrderedEdgeStep copy(CommandContext ctx) {  // ← will be removed
        return new IndexOrderedEdgeStep(..., edge.copy(), ...);
    }
}
```

After migration:
- `canBeCached() → true`
- `copy()` removed (replaced by constructor only)
- `internalStart()` → `execute(upstream, ctx)`
- `EdgeTraversal.cache` per-execution via `edge.withFreshCache()`
- Eager-drain pattern (slide 12) for upstream consumption

---

## Implementation order: 5 phases

Each phase compiles and passes tests independently. Plan can be merged
phase-by-phase rather than as a single mega-PR.

1. **Dead code + scaffolding.** Remove `genericStatement` from 11 statement
   classes + plan classes. Introduce `ExecutionStep` interface with default
   `execute()` throwing. Update `SelectExecutionPlan.start()` to chain
   `execute()` calls with a fallback to legacy `internalStart()` for
   un-migrated steps. Update `YqlExecutionPlanCache` to skip `copy()` for
   plans where every step implements `execute()`.

2. **Stream transformer migration.** ~45 stateless transformers move from
   `internalStart` to `execute`. Mechanical.

3. **Source + sub-plan migration.** ~20 source steps. Adapt
   `replaceFirstStep` → `withFirstStepReplaced` call sites
   (`MaterializedLetGroupStep`).

4. **Stateful steps.** `BackRefHashJoinStep`, `InvertedWhileHashJoinStep`,
   `CorrelatedOptionalHashJoinStep`, `MatchStep`, `OptionalMatchStep`,
   `MatchPrefetchStep`, `IndexOrderedEdgeStep`, `MaterializedLetGroupStep`.
   Add `EdgeTraversal.withFreshCache()`. This is the careful phase.

5. **Cleanup + AST safety.** Remove `AbstractExecutionStep` (or strip to
   utility). Remove `sendTimeout`, `reset`, `setPrevious`/`setNext` from the
   interface. Add `volatile` + pre-computation to AST lazy fields. Make step
   fields `final`. Replace `deserialize()` with deserializing constructors.
   Remove `setProfilingEnabled` setter. Make `getSubSteps`/`getSubExecutionPlans`
   return unmodifiable lists.

---

## Files changed: ~120 across the executor

| Category                                      | Count | Change |
|-----------------------------------------------|-------|--------|
| `SimpleNode`                                  |   1   | Javadoc thread-safety contract |
| AST nodes with lazy fields                    |   4   | `volatile` + `precomputeCaches()` |
| `ExecutionStep` interface                     |   1   | New interface with `execute()` |
| Stream transformer steps                      |  ~45  | `internalStart` → `execute`; remove `copy()` |
| Source steps                                  |  ~20  | `internalStart` → `execute` + `upstream.consume()` |
| Stateful steps                                |   7   | Fields → closures; `EdgeTraversal.withFreshCache()` |
| Control-flow steps                            |   4   | Keep current model |
| `AbstractExecutionStep`                       |   1   | Remove or strip to utility |
| `EdgeTraversal`                               |   1   | Add `withFreshCache()` |
| `SelectExecutionPlan`                         |   1   | Forward loop; remove copy/copyOn/close; add `withFirstStepReplaced` |
| `YqlExecutionPlanCache`                       |   1   | Remove `copy()`; add `precomputeLazyCaches()` |
| Statement classes                             |  11   | Remove `setGenericStatement` calls |
| `LocalResultSet`                              |   1   | Remove `plan.close()` |
| `ExecutionStream`                             |   1   | Add `consume()` default method |
| Steps with `deserialize()`                    |  ~10  | Replace with deserializing constructor |
| `ExecutionStepInternal`                       |   1   | Remove `deserialize` default + `basicDeserialize`; add `readSubSteps`/`readSubPlans` |
| `InternalExecutionPlan`                       |   1   | Same shift on plan-level deserialize |
| Reflection call sites                         |   2   | `newInstance + deserialize` → ctor reflection |
| **Total**                                     | **~120** | |

---

## Risk and mitigation

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Silent correctness regression in FilterStep ↔ GlobalLetQueryStep | Medium without solution | Pre-registration phase + explicit `registerExpressions()` hook |
| ARM-only race on AST lazy fields | High without volatile | Pre-compute at cache-put time; `volatile` on every lazy field as safety net |
| Caller of `replaceFirstStep` keeps using stale variable | Low — only one call site (`MaterializedLetGroupStep`), test-covered | Code review checklist; rename forces touchpoint |
| Cached plan held after schema change | Low — existing `MetadataUpdateListener` handles it | Documented invariant; cache invalidation already plumbed |
| Deserialize API break for external tools | Low — `deserialize()` is internal | Internal-only API; no external surface |
| Phase 1's "fallback to `internalStart()`" code path masks bugs | Medium — keeps both paths alive briefly | Phase 1 ships with assertion that cached plans use only migrated steps; phase 2 removes fallback |
| Step constructor reads from `Index` (live) at plan time | None today, but a future regression possible | Document the contract that `Index` lookup belongs in the planner, not in `execute()` |

---

## Open questions worth flagging

1. **`getCost()` and prev's cost.** Today some `prettyPrint()` chains read
   `prev.getCost()`. After the prev pointer goes away, EXPLAIN needs an
   alternative — likely passing the parent plan's stats map.

2. **`OrderByStep.maxResults` is non-final but only set in the constructor.**
   Worth making truly `final` as part of phase 5 cleanup. Trivial change,
   caught by the audit.

3. **`Index` runtime calls during execution** (`index.size()`,
   `index.getHistogram()`, `index.iterateEntriesBetween()`). The plan claims
   "Index reference is immutable config" but these calls hit live storage.
   Safe under YTDB's storage-level read concurrency contract, but the
   contract should be documented at the step level.

4. **Plan-cache invalidation timing.** Pre-computation reads from
   `DatabaseSessionEmbedded` at cache-put time. If schema changes between
   plan construction and `MetadataUpdateListener` firing, a brief window of
   stale plan exists. Likely benign (resolved indexes survive a single query)
   but worth a regression test.

---

## Take-aways

1. **The hot path is dead code.** ~3.6% of CPU — the largest single hotspot
   on LDBC IS1 — is `toGenericStatement` building a string nobody reads.
2. **Stateful steps are the real cost.** Hash sets, traversal caches, RID
   maps — six classes contain all the genuinely-mutable state. The other
   ~65 classes are mechanical migrations.
3. **Threading is solved by the model, not by locks.** Steps become pure
   functions; per-execution state lives on the stack frame of `execute()`;
   `CommandContext` is naturally per-thread.
4. **The hidden landmine is FilterStep.** Backward-order initialization
   carries one subtle correctness dependency. Pre-registration phase
   defuses it.
5. **The biggest win may not be the 6%.** Plan-cache eligibility for
   index-ordered MATCH (and any other step that drops `canBeCached()`
   solely because of mutable fields) likely matters more on parse-heavy
   workloads.

---

## Reading order if you want to verify

1. `core/.../sql/executor/AbstractExecutionStep.java` — the legacy base.
2. `core/.../sql/executor/SelectExecutionPlan.java` — the legacy plan.
3. `core/.../sql/executor/YqlExecutionPlanCache.java` — current put/get with copy.
4. `core/.../sql/executor/match/EdgeTraversal.java` — the cache that needs
   per-execution isolation.
5. `core/.../sql/executor/match/MaterializedLetGroupStep.java` — the only
   runtime plan mutation we have to handle.
6. The three incoming PRs:
   - `pr-880` branch — `IndexOrderedEdgeStep`, `IndexOrderedPlanner`.
   - PR #946 (merged) — `BackRefHashJoinStep` and friends.
7. Profiling baseline: LDBC IS1 flame graph, 8-thread run.

---

## End

Questions, holes, second opinions welcome.

The design is meant to be merged in phases — phase 1 ships scaffolding and
dead-code removal alone (a clean +6% with no risk), then categories 3.1, 3.2,
3.3, and finally 3.4-or-cleanup. Each phase is reversible until phase 5
removes the fallback to `internalStart()`.
