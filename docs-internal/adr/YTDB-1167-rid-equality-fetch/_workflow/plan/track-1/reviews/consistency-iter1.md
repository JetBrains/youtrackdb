# Consistency review — track-1 (iter1)

- **Verdict**: PASS
- **Tier**: single-track, `design_gate=no` (ledger: `tracks=1`). No `implementation-plan.md`, no `design.md`.
- **Axes run**: Track ↔ Code only (design axes and PLAN ↔ CODE dropped per ledger + spawn instructions).
- **Tooling**: grep + Read against the worktree. mcp-steroid/PSI was pointed at a different project (main checkout, branch `gremlin-to-match-translator-design`) — a cwd mismatch, so PSI was unavailable. Reference-accuracy caveat: every symbol cited is uniquely named with no reflective or polymorphic dispatch (concrete package-private methods, records, and fields on named classes), so grep reference-accuracy is reliable here; no finding hinges on a "no other caller" or polymorphic-dispatch claim.
- **Findings**: 0.

## Findings

No findings. Every current-state anchor cited by track-1 was verified against the real files and matches. Target-state claims (the new `handleClassAsTargetWithRidEquality` handler and the new `extractAndRemoveRidInList` primitive, plus the "map each element the way `SQLRid.toRecordId` does" evaluation detail) were pre-screened out — their absence from current code is expected and not a finding.

## Evidence base

All certificates are Track ↔ Code reference certificates. Every current-state symbol and line anchor the spawn enumerated was checked.

#### Ref: `handleClassAsTarget` @ SelectExecutionPlanner.java:2099
- **Document claim** (D1, Context): `handleClassAsTarget` is the class-target dispatcher at `SelectExecutionPlanner.java:2099`; the new handler is added as the first attempt inside it.
- **Search performed**: grep `handleClassAsTarget\b`; Read 2099-2118.
- **Code location**: SelectExecutionPlanner.java:2099 (the multi-arg overload `handleClassAsTarget(plan, from, info, ctx, profilingEnabled)`).
- **Actual signature/role**: `private void handleClassAsTarget(SelectExecutionPlan plan, SQLFromClause from, QueryPlanningInfo info, CommandContext ctx, boolean profilingEnabled)`; derives `identifier` from `from.getItem().getIdentifier()` at 2105, then tries the two `WithIndexedFunction`/`WithIndex` handlers.
- **Verdict**: MATCHES.

#### Ref: `handleClassAsTargetWithIndexedFunction` @ SelectExecutionPlanner.java:2106
- **Document claim** (D1, Context): indexed-function handler called at line 2106, `WithRidEquality` mirrors its boolean pattern and is placed before it.
- **Search performed**: grep `handleClassAsTargetWithIndexedFunction`; Read 2106-2110, def at 2195.
- **Code location**: call site 2106-2107; `private boolean handleClassAsTargetWithIndexedFunction(...)` def at 2195.
- **Actual signature/role**: boolean-returning; caller returns after chaining `FilterByClassStep` when it returns true. Matches the "boolean-returning first-attempt" pattern D1 mirrors.
- **Verdict**: MATCHES.

#### Ref: `handleClassAsTargetWithIndex` @ SelectExecutionPlanner.java:2112
- **Document claim** (D1, Context): regular-index handler called at line 2112.
- **Search performed**: grep `handleClassAsTargetWithIndex\b`; Read 2112-2116.
- **Code location**: call site 2112-2113; a boolean overload at 2515 and a `List`-returning overload at 2674 exist, but the 2112 call is the boolean one used in the dispatch.
- **Actual signature/role**: boolean-returning, caller returns after `FilterByClassStep`.
- **Verdict**: MATCHES.

#### Ref: dispatch order `handleFetchFromTarget`(267) → `handleWhere`(271) → `tryPushDownFilterIntoExpand`(274)
- **Document claim** (D5): `handleClassAsTarget` runs inside `handleFetchFromTarget` at line 267, before `handleWhere` at 271, and `tryPushDownFilterIntoExpand` runs after at 274.
- **Search performed**: grep `handleFetchFromTarget|handleWhere|tryPushDownFilterIntoExpand`; Read call sites.
- **Code location**: `handleFetchFromTarget(...)` @ 267; `handleWhere(...)` @ 271; `tryPushDownFilterIntoExpand(...)` @ 274. `handleFetchFromTarget` def @ 1388 calls `handleClassAsTarget`; `tryPushDownFilterIntoExpand` def @ 3357.
- **Actual signature/role**: exact ordering as claimed — fetch source built first, WHERE filter chained second, EXPAND push-down last.
- **Verdict**: MATCHES.

