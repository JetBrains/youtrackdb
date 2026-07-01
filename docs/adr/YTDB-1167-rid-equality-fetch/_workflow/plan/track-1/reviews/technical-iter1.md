<!--REVIEW-MANIFEST
role: reviewer-technical
phase: 3A
track: "Track 1: Direct RID fetch for SELECT FROM <class> WHERE @rid = / IN"
iteration: 1
verdict: PASS
tooling: grep+Read (PSI unavailable — IDE open on a different branch; reference-accuracy caveat applies to every symbol-search premise below)
counts:
  findings: 2
  blocker: 0
  should-fix: 0
  suggestion: 2
  skip: 0
index:
  - id: T1
    sev: suggestion
    anchor: "Caller wiring: success branch must NOT append FilterByClassStep"
    loc: "track-1.md D1/D2/Plan-of-Work step 2; SelectExecutionPlanner.java:2106-2116"
    cert: "Integration: handleClassAsTarget dispatch + FilterByClassStep pattern"
    basis: read
  - id: T2
    sev: suggestion
    anchor: "toRecordId(ctx) citation is the two-arg overload; handler maps an SQLExpression, not an SQLRid"
    loc: "track-1.md D3 + Context/Orientation; SelectExecutionPlanner.java:1631-1637; SQLRid.java:60-73"
    cert: "Premise: handleRidsAsTarget RID evaluation template"
    basis: read
evidence_base:
  premises: 19
  edge_cases: 8
  integrations: 3
  confirmed: 29
  planned_by_track: 2
  partial: 1
-->

## Findings

### T1 [suggestion]
**Certificate**: Integration: handleClassAsTarget dispatch + FilterByClassStep pattern
**Location**: track-1.md — D1, D2, and Plan of Work step 2; against `SelectExecutionPlanner.java:2106-2116`
**Issue**: Every existing `handleClassAsTargetWith*` success branch in `handleClassAsTarget` follows the shape `if (handler(...)) { plan.chain(new FilterByClassStep(identifier, ctx, profilingEnabled)); return; }` (lines 2106-2110 for the indexed-function handler, 2112-2116 for the index handler). If `handleClassAsTargetWithRidEquality` is wired in by copying that neighbour verbatim, it would append a redundant `FilterByClassStep` after the direct `FetchFromRidsStep`. That would be a correctness bug in the empty-`IN []` / all-non-member case (an `EmptyStep` followed by a `FilterByClassStep` is harmless but pointless) and, more importantly, would double the class-membership work the D2 collection-id guard already performs — and `FilterByClassStep` filters a *loaded* record, exactly the loaded-record filtering D2 explicitly rejects. The track's intent is unambiguous at the design level (D2 rejects `FilterByClassStep`; `handleRidsAsTarget` at 1631-1637 chains only `FetchFromRidsStep` with no class filter), but the exact caller snippet is not shown in the track, and the surrounding pattern actively invites the wrong wiring.
**Proposed fix**: In decomposition, make the caller wiring explicit in the handler step: the success branch is `if (handleClassAsTargetWithRidEquality(plan, identifier, info, ctx, profilingEnabled)) { return; }` with **no** `FilterByClassStep` chained (the D2 collection-id membership guard is the class filter, applied at plan time before the fetch). Add a one-line comment at the call site noting why this branch differs from its two neighbours. No plan-level change; this is a decomposition-precision note.

### T2 [suggestion]
**Certificate**: Premise: handleRidsAsTarget RID evaluation template
**Location**: track-1.md — D3 ("as `handleRidsAsTarget` gets it via `toRecordId(ctx)`") and Context and Orientation ("`handleRidsAsTarget` evaluating SQLRid via toRecordId(ctx)"); against `SelectExecutionPlanner.java:1631-1637` and `SQLRid.java:60-73`
**Issue**: Two paraphrase-level imprecisions in the RID-evaluation citations, neither load-bearing. (1) There is no single-argument `SQLRid.toRecordId(ctx)` overload — the real signatures are `toRecordId(Result, ctx)` (SQLRid.java:60) and `toRecordId(Identifiable, ctx)` (:76), and `handleRidsAsTarget` calls `rid.toRecordId((Result) null, ctx)` (:1635). (2) This track's handler does *not* evaluate a parsed `SQLRid` — it evaluates the extracted `ridExpression` (an `SQLExpression`) via `SQLExpression.execute(...)` and then maps the result the way `SQLRid.toRecordId`'s switch does (`Identifiable → getIdentity()`, `String → RecordIdInternal.fromString`, else null; SQLRid.java:66-72). Surprise S4 states this correctly ("its value comes from evaluating the extracted `ridExpression` rather than a parsed `SQLRid` target"), so the substance is right; only the D3 / Context wording rounds it off to "toRecordId(ctx)". The mechanism the track describes is faithful and feasible.
**Proposed fix**: Optional wording tightening at decomposition time: in D3 and Context and Orientation, replace "via `toRecordId(ctx)`" with "by evaluating the RID expression and mapping the result to a `RecordIdInternal` the way `SQLRid.toRecordId`'s switch does (Identifiable → identity, String → fromString)". No design change.

