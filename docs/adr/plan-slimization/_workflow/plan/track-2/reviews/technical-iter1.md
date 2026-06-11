<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 2, suggestion: 3}
index:
  - {id: T1, sev: should-fix, loc: "track-2.md §C&O bullet 7 (implementer-rules.md)", anchor: "### T1 ", cert: "Premise P7", basis: "guard paragraph spans lines 75-85, not 75-81; cited range undershoots and the implementer reads via §-anchor not line numbers"}
  - {id: T2, sev: should-fix, loc: "track-2.md §Plan-of-Work step 3 + §Interfaces (inline-replanning.md)", anchor: "### T2 ", cert: "Premise P6 + Integration I3", basis: "the live updatable-section enumeration is six per-case blocks (cases 1-6) plus a separate §2.1 mid-execution rewrite line; step 3 names only one of two surfaces D7 must reach"}
  - {id: T3, sev: suggestion, loc: "track-2.md §C&O bullet 9 + §Plan-of-Work step 7 (workflow.md / create-final-design.md)", anchor: "### T3 ", cert: "Integration I4", basis: "the §1.7(f) promotion machinery has two homes; the fold insertion point sits in create-final-design.md while the verdict carrier is the §Adversarial gate record carryover, an ordering the step does not spell out"}
  - {id: T4, sev: suggestion, loc: "track-2.md §Plan-of-Work step 1 + §C&O bullet on §2.1 (conventions-execution.md)", anchor: "### T4 ", cert: "Premise P8 + Edge case E1", basis: "the §2.1 Move-1 placeholder slot and the live Section-lifecycle table row both change; step 1 names the table rows and section descriptions but not the Move-1 reserved-slot comment the live file carries"}
  - {id: T5, sev: suggestion, loc: "track-2.md §Plan-of-Work step 2 (plan-slim-rendering.md)", anchor: "### T5 ", cert: "Premise P10 + Edge case E2", basis: "the slim-track rendering is new and the live renderer is a Python script (render-slim-plan.py) plus a doc; the prose-only edit cannot make sub-agents receive a slim track without a consumer change the track scopes out (S1)"}
evidence_base: {section: "## Evidence base", certs: 18, matches: 14}
flags: [CONTRACT_OK]
-->

# Track 2 technical review — iteration 1

Workflow-modifying track. The "codebase" is the workflow machinery; every
current-state claim in Track 2's `## Context and Orientation` and `## Plan of
Work` was audited against the LIVE develop version of each cited file (the
baseline Track 2 declares for all 11 in-scope files, except
`conventions-execution.md` §2.5 which Track 1 owns — Track 2 touches §2.1,
a disjoint section). Verification used path/anchor + section-content reads,
the lens the technical-review prompt re-points to for prose references on a
workflow-modifying plan. The frozen design seed (`design.md`) and the plan's
Decision Records D7/D9/D10/D12/D14/D16/D18 and invariants S2/S4/I6 were read
in full and used as the authority for "what the track should be aiming at."

Verdict: no blockers. Track 2's current-state map is accurate on every
load-bearing point — the panel selector, the frozen-design guard, the
design-absent consistency gap, the §2.1 `## Decision Log` lifecycle, the
duplication check, and the Phase-4 unconditional artifact production all
hold as described. Two `should-fix` findings sharpen claims that are
directionally right but incomplete (T1 line range, T2 the second
inline-replan surface). Three `suggestion` findings flag derivation
subtleties the decomposition should carry into steps.

## Findings

