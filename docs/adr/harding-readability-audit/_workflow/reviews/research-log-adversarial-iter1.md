# Research-log adversarial gate — harding-readability-audit (iter1)

<!-- §2.5 review file. Manifest first, then Evidence base, then Findings. -->

## Manifest

- **Verdict**: NEEDS REVISION
- **Findings**: 7
- **Scope**: research-log (Phase 0→1 gate)
- **matched_categories**: Workflow machinery

| id | sev | target | anchor | loc | cert | basis |
|---|---|---|---|---|---|---|
| A1 | blocker | D2 (agent-side whole-doc guard) | `### A1` | `readability-auditor.md:80-81`, `edit-design/SKILL.md:677-682` | Challenge D2 | The cold agent receives only `target`/`target_path`/`range`; it cannot compute "spans near the whole document" without the doc's total length, which S1 forbids it from reading. |
| A2 | blocker | D6 (file relocation + resume glob) | `### A2` | `create-plan/SKILL.md:764-898`, `edit-design/SKILL.md:821-830` | Challenge D6 | The track-path Step-4b loop has no resume round-count glob to update; D6's touched-file set names only `edit-design` Step 6's glob, so the track-path loop is left with no documented mid-loop resume after the move. |
| A3 | should-fix | D4 (anchor-hash with Core Concepts) | `### A3` | `edit-design/SKILL.md:222-223`, `readability-auditor.md:64-66` | Assumption D4 | `## Core Concepts` is seeded only conditionally (Parts or ≥3 new terms); D4 hashes "section + the two standing anchors" as if both always exist, so the hash input is undefined when Core Concepts is absent. |
| A4 | should-fix | D1 (prose-only partition, no verifiable count) | `### A4` | `create-plan/SKILL.md:813-821`, `readability-feedback/SKILL.md:32-33` | Challenge D1 | Issue point-3 asks for a "verifiable spawn count"; D1 rejects the helper script for lightness but states no verification mechanism, so the count remains unenforced — the same gap the issue opened against the design path. |
| A5 | should-fix | D4 (calibrated-hold convergence-forcing) | `### A5` | research-log D4 bullet 6, `edit-design/SKILL.md:549-583` | Assumption D4 | "Comprehension gate + iteration-budget escalation remain the backstops" — but the comprehension gate runs no prose axis (D9/S4), so an orchestrator accepting prose should-fixes as holds to force dual-clean faces no prose backstop until the user veto at D15. |
| A6 | should-fix | OQ-meta (tier + §1.7 routing unresolved) | `### A6` | research-log Open Questions (meta) | Open-question | The tier (full vs lite) and §1.7(k)-opt-out-vs-staging routing is still open; it is load-bearing (decides whether a `design.md` derives at all) and must resolve into the Decision Log before any artifact derives. |
| A7 | suggestion | D5 (track-path anchor set on `lite`/`minimal`) | `### A7` | `create-plan/SKILL.md:823-825`, `readability-auditor.md:67` | Assumption D5 | D5's `target=tracks` anchor set is "plan Component Map + each track's Purpose"; on `lite`/`minimal` there is no `design.md`, and the Component Map's presence as a stable cross-round anchor for the settled-state hash is unstated. |

## Evidence base

