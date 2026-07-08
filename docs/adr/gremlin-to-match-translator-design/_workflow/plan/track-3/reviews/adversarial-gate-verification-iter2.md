<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: A10, sev: should-fix, loc: implementation-plan.md:378-393, anchor: "### A10 ", cert: G1, basis: "plan-file Track 3 blurb still carries the pre-amendment premises the track file just dropped (chain-target narrowing, first-ELEMENT-wiring, polymorphic as new field, ~15-file scope with no builder extension) — contradictory strategic context for decomposition"}
  - {id: A11, sev: suggestion, loc: WalkerContext.java:81,          anchor: "### A11 ", cert: G2, basis: "landed WalkerContext.polymorphic Javadoc instructs chain-hop recognisers to @class-narrow when false — the BC2-wrong guidance the amendment removed; no plan item schedules the comment fix"}
verdicts:
  - {id: A1, verdict: STILL OPEN}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
  - {id: A8, verdict: VERIFIED}
  - {id: A9, verdict: VERIFIED}
evidence_base: {section: "## Evidence base", certs: 2, matches: 0}
cert_index:
  - {id: G1, verdict: BREAKS,  anchor: "#### G1 "}
  - {id: G2, verdict: FRAGILE, anchor: "#### G2 "}
overall: FAIL
flags: [CONTRACT_OK]
-->

# Adversarial gate verification — Track 3, iteration 2

FAIL on one residual: eight of nine findings are resolved in the amended track
file, but A1 (blocker) is STILL OPEN because its accepted fix listed the mermaid
`AddEdge` node and the diagram was not touched — `track-3.md:57` still routes
the edge filter through `MatchPatternBuilder.addEdge(... SQLMatchPathItem.filter)`,
the exact mechanism every amended prose section now rejects, and the
mint-before-peek flow at `:52-54` contradicts item 2's no-mutation-on-decline
contract. The fix is one diagram edit. Two new findings: the plan file's Track 3
blurb still carries the pre-amendment premises (should-fix), and the landed
`WalkerContext.polymorphic` Javadoc still teaches the BC2-wrong chain-target
narrowing (suggestion). All codebase premises the amendment leans on were
re-checked and hold: 7-param `addEdge` with target-vertex filter, edge-as-node
`outE(L){as, where}.inV()` executing today in `MatchEdgeMethod*Test`,
`MAX_RECOGNISED_STEPS = 1`, for-each walker loop, `Map.of` registry,
`final boolean polymorphic`, and the start recogniser's no-narrowing +
`$g2m_v0` single-return pin.

IDE preflight: `steroid_list_projects` confirms the project at
`/home/sandra-adamiec/IdeaProjects/youtrackdb` is open. All re-checks here are
declaration reads of known files (signatures, constants, Javadoc, test bodies),
not usage sweeps, so direct Read/grep of the declaring files is
reference-accurate without PSI.

## Verification certificates

#### Verify A1: planned `addEdge` call shape does not exist; edge filter lands on the target-vertex slot
- **Original issue**: Item 2 and the Signatures line specified an 8-param
  `addEdge(…, edgeAlias, edgeFilter, …)` that was never built; the nearest real
  overload parks the filter on the target-vertex `{…}` block, so edge
  predicates would evaluate against the friend vertex — plausible non-empty
  wrong results.
- **Fix applied**: Context and Orientation rewritten around the edge-as-node
  two-path-item form `outE(L){as: $e, where}.inV()` with an explicit statement
  that the old framing was wrong; item 2 rewritten to mint the edge alias and
  emit via a new assembler capability; item 4 adds the edge-as-node assembly to
  `GremlinPatternAssembler` plus a planner premise test with a descope path;
  Signatures corrected to the real 7-param shape with the target-vertex
  semantics named; Surprises flags `design.md` §"Edge filtering in non-adjacent
  chains" (≈1217-1221) for Phase-4 reconciliation.
- **Re-check**:
  - Track-file location: `track-3.md:19-27` (Surprises), `:46` (Context),
    `:63` (item 2), `:65` (item 4), `:82` (Validation), `:103` (Signatures).
  - Current state: every prose section carries the corrected mechanism.
    Codebase premises hold: `MatchPatternBuilder.addEdge` is 7-param and its
    Javadoc plus `toFilter.setFilter(edgeFilter)` confirm the target-vertex
    attachment (`MatchPatternBuilder.java:110-140`); the edge-as-node form runs
    in the executor today, including the edge-alias variant
    `outE('IC11bWorkAt'){as: wa, where: (workFrom < 2016)}.inV()`
    (`MatchEdgeMethodLdbcPatternTest.java:134-135`,
    `MatchEdgeMethodPreFilterTest.java:143-146`). The Phase-4 reconcile item is
    named, so the design-error deferral counts as resolved-in-plan.
  - Criteria met: wrong call shape gone from prose; builder extension scoped
    (items 2, 4, Inter-track dependencies line); executor-support claim
    verified; design reconciliation named.