### T1 [should-fix]
**Certificate**: Premise P7 (frozen-design guard location).
**Location**: track-2.md `## Context and Orientation`, bullet 7
(`implementer-rules.md` §Loading discipline); live file
`.claude/workflow/implementer-rules.md`.
**Issue**: Track 2 claims the guard "carries the frozen-design guard
(lines 75-81; line 103 only cross-references it from the inputs contract)."
The guard paragraph actually spans lines 75-85 in the live file — the
`DESIGN_DECISION_NEEDED` continuation runs to line 85, so the cited
range undershoots by four lines. The load-bearing sentence the D7
rewording targets ("The plan's Decision Records and the track file are
the authoritative source of truth during execution") sits at lines
79-80, inside the cited range, so the claim is not wrong about *what* it
edits — but a step that opens "lines 75-81" and edits only that span
would clip the guard mid-paragraph. The line-103 cross-reference claim
is exactly right (the inputs contract's `design_path` bullet at lines
101-103 points back to "the frozen-design guard in §Loading discipline").
**Proposed fix**: Reword the C&O bullet to anchor on the section name and
the target sentence rather than a brittle line range: "carries the
frozen-design guard in §Loading discipline (the coupled-carriers sentence
naming `plan's Decision Records and the track file` as the authoritative
source of truth during execution); a separate cross-reference rides the
`design_path` inputs bullet." Line numbers in prose drift on the next
edit to the file above them; the §-anchor + sentence quote is stable. No
change to the D7 rewording target itself — design.md Part 4 confirms
"`plan's DRs` re-worded to `track's DRs`," and that phrase is at line 79.

### T2 [should-fix]
**Certificate**: Premise P6 (inline-replan updatable-section lists) +
Integration I3 (replan propagation duty surfaces).
**Location**: track-2.md `## Plan of Work` step 3 + `## Interfaces and
Dependencies` (file 6, `inline-replanning.md`); live file
`.claude/workflow/inline-replanning.md`.
**Issue**: Track 2 step 3 says it makes "the updatable-section lists gain
`## Decision Log`." In the live file there are two distinct surfaces that
together constitute "which sections a replan may rewrite," and the claim
names only one implicitly:
(1) the per-case enumeration in §"Updating plan and track files" (cases
1-6, lines 205-282), where cases 2 and 3 list the track-file sections a
revision updates — and `## Decision Log` is absent from both; and
(2) the cross-reference in `conventions-execution.md` §2.1 (line 252-256):
"Inline replanning ... may rewrite `## Concrete Steps`, `## Plan of Work`,
and `## Validation and Acceptance` mid-execution" — a second, terser
enumeration of the same fact that also omits `## Decision Log`.
A D7 propagation duty that "revises a duplicated decision" must reach the
`## Decision Log` of not-yet-completed tracks, so BOTH enumerations need
`## Decision Log` added or they will contradict each other after the edit
(one says the replan may touch Decision Log, the other still says it may
not). The §2.1 line is owned by Track 2 (file 9 is §2.1), so this is
in-scope, but step 3 frames it as a single "updatable-section lists" edit
in `inline-replanning.md` and the step roster could miss the §2.1 mirror.
**Proposed fix**: In step 3, split the propagation-duty edit explicitly:
(a) add the documentation-only `## Decision Log` supersession-append to
the relevant `inline-replanning.md` case (case 3 mid-execution revision,
and a new sub-clause under case 4 for the completed-track supersession
note — case 4 today says "Pause and ask the user," which step 3's
"documentation-only carve-out" must amend); and (b) update the
`conventions-execution.md` §2.1 mid-execution-rewrite line (line ~252) to
add `## Decision Log` to the rewritable set, so the two enumerations stay
consistent. Note in the step that case 4's existing pause is what the
carve-out relaxes.

### T3 [suggestion]
**Certificate**: Integration I4 (Phase-4 verdict fold wiring).
**Location**: track-2.md `## Context and Orientation` bullet 9 +
`## Plan of Work` step 7 (files 10-11, `workflow.md` §Final Artifacts and
`prompts/create-final-design.md`).
**Issue**: Track 2 correctly states both Phase-4 files produce
`design-final.md` + `adr.md` unconditionally and that "the §1.7(f)
promotion machinery lives in the same Phase-4 prompt and is unchanged."
Two precision notes for the decomposer:
(1) the §1.7(f) promotion machinery actually has TWO homes — the
narrative in `workflow.md` §Final Artifacts (step 1 at lines 616-635) AND
the operative step in `create-final-design.md` (lines 333-380). Both are
in-scope (files 10 and 11), and the track says promotion is *unchanged*,
so this is not a defect — but step 7 should make clear the fold is
*inserted around* the existing promotion/cleanup ordering, not into it.
(2) The fold's verdict source is the Track-1 carryover `## Adversarial
gate record` section in `research.md` (the prior-episode carryover names
this explicitly as "the canonical verdict carrier the Phase-4 fold
reads"), and create-final-design.md's cleanup (Step 6, line 414+) deletes
`_workflow/` wholesale — so the fold MUST read the gate record before the
cleanup commit. The track's `## Validation and Acceptance` line "The fold
reads the log's resolved gate records before the `_workflow/` cleanup
deletes them" captures this, but step 7's prose does not name the
`## Adversarial gate record` section as the read source.
**Proposed fix**: In step 7, name the read source concretely — "the fold
reads `research.md`'s `## Adversarial gate record` section (Track 1's
canonical verdict carrier) and writes the per-tier durable artifact before
create-final-design.md Step 6's `_workflow/` cleanup runs." This grounds
the ordering constraint already stated in acceptance and ties it to the
Track-1 carryover so the step author does not re-derive the carrier.

