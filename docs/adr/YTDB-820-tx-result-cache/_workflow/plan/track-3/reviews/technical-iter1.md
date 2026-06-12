<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 4, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "track-3.md Plan of Work step 1; ShapeClassifier.java:133-146", anchor: "### T1 ", cert: "Premise P9 / Edge case E1", basis: "Track step-1 classify gate omits SKIP/LIMIT/GROUP BY/ORDER BY-aggregation/RETURN DISTINCT/UNWIND/NOT MATCH/n+m cap that design.md L329-330,506 route to K0_NONE; MATCH_TUPLE_MULTI has no version backstop so a missed gate serves wrong results"}
  - {id: T2, sev: should-fix, loc: "track-3.md Plan of Work step 1+2; SQLMatchStatement.returnsElements()/returnsPathElements()", anchor: "### T2 ", cert: "Premise P10 / Edge case E2", basis: "Classify gate never restricts RETURN mode; $elements/$pathElements flatten the tuple so getProperty(alias) and the returnProjector break for those shapes"}
  - {id: T3, sev: should-fix, loc: "track-3.md Interfaces and Dependencies; DatabaseSessionEmbedded.java:741-1143", anchor: "### T3 ", cert: "Integration I-SC / Integration I-BV", basis: "DatabaseSessionEmbedded not listed in modified interfaces, but MATCH requires edits to serveThroughCache gate, buildView, effectiveFromClasses, whereClauseOf/orderByOf, populateAndBuildView"}
  - {id: T4, sev: suggestion, loc: "track-3.md Plan of Work step 3; SQLMatchStatement.java:211-340", anchor: "### T4 ", cert: "Premise P11", basis: "SQLMatchStatement.buildPatterns/addAliases already builds alias->class and alias->where maps; step 3 can reuse rather than reimplement extraction"}
  - {id: T5, sev: should-fix, loc: "track-3.md Plan of Work step 5; QueryResultCache.java:107-144", anchor: "### T5 ", cert: "Integration I-LK", basis: "Track step 5 places buildForMatchMulti inside QueryResultCache.lookup(CacheKey,long), which has no tx/ctx; Track1/2 build deltas in buildView. Deviates from the separated-gate pattern and forces a lookup signature change"}
  - {id: T6, sev: suggestion, loc: "design-mechanics.md:126-133 (inherited by track step 4)", anchor: "### T6 ", cert: "Edge case E3", basis: "Pass->fail branch calls aliasWheres[alias].matchesFilters without a null guard; a no-WHERE bound alias yields null and NPEs mid-delta-build"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 9}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: CONFIRMED, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: CONFIRMED, anchor: "#### P6 "}
  - {id: P7, verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: P8, verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: P9, verdict: PARTIAL, anchor: "#### P9 "}
  - {id: P10, verdict: WRONG, anchor: "#### P10 "}
  - {id: P11, verdict: CONFIRMED, anchor: "#### P11 "}
  - {id: I-SC, verdict: MISMATCHES, anchor: "#### I-SC "}
  - {id: I-BV, verdict: MISMATCHES, anchor: "#### I-BV "}
  - {id: I-LK, verdict: MISMATCHES, anchor: "#### I-LK "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise P9 (classify gate completeness) / Edge case E1 (paginated MATCH reaches the no-backstop path)
**Location**: track-3.md `## Plan of Work` step 1 ("MATCH classify branches"); the gate it amends, `ShapeClassifier.classify`, `core/.../sql/executor/cache/ShapeClassifier.java:133-146`.
**Issue**: The track names `MATCH_TUPLE_MULTI` having no version backstop as "the entire correctness story" and says "the classify gates must route every non-floor-handleable shape to K0_NONE." But step 1's exclusion list is only "no LET/UNWIND, no cross-alias-state WHEREs, no subqueries, no dereferencing WHERE." It omits the result-shaping features `SQLMatchStatement` actually carries and that a per-tuple skip/inject delta cannot reconcile: `skip`, `limit`, `groupBy`, `orderBy`-with-aggregation, `returnDistinct`, `unwind`, and `notMatchExpressions` (NOT MATCH). All ten are real fields on `SQLMatchStatement` (verified: equals/hashCode at lines 518-549 enumerate `skip`, `limit`, `groupBy`, `orderBy`, `unwind`, `returnDistinct`, `notMatchExpressions`). design.md L329-330 and L506 specify the full gate correctly (MATCH with SKIP/LIMIT → K0_NONE, plus the `n + m <= maxRecordsPerEntry` cap), so the design is right; the track summary is lossy on exactly the surface it flags as load-bearing. The decomposer works from the track file. Today `classify` returns `MATCH_TUPLE_MULTI` unconditionally for any `SQLMatchStatement` (line 137-142), so a decomposer implementing only the step-1 list would let `MATCH ... SKIP 5 LIMIT 10` or `MATCH ... GROUP BY ...` through to the no-backstop path and serve stale rows after a mutation.
**Proposed fix**: Rewrite step 1's exclusion list to match design.md L329-330 verbatim: SKIP/LIMIT checked first (mirroring `classifySelect` lines 152-154), then route to K0_NONE on GROUP BY, UNWIND, RETURN DISTINCT, NOT MATCH, any node lacking `class:`, cross-alias-state WHERE, subquery WHERE, link-deref WHERE, and `n + m > maxRecordsPerEntry`. Add the SKIP/LIMIT-MATCH and GROUP-BY-MATCH cases to the step-7 (I4) test matrix as K0_NONE-routing assertions.

### T2 [should-fix]
**Certificate**: Premise P10 (RETURN mode is alias-keyed) / Edge case E2 ($elements RETURN flattens the tuple)
**Location**: track-3.md `## Plan of Work` steps 1-2; `SQLMatchStatement.returnsElements()` / `returnsPathElements()` (lines 266-296), the `ReturnMatch*Step` family.
**Issue**: Etap A's `returnProjector` and the multi-alias bookkeeping (`reverseIndex`, `contributingRids`, the pass→fail `rid-binds-alias(rid, alias)` probe via `getProperty(alias)`) all assume the MATCH result row is a tuple keyed by alias — the shape `RETURN i, p` produces through `ReturnMatchPatternsStep`. But MATCH has four RETURN modes: `returnsElements()` (`RETURN $elements`) and `returnsPathElements()` flatten the result to one element per row with no alias keys, so `getProperty(alias)` returns null and the returnProjector cannot construct its single-binding tuple. Neither the track file nor design.md's MATCH classify gate (L329-330) restricts RETURN mode, so a `RETURN $elements` MATCH would be classified `MATCH_TUPLE_MULTI` / Etap-A-RECORD and then mis-reconciled. This is the same "alias-keyed tuple" assumption the central correctness claim rests on.
**Proposed fix**: Add a classify gate routing any MATCH whose RETURN is not the alias-keyed form (`returnsElements()` / `returnsPathElements()` true, and reconcile the `$paths`/`$patterns` alias-set difference) to K0_NONE. Add a `RETURN $elements` case to the I4 test matrix asserting K0_NONE routing. Raise the RETURN-mode question against design.md so the design's MATCH gate is amended too (out of this track's edit scope, but the decomposer should flag it).

