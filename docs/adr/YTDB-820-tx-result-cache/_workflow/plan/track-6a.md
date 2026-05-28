<!-- workflow-sha: 7cdacac6aecc5fac81f314418453a8831c3ef37e -->
# Track 6a: MATCH delta — Etap A (single-alias as RECORD)

## Purpose / Big Picture

BLUF: After this track, cached single-alias MATCH queries reflect intra-tx mutations via the RECORD delta-build path with a `returnProjector` applied to each inject-list entry.

Extends `ShapeClassifier` to classify single-alias MATCH `MATCH {as:u, class:X WHERE simple-predicate} RETURN <projection of u>` as RECORD and to build a `returnProjector` closure at entry construction. Reuses Track 4's `DeltaBuilder.buildForRecord` + `OrderByComparator` machinery — only the projector is new infrastructure. Patterns that fail the Etap A gate fall through to Track 6b's MATCH_TUPLE_MULTI gate; patterns that fail both classify as K0_NONE (cacheable under D18's mutation-version gate).

## Context and Orientation

**Codebase state at track start.** After Tracks 4-5: RECORD and AGGREGATE delta work for SELECT. This track adds the first MATCH path.

Existing relevant code:
- `SQLMatchStatement` — `matchExpressions: List<SQLMatchExpression>` and `returnItems: List<SQLExpression>`.
- Each `SQLMatchExpression` has `origin: SQLMatchFilter` (the start node) and `items: List<SQLMatchPathItem>` (the edges). Etap A condition: `matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()`.
- `SQLMatchFilter` (origin / path-item filter) exposes accessors `getAlias()` / `getClassName(CommandContext)` / `getFilter()` over an internal `items: List<SQLMatchFilterItem>` (the parser breaks one `{as:u, class:X, where: …}` block into one or more items). For Etap A's no-edges single-binding case there is exactly one item; the accessors iterate items and return the first non-null match.

**Concrete deliverables.**
- `ShapeClassifier.classify(SQLMatchStatement)` Etap A branch — applies the conditions in Plan-of-Work step 1; if pass, returns RECORD and the entry is populated with the projector + alias-derived metadata.
- `returnProjector: Function<RecordAbstract, Result>` — closure built at entry construction. For RETURN clause like `RETURN u, u.name`, projector takes a record and produces `Result{u: rec, name: rec.name}` matching the original execution's output shape.
- Entry construction extension — MATCH-flavored RECORD entries store: `effectiveFromClasses = {origin.clazz} ∪ subclass closure` (D11), `whereClause = origin.filter`, `orderBy = MATCH's ORDER BY`, `returnProjector`.
- `DeltaBuilder.buildForRecord` — extended to call `entry.returnProjector(rec, ctx)` when constructing inject-list entries for MATCH-flavored RECORD entries. SELECT-flavored entries skip the projector (or use identity projector).
- `OrderByComparator` extension — projected tuples can have ORDER BY on either record properties (`ORDER BY u.name`) or projection aliases (`ORDER BY name`). Build comparator that resolves to the appropriate value in the projected `Result`.

## Plan of Work

1. `ShapeClassifier.classify(SQLMatchStatement)` Etap A — condition check. Etap A iff:
   - `matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()` (single node, no edges)
   - `origin.getClassName(ctx)` is non-null
   - Origin's `where:` clause has no cross-alias-state references (`$current`, `$matched`, `${otherAlias}.…`)
   - No LET / UNWIND
   - No subqueries in pattern WHERE
   - Every `returnItem` resolves to an expression on the single alias (or its modifiers); references to `$matched`, `$current`, another alias → fall through to Track 6b's gate or K0_NONE
   If pass: classify returns RECORD with `entry.returnProjector` populated. Otherwise fall through to the MATCH_TUPLE_MULTI gate (Track 6b).

2. `returnProjector` builder — given `returnItems: List<SQLExpression>` and the alias name, build a closure that, on each invocation `(rec, ctx)`: (a) constructs a `ResultInternal` binding the record under the alias name (e.g., `Result{alias → rec}`); (b) sets `ctx.setVariable(alias, boundResult)` so that `SQLExpression.execute` resolves `alias.field` references correctly; (c) iterates `returnItems` and calls `expr.execute(boundResult, ctx)` for each, accumulating the (alias, value) pairs into the output Result. Without step (b) the binding is missing and `u.someProp + 1` would fail to resolve `u`.

3. `DeltaBuilder.buildForRecord` integration — flag on entry indicates "use returnProjector" path; defaults to identity for SELECT-flavored entries.

4. `OrderByComparator` for MATCH — projected tuples can have ORDER BY on either record properties (`ORDER BY u.name`) or projection aliases (`ORDER BY name`). Build comparator that resolves to the appropriate value in the projected `Result`.

5. Test matrix (T6 set, Etap A subset):
   - **T6a** (Etap A CREATE): single-alias MATCH; new record matching WHERE appears as tuple in view.
   - **T6b** (Etap A UPDATE): record's WHERE-relevant prop changes to/from matching.
   - **T6c** (Etap A DELETE): cached tuple disappears from view.
   - **T6d** (Etap A classify-NONE for cross-alias): cross-alias WHERE (`WHERE name = $matched.u.name`) — classify NONE (K0_NONE under D18, or MATCH_TUPLE_MULTI per Track 6b's gate; verify Etap A branch alone returns NONE for this shape).
   - **T6h** (L5 ctx-binding for Etap A): MATCH `{as:u, class:User WHERE active=true} RETURN u.name, u.age * 2 AS double_age` — Etap A delta CREATE produces a Result with correct `u.name` AND correct `double_age` from the projector closure.

**Invariants to preserve.** I4 for Etap A: view output equivalent to fresh MATCH execution against the (cached + tx-delta) snapshot for CREATED/UPDATED/DELETED on the single-alias class.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (Etap A MATCH classify branch)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (Etap A `returnProjector` field)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (Etap A integration in buildForRecord — projector application)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/OrderByComparator.java` (projected-tuple ORDER BY support)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/MatchReturnProjector.java` (Etap A closure builder utility)

**Out-of-scope files.**
- `MatchExecutionPlanner`, `MatchFirstStep`, `MatchPrefetchStep` — full Etap B (CREATED constrained-walk discovery) is a separate ADR.
- `SQLMatchExpression`, `SQLMatchFilter`, `SQLMatchPathItem` — read-only; no modifications.
- `MatchMultiDelta`, `buildForMatchMulti`, MATCH_TUPLE_MULTI shape — Track 6b.

**Inter-track dependencies.**
- Depends on: Track 4 (RECORD shape + delta machinery + `OrderByComparator` base).
- Unblocks: Track 6b (MATCH_TUPLE_MULTI gate sits below the Etap A gate in `ShapeClassifier`); Track 7 (hardening covers MATCH bypass paths and NONE-shape behavior).

**Library / function signatures.**
- `MatchReturnProjector.build(List<SQLExpression>, String alias) → Function<RecordAbstract, Result>`.
- `ShapeClassifier.isMatchEtapA(SQLMatchStatement) → boolean` (helper).
- `CachedEntry.returnProjector` field — `@Nullable Function<RecordAbstract, Result>`.
