<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 3 adversarial gate-verification — iteration 2

Re-check of all seven Phase A adversarial findings after the Phase A amendments
landed in `plan/track-3.md`. Each was ACCEPTED in iteration 1; this pass confirms
the fix landed in the track prose, cross-checks the prose against the mermaid
diagram and the Validation rows for contradictions, and re-verifies each
load-bearing source citation against the code. All seven VERIFIED; no new finding.

## Findings

(none — pure-verdict pass)

## Evidence base

#### Verify A1: MATCH SKIP/LIMIT/GROUP BY/UNWIND/RETURN DISTINCT bypassed the K0_NONE gate
- **Original issue**: `classify` returned `MATCH_TUPLE_MULTI` for every MATCH, so
  paginated and non-alias-keyed shapes the per-tuple delta cannot reconcile would
  cache and replay stale — silent, because `MATCH_TUPLE_MULTI` has no version
  backstop.
- **Fix applied**: Plan-of-Work step 1 (track-3.md:104-142) adds the full gate:
  SKIP/LIMIT as the first gate (mirroring `classifySelect`), then GROUP BY / UNWIND
  / RETURN DISTINCT / NOT MATCH (`notMatchExpressions`) / non-alias-keyed RETURN
  mode / LET / subquery / no-class / cap → K0_NONE.
- **Re-check**:
  - Location: track-3.md:104-142 (gate prose), :240 (test-matrix routing), :284-287
    (Validation rows).
  - Current state: every irreconcilable shape now routes to K0_NONE before the
    Etap-A / multi split. `classify` is correctly described as schema-free
    (lines 106-107), so the gate is AST-only.
  - Citations verified against `SQLMatchStatement.java`: `notMatchExpressions`:30,
    `returnDistinct`:34, `groupBy`:35, `unwind`:37, `skip`:38, `limit`:39,
    `returnsPathElements()`:266, `returnsElements()`:275, equals block 508-549 — all
    real fields. `ShapeClassifier.java`: MATCH→`MATCH_TUPLE_MULTI` unconditional at
    137-142; `classifySelect` SKIP/LIMIT-first gate at 148-170. Citations accurate.
  - Criteria met: gap closed; gate is the complete-floor's first line.
- **Regression check**: prose vs diagram vs Validation. Diagram K0_NONE label
  ("cross-alias-state / subquery / no class:") is a representative subset, not an
  exhaustive list, and does not claim those are the only routes — no contradiction
  with the fuller step-1 list. Validation rows match. Clean.
- **Verdict**: VERIFIED

#### Verify A2: edge mutation might evade the tombstone pre-scan
- **Original issue**: an edge op (edge-op-existence / lightweight edge) might not
  surface an edge-class `RecordOperation`, so the edge-class fold (R1) alone might
  miss an edge mutation, requiring an endpoint-vertex-closure fallback.
- **Fix / resolution**: verified all edges are record-based in this engine, so the
  edge-class fold is sufficient; no fallback needed. Context bullet states this with
  citations; Validation adds the both-endpoints-already-cached edge-CREATE row.
- **Re-check**:
  - Location: track-3.md:66-72 (Context), :194-195 (pass-1 short-circuit-before-
    pass-2), :280-281 (Validation).
  - Code verification (`DatabaseSessionEmbedded.java`): comment "All edges are
    record-based" at 1563-1564; `newEdgeInternal` unconditionally calls
    `currentTx.addRecordOperation(edge, RecordOperation.CREATED)` at 1520;
    `addEdgeInternal` always calls `newEdgeInternal(className)` (1565) — there is no
    branch that skips the edge record. `createLink` updates both endpoint vertices
    at 1571-1574. No lightweight-edge mode exists. Every edge CREATE therefore emits
    an edge-class CREATED op plus two endpoint-vertex UPDATEs.
  - Current state: the resolution holds — the edge-class fold (R1/step 3) catches
    every edge op, and the pass-1 short-circuit fires before pass 2 sees the
    co-emitted endpoint UPDATEs (track 70-72, 194-195).
  - Criteria met: the "edge CREATE between already-cached vertices tombstones" row
    (280-281) is the end-to-end guard the finding asked for.
- **Regression check**: the short-circuit-ordering requirement is stated in two
  places (Context 70-72, step 4 194-195) and they agree. Clean.
- **Verdict**: VERIFIED

#### Verify A3: cross-class link-deref WHERE detection mechanism unspecified
- **Original issue**: the cited `getMatchPatternInvolvedAliases` reuse detects
  cross-alias predicates but not a dotted link-path deref into an out-of-pattern
  class; that mechanism was unspecified.
- **Fix applied**: step 1 specifies a dedicated walk of each alias WHERE for a path
  expression whose head is a property/link, distinct from the cross-alias check;
  Interfaces notes `getMatchPatternInvolvedAliases` does NOT detect link-path
  derefs; Validation adds the K0_NONE row plus a negative `where:(i.title=?)` row.
- **Re-check**:
  - Location: track-3.md:127 (cross-alias via `getMatchPatternInvolvedAliases`),
    :130-134 (dedicated link-deref walk), :363 (Interfaces caveat), :288-291
    (Validation positive + negative rows).
  - Code: `SQLWhereClause.getMatchPatternInvolvedAliases` used at 811-844 (track
    cites 811-844); `matchesFilters` at 50/57. The two detection mechanisms are now
    correctly separated in prose — cross-alias is the existing helper, link-deref is
    the new dedicated walk.
  - Criteria met: the mechanism is specified; the negative row pins that an in-alias
    property predicate (`i.title`) does NOT over-route to K0_NONE.
