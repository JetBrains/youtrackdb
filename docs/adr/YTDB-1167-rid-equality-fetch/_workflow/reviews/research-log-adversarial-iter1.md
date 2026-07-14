---
kind: adversarial-review
scope: research-log
target: docs/adr/YTDB-1167-rid-equality-fetch/_workflow/research-log.md
iteration: 1
verdict: changes-requested
findings: 7
blockers: 0
should_fix: 5
suggestions: 2
matched_categories: [Performance hot path, query-result-correctness]
tooling: grep+Read (mcp-steroid unavailable — different project open; symbols in play are uniquely named with no reflective dispatch, so grep reference-accuracy is reliable except where noted)
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    loc: research-log.md D2
    cert: "Challenge: Decision D2"
    basis: "D2 membership guard is sound (polymorphic via getPolymorphicCollectionIds) but the non-member emission and dual-field WHERE sync are under-specified"
  - id: A2
    sev: should-fix
    anchor: "### A2 "
    loc: research-log.md Q2/S6
    cert: "Assumption test: remaining-WHERE wiring"
    basis: "Load-bearing open question: class-target path runs BEFORE handleWhere, so both info.whereClause and info.flattenedWhereClause must be mutated in sync"
  - id: A3
    sev: should-fix
    anchor: "### A3 "
    loc: research-log.md D3
    cert: "Challenge: Decision D3"
    basis: "Bound-param path loses plan caching: FetchFromRidsStep.canBeCached()==false vs today's cacheable class-scan plan"
  - id: A4
    sev: should-fix
    anchor: "### A4 "
    loc: research-log.md D1
    cert: "Assumption test: plan-time cost on non-optimized queries"
    basis: "Every class-target query now pays RID-detection cost before falling through; the log never bounds this"
  - id: A5
    sev: should-fix
    anchor: "### A5 "
    loc: research-log.md D4/S7
    cert: "Challenge: Decision D4"
    basis: "IN edge cases (empty list, duplicate/mixed-collection RIDs) and the at-most-one-RID-predicate post-filter correctness are asserted, not demonstrated"
  - id: A6
    sev: suggestion
    anchor: "### A6 "
    loc: research-log.md Q3
    cert: "Assumption test: ORDER BY / GROUP BY interaction"
    basis: "Open question; architecture decouples clauses (handleProjectionsBlock runs after fetch) so short-circuit is safe, but GROUP BY/aggregation over a direct fetch wants an explicit note"
  - id: A7
    sev: suggestion
    anchor: "### A7 "
    loc: research-log.md Q4
    cert: "Assumption test: design_gate=no"
    basis: "design_gate=no is defensible; the contained, template-mirroring change fits a track-file-only tier"
evidence_base:
  - "Challenge: Decision D1 — hook placement first in handleClassAsTarget"
  - "Challenge: Decision D2 — collection-id membership guard"
  - "Challenge: Decision D3 — early-calculable-only gating"
  - "Challenge: Decision D4 — IN support + single-common-collection + at-most-one-RID-predicate"
  - "Assumption test: remaining-WHERE wiring (Q2/S6)"
  - "Assumption test: plan-time cost on non-optimized queries (D1)"
  - "Assumption test: ORDER BY / GROUP BY interaction (Q3)"
  - "Assumption test: design_gate=no (Q4)"
---

# Adversarial review — research log YTDB-1167 (iteration 1)

BLUF: The four decisions are directionally right and every mechanism they lean on exists in the code exactly as the log claims — `EmptyStep`, `FetchFromRidsStep(Collection)`, `resolveClassToCollectionIds` → `getPolymorphicCollectionIds`, `extractAndRemoveRidEquality`, the EXPAND push-down template. No blocker. But two load-bearing open questions (Q2, Q3) still gate, and three decisions carry rationale gaps a track author would otherwise discover the hard way: the class-target path mutates the WHERE at a *different plan stage* than the EXPAND template (so it must keep two fields in sync, not one), the bound-parameter path silently forfeits plan caching, and the D4 IN edge cases are asserted rather than shown. Five should-fix, two suggestion.

