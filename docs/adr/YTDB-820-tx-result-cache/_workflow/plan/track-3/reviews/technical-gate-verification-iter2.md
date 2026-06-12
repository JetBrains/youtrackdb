<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: T5, verdict: VERIFIED}
  - {id: T6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 3 technical gate-verification — iteration 2

Re-check of six ACCEPTED Phase A technical findings against the amended track
file (`plan/track-3.md`) and the cited sources. Source citations the fixes lean
on were re-read directly (PSI `steroid_execute_code` skipped to avoid
kotlinc-compile contention; symbol facts confirmed by reading the cited files).
All six fixes landed. No new findings.

## Findings

<!-- No new findings surfaced by this verification pass. -->

## Evidence base

#### C1 ShapeClassifier unconditional MATCH return + classifySelect SKIP/LIMIT-first gate — MATCHES
`ShapeClassifier.classify` returns `CacheableShape.MATCH_TUPLE_MULTI` for any
`SQLMatchStatement` (lines 137-142). `classifySelect` gates SKIP/LIMIT first
(152-154), then GROUP BY/LET/UNWIND/subquery (159-164). The track's "mirror
classifySelect" framing is accurate. (grep-only)

#### C2 SQLMatchStatement gate fields exist with equals/hashCode coverage — MATCHES
`notMatchExpressions` (line 30), `returnDistinct` (34), `groupBy` (35), `unwind`
(37) are real fields; `returnsPathElements()` (266), `returnsElements()` (275),
`buildPatterns()` (211), `addAliases(...)` (306/319) all present. equals covers
notMatchExpressions/groupBy/unwind/returnDistinct (521-549); hashCode mirrors
(555-562). Confirms T1's full-gate roster and T2's RETURN-mode routing both
reference live AST surface. (grep-only)

#### C3 SQLMatchPathItem edge-class shape — MATCHES
`method` field (line 21); `graphPath` folds a `null` edge name to `"E"`
(32-36), sets `methodName.value = direction` (38-39), and adds the edge class as
the first param expression (40-43). Confirms step 3's extractor contract
(direction = methodName, edge class = first param, null→"E" default). (grep-only)

#### C4 getMatchPatternInvolvedAliases exists; link-deref distinct — MATCHES
`SQLWhereClause` uses `getMatchPatternInvolvedAliases()` for cross-alias detection
(lines 811-844). The track's claim that this does NOT detect link-path derefs and
needs a dedicated walk is consistent with the method's purpose (alias-set
membership, not path-head classification). (grep-only)

#### C5 CachedEntry single-class closure is genuinely single-class — MATCHES
`computeEffectiveFromClasses(@Nullable SchemaClass)` takes one class, unions its
`getAllSubclasses()` (147-157). A multi-class union builder
(`computeMatchEffectiveFromClasses`) is genuinely new, as T3/step 3 state. (grep-only)

#### C6 QueryResultCache overflowEntry vs invalidate vs lookup signature — MATCHES
`overflowEntry` removes from map + routes key to nonCacheableKeys, does NOT close
the stream (177-182). `invalidate` does immediate `entry.close()` (210-213).
`lookup(CacheKey, long)` carries no transaction/context (107). Confirms T5: a
pinned-entry-safe `removeForTombstone` following overflowEntry discipline is the
right new helper, and building the delta in `lookup` was correctly rejected for
the missing tx/ctx. (grep-only)

#### C7 DatabaseSessionEmbedded SELECT-only helpers + viewOwnsGuard transfer — MATCHES
`effectiveFromClasses` (1134-1144), `whereClauseOf` (1145), `orderByOf` (1149) are
`instanceof SQLSelectStatement`-gated, returning `Set.of()`/`null` for MATCH.
`serveThroughCache` (741) already admits `SQLMatchStatement` at the shape gate
(761) and uses `viewOwnsGuard = result instanceof CachedResultSetView` (809/824);
`buildView` (1084) is where RECORD/aggregate deltas build. Confirms T3's five
touch-points and T5's "build in buildView, not lookup" placement. (grep-only)

#### Verify T1: classify gate full K0_NONE roster — VERIFIED
- **Original issue**: classify gate was a lossy subset of `classifySelect`.
- **Fix applied**: Plan-of-Work step 1 (track lines 104-142) now lists SKIP/LIMIT
  first, then GROUP BY / UNWIND / RETURN DISTINCT / NOT MATCH, non-alias-keyed
  RETURN mode, LET/subquery, per-node `class:`, cross-alias-state WHERE,
  link-deref WHERE, non-static edge/class label, and `n+m>maxRecordsPerEntry`.
  Validation adds K0_NONE-routing rows (track 284-291).
- **Re-check**: every gate clause maps to a real AST field (C2) and mirrors
  `classifySelect`'s ordering (C1). SKIP/LIMIT first is preserved. The roster is
  now a superset that covers the floor's no-version-backstop requirement.
