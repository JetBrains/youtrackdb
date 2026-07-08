<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: R10, sev: suggestion, loc: implementation-plan.md:384, anchor: "### R10 ", cert: "Verify R9 regression", basis: "plan Track-3 checklist gloss still asserts 'WalkerContext gains the polymorphic flag' and 'first track that wires a boundary step (ELEMENT)' — the two premises R9 corrected in the track file; low impact (track file is authoritative), doc-consistency only"}
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
  - {id: R7, verdict: VERIFIED}
  - {id: R8, verdict: VERIFIED}
  - {id: R9, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

### R10 [suggestion]
**Location**: `implementation-plan.md:384-385` (Track 3 Checklist gloss); `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-3.md` `## Surprises & Discoveries`
**Issue**: The R9 amendment corrected the two stale premises in the track file (Purpose, Context, Interfaces), but the plan's own Track 3 checklist gloss still states "`WalkerContext` gains the `polymorphic` flag" and "Track 3 is the first track that wires a boundary step at all (`ELEMENT` output type)." Both are contradicted by live code: `WalkerContext.polymorphic` exists at `WalkerContext.java:86` and `YTDBMatchPlanStep.projectVertex`/`ELEMENT` are implemented (Track 2). The plan already carries a corrective note under the Track 2 strategy-refresh bullet (`implementation-plan.md:372-373`) for `polymorphic`, so the gloss is internally inconsistent with the plan's own text; the "first track to wire ELEMENT" claim has no such corrective note anywhere. Separately, R9's proposed fix asked for a Phase-4 reconciliation of the design's "Boundary output types" ELEMENT wording (design.md ≈547-555); the track file's `## Surprises & Discoveries` logs two Phase-4 reconciliation items (edge-filter mechanism, schema polymorphism) but not the ELEMENT-boundary wording.
**Impact**: Low. The track file is the authoritative source the implementer reads, and it is correct. The stale gloss can only mislead a reader who consults the plan checklist summary instead of the track file, and the design.md ELEMENT wording is a Phase-4 cleanup, not a Track-3 execution input. Non-blocking.
**Proposed fix**: Update the `implementation-plan.md` Track 3 checklist gloss to match the track file ("reads the existing `polymorphic` flag; gains `edgeFilters` + anon-alias counters"; drop "first track that wires a boundary step — `ELEMENT` landed in Track 2"), and add the design.md ELEMENT-boundary-wording reconciliation to the track file's `## Surprises & Discoveries` Phase-4 list.

#### Verify R1: addEdge misroutes edgeFilter to target vertex; no edge-as-node shape
- **Original issue**: The headline feature (non-adjacent edge filtering, IC2) depended on `addEdge` parking the filter on the edge. The shipped `addEdge` is 7-arg with no `edgeAlias`, and `edgeFilter` attaches to the target-vertex `SQLMatchFilter`, so a single `out(L)` path item filters the wrong element. `MatchPatternBuilder` was not in Track 3's modified scope.
- **Fix applied**: `## Context and Orientation` and Plan of Work item 2 rewritten to the edge-as-node two-path-item form (`outE(L){as: $g2m_edge_N, where}.inV(){as: $g2m_anon_M}`), emitted via a new `GremlinPatternAssembler` capability (item 4). Signatures line corrects `addEdge` to 7-arg, edge-as-node marked a new assembler capability. `GremlinPatternAssembler (incl. the edge-as-node assembly)` listed in In-scope(new); the edge-as-node assembly named as a Track-3 builder/assembler extension in inter-track deps.
- **Re-check**:
  - Location: track-3.md lines 46, 63, 65, 99, 102, 103; `MatchPatternBuilder.java:121-158`.
  - Current state: PSI-confirmed `addEdge(fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth)` — 7-arg, `edgeFilter` → `toFilter.setFilter` (line 138-139), single `out/in/both` path item (line 145-150), no `edgeAlias`. The amended plan no longer claims `addEdge` can filter the edge; it routes edge filters through the edge-as-node assembler and adds a premise test that `MatchExecutionPlanner` plans an edge-aliased intermediate node, with a descope-to-later-track fallback (item 4).
  - Criteria met: wrong premise removed; the correct two-path-item shape is the plan; the builder/assembler extension is in modified/new scope; the executor path is confirmed (3 `MatchEdgeMethod*Test` files exist).
- **Regression check**: Checked scope lists, signatures, validation. Clean. Minor residual (not blocking): item 9's edge-filter case does not explicitly require the edge to carry a property the endpoint vertices lack, so a naive fixture could pass while filtering the wrong entity — a decomposition-time fixture detail; the validation line already pins the filter on the edge's own `SQLMatchFilter`.
- **Verdict**: VERIFIED

#### Verify R2: chain-target @class narrowing reintroduces BC2 undercount
- **Original issue**: Plan step 1 said the vertex recogniser applies the "same `@class` narrowing as the start recogniser." The start recogniser applies none (BC2 fix), and a bare `out(L)` target roots at the abstract `V`; narrowing to `@class = 'V'` under `polymorphic=false` undercounts subclasses or matches zero rows.
- **Fix applied**: Item 1 now states "**No `@class` narrowing on the bare hop target**," rooting at generic `V` polymorphically like the start node, "regardless of `WalkerContext.polymorphic`"; `MatchClassFilters` narrows only explicit user classes (Track 4 `hasLabel`). Validation and item 9 add a `polymorphic=false` subclassed-schema BC2 pin.
- **Re-check**:
  - Location: track-3.md lines 9, 62, 65, 70, 84; `StartStepRecogniser.java:148,156-160`.
  - Current state: PSI-confirmed `addNode(BOUNDARY_ALIAS, DEFAULT_VERTEX_CLASS, null, false)` with a comment stating "`@class = 'V'` would wrongly exclude subclass instances." The amended item 1 matches this exactly and moves narrowing to explicit-class-only.
  - Criteria met: wrong "same narrowing" premise removed; bare-hop target left unnarrowed; BC2 regression pin added to the equivalence fixture; design error logged for Phase-4 reconciliation.
- **Regression check**: Checked polymorphic gating and Track 4 handoff. Clean.
- **Verdict**: VERIFIED

#### Verify R3: multi-hop boundary re-pin missing
- **Original issue**: For a multi-hop chain the terminator hop must re-pin `boundaryAlias` to the target and rebuild the RETURN projection; a naive append leaves the start-node projection, so the boundary returns null (row dropped) or source vertices. The re-pin was absent from the Plan of Work.
- **Fix applied**: New item 8 makes each terminator-advancing recogniser **replace** the single return item and re-pin `boundaryAlias` to the new target alias, leaving one RETURN column on the final hop's alias; item 1 references item 8; Interfaces lists `StartStepRecogniser` (boundary/RETURN re-pin); validation pins "boundary emits `Vertex` from the last hop's target alias."
- **Re-check**:
  - Location: track-3.md lines 9, 62, 69, 81, 100; `YTDBMatchPlanStep.java:476-477` (`getVertex(boundaryAlias)`); `WalkerContext.java:54,68` (`returnItems`, `boundaryAlias`).
  - Current state: the projection pull-by-alias and the three parallel return lists exist as the finding described; item 8 now names the re-pin as the terminator hop's responsibility.
  - Criteria met: naive-append premise removed; re-pin is a named plan responsibility; equivalence test asserts target identity.
- **Regression check**: `WalkerContext` carries `returnItems`/`returnAliases`/`returnNestedProjections` (lines 54-60). Item 8 says "replaces the single return item" — decomposition must keep all three parallel lists in sync (one entry each) so the custom-RETURN branch stays valid; noted, not blocking (the intent — one RETURN column on the final target — is unambiguous).
- **Verdict**: VERIFIED

#### Verify R4: walker double-advance and MAX_RECOGNISED_STEPS gate
- **Original issue**: The foreach loop with a walker-owned `ctx.stepIndex++` collides with a multi-step recogniser advancing the index by N; `recognize` returns a bare `boolean` with no consumed-count channel; `MAX_RECOGNISED_STEPS = 1` declines every ≥2-step traversal and was not flagged for raising.
- **Fix applied**: Item 6 raises/removes `MAX_RECOGNISED_STEPS`, converts the loop to `while (i < steps.size())`, and changes `StepRecogniser.recognize` to report a consumed-step count (walker drops its unconditional `++`); `StartStepRecogniser` updated to the single-step contract. Signatures line records `recognize(...) → consumed-count (new)`; Interfaces lists the gate raise and the contract change.
- **Re-check**:
  - Location: track-3.md lines 48, 67, 100, 103; `GremlinStepWalker.java:79,110,129,138`; `StepRecogniser.java:54`.
  - Current state: PSI-confirmed `MAX_RECOGNISED_STEPS = 1`, foreach at 129, walker `stepIndex++` at 138, `boolean recognize` at 54 — exactly the finding's baseline. The amendment maps to the finding's option (b) (return the consumed count) plus the gate raise.
  - Criteria met: single-owner advance (walker applies the reported count, drops its `++`); gate raised; consumed-count contract in the signature; start recogniser migrated.
- **Regression check**: Checked contract phrasing. The parenthetical blends "recognisers advance `ctx.stepIndex`" with "report a consumed-step count"; the Signatures line resolves it to a return-count the walker applies, so a single owner is clear. Decomposition should pick the return-count mechanism explicitly to avoid re-introducing dual ownership — clarity note, not a STILL OPEN.
- **Verdict**: VERIFIED

#### Verify R5: AnonAliasGenerator + reserved-$ pre-flight absent from scope
- **Original issue**: The anonymous-alias generator (vertex + edge) and the reserved-`$`-label pre-flight scan were deferred from Track 2 and unbuilt, yet neither appeared in Track 3's In-scope(new); Plan step 6 named only `anonEdgeAliasGenerator`.
- **Fix applied**: Item 6 adds the reserved-`$` pre-flight scan (scan `getLabels()`, decline the traversal on any `$`-prefixed user label); item 7 adds the anonymous-alias generator (vertex `$g2m_anon_M` + edge `$g2m_edge_N`) as new `WalkerContext` state; In-scope(new) lists "the anonymous-alias generator + reserved-`$` pre-flight (in the walker)"; decline cases include any `$`-prefixed user label.
- **Re-check**:
  - Location: track-3.md lines 9, 67, 68, 86, 99, 100; confirmed no `AnonAliasGenerator`/`AliasGenerator` file in `core/src`, no generator field on `WalkerContext`.
  - Current state: both the generator (both instances) and the pre-flight are now in the Plan of Work and In-scope(new); the vertex generator omission (Plan step 6) is corrected — item 7 names both vertex and edge.
  - Criteria met: scope omission closed; decline test for `$`-prefixed labels named in validation.
- **Regression check**: Checked plan checklist gloss — `implementation-plan.md:384` names only "anonymous edge-alias generator" (edge-only); the track file (authoritative) correctly names both. Folded into R10 with the other gloss staleness. Not blocking.
- **Verdict**: VERIFIED

#### Verify R6: peek-ahead mutates ctx before confirming closing hop
- **Original issue**: The edge recogniser could mint aliases / call `addEdge` / advance counters during the peek and then decline, leaving a partial write in the shared counters and one-shot builder (no rollback).
- **Fix applied**: Item 2 states "**No-mutation-on-decline:** it does not mint aliases or touch `ctx` until the closing hop is confirmed"; validation records that decline cases leave `WalkerContext` unmutated.
- **Re-check**:
  - Location: track-3.md lines 63, 86.
  - Current state: the recogniser peeks read-only and mints only after the closing hop is confirmed — the finding's proposed discipline.
  - Criteria met: no-mutation-on-decline made explicit for the multi-step recogniser; matches the design's `decline_doesNotCommitPartialStateToOuterContext` contract.
- **Regression check**: Consistent with the D9 per-recogniser invariant in the plan. Clean.
- **Verdict**: VERIFIED

#### Verify R7: NoOpBarrier injected mid-chain trips the peek's decline rule
- **Original issue**: `LazyBarrierStrategy` injects `NoOpBarrierStep`s between recognized steps; a barrier between `outE(L)` and `has(...)` or before `inV()` is a non-`HasStep` and would trip the peek's decline-on-non-HasStep rule, silently rejecting a valid IC2 chain. Barrier positioning was unverified.
- **Fix applied**: Item 2 skips interleaved `NoOpBarrierStep` in the peek and declines only on a non-`HasStep`/non-barrier; item 3 records the adversarial bytecode finding that `LazyBarrierStrategy`'s `returnsEdge()` carve-out keeps a barrier out of the `outE…inV` window (so the inline skip is belt-and-suspenders) plus a decompose-time reachability test; item 9 seeds an interleaved `NoOpBarrierStep` case.
- **Re-check**:
  - Location: track-3.md lines 63, 64, 70, 86.
  - Current state: the peek skips barriers, the empirical carve-out is documented, and the equivalence fixture exercises an interleaved barrier through the real strategy chain.
  - Criteria met: barrier-skip in the peek; empirical verification via the fixture; decline rule narrowed to non-barrier non-HasStep.
- **Regression check**: Consistent with the plan Constraint "recognizers see post-fold shapes" (barriers appear between recognized steps). Clean.
- **Verdict**: VERIFIED

#### Verify R8: both/self-loop/parallel-edge multiset equivalence
- **Original issue**: `both(L)`, self-loops, and parallel edges are multiplicity shapes where MATCH and native Gremlin can diverge; a naive single-direction fixture never exercises them.
- **Fix applied**: Item 9 seeds a graph "with edge subclasses and parallel edges" and requires cases for "`both()`/self-loop/parallel-edge multiplicity"; validation pins that these preserve native multiplicity; the mermaid keeps a D3 decline path if a shape diverges.
- **Re-check**:
  - Location: track-3.md lines 58, 70, 85.
  - Current state: the three multiplicity shapes are named fixture cases asserting multiset (not set) equality, with a D3 decline fallback.
  - Criteria met: the omission-risk shapes are seeded; multiset assertion; divergence → D3 decline decision available.
- **Regression check**: Consistent with the "Multiset equality is the contract" plan constraint. Clean.
- **Verdict**: VERIFIED

#### Verify R9: stale YTDBMatchPlanStep-modified line and polymorphic-as-new-field
- **Original issue**: The track file claimed Track 3 is the first to wire the boundary `ELEMENT` projection and listed `YTDBMatchPlanStep` as modified, and Plan step 6 listed `polymorphic` as a field to add — all three contradicted by Track 2 (ELEMENT and `polymorphic` already delivered).
- **Fix applied**: Purpose/Context now state "`ELEMENT` … already landed in Track 2"; Interfaces marks `YTDBMatchPlanStep` "no change unless the re-pin needs it"; `WalkerContext.polymorphic` marked "already exists — read only"; item 7 says Track 3 only reads `polymorphic`.
- **Re-check**:
  - Location: track-3.md lines 9, 48, 68, 100; PSI-confirmed `YTDBMatchPlanStep.projectVertex`/`projectElement`/`ELEMENT` implemented (lines 438-477), `WalkerContext.polymorphic` at line 86.
  - Current state: the track file (authoritative) no longer carries any of the three stale claims; the vestigial "modified" line is downgraded to conditional-on-re-pin.
  - Criteria met: wrong premises removed from the track file; `polymorphic` reclassified read-only; ELEMENT reclassified as Track-2-delivered.
- **Regression check**: The same two premises survive in the plan's Track 3 checklist gloss (`implementation-plan.md:384-385`) and the design.md ELEMENT-boundary wording is not on the track file's Phase-4 reconciliation list — surfaced as new finding R10 (suggestion, non-blocking).
- **Verdict**: VERIFIED

## Summary

PASS. All five should-fix findings (R1–R5) and all four suggestions (R6–R9) are VERIFIED against the amended track file, with the load-bearing code premises confirmed by PSI/Read (`addEdge` 7-arg target-vertex filter, `StartStepRecogniser` no-narrowing, `WalkerContext.polymorphic` and `YTDBMatchPlanStep.ELEMENT` already present, walker foreach + `MAX_RECOGNISED_STEPS=1` + `boolean recognize`, `AnonAliasGenerator` absent). One new suggestion (R10) records residual staleness in the plan's Track 3 checklist gloss and an untracked design.md Phase-4 reconciliation item; it does not block the gate.
