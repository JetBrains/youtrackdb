<!--MANIFEST
dimension: performance
iteration: 1
verdict: pass
findings_total: 2
blockers: 0
should_fix: 0
suggestions: 2
evidence_base: 3
cert_index: [C1, C2, C3]
flags: []
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-suggestion-both-rid-extractors-run-full-per-term-traversals-on-every-single-branch-class-target-where"
    loc: "SelectExecutionPlanner.java:2219-2223"
    cert: C1
    basis: "read + call-trace"
  - id: PF2
    sev: suggestion
    anchor: "#pf2-suggestion-bound-param-queries-re-plan-every-execution-d3-trade"
    loc: "SelectExecutionPlanner.java:2226-2231; FetchFromRidsStep.java:88"
    cert: C2
    basis: "read + call-trace"
-->

# Performance review — Track 1 (direct RID fetch), iteration 1

This track replaces an O(class-size) scan-plus-filter with an O(k) direct RID fetch for `SELECT FROM <class> WHERE @rid = / IN`. The optimization is sound and the data structures are the right ones: the fetch is lazy with no hidden scan, dedup uses `LinkedHashSet` (O(1) insert), and the class-membership guard uses an `IntOpenHashSet` (O(1) `contains`). The two findings are both suggestion-tier — the added plan-time cost on the non-optimized majority is real but small and bounded, and the non-cacheable-param trade is correctly identified and accepted in D3. No blocker or should-fix.

## Findings