### T4 [suggestion]
**Certificate**: Premise P8 (§2.1 `## Decision Log` lifecycle) + Edge
case E1 (Move-1 reserved-slot placeholder).
**Location**: track-2.md `## Plan of Work` step 1 + `## Context and
Orientation` §2.1 bullet (file 9, `conventions-execution.md` §2.1).
**Issue**: Track 2 step 1 says "`## Decision Log` becomes plan-at-start +
continuous: ... the lifecycle table rows and the section descriptions
update accordingly." Verified accurate — the live §2.1 carries (a) the
Section-lifecycle table row (line 233: Phase-1 writer = "Move 1
placeholder", Phase-A writer = "—") and (b) the numbered section
description (lines 99-103: "continuous-log of execution-time decisions ...
Move 1 (YTDB-814) will later land per-track inlined Decision Records
here. Slot below the running log is reserved for Move 1 (empty
placeholder until that Move lands)"). The description bullet 4 contains a
forward-looking "Move 1 will later land" sentence and a reserved-slot
note that step 1's "section descriptions update accordingly" must rewrite
from future-tense-deferred to present-tense-active — the Move-1 framing is
the thing being resolved, not a third surface. Step 1 names "rows and
section descriptions" but not this Move-1 reserved-slot comment
specifically.
**Proposed fix**: In step 1, add the Move-1 reservation to the edit
inventory: "the lifecycle table row (line 233), the numbered section
description (bullet 4), and the bullet's Move-1 reserved-slot note — which
flips from `reserved for Move 1 (empty placeholder until that Move lands)`
to the now-active plan-at-start inline-DR home." This is the same
introduce-once Move-1 resolution Track 1 applied to the `design.md` seed
side; Track 2 owns the §2.1 track-side analog.

### T5 [suggestion]
**Certificate**: Premise P10 (no slim-track rendering today) + Edge case
E2 (renderer is a script + doc, not prose alone).
**Location**: track-2.md `## Plan of Work` step 2 + `## Context and
Orientation` bullet on `plan-slim-rendering.md` (file 8).
**Issue**: Track 2 correctly establishes that "track files are passed
whole today (no track-side rendering exists anywhere in the live
machinery)" — verified: `plan-slim-rendering.md` renders only the plan
(driven by the Python script `render-slim-plan.py`, line 67), and
`implementer-rules.md` passes the implementer `step_file_path` = the
whole track file (line 100) alongside `plan_slim_path` (line 99). Step 2
defines "the slim-track rendering in `plan-slim-rendering.md` (new)." The
subtlety the decomposer should weigh: the live plan-slim transform is
implemented by a script the doc only *describes* — `render-slim-plan.py`
under `.claude/scripts/`. Track 2's `## Interfaces and Dependencies`
out-of-scope list says "this track edits no script" (S1). So the
slim-track rendering Track 2 adds is necessarily a **doc-only rendering
rule** with no script backing, unlike the plan-slim path. That is a
legitimate design choice (D7 says full-tier track files grow and are
"bounded by ... the slim-track rendering"), but it means the slim-track
render is a manual/prose contract, not a script transform, and the
consumer that *receives* the slim track is the implementer/reviewer spawn
wiring — which step 2 touches via `implementer-rules.md` but which is
otherwise governed by `step-implementation.md`/`track-code-review.md`
(both explicitly out of scope per §Interfaces). Confirm the slim-track
render can be delivered by the inputs the in-scope files already control
(the implementer's `step_file_path` would need to point at a rendered
slim copy, or a new slim-track input added) without editing the
out-of-scope Phase-3B/3C orchestration docs.
**Proposed fix**: In step 2, state how the slim track reaches sub-agents
without a script and without touching the out-of-scope spawn docs: either
(a) the rendering is a prose rule the orchestrator applies inline when
composing the `step_file_path` value (no new input, no Phase-3B/3C doc
edit), or (b) flag a cross-track dependency if the slim track needs a new
sub-agent input the spawn docs must declare. Resolving this at
decomposition avoids a Phase-B discovery that the rendering has no
delivery path within Track 2's file set.

## Evidence base

#### Premise P1: track-review.md selects the Phase-3A panel by the step-count complexity axis
- **Track claim**: "`track-review.md` selects the Phase-3A panel by the step-count complexity axis (Simple / Moderate / Complex)"; today's upgrade rows: "critical paths or performance constraints add the Risk pass, while major architectural decisions or non-obvious scope add the Adversarial pass."
- **Search performed**: Read `.claude/workflow/track-review.md` §Complexity Assessment in full (lines 596-621).
- **Code location**: `.claude/workflow/track-review.md:607-621`
- **Actual behavior**: Two tables. The first (607-611) maps Simple (1-2 steps) → Technical only; Moderate (3-5) → Technical baseline + warranted; Complex (6-7 / critical / high-risk) → full. The second (615-621) is the upgrade table: "Moderate + critical paths or performance constraints → Technical + Risk"; "Moderate + major architectural decisions or non-obvious scope → Technical + Adversarial."
- **Verdict**: CONFIRMED
- **Detail**: Exact match to the track's current-state claim, including the upgrade-row semantics. design.md Part 6 line 904 confirms the live axis is the one D9 replaces.

#### Premise P2: today's 3A panel is Technical + Risk + Adversarial, Risk track-characteristic-gated
- **Track claim**: step 6 — "Risk stays track-characteristic-gated in `lite`/`full` and drops in `minimal`"; S4 keeps the per-step risk tag as the 3B gate.
- **Search performed**: Read track-review.md §Complexity Assessment + §Inputs (lines 596-727); cross-read design.md Part 6 matrix.
- **Code location**: `.claude/workflow/track-review.md:615-621`; design.md:893-913
- **Actual behavior**: The live Risk pass is gated by "critical paths or performance constraints" (and, in the design's widened target, "major architectural decisions"). The Phase-3A Risk reviewer is a distinct sub-agent from the per-step `risk` tag (design Part 6 line 909-910).
- **Verdict**: CONFIRMED
- **Detail**: Track 2 §C&O correctly flags that "the design's target Risk gate deliberately widens to include architectural decisions, so step 6 implements the design's Part-6 enumeration rather than today's mapping" — this is faithful to design.md line 908-910.

#### Premise P3: implementation-review.md runs Phase-2 consistency + structural unconditionally with a design half
- **Track claim**: "runs the Phase-2 consistency and structural passes unconditionally with a design half (design ↔ code ↔ plan); the design-frozen findings-routing rule defers frozen-design findings to Phase 4."
- **Search performed**: Read `.claude/workflow/implementation-review.md` in full (677 lines).
- **Code location**: `.claude/workflow/implementation-review.md:46-62, 199-219, 580-597`
- **Actual behavior**: Both steps run in sequence unconditionally (line 50-62); consistency checks Design↔Code / Plan↔Code / Design↔Plan (199-210); §"`design.md` is frozen" (580-597) records design-touching findings and defers them to the Phase-4 `design-final.md` reconciliation.
- **Verdict**: CONFIRMED

#### Premise P4: structural-review.md + prompts/structural-review.md carry the duplication check pointing at design.md
- **Track claim**: "carry the bloat checks whose fix destinations point at `design.md`, and the duplication check that compares a long plan DR against its matching design section — a check that would fire backwards against the now-mandated full track DRs."
- **Search performed**: Read structural-review.md:49-150 and prompts/structural-review.md:300-421 (grep + targeted Read).
- **Code location**: `.claude/workflow/structural-review.md:70-76`; `.claude/workflow/prompts/structural-review.md:341-348`
- **Actual behavior**: structural-review.md:75 — "Plan/design duplication | should-fix | A DR body or Architecture Notes subsection is >50 lines AND `design.md` has a section whose title matches the DR's topic | Replace the duplicated body in the plan with a one-line link to the matching `design.md` section." Bloat-fix destinations (rows 70-73) all say "move ... to `design.md`." Prompt mirror at 341-348.
- **Verdict**: CONFIRMED
- **Detail**: The "fire backwards" risk is real — under D7 the full track DR matching a design section is the *mandated* shape, so the >50-line + title-match heuristic would flag the mandated duplication as bloat. design.md Part 7 line 1011-1015 confirms the repurpose-into-fidelity-check resolution. The check's `mechanical` classification (auto-fix, no user ask) at prompts/structural-review.md:421 makes the backward-fire especially dangerous, reinforcing step 5's priority.

#### Premise P5: prompts/consistency-review.md reads design + plan + code with no design-absent branch
- **Track claim**: "reads design + plan + code with no design-absent branch."
- **Search performed**: Read prompts/consistency-review.md (TOC + Inputs + Review Criteria; grep for design.md / design-absent / no-design).
- **Code location**: `.claude/workflow/prompts/consistency-review.md:104-124, 188-230`
- **Actual behavior**: Inputs require `design_path` (line 121, "Design document: {design_path}"); the four Review Criteria axes (DESIGN↔CODE 191, PLAN↔CODE 206, DESIGN↔PLAN 228, GAPS) all assume a design document. No conditional guards on design presence anywhere in the file.
- **Verdict**: CONFIRMED
- **Detail**: This is the surface D10's design-presence conditionals (step 4) must add. The staged-read precedence note (line 124) is workflow-modifying handling, not a design-presence guard.

#### Premise P6: inline-replanning.md owns the updatable-section lists (no `## Decision Log` entry), completed-track pause, and the ESCALATE path
- **Track claim**: "owns the updatable-section lists (no `## Decision Log` entry today), the completed-track pause (no carve-out for documentation-only appends), and the ESCALATE path D12's tier upgrade rides."
- **Search performed**: Read inline-replanning.md in full (282 lines), focus §When ESCALATE triggers (20-53), §Updating plan and track files cases 1-6 (193-282).
- **Code location**: `.claude/workflow/inline-replanning.md:236-267`; `conventions-execution.md:252-256`
- **Actual behavior**: Cases 2 (not-yet-started, 236-246) and 3 (mid-execution, 248-259) enumerate the updated track-file sections — `## Decision Log` absent from both. Case 4 (261-267) "Revising a completed track ... **Pause and ask the user** before proceeding" — no documentation-only carve-out. ESCALATE triggers and process are owned here (1-189). The §2.1 cross-reference (conventions-execution.md:252-256) independently lists the rewritable set as Concrete Steps / Plan of Work / Validation and Acceptance — also omitting Decision Log.
- **Verdict**: PARTIAL
- **Detail**: The claim is correct but the "updatable-section lists" exist in two coordinated places (inline-replanning cases + the §2.1 mid-execution line). See finding T2 — both must gain `## Decision Log` or they contradict.

#### Premise P7: implementer-rules.md §Loading discipline carries the frozen-design guard at lines 75-81
- **Track claim**: "§Loading discipline carries the frozen-design guard (lines 75-81; line 103 only cross-references it from the inputs contract). The guard's sentence couples two carriers ... the D7 rewording targets that whole sentence."
- **Search performed**: Read `.claude/workflow/implementer-rules.md:60-120`.
- **Code location**: `.claude/workflow/implementer-rules.md:75-85, 101-103`
- **Actual behavior**: The **Frozen-design guard.** paragraph runs lines 75-85 (not 75-81). The coupled-carriers sentence "The plan's Decision Records and the track file are the authoritative source of truth during execution" is at lines 79-80. The `design_path` inputs bullet (101-103) cross-references "the frozen-design guard in §Loading discipline."
- **Verdict**: PARTIAL
- **Detail**: The target sentence is inside the cited 75-81 range, and the line-103 cross-reference claim is exactly right. But the guard paragraph extends to 85 — the cited upper bound undershoots by 4 lines. See finding T1. design.md Part 4 line 673 confirms the rewording target ("`plan's DRs` re-worded to `track's DRs`").

#### Premise P8: conventions-execution.md §2.1 tabulates `## Decision Log` as execution-time-only, becoming plan-at-start
- **Track claim**: "`## Decision Log` is execution-time-only there today and becomes a plan-at-start home written from Phase 1 (full inline DRs) that execution appends to."
- **Search performed**: Read conventions-execution.md §2.1 (lines 27-273): numbered section descriptions + Section lifecycle table.
- **Code location**: `.claude/workflow/conventions-execution.md:99-103, 233`
- **Actual behavior**: Section description bullet 4 (99-103): "continuous-log of execution-time decisions (inline-replan choices, scope-downs, dependency reveals, gate overrides). Move 1 (YTDB-814) will later land per-track inlined Decision Records here. Slot below the running log is reserved for Move 1." Lifecycle table row (233): `## Decision Log | Move 1 placeholder | — | promotion ... | gate-override / inline-replan entries | Phase A reviews; Phase 4`. Phase-1 writer = placeholder only; Phase-A writer = none.
- **Verdict**: CONFIRMED
- **Detail**: Today execution-time-only (Phase B/C writers). The Move-1 reserved slot is the placeholder that step 1's "plan-at-start home written from Phase 1" resolves to active. See T4 for the Move-1 reserved-slot note that step 1's inventory should name explicitly.

#### Premise P9: workflow.md §Final Artifacts and create-final-design.md produce design-final.md + adr.md unconditionally
- **Track claim**: "produce `design-final.md` + `adr.md` unconditionally; the §1.7(f) promotion machinery lives in the same Phase-4 prompt and is unchanged by this track."
- **Search performed**: grep + Read of workflow.md:604-706 and prompts/create-final-design.md (grep for design-final/adr/§1.7(f)/cleanup/tier).
- **Code location**: `.claude/workflow/workflow.md:604-639`; `.claude/workflow/prompts/create-final-design.md:84-88, 123-126, 219-222, 333-417`
- **Actual behavior**: Both files author `design-final.md` + `adr.md` with no tier conditional (workflow.md:611 "the two artifacts that survive"; create-final-design.md:84 "the **only** workflow files that survive"). No occurrence of "tier"/"lite"/"minimal" in create-final-design.md. §1.7(f) promotion: workflow.md step 1 (616-635) + create-final-design.md step ~333-380 (the "promotion is additive" + rebase-precedes-promotion guard). Cleanup at create-final-design.md Step 6 (414+).
- **Verdict**: CONFIRMED
- **Detail**: The promotion machinery is in BOTH Phase-4 files (both in-scope, files 10-11), and "unchanged by this track" holds — the fold is additive. See T3 on the fold's read-source ordering.

#### Premise P10: no track-side slim rendering exists in the live machinery
- **Track claim**: "track files are passed whole today (no track-side rendering exists anywhere in the live machinery). D7's consumption model needs a new slim-track rendering."
- **Search performed**: Read plan-slim-rendering.md in full (221 lines); Read implementer-rules.md inputs (89-103).
- **Code location**: `.claude/workflow/plan-slim-rendering.md:1-221`; `.claude/workflow/implementer-rules.md:99-100`
- **Actual behavior**: plan-slim-rendering.md renders only the plan; for `[ ]` tracks the transform is "a no-op ... passes them through verbatim" (88-92) — the track *detail* lives in the track file, untouched. The implementer receives `plan_slim_path` (slim plan, 99) and `step_file_path` (the whole track file, 100). No slim-track transform anywhere.
- **Verdict**: CONFIRMED
- **Detail**: The slim-track rendering is genuinely new. See T5 — the live plan-slim transform is a Python script (render-slim-plan.py); a slim-track rendering Track 2 adds is necessarily doc-only (S1 forbids script edits), and its delivery path to sub-agents needs to stay inside the in-scope file set.

#### Integration I1: D9 review matrix realization (track-2 step 4 + step 6 vs design Part 6)
- **Plan claim**: step 4/6 — `minimal` drops structural + 3A risk + 3A adversarial; consistency lightens to track-vs-code; `lite`/`full` keep narrowed 3A adversarial with episode challenge dropped on track 1.
- **Actual entry point**: design.md Part 6 matrix (lines 893-913); implementation-review.md (Phase-2 passes) + track-review.md (Phase-3A panel) are the edit sites.
- **Caller analysis**: The Phase-2 passes are selected in implementation-review.md State 0 (no tier read today); the Phase-3A panel in track-review.md §Complexity Assessment (step-count today). Both are in-scope (files 1-2, 5).
- **Breaking change risk**: Low — the edits add tier-keyed conditionals; the develop-state fallback (no tier line → today's behavior) is the design's I6 safety net since live files stay at develop.
- **Verdict**: MATCHES
- **Detail**: Track 2's acceptance criterion "The D9 review matrix is realized exactly" binds each (pass, tier) cell to design Part 6. Faithful.

#### Integration I2: D18 tier line read by Phase-2/3A selectors
- **Plan claim**: §Signatures — "The D18 tier line is read-only for every consumer in this track; only `create-plan` writes it." Step 4 reads the tier line; step 6 reads it.
- **Actual entry point**: implementation-plan.md tier line (written by Track 1's create-plan template work, D18); read by implementation-review.md State 0 and track-review.md Phase A.
- **Caller analysis**: D18 (plan DR) "Implemented in: Track 1 (template), Track 2 (readers)" — confirms Track 2 is the reader side. The plan's Integration Points (lines 437-440) name "implementation-review.md State 0: Phase-2 pass selection reads the D18 tier line" and "track-review.md Phase A: panel selection reads the tier."
- **Breaking change risk**: Low — read-only consumption.
- **Verdict**: MATCHES
- **Detail**: S2 forbids reading the tier from the research log (dies at cleanup); D18 §Alternatives confirms the plan is the home. Track 2 correctly treats the tier line as an existing input (Track 1 produces it).

#### Integration I3: D7 cross-track propagation duty (track-2 step 3)
- **Plan claim**: step 3 — replan revising a duplicated decision updates every not-yet-completed track copy in the same replan + appends a supersession note to completed tracks' `## Decision Log`; updatable-section lists gain `## Decision Log`; completed-track pause gains a documentation-only carve-out.
- **Actual entry point**: inline-replanning.md cases 2/3/4 (236-267) + conventions-execution.md §2.1 mid-execution-rewrite line (252-256).
- **Caller analysis**: Case 4 today is a hard "Pause and ask the user" with "The existing `[x]` status does not change; any new scope becomes a new track." The carve-out must amend case 4 to allow the documentation-only supersession append.
- **Breaking change risk**: Medium — the carve-out relaxes a user-gate (case 4 pause). The relaxation is scoped to "documentation-only" appends, which is safe, but the step must state the boundary so it does not over-relax into content revisions.
- **Verdict**: MATCHES (with the T2 completeness gap)
- **Detail**: Both updatable-section enumerations (inline-replanning cases + §2.1 line) must gain `## Decision Log`. See T2.

#### Integration I4: Phase-4 verdict fold reads the Track-1 `## Adversarial gate record` carrier
- **Plan claim**: step 7 — the fold reads the log's resolved gate records before cleanup; per-tier durable artifacts.
- **Actual entry point**: create-final-design.md (fold insertion + Step 6 cleanup at 414+); the read source is research.md `## Adversarial gate record` (Track-1 carryover).
- **Caller analysis**: Track-1 episode names `## Adversarial gate record` in research.md as "the canonical verdict carrier the Phase-4 fold reads ... (not the ephemeral `_workflow/reviews/` files)." create-final-design.md Step 6 deletes `_workflow/` wholesale, so the fold's read must precede the cleanup commit.
- **Breaking change risk**: Low — additive fold; ordering is the only constraint.
- **Verdict**: MATCHES
- **Detail**: See T3 — step 7 prose should name the `## Adversarial gate record` read source and the before-cleanup ordering explicitly.

#### Edge case E1: §2.1 Move-1 reserved-slot placeholder on the live track file
- **Trigger**: step 1 rewrites the §2.1 `## Decision Log` description; the live description carries a forward-looking "Move 1 will later land" sentence + a reserved-slot note.
- **Code path trace**:
  1. conventions-execution.md:99-103 — section description bullet 4 carries the Move-1 deferral.
  2. The live track-2.md file itself (line 34) carries `<!-- Reserved for Move 1 — per-track inlined Decision Records. -->` in its `## Decision Log`.
  3. Step 1 must flip the §2.1 description from deferred-future to active-present.
- **Outcome**: If step 1 edits only the lifecycle table row + the running-log description sentence but leaves the Move-1 reserved-slot note, the §2.1 text self-contradicts (says Decision Log is plan-at-start AND that the inline-DR slot is "reserved until Move lands").
- **Track coverage**: PARTIAL — step 1 says "section descriptions update accordingly" but does not enumerate the Move-1 note. See T4.

#### Edge case E2: slim-track rendering delivery without a script or out-of-scope spawn-doc edit
- **Trigger**: step 2 adds a slim-track rendering; S1 forbids script edits; the spawn docs (step-implementation.md, track-code-review.md) are out of scope.
- **Code path trace**:
  1. plan-slim-rendering.md:67 — the live plan-slim transform is `render-slim-plan.py`, a script.
  2. implementer-rules.md:100 — implementer receives `step_file_path` = whole track file (no slim variant).
  3. The slim-track render must reach the implementer/reviewer either via inline orchestrator rendering of the `step_file_path` value or a new input.
- **Outcome**: A doc-only slim-track rule with no delivery wiring leaves the rendering defined but unused — sub-agents still receive the whole track file.
- **Track coverage**: PARTIAL — step 2 defines the rendering but not its delivery path. See T5.

#### Edge case E3: develop-state fallback keeps live workflow stable (I6)
- **Trigger**: Track 2 edits land in the staged mirror; the LIVE `.claude/**` runs at develop state until Phase-4 promotion.
- **Code path trace**:
  1. Plan Constraints (38-42) + I6 (422-424): live workflow byte-identical to develop for the branch lifetime.
  2. A `/execute-tracks` run during the branch executes the develop-state docs, which have no tier conditionals — so the running workflow behaves exactly as today.
- **Outcome**: No behavior change to the running workflow during execution; the tier machinery activates only after promotion. Correct and intended.
- **Track coverage**: yes — §Plan of Work invariants line names I6 ("all edits staged"); §Interfaces routes every write to the staged mirror.

#### Edge case E4: S4 — tier and per-step risk tag never stack
- **Trigger**: step 6 replaces the step-count axis with the tier as the 3A change-level selector while the per-step risk tag stays the 3B gate.
- **Code path trace**:
  1. design.md Part 6 line 938-944 + S4 (plan line 419-421): tier = change-level driver; risk tag = per-step 3B/3C gate; different scopes.
  2. Step 6 explicitly states "S4: the per-step risk tag stays the 3B gate, triage stays the 3C gate."
  3. track-2 acceptance: "no staged sentence combines the tier and the per-step risk tag into one selection signal."
- **Outcome**: The two complexity signals address different scopes; no single inflated signal. The acceptance check is a dry-run grep over the staged sentences.
- **Track coverage**: yes — both step 6 and the §Validation acceptance criterion guard S4.
