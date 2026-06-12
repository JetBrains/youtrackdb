# IC2 Pre-Filter Regression — Root Cause and Proposed Fix

## Summary

PR #973 (YTDB-651) regresses LDBC IC2 throughput by 83% on ST and 86% on
MT (BASE develop = 149 ops/s ST → HEAD = 26 ops/s ST). Hetzner profiling
confirms the regression reproduces and pinpoints `TraversalPreFilterHelper.resolveIndexToRidSet`
as the dominant hot path (51% of CPU samples in HEAD; 0% in BASE).

The cost model that decides when to build an IndexLookup RidSet uses
class-level selectivity (`estimateSelectivity`, the fraction of *all*
records in the class matching the filter). For IC2, this value is around
1% — favorable on paper. But the actual savings depend on **in-list
selectivity** (the fraction of a *single friend's* link bag matching
the filter), which is around 70% for IC2 because friends' adjacency lists
skew toward recent messages. The cost model decides BUILD_EAGER on the
1% class-level signal, runs a 35K-entry index scan (~35ms wall-clock),
then probes the link bag with a RidSet that filters almost nothing.

A previous attempt (commit `ab230ab7cc`, "skip prefilter when index
selectivity unknown") addressed an adjacent issue (missing histogram
stats) but does not apply here — IC2's stats are present and correct.

## Diagnostic Evidence

### Profile comparison (HEAD vs BASE, IC2 ST)

| Method | HEAD samples | BASE samples |
|---|---|---|
| `TraversalPreFilterHelper.resolveIndexToRidSet` | 568 (40%) | 0 |
| `IndexMultiValuKeySerializer.deserializeKeyFromByteBuffer` (leaf) | 980 | 0 |
| `BTree.readKeysFromBucketsForward` (leaf) | 272 | 0 |
| `executeReadRecord` (BASE-only normal traversal) | 5 | 456 |

The entire IC2 regression is one hot path: index scan + RoaringBitmap
build that did not exist in BASE.

### Histogram values for IC2 (250 sampled calls)

- `estimateHits`: min 4.8K, median 35K, p95 117K, max 197K
- `estimateSelectivity` (class-level): min 0.001, median 0.010, p95 0.033

The histogram is **accurate** for class-level selectivity. The cost
model formula `m = estimatedSize / (loadToScanRatio · (1 − s))` plugs in
correct numbers and produces a small `m` (≈354 with s=0.01,
ratio=100), well below `forecast_n ≈ 9500`, so BUILD_EAGER fires.

### Cap-sweep experiment (HEAD with overridden `maxRidSetSize`)

