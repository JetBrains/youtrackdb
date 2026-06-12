# IndexLookup Optimization in MATCH — Speaker Notes

Companion notes for `match-indexlookup-optimization.pptx` (28 slides).
Each section corresponds to one slide. Notes are also embedded in the
pptx file itself and visible in PowerPoint's Presenter View.

---

## Slide 1 — Title

Welcome. This talk is about a specific optimization in the MATCH execution engine: asking an index
whether a vertex would pass a WHERE clause BEFORE paying to load it. The optimization gives us
200–1000% throughput wins on LDBC SNB workloads, but only when we make the right runtime decisions
about WHEN to use it. About 60% of this talk is about those decisions — the cost arithmetic, the
gates, the bounded-loss contract. By the end you should know exactly when this fires, when it
doesn't, and how to read the PROFILE counters to verify it in production.

---

## Slide 2 — How a MATCH query runs (the bigger picture)

Quick orientation for anyone who hasn't worked deeply in the MATCH executor. A MATCH query goes
through three stages. The parser produces an AST. The planner picks aliases and a cost-based
schedule — which edge to traverse first, next, last. That gives us a list of EdgeTraversal objects
in execution order. Then execution walks that list: for each edge, for each source vertex, walk the
link bag, load each neighbor, check WHERE, emit binding if everything matches. Our optimization sits
in stage 3, INSIDE the per-edge inner loop. We don't change the schedule, we don't change the
planner's edge ordering. We change which neighbor RIDs actually reach loadEntity. Schedule order and
our optimization are orthogonal — the wins compound, they don't compete.

---

## Slide 3 — How edges are stored (the link bag)

One more piece of background. In YouTrackDB, edges aren't separate records — each vertex carries its
own adjacency as fields on its own record. A Person record has out_KNOWS pointing to a list of
friend RIDs, in_HAS_CREATOR pointing to message RIDs. The LinkBag is just that list. Walking the
list is cheap — it's sequential RIDs already in memory once the vertex is loaded. Loading each RID
is the expensive part — random disk I/O per neighbor record. That asymmetry is the entire economic
premise of this optimization. We trade one cheap operation, a bitmap probe, for many expensive
operations, vertex loads we won't use.

---

## Slide 4 — The problem (MATCH loads vertices it will throw away)

Concrete example. LDBC SNB IC9 — friend-of-friend recent messages. Today's execution: 200 friends,
each has roughly 5000 messages in their HAS_CREATOR link bag, we want only messages created after a
specific date. We load all million messages, just to check their creationDate field. 99.8 percent of
those loads are wasted — the WHERE clause rejects them. The crucial detail: the WHERE only checks
creationDate. We have an index on Message.creationDate. We could resolve the answer from the index
without ever loading those messages. That's the structural opportunity this optimization captures.

---

## Slide 5 — What we want to skip (concretely) ★ NEW

Visual recap before we describe the fix in pseudocode. The query at the top is the same IC9 we just
traced. The graph below shows what executes today.

One Person on the left. Three friend vertices shown — in reality 200. Each friend has a link bag of
5,000 message RIDs — fourteen boxes per row stand in for that 5,000.

Red crosses are vertices we LOAD from disk today and then THROW AWAY because their creationDate
doesn't match. Green checkmarks are the few vertices that actually pass.

Across the whole picture: 42 posts shown, 6 green, 36 red. Generalize to reality and you get 200
times 5,000 equals one million loads, of which roughly 2,000 actually pass the WHERE. Every red box
is wasted I/O.

The IndexLookup optimization is, in one sentence: skip every red box. Don't even touch them. Load
only the green ones. Next slide shows how.

---

## Slide 6 — The idea (ask the index once, intersect for free)

The fix in pseudocode. Once, before the friend loop: scan the creationDate index for the date range,
get back roughly 14,000 qualifying message RIDs, materialize them into a fast bitmap we call a
RidSet. Then per friend: walk the 5000-RID link bag as before, but for each RID probe the bitmap
first. Only RIDs that pass the probe reach loadEntity. The index scan is paid ONCE for the entire
query. The bitmap probe is O(1) memory access. The orders-of-magnitude cost gap between "probe" and
"load" is what makes this profitable.

---

