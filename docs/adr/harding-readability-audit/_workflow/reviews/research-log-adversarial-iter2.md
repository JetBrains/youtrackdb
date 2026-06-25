# Research-log adversarial gate — harding-readability-audit (iter2)

<!-- §2.5 review file. Manifest first, then Evidence base, then Findings. -->
<!-- Verdict-producer variant (iteration 2): each prior finding A1–A7 gets a
     VERIFIED / STILL OPEN / REJECTED verdict against the revised log (D1–D7)
     and the current on-disk file state. New findings, if any, follow. -->

## Manifest

- **Verdict**: PASS
- **Findings**: 7
- **Scope**: research-log (Phase 0→1 gate)
- **matched_categories**: Workflow machinery

| id | sev | target | anchor | verdict | loc | cert | basis |
|---|---|---|---|---|---|---|---|
| A1 | blocker | D2 (agent-side whole-doc guard) | `### A1` | VERIFIED | `edit-design/SKILL.md:513-527`, `readability-auditor.md:27-29,75-83` | Challenge D2 | D2 makes the orchestrator partition (D1) primary and the agent guard secondary; params gain round-constant `slice_count`/`total_lines` (slicing metadata, S1-safe), guard fires only `slice_count==1 AND total_lines>~300`. Computable; short-doc excluded. |
| A2 | blocker | D6 (file relocation + resume glob) | `### A2` | VERIFIED | `create-plan/SKILL.md:782,844-870`, `edit-design/SKILL.md:821-830` | Challenge D6 | D6 now scopes the resume-glob payoff to the design path only, documents the track-path no-resume-glob gap (new Surprises bullet), and frames the track-path move as `plan/` de-pollution. Matches the on-disk asymmetry. |
| A3 | should-fix | D4 (anchor-hash with Core Concepts) | `### A3` | VERIFIED | `edit-design/SKILL.md:222-223`, `readability-auditor.md:64-66` | Assumption D4 | D4 restates the design-path anchor set as "Overview (always present) plus Core Concepts when present," citing the conditional seed and resolve-on-demand. |
| A4 | should-fix | D1 (verifiable spawn count) | `### A4` | VERIFIED | `create-plan/SKILL.md:812-821`, `readability-feedback/SKILL.md:32-33` | Challenge D1 | D1 adds the prose self-check `slices_spawned == expected_slice_count` (derived from line count + ~200 window + ~6 cap), satisfying the issue's stated-obligation acceptance of a verifiable count. |
| A5 | should-fix | D4 (calibrated-hold backstop) | `### A5` | VERIFIED | `edit-design/SKILL.md:640,684-696`, `readability-auditor.md:58` | Assumption D4 | D4 narrows the prose-hold backstop to the user veto at D15 and states the comprehension gate is NOT a prose-hold backstop (D9/S4); gate+budget back only comprehension/structural and decision-shaped axes. |
| A6 | should-fix | OQ-meta → D7 (tier + §1.7 routing) | `### A6` | VERIFIED | `conventions.md:1362-1372` (§1.7(k) criteria) | Open-question | D7 resolves it into the Decision Log: tier=`full`, §1.7=full staging; criterion-2 ("step-implementation orchestration loop"=executable procedure) disqualifies the opt-out. Open Questions carries a RESOLVED marker. |
| A7 | suggestion | D5 (track-path anchor stability) | `### A7` | VERIFIED | `create-plan/SKILL.md:770-776` | Assumption D5 | D5 notes items 1-8 settle the Component Map + track skeletons before item 9's loop, so the standing anchors are byte-stable for the loop's duration. |

No new findings. Gate clears: 0 blocker, 0 unaddressed should-fix.

## Evidence base

<!-- One verdict certificate per prior finding. Each cites the revised log
     decision and the current on-disk state it was checked against. -->

#### Verdict A1 — D2 makes the whole-doc guard computable

- **Prior finding**: A1 (blocker) — the cold auditor cannot run the whole-doc-refusal guard because its params (`target`/`target_path`/`range`) carry no document length and S1 bars it from reading beyond its slice.
- **Revision under test**: D2 (research-log lines 81-104). Reframes the orchestrator partition (D1) as the **primary** enforcement (spawns exactly the computed count, ≥2 above the floor) and the agent guard as a **secondary** detector. Adds two params fields — `slice_count` and `total_lines`, both constant across a round's fan-out. The agent flags a wiring error only when `slice_count == 1 AND total_lines > ~300`; a legitimate single-slice short doc under the floor does not fire it.
- **On-disk check**:
  1. `edit-design/SKILL.md:513-527` confirms the params-file contract: per-agent inputs go in a params file, the spawn *prompt body* names only that file's path and stays byte-identical across the fan-out. Adding two scalar fields to each per-spawn params file therefore does not vary the prompt body, so the shared-prompt cache the fan-out relies on is intact.
  2. `readability-auditor.md:27-29` (S1) bars reading the *research log* and anything beyond the slice + anchors + house-style. Two constant scalars (`slice_count`, `total_lines`) are slicing metadata, not document content and not a conclusion about the prose, so they do not breach S1's cold-read guarantee. `readability-auditor.md:75-83` lists the current params (`target`/`target_path`/`range`); the touched-file set (research-log lines 406-408) scopes the agent edit as "'range-sliced' becomes a hard rule + whole-doc-refusal guard," which covers teaching the agent to read and act on the two new fields.
  3. The collapse case is now decidable: D1 forces `slice_count >= 2` for any doc above the floor, so `slice_count == 1 AND total_lines > ~300` fires precisely on the degenerate whole-doc collapse and never on a legitimate short single-slice doc.