- **Regression check**: The accepted fix explicitly included "reword item 2 and
  the mermaid `AddEdge` node". The mermaid (`track-3.md:50-59`) was not
  updated: node `AddEdge["MatchPatternBuilder.addEdge(... SQLMatchPathItem.filter)"]`
  (`:57`) still depicts the rejected single-item route, and the
  `Mint → Peek → Acc["AND-merge into edgeFilters[$g2m_edge_N]"] → … → Decline`
  flow (`:52-58`) depicts ctx mutation before the claim decision — `edgeFilters`
  is named `WalkerContext` state in item 7, so the figure also contradicts
  item 2's bolded no-mutation clause. The diagram sits in Context and
  Orientation, the section a decomposer sketches the flow from.
- **Verdict**: STILL OPEN — narrow residual, one edit: redraw the figure to the
  claim-time edge-as-node flow (peek/accumulate into locals; on confirmed
  closing hop mint `$g2m_edge_N` + `$g2m_anon_M` and emit the two path items
  via the item-4 assembler). All other fix components are applied and verified.

#### Verify A2: chain-target `@class` narrowing reintroduces the BC2 undercount
- **Original issue**: Item 1 instructed "the same `@class` narrowing as the
  start recogniser" on bare hop targets — narrowing that the start recogniser
  no longer performs (BC2 fix) and that native `out()` never applies, so a
  bare-target `@class = 'V'` WHERE would exclude every subclass instance.
- **Fix applied**: Item 1 now states in bold that a bare hop target gets no
  `@class` narrowing, roots at `V` polymorphically like the start node, and
  reserves narrowing for Track 4's explicit user classes via `MatchClassFilters`;
  Validation pins `polymorphic=false` over a subclassed schema (Person extends
  `V`) against native; Surprises flags `design.md` §"Schema polymorphism"
  (≈1570-1583) for Phase-4 reconciliation.
- **Re-check**:
  - Track-file location: `track-3.md:28-33` (Surprises), `:62` (item 1),
    `:65` (item 4 — helper "for explicit user classes only"), `:84`
    (Validation pin).
  - Current state: the narrowing instruction is inverted to match the BC2
    resolution; `StartStepRecogniser` confirmed to emit no `@class` narrowing
    (`StartStepRecogniser.java:32,156-160`); the cited design section confirmed
    to carry the wrong "chain-target nodes inherit the same narrowing"
    instruction, so the Phase-4 reconcile target is real. Keeping
    `MatchClassFilters` in Track 3 with no Track-3 WHERE call site is the
    alternative the original finding explicitly permitted.
  - Criteria met: wrong premise removed; equivalence invariant protected by a
    named test case; design reconciliation named.
