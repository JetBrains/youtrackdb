<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 5: Bootstrap block + agent files refs sweep + migration verification

## Purpose / Big Picture

After this track lands, 38 system-prompt files carry the bootstrap protocol block at the top, every outgoing workflow-doc reference in the 20 agent files and in SKILL.md startup read-lists carries the `:roles:phases` suffix, and `/migrate-workflow` is confirmed to replay cleanly on two active branches.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Insert the bootstrap protocol block (~30 lines, per `design.md §"Bootstrap protocol for agent system prompts"`) at the top of 38 system prompts: 7 SKILL.md files, 11 `.claude/workflow/prompts/*.md`, and 20 `.claude/agents/*.md`. Apply the `name:roles:phases` cross-reference suffix to outgoing workflow-doc refs in the 20 agent files and to SKILL.md startup read-lists. Then verify `/migrate-workflow` replays cleanly onto at least two active branches — expected to be a stamp-rewrite-only normalization since this plan does not change `_workflow/**` artifact shape.

`CLAUDE.md` is intentionally out of scope. CLAUDE.md is a general-purpose project guide, not workflow-specific; the file-level cross-ref filter does not apply. See the plan's Non-Goals entry.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

The 38 system-prompt files split into three categories.

**Workflow-referencing SKILL.md (7 files):**
- `.claude/skills/create-plan/SKILL.md` (17 workflow-doc refs)
- `.claude/skills/execute-tracks/SKILL.md` (17 refs)
- `.claude/skills/edit-design/SKILL.md` (14 refs)
- `.claude/skills/migrate-workflow/SKILL.md` (18 refs)
- `.claude/skills/review-workflow-pr/SKILL.md` (8 refs)
- `.claude/skills/review-plan/SKILL.md` (7 refs)
- `.claude/skills/code-review/SKILL.md` (3 refs; borderline)

These carry the bootstrap block AND the `:roles:phases` suffix on every workflow-doc ref in their startup read-list and body. They are also annotated by Track 4 (TOC + per-section annotations); Track 5 leaves the TOC and annotations alone and only adds the bootstrap + cross-ref suffix.

**Workflow internal prompts (11 files under `.claude/workflow/prompts/`):**
adversarial-review.md, consistency-gate-verification.md, consistency-review.md, create-final-design.md, design-review.md, dimensional-review-gate-check.md, review-gate-verification.md, risk-review.md, structural-gate-verification.md, structural-review.md, technical-review.md.

These prompts are spawned as sub-agent system prompts during Phase 2 (consistency + structural reviews), Phase A (technical / risk / adversarial / design reviews), Phase B (dimensional reviews), Phase C (track-level dimensional reviews), and Phase 4 (create-final-design). Each spawned sub-agent is fresh — no shared context with the parent — so the bootstrap block must live in the prompt content itself. Track 4 already adds TOC + annotations to these files; Track 5 adds the bootstrap block.

**Agent files (20 files under `.claude/agents/`):**
- 6 workflow-review agents: `review-workflow-consistency.md` (8 refs), `review-workflow-context-budget.md` (4), `review-workflow-hook-safety.md` (1), `review-workflow-instruction-completeness.md` (3), `review-workflow-prompt-design.md` (3), `review-workflow-writing-style.md` (1).
- 10 dim agents: `review-bugs-concurrency.md`, `review-code-quality.md`, `review-crash-safety.md`, `review-performance.md`, `review-security.md`, `review-test-behavior.md`, `review-test-completeness.md`, `review-test-concurrency.md`, `review-test-crash-safety.md`, `review-test-structure.md` (1 ref each, mostly boilerplate).
- 4 misc: `code-reviewer.md` (1), `dr-audit.md` (7), `pr-reviewer.md` (1), `test-quality-reviewer.md` (1).

These carry the bootstrap block AND the `:roles:phases` suffix on every outgoing workflow-doc ref. They are not annotated by Track 4 (D6: refs-only, no per-section annotations).

Migration verification per `design.md §"Migration replay semantics"`: pick two active branches with `_workflow/**` artifacts, rebase each onto develop after this plan lands, run `/migrate-workflow`, confirm clean completion. Candidates per memory file: `ytdb-612-rollback-log`, `read-cache-concurrency-bug`, `ytdb-614-property-map`, `failed-wal-recovery`. Two picks resolved at Track 5 start.

### Files in scope

- 7 SKILL.md files — staged copies under `_workflow/staged-workflow/.claude/skills/`.
- 11 prompts — staged copies under `_workflow/staged-workflow/.claude/workflow/prompts/`.
- 20 agent files — modified live (agents are not staged per §1.7).

### Files out of scope

- `CLAUDE.md` — general-purpose project guide, not workflow-specific. The cross-ref convention and bootstrap block do not apply.
- Non-workflow skill files (e.g., `ai-tells`, `run-jmh-benchmarks-hetzner`) — those skills do not Read files under `.claude/workflow/` or `.claude/skills/` at runtime.
- Per-section annotations on agent files — D6 decided refs-only.
- Workflow-doc content — Track 4 territory.
- Scripts — Tracks 2, 3.
- Schema — Track 1.

