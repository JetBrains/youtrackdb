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
>     | `review-workflow-context-budget` | Always-loaded surface changes — skill/agent `description:` fields, root `CLAUDE.md`, SessionStart hook stdout |
>     | `review-workflow-writing-style` | `.claude/**/*.md`, root `CLAUDE.md`, `docs/adr/**/*.md` |
>   - Add the **baseline-skip override**: when a step's or track's `git diff --name-only` produces only workflow-machinery files (defined as files under `.claude/`, the project root `CLAUDE.md`, or under any `docs/adr/<dir>/_workflow/` path), skip the four baseline code/test agents (`review-code-quality`, `review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness`) — they have nothing meaningful to evaluate in markdown / JSON / shell. The override mirrors `/code-review` SKILL.md Step 5d's first edge-case rule.
>   - Update the **Selection process** section to reference the workflow-review group: step 4 ("Spawn the baseline agents plus any matching conditional agents") becomes "Spawn the baseline agents (subject to the baseline-skip override below) plus any matching conditional agents plus any matching workflow-review agents".
>   - Update the **Examples** section to add a workflow-machinery example: e.g., *"Step rewrites `.claude/workflow/conventions-execution.md` §2.1 with a new track-file template → baseline skipped (workflow-only diff); workflow-review group fires `review-workflow-consistency` + `review-workflow-instruction-completeness` + `review-workflow-writing-style`. 3 agents."*
> - Verify finding-prefix uniqueness against existing prefixes in the file: `CQ`, `BC`, `TB`, `TC`, `CS`, `TY`, `SE`, `PF`, `TX`, `TS`. New prefixes `WC`, `WP`, `WI`, `WH`, `WB`, `WS` are non-colliding — confirm by grep.
> - Read `.claude/workflow/track-code-review.md` §Agent selection and launching — usually no edit needed because the prose routes through `review-agent-selection.md` already; verify and update only if the section names the agent set explicitly.
> - Read `.claude/workflow/step-implementation.md` §Per-Step Orchestration Loop sub-step 4 — same as above. The dispatch routes through `review-agent-selection.md`; verify.
> - Sync-check with `/code-review` SKILL.md Step 5b/5c/5d — read the two files side-by-side, confirm the per-agent triggers and the baseline-skip override agree across both paths. Record any deliberate deviation in this track's `## Decision Log` (per the spec lands in Track 2 — for this Track 1, since the new spec is not in effect yet, capture the deviation in the track episode at Phase C).
>
> **How**:
> - Step 1: rewrite `review-agent-selection.md` — add the Workflow-review agents section, the per-agent trigger table, the baseline-skip override, and the updated Selection process + Examples. Single commit; this is the load-bearing edit.
> - Step 2: verify `track-code-review.md` §Agent selection and `step-implementation.md` sub-step 4 don't name the agent set explicitly. If they do, update; otherwise no edit. Commit only if edits land.
> - Step 3: side-by-side sanity check with `/code-review` SKILL.md — read both, run a grep for each new agent name + finding prefix, confirm both selection paths route the same agents on the same triggers. No code edit — output is a confirmation note in the step episode.
>
> **Constraints**:
> - **In-scope files**: `.claude/workflow/review-agent-selection.md` (load-bearing edit); read-only verification of `.claude/workflow/track-code-review.md`, `.claude/workflow/step-implementation.md`, and `.claude/skills/code-review/SKILL.md`.
> - **Out-of-scope**: the `/code-review` SKILL itself (it already has the right triage); the six workflow-review agent definitions under `.claude/agents/review-workflow-*.md` (already exist with the right shape); the spec / writers / readers work (Tracks 2–4).
> - **No agent definition changes**: the six workflow-review agents already exist. Their frontmatter, descriptions, and review rubrics are not edited by this track.
> - **No new finding prefixes invented**: the six `W*` prefixes proposed here must not collide with existing ones. If a collision is found at the verification step, rename the colliding new prefix before committing.
> - **Old-format compatibility**: this track edits `review-agent-selection.md`, which is read by Phase B/C of every active branch's `/execute-tracks` session. Edits MUST be backward-compatible — adding a new group + override is additive; the existing baseline + conditional logic is unchanged. In-flight branches' Phase C reviews on Java code keep working identically.
>
> **Interactions**:
> - **All subsequent tracks depend on this one** for Phase C dispatch correctness. Phase C of Tracks 2, 3, 4 will dispatch the six workflow-review agents because of this track's edit.
> - **This track's own Phase C review** dispatches the new workflow-review agents — `review-agent-selection.md` is a `.claude/workflow/*.md` file, matching `review-workflow-instruction-completeness`, `review-workflow-consistency`, and `review-workflow-writing-style`. The baseline-skip override applies (workflow-only diff). 3-4 agents fire.
> - No dependency on the directory rename (Track 2), writer changes (Track 3), or reader sweeps (Track 4). `review-agent-selection.md` is touched only by this track.
> - The `/code-review` standalone skill is not modified — sync-check confirms the two paths agree, but the standalone path is not the in-workflow path that Phase B/C uses.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