- **Regression check**: The track file is clean. Two artifacts outside it still
  carry the removed premise: the plan file's Track 3 blurb ("including
  chain-target polymorphism", `implementation-plan.md:380`) plus Track 2's
  strategy-refresh note ("chain-target `@class` narrowing stays
  `polymorphic=false`-gated", `:372-373`) — new finding A10 — and the landed
  `WalkerContext.polymorphic` Javadoc — new finding A11.
- **Verdict**: VERIFIED (residuals tracked as A10/A11, both outside the track
  file the finding targeted).

#### Verify A3: `out(L)` / `outE(L)` share the concrete `VertexStep` class; two recognisers cannot both register (D9)
- **Original issue**: Items 1-2 registered `VertexStepRecogniser` and
  `EdgeStepRecogniser` as separate registry entries, but both step shapes
  construct the same concrete class, and the `Map.of` registry throws on a
  duplicate key. `otherV()` was also misnamed a `VertexStep` form.
- **Fix applied**: A decomposition-time verification note (`track-3.md:72`)
  requires printing the post-`applyStrategies()` classes for the three shapes
  against a real graph, registering under the observed classes, collapsing to a
  single recogniser branching on `VertexStep.returnsEdge()` if `out`/`outE`
  collide, handling `VertexStepPlaceholder`, and pinning with a regression test
  that fails if the registry key drifts. Item 2 corrects the closing steps to
  `EdgeVertexStep` / `EdgeOtherVertexStep`, "both distinct from `VertexStep`".
- **Re-check**:
  - Track-file location: `track-3.md:63` (item 2 closing-step classes), `:72`
    (decomposition note).
  - Current state: the plan no longer asserts two entries under one key; it
    conditions registration on empirically observed classes and names the
    single-recogniser fallback D9 prescribes. The registry's duplicate-key
    behavior confirmed (`Map.of`, `GremlinStepWalker.java:58-59`). The deferral
    is genuine work, not evasion: iteration 1's fork-jar evidence covered raw
    step classes, while the post-`applyStrategies()` picture can substitute
    `VertexStepPlaceholder` under deferred GValue reduction — only the
    empirical print settles it.
  - Criteria met: deferred-to-decomposition with the check, the branch
    condition, and the pin all named — resolved-in-plan under the Phase A
    criterion.
- **Regression check**: In-scope (new) still lists both recogniser class names
  (`track-3.md:99`); acceptable as the default structure since the
  decomposition note governs registration and names the merge path. Clean
  otherwise.
- **Verdict**: VERIFIED.

#### Verify A4: peek-ahead mutates `WalkerContext` before the claim decision
- **Original issue**: Item 2 minted the edge alias and AND-merged filters into
  `ctx.edgeFilters` before deciding, then declined — leaving stale context
  state the walker never rolls back, violating the no-mutation-on-decline
  contract the track's own Validation asserts.
- **Fix applied**: Item 2 now carries a bolded contract sentence: the
  recogniser "does not mint aliases or touch `ctx` until the closing hop is
  confirmed"; accumulation targets a local edge `SQLMatchFilter`; item 8's
  return-entry replacement is likewise claim-time; Validation keeps the
  decline-cases-leave-context-unmutated line with the full decline list.
- **Re-check**:
  - Track-file location: `track-3.md:63` (item 2), `:69` (item 8), `:86`
    (Validation).
  - Current state: the prescribed implementation now satisfies the contract
    the track tests (`WalkerContext.java:28-32` confirmed unchanged: no
    rollback, mutate only when about to return true). The promised unit test
    and the prescribed implementation no longer conflict.
  - Criteria met: commit-on-claim discipline stated where the original
    violation was written; counters advance on claim.
- **Regression check**: The mermaid still depicts the old mint-before-peek /
  accumulate-into-`edgeFilters` flow — the same un-updated figure carried as
  A1's residual; no separate action beyond A1's diagram edit. Prose is clean.
- **Verdict**: VERIFIED (A4's accepted fix was the item-2 rewording, which is
  fully applied; the figure belongs to A1's fix list).

#### Verify A5: ELEMENT boundary and `polymorphic` already landed; the real walker delta was unnamed
- **Original issue**: Purpose claimed Track 3 first wires the `ELEMENT`
  boundary output (Track 2 shipped it), item 6 listed `polymorphic` as a new
  field (it exists), and the genuine deltas — index-driven loop and the
  `MAX_RECOGNISED_STEPS = 1` gate — appeared nowhere.
- **Fix applied**: Purpose (`track-3.md:9`) and Context (`:48`) state ELEMENT
  and `polymorphic` already landed in Track 2; item 6 (`:67`) names the
  for-each → `while (i < steps.size())` conversion, raising/removing
  `MAX_RECOGNISED_STEPS` "(currently `1`, which declines every ≥2-step
  traversal before any recogniser runs)", and the consumed-step-count contract
  change including the `StartStepRecogniser` update; item 7 (`:68`) scopes
  `WalkerContext` additions to `edgeFilters` + counters with `polymorphic`
  read-only; In-scope (modified) (`:100`) drops the `YTDBMatchPlanStep` edit
  ("no change unless the re-pin needs it").
- **Re-check**:
  - Codebase state confirmed: `MAX_RECOGNISED_STEPS = 1`
    (`GremlinStepWalker.java:79`), size gate (`:110`), for-each loop with
    unconditional `ctx.stepIndex++` (`:129-138`), `final boolean polymorphic`
    (`WalkerContext.java:86`). The track's descriptions now match the landed
    code exactly.
  - Criteria met: stale scope removed; every real walker delta named as a work
    item.
- **Regression check**: The plan file's Track 3 blurb still carries both stale
  claims verbatim ("`WalkerContext` gains the `polymorphic` flag",
  "Track 3 is the first track that wires a boundary step at all (`ELEMENT`
  output type)", `implementation-plan.md:383-385`) — folded into new finding
  A10. Track file clean.
- **Verdict**: VERIFIED.

#### Verify A6: anon-alias generator and reserved-`$` pre-flight unplanned
- **Original issue**: Track 2 deferred the generator and the `$`-label
  pre-flight to Track 3, but no Plan of Work item built either and neither
  appeared in In-scope.
- **Fix applied**: Item 6 adds the pre-flight — scan every step's
  `getLabels()`, decline (not throw) on any `$`-prefixed user label — before
  recogniser dispatch; item 7 builds the generator (vertex `$g2m_anon_M` +
  edge `$g2m_edge_N` counters) as `WalkerContext` state; In-scope (new) lists
  "the anonymous-alias generator + reserved-`$` pre-flight (in the walker)";
  Validation (`:86`) pins the `$`-label decline case.
- **Re-check**: `track-3.md:67,68,86,99`. Both design commitments now have
  owning work items, scope entries, and a test line. Walker confirmed to have
  no labels scan today (`GremlinStepWalker.java:102-160`), so the item is real
  new work, correctly placed.
- **Regression check**: Clean; decline-not-throw matches the all-or-nothing
  contract.
- **Verdict**: VERIFIED.

#### Verify A7: boundary metadata overwrite and return-entry replacement unplanned
- **Original issue**: On a chain, hop recognisers must re-pin
  `boundaryAlias`/`returnClass` and replace — not append to — the single
  return entry `StartStepRecogniser` pins, or the plan projects start vertices
  relabeled as targets; no item named the handoff.
- **Fix applied**: New item 8 (`track-3.md:69`) names the mechanic: each
  terminator-advancing recogniser replaces the single return item and re-pins
  `boundaryAlias`, "leaving exactly one RETURN column keyed on the final hop's
  alias", with the naive-append failure mode spelled out; item 1 cross-references
  it; In-scope (modified) adds "boundary/RETURN re-pin" to `StartStepRecogniser`;
  Validation (`:81`) asserts the boundary emits from the last hop's target
  alias — the equivalence-test case the original fix asked for.
- **Re-check**: Premise confirmed in code: `BOUNDARY_ALIAS = "$g2m_v0"` pinned
  with one appended return item (`StartStepRecogniser.java:93,149,169`), and
  `buildResult` packages `ctx.returnItems` as-is (`GremlinStepWalker.java:182`).
  The plan now owns the handoff, claim-time per item 2's contract.
- **Regression check**: Clean.
- **Verdict**: VERIFIED.

#### Verify A8: fixture lacks edge-subclass and parallel-edge cases
- **Original issue**: The Person/Place + Knows/Likes/Follows fixture could not
  detect an edge-label polymorphism divergence between MATCH `out()` and
  native `out()`, nor pin parallel-edge multiplicity.
- **Fix applied**: Item 9 (`track-3.md:70`) seeds the fixture "with edge
  subclasses and parallel edges" and requires `both()`/self-loop/parallel-edge
  multiplicity cases plus the `polymorphic=false` subclassed-schema pin;
  Validation (`:85`) adds "parallel edges preserve native multiplicity;
  edge-subclass labels behave as native `out()`".
- **Re-check**: Both blind spots now have fixture data and acceptance lines.
  Minor: the certificate's "edge subclass under polymorphic on and off" is not
  spelled per-setting, but the fixture carries the subclass and the
  equivalence harness is parameterised — adequate at suggestion severity; the
  decomposer can split the case per setting.
- **Regression check**: Clean.
- **Verdict**: VERIFIED.

#### Verify A9: decomposition and footprint survive the amendments
- **Original issue** (confirmation-shaped): verify the amended track still
  fits one track inside the 12-25-file band.
- **Fix applied**: Plan of Work grew from 7 to 9 items (assembler capability,
  re-pin, generator split out); In-scope lists updated.
- **Re-check**: Recount from the amended In-scope lists: 7-8 new production
  classes/capabilities + `EdgeTraversalEquivalenceTest` + recogniser unit
  tests (~4-5) + 4 modified classes (plus `MatchPatternBuilder` if the
  edge-as-node emission needs a builder method rather than living wholly in
  `GremlinPatternAssembler` — the deps line names it a "builder/assembler
  extension") ≈ 16-18 files. Inside the band; still one coherent capability
  with a single equivalence-test story; no split or merge indicated.
- **Regression check**: The plan blurb's "**Scope:** ~15 files"
  (`implementation-plan.md:386`) predates the amendments — the count update
  the original finding asked for lands with the A10 blurb sync.
- **Verdict**: VERIFIED (bookkeeping residual folded into A10).

## Findings

### A10 [should-fix]
**Certificate**: G1
**Target**: `implementation-plan.md` Track 3 blurb (`:378-393`) and the Track 2
strategy-refresh note (`:372-373`)
**Challenge**: The amendment fixed the track file and left the plan file's
Track 3 entry carrying every premise the amendment removed: "including
chain-target polymorphism" (`:380` — A2's narrowing premise, reinforced by
`:372-373` "chain-target `@class` narrowing stays `polymorphic=false`-gated"),
"`WalkerContext` gains the `polymorphic` flag" (`:383` — the field exists),
"Track 3 is the first track that wires a boundary step at all (`ELEMENT`
output type)" (`:384-385` — Track 2 shipped it), and "**Scope:** ~15 files"
(`:386`) with no mention of the edge-as-node builder/assembler extension that
is now the track's central mechanism. The gate-verification protocol reads the
plan as strategic context beside the track file; a decomposer who trusts the
blurb gets instructions the authoritative track file contradicts — the exact
drift class this gate exists to catch.
**Proposed fix**: Sync the Track 3 blurb with the amended track file: drop the
chain-target-narrowing and first-ELEMENT claims, describe `polymorphic` as
read-only, name the edge-as-node assembler/builder extension, and update the
scope count to ~16-18 files. Annotate (or reword) the Track 2 strategy-refresh
sentence so it no longer reads as prescribing gated chain-target narrowing.