- **Regression check**: checked the single/multi-alias split tail (track 140-142)
  — still routes single→RECORD, multi→MATCH_TUPLE_MULTI; no clause contradicts
  the split. Clean.
- **Verdict**: VERIFIED

#### Verify T2: RETURN mode routing — VERIFIED
- **Original issue**: $elements/$pathElements RETURN flattens the row, breaking
  the alias-keyed tuple.
- **Fix applied**: step 1 routes `returnsElements()` / `returnsPathElements()` to
  K0_NONE (track 120-122); Validation adds a `MATCH RETURN $elements` row (track
  286); design.md RETURN-mode gap flagged for Phase 4 (track 122).
- **Re-check**: both predicates exist (C2, lines 266/275). The rationale (no alias
  keys → `getProperty(alias)`/`reverseIndex`/`returnProjector` all break) is sound.
- **Regression check**: checked that the Etap-A `returnProjector` (step 2) assumes
  alias-keyed RETURN — consistent, since non-alias-keyed is now gated out upstream.
  Clean.
- **Verdict**: VERIFIED

#### Verify T3: DatabaseSessionEmbedded in Interfaces — VERIFIED
- **Original issue**: `DatabaseSessionEmbedded` missing from the Interfaces section.
- **Fix applied**: added to "In scope (modified)" with five touch-points (track
  327-332): serveThroughCache MATCH gate + viewOwnsGuard transfer, buildView MATCH
  branch, effectiveFromClasses/whereClauseOf/orderByOf MATCH overloads. Steps 2
  (track 146-155) and 5 (track 207-227) describe them.
- **Re-check**: all five touch-points exist at the cited lines (C7). The
  serveThroughCache shape gate already admits MATCH (761), so the work is the
  separate branch + guard transfer, exactly as described.
- **Regression check**: checked the Upstream dependency note (track 345-351) — it
  correctly attributes the separated-gate + viewOwnsGuard pattern to Track 2.
  Clean.
- **Verdict**: VERIFIED

#### Verify T4: reuse buildPatterns/addAliases — VERIFIED
- **Original issue**: step 3 re-derived alias/class/where extraction instead of
  reusing existing machinery.
- **Fix applied**: step 3 now says reuse `SQLMatchStatement.buildPatterns` /
  `addAliases` (track 165-167), noting `SQLMatchFilter.getClassName(CommandContext)`
  needs the context.
- **Re-check**: both methods exist (C2, lines 211/306). Reuse at entry construction
  (session/schema available) is the right layer.
- **Regression check**: checked that this does not undercut the schema-free
  `classify` constraint — extraction is explicitly at entry construction (track
  157-159), not in classify. Clean.
- **Verdict**: VERIFIED

#### Verify T5: tombstone build site — VERIFIED
- **Original issue**: tombstone build was placed in `lookup`, which has no
  tx/ctx.
- **Fix applied**: step 5 builds `buildForMatchMulti` in `buildView` (track
  207-213); a new `QueryResultCache.removeForTombstone(key)` helper follows
  `overflowEntry`'s pinned-entry discipline (track 223-227); the `lookup`
  signature is unchanged. Key signatures updated (track 356-360).
- **Re-check**: `lookup(CacheKey, long)` indeed carries no tx/ctx (C6, line 107),
  so building there would force a signature change — the rejection is sound.
  `overflowEntry` removes-without-close (177-182) vs `invalidate` immediate-close
  (210-213): a tombstone of a pinned entry must use the former to honor I7, which
  the track now states explicitly (track 223-227).
- **Regression check**: checked the viewOwnsGuard interaction (track 214-221) — a
  TOMBSTONE→executeUncached must leave `viewOwnsGuard == false`; the track calls
  this out, consistent with the `instanceof CachedResultSetView` test at 809/824.
  Clean.
- **Verdict**: VERIFIED

#### Verify T6: null-guard on aliasWheres — VERIFIED
- **Original issue**: pass-2 pass→fail check would NPE on a bound alias with no
  `where:` (null `aliasWheres[alias]`).
- **Fix applied**: step 4 pass-2 treats null `aliasWheres[alias]` as "always
  matches" / skip the check (track 202-204); Validation adds a no-WHERE-bound-alias
  UPDATE row (track 275-276) and step 7 lists it in the test matrix (track 236).
- **Re-check**: the null-as-always-matches semantics are correct — an alias with
  no WHERE binds unconditionally, so a mutation cannot flip its membership.
- **Regression check**: checked that the update-into-match definition (track
  196-199) does not also assume a non-null where — it keys on `contributingRids`
  membership, independent of the WHERE, so the null-guard does not weaken it.
  Clean.
- **Verdict**: VERIFIED

## Summary

PASS. All six ACCEPTED Phase A technical findings (T1-T6) are VERIFIED in the
amended track file; every source citation the fixes depend on was re-read and
holds. No regressions introduced, no new findings.