## Findings

### A1 [should-fix]
**Certificate**: Challenge: Decision D2 — collection-id membership guard
**Target**: Decision D2 (class-membership guard via `resolveClassToCollectionIds` ∩ `rid.getCollectionId()`)
**Challenge**: The guard is sound — I confirmed it, so this is a rationale-completeness gap, not a defect. `resolveClassToCollectionIds` (SelectExecutionPlanner.java:3675) delegates to `TraversalPreFilterHelper.collectionIdsForClass` (TraversalPreFilterHelper.java:90), which uses `clazz.getPolymorphicCollectionIds()` — the class *and all subclasses*. So `SELECT FROM Vehicle WHERE @rid = <rid-of-Car>` with `Car extends Vehicle` correctly returns the Car record, and `SELECT FROM Car WHERE @rid = <rid-of-Vehicle>` correctly returns empty. The log states the guard but never spells out the polymorphic direction, which is the exact case a reviewer worries about. Two mechanics the log leaves implicit and a track author can get wrong: (1) the non-member branch must emit `EmptyStep` *and still return true* so the caller short-circuits past the class-scan fallthrough (handleClassAsTarget:2135-2147) — if it returns false the query silently falls through to a full scan and the "empty result" guarantee is lost; (2) when the RID is a non-member and a residual WHERE survives (`@rid = <B> AND name = 'x'`), the residual FilterStep chains over the empty stream — harmless, but only if the handler still consumes `info.whereClause` correctly (see A2).
**Evidence**: TraversalPreFilterHelper.java:90-97 (`getPolymorphicCollectionIds`); EmptyStep.java:19 (real step, used by ForEachStep.java:53 and the MATCH planner); FetchFromRidsStep.java:34-47 (no class check — confirms guard is mandatory); RID `getCollectionId()` exists (RidSet.java:65, ExpandStep.java:168). Each YouTrackDB collection belongs to exactly one class, so the collection-id set is an exact membership test, not an approximation.
**Proposed fix**: In D2, add one line that the guard resolves *polymorphic* collection ids (subclass RIDs are members of a superclass query), and one line that the non-member branch emits `EmptyStep` and returns true (short-circuits the scan). Fold the "member" vs "non-member" emission into the D4 unified path description so the track author sees the full contract in one place.

### A2 [should-fix]
**Certificate**: Assumption test: remaining-WHERE wiring (Q2/S6)
**Target**: Open Question Q2 (remaining-WHERE wiring) — bears on load-bearing D1/D2, so per the research-log gate rule an unresolved question here is at least should-fix
**Challenge**: Q2 treats the remaining-WHERE wiring as "confirm the exact step assembly," modeled on the EXPAND push-down path (S1/S6). But the EXPAND path and the new class-target path consume the WHERE at *different plan stages*, and that difference is the real trap. The EXPAND path (SelectExecutionPlanner.java:3421-3484) runs in `tryPushDownFilterIntoExpand`, which fires at line 274 — *after* `handleWhere` (line 271) has already chained a `FilterStep`; it surgically removes or rewrites that already-built FilterStep (3469-3482). The new handler runs inside `handleClassAsTarget` (called at line 1411), which is *before* `handleWhere`. So the correct mechanism is the opposite of the EXPAND template: the handler must set `info.whereClause` to the extracted remainder and let `handleWhere` (line 1990) chain the FilterStep — exactly how the index handlers signal a satisfied WHERE by nulling it (lines 2357, 2529). The load-bearing subtlety the log misses entirely: there are **two** WHERE fields kept in sync — `info.whereClause` (the AST, read by `handleWhere:1990`) and `info.flattenedWhereClause` (the AND-block list). The index handlers null *both* (2357-2358, 2529-2530). If the new handler mutates only `info.whereClause` and leaves `info.flattenedWhereClause` stale, no *current* downstream reader is hit (the only post-fetch readers are the index handlers the short-circuit skips, and `extractRidRanges` already ran at line 1406 and returned empty for `=`), so it works today — but it is a latent inconsistency that a future reader of the flattened clause would trip on, and it violates the invariant the index handlers uphold. A track author copying the EXPAND template verbatim will build a FilterStep-removal that has nothing to remove.
**Evidence**: Plan assembly order at SelectExecutionPlanner.java:267-276 (`handleFetchFromTarget` → `handleWhere` → `tryPushDownFilterIntoExpand`); `handleWhere` at 1985-1998 chains a FilterStep iff `info.whereClause != null`; index handlers null both fields at 2357-2358 and 2529-2530; EXPAND push-down FilterStep surgery at 3469-3482; `extractRidRanges` on `info.flattenedWhereClause` at 1406-1408 runs before the handler and yields empty for `=` because `isRidRange` gates on `isRangeOperator()` (1495-1498).
**Proposed fix**: Resolve Q2 into the Decision Log before the gate clears: state that the handler sets `info.whereClause` to the extraction remainder and invalidates `info.flattenedWhereClause` (both fields, mirroring the index handlers at 2357-2358), and that `handleWhere` then applies the remainder exactly once — do *not* model it on the EXPAND FilterStep-removal, which fires at a later stage. Add a regression test for `@rid = <literal> AND <other-predicate>` asserting the other predicate is applied exactly once (not zero, not twice).

