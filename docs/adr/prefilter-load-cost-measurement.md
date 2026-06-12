# Load Cost Measurement — Options A, A3, Sampling, Adaptive Buffering

Four options for sampling the per-entry load+eval cost on the MATCH
no-prefilter fallback path. All four feed `RECORD_LOAD_COST` (a `Ratio`
metric: `numerator=nanos`, `denominator=entries`) consumed by the
`IndexLookup` amortization formula.

The baseline today is the develop-branch **static model** — the load cost is
estimated from cold/warm constants weighted by `CACHE_HIT_RATIO`, with **zero
per-entry overhead**. Every option below adds some overhead in exchange for
real measurements.

A naive per-callback `System.nanoTime()` wrap would cost ~65 ns/entry on the
hot path (5-15% regression on MATCH workloads) and is used in the analysis
below as the upper-bound reference for "real measurement at any cost". It is
**not** the current implementation — it is the option we are trying to
improve on.

All four options aim to keep the measurement real while bringing the overhead
close to zero.

All four measure the same thing: **the per-entry cost of `upstream.next()`
(load) + `filter.filterMap()` (WHERE/class/RID eval)** on the no-prefilter
path. This is the cost the prefilter saves us when it skips an entry, so it
is the value the amortization formula needs. To stay correct, all four
options time both calls together — wrapping only the filter callback would
miss the load cost (which dominates eval by an order of magnitude) and bias
the formula toward never building prefilters.

All four are **estimation strategies** — they measure a subset of entries and
rely on the timed subset being representative of the untimed one. They differ
only in the selection rule:

- **Option A** measures every entry inside a `fetchNextItem` cycle, batching the
  `nanoTime` pair around the loop.
- **Option A3** measures the first 64 entries of every `MatchEdgeTraverser`,
  then switches to a zero-overhead fast path.
- **Sampling** measures one entry out of every 64 invocations of the filter
  callback.
- **Adaptive Buffering** introduces a small look-ahead buffer that grows when
  the consumer drains it and shrinks (or stays small) when it doesn't,
  amortising the `nanoTime` pair over the buffer fill.

YouTrackDB iterates link bags in physical RID order, which has no correlation
with cache temperature or access recency. All four selection rules are
structurally unbiased: no position in the link bag is systematically faster or
slower than another.

The regression numbers below are **predictions against the develop-branch
static-model baseline** (zero per-entry overhead), assuming LDBC SF1-class
workloads. Actual numbers will need to be confirmed by JMH.

---

## Option A — per-`fetchNextItem` timing

Move the `nanoTime` pair from around each individual filter callback to around
the whole `fetchNextItem` loop. The loop runs `upstream.next() +
filter.filterMap()` until it finds one entry that passes the filter (or until
the upstream is exhausted).

```java
private void fetchNextItem(CommandContext ctx) {
    long t0 = System.nanoTime();
    long entries = 0;
    while (upstream.hasNext(ctx)) {
      Result r = upstream.next(ctx);
      r = filter.filterMap(r, ctx);
      entries++;
      if (r != null) {
        loadCostRatio.record(System.nanoTime() - t0, entries);
        nextItem = r;
        return;
      }
    }
    if (entries > 0) {
      loadCostRatio.record(System.nanoTime() - t0, entries);
    }
}
```

### Correctness

`fetchNextItem` is a tight loop. Nothing else runs between the two `nanoTime`
calls — no downstream work, no upstream work outside `next()`. The elapsed
value covers exactly `entries × (load + filter eval)`.

### Overhead

2 × `nanoTime` per `fetchNextItem` call, amortized over however many entries
the loop processes before emitting one result.

| Filter selectivity | Entries per fetch cycle | Effective per-entry overhead |
|---|---|---|
| Permissive (≥95% pass) | 1 | ~65 ns (same as naive) |
| Moderate (~50% pass) | 2 | ~32 ns |
| Restrictive (~5% pass) | ~20 | ~3 ns |
| Very restrictive (~1% pass) | ~100 | ~0.6 ns |

### Predicted LDBC SF1 regression vs static-model baseline

| Query | Filter character | Predicted regression |
|---|---|---|
| IC1 (friend-of-friend with `firstName = 'X'`) | restrictive | 0.3-3% |
| IC2 (`Post.creator` lookups, class-only check) | permissive | 5-15% |
| IC11 (deep, mixed predicates) | mixed | 3-10% |

### Caveats