### T3 [should-fix]
**Certificate**: Integration I-SC (serveThroughCache MATCH gate) / Integration I-BV (buildView + metadata helpers)
**Location**: track-3.md `## Interfaces and Dependencies` ("In scope (modified)"); `DatabaseSessionEmbedded.java:741-1143`.
**Issue**: The modified-interfaces list names `ShapeClassifier`, `DeltaBuilder`, `CachedResultSetView`, `CachedEntry`, `QueryResultCache` — but not `DatabaseSessionEmbedded`, where the MATCH path needs five edits, all currently SELECT-only: (1) `serveThroughCache` lines 812-816 route any non-RECORD/non-K0_NONE shape (including `MATCH_TUPLE_MULTI`) to `executeUncached` — the MATCH branch must be added here as a separate gate, with the `viewOwnsGuard = result instanceof CachedResultSetView` transfer matching the RECORD/aggregate branches (lines 809, 824); (2) `buildView` lines 1105-1108 build a delta only for RECORD — a MATCH branch is needed; (3) `effectiveFromClasses` lines 1134-1143 returns `Set.of()` for MATCH; (4) `whereClauseOf` (1145) and `orderByOf` (1149) return null for non-SELECT; (5) `populateAndBuildView` lines 863-906 builds the RECORD entry. The Track 1 episode and design.md L185 both name `DatabaseSessionEmbedded.query()` as the view-building integration surface, so the omission is a track-file completeness gap, not a design gap.
**Proposed fix**: Add `DatabaseSessionEmbedded` to the track's "In scope (modified)" list with the five touch-points enumerated, and add a step (or fold into step 1/2/5/6) covering the `serveThroughCache` MATCH gate + `viewOwnsGuard` transfer, the `buildView` MATCH branch, and the MATCH-aware `effectiveFromClasses`/`whereClauseOf`/`orderByOf` overloads.