## Plan of Work

The track lands in six steps:

1. **Bootstrap block insertion — SKILL.md (7 files).** Insert the canonical bootstrap block at the top of each workflow-referencing SKILL.md, right after the YAML frontmatter and before the H1 / main body. Per-file substitutions fill the role and phase placeholders. Routed through `_workflow/staged-workflow/.claude/skills/` per §1.7.
2. **Bootstrap block insertion — workflow prompts (11 files).** Insert the bootstrap block at the top of each prompt file under `.claude/workflow/prompts/`. Each prompt's role is fixed (e.g., `reviewer-technical` for technical-review.md), so the substitution is mechanical. Routed through `_workflow/staged-workflow/.claude/workflow/prompts/`.
3. **Bootstrap block insertion + refs sweep — agent files (20 files).** Insert the bootstrap block and apply the `:roles:phases` suffix to every outgoing workflow-doc ref in each agent file. Agent files are not staged (per §1.7); modified live. The role tag matches the agent's calling role (e.g., `reviewer-dim-step` for dim-step agents); the phase tag matches when the agent runs (e.g., `phases=3B` for step-level dim agents).
4. **SKILL.md startup read-list suffix sweep.** Apply the `:roles:phases` suffix to every workflow-doc reference in the 7 SKILL.md files' startup read-lists. The suffix is computed per ref site from the calling skill's role and phase set. Run `workflow-reindex.py --check` to verify suffix completeness.
5. **Migration verification — branch A.** Pick one of the candidate branches, rebase onto develop after this plan's previous tracks land, run `/migrate-workflow`. Verify: drift gate fires; bootstrap-prompt path matches expectations (no unstamped artifacts unless the branch is pre-stamp era); migration completes with one stamp-rewrite commit or silent skip. Subsequent `/execute-tracks` startup runs clean.
6. **Migration verification — branch B + final validation.** Same `/migrate-workflow` procedure on a second branch. Picking branches with different shapes (one with active in-flight track, one with completed plan) covers more ground than two similar branches. Then run `workflow-reindex.py --check` across the full file set; confirm zero findings; confirm `measure-read-share.py` can be invoked from this worktree (smoke test).

Phase A may split this track if step count grows past 7 — natural split points are the three insertion categories (SKILL / prompts / agents) versus the two verification branches.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

After this track lands:

- Every in-scope system-prompt file (38 total: 7 SKILL.md, 11 prompts, 20 agents) carries the bootstrap block at the top, identifiable by the literal heading `## Reading workflow files (TOC protocol)`.
- Every outgoing workflow-doc reference in each of the 20 agent files carries the `:roles:phases` suffix.
- Every workflow-doc reference in the 7 SKILL.md startup read-lists carries the `:roles:phases` suffix.
- `python3 .claude/scripts/workflow-reindex.py --check` exits 0 against the full file set including SKILL.md, prompts, and agent files.
- Two active branches (named in the track-completion episode) have been rebased onto post-plan develop and `/migrate-workflow` has completed cleanly on each.
- `python3 .claude/scripts/measure-read-share.py` runs cleanly from this worktree as a smoke test (output format inspection; no need to commit the output yet — Phase 4 does that).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

### In-scope file set

- 7 SKILL.md files under `.claude/skills/` — bootstrap + startup-read-list suffix (staged).
- 11 prompts under `.claude/workflow/prompts/` — bootstrap only (staged).
- 20 agent files under `.claude/agents/` — bootstrap + refs sweep (live).

### Out-of-scope

- `CLAUDE.md` — general-purpose, not workflow-specific.
- Non-workflow skill files — no Reads of workflow files at runtime.
- Per-section annotations on any file — Track 4 covered all annotated files.
- Workflow-doc content — Track 4 / Track 1.
- Scripts — Tracks 2, 3.

### Inter-track dependencies

- **Depends on Track 1.** Bootstrap block content names role/phase tokens drawn from the §1.8 locked enums.
- **Depends on Track 2.** The reindex script's rule 7 (bootstrap presence check) and rule 6 (cross-file ref suffix on SKILL.md startup read-lists and agent files, hand-written) enforce this track's output. Rule 8 (in-file ref auto-stamp) also fires on this track's edits but is mechanical: any in-file refs the bootstrap insertion or refs sweep introduces get auto-stamped by `workflow-reindex.py --write` before commit.
- **Depends on Track 4.** Per-section TOC and annotations on the 7 SKILL.md and 11 prompts must exist before the bootstrap block points at the TOC-aware reading protocol. The agent-side `:roles:phases` suffix also points at annotated targets.
- **Closes the acceptance criterion.** The two-branch migration verification is the final acceptance gate from the issue.

### Library/function signatures touched

- None. This track is bootstrap-block insertion and text substitution in Markdown files plus external verification via `/migrate-workflow`.
