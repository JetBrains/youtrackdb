<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: CR1, verdict: REJECTED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 10, matches: 10}
flags: [CONTRACT_OK]
-->

PASS. The consistency review's effective-PASS verdict is well-formed and holds. CR1 is a genuine frozen-`design.md` class-diagram drift, correctly recorded-only and deferred to the Phase-4 `design-final.md` reconciliation — not an actionable plan/track defect that was missed. The pending tracks (4-7) cite the as-built code shapes, so leaving the plan and track files unedited introduces no inconsistency. No fix was applied this iteration, so there is no fix-shifted regression surface to scan.

## Findings

None. This verification pass surfaced no new current-state defect in the plan or track files.

#### Verify CR1 (recorded-only — no plan/track edit applied): frozen class-diagram drift
- **Original issue**: The frozen `design.md` §"Class Design" diagram and §"Scope" table draw the pre-Track-2/3-rework translator surface, so three shapes no longer match the as-built code: an 8-param `MatchPatternBuilder.addEdge(...edgeAlias...)`; a `WalkerContext` field/method set (`traversal`, `aliasRids`, `stepIndex`, `anonAliasGenerator`/`anonEdgeAliasGenerator`, `rebindBoundaryProjection`, `bindParam`); and a `NotFilterStepRecogniser` for `hasNot(key)`.
- **Why no plan/track edit (rejection reason to verify)**: `design.md` is the frozen Phase-1 artifact, never edited during Phase 3 execution; the class-diagram redraw is a Phase-4 `design-final.md` reconciliation choice (like the §"Parameter binding" / §"Schema polymorphism" items the tracks already flag), and the pending tracks already carry the as-built shapes. Classification `design-decision` — sound.
- **Downstream check** (does leaving the plan/tracks unedited cause any inconsistency? Checked track-4, track-5 — the pending tracks CR1's surface touches — plus the plan checklist; grep for every stale identifier across all track files):
  - **`addEdge`/`addEdgeAsNode` split** — the completed track-3 uses `addEdgeAsNode`; no track references an 8-param `addEdge(...edgeAlias...)`. As-built `addEdge` has 7 params (`MatchPatternBuilder.java:122`), the edge-alias form is `addEdgeAsNode` (`:189`). Clean.
  - **`WalkerContext` field set** — `aliasRids`/`stepIndex` never appear as live pending-track references: track-4:43 explicitly states "there is no `aliasRids`/`aliasClasses` slot on the context", and track-4:45 cites the "retired ... manual `stepIndex` form" as history. The other `aliasRids`/`stepIndex` hits are in completed tracks 2-3 (where `aliasRids` is a `MatchPlanInputs` record field, not the context). track-5 (:40, :49) cites the real `setSingleReturnColumn` (WalkerContext.java:253-263), never `rebindBoundaryProjection`. `bindParam` appears only as a Track-5 target-state addition (:46, :53, :81, :82). `anonAliasGenerator`/`anonEdgeAliasGenerator` never appear. Clean.
  - **`NotFilterStepRecogniser`** — absent from every track file. track-5 uses the single `NotStepRecogniser` under `NotStep.class` (:9, :51, :81, A2, user-approved 2026-07-15); track-4 routes `has(key)` through `TraversalFilterStepRecogniser` (:9, :43, :52, :85). Clean.
- **Verdict**: REJECTED (no action needed). The frozen-design deferral is sound; no pending-track inconsistency. The Phase-4 pointer the review proposed is genuinely optional — track-3:182 already carries the edge-filter and schema-polymorphism reconciliation pointers in its `## Surprises & Discoveries`, of which the class-shape drift is a consequence.

## Evidence base

**Tooling note.** mcp-steroid PSI is non-responsive this session (IDE stuck indexing, ~60 s timeouts), so I verified via `grep`/`find` plus direct full-source `Read`s with line-number confirmation, matching the consistency review's fallback. Reference-accuracy caveat: caller-set / "no-other-reference" negatives were not machine-confirmed via PSI find-usages. None of the certificates below depend on such a negative — each is a declaration-existence / declaration-shape check read directly from source (definitive for existence and shape), or a text-presence sweep of the plan/track Markdown (definitive for what the documents cite). Source tree is clean at HEAD (`git status` on the two source packages empty), so the review's cited line numbers are stable.

### Re-verified current-state certificates (representative sample)

#### V-ADDEDGE — `MatchPatternBuilder.addEdge` 7-param + separate `addEdgeAsNode` (re-checks RC-ADDEDGE)
- **Search**: full `Read` of `MatchPatternBuilder.java`.
- **Code location**: `addEdge(fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth)` at `:122` (7 params, no `edgeAlias`); `addEdgeAsNode(fromAlias, edgeAlias, toAlias, edgeDir, edgeLabel, closingVertexDir, edgeFilter)` at `:189`; `build()` → `PatternIR` at `:255`, nested `record PatternIR` at `:46`.
- **Verdict**: MATCHES. The design's 8-param `addEdge(...edgeAlias...)` is stale; as-built is the split. (Also re-confirms RC-PATTERNIR.)

#### V-WALKERCTX — `WalkerContext` as-built field/method set (re-checks RC-WALKERCTX, RC-SETSINGLECOL, RC-PUTALIASFILTER, RC-BINDPARAM, RC-WALKER-POLY)
- **Search**: full `Read` of `WalkerContext.java`.
- **Code location**: no `traversal` / `aliasRids` / `stepIndex` / `boundParams` fields. Anon generators are `AliasSequence anonVertexAliases` (`:159`) / `edgeAliases` (`:163`). Projection re-pin is `setSingleReturnColumn(String alias)` (`:254`, clears three parallel lists then adds one column, `:253-263`) — no `rebindBoundaryProjection`. `putAliasFilter` overwrites (`aliasFilters.put`, `:236-238`; "override builder entries" comment `:36`) — AND-compose is the Track-4 target-state. `bindParam` absent (Track-5 target). `polymorphic()` at `:173`, backing field `:77`.
- **Verdict**: MATCHES. Every design field/method CR1 flags is confirmed drifted; every current-state and target-state claim across five certificates holds.

#### V-NOTREC — frozen `design.md` names `NotFilterStepRecogniser`; plan uses single `NotStepRecogniser` (re-checks RC-NOTREC)
- **Search**: grep `design.md` + track files.
- **Code location**: `design.md:124` and `:1104` name `NotFilterStepRecogniser` (and `:117` references it as the `hasNot(key)` handler). Plan/track-5 consolidates to one `NotStepRecogniser` under `NotStep.class`.
- **Verdict**: MATCHES. Legitimate Design ↔ Plan divergence, expected under the frozen-DR-supersession rule.

#### V-RECOGCONTRACT — `StepRecogniser.recognize(StepCursor, RecognitionContext): Outcome` (re-checks RC-RECOGCONTRACT)
- **Search**: full `Read` of `StepRecogniser.java`.
- **Code location**: `Outcome recognize(StepCursor cursor, RecognitionContext ctx)` at `:47`; `@FunctionalInterface`; Javadoc pins the post-Track-3 contract (head via `cursor.take()`, trailing via conditional matchers, `ACCEPTED`/`DECLINE`, no step-index/consumed-count).
- **Verdict**: MATCHES. This is the contract track-4:45 / track-4:167-equivalent build against.

#### V-WHEREBUILDER — `MatchWhereBuilder` existing vs new methods (re-checks RC-WHEREBUILDER)
- **Search**: grep `MatchWhereBuilder.java`.
- **Code location**: present — `classEquals` `:65`, `containsText` `:133`, `startsWith` `:152`, `and` `:172`, `or` `:180`, `isDefined` `:263`, `isNotDefined` `:276`, `not` `:288`. Absent — `endsWith`, `matchesRegex` (grep count 0).
- **Verdict**: MATCHES. Existing methods present; the two new methods correctly absent (Track-4 target-state).

#### V-DELETED — `MatchClassFilters` deleted; `SQLEndsWithCondition` uncreated; `SQLMatchesCondition` exists (re-checks RC-DELETED, RC-SQLMATCHES)
- **Search**: `find -name`.
- **Code location**: no `MatchClassFilters.java` (Track-3 deletion confirmed); no `SQLEndsWithCondition.java` (Track-4 target-state); `SQLMatchesCondition.java` present (find-mode flag is the Track-4 addition).
- **Verdict**: MATCHES.

### Pending-track reference-integrity sweep (the crux for CR1's deferral)

#### V-STALE-ABSENT — no stale design identifier appears as a live reference in any pending track
- **Search**: grep across `track-*.md` for `NotFilterStepRecogniser`, `rebindBoundaryProjection`, `anonAliasGenerator`, `anonEdgeAliasGenerator`.
- **Result**: `NotFilterStepRecogniser` — none. `rebindBoundaryProjection` — none. `anonAliasGenerator`/`anonEdgeAliasGenerator` — none. `aliasRids`/`stepIndex` — present only as completed-track-2/3 content (record field / historical model) or as explicit "no such slot" / "retired form" notes in track-4.
- **Verdict**: MATCHES the review's "none mislead the pending tracks" claim.

#### V-REAL-PRESENT — pending tracks cite the real as-built and target-state names
- **Search**: grep across `track-*.md`.
- **Result**: `TraversalFilterStepRecogniser` in track-4 (`:9`, `:43`, `:52`, `:85`); single `NotStepRecogniser` in track-5 (`:9`, `:51`, `:81`); `setSingleReturnColumn` in track-5 (`:40`, `:49`, with the correct WalkerContext.java:253-263 anchor); `bindParam` as a Track-5 `RecognitionContext` addition; `addEdgeAsNode` in track-3.
- **Verdict**: MATCHES.

#### V-D2-INDEX — plan checklist / decision index internally consistent with the completed and pending split
- **Search**: `Read` of `implementation-plan.md`.
- **Code location**: Tracks 1-3 `[x]`, Tracks 4-7 `[ ]`; the D5→Track-5 reassignment (A1 split, user-approved 2026-07-15) is reflected in the checklist entry, the Implementation-state table, and the Decision-conformance paragraph.
- **Verdict**: MATCHES. No dangling decision or track-assignment inconsistency introduced by the frozen-design drift.

#### V-FROZEN-DISCIPLINE — the recorded-only handling conforms to the frozen-artifact rule
- **Search**: cross-read plan intro + `conventions-execution.md §2.5` verdict-producer variant + the review's Classification/Justification.
- **Result**: `design.md` is the frozen Phase-1 artifact (plan refers to "the frozen `design.md`"); Phase-3 execution does not edit it; Phase-4 `design-final.md` reconciliation is the redraw home. CR1's `design-decision` classification and no-plan-edit outcome are the correct handling.
- **Verdict**: MATCHES.

### Regression scan

No fix was applied this iteration (CR1 recorded-only), so there is no edited region that could shift an inconsistency. The plan and track files are byte-identical to the state the consistency review checked. Regression scan is vacuously clean.
