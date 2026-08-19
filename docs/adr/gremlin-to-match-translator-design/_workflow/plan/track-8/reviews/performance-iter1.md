<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: PF1, sev: should-fix, loc: MultiPlanMatchStep.java:408-420, anchor: "### PF1 ", cert: C1, basis: "post-union dedup() loads every concatenated record through transaction.loadEntity just to read its RID; union(..).dedup().count() loads N rows it never needs"}
  - {id: PF2, sev: should-fix, loc: UnionStepRecogniser.java:70-107, anchor: "### PF2 ", cert: C2, basis: "N full child sub-walks run before the post-union suffix is checked, so every declining union shape (order/fold/path) pays and discards them on each traversal compilation"}
  - {id: PF3, sev: suggestion, loc: MatchExecutionPlanner.java:616-623, anchor: "### PF3 ", cert: C3, basis: "zero-estimate EmptyStep short-circuit disabled for all bare count(*) instead of paired with the empty-count guarantee; disjoint-pattern count goes O(1) to O(outer rows)"}
  - {id: PF4, sev: suggestion, loc: GremlinAggregateAssembler.java:52-68, anchor: "### PF4 ", cert: C4, basis: "non-polymorphic bare counts now pre-empt the single-step native fast path with a MATCH plan GremlinPlanCache always rejects, so every execution re-fingerprints and re-plans"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 12}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: NEGLIGIBLE, anchor: "#### C5 "}
  - {id: C6, verdict: NEGLIGIBLE, anchor: "#### C6 "}
  - {id: C7, verdict: NEGLIGIBLE, anchor: "#### C7 "}
  - {id: C8, verdict: NEGLIGIBLE, anchor: "#### C8 "}
  - {id: C9, verdict: NEGLIGIBLE, anchor: "#### C9 "}
  - {id: C10, verdict: NEGLIGIBLE, anchor: "#### C10 "}
  - {id: C11, verdict: NEGLIGIBLE, anchor: "#### C11 "}
  - {id: C12, verdict: NEGLIGIBLE, anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [should-fix] Post-union `dedup()` loads every concatenated record to read a RID it already has

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` (lines 408-420)

**Issue.** `dedupConcatStream` identifies a row by calling `result.getEntity(boundary)` and then
`entity.getIdentity()`. `ResultInternal.getEntity` (`ResultInternal.java:479-495`) resolves the
property, sees an `Identifiable`, and calls `transaction.loadEntity(id)` — a full record
materialisation — before the code discards the entity and keeps only the RID it started from. The
RID is already in the row: the boundary property holds the link, and `Identifiable.getIdentity()`
answers without touching storage.

**Cost trace.** Per concatenated row: one `loadEntity` call, one `Entity` wrapper, one `HashSet`
probe. For `R` concatenated rows with `D` distinct boundary entities:

- `union(c1, c2).dedup()` — `R` loads where `D` would do. The `R − D` loads on dropped duplicates
  buy nothing: the row is filtered out, so the base's `projectVertex` never runs on it. The `D`
  loads on surviving rows are pulled forward from the projection rather than added.
- `union(c1, c2).dedup().count()` — `R` loads where **zero** are needed. `configurePostUnionCount`
  appends `Count` after `Dedup`, so `isPushDownCountOnly` is false and `countConcatStream` drains
  the dedup filter row by row (`MultiPlanMatchStep.java:359-382`). Counting distinct RIDs needs no
  record content at all, yet every row is materialised.

**Scale check.** At 100 rows the cost is invisible. At 100 K concatenated rows with 50 % overlap the
path issues 100 K `loadEntity` calls against 50 K needed; duplicates whose first sighting has aged
out of the transaction record cache take a page read plus deserialisation each. At LDBC scale
(`union(out('knows'), out('likes')).dedup()` over a hub vertex set) the same ratio holds on a
larger `R`. Verdict: MATTERS AT SCALE, and MATTERS NOW for the `.dedup().count()` shape, which the
native pipeline serves with no record loads at all (`DedupGlobalStep` dedups on traverser identity).

**Suggestion.** Read the raw property and take the identity without loading:

```java
var raw = result.getProperty(boundary);
if (raw == null) {
  return null;
}
Object id = raw instanceof Identifiable ident ? ident.getIdentity() : raw;
return seen.add(id) ? result : null;
```

That also removes the `DatabaseException` `getEntity` throws for a non-entity payload, which the
`BoundaryOutputType.ELEMENT` gate in `DedupGlobalStepRecogniser` currently has to guarantee against.

### PF2 [should-fix] The union recogniser forks and re-walks every child before checking the suffix is translatable

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionStepRecogniser.java` (lines 70-107),
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionForkHostImpl.java` (lines 37-67)

**Issue.** `recognize` walks every global child to a full single-plan `TranslationResult` and only
then returns `ACCEPTED`. The walker resumes dispatch after that, and the step following the union
decides whether the whole traversal translates. When it declines — `order()` declines explicitly
(`OrderGlobalStepRecogniser.java:32-36`), `fold()` and every other unregistered step decline through
`dispatchAll`'s missing-recogniser branch — the walk returns `null` and all `N` child walks are
thrown away.

Each discarded child walk is not cheap. `walkFork` rebuilds `recognisedPrefixSteps()`, allocates a
`DefaultGraphTraversal`, clones every prefix step plus every child step, and runs a complete
`GremlinStepWalker.production().walk(...)`: reserved-prefix scan, two `resolveYtdbSession` calls
(each doing `tx.readWrite()`), a config read, a strategy lookup, a schema fetch, per-step
recognition, and a full `MatchPlanInputs` assembly with its MATCH AST and alias-filter maps. The
prefix is re-walked once per child, so the recognition cost is O(N × prefix) rather than
O(prefix + Σ children). `recognisedPrefixSteps()` itself runs N + 1 times and copies the prefix
twice on each call (`ArrayList` then `List.copyOf`).

**Cost trace.** `GremlinToMatchStrategy.apply` runs inside `traversal.applyStrategies()`, which
fires on every Gremlin traversal compilation (the strategy's own class Javadoc states this), and no
walk-level cache exists — only the built plan is cached. So a declining union shape pays N discarded
sub-walks on every execution of the query, not once.

**Scale check.** At one query the waste is tens of microseconds. On a server running
`g.V().hasLabel(L).union(out(a), out(b)).order().by(k)` (or any `union(...).fold()`, which Track 9
has not landed yet) at request rate, every request pays two full sub-walks and two `MatchPlanInputs`
builds before declining to native. The absolute per-call number stays small, but it lands on the
compile path of a query the translator ultimately refuses — the decline path this branch is meant
to keep cheap. Verdict: MATTERS AT SCALE.

**Suggestion.** Gate on the suffix before forking. `StepCursor` already exposes `peek()` and
`peek(int)` (`StepCursor.java:45-63`), so after `cursor.take()` consumes the union the recogniser
can scan the remaining significant steps and decline immediately unless each one is a
`CountGlobalStep`, a `RangeGlobalStepContract`, or a `DedupGlobalStep` — the three the post-concat
path can actually absorb. The scan is O(suffix) and allocation-free. While there, hoist
`recognisedPrefixSteps()` to a single snapshot taken once in `recognize` and pass it into
`walkFork`, and drop the second copy (`List.copyOf` over a list the method just built privately).

### PF3 [suggestion] The zero-estimate short-circuit is disabled for every bare `count(*)` rather than paired with the empty-count guarantee

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (lines 616-623)

**Issue.** Step 2a fixed a real correctness hole — a filtered MATCH count on an empty-but-existing
class returned zero rows instead of `{count: 0}`. The fix removes the zero-estimate `EmptyStep`
short-circuit for every bare `count(*)` shape and lets planning continue so
`handleProjectionsBlock` can attach `GuaranteeEmptyCountStep`. Correctness is restored, but the
short-circuit itself is a performance guard and it is now off for that whole class of query, not
replaced.

**Cost trace.** For a single connected pattern the loss is small: root candidates sort ascending by
estimate (`MatchExecutionPlanner.java:2155-2167`), so the zero-estimate alias becomes the first
root, its scan yields nothing, and execution ends quickly. The plan-shaping work
(`addPrefetchSteps`, topological scheduling, step chaining) now runs where it previously did not,
but it is behind the plan cache.

The disjoint case is the one that bites. With `subPatterns.size() > 1` the planner chains a
`CartesianProductStep`, whose sub-plans are ordered by `splitDisjointPatterns` and not by estimate,
and whose inner sub-plan is restarted per outer row (`CartesianProductStep.java:80-91`). A
`MATCH {class:Big, as:a}, {class:Empty, as:b} RETURN count(*)` with `Big` ordered first therefore
enumerates all of `Big` and starts and stops the empty inner plan once per row. Previously the
query returned through `EmptyStep` in O(1).

**Scale check.** 100 rows in `Big`: negligible. 1 M rows in `Big`: a full enumeration plus 1 M plan
start/stop pairs for a query whose answer is known to be `0`. The shape is narrow — Gremlin
translation produces connected patterns, so this is reachable through hand-written SQL MATCH — but
the regression inside it is unbounded. Verdict: MATTERS AT SCALE for that shape.

**Suggestion.** Keep the short-circuit and make it emit the guaranteed row: on the zero-estimate
branch, chain `EmptyStep` and then the same empty-count guarantee `handleProjectionsBlock` would
attach, and return. That preserves both the SQL `count(*)`-over-empty semantics Step 2a restored
and the O(1) exit the short-circuit exists for.

### PF4 [suggestion] Non-polymorphic bare counts pre-empt the native fast path with a plan the cache always rejects

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinAggregateAssembler.java` (lines 52-68)

**Issue.** `configureCount` lost its `if (!ctx.polymorphic()) return Outcome.DECLINE;` gate, so on a
session with `QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT` false (or `polymorphicQuery` set false on the
source) every recognised count shape now translates. For the two shapes
`YTDBGraphCountStrategy` already handles — `g.V().count()` and `g.V().hasLabel(L).count()` — the
translator wins the ordering race and replaces a one-step rewrite with the full MATCH route.

Both routes read the count from class metadata, so the asymptotics are identical. The constant is
not. `CountFromClassStep.canBeCached()` returns false, and `GremlinPlanCache.putInternal` honours
that (`GremlinPlanCache.java:70-85`), so the plan is never stored. Every execution therefore runs:
the recognition walk, `GremlinPlanFingerprint.fingerprint` (a `StringBuilder` over the whole
pattern), a `GremlinPlanCache.get` that always misses and records a miss metric, a full
`MatchExecutionPlanner.createExecutionPlan`, a rejected `put`, and a `plan.copy(ctx)`. The native
path it displaced was `YTDBGraphCountStrategy.apply` allocating one `YTDBClassCountStep`, whose
`processNextStart` calls `session.countClass(cl, polymorphic)` directly — and which already honoured
the non-polymorphic setting (`YTDBGraphCountStrategy.java:63-77`), so this is not a semantics fix
for those two shapes.

**Scale check.** The record count is metadata in both cases, so no scale of data changes the
picture; what changes is per-call latency on the cheapest query in the system, from roughly one
allocation to a fingerprint plus a plan build plus a plan copy on every call. Negligible inside a
request that also does real work; visible in a tight count loop or a count-heavy benchmark.
Polymorphic sessions are unaffected — they already took this route before Track 8. Verdict:
MATTERS AT SCALE, scoped to non-polymorphic configurations.

**Suggestion.** Either restore a narrow decline for exactly the two shapes `YTDBGraphCountStrategy`
serves (single `hasLabel` or no has-container, count as the only other step), or record in the
track's decision log that the constant-factor cost is accepted in exchange for a single count
owner. The second is defensible; what should not stand is the implicit assumption that the MATCH
route is free because both sides are O(1).

## Evidence base

The hot dimension across this track is result-row count, which is unbounded. The child count `N` is
bounded by union arity in the query text, and prefix and suffix length are bounded by query text.
Certificates separate per-row cost (hot) from per-arming, per-compilation, and per-plan-build cost
(cold), then scale-validate. Confirmed issues compress to one line here; the reasoning lives in the
finding body. Refuted claims are written out in full, because the refutation is the useful part.

#### C1 Post-union `dedup()` materialises every row through `transaction.loadEntity` to obtain a RID already present in the row (CONFIRMED — see PF1)

#### C2 Union forks and fully re-walks N children before the post-union suffix is checked, and the strategy re-runs on every traversal compilation (CONFIRMED — see PF2)

#### C3 Disabling the zero-estimate `EmptyStep` short-circuit for bare `count(*)` turns a disjoint-pattern count from O(1) into O(outer rows) with a per-row inner plan restart (CONFIRMED — see PF3)

#### C4 Non-polymorphic `g.V().count()` / `hasLabel(L).count()` now route through an uncacheable MATCH plan rebuilt on every execution (CONFIRMED — see PF4)

#### C5 Post-union `limit` / `range` early-stop holds; unreached children never open (NEGLIGIBLE)

The concern was that a post-union `limit` would drain every child before trimming, so
`union(a, b).limit(5)` over a million-row `a` would pay for the whole concatenation.

Refuted. `applyPostConcatOp` (`MultiPlanMatchStep.java:342-357`) wraps skip first and then calls
`ExecutionStream.limit`, producing `LimitedExecutionStream(SkipExecutionStream(MultipleExecution
Stream))`. `LimitedExecutionStream.hasNext` returns false on `count >= limit` without touching
upstream, and `MultipleExecutionStream` opens the next child only from inside its own `hasNext`
(`MultipleExecutionStream.java:16-27`). Once the limit is reached the concatenator is never asked
again, so children after the one that satisfied the limit are never started, and the satisfying
child is only partially drained. Live footprint stays one child stream. Cost is O(skip + limit)
rows, which is optimal for a concatenation whose children cannot be limited independently.
SCALE CHECK — at 100 / 100 K / 1 M rows in the first child the work is still bounded by
`skip + limit`. VERDICT: NEGLIGIBLE.

#### C6 Lone-`count` push-down is a genuine optimisation, not a hidden cost (NEGLIGIBLE)

The concern was that `sumChildCountStreams` (`MultiPlanMatchStep.java:269-330`) opens all N children
eagerly inside `ensure`, defeating the one-live-stream property the step was built around.

Refuted, and the shape is an improvement over the alternative. `ensure` opens child `i`, reads its
single scalar row, and closes it in a `finally` before moving to child `i + 1`, so one child stream
is live at a time exactly as in the streaming path. Because `buildChildPlans` rewrote each child to
`RETURN count(*)` (`PostConcatSupport.rewriteToCountStar`), each child plan reaches
`tryHardwiredMatchCount` on its own and can collapse to `CountFromClassStep` — a metadata read
instead of a scan. The alternative (drain the element concatenation and count rows) would be O(total
rows). Eagerness here is inherent to `count`, which is a barrier. `scalarCount` iterates
`getPropertyNames()` on a one-or-two-property row, once per child. SCALE CHECK — cost is O(N)
regardless of record count. VERDICT: NEGLIGIBLE, and the push-down is the right call.

#### C7 The dedup identity set is not a new memory hazard (NEGLIGIBLE)

The concern was unbounded growth of the `HashSet<Object> seen` in `dedupConcatStream` across a large
concatenation.

Refuted on parity grounds. The set holds one RID per distinct boundary entity, so memory is
O(distinct rows) — the irreducible cost of a global dedup. The native path this replaces,
TinkerPop's `DedupGlobalStep`, holds a set of the deduplicated objects themselves, so the MATCH
route's RID-keyed set is the smaller of the two. The set is allocated inside `dedupConcatStream`,
which is reached only from `startPlanStream`, so a re-armed step gets a fresh set rather than
leaking the previous arming's entries. SCALE CHECK — at 1 M distinct rows the set is ~1 M RIDs
either way, and the native comparison is worse. VERDICT: NEGLIGIBLE. The wasteful part of this code
path is the record load, reported separately as PF1.

#### C8 Per-child `GremlinPlanCache` publication does not degrade cache behaviour (NEGLIGIBLE)

The concern was that `buildChildPlans` (`GremlinToMatchStrategy.java:443-483`) publishes N entries
per union query, inflating pressure on a shared LRU and evicting single-plan entries.

Refuted. N is union arity from the query text, single digits in practice, so a union contributes N
entries where a comparable set of N separate queries would contribute N anyway. Each child is
fingerprinted on its own post-rewrite `MatchPlanInputs`, so two unions sharing a child shape share
one entry rather than adding two. The multi-plan carrier is never a cache entry: `buildChildPlans`
routes every child through the single-plan builder and `TranslationResult`'s compact constructor
forces `cacheEligible = false` on the carrier (`GremlinToMatchTranslator.java:129-132`), which makes
the DR-U5 bypass structural rather than flag-dependent. The build loop's per-iteration
`translation.childInputs().size()` and `isPushDownCountOnly(...)` calls are record-accessor field
reads and a list-size check, not copies. SCALE CHECK — entry count scales with distinct query
shapes, not with data. VERDICT: NEGLIGIBLE.

#### C9 The non-union decline path picks up no measurable cost (NEGLIGIBLE)

This is the highest-value defect class named in the brief, so it gets an explicit refutation rather
than silence. The concern: every existing query still compiles through `GremlinStepWalker.walk`, and
Track 8 adds work there.

What the track actually adds to a traversal with no `UnionStep`:

- One `UnionForkHostImpl` allocation per walk (`GremlinStepWalker.java:251`). Its constructor stores
  three references and reads `parent.getSteps()`; nothing is copied. Cost: one small object.
- One empty `ArrayList` field on every `WalkerContext` (`WalkerContext.java:191`). Java's no-arg
  `ArrayList` uses the shared empty backing array, so this is a header-sized object with no array
  allocation until first `add`, which never happens without a union.
- Four extra `List.copyOf` calls and one stream pipeline in `TranslationResult`'s compact
  constructor (`GremlinToMatchTranslator.java:122-133`), all over `List.of()` for a single-plan
  result. The stream pipeline is the largest of these at roughly five short-lived objects.
- One extra `Map.get` on the recogniser registry for `UnionStep.class`, which never fires.

None of these is per-row or per-record; they are per traversal compilation, and only the
`TranslationResult` items run at all on a successful walk (a declining walk returns before
`buildResult`). SCALE CHECK — at 100 / 100 K / 1 M result rows the added allocation count is
unchanged and is under ten objects against a compilation that already allocates a `WalkerContext`,
a pattern builder, a cursor, and a MATCH AST. VERDICT: NEGLIGIBLE. The decline path that does pick
up real cost is the union-bearing one, reported as PF2.

#### C10 Chained unions do not blow up recognition time (NEGLIGIBLE)

The concern was exponential compile cost from `walkFork` running a full walk that can itself contain
a `UnionStep`: `g.V().union(a,b).union(c,d).union(e,f)` would fork N children per level, and each
fork re-walks a prefix containing the earlier unions.

Refuted by the multi-plan guard. `containsNestedUnion` (`UnionStepRecogniser.java:112-119`) rejects a
union inside a child's own step list before any fork. For a *chained* union the prefix does contain
the earlier `UnionStep`, so the fork walk recognises it and returns a multi-plan
`TranslationResult` — and `recognize` declines on the first child that comes back multi-plan
(`UnionStepRecogniser.java:74-77`). Recursion therefore terminates one level down on the first
child, giving roughly 2N fork walks for any chain length rather than N^K. A union reached through a
`where(...)` child goes through `subWalk`, whose context has no fork host, so `unionForkHost()`
returns null and the union declines. SCALE CHECK — cost is linear in N and independent of chain
depth and of data size. VERDICT: NEGLIGIBLE.

#### C11 The metrics step's extra traversal scans are off any hot path (NEGLIGIBLE)

The concern was that `capturedExecutionPlan` (`YTDBQueryMetricsStep.java:91-110`) now runs up to
three `TraversalHelper.getFirstStepOfAssignableClass` scans instead of one.

Refuted on frequency. The method is called once per monitored query at reporting time
(`YTDBQueryMetricsStep.java:187`), and query monitoring is off unless a listener and a monitoring
mode are installed. Each scan is a linear walk of a step list whose length is the compiled step
count, which after translation is one. SCALE CHECK — the scan count is fixed at three regardless of
result-row count, and the list is single-element on exactly the path that added the two new scans.
VERDICT: NEGLIGIBLE. The ordering is also correct for cost: the two cheap boundary-step checks come
first and the fallback runs only when neither matched.

#### C12 The new AST `toString` probes in the count short-circuit are per-plan-build, not per-row (NEGLIGIBLE)

The concern was string construction over SQL AST nodes on a path every MATCH query touches:
`isBareCountStarWithoutGroupBy` calls `returnItems.getFirst().toString().trim()`
(`MatchExecutionPlanner.java:743-748`), and `isExactClassEqualsOnly` calls `toString` on both sides
of a binary condition (`HardwiredCountOptimizations.java:87-101`).

Refuted on frequency and on guard placement. Both run inside `createExecutionPlan`, which is behind
the plan cache, so they execute per cache miss and not per query and never per row.
`isBareCountStarWithoutGroupBy` short-circuits on `groupBy != null || returnItems.size() != 1`
before any string work, which excludes most MATCH shapes. `isExactClassEqualsOnly` is reached only
after `tryHardwiredMatchCount` has already established a single-node, edge-free, NOT-free `count(*)`
pattern. The strings are short and immediately garbage. SCALE CHECK — cost is independent of record
count and amortised over the cached plan's lifetime. VERDICT: NEGLIGIBLE. Comparing structurally
instead of by `toString` would be marginally cheaper and more robust, but the code documents why the
textual comparison was chosen (builder and parser ASTs diverge structurally), and that trade is
reasonable.

## Reviewer notes

**Reference-accuracy caveat.** mcp-steroid was reachable and `steroid_list_projects` confirmed the
open project matches the working tree, but the PSI query timed out — the known repository caveat
(cold Kotlin script compilation exceeds the 60 s MCP limit). Caller-frequency and call-site facts
therefore rest on grep plus declaration reads. Three conclusions depend on such a search:

1. PF1's claim that `getEntity` loads. This is a declaration read of
   `ResultInternal.getEntity` (`ResultInternal.java:479-495`) plus the `MatchFirstStep.java:113`
   row-assembly site, not a caller sweep, so a missed caller cannot flip it.
2. PF2's claim that the strategy compiles per traversal. Grounded in
   `GremlinToMatchStrategy`'s own class Javadoc ("`apply` runs inside `traversal.applyStrategies()`,
   which fires on every Gremlin traversal compilation") plus the absence of any walk-level cache;
   only the built plan is cached.
3. PF4's claim that a `CountFromClassStep` plan is never cached. Grounded in
   `CountFromClassStep.canBeCached()` returning false and `GremlinPlanCache.putInternal`
   short-circuiting on `!internal.canBeCached()`, both direct declaration reads.

A grep sweep of `core/src/main/java` for `MultiPlanMatchStep` and `PostConcatOp` found production
references only in the six files this track touches, so the new surface has no caller outside it.

**Test cost.** The new and modified tests add no meaningful CI time. `MultiPlanMatchStepTest`'s
`clone_concurrentDrives_noCrossCloneVariableBleed` runs 200 iterations of a two-thread barrier drive
with 64 probe cycles each, all over mock plans and in-memory list streams with a timed
`Future.get`; the union equivalence and recogniser tests use fixtures of three or four vertices. No
sleeps, no large data loops.

**Step-1 overlap.** The Step 1 performance review (`performance-step1-iter1.md`) cleared laziness,
per-arming allocation, the `ChildContextStream` indirection, the O(N) close and rewind sweeps, and
the O(N) clone. Re-checked against the cumulative diff: those conclusions still hold, and the
`postConcatOps` loop added to `startPlanStream` after that review is per-arming, not per-row. None
of them is re-reported here.
