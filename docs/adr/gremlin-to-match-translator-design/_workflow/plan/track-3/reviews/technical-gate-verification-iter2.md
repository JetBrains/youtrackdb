<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, verdict: STILL OPEN}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: T5, verdict: VERIFIED}
  - {id: T6, verdict: VERIFIED}
  - {id: T7, verdict: VERIFIED}
  - {id: T8, verdict: VERIFIED}
  - {id: T9, verdict: VERIFIED}
  - {id: T10, verdict: VERIFIED}
overall: FAIL
flags: [CONTRACT_OK]
-->

# Track 3 — Technical gate verification (iteration 2)

Nine of ten findings verify clean against the amended track file; T1 (blocker) is STILL OPEN. The T1 prose reframe landed in full — Context and Orientation, Plan of Work items 2 and 4, the Signatures line, and the Surprises & Discoveries Phase-4 flag all carry the edge-as-node mechanism correctly — but the `## Context and Orientation` mermaid diagram's terminal node still reads `MatchPatternBuilder.addEdge(... SQLMatchPathItem.filter)` on the edge-filter path, re-asserting the exact discredited premise the amendment removed from the prose. One residual contradiction fails the gate. No new findings.

Method: track-file text read in full; the load-bearing code facts the amendment rests on were re-confirmed by reading YTDB source (`MatchPatternBuilder`, `GremlinStepWalker`, `WalkerContext`, `StepRecogniser`, `StartStepRecogniser`) plus the iteration-1 fork-jar evidence for the TinkerPop step classes. `steroid_execute_code` was not needed — the re-checks are signature/field/loop-shape facts readable from source. Fork-jar step-class identities (`EdgeVertexStep` / `EdgeOtherVertexStep` / `VertexStepPlaceholder`) carry the iteration-1 reference-accuracy caveat (jar bytecode, not PSI).

#### Verify T1 (blocker): edge-filter mechanism rests on a nonexistent addEdge form
- **Original issue**: The track claimed edge filtering needs no builder change and rides on an `addEdge(... edgeAlias, edgeFilter ...)` param plus a `SQLMatchPathItem.filter` edge slot; both are false — `addEdge` is 7-arg, its `edgeFilter` lands on the target vertex, and `outE.has.inV` cannot be expressed by a single path item.
- **Fix applied**: Context and Orientation (line 46) rewritten — `addEdge`'s `edgeFilter` attaches to the target vertex, a single `out(L)` path item cannot filter edge properties, and the IR expresses an edge filter only via the two-path-item `outE(L){as: $e, where}.inV()` edge-as-node form; the "addEdge output unchanged / IR already supports edge-side filters" framing is explicitly called wrong. Plan item 2 (line 63) emits the edge-as-node pattern "via item 4's assembler capability". Item 4 (line 65) adds the `GremlinPatternAssembler` edge-as-node assembly (two `SQLMatchPathItem`s) plus a `MatchExecutionPlanner` premise test with a descope fallback. Signatures (line 103) corrected to the real 7-arg `addEdge`. Surprises & Discoveries (lines 19-27) flags the design error for Phase-4 reconciliation.
- **Re-check**:
  - Track-file location: `## Context and Orientation` mermaid diagram, line 57.
  - Current state: the diagram's edge-filter flow (`outE(L)` → mint `$g2m_edge_N` → peek → AND-merge into `edgeFilters[$g2m_edge_N]` → mint `$g2m_anon_M` target) terminates at `AddEdge["MatchPatternBuilder.addEdge(... SQLMatchPathItem.filter)"]`. This node names `MatchPatternBuilder.addEdge` as the emit call and `SQLMatchPathItem.filter` as the filter sink — the discredited single-path-item mechanism. The amended prose (item 2) routes the same flow through item 4's `GremlinPatternAssembler` edge-as-node capability, not `addEdge`. The diagram and the prose now contradict each other, and the diagram is the visual restatement of the very premise T1 flagged.
  - Criteria met: the prose reframe satisfies the finding; the diagram does not — the wrong premise still lives in the file, inside a section T1 named.
- **Regression check**: verified the prose against source — `MatchPatternBuilder.addEdge` (`MatchPatternBuilder.java:121-158`) is 7-arg and sets `toFilter.setFilter(edgeFilter)` (target-vertex filter), exactly as the amendment states; every other `addEdge` / `SQLMatchPathItem.filter` mention in the track (folded case item 1, Signatures) is correct. The lone contradiction is the diagram terminal.
- **Verdict**: STILL OPEN — retarget the diagram's edge-filter terminal node to the item-4 edge-as-node assembler (two `SQLMatchPathItem`s: an `outE`-method item carrying the edge `SQLMatchFilter`, then the `inV` target item), so it stops naming `MatchPatternBuilder.addEdge(... SQLMatchPathItem.filter)`.