#### Ref: `handleWhere` chains iff `info.whereClause != null` @ SelectExecutionPlanner.java:1990
- **Document claim** (D5): `handleWhere` chains the remainder `FilterStep` exactly once, iff `info.whereClause != null` at line 1990.
- **Search performed**: Read 1985-1998.
- **Code location**: `handleWhere` def @ 1985; guard `if (info.whereClause != null)` @ 1990, chaining one `FilterStep`.
- **Actual signature/role**: single conditional `FilterStep` chain gated on non-null `whereClause`. Matches — supports D5's "set remainder before handleWhere runs; null remainder → no filter step."
- **Verdict**: MATCHES.

#### Ref: index-handler dual-field null @ SelectExecutionPlanner.java:2357-2358
- **Document claim** (D5): the index handlers null both `info.whereClause` and `info.flattenedWhereClause` at lines 2357-2358; the new handler must do the same.
- **Search performed**: Read 2350-2363.
- **Code location**: `info.whereClause = null;` @ 2357, `info.flattenedWhereClause = null;` @ 2358, inside `handleClassAsTargetWithIndexedFunction` (returns true @ 2359).
- **Actual signature/role**: both WHERE fields nulled together after the index handler applies the condition. Exact line and semantic match.
- **Verdict**: MATCHES.

#### Ref: EXPAND push-down RID-equality template @ SelectExecutionPlanner.java:3400, 3421-3428
- **Document claim** (Context): line 3423 calls `remainingWhere.extractAndRemoveRidEquality()`, wraps in `RidFilterDescriptor.DirectRid` at line 3425; line 3400 resolves the class to a collection-id set via `resolveClassToCollectionIds`.
- **Search performed**: Read 3395-3434.
- **Code location**: `resolveClassToCollectionIds(...)` @ 3400; `extractAndRemoveRidEquality()` @ 3423; `new RidFilterDescriptor.DirectRid(...)` @ 3425.
- **Actual signature/role**: the "Step 3: Extract @rid = <expr>" block (3421-3428) matches; the class-to-collection-id resolution at 3400 matches. This is the template the track mirrors into the plain class-target path.
- **Verdict**: MATCHES.

#### Ref: `resolveClassToCollectionIds` @ SelectExecutionPlanner.java:3675 → `collectionIdsForClass` → `getPolymorphicCollectionIds()`
- **Document claim** (D2, Context): `resolveClassToCollectionIds` at line 3675 delegates to `TraversalPreFilterHelper.collectionIdsForClass`, which returns the class and all subclasses via `getPolymorphicCollectionIds()`.
- **Search performed**: grep `resolveClassToCollectionIds|collectionIdsForClass|getPolymorphicCollectionIds`; Read helper.
- **Code location**: `resolveClassToCollectionIds(...)` def @ 3675, delegating at 3686 to `TraversalPreFilterHelper.collectionIdsForClass(schemaClass)`; `collectionIdsForClass` @ TraversalPreFilterHelper.java:90 calls `clazz.getPolymorphicCollectionIds()` @ line 91.
- **Actual signature/role**: `@Nullable private static IntSet resolveClassToCollectionIds(...)` — matches the D2/Interfaces `IntSet` return claim and the polymorphic-set (class + subclasses) semantics.
- **Verdict**: MATCHES.

#### Ref: `extractAndRemoveRidEquality` / `RidExtractionResult` @ SQLWhereClause.java:1003
- **Document claim** (D4, Context, Interfaces): `extractAndRemoveRidEquality()` at line 1003 returns `RidExtractionResult(SQLExpression ridExpression, SQLWhereClause remainingWhere)`; `remainingWhere` is null when the RID equality was the sole predicate; handles single-OR-branch WHERE and both orderings.
- **Search performed**: grep; Read 986-1030.
- **Code location**: `RidExtractionResult` record @ 986-988 with fields `SQLExpression ridExpression, @Nullable SQLWhereClause remainingWhere`; `extractAndRemoveRidEquality()` @ 1003; single-OR-branch guard @ 1010; `remainingWhere` null when `subBlocks.size() == 1` @ 1022-1023.
- **Actual signature/role**: matches the Interfaces signature and the null-remainder-when-sole-predicate semantics exactly. (The `RidExtractionResult` record declaration itself is at 986; the track groups it under ":1003" alongside the method, which is a reasonable grouping — not a wrong anchor.)
- **Verdict**: MATCHES.