### A11 [suggestion]
**Certificate**: G2
**Target**: `WalkerContext.java:78-86` (`polymorphic` field Javadoc, landed
Track 2); track-3.md items 1/7
**Challenge**: The landed Javadoc instructs that "later node-introducing
recognisers (the vertex-step chain hops, `hasLabel`) … narrow a new alias with
`@class = '<class>'` when `false`" — the BC2-wrong bare-hop guidance the
amendment just removed from the plan. Track 3's implementer edits this exact
class (item 7 adds `edgeFilters` + counters) with this comment in view; a
comment prescribing the undercount the Validation section forbids is the
stale-comment hazard CLAUDE.md's keep-comments-in-sync rule targets.
**Proposed fix**: Add a clause to item 7 (or item 1): update the
`WalkerContext.polymorphic` Javadoc so narrowing is described as
Track-4/`hasLabel`-only and bare chain hops are explicitly excluded.

## Evidence base

#### G1 Consistency check: amended track file vs plan-file Track 3 blurb
- **Claim under test**: the amendment leaves plan and track file telling one
  story.
- **Evidence**: `implementation-plan.md:372-373,380,383-386` retain the
  pre-amendment wording quoted in A10; the amended `track-3.md:9,48,62,67,100`
  state the opposite on all four points. Both files are Phase-A inputs to
  decomposition.