#### Verify T2 (should-fix): documented 8-arg addEdge signature
- **Original issue**: Signatures documented `addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)` — 8-arg with `edgeAlias`; the real method is 7-arg without it.
- **Fix applied**: Signatures (line 103) now reads `addEdge(fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth) — 7-arg, edgeFilter attaches to the target vertex, no edgeAlias`.
- **Re-check**:
  - Track-file location: `## Interfaces and Dependencies` → Signatures, line 103; code `MatchPatternBuilder.java:121-128`.
  - Current state: the documented signature matches the source parameter list verbatim (`fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth`), and the `edgeFilter → target vertex` semantics match `toFilter.setFilter(edgeFilter)` at line 139.
  - Criteria met: NAMED-REFERENCE accuracy restored; a call written to the documented shape compiles.
- **Regression check**: no `edgeAlias` token survives anywhere in the track (grep clean); the two remaining folded-case `addEdge` calls use the 7-arg form. Clean.
- **Verdict**: VERIFIED

#### Verify T3 (should-fix): chain-target @class narrowing reintroduces BC2 undercount
- **Original issue**: Item 1 said apply "the same `@class` narrowing as the start recogniser" to chain targets under `polymorphic=false` — the start recogniser applies none (BC2 fix), and narrowing a bare hop's generic-`V` target undercounts subclasses.
- **Fix applied**: Item 1 (line 62) now states "**No `@class` narrowing on the bare hop target**", roots bare `out/in/both` targets at generic `V` polymorphically like the post-BC2 start node, and restricts narrowing to explicit user classes (Track 4 `hasLabel`) via `MatchClassFilters`. Validation (line 84) adds a `polymorphic=false` subclassed-schema pin. Surprises (lines 28-33) flags design §"Schema polymorphism" for Phase-4 reconciliation.
- **Re-check**:
  - Track-file location: `## Plan of Work` item 1 (line 62), `## Validation and Acceptance` (line 84); code `StartStepRecogniser.java:148-170`.
  - Current state: the track now matches the source — `StartStepRecogniser` roots at `DEFAULT_VERTEX_CLASS = "V"` (line 100/148) and emits no `@class` filter ("Emitting `@class = 'V'` would wrongly exclude subclass instances", line 160). "No narrowing on the bare hop target" is the accurate description; the earlier "same narrowing as the start recogniser" wording is gone.
  - Criteria met: no BC2 reintroduction; the `polymorphic=false` subclassed-vertex acceptance case pins it.
- **Regression check**: the "explicit user class only" carve-out defers to Track 4 without contradicting the folded-case item 1; MatchClassFilters is scoped to explicit classes only. Clean.
- **Verdict**: VERIFIED

#### Verify T4 (should-fix): walker index-driven refactor under-specified
- **Original issue**: The D10 refactor named neither the `MAX_RECOGNISED_STEPS=1` gate that declines every ≥2-step traversal, nor the for-each re-dispatch hazard, nor the boolean `recognize` contract's lack of a consumed-count channel.
- **Fix applied**: Item 6 (line 67) now raises/removes `MAX_RECOGNISED_STEPS`, converts `for (Step …)` to `while (i < steps.size())`, changes `StepRecogniser.recognize` to a consumed-step-count contract (recognisers advance `ctx.stepIndex`; walker drops its unconditional `++`), and updates `StartStepRecogniser`; the all-or-nothing / no-mutation-on-decline guarantees are called out as preserved.
- **Re-check**:
  - Track-file location: `## Plan of Work` item 6 (line 67); code `GremlinStepWalker.java:79,110,129,138`, `StepRecogniser.java:54`.
  - Current state: all three obstacles are named and match source — `MAX_RECOGNISED_STEPS = 1` (line 79) with the `size > MAX_RECOGNISED_STEPS` decline (line 110); the `for (Step<?, ?> step : steps)` loop (line 129) with walker-owned `ctx.stepIndex++` (line 138); `boolean recognize(Step, WalkerContext)` (line 54) with no count channel.
  - Criteria met: the three concrete gaps are now explicit decomposition work, not a bare "for-each → index-driven" label.
- **Regression check**: the consumed-count contract change is reflected in the Signatures line (`recognize(...) → consumed-count (new)`) and Interfaces modified-list (`StepRecogniser` consumed-count contract). Consistent across sections. Clean.
- **Verdict**: VERIFIED

