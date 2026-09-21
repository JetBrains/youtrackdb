# Index-path ORDER BY leak, name matching, and BG2200

## Summary

Three defects remain on the ordered-slice / translator stack. Track 11 deletes
minted `_$$$ORDER_BY_ALIAS$$$_N` columns with a mint-fact `removeProperty` step
that runs even when an index already applied ORDER BY. Track 12 refuses
index-served ORDER BY unless every sort key is a bare pass-through property.
Track 13 records typed emitted-column descriptors after modulated `select().by`
so a trailing `select(label)` reads the map cell, not the pattern vertex.

## Decision records

### DR-T11 — Mint-fact deletion, not rebuild strip alone

`addOrderByProjections` mints synthetic aliases and builds `projectionAfterOrderBy`.
`handleOrderBy` chains that strip only when `!orderApplied`. Sort-only index fetch
sets `orderApplied = true` and skips the strip. Deferred plain keys often never
mint, so the classic `SELECT marker … ORDER BY name` path is masked; LET variables
and aggregates still mint and leak.

**Decision:** record the exact minted alias set on `QueryPlanningInfo`. After the
user projection and before DISTINCT, chain a cacheable step that calls
`ResultInternal.removeProperty` for each minted name. Do not rely on alias-list
rebuild of `projectionAfterOrderBy` as the only strip (wildcard / exclusion
rebuild is already broken and pinned).

Closes AD52–AD57 for the leak surface: aggregate path uses the same step; strip
before Distinct; exact mint set avoids AD55 prefix wipe; no `MatchResultRow`
special case; `canBeCached() == true`.

### DR-T12 — Bare-property predicates at both index call sites

Sort-only matching uses `indexField.equals(alias)` and ignores modifiers.
`fullySorted` / `getProperties` does the same. Shadowed aliases and
`ORDER BY name.length()` therefore claim an index on raw `name`.

**Decision:** two separate predicates (not shared with the deferral helper).
Sort-only requires `modifier == null` and either an exact field alias or a
pass-through projection of that field. WHERE fullySorted refuses any ORDER BY
item with a modifier. Nested-projection correctness stays plan-shape only.

### DR-T13 — Typed emitted-column descriptors for BG2200

After `select(a,b).by(name).by(city)`, native `select(a)` reads the map cell.
Translated `configureSelect` resolves `a` through `userLabelToAlias` and emits a
Vertex. Post-cardinality multi-select overlap is already declined; the open hole
is the dedup-less main line (`mapSelectThenOverlappingSelectOne_knownUnfixedWithoutDedup`).

**Decision:** on multi-label modulated select accept, register per-label emit
descriptors (scalar-from-property, record attribute). Trailing `configureSelect`
projects from the descriptor. Do not register after a singleton modulated select
— that unwraps to a scalar, so a trailing `select(label)` rebinds through the path
(native SelectOne). Clear descriptors on hops that change stream type
(`pinBoundary`). Fail-closed decline when a requested label lacks a descriptor.
Share `trailingSelectOverlaps` on `SelectOneStepRecogniser` as interim safety under
post-cardinality containment.

## Out of scope

Track 10 budget closeout; research-log tracks 06/07; nested-projection row-order
semantics; narrow decline as the BG2200 fix.