- **Verdict**: BREAKS — contradictory strategic context. → A10

#### G2 Stale-comment check: landed `WalkerContext.polymorphic` Javadoc vs amended narrowing rule
- **Claim under test**: no landed artifact steers the implementer back to the
  removed premise.
- **Evidence**: `WalkerContext.java:80-84` prescribes `@class` narrowing by
  chain-hop recognisers under `polymorphic=false`; amended `track-3.md:62`
  forbids it for bare hops and `StartStepRecogniser.java:156-160` documents
  why. The comment predates the BC2 chain-target decision and was written for
  the design.md §"Schema polymorphism" instruction now flagged for Phase-4
  reconciliation.
- **Verdict**: FRAGILE — the code compiles and runs either way; the comment
  misleads exactly the person item 7 sends into this file. → A11

## Summary

FAIL. A1 remains open on a single residual: the Context-and-Orientation mermaid
(`track-3.md:50-59`) still encodes the rejected
`addEdge(... SQLMatchPathItem.filter)` mechanism and the pre-claim mutation
flow, and the accepted A1 fix explicitly listed that diagram node. A2-A9 are
VERIFIED — each amendment resolves the underlying realization gap rather than
rewording it, and every codebase premise the amended plan leans on was
re-checked against the landed code and holds. Two new findings: A10
(should-fix — sync the plan file's Track 3 blurb, which still carries the
removed premises) and A11 (suggestion — schedule the stale
`WalkerContext.polymorphic` Javadoc fix). Expected next iteration: one diagram
edit, one blurb sync, one plan-item clause.