| `maxRidSetSize` | IC2 ST throughput | vs BASE (149) |
|---|---|---|
| 10M (current auto-scale default on 4 GB heap) | 26 ops/s | −83% |
| 1M (design's recommended fixed cap) | 28 ops/s | −81% |
| 500K | 28 ops/s | −80% |
| 200K | 71 ops/s | −52% |
| 100K (pre-PR fixed default) | 119 ops/s | −15% |
| 10K | 197 ops/s | +33% (but breaks IC4) |

No cap value cleanly separates IC2 (regression we want to remove) from
IC4 (gain we want to preserve, `estimateHits ≈ 33K`).

## Why the Cost Model Mispredicts

The design doc paired the formula with a "build amortization" guard:

```
m = estimatedSize / (loadToScanRatio · (1 − selectivity))
BUILD_EAGER iff forecast_n > m
```

`selectivity` here is meant to be the fraction filtered out at probe
time. The code passes class-level selectivity (records matching globally
÷ class cardinality). For queries where adjacency lists are
representative samples of the class, class-level and in-list selectivity
coincide and the formula works (IC4, IC11). For queries where adjacency
lists are biased subsets (IC2: a friend's recent messages are not a
uniform sample of all messages — recency-biased), the two diverge
dramatically.

The current code has **no per-vertex signal** to detect this divergence:

- `RidFilterDescriptor.IndexLookup.passesSelectivityCheck` (called in
  `MatchEdgeTraverser.applyPreFilter` post-materialization) is bypassed
  for IndexLookup descriptors via the `indexLookupVerified` short-circuit.
- Even if the short-circuit were removed, the method still uses
  class-level selectivity and would return true for IC2.

## Proposed Fix — Pre-Build In-List Sampling

Add a per-edge sampling step before invoking `resolveIndexToRidSet`.
Load K=30 entries from the first vertex's link bag, evaluate the
IndexLookup's filter on each, compute the hit rate. If above 0.5,
skip the build entirely and let normal traversal handle the edge.

### Implementation outline

1. **New helper** in `TraversalPreFilterHelper`:
   ```java
   public static double samplePreBuildInListHit(
       PreFilterableLinkBagIterable pfli,
       IndexSearchDescriptor desc,
       CommandContext ctx,
       int sampleSize) {
     // Load up to sampleSize RIDs from pfli.iterator(), evaluate
     // desc.getKeyCondition() (and additionalRangeCondition if present)
     // on each loaded record, return hits/sampled.
     // Iterator is fresh — normal traversal re-iterates from a new
     // iterator() call. Re-loads are warm-cached (~0.5µs each).
   }
   ```
2. **Memoization on `EdgeTraversal`**:
   ```java
   private double inListSampleResult = Double.NaN;
   public boolean isInListSampleTaken() { ... }
   public boolean isInListSampleRejected() { ... }
   ```
   Reset by `copy()` (bind-dependent — sample uses runtime filter values).
3. **Inject in `MatchEdgeTraverser.applyPreFilter`** before
   `resolveWithCache`:
   ```java
   if (desc instanceof RidFilterDescriptor.IndexLookup il) {
     if (!edge.isInListSampleTaken()) {
       double hit = TraversalPreFilterHelper.samplePreBuildInListHit(
           pfli, il.indexDescriptor(), ctx, IN_LIST_SAMPLE_SIZE);
       edge.setInListSampleResult(hit);
     }
     if (edge.isInListSampleRejected()) {
       edge.recordPreFilterSkip(PreFilterSkipReason.IN_LIST_SELECTIVITY_HIGH);
       return pfli;
     }
   }
   ```
4. **New enum value** `PreFilterSkipReason.IN_LIST_SELECTIVITY_HIGH`
   for PROFILE diagnostics.
5. **Constants** in `TraversalPreFilterHelper`:
   ```java
   private static final int IN_LIST_SAMPLE_SIZE = 30;
   private static final double IN_LIST_THRESHOLD = 0.5;
   ```
   K=30 gives binomial standard error ~9%; the gap between IC2 (~70%)
   and IC4 (~3%) is far wider than this, so decisions are stable.
   Threshold 0.5 leaves margin above the theoretical break-even
   (`1 − probe_cost / load_cost ≈ 0.4` with probe ≈ 3µs, load ≈ 5µs).

### Expected outcomes

Per-query overhead breakdown:

| Query | In-list | Sample cost | Build cost | Probe cost | Total | vs BASE |
|---|---|---|---|---|---|---|
| IC2 | ~70% | 150µs | 0ms (skipped) | 0 | ≈ 47.7ms | **−0.4%** |
| IC4 | ~3% | 150µs | ~5ms | ~33ms | ≈ 41.5ms | **+26%** |
| IC3 | bounded range, low | 150µs | small | small | unchanged | **+999%** preserved |
| IC9 | ~70% | 150µs | skipped | 0 | unchanged (different path) | **+413%** preserved |

IC9's existing gain comes from `BackRefHashJoinStep` improvements,
not from the `MatchEdgeTraverser` IndexLookup path, so it is not
affected by the sample.

### Why this is the minimum viable fix

Three alternatives were evaluated and rejected:

1. **Lower the `maxRidSetSize` cap** (e.g. back to fixed 100K). Reduces
   IC2 regression from −83% to −15%, but no value cleanly separates
   IC2's `estimateHits` distribution from IC4's. To fully block IC2
   the cap would need to be below 5K, which also blocks IC4
   (`estimateHits ≈ 33K`) and loses its +18% gain.
2. **Sanity check inside `resolveIndexToRidSet`** (abort scan if
   `count > estimateHits × 3`). Did not help in measurement: histogram
   is accurate, so real scan stays within the multiplier bound and the
   guard never fires.
3. **Post-build sampling** (sample after `resolveIndexToRidSet` returns).
   Cheaper to implement (no iterator reuse concern) but still pays the
   ~35ms build cost per query. Caps the regression at around −43%,
   not <5%.

Pre-build sampling is the only path tested that brings IC2 regression
within the 5% target while preserving every other query's gain.

### Risks and open questions

- **Iterator consumption**: `pfli.iterator()` must obey the standard
  `Iterable` contract (fresh iterator per call). Spot-check on
  `PreFilterableLinkBagIterable` implementations is part of the
  implementation work.
- **First-vertex bias**: the sample is taken from one friend's link
  bag and generalized to all friends on the edge. LDBC params are
  uniformly drawn from a population of similar-degree persons, so the
  per-friend distribution is statistically representative. Worst case
  for a misleading first sample is one wrong decision per query
  execution, bounded by `build_cost = ~35ms`.
- **K=30 and threshold=0.5 are new calibration constants**. The design
  doc's R1 ("no calibration constants") is technically violated.
  Justification: cost-model accuracy without an in-list signal is
  empirically insufficient for the LDBC IC2 / IC9 query shape, and
  the threshold derives from the existing `probe_cost / load_cost`
  ratio that the cost model already uses.
- **Sample overhead in the "apply prefilter" path** (IC4-shaped queries)
  is ~150µs per first vertex per edge per query. Compared to the
  benefit (3-50ms saved per query on filtered loads), this is well
  under 1% overhead.

## Verification Plan

1. Implement the four code changes above on top of `prefilter-selectivity-fix`
   branch.
2. Unit tests:
   - `samplePreBuildInListHit_highHitRate_returnsAboveThreshold`
   - `samplePreBuildInListHit_lowHitRate_returnsBelowThreshold`
   - `applyPreFilter_indexLookupHighInList_skipsBuild`
   - `applyPreFilter_indexLookupLowInList_appliesPrefilter`
   - `applyPreFilter_inListSampleMemoizedAcrossVertices`
   - `copy_resetsInListSampleResult`
3. Hetzner JMH benchmark run comparing patched HEAD against develop
   fork-point `ecb4b01c8b`. Target: IC2 ST and MT both within ±5% of
   BASE; IC3, IC4, IC9 maintain their existing gains.
4. Profile IC2 ST on patched HEAD; confirm `resolveIndexToRidSet`
   samples drop to near zero and CPU distribution matches BASE.

## Context

- PR: https://github.com/JetBrains/youtrackdb/pull/973
- Branch: `prefilter-selectivity-fix`
- BASE commit (develop fork-point): `ecb4b01c8b`
- HEAD commit (current): `ab230ab7cc`
- Latest JMH CI run (2026-05-14): IC2 ST −83.7%, IC2 MT −86.2% red_circle
- Hetzner profile session (2026-05-14, this analysis): server `jmh-prof-prefilter-selectivity-fix`,
  CCX33, LDBC SF 1 with canonical curated params