## Evidence base

#### Premise: `handleClassAsTarget` at SelectExecutionPlanner:2099, boolean `handleClassAsTargetWith*` pattern at 2106/2112
- **Track claim**: "Add a boolean-returning `handleClassAsTargetWithRidEquality(...)` as the first attempt in `handleClassAsTarget` (SelectExecutionPlanner.java:2099), before `handleClassAsTargetWithIndexedFunction`. ... Mirrors the existing `handleClassAsTargetWith*` boolean pattern already at lines 2106/2112."
- **Search performed**: Read SelectExecutionPlanner.java:2099-2148 (grep+Read; PSI unavailable).
- **Code location**: SelectExecutionPlanner.java:2099 (method decl), 2106 (`handleClassAsTargetWithIndexedFunction`), 2112 (`handleClassAsTargetWithIndex`), 2118 (`handleClassWithIndexForSortOnly`), 2134-2147 (scan fallthrough).
- **Actual behavior**: `handleClassAsTarget` is a `private void` that tries the indexed-function handler first (`if (handleClassAsTargetWithIndexedFunction(...)) { plan.chain(new FilterByClassStep(...)); return; }`), then the index handler, then the sort-only handler, then falls through to build `FetchFromClassExecutionStep`. Each `handleClassAsTargetWith*` returns boolean and the caller returns on true.
- **Verdict**: CONFIRMED
- **Detail**: Reference-accuracy caveat: symbol located by grep; the name is unique in this file with no polymorphic dispatch. `handleClassAsTargetWithRidEquality` itself is planned by this track (see next premise).

#### Premise: `handleClassAsTargetWithRidEquality` — new handler this track creates
- **Track claim**: Deliverables — "A new `handleClassAsTargetWithRidEquality` handler in `SelectExecutionPlanner`, tried first inside `handleClassAsTarget`."
- **Search performed**: `grep -rn "handleClassAsTargetWithRidEquality" core/src/main/java/` (PSI unavailable).
- **Code location**: NOT FOUND (absent from the codebase).
- **Actual behavior**: No such symbol exists today.
- **Verdict**: CONFIRMED
- **Detail**: Planned by this track. A not-yet-existing name here is expected, not a blocker.

#### Premise: `extractAndRemoveRidInList` — new primitive this track creates
- **Track claim**: Deliverables — "A new `extractAndRemoveRidInList` primitive on `SQLWhereClause`, sibling to `extractAndRemoveRidEquality`."
- **Search performed**: `grep -rn "extractAndRemoveRidInList" core/src/main/java/` (PSI unavailable).
- **Code location**: NOT FOUND (absent from the codebase).
- **Actual behavior**: No such symbol exists today.
- **Verdict**: CONFIRMED
- **Detail**: Planned by this track.

#### Premise: dispatch order handleFetchFromTarget:267 → handleWhere:271 → tryPushDownFilterIntoExpand:274
- **Track claim**: D5 — "`handleClassAsTarget` runs inside `handleFetchFromTarget` (line 267), before `handleWhere` (line 271). ... `tryPushDownFilterIntoExpand` ... runs after `handleWhere` in the pipeline (dispatch order at lines 267 → 271 → 274)."
- **Search performed**: Read SelectExecutionPlanner.java:264-277 (grep+Read).
- **Code location**: SelectExecutionPlanner.java:267 (`handleFetchFromTarget`), 269 (`handleLet`), 271 (`handleWhere`), 274 (`tryPushDownFilterIntoExpand`), 276 (`handleProjectionsBlock`).
- **Actual behavior**: Pipeline assembly is exactly: global LET (265), fetch-from-target (267), per-record LET (269), WHERE (271), expand push-down (274), projections block (276).
- **Verdict**: CONFIRMED
- **Detail**: The EXPAND push-down (274) runs after `handleWhere` (271), so at the point the new fetch handler emits (inside 267) no `FilterStep` exists yet — the rationale for D5's index-handler mechanism over EXPAND surgery holds.

