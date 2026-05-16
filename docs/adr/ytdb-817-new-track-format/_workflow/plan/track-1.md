# Track 1: Enrich Phase B/C review-agent-selection with workflow-machinery triage

## Purpose / Big Picture

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Extend `.claude/workflow/review-agent-selection.md` so Phase B (step-level, `risk: high`) and Phase C (track-level) dimensional reviews dispatch the six workflow-review agents (`review-workflow-consistency`, `review-workflow-prompt-design`, `review-workflow-instruction-completeness`, `review-workflow-hook-safety`, `review-workflow-context-budget`, `review-workflow-writing-style`) when a step or track touches workflow-machinery files. Without this, Phase C of Tracks 2–4 (and the very review of this track too) would dispatch only Java-focused agents — `review-code-quality`, `review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness` — and find nothing meaningful in our markdown-only diff. The `/code-review` standalone skill already has the right triage; this track imports its logic into the Phase B/C selection rules so the two paths agree on agent dispatch.

## Progress
- [x] 2026-05-15T17:28Z [ctx=unknown] Review + decomposition complete
- [x] 2026-05-15T17:45Z [ctx=safe] Step 1 complete (commit ecd885f883)
- [x] 2026-05-15T17:48Z [ctx=safe] Step 2 complete (commit 300693c835)
- [x] 2026-05-15T17:53Z [ctx=safe] Step 3 complete (commit 9cd6cddd39)
- [x] 2026-05-15T18:09Z [ctx=unknown] Review fix complete (commit 36d7a62201)
- [x] 2026-05-15T18:13Z [ctx=unknown] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Empty for this track — no cross-cutting discoveries surfaced during Phase B/C. -->

## Decision Log
<!-- Continuous-log. Empty for this track — no execution-time decisions surfaced beyond the plan as written. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

- [x] Technical: PASS at iteration 2 (5 findings, 5 accepted, 0 rejected; iter-1 surfaced T1+T2+T3 should-fix and T4+T5 suggestions; one orchestrator-applied fix on `review-workflow-context-budget` row; iter-2 gate VERIFIED all six)
- [x] Track-level code review: PASS at iteration 2 (8 findings, all accepted, 0 rejected; 4 reviewers fired — WC + WI + WB + WS per workflow-only override case 1; WB returned no findings; iter-1 surfaced F1+F2+F3 should-fix (stale `Baseline agents (4) always run` prose in `track-code-review.md`/`code-review-protocol.md` + missing `WC/WP/WI/WH/WB/WS` rows in `review-iteration.md`), F6 should-fix (durable Maintenance contract for SKILL.md sync), F4+F5+F7+F8 suggestions; iter-2 gate VERIFIED all eight; 4 deferred findings routed to Track 2 (§2.1 wording) or self-improvement reflection (re-verification scope, pre-existing budget commentary, audit-anchor recipe codification))
- Phase C Review fix (commit 36d7a62201, 2026-05-15T18:09Z [ctx=unknown]): synced workflow-doc references to the new workflow-review agent tier across `track-code-review.md`, `code-review-protocol.md`, and `review-iteration.md`'s finding-prefix registry to add `WC/WP/WI/WH/WB/WS` rows, plus a Maintenance contract subsection for the SKILL.md sync. Eight findings (F1-F8) accepted; iter-2 gate VERIFIED all eight. Touched files: `.claude/workflow/track-code-review.md`, `.claude/workflow/code-review-protocol.md`, `.claude/workflow/review-iteration.md`.

## Context and Orientation

`.claude/workflow/review-agent-selection.md` is the load-bearing dispatch rule that both Phase B (step-level, `risk: high`) and Phase C (track-level) dimensional reviews read at runtime. Pre-Track-1 baseline lists only Java-focused agents — `review-code-quality`, `review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness` — under "Baseline agents (always)" plus conditional agents under "Conditional agents". The `/code-review` standalone skill at `.claude/skills/code-review/SKILL.md` already carries a workflow-machinery triage at Step 5a-5d + Step 6 that dispatches six workflow-review agents (`WC/WP/WI/WH/WB/WS`) on workflow-machinery files and skips the baseline code/test agents on workflow-only diffs. The in-workflow path and the standalone path produce divergent agent sets on the same diff, which is the failure mode this track closes.

## Plan of Work

Import the `/code-review` Step 5a-5d + Step 6 triage logic into `review-agent-selection.md` as a third tier (Workflow-review agents) parallel to Baseline and Conditional. Add the per-agent file-pattern trigger table verbatim from SKILL.md Step 5b. Add the canonical workflow-machinery file-set definition from SKILL.md Step 5a. Add the three-case baseline-skip override mirroring SKILL.md Step 5d bullets 1-3 (workflow-only / `docs-only + workflow-machinery` mix / mixed with Java with `IN_SCOPE_FILES` filtering per SKILL.md Step 6). Update the Selection-process step 4 wording and the Examples section. Update the recap prose at `step-implementation.md` sub-step 4(a) lines 373-374 to reflect the override. End with a four-item side-by-side sync check against `/code-review` SKILL.md to lock the mirror.