## Slide 7 — Before vs. after (concrete numbers)

The numbers. One million vertex loads collapse to 2,000 vertex loads plus 14,000 index entries
scanned — about 62× total I/O reduction. The measured wall-clock impact on LDBC SF1 for IC9 is plus
483 percent throughput single-thread — the optimized version completes 5.83 queries in the time the
un-optimized version does 1. IC3 stacks two IndexLookups on one MATCH pattern and gets plus 1117
percent. These aren't theoretical — they're JMH measurements against the same baseline. Multi-thread
numbers are slightly lower because we hit shared-cache contention sooner, but they still scale.

---

## Slide 8 — Which queries benefit?

When does the planner attach an IndexLookup descriptor? Three requirements. One: target node has a
WHERE clause. Two: the target class has an index covering that condition — range, hash, unique,
full-text whatever. Three: the condition does NOT reference $matched. Index parameters must be
resolvable at query start, not mid-traversal — otherwise the result isn't constant per query and we
lose the amortization. Common shapes are date ranges, IN lists, BETWEEN (which we rewrite), and
exact-match on unique indexes. On LDBC: IC3, IC9, IS7 hit this pattern. Any production query whose
target node filters on indexed properties is a candidate. Note: filters that reference $matched —
like "where my friend's friend is in this list" — need different techniques. This optimization is
specifically for class-level predicates.

---

## Slide 9 — The catch (the index scan itself costs something)

Here's where it gets interesting. The index scan itself costs about 14,000 I/O reads. That's the
BUILD COST — paid once per query if we choose to build. Three scenarios in the table. First
scenario: 200 friends each with 5000-RID bags. One million traversals; building 14,000 pays back 62
times. Clear win. Second: 10 friends, 50-RID bags each. 500 traversals; building 14,000 is a 28×
LOSS. Third: 3 friends with 500-RID bags. 1500 traversals; same story, 9× loss. The same code path
can be a 62× win OR a 28× loss depending purely on traversal volume. The next half of this talk is
about HOW we decide.

---

## Slide 10 — The optimization in three pieces

Architecture. Three pieces. Planner detection finds eligible filters at plan time and attaches a
descriptor object to the edge — that's the cheap part. Runtime decision applies two independent
gates per edge: is the filter selective enough at all, and will traversal volume actually justify
the build? Per-edge cache: once we decide to build, the resulting RidSet is cached and reused for
every subsequent vertex on the same edge. That cache is what makes the math work — build cost
amortizes over all traversals through the edge. If the planner attached the descriptor but the gates
decide no, the cache stays empty and we fall back to naive load-and-check. No regression — we just
didn't activate the optimization.

---

## Slide 11 — The descriptor (what gets attached to the edge)

The descriptor is a record with four methods. `estimatedSize` gives a histogram-based hit count —
fast, O(1). `resolve` actually runs the index scan and builds the bitmap — slow, paid once.
`cacheKey` returns a fingerprint that includes index name, key condition, and range condition — so
two IndexLookups on the same index with different parameters get different cache slots.
`passesSelectivityCheck` is gate 1. Important historical note: the fingerprint used to be
`index.getName()` alone, which would have aliased two IndexLookups on the same index with different
conditions. The collision was latent — today's planner emits at most one IndexLookup per edge — but
the fingerprint fix pins correctness on data instead of on planner discipline. One PR change away
from a silent bug.

---

## Slide 12 — The decision flow (one picture for both gates)

The whole control flow in one picture. Per source vertex: cache lookup first. Cache hit with a real
RidSet — use it, filter the link bag, done. Cache hit with null — that's a remembered rejection; we
restore the original skip reason and apply no filter. Cache miss — run gate 1. Pass — run gate 2.
Pass gate 2 — three outcomes: BUILD_EAGER, DEFERRED_WITH_NET, or REJECT. Whatever outcome, cache it.
Subsequent vertices reuse the decision without re-evaluating. This is the entire control flow;
everything else in the deck is detail about each of these boxes.

---

## Slide 13 — Gate 1 (is the filter selective enough at all?)

