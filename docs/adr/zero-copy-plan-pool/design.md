# Zero-Copy Plan Cache via Pooling — Design (v2)

> **Status:** proposal. Replaces v1 after critical review — see §17 for changes.
> **Scope:** YTDB embedded/server-side plan cache. Does not cover remote driver.

## 1. Goal

Eliminate the ~6% CPU spent on defensive deep-copying of cached execution
plans, without the full step-interface migration in `design-functional.md`
(105 files). Target: **zero allocations in the steady-state cache hit path**
for cacheable plans, reached after a warm-up period.

## 2. Core idea: share by pooling, not by immutability

`design-functional.md` makes plans shareable by making steps immutable, so
one plan serves many concurrent executions. This design achieves the same
**allocation profile** by keeping N plan instances per SQL in a bounded pool;
each borrower gets exclusive ownership.

Pooling preserves the current **single-owner** thread-safety model — steps
stay mutable, the existing `reset()` infrastructure becomes load-bearing,
and no new `ExecutionStep` interface is required.

### Honest comparison

| Aspect | design-functional (sharing) | this design (pooling) |
|-------|-----------------------------|-----------------------|
| Plan instances per SQL | 1 | `N` (bounded, typically 16) |
| Concurrency model | Shared concurrent access | Single-owner per borrow |
| Step mutable fields | Must be removed | Kept — cleared by `reset()` |
| Step interface migration | Required | None |
| Execution semantic (backward/forward) | Changed to forward | Kept backward |
| AST `volatile` on lazy fields | Required | **Also required** (§8.5) |
| AST pre-computation | Required | Not needed |
| `EdgeTraversal.withFreshCache()` | New API | Not needed (`reset()` clears) |
| Closure-captured state for stateful steps | Required pattern | Not needed |
| `reset()` in interface | Removed | **Central mechanism** |
| `sendTimeout()`, `close()` on steps | Removed | Kept |
| Files changed (estimate) | ~105 | ~25 |
| Cold-start cost | Same as today | Same as today |
| Steady-state allocations per request | ~13 (stream decorators) | ~13 (stream decorators) |
| New architectural invariant | "Steps are pure functions" | "reset() must be sound" |
| Reversibility of cache-layer change | Changed interface → no | Changed wrapper → yes, but step-level reset overrides stay |

**Both designs require AST volatile.** Pooling shares the AST across pooled
plan instances for the same SQL (because `SimpleNode.copy()` returns `this` —
see §5.2), so lazy AST fields are written concurrently by multiple
borrowers. The original v1 of this document misrepresented this as a
pooling advantage — corrected here.

## 3. Problem restated

`core/.../sql/parser/YqlExecutionPlanCache.java` (current):

```java
// putInternal:
var internal = (InternalExecutionPlan) plan;
internal = internal.copy(ctx);      // deep-copy: traverses steps + AST recursively
internal.close();                   // the copy is only a clean template
cache.put(statement, internal);

// getInternal:
var result = cache.getIfPresent(statement);
return result != null ? result.copy(ctx) : null;    // deep-copy on every hit
```

Every cache hit pays ~130 allocations (measured) — 10–15 step clones plus
100+ AST node copies plus the unused `genericStatement` string. This is the
2.5% `SQLSuffixIdentifier.copy` + 1.0% `copyOn` + 3.6% `toGenericStatement`
from LDBC IS1 profiling.

## 4. Proposed model

### 4.1 Cache shape

```java
class YqlExecutionPlanCache {
    Cache<String, PlanPool> cache;              // Guava LRU keyed by SQL

    void putInternal(String sql, ExecutionPlan plan, DatabaseSessionEmbedded db) {
        var internal = (InternalExecutionPlan) plan;
        internal.reset(null);                   // prototype is perpetually clean
        var pool = new PlanPool(internal, db.getSchema().getVersion());
        cache.put(sql, pool);
    }

    InternalExecutionPlan borrow(String sql, CommandContext ctx, DatabaseSessionEmbedded db) {
        var pool = cache.getIfPresent(sql);
        if (pool == null) return null;
        if (pool.schemaVersion != db.getSchema().getVersion()) {
            cache.invalidate(sql);              // stale pool; force replan
            return null;
        }
        return pool.borrow(ctx);
    }

    void release(String sql, InternalExecutionPlan plan) {
        var pool = cache.getIfPresent(sql);
        if (pool != null) pool.release(plan);
        else plan.close();                      // entry evicted — discard
    }
}
```

### 4.2 Pool with bounded capacity (no counter race)

v1 used `ConcurrentLinkedQueue` + separate `AtomicInteger`, which has a race
between size-check and offer. v2 uses `LinkedBlockingDeque(maxSize)` — the
capacity bound is enforced atomically by the deque itself.