- **Verdict**: **VERIFIED.** The revision relocates the load-bearing detection to the orchestrator (where the length lives) exactly as A1's proposed fix asked, and makes the residual agent guard computable by passing the two scalars S1-safely. The short-doc false-positive A1 warned about is explicitly excluded.

#### Verdict A2 — D6 scopes the resume-glob rationale honestly

- **Prior finding**: A2 (blocker) — D6's "lets the Step-6 resume glob read one location" rationale holds only for the design path; the `create-plan` Step 4b loop has no resume round-count glob, so the move serves a non-existent track-path glob and leaves the track-path resume gap.
- **Revision under test**: D6 (research-log lines 213-251) now states the primary value on both paths is `plan/` de-pollution; the "resume glob reads one location" benefit applies **only to the design path**; the `create-plan` Step 4b loop has **no** resume round-count glob (a pre-existing gap), so the move's track-path value is solely de-pollution. A new Surprises bullet (research-log lines 341-349) records the track-path no-resume-glob discovery. D6's rejected-alternatives note adding a track-path resume glob is out of scope.
- **On-disk check**: `edit-design/SKILL.md:821-830` confirms the design-path resume block re-derives the round from "the latest per-round params files written under `_workflow/plan/`" — the glob D6 moves. `create-plan/SKILL.md:782` confirms the Step 4b loop writes per-spawn params under `_workflow/plan/`; a sweep of item 9 (lines 770-870, including the per-round pair at 804-843) shows **no** resume / round-count / re-derive / context-clear block — the asymmetry A2 identified is real and now documented. The skill-level resume references (`create-plan/SKILL.md:113,532`) are the ledger-based whole-skill resume, not a mid-loop round-count glob, so they do not contradict the gap.
- **Verdict**: **VERIFIED.** This is A2's proposed fix option (b) applied verbatim: narrow the rationale, document the track-path has no glob today, and state the move's track-path value is de-pollution only.

#### Verdict A3 — D4 conditions the anchor on Core Concepts presence

- **Prior finding**: A3 (should-fix) — D4 hashes "section + the two standing anchors (Overview / Core Concepts)" as if both always exist, but Core Concepts is seeded only conditionally.
- **Revision under test**: D4 (research-log lines 148-151) now reads "`## Overview` (always present) plus `## Core Concepts` **when present** (Core Concepts is seeded only conditionally — when the doc has Parts or ≥3 new domain terms — per gate A3, and the auditor already resolves anchors on demand, so it tolerates an absent Core Concepts)."
- **On-disk check**: `edit-design/SKILL.md:222-223` confirms the conditional seed ("Core Concepts (when the doc will have Parts or ≥3 new domain terms)"). `readability-auditor.md:64-66` confirms the auditor reads Overview + Core Concepts as the `target=design` anchors and frames them as resolve-on-demand (line 64), tolerating an absent Core Concepts. D4's restated hash input now matches both.
- **Verdict**: **VERIFIED.** A3's proposed fix ("Overview, plus Core Concepts when present") is applied and the auditor's resolve-on-demand tolerance is confirmed consistent.

#### Verdict A4 — D1 adds a prose-level verifiable spawn count

- **Prior finding**: A4 (should-fix) — D1 rejects the helper script but states no verification that the orchestrator actually produced N slices, dropping the issue's "verifiable spawn count" clause.
- **Revision under test**: D1 (research-log lines 62-70) adds a "Verifiable count without a script (resolves gate A4)" paragraph: the issue's point-3 explicitly accepts "a stated orchestrator obligation"; the orchestrator computes the expected slice count deterministically from values it already holds (total line count, the ~200-line window, the ~6 cap), spawns exactly that many, and self-checks `slices_spawned == expected_slice_count`, surfacing a mismatch as a wiring error. It pairs with the D2 agent guard for the collapse case.
- **On-disk check**: `readability-feedback/SKILL.md:32-33` confirms the proven partition is itself orchestrator prose (capture the line count, split, cap at ~6) — A4's premise that the standalone tool carries no count check is accurate, and D1 now closes that gap for the in-loop path with a prose check, not new machinery. The cap is correctly included in D1's input list (so `expected_slice_count` is `min(ceil(total/~200), ~6)`, not bare `ceil`), matching the cap-binding behavior D1 describes. `create-plan/SKILL.md:812-821` shows the track path's deterministic per-file partition, against which the design-path count check is symmetric.
- **Verdict**: **VERIFIED.** This is A4's proposed fix option (a): a prose-level self-check derived from values the orchestrator already holds, no script.