#### Verify T5 (should-fix): boundary / RETURN re-pin
- **Original issue**: A chain hop makes the target the result, but `StartStepRecogniser` pins `boundaryAlias` to the start and appends (not replaces) a return item; a chain recogniser appending a second item yields two columns or returns the start vertex.
- **Fix applied**: New item 8 (line 69) — each terminator-advancing recogniser **replaces** the single return item and re-pins `boundaryAlias` to its new target alias, leaving exactly one RETURN column; item 1 references "re-pin the boundary to the new target (item 8)".
- **Re-check**:
  - Track-file location: `## Plan of Work` item 8 (line 69) and item 1 (line 62); code `StartStepRecogniser.java:149,169-170`, `GremlinStepWalker` buildResult.
  - Current state: matches source — `StartStepRecogniser` pins `boundaryAlias = BOUNDARY_ALIAS` (line 149) and appends to `returnItems`/`returnAliases` (lines 169-170); the track now mandates replace + re-pin and names the two-column / start-vertex failure modes.
  - Criteria met: the replace-not-append and re-pin requirement is stated, with a named acceptance behavior (one RETURN column at the last hop's alias).
- **Regression check**: Interfaces modified-list records `StartStepRecogniser (new contract + boundary/RETURN re-pin)`; Validation line 81 requires the boundary to emit `Vertex` from the last hop's target. Consistent. Clean.
- **Verdict**: VERIFIED

#### Verify T6 (should-fix): D9 exact-class dispatch on folded step
- **Original issue**: The folded `outE(L).inV()` is produced as a `VertexStepPlaceholder`, not a plain `VertexStep`; whether it is reduced to concrete before the provider strategy runs was unconfirmed, and the track never said which class the recognisers key on.
- **Fix applied**: A "Decomposition-time verification (D9 dispatch class)" note (line 72) requires empirically printing post-`applyStrategies()` classes for `g.V().out(L)`, `g.V().outE(L).has(...).inV()`, `g.V().bothE(L).otherV()`, registering under the observed classes (single recogniser branching on `returnsEdge()` if `out`/`outE` collide), defensively handling `VertexStepPlaceholder` or adding `ProviderGValueReductionStrategy` to `applyPrior()`, and pinning with a regression test. Context and Orientation (line 44) notes the class is confirmed empirically at decomposition.
- **Re-check**:
  - Track-file location: Decomposition-time note (line 72), Context and Orientation (line 44).
  - Current state: the dispatch-class uncertainty is now an explicit decomposition-time empirical step with a defensive fallback and a regression pin — the same treatment that resolved Track 2's class-key trap. Deferring the exact class to a named decomposition experiment counts as resolved-in-plan.
  - Criteria met: the load-bearing unknown is scoped, not assumed.
- **Regression check**: consistent with iteration-1 P10/P12 (fork jar) — placeholder existence confirmed, reduction timing left to the empirical print, which is exactly what the note prescribes. Clean.
- **Verdict**: VERIFIED

#### Verify T7 (should-fix): otherV() closing step is EdgeOtherVertexStep
- **Original issue**: The track called the `both` closing step an "`otherV`-form `VertexStep`"; `otherV()` produces `EdgeOtherVertexStep`, distinct from both `VertexStep` and `EdgeVertexStep`, so an exact-class peek would miss it.
- **Fix applied**: Item 2 (line 63) now consumes "`EdgeVertexStep` for `inV`/`outV`, `EdgeOtherVertexStep` for `otherV` — both distinct from `VertexStep`"; Context and Orientation (line 44) uses `EdgeVertexStep(inV)`.
- **Re-check**:
  - Track-file location: `## Plan of Work` item 2 (line 63), Context and Orientation (line 44).
  - Current state: the closing-step classes are correct and the misleading "VertexStep(otherV)" wording is gone.
  - Criteria met: peek-ahead closing-step matcher keys on the right classes for out/in vs both.
- **Regression check**: fork-jar class identities carry the iteration-1 reference-accuracy caveat (jar bytecode, not PSI); iteration-1 P10/P13 confirmed `otherV → EdgeOtherVertexStep`, `inV/outV → EdgeVertexStep`. No contradiction introduced. Clean.
- **Verdict**: VERIFIED

#### Verify T8 (should-fix): reserved-$ pre-flight absent, first $-alias minting track
- **Original issue**: The design's reserved-`$` user-label pre-flight (decline if any `getLabels()` starts with `$`) was deferred from Track 2, absent from the walker, and unlisted in Track 3 scope — yet Track 3 is first to mint `$g2m_` aliases.
- **Fix applied**: Item 6 (line 67) adds the reserved-`$` pre-flight scan (scan every step's `getLabels()`, decline — not throw — on any `$`-prefixed user label) before recogniser dispatch; item 7 (line 68) builds the anon-alias generator; Interfaces (lines 99-100) and Validation (line 86) list both.
- **Re-check**:
  - Track-file location: `## Plan of Work` items 6-7, Interfaces (lines 99-100), Validation (line 86); code `GremlinStepWalker.java:102-127`.
  - Current state: the walker source has no `getLabels()` scan today (grep confirms none), matching the finding; the track now lists the scan as in-scope walker work with a decline (not throw) behavior and a decline acceptance case.
  - Criteria met: the collision guard is scheduled in the track that opens the collision surface.
- **Regression check**: decline-not-throw is consistent with the walker's existing decline-on-null-polymorphism convention; no conflict with the size gate. Clean.
- **Verdict**: VERIFIED

#### Verify T9 (should-fix): interleaved NoOpBarrierStep false-decline
- **Original issue**: `LazyBarrierStrategy` may inject a `NoOpBarrierStep` inside the `outE … has … inV` chain; the edge peek-ahead would see a non-`HasStep`/non-closing step and falsely decline.
- **Fix applied**: Item 2 (line 63) "skips any interleaved `NoOpBarrierStep`"; item 3 (line 64) notes the adversarial-review bytecode finding that `LazyBarrierStrategy`'s `returnsEdge()` carve-out keeps a barrier out of the window (so the skip is belt-and-suspenders) and adds a decompose-time reachability test; Validation (line 70) includes an interleaved-barrier case.
- **Re-check**:
  - Track-file location: `## Plan of Work` items 2-3, Validation (line 70).
  - Current state: the peek-ahead now treats a barrier as transparent, and the decline rule is scoped to "non-`HasStep`/non-barrier". The false-decline hole is closed.
  - Criteria met: barrier interleaving no longer forces a false decline; an acceptance case pins it.
- **Regression check**: item 3's "returnsEdge() carve-out" claim goes beyond iteration-1 P5b (which marked the interleaving PARTIAL/untested), but it is framed as belt-and-suspenders and the load-bearing behavior is the item-2 skip plus the decompose-time test — the plan does not depend on the carve-out holding. Provenance of the "adversarial review bytecode-confirmed" attribution is worth a glance at decomposition, but it does not weaken the fix. Clean.
- **Verdict**: VERIFIED

#### Verify T10 (suggestion): stale plan wordings
- **Original issue**: The dependency line implied the anon-alias generator comes from Track 2 (it was deferred to Track 3), and Purpose/Plan listed `WalkerContext.polymorphic` as a new field though it already exists.
- **Fix applied**: Purpose (line 9) — "`WalkerContext` reads its existing `polymorphic` field and gains new fields" and "Track 3 builds the anonymous-alias generator … deferred from Track 2"; Interfaces (line 100) — "`polymorphic` already exists — read only"; Inter-track dependencies (line 102) drops the anon-alias generator from the Track 2 list ("depends on Track 2 (walker, registry, boundary step)").
- **Re-check**:
  - Track-file location: Purpose (line 9), Interfaces (line 100), Inter-track dependencies (line 102); code `WalkerContext.java:86`.
  - Current state: `WalkerContext.polymorphic` is a `final boolean` field (line 86) resolved by the walker — matches "already exists, read only"; the anon-alias generator is now attributed to Track 3, not Track 2.
  - Criteria met: both stale wordings corrected; no scope change.
- **Regression check**: item 7 and Interfaces agree the generator is new Track-3 state and `polymorphic` is read-only. Consistent. Clean.
- **Verdict**: VERIFIED

## Findings

_No new findings. The T1 residual is a STILL-OPEN prior finding (see the verdicts block and the T1 certificate), not a fresh issue._

## Summary

**FAIL.** T2–T10 verify clean; the amendments are accurate against source and correctly scope the deferred-to-decomposition and Phase-4 items. T1 (blocker) is STILL OPEN: the `## Context and Orientation` mermaid diagram (line 57) still terminates the edge-filter flow at `MatchPatternBuilder.addEdge(... SQLMatchPathItem.filter)`, re-asserting the discredited single-path-item mechanism the prose amendment removed. One targeted diagram edit — retarget that terminal node to item 4's `GremlinPatternAssembler` edge-as-node assembly — clears it.
