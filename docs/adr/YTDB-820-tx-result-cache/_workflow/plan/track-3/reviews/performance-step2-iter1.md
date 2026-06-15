<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: PF1, sev: suggestion, loc: CachedResultSetView.java:296, anchor: "### PF1 ", cert: C1, basis: "merge re-projects the cache head once per losing compare; O(n*k) projector calls worst case, n capped at 10000"}
  - {id: PF2, sev: suggestion, loc: CachedResultSetView.java:323, anchor: "### PF2 ", cert: C2, basis: "same row projected across DeltaBuilder sort, merge compare, and emit with no memoization; projectForCompare seam already anticipates a cache"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] Merge re-projects the cache head on every losing comparison

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/CachedResultSetView.java` (line 290-302)

**Issue**: In the RECORD sorted-merge, while both the cache head and the inject
head are present, every loop iteration projects both heads for the ORDER BY
comparison (`projectForCompare(deltaHead)`, `projectForCompare(cacheHead)`).
When the inject side wins (`cmp <= 0`), `position` does not advance, so the
same `cacheHead` is re-projected on the next iteration. A cache row that `k'`
inject rows sort before is projected `k'+1` times before it is emitted. Each
projection is not free: `buildMatchReturnProjector`'s closure allocates an
alias-keyed `ResultInternal`, then `SQLProjection.calculateSingle`
(`SQLProjection.java:138`) allocates at least one more `ResultInternal` and runs
`execute` per RETURN item. Nothing memoizes the projected value.

**Evidence**: See `#### C1`. The merge runs per emitted row of a cached
single-alias MATCH (the cache-hit hot path). Cache size `n` is the matching
committed record count, capped at 10000 by
`QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY`
(`GlobalConfiguration.java:971`, default 10000). Inject size `k` is the matching
in-tx mutation count. The comparison branch is reached only while inject rows
remain; once the delta drains (`deltaHead == null`) the `project(cacheHead)`
emit branch runs with no comparison projection. Worst case (all `k` injects sort
before all `n` cache rows) the cache head is re-projected up to `k` times each,
giving `O(n*k)` projector calls where `O(n+k)` distinct projections suffice.

**Impact**: Extra CPU and short-lived `ResultInternal` allocations (GC pressure)
on the merge path. Negligible at typical transaction scale (few in-tx mutations,
small result, 1-2 RETURN items). At scale — a near-cap cached result (up to
10000 rows) combined with hundreds of in-tx mutations of the same class — this
reaches millions of `calculateSingle` calls and matching allocations where tens
of thousands would do. This works against the latency win the cache exists to
deliver on exactly the largest cached results.

**Suggestion**: Project each merge head at most once per emission decision.
Cheapest fix that preserves the current structure: compute
`projectForCompare(cacheHead)` once when `cacheHead` is established for a
position and reuse it across iterations until `position` advances (the inject
head already advances when it is consumed, so its projection is naturally
single-use). The `projectForCompare` seam (line 323) was added precisely for
this; see PF2 for the memoization angle. Defer if the team judges the scale
gate (both `n` and `k` large simultaneously) too rare to fund now — the path is
correct as written.

### PF2 [suggestion] Same row projected across sort, compare, and emit with no memoization

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/CachedResultSetView.java` (line 312-325); `DeltaBuilder.java` (line 188-194)

**Issue**: An inject row is projected through `returnProjector` in three
separate phases, and the result is recomputed each time:
1. `DeltaBuilder` inject-list sort (`DeltaBuilder.java:193`) projects both
   comparator operands on every compare — `O(k log k)` projector calls for an
   inject list of size `k`.
2. The view merge re-projects it on each ORDER BY comparison it participates in
   (PF1).
3. `project(delta.advanceInject())` projects it once more at emit
   (`CachedResultSetView.java:299`).

The projector output is a pure function of the raw row (the closure captures a
fixed `projectorCtx`), so projecting the same row repeatedly recomputes an
identical tuple. `projectForCompare` (line 323) is documented as a seam kept
separate from `project` specifically so a future comparison-time projection
cache "does not disturb the emit-time projection" — that cache has not been
added, so today the seam only forwards.

**Evidence**: See `#### C2`. `DeltaBuilder.getReturnProjector()` is read at
`DeltaBuilder.java:189`; the merge and emit reads are at
`CachedResultSetView.java:296-302`. Grep across the cache package
(reference-accuracy caveat: PSI find-usages timed out twice on this project, so
this is a grep-only confirmation) shows these are the only `getReturnProjector`
read sites, so a single memoization layer keyed on the raw row's RID would cover
all three phases.

