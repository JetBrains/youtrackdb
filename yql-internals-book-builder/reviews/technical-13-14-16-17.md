# Technical Review R5 — Chapters 13, 14, 16, 17
Reviewer: R5 (source-tree verification)
Date: 2026-04-23
Source baseline: commit `cca739f215` (develop)

---

## Summary

All three hash-join step classes exist and their core cited methods verify correctly. The `RidFilterDescriptor` sealed interface has exactly four `record` implementations as described. `GlobalConfiguration` knob names and defaults are accurate. Most cited `file:line` anchors are correct. Four factual issues were found: one wrong line-number pair in Chapter 13, one phase-numbering discrepancy carried through Chapters 17, and two omissions in Chapter 17's reference tables.

---

## Chapter 13 — When Nested Loops Aren't Enough: Hash Joins

### Issues

**[MEDIUM] Guard 2 line reference is wrong.**
The text (Guard 2 section, body paragraph) cites
`MatchExecutionPlanner.java:337–338` as the location where the planner rejects
hash join when the build-side estimate exceeds the threshold. Lines 337–338 are
actually the opening lines of the helper method `getHashJoinThreshold()`:

```java
// Line 337
static long getHashJoinThreshold() {
    return Math.max(0, GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValueAsLong());
```

The actual cardinality cap check (`if (cardinality > threshold) { return null; }`) is at
lines 1192–1194. The "Further reading" section at the bottom of the chapter correctly
cites `lines 1189–1227` for the cardinality and cost guards; only the in-body citation
at line 337–338 is wrong.

**[LOW] `JoinKey` shape names are private.**
The text refers to `JoinKey.SINGLE_RID`, `JoinKey.RID_ARRAY`, and `JoinKey.OBJECT_ARRAY`
as if they are public constants. They are values of a `private enum Kind` inside
`JoinKey.java` (line 32). The public API consists of static factory methods
`ofRid()`, `ofRids()`, `ofObjects()` (and the owned-array variants). The description
is semantically accurate but uses private internal identifiers as if they were the
public surface.

### Verified correct

- All three step classes exist: `HashJoinMatchStep.java`, `CorrelatedOptionalHashJoinStep.java`,
  `InvertedWhileHashJoinStep.java`. All cited methods and line numbers check out:
  - `HashJoinMatchStep.internalStart` at line 90; `buildHashSet` at 142–167;
    `buildHashMap` at 173–204; `probeFilter` at 270; `nestedLoopProbe` at 337;
    `nestedLoopInnerJoin` at 373; `prettyPrint` at 416.
  - `CorrelatedOptionalHashJoinStep.neighborCache` field at line 60; `buildNeighborEntry`
    at 148; SQL inverse-direction query at 164; truncation check at 176;
    fallback call at 123–130; `prettyPrint` at 216.
  - `InvertedWhileHashJoinStep.findAnchorVertices` at 185; `collectDescendantRids`
    at 219; `forwardBfsToAnchors` at 256; `prettyPrint` at 343.
- `JoinMode` enum at line 15 of `JoinMode.java`: `ANTI_JOIN`, `SEMI_JOIN`, `INNER_JOIN`. ✓
- `FilterNotMatchPatternStep.prettyPrint` at 123; `ChainStep` injection at 100–108. ✓
- `GlobalConfiguration` knob names, defaults, and line numbers:
  `QUERY_MATCH_HASH_JOIN_THRESHOLD` at line 854 (default `10000L`, type `Long`);
  `QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN` at 864 (default `5L`);
  `QUERY_MATCH_CORRELATED_CACHE_SIZE` at 875 (default `16`, type `Integer`). ✓
- `INNER_JOIN_MEMORY_WEIGHT = 7` at `MatchExecutionPlanner.java:358`. ✓
- Cardinality and cost guards at 1189–1227; INNER_JOIN tighter cap check at 1199–1200. ✓
- `canUseHashJoin` at 893; `notPatternDependsOnMatched` at 744;
  `traceBackwardBranch` at 1150. ✓

---

## Chapter 14 — Index-Assisted Traversal: Pre-Filtering Adjacency Lists

### Issues

None. All factual claims verified.

### Verified correct

- `RidFilterDescriptor` sealed interface at line 27; four `record` implementations:
  `DirectRid` at 97, `EdgeRidLookup` at 141, `IndexLookup` at 200, `Composite` at 239. ✓
  (Note: the interface Javadoc says "Three variants are supported" and omits `Composite` —
  this is an error in the source Javadoc, not in the book. The book correctly describes all four.)
- `TraversalPreFilterHelper` method line numbers:
  `collectionIdsForClass` at 69; `resolveIndexToRidSet` at 93;
  `resolveReverseEdgeLookup` at 163; `findIndexForFilter` at 293;
  `passesRatioCheck` at 351. ✓
  Checkpoint mask `CHECKPOINT_INTERVAL_MASK = 0x3FF` at line 58. ✓
- `EdgeTraversal.addIntersectionDescriptor` at 162; `resolveWithCache` at 229;
  `CACHE_CAPACITY = 64` at line 88. ✓