#### Ref: `tryExtractRidValue` left-side `@rid` check @ SQLWhereClause.java:1081
- **Document claim** (Plan-of-Work step 1): `extractAndRemoveRidInList` reuses the same left-side check `tryExtractRidValue` applies at line 1081.
- **Search performed**: grep; Read 1081-1097.
- **Code location**: `private static SQLExpression tryExtractRidValue(SQLExpression attrSide, SQLExpression valueSide)` @ 1081; verifies the attr side is a `@rid` record attribute via `recordAttribute.getName()` equalsIgnoreCase "@rid" @ 1092-1093.
- **Actual signature/role**: the left-side `@rid` recognition logic is exactly as described. (Reusing it for `SQLInCondition` is a target-state design detail — the method today takes two `SQLExpression` sides, not an `SQLInCondition` — pre-screened out.)
- **Verdict**: MATCHES.

#### Ref: `SQLBaseExpression.isEarlyCalculated` @ SQLBaseExpression.java:396 and `SQLExpression.isEarlyCalculated`
- **Document claim** (D3, Interfaces): gate on `ridExpression.isEarlyCalculated(ctx)` at `SQLBaseExpression.java:396`; Interfaces cites `SQLExpression.isEarlyCalculated(CommandContext)`.
- **Search performed**: grep in SQLBaseExpression.java and SQLExpression.java.
- **Code location**: `SQLBaseExpression.isEarlyCalculated(CommandContext ctx)` @ 396; `SQLExpression.isEarlyCalculated(CommandContext ctx)` @ SQLExpression.java:216, delegating to `mathExpression.isEarlyCalculated`.
- **Actual signature/role**: `ridExpression` is typed `SQLExpression` (per `RidExtractionResult`), so `SQLExpression.isEarlyCalculated(CommandContext)` (216) is the method actually invoked; it reaches `SQLBaseExpression.isEarlyCalculated` (396). Both cited anchors exist and are consistent — no mismatch.
- **Verdict**: MATCHES.

#### Ref: `FetchFromRidsStep` ctor(Collection), no class check, no dedup @ FetchFromRidsStep.java:34-47; `canBeCached()==false` @ :88
- **Document claim** (D2, D3, D4, Context, Interfaces): ctor takes `Collection<RecordIdInternal>`; `loadIterator`s with no class check and no dedup (:34-47); `canBeCached()` hard-false (:88).
- **Search performed**: Read whole file.
- **Code location**: `FetchFromRidsStep(Collection<RecordIdInternal> rids, CommandContext ctx, boolean profilingEnabled)` @ 34-38; `internalStart` returns `ExecutionStream.loadIterator(this.rids.iterator())` @ 46 with no class filter and no dedup; `canBeCached()` returns `false` @ 88-89.
- **Actual signature/role**: matches the Interfaces signature exactly; the no-class-check basis for the D2 membership guard and the no-dedup basis for the D4 IN-dedup requirement both hold; `canBeCached()==false` matches D3's non-cacheable-plan trade-off.
- **Verdict**: MATCHES.

#### Ref: `EmptyStep` ctor @ EmptyStep.java:19/25
- **Document claim** (D2, D4, Interfaces): `EmptyStep` is a real step (`EmptyStep.java:19`), ctor `new EmptyStep(CommandContext, boolean)`, used for the no-survivors / empty-IN branch.
- **Search performed**: Read whole file.
- **Code location**: `public class EmptyStep extends AbstractExecutionStep` @ 19; ctor `EmptyStep(CommandContext ctx, boolean profilingEnabled)` @ 25; produces `ExecutionStream.empty()` @ 36; `canBeCached()==false` @ 48.
- **Actual signature/role**: matches the Interfaces signature and the empty-result role.
- **Verdict**: MATCHES.

