<!-- MANIFEST
dimension: performance
prefix: PF
iteration: 1
high_water_mark: 0
findings: 3
evidence_base: 5
cert_index: C1,C2,C3,C4,C5
flags: []
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-minmax-replay-on-extremum-holder-mutation-is-onm-per-build"
    loc: "AggregateState.java:677-735 (recomputeExtremum), DeltaBuilder.java:1177-1194 (replay loop)"
    cert: C1
    basis: "Each replayed mutation touching the extremum holder triggers an O(n) recomputeExtremum; m such ops in one build give O(n*m). MIN/MAX only; SUM/AVG/COUNT/COUNT_DISTINCT are O(1) amortized per op."
  - id: PF2
    sev: suggestion
    anchor: "#pf2-buildforaggregate-rebuilds-full-state-copy--replay-on-every-cache-hit-no-cross-view-share"
    loc: "DeltaBuilder.java:1163-1197 (buildForAggregate), AggregateState.java:872-886 (copy)"
    cert: C2
    basis: "Aggregate hit path does state.copy() (O(n) alloc) + full tx-op-log walk + per-op WHERE re-eval on every view, unlike buildForRecord which caches the (skipSet,injectList) pair per mutationVersion. Documented as intentional; flagged for scale awareness only."
  - id: PF3
    sev: suggestion
    anchor: "#pf3-contributor-collections-allocated-at-default-capacity-carried-from-step-1-pf2pf3"
    loc: "AggregateState.java:454-499 (field initializers), 873-885 (copy)"
    cert: C3
    basis: "contributingValues/contributingRids/distinctBuckets + per-view copy allocate at default capacity, forcing log2(n) rehash/resize cycles when n approaches the 10k cap. Already logged as deferred PF2/PF3 in Step 1/Step 3 episodes."
-->

# Performance review — Track 2 (Aggregate shapes)

BLUF: No blocker or should-fix performance defects. The hot paths (`observe` at populate, `applyMutation`/`copy` per cache hit) are correctly designed: SUM/AVG fold once lazily via `sumDirty`, COUNT/COUNT_DISTINCT are O(1) per op, and the contributor collections are bounded at 10,000 by the overflow cap. Three suggestion-severity items, all bounded by that cap and two of them already logged as deferred in the track episodes.

## Findings

### PF1 [suggestion] MIN/MAX replay on extremum-holder mutation is O(n·m) per build {#pf1-minmax-replay-on-extremum-holder-mutation-is-onm-per-build}

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java` (lines 677-735, `recomputeExtremum` at 777-787)
**Also**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilder.java` (lines 1177-1194, the per-view replay loop)

**Issue**: For `AGGREGATE_MIN` / `AGGREGATE_MAX`, every replayed mutation that drops or moves-away-from-extremum the current holder calls `recomputeExtremum`, an O(n) scan over `contributingValues` (n = contributor count, capped at 10,000). The replay loop in `buildForAggregate` walks all post-populate ops; if `m` of them touch the extremum holder, the build is O(n·m). SUM/AVG sidestep this with the lazy `sumDirty` + single `ensureSumFolded` fold (the Step 1 review fix); COUNT_DISTINCT re-bucketing and COUNT membership are O(1) per op. MIN/MAX is the only shape that retains a per-op O(n) branch inside the replay.

**Evidence**: COST TRACE C1, SCALE CHECK in C1. The pathological case requires repeated mutation of whichever record currently holds the extremum within one transaction — e.g. a tx that repeatedly lowers the running MIN holder below the field, each time forcing a rescan.

**Impact**: Latency on the aggregate cache-hit path for MIN/MAX only. At the n≤10,000 cap and realistic m (tens of post-populate mutations per tx in the target DNQ pattern), worst case is ~10,000 × tens = sub-millisecond, dominated by the WHERE re-eval already in the same loop. MATTERS AT SCALE only if a tx both holds a near-cap MIN/MAX contributor set and mutates the extremum holder repeatedly — not the documented target workload.

**Suggestion**: Leave as-is for v1; the cost is bounded and the worst case is workload-adversarial. If a future D13 measurement flags MIN/MAX hit latency, the deferred D14 `TreeMap` sorted-value index (already named out-of-scope in the track Interfaces section) makes `recomputeExtremum` O(log n). No code change recommended now — record the O(n·m) MIN/MAX shape as the trigger condition for that v2 lever.

