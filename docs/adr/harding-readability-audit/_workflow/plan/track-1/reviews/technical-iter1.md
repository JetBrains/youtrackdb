<!--
MANIFEST
role: reviewer-technical
phase: 3A
track: Track 1: Harden readability-auditor slicing and convergence
iteration: 1
verdict: PASS
findings: 2
blockers: 0
should_fix: 0
suggestions: 2
index:
  - id: T1
    sev: suggestion
    anchor: "#t1-suggestion"
    loc: "create-plan/SKILL.md:812-826; track-1.md D5 / Plan-of-Work step 5"
    cert: "Premise: D5 track-path cross-reference is additive over an existing rule"
    basis: read
  - id: T2
    sev: suggestion
    anchor: "#t2-suggestion"
    loc: "readability-feedback/SKILL.md:33-34,65-94; track-1.md D2 / Plan-of-Work step 5"
    cert: "Premise: /readability-feedback spawns general-purpose, not readability-auditor"
    basis: read
evidence_base:
  premises: 11
  edge_cases: 1
  integrations: 2
  confirmed: 14
  not_confirmed: 0
tooling: "grep + Read over live develop-state .claude/** files; mcp-steroid PSI not applicable (no Java surface); staged-workflow tree absent at Phase A so all reads resolve to live files per the staged-read precedence"
-->

# Technical review — Track 1 (iteration 1)

This track edits only `.claude/**` workflow markdown; there is no Java symbol
surface, so the prompt's Workflow-machinery criteria apply — every named
reference is verified as a workflow file path or `§`-anchor via grep + Read,
and the five prose criteria (rule coherence / non-contradiction, instruction
completeness, prompt-design soundness, context-budget impact, breakage of
dependent prompts or agents) replace the Java-oriented criteria. The branch
ledger's last `s17` is `workflow-modifying` and the `_workflow/staged-workflow/`
tree does not exist yet, so every `.claude/**` read resolves to the live
develop-state file.

Verdict: **PASS**. Every current-state claim the track and Decision Records
D1–D8 make is confirmed against the live files, the design is feasible as prose
obligations, and the Component Map edges are accurate. Two `suggestion`-level
findings note places where the cross-reference work is partly already done
(T1) and where a sibling tool uses different spawn machinery than the loop path
(T2) — both are decomposition hygiene, not correctness defects.

## Findings