Gate 1 is fast. Selectivity equals hits divided by class total count — the fraction of the class
matching the filter. Threshold 0.95: if the filter rejects fewer than 5 percent of class members,
even a free build wouldn't save meaningful work. We skip. The threshold is conservative — break-even
on individual probe cost is way out near 0.9997, but 0.95 avoids degenerate cases like "creationDate
less than year 3000" that the planner can't reason about. Special case: `estimateSelectivity`
returns minus one when no histogram exists. Then we REJECT — better to skip than optimistically
build a RidSet we can't bound. The selectivity check evaluates ONCE per query, not per vertex;
result is cached for all subsequent vertices.

---

## Slide 14 — The deferral tax (why naive 'n > m' is wrong) ★ NEW

This is the most important slide in the deck. The naive question "when does build pay back?" has a
naive answer: when traversal volume `n` exceeds `m`, where `m` equals build cost divided by
per-vertex savings. That answer is WRONG when you have a deferral phase.

Cost arithmetic. Three policies. No pre-filter: every neighbor costs L. Perfect oracle that knows in
advance to build: B once, then s·L per neighbor — only matching neighbors loaded. Runtime
accumulator: deferral phase loads m neighbors at full L, then B, then s·L for the remaining n minus
m.

Subtract: `Cost(accumulator) − Cost(oracle) = B`, always — regardless of n or s. Why? During
deferral, m neighbors get loaded at full L when the oracle would have loaded them at s·L. The
forfeited savings exactly equal B by the definition of m. So you pay for the build TWICE in effect —
once as the build itself, once as savings forfeited while you were waiting to decide.

The danger zone is `n ∈ [m, 2m)`. In that range the runtime accumulator is ACTIVELY SLOWER than not
optimizing at all. At n equal to m you can be 2× slower than nothing. This is why BUILD_EAGER exists
as a separate mode — eager means we build on vertex 1, no deferral phase, no tax. And it's why the
safety-net trigger T we'll see in a moment has m as its floor, not zero.

---

## Slide 15 — Gate 2 (will the traversal volume pay back the build?)

Gate 2 uses the formula we just motivated. `m = estimatedSize / (loadToScanRatio × (1 −
selectivity))`. `estimatedSize` is the build cost in proxy units — one I/O per index entry.
`loadToScanRatio` is 100 today — a random vertex load is roughly 100× a bitmap probe. Three example
rows. Selective filter on creationDate, m around 56. Moderate filter, m around 429. Bulk filter
where 90 percent pass, m equals 5000 — only very large edges should build for this one. Decision:
forecast versus m. Forecast comfortably above m, BUILD_EAGER. Otherwise DEFERRED_WITH_NET — let the
runtime accumulator catch reality. The accumulator's trigger formula T includes the m floor we just
derived AND a deviation term we'll see momentarily.

---

## Slide 16 — Three modes table (eager, deferred, reject)

Three modes table. BUILD_EAGER fires when forecast comfortably exceeds m AND the sample size for the
forecast is at least 30 — that's the CLT threshold we cover in two slides. DEFERRED_WITH_NET is the
fall-through: selectivity is known but eager conditions not met. Accumulate per-vertex link bag
size; when the running total exceeds `T = max(2·forecast, m)`, build now. The 2× term catches
forecast under-estimates — if reality is twice the forecast, the forecast was clearly wrong by
enough that a build is justified. The m floor catches the no-forecast case — when we have no useful
forecast at all, the m floor takes over. REJECT is for unknown selectivity or guaranteed-low volume
— never retry on this edge. Once the mode is decided at the first call, it's memoised on the
EdgeTraversal — no re-evaluation per vertex.

---

## Slide 17 — Worst-case loss per mode (the bounded-loss contract) ★ NEW

This slide explains the design trade-off you might be quietly wondering about. Why DEFERRED_WITH_NET
as the fallback, not just plain SKIP?

Look at the table.

- BUILD_EAGER, when forecast over-estimates and actual is below m — loss bounded by B. Even if forecast is 100× wrong, we waste at most one build's worth of work.
- DEFERRED_WITH_NET common case, forecast accurate and actual stays below T — zero excess, safety net never fires, edge runs at no-prefilter cost plus a per-vertex counter.
- DEFERRED_WITH_NET residual danger zone, actual lands near T — up to B excess. Same bound as BUILD_EAGER.
- DEFERRED_WITH_NET, actual way exceeds T — clean win.