### PF2 [suggestion] buildForAggregate rebuilds full state copy + replay on every cache hit (no cross-view share) {#pf2-buildforaggregate-rebuilds-full-state-copy--replay-on-every-cache-hit-no-cross-view-share}

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilder.java` (lines 1163-1197)
**Also**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java` (lines 872-886, `copy`)

**Issue**: `buildForAggregate` does a fresh `seeded.copy()` (a new `LinkedHashMap`/`HashSet`/per-bucket `HashSet`, O(n) entries copied) plus a full walk of the transaction's record-operation log plus per-surviving-op WHERE re-eval — on every cache hit. `buildForRecord` (lines 110-118 of the same file) caches its computed `(skipSet, injectList)` pair on the entry keyed by `mutationVersion` and returns a shared immutable cursor when two views hit the entry at the same tx state, skipping the re-walk. The aggregate path deliberately does not (Javadoc: "the aggregate state is not cross-view cached on the entry: a single call produces a fresh copy per view... there is no pause/resume to share").

**Evidence**: COST TRACE C2. The asymmetry is real and intentional, but it means two aggregate `query()` calls at the same `mutationVersion` each pay the full copy + op-log walk, where the RECORD path pays it once.

**Impact**: Per-hit allocation (one `AggregateState.copy()`: 1 `LinkedHashMap` + 1 `HashSet` + k bucket `HashSet`s for COUNT_DISTINCT) and CPU (op-log walk + WHERE re-eval). Bounded by the 10,000 cap. The dominant target pattern is *repeated identical shape within one tx*, so same-version re-hits are exactly the case the RECORD path optimizes and the aggregate path does not. MATTERS AT SCALE for hot aggregate shapes hit many times between two mutations; NEGLIGIBLE for the read-then-mutate-then-read pattern the tests exercise.

**Suggestion**: Keep for v1 — correctness is unaffected and the same-version cross-view cache is a measured optimization, not a default. If D13 shows a hot aggregate shape re-queried many times at a stable `mutationVersion`, mirror `buildForRecord`'s version-tagged pair cache: store the finalized scalar (not the full state) on the entry tagged by `mutationVersion`, and short-circuit when the version matches. Cheaper than the RECORD pair because the aggregate result is a single scalar. Record as a v2 candidate alongside D14.

