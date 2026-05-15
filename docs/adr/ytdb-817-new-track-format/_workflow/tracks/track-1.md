# Track 1: Enrich Phase B/C review-agent-selection with workflow-machinery triage

## Description
Extend `.claude/workflow/review-agent-selection.md` so Phase B (step-level, `risk: high`) and Phase C (track-level) dimensional reviews dispatch the six workflow-review agents (`review-workflow-consistency`, `review-workflow-prompt-design`, `review-workflow-instruction-completeness`, `review-workflow-hook-safety`, `review-workflow-context-budget`, `review-workflow-writing-style`) when a step or track touches workflow-machinery files. Without this, Phase C of Tracks 2–4 (and the very review of this track too) would dispatch only Java-focused agents — `review-code-quality`, `review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness` — and find nothing meaningful in our markdown-only diff. The `/code-review` standalone skill already has the right triage; this track imports its logic into the Phase B/C selection rules so the two paths agree on agent dispatch.

> **What**:
> - Update `.claude/workflow/review-agent-selection.md`:
>   - Add a new third-tier group **"Workflow-review agents"** (parallel to "Baseline agents" and "Conditional agents"). Each agent gets a `subagent_type` and a finding-prefix mapping:
>     | Agent | `subagent_type` | Finding prefix |
>     |---|---|---|
>     | Workflow consistency | `review-workflow-consistency` | `WC1, WC2, ...` |
>     | Workflow prompt design | `review-workflow-prompt-design` | `WP1, WP2, ...` |
>     | Workflow instruction completeness | `review-workflow-instruction-completeness` | `WI1, WI2, ...` |
>     | Workflow hook safety | `review-workflow-hook-safety` | `WH1, WH2, ...` |
>     | Workflow context budget | `review-workflow-context-budget` | `WB1, WB2, ...` |
>     | Workflow writing style | `review-workflow-writing-style` | `WS1, WS2, ...` |
>   - Add per-agent file-pattern triggers mirroring the `/code-review` skill's Step 5b workflow-machinery rules:
>     | Agent | Fires when changed files include |
>     |---|---|
>     | `review-workflow-consistency` | Any workflow-machinery file — always fires for this group |
>     | `review-workflow-prompt-design` | `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, `.claude/workflow/prompts/*.md` |
>     | `review-workflow-instruction-completeness` | `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, `.claude/workflow/*.md`, `.claude/workflow/prompts/*.md` |
>     | `review-workflow-hook-safety` | `.claude/hooks/*.sh`, `.claude/scripts/**`, `.claude/settings*.json` |
>     | `review-workflow-context-budget` | `workflow-machinery` is present — always launched for this group; the agent decides whether the diff affects any of the three context-budget axes and emits empty findings when none are affected (mirrors `/code-review` SKILL.md row verbatim) |
>     | `review-workflow-writing-style` | `.claude/**/*.md`, root `CLAUDE.md`, `docs/adr/**/*.md` |
>   - Define **workflow-machinery** as the SKILL.md categorisation (verbatim from `/code-review` SKILL.md:120): files under `.claude/` (skills, agents, hooks, scripts, settings, workflow rules, workflow prompts, output styles, docs), the project root `CLAUDE.md`, **and all files under `docs/adr/<dir>/`** — covering both the `_workflow/` working files and the durable post-merge `design-final.md` / `adr.md` at top level.
>   - Add the **baseline-skip override**, mirroring `/code-review` SKILL.md Step 5d's three workflow-machinery cases verbatim so the in-workflow and standalone paths dispatch the same agents on the same diff (per D8 rationale):
>     1. **Workflow-only diff** — only category is `workflow-machinery`: skip the four baseline code/test agents (`review-code-quality`, `review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness`); launch the workflow-review group via the per-agent triggers above.
>     2. **`docs-only` + `workflow-machinery` mix** (any mix, no Java/test) — treat as workflow-machinery-only: skip baseline code/test agents; launch the workflow-review group on the `workflow-machinery` files. `docs-only` covers `docs/**` outside `docs/adr/`; `workflow-machinery` covers everything else workflow-related (per Step 5a's `docs-only`/`workflow-machinery` exclusivity at SKILL.md:124).
>     3. **`workflow-machinery` mixed with production-code or test categories** — launch each group's agents on its in-scope files; pre-filter each group with an `IN_SCOPE_FILES` list so baseline agents see only Java/test files and workflow-review agents see only workflow-machinery files (mirrors SKILL.md Step 6's filtered-dispatch shape so cross-contamination is bounded).
>   - Update the **Selection process** section to reference the workflow-review group: step 4 ("Spawn the baseline agents plus any matching conditional agents") becomes "Spawn the baseline agents (subject to the three-case override above) plus any matching conditional agents plus any matching workflow-review agents, each group dispatched with its filtered `IN_SCOPE_FILES` on mixed diffs."
>   - Update the **Examples** section to add three workflow-machinery examples covering the three override cases: (a) *"Step rewrites `.claude/workflow/conventions-execution.md` §2.1 → workflow-only diff; baseline skipped; workflow-review group fires `review-workflow-consistency` + `review-workflow-instruction-completeness` + `review-workflow-writing-style` + `review-workflow-context-budget`. 4 agents."* (b) *"Step edits `docs/architecture.md` (non-ADR) plus `.claude/workflow/track-review.md` → `docs-only` + `workflow-machinery` mix; baseline skipped; workflow-review group fires on the `.claude/` file only."* (c) *"Step changes `core/.../BTree.java` plus `.claude/workflow/step-implementation.md` → mixed Java+workflow diff; baseline + workflow-review groups both fire, each on its filtered file list."*
> - Finding-prefix uniqueness for `WC`, `WP`, `WI`, `WH`, `WB`, `WS` against existing `CQ, BC, TB, TC, CS, TY, SE, PF, TX, TS` was confirmed at Phase A review time (grep `'\b(WC|WP|WI|WH|WB|WS)[0-9]'` across `.claude/` returned no matches). Implementer re-runs the grep only if `review-iteration.md`'s prefix table has changed since this track's Phase A.
> - Read `.claude/workflow/track-code-review.md` §Agent selection and launching — verification at Phase A confirmed the section routes through `review-agent-selection.md` without naming any agent count or set explicitly. No edit needed.
> - Read `.claude/workflow/step-implementation.md` §Per-Step Orchestration Loop sub-step 4(a) — the recap prose at line 373-374 currently says *"Baseline agents (4) always run; conditional agents are added based on the step description and changed files."* After this track lands, both "(4)" and "always run" are stale: the override fires on workflow-only diffs and the workflow-review group adds a third tier. Update the recap to *"Baseline agents (4) run unless the diff is workflow-only (see the baseline-skip override in `review-agent-selection.md`); conditional agents and workflow-review agents are added based on the step description and changed files."*
> - Sync-check Step (final step): read `review-agent-selection.md` and `/code-review` SKILL.md side-by-side and confirm the following items match (deltas-or-all-clear note in the step episode):
>   1. The workflow-machinery file-set definition matches SKILL.md Step 5a (line 120).
>   2. The per-agent file-pattern triggers match SKILL.md Step 5b table verbatim (especially `review-workflow-context-budget`, which is "always launched", not gated on a sub-pattern).
>   3. The three override cases (workflow-only / docs-only+workflow mix / mixed with Java) match SKILL.md Step 5d bullets 1, 2, 3.
>   4. The filtered-dispatch shape on mixed diffs matches SKILL.md Step 6's `IN_SCOPE_FILES` per group.
>
>   If any item diverges, fix `review-agent-selection.md` in the same commit; do not record divergence in an episode — D8's rationale requires agreement, not documented disagreement.
>
> **How**:
> - Step 1: rewrite `review-agent-selection.md` — add the Workflow-review agents section, the per-agent trigger table mirroring SKILL.md Step 5b verbatim, the three-case baseline-skip override mirroring SKILL.md Step 5d bullets 1-3, the updated Selection process step 4 wording, the three Examples covering all three override cases, and the canonical workflow-machinery definition from SKILL.md Step 5a (full `docs/adr/<dir>/` tree, not just `_workflow/`). Single commit; this is the load-bearing edit.
> - Step 2: update `step-implementation.md` sub-step 4(a) recap prose at line 373-374 to reflect the override (replace "Baseline agents (4) always run" with the conditional wording above) and add the workflow-review group to the recap. Read `track-code-review.md` §Agent selection — verified at Phase A to route through `review-agent-selection.md` without naming agents explicitly, no edit expected; re-verify and skip the edit if still clean. Single commit if `step-implementation.md` is updated; otherwise the verification result rolls into Step 3's sync-check note.
> - Step 3: side-by-side sync check against `/code-review` SKILL.md per the four check items above (workflow-machinery definition, per-agent triggers, three override cases, mixed-diff filtered dispatch). Deltas-or-all-clear note in the step episode. If any divergence remains after Step 1, fix `review-agent-selection.md` in this step's commit; do not record disagreement.
>
> **Constraints**:
> - **In-scope files**: `.claude/workflow/review-agent-selection.md` (load-bearing edit, Step 1); `.claude/workflow/step-implementation.md` sub-step 4(a) recap prose at line 373-374 (Step 2 edit per T2). Read-only verification of `.claude/workflow/track-code-review.md` §Agent selection and `.claude/skills/code-review/SKILL.md` Step 5a-5d + Step 6.
> - **Out-of-scope**: the `/code-review` SKILL itself (it already has the right triage); the six workflow-review agent definitions under `.claude/agents/review-workflow-*.md` (already exist with the right shape); the spec / writers / readers work (Tracks 2–4).
> - **No agent definition changes**: the six workflow-review agents already exist. Their frontmatter, descriptions, and review rubrics are not edited by this track.
> - **No new finding prefixes invented**: `WC`, `WP`, `WI`, `WH`, `WB`, `WS` are confirmed non-colliding (verified at Phase A review against existing `CQ, BC, TB, TC, CS, TY, SE, PF, TX, TS`). Implementer re-runs the grep only if `review-iteration.md`'s prefix table changed between Phase A and Phase B.
> - **Old-format compatibility**: this track edits `review-agent-selection.md`, which is read by Phase B/C of every active branch's `/execute-tracks` session. Edits MUST be backward-compatible — adding a new group + three-case override is additive; the existing baseline + conditional logic is unchanged. In-flight branches' Phase C reviews on Java code keep working identically (workflow-only override does not fire on Java-only diffs).
>
> **Interactions**:
> - **All subsequent tracks depend on this one** for Phase C dispatch correctness. Phase C of Tracks 2, 3, 4 will dispatch the workflow-review agents because of this track's edit.
> - **This track's own Phase C review** dispatches the new workflow-review agents — `review-agent-selection.md` is a `.claude/workflow/*.md` file under workflow-machinery, matching `review-workflow-instruction-completeness` + `review-workflow-consistency` + `review-workflow-writing-style` + `review-workflow-context-budget` (the latter always fires per SKILL.md row). The workflow-only baseline-skip override applies. **4 agents fire** for the cumulative track diff; no Java baseline agents.
> - No dependency on the directory rename (Track 2), writer changes (Track 3), or reader sweeps (Track 4). `review-agent-selection.md` is touched only by this track; `step-implementation.md` sub-step 4(a) recap is touched only by this track's Step 2.
> - The `/code-review` standalone skill is not modified — sync-check confirms the two paths agree post-edit per D8 rationale. Any divergence at sync-check time is repaired inside this track, not documented as a deviation.

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/3 complete)
- [ ] Track-level code review

## Base commit
1efc5831216eabb5b47952b6bf05bbfbbefbf420

## Reviews completed
- [x] Technical: PASS at iteration 2 (5 findings, 5 accepted, 0 rejected; iter-1 surfaced T1+T2+T3 should-fix and T4+T5 suggestions; one orchestrator-applied fix on `review-workflow-context-budget` row; iter-2 gate VERIFIED all six)

## Steps
- [x] Step: Rewrite `.claude/workflow/review-agent-selection.md` — add the Workflow-review agents tier (six entries with `subagent_type` and finding-prefix mapping `WC/WP/WI/WH/WB/WS`), the per-agent file-pattern trigger table mirroring `/code-review` SKILL.md Step 5b (lines 156-161) verbatim including the "always launched" rows for `review-workflow-consistency` and `review-workflow-context-budget`, the canonical workflow-machinery file-set definition from SKILL.md Step 5a (line 120) covering the full `docs/adr/<dir>/` tree, the three-case baseline-skip override mirroring SKILL.md Step 5d bullets 1-3 (workflow-only / `docs-only + workflow-machinery` mix / mixed with Java with `IN_SCOPE_FILES` filtering per SKILL.md Step 6), the updated Selection process step 4 wording, and three Examples covering all three override cases. Run Spotless N/A (markdown only). Single commit.
  - [x] Context: safe
  > **Risk:** medium — multi-section change to a load-bearing workflow-dispatch rule that all Phase B/C reviews read at runtime. Not a HIGH category (no concurrency, durability, public API, security, performance hot path, or cross-module SPI change), but observable behavior change for review dispatch — Phase C of this very track plus Tracks 2-4 read the updated file. Phase C track-level review on the cumulative diff will exercise the rule with the new workflow-review agents.
  >
  > **What was done:** Rewrote `.claude/workflow/review-agent-selection.md` (commit `ecd885f883`) to add a third **Workflow-review agents** tier (six entries with `subagent_type` and finding-prefix mapping `WC/WP/WI/WH/WB/WS`), the canonical workflow-machinery file-set definition (`.claude/` + root `CLAUDE.md` + full `docs/adr/<dir>/` tree per SKILL.md Step 5a line 120), the per-agent file-pattern trigger table verbatim from SKILL.md Step 5b lines 156-161 (including the "always launched" rows for `review-workflow-consistency` and `review-workflow-context-budget`), the three-case baseline-skip override mirroring SKILL.md Step 5d bullets 1-3 (workflow-only / `docs-only + workflow-machinery` mix / mixed with Java using `IN_SCOPE_FILES` filtering per SKILL.md Step 6), updated **Selection process** step 4 wording, and three **Examples** covering all three override cases. The existing Baseline + Conditional sections retained their content; a one-sentence pointer in Baseline links to the override.
  >
  > **Key files:** `.claude/workflow/review-agent-selection.md` (modified, 184 lines)
  >
  > **Critical context:** The implementer's `CROSS_TRACK_HINTS` notes this track's own Phase C review will exercise the new override on a workflow-only cumulative diff — per case 1 the baseline group is skipped and the four "always launched" workflow-review agents fire (`review-workflow-consistency` + `review-workflow-instruction-completeness` + `review-workflow-writing-style` + `review-workflow-context-budget`). This matches the agent-set §Interactions in this track's description predicted, confirming the rule is internally consistent with its first consumer.

- [ ] Step: Update `.claude/workflow/step-implementation.md` sub-step 4(a) recap prose at lines 373-374 — replace *"Baseline agents (4) always run; conditional agents are added based on the step description and changed files."* with *"Baseline agents (4) run unless the diff is workflow-only (see the baseline-skip override in `review-agent-selection.md`); conditional agents and workflow-review agents are added based on the step description and changed files."*  Re-verify `.claude/workflow/track-code-review.md` §Agent selection and launching does not name agents explicitly (Phase A check confirmed clean; skip the edit if still clean). Single commit covering the `step-implementation.md` recap update.
  > **Risk:** low — one-line prose edit to a recap sentence. Isolated scope, no behavior change in code, no semantic divergence from Step 1's `review-agent-selection.md` rule.

- [ ] Step: Side-by-side sync check against `.claude/skills/code-review/SKILL.md` per the four canonical items — (1) workflow-machinery file-set matches SKILL.md Step 5a (line 120); (2) per-agent file-pattern triggers match Step 5b table (lines 156-161) verbatim, especially `review-workflow-context-budget` (always launched, not gated on a sub-pattern); (3) three override cases match Step 5d bullets 1-3 (lines 182-184); (4) filtered-dispatch shape on mixed diffs matches Step 6's `IN_SCOPE_FILES` per group (lines 196-198). Output is a deltas-or-all-clear note recorded in the step episode. If any item diverges, repair `review-agent-selection.md` in this step's commit; do not document disagreement (per D8 rationale).
  > **Risk:** low — verification gate. No new code path; any repair lands in the same file Step 1 already edited, with the same justification (mirror SKILL.md). Phase A's evidence certificates C2 / C6 already established the canonical anchors, so divergence at sync-check time is improbable.
