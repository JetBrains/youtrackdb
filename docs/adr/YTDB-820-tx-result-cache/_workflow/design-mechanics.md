<!-- workflow-sha: 8995acfc3b0c50453595911342427c60742617b4 -->
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

### References

The above pseudocode is referenced from `design.md § Lazy merge-on-read`
under the `Mechanics:` link in that section's References footer. The view
iteration semantics tie back to invariants I4 (view output equals
fresh-execution composed with tx-delta-applied snapshot) and I7 (deltaCursor
immutable post-construction).
