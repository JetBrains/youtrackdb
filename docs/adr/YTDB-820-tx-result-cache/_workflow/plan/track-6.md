# Track 6: MATCH delta — Etap A (single-alias) + partial Etap B (multi-alias DELETE/UPDATE/tombstone-on-CREATE)

## Purpose / Big Picture

BLUF: After this track, cached MATCH queries reflect intra-tx mutations. Single-alias MATCH (Etap A) folds into RECORD shape with a `returnProjector`. Multi-alias MATCH (partial Etap B, in v1) caches as a new shape `MATCH_TUPLE_MULTI` and survives DELETED + UPDATED mutations via a per-tuple `reverseIndex`; CREATED on a pattern class tombstones the entry and forces re-execution at next lookup.

Extends `ShapeClassifier` for both shapes. Etap A keeps the existing RECORD path with `returnProjector`. Partial Etap B introduces `MATCH_TUPLE_MULTI` with `aliasClasses`, `aliasWheres`, `contributingRids`, `reverseIndex`, `tombstoned` on the entry, plus `DeltaBuilder.buildForMatchMulti` and `MatchMultiDelta`. Full Etap B (constrained-pattern-walk discovery of new tuples on CREATED) is deferred to a separate ADR — see D8-lazy.

## Context and Orientation

**Codebase state at track start.** After Tracks 4-5: RECORD and AGGREGATE delta work for SELECT. This track adds both MATCH paths.

Existing relevant code:
- `SQLMatchStatement` — `matchExpressions: List<SQLMatchExpression>` and `returnItems: List<SQLExpression>`.
- Each `SQLMatchExpression` has `origin: SQLMatchFilter` (the start node) and `items: List<SQLMatchPathItem>` (the edges). Etap A condition: `matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()`.
- `SQLMatchFilter` (origin / path-item filter) exposes accessors `getAlias()` / `getClassName(CommandContext)` / `getFilter()` over an internal `items: List<SQLMatchFilterItem>` (the parser breaks one `{as:u, class:X, where: …}` block into one or more items). For Etap A's no-edges single-binding case there is exactly one item; the accessors iterate items and return the first non-null match.
- `MatchPrefetchStep` + `PREFETCHED_MATCH_ALIAS_PREFIX` — separate-ADR primitive for full Etap B's CREATED-discovery. NOT used in v1.

**Etap A (single-alias) concrete deliverables.**
- `ShapeClassifier.classify(SQLMatchStatement)` Etap A branch — applies the conditions in Plan-of-Work step 1; if pass, returns RECORD and the entry is populated with the projector + alias-derived metadata.
- `returnProjector: Function<RecordAbstract, Result>` — closure built at entry construction. For RETURN clause like `RETURN u, u.name`, projector takes a record and produces `Result{u: rec, name: rec.name}` matching the original execution's output shape.
- Entry construction extension — MATCH-flavored RECORD entries store: `effectiveFromClasses = {origin.clazz} ∪ subclass closure` (D11), `whereClause = origin.filter`, `orderBy = MATCH's ORDER BY`, `returnProjector`.
- `DeltaBuilder.buildForRecord` — extended to call `entry.returnProjector(rec, ctx)` when constructing inject-list entries for MATCH-flavored RECORD entries. SELECT-flavored entries skip the projector (or use identity projector).

