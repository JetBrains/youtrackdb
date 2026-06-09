<!-- workflow-sha: 660537f5848e796d2835e63338c9ddb483ca3e09 -->
# YTDB-820 Transaction-scoped query result cache — Design Mechanics

> Companion to `design.md`. Long-form derivations, full algorithm pseudocode,
> and exhaustive state-machine listings. Cross-references go one direction:
> `design.md → design-mechanics.md`. Section names match `design.md` so each
> `Mechanics:` footer link resolves by section name.

## Lazy merge-on-read

### View iteration — `view.next()` sorted-merge algorithm

Full pseudocode for `CachedResultSetView.next()` over a RECORD-shape entry.
The view holds an immutable `TxDeltaCursor` snapshotted at construction; the
cache entry's `entry.results` list and `cachedRids` set grow under lazy
stream-pull (filtered through the skip-set per § Stream-pull dispatch
unification in `design.md`).

```
view.next():
  while true:
    cache_head = (position < entry.results.size()) ? entry.results[position] : null

    // Suppress cached rows whose RID the delta marks for replacement or deletion.
    if cache_head != null && deltaCursor.shouldSkip(cache_head.rid):
      position++; continue

    // Materialize cache_head from the live stream BEFORE looking at delta_head.
    // The stream is the only source of records that sort between the already-
    // pulled prefix and the storage tail. If this step were skipped and the
    // delta carried any inject whose ORDER BY key sorts after some not-yet-pulled
    // storage row, we would return the delta inject ahead of those storage
    // rows, violating the sorted-merge invariant. Pulling here materializes
    // the next storage row (filtered through the skip-set so any post-mutation
    // RID is dropped), appends it to entry.results, and re-enters the loop:
    // the next iteration sees cache_head = that newly-pulled row.
    if cache_head == null && !entry.exhausted:
      r = stream_pull_one()  // skip-set-filtered append; sets entry.exhausted on drain
      if r != null:
        continue  // re-loop; cache_head will be r at position
      // r == null means the stream just exhausted; fall through with cache_head still null

    delta_head = deltaCursor.peek()  // null if delta exhausted

    // Both cursors exhausted, view is done.
    if cache_head == null && delta_head == null:
      throw NoSuchElementException

    // Cache exhausted, delta has rows: drain delta in sort order.
    if cache_head == null:
      return deltaCursor.pop()

    // Delta exhausted, cache has rows: drain cache in sort order.
    if delta_head == null:
      position++; return cache_head

    // Both cursors carry a head. Emit the smaller per ORDER BY. Ties favour
    // delta, since the only way a tie can occur is between distinct RIDs (a
    // skip-set hit on cache_head would have been consumed above), so either
    // ordering is correct per SQL-standard tie semantics; choosing delta keeps
    // mutated rows at-or-before equally-ranked cached rows.
    if cmp(delta_head, cache_head, orderBy) <= 0:
      return deltaCursor.pop()
    position++; return cache_head
```

### Notes on the sorted-merge

The cache cursor and the delta cursor advance independently. The cache cursor
is positional (`position` is a per-view int index into `entry.results`); the
delta cursor is per-view stateful but reads from the entry-shared
`(skipSet, injectList)` pair built once per `mutationVersion`. Skip-set hits
on the cache cursor consume a position without emitting; skip-set hits on a
stream pull cause the pull to drop the row and recurse.

Invariant preserved by the loop: a delta inject is never returned before a
stream-pullable cache row that sorts earlier. Materializing the next stream
row before consulting `delta_head` is the load-bearing step that holds this
invariant.

### MATCH multi-alias (partial Etap B in v1)

Full `DeltaBuilder.buildForMatchMulti(entry, tx, ctx)` pseudocode. The matching
`design.md` MATCH multi-alias section keeps the overview, field definitions, and the
covers / does-not-cover prose. Two-pass with a tombstone short-circuit; the
populate-version filter (D21) applies symmetrically, so only post-populate mutations
enter the build.

```
1. Snapshot filtered recordOps:
   snapshot = tx.recordOperations.values().stream()
              .filter(op -> op.version > entry.populateMutationVersion)
              .toList()

2. Tombstone-trigger pre-scan:
   for op in snapshot:
     cls = op.record's schema class name (Entity-guarded; non-Entity skips this op)
     if cls not in entry.effectiveFromClasses: continue
     // CREATE of any class can introduce a tuple the skip-only delta cannot inject;
     // an edge-class DELETE cannot be dropped incrementally (unbound edge RID untracked).
     if op.type == CREATED
        or (op.type == DELETED and cls is an edge class in effectiveFromClasses):
       entry.tombstoned = true
       return TOMBSTONE  // signal lookup to evict + miss

3. Build per-tuple skip set + per-RID skip set:
   tupleSkipSet = new HashSet<Integer>()
   ridSkipSet = new HashSet<RID>()
   for op in snapshot:
     class-filter via effectiveFromClasses; skip if no match
     rid = op.record.rid
     affectedTuples = reverseIndex.get(rid)  // may be empty
     if op.type == DELETED:
       tupleSkipSet.addAll(affectedTuples)
       ridSkipSet.add(rid)
     elif op.type == UPDATED:
       // (a) Update-into-match: a record now matching an alias WHERE it did not bind
       // may form new tuples a skip-only delta cannot inject. Gated on the alias having
       // a WHERE (a no-WHERE alias always matched, so a property update adds no binding).
       for alias in aliasClasses.keySet():
         if aliasWheres[alias] != null and op.record's class in aliasClasses[alias]
            and not rid-binds-alias(rid, alias)
            and aliasWheres[alias].matchesFilters(op.record, ctx):
           entry.tombstoned = true
           return TOMBSTONE
       // (b) Pass→fail drop for roles the record already binds (vertex or bound edge).
       for tupleIndex in affectedTuples:
         // find which alias(es) bind this rid in this tuple
         for alias in aliasClasses.keySet():
           if rid binds to alias in tuple[tupleIndex]:
             if !aliasWheres[alias].matchesFilters(op.record, ctx):
               tupleSkipSet.add(tupleIndex)
               break  // one failing alias is enough to drop the tuple
       ridSkipSet.add(rid)  // also suppress stream-pull-append re-emission of this RID
       // ridSkipSet on UPDATED guards against the stream emitting a tuple containing
       // this rid where storage hasn't yet seen the in-tx update — the WHERE check
       // would pass on stale storage state but fail on post-update state, and the
       // tuple wouldn't exist in fresh re-execution.

4. Return MatchMultiDelta(tupleSkipSet, ridSkipSet).
```

The step-2 pre-scan returns before the step-3 skip-set build, so step 3's `DELETED`
branch only ever sees vertex deletes (edge-class deletes already tombstoned in step 2).
`rid-binds-alias(rid, alias)` resolves from the cached tuple `Result` at
`entry.results[i]` via `getProperty(alias)`. The skip sets tie back to invariant I4
(view output equals fresh-execution composed with the tx-delta-applied snapshot).

### References

The above pseudocode is referenced from `design.md § Lazy merge-on-read`
under the `Mechanics:` link in that section's References footer. The view
iteration semantics tie back to invariants I4 (view output equals
fresh-execution composed with tx-delta-applied snapshot) and I7 (deltaCursor
immutable post-construction).
