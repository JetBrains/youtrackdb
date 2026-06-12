# Index Lookup Pre-Filter in MATCH — Slides + Speaker Notes

Internal JetBrains tech talk. ~25 min. Slide content is what appears on the
deck; speaker notes are what you say. Times are guidance, not strict.

Scope: three PRs that build, validate, and tune the **pre-filter** mechanism
for MATCH edge traversals. Hash-join work on MATCH (#918, #946) is a sibling
optimization family covered separately.

---

## Part 0 — Setup (3 min)

### Slide 1 — Title

**On slide:**
- Index Lookup Pre-Filter in MATCH
- Three PRs, one mechanism
- #822 → #923 → #973
- Sandra Adamiec, YouTrackDB

**Speaker (30s):**
This talk covers the pre-filter mechanism for MATCH edge traversals.
Three PRs that share one idea: filter vertices before you load them from
disk. I will walk you through the architecture, then the cost model,
then how to read what the optimization does at runtime through PROFILE.
By the end you should be able to debug a MATCH query and know whether
the pre-filter fired, why, and what it cost.

---

### Slide 2 — TL;DR and the page you read in MATCH

**On slide:**
- MATCH loaded every vertex from disk only to throw most of them away
- Three PRs move filtering before `loadEntity()`
- Three mechanisms: class ID check, index RID intersection, adjacency intersection
- IC7 +1087%, IC12 +106%, IS7 +220% from #822 alone on JMH LDBC

**Diagram:** LinkBag → `loadEntity()` → WHERE filter, with arrow on `loadEntity` labelled "90% of cost"

**Speaker (60s):**
Look at the diagram. A MATCH edge traversal walks a LinkBag, calls
`loadEntity` for each RID, then asks the WHERE clause if the vertex matches.
That `loadEntity` call is the expensive part. On a forum with ten thousand
posts, when WHERE matches three of them, we load and throw away nine
thousand nine hundred ninety seven. The fix is small in idea and big in
plumbing: we push the filter before the load. If we know the class we
want, we check the collection ID. If we have an index, we resolve to a
RID set and intersect. If we have a back-reference to a bound vertex, we
intersect with its reverse LinkBag. Three PRs build, validate, and tune
this mechanism.

---

### Slide 2b — Glossary for the next 25 minutes

**On slide:**

| Term | One-line definition |
|---|---|
| **MATCH** | YTDB's pattern-matching SQL extension. `{class: X, as: a, where: …}.out('E'){as: b}` = "find vertices of class X bound to alias `a`, follow edge `E` to vertices bound to `b`" |
| **LinkBag** | YTDB's on-disk list of target RIDs that an edge property holds. A vertex's `out('KNOWS')` reads its `out_KNOWS` LinkBag |
| **RID** | Record ID, `cluster:position`. `cluster` ≈ a class's storage segment (collection ID below) |
| **collection ID** | Numeric tag every record carries identifying its class — let us check class without loading the record |
| **`@class`, `@rid`** | Metadata accessors in SQL: class name and RID of the current record |
| **`$matched`** | Inside MATCH, the bindings already made earlier in the pattern. `$matched.start.@rid` = the RID of whatever was bound to alias `start` |
| **LDBC SNB IC1–IC13** | A standard graph-DB benchmark suite. Each IC is a fixed read-heavy query against a synthetic social network |
| **pre-filter** | What this whole talk is about. Reject a vertex *before* the disk read that loads it |
| **JMH** | The benchmark framework that produces the numbers we will look at |
| **PROFILE** | A SQL keyword that wraps a query and emits per-step timing and counters |

**Speaker (60s):**
Quick glossary for anyone who has not touched MATCH. MATCH is our pattern
matching SQL extension. The shape inside curly braces is a vertex,
followed by an edge step like out or in, then another vertex. LinkBag is
the on disk list of target RIDs of an edge. RID is record ID, cluster
plus position. Collection ID is the numeric tag every record carries
identifying its class, which is how we check class without loading the
record. Dollar matched is the MATCH context that holds already bound
aliases. LDBC SNB is the social network benchmark suite, IC stands for
interactive complex, queries numbered one through thirteen. PROFILE is
the SQL keyword that emits per step counters. If you forget any of
these, this slide is the cheat sheet.

---

## Part 1 — Problem (5 min)

### Slide 3 — IC3 as our reference case

**On slide:**

```
MATCH {class: Person, as: start, where: (id = :personId)}
  .out('KNOWS'){while $depth<2, as: person,
                where @rid <> $matched.start.@rid},

  {person}.out('IS_LOCATED_IN').out('IS_PART_OF')          ← BRANCH A
    {where: name NOT IN [:countryX, :countryY]},

  {person}.in('HAS_CREATOR')                                ← BRANCH B
    {as: message, where: creationDate >= :start AND < :end}
    .out('IS_LOCATED_IN')
    {as: msgCountry, where: name IN [:countryX, :countryY]}
RETURN person, msgCountry
```

Four filter shapes in one query:
- **Index-backed range:** `creationDate >= … AND < …`
- **Index-backed IN list:** `msgCountry.name IN [...]`
- **Class/NOT-IN literal:** `country.name NOT IN [...]`
- **Multi-branch:** two sub-patterns from `person`

**Speaker (75s):**
This is LDBC IC3, friends in countries. The reason it is our anchor is
that it carries four different filter shapes in a single query. There is
a date range on the message that maps to an index. There is an IN list
on the message country, also indexed. There is a NOT IN list on the
person country. And the whole thing has two branches from the friend
vertex, one going to the friend's country, one going to the friends's
messages. We will see each PR in this talk hit a different one of those
four shapes. By the time we get to the closing slide you will see why
IC3 shows up as a benefit three times across the chain.

---

### Slide 4 — Three bottleneck classes

**On slide:**

| Class | Example query shape | Why slow |
|---|---|---|
| Back-reference | `out('E'){where: @rid = $matched.X.@rid}` | Per-row scan of a LinkBag we already know the target of |
| Class filter after expand | `expand(out('E'))` + `WHERE @class = 'Post'` | Loads Comments only to discard them |
| Index-backed property | `WHERE creationDate >= :start` on a target | Loads vertices outside the date window |

- IC3 (our anchor) hits **two** of these three classes
- IC5 adds a back-reference on its final edge
- IC10 mixes class filter and back-reference
- All three classes load vertices that the WHERE clause will then reject

**Speaker (75s):**
We did not pick these three patterns from a textbook. They came out of
profiling LDBC SNB. Take our anchor IC3: the date range and IN list are
both index-backed property filters, and the NOT IN list against the
person country is effectively a class filter. So IC3 alone hits two of
the three classes. IC5 adds the third one, the back-reference on its
final edge. IC10 mixes class filter and back-reference. The three rows
in the table are the three forms of "we know in advance this vertex
will be rejected, and we can prove it without loading the vertex."

---

### Slide 4b — Three query shapes that hurt today

**On slide:**

```
1. Class filter after edge
   .out('E'){where: @class = 'Post'}
   ← walks every LinkBag entry, loads every vertex,
     filters at the end — only ~3% are Post

2. Index-backed range / IN list
   .out('E'){where: creationDate >= :start AND < :end}
   .out('E'){where: msgCountry.name IN [:cX, :cY]}
   ← walks every LinkBag entry, loads every vertex,
     filters at the end — index is unused

3. Back-reference (adjacency intersection)
   .out('E'){where: @rid = $matched.X.@rid}
   ← walks every LinkBag entry, loads every vertex,
     filters at the end — but we know the target RID
```

All three have the same structure: **walk LinkBag → load every vertex →
filter at the end**. The fix is push the filter *before* the load.

**Speaker (60s):**
Three shapes, one structure. Each walks the LinkBag, loads every vertex,
filters at the end. The fix is to push the filter before the load.
Shapes one and two come from class and index information available at
plan time. Shape three is the back-reference case, we already know the
exact RID we want. Every PR in this talk fixes one or more of these
three shapes. PR eight twenty two adds the mechanism. PR nine twenty
three adds tests plus a few corner cases. PR nine seventy three tunes
the cost model so the mechanism stays correct under varying statistics.

---

### Slide 5 — Why this was not fixed earlier

**On slide:**
- MATCH scheduler picks edges by cost
- But after an edge is picked, the traversal forgets about the target's WHERE
- And it forgets that another alias may pin the target's RID via `$matched`
- The scheduler optimises the order; nothing optimises the scan inside an edge

**Speaker (60s):**
The MATCH planner already does cost-based edge ordering. It picks the
cheapest edge first, then the next cheapest given the bindings so far.
That part works. The gap is that once an edge is chosen, the runtime
treats it as a plain LinkBag walk. It does not look at the target's
WHERE clause, and it does not look at whether another alias has pinned
the target's RID. Closing this gap is what the first PR does.

---

## Part 2 — PR #822: foundation (8 min)

### Slide 6 — Three descriptor types

**On slide:**

Maps directly to shapes 1, 2, 3 from Slide 4b.

| Case | Shape | What it filters by | Where the test runs | I/O cost |
|---|---|---|---|---|
| A — Class | shape 1 | `WHERE @class = 'Post'` | collection ID in LinkBag iterator | zero |
| B — IndexLookup | shape 2 | indexed property condition | resolved RID set, intersected | one index scan |
| C — EdgeRidLookup | shape 3 | `out('E').@rid = $matched.X.@rid` | reverse LinkBag RID set, intersected | one LinkBag walk |

- Filter target: `VertexFromLinkBagIterator` fields `acceptedCollectionIds` and `acceptedRids`
- The test runs *before* `loadEntity()`

**Speaker (75s):**
Three descriptor types cover the three bottleneck classes. Case A checks
the class through the collection ID, no disk access at all. Case B asks
the index for the set of RIDs that match the condition, then intersects.
Case C reads the reverse adjacency list of the already-bound vertex and
intersects. All three end up as fields on the LinkBag iterator. The
iterator checks them before it calls `loadEntity`. Same iterator code,
three different data sources for "what RIDs are acceptable here."

---

### Slide 7 — Shared helper, two callers

**On slide:**

```
TraversalPreFilterHelper
        │
        ├──► ExpandStep            (SELECT expand(out('E')) ... WHERE ...)
        └──► MatchEdgeTraverser    (MATCH .out('E'){where ...})
```

- One AST → descriptor pipeline
- Two consumers, same descriptor types, same iterator
- Symmetry is deliberate: SELECT expand and MATCH have the same bottleneck shape

**Speaker (60s):**
Pre-filter is not MATCH-only. `SELECT expand(out('E'))` followed by a
`WHERE` is the same problem in a different syntax. So the analyzer lives
in `TraversalPreFilterHelper`, and both `ExpandStep` and `MatchEdgeTraverser`
call it. One place to maintain, one place to test, one set of descriptor
types. When someone adds a new query shape later, they extend the helper
and both call sites pick it up.

---

### Slide 8 — Planner pass

**On slide:**
- `MatchExecutionPlanner.optimizeScheduleWithIntersections()`
- Runs *after* cost-based edge reordering
- For each scheduled edge: does the target's WHERE have a selectivity estimate below threshold?
- If yes, attach a `RidFilterDescriptor` to the `EdgeTraversal`

**Speaker (60s):**
The planner pass is a second pass. The first pass reorders edges by
cost. The second pass walks the ordered edges and asks: does this
edge's target have a WHERE that we can pre-filter? If yes, attach a
descriptor. This separation matters. The cost model already estimates
selectivity. We reuse that estimate to gate attachment. We do not run
the pre-filter on edges where it would not pay off.

---

### Slide 9 — Adjacency intersection in pictures

**Diagram:**

```
forward LinkBag (forum → posts)    reverse LinkBag (person → posts)
  ●●●●●●●●●●●●●●●●●●●●●               ●●●● (just a few)
                ▼  intersect  ▼
                 ●●●  (3 posts)
```

- Forward: thousands of posts in the forum
- Reverse: a few posts created by the friend
- Intersection: load only those few

- Backed by `RidSet`: IntMap + Roaring64Bitmap (PR #781)
- Probe is O(1) per LinkBag entry

**Speaker (75s):**
This is the picture for IC5. The forward LinkBag is huge: every post in
the forum. The reverse LinkBag is small: posts created by this one
friend. Their intersection is what we actually want. The data structure
underneath is `RidSet`, rewritten in PR #781 to use an IntMap of
Roaring bitmaps. Cluster ID maps to a bitmap of positions. Membership
test is a few instructions. That is what makes the per-entry probe
cheap enough that intersecting beats loading.

---

### Slide 10 — What changes after #822 (qualitative)

**On slide:**
- `WHERE @class = 'X'` after expand: target class skipped without I/O
- Indexed property filter on target: RID set intersected with LinkBag during iteration
- Adjacency intersection: forward LinkBag ∩ reverse LinkBag from a bound vertex
- Planner attaches descriptors per edge during `optimizeScheduleWithIntersections`

- See closing slide for cumulative measured impact across the chain

**Speaker (45s):**
Three pre-filter mechanisms in place after PR eight twenty two. Class
filter, index filter, adjacency intersection. All three live behind the
same iterator. The planner decides which descriptor to attach per edge.
Measured numbers come at the closing slide where we look at the whole
chain together.

---

### Slide 11 — What #822 left on the table

**On slide:**
- One selectivity formula for all descriptor types: `ridSetSize / linkBagSize`
- RID literals and parameters not handled: `@rid = #12:0`, `@rid = :param` slipped through
- `BETWEEN` not recognised as an indexable range
- No observability: PROFILE did not say whether pre-filter fired

**Speaker (60s):**
PR #822 is the foundation, not the finish line. Four gaps. One: the
selectivity check is a single formula, fine for adjacency intersection
but wrong for IndexLookup, and we will fix that in PR #973. Two and
three: small parser gaps that PR #923 picks up while writing tests.
Four: no PROFILE output, which we cannot live with once we start
tuning, and that lands in #973 too.

---

## Part 3 — PR #923: validation (4 min)

### Slide 12 — Why 90 tests

**On slide:**
- Pre-filter is invisible in query results
- Visible only in EXPLAIN plan shape
- Without systematic tests, the next refactor can silently disable it
- "Optimization that does not fire" looks identical to "optimization that fires"

**Speaker (45s):**
The thing about pre-filter is that it is correct either way. If it does
not fire, the query still returns the right answer, just slower. So a
regression is invisible to any test that checks results. We needed a
test layer that asserts "this query *uses* the pre-filter" through
EXPLAIN. That is what PR #923 is. Ninety tests, every one checking the
plan shape, not just the answer.

---

### Slide 13 — Test coverage matrix

**On slide:**

| Axis | Coverage |
|---|---|
| Descriptor types | 4 (IndexLookup, EdgeRidLookup, DirectRid, Composite) |
| Graph topologies | 11 (linear, star, diamond, triangle, tree, bipartite, self-ref, multi-edge, fan-out, fan-in, isolated) |
| Edge methods | 6 (out, in, outE+inV, inE+outV, bothE, both) |
| Negative tests | 20 (cases where pre-filter must *not* fire) |

- Every positive test asserts the descriptor type in EXPLAIN
- Every negative test has a comment explaining why

**Speaker (60s):**
The matrix is the contract. Four descriptor types times eleven topologies
times six edge methods gives us the surface. The twenty negative tests
are the interesting part. `both()` cannot use pre-filter because
direction is ambiguous. `WHILE` cannot use it because the target class
changes per hop. `OR` in WHERE cannot use it without losing
correctness. Each negative test is a comment-as-spec: this is the
boundary, here is why.

---

### Slide 14 — Two fixes that fell out

**On slide:**
- **DirectRid singleton:** `WHERE @rid = #12:0` or `:param` now creates a singleton intersection. Was silently skipped.
- **BETWEEN as range:** `SQLBetweenCondition.flatten()` rewrites `val BETWEEN low AND high` into `val >= low AND val <= high`. Existing index infra now recognises it.

- Both fixes were invisible without the test suite
- Writing tests is faster than reviewing for completeness

**Speaker (45s):**
Two bugs. Writing tests caught both. RID literals and parameters were
silently skipped because the planner only handled `$matched.X.@rid`
back-references. BETWEEN was not recognised as an indexable range
because the condition class did not implement `isIndexAware`. Both
fixes are tiny. Both would have stayed hidden without the test layer.
The lesson is on the slide: structured tests find structural bugs.

---

## Part 4 — PR #973: cost-model tuning (8 min)

### Slide 24 — The problem that surfaced after #822 deployment

**On slide:**
- All descriptor types shared one formula: `ridSetSize / linkBagSize`
- That ratio is per-vertex overlap
- Correct semantics for `EdgeRidLookup` (the LinkBag changes per source vertex)
- Wrong semantics for `IndexLookup` (the RID set is class-level, constant per query)

**Speaker (75s):**
Once #822 was deployed we started reviewing PROFILE output on
production-shaped workloads. For IndexLookup edges, the selectivity
check ran per vertex and made the same decision every time. That is a
clue. A signal that does not change should not be sampled per vertex.
Worse, the formula it was using had per-vertex overlap semantics, which
makes sense for adjacency intersection but is meaningless for an
index-backed set. So we split the check.

---

### Slide 25 — Per-descriptor selectivity

**On slide:**

```java
sealed interface RidFilterDescriptor {
  boolean passesSelectivityCheck(int linkBagSize, int ridSetSize);
}

// DirectRid: always passes
// EdgeRidLookup: ridSetSize / linkBagSize ≤ edgeLookupMaxRatio (default 0.8)
// IndexLookup: class-level selectivity ≤ indexLookupMaxSelectivity (default 0.95)
// Composite: passes if any child passes
```

- Each type has its own threshold and its own data source
- `IndexLookup` looks at class statistics, not per-vertex overlap

**Speaker (60s):**
Four concrete descriptor types now, each with its own check.
`DirectRid` always passes because it is a singleton. `EdgeRidLookup`
keeps the per-vertex overlap ratio, threshold 0.8. `IndexLookup` asks
the index statistics for class-level selectivity, threshold 0.95.
`Composite` passes if any child passes. The thresholds are
configurable. The defaults come from LDBC SF 1 calibration.

---

### Slide 26 — Build cost for IndexLookup

**On slide:**
- `IndexLookup` build = materialise RID set from index
- Cost: one index scan, allocates a `RidSet`
- One-time cost per query, but charged on the first qualifying vertex
- Question: does the traversal volume justify the build?

**Break-even formula:**

```
m = estimatedSize / (loadToScanRatio × (1 − selectivity))
```

- `loadToScanRatio` ≈ 100 (random vertex load is ~100× a RidSet probe)
- `m` is the number of LinkBag entries we need to filter before the build pays for itself

**Speaker (75s):**
The selectivity check decides *whether* to pre-filter. We also need to
decide *when* to materialise. Building the RID set is not free. One
index scan, one allocation. If the traversal only touches a handful of
vertices, the build cost never amortises. The formula on the slide is
the break-even. Estimated index size, divided by load-to-scan ratio,
divided by the fraction the filter rejects. If the LinkBag walk will
touch at least `m` entries, the build is worth it.

---

### Slide 27 — Two modes: BUILD_EAGER vs DEFERRED_WITH_NET

**On slide:**

```
                  ┌──────────────────────────────────┐
                  │ first qualifying vertex          │
                  └─────────────┬────────────────────┘
                                ▼
                  ┌──────────────────────────────────┐
                  │ forecastN > ceil(m)              │
                  │ AND rootSourceRows >= 30 (CLT)   │
                  │ AND selectivity known            │
                  └──────┬────────────────────┬──────┘
                       yes                   no
                         │                    │
                         ▼                    ▼
                  BUILD_EAGER         DEFERRED_WITH_NET
                  (materialise now)   (accumulate, trigger at T=max(2·forecastN, m))
```

- Decision memoized per edge on first call
- Memoization key: `EdgeTraversal` identity

**Speaker (60s):**
Two modes. `BUILD_EAGER` materialises on the first qualifying vertex.
`DEFERRED_WITH_NET` waits. Three conditions need to hold for eager: the
forecast says we will touch more than `m` entries, the root sample size
is statistically meaningful (we will see why in two slides), and the
selectivity estimate exists. If any fails, we defer and let the runtime
accumulator decide. The decision is memoized per edge.

---

### Slide 28 — Why CLT gate at 30

**On slide:**
- `forecastN = sourceRows × fanOut_mean`
- Relative error of forecast scales as `CV_fanOut / sqrt(N)`
- LDBC SNB has heavy-tail fan-out (messages-per-person, power-law)
- Small `N` ⇒ forecast is essentially random
- Below `N = 30` (textbook CLT threshold): route to `DEFERRED_WITH_NET`, let runtime accumulator decide

**Diagram:** fan-out histogram from LDBC with sample-of-30 region marked

**Speaker (90s):**
The forecast is `sourceRows times mean fan-out`. We are treating the
sources as a sample from the fan-out distribution. The Central Limit
Theorem says the mean of a sample of size `N` has relative error
proportional to one over square root of `N`. For LDBC SNB the fan-out
distribution is heavy-tailed. Messages per person follows a power law.
That means the coefficient of variation is large. With a small sample,
the forecast is unreliable. Committing to `BUILD_EAGER` on a bad
forecast is the worst outcome: we pay the build, the traversal stops
early, we never amortise. Below the CLT threshold we route to
`DEFERRED_WITH_NET` and let reality decide. Thirty is the textbook
threshold for CLT, not a tuned constant.

---

### Slide 29 — Unknown selectivity → REJECT

**On slide:**
- When does `estimateSelectivity()` return `-1`?
  - Histogram missing
  - Index never loaded
- Old behaviour: optimistic `PROCEED`, capped by fixed 100K `maxRidSetSize`
- New behaviour: REJECT, with skip reason `STATS_UNAVAILABLE`
- Why the change: cap is now auto-scaled (up to 10M of RAM), the old safety net is gone

**Speaker (60s):**
One detail with a sharp edge. Before PR #973, when the selectivity
estimate came back as unknown, we proceeded optimistically. That was
safe because the RID set cap was a hard 100K. The cap was a safety
net. PR #973 auto-scales the cap based on heap size, up to 10M. The
safety net is gone. So we changed the optimistic PROCEED to a
defensive REJECT, with a named skip reason. The class-level estimate
is cached, so the rejection is recorded once per query.

---

### Slide 30 — PROFILE output

**On slide:**

```
MATCH ... PROFILE:

  edge: forum.containerOf.post.hasCreator
    preFilter: applied=4823, skipped=0, ridSetSize=14210,
               buildTime=2.1ms, filterRate=0.96
    descriptor: EdgeRidLookup

  edge: post.hasCreator
    preFilter: NEVER APPLIED (reason: STATS_UNAVAILABLE,
               threshold: selectivity != -1)
    descriptor: IndexLookup
```

- Per-edge counters: applied / skipped / probed / filtered / buildTime / ridSetSize
- `PreFilterSkipReason` enum: 8 values
- Global metric: `PREFILTER_EFFECTIVENESS`

**Speaker (75s):**
This is what you read when debugging a slow query. Per edge, you see
whether the pre-filter applied, how many times, the build time, the
filter rate. When it did not apply you see why, with the threshold
that was checked. Eight skip reasons cover every gate in the pipeline.
The global effectiveness metric is filter-rate aggregated across all
queries. This output is the contract for the next person who has to
debug a pre-filter regression.

---

## Part 5 — Closing (3 min)

### Slide 31 — Best wins per PR (final JMH against own fork point)

**On slide:**

| PR | LDBC query | Δ% ST | Δ% MT | Mechanism |
|---|---|---|---|---|
| **#822** | IC7 recentLikers | **+1087%** | **+1110%** | lazy expand + class filter |
| #822 | IC12 expertSearch | **+106%** | **+101%** | adjacency intersection on hierarchy |
| #822 | IS7 messageReplies | **+220%** | **+212%** | index-backed property filter |
| #822 | IC1 transitiveFriends | +12% | +8% | adjacency intersection on KNOWS |
| **#923** | — | — | — | test suite + DirectRid + BETWEEN — no JMH-visible delta |
| **#973** *(open)* | IC3 friendsInCountries | **+1117%** | **+929%** | per-descriptor selectivity + amortization |
| #973 | IC9 recentFofMessages | **+483%** | **+433%** | unknown-selectivity REJECT prevents bad builds |
| #973 | IC13 shortestPath | +7% | +8% | smaller RidSets from CLT-gated builds |

- Each row is from that PR's own final JMH comparison comment
- Fork points differ per PR — percentages are *not* additive across rows
- #973's IC3 baseline is post-merge develop; the IC3 number reflects pre-filter activation that was dormant without #973's cost-model fix

**Speaker (60s):**
The wins per PR, each row from that PR's own JMH comparison. PR eight
twenty two: IC7 over ten times, IC12 over two times, IS7 over two
times. PR nine twenty three is a test suite PR with a couple of small
parser fixes; no headline benchmark delta, but the test suite is what
catches regressions in the rest of the chain. PR nine seventy three:
IC3 over eleven times, IC9 nearly five times, IC13 a small bump. The
caveat below the table matters. Each percentage is against a different
fork point, you cannot add them. What you can say: the chain produced
two queries with over ten times speedup on its own, IC7 from #822 and
IC3 from #973.

---

### Slide 31b — Back to IC3: where pre-filter fires

**On slide:**

The query we opened with — IC3 / `friendsInCountries`:

```
  BRANCH A: {person}.out('IS_LOCATED_IN').out('IS_PART_OF')
              {where name NOT IN [:cX, :cY]}            ← class filter / NOT IN

  BRANCH B: {person}.in('HAS_CREATOR')
              {message, where creationDate >= …}        ← IndexLookup (date)
              .out('IS_LOCATED_IN')
              {msgCountry, where name IN [:cX, :cY]}    ← IndexLookup (name IN)
```

Three pre-filter fire sites in one query:

| Part of IC3 | Descriptor | PR enabling | PR tuning |
|---|---|---|---|
| `creationDate >= … AND < …` | IndexLookup | #822 | #973 |
| `msgCountry.name IN [...]` | IndexLookup | #822 | #973 |
| `country.name NOT IN [...]` | Class filter (Case A) | #822 | — |

- Pre-filter alone activates these three sites
- Multi-branch handling is a sibling optimization (out of scope here)

**Speaker (60s):**
Back to the query we opened with. Pre-filter fires at three sites in
IC3. Two IndexLookup descriptors, one class filter. PR eight twenty
two enables all three. PR nine seventy three tunes the IndexLookup
activation so it stays correct across all vertices in the traversal,
which is where the IC3 number on the previous slide comes from. The
multi-branch structure of IC3, the two branches from person, is
optimised by a sibling project on hash join that is not part of this
talk. The pre-filter contribution alone is what we are showing here.

---

### Slide 32 — What's next

**On slide:**
- Live cost measurement from runtime metrics (`loadToScanRatio` is static today)
- Histogram coverage for edge properties (`EdgeFanOutEstimator` is class-level)
- Composite pre-filter for index + back-reference together
- Better integration with sibling hash-join work (out of scope here)

**Speaker (45s):**
Four things on the roadmap. Live cost measurement so the load-to-scan
ratio adapts to the storage tier. Histograms on edge properties so
fan-out estimates pick up correlation. Composite pre-filter that
combines index lookup and back-reference in one descriptor, for queries
where both apply. And tighter integration with the hash-join work that
lives in a sibling project — today they collaborate via shared
descriptors, but the activation logic could be more coordinated.

---

### Slide 33 — Reading order if you want to verify

**On slide:**

Root path: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/`

1. `TraversalPreFilterHelper.java`
2. `match/MatchExecutionPlanner.optimizeScheduleWithIntersections`
3. `match/EdgeTraversal.resolveWithCache`
4. `RidFilterDescriptor.passesSelectivityCheck`
5. `match/MatchStep.appendPreFilterStats` (what PROFILE shows)

- ADR: `docs/adr/index-assisted-traversal/design-final.md`

**Speaker (45s):**
Five files, in order, if you want to walk the implementation from the
top. Start with the helper that builds descriptors, follow the planner
pass that attaches them, then the runtime decision in
`resolveWithCache`, then the per-descriptor selectivity check, then the
PROFILE output. The ADR for PR eight twenty two is the durable design
document. Thanks. Questions.

---

## Backup slides (for Q&A)

### Backup A — `EdgeTraversal.cache`

- Per-edge LRU, capacity 64
- Key: descriptor cache fingerprint
- Value: resolved `RidSet`, or null with parallel `cachedSkipReasons` map
- Why a separate map for reasons: cached-null hit must restore the original skip reason, not the most-recent one

**Speaker (60s):**
This is the internal cache that PR #973 hardened. The cache stores RID
sets. When a key is rejected we cache null. Without the parallel
reasons map, an interleaved skip on a different vertex would mask the
original cause in PROFILE. With the parallel map, the cached-null hit
restores the original reason. Small fix, important for debugging.

---

### Backup B — `RidSet` data structure

- `Int2ObjectOpenHashMap<Roaring64Bitmap>` (cluster ID → bitmap of positions)
- Rewritten in PR #781 from a `HashSet<RID>`
- Membership test: ~3 instructions
- Iteration: cluster-by-cluster, friendly to LinkBag layout

---

### Backup C — `IndexSearchDescriptor.cacheFingerprint`

- Previously cache key was `index.getName()`
- Two IndexLookups on the same index with different conditions would have aliased
- Latent bug: planner happens to emit at most one IndexLookup per edge today
- Fix: include `keyCondition` and `additionalRangeCondition` in fingerprint
- Regression test: `EdgeTraversalCacheTest.indexLookup_cacheKey_distinctConditionsDoNotAlias`

---

### Backup D — `stampEdgeForecasts` short-circuit and `Long.MAX_VALUE` strip

- Forecast pass exists only to feed BUILD_EAGER decision
- Short-circuit: skip if no edge has `IndexLookup` (standalone or in Composite)
- `Long.MAX_VALUE` sentinels for inferred-class aliases stripped at input
- Saturated forecast stored as `-1`, not clamped (clamping would re-introduce inflation)

---

### Backup E — Why no live cost measurement in #973

- We considered using runtime metrics to update `loadToScanRatio` per query
- Sample bias: pre-filter pre-rejected vertices never get loaded, so we cannot measure their hypothetical load cost
- Solution: collect cost samples from queries where pre-filter did not apply
- Out of scope for #973, planned for a follow-up

**Speaker (60s):**
The most common question on this PR. We have `loadToScanRatio` as a
configurable constant, default 100. Why not measure it live? Because
pre-filter pre-rejects vertices that we would have measured. The
sample is biased toward cheap loads. To get an unbiased measurement we
have to collect from queries where pre-filter did not run, and we have
not built that path yet. It is on the list.
