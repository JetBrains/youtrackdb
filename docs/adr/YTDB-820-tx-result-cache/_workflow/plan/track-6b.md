# Track 6b: MATCH delta — partial Etap B (MATCH_TUPLE_MULTI)

## Purpose / Big Picture

BLUF: After this track, cached multi-alias MATCH queries (more than one pattern node, or any pattern node with edges, or cross-join with multiple top-level match-expressions) cache as a new shape `MATCH_TUPLE_MULTI` and survive DELETED + UPDATED mutations via a per-tuple `reverseIndex`; CREATED on a pattern class tombstones the entry and forces re-execution at next lookup.

Introduces `CacheableShape.MATCH_TUPLE_MULTI`, `MatchMultiDelta` (immutable per-view delta with `tupleSkipSet` + `ridSkipSet`), and `DeltaBuilder.buildForMatchMulti` (two-pass: tombstone pre-scan + skip-set build). Patterns with classless nodes / cross-alias-state / subqueries in pattern WHEREs classify as `K0_NONE` (cacheable under D18's mutation-version gate; not reachable by MATCH delta-build). Full Etap B (constrained-pattern-walk discovery of new tuples on CREATED) is deferred to a separate ADR — see D8-lazy.

## Context and Orientation

**Codebase state at track start.** After Tracks 4-5 and Track 6a: RECORD and AGGREGATE delta work, plus Etap A single-alias MATCH. This track adds the multi-alias MATCH path.

Existing relevant code (same as Track 6a):
- `SQLMatchStatement`, `SQLMatchExpression.{origin, items}`, `SQLMatchFilter.{getAlias, getClassName, getFilter}` (read-only).
- `MatchPrefetchStep` + `PREFETCHED_MATCH_ALIAS_PREFIX` — separate-ADR primitive for full Etap B's CREATED-discovery. NOT used in v1.

**Concrete deliverables.**
- `ShapeClassifier.classify(SQLMatchStatement)` MATCH_TUPLE_MULTI branch — applies the conditions in step 1 below. Runs after Track 6a's Etap A gate fails.
- `CacheableShape.MATCH_TUPLE_MULTI` enum value.
- Entry construction extension — MATCH_TUPLE_MULTI entries store: `effectiveFromClasses = ⋃ aliasClasses.values()`, `aliasClasses: Map<String, Set<String>>` (per-alias subclass closure), `aliasWheres: Map<String, SQLWhereClause>`, `contributingRids: Map<Integer, Set<RID>>` (populated incrementally during stream-pull), `reverseIndex: Map<RID, Set<Integer>>` (populated incrementally), `tombstoned: boolean` (default false).
- Stream-pull-append walker — for each pulled tuple `r` appended to `entry.results` at tuple-index `i`, iterate aliases in `aliasClasses.keySet()`; for each alias `a`, read `r.getProperty(a).getIdentity()` to get the bound RID; update `contributingRids[i].add(rid)` + `reverseIndex.computeIfAbsent(rid, _ -> new HashSet<>()).add(i)`.
- `DeltaBuilder.buildForMatchMulti(entry, recordOps, ctx)` → `MatchMultiDelta` or TOMBSTONE sentinel — two-pass algorithm with tombstone pre-scan + per-tuple skip set + RID skip set (see step 2).
- `MatchMultiDelta` class — immutable per-view delta: `Set<Integer> tupleSkipSet`, `Set<RID> ridSkipSet`. No injectList (partial Etap B does not discover new tuples).
- Cache lookup tombstone handling — `QueryResultCache.lookup` for MATCH_TUPLE_MULTI entries invokes the DeltaBuilder; if TOMBSTONE sentinel returned, evict entry + return null (miss); else cache the `MatchMultiDelta` per Option C sharing.
- `CachedResultSetView` extension for MATCH_TUPLE_MULTI — `view.next()` iterates `entry.results`, skipping tuples whose index is in `tupleSkipSet`. On stream-pull-append: drop the pulled tuple if any alias's bound RID is in `ridSkipSet` (drop, pull next); else append + populate reverseIndex + contributingRids for the new tuple index.

## Plan of Work

1. `ShapeClassifier.classify(SQLMatchStatement)` MATCH_TUPLE_MULTI branch (runs after Track 6a's Etap A check fails). MATCH_TUPLE_MULTI iff:
   - Etap A conditions failed (multi-alias or pattern-with-edges or cross-join)
   - Every pattern node (origin + every path item's filter) returns a non-null `getClassName(ctx)`
   - No `LET` / `UNWIND` in scope
   - No pattern-node WHERE references cross-alias state (walk each filter's WHERE AST for `$current`, `$matched`, `$parent`, `$depth`, or `${someOtherAlias}.field` references)
   - No subqueries (nested `SQLSelectStatement` descendant) in any pattern-node WHERE
   - **No SKIP, no LIMIT** (presence routes to K0_NONE via the first-gate check in `ShapeClassifier`)
   If pass: classify returns MATCH_TUPLE_MULTI. Else K0_NONE (delta-unreconcilable but D18 still caches under mutation-version gate).

2. `DeltaBuilder.buildForMatchMulti(entry, tx, ctx) → MatchMultiDelta | TOMBSTONE`. Two-pass algorithm:

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

3. `QueryResultCache.lookup(key)` extension — when `entry.shape == MATCH_TUPLE_MULTI`:
   ```
   delta = DeltaBuilder.buildForMatchMulti(entry, tx, ctx)
   if delta == TOMBSTONE:
     entries.remove(key)
     return null  // miss; caller falls through to statement.execute(...)
   // else delta is a MatchMultiDelta; store on entry for sharing and return entry
   ```

4. `CachedResultSetView` MATCH_TUPLE_MULTI branch. View carries the `MatchMultiDelta` ref. `view.next()`:
   ```
   while true:
     while position < entry.results.size():
       if matchMultiDelta.tupleSkipSet.contains(position):
         position++
         continue
       result = entry.results[position]
       position++
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
   MATCH_TUPLE_MULTI does not carry SKIP / LIMIT — those route to K0_NONE upstream.

5. Stream-pull-append population — make sure entry.contributingRids and entry.reverseIndex are kept in sync with entry.results at all times. The population pass during initial stream pull runs at view-construction time (cache-miss); the same logic is re-used by step 4's stream-pull-append for late tuples.

6. Test matrix (T6 set, partial Etap B subset):
    - **T6e** (MATCH_TUPLE_MULTI classify pass): pattern `MATCH {as:i, class:Issue}.out('project'){as:p, class:Project} RETURN i, p` — classify returns MATCH_TUPLE_MULTI.
    - **T6f** (MATCH_TUPLE_MULTI classify NONE for classless node): `MATCH {as:any}.out('memberOf'){as:g, class:Group} RETURN any, g` (no `class:` on first alias) → classify NONE.
    - **T6g** (MATCH_TUPLE_MULTI classify NONE for subquery in pattern WHERE): `MATCH {as:u, class:User WHERE id IN (SELECT id FROM Active)} RETURN u` → classify NONE.
    - **T6i** (partial Etap B DELETED): pre-populate `MATCH {as:i, class:Issue}.out('project'){as:p, class:Project} RETURN i, p` with 10 (Issue, Project) tuples. `tx.delete(issue_with_rid_X)`. Re-query → assert: tuples containing issue X are skipped; tuples containing other issues remain. `reverseIndex.get(X)` was consulted; affected tuples in `tupleSkipSet`.
    - **T6j** (partial Etap B UPDATED, WHERE-still-passes): same shape. `tx.save(issue_X with priority changed but no WHERE-fail)`. Re-query → assert: tuples containing X stay (post-update record satisfies the `i` alias's WHERE).
    - **T6k** (partial Etap B UPDATED, WHERE-fails): same shape. `tx.save(issue_X with status changed so it no longer matches origin's WHERE)`. Re-query → assert: tuples containing X are dropped.
    - **T6l** (partial Etap B CREATED tombstone): same shape. `tx.save(new Issue)`. Re-query → assert: entry was removed (cache.size dropped by 1 for this key, then re-populated on miss); subsequent re-query returns fresh tuples including the new issue.
    - **T6m** (multi-alias-same-class self-loop): `MATCH {as:u, class:User}.out('reportsTo'){as:m, class:User} RETURN u, m`. UPDATED user X (bound to both u and m positions in different tuples). Verify each tuple is re-evaluated against BOTH aliases' WHEREs; if either fails, the tuple is dropped.
    - **T6n** (cross-join CREATED tombstone): `MATCH {as:u, class:User}, {as:g, class:Group} RETURN u, g` (cross-join). Pre-populate. `tx.save(new User)` → entry tombstoned (classify=MATCH_TUPLE_MULTI; CREATED on a class in effectiveFromClasses tombstones per partial Etap B).
    - **T6o** (stream-pull-append with RID skip): partial-populated MATCH_TUPLE_MULTI entry. tx.delete(issue_in_uncached_storage_tail). Stream-pull pulls the deleted issue from storage tail (in-memory record-cache may emit it); view's stream-pull-append checks ridSkipSet, drops the tuple, continues. Verify the view never emits a tuple containing the deleted issue.
    - **T6p** (Option C delta sharing): construct view-A on a MATCH_TUPLE_MULTI entry at mutationVersion=5; entry.cachedMatchMultiDelta is shared. View-B at same version reuses by reference. View-C after a new mutation rebuilds (tombstone or fresh skipSet).

**Invariants to preserve.** I4 for partial Etap B: view output equivalent to fresh MATCH execution against the (cached + tx-delta) snapshot for the DELETED + UPDATED subset; CREATED in MATCH_TUPLE_MULTI tombstones the entry so re-execution sees the new tuple via fresh storage scan.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (MATCH_TUPLE_MULTI classify branch)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (MATCH_TUPLE_MULTI fields: `aliasClasses`, `aliasWheres`, `contributingRids`, `reverseIndex`, `tombstoned`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CacheableShape.java` (add `MATCH_TUPLE_MULTI` enum value)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (new buildForMatchMulti)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/MatchMultiDelta.java` (new — immutable delta type for MATCH_TUPLE_MULTI)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (MATCH_TUPLE_MULTI shape branch in next() with tupleSkipSet + stream-pull-append ridSkipSet filter + late-tuple reverseIndex maintenance)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryResultCache.java` (lookup tombstone handling for MATCH_TUPLE_MULTI)

**Out-of-scope files.**
- `MatchExecutionPlanner`, `MatchFirstStep`, `MatchPrefetchStep` — full Etap B (CREATED constrained-walk discovery) is a separate ADR; v1 tombstones on CREATED and re-executes.
- `SQLMatchExpression`, `SQLMatchFilter`, `SQLMatchPathItem` — read-only; no modifications.
- `MatchReturnProjector`, `CachedEntry.returnProjector`, Etap A classify branch — Track 6a.

**Inter-track dependencies.**
- Depends on: Track 6a (Etap A classify gate must fail before MATCH_TUPLE_MULTI gate evaluates; both share `ShapeClassifier.classify(SQLMatchStatement)` entry point).
- Unblocks: Track 7 (hardening covers MATCH bypass paths and NONE-shape behavior).

**Library / function signatures.**
- `ShapeClassifier.isMatchTupleMulti(SQLMatchStatement) → boolean` (helper).
- `CachedEntry.aliasClasses` / `aliasWheres` / `contributingRids` / `reverseIndex` / `tombstoned` fields — populated only when shape == MATCH_TUPLE_MULTI.
- `DeltaBuilder.buildForMatchMulti(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → MatchMultiDelta` (or TOMBSTONE sentinel object).
- `MatchMultiDelta(Set<Integer> tupleSkipSet, Set<RID> ridSkipSet)` — immutable constructor; getters for both sets.