#### Premise: index-handler dual-field null of whereClause/flattenedWhereClause at 2357-2358
- **Track claim**: D5 — "nulls both fields, exactly as the index handlers do at SelectExecutionPlanner.java:2357-2358."
- **Search performed**: Read SelectExecutionPlanner.java:2350-2363 (grep+Read).
- **Code location**: SelectExecutionPlanner.java:2357 (`info.whereClause = null;`), 2358 (`info.flattenedWhereClause = null;`).
- **Actual behavior**: Inside the indexed-function handler success path, both fields are nulled with the comment "// WHERE condition already applied", then `return true`.
- **Verdict**: CONFIRMED
- **Detail**: Same dual-null pattern also at 2529-2530 (regular-index handler). The invariant D5 describes is real and upheld by both index handlers.

#### Premise: `handleWhere` chains FilterStep iff info.whereClause != null (line 1990)
- **Track claim**: D5 — "`handleWhere` then chains the remainder FilterStep exactly once, because it chains iff `info.whereClause != null` (line 1990)."
- **Search performed**: Read SelectExecutionPlanner.java:1985-1998 (grep+Read).
- **Code location**: SelectExecutionPlanner.java:1990 (`if (info.whereClause != null)`), 1991-1996 (`plan.chain(new FilterStep(info.whereClause, ...))`).
- **Actual behavior**: `handleWhere` chains exactly one `FilterStep` built from `info.whereClause` when non-null; no-op otherwise. Confirms: set `info.whereClause` to the remainder → one filter step; leave it null (sole predicate) → no filter step.
- **Verdict**: CONFIRMED

#### Premise: EXPAND template — resolveClassToCollectionIds@3400, extractAndRemoveRidEquality@3423, RidFilterDescriptor.DirectRid@3425
- **Track claim**: Context/Orientation — "SelectExecutionPlanner.java:3423 calls `remainingWhere.extractAndRemoveRidEquality()` and wraps the result in a `RidFilterDescriptor.DirectRid` (line 3425), and line 3400 resolves the target class to a collection-id set via `resolveClassToCollectionIds`."
- **Search performed**: Read SelectExecutionPlanner.java:3390-3428 and RidFilterDescriptor.java (grep+Read).
- **Code location**: 3400 (`classFilter = resolveClassToCollectionIds(classExtraction.className(), plan)`), 3423 (`remainingWhere.extractAndRemoveRidEquality()`), 3425 (`new RidFilterDescriptor.DirectRid(ridExtraction.ridExpression())`); RidFilterDescriptor.java:129 (`record DirectRid(SQLExpression ridExpression)`).
- **Actual behavior**: The EXPAND push-down path extracts `@class =` then `@rid =`, resolving the class to a collection-id set for the ExpandStep pre-filter. `RidFilterDescriptor.DirectRid` is a record wrapping an `SQLExpression`. Exact template the track mirrors.
- **Verdict**: CONFIRMED

#### Premise: resolveClassToCollectionIds@3675 → collectionIdsForClass → getPolymorphicCollectionIds (class + all subclasses)
- **Track claim**: D2 — "`resolveClassToCollectionIds` (line 3675) delegates to `TraversalPreFilterHelper.collectionIdsForClass`, which returns the class and all subclasses via `getPolymorphicCollectionIds()`."
- **Search performed**: Read SelectExecutionPlanner.java:3671-3687 and TraversalPreFilterHelper.java:84-97 (grep+Read).
- **Code location**: SelectExecutionPlanner.java:3675 (`resolveClassToCollectionIds`), 3686 (`return TraversalPreFilterHelper.collectionIdsForClass(schemaClass)`); TraversalPreFilterHelper.java:90 (`collectionIdsForClass`), 91 (`var ids = clazz.getPolymorphicCollectionIds()`).
- **Actual behavior**: `resolveClassToCollectionIds` returns null if ctx/session/class is missing, else delegates to `collectionIdsForClass`, which builds an `IntOpenHashSet` from `getPolymorphicCollectionIds()` — the class plus all subclasses. Javadoc at 84-88 confirms "the given class and all its subclasses".
- **Verdict**: CONFIRMED
- **Detail**: Polymorphic direction is exactly as D2 claims: `SELECT FROM Super WHERE @rid = <rid-of-Sub>` matches (Sub's collection is in Super's polymorphic set); `SELECT FROM Sub WHERE @rid = <rid-of-Super>` does not. Signature note: the real signature is `resolveClassToCollectionIds(String, SelectExecutionPlan)` returning `it.unimi.dsi.fastutil.ints.IntSet`; the track's Interfaces section writes it as `(String className, plan) → IntSet`, which matches.