### T4 [suggestion]
**Certificate**: Premise P11 (alias/class/where extraction already exists)
**Location**: track-3.md `## Plan of Work` step 3 ("MATCH multi-alias entry metadata"); `SQLMatchStatement.buildPatterns` / `addAliases` (lines 211-340).
**Issue**: Step 3 plans to "populate `aliasClasses`, `traversalEdgeClasses`, `aliasWheres` ... at entry construction." `SQLMatchStatement.buildPatterns()` (line 211) already walks `matchExpressions` and builds `aliasFilters` (alias → `SQLWhereClause`) and `aliasClasses` (alias → class name) via the private `addAliases` helper (lines 306-340), using the per-alias `origin`/`items` structure. The track can extract alias classes and WHEREs by reusing this rather than re-walking `SQLMatchFilter.getClassName(ctx)` / `getFilter()` by hand. Note `getClassName` takes a `CommandContext` (line 108), so this resolution belongs at populate time (where ctx exists), not at AST-only classify time.
**Proposed fix**: In step 3, reuse or factor out `SQLMatchStatement.addAliases`/`buildPatterns` for the alias→class and alias→where maps. Keep edge-class folding (`traversalEdgeClasses` from `.out/.in/.both(label)` via `SQLMatchPathItem.getMethod()`) as the new work, since `addAliases` does not currently surface unbound-edge classes.

