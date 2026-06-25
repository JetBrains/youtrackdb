<!-- MANIFEST
role: reviewer-risk
track: "Track 1: Harden readability-auditor slicing and convergence"
iteration: 2
phase: 3A
review_kind: gate-verification
overall: PASS
findings: 0
blockers: 0
verdicts:
  - id: R1
    sev: should-fix
    verdict: VERIFIED
    loc: "track-1.md Plan-of-Work step 2 vs edit-design/SKILL.md:832-837"
  - id: R2
    sev: should-fix
    verdict: VERIFIED
    loc: "track-1.md Plan-of-Work step 3 + Interfaces row vs readability-auditor.md:75-83"
  - id: R3
    sev: suggestion
    verdict: VERIFIED
    loc: "track-1.md D1 Risks/Caveats"
  - id: R4
    sev: suggestion
    verdict: VERIFIED
    loc: "track-1.md D5 + Plan-of-Work step 5 vs create-plan/SKILL.md:810-826"
-->

# Risk gate-verification — Track 1: Harden readability-auditor slicing and convergence (iteration 2)

Workflow-prose change, no Java symbol surface. References verified as workflow paths / `§`-anchors via grep + Read against the LIVE `.claude/**` files (branch in §1.7(b) staging, `s17`=workflow-modifying, staged tree absent at Phase A). All four prior risk findings (R1/R2 should-fix, R3/R4 suggestion) were ACCEPTED and fixed in the track file. Each fix was re-checked against the live source it couples to, and the surrounding track sections were re-read for regressions.

Verdict: PASS. All four findings VERIFIED. No regression introduced by the edits. No new finding.

#### Verify R1: Step 6 convergence-claim prose vs the D8 never-clean tail
- **Original issue**: The live `edit-design` Step 6 closes (lines 832-837) with "the loop moves monotonically toward dual-clean … the budget plus escalation is the backstop for a pathological case, not the expected path." D8's never-clean dense-but-acceptable tail makes budget+S5 a *designed* terminal exit. If the Step-6 edit appends beside that paragraph instead of reconciling it, two adjacent claims contradict (budget = pathology vs budget = designed exit).
- **Fix applied**: Plan-of-Work step 2 (track-1.md:215) now states the Step-6 edit **reconciles** the live closing paragraph "rather than appending beside it" — quoting the exact live text ("the loop moves monotonically toward dual-clean … the budget plus escalation is the backstop for a pathological case, not the expected path") and restating it so monotonic convergence holds on the settled sections while the never-clean dense-but-acceptable tail bounded by `iteration_budget` + S5 reads as a designed terminal exit (D8), not a pathology. It closes with "The two adjacent claims must not contradict — flag this reconciliation as an explicit in-scope edit so the Phase C `writing-style` / `instruction-completeness` reviewers check it."
- **Re-check**:
  - Track-file location: `## Plan of Work` step 2 (track-1.md:215).
  - Live coupling confirmed: `edit-design/SKILL.md:832-837` carries verbatim "moves monotonically toward dual-clean — typically one or two rounds. The budget plus escalation is the backstop for a pathological case, not the expected path." The quote in step 2 matches the live text, so the reconciliation target is correctly identified.
  - Current state vs original: the instruction is now RECONCILE (not append), names the live paragraph, and routes verification to the Phase C reviewers. The contradiction risk is closed at the plan level — an implementer following step 2 must edit the existing paragraph, not add a second claim.
  - Criteria met: rule-coherence / non-contradiction is now an explicit decomposition obligation. D8 (track-1.md:134, 142) independently frames the budget+S5 tail as the bounded exit, so the design rationale and the Step-6 edit instruction agree.
- **Regression check**: Checked the Decision Log D8 body and the `## Context and Orientation` loop description (track-1.md:156-164) for a newly-introduced contradiction. D8 already calls budget+S5 the designed terminal exit; the orientation text says "terminates at dual-clean or at `iteration_budget`". Step 2 now aligns the live Step-6 prose with both. No new contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify R2: auditor params block must gain slice_count/total_lines and the guard, or the new params are inert
- **Original issue**: `readability-auditor.md`'s `## Inputs (read from the params file first)` block (lines 75-83) lists only `target`/`target_path`/`range`. The new `slice_count`/`total_lines` params and the guard condition must land in that block, or the orchestrator passes params the agent contract never reads and the guard has no documented inputs (silently dead). The track's `## Interfaces and Dependencies` row named only "Range-sliced fan-out" + cold-read guarantee, omitting `## Inputs`.
- **Fix applied**: Plan-of-Work step 3 (track-1.md:216) now states the two params and the guard condition land in the agent's `## Inputs (read from the params file first)` block "which today enumerates only `target` / `target_path` / `range` — not only in the descriptive 'Range-sliced fan-out' prose," and spells out the failure mode ("Editing the prose alone would leave the orchestrator passing two params the agent contract never reads and the guard with no documented inputs (the secondary detector silently dead)"). The `## Interfaces and Dependencies` row for `readability-auditor.md` (track-1.md:260) now names `## Inputs (read from the params file first)` as a touched section and states the params and guard "added to the `## Inputs` block so the new params are not inert (D2)".
- **Re-check**:
  - Track-file location: Plan-of-Work step 3 (track-1.md:216) and the `readability-auditor.md` row of the `## Interfaces and Dependencies` table (track-1.md:260).
  - Live coupling confirmed: `readability-auditor.md:75-83` is the `## Inputs (read from the params file first)` block, enumerating exactly `target` (`:79`), `target_path` (`:80`), `range` (`:81`), closing with "The params file names no research-log path" (`:83`). The track now names this exact block, so the obligation lands on the correct anchor.
  - Current state vs original: the `## Inputs` block is now a named touched section in both the Plan of Work and the interface table; the params↔guard coupling is an inspectable obligation for the Phase C `instruction-completeness` reviewer.
  - Criteria met: the assumption "the new params must land in `## Inputs` or they are inert" is now an explicit, inspectable contract, closing the under-specification that made the secondary detector silently-dead-but-looks-complete.