#### Verdict A5 — D4 narrows the prose-hold backstop to the user veto

- **Prior finding**: A5 (should-fix) — D4 named the comprehension gate + iteration-budget as the backstops against over-accepted holds, but the comprehension gate runs no prose AI-tell axis, so a prose hold has no reviewer backstop until the user veto.
- **Revision under test**: D4 (research-log lines 166-172) now states "The backstop against over-accepting **prose** holds … is the **user veto at the D15 presentation** … the de-warmed comprehension gate runs no prose AI-tell axis (D9/S4) and the only prose owner is the very auditor the hold suppresses, so the comprehension gate is **not** a prose-hold backstop. The comprehension gate and the iteration-budget escalation remain backstops only for the comprehension/structural and decision-shaped axes (a decision-shaped hold re-opens the S3 gate)."
- **On-disk check**: `edit-design/SKILL.md:640` confirms the comprehension gate is de-warmed and "runs it nowhere (D9/S4)" for the prose axis. `readability-auditor.md:58` confirms the one-owner-per-surface invariant (S4) — the auditor owns the prose AI-tell axis, the comprehension reviewer runs it nowhere — so the auditor is the sole prose owner and a hold that suppresses it leaves no prose reviewer. D4's narrowed claim matches.
- **Verdict**: **VERIFIED.** A5's proposed fix is applied: the prose-hold backstop is correctly attributed to the D15 user veto, and the comprehension gate's reach is confined to the non-prose axes.

#### Verdict A6 — D7 resolves the tier + §1.7 routing into the Decision Log

- **Prior finding**: A6 (should-fix) — the tier (full vs lite) and §1.7(k)-opt-out-vs-staging routing was a load-bearing open question that had to resolve into the Decision Log before the gate clears.
- **Revision under test**: D7 (research-log lines 253-282) resolves it: **Tier = `full`** (Concern B is a genuine mechanism design warranting a `design.md`); matched HIGH-risk category = `Workflow machinery`; **§1.7 routing = full staging** (`s17` = workflow-modifying), **not** the §1.7(k) opt-out, because §1.7(k) criterion 2 disqualifies a plan whose edited files a running phase reads as executable procedure. The Open Questions section (research-log lines 385-390) now carries a RESOLVED marker mapping all four questions to D1–D7.
- **On-disk check**: `conventions.md:1362-1372` confirms the opt-out criteria. Criterion 2 names "the step-implementation orchestration loop" as executable procedure that "stay[s] staged even on an otherwise-qualifying plan." D7's core edits are exactly orchestration-loop edits (`edit-design` Step 4/6, `create-plan` Step 4b), so criterion 2 disqualifies the opt-out as D7 reasons. Independently, criterion 1 ("changes no `_workflow/**` artifact … resume-state field") is also strained: D6 moves the resume glob's read location and generalizes the §2.5 review-file home, so staging is the conservative correct call on either criterion. D7's verdict holds.
- **Verdict**: **VERIFIED.** The load-bearing open question is now a Decision Log entry (D7) with the chosen tier, the chosen staging mode, and the rejected alternative, and the gate-clearing precondition A6 demanded is met.

#### Verdict A7 — D5 documents track-path anchor stability