```java
class PlanPool {
    final InternalExecutionPlan prototype;              // clean, never executed
    final long schemaVersion;
    final LinkedBlockingDeque<InternalExecutionPlan> available;
    final int maxSize;

    PlanPool(InternalExecutionPlan prototype, long schemaVersion) {
        this.prototype = prototype;
        this.schemaVersion = schemaVersion;
        this.maxSize = 16;
        this.available = new LinkedBlockingDeque<>(maxSize);
    }

    InternalExecutionPlan borrow(CommandContext ctx) {
        var plan = available.pollFirst();               // LIFO — CPU-cache-friendly
        if (plan == null) {
            plan = prototype.copy(ctx);                 // cold clone — same as today
        } else {
            plan.bindContext(ctx);                      // re-wire ctx, no alloc
        }
        plan.markBorrowed();                            // CAS state transition (§8.4)
        return plan;
    }

    void release(InternalExecutionPlan plan) {
        try {
            plan.markReleased();                        // CAS BORROWED → POOLED
            plan.reset(null);                           // clear all per-execution state
        } catch (Exception e) {
            plan.close();                               // unsound state → discard
            return;
        }
        if (!available.offerFirst(plan)) {              // capacity atomic
            plan.close();                               // pool full → discard
        }
    }
}
```

Key properties:
- `offerFirst` returns `false` when full — **no counter drift**
- LIFO via `pollFirst`/`offerFirst` — recently-released plan has warmer caches
- Cold clone hits the exact path used today — fallback is never slower

### 4.3 Cold-start performance is unchanged

The first N requests for a SQL statement (where N = pool warm-up) pay the
same full deep-copy cost as today. Zero-copy is a **steady-state** property,
reached after the first burst. `cache_hit` metrics should distinguish
`pool_warm_hit` (no copy) from `pool_cold_miss` (full copy) to avoid false
steady-state readings.

Success metric §15 is explicit about this.

## 5. Phase 1 — Independent cheap wins

Land these first, **independently**. They deliver ~5% of the 6% CPU without
any pooling, and some of them (1.2) are prerequisites for pooling's
correctness.

### 5.1 Remove `genericStatement` (3.6% CPU)

`setGenericStatement(this.toGenericStatement())` is called on every
`createExecutionPlan()` — including on cache hits. The resulting string has
**zero production readers** (`getGenericStatement()` usages: test-only).

**Files:** 11 SQL statement classes + `SelectExecutionPlan.java`,
`ScriptExecutionPlan.java`, `InternalExecutionPlan.java`. ~15 files, 1 day.

**Risk:** none — dead code deletion. If any caller breaks, compilation fails.

### 5.2 AST volatile + `SimpleNode.copy()` returns `this` (1.5% CPU)

**This is not optional for pooling** — it is a prerequisite for pooling's
correctness (see §8.5). It is presented in Phase 1 because it also stands
alone as a CPU win.

Make AST nodes shared across all plan copies:

```java
// SimpleNode
@Override public SimpleNode copy() { return this; }  // was: deep-copy tree
```

Mark lazy cache fields `volatile` to make concurrent benign-race writes safe
on ARM's relaxed memory model:

```java
// SQLProjectionItem
private volatile Boolean aggregate;
private volatile Boolean cachedIsAll;

// SQLProjection
private volatile Set<String> excludes;
private volatile Boolean cachedIsExpand;

// SQLWhereClause
private volatile List<SQLAndBlock> flattened;

// SQLOrderByItem
private volatile Collate collateStrategy;
private volatile Collator stringCollator;
```

**Files:** `SimpleNode.java` + 4 AST classes. ~5 files, 1 day.

**Caller audit required:** grep for `.copy()` on AST nodes to verify no
caller mutates the return value. The AST is read-only post-parse by design,
but this audit is part of Phase 1.2 — not waived.

**Risk:** LOW after audit. Without audit: MEDIUM (hidden mutation → silent
corruption).

### 5.3 Drop throwaway copy on cache put

Current put path allocates a throwaway copy solely to produce a clean
template. With `reset()` call on the original, we skip the allocation:

```java
var internal = (InternalExecutionPlan) plan;
internal.reset(null);                          // mark as clean prototype
cache.put(sql, new PlanPool(internal, schemaVersion));
```

**Files:** `YqlExecutionPlanCache.java`. 1 file, 0.5 day.

**Risk:** depends on `reset()` completeness — Phase 2 property test is the
validation gate.

## 6. Phase 2 — `reset()` audit and the soundness invariant

Pooling's correctness depends on `reset()` restoring every step to a state
**observationally indistinguishable** from a freshly-constructed step.

### 6.1 The new architectural invariant

Adopting pooling means adopting a durable maintenance obligation:

> **Every `ExecutionStepInternal` implementation with per-execution mutable
> state MUST override `reset()` to null/clear all such fields. This invariant
> is enforced by `PlanPoolSoundnessTest`. New stateful steps must add cases
> to the test corpus.**

This is a **real architectural change** — v1 of this document called the
design "no architectural change", which was wrong. Pooling trades the cost
of rewriting steps (functional design) for the cost of maintaining reset
soundness forever. The tradeoff is favorable because the invariant is
mechanically testable, but it is not zero.

### 6.2 Existing `reset()` landscape

From `git grep "public void reset"` in `core/.../sql/executor/`:

