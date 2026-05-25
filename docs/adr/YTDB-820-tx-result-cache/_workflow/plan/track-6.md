# Track 6: MATCH Etap A delta — single-alias as RECORD-shape composition

## Purpose / Big Picture

BLUF: After this track, cached single-alias MATCH queries (`MATCH {as:u, class:X WHERE …} RETURN <projection of u>`) reflect intra-tx CREATE/UPDATE/DELETE via the same RECORD-shape delta logic, with a `returnProjector` wrapping each delta record into a tuple-shaped Result.

Extend `ShapeClassifier` to return RECORD for single-alias MATCH that meets the Etap A conditions: `matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()` (no edges), pattern node carries `class:` annotation, no LET/UNWIND in scope, no cross-alias-state references in pattern WHERE. Build `returnProjector: Function<RecordAbstract, Result>` from the RETURN clause at entry construction. Delta-build follows the RECORD path; the projector wraps each inject-list record into a single-binding tuple before sort. Multi-alias / cross-join / pattern-with-edges classify as NONE.

## Context and Orientation

**Codebase state at track start.** After Tracks 4-5: RECORD and AGGREGATE delta work for SELECT. This track lifts MATCH single-alias into the RECORD path.

Existing relevant code:
- `SQLMatchStatement` — `matchExpressions: List<SQLMatchExpression>` and `returnItems: List<SQLExpression>`.
- Each `SQLMatchExpression` has `origin: SQLMatchFilter` (the start node) and `items: List<SQLMatchPathItem>` (the edges). Etap A condition: `items.isEmpty()` AND `matchExpressions.size() == 1`.
- `SQLMatchFilter` (origin node) exposes accessors `getAlias()` / `getClassName(CommandContext)` / `getFilter()` over an internal `items: List<SQLMatchFilterItem>` (the parser breaks one `{as:u, class:X, where: …}` block into one or more items). For Etap A's no-edges single-binding case there is exactly one item; the accessors iterate items and return the first non-null match.
- `MatchPrefetchStep` + `PREFETCHED_MATCH_ALIAS_PREFIX` — Etap B primitive (NOT used in v1, but referenced as v2 mechanism).

**Concrete deliverables.**
- `ShapeClassifier.classify(SQLMatchStatement)` — applies Etap A test. If pass: returns RECORD; populates `entry.returnProjector` closure from `returnItems`. If fail (multi-alias, edges, cross-alias-state in WHERE, LET, UNWIND): returns NONE.
- `returnProjector: Function<RecordAbstract, Result>` — closure built at entry construction. For RETURN clause like `RETURN u, u.name`, projector takes a record and produces `Result{u: rec, name: rec.name}` matching the original execution's output shape.
- Entry construction extension — MATCH-flavored RECORD entries store: `effectiveFromClasses = {origin.clazz} ∪ subclass closure` (D11), `whereClause = origin.filter`, `orderBy = MATCH's ORDER BY`, `returnProjector`.
- `DeltaBuilder.buildForRecord` — extended to call `entry.returnProjector(rec, ctx)` when constructing inject-list entries for MATCH-flavored RECORD entries. SELECT-flavored entries skip the projector (or use identity projector).

## Plan of Work

1. `ShapeClassifier.classify(SQLMatchStatement)` — Etap A condition check. Reject patterns with multi-alias, edges, cross-alias-state WHERE references (`$current`, `$matched`, `${otherAlias}.…`), LET, UNWIND, subqueries in pattern WHEREs.
2. `returnProjector` builder — given `returnItems: List<SQLExpression>` and the alias name, build a closure that, on each invocation `(rec, ctx)`: (a) constructs a `ResultInternal` binding the record under the alias name (e.g., `Result{alias → rec}`); (b) sets `ctx.setVariable(alias, boundResult)` so that `SQLExpression.execute` resolves `alias.field` references correctly; (c) iterates `returnItems` and calls `expr.execute(boundResult, ctx)` for each, accumulating the (alias, value) pairs into the output Result. Without step (b) the binding is missing and `u.someProp + 1` would fail to resolve `u`. Restrict Etap A admission in `ShapeClassifier` to projections that the existing `SQLExpression.execute` infrastructure can handle against a single alias-bound Result; any expression referencing `$matched`, `$current`, or another alias falls back to NONE.
3. `DeltaBuilder.buildForRecord` integration — flag on entry indicates "use returnProjector" path; defaults to identity for SELECT-flavored entries.
4. `OrderByComparator` for MATCH — projected tuples can have ORDER BY on either record properties (`ORDER BY u.name`) or projection aliases (`ORDER BY name`). Build comparator that resolves to the appropriate value in the projected `Result`.
5. Test matrix (T6 set):
   - T6a: Etap A CREATE — single-alias MATCH; new record matching WHERE appears as tuple in view.
   - T6b: Etap A UPDATE — record's WHERE-relevant prop changes to/from matching.
   - T6c: Etap A DELETE — cached tuple disappears from view.
   - T6d: Multi-alias MATCH (`MATCH {as:u, class:X}.out{as:v, class:Y} RETURN u, v`) — classify NONE.
   - T6e: Pattern with edges — classify NONE.
   - T6f: Cross-alias WHERE (`WHERE name = $matched.u.name`) — classify NONE.
   - T6g (I4 for MATCH): cache-then-mutate scenario equivalent to fresh re-execution.
   - T6h (L5 ctx-binding): MATCH `{as:u, class:User WHERE active=true} RETURN u.name, u.age * 2 AS double_age` — Etap A delta CREATE produces a Result with correct `u.name` AND correct `double_age` from the projector closure. Verifies the alias-binding step in `returnProjector` works for computed expressions, not just plain `RETURN u`.

**Invariants to preserve.** I4 for MATCH Etap A: view output equivalent to fresh MATCH execution. Multi-alias non-coverage: classify returns NONE for shapes outside Etap A.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (MATCH classify rules)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (`returnProjector` field)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (projector wrapping in inject-list construction)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/OrderByComparator.java` (projected-tuple ORDER BY support)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/MatchReturnProjector.java` (new — closure builder utility)

**Out-of-scope files.**
- `MatchExecutionPlanner`, `MatchFirstStep`, `MatchPrefetchStep` — Etap B v2 will use these.
- `SQLMatchExpression`, `SQLMatchFilter`, `SQLMatchPathItem` — read-only; no modifications.

**Inter-track dependencies.**
- Depends on: Track 4 (RECORD shape + delta machinery).
- Unblocks: Track 7 (hardening covers MATCH bypass paths and NONE-shape behavior).

**Library / function signatures.**
- `MatchReturnProjector.build(List<SQLExpression>, String alias) → Function<RecordAbstract, Result>`.
- `ShapeClassifier.isMatchEtapA(SQLMatchStatement) → boolean` (helper).
- `CachedEntry.returnProjector` field — `@Nullable Function<RecordAbstract, Result>`.