#### Premise: `SQLWhereClause.extractAndRemoveRidEquality`@1003 — single-OR-branch, both orderings, no early-calc check
- **Track claim**: Context/Orientation + S2 — "handles a single-OR-branch WHERE, both orderings (`@rid = x` and `x = @rid`), unwraps nested single-element Or/And wrappers, skips a negated NOT, and returns a `RidExtractionResult(ridExpression, remainingWhere)` where `remainingWhere` is null when the RID equality was the sole predicate — but it does not verify the value side is early-calculable."
- **Search performed**: Read SQLWhereClause.java:1002-1097 (grep+Read).
- **Code location**: 1003 (`extractAndRemoveRidEquality`), 1004 (null baseExpression → null), 1009-1012 (multi-OR-branch → null), 1019-1028 (iterate AND terms, null remainder when size==1), 1038-1074 (`tryExtractRidFromTerm`: unwrap wrappers, skip negated NotBlock at 1060-1062, require `SQLEqualsOperator` at 1069, both orderings at 1072-1073), 1081-1097 (`tryExtractRidValue`: bare-`@rid` check).
- **Actual behavior**: Exactly as claimed. No `isEarlyCalculated` call anywhere in the method — confirms D3 is a genuine new gate, not a duplicate of existing behavior.
- **Verdict**: CONFIRMED

#### Premise: `tryExtractRidValue`@1081 — bare-`@rid` left-side check reusable for IN
- **Track claim**: Plan of Work step 1 + D4 — "reusing the same left-side check `tryExtractRidValue` applies at SQLWhereClause.java:1081"; "verify `SQLInCondition`'s left side has the `mathExpression instanceof SQLBaseExpression` shape so the bare-`@rid` check can be reused."
- **Search performed**: Read SQLWhereClause.java:1081-1097 and SQLInCondition.java:29 (grep+Read).
- **Code location**: SQLWhereClause.java:1083 (`attrSide.mathExpression instanceof SQLBaseExpression attrBase`), 1086-1095 (identifier suffix recordAttribute == "@rid", no modifier); SQLInCondition.java:29 (`protected SQLExpression left`).
- **Actual behavior**: `tryExtractRidValue` takes two `SQLExpression`s and checks `attrSide.mathExpression instanceof SQLBaseExpression` with a `@rid` record-attribute suffix and no modifier. `SQLInCondition.left` is a `SQLExpression`, so it exposes the `.mathExpression` field the check reads. The bare-`@rid` check is reusable against `SQLInCondition.left`.
- **Verdict**: CONFIRMED

#### Premise: `FetchFromRidsStep` ctor takes Collection<RecordIdInternal>, canBeCached() hard-false, no dedup, no class check
- **Track claim**: S3 + D2 + D4 — "ctor takes `Collection<RecordIdInternal>` and `loadIterator`s them with no class check and no dedup"; "`canBeCached()` is hard-false (FetchFromRidsStep.java:88)."
- **Search performed**: Read FetchFromRidsStep.java in full (grep+Read).
- **Code location**: 34-38 (`FetchFromRidsStep(Collection<RecordIdInternal> rids, CommandContext ctx, boolean profilingEnabled)`), 46 (`ExecutionStream.loadIterator(this.rids.iterator())`), 88-90 (`canBeCached()` returns false).
- **Actual behavior**: Ctor stores the `Collection` directly; `internalStart` drains any predecessor then returns `loadIterator(rids.iterator())` — no membership filter, no dedup. `canBeCached()` returns false unconditionally.
- **Verdict**: CONFIRMED
- **Detail**: Confirms the D2 membership guard and D4 dedup are both mandatory (the step does neither), and confirms D3's plan-cache trade-off (the resulting plan is non-cacheable).

#### Premise: `EmptyStep`@19 — real step, ctor (CommandContext, boolean)
- **Track claim**: D2 — "chain `EmptyStep` (EmptyStep.java:19, a real step already used by MATCH and ForEachStep)"; Interfaces — "`new EmptyStep(CommandContext, boolean)`."
- **Search performed**: Read EmptyStep.java in full (grep+Read).
- **Code location**: 19 (`public class EmptyStep extends AbstractExecutionStep`), 25 (`EmptyStep(CommandContext ctx, boolean profilingEnabled)`), 36 (`ExecutionStream.empty()`), 48 (`canBeCached()` returns false).
- **Actual behavior**: Two-arg ctor, produces an empty stream, not cacheable. Signature matches the track's Interfaces claim.
- **Verdict**: CONFIRMED

#### Premise: `SQLInCondition` right-side fields rightStatement/rightParam/rightMathExpression@31-33; left@29
- **Track claim**: S7 + Plan of Work step 1 — "left is the `@rid` side; the right side is one of `rightStatement` (subquery), `rightParam` (bound param), `rightMathExpression` (a list literal). A bound param (`rightParam`) or a list-literal `SQLMathExpression` (`rightMathExpression`) qualify; a subquery (`rightStatement`) does not."
- **Search performed**: Read SQLInCondition.java:26-67 (grep+Read).
- **Code location**: 29 (`protected SQLExpression left`), 31 (`rightStatement`), 32 (`rightParam`), 33 (`rightMathExpression`); 57-66 (`evaluateRight` branches on those three in order).
- **Actual behavior**: Exactly the three right-side shapes. `evaluateRight` executes the subquery for `rightStatement`, resolves the param for `rightParam`, executes the math expression for `rightMathExpression`. A subquery is not plan-time-resolvable → skip and fall through, as the track states.
- **Verdict**: CONFIRMED

