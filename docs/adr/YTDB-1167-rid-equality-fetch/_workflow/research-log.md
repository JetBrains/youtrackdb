# Research Log — YTDB-1167

## Initial request

YTDB-1167: SELECT FROM <class> WHERE @rid = <rid> does a full class scan instead of a direct RID fetch.

FINDING (Performance):
A plain `SELECT FROM <class> WHERE @rid = <literal>` scans the whole class and post-filters on the RID, instead of fetching the one record directly. Confirmed by EXPLAIN on an in-memory DB:

    EXPLAIN SELECT FROM Probe WHERE @rid = #18:0
      + FETCH FROM CLASS Probe
      + FILTER ITEMS WHERE
          @rid = #18:0

For contrast, a direct RID target compiles to an O(1) fetch:

    EXPLAIN SELECT FROM #18:0
      + FETCH FROM RIDs
          [#18:0]

So the cost of `SELECT FROM <class> WHERE @rid = <rid>` is O(class size), not O(1). On a 2M-row class this reads every record to return one.

ROOT CAUSE (as reported):
SelectExecutionPlanner.handleClassAsTarget tries, in order: indexed function -> regular index -> full class scan fallback (FetchFromClassExecutionStep). @rid is not a property and has no index, so the first two stages never match and the query falls through to the scan. The scan can be narrowed by a RID range -- extractRidRanges/isRidRange push `@rid > #10:5 AND @rid < #10:100` into FetchFromClassExecutionStep to bound iteration. But isRidRange gates on SQLBinaryCompareOperator.isRangeOperator(), true only for <, <=, >, >= and false for =. A RID equality is therefore neither narrowed nor short-circuited; it stays in the WHERE clause as a post-filter over the full scan.

WHY YTDB-629 / PR #1124 DOES NOT COVER THIS:
YTDB-629 fixes the same pathology only inside the MATCH planner: MatchExecutionPlanner.promoteStaticRidsFromFilters lifts `WHERE: (@rid = <literal>)` out of a node's aliasFilters into aliasRids, so estimateRootEntries returns 1 and MatchFirstStep emits a direct RID fetch. That change touches MatchExecutionPlanner and SQLRid only. The plain SELECT planner (SelectExecutionPlanner) is untouched.

PROPOSED FIX (as reported):
In SelectExecutionPlanner (in optimizeQuery or at the top of handleClassAsTarget), detect a static `@rid = <literal|param>` equality in the flattened WHERE and route it to a FetchFromRidsStep, keeping any remaining predicates as a post-filter. Extraction primitives already exist and are used elsewhere: SQLWhereClause.findRidEquality() / extractAndRemoveRidEquality() (MATCH planner and expand-push-down path both call them). The RID expression must be early-calculable (literal or bound parameter), matching the guard the MATCH change uses.

CORRECTNESS NOTE -- preserve class-membership semantics: A class scan returns only records of the target class and its subclasses; a bare FETCH FROM RIDs fetches the record regardless of class. Because each class maps to a fixed set of collection ids (resolveClassToCollectionIds), the fix must check that the target RID's collection id belongs to the target class hierarchy: if it does, emit the direct fetch; if not, emit an empty result. Without this guard, `SELECT FROM A WHERE @rid = <rid-of-B>` would wrongly return the B record.

`@rid IN [<literal>, ...]` is the natural multi-RID extension and can use the same path (FetchFromRidsStep already takes a list).

IMPACT: SELECT FROM <class> WHERE @rid = <rid> goes from O(class size) to O(1). Common access pattern (fetch-by-id with a class guard).

REPRODUCTION: EXPLAIN any `SELECT FROM <class> WHERE @rid = <rid>` against a non-trivial class and observe FETCH FROM CLASS + FILTER ITEMS WHERE @rid = ... rather than FETCH FROM RIDs.

## Decision Log

### D1: Hook the fix as `handleClassAsTargetWithRidEquality`, tried first inside `handleClassAsTarget`
2026-07-01 [ctx=safe]
Add a boolean-returning `handleClassAsTargetWithRidEquality(plan, identifier, info, ctx, profilingEnabled)` as the first attempt in `handleClassAsTarget` (SelectExecutionPlanner.java:2099), before `handleClassAsTargetWithIndexedFunction`. It returns true (and the caller returns) when it emits the direct fetch.
- **Why:** Mirrors the existing `handleClassAsTargetWith*` boolean pattern already at 2106/2112/2118, so it short-circuits before index probing with minimal blast radius and stays cohesive with the other class-target handlers.
- **Alternatives rejected:** (a) hook in `optimizeQuery` — too early, before the target-type dispatch at line 1395-1445, so it would duplicate class resolution; (b) hook at the buildFetchSteps dispatch site (line 1406, beside `extractRidRanges`) — works but splits RID-equality handling away from the class-target handlers, less cohesive.
- **Plan-time cost on the non-optimized majority (gate A4):** Placing the detector first means every class-target query runs it before falling through. Bound the cost with cheap O(1) guards first: bail immediately when `info.whereClause == null` and when the WHERE flattens to more than one OR branch — both are the existing early returns in `extractAndRemoveRidEquality` (SQLWhereClause.java:1004 null baseExpression, 1009-1012 multi-OR-branch). The per-term unwrap traversal (1019-1074) runs only for a single-branch WHERE. Plan caching amortizes detection across repeated identical literal queries, so the non-optimized path pays only a constant check per distinct statement.

### D2: Class-membership guard via `resolveClassToCollectionIds(className)` ∩ `rid.getCollectionId()`
2026-07-01 [ctx=safe]
Before emitting the direct fetch, resolve the target class to its polymorphic collection-id set (`resolveClassToCollectionIds`, SelectExecutionPlanner.java:3675 → `TraversalPreFilterHelper.collectionIdsForClass`) and check the target RID's `getCollectionId()` is a member. Member → `FetchFromRidsStep([rid])`; non-member → an empty-result step.
- **Why:** `FetchFromRidsStep` fetches by RID with no class check (FetchFromRidsStep.java:34-47), so a bare fetch would return `SELECT FROM A WHERE @rid = <rid-of-B>` wrongly. The collection-id set is exactly the polymorphic membership a class scan enforces. This is the same guard the EXPAND push-down path already uses (SelectExecutionPlanner.java:3400).
- **Polymorphic direction (gate A1):** `resolveClassToCollectionIds` → `TraversalPreFilterHelper.collectionIdsForClass` → `clazz.getPolymorphicCollectionIds()` (TraversalPreFilterHelper.java:90-97) returns the class *and all subclasses*. So `SELECT FROM Vehicle WHERE @rid = <rid-of-Car>` (Car extends Vehicle) correctly returns the Car record, and `SELECT FROM Car WHERE @rid = <rid-of-Vehicle>` correctly returns empty. Each collection belongs to exactly one class, so membership is an exact test, not an approximation.
- **Emission contract (gate A1):** member RID → chain `FetchFromRidsStep` (+ the remainder FilterStep per D4/Q2) and **return true**; non-member RID → chain `EmptyStep` (EmptyStep.java:19, a real step used by MATCH and ForEachStep) and **return true**. The non-member branch must return true so the caller short-circuits past the class-scan fallthrough (handleClassAsTarget:2135-2147); returning false would silently fall through to a full scan and lose the empty-result guarantee. This member/non-member emission is folded into the D4 unified path so the whole contract lives in one place.
- **Alternatives rejected:** chain `FilterByClassStep` after the fetch — redundant with the collection-id check, and it filters a loaded record rather than short-circuiting at plan time.

### D3: Optimize only when the RID value is early-calculable
2026-07-01 [ctx=safe]
Gate the optimization on `ridExpression.isEarlyCalculated(ctx)`; otherwise fall through to the class scan.
- **Why:** The membership check and the `RecordIdInternal` for `FetchFromRidsStep` both need the concrete RID value at plan time (as `handleRidsAsTarget` does via `toRecordId(ctx)`, SelectExecutionPlanner.java:1631). `isEarlyCalculated` is true for literals and bound parameters — the same guard the MATCH planner applies. A non-early-calc value (field ref, subquery) cannot be resolved at plan time.
- **Plan-cache trade-off on the bound-param path (gate A3):** `isEarlyCalculated` is true for bound params (SQLBaseExpression.java:396-401), which are available at plan time (`ctx.setInputParameters` precedes `createExecutionPlan`). Admitting them means `SELECT FROM C WHERE @rid = :rid` now compiles to `FetchFromRidsStep`, whose `canBeCached()` is hard-false (FetchFromRidsStep.java:87-90), so the whole plan is non-cacheable (SelectExecutionPlan.java:280-287) and re-plans on every execution — whereas today it compiles to a cacheable class-scan plan planned once. On a non-trivial class the O(1) fetch dwarfs the re-plan cost, so this is a clear net win (the target workload). Accepted trade: a small class queried at high frequency by param trades a cached O(class) scan for a per-call re-plan plus O(1) fetch; not worth special-casing now.
- **Alternatives rejected:** defer RID evaluation to a runtime step — cannot check class membership at plan time and adds machinery for no gain over the class scan.

### D4: Support `@rid IN [...]` via a new `extractAndRemoveRidInList` primitive, unified into one emission path
2026-07-01 [ctx=safe]
Add a second extraction primitive on `SQLWhereClause` for `@rid IN [<early-calc list>]`, sibling to `extractAndRemoveRidEquality`. Both feed one emission path: gather candidate RIDs (a singleton for `=`, a collection for `IN`), filter by the D2 class-membership guard, then chain a single `FetchFromRidsStep(members)` (empty result if none survive). User confirmed IN is in scope for this change.
- **Why:** `FetchFromRidsStep` already takes a `Collection<RecordIdInternal>` (S3), so the fetch side is free; the only new code is the WHERE-parsing primitive. Unifying `=` and `IN` behind one candidate-RID → membership-filter → fetch path avoids two near-duplicate emission branches.
- **Alternatives rejected:** (a) `=` only, `IN` as a follow-up issue — user chose to include it; (b) a single primitive that handles both `SQLBinaryCondition` and `SQLInCondition` — they are distinct AST node types with different right-side shapes, so one detector per node type is cleaner than a branchy combined one.
- **Representation (one common collection, not two fields):** The `=` and `IN` candidates land in a single local `List<RecordIdInternal>` at the emission site (like `handleRidsAsTarget`), not two separate fields and not a new `QueryPlanningInfo` field (contrast `info.ridRangeConditions`). The handler extracts at most one RID predicate; a second RID predicate in the same WHERE (e.g. `@rid = x AND @rid IN [...]`) stays in the remaining WHERE as a post-filter, so correctness holds without plan-time set intersection. Both extractors can return one value-side expression and the emission code normalizes scalar-vs-collection (`=` → singleton, `IN` → iterate), making `=` the degenerate case of the `IN` path.
- **Rejected here:** separate `ridEquality` / `ridInList` fields intersected at plan time — only needed to precisely optimize the rare (often contradictory) `@rid = x AND @rid IN [...]`; the post-filter already yields the correct result, so the extra state is not worth it.
- **Edge cases the emission path must handle (gate A5):**
  - **Empty list** `@rid IN []` → candidate set empty → after membership filter, chain `EmptyStep` (empty in, empty out). Must not fall through to a scan (which would return everything).
  - **Duplicate RIDs** `@rid IN [#10:1, #10:1]` → **dedup the candidate collection** before `FetchFromRidsStep`. The step iterates with no dedup (FetchFromRidsStep.java:44-46), so without dedup it returns the record twice, whereas the class-scan-plus-filter it replaces returns it once. Dedup preserves scan cardinality — this is a correctness requirement, not a nicety.
  - **Mixed member / non-member list** `@rid IN [<member>, <non-member>]` → the membership filter drops only the non-member and fetches the member; a naive all-or-nothing implementation would err here.
  - **`@rid = x AND @rid IN [...]` convergence:** the extractor takes the *first* matching AND-term (SQLWhereClause.java:1019 iteration order), the second RID predicate stays as a post-filter. Both orders converge: extract `=` → fetch {x} → IN post-filter keeps x; extract `IN` → fetch {x,y} → `=` post-filter keeps x. Same final result, different intermediate cardinality — both correct.
- **Confirm at implementation (gate A5, grep-only, PSI unavailable):** verify `SQLInCondition`'s left side has the `mathExpression instanceof SQLBaseExpression` shape so the bare-`@rid` check in `tryExtractRidValue` (SQLWhereClause.java:1081) can be reused for the IN detector; the IN detector maps each evaluated list element to `RecordIdInternal` the way `SQLRid.toRecordId` does (SQLRid.java:61-72, handles Identifiable and String). `SQLNotInCondition` is a distinct class, so "must not optimize negated IN" (S7) is enforced by node-type discrimination.

## Surprises & Discoveries

### S7: `@rid IN [...]` is an `SQLInCondition`; only a literal/param right side is early-calculable
2026-07-01 [ctx=safe]
SQLInCondition.java:26-67 — `left` is the `@rid` side, the right side is one of `rightStatement` (subquery), `rightParam` (bound param), or `rightMathExpression` (a list literal like `[#1:0, #2:0]`). Detection reuses the bare-`@rid` check from `tryExtractRidValue`. Early-calc gating: `rightParam` and an early-calc `rightMathExpression` qualify; `rightStatement` (a subquery) does not — skip it and fall through to the scan. `SQLNotInCondition` and a negated `@rid IN` must NOT be optimized (the complement matches many records).

### S1: The SELECT planner already extracts `@rid = <expr>` — but only in the EXPAND push-down path
2026-07-01 [ctx=safe]
`SelectExecutionPlanner` already calls `extractAndRemoveRidEquality()` → `RidFilterDescriptor.DirectRid` at line 3421-3428, and already resolves class membership via `resolveClassToCollectionIds` + a `classFilter` IntSet handed to `ExpandStep` (3394-3405). This is the exact template for the class-target fix; the mechanism exists, it just was never wired into the plain class-target path.

### S2: `extractAndRemoveRidEquality()` handles single-OR-branch WHERE only, both orderings, and does NOT check early-calc
2026-07-01 [ctx=safe]
SQLWhereClause.java:1003. Returns null when the WHERE flattens to multiple OR branches. Handles `@rid = x` and `x = @rid`, unwraps nested single-element Or/And wrappers, skips a negated NOT. Returns a `RidExtractionResult(ridExpression, remainingWhere)` where remainingWhere is null when the RID equality was the sole predicate. It does not verify the value side is early-calculable — hence D3.

### S3: `FetchFromRidsStep` fetches by RID with no class check
2026-07-01 [ctx=safe]
FetchFromRidsStep.java:34-47 — ctor takes `Collection<RecordIdInternal>` and `loadIterator`s them directly. No membership filter. Confirms D2 is mandatory. The Collection ctor also means `@rid IN [...]` reuses the same step unchanged.

### S4: Direct-RID target template is `handleRidsAsTarget`
2026-07-01 [ctx=safe]
SelectExecutionPlanner.java:1631 evaluates each `SQLRid` to a `RecordIdInternal` at plan time (`toRecordId(ctx)`) and chains `FetchFromRidsStep`. The fix mirrors this, but its value comes from evaluating the extracted `ridExpression` rather than a parsed `SQLRid` target.

### S5: The finding's `promoteStaticRidsFromFilters` name is approximate
2026-07-01 [ctx=safe]
On develop the MATCH RID-equality logic is inline in `MatchExecutionPlanner.addStepsFor()` using `findRidEquality()` (non-destructive) plus an `isEarlyCalculated` guard, not a standalone `promoteStaticRidsFromFilters` method. Substance of the finding holds; only the method name differs.

### S6: The remaining-WHERE application site differs between class-scan and RID-target paths
2026-07-01 [ctx=safe]
The class-scan path embeds the WHERE post-filter inside `FetchFromClassExecutionStep` (via `info`), whereas the RID-target path (`handleRidsAsTarget`) relies on a separate downstream filter step in the plan pipeline. Routing a class target to `FetchFromRidsStep` therefore changes where the remaining predicates are applied — the fix must apply the extracted-out remainder correctly (explicit `FilterStep`, as the EXPAND path does) and avoid both double-filtering and dropped predicates. Resolve the exact wiring in planning/implementation — see Q2.

## Open Questions

### Q1: Scope of `@rid IN [<literal>, ...]` — RESOLVED
Resolved 2026-07-01: user chose to include `IN` in this change. See D4.

### Q2: Remaining-WHERE wiring for the RID-fetch path — RESOLVED
Resolved 2026-07-01 (gate A2). The correct mechanism is the **index-handler** one, NOT the EXPAND FilterStep-surgery. `handleClassAsTarget` (via `handleFetchFromTarget`, SelectExecutionPlanner.java:267) runs *before* `handleWhere` (line 271); the EXPAND path (line 274, `tryPushDownFilterIntoExpand`) runs *after* `handleWhere` and removes an already-built FilterStep — a mechanism that would find nothing to remove here. So the new handler sets `info.whereClause` to the extraction remainder and **invalidates `info.flattenedWhereClause`** (nulls both fields, exactly as the index handlers at 2357-2358 do), then `handleWhere` (line 1985-1998) chains the remainder FilterStep exactly once (it chains iff `info.whereClause != null`). Both WHERE fields must be kept in sync: mutating only `whereClause` leaves `flattenedWhereClause` stale — no current downstream reader is hit, but it violates the invariant the index handlers uphold and is a latent trap. Test: `@rid = <literal> AND <other-predicate>` asserts the other predicate applies exactly once.

### Q3: Interaction with ORDER BY / SKIP / LIMIT / GROUP BY — RESOLVED
Resolved 2026-07-01 (gate A6). Safe — the fetch handler builds only the *source* step; `handleProjectionsBlock` (line 276) assembles ORDER BY / SKIP / LIMIT / GROUP BY / DISTINCT afterward regardless of which fetch handler won, so nothing is bypassed. The new handler must leave `info.orderApplied` false (it does not push ordering into the fetch, unlike the class-scan path at 2144-2146), so `handleOrderBy` sorts normally — a no-op for a single RID, correct for a multi-RID `IN ... ORDER BY`. The COUNT hardwired optimization (line 573-620, runs before fetch) will not match an `@rid` equality (it requires a base-identifier indexed property), so `SELECT count(*) FROM C WHERE @rid = x` is unaffected.

### Q4: Design gate — RESOLVED
Resolved 2026-07-01: user confirmed `design_gate=no`, adversarial lens = Performance + query-result correctness. Holds contingent on the A2 two-field WHERE-sync detail landing in the track file's Decision Log (the change is not a verbatim template copy — it consumes the WHERE at a different plan stage). If a track reviewer cannot reconstruct the wiring from the track file alone, escalate to a design doc.

## Adversarial gate record

### Adversarial review of this log (2026-07-01) — NEEDS REVISION: 0 blockers, 5 should-fix, 2 suggestions
Lens: Performance hot path + query-result correctness. See `reviews/research-log-adversarial-iter1.md`. Findings: A1 (D2 polymorphic direction + non-member emission contract), A2 (Q2 WHERE-wiring — index-handler mechanism, two-field sync, not EXPAND surgery), A3 (D3 bound-param plan-cache trade-off), A4 (D1 cheap-guard ordering), A5 (D4 IN edge cases — empty list, duplicate-RID dedup for cardinality, mixed member/non-member, convergence trace). Suggestions A6 (Q3 clause interaction, safe), A7 (design_gate=no holds contingent on A2 in the track).

### Adversarial review of this log (2026-07-01) — PASS
Gate cleared. 0 blockers (no research re-decision needed). All 5 should-fix folded into the Decision Log rationale: A1→D2, A2→Q2 (resolved), A3→D3, A4→D1, A5→D4. Q3 resolved (A6), design_gate=no retained (A7, contingent detail now in Q2/D2). Code claims behind A2/A3/A5 independently verified against develop (handleFetchFromTarget:267→handleWhere:271 ordering, index-handler dual-field null at 2357-2358, FetchFromRidsStep.canBeCached()=false at 87-90, no-dedup iterate at 44-46). Orchestrator-adjudicated should-fix clear — no re-spawn (gate loops only on blockers, of which there were none).
