<!-- workflow-sha: 03eac656fa115a8e6af3f53d8805d8f16f3bad50 -->
# Track 1: Direct RID fetch for `SELECT FROM <class> WHERE @rid = / IN`

## Purpose / Big Picture
`SELECT FROM <class> WHERE @rid = <literal-or-param>` (and the `@rid IN [...]` list form) fetches its records in O(1) instead of scanning the whole class.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Today a `SELECT FROM Person WHERE @rid = #18:0` reads every record in `Person` and post-filters on the RID, so the cost is O(class size) — on a 2M-row class it reads two million records to return one. A RID (record id, YouTrackDB's physical record address, written `#<collection-id>:<position>`) already names the exact record, so the scan is pure waste. The direct-target form `SELECT FROM #18:0` already compiles to an O(1) fetch; this track makes the class-plus-WHERE form do the same, while preserving the class-membership semantics the scan gave for free — a class scan returns only records of the target class and its subclasses, so a direct RID fetch must reject a RID that belongs to some other class. The change lives entirely in the plain-SELECT planner (`SelectExecutionPlanner`) plus one new WHERE-parsing primitive on `SQLWhereClause`; the MATCH planner already solved the same pathology for its own path (YTDB-629) and is untouched here.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [ ] Track completion
- [x] 2026-07-01T13:50Z [ctx=safe] Review + decomposition complete
- [x] 2026-07-01T14:36Z [ctx=safe] Step 1 complete (commit 08444b1fb8)
- [x] 2026-07-01T16:30Z [ctx=info] Track-level code review iteration 1 complete
- [x] 2026-07-01T16:34Z [ctx=info] Phase C review passed (iteration 1, all reviewers PASS; 2 should-fix + 1 fixed suggestion + coverage tests applied in commit ead4b3f8c2)

## Surprises & Discoveries
- Step 1 leaves the defensive `toRecordIdCandidate` switch arms for a String or null/other-type evaluated RID value uncovered — the Identifiable-RID tests never reach them. Changed-code coverage is 93.6% line / 76.3% branch, above the 85/70 gate. Phase C's test-completeness review can add a `@rid = '#c:p'` string-literal test to exercise the `case String` arm, or accept the arms as defensive skips. See Episodes §Step 1.

## Decision Log
### D1: Hook the fix as `handleClassAsTargetWithRidEquality`, tried first inside `handleClassAsTarget`
Add a boolean-returning `handleClassAsTargetWithRidEquality(plan, identifier, info, ctx, profilingEnabled)` as the first attempt in `handleClassAsTarget` (`SelectExecutionPlanner.java:2099`), before `handleClassAsTargetWithIndexedFunction`. It returns true (and the caller returns) when it emits the direct fetch.
- **Alternatives considered:** (a) hook in `optimizeQuery` — too early, before the target-type dispatch, so it would duplicate class resolution; (b) hook at the `buildFetchSteps` dispatch site beside `extractRidRanges` — works but splits RID-equality handling away from the class-target handlers, less cohesive.
- **Rationale:** Mirrors the existing `handleClassAsTargetWith*` boolean pattern already at lines 2106/2112, so it short-circuits before index probing with minimal blast radius and stays cohesive with the other class-target handlers.
- **Risks / caveats:** Placing the detector first means every class-target query runs it before falling through, so it adds plan-time cost to the non-optimized majority. Bound that cost with cheap O(1) guards first: bail immediately when `info.whereClause == null` and when the WHERE flattens to more than one OR branch — both are the existing early returns in `extractAndRemoveRidEquality`. The per-term unwrap traversal runs only for a single-branch WHERE. Plan caching amortizes detection across repeated identical literal queries, so the non-optimized path pays a constant check per distinct statement.
- **Implemented in:** this track.

### D2: Class-membership guard via `resolveClassToCollectionIds(className)` ∩ `rid.getCollectionId()`
Before emitting the direct fetch, resolve the target class to its polymorphic collection-id set (a collection id is YouTrackDB's cluster/partition id that determines a record's owning class) via `resolveClassToCollectionIds` (`SelectExecutionPlanner.java:3675`), and check that the target RID's `getCollectionId()` is a member. Member → `FetchFromRidsStep([rid])`; non-member → an empty-result step.
- **Alternatives considered:** chain a `FilterByClassStep` after the fetch — redundant with the collection-id check, and it filters a loaded record rather than short-circuiting at plan time.
- **Rationale:** `FetchFromRidsStep` fetches by RID with no class check (`FetchFromRidsStep.java:34-47`), so a bare fetch would make `SELECT FROM A WHERE @rid = <rid-of-B>` wrongly return the B record. The collection-id set is exactly the polymorphic membership a class scan enforces, and it is the same guard the EXPAND push-down path already uses at line 3400. `resolveClassToCollectionIds` → `collectionIdsForClass` → `getPolymorphicCollectionIds()` returns the class *and all subclasses*, so `SELECT FROM Vehicle WHERE @rid = <rid-of-Car>` (Car extends Vehicle) correctly returns the Car record, and `SELECT FROM Car WHERE @rid = <rid-of-Vehicle>` correctly returns empty. Each collection belongs to exactly one class, so membership is an exact test, not an approximation.
- **Risks / caveats:** The non-member branch must chain `EmptyStep` (`EmptyStep.java:19`, a real step already used by MATCH and `ForEachStep`) and **return true** so the caller short-circuits past the class-scan fallthrough. Returning false there would silently fall through to a full scan and lose the empty-result guarantee — the one failure mode this branch exists to prevent.
- **Implemented in:** this track.

### D3: Optimize only when the RID value is early-calculable
Gate the optimization on `ridExpression.isEarlyCalculated(ctx)` (`SQLBaseExpression.java:396`); otherwise return false and fall through to the class scan.
- **Alternatives considered:** defer RID evaluation to a runtime step — cannot check class membership at plan time and adds machinery for no gain over the class scan.
- **Rationale:** The membership check and the `RecordIdInternal` for `FetchFromRidsStep` both need the concrete RID value at plan time (as `handleRidsAsTarget` gets it via `toRecordId(ctx)`). `isEarlyCalculated` is true for literals and bound parameters — the same guard the MATCH planner applies — and false for a field ref or subquery, which cannot resolve at plan time.
- **Risks / caveats:** Bound parameters are early-calculable and available at plan time (`ctx.setInputParameters` precedes plan creation), so `SELECT FROM C WHERE @rid = :rid` now compiles to `FetchFromRidsStep`, whose `canBeCached()` is hard-false (`FetchFromRidsStep.java:88`). That makes the whole plan non-cacheable, so it re-plans on every execution — whereas today it compiles to a cacheable class-scan plan planned once. On a non-trivial class the O(1) fetch dwarfs the re-plan cost (the target workload), so this is a net win. Accepted trade: a small class queried at high frequency by param swaps a cached O(class) scan for a per-call re-plan plus O(1) fetch; not worth special-casing now.
- **Implemented in:** this track.

### D4: Support `@rid IN [...]` via a new `extractAndRemoveRidInList` primitive, unified into one emission path
Add a second extraction primitive on `SQLWhereClause` for `@rid IN [<early-calc list>]`, sibling to `extractAndRemoveRidEquality`. Both feed one emission path: gather candidate RIDs (a singleton for `=`, a collection for `IN`), dedup, membership-filter by the D2 guard, then chain a single `FetchFromRidsStep(members)` (empty result if none survive).
- **Alternatives considered:** (a) `=` only, with `IN` as a follow-up issue — the user chose to include `IN`; (b) a single primitive handling both `SQLBinaryCondition` and `SQLInCondition` — they are distinct AST node types with different right-side shapes, so one detector per node type is cleaner than a branchy combined one; (c) separate `ridEquality` / `ridInList` fields intersected at plan time — only needed to precisely optimize the rare (often contradictory) `@rid = x AND @rid IN [...]`, and the post-filter already yields the correct result, so the extra state is not worth it.
- **Rationale:** `FetchFromRidsStep` already takes a `Collection<RecordIdInternal>`, so the fetch side is free; the only new code is the WHERE-parsing primitive. The candidates land in one local `List<RecordIdInternal>` at the emission site (like `handleRidsAsTarget`), not two fields and not a new `QueryPlanningInfo` field. Both extractors return one value-side expression and the emission code normalizes scalar-vs-collection, making `=` the degenerate case of the `IN` path.
- **Risks / caveats:** The emission path must handle four edge cases. **Empty list** `@rid IN []` → candidate set empty → chain `EmptyStep`, not a scan (a scan would return everything). **Duplicate RIDs** `@rid IN [#10:1, #10:1]` → dedup before `FetchFromRidsStep`, because the step iterates with no dedup and would otherwise return the record twice, whereas the scan-plus-filter it replaces returns it once — dedup preserves scan cardinality and is a correctness requirement, not a nicety. **Mixed member / non-member list** → the membership filter drops only the non-member and fetches the member; an all-or-nothing implementation would err. **`@rid = x AND @rid IN [...]`** → the extractor takes the first matching AND-term; the second RID predicate stays as a post-filter. Both extraction orders converge on the same rows, differing only in intermediate cardinality: extract `=` fetches {x} and the `IN` post-filter keeps x; extract `IN` fetches {x,y} and the `=` post-filter keeps x.
- **Implemented in:** this track.

### D5: Remaining-WHERE wiring via the index-handler two-field mechanism, not EXPAND FilterStep-surgery
When the extraction leaves a remainder (e.g. `@rid = <literal> AND <other-predicate>`), the handler sets `info.whereClause` to the remainder and **invalidates `info.flattenedWhereClause`** (nulls both fields, exactly as the index handlers do at `SelectExecutionPlanner.java:2357-2358`). `handleWhere` then chains the remainder `FilterStep` exactly once, because it chains iff `info.whereClause != null` (line 1990).
- **Alternatives considered:** the EXPAND push-down mechanism (`tryPushDownFilterIntoExpand`), which removes an already-built `FilterStep`. Rejected — it runs *after* `handleWhere` in the pipeline, whereas the new fetch handler runs *before* it (dispatch order at lines 267 → 271 → 274), so at the point the fetch handler emits, no `FilterStep` exists yet for the EXPAND mechanism to find and remove.
- **Rationale:** `handleClassAsTarget` runs inside `handleFetchFromTarget` (line 267), before `handleWhere` (line 271). Setting `info.whereClause` to the remainder before `handleWhere` runs lets `handleWhere` build the one filter step in its normal place in the pipeline — the same contract the index handlers rely on. If the RID equality was the sole predicate, the remainder is null and no filter step is chained.
- **Risks / caveats:** Both WHERE fields must be kept in sync. Mutating only `whereClause` and leaving `flattenedWhereClause` stale violates the invariant the index handlers uphold; no current downstream reader is hit by the stale field, but it is a latent trap, so null both. A `@rid = <literal> AND <other-predicate>` test must assert the other predicate applies exactly once (not zero, not twice).
- **Implemented in:** this track.

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 1 (2 findings, 2 accepted). Both suggestion-tier, folded into decomposition rather than the reviewed sections: T1 — the handler's success branch must chain **no** `FilterByClassStep` (unlike its two neighbours at `SelectExecutionPlanner.java:2106-2116`), because the D2 collection-id guard is the class filter; T2 — the handler maps an evaluated `SQLExpression` to `RecordIdInternal` (no single-arg `toRecordId(ctx)` overload exists), phrased precisely in the handler step. Tooling: grep + Read (PSI unavailable — IDE open on a different branch); all cited symbols uniquely named with no polymorphic dispatch, so grep read the correct worktree files. Review file: `plan/track-1/reviews/technical-iter1.md`.

## Context and Orientation
**What the query compiles to today.** `SELECT FROM Person WHERE @rid = #18:0` produces `FetchFromClassExecutionStep` (a full class scan) followed by a `FilterStep` that post-filters each scanned record on `@rid = #18:0`. `EXPLAIN` confirms it:

```
+ FETCH FROM CLASS Person
+ FILTER ITEMS WHERE
    @rid = #18:0
```

**Why the scan happens.** `handleClassAsTarget` (`SelectExecutionPlanner.java:2099`) tries, in order, an indexed-function handler (`handleClassAsTargetWithIndexedFunction`, line 2106), a regular-index handler (`handleClassAsTargetWithIndex`, line 2112), and then falls through to the class scan. `@rid` is not a property and carries no index, so the first two never match. The scan *can* be narrowed by a RID range — `extractRidRanges`/`isRidRange` push `@rid > #10:5 AND @rid < #10:100` into the fetch step to bound iteration — but `isRidRange` gates on `SQLBinaryCompareOperator.isRangeOperator()`, which is true only for `<`, `<=`, `>`, `>=` and false for `=`. A RID equality is therefore neither narrowed nor short-circuited; it survives as a post-filter over the full scan.

**The template this mirrors.** The planner already extracts a static `@rid` equality — but only in the EXPAND push-down path. `SelectExecutionPlanner.java:3423` calls `remainingWhere.extractAndRemoveRidEquality()` and wraps the result in a `RidFilterDescriptor.DirectRid` (line 3425), and line 3400 resolves the target class to a collection-id set via `resolveClassToCollectionIds` for a membership check handed to `ExpandStep`. The mechanism exists; it was never wired into the plain class-target path. This track wires it there.

**Supporting primitives already in place.** `SQLWhereClause.extractAndRemoveRidEquality()` (line 1003) handles a single-OR-branch WHERE, both orderings (`@rid = x` and `x = @rid`), unwraps nested single-element `Or`/`And` wrappers, skips a negated `NOT`, and returns a `RidExtractionResult(ridExpression, remainingWhere)` where `remainingWhere` is null when the RID equality was the sole predicate — but it does *not* verify the value side is early-calculable. `FetchFromRidsStep` (line 34) takes a `Collection<RecordIdInternal>` and `loadIterator`s them with no class check and no dedup, which is why the membership guard and IN dedup below are mandatory. `resolveClassToCollectionIds` (line 3675) delegates to `TraversalPreFilterHelper.collectionIdsForClass`, which returns the class *and all subclasses* via `getPolymorphicCollectionIds()`.

**Deliverables of this track.**

- A new `extractAndRemoveRidInList` primitive on `SQLWhereClause`, sibling to `extractAndRemoveRidEquality`, that detects `@rid IN [<early-calc list>]`.
- A new `handleClassAsTargetWithRidEquality` handler in `SelectExecutionPlanner`, tried first inside `handleClassAsTarget`, that routes an early-calculable `@rid = / IN` to a direct fetch.
- A plan-shape-plus-correctness test class for the plain-SELECT path (`FetchFromRidsStep` not `FetchFromClassExecutionStep`; wrong-class → empty; subclass membership; IN dedup; mixed IN list; remainder applied once; non-early-calc fall-through).

## Plan of Work
The edits proceed bottom-up: the WHERE-parsing primitive first (so the handler has something to call), then the handler, then the tests. Phase A settles the exact step boundaries; the shape is:

1. **`SQLWhereClause.extractAndRemoveRidInList`** — a new primitive sibling to `extractAndRemoveRidEquality`. It detects an `SQLInCondition` whose left side is a bare `@rid` (reusing the same left-side check `tryExtractRidValue` applies at `SQLWhereClause.java:1081`) and whose right side is early-calculable: a bound param (`rightParam`) or a list-literal `SQLMathExpression` (`rightMathExpression`) qualify; a subquery (`rightStatement`) does not, and the primitive returns null so the planner falls through to the scan. An `SQLNotInCondition` is a distinct AST node type, so a negated `@rid IN` never reaches this detector — node-type discrimination enforces "do not optimize the complement" for free. It returns the value-side expression plus the WHERE remainder, matching `extractAndRemoveRidEquality`'s result shape.

2. **`SelectExecutionPlanner.handleClassAsTargetWithRidEquality`** — a boolean-returning handler tried first inside `handleClassAsTarget`, before the indexed-function handler. The body:
   - Bail on the cheap O(1) guards first (see the non-optimized-path cost in D1): `info.whereClause == null`, or a WHERE that flattens to more than one OR branch.
   - Try `extractAndRemoveRidEquality` then `extractAndRemoveRidInList` to pull at most one RID predicate; if neither matches, return false (fall through to the existing chain).
   - Gate on `ridExpression.isEarlyCalculated(ctx)` — a literal or bound param resolves at plan time; a field ref or subquery cannot, so return false and fall through.
   - Evaluate the extracted `ridExpression` (via `SQLExpression.execute`) to candidate `RecordIdInternal`s (a singleton for `=`, the list elements for `IN`), mapping each result the way `SQLRid.toRecordId`'s switch does — `Identifiable → getIdentity()`, `String → RecordIdInternal.fromString`, else skip. (Precision from technical-review T2: there is no single-arg `toRecordId(ctx)` overload; the handler maps an evaluated `SQLExpression`, not a parsed `SQLRid` target — see S4 in the research log.) Collect into one local `List<RecordIdInternal>`, and **dedup**.
   - Membership-filter the candidates against `resolveClassToCollectionIds(className)`: keep a candidate iff its `getCollectionId()` is in the class's polymorphic collection set.
   - Emit: survivors present → set `info.whereClause` to the extraction remainder and null `info.flattenedWhereClause` (D5), chain `FetchFromRidsStep(members)`, return true. No survivors (wrong-class RID, empty `IN []`, or an all-non-member list) → chain `EmptyStep`, return true. Both branches return true so the caller short-circuits past the scan fallthrough. **The success branch chains no `FilterByClassStep`** (precision from technical-review T1): unlike the two neighbouring handlers at `SelectExecutionPlanner.java:2106-2116`, which append one after their fetch, this branch must not — the D2 collection-id membership guard is already the class filter, applied at plan time, whereas `FilterByClassStep` would redundantly re-filter a loaded record, exactly what D2 rejects. At the call site inside `handleClassAsTarget`, wire it as `if (handleClassAsTargetWithRidEquality(...)) { return; }` with no `FilterByClassStep`, and add a one-line comment noting why this branch differs from its neighbours.

3. **Tests** — a plan-shape-plus-correctness class exercising: `EXPLAIN` asserts `FetchFromRidsStep` (not `FetchFromClassExecutionStep`) for `@rid = <literal>` and `@rid IN [...]`; wrong-class RID → empty; subclass RID under a superclass target → the record; duplicate RIDs in `IN` → single row (cardinality parity); mixed member/non-member `IN` list → only members; `@rid = <literal> AND <other-predicate>` → the other predicate applied exactly once; a non-early-calc RID (field ref / subquery) → falls through to the scan unchanged.

**Ordering and invariants to preserve.** The handler builds only the *source* step; the handler must leave `info.orderApplied` false so `handleProjectionsBlock` (the downstream ORDER BY / SKIP / LIMIT / GROUP BY / DISTINCT assembler) still runs — a no-op sort for one RID, a real sort for a multi-RID `IN ... ORDER BY`. The `COUNT` hardwired optimization runs before fetch and requires a base-identifier indexed property, so it never matches an `@rid` equality and is unaffected.

## Concrete Steps
1. Add the `@rid = / IN` direct-fetch fast path to the plain-SELECT planner — new `SQLWhereClause.extractAndRemoveRidInList` primitive, new `SelectExecutionPlanner.handleClassAsTargetWithRidEquality` handler wired first in `handleClassAsTarget` (per D1–D5 and `## Plan of Work`), and a new `SelectExecutionPlannerRidEqualityTest` (package `com.jetbrains.youtrackdb.internal.core.sql.executor`) covering the `## Validation and Acceptance` lines below — risk: medium (new non-public methods change the SQL planner's observable behavior; no HIGH trigger fires — the change is plan-time only, plan-cached, and does not touch the record-read/index-read or query-execution inner loop) — size: ~3 files; the whole minimal-tier change is one interdependent unit (the primitive feeds the handler; the tests exercise both), so there is no other `low`/`medium` work to merge  [x] commit: 08444b1fb8

## Episodes
### Step 1 — commit 08444b1fb8, 2026-07-01T14:36Z [ctx=safe]
**What was done:** Added the `@rid = / IN` direct-fetch fast path to the plain-SELECT planner. A new `SQLWhereClause.extractAndRemoveRidInList` primitive detects an early-calculable `@rid IN` list and returns the same `RidExtractionResult` shape as its equality sibling. A new `SelectExecutionPlanner.handleClassAsTargetWithRidEquality` handler, tried first in `handleClassAsTarget`, extracts the RID predicate, gates on `isEarlyCalculated`, evaluates and dedups the candidate RIDs into a `LinkedHashSet`, membership-filters them against `resolveClassToCollectionIds`, then chains `FetchFromRidsStep(members)` with the WHERE remainder wired (both `whereClause` and `flattenedWhereClause` updated) — or `EmptyStep` when no candidate survives. Both branches return true, and the success branch chains no `FilterByClassStep` because the collection-id guard is already the class filter. A new `SelectExecutionPlannerRidEqualityTest` maps each of the 10 acceptance criteria to one test method.

**What was discovered:** The parser splits an `@rid IN [...]` value across two fields — a list literal lands in `rightMathExpression`, a bound param in `rightParam` — so the primitive wraps the value into an `SQLExpression` the way `SQLInCondition.resolveKeyFrom` does. `info.flattenedWhereClause` is already populated when `handleClassAsTarget` runs (`optimizeQuery` at line 257 precedes `handleFetchFromTarget` at line 267), so the one-OR-branch guard reads it directly. `EXPLAIN` renders the two fetch steps as "FETCH FROM RIDs" and "FETCH FROM CLASS", which the plan-shape assertions key on. The defensive `toRecordIdCandidate` switch arms for a String or null/other-type RID value stay uncovered — the Identifiable-RID tests never reach them; changed-code coverage is 93.6% line / 76.3% branch, above the 85/70 gate. A `@rid = '#c:p'` string-literal test would exercise the `case String` arm if Phase C's test-completeness review wants full arm coverage.

**What changed from the plan:** None. Implemented per D1–D5 and the Plan of Work, honoring technical-review precisions T1 (no `FilterByClassStep` on the success branch) and T2 (map an evaluated `SQLExpression`; no single-arg `toRecordId` overload).

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java` (modified)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlanner.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java` (new)

## Validation and Acceptance
The track is done when an early-calculable `@rid = / IN` under a class target fetches directly and every other query shape behaves exactly as before. Concretely: `EXPLAIN SELECT FROM <class> WHERE @rid = <literal>` shows `FetchFromRidsStep`, not `FetchFromClassExecutionStep` with a post-filter; the same for `@rid IN [<literals>]`. Result-set parity with the old scan holds for all optimized shapes — same rows, same cardinality (including single-row output for a duplicate-RID `IN` list). Class-membership semantics are preserved: a RID whose collection is outside the target class's polymorphic set yields empty, a subclass RID under a superclass target yields the record. Any predicate left beside the RID equality (e.g. `@rid = x AND status = 'A'`) applies exactly once. A non-early-calculable RID value (field ref, subquery) falls through to the class scan with no behavior change. All of the above are backed by tests in the plan-shape-plus-correctness class named in Interfaces and Dependencies.

**Acceptance criteria (Step 1 — each line maps to one test method in `SelectExecutionPlannerRidEqualityTest`):**

1. WHEN a class-target SELECT carries `@rid = <literal>`, the planner SHALL compile it to `FetchFromRidsStep`, not `FetchFromClassExecutionStep` (asserted via EXPLAIN).
2. WHEN a class-target SELECT carries `@rid IN [<literals>]`, the planner SHALL compile it to a single `FetchFromRidsStep` over the listed RIDs.
3. WHEN the `@rid` names a RID whose collection is outside the target class's polymorphic set, the query SHALL return an empty result.
4. WHEN the target is a superclass and the `@rid` names a subclass record, the query SHALL return that record.
5. WHEN an `@rid IN [...]` list contains duplicate RIDs, the query SHALL return each matching record exactly once (cardinality parity with the old scan).
6. WHEN an `@rid IN [...]` list mixes member and non-member RIDs, the query SHALL return only the member records.
7. WHEN an `@rid IN []` empty list is given, the query SHALL return an empty result, not a full-class scan.
8. WHEN a predicate accompanies the RID equality (`@rid = <literal> AND <other>`), the query SHALL apply the other predicate exactly once — neither dropped nor double-applied.
9. WHEN the `@rid` value is not early-calculable (a field reference or subquery), the planner SHALL fall through to the class scan with no behavior change.
10. WHEN `@rid = :param` binds an early-calculable parameter, the planner SHALL compile it to `FetchFromRidsStep`.

## Idempotence and Recovery
This is a plan-time-only change to the query planner. It writes no persistent state, changes no on-disk format, and needs no migration. The optimization is deterministic and idempotent: re-planning the same statement always yields the same plan, and re-executing a query re-fetches the same RIDs. Recovery from a bad implementation is a plain `git revert` of the single step commit — there is no data to roll back. The step is fully covered by the acceptance tests above, so a broken implementation is caught before commit rather than at runtime.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope (files this track edits or adds).**

- `SelectExecutionPlanner.java` — new `handleClassAsTargetWithRidEquality` handler, called first inside `handleClassAsTarget`.
- `SQLWhereClause.java` — new `extractAndRemoveRidInList` primitive, sibling to `extractAndRemoveRidEquality`.
- A new SELECT plan-shape-plus-correctness test class (exact name settled at Phase A).

**Out of scope (unchanged).** `MatchExecutionPlanner` (YTDB-629 already handles the MATCH path), the EXPAND push-down path, `FetchFromRidsStep` internals, and the existing RID-range narrowing (`extractRidRanges`/`isRidRange`).

**Relevant existing signatures the new code consumes.**

- `SQLWhereClause.extractAndRemoveRidEquality()` → `RidExtractionResult(SQLExpression ridExpression, SQLWhereClause remainingWhere)` — the new `extractAndRemoveRidInList` returns the same result shape.
- `resolveClassToCollectionIds(String className, plan)` → `IntSet` — the polymorphic collection-id set for the membership guard.
- `new FetchFromRidsStep(Collection<RecordIdInternal> rids, CommandContext ctx, boolean profilingEnabled)` — the direct-fetch source step.
- `SQLExpression.isEarlyCalculated(CommandContext)` — the plan-time-resolvable guard.
- `new EmptyStep(CommandContext, boolean)` — the empty-result step for the no-survivors branch.

**Dependencies.** No inter-track dependencies — this is a single-track change, so there is no `implementation-plan.md`. Reference accuracy: all anchors above were verified by grep + Read against this worktree (mcp-steroid/PSI was pointed at a different project); the symbols are uniquely named with no reflective dispatch, so grep is reliable.

## Invariants & Constraints
Each invariant below is backed by a test.

- **Class-membership preserved.** `SELECT FROM A WHERE @rid = <rid-of-B>` returns empty when B's collection is not in A's polymorphic collection set.
- **Subclass membership.** `SELECT FROM Super WHERE @rid = <rid-of-Sub>` returns the Sub record (subclass collections are in the superclass's polymorphic set).
- **Result-set parity.** The optimized shapes return the same rows at the same cardinality as the old scan-plus-filter, including single-row output when the `IN` list carries duplicate RIDs.
- **Remaining predicates applied exactly once.** A predicate left beside the RID equality is neither dropped nor double-applied.
- **Non-early-calc fall-through.** A field-ref or subquery RID value falls through to the class scan with no behavior change.
- **Plan shape.** An optimized query compiles to `FetchFromRidsStep`, not `FetchFromClassExecutionStep` — asserted via `EXPLAIN`.
- **Ordering untouched.** The handler leaves `info.orderApplied` false, so ORDER BY / SKIP / LIMIT / GROUP BY / DISTINCT still assemble downstream.

## Base commit
75e4d639fd1f4a6ad7ee21ccc84596e51a80b4ee