- **Worst case = naive**: a `WHERE` clause that passes nearly every entry (or
  no `WHERE` at all with only class checks) gives no batching benefit.
- **Workload-dependent**: the same code change can produce 0% regression on
  one query and 15% on another.

---

## Option A3 — per-traverser timing budget

Cap the timing to the first 64 entries of every `MatchEdgeTraverser`. After
the budget is exhausted, the rest of that traverser's entries take a
zero-overhead fast path.

The timing wraps both `upstream.next()` and `filter.filterMap()` to cover
load + eval, so it must live at the stream level (not the filter callback —
the callback receives an already-loaded `next`).

```java
class CappedTimedStream implements ExecutionStream {
  static final int BUDGET = 64;
  int timedEntries = 0;
  long accumNanos = 0;
  Result nextItem;

  void fetchNextItem(CommandContext ctx) {
    while (upstream.hasNext(ctx)) {
      boolean timed = timedEntries < BUDGET;
      long t0 = timed ? System.nanoTime() : 0L;
      Result r = upstream.next(ctx);        // load
      r = filter.filterMap(r, ctx);         // eval
      if (timed) {
        accumNanos += System.nanoTime() - t0;
        timedEntries++;
      }
      if (r != null) { nextItem = r; return; }
    }
  }

  void close(CommandContext ctx) {
    if (timedEntries > 0) loadCostRatio.record(accumNanos, timedEntries);
    upstream.close(ctx);
  }
}
```

### Correctness

`numerator_sum / denominator_sum` is the average per-entry cost over all timed
entries. With RID-order iteration there is no positional bias, so the sample
is representative.

Every traverser contributes a measurement (up to 64 entries). Vertices are not
skipped — only the *tail* of each link bag past entry 64 is not timed.

### Overhead

**Bounded per traverser** at 64 × ~65 ns = ~4 µs. Independent of link-bag size
beyond 64. For traversers with **fewer than 64 entries**, A3 times all of
them — overhead is identical to the naive per-callback approach for that
traverser.

| Traverser size | A3 overhead | vs naive per-callback |
|---|---|---|
| 1-10 entries (narrow) | N × 65 ns | identical |
| 64 entries (threshold) | ~4 µs | identical |
| 1000 entries (wide) | ~4 µs (capped) | ~250× less |
| 1M entries (huge) | ~4 µs (capped) | ~250 000× less |

Per-query overhead = `Σᵢ min(Nᵢ, 64) × 65 ns` where `Nᵢ` is entries per
traverser. **Not known a priori** — depends on the number of traversers and
the distribution of link-bag sizes.

A3 is **bounded above by the naive per-callback overhead** — never worse,
often much better — but the actual per-query overhead is workload-dependent.

### Predicted LDBC SF1 regression vs static-model baseline

| Query | Link-bag character | Predicted regression |
|---|---|---|
| IC1 (`Person.knows` wide, ~150 neighbours) | wide | 0.5-2% |
| IC2 (`Post.creator` narrow, 1 per post) | narrow | 5-15% (≈ naive) |
| IC11 (deep mix) | mixed | 0.8-3% |

### Caveats

- **Workload-dependent**: like Option A, the actual overhead depends on query
  shape. Dependency is on link-bag width rather than filter selectivity, but
  no constant per-query guarantee.
- **Single huge-traversal queries get a thin sample.** Only 64 timed entries
  contribute to the metric from that one traverser.
- **`denominator_sum` is not a count of work done.** Reflects only the timed
  portion. Do not use as a throughput counter.

---

## Option Sampling — measure 1 entry out of every 64

Run the timer on a deterministic fraction of stream advances. Like A3, the
timer must wrap `upstream.next() + filter.filterMap()` together (load +
eval), not just the filter callback.

```java
class SampledTimedStream implements ExecutionStream {
  static final int SAMPLE_MASK = 0x3F;   // 1/64
  long callCount = 0;
  long accumNanos = 0;
  long accumEntries = 0;
  Result nextItem;

  void fetchNextItem(CommandContext ctx) {
    while (upstream.hasNext(ctx)) {
      boolean timed = (callCount++ & SAMPLE_MASK) == 0;
      long t0 = timed ? System.nanoTime() : 0L;
      Result r = upstream.next(ctx);        // load
      r = filter.filterMap(r, ctx);         // eval
      if (timed) {
        accumNanos += System.nanoTime() - t0;
        accumEntries++;
      }
      if (r != null) { nextItem = r; return; }
    }
  }

  void close(CommandContext ctx) {
    if (accumEntries > 0) loadCostRatio.record(accumNanos, accumEntries);
    upstream.close(ctx);
  }
}
```