#### Premise: `SQLNotInCondition`@18 is a distinct AST node type (not a subclass of SQLInCondition)
- **Track claim**: Plan of Work step 1 + D4 — "An `SQLNotInCondition` is a distinct AST node type, so a negated `@rid IN` never reaches this detector — node-type discrimination enforces 'do not optimize the complement' for free."
- **Search performed**: Read SQLNotInCondition.java:18-26 (grep+Read).
- **Code location**: 18 (`public class SQLNotInCondition extends SQLBooleanExpression`).
- **Actual behavior**: `SQLNotInCondition` extends `SQLBooleanExpression` directly — it is NOT a subclass of `SQLInCondition`. An `instanceof SQLInCondition` check in the new detector excludes it. So "must not optimize negated IN" (S7) is enforced structurally.
- **Verdict**: CONFIRMED

#### Premise: `isEarlyCalculated`@SQLBaseExpression:396 true for literals/params; SQLExpression:216 delegates; SQLMathExpression:985 recurses
- **Track claim**: D3 — "Gate the optimization on `ridExpression.isEarlyCalculated(ctx)` (SQLBaseExpression.java:396); true for literals and bound parameters ... false for a field ref or subquery."
- **Search performed**: Read SQLBaseExpression.java:395-401, SQLExpression.java:216-244, SQLMathExpression.java:985-994 (grep+Read).
- **Code location**: SQLBaseExpression.java:396-400 (true for number/inputParam/string, or an early-calc identifier); SQLExpression.java:216-218 (delegates to `mathExpression.isEarlyCalculated`); SQLMathExpression.java:985-993 (true iff all `childExpressions` are early-calc).
- **Actual behavior**: The extracted `ridExpression` is a `SQLExpression`; its `isEarlyCalculated` delegates to `mathExpression`, which for a `SQLBaseExpression` is true for literals/params and for early-calc identifiers, false for field refs/subqueries. For the IN list-literal side (`rightMathExpression`, a `SQLMathExpression`), `isEarlyCalculated` recurses over child expressions — so a list of literals is early-calc, a list containing a field ref is not.
- **Verdict**: CONFIRMED

#### Premise: `isRangeOperator()` true only for < <= > >=; false for `=`
- **Track claim**: Context/Orientation — "`isRidRange` gates on `SQLBinaryCompareOperator.isRangeOperator()`, which is true only for `<`, `<=`, `>`, `>=` and false for `=`."
- **Search performed**: Read SQLBinaryCompareOperator.java:322-324; `grep -n isRangeOperator SQLEqualsOperator.java` (grep+Read).
- **Code location**: SQLBinaryCompareOperator.java:322-324 (`default boolean isRangeOperator() { return false; }`); SQLEqualsOperator.java — no `isRangeOperator` override (grep empty).
- **Actual behavior**: The interface default is `false`. `SQLEqualsOperator` does not override it, so `=` inherits `false`. Confirms a RID equality is never picked up by `extractRidRanges`/`isRidRange` — it survives as a post-filter, which is the pathology this track fixes.
- **Verdict**: CONFIRMED
- **Detail**: The four range operators overriding to `true` were not individually opened (out of scope — the load-bearing fact is that `=` is false), but the default-false + no-`SQLEqualsOperator`-override chain is dispositive for the `=` case.