#### Challenge: Decision D2 — agent-side whole-doc-refusal guard
- **Chosen approach**: `readability-auditor.md` turns "range-sliced fan-out" into a hard requirement **and** adds a guard — the agent flags a slice spanning (near) the whole document above the floor as a wiring error rather than auditing it whole, "making a whole-doc collapse detectable by the agent."
- **Best rejected alternative**: The helper-script slicer (D1's rejected alternative), which emits the slice ranges deterministically and gives the orchestrator a verifiable spawn count — the script is where a whole-doc collapse is actually detectable, because the script knows the document length and the floor.
- **Counterargument trace**:
  1. The cold auditor's entire input surface is its params file: `target`, `target_path`, `range` (`readability-auditor.md:78-82`). It is handed no total-line-count and is forbidden by S1 (`readability-auditor.md:27-29`) from reading anything beyond its slice + the two anchors + `house-style.md`.
  2. To "flag a slice spanning near the whole document above the floor," the agent must know both (a) the document's total length and (b) the floor threshold (~300 lines). It has neither. A slice `range` of `1-340` tells the agent its slice is 340 lines; it cannot tell whether that is the whole 340-line doc (a collapse) or the first 340 of an 800-line doc (a legitimate first slice).
  3. The rejected script would compute `total_lines` and `num_slices` at partition time and assert `num_slices >= 2` when `total_lines > 300` — the detection D2 wants, located where the data exists.
- **Codebase evidence**: `readability-auditor.md:80-81` (params = `target_path` + `range` only, no length); `readability-auditor.md:27-29` (S1: only the slice + anchors + house-style are readable). The `/readability-feedback` standalone path computes the partition in the *orchestrator* (`readability-feedback/SKILL.md:32-33`: "Capture … the line count" then "Split … Cap at ~6"), confirming length-awareness lives orchestrator-side, never in the audit agent.
- **Survival test**: NO. As stated, the guard is uncomputable by the agent that is supposed to run it. D2 recovers "most of the issue's point-3 'detectable, not silent' goal" only if the detection actually fires; it cannot fire without the doc length. The guard must either (a) move to the orchestrator (which has the length, per D1's partition step), or (b) D2 must pass the agent the total document length and the floor so its slice-vs-whole comparison is decidable — and even then a legitimate single-slice short doc (D1: under ~300 lines gets one slice) is indistinguishable from a collapse without the length+floor pair.

#### Challenge: Decision D6 — relocate Phase-1 authoring-loop files to `_workflow/reviews/`
- **Chosen approach**: Move every params/output file from `_workflow/plan/` to `_workflow/reviews/`; "a uniform rule … lets the Step-6 resume round-count glob read one location." Scope (research-log D6 closing paragraph) names `edit-design` Step 4 + Step 6 (resume glob), `create-plan` Step 4b item 9 (params), and `conventions-execution.md` §2.5.
- **Best rejected alternative**: Keep the relocation but also add (or first add) a resume round-count glob to the `create-plan` Step 4b loop, so both loops have the resume mechanism the move is justified by.
- **Counterargument trace**:
  1. `edit-design` Step 6 has an explicit "Resume after a mid-loop context-clear" block (`edit-design/SKILL.md:821-830`): it re-derives the round count from "the latest per-round params files written under `_workflow/plan/`." D6's stated payoff is "lets the Step-6 resume round-count glob read one location" — so the move's value is glued to this glob.
  2. The `create-plan` Step 4b loop (`create-plan/SKILL.md:764-898`) runs the **same** dual-clean loop, writes the same one-params-file-per-spawn under `_workflow/plan/` (line 782), and is equally exposed to a mid-loop `/clear`. But it has **no** resume round-count glob at all — a grep of item 9 and its neighbours for `resume|round-count|re-derive|context-clear|params file` returns nothing on the resume axis.
  3. So after D6 moves the track-path params to `_workflow/reviews/`, there is no track-path glob to update (D6's scope correctly omits one), but the track-path loop is left with the *same* resume gap it had before — only now its params live somewhere the orchestrator must be told to look, with no documented mechanism telling it to.
- **Codebase evidence**: `edit-design/SKILL.md:821-830` (design-path resume glob exists, reads `_workflow/plan/`); `create-plan/SKILL.md:782` (track-path writes params under `_workflow/plan/`, one per spawn) with no matching resume block anywhere in item 9. The asymmetry is real: one loop is resumable, the sibling loop is not, and D6 reasons as if moving the files serves a glob that exists on only one of them.
- **Survival test**: WEAK. The relocation decision itself survives (a uniform `reviews/` home is defensible and §2.5 already hosts the gate's files). But its load-bearing rationale ("lets the Step-6 resume glob read one location") is half-true: it holds for the design path and is vacuous for the track path, which has no glob. The log should either (a) note that the track-path loop has no resume glob today and that D6 does not add one (scoping the rationale honestly), or (b) extend the move with a track-path resume glob so the symmetry the prose claims is real.

#### Assumption test: D4 hashes "section text plus the two standing anchors (Overview + Core Concepts)"
- **Claim**: A settled-state section hash is computed over "its own text **plus** the two standing anchors (`## Overview` / `## Core Concepts`), because the auditor reads those too."
- **Stress scenario**: A small design (the D1/seed common case): under ~5 sections, no `# Part` headings. The author seeds Overview, Class Design, Workflow — but **not** Core Concepts, which is seeded only "when the doc will have Parts or ≥3 new domain terms" (`edit-design/SKILL.md:222-223`). The auditor's own anchor list is `## Overview` and `## Core Concepts` (`readability-auditor.md:66`), but `readability-auditor.md:64` frames Core Concepts as a resolvable-on-demand anchor, not a guaranteed one.
- **Code evidence**: `edit-design/SKILL.md:222-223` (Core Concepts is conditional on Parts/≥3 terms); `readability-auditor.md:64-66` (the auditor reads Overview + Core Concepts as the `target=design` standing anchors). When Core Concepts is absent, D4's hash input "section + the two anchors" includes a section that does not exist.
- **Verdict**: FRAGILE. The hash is still computable (hash over Overview alone when Core Concepts is absent), but D4 states the input as a fixed pair, so an artifact deriving from it will either (a) crash on the missing anchor or (b) silently hash a different input set than D4's prose claims, weakening the "anchor edit re-opens dependent sections" guarantee. D4 should state the anchor set as "Overview, plus Core Concepts when present" to match the conditional seed.

#### Challenge: Decision D1 — pure-prose partition with no verification mechanism
- **Chosen approach**: Port `/readability-feedback`'s partition as a prose orchestrator obligation, no helper script. The issue (research-log line 18-19) asks for "a verifiable spawn count."
- **Best rejected alternative**: The helper script D1 itself lists — it gives "free spawn-count verifiability" (research-log D1 alternatives).
- **Counterargument trace**:
  1. D1 states the partition as a prose rule the orchestrator follows and states a hard minimum-slice floor (≥2 slices above ~300 lines), but names **no** check that the orchestrator actually produced N slices. The track path's own prose (`create-plan/SKILL.md:813-821`) likewise states "one spawn per `plan/track-N.md`" with no count assertion.
  2. The issue's point-3 explicitly bundles "a verifiable spawn count" with the slicing rule — that is the part the prose-only approach drops. A prose obligation an orchestrator can silently under-honor is exactly the "unenforced" failure mode YTDB-1158 opened against the design path.
  3. The rejected script closes this by construction: it emits the ranges, so the spawn count equals the range count it printed.
- **Codebase evidence**: `readability-feedback/SKILL.md:32-33` (the proven rule is itself orchestrator prose, no count check) — so D1's "proven" claim is accurate for the *partition* but inherits the standalone tool's lack of a count check, which is acceptable for an interactive standalone tool but is the exact unenforcement the in-loop issue is about.
- **Survival test**: WEAK. The prose obligation survives as the partition mechanism (it is proven and matches the track-path style), but the "verifiable spawn count" sub-requirement of the issue is silently dropped. D1's rationale should either (a) state how the count is verified without a script (e.g., the orchestrator asserts `slices_spawned == ceil(total/200)` from values it already holds — a prose check, not new machinery), or (b) explicitly waive the verifiable-count clause as out-of-scope with the user's sign-off, since the issue named it.

#### Assumption test: D4 — calibrated holds cannot be abused to force convergence
- **Claim**: "The comprehension gate (separate, after dual-clean) and the iteration-budget escalation remain the backstops against an orchestrator over-accepting holds to force convergence."
- **Stress scenario**: An orchestrator near its `iteration_budget` (default 3) has one stubborn prose should-fix re-flagged each round. It records it as a "calibrated hold" with a one-line reason, the filter drops it, dual-clean is declared, and the loop exits.
- **Code evidence**: The comprehension gate runs **no** prose AI-tell axis — `edit-design/SKILL.md:470-472` ("The de-warmed comprehension gate runs that axis nowhere (D9)") and `readability-auditor.md:58` ("The comprehension reviewer runs it nowhere"). So a *prose* hold has no downstream reviewer: the comprehension gate (structural/comprehension only) will not catch it. The only remaining backstop for a prose hold is the user veto at the D15 presentation.
- **Verdict**: FRAGILE. D4's two named backstops are real for *decision-shaped* holds (which re-open the S3 gate) but do not cover *prose* holds, because the only prose owner is the auditor the hold suppresses. The claim "the comprehension gate … remain[s] the backstop" overstates the comprehension gate's reach for the prose axis. D4 should narrow the backstop claim to "the user veto at D15 is the backstop for prose holds; the comprehension gate backstops only the comprehension/structural and decision-shaped axes," so a reader does not over-trust the gate.

#### Open-question challenge: the (meta) tier + §1.7 routing is unresolved and load-bearing
- **Claim under test**: The research log's Open Questions still carry "(meta) Tier and §1.7 routing — Concern B's stateful mechanism likely warrants a `design.md` (→ `full` tier) … §1.7(k) prose-rule opt-out vs full staging is a live choice. To settle at Step 4."
- **Why it is load-bearing**: The tier decides whether a `design.md` is authored at all. If the gate clears and the branch goes `lite`/`minimal`, no `design.md` derives and the D1/D2 design-path slicing rules land only in the track-path and the live files; if `full`, a `design.md` derives and the design-path slicing rule must be exercised on this very branch's own design. The §1.7(k)-opt-out-vs-staging choice decides whether the prose-rule self-application gate fires. Both are decisions, not questions — deriving any Phase-1 artifact over them is the gap the gate exists to catch.
- **Verdict**: must resolve into the Decision Log (or be user-waived as deferred) before the gate clears. Per the research-log-scoped review rules, an unresolved load-bearing open question is at least a should-fix.

#### Assumption test: D5 — the track-path standing anchors exist as stable cross-round keys
- **Claim**: For `target=tracks`, the settled-state standing-anchor set is "the plan Component Map + each track's `## Purpose / Big Picture`."
- **Stress scenario**: A `lite`/`minimal` branch where the track path is the only authoring loop and there is no `design.md`. The Component Map lives in `implementation-plan.md` (`readability-auditor.md:67`: "the plan Component Map"); on `minimal` the plan is a thinned stub. D4 hashes the section text plus the standing anchors, and the convergence relies on the anchor being stable round-to-round.
- **Code evidence**: `create-plan/SKILL.md:823-825` (the track-path anchors are the Component Map + each track's Purpose); `readability-auditor.md:67` (same). The log does not state whether the Component Map is byte-stable across rounds while tracks are being authored, nor what the anchor set is when the Component Map itself is still being written in the same Step-4b session.
- **Verdict**: HOLDS, weakly. The anchor set is named and the mechanism is parameterized correctly (D5 is explicit that the two parameters differ by path). The residual is a documentation gap, not a defect: D5 should note that on the track path the Component Map must be settled before the dual-clean loop runs (item 8 settles it before item 9, per `create-plan/SKILL.md:769-776`), so the anchor is stable for the loop's duration. Suggestion only.

## Findings

### A1 [blocker]
**Certificate**: Challenge D2 — agent-side whole-doc-refusal guard
**Target**: Decision D2
**Challenge**: The cold auditor cannot run the whole-doc-refusal guard D2 assigns it. Its entire input is `target`/`target_path`/`range` (`readability-auditor.md:80-81`), and S1 (`readability-auditor.md:27-29`) forbids it from reading beyond its slice + anchors. To flag "a slice spanning near the whole document above the floor" it would need the document's total length and the ~300-line floor — it has neither. A slice `range=1-340` is indistinguishable to the agent between "the whole 340-line doc collapsed into one slice" and "the first 340 lines of an 800-line doc," which is exactly the case D1 says is legitimate for a short doc (one slice under ~300 lines).
**Evidence**: `readability-auditor.md:80-81` (params carry no length); `readability-auditor.md:27-29` (S1 read restriction); `/readability-feedback` computes the partition orchestrator-side because that is where the line count lives (`readability-feedback/SKILL.md:32-33`).
**Proposed fix**: Move the whole-doc-collapse detection to the orchestrator (D1's partition step already computes `total_lines` and `num_slices`, so it can assert `num_slices >= 2` when `total_lines > ~300`), OR amend D2 to pass the agent both the document's total line count and the floor threshold so its slice-vs-whole comparison is decidable. Either way, state in D2 that a legitimate single-slice short doc is not a collapse, so the guard does not false-positive on small designs.

### A2 [blocker]
**Certificate**: Challenge D6 — relocate Phase-1 authoring-loop files
**Target**: Decision D6
**Challenge**: D6's load-bearing rationale — "a uniform rule … lets the Step-6 resume round-count glob read one location" — holds only for the design path. The `create-plan` Step 4b loop runs the same dual-clean loop and writes the same one-params-file-per-spawn (`create-plan/SKILL.md:782`), but has **no** resume round-count glob anywhere in item 9 (confirmed: a resume-axis grep over item 9 returns nothing). So moving the track-path params to `_workflow/reviews/` serves a glob that does not exist on that path, and leaves the track-path loop with the same un-documented mid-loop resume gap it had before — now pointing at a moved location with no mechanism told to read it.
**Evidence**: `edit-design/SKILL.md:821-830` (design-path resume glob exists, reads `_workflow/plan/`); `create-plan/SKILL.md:764-898` (track-path loop, same structure, no resume block); D6's scope paragraph names only `edit-design` Step 6's glob.
**Proposed fix**: Either (a) extend D6 to add a resume round-count glob to the `create-plan` Step 4b loop (reading the moved `_workflow/reviews/` location), making the symmetry the rationale claims real, or (b) narrow D6's rationale to state explicitly that only the design-path Step-6 glob benefits and the track-path loop has no resume glob today (so the move's track-path value is solely `plan/`-de-pollution, not glob-simplification).

### A3 [should-fix]
**Certificate**: Assumption D4 — anchor-hash over the two standing anchors
**Target**: Decision D4
**Challenge**: D4 hashes "the section's own text plus the two standing anchors (Overview / Core Concepts)," but `## Core Concepts` is seeded only conditionally — "when the doc will have Parts or ≥3 new domain terms" (`edit-design/SKILL.md:222-223`). On the common small-design case there is no Core Concepts section, so D4's stated hash input references a section that does not exist; an artifact deriving the hash literally would either error or silently hash a different set than D4 claims, weakening the "an anchor edit re-opens dependent sections" guarantee.
**Evidence**: `edit-design/SKILL.md:222-223` (Core Concepts conditional); `readability-auditor.md:64-66` (auditor reads Overview + Core Concepts as `target=design` anchors).
**Proposed fix**: Restate D4's anchor set as "Overview, plus Core Concepts when present" to match the conditional seed, and confirm the auditor's standing-anchor read (`readability-auditor.md:64`) already tolerates an absent Core Concepts (it does — it resolves anchors on demand) so the two stay consistent.

### A4 [should-fix]
**Certificate**: Challenge D1 — prose partition with no verifiable count
**Target**: Decision D1
**Challenge**: The issue's Concern A point-3 asks for "a verifiable spawn count." D1 rejects the helper script for lightness and states the partition + a hard minimum-slice floor, but names no mechanism that verifies the orchestrator actually produced the slices — so the count stays unenforced, the same "unenforced" failure mode YTDB-1158 opened against the design path. A prose obligation an orchestrator can silently under-honor is precisely what the issue is closing.
**Evidence**: research-log line 18-19 (issue asks for verifiable count); D1 alternatives (the rejected script offered "free spawn-count verifiability"); `readability-feedback/SKILL.md:32-33` (the proven rule is itself count-check-free prose, fine for a standalone tool, weaker for an enforced in-loop gate).
**Proposed fix**: Add to D1 a prose-level verification the orchestrator runs from values it already holds — e.g., after partitioning, assert `slices_spawned == expected_slice_count` derived from the line count and window size, surfacing a mismatch as a wiring error — so "verifiable" is satisfied without new machinery. Alternatively, record an explicit user waiver of the verifiable-count clause, since the issue named it.

### A5 [should-fix]
**Certificate**: Assumption D4 — calibrated holds cannot force convergence
**Target**: Decision D4
**Challenge**: D4 names "the comprehension gate (separate, after dual-clean) and the iteration-budget escalation" as the backstops against over-accepting holds. For a *prose* hold this is wrong: the comprehension gate runs no prose AI-tell axis (`edit-design/SKILL.md:470-472`, `readability-auditor.md:58`), and the hold suppresses the only prose owner (the auditor). So a prose should-fix accepted as a calibrated hold to force dual-clean has no reviewer backstop until the user veto at D15.
**Evidence**: `edit-design/SKILL.md:470-472` (comprehension gate runs the prose axis nowhere, D9); `readability-auditor.md:58` (one-owner-per-surface, S4 — the auditor is the sole prose owner).
**Proposed fix**: Narrow D4's backstop claim: the comprehension gate and budget backstop the comprehension/structural and decision-shaped axes (decision-shaped holds re-open S3); for *prose* holds the backstop is the user veto at the D15 presentation. State this so a reader does not over-trust the comprehension gate to catch an over-accepted prose hold.

### A6 [should-fix]
**Certificate**: Open-question challenge — (meta) tier + §1.7 routing
**Target**: Open Question (meta)
**Challenge**: The tier (full vs lite/minimal) and the §1.7(k)-opt-out-vs-full-staging routing is still an open question "to settle at Step 4." It is load-bearing: the tier decides whether a `design.md` derives at all (and therefore whether the design-path slicing rule D1/D2 add is exercised on this branch's own design), and the §1.7 choice decides whether the prose-rule self-application gate fires. Deriving any Phase-1 artifact while this is unresolved is the gap the gate exists to catch.
**Evidence**: research-log Open Questions, the (meta) bullet ("likely warrants a `design.md` (→ `full` tier)"; "§1.7(k) … vs full staging is a live choice. To settle at Step 4").
**Proposed fix**: Resolve the tier and §1.7 routing into a `## Decision Log` entry (with the chosen tier and staging mode and the rejected alternative) before the gate clears, or record the user's explicit deferral of it as out-of-scope for this gate iteration.

### A7 [suggestion]
**Certificate**: Assumption D5 — track-path anchors are stable cross-round keys
**Target**: Decision D5
**Challenge**: D5's `target=tracks` standing anchors are "the plan Component Map + each track's `## Purpose / Big Picture`." On `lite`/`minimal` the plan is thinned and the Component Map and tracks are authored in the same Step-4b session as the loop runs. The settled-state hash (D4) folds the anchors in, and convergence relies on the anchor being byte-stable across rounds — the log does not state that the Component Map is settled before the loop starts.
**Evidence**: `create-plan/SKILL.md:823-825` and `readability-auditor.md:67` (the track-path anchor set); `create-plan/SKILL.md:769-776` (items 1-8 settle the track shape before item 9's loop runs).
**Proposed fix**: Note in D5 that on the track path the Component Map and track skeletons are settled in items 1-8 before item 9's dual-clean loop, so the standing anchors are stable for the loop's duration; this closes a small documentation gap, not a defect. Suggestion only.