#### Ref: `SQLInCondition` fields `left` / `rightParam` / `rightMathExpression` / `rightStatement`
- **Document claim** (D4, Plan-of-Work step 1): `SQLInCondition` right side is early-calculable when it is a bound param (`rightParam`) or list-literal `SQLMathExpression` (`rightMathExpression`); a subquery (`rightStatement`) does not qualify; left side is `left`.
- **Search performed**: grep field decls; Read 26-34.
- **Code location**: `class SQLInCondition extends SQLBooleanExpression` @ 26; `protected SQLExpression left` @ 29; `protected SQLSelectStatement rightStatement` @ 31; `protected SQLInputParameter rightParam` @ 32; `protected SQLMathExpression rightMathExpression` @ 33.
- **Actual signature/role**: all four cited field names exist with the described types. The right-side discrimination (param/math-expr vs. subquery-statement) is exactly the three-way split the runtime `evaluate` uses @ 59-64.
- **Verdict**: MATCHES.

#### Ref: `SQLNotInCondition` is a distinct AST node type
- **Document claim** (D4, Plan-of-Work step 1): a negated `@rid IN` parses to `SQLNotInCondition`, a distinct node type, so it never reaches the `SQLInCondition` detector — "do not optimize the complement" is enforced by node-type discrimination.
- **Search performed**: grep `class SQLNotInCondition`.
- **Code location**: `public class SQLNotInCondition extends SQLBooleanExpression` @ SQLNotInCondition.java:18 — a separate class from `SQLInCondition` (SQLInCondition.java:26).
- **Actual signature/role**: distinct sibling class, not a flag on `SQLInCondition`. Supports the claim that a detector matching `SQLInCondition` structurally cannot match the NOT-IN form.
- **Verdict**: MATCHES.

#### Ref: `isRangeOperator()` false for `=`
- **Document claim** (Context): the scan can be narrowed by a RID range, but `isRidRange` gates on `SQLBinaryCompareOperator.isRangeOperator()`, true only for `<`, `<=`, `>`, `>=` and false for `=`.
- **Search performed**: grep `isRangeOperator` across parser; Read default @ SQLBinaryCompareOperator.java:322; checked SQLEqualsCompareOperator for an override.
- **Code location**: default `isRangeOperator()` returns `false` @ SQLBinaryCompareOperator.java:322-324; overridden `true` only in `SQLGtOperator` (54), `SQLLtOperator` (59), `SQLGeOperator` (74), `SQLLeOperator` (54); `SQLEqualsCompareOperator` has NO override, so `=` inherits `false`.
- **Actual signature/role**: exactly the four range operators return true; `=` returns the default false. Matches the Context claim that a RID equality is neither narrowed nor short-circuited today.
- **Verdict**: MATCHES.

#### Ref: `handleRidsAsTarget` / `SQLRid.toRecordId`
- **Document claim** (D3, Plan-of-Work step 2): the membership check and `RecordIdInternal` need the concrete RID at plan time, "as `handleRidsAsTarget` gets it via `toRecordId(ctx)`"; the new handler maps each element "the way `SQLRid.toRecordId` does."
- **Search performed**: grep `handleRidsAsTarget` / `toRecordId`; Read 1631-1638 and SQLRid.java:60-88.
- **Code location**: `handleRidsAsTarget(SelectExecutionPlan, List<SQLRid>, CommandContext, boolean)` @ 1631; builds `List<RecordIdInternal>` by calling `rid.toRecordId((Result) null, ctx)` @ 1635; chains `FetchFromRidsStep(actualRids, ctx, profilingEnabled)` @ 1637. `SQLRid.toRecordId(Result target, CommandContext ctx)` @ SQLRid.java:61 (also a `toRecordId(Identifiable, CommandContext)` overload @ 76).
- **Actual signature/role**: `handleRidsAsTarget` does convert `SQLRid`s to `RecordIdInternal` via `toRecordId` and chains `FetchFromRidsStep` — the pattern the track cites. Two nuances, both pre-screened as target-state, not current-state findings: (1) the actual method is the two-arg `toRecordId((Result) null, ctx)`, not a one-arg `toRecordId(ctx)` — D3's "via `toRecordId(ctx)`" is a loose paraphrase of the call, with D3's real line anchor being `SQLBaseExpression.java:396` (correct); (2) `SQLRid.toRecordId` is a method on an `SQLRid` receiver, whereas this track's candidates come from an `SQLExpression` (`ridExpression`) — the track's "the way `SQLRid.toRecordId` does" is explicitly an analogy for how the new handler will evaluate the expression, a target-state design detail. Neither is a current-state anchor error.
- **Verdict**: MATCHES (current-state anchors); the two paraphrase nuances are target-state and pre-screened out.