- **Regression check**: no contradiction between the cross-alias gate (127) and the
  link-deref gate (130-134); they are explicitly distinct. Clean.
- **Verdict**: VERIFIED

#### Verify A4: traversalEdgeClasses static-read hazards
- **Original issue**: a parameterized edge label or the parser's null→E default
  could seed a wrong or empty closure.
- **Fix applied**: step 1 routes non-static labels to K0_NONE; step 3 folds the
  null→E base closure (coarse-safe); Validation adds a parameterized-edge-label row.
- **Re-check**:
  - Location: track-3.md:135-138 (non-static → K0_NONE), :180-184 (null→E fold +
    multi-param + the "already routed to K0_NONE in step 1" note), :286-287
    (Validation parameterized-label row).
  - Code: `SQLMatchPathItem.graphPath` (lines shown) sets `edgeName.value = "E"` on
    null and `method.methodName.value = direction` — the parser default is the
    literal "E", statically resolvable; a parameterized label is not. The prose
    distinguishes these correctly: bare `out()` → literal "E" closure (step 3),
    parameterized `out(:edgeType)` → K0_NONE (step 1).
  - Criteria met: both hazards addressed; no double-handling conflict.
- **Regression check**: adversarial check on the step-1 vs step-3 boundary — null→E
  (statically "E") is NOT the same as a non-static label, and line 184 makes that
  explicit. No contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify A5: update-into-match detection unspecified
- **Original issue**: the update-into-match tombstone trigger had no operational
  definition.
- **Fix applied**: step 4 defines it — any post-populate UPDATE on an alias-class
  record NOT in `contributingRids`, OR in it with a fail→pass WHERE-membership flip,
  tombstones; over-tombstone accepted because the entry holds no before-state for
  records outside cached tuples.
- **Re-check**:
  - Location: track-3.md:196-199 (operational definition), :277 and :282 (Validation
    update-into-match rows).
  - Current state: definition is concrete and testable; the accept-over-tombstone
    rationale (no before-state for non-cached records) is sound.
  - Criteria met: trigger is specified; Validation exercises it.
- **Regression check**: the definition is scoped to alias-class records (consistent
  with the class-filter-first ordering in pass 1, line 189-191), so it does not
  over-fire on unrelated-class UPDATEs. Clean.
- **Verdict**: VERIFIED

#### Verify A6: D11 closure reuse mis-stated
- **Original issue**: the single-class `CachedEntry.computeEffectiveFromClasses`
  helper cannot build the alias∪edge-class union, and the closure cannot be built in
  schema-free classify.
- **Fix applied**: step 3 + Interfaces add a multi-class builder
  `computeMatchEffectiveFromClasses(aliasClasses, edgeClasses)` at entry
  construction; step 1/3 state classify is schema-free.
- **Re-check**:
  - Location: track-3.md:162-164 (multi-class builder), :359 (Interfaces signature),
    :105-107 + :158-160 (entry-construction-not-classify split).
  - Code: `CachedEntry.computeEffectiveFromClasses(@Nullable SchemaClass)` is
    single-class at 147-157 (track cites 147-157) — confirmed it cannot union, so the
    new builder is genuinely required. `getAllSubclasses()` reuse is valid.
  - Criteria met: the union builder is named and placed at entry construction
    (session/schema available), resolving the schema-free-classify conflict.
- **Regression check**: the schema-free claim for classify (106-107) is consistent
  with deferring closure work to entry construction (160). No contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify A7: tombstone single-shot vs I7 frozen view for a pinned live view
- **Original issue**: a tombstone that closed a stream while a live view pinned the
  entry would break the I7 frozen-view contract.
- **Fix applied**: step 5 specifies tombstone eviction follows `overflowEntry`'s
  pinned-entry discipline (remove from map, do not close the stream while a live view
  pins), not `invalidate`'s immediate close; Validation adds the live-view I7 test.
- **Re-check**:
  - Location: track-3.md:222-227 (tombstone discipline), :297-299 (Validation I7
    row), :360 (Interfaces `removeForTombstone` following overflow discipline).
  - Code (`QueryResultCache.java`): `overflowEntry` (177-182) removes from the map
    without closing the stream — the pinned-safe path. `invalidate` (210-213) closes
    immediately at 212. `evictEldestIfUnpinned` (193-207) confirms the pinned-entry
    convention (`getLiveViewCount() != 0` → stay over the bound, don't truncate). The
    track cites the correct path and the contrast is real.
  - Criteria met: tombstone latency now honors I7; re-execution on next `query()`.
- **Regression check**: the discipline matches the existing overflow/pin convention,
  so no new pin-leak surface. The I7 Validation row exercises a live view surviving a
  tombbstone. Clean.
- **Verdict**: VERIFIED

## Summary

PASS — all seven Phase A adversarial findings VERIFIED. The amendments landed in
the track prose, the prose is internally consistent with the mermaid diagram and the
Validation rows (no gate contradicted across surfaces), and every load-bearing source
citation re-checked against the code. No new finding surfaced.