## Concrete Steps

1. Rewrite `.claude/workflow/review-agent-selection.md` — add the Workflow-review agents tier (six entries with `subagent_type` and finding-prefix mapping `WC/WP/WI/WH/WB/WS`), the per-agent file-pattern trigger table mirroring `/code-review` SKILL.md Step 5b (lines 156-161) verbatim including the "always launched" rows for `review-workflow-consistency` and `review-workflow-context-budget`, the canonical workflow-machinery file-set definition from SKILL.md Step 5a (line 120) covering the full `docs/adr/<dir>/` tree, the three-case baseline-skip override mirroring SKILL.md Step 5d bullets 1-3 (workflow-only / `docs-only + workflow-machinery` mix / mixed with Java with `IN_SCOPE_FILES` filtering per SKILL.md Step 6), the updated Selection process step 4 wording, and three Examples covering all three override cases. Run Spotless N/A (markdown only). Single commit. — `risk: medium` [x] commit: ecd885f883
2. Update `.claude/workflow/step-implementation.md` sub-step 4(a) recap prose at lines 373-374 — replace *"Baseline agents (4) always run; conditional agents are added based on the step description and changed files."* with *"Baseline agents (4) run unless the diff is workflow-only (see the baseline-skip override in `review-agent-selection.md`); conditional agents and workflow-review agents are added based on the step description and changed files."* Re-verify `.claude/workflow/track-code-review.md` §Agent selection and launching does not name agents explicitly (Phase A check confirmed clean; skip the edit if still clean). Single commit covering the `step-implementation.md` recap update. — `risk: low` [x] commit: 300693c835
3. Side-by-side sync check against `.claude/skills/code-review/SKILL.md` per the four canonical items — (1) workflow-machinery file-set matches SKILL.md Step 5a (line 120); (2) per-agent file-pattern triggers match Step 5b table (lines 156-161) verbatim, especially `review-workflow-context-budget` (always launched, not gated on a sub-pattern); (3) three override cases match Step 5d bullets 1-3 (lines 182-184); (4) filtered-dispatch shape on mixed diffs matches Step 6's `IN_SCOPE_FILES` per group (lines 196-198). Output is a deltas-or-all-clear note recorded in the step episode. If any item diverges, repair `review-agent-selection.md` in this step's commit; do not document disagreement (per D8 rationale). — `risk: low` [x] commit: 9cd6cddd39

## Episodes

### Step 1 — commit ecd885f883, 2026-05-15T17:45Z [ctx=safe]
**What was done:** Rewrote `.claude/workflow/review-agent-selection.md` (commit `ecd885f883`) to add a third **Workflow-review agents** tier (six entries with `subagent_type` and finding-prefix mapping `WC/WP/WI/WH/WB/WS`), the canonical workflow-machinery file-set definition (`.claude/` + root `CLAUDE.md` + full `docs/adr/<dir>/` tree per SKILL.md Step 5a line 120), the per-agent file-pattern trigger table verbatim from SKILL.md Step 5b lines 156-161 (including the "always launched" rows for `review-workflow-consistency` and `review-workflow-context-budget`), the three-case baseline-skip override mirroring SKILL.md Step 5d bullets 1-3 (workflow-only / `docs-only + workflow-machinery` mix / mixed with Java using `IN_SCOPE_FILES` filtering per SKILL.md Step 6), updated **Selection process** step 4 wording, and three **Examples** covering all three override cases. The existing Baseline + Conditional sections retained their content; a one-sentence pointer in Baseline links to the override.

**Key files:** `.claude/workflow/review-agent-selection.md` (modified, 184 lines)

**Critical context:** The implementer's `CROSS_TRACK_HINTS` notes this track's own Phase C review will exercise the new override on a workflow-only cumulative diff — per case 1 the baseline group is skipped and the four "always launched" workflow-review agents fire (`review-workflow-consistency` + `review-workflow-instruction-completeness` + `review-workflow-writing-style` + `review-workflow-context-budget`). This matches the agent-set §Interactions in this track's description predicted, confirming the rule is internally consistent with its first consumer.

### Step 2 — commit 300693c835, 2026-05-15T17:48Z [ctx=safe]
**What was done:** Updated `.claude/workflow/step-implementation.md` sub-step 4(a) recap (commit `300693c835`) to read *"Baseline agents (4) run unless the diff is workflow-only (see the baseline-skip override in `review-agent-selection.md`); conditional agents and workflow-review agents are added based on the step description and changed files."* — replacing the stale *"Baseline agents (4) always run"* sentence that became inaccurate after Step 1 introduced the workflow-review tier and the three-case override. Recap defers override details to `review-agent-selection.md` rather than restating them.

**What was discovered:** Re-verified `.claude/workflow/track-code-review.md` §Agent selection and launching (lines 362-378) — still clean (routes through `review-agent-selection.md` without naming any agent count or specific agent set). No edit needed there, as Phase A predicted.

**Key files:** `.claude/workflow/step-implementation.md` (modified)