| File | What `reset()` does | Complete? |
|------|---------------------|-----------|
| `ExecutionStepInternal.java:160` | Default no-op | OK for stateless steps |
| `AbstractExecutionStep.java` | `ctx = null`, `alreadyClosed = false`, no propagation | **Incomplete** — does not propagate to `prev` |
| `SelectExecutionPlan.java:107` | `steps.forEach(reset)` | OK |
| `FetchFromIndexStep.java` | `desc = null` | **Needs verification** — does `start()` re-resolve? |
| `MatchPrefetchStep.java` | Propagates to sub-plan | OK |
| `MatchFirstStep.java` | Propagates to sub-plan | OK |
| `AccumulatingTimeoutStep.java` | Timeout flag reset | OK |

### 6.3 Steps requiring reset audit (expanded from v1)

v1 listed 7 stateful steps. Grepping `this\.\w+ *=` inside `internalStart()`
and helper methods across all 76 step classes yields a larger set.

**High-confidence (known mutable state):**

| Step | Mutable fields | Current reset? | Action |
|------|----------------|----------------|--------|
| `HashJoinMatchStep` (PR #918) | `hashSet`, `hashMap` | None | Add override, null both |
| `CorrelatedOptionalHashJoinStep` (PR #918) | `neighborRids`, `lastCorrelatedRid` | None | Add override |
| `InvertedWhileHashJoinStep` (PR #918) | `reachableRids` | None | Add override |
| `IndexOrderedEdgeStep` (PR #880) | `edge.cache` | None | Add override via `edge.clearCache()` |
| `MatchStep` | `edge.cache` | None | Add override via `edge.clearCache()` |
| `MaterializedLetGroupStep` | `entryPlanCacheFlags` | None | Add override |
| `GlobalLetExpressionStep` | `executed` guard | None | Reset flag |
| `OrderByStep` | Accumulation buffer (`List<Result>`) | None | **Clear + release capacity** (§11 risk) |
| `AggregateProjectionCalculationStep` | Aggregator accumulators | None | Clear group state |
| `DistinctExecutionStep` | `RidSet` for dedup | None | Clear set |
| `GuaranteeEmptyCountStep` | Counter | None | Zero counter |
| `UnwindStep` | Iterator state | None | Null iterator |
| `BatchStep` | Batch buffer | None | Clear buffer |
| `SubQueryStep` | Cached sub-query results? | **Needs verification** | TBD after audit |
| `CartesianProductStep` | Per-branch iterator state | **Needs verification** | TBD |

**Low-confidence (stateless or already-handled):** Remaining ~60 classes —
must still be grep-audited, but expected to need no change.

**Audit methodology:**

```bash
# For each step class in core/.../sql/executor/:
for f in $(find core/.../sql/executor -name "*Step.java"); do
  echo "=== $f ==="
  grep -n "this\.\w\+ *= " "$f" | grep -v "= this\."  # assignments, not getters
done
```

Any assignment to `this.x` outside the constructor is candidate mutable
state. Cross-reference with whether it's set in `internalStart()` or helpers
called from it.

**Files:** expected ~15 step classes + `EdgeTraversal.java` (new
`clearCache()` method). Estimated **~15 files, 1.5 weeks** (not 3 days).

### 6.4 The reset-soundness property test

**This is the central safety net.** Without it, pooling's correctness is
unverifiable.

```java
@ParameterizedTest(name = "{0}")
@MethodSource("resetSoundnessCorpus")
public void resetIsSoundForQuery(String sql) {
    try (var db = openFreshDatabase()) {                // isolated tx
        loadFixtureData(db);
        var planner = db.getPlanner();

        // Run 1: fresh plan
        var plan1 = planner.createExecutionPlan(sql);
        var results1 = executeAndMaterialize(plan1, db);

        // Run 2: same plan instance, reset, executed again
        var plan2 = planner.createExecutionPlan(sql);   // same prototype
        executeAndMaterialize(plan2, db);               // warm up
        plan2.reset(null);
        var results2 = executeAndMaterialize(plan2, db);

        // Order-stable comparison — corpus restricted to ORDER BY or single-row
        assertResultsEqual(results1, results2);
    }
}
```

**Corpus construction:**
- Source: existing `core/src/test/.../sql/` test queries
- Filter: read-only (SELECT, MATCH, TRAVERSE only — no INSERT/UPDATE/DELETE)
- Filter: stable order (has explicit ORDER BY, OR single-row result, OR
  wrapped in set-comparison helper)
- Coverage requirement: at least one query per known stateful step class
  from §6.3
- Size: aim for 200, accept 150 if coverage is met

**Known limitations (from v1 review):**

1. **Non-determinism** — corpus excludes queries without stable order.
   Alternative: `assertResultsEqualAsSet()` helper that normalizes order.
2. **Side effects** — corpus is read-only. INSERT/UPDATE plans have
   `canBeCached() = false` and bypass pooling anyway.
3. **Tx isolation** — both runs in same transaction → identical snapshots.
4. **Single-threaded** — this test does NOT catch concurrency bugs.
   Separate concurrency test in §8.11.

**This test must pass on the full corpus before Phase 2 ships.**

### 6.5 Idempotency test

Reset may be called twice in edge paths (exception in release → retry):

```java
@Test
public void resetIsIdempotent() {
    for (var sql : corpus) {
        var plan = planner.createExecutionPlan(sql);
        executeAndMaterialize(plan, db);
        plan.reset(null);
        plan.reset(null);                               // idempotent
        var results = executeAndMaterialize(plan, db);
        assertResultsEqual(results, freshExecute(sql));
    }
}
```

## 7. Pool mechanics

### 7.1 Sizing: governed by plan lifetime, not execution time

**v1 error:** sized the pool by `RPS × execution_time`. The real dominator
is plan **lifetime** — from `borrow()` to `release()` — which extends
beyond execution time to include client read time (streaming, pagination,
slow poll, network hops).

Typical paginated query:
- Plan execution: 2 ms
- Client reads page: 30 ms (network + deserialize + app processing)
- **Plan lifetime: 32 ms, not 2 ms**

At 20K RPS per hot SQL × 32 ms lifetime = **640 concurrent borrowers**.
Pool sized 16 → 97.5% cold-clone rate → pooling delivers essentially nothing
for this workload shape.

### 7.2 Two sizing strategies

**Strategy A: small bounded pool, fast-drain queries only.** Pool captures
the subset of queries that drain fully within tens of microseconds (simple
SELECTs, point lookups). Pagination-style queries fall through to cold
path. Acceptable if measurement shows hot workload is drain-fast.

**Strategy B: adaptive sizing driven by metrics.** Start with 16, grow
on observed overflow:

```java
if (overflowDiscards > threshold && cacheHits > threshold) {
    maxSize = min(maxSize * 2, hardCap);    // hardCap e.g. 256
}
```

Grow on pool exhaustion during release (the overflow signal), shrink on
idle (no borrows for N seconds).

**Recommendation:** implement Strategy A in Phase 2 (simple, default `16`),
ship with explicit metrics, add Strategy B in Phase 3 if metrics show
cold-clone rate >20% on hot SQL.

### 7.3 Release timing — eager vs lazy

**Decision needed:** release on stream exhaustion (eager) or on
`LocalResultSet.close()` (lazy)?

- **Eager** (release when `stream.hasNext() == false` naturally):
  plan lifetime ≈ execution time. Pool sizing math works. But: if client
  reads partial results then closes, stream was never exhausted → still lazy.
- **Lazy** (release on close only):
  plan lifetime = execution + client read time. Pool needs to be large.
  Simpler, matches existing `close()` lifecycle.

**Proposal: lazy release** (matches current close semantics, no new
invariants), paired with explicit docs that pool sizing reflects lifetime.
Any eager-release optimization is Phase 3.

### 7.4 Eviction

Two sources:

1. **Cache-level (Guava LRU)** — SQL entry evicts → whole pool dereferenced
   → GC reclaims after in-flight borrowers release
2. **Pool-level (capacity)** — `offerFirst` returns false → released plan
   closed + GC'd

No idle-plan TTL in Phase 2. Add only if metrics show retention waste
(large `OrderByStep` buffers — see §11 risk B).

### 7.5 Schema invalidation

New mechanism required because pooling removes the implicit per-request
"refresh via deep-copy" that hid schema changes today:

- `PlanPool.schemaVersion` stamped at put time
- `borrow()` checks `db.getSchema().getVersion() == pool.schemaVersion`
- Mismatch → invalidate entry → planner rebuilds
- Already-borrowed plans with stale schema → same risk as today (user's
  responsibility to not DDL mid-query); not worsened by pooling

Hook: `MetadataUpdateListener` (already emits on schema change) →
`YqlExecutionPlanCache.invalidate()`. Current invalidation path works;
adding schema version is defense-in-depth for the narrow race where
invalidation arrives after borrow but before use.

## 8. Thread safety (new section)

v1 claimed "thread safety preserved by exclusive borrow" — partially
misleading. Real thread safety comes from eleven distinct mechanisms, each
covering one concern. Enumerated here so review can verify coverage.

### 8.1 Pool queue atomicity

**Mechanism:** `LinkedBlockingDeque(maxSize)`.

- `pollFirst`/`offerFirst` are lock-free atomic operations (ReentrantLock
  under the hood, fast-path CAS when uncontested)
- Capacity bound is enforced by the deque itself — **no separate
  counter/queue race** (fixed vs v1's `ConcurrentLinkedQueue + AtomicInteger`)

**What this does NOT cover:** everything below.

### 8.2 JMM publication via queue (the elegant property)

Borrow → use → reset → release → borrow creates a chain of happens-before
edges via the deque:

```
Thread A (previous borrower):
  plan.reset(null);                    // writes to plan fields
  deque.offerFirst(plan);              // release semantics

  ─── happens-before ───

Thread B (next borrower):
  plan = deque.pollFirst();            // acquire semantics
  plan.someField;                      // sees A's reset writes
```

JDK javadoc guarantees: *"actions in a thread prior to placing an object
into the queue happen-before actions subsequent to the access or removal of
that element from the queue in another thread."*

**Consequence:** step-level mutable fields (`ctx`, `prev`, `next`,
`alreadyClosed`, HashJoinMatchStep's `hashMap`, etc.) **do not need
`volatile`**. The deque barrier publishes the full object graph.

This is strictly stronger than per-field volatile — one synchronization
covers everything.

### 8.3 Guava cache publication

`Cache.put(sql, pool)` → `Cache.getIfPresent(sql)` uses `ConcurrentHashMap`
internally. Same JMM guarantee publishes the entire `PlanPool` including
its `prototype` and `available` fields.

### 8.4 Ownership enforcement (CAS state machine)

Defense against bugs in client code (use after release, double release):

```java
class InternalExecutionPlan {
    enum State { POOLED, BORROWED, CLOSED }
    private final AtomicReference<State> state = new AtomicReference<>(State.POOLED);

    void markBorrowed() {
        if (!state.compareAndSet(State.POOLED, State.BORROWED)) {
            throw new IllegalStateException(
                "Plan borrow while in state " + state.get());
        }
    }

    void markReleased() {
        if (!state.compareAndSet(State.BORROWED, State.POOLED)) {
            throw new IllegalStateException(
                "Plan release while in state " + state.get());
        }
    }
}
```

- Double-release throws before `reset()` runs (preventing double-reset on
  corrupted state)
- Use after release cannot happen undetected — any state-checking operation
  fails fast
- `AtomicReference.compareAndSet` provides the CAS ordering

Overhead: one CAS per borrow, one per release. Negligible.

### 8.5 Shared AST (requires `volatile`)

After §5.2, `SimpleNode.copy()` returns `this` — so all pooled plans for
the same SQL **share the same AST node instances**. Different borrowers on
different threads access these shared AST nodes.

The deque barrier (§8.2) does **not** help here, because there is no
borrow/release edge between two borrowers of different plan instances —
they touch the AST independently.

Lazy fields written during execution (`aggregate`, `cachedIsAll`, etc.)
become racy without `volatile`. This is a **benign race** (both threads
compute the same value from the same input), so correctness is preserved,
but on ARM a thread may repeatedly recompute because it never sees the
other's write.

`volatile` eliminates the recomputation and makes the race provably benign
per JMM. Cost: one memory barrier per read/write on Boolean/reference
fields — negligible.

**This is why §5.2 is not optional for pooling.**

### 8.6 Prototype concurrent clone safety

Cold path does `prototype.copy(ctx)`. Multiple threads hitting cold path
simultaneously call `copy()` on the same prototype.

**Already safe today:** current `getInternal()` does `cached.copy(ctx)`
concurrently on every hit. If `copy()` weren't thread-safe, there would be
production bugs today — there aren't. `copy()` performs reads + allocates;
the reads see immutable fields (structural) or volatile fields (lazy AST).

**Pre-condition:** prototype is in clean state at all times. Enforced by
`internal.reset(null)` at put time (§4.1).

### 8.7 CommandContext per borrow

Each borrow gets a fresh `CommandContext` (from the query caller).
`plan.bindContext(ctx)` iterates steps and assigns the field. Since the
borrower is the only writer, there is no concurrent access.

**Visibility to sub-executions (e.g., `ParallelExecStep`):** the borrower
thread writes ctx fields, then spawns sub-threads. Standard Java thread-start
semantics provide happens-before. Sub-threads see the ctx assignments.

### 8.8 Sub-plans — recursive ownership

Steps that hold `InternalExecutionPlan` fields (`MatchFirstStep`,
`HashJoinMatchStep.buildPlan`, `SubQueryStep`, etc.) form a tree of
ownership. When the parent plan is borrowed, the sub-plan is implicitly
part of that borrow. No separate pool entry per sub-plan.

**`reset()` must recurse** — parent's reset must call sub-plan's reset.
This is already the case for `MatchFirstStep` and `MatchPrefetchStep`;
extend to new stateful steps in §6.3.

### 8.9 Schema invalidation during borrow

Covered in §7.5. The `schemaVersion` check on borrow catches post-DDL stale
plans. Race window: DDL lands after `borrow()` check but before `execute()` —
identical risk to today (stale plan in user's hand after deep-copy). Not
worsened by pooling.

### 8.10 Stream abandonment / leak detection

Pool-specific risk: client drops `ResultSet` without `close()` → plan never
released → permanent slot loss.

```java
class InternalExecutionPlan {
    private static final Cleaner CLEANER = Cleaner.create();
    private final Cleaner.Cleanable cleanable;

    protected InternalExecutionPlan(String sql) {
        this.cleanable = CLEANER.register(this, new LeakDetector(sql, state));
    }

    static class LeakDetector implements Runnable {
        private final String sql;
        private final AtomicReference<State> stateRef;
        @Override public void run() {
            if (stateRef.get() == State.BORROWED) {
                metrics.incrementLeakedBorrows(sql);
                log.warn("Plan for {} GC'd while BORROWED — client forgot close()", sql);
            }
        }
    }
}
```

This detects leaks (visibility via metrics) but does not fix them — a
leaked plan is gone. Correct fix is always caller-side `try-with-resources`
on `ResultSet`. Monitoring ensures we know when it happens at scale.

Overhead: one `PhantomReference` per plan instance. Cleaner is shared —
negligible cost.

### 8.11 Concurrency soundness test

The property test in §6.4 is single-threaded. Concurrency test complements:

```java
@Test
public void concurrentBorrowReleaseProducesConsistentResults() {
    var sql = "SELECT FROM Person WHERE age > 30 ORDER BY name";
    var expected = computeExpectedResults(sql);
    warmupPool(sql, 16);

    var executor = Executors.newFixedThreadPool(64);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var latch = new CountDownLatch(5000);

    for (var i = 0; i < 5000; i++) {
        executor.submit(() -> {
            try {
                var actual = executeAndMaterialize(sql);
                assertResultsEqual(expected, actual);
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                latch.countDown();
            }
        });
    }
    latch.await(60, SECONDS);
    assertTrue(errors.isEmpty(), () -> "Concurrency errors: " + errors);
}
```

Runs 5000 concurrent queries against a 16-slot pool at 64-thread contention —
exercises borrow/release races, shared AST races (§8.5), state CAS (§8.4).

### 8.12 Summary table

| Concern | Mechanism | Where it lives |
|---------|-----------|----------------|
| Pool atomicity | `LinkedBlockingDeque(max)` | §8.1 |
| Step fields visibility | Deque barrier | §8.2 — free |
| Cache entry visibility | `ConcurrentHashMap` | §8.3 — free |
| Ownership violations | CAS `State` enum | §8.4 — ~10 LOC |
| Shared AST races | `volatile` on lazy fields | §8.5 — 5 files (Phase 1.2) |
| Concurrent prototype clone | Already safe today | §8.6 — no code |
| Ctx propagation | Single-writer per borrow | §8.7 — no code |
| Sub-plan reset | Recursive reset | §8.8 — part of §6.3 |
| Schema staleness | `schemaVersion` on pool | §8.9 — ~15 LOC |
| Abandoned streams | `Cleaner` + metrics | §8.10 — ~30 LOC |
| Concurrency regression | Stress test | §8.11 — ~100 LOC test |

## 9. Integration points

### 9.1 `bindContext(ctx)` on plan

Replaces the ctx-propagation side of `copy(ctx)`:

```java
class SelectExecutionPlan implements InternalExecutionPlan {
    @Override
    public void bindContext(CommandContext ctx) {
        for (var step : steps) {
            ((ExecutionStepInternal) step).setContext(ctx);
        }
    }
}
```

Stateless iteration over steps. No allocation beyond the per-step `ctx`
field write.

### 9.2 Release routing in `LocalResultSet`

```java
// LocalResultSet.close():
try {
    if (plan.isPooled()) {
        cache.release(sql, plan);            // §4.1 flow
    } else {
        plan.close();                        // non-cacheable plans
    }
} finally {
    stream.close(ctx);
}
```

Exception safety: if `release()` throws, the release path in `PlanPool`
already handles it (discard via `close()`). Caller does not see the
internal exception.

### 9.3 Observability

New metrics (expose via existing `StorageStatistics`):

- `plan_pool_warm_hits{sql}` — borrow from non-empty pool
- `plan_pool_cold_misses{sql}` — pool empty → deep-copy
- `plan_pool_size{sql}` — current available count
- `plan_pool_overflow_discards{sql}` — release beyond maxSize
- `plan_pool_schema_invalidations{sql}` — schemaVersion mismatch
- `plan_pool_leaked_borrows{sql}` — GC'd while borrowed (§8.10)
- `plan_pool_state_violations{sql}` — CAS failures (§8.4, indicates bugs)

**Rollback criterion** (§15) uses these.

## 10. Interaction with incoming PRs

| PR | Interaction | Effort added to Phase 2 |
|----|------------|--------------------------|
| #863 (lazy RID-only MATCH) | None — traverser is per-execution | 0 |
| #880 (IndexOrderedEdgeStep) | `canBeCached() = false` today; with sound reset can flip to `true` (optional Phase 3.2) | 1 step audit |
| #918 (HashJoin variants) | 3 stateful steps need reset audits | 3 step audits |

## 11. Risk analysis (expanded)

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| A. `reset()` incomplete on some step | Medium | **High** (silent wrong results on page 2+) | §6.4 property test + §8.11 concurrency test; must pass on corpus |
| B. Retained `ArrayList` capacity in `OrderByStep` / aggregates | High | **Medium-High** (memory growth) | Reset must call `list.clear()` then `list.trimToSize()` or create new list |
| C. Pool size too small for lifetime-dominated queries | High | Medium (pooling delivers little) | §7.2 adaptive sizing in Phase 3 |
| D. Schema invalidation during borrow | Low | Medium | §7.5 version stamp; same worst-case as today |
| E. Stream abandonment → pool leak | Medium | Medium (slow drain) | §8.10 Cleaner + metrics |
| F. State CAS violation (bug in client code) | Medium | Low (throws early, doesn't corrupt) | §8.4 fail-fast |
| G. `plan.close()` called directly on pooled plan | Low | Medium | State CAS catches it |
| H. Cold-start regression masquerades as success | Medium | Low (measurement error) | §15 distinguishes warm_hits from cold_misses |
| I. AST volatile missing on new lazy field | Medium | High (ARM-only silent bug) | Javadoc contract in SimpleNode + new-field checklist |
| J. Exception during reset leaves plan in partial state | Low | Medium | §4.2 release wraps reset in try/catch → close |
| K. Sub-plan reset not recursive | Medium | High (stale state in nested MatchFirstStep) | §6.3 includes sub-plan-holding steps |
| L. Thread-local / transaction refs held in steps | Low | Medium | Phase 2 audit greps `DatabaseSession`, `Transaction` holds |
| M. Remote execution path bypassed vs. embedded | Low | Low | §15 non-goals — explicit |
| N. `PreparedStatement` bound parameter handling | Unknown | Medium | Audit needed: are parameters in steps or in ctx? |

Risk B in detail: `OrderByStep` builds `ArrayList<Result>` up to the full
sort size. If sort is 10M rows, ArrayList capacity stays at 10M after
`clear()`. Pool of 16 plans × 10M × 8 bytes = 1.3 GB retained. **Must**
either null the field (GC reclaims ArrayList) or explicitly trim — see §6.3
action.

## 12. Comparison with `design-functional.md`

Duplicated in §2 table but re-stated explicitly for the decision frame:

|  | design-functional | plan pool |
|--|-------------------|-----------|
| Files | ~105 | ~25 |
| New interface | Yes | No |
| Execution semantic changed | Yes | No |
| AST volatile | Required | Required |
| AST pre-computation | Required | Optional |
| Enables paginated MATCH | Yes | Yes |
| Enables `IndexOrderedEdgeStep` caching | Yes (via immutability) | Yes (via sound reset) |
| Parallel landing with PRs #863/#880/#918 | Heavy coordination | Light |
| New invariant for future contributors | "steps are pure functions" | "reset() must be sound" |
| Mechanical test for invariant | Type system enforces | §6.4 property test |
| Rollback cost if pooling design fails | N/A | Revert `YqlExecutionPlanCache` (1 file) + leave `reset()` overrides (cruft, ~15 files) |
| Memory footprint | 1 plan per SQL | Up to 16 plans per SQL (~80 MB worst case at 1000 hot SQLs) |

The "reversibility" trade-off is real but asymmetric: cache wrapper is 1
file to revert; the reset overrides added in §6.3 are permanent unless
removed in a second migration. Call it "partially reversible."

## 13. Implementation order

### Track 1 — Phase 1 (independent cheap wins, can merge any time)

| Step | Files | Days |
|------|-------|------|
| 1.1 Remove `genericStatement` | 15 | 1 |
| 1.2 AST volatile + `SimpleNode.copy()` returns `this` + caller audit | 5 + audit | 2 |
| 1.3 `reset(null)` replaces throwaway copy on put | 1 | 0.5 |

**Total: ~3.5 days. ~5% CPU recovered. Zero architectural change.**

### Track 2 — Phase 2 (pooling core)

| Step | Files | Days | Notes |
|------|-------|------|-------|
| 2.1 `PlanPool` class + `LinkedBlockingDeque` | 1 new + test | 2 | |
| 2.2 `bindContext()` on `InternalExecutionPlan` | 5 | 1 | |
| 2.3 State CAS (`markBorrowed`/`markReleased`) | 2 | 1 | §8.4 |
| 2.4 Schema version on pool | 2 | 0.5 | §7.5 |
| 2.5 Cleaner-based leak detector + metrics | 2 | 1.5 | §8.10 |
| 2.6 Reset audit — stateful steps | ~15 | **8–10** | §6.3, dominant cost |
| 2.7 Property test + corpus | 3 new | 5 | §6.4 |
| 2.8 Concurrency test | 1 new | 2 | §8.11 |
| 2.9 Wire pool into cache behind feature flag | 3 | 1 | default off |
| 2.10 Enable flag in CI; iterate to green | config | 3 | |
| 2.11 Metrics observability dashboards | config | 1 | §9.3 |
| 2.12 Enable by default in one config profile | config | 0.5 | |
| 2.13 One release observation + rollback gate | — | wait 1 release | |
| 2.14 Remove flag | cleanup | 0.5 | |

**Total: ~27 days active development + 1 release observation window.**
Realistic calendar: **5–6 weeks** for a solo engineer, **3 weeks** with 2
engineers on parallel reset audits.

v1 estimated 10 days. v2 is 3× higher because v1 underestimated the reset
audit (7 days vs 1.5) and test infrastructure (7 days vs 4).

### Track 3 — Phase 3 (conditional, measured-driven)

Gate: Phase 2 in production for ≥1 release, metrics show:

- 3.1 Adaptive pool sizing — if `cold_misses / (warm_hits + cold_misses) > 20%`
  on hot SQL → implement §7.2 Strategy B
- 3.2 `IndexOrderedEdgeStep.canBeCached() = true` — if IS/IC LDBC workload
  shows >3% CPU remaining in planning cost → implement sound reset →
  enable caching
- 3.3 Idle TTL for pool entries — if memory metric shows retention of
  large `OrderByStep` buffers → add TTL
- 3.4 Eager release on stream exhaustion — if lifetime-dominated queries
  measured → add eager-release hook

Each conditional on concrete metric threshold; no speculative implementation.

## 14. Open questions

1. **Pool sizing for lifetime-dominated workloads (§7.1–7.2).** If a query's
   plan lifetime is 100 ms, pool of 16 is vastly undersized at 20K RPS.
   Decision: ship with strategy A + metrics; if cold-miss rate high, trigger
   strategy B in Phase 3. Accept that pooling may deliver <expected gains
   for slow-drain workloads — but never slower than today.

2. **PreparedStatement parameter handling.** Where do bound parameters live —
   in step fields or in CommandContext? If steps cache
   parameter-substituted ASTs, reset must clear them. Audit required.

3. **EXPLAIN output stability.** Cost/stats in `prettyPrint()` read from ctx.
   Post-pool, different EXPLAIN invocations may show different stats
   (different pooled plan instance had different recent execution).
   Acceptable or documentation required?

4. **Should Phase 1 ship independently or bundled with Phase 2?**
   Phase 1 alone = 5% CPU win, easy revert. Phase 1 + 2 = full target but
   Phase 1.2 (AST volatile) is "wasted" if Phase 2 never ships (volatile
   pays negligible cost but enables nothing without sharing). Recommendation:
   ship Phase 1 standalone, commit to Phase 2 within 1 quarter.

5. **Embedded vs server-side vs remote.** `YqlExecutionPlanCache` lives in
   `core` — used by embedded. Server uses same? Remote driver has separate
   plan path? Non-goal for this design, but needs verification that the
   cache IS exercised in server mode before committing.

## 15. Success criteria and rollback gates

### Phase 1 ships when
- `git grep "toGenericStatement"` returns test-only usages
- JMH on LDBC IS1 8-thread shows ≥3.5% CPU reduction vs baseline
- All unit + integration tests pass
- No p99 latency regression >2%

### Phase 2 ships to default-on when
- §6.4 property test green on full corpus (≥150 read-only queries)
- §6.5 idempotency test green
- §8.11 concurrency stress test green
- JMH on LDBC IS1: ≥5.5% CPU reduction vs Phase 1 baseline (total 9%+ vs
  pre-Phase-1)
- JMH `-prof gc`: steady-state cache hit allocates ≤20 bytes per request
  (i.e., effectively zero — only stream decorators, which are unchanged)
- Metrics show `warm_hit / (warm_hit + cold_miss) > 80%` on hot LDBC queries
- No `state_violations` in 24-hour test run
- Integration test suite passes with pool flag on

### Rollback criteria (flag flip back)
- Any of:
  - Production p99 latency regression >5% on any canary SQL
  - `leaked_borrows` rate >0.1% of requests over 1 hour
  - `state_violations` >0 per hour
  - Test failure in property/concurrency test post-merge

## 16. Non-goals

- **Paginated MATCH** — separate ADR. Pool is a prerequisite; this design
  does not add pagination support.
- **Projection pushdown / lazy `Result` / batch pipeline** — data-path
  zero-copy. Orthogonal axis.
- **Plan serialization / distribution** — requires immutability. If needed,
  `design-functional.md` becomes the path.
- **Remote driver plan cache** — separate system.
- **Removing `reset()` / `sendTimeout()` / `close()` from interface** — kept.

## 17. Changelog from v1

- Added §8 (Thread safety) as a proper standalone section with 11 sub-mechanisms
- Fixed §4.2: race in `release()` via `LinkedBlockingDeque(max)` replacing
  `CLQ + AtomicInteger`
- Fixed §2/§11 tables: AST volatile is required, not avoided
- Expanded §6.3 reset audit from 7 to ~15 steps with known-state table
- Added §6.4 property test limitations (non-determinism, side effects, tx
  isolation, single-threaded)
- Added §6.5 idempotency test
- Added §7.1 corrected sizing analysis (lifetime, not execution time)
- Added §7.5 schema invalidation mechanism
- Added §8.10 leak detection via Cleaner
- Added §9.3 observability metrics and rollback hooks
- Added §11 risks B, E, H, I, J, K, L, N (8 new risks)
- Revised §13 estimates 3× upward (10 days → 27 days + 1 release window)
- Added §15 rollback criteria
- Corrected "no architectural change" claim — reset soundness IS an invariant
- Removed "reversible" overclaim — partial, with permanent reset-override residue