#### Premise: `handleRidsAsTarget` evaluates RID at plan time and chains FetchFromRidsStep (template for S4)
- **Track claim**: S4 + D3 — "SelectExecutionPlanner.java:1631 evaluates each `SQLRid` to a `RecordIdInternal` at plan time (`toRecordId(ctx)`) and chains `FetchFromRidsStep`. The fix mirrors this, but its value comes from evaluating the extracted `ridExpression` rather than a parsed `SQLRid` target."
- **Search performed**: Read SelectExecutionPlanner.java:1631-1638 and SQLRid.java:60-84 (grep+Read).
- **Code location**: SelectExecutionPlanner.java:1631-1637 (`handleRidsAsTarget`: loops `rids`, `actualRids.add(rid.toRecordId((Result) null, ctx))`, chains `FetchFromRidsStep(actualRids, ...)`); SQLRid.java:60 (`toRecordId(Result, ctx)`), 76 (`toRecordId(Identifiable, ctx)`).
- **Actual behavior**: `handleRidsAsTarget` calls the two-arg `toRecordId((Result) null, ctx)` per `SQLRid` and chains one `FetchFromRidsStep` with no `FilterByClassStep`. There is no single-arg `toRecordId(ctx)`. This track's handler evaluates an `SQLExpression` (not a `SQLRid`) and maps the result the way `SQLRid.toRecordId`'s switch does (SQLRid.java:66-72: Identifiable → identity, String → fromString, else null).
- **Verdict**: PARTIAL
- **Detail**: The template is real and the mechanism the track describes is faithful (S4 states it correctly). The imprecision is confined to the D3 / Context shorthand "toRecordId(ctx)" (a two-arg overload, called with a null Result) and to the fact the handler maps an `SQLExpression` result rather than calling `SQLRid.toRecordId` at all. Not load-bearing → suggestion T2, not a blocker.

#### Premise: COUNT hardwired optimization requires a base-identifier indexed property, runs before fetch
- **Track claim**: Q3 + Plan of Work — "The COUNT hardwired optimization runs before fetch and requires a base-identifier indexed property, so it never matches an `@rid` equality and is unaffected."
- **Search performed**: Read SelectExecutionPlanner.java:260 (handleHardwiredOptimizations dispatch) and 572-621 (the count-by-index guard) (grep+Read).
- **Code location**: 260 (`if (handleHardwiredOptimizations(...)) return result;` — before `handleFetchFromTarget` at 267), 589 (`condition instanceof SQLBinaryCondition`), 592 (`binaryCondition.getLeft().isBaseIdentifier()`), 595 (`SQLEqualsOperator`), 603-607 (single-field class index matching the left alias).
- **Actual behavior**: The count-by-index optimization requires the WHERE to be a single `SQLBinaryCondition` whose left is a base identifier with a matching single-field class index. `@rid` is a record attribute (not a base identifier) and carries no class index, so the guard at 592/603-607 never fires for an `@rid` equality. It runs at 260, before the fetch handlers.
- **Verdict**: CONFIRMED

#### Premise: MultiValue.isMultiValue / getMultiValueIterable exist for IN-list scalar-vs-collection normalization
- **Track claim**: D4 — "Both extractors return one value-side expression and the emission code normalizes scalar-vs-collection, making `=` the degenerate case of the `IN` path."
- **Search performed**: `grep -n` MultiValue.java for `isMultiValue`/`getMultiValueIterable`; Read SQLInCondition.java:155-180 (grep+Read).
- **Code location**: MultiValue.java:79 (`isMultiValue(Object)`), 329 (`getMultiValueIterable(Object)`); SQLInCondition.java:155-169 (uses both to iterate the right-side list).
- **Actual behavior**: `MultiValue.isMultiValue` + `getMultiValueIterable` are the exact primitives `SQLInCondition` uses to iterate its right-side value. The emission code can use the same pair to normalize the evaluated IN result into per-element RIDs while treating the `=` result as a singleton.
- **Verdict**: CONFIRMED
- **Detail**: Closes the feasibility of D4's "one common collection" claim — iterating an evaluated list literal into element RIDs is a solved pattern in the same codebase.

#### Edge case: wrong-class RID → empty result
- **Trigger**: `SELECT FROM A WHERE @rid = <rid-of-B>` where B's collection ∉ A's polymorphic collection set.
- **Code path trace**:
  1. `handleClassAsTargetWithRidEquality` extracts the RID via `extractAndRemoveRidEquality` (SQLWhereClause.java:1003) → early-calc passes.
  2. Evaluate to `RecordIdInternal`; membership-filter against `resolveClassToCollectionIds("A")` (SelectExecutionPlanner.java:3675 → polymorphic set of A) — the RID's `getCollectionId()` is not a member.
  3. Survivors empty → chain `EmptyStep` (EmptyStep.java:25), return true.
- **Outcome**: Empty result; the caller short-circuits past the scan fallthrough. Correct.
- **Track coverage**: yes (D2, Invariants "Class-membership preserved", test roster).

#### Edge case: subclass RID under superclass target → the record
- **Trigger**: `SELECT FROM Vehicle WHERE @rid = <rid-of-Car>` where Car extends Vehicle.
- **Code path trace**:
  1. Extract + early-calc pass.
  2. `resolveClassToCollectionIds("Vehicle")` → `getPolymorphicCollectionIds()` (TraversalPreFilterHelper.java:91) includes Car's collection.
  3. RID is a member → chain `FetchFromRidsStep([rid])`, return true.