### T1 [suggestion]
**Certificate**: Premise — "D5 track-path cross-reference is additive over an existing rule"
**Location**: `create-plan/SKILL.md:812-826`; track-1.md D5 and `## Plan of Work` step 5
**Issue**: D5 and Plan-of-Work step 5 frame the track-path convergence/slicing
work as "`create-plan` Step 4b item 9 cross-references it with the track-path
values," implying the track-path slicing rule and standing-anchor set need to
be supplied. They are already present verbatim in item 9: lines 812–821 carry
the per-file deterministic partition ("one `readability-auditor` spawn per
`plan/track-N.md` (in track-number order)... a whole-file `range`"), and lines
822–825 carry the track-path standing anchors ("the **plan Component Map and
each track's `## Purpose / Big Picture`**"). So the *slicing* half of D5 is
already in the live file; what is genuinely missing on the track path is the
*convergence* half — section/file-keyed settled-state and the anchor-folded
hash (D4/D8), which item 9 does not yet carry. If decomposition treats D5 as
"add the track-path slicing values," it will either duplicate the existing
lines 812–826 or contradict them.
**Proposed fix**: In the track file's `## Concrete Steps` (written at
decomposition) and in the D5 prose, scope the create-plan edit precisely: the
edit ADDS the convergence-mechanism cross-reference (settled-state keyed per
`track-N.md` file, anchors = Component Map + each track's `## Purpose`) and
re-points item 9's params home to `_workflow/reviews/` (D6); it must NOT
re-author the per-file slicing rule, which already exists at lines 812–826.
Note that item 9 already states the track-path slicing rule is the deterministic
partition, so the slicing principle is satisfied there without a new edit.

### T2 [suggestion]
**Certificate**: Premise — "/readability-feedback spawns general-purpose, not readability-auditor"
**Location**: `readability-feedback/SKILL.md:33-34, 65-94`; track-1.md D2 and `## Plan of Work` step 5
**Issue**: D2 and Plan-of-Work step 5 say `/readability-feedback`
"cross-references the canonical partition statement so the standalone tool and
the in-loop path cannot drift on window size or cap." The window/cap value the
cross-reference targets is shared and the back-reference is feasible (the
~200-line / `##`-`# Part` / cap-~6 rule the track ports originates here, line
33). But the framing "tool and in-loop path" can read as if they share spawn
machinery: they do not. `/readability-feedback` step 3 launches
`general-purpose` sub-agents with an inline self-contained dispatch prompt
(`## Audit sub-agent prompt`, lines 65–94), whereas the in-loop path spawns the
`readability-auditor` agent. The cross-reference can therefore couple only the
partition *value* (window size, boundary set, cap), not the agent contract or
the params channel. The whole-doc floor and the `slice_count`/`total_lines`
params (D1/D2) belong to the in-loop path only — porting them into
`/readability-feedback` would be wrong, because its agents take no params file.
**Proposed fix**: In the D2 prose and the decomposition step, state the
cross-reference scope explicitly: `/readability-feedback` step 2 back-references
the canonical partition statement (`edit-design` Step 4) for **window size,
boundary set, and cap only**; it keeps its own `general-purpose` inline-prompt
fan-out and does not adopt the whole-doc floor, the agent guard, or the new
params. This prevents a decomposition that tries to make `/readability-feedback`
spawn `readability-auditor` or pass it params.

## Evidence base

#### Premise: edit-design Step 4 spawns the auditor as "is range-sliced" with no partition rule, slice count, or floor
- **Track claim**: D1 / `## Context and Orientation`: "Step 4 spawns the per-round auditor (today: 'is range-sliced,' with no partition rule)."
- **Search performed**: grep + Read over `edit-design/SKILL.md` Step 4 (lines 446–727)
- **Code location**: `.claude/skills/edit-design/SKILL.md:676-682`
- **Actual behavior**: "**The readability auditor** ... is range-sliced: each slice gets its own spawn whose params file carries `target=design`, `target_path=<design_path>`, and the slice `range`." No partition algorithm, no slice count, no floor, no `slice_count`/`total_lines` params appear. The shared spawn-contract params list (lines 513–527) names only `range` / `target` / `target_path` / `output_path` for the auditor.
- **Verdict**: CONFIRMED
- **Detail**: The track's edit (add partition + floor + self-check + two params) lands on a genuine gap.

#### Premise: edit-design Step 6 states the convergence claim and holds the resume round-count glob over per-round params on disk
- **Track claim**: D6 / `## Context and Orientation`: "Step 6 states the convergence claim and holds the resume round-count glob"; the glob "recovers which round a resumed loop was on by counting per-round params files on disk."
- **Search performed**: grep + Read over `edit-design/SKILL.md` Step 6 (lines 754–846)
- **Code location**: `.claude/skills/edit-design/SKILL.md:821-837` (convergence prose 832–837; resume glob 824–825)
- **Actual behavior**: Lines 832–837 argue convergence ("the two checks converge because they re-open the loop for disjoint reasons ... moves monotonically toward dual-clean"). Lines 824–825: "re-derive the round count from the latest per-round params files written under `_workflow/plan/` (each round writes one)." No section-keyed settled-state / hash exists today (grep for hash|settled|fingerprint|re-flag returned only unrelated matches).
- **Verdict**: CONFIRMED
- **Detail**: Both halves of the claim hold — the convergence argument is present and the resume glob reads `_workflow/plan/`, the home D6 relocates.

#### Premise: readability-auditor.md describes "Range-sliced fan-out" as a description (not a hard requirement) and has no whole-doc guard
- **Track claim**: D2 / `## Context and Orientation`: "Today 'Range-sliced fan-out' is a description, not a requirement, and the agent has no whole-doc guard."
- **Search performed**: Read of `.claude/agents/readability-auditor.md` in full
- **Code location**: `.claude/agents/readability-auditor.md:3` (frontmatter description "Range-sliced fan-out."), `:25` ("a range-sliced fan-out where each slice is obligated to enumerate every finding in its range"), `:47` ("You are range-sliced, so you take the checks a slice plus the standing anchors can answer")
- **Actual behavior**: All three mentions are descriptive prose; there is no MUST/hard-requirement phrasing and no guard that fires on `slice_count == 1 AND total_lines > ~300`. The `## Inputs` block (lines 75–83) lists only `target`, `target_path`, `range` — no `slice_count` / `total_lines`.
- **Verdict**: CONFIRMED
- **Detail**: The edit (turn description → hard requirement, add the guard, add two params) lands on a real gap. The params channel exists and is extensible.

#### Premise: readability-auditor cold-read scope is slice + standing anchors + house-style, no research log
- **Track claim**: D3 / S1: "it reads its slice plus the standing anchors fully cold every spawn"; "the auditor still reads no research log and receives no settled-state (S1)."
- **Search performed**: Read of `.claude/agents/readability-auditor.md`
- **Code location**: `.claude/agents/readability-auditor.md:27-29` (S1 invariant), `:62-69` (standing anchors), `:71-73` (house-style reads)
- **Actual behavior**: "Your tool allow-list is `Read` plus `Grep` ... the only paths you read are `house-style.md`, your document slice, and the standing anchors." Standing anchors: `target=design` → `## Overview` + `## Core Concepts`; `target=tracks` → plan Component Map + each track's `## Purpose / Big Picture` (lines 66–67). No log path is passed.
- **Verdict**: CONFIRMED
- **Detail**: The new params (slicing metadata, constant across the fan-out) do not breach S1 — they are not log content and not conclusions.

#### Premise: conventions-execution §2.5 defines a Third-scope review-file home scoped today to the Phase-0→1 gate's files
- **Track claim**: D6 / `## Context and Orientation`: "§2.5 defines the third-scope review-file home, scoped today to 'the Phase-0→1 gate's files.'"
- **Search performed**: grep + Read over `conventions-execution.md` (TOC line 17; section 707–733; §-numbering scan)
- **Code location**: `.claude/workflow/conventions-execution.md:707-719`
- **Actual behavior**: Heading `### Third-scope review-file home (Phase 0→1 gate)` is a `###` under `## 2.5 Review-file schema, count validation, and coverage` (the §2.5 span runs from line 539; its `###` subsections are 611/663/707/734). The home is named `docs/adr/<dir-name>/_workflow/reviews/` (line 715); the TOC row 17 scopes it "(Phase 0→1 gate)."
- **Verdict**: CONFIRMED
- **Detail**: Generalizing the home from "the Phase-0→1 gate's files" to "Phase-1 plan-scoped review scaffolding" is a real, well-targeted edit. The reviews/ directory already exists on disk holding `research-log-adversarial-iter{1,2}.md`.

#### Premise: the Phase-1 authoring-loop params files live in _workflow/plan/ today (D6 relocation source)
- **Track claim**: D6: "Every Phase-1 authoring-loop per-spawn params file ... moves out of `_workflow/plan/` into the plan-scoped `_workflow/reviews/`."
- **Search performed**: `ls _workflow/plan/`; grep over `edit-design/SKILL.md` and `create-plan/SKILL.md` for the params home
- **Code location**: `_workflow/plan/` (on disk: author-*, readability-*, absorption-*, comprehension-* params files alongside `track-1.md`); `edit-design/SKILL.md:524,624`; `create-plan/SKILL.md:782`
- **Actual behavior**: Both skills name `_workflow/plan/` as the per-spawn params home; the live `plan/` directory holds 24 authoring-loop params files plus `track-1.md`, confirming the pollution D6 cites. The resume glob (above) reads the same directory.
- **Verdict**: CONFIRMED

#### Premise: create-plan Step 4b item 9 is the track-path analog of the edit-design phase1-creation loop, spawns the same readability-auditor, with iteration_budget, referencing edit-design's loop contracts
- **Track claim**: D5 / `## Context and Orientation`: item 9 "runs the track-path analog of the design-path loop, spawning the same auditor agent per track file ... same `iteration_budget`."
- **Search performed**: Read of `create-plan/SKILL.md:764-863`
- **Code location**: `.claude/skills/create-plan/SKILL.md:773-775` (analog statement), `:778-787` (reuses the four roles + edit-design Step 4 spawn contracts), `:810-826` (readability-auditor per track file), `:847` (`iteration_budget` default 3), `:849` (defers to `edit-design/SKILL.md` § Step 6)
- **Actual behavior**: Item 9 is "the track-path analog of the `edit-design phase1-creation` loop ... parameterized to `target=tracks`," spawns `subagent_type: readability-auditor` one per `plan/track-N.md`, bounded by `iteration_budget` (default 3), explicitly reusing edit-design Step 4's spawn contracts and Step 6's termination.
- **Verdict**: CONFIRMED
- **Detail**: Item 9 ALSO already carries the track-path per-file slicing rule and the track-path standing anchors (lines 812–826) — see finding T1.

#### Premise: design-document-rules.md carries a `### Cold-read sub-agent prompt` section with no slicing/partition statement today
- **Track claim**: D2 / `## Context and Orientation`: "It carries no slicing statement today, so this change adds a one-line cross-reference."
- **Search performed**: grep for the heading + Read of the section
- **Code location**: `.claude/workflow/design-document-rules.md:344-353`
- **Actual behavior**: The section describes the cold-read prompt (`prompts/design-review.md`), the fresh sub-agent, comprehension questions, and "once per auto-review cycle (skipped for `mechanics-edit`)." No window size, partition, slice count, or floor appears.
- **Verdict**: CONFIRMED
- **Detail**: The edit ADDS a one-line cross-reference to `edit-design` Step 4; it does not sync an existing statement. Accurately characterized.

#### Premise: readability-feedback Procedure step 2 carries the ~200-line partition rule (windows on ##/# Part, cap ~6)
- **Track claim**: D1/D2 / `## Context and Orientation`: "the proven ~200-line partition rule this change ports."
- **Search performed**: Read of `readability-feedback/SKILL.md` in full
- **Code location**: `.claude/skills/readability-feedback/SKILL.md:33`
- **Actual behavior**: "**Partition.** Split the doc into ~200-line ranges on `##` / `# Part` boundaries; give each companion file its own range. Cap at ~6 sub-agents." This is the exact partition D1 ports into `edit-design` Step 4.
- **Verdict**: CONFIRMED
- **Detail**: The fan-out here uses `general-purpose` agents and an inline prompt, not the `readability-auditor` agent — see finding T2. The partition *value* is shared and the back-reference is sound.

#### Premise: the never-clean tail exits through the existing iteration_budget + S5 user-is-the-gate path (no hold mechanism today)
- **Track claim**: D8: "the loop exits through the existing S5 user-is-the-gate path"; "the loop is bounded ... by `iteration_budget` (default 3)."
- **Search performed**: grep + Read over `edit-design/SKILL.md` Step 6 Outcomes (lines 878–890) and `iteration_budget` (line 113, 760)
- **Code location**: `.claude/skills/edit-design/SKILL.md:882-890` (budget-exhaustion outcomes), `:760` ("bounded by the `iteration_budget` (default 3) and both exit to the user on budget exhaustion ... (S5)")
- **Actual behavior**: "Budget exhausted with only `should-fix` findings remaining: the action completes with a warning. Log the unresolved findings and proceed ... The user is the gate when the action can't self-correct." Exactly the residual-acceptance path D8 routes the never-clean tail through. No accept-as-held / calibrated-hold mechanism exists today, so dropping it removes nothing live.
- **Verdict**: CONFIRMED

#### Premise: tier is full and §1.7 routing is full staging; ledger s17 = workflow-modifying
- **Track claim**: D7 / I6: "every edit lands under `_workflow/staged-workflow/.claude/...` and a Phase 4 promotion commit copies them live."
- **Search performed**: Read of `_workflow/phase-ledger.md`; `ls _workflow/staged-workflow/`
- **Code location**: `docs/adr/harding-readability-audit/_workflow/phase-ledger.md:1` (`s17=workflow-modifying tier=full`)
- **Actual behavior**: Ledger line 1 records `phase=0 tier=full categories="Workflow machinery" s17=workflow-modifying`; line 2 `phase=A`. The `_workflow/staged-workflow/` tree does not exist yet (correct at Phase A — it is created when the first staged edit lands in Phase B). I6's "no live edit before promotion" is verified by the absence of any in-scope edit on disk.
- **Verdict**: CONFIRMED

#### Integration: Component Map edges (O→A spawns one per slice; O maintains SS; ED/RA/CE define O/A/SS; CP/DDR/RF cross-reference ED)
- **Plan claim**: Component Map: `O -->|spawns one per slice| A`; `O -->|maintains| SS`; `ED -.defines.-> O`; `RA -.defines.-> A`; `CE -.defines.-> SS`; `CP/DDR/RF -.cross-references.-> ED`.
- **Actual entry point**: `edit-design/SKILL.md` Step 4 (defines the orchestrator's spawn = ED→O) and Step 6 (the loop O runs); `readability-auditor.md` (defines A); `conventions-execution.md` §2.5 Third-scope home (the SS/scaffolding home, after generalization).
- **Caller analysis**: ED→O confirmed (Step 4/6 are the orchestrator's procedure). RA→A confirmed (the agent contract). O→A "spawns one per slice" confirmed (Step 4 line 677; Step 6 line 803–804). O maintains SS is NEW behavior the track adds (no settled-state today) — correctly drawn as the track's change, not an existing edge. CE→SS is the relocation target (the params/settled-state scaffolding home), accurate after the §2.5 generalization. CP→ED confirmed live (create-plan item 9 already defers to edit-design Step 4/6). DDR→ED and RF→ED are edges the track ADDS (today DDR points at `prompts/design-review.md`; RF is itself the partition source) — correctly the "what changes" column.
- **Breaking change risk**: None. All edges are either already live or additive prose cross-references; no live consumer's contract is removed or repointed in a way that breaks an existing reader.
- **Verdict**: MATCHES

#### Integration: feasibility of orchestrator-side section-keyed settled-state + anchor-folded hash as prose obligations, and the auditor params channel
- **Plan claim**: D4/D5 require the orchestrator to hold per-section settled-state keyed on identity + an anchor-folded content hash, expressed in Step 6 prose without a script; D1/D2 require two new auditor params (`slice_count`, `total_lines`).
- **Actual entry point**: `edit-design/SKILL.md:821-830` (orchestrator already holds per-round state "in the orchestrator's working memory, not in a file" and re-derives counts/budgets in prose); `readability-auditor.md:75-83` (`## Inputs` params channel).
- **Caller analysis**: Step 6 already expresses non-trivial orchestrator obligations in prose (round count, budget decrement, mechanical-first sub-loop, resume re-derivation) with no script. A section-keyed settled map + recompute-hash + drop-on-unchanged obligation is the same shape — feasible as prose. The auditor reads its params file first (agent definition mandates it), and the file is an open key/value channel — adding `slice_count` / `total_lines` is a one-line schema extension on both the spawn side (Step 4 shared params list) and the agent side.
- **Breaking change risk**: None — additive params on a file already read first; constant across the fan-out so they do not bust the shared-prompt cache the warm-up relies on (the cache key is the byte-identical prompt body, not the params file).
- **Verdict**: MATCHES

#### Edge case: a legitimate single-slice short doc (< ~300 lines) under the new partition + guard
- **Trigger**: a `design.md` under the ~300-line floor where the partition correctly emits one slice.
- **Code path trace**:
  1. Orchestrator partition (D1, new in Step 4): doc < ~250 lines → 1 window → `expected_slice_count == 1`.
  2. Self-check `slices_spawned == expected_slice_count` → 1 == 1, passes.
  3. Auditor guard (D2, new in readability-auditor.md): fires only when `slice_count == 1 AND total_lines > ~300` → `total_lines` below floor → does not fire.
- **Outcome**: All three actors agree on the one-slice outcome; no false wiring error. This is D2's stated risk/caveat, and the track resolves it correctly (the floor lives in both the partition and the guard, each enforcing independently).
- **Track coverage**: yes (D1 Risks/Caveats and D2 Risks/Caveats both trace this exact case).