**Partial Etap B (multi-alias MATCH_TUPLE_MULTI) concrete deliverables.**
- `ShapeClassifier.classify(SQLMatchStatement)` MATCH_TUPLE_MULTI branch — applies the conditions in step 5 below.
- `CacheableShape.MATCH_TUPLE_MULTI` enum value.
- Entry construction extension — MATCH_TUPLE_MULTI entries store: `effectiveFromClasses = ⋃ aliasClasses.values()`, `aliasClasses: Map<String, Set<String>>` (per-alias subclass closure), `aliasWheres: Map<String, SQLWhereClause>`, `contributingRids: Map<Integer, Set<RID>>` (populated incrementally during stream-pull), `reverseIndex: Map<RID, Set<Integer>>` (populated incrementally), `tombstoned: boolean` (default false).
- Stream-pull-append walker — for each pulled tuple `r` appended to `entry.results` at tuple-index `i`, iterate aliases in `aliasClasses.keySet()`; for each alias `a`, read `r.getProperty(a).getIdentity()` to get the bound RID; update `contributingRids[i].add(rid)` + `reverseIndex.computeIfAbsent(rid, _ -> new HashSet<>()).add(i)`.
- `DeltaBuilder.buildForMatchMulti(entry, recordOps, ctx)` → `MatchMultiDelta` or TOMBSTONE sentinel — two-pass algorithm with tombstone pre-scan + per-tuple skip set + RID skip set (see step 6).
- `MatchMultiDelta` class — immutable per-view delta: `Set<Integer> tupleSkipSet`, `Set<RID> ridSkipSet`. No injectList (partial Etap B does not discover new tuples).
- Cache lookup tombstone handling — `QueryResultCache.lookup` for MATCH_TUPLE_MULTI entries invokes the DeltaBuilder; if TOMBSTONE sentinel returned, evict entry + return null (miss); else cache the `MatchMultiDelta` per Option C sharing.
- `CachedResultSetView` extension for MATCH_TUPLE_MULTI — `view.next()` iterates `entry.results`, skipping tuples whose index is in `tupleSkipSet`. On stream-pull-append: drop the pulled tuple if any alias's bound RID is in `ridSkipSet` (drop, pull next); else append + populate reverseIndex + contributingRids for the new tuple index.

## Plan of Work

### Etap A (single-alias) — same as prior Track 6, retained

1. `ShapeClassifier.classify(SQLMatchStatement)` Etap A — condition check. Etap A iff:
   - `matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()` (single node, no edges)
   - `origin.getClassName(ctx)` is non-null
   - Origin's `where:` clause has no cross-alias-state references (`$current`, `$matched`, `${otherAlias}.…`)
   - No LET / UNWIND
   - No subqueries in pattern WHERE
   - Every `returnItem` resolves to an expression on the single alias (or its modifiers); references to `$matched`, `$current`, another alias → fall back to NONE
   If pass: classify returns RECORD with `entry.returnProjector` populated. Otherwise fall through to the MATCH_TUPLE_MULTI gate.

2. `returnProjector` builder — given `returnItems: List<SQLExpression>` and the alias name, build a closure that, on each invocation `(rec, ctx)`: (a) constructs a `ResultInternal` binding the record under the alias name (e.g., `Result{alias → rec}`); (b) sets `ctx.setVariable(alias, boundResult)` so that `SQLExpression.execute` resolves `alias.field` references correctly; (c) iterates `returnItems` and calls `expr.execute(boundResult, ctx)` for each, accumulating the (alias, value) pairs into the output Result. Without step (b) the binding is missing and `u.someProp + 1` would fail to resolve `u`.

3. `DeltaBuilder.buildForRecord` integration — flag on entry indicates "use returnProjector" path; defaults to identity for SELECT-flavored entries.

4. `OrderByComparator` for MATCH — projected tuples can have ORDER BY on either record properties (`ORDER BY u.name`) or projection aliases (`ORDER BY name`). Build comparator that resolves to the appropriate value in the projected `Result`.

### Partial Etap B (multi-alias MATCH_TUPLE_MULTI) — new