Now the bottom row — the option we REJECTED. Pure SKIP, no safety net. If forecast under-estimated,
actual could be 100× the forecast and we eat the full un-optimized cost. UNBOUNDED — scales with
actual volume.

The bounded-loss contract — every mode's worst case at most ~B per edge — is the entire reason we
chose DEFERRED_WITH_NET over SKIP. Aggregate benchmark performance is one thing; per-query latency
cap is what operators care about. Without bounded loss you can't promise consistent SLA on
heterogeneous query mixes.

---

## Slide 18 — Three modes in time (when does the build actually fire?)

Visual of the three modes in time. Top row, BUILD_EAGER — vertex 1 triggers the build, every
subsequent vertex gets filtered. Build cost amortized across 200 traversals. Middle row,
DEFERRED_WITH_NET — vertices 1 through 11 process at full unfiltered cost (these are the
deferral-tax neighbors we discussed), accumulator reaches T at vertex 12, build fires, remaining 188
vertices get filtered. The math has to work out that the deferral-phase loss is less than the
build's savings on the remaining vertices — that's why T has the m floor. Bottom row, REJECT — every
vertex loaded fully. No optimization, no build. That's the contract for unknown-selectivity case:
better to be slow but predictable than to build an unbounded RidSet we can't reason about.

---

## Slide 19 — The CLT subtlety (why small samples can't be trusted)

Why the sample-size guard? `forecast = sourceRows × meanFanOut`. That mean is a statistic of the
fan-out distribution. LDBC SNB messages-per-person follows a power law — most users have 5 to 50
messages, a few have 50,000 plus. Sample 3 random users and the mean is dominated by whether you
happened to catch a whale. Sampling 3 from this distribution gives basically random forecasts. The
Central Limit Theorem says the sample mean is roughly normal for N around 30 or higher, even for
skewed distributions. Below 30, no statistical guarantee. Committing to BUILD_EAGER on a
noise-quality forecast is a coin flip on a one-time cost. So below 30 effective root-lineage rows we
route to DEFERRED_WITH_NET. The runtime accumulator catches genuinely high-volume edges; we just
don't commit eagerly on bad data. Number 30 isn't a tuning knob — it's a textbook statistical
threshold.

---

## Slide 20 — Where the forecast comes from (stampEdgeForecasts)

Where does the forecast come from? A new planner pass after the schedule walk. For each edge with an
IndexLookup descriptor: compute sourceRows from the upstream alias, fanOut from EdgeFanOutEstimator,
multiply with saturation guard, stamp onto the edge.

Two pitfalls.

First, the scheduler uses `Long.MAX_VALUE` as a sentinel for inferred-class aliases — forces low
scheduling priority. Multiplying THROUGH MAX_VALUE poisons every downstream forecast. So we strip
those sentinels BEFORE any arithmetic.

Second, on overflow we store minus one — absent — not the clamped MAX_VALUE. A clamped MAX_VALUE
would trivially satisfy `forecast > m` and force BUILD_EAGER on every edge regardless of cost.

Both pitfalls have regression tests. The forecast pipeline has subtle correctness invariants because
the consumer is a comparison against a threshold — wrong inputs silently produce wrong decisions.

---

## Slide 21 — Worked example: IC4 forecast propagation ★ NEW