- `MatchEdgeTraverser.applyPreFilter` at 556; class-filter branch at 565;
  RID-set filter branch at 574. ✓
- `MatchExecutionPlanner.optimizeScheduleWithIntersections` at 3010;
  `attachCollectionIdFilters` at 3905; `inferClassFromEdgeSchema` at 4558. ✓
- `GlobalConfiguration` prefilter knobs at line 1292:
  `QUERY_PREFILTER_MAX_RIDSET_SIZE` (default 100 000);
  `QUERY_PREFILTER_MAX_SELECTIVITY_RATIO` (default 0.8);
  `QUERY_PREFILTER_MIN_LINKBAG_SIZE` (default 50). ✓

---

## Chapter 16 — Reading EXPLAIN: Diagnosing Plans in Practice

### Issues

None. All cited `file:line` anchors verified.

### Verified correct

- `ExplainResultSet.java:57` — `executionPlan.prettyPrint(0, 3)` call. ✓
- `MatchPrefetchStep.prettyPrint` at line 111. ✓
- `MatchFirstStep.prettyPrint` at line 125. ✓
- `MatchStep.prettyPrint` at line 125; `appendIntersectionDescriptor` at line 154. ✓
- `HashJoinMatchStep.prettyPrint` at line 416. ✓
- `FilterNotMatchPatternStep.prettyPrint` at line 123. ✓
- `CorrelatedOptionalHashJoinStep.prettyPrint` at line 216. ✓
- `InvertedWhileHashJoinStep.prettyPrint` at line 343. ✓
- `BackRefHashJoinStep.prettyPrint` at line 675. ✓
- `SelectExecutionPlan.prettyPrint` at line 94. ✓
- Prefetch threshold `THRESHOLD = 100` at `MatchExecutionPlanner.java:328`. ✓
- `splitDisjointPatterns` at line 4185 (cited as `MatchExecutionPlanner.java:4185`). ✓

---

## Chapter 17 — Reference: Files, Classes, Configuration, Glossary

### Issues

**[HIGH] Three prefilter knobs absent from Table 17.2.**
Table 17.2 (runtime configuration properties) lists five rows but omits the three
`QUERY_PREFILTER_*` knobs that Chapter 14 introduces:
`QUERY_PREFILTER_MAX_RIDSET_SIZE`, `QUERY_PREFILTER_MAX_SELECTIVITY_RATIO`, and
`QUERY_PREFILTER_MIN_LINKBAG_SIZE`. These are runtime-hot-configurable knobs with
non-trivial defaults (100 000, 0.8, 50) that directly affect query performance and
are covered in detail in Chapter 14. Their absence from the reference table makes
the table incomplete as a look-up resource.

**[MEDIUM] GlobalConfiguration line range in Table 17.1 is stale.**
Table 17.1's row for `GlobalConfiguration.java` states
"MATCH-related knobs at lines 854–885 and 1174–1195". The three prefilter knobs
are declared at lines 1292–1315, outside both cited ranges. The range should be
extended to include lines 1292–1315 (or phrased as "854–885, 1174–1195, and 1292–1315").

**[LOW] Phase-numbering discrepancy in Figure 17.1.**
The end-to-end pipeline diagram (Figure 17.1) labels the step-generation phase as
"Phase 6" and the NOT-pattern phase as "Phase 7". The source code in
`MatchExecutionPlanner.createExecutionPlan()` comments Phase 5 as
"Topological scheduling + step generation" (combining the book's Phases 5 and 6),
Phase 6 as "Append NOT-pattern filter steps", and Phase 7 as "optional cleanup".
The book's phase split is defensible (scheduling and step-generation are logically
distinct), but readers who cross-reference the source code will find the phase
numbers off by one for Phases 6–8. A note such as "book Phase 6 = second half of
source Phase 5" would prevent confusion.

### Verified correct

- All 44 file-layout rows spot-checked (20 fully, the remainder by `ls`). Every
  file exists at the stated path. ✓
- `QUERY_STATS_DEFAULT_FAN_OUT` at line 1181 (default `10.0`);
  `QUERY_STATS_DEFAULT_SELECTIVITY` at line 1174 (default `0.1`). ✓
- `THRESHOLD = 100` and `INNER_JOIN_MEMORY_WEIGHT = 7` private planner constants at
  `MatchExecutionPlanner.java:328` and `358`. ✓
- `RidFilterDescriptor.Composite` (fourth permitted type) correctly described.
  Source Javadoc omits it, but the sealed interface itself declares all four
  `record` implementations. ✓
- Glossary terms all correspond to classes, interfaces, or concepts that exist in
  the current source. No dropped or invented terms detected. ✓

---

## Cross-chapter consistency check (§17.2 vs §13 / §14)

Chapter 13 and Chapter 17 Table 17.2 agree on all three hash-join knobs
(names, defaults, roles). Chapter 14 and Chapter 17 disagree: the three prefilter
knobs are covered in Chapter 14 but absent from Table 17.2 and from the
GlobalConfiguration range in Table 17.1. This is the same issue as the HIGH-severity
finding above.