- **Regression check**: Checked that the added `## Inputs` touch does not collide with the S1 cold-read invariant. The `## Inputs` block's `:83` "names no research-log path" line and the S1 invariant (track-1.md:279) are untouched by the instruction; the two new fields are slicing metadata (constant across the fan-out, design.md:241), so adding them to `## Inputs` does not introduce a research-log read or a per-round-varying field. S1 holds. Clean.
- **Verdict**: VERIFIED

#### Verify R3: the agent guard catches only total collapse; the self-check is the sole partial-collapse catcher
- **Original issue**: The "deliberate redundant double-check" framing could over-claim coextensive coverage. The agent guard fires only on total collapse (`slice_count == 1 AND total_lines > ~300`); a partial collapse (e.g. 2 slices where 5 are demanded) is caught only by the orchestrator self-check. The residual should be visible so a reader does not over-trust the double-check.
- **Fix applied**: D1 Risks/Caveats (track-1.md:39) now states "The two layers are not coextensive: the agent-side guard fires only on the *total* collapse (`slice_count == 1 AND total_lines > ~300`), so the orchestrator self-check is the sole catcher of a *partial* collapse (e.g. 2 slices where the partition demands 5). The redundancy is full for the total-collapse case and self-check-only for a partial-count mismatch — Step 4 states this so a reader does not over-trust the guard as covering every fan-out error."
- **Re-check**:
  - Track-file location: D1 Risks/Caveats (track-1.md:39).
  - Design coupling confirmed: design.md:240 ("The guard is secondary: it catches the orchestrator-collapsed case from inside the auditor, but the orchestrator's partition + self-check is what normally prevents the collapse") and design.md:234 (guard fires on `slice_count == 1 AND total_lines > ~300`) corroborate the asymmetry. The track text matches the design's coverage model.
  - Current state vs original: the residual is now stated explicitly — guard = total-collapse only, self-check = sole partial-collapse catcher — and routes the disclosure into Step 4 prose. The "double-check" framing no longer reads as coextensive.
  - Criteria met: the exposure residual is visible rather than implied-away; no structural change required (the prose-over-script lightness call stands, D1).
- **Regression check**: Checked D2 Risks/Caveats (track-1.md:53-58), which carries the parallel "deliberate redundant double-check" language. D2 frames the redundancy around the floor condition living in two places (partition + guard), which is the total-collapse axis; it does not claim partial-collapse coverage, so D1's added clarification does not contradict D2. The two Decision Records are consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R4: the D5 cross-reference must point at the canonical mechanism without re-opening the existing per-file partition rule
- **Original issue**: `create-plan` Step 4b item 9 already carries a per-file deterministic slice rule and the track-path anchors. The D5 cross-reference must point at the canonical convergence statement without re-authoring or contradicting that existing per-file rule. A cross-reference written as "apply the same partition" would contradict the correct per-file rule (the slicing units differ by design: per-window design / per-file track).
- **Fix applied**: D5 (track-1.md:92) now states item 9 "**already** carries the per-file deterministic slicing rule … so this edit adds only the *convergence-mechanism* cross-reference … it preserves the existing per-file partition verbatim and must not re-author or contradict it. The slicing *unit* differs by design — per-window on the design path, per-file on the track path — so the reference reads 'same convergence mechanism, parameterized; the slicing unit stays per-file as already stated here,' never 'apply the same partition.'" Plan-of-Work step 5 (track-1.md:218) repeats the same constraint: "preserve its existing per-file partition rule verbatim and frame the reference as 'same convergence mechanism, parameterized; the slicing unit stays per-file as already stated here,' never 'apply the same partition.'"
- **Re-check**:
  - Track-file location: D5 (track-1.md:92) and Plan-of-Work step 5 (track-1.md:218).
  - Live coupling confirmed: `create-plan/SKILL.md:810-826` carries the existing per-file rule verbatim — "one `readability-auditor` spawn per `plan/track-N.md` (in track-number order)" (`:813-814`) and "this per-file rule is the deterministic partition, so every orchestrator run produces the same set of slices" (`:819-821`), with the track-path anchors "the plan Component Map and each track's `## Purpose / Big Picture`" (`:822-825`). The track's claim that the per-file rule and anchors already exist there is accurate.
  - Current state vs original: the cross-reference is now framed as adding only the convergence mechanism while preserving the per-file partition verbatim, with the banned "apply the same partition" phrasing explicitly ruled out in both D5 and step 5. The drift/contradiction risk is closed.
  - Criteria met: the assumption (the track-path partition exists and is per-file deterministic) is VALIDATED against the live file, and the instruction now forbids re-opening it. No contradiction risk remains.
- **Regression check**: Checked D2 (track-1.md:49) and the `create-plan/SKILL.md` interface row (track-1.md:262) for consistency with the "preserve verbatim, add convergence-mechanism only" framing. D2 names "`create-plan` Step 4b item 9 (per-file unit on the track path)" and the interface row says "the per-file slicing rule and track-path anchors already exist there and are preserved verbatim (D2/D5)". All three agree. The D6 relocation row (track-1.md:262, "relocate **only** the per-spawn params-file home … leaving the author `output_path` and absorption `draft_path` on `plan/`") is consistent with item 9's live `output_path`/`draft_path` on `plan/` (`:830-835`). Clean.
- **Verdict**: VERIFIED

## Findings

None.