### PF3 [suggestion] Contributor collections allocated at default capacity (carried from Step 1 PF2/PF3) {#pf3-contributor-collections-allocated-at-default-capacity-carried-from-step-1-pf2pf3}

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java` (lines 454-499 field initializers; 873-885 `copy`)

**Issue**: `contributingRids` (`HashSet`), `contributingValues` (`LinkedHashMap`), and `distinctBuckets` (`HashMap`) are all created at default capacity (16). At populate, `observe` grows them one contributor at a time; near the 10,000 cap that is ~10 rehash/resize cycles per collection. `copy()` likewise builds the destination maps at default capacity and `putAll`s into them, repeating the resize cost per view. The contributor count is unknown at populate (the side-tap discovers it during the drive), so populate-time presizing is not free — but `copy()` knows the source size and could presize.

**Evidence**: COST TRACE C3. This is the same observation already recorded as deferred PF2 (Step 1 episode: "presize the contributor collections... folds into the Step 3 cap wiring") and PF3 (Step 3 episode: "contributor collections allocate at default capacity, carried from Step 1").

**Impact**: GC pressure and CPU from incremental rehashing during populate and per-view copy. O(n) amortized either way; presizing removes the log n resize constant. At n=10,000 the saved work is small relative to the WHERE re-eval and `increment` boxing in the same paths. MATTERS AT SCALE marginally for high-cardinality aggregates copied on every hit.

**Suggestion**: In `copy()`, presize the destination collections from the source sizes: `new LinkedHashMap<>(Math.max(16, contributingValues.size() * 4 / 3 + 1))` and the analogous `HashSet`/`HashMap`. Leave populate-time collections at default (size unknown until the drive completes). One-line-per-collection change, no semantic risk. Consistent with the already-deferred PF2/PF3 — promote to this track only if the copy is confirmed hot by D13; otherwise leave deferred as the episodes recorded.

## Evidence base

#### C1 — MIN/MAX recomputeExtremum O(n) per extremum-holder mutation {#c1}

CONFIRMED-as-issue (suggestion). `recomputeExtremum` (AggregateState.java:777-787) is an O(n) scan over `contributingValues`. It fires from `removeContributor` when the dropped RID `equals(extremumRid)` (line 684-688) and from `updateContributor` when the holder moves away from the extremum direction (line 714-722). Non-holder mutations are O(1) (single `beatsExtremum` compare). Within one `buildForAggregate` replay (DeltaBuilder.java:1177-1194) the loop applies every post-populate op; m holder-touching ops give O(n·m). n is bounded at `maxRecordsPerEntry`=10,000 (GlobalConfiguration.java:978). SUM/AVG are exempt: `addContributor`/`removeContributor`/`updateContributor` only set `sumDirty` (lines 658-660, 681-682, 702-703) and the O(n) fold runs once via `ensureSumFolded` (743-748). SCALE: at 100 records negligible; at 10k cap with tens of holder mutations sub-ms and dominated by the co-located WHERE re-eval; pathological only under adversarial repeated-extremum-holder mutation.

#### C2 — Aggregate hit path: full copy + op-log walk per view, no version-tagged share {#c2}

CONFIRMED-as-issue (suggestion). `buildForAggregate` (DeltaBuilder.java:1163-1197) unconditionally calls `seeded.copy()` then iterates `tx.getRecordOperationsInternal()` (confirmed `Collection<RecordOperation>`, the full tx op list — FrontendTransactionImpl.java:1468) with a per-survivor `whereClause.matchesFilters(record, ctx)`. `copy()` (AggregateState.java:872-886) allocates a fresh `LinkedHashMap`+`HashSet`+per-bucket `HashSet` and copies all n entries. Contrast `buildForRecord` (DeltaBuilder.java:96-101): returns a shared cursor over the entry's cached `(skipSet,injectList)` when `getCachedDeltaVersion() == version`, skipping the walk for same-version re-hits. The aggregate Javadoc states the omission is deliberate (no pause/resume to share). The full op-log walk itself is the accepted v1 cost shared with the RECORD path (DeltaBuilder.java comment: "The remaining full-log walk is the accepted v1 cost"). SCALE: target pattern is hundreds-to-thousands of duplicate-shape queries per request; same-version re-hits of a hot aggregate pay the copy+walk each time where RECORD pays once.

#### C3 — Default-capacity contributor collections {#c3}

CONFIRMED-as-issue (suggestion), already deferred. Fields at AggregateState.java:454 (`new HashSet<>()`), 470 (`new LinkedHashMap<>()`), 499 (`new HashMap<>()`) and the copy destinations (873-885) use default capacity. `observe` grows them incrementally to the 10k cap (~10 resizes). Recorded deferred in Step 1 episode (PF2 "presize the contributor collections") and Step 3 episode (PF3 "contributor collections allocate at default capacity, carried from Step 1"). Populate-time size is unknown until the drive completes (side-tap discovers it), so only `copy()` can presize cheaply.

#### C4 — SUM/AVG fold is correctly lazy (refuted as a finding) {#c4}

Investigated whether SUM/AVG re-fold on every mutation. REFUTED: `addContributor`/`removeContributor`/`updateContributor` set `sumDirty=true` only (AggregateState.java:658-660, 681-682, 702-703); the O(n) `refoldSum` runs once via `ensureSumFolded` on the next scalar read (743-748, 909, 913). A build replaying m mutations does one fold (O(n+m)), not m folds (O(n·m)). The Javadoc at lines 481-488 documents this collapse explicitly. This is the Step 1 review fix and it holds. `refoldSum` does autobox a new `Number` per `PropertyTypeInternal.increment` (line 768) — O(n) boxed allocations per fold — but once per build, not per op, and required for D19 storage-fold parity (no symmetric subtract available). Not a finding.

#### C5 — Eager-drive cost parity and observe-time cost (refuted as a finding) {#c5}

Investigated the aggregate cache-miss eager drive (`populateAndBuildAggregateView`, DatabaseSessionEmbedded.java:97-176) as a possible regression vs uncached. REFUTED: the aggregation step (`AggregateProjectionCalculationStep`) is already blocking — a fresh uncached aggregate query drains every contributor to produce its single row regardless. The eager drive (lines 143-153) adds only the per-row `state.observe(r)` side-call in `ObservingStream.next` (AggregateCacheTapStep.java:334-338): one map/set put + an O(1) overflow check per contributing record. The track Compatibility note ("Eager drive matches the uncached aggregate latency profile") is accurate. The miss path does build the plan once (`createExecutionPlan`) — the deferred Step 3 PF1 notes the untappable-shape fallback builds it twice, but that is the fallback-only path (hardwired COUNT(*)), not the cacheable aggregate path, and is bounded by one extra plan build on a shape that was never going to cache. Not a track-2 finding beyond the already-recorded Step 3 PF1.