- **Outcome**: The Car record is returned. Correct (matches a class scan's polymorphic semantics).
- **Track coverage**: yes (D2, Invariants "Subclass membership", test roster).

#### Edge case: duplicate-RID IN → single row
- **Trigger**: `SELECT FROM C WHERE @rid IN [#10:1, #10:1]`.
- **Code path trace**:
  1. `extractAndRemoveRidInList` yields the list-literal value side; early-calc passes (SQLMathExpression.java:985).
  2. Evaluate to two identical `RecordIdInternal`s; **dedup** to one before the step.
  3. Membership filter (member) → `FetchFromRidsStep([#10:1])` (one element).
- **Outcome**: Single row. Without dedup, `FetchFromRidsStep.loadIterator` (FetchFromRidsStep.java:46) would emit the record twice (no dedup in the step), diverging from the scan-plus-filter cardinality. Dedup is a correctness requirement, correctly identified.
- **Track coverage**: yes (D4 "Duplicate RIDs", Invariants "Result-set parity", test roster).

#### Edge case: mixed member/non-member IN list
- **Trigger**: `SELECT FROM C WHERE @rid IN [<member-of-C>, <non-member>]`.
- **Code path trace**:
  1. Extract + early-calc pass; evaluate to two RIDs.
  2. Membership filter keeps only the member (drops the non-member); survivors = 1.
  3. `FetchFromRidsStep([member])`, return true.
- **Outcome**: Only the member record. An all-or-nothing implementation would either drop both or return the non-member's record (a class-membership violation). The per-element filter is correct.
- **Track coverage**: yes (D4 "Mixed member / non-member list", test roster).

#### Edge case: empty IN [] → EmptyStep, not scan
- **Trigger**: `SELECT FROM C WHERE @rid IN []`.
- **Code path trace**:
  1. `extractAndRemoveRidInList` yields an empty list-literal (early-calc trivially true — no child expressions to fail, SQLMathExpression.java:986-992).
  2. Evaluate → empty candidate set → after membership filter, empty.
  3. Chain `EmptyStep`, return true (do NOT return false / fall through).
- **Outcome**: Empty result. If the handler returned false here, it would fall through to a full class scan whose post-filter `@rid IN []` returns nothing anyway — so the *result* is still correct, but the O(class) scan is emitted instead of O(1) empty. Returning true + `EmptyStep` is both correct and optimal; the track mandates it (D4 "Empty list ... Must not fall through to a scan"). Note the primitive must actually recognize an empty list literal as an IN it handles (rather than returning null → fall-through); this is an implementation detail for decomposition, and the result is correct either way.
- **Track coverage**: yes (D4 "Empty list", test roster).

#### Edge case: remainder predicate applied exactly once (@rid = x AND status = 'A')
- **Trigger**: `SELECT FROM C WHERE @rid = #10:1 AND status = 'A'`.
- **Code path trace**:
  1. `extractAndRemoveRidEquality` (SQLWhereClause.java:1019 iterates AND terms) returns `ridExpression=#10:1`, `remainingWhere = "status = 'A'"` (via `buildWhereWithout`, line 1025).
  2. Handler sets `info.whereClause = remainingWhere` and nulls `info.flattenedWhereClause` (D5 / 2357-2358 pattern).
  3. `handleFetchFromTarget` returns → `handleLet` (reads only `perRecordLetClause`, SelectExecutionPlanner.java:1712, does not touch WHERE) → `handleWhere` (1990) chains exactly one `FilterStep("status = 'A'")`.
- **Outcome**: The remainder filter is chained exactly once — not zero (would drop `status = 'A'`), not twice (no other reader chains it). Verified: no `flattenedWhereClause` read site sits between the fetch handler and `handleWhere` (all reads are at 583/588 pre-fetch COUNT, or inside the index handlers 2218/2320/2695 that never run once the new handler wins).
- **Track coverage**: yes (D5, Invariants "Remaining predicates applied exactly once", test roster).

#### Edge case: non-early-calc RID (field ref / subquery) → falls through to scan
- **Trigger**: `SELECT FROM C WHERE @rid = someField` or `@rid IN (SELECT ...)`.
- **Code path trace**:
  1a. `= someField`: `extractAndRemoveRidEquality` extracts it (no early-calc check there), then the handler's `ridExpression.isEarlyCalculated(ctx)` gate (SQLBaseExpression.java:396 / SQLExpression.java:216) returns false for a field-ref identifier → handler returns false.
  1b. `IN (subquery)`: `extractAndRemoveRidInList` sees `rightStatement != null` (SQLInCondition.java:31) → returns null → handler returns false.
  2. `handleClassAsTarget` continues to the index handlers, then the class-scan fallthrough — unchanged behavior.
- **Outcome**: Full class scan with the RID predicate as a post-filter — identical to today. Correct.
- **Track coverage**: yes (D3, Invariants "Non-early-calc fall-through", test roster).

#### Edge case: @rid = x AND @rid IN [...] convergence
- **Trigger**: `SELECT FROM C WHERE @rid = #10:1 AND @rid IN [#10:1, #10:2]`.
- **Code path trace**:
  1. Handler tries `extractAndRemoveRidEquality` first; it iterates AND terms in order (SQLWhereClause.java:1019) and returns the first RID predicate — the `=` term — with the `IN` term left in `remainingWhere`.
  2. Fetch {#10:1} (membership-filtered); set `info.whereClause` to `@rid IN [#10:1, #10:2]`; `handleWhere` post-filters → keeps #10:1.
- **Outcome**: {#10:1}. If extraction order had picked `IN` first, it would fetch {#10:1, #10:2} and the `=` post-filter would keep #10:1 — same final rows, different intermediate cardinality. Both converge. The track's D4 order-independence argument holds because the handler extracts at most one RID predicate and the other stays as a post-filter.
- **Track coverage**: yes (D4 "`@rid = x AND @rid IN [...]`", convergence trace).

#### Integration: handleClassAsTarget dispatch + FilterByClassStep pattern
- **Plan claim**: D1 — new handler "tried first inside `handleClassAsTarget`, before `handleClassAsTargetWithIndexedFunction`. It returns true (and the caller returns) when it emits the direct fetch."
- **Actual entry point**: SelectExecutionPlanner.java:2106-2116 — the caller's `if (handler(...)) { plan.chain(new FilterByClassStep(...)); return; }` pattern for each existing `handleClassAsTargetWith*`.
- **Caller analysis**: `handleClassAsTarget` is called from `handleFetchFromTarget` (grep: single call site within this file's target dispatch). The new handler must be inserted as the first `if` and must return without appending `FilterByClassStep` (contrast the two neighbours). `handleRidsAsTarget` (1631-1637) is the precedent: it chains `FetchFromRidsStep` alone, no class filter.
- **Breaking change risk**: Low for existing callers (the new handler returns false on any non-`@rid` query, leaving the existing chain intact). The one wiring hazard is a copy-paste of the neighbour's `FilterByClassStep` line — see finding T1.
- **Verdict**: MATCHES (with the T1 wiring caveat).

#### Integration: remainder WHERE wiring (info.whereClause / flattenedWhereClause → handleWhere)
- **Plan claim**: D5 / Q2 — set `info.whereClause` to the remainder and null `info.flattenedWhereClause`; `handleWhere` then chains the one remainder FilterStep.
- **Actual entry point**: SelectExecutionPlanner.java:1990 (`handleWhere` reads `info.whereClause`); 2357-2358 (index-handler dual-null precedent).
- **Caller analysis**: Between the fetch handler (267) and `handleWhere` (271) sits only `handleLet` (269), which reads `info.perRecordLetClause` (1712) — never the WHERE fields. All `flattenedWhereClause` read sites (583/588 pre-fetch COUNT; 1406/1482 extractRidRanges inside the index/build path; 2218/2320/2695/2699 inside the index handlers) either run before the fetch handler or belong to the alternative handlers that never execute once the new handler returns true.
- **Breaking change risk**: None. Nulling `flattenedWhereClause` is invariant-preserving (matches the index handlers) and hits no live downstream reader — the track's "latent trap, so null both" framing is accurate.
- **Verdict**: MATCHES

#### Integration: ORDER BY / SKIP / LIMIT / GROUP BY assembly (handleProjectionsBlock) + info.orderApplied
- **Plan claim**: Ordering-and-invariants + Q3 — "The handler builds only the source step; the handler must leave `info.orderApplied` false so `handleProjectionsBlock` still runs." COUNT unaffected.
- **Actual entry point**: SelectExecutionPlanner.java:276 (`handleProjectionsBlock`), 320 (its static decl); 2144-2146 (the class-scan path sets `info.orderApplied = true` only when it pushes RID ordering into the fetch).
- **Caller analysis**: `handleProjectionsBlock` runs at 276 regardless of which fetch handler won at 267. The class-scan path sets `orderApplied = true` only for `ORDER BY @rid` push-down (2144-2146); the new handler does no such push-down, so leaving `orderApplied` at its default false lets `handleOrderBy` sort normally downstream (no-op for a single RID, real sort for a multi-RID IN ... ORDER BY).
- **Breaking change risk**: None, provided the handler does not set `info.orderApplied`. The COUNT hardwired opt (260, before 267) never matches `@rid` (see COUNT premise), so `SELECT count(*) FROM C WHERE @rid = x` is unaffected.
- **Verdict**: MATCHES