### T5 [should-fix]
**Certificate**: Integration I-LK (tombstone-at-lookup vs separated-gate pattern)
**Location**: track-3.md `## Plan of Work` step 5 ("Tombstone handling in `QueryResultCache.lookup`"); `QueryResultCache.lookup` (lines 107-144).
**Issue**: Step 5 says lookup, "for a `MATCH_TUPLE_MULTI` entry, invoke `buildForMatchMulti`; on TOMBSTONE remove the entry and return MISS." But `lookup` today has signature `lookup(CacheKey key, long currentMutationVersion)` — it has no `FrontendTransactionImpl` and no `CommandContext`, which `buildForMatchMulti(CachedEntry, FrontendTransactionImpl, CommandContext)` requires (per the track's own Key signatures). Track 1 and Track 2 build their deltas in `DatabaseSessionEmbedded.buildView` (RECORD: line 1106; aggregate: line 1101), after `lookup` returns the hit — the Track 2 episode explicitly records the "separate-gate in serveThroughCache / delta in buildView" pattern that step 5 deviates from. Running the delta build inside `lookup` forces a `lookup` signature change (thread tx + ctx) and splits the MATCH delta build away from where every other shape builds it, hurting the symmetry the track claims to follow.
**Proposed fix**: Build `buildForMatchMulti` in `buildView` (or in `serveThroughCache` right after the hit), consistent with RECORD/aggregate. The tombstone-then-MISS outcome can be handled there: on TOMBSTONE, evict the entry via a `QueryResultCache` method and re-run `executeUncached`. Keep `lookup`'s signature unchanged. Update step 5 to place the build in `buildView` and describe the lookup-time eviction as a small `QueryResultCache` helper the view path calls, not an in-`lookup` delta build.

### T6 [suggestion]
**Certificate**: Edge case E3 (no-WHERE bound alias on the pass→fail branch)
**Location**: design-mechanics.md:126-133, the step-3(b) pass→fail branch the track's step 4 implements.
**Issue**: The two-pass pseudocode gates the update-into-match branch (3a) on `aliasWheres[alias] != null`, but the pass→fail branch (3b) calls `!aliasWheres[alias].matchesFilters(op.record, ctx)` with no null guard. A bound alias declared without a `where:` (`{as:p, class:Project}`, no filter) has a null `aliasWheres` entry, so an UPDATE to a record bound by that alias NPEs mid-delta-build. The fix is trivial (a null WHERE always matches, so the alias never drives a pass→fail drop), but it sits on the central delta-build path. The defect is in the design mechanics, inherited by the track; flagging so the decomposer carries a null-guard into the step and a no-WHERE-alias UPDATE case into the I4 matrix.
**Proposed fix**: In step 4, treat a null `aliasWheres[alias]` as "always matches" in branch 3(b) (skip the pass→fail check for that alias). Add a multi-alias-with-no-WHERE-bound-alias UPDATE scenario to the I4 test matrix. Optionally raise the pseudocode null-guard against design-mechanics.md.

## Evidence base

#### P1 SQLMatchStatement exists with the assumed shape
- **Track claim**: "`SQLMatchStatement` (`internal/core/sql/parser/`) — the MATCH AST."
- **Search performed**: mcp-steroid `findClass` attempted but `steroid_execute_code` timed out repeatedly on the kotlinc-compile path (two projects + multiple worktrees open). Fell back to `find . -path '*/sql/parser/SQLMatchStatement.java'` + Read. Reference-accuracy caveat: existence/signature facts below are from filename-glob + source Read, not PSI find-usages.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchStatement.java:23` (single unambiguous match in the main checkout; worktree copies are siblings, not ambiguity).
- **Actual behavior**: `public final class SQLMatchStatement extends SQLStatement` with `matchExpressions`, `notMatchExpressions`, `returnItems`, `returnAliases`, `returnNestedProjections`, `returnDistinct`, `groupBy`, `orderBy`, `unwind`, `skip`, `limit` fields (lines 29-39).
- **Verdict**: CONFIRMED
- **Detail**: —

#### P2 SQLWhereClause.matchesFilters(Identifiable, CommandContext) exists
- **Track claim**: "`SQLWhereClause.matchesFilters(Identifiable, CommandContext)` — reused to re-evaluate each alias's pattern WHERE against a mutated record at delta-build."
- **Search performed**: grep on `matchesFilters(` in SQLWhereClause.java (PSI unavailable, see P1).
- **Code location**: `SQLWhereClause.java:50` `public boolean matchesFilters(Identifiable currentRecord, CommandContext ctx)`.
- **Actual behavior**: The exact signature exists; a `matchesFilters(Result, CommandContext)` overload also exists at line 57. `DeltaBuilder.buildForRecord` (line 143) and `buildForAggregate` (line 258) already call the Identifiable overload, confirming it is the live re-eval surface.
- **Verdict**: CONFIRMED
- **Detail**: —

#### P3 SchemaClass.getAllSubclasses() exists
- **Track claim**: "`SchemaClass.getAllSubclasses()` — the subclass-closure source for `aliasClasses` and `traversalEdgeClasses`."
- **Search performed**: grep on `getAllSubclasses` in SchemaClass.java (PSI unavailable).
- **Code location**: `SchemaClass.java:135` `Collection<SchemaClass> getAllSubclasses();`.
- **Actual behavior**: Exists; `CachedEntry.computeEffectiveFromClasses` (line 153) already uses it for the RECORD closure, so the reuse the track plans is proven live.
- **Verdict**: CONFIRMED
- **Detail**: —

#### P4 SQLMatchStatement.equals()/hashCode() cover SKIP (and all result-shaping fields)
- **Track claim**: "`SQLMatchStatement.equals()` covers statement-level SKIP natively, so the cache key needs no special MATCH handling."
- **Search performed**: Read of SQLMatchStatement.java:507-566.
- **Code location**: `SQLMatchStatement.java:508-549` (equals), `:552-566` (hashCode).
- **Actual behavior**: equals/hashCode compare `matchExpressions`, `notMatchExpressions`, `returnItems`, `returnAliases`, `returnNestedProjections`, `groupBy`, `orderBy`, `unwind`, `skip`, `limit`, `returnDistinct`. The CacheKey (which delegates to `SQLStatement.equals`) therefore distinguishes all SKIP/LIMIT/GROUP BY/DISTINCT variants. design.md L290 confirms.
- **Verdict**: CONFIRMED
- **Detail**: The claim holds — but the same enumeration is the evidence for T1: every one of those fields is a result-shaping feature the delta cannot reconcile, and the track's classify gate (step 1) omits most of them.

#### P5 serveThroughCache separate-gate pattern + viewOwnsGuard
- **Track claim**: "Track 3's MATCH branch must follow the same separate-gate pattern and transfer `viewOwnsGuard` when it builds a `CachedResultSetView`."
- **Search performed**: grep + Read of `DatabaseSessionEmbedded.serveThroughCache` (lines 741-836).
- **Code location**: `DatabaseSessionEmbedded.java:741-836`.
- **Actual behavior**: `serveThroughCache` enters the guard (line 782), sets `viewOwnsGuard = true` on a hit (790), `viewOwnsGuard = aggregateResult instanceof CachedResultSetView` (809) and `= result instanceof CachedResultSetView` (824) on the populate branches, and releases the guard in `finally` only when `!viewOwnsGuard` (832). Lines 812-816 route any non-RECORD/non-K0_NONE shape (including `MATCH_TUPLE_MULTI`) to `executeUncached`. The pattern is exactly as the track describes.
- **Verdict**: CONFIRMED
- **Detail**: The MATCH branch must be inserted at line 812-816 and set `viewOwnsGuard` like the sibling branches (feeds T3).

#### P6 CachedEntry reuse surface (effectiveFromClasses, whereClause, orderBy, recordPulledRow, liveViewCount)
- **Track claim**: Track 3 reuses Track 1's `CachedEntry` and adds MATCH metadata fields.
- **Search performed**: Read of CachedEntry.java in full.
- **Code location**: `CachedEntry.java:53-349`.
- **Actual behavior**: `CachedEntry` carries `effectiveFromClasses`, `whereClause`, `orderBy`, `results`, `cachedRids`, `recordPulledRow` (line 290, the cap-enforcing append point Track 1's episode mandates routing through), `liveViewCount` pin (250-262), and a `null`-defaulted `aggregateState`. Adding `aliasClasses`/`traversalEdgeClasses`/`aliasWheres`/`contributingRids`/`reverseIndex`/`tombstoned`/`returnProjector` as new nullable fields is structurally consistent with how `aggregateState` was added in Track 2.
- **Verdict**: CONFIRMED
- **Detail**: The `recordPulledRow` cap (lines 290-309) governs `results`/`cachedRids`; the MATCH `contributingRids`/`reverseIndex` extension during stream-pull (step 6) must be wired alongside it so the cap still latches.

#### P7 CachedResultSetView shape-dispatch + viewOwns-guard release
- **Track claim**: Step 6 adds a MATCH per-tuple path to `CachedResultSetView`.
- **Search performed**: Read of CachedResultSetView.java in full.
- **Code location**: `CachedResultSetView.java:194-202` (computeNext dispatch), `:319-344` (pullOneFromStream), `:352-363` (releasePin via exitCacheCodeUnchecked).
- **Actual behavior**: `computeNext` dispatches aggregate → K0_NONE (delta==null) → RECORD. A MATCH branch (tuple-skip iteration) slots in here. `releasePin` uses `tx.exitCacheCodeUnchecked()` (361), matching the Track 1/2 cross-thread-release contract. The constructor pins via `incrementLiveViewCount` (159).
- **Verdict**: CONFIRMED
- **Detail**: The MATCH view needs its own `computeNextMatch` plus the `MatchMultiDelta`-carrying constructor/factory, mirroring the aggregate `forAggregate` static factory (130-139).

#### P8 SQLMatchExpression / SQLMatchFilter / SQLMatchPathItem extraction surface
- **Track claim**: aliasClasses from node `class:`, traversalEdgeClasses from `.out/.in/.both(label)` steps.
- **Search performed**: Read of SQLMatchExpression.java (origin/items), grep of SQLMatchFilter.java and SQLMatchPathItem.java getters.
- **Code location**: `SQLMatchExpression.java:14-15` (`origin: SQLMatchFilter`, `items: List<SQLMatchPathItem>`); `SQLMatchFilter.java:28` getAlias, `:72` getFilter, `:108` getClassName(CommandContext); `SQLMatchPathItem.java:46-55` in/both/outPath, `:256` getMethod, `:264` getFilter.
- **Actual behavior**: The pattern decomposes into `origin` (root alias/class/where) + `items` (edge traversals carrying a `SQLMethodCall` and a target `SQLMatchFilter`). `getClassName` requires a `CommandContext`, so class resolution is a populate-time op (ctx available) not an AST-only classify op.
- **Verdict**: CONFIRMED
- **Detail**: Structurally feasible; the ctx dependency of `getClassName` constrains where step 3's extraction can run (populate, not classify) — consistent with the design and with P11.

#### P9 Classify gate routes every non-floor-handleable shape to K0_NONE
- **Track claim**: "the classify gates must route every non-floor-handleable shape to K0_NONE" (Compatibility section); step-1 list enumerates the gates.
- **Search performed**: Read of ShapeClassifier.classify (133-146) + classifySelect (148-179); cross-read of design.md L329-330, L506 and SQLMatchStatement fields.
- **Code location**: `ShapeClassifier.java:137-142` (current: MATCH → `MATCH_TUPLE_MULTI` unconditionally).
- **Actual behavior**: Today classify returns `MATCH_TUPLE_MULTI` for any `SQLMatchStatement` with no gating. design.md L329-330 specifies the full gate (SKIP/LIMIT first, then GROUP BY/UNWIND/DISTINCT/non-class-node/cross-alias-state/subquery/link-deref → K0_NONE) plus L506's `n + m <= maxRecordsPerEntry` cap. The track file's step-1 list covers only LET/UNWIND, cross-alias-state, subquery, link-deref — omitting SKIP/LIMIT, GROUP BY, ORDER BY-with-aggregation, RETURN DISTINCT, NOT MATCH, and the `n+m` cap.
- **Verdict**: PARTIAL
- **Detail**: The design is complete; the track file's gate list is a lossy subset on the exact correctness-critical surface. Produces T1.

#### P10 MATCH result rows are alias-keyed tuples
- **Track claim**: `getProperty(alias)` probes per-alias bindings; `returnProjector` wraps a record into the single-binding tuple the executor produces.
- **Search performed**: ls of `sql/executor/match/`; Read of ReturnMatchPatternsStep.java; grep of MatchExecutionPlanner RETURN dispatch (lines 1738-1742); grep of SQLMatchStatement.returnsElements/Paths/Patterns/PathElements.
- **Code location**: `SQLMatchStatement.java:266-296` (four RETURN-mode accessors); `MatchExecutionPlanner.java:1738-1742` (chains `ReturnMatchElementsStep` / `ReturnMatchPathsStep` / `ReturnMatchPatternsStep`).
- **Actual behavior**: The alias-keyed tuple shape holds only for `RETURN <aliases>` / `$patterns` / `$paths`. `RETURN $elements` (`returnsElements()`) and `$pathElements` (`returnsPathElements()`) flatten to one element per row with no alias keys, so `getProperty(alias)` returns null and the returnProjector cannot build a single-binding tuple. Neither the track nor design.md L329-330 gates RETURN mode.
- **Verdict**: WRONG
- **Detail**: The assumption is true only for a subset of RETURN modes; the missing gate produces T2.

#### P11 Existing alias-extraction machinery (buildPatterns / addAliases)
- **Track claim**: step 3 "Populate `aliasClasses`, `aliasWheres` ... at entry construction" (implies new extraction code).
- **Search performed**: Read of SQLMatchStatement.buildPatterns (211-227) + grep of addAliases (306-340).
- **Code location**: `SQLMatchStatement.java:211` (buildPatterns builds `aliasFilters` + `aliasClasses` local maps via addAliases), `:306`/`:319` (addAliases overloads).
- **Actual behavior**: `buildPatterns` already produces alias → `SQLWhereClause` (`aliasFilters`, stored as a field) and alias → class name (`aliasClasses`, a local) using ctx. The track can reuse this instead of re-walking filters by hand.
- **Verdict**: CONFIRMED
- **Detail**: A simpler-approach reuse opportunity → T4 (suggestion). `aliasClasses` is currently a local var, not stored; either store it or factor the walk.

#### I-SC serveThroughCache MATCH gate
- **Plan claim**: track lists modified components as ShapeClassifier / DeltaBuilder / CachedResultSetView / CachedEntry / QueryResultCache (no DatabaseSessionEmbedded).
- **Actual entry point**: `DatabaseSessionEmbedded.serveThroughCache:812-816` — the gate that routes `MATCH_TUPLE_MULTI` to `executeUncached` today.
- **Caller analysis**: `serveThroughCache` is called from the two `query()` overloads (lines 668, 710), verified by grep (PSI unavailable). Reference-accuracy caveat: grep may miss a polymorphic call site, but `serveThroughCache` is private to `DatabaseSessionEmbedded` so the two in-file callers are the complete set.
- **Breaking change risk**: The MATCH branch is additive (new gate before line 815), low risk to existing RECORD/aggregate/K0_NONE callers, but it MUST be in scope.
- **Verdict**: MISMATCHES
- **Detail**: `DatabaseSessionEmbedded` missing from the modified-interfaces list → T3.

#### I-BV buildView + metadata helpers
- **Plan claim**: same as I-SC (DatabaseSessionEmbedded not listed).
- **Actual entry point**: `DatabaseSessionEmbedded.buildView:1084-1109`, `effectiveFromClasses:1134-1143`, `whereClauseOf:1145`, `orderByOf:1149`, `populateAndBuildView:863-906`.
- **Caller analysis**: `buildView` is called from `serveThroughCache` (789) and the populate methods (905, 1019); `effectiveFromClasses`/`whereClauseOf`/`orderByOf` are called only from `populateAndBuildView` (896-898). All SELECT-only today: `effectiveFromClasses` returns `Set.of()` for MATCH (1142), `whereClauseOf`/`orderByOf` return null for non-SELECT.
- **Breaking change risk**: Additive MATCH branches; the risk is omission (track does not list these), not breakage.
- **Verdict**: MISMATCHES
- **Detail**: Feeds T3 — the five touch-points must be enumerated in the track.

#### I-LK tombstone build location
- **Plan claim**: step 5 — "invoke `buildForMatchMulti` ... in `QueryResultCache.lookup`."
- **Actual entry point**: `QueryResultCache.lookup:107` signature `lookup(CacheKey key, long currentMutationVersion)` — no tx, no ctx.
- **Caller analysis**: `lookup` is called once, from `serveThroughCache:785`, passing `tx.getMutationVersion()`. The RECORD/aggregate delta builds happen later in `buildView` (1101, 1106), which has tx + ctx.
- **Breaking change risk**: Running `buildForMatchMulti(entry, tx, ctx)` inside `lookup` forces threading `FrontendTransactionImpl` + `CommandContext` into the lookup signature and splits MATCH delta build away from the buildView site every other shape uses — a structural deviation from the Track 1/2 separated-gate pattern the track claims to follow.
- **Verdict**: MISMATCHES
- **Detail**: Produces T5 — relocate the build to buildView/serveThroughCache; keep lookup's signature; expose a small evict helper for the TOMBSTONE→MISS outcome.