### Correctness

`numerator_sum / denominator_sum` is the average per-entry cost over the
sampled subset. With no positional bias in link-bag iteration, the 1-in-64
systematic sample is unbiased.

Sample rate of 1/64 over a 60s window in any non-trivial workload produces
thousands of samples, giving a stable mean within seconds.

### Overhead

**Constant ~1 ns per entry**, regardless of query shape, filter selectivity,
link-bag size, or traverser count.

- 1 in 64 entries pays the full ~65 ns timing cost.
- 63 in 64 entries pay only a counter increment and a masked compare (~1 ns).
- Average: ~1 ns/entry.

For a query with 1M filter calls: ~1 ms total overhead.

### Predicted LDBC SF1 regression vs static-model baseline

| Query | Predicted regression |
|---|---|
| IC1 | 0.2-1% |
| IC2 | 0.2-1% |
| IC11 | 0.1-0.5% |

Below JMH noise floor (~3-5%) on all queries.

### Caveats

- **Higher variance per individual measurement** than A3 in the same window.
  Sliding-window aggregation neutralises this for the average; if percentiles
  or outliers matter, the choice differs.
- **`denominator_sum` is not a count of work done** — same caveat as A3.

---

## Option Adaptive Buffering — dynamic look-ahead with size adaptation

Introduce a small look-ahead buffer that holds accepted results. The buffer
fill runs in a tight loop with a single `nanoTime` pair around it (similar to
how `resolveIndexToRidSet` already times the whole index scan). The buffer
size starts at 1 and grows when the consumer drains it.

```java
class AdaptiveBufferedTimedFilter implements ExecutionStream {
  static final int MIN_BATCH = 1;
  static final int MAX_BATCH = 64;
  static final int GROW_AFTER = 2;     // consecutive full drains before grow

  ArrayDeque<Result> buffer = new ArrayDeque<>(MAX_BATCH);
  int currentBatchSize = MIN_BATCH;
  int consecutiveFullDrains = 0;

  Result next(CommandContext ctx) {
    if (buffer.isEmpty()) refillBuffer(ctx);
    Result r = buffer.pollFirst();
    if (buffer.isEmpty()) consecutiveFullDrains++;
    return r;
  }

  void refillBuffer(CommandContext ctx) {
    if (consecutiveFullDrains >= GROW_AFTER) {
      currentBatchSize = Math.min(currentBatchSize * 2, MAX_BATCH);
      consecutiveFullDrains = 0;
    }
    long t0 = System.nanoTime();
    int entries = 0;
    while (upstream.hasNext(ctx) && buffer.size() < currentBatchSize) {
      Result r = upstream.next(ctx);
      entries++;
      r = filter.filterMap(r, ctx);
      if (r != null) buffer.addLast(r);
    }
    if (entries > 0) {
      loadCostRatio.record(System.nanoTime() - t0, entries);
    }
  }
}
```

### Correctness

The refill loop is tight — no yielding to downstream between the two
`nanoTime` calls. Elapsed time measures exactly `entries × (load + filter
eval)`, like Option A but for a larger guaranteed batch when streaming is
sustained.

The look-ahead is bounded by `currentBatchSize`. Buffered entries are emitted
in order, no reordering. Filter rejections happen in `refillBuffer` and do
not appear in the buffer.

### Overhead and warmup

| Batch size | Overhead per entry |
|---|---|
| 1 (start) | ~65 ns (= naive) |
| 2 | ~32 ns |
| 4 | ~16 ns |
| 8 | ~8 ns |
| 16 | ~4 ns |
| 32 | ~2 ns |
| 64 (max) | ~1 ns |

After the consumer has drained the buffer twice consecutively, the batch
doubles. With `GROW_AFTER = 2` the growth schedule is 1 → 2 → 4 → 8 → 16 →
32 → 64 over the first ~6 drain cycles.

**Per-query overhead**: dominated by the warmup tax until the batch reaches
its steady-state size.

- Streaming-heavy query (no `LIMIT`, drains continuously): converges to
  ~1 ns/entry after ~64 entries emitted. For 1M total entries: ~1 ms overhead.
- `LIMIT N` query: buffer never grows past ~log₂(N), warmup tax dominates.

### Wastage from look-ahead

Look-ahead processes entries that the consumer never requests when `LIMIT` or
`EXISTS` short-circuits the stream. Wastage is bounded by `currentBatchSize - 1`
at the moment of close.