### Step 3 — commit 9cd6cddd39, 2026-05-15T17:53Z [ctx=safe]
**What was done:** Re-performed the four-item side-by-side sync check between `.claude/workflow/review-agent-selection.md` (Step 1 output) and `.claude/skills/code-review/SKILL.md`. Independently re-verified each item: (1) workflow-machinery file-set + `docs-only` exclusivity at `review-agent-selection.md` lines 76-93 vs SKILL.md line 120 + line 124; (2) per-agent file-pattern triggers including always-launched `review-workflow-context-budget` and `review-workflow-consistency` with three-axes parenthetical intact at lines 105-112 vs SKILL.md lines 156-161; (3) three override cases at lines 121-138 vs SKILL.md Step 5d bullets 1-3 (lines 182-184); (4) filtered-dispatch `IN_SCOPE_FILES` shape at lines 134-138 + Selection-process step 4 (lines 154-157) vs SKILL.md Step 6 (lines 196-198). **All four items matched verbatim in content** — no divergences, no repairs needed. Appended a one-line durable audit-anchor at the bottom of `review-agent-selection.md` dated 2026-05-15 so future drift sweeps can update the date and surface as a single-line `git blame` (commit `9cd6cddd39`).

**What was discovered:** The first spawn surfaced a protocol-fit issue: verification-gate steps that produce no code change on the happy path don't cleanly satisfy the implementer SUCCESS contract, which requires a non-empty `COMMIT`. The orchestrator-side resolution adopted here is to mint a durable audit-anchor on all-clear so the step has a real commit and the audit trail is locally readable from `git blame` of the anchor line. Future verification-gate steps elsewhere in the workflow should adopt the same pattern.

**Key files:** `.claude/workflow/review-agent-selection.md` (modified — audit anchor appended)

**Critical context:** The audit-anchor is a **load-bearing artifact** of the SKILL.md ↔ `review-agent-selection.md` sync contract. Any future edit that changes either file's Step 5a / 5b / 5d / 6 content MUST update both files together and bump the anchor date. Worth flagging in later tracks that touch workflow-review agent selection rules.

## Validation and Acceptance
<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — no idempotence or recovery concerns for this track (markdown-only edits to workflow rule files; re-running any step would re-apply the same edits idempotently). -->

## Artifacts and Notes
<!-- Cross-step artifacts only. None for this track. -->

## Interfaces and Dependencies

**In-scope files**: `.claude/workflow/review-agent-selection.md` (load-bearing edit, Step 1); `.claude/workflow/step-implementation.md` sub-step 4(a) recap prose at line 373-374 (Step 2 edit per T2). Read-only verification of `.claude/workflow/track-code-review.md` §Agent selection and `.claude/skills/code-review/SKILL.md` Step 5a-5d + Step 6.

**Out-of-scope**: the `/code-review` SKILL itself (it already has the right triage); the six workflow-review agent definitions under `.claude/agents/review-workflow-*.md` (already exist with the right shape); the spec / writers / readers work (Tracks 2–4).

**Inter-track dependencies**:
- **All subsequent tracks depend on this one** for Phase C dispatch correctness. Phase C of Tracks 2, 3, 4 will dispatch the workflow-review agents because of this track's edit.
- **This track's own Phase C review** dispatches the new workflow-review agents — `review-agent-selection.md` is a `.claude/workflow/*.md` file under workflow-machinery, matching `review-workflow-instruction-completeness` + `review-workflow-consistency` + `review-workflow-writing-style` + `review-workflow-context-budget` (the latter always fires per SKILL.md row). The workflow-only baseline-skip override applies. **4 agents fire** for the cumulative track diff; no Java baseline agents.
- No dependency on the directory rename (Track 2), writer changes (Track 3), or reader sweeps (Track 4). `review-agent-selection.md` is touched only by this track; `step-implementation.md` sub-step 4(a) recap is touched only by this track's Step 2.
- The `/code-review` standalone skill is not modified — sync-check confirms the two paths agree post-edit per D8 rationale. Any divergence at sync-check time is repaired inside this track, not documented as a deviation.

**No agent definition changes**: the six workflow-review agents already exist. Their frontmatter, descriptions, and review rubrics are not edited by this track.

**No new finding prefixes invented**: `WC`, `WP`, `WI`, `WH`, `WB`, `WS` are confirmed non-colliding (verified at Phase A review against existing `CQ, BC, TB, TC, CS, TY, SE, PF, TX, TS`). Implementer re-runs the grep only if `review-iteration.md`'s prefix table changed between Phase A and Phase B.

**Old-format compatibility**: this track edits `review-agent-selection.md`, which is read by Phase B/C of every active branch's `/execute-tracks` session. Edits MUST be backward-compatible — adding a new group + three-case override is additive; the existing baseline + conditional logic is unchanged. In-flight branches' Phase C reviews on Java code keep working identically (workflow-only override does not fire on Java-only diffs).

## Base commit
1efc5831216eabb5b47952b6bf05bbfbbefbf420