### A3 [should-fix]
**Certificate**: Challenge: Decision D3 — early-calculable-only gating
**Target**: Decision D3 (optimize only when the RID value is early-calculable)
**Challenge**: D3 admits bound parameters into the optimization (`isEarlyCalculated` is true for `inputParam` — confirmed at SQLBaseExpression.java:397). The performance consequence, which the log never states, is a **plan-cache regression on the parameterized path**. Today `SELECT FROM Probe WHERE @rid = :rid` compiles to `FetchFromClassExecutionStep` (cacheable, FetchFromClassExecutionStep.java:200 → true) + `FilterStep` (cacheable, FilterStep.java:115 → true), so the whole plan caches once (createExecutionPlan:284-289) and every subsequent `:rid` value reuses it — planning happens once. After the change the same query compiles to `FetchFromRidsStep`, whose `canBeCached()` returns **false** (FetchFromRidsStep.java:87-90), so `SelectExecutionPlan.canBeCached()` returns false (SelectExecutionPlan.java:280-287) and the plan is re-planned on *every* execution. On a large class the O(1) fetch dwarfs the re-planning cost, so this is a net win — but for a small class queried at high frequency by parameter, trading a cached O(class) scan of a tiny class for a per-call re-plan could regress. The decision survives (the intended workload is large classes), but the rationale must acknowledge the trade rather than leave it for a benchmark to surface.
**Evidence**: SQLBaseExpression.java:396-401 (`inputParam` → early-calc true); input params available at plan time — `ctx.setInputParameters(params)` at SQLSelectStatement.java:303/323 precedes `createExecutionPlan` at 306/326; FetchFromRidsStep.java:87-90 (`canBeCached` → false); FetchFromClassExecutionStep.java:200 + FilterStep.java:115 (both cacheable today); SelectExecutionPlan.java:280-287 (plan cacheable iff all steps cacheable); cache populate gate at SelectExecutionPlanner.java:284-289.
**Proposed fix**: In D3, add a sentence noting that the parameterized case forgoes plan caching (FetchFromRidsStep is non-cacheable) so the optimization trades a one-time cached scan-plan for a per-execution re-plan plus an O(1) fetch — a clear win on large classes, the target workload. If small-class high-frequency-by-param is a concern, note it as an accepted trade or a follow-up.