Let's make this concrete. LDBC SNB IC4. The query — a Person, their friends through KNOWS, the
friends' Posts created in a 30-day window, count the tags. The interesting edge is `friend ←
HAS_CREATOR ← post` — that's where the IndexLookup lives, because Post has creationDate indexed.

Plan-time row propagation walks the schedule. Start with person count 1 because we're RID-pinned.

- Edge 1 to friend: average fan-out of KNOWS from Person is about 38. So friend count estimate is 38.
- Edge 2 from friend back to post: average fan-out of HAS_CREATOR-incoming-to-Person is about 100. `sourceRows × fanOut = 38 × 100 = 3,800`. Stamp 3,800 on this edge's forecast field.

Selectivity from the histogram for a 30-day window is 0.028. `m = 33,000 / (100 × 0.972) ≈ 340`.
3,800 is way more than 340, so BUILD_EAGER fires.

Compare to the naive accumulator: deferral phase loads about 3 friends at full cost before
accumulator passes 340, build fires, remaining 35 friends filtered. Total cost about 767·L versus
our 436·L. That's 1.76× faster on this edge ALONE, just from skipping the deferral tax. Multiply
across all edges in the query and you get the IC4 number from the results slide.

---

## Slide 22 — The cache in action (built once, reused for everyone after)

Visual recap of caching. Friend 1 hits the cache, misses, runs gates, builds, caches the RidSet,
applies to its own link bag. Friends 50 and 200 — hundreds of vertices later — hit the cache and
reuse the same RidSet for free. The whole reason this optimization is profitable: the index result
is constant for the query, so we build once and amortize. The bottom path — `cache.put(key, null)`
plus a skip reason in the parallel map — is the rejection branch. Once we decide an edge isn't worth
it, subsequent vertices on that edge skip without re-running gates. Cache capacity is 64 per edge —
plenty for any realistic pattern, tiny memory footprint.

---

## Slide 23 — Per-edge cache anatomy

Code-level look at the cache. `LinkedHashMap` with capacity 64, LRU eviction. The parallel
`cachedSkipReasons` map is subtle but important. When a vertex hits a cached null, you want to
restore the ORIGINAL rejection reason — not the most recent skip from somewhere else. Without the
parallel map, an external skip event between caching and retrieval would overwrite the cause. With
it, the cause is preserved. `BUILD_FAILED` is the other null case — both gates passed but the actual
scan returned null mid-stream. Could be cap exceeded mid-scan, missing target on a reverse-edge
lookup, empty stream. Cached with reason `BUILD_FAILED` so PROFILE doesn't lie with `reason: NONE`.

---

## Slide 24 — What ends up in the cache (permanent vs transient skips) ★ NEW

Operational detail that matters in practice. When the gates reject, sometimes the rejection is
permanent for this query — every vertex on this edge should skip without re-running gates.
Sometimes it's transient — the next vertex might give a different answer. The cache distinguishes
these.

Permanent rejections — left column. `SELECTIVITY_TOO_LOW`: selectivity is a class-wide constant.
`STATS_UNAVAILABLE`: no histogram is a schema fact, doesn't appear mid-query. `CAP_EXCEEDED`:
estimatedSize is a property of the index condition against the class, not of any vertex.
`BUILD_FAILED`: same index plus same parameters plus same data equals same failure. All four get
`cache.put(key, null)` plus the skip reason stored in the parallel `cachedSkipReasons` map.

Transient skips — right column. `BUILD_NOT_AMORTIZED` in DEFERRED_WITH_NET mode: the accumulator
may cross T later and the build should still fire. If we cached null here, the safety net would
die — subsequent vertices would short-circuit and the accumulator would never update.
`LINKBAG_TOO_SMALL`: different source vertices have different bag sizes. `OVERLAP_RATIO_TOO_HIGH`:
EdgeRidLookup's ratio depends on the per-vertex `$matched.X` binding.

The operational consequence: cache hits with null short-circuit the whole gate evaluation in O(1).
Transient skips don't poison the cache, so the next vertex starts fresh and gates run again with
up-to-date state. That's the design contract you want to verify with PROFILE — see next slide.

---

## Slide 25 — PROFILE output (how to tell if it's firing)

How to verify this in production. PROFILE prints per-edge counters. `applied` versus `skipped`
count. RidSet size after resolve. Build time in nanos. `filterRate` — fraction of link bag entries
the filter rejected. A 0.96 filter rate means the optimization rejected 96 percent of would-be loads
— great. A skip diagnostic carries the threshold value. `"NEVER APPLIED (reason:
BUILD_NOT_AMORTIZED, threshold: T = 12000)"` tells you exactly why it didn't fire and how big the
volume gap was. Eight enumerable skip reasons cover every code path: NONE, CAP_EXCEEDED,
SELECTIVITY_TOO_LOW, BUILD_NOT_AMORTIZED, LINKBAG_TOO_SMALL, OVERLAP_RATIO_TOO_HIGH, BUILD_FAILED,
STATS_UNAVAILABLE. Global metric `PREFILTER_EFFECTIVENESS` aggregates filter rate across all queries
— that's your headline operational metric for ops.