| Consumer pattern | Buffer size at close | Wasted entries |
|---|---|---|
| `LIMIT 1` | 1 | 0 |
| `LIMIT 10` | ~4-8 | up to 7 |
| `LIMIT 100` | ~16-32 | up to 31 |
| No `LIMIT` (full drain) | 64 | up to 63 |

Wasted entries each cost ~500 ns of load + eval work, so worst-case wastage on
`LIMIT 100` is ~16 µs — well below typical query latency.

### Predicted LDBC SF1 regression vs static-model baseline

| Query | Predicted regression |
|---|---|
| IC1 (no `LIMIT` on inner traversal, wide drain) | 0.1-1% |
| IC2 (small per-vertex emit, mostly warmup tax) | 1-5% |
| IC11 (mix of streaming and `LIMIT`-bounded steps) | 0.3-2% |

Generally the best predicted profile of any option for queries with long
sustained streams, slightly worse than sampling for short-stream-heavy
workloads.

### Caveats

- **Wastage is real I/O**, not just CPU. Wasted entries trigger page loads
  that would not otherwise happen. For cold-cache workloads this is more
  expensive than the warmup tax suggests.
- **Complexity is highest of the four options.** Adaptive logic, state per
  stream, edge cases on close, on exception, on early termination.
- **`denominator_sum` matches `entries`** (real entry count for the timed
  portion), unlike A3 and sampling. The timed portion equals the processed
  portion.
- **First few results pay full naive cost** while the batch warms up. For
  `LIMIT 1` queries the batch never grows, so overhead is identical to naive.
- **Look-ahead may break query semantics in edge cases**: e.g. a consumer that
  relies on side effects from `next()` being delayed will see them eagerly.
  Need to verify no such consumers exist in the MATCH pipeline.

---

## Comparison

| | Option A | Option A3 | Sampling 1/64 | Adaptive Buffering |
|---|---|---|---|---|
| Per-entry overhead | 0.6–65 ns | 0–65 ns | **~1 ns constant** | 1-65 ns (warmup) |
| Bounded by | filter selectivity | 64 entries × 65 ns / traverser | sample rate | buffer size adaptation |
| Worst case vs naive | identical | identical | **always 65× better** | identical (LIMIT 1) |
| Constant per-query regression | no | no | **yes** | no, but small spread |
| Breaks lazy streaming | no | no | no | **partially (look-ahead)** |
| Causes wasted I/O | no | no | no | **yes (LIMIT/EXISTS)** |
| `denominator_sum` = real entries | yes | no | no | yes |
| Implementation complexity | small | small | trivial | **highest** |
| Sample richness per query | high (per fetch cycle) | up to 64 per traverser | ~1/64 of all calls | high (per batch) |

### Predicted regression range summary (LDBC SF1 vs static-model baseline)

| Option | Best query | Worst query | Typical |
|---|---|---|---|
| Static model (baseline) | 0% | 0% | 0% |
| Option A | 0.3% | 15% | 3-10% |
| Option A3 | 0.5% | 15% | 1-5% |
| Sampling 1/64 | 0.1% | 1% | 0.2-0.6% |
| Adaptive Buffering | 0.1% | 5% | 0.5-2% |
| Naive per-callback (upper-bound reference) | 5% | 15% | 6-12% |

## Picking between them

- **Option A** is the simplest conceptual change but offers no constant
  overhead guarantee. Not recommended as a general solution.

- **Option A3** trades variable per-query overhead for predictable
  per-traverser overhead. Wins on wide-edge schemas, identical to naive on
  narrow-edge schemas. Never worse than naive per-callback.

- **Sampling 1/64** is the only option with a **constant per-entry overhead**
  independent of query shape. Trivial to implement. Smaller sample per
  individual query but the metric's 60s window aggregates fast across the
  whole workload.

- **Adaptive Buffering** approaches scan-like overhead (~1 ns/entry) for
  long-running streams but pays a warmup tax and causes some wasted I/O on
  early-termination queries. Highest complexity. Best for workloads dominated
  by `LIMIT`-free or large-`LIMIT` queries where the warmup is amortised.

All four are estimation strategies. The choice is between:
- **predictable overhead** (sampling)
- **predictable per-query sample richness** (A3)
- **scan-class efficiency at the cost of wastage and complexity** (adaptive
  buffering)
- **simplicity at the cost of workload-dependence** (Option A)