### A4 [should-fix]
**Certificate**: Assumption test: plan-time cost on non-optimized queries (D1)
**Target**: Decision D1 (hook `handleClassAsTargetWithRidEquality` first in `handleClassAsTarget`)
**Challenge**: D1 places the new detector first in `handleClassAsTarget`, so *every* class-target query — the overwhelming majority that have no `@rid` equality — now pays the detection cost before falling through to the index/scan handlers. The cost is: constructing the extraction (a flatten inspection + the `extractAndRemoveRidEquality` / IN-detector traversal of the flattened WHERE, unwrapping single-element Or/And wrappers per term at SQLWhereClause.java:1019-1074). This is cheap relative to a scan, but D1's rationale ("minimal blast radius") speaks only to code cohesion, not to the added per-plan cost on the non-optimized path. For the performance lens the operative question is whether the detector short-circuits fast on the common shapes: a query with no WHERE (`info.whereClause == null`) or a multi-OR-branch WHERE must bail before any deep traversal. `extractAndRemoveRidEquality` already returns null immediately when `baseExpression == null` (1004) and when the OR block has more than one sub-block (1010-1012), so the fast paths exist — but the log never states that the handler checks the cheap guard (whereClause present, single OR branch) before the per-term traversal, nor that plan caching amortizes even this across repeated literal queries.
**Evidence**: SQLWhereClause.java:1004 (null baseExpression → null), 1009-1017 (multi-OR-branch → null), 1019-1074 (per-term unwrap loop only reached for single-branch WHERE). Detection runs once per plan; for cacheable literal-free-of-`@rid` queries the plan is cached so detection does not repeat.
**Proposed fix**: In D1, add that the detector cheap-guards on `info.whereClause == null` and multi-OR-branch WHERE (both O(1) via the existing early returns) before any per-term traversal, so the non-optimized path pays only a constant check. Optionally note that repeated identical queries hit the plan cache, so detection is a one-time cost per distinct statement.