---

## Slide 26 — Measured impact (LDBC SNB SF 1)

LDBC SNB SF1 numbers, single-thread and multi-thread percentage speedups against own-baseline. IC3 —
two IndexLookups stacked on one pattern, biggest gain. IC9 — single IndexLookup on creationDate. IS7
— index on edge target property. IC13 — a modest 7 percent. That one is interesting — IC13 doesn't
have an IndexLookup attached. The 7 percent comes from the CLT gate PREVENTING a BUILD_EAGER
mis-fire that would have happened with the old logic. So the gates aren't just for capturing gains;
they prevent regressions too. Percentages are not additive across queries — each row is its own
measurement against its own baseline. What's NOT in this table: queries where the gates correctly
chose not to build. Those stay at baseline. No regression. That's the bounded-loss contract from the
earlier slide actually delivering in measurements.

---

## Slide 27 — Honest limitations (what this design does NOT solve) ★ NEW

What we did NOT solve. Three things, all acknowledged.

First — residual danger zone at the trigger point. Any deferred-build strategy must throw away some
savings during the deferral phase. The worst case is at actual n near T — about B of excess work. We
bound it; we don't eliminate it.

Second — forecast quality. Population mean fan-out is wrong for heavy-tail distributions.
BUILD_EAGER will mis-fire on the low-degree tail. Each mis-fire is bounded by B, but the RATE
depends on forecast inputs. Aggregate JMH numbers HIDE regressions on the median execution because
high-degree users dominate wall-clock time. If you want median-user fairness, the fix is
per-subclass degree statistics or surviving-subpopulation estimates — separate work stream.

Third — histogram accuracy. m is computed from `estimateHits`. On skewed value distributions,
`estimateHits` can be 2 to 5 times off in either direction. m shifts proportionally; decisions
become wrong before any of our logic runs. Better histograms — multivariate stats, more buckets —
would benefit every cost-based decision in the planner, not just this one.

None of these are reasons not to ship. They're acknowledged limits that need their own follow-up
work.

---

## Slide 28 — What's next

Three follow-ups already on the radar. Live cost measurement for `loadToScanRatio` — today it's
static 100, but actual values range from 20 on warm cache to 500 on cold SSD with large records. The
obstacle is sample bias — pre-rejected vertices never get loaded, so we never measure their
hypothetical load cost. Plan: collect samples from queries where pre-filter did NOT fire and weight
accordingly. Per-property edge fan-out histograms — today we have class-mean only, which is exactly
the heavy-tail problem from the previous slide. Forecast propagation through multi-branch and
optional patterns — currently we approximate diamond patterns conservatively, which routes to
DEFERRED_WITH_NET more often than strictly needed. Acceptable for now but worth revisiting if
production telemetry shows the pattern is common.

---

## Slide 29 — Where to look in the code

If you want to verify the design from the code. Start with `RidFilterDescriptor` — the descriptor
record, gate 1 lives on the `IndexLookup` variant. Then `EdgeTraversal` — `resolveWithCache` is the
entry point, `checkIndexLookupAmortization` is gate 2 with its three-way decision,
`evaluateIndexLookupAmortization` is the Composite-rescue variant. Then `MatchExecutionPlanner` —
`optimizeScheduleWithIntersections` attaches the descriptor, `stampEdgeForecasts` runs the forecast
pass with the `anyEdgeHasIndexLookup` short-circuit. `TraversalPreFilterHelper` holds
`findIndexForFilter` for planner-side detection and `resolveIndexToRidSet` for runtime
materialisation. Plus `MatchStep.appendPreFilterStats` emits the PROFILE diagnostic strings. Durable
ADR lives in `docs/adr/index-assisted-traversal/design-final.md`. The implementation PR is number
973 on the JetBrains/youtrackdb repo — design details there too.

---

## Slide 30 — Thanks (questions?)

Questions. IndexLookup is one descriptor in a broader RidSet pre-filter mechanism — the companion
deck covers all four descriptor types end to end if you want the wider picture. Happy to dig into
the cost arithmetic, the forecast propagation, the regression test cases, or how this lands against
Neo4j's NodeHashJoin equivalent.