- **Prior finding**: A7 (suggestion) — D5 did not state that the track-path standing anchors (Component Map + each track's Purpose) are byte-stable across rounds while the loop iterates.
- **Revision under test**: D5 (research-log lines 198-203) adds "**Track-path anchor stability (gate A7):** on the track path, `create-plan` Step 4b items 1–8 settle the plan Component Map and the track skeletons *before* item 9's dual-clean loop runs, so the standing anchors the settled-state hash folds in are byte-stable for the loop's duration; the cross-reference states this so a `lite`/`minimal` reader does not assume the Component Map is still moving while the loop iterates."
- **On-disk check**: `create-plan/SKILL.md:770-776` confirms items 1-8 settle the decisions, track boundaries, Decision Records, and section homes, and item 9 "hands that settled shape to the `design-author` spawn … Run it after items 1-8 produce the track shape." D5's added note matches the on-disk ordering.
- **Verdict**: **VERIFIED.** A7's documentation-gap suggestion is closed.

## Findings

<!-- All seven prior findings are VERIFIED; no new finding was introduced by
     the D1–D7 revisions. Each finding below records the verdict and the
     ground it was checked against. -->

### A1 [blocker]
**Certificate**: Verdict A1 — D2 makes the whole-doc guard computable
**Target**: Decision D2
**Verdict**: VERIFIED.
**Resolution**: D2 makes the orchestrator partition (D1) the primary enforcement and the agent guard a secondary detector, passing the agent two round-constant scalars (`slice_count`, `total_lines`) as slicing metadata. The guard fires only on `slice_count == 1 AND total_lines > ~300`, which is now computable and excludes the legitimate short single-slice doc. The two scalars are metadata, not document content or a primed conclusion, so they do not breach S1 (`readability-auditor.md:27-29`); they ride in the per-spawn params file, so the byte-identical prompt body and its shared cache (`edit-design/SKILL.md:513-527`) are intact. This is A1's proposed fix (move detection to the orchestrator; pass the agent the length + floor for a decidable secondary check).

### A2 [blocker]
**Certificate**: Verdict A2 — D6 scopes the resume-glob rationale honestly
**Target**: Decision D6
**Verdict**: VERIFIED.
**Resolution**: D6 now states the resume-glob "one location" payoff applies only to the design path, documents that the `create-plan` Step 4b loop has no resume round-count glob (a pre-existing gap, recorded in a new Surprises bullet), and frames the track-path move as `plan/` de-pollution only. Confirmed against the on-disk asymmetry: `edit-design/SKILL.md:821-830` has the design-path resume glob reading `_workflow/plan/`; `create-plan/SKILL.md:782` writes params there but item 9 (lines 770-870) carries no resume block. This is A2's proposed fix option (b).

### A3 [should-fix]
**Certificate**: Verdict A3 — D4 conditions the anchor on Core Concepts presence
**Target**: Decision D4
**Verdict**: VERIFIED.
**Resolution**: D4 restates the design-path anchor set as "Overview (always present) plus Core Concepts when present," citing the conditional seed (`edit-design/SKILL.md:222-223`) and the auditor's resolve-on-demand tolerance (`readability-auditor.md:64-66`). The hash input now matches the actual seed behavior — A3's proposed fix.

### A4 [should-fix]
**Certificate**: Verdict A4 — D1 adds a prose-level verifiable spawn count
**Target**: Decision D1
**Verdict**: VERIFIED.
**Resolution**: D1 adds a self-check `slices_spawned == expected_slice_count`, where `expected_slice_count` is derived from the total line count, the ~200-line window, and the ~6 cap (cap correctly included, so it is `min(ceil(total/~200), ~6)`), surfacing a mismatch as a wiring error. The issue's point-3 explicitly accepts a stated orchestrator obligation in lieu of a script, so this satisfies the verifiable-count clause without new machinery — A4's proposed fix option (a). It pairs with the D2 agent guard for the collapse case.

### A5 [should-fix]
**Certificate**: Verdict A5 — D4 narrows the prose-hold backstop to the user veto
**Target**: Decision D4
**Verdict**: VERIFIED.
**Resolution**: D4 now attributes the prose-hold backstop to the user veto at the D15 presentation and states explicitly that the de-warmed comprehension gate is NOT a prose-hold backstop (it runs no prose AI-tell axis, D9/S4, and the hold suppresses the only prose owner). The comprehension gate + iteration-budget back only the comprehension/structural and decision-shaped axes. Confirmed against `edit-design/SKILL.md:640` and `readability-auditor.md:58` (the S4 one-owner invariant). This is A5's proposed fix.

### A6 [should-fix]
**Certificate**: Verdict A6 — D7 resolves the tier + §1.7 routing into the Decision Log
**Target**: Open Question (meta) → Decision D7
**Verdict**: VERIFIED.
**Resolution**: D7 resolves the load-bearing open question into the Decision Log: tier = `full` (Concern B warrants a `design.md`), §1.7 routing = full staging not the opt-out. The reasoning is grounded correctly: §1.7(k) criterion 2 (`conventions.md:1362-1372`) names "the step-implementation orchestration loop" as executable procedure that stays staged, and this change's core edits are the dual-clean orchestration loop, so the opt-out is disqualified. (Criterion 1 is independently strained by D6's resume-glob/review-file-home relocation, reinforcing the staging call.) The Open Questions section now carries a RESOLVED marker. A6's gate-clearing precondition is met.

### A7 [suggestion]
**Certificate**: Verdict A7 — D5 documents track-path anchor stability
**Target**: Decision D5
**Verdict**: VERIFIED.
**Resolution**: D5 adds the note that `create-plan` Step 4b items 1–8 settle the Component Map and track skeletons before item 9's dual-clean loop, so the standing anchors are byte-stable for the loop's duration. Confirmed against `create-plan/SKILL.md:770-776`. The documentation gap A7 flagged is closed.