### A5 [should-fix]
**Certificate**: Challenge: Decision D4 — IN support + single-common-collection + at-most-one-RID-predicate
**Target**: Decision D4 (support `@rid IN [...]`, one common collection, at most one RID predicate)
**Challenge**: D4 and S7 assert the IN edge cases are handled but do not demonstrate the failure-prone ones. (1) **Empty list** `@rid IN []`: the candidate set is empty, so after membership filtering the path must emit `EmptyStep` (correct — empty in, empty out) rather than `FetchFromRidsStep([])` degenerating oddly or falling through to a scan that returns everything. The log's unified "empty result if none survive" covers this only if the empty-list case reaches the membership filter at all. (2) **Duplicate RIDs** `@rid IN [#10:1, #10:1]`: `FetchFromRidsStep` iterates the collection with no dedup (FetchFromRidsStep.java:46 `loadIterator(rids.iterator())`), so the record is returned twice — but the class-scan-plus-filter it replaces returns it once. This is a **result-cardinality change** the log does not mention. (3) **Mixed-collection list** `@rid IN [<member>, <non-member>]`: the membership filter must drop only the non-member and fetch the member — the log says "filter by the D2 class-membership guard," which is correct, but the mixed case is exactly where a naive "all-or-nothing" implementation errs. (4) The **at-most-one-RID-predicate** rule for `@rid = x AND @rid IN [...]`: the log claims leaving the second predicate as a post-filter "yields the correct result." I confirmed the mechanism works — the extractor takes the first RID predicate, the second stays in the remaining WHERE, and `handleWhere` chains a FilterStep — but the log should state *which* predicate wins extraction (the AND-term iteration order at SQLWhereClause.java:1019 takes the first matching term), because for `@rid = x AND @rid IN [x, y]` extracting the `=` fetches {x} then the IN post-filter keeps x (correct), whereas extracting the IN fetches {x, y} then the `=` post-filter keeps x (also correct but a different intermediate cardinality). Both converge, so the rule survives — but "correct result" deserves the one-line trace.
**Evidence**: FetchFromRidsStep.java:44-46 (iterates rids with no dedup — duplicate RIDs surface twice); SQLInCondition.java:29-33 (`left`, `rightStatement`/`rightParam`/`rightMathExpression` — S7's shape is accurate); SQLInCondition list-literal right side is `rightMathExpression` whose `execute` (line 64) returns the evaluated list — the IN detector must map each element to `RecordIdInternal` the way `SQLRid.toRecordId` does (SQLRid.java:61-72, handles Identifiable and String); SQLNotInCondition.java exists as a distinct class, so S7's "must not optimize negated IN" is enforceable by node-type discrimination. Reference-accuracy caveat: `SQLInCondition.left` shape (that `left.mathExpression instanceof SQLBaseExpression` so `tryExtractRidValue` reuse applies) I inferred from the parser structure, not a PSI type check — the track author should confirm the bare-`@rid` reuse compiles against `SQLInCondition.getLeft()`.
**Proposed fix**: In D4/S7, add explicit handling notes for: empty IN list → EmptyStep; whether duplicate RIDs are deduped to match class-scan cardinality (decide and state — likely dedup the candidate collection); mixed member/non-member list → fetch members only. Add the one-line trace showing why `@rid = x AND @rid IN [...]` converges regardless of which predicate extracts. Cover all four in the track's test list.

### A6 [suggestion]
**Certificate**: Assumption test: ORDER BY / GROUP BY interaction (Q3)
**Target**: Open Question Q3 (interaction with ORDER BY / SKIP / LIMIT / GROUP BY)
**Challenge**: Q3 asks whether any downstream clause path is bypassed when short-circuiting to the direct fetch. The architecture answers it cleanly and safely, which downgrades this from the should-fix an unresolved load-bearing question would otherwise earn: `handleClassAsTarget` only builds the *source* step; ORDER BY / SKIP / LIMIT / GROUP BY / DISTINCT are all assembled later by `handleProjectionsBlock` (line 276), which runs unconditionally after the fetch regardless of which fetch handler won. So routing to `FetchFromRidsStep` bypasses nothing — the same tail steps chain on. Two residual points worth a note: (1) the `info.orderApplied` flag — the class-scan path sets it when it pushes `ORDER BY @rid` into the fetch (line 2144-2146); the new handler does not push ordering, so it must leave `info.orderApplied` false and let `handleOrderBy` sort the (1-or-few-row) result — a no-op for a single RID but correct for a multi-RID IN with ORDER BY. (2) GROUP BY / aggregation over a direct fetch (`SELECT count(*) FROM C WHERE @rid = x`) flows through Path B (line 349) unchanged — fine, but worth confirming the direct fetch does not accidentally trip the COUNT hardwired optimization at line 582, which runs earlier (line 260) and is guarded to bail on `orderBy`/`groupBy`/multi-block WHERE.
**Evidence**: handleProjectionsBlock at 320-374 (three tail paths, all after fetch); handleOrderBy honors `info.orderApplied` (2004-2005); COUNT hardwired opt at 573-620 runs at line 260 before fetch and bails on non-single-equality WHERE (582-598); the RID-equality WHERE is a single equality but on `@rid` (not a base identifier property with an index), so 592-607 will not match it.
**Proposed fix**: Resolve Q3 into the log with one line: the fetch handler builds only the source, and `handleProjectionsBlock` assembles ORDER BY/SKIP/LIMIT/GROUP BY afterward regardless, so nothing is bypassed; the handler leaves `info.orderApplied` false. This is a confirmation, not a redesign.

### A7 [suggestion]
**Certificate**: Assumption test: design_gate=no (Q4)
**Target**: Open Question Q4 (design gate)
**Challenge**: Q4 leans `design_gate=no`. That is defensible: the change is contained to one method family in one file, mirrors the EXPAND push-down template (S1) and the MATCH RID logic (S5), and every mechanism it needs already exists. The one reason to reconsider is that A2 shows the change is *not* a verbatim template copy — it consumes the WHERE at a different plan stage and must keep two fields in sync — so the track file's Decision Log must carry that nuance precisely, or the "mirrors an existing template" justification for skipping a design doc becomes a trap. If A2 is resolved into the log with the two-field-sync detail, `design_gate=no` holds.
**Evidence**: Single-file blast radius (all edits in SelectExecutionPlanner.java plus one WHERE-parsing primitive in SQLWhereClause.java); template precedents at SelectExecutionPlanner.java:3421-3484 (EXPAND) and MatchExecutionPlanner.java:3286 (MATCH). The A2 stage-mismatch is the sole wrinkle against "pure template reuse."
**Proposed fix**: Keep `design_gate=no`, contingent on A2's two-field WHERE-sync detail landing in the track file's Decision Log. If the reviewer of the track file cannot reconstruct the wiring from the log alone, escalate to a design doc.

## Evidence base

#### Challenge: Decision D1 — hook `handleClassAsTargetWithRidEquality` first in `handleClassAsTarget`
- **Chosen approach**: New boolean-returning handler tried first in `handleClassAsTarget` (SelectExecutionPlanner.java:2099-2148), before the indexed-function/index handlers, mirroring the `handleClassAsTargetWith*` pattern.
- **Best rejected alternative**: Hook at the `buildFetchSteps` dispatch site beside `extractRidRanges` (line 1406), where RID-range narrowing already lives.
- **Counterargument trace**:
  1. Placing the detector first means every class-target query pays the detection cost before fallthrough (SelectExecutionPlanner.java:2106 onward runs after the new first attempt).
  2. The dispatch-site alternative would co-locate RID-equality with RID-range handling (both are `@rid`-shaped), arguably more discoverable — but it splits RID handling away from the class-target handler family, which the log rejects for cohesion (D1 alt b).
  3. Difference: cohesion vs discoverability; both pay the same per-plan detection cost. The chosen approach is fine *if* the detector cheap-guards fast (see A4).
- **Codebase evidence**: handleClassAsTarget:2099-2148; extractRidRanges/isRidRange:1474-1498 (the neighbor the alternative would sit beside).
- **Survival test**: YES — cohesion argument holds; the residual concern is the unbounded-sounding plan-time cost, addressed as A4 (should-fix rationale gap, not a placement change).

#### Challenge: Decision D2 — collection-id membership guard
- **Chosen approach**: Resolve target class to its polymorphic collection-id set; member RID → `FetchFromRidsStep`, non-member → empty result.
- **Best rejected alternative**: Chain `FilterByClassStep` after a bare fetch (log's rejected alt).
- **Counterargument trace**:
  1. The chosen guard checks membership at plan time (`getCollectionId` ∈ set) and short-circuits; the FilterByClassStep alternative loads the record then filters it — strictly more work and no plan-time empty short-circuit.
  2. The guard is exact, not approximate: each collection belongs to one class, and `getPolymorphicCollectionIds` includes subclasses (TraversalPreFilterHelper.java:90-97).
  3. Difference: the chosen approach is both cheaper and correct for polymorphism; the alternative is redundant.
- **Codebase evidence**: TraversalPreFilterHelper.java:90-97; EmptyStep.java:19; FetchFromRidsStep.java:34-47 (no class check).
- **Survival test**: YES — but rationale under-specifies the polymorphic direction and the non-member emission contract (A1).

#### Challenge: Decision D3 — early-calculable-only gating
- **Chosen approach**: Gate on `ridExpression.isEarlyCalculated(ctx)`; literals and bound params qualify.
- **Best rejected alternative**: Defer RID evaluation to a runtime step (log's rejected alt).
- **Counterargument trace**:
  1. Chosen: resolves the RID at plan time so membership can be checked and `FetchFromRidsStep` populated — but a plan containing FetchFromRidsStep is non-cacheable, so the param path re-plans per execution (FetchFromRidsStep.java:87-90).
  2. Rejected runtime alternative: cannot check membership at plan time, adds machinery for no gain (log is right).
  3. Difference: the chosen approach is correct; the unstated cost is the lost plan cache on the param path vs today's cacheable scan plan (A3).
- **Codebase evidence**: SQLBaseExpression.java:396-401; params-at-plan-time SQLSelectStatement.java:303/306; cache gating SelectExecutionPlan.java:280-287, SelectExecutionPlanner.java:284-289.
- **Survival test**: WEAK — decision survives but rationale must state the plan-cache trade (A3).

#### Challenge: Decision D4 — IN support + single-common-collection + at-most-one-RID-predicate
- **Chosen approach**: New `extractAndRemoveRidInList` primitive; `=` and IN feed one candidate-RID → membership-filter → single `FetchFromRidsStep` path; at most one RID predicate extracted, a second stays as post-filter.
- **Best rejected alternative**: separate `ridEquality`/`ridInList` fields intersected at plan time (log's rejected alt).
- **Counterargument trace**:
  1. Chosen: one common `List<RecordIdInternal>`, `=` as the degenerate singleton; the rare `@rid = x AND @rid IN [...]` leaves the second predicate as a post-filter.
  2. Rejected intersection: only needed to precisely optimize the rare (often contradictory) double-RID case; the post-filter already yields the correct result — I confirmed both extraction orders converge.
  3. Difference: the chosen approach is simpler and correct; the gap is that empty-list, duplicate-RID (cardinality!), and mixed-collection cases are asserted not shown (A5).
- **Codebase evidence**: FetchFromRidsStep.java:44-46 (no dedup); SQLInCondition.java:29-33, 57-67; SQLNotInCondition.java exists; SQLRid.toRecordId:61-72.
- **Survival test**: YES — unified path is the right call; rationale needs the edge-case traces (A5).

#### Assumption test: remaining-WHERE wiring (Q2/S6)
- **Claim**: The remaining WHERE can be wired "as the EXPAND path does" so predicates apply exactly once.
- **Stress scenario**: `SELECT FROM C WHERE @rid = #10:1 AND name = 'x'` — the `name` predicate must apply exactly once.
- **Code evidence**: The EXPAND template (SelectExecutionPlanner.java:3469-3482) removes an already-built FilterStep because it runs at line 274, *after* handleWhere (271). The class-target handler runs at line 1411, *before* handleWhere. Correct mechanism: set `info.whereClause` to the remainder + null `info.flattenedWhereClause` (like index handlers 2357-2358), let handleWhere:1990 chain the FilterStep. Modeling on the EXPAND FilterStep-removal would find no FilterStep to remove.
- **Verdict**: FRAGILE — works only if the author uses the index-handler mechanism (mutate both WHERE fields), not the EXPAND FilterStep-surgery mechanism. Q2 must resolve this into the log (A2).

#### Assumption test: plan-time cost on non-optimized queries (D1)
- **Claim (implicit)**: Placing the detector first is low-cost for the non-optimized majority.
- **Stress scenario**: A high-frequency `SELECT FROM C WHERE name = :n` (no `@rid`) now runs the detector first on every plan build.
- **Code evidence**: `extractAndRemoveRidEquality` early-returns on null baseExpression (SQLWhereClause.java:1004) and multi-OR-branch (1009-1012); the per-term unwrap loop (1019-1074) is reached only for single-branch WHERE. Plan caching amortizes across identical statements. So the cost is a constant guard on the common path — but the log never states the cheap-guard ordering.
- **Verdict**: HOLDS (barely) — fast paths exist; document them (A4).

#### Assumption test: ORDER BY / GROUP BY interaction (Q3)
- **Claim**: Short-circuiting to the direct fetch bypasses no downstream clause.
- **Stress scenario**: `SELECT FROM C WHERE @rid IN [#10:1,#10:2] ORDER BY name`; `SELECT count(*) FROM C WHERE @rid = #10:1`.
- **Code evidence**: `handleProjectionsBlock` (line 276) assembles ORDER BY/SKIP/LIMIT/GROUP BY after the fetch regardless of handler (320-374); `info.orderApplied` stays false for the new handler so `handleOrderBy` sorts normally (2004-2005); the COUNT hardwired opt (573-620) runs before fetch and will not match an `@rid` equality (592-607 require a base-identifier property).
- **Verdict**: HOLDS — architecture decouples the tail; only a confirmation note is owed (A6).

#### Assumption test: design_gate=no (Q4)
- **Claim**: The change is contained and template-mirroring, so no design doc is needed.
- **Stress scenario**: A track reviewer must reconstruct the WHERE-wiring from the track file's Decision Log alone.
- **Code evidence**: Single-file blast radius; but A2 shows it is not a verbatim template copy (different plan stage, two-field sync). The log must carry that nuance for `design_gate=no` to be safe.
- **Verdict**: HOLDS — contingent on A2's detail landing in the track file (A7).