### PF1 [suggestion] Both RID extractors run full per-term traversals on every single-branch class-target WHERE

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlanner.java` (line 2219-2223)

**Issue**: The two cheap O(1) guards do short-circuit correctly (null WHERE at 2207; flattened-OR-branch-count > 1 at 2214, reading the already-computed `info.flattenedWhereClause` — no re-flatten). But once a query passes them — which every single-branch WHERE does, including the common `SELECT FROM Person WHERE name = 'x'` — the handler calls `extractAndRemoveRidEquality()` and, on its null return, `extractAndRemoveRidInList()`. Each walks all `andBlock.subBlocks`, and per term unwraps nested single-element Or/And wrappers before an `instanceof` check that fails for a non-`@rid` predicate. So a k-term single-branch WHERE pays O(k) wrapper-walk work **twice** (once per extractor) before falling through, not "a constant check." D1's "constant check per distinct statement" framing holds only after plan caching amortizes it; the first plan of each distinct literal statement, and every execution of a bound-param statement (see PF2), pays the full traversal.

**Evidence**: C1. Guard-then-traverse order at `SelectExecutionPlanner.java:2207-2223`; per-term unwrap loop in `tryExtractRidFromTerm` (`SQLWhereClause.java:1157-1187`) and `tryExtractRidInListFromTerm` (`SQLWhereClause.java:1093-1130`), each iterated across `andBlock.subBlocks` by the extractor loops at `SQLWhereClause.java:1019` and `1046`.

**Impact**: Plan-time only, single-threaded per plan. For a typical WHERE (1-4 AND-terms) this is a few `instanceof` checks and pointer-walks — sub-microsecond, dwarfed by the index/scan planning that follows and invisible next to query execution. It matters only in aggregate for a workload dominated by distinct un-cacheable single-branch non-RID queries, and even then marginally.

**Suggestion**: Optional. If the double-traversal is ever measured to matter, `extractAndRemoveRidInList` could bail early when the sole term is a `SQLBinaryCondition` (equality was already tried) — but that couples the two extractors and is not worth the readability cost now. Recommend leaving as-is; recorded so the D1 "constant check" wording is understood as post-cache, not per-plan.

### PF2 [suggestion] Bound-param queries re-plan every execution (D3 trade)

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlanner.java` (line 2226-2231); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/FetchFromRidsStep.java` (line 88)

**Issue**: `isEarlyCalculated(ctx)` is true for bound params, so `SELECT FROM C WHERE @rid = :rid` now compiles to `FetchFromRidsStep`, whose `canBeCached()` is hard-false (`FetchFromRidsStep.java:88`). That makes the whole plan non-cacheable, so a param query re-plans on every execution — where today it compiles to a cacheable class-scan plan planned once. The re-plan re-runs the full planner, including the PF1 traversal and the RID evaluation/membership filter, on every call.

**Evidence**: C2. `isEarlyCalculated` gate at `SelectExecutionPlanner.java:2226`; `FetchFromRidsStep.canBeCached()` returns false at `FetchFromRidsStep.java:88`, confirmed by Read. D3 documents this trade explicitly.

**Impact**: On any non-trivial class the O(1) fetch dwarfs the re-plan cost — the target workload wins outright. The one regression shape is a small, high-frequency, param-swapped query that previously rode a cached O(class) scan: it swaps a cache hit for a per-call re-plan plus O(1) fetch. On a small class the scan was already cheap, so the re-plan can dominate. This is the accepted trade in D3, and the framing holds — the loser is a narrow case (small class + param + high frequency) and the fetch is still correct and typically faster. Flagging so it is a conscious accept, not an oversight.

**Suggestion**: Accept as documented in D3. No change requested. If the small-class-param regression is ever observed in practice, a future option is to keep the plan cacheable by deferring RID resolution to a runtime step gated on class membership — but D3 already weighed and rejected that for adding machinery with no gain on the target workload. Leave as-is.

## Evidence base

Verdict rendering: a claim that survived the Phase-4 scale-validation refutation as a CONFIRMED issue is compressed to one line; a refuted or negligible-but-noted claim appears in full.

#### C1 — PF1: extractor double-traversal on single-branch WHERE
CONFIRMED as a (suggestion-tier) issue: the two extractors do run O(k) per-term traversals twice before fall-through on every single-branch non-RID query; verified by Read of the guard/extract order (`SelectExecutionPlanner.java:2207-2223`) and the per-term unwrap loops (`SQLWhereClause.java:1093-1130`, `1152-1188`).

#### C2 — PF2: non-cacheable param plan re-plans each execution
CONFIRMED as a (suggestion-tier) issue: `FetchFromRidsStep.canBeCached()` is hard-false (Read, `FetchFromRidsStep.java:88`) and the param path reaches it via the `isEarlyCalculated` gate; the small-class high-frequency regression shape is real but narrow and accepted in D3.

#### C3 — Fast path is genuinely O(k) with the right structures (no finding)
Refutation of "the fast path hides a scan or uses an inefficient structure" — this candidate was checked and did not survive as a finding, so it is recorded in full here rather than raised:

- **No hidden scan.** `FetchFromRidsStep.internalStart` returns `ExecutionStream.loadIterator(this.rids.iterator())` (`FetchFromRidsStep.java:46`) — it iterates only the resolved RID collection and loads each by RID. Cost is O(k) loads for k surviving members, no class enumeration. Confirmed by Read.
- **Dedup is O(1) per element.** Candidates accumulate in a `LinkedHashSet<RecordIdInternal>` (`SelectExecutionPlanner.java:2235`), so duplicate RIDs collapse at insert with O(1) hashing and insertion-order preservation. Confirmed by Read of the diff.
- **Membership lookup is O(1).** `resolveClassToCollectionIds` returns `TraversalPreFilterHelper.collectionIdsForClass`, which builds an `IntOpenHashSet` (`TraversalPreFilterHelper.java:92`); the handler's `classCollectionIds.contains(rid.getCollectionId())` (`SelectExecutionPlanner.java:2262`) is therefore O(1) per candidate. The membership filter is O(k) total. Confirmed by grep + Read.
- **Multi-value handling is type-dispatch, not a scan.** `MultiValue.isMultiValue` / `getMultiValueIterable` (`MultiValue.java:79,329`) branch on type; the subsequent loop iterates the IN-list elements once (O(k)). Confirmed by grep.
- **Ordering preserved for downstream.** The handler leaves `info.orderApplied` untouched, so a multi-RID `IN ... ORDER BY` still sorts downstream; no correctness-for-free lost, no extra sort injected on the fast path. Per D1/track invariants, verified against the diff.

Net: the optimization's runtime cost is O(k) in the number of listed/surviving RIDs with no super-linear term and no scan — exactly the intended O(1)/O(k). No finding.

**Tooling caveat (reference accuracy).** mcp-steroid was reachable but the IDE held the develop checkout, not this worktree, so PSI was unavailable. All symbol lookups used grep + Read against the worktree files. Every cited symbol (`FetchFromRidsStep`, `EmptyStep`, `resolveClassToCollectionIds`, `collectionIdsForClass`, `MultiValue.isMultiValue`, `isEarlyCalculated`) is uniquely named with no polymorphic dispatch on the read paths, so grep read the correct files; the findings depend on read code paths and cardinality reasoning, not on a caller enumeration that grep could silently under-count.