5. `ShapeClassifier.classify(SQLMatchStatement)` MATCH_TUPLE_MULTI branch (runs after Etap A check fails). MATCH_TUPLE_MULTI iff:
   - Etap A conditions failed (multi-alias or pattern-with-edges or cross-join)
   - Every pattern node (origin + every path item's filter) returns a non-null `getClassName(ctx)`
   - No `LET` / `UNWIND` in scope
   - No pattern-node WHERE references cross-alias state (walk each filter's WHERE AST for `$current`, `$matched`, `$parent`, `$depth`, or `${someOtherAlias}.field` references)
   - No subqueries (nested `SQLSelectStatement` descendant) in any pattern-node WHERE
   - SKIP / LIMIT bounded by `n + m <= maxRecordsPerEntry` if present (D10-lazy gate symmetric with RECORD)
   If pass: classify returns MATCH_TUPLE_MULTI. Else NONE.

6. `DeltaBuilder.buildForMatchMulti(entry, tx, ctx) → MatchMultiDelta | TOMBSTONE`. Two-pass algorithm:

   **Pass 1 — Tombstone pre-scan**:
   ```
   snapshot = new ArrayList<>(tx.recordOperations.values())
   for op in snapshot:
     if op.type != CREATED: continue
     rec = op.record
     if !(rec instanceof Entity entity): continue  // non-Entity records can't bind into MATCH
     cls = entity.getSchemaClass()
     if cls == null: continue
     if entry.effectiveFromClasses.contains(cls.getName()):
       entry.tombstoned = true
       return TOMBSTONE  // signal lookup to evict + miss
   ```

   **Pass 2 — Build tupleSkipSet + ridSkipSet**:
   ```
   tupleSkipSet = new HashSet<Integer>()
   ridSkipSet = new HashSet<RID>()
   for op in snapshot:
     rec = op.record
     if !(rec instanceof Entity entity): continue
     cls = entity.getSchemaClass()
     if cls == null: continue
     if !entry.effectiveFromClasses.contains(cls.getName()): continue
     rid = entity.getIdentity()
     affectedTuples = entry.reverseIndex.getOrDefault(rid, emptySet())
     if op.type == DELETED:
       tupleSkipSet.addAll(affectedTuples)
       ridSkipSet.add(rid)
     elif op.type == UPDATED:
       for tupleIndex in affectedTuples:
         // find which alias(es) bind this rid in this tuple
         for alias in entry.aliasClasses.keySet():
           classes = entry.aliasClasses.get(alias)
           if classes.contains(cls.getName()):
             // this alias could bind a record of this class
             where = entry.aliasWheres.get(alias)
             if where != null && !where.matchesFilters(entity, ctx):
               tupleSkipSet.add(tupleIndex)
               break  // one failing alias is enough to drop the tuple
       ridSkipSet.add(rid)
   return new MatchMultiDelta(unmodifiableSet(tupleSkipSet), unmodifiableSet(ridSkipSet))
   ```

   Cross-view sharing via `mutationVersion` (Option C) symmetric with RECORD / AGGREGATE: entry caches the latest `(tupleSkipSet, ridSkipSet, mutationVersion)` triple; new views at the same mutationVersion reuse it; new views at a fresher version trigger rebuild.

7. `QueryResultCache.lookup(key)` extension — when `entry.shape == MATCH_TUPLE_MULTI`:
   ```
   delta = DeltaBuilder.buildForMatchMulti(entry, tx, ctx)
   if delta == TOMBSTONE:
     entries.remove(key)
     return null  // miss; caller falls through to statement.execute(...)
   // else delta is a MatchMultiDelta; store on entry for sharing and return entry
   ```

8. `CachedResultSetView` MATCH_TUPLE_MULTI branch. View carries the `MatchMultiDelta` ref. `view.next()`:
   ```
   while true:
     while position < entry.results.size():
       if matchMultiDelta.tupleSkipSet.contains(position):
         position++
         continue
       result = entry.results[position]
       position++
       if shouldApplySkipLimit(): handle skip/limit accounting
       return result
     // Cache exhausted, try stream-pull
     if entry.exhausted:
       throw NoSuchElementException
     r = entry.stream.next(entry.ctx)
     // For each alias in aliasClasses, check the new tuple's binding RID against ridSkipSet
     newTupleIndex = entry.results.size()
     dropped = false
     for alias in entry.aliasClasses.keySet():
       boundRid = r.getProperty(alias).getIdentity()
       if matchMultiDelta.ridSkipSet.contains(boundRid):
         dropped = true
         break
     if dropped:
       continue  // don't append; pull next
     entry.results.add(r)
     // Populate reverseIndex + contributingRids for the new tuple
     for alias in entry.aliasClasses.keySet():
       boundRid = r.getProperty(alias).getIdentity()
       entry.contributingRids.computeIfAbsent(newTupleIndex, _ -> new HashSet<>()).add(boundRid)
       entry.reverseIndex.computeIfAbsent(boundRid, _ -> new HashSet<>()).add(newTupleIndex)
     return r
   ```
   SKIP / LIMIT applied via the same `emitted` counter as RECORD shape.

9. Stream-pull-append population — make sure entry.contributingRids and entry.reverseIndex are kept in sync with entry.results at all times. The population pass during initial stream pull runs at view-construction time (cache-miss); the same logic is re-used by step 8's stream-pull-append for late tuples.

10. Test matrix (T6 set):
    - **T6a** (Etap A CREATE): single-alias MATCH; new record matching WHERE appears as tuple in view.
    - **T6b** (Etap A UPDATE): record's WHERE-relevant prop changes to/from matching.
    - **T6c** (Etap A DELETE): cached tuple disappears from view.
    - **T6d** (Etap A classify-NONE for cross-alias): cross-alias WHERE (`WHERE name = $matched.u.name`) — classify NONE.
    - **T6e** (MATCH_TUPLE_MULTI classify pass): pattern `MATCH {as:i, class:Issue}.out('project'){as:p, class:Project} RETURN i, p` — classify returns MATCH_TUPLE_MULTI.
    - **T6f** (MATCH_TUPLE_MULTI classify NONE for classless node): `MATCH {as:any}.out('memberOf'){as:g, class:Group} RETURN any, g` (no `class:` on first alias) → classify NONE.
    - **T6g** (MATCH_TUPLE_MULTI classify NONE for subquery in pattern WHERE): `MATCH {as:u, class:User WHERE id IN (SELECT id FROM Active)} RETURN u` → classify NONE.
    - **T6h** (L5 ctx-binding for Etap A): MATCH `{as:u, class:User WHERE active=true} RETURN u.name, u.age * 2 AS double_age` — Etap A delta CREATE produces a Result with correct `u.name` AND correct `double_age` from the projector closure.
    - **T6i** (partial Etap B DELETED): pre-populate `MATCH {as:i, class:Issue}.out('project'){as:p, class:Project} RETURN i, p` with 10 (Issue, Project) tuples. `tx.delete(issue_with_rid_X)`. Re-query → assert: tuples containing issue X are skipped; tuples containing other issues remain. `reverseIndex.get(X)` was consulted; affected tuples in `tupleSkipSet`.
    - **T6j** (partial Etap B UPDATED, WHERE-still-passes): same shape. `tx.save(issue_X with priority changed but no WHERE-fail)`. Re-query → assert: tuples containing X stay (post-update record satisfies the `i` alias's WHERE).
    - **T6k** (partial Etap B UPDATED, WHERE-fails): same shape. `tx.save(issue_X with status changed so it no longer matches origin's WHERE)`. Re-query → assert: tuples containing X are dropped.
    - **T6l** (partial Etap B CREATED tombstone): same shape. `tx.save(new Issue)`. Re-query → assert: entry was removed (cache.size dropped by 1 for this key, then re-populated on miss); subsequent re-query returns fresh tuples including the new issue.
    - **T6m** (multi-alias-same-class self-loop): `MATCH {as:u, class:User}.out('reportsTo'){as:m, class:User} RETURN u, m`. UPDATED user X (bound to both u and m positions in different tuples). Verify each tuple is re-evaluated against BOTH aliases' WHEREs; if either fails, the tuple is dropped.
    - **T6n** (cross-join CREATED tombstone): `MATCH {as:u, class:User}, {as:g, class:Group} RETURN u, g` (cross-join). Pre-populate. `tx.save(new User)` → entry tombstoned (classify=MATCH_TUPLE_MULTI; CREATED on a class in effectiveFromClasses tombstones per partial Etap B).
    - **T6o** (stream-pull-append with RID skip): partial-populated MATCH_TUPLE_MULTI entry. tx.delete(issue_in_uncached_storage_tail). Stream-pull pulls the deleted issue from storage tail (in-memory record-cache may emit it); view's stream-pull-append checks ridSkipSet, drops the tuple, continues. Verify the view never emits a tuple containing the deleted issue.
    - **T6p** (Option C delta sharing): construct view-A on a MATCH_TUPLE_MULTI entry at mutationVersion=5; entry.cachedMatchMultiDelta is shared. View-B at same version reuses by reference. View-C after a new mutation rebuilds (tombstone or fresh skipSet).

**Invariants to preserve.** I4 for both Etap A and partial Etap B: view output equivalent to fresh MATCH execution against the (cached + tx-delta) snapshot for the DELETED + UPDATED subset; CREATED in MATCH_TUPLE_MULTI tombstones the entry so re-execution sees the new tuple via fresh storage scan.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (MATCH classify rules for both Etap A and MATCH_TUPLE_MULTI)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (Etap A `returnProjector` + MATCH_TUPLE_MULTI fields: `aliasClasses`, `aliasWheres`, `contributingRids`, `reverseIndex`, `tombstoned`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CacheableShape.java` (add `MATCH_TUPLE_MULTI` enum value)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (Etap A integration in buildForRecord; new buildForMatchMulti)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/MatchMultiDelta.java` (new — immutable delta type for MATCH_TUPLE_MULTI)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/OrderByComparator.java` (projected-tuple ORDER BY support for both Etap A and MATCH_TUPLE_MULTI)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/MatchReturnProjector.java` (Etap A closure builder utility)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (MATCH_TUPLE_MULTI shape branch in next() with tupleSkipSet + stream-pull-append ridSkipSet filter + late-tuple reverseIndex maintenance)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryResultCache.java` (lookup tombstone handling for MATCH_TUPLE_MULTI)

**Out-of-scope files.**
- `MatchExecutionPlanner`, `MatchFirstStep`, `MatchPrefetchStep` — full Etap B (CREATED constrained-walk discovery) is a separate ADR; v1 tombstones on CREATED and re-executes.
- `SQLMatchExpression`, `SQLMatchFilter`, `SQLMatchPathItem` — read-only; no modifications.

**Inter-track dependencies.**
- Depends on: Track 4 (RECORD shape + delta machinery + `OrderByComparator`).
- Unblocks: Track 7 (hardening covers MATCH bypass paths and NONE-shape behavior).

**Library / function signatures.**
- `MatchReturnProjector.build(List<SQLExpression>, String alias) → Function<RecordAbstract, Result>`.
- `ShapeClassifier.isMatchEtapA(SQLMatchStatement) → boolean` (helper).
- `ShapeClassifier.isMatchTupleMulti(SQLMatchStatement) → boolean` (helper).
- `CachedEntry.returnProjector` field — `@Nullable Function<RecordAbstract, Result>`.
- `CachedEntry.aliasClasses` / `aliasWheres` / `contributingRids` / `reverseIndex` / `tombstoned` fields — populated only when shape == MATCH_TUPLE_MULTI.
- `DeltaBuilder.buildForMatchMulti(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → MatchMultiDelta` (or TOMBSTONE sentinel object).
- `MatchMultiDelta(Set<Integer> tupleSkipSet, Set<RID> ridSkipSet)` — immutable constructor; getters for both sets.