**Impact**: Same nature as PF1 — redundant CPU and allocation on the cache hot
path, scale-gated on `k` (sort) and `n*k` (merge). The sort cost alone is
`O(k log k)` projector calls where the rows could be Schwartzian-transformed
(project once into a sort key, sort the keys) for `O(k)` projections.

**Suggestion**: When (and if) PF1 is funded, address both with one change:
memoize the projected tuple per raw row (e.g. an `IdentityHashMap<Result,
Result>` or a decorate-sort-undo on the inject list) so each distinct row is
projected once and reused across sort, compare, and emit. The existing
`projectForCompare` seam is the intended insertion point. Until then, the sort's
`O(k log k)` projections (DeltaBuilder) are the more isolated quick win — a
sort-key precompute there needs no change to the view.

## Evidence base

#### C1 PF1 cache-head re-projection — CONFIRMED
`computeNextRecord` (CachedResultSetView.java:250-304) is the per-row cache-hit
merge path. When `cmp <= 0` the inject head advances and `position` is unchanged,
so the next iteration reads the same `cacheHead` and re-runs
`projectForCompare(cacheHead)`. Projector cost confirmed in
`buildMatchReturnProjector` (DatabaseSessionEmbedded.java:1240-1271: per-row
`ResultInternal aliasRow` alloc + `projection.calculateSingle`) and
`SQLProjection.calculateSingle` (SQLProjection.java:138-229: ≥1 `ResultInternal`
alloc + per-item `execute`). `n` bound confirmed at
GlobalConfiguration.java:971-979 (`QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY`,
default 10000). Worst-case `O(n*k)` vs `O(n+k)` minimum survives the scale check:
NEGLIGIBLE at small `k`, MATTERS AT SCALE when both `n` and `k` are large.

#### C2 PF2 triple-phase projection without memoization — CONFIRMED
Three read sites for `getReturnProjector` confirmed by grep over the cache
package (PSI find-usages unavailable — timed out twice; grep-only): DeltaBuilder
sort comparator (DeltaBuilder.java:188-194), view merge compare
(CachedResultSetView.java:296-297), view emit (CachedResultSetView.java:282-302).
Projector is a pure function of the raw row (fixed captured `projectorCtx` from
`freshContext(args)` built once in `buildMatchReturnProjector`,
DatabaseSessionEmbedded.java:1253), so repeated projection recomputes an
identical tuple. `projectForCompare` Javadoc (CachedResultSetView.java:317-322)
names the comparison-time projection cache as the intended-but-unbuilt
optimization.

#### C3 Single-alias detection adds redundant per-row or per-query traversal — REFUTED
The review brief flagged "redundant traversal added to serveThroughCache /
buildView for the single-alias detection." Traced and refuted as a finding. The
detection helpers — `singleAliasOrigin`, `effectiveFromClasses`, `whereClauseOf`,
`orderByOf`, `buildMatchReturnProjector`, `rawAliasRecordMapper` — are all called
inside the populate block (DatabaseSessionEmbedded.java:894-929), which runs once
per cache miss, never per emitted row (confirmed by the call-site grep: the only
per-row hook is `lifted.map(rawAliasRecordMapper(alias))` at line 910, a single
`ResultInternal` allocation per populated row, which is intrinsic to the
store-raw design choice and not redundant). `singleAliasOrigin` is invoked ~4-5
times per populate (once each from `effectiveFromClasses`, `whereClauseOf`,
`orderByOf`, plus the inline checks at lines 902-903 and 928), but each call is
O(1) on a size-1 `getMatchExpressions()` list — negligible per cache miss.
SCALE CHECK: negligible at every scale (per-miss, O(1)). VERDICT: NEGLIGIBLE,
not reported.
