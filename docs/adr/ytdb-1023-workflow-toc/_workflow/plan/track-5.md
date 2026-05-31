<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 5: Bootstrap block + agent files refs sweep + migration verification

## Purpose / Big Picture

After this track lands, the reindex script validates the 20 live agent files (D17), 38 system-prompt files carry the bootstrap protocol block at their TOC anchor, every outgoing workflow-doc reference in the 20 agent files and in the 7 SKILL.md startup read-lists carries the `:roles:phases` suffix, and the post-merge two-branch `/migrate-workflow` acceptance procedure is documented with its in-branch premise check and smokes green.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

First land a HIGH-risk `workflow-reindex.py` agent-scope fix (D17) so rules 6 and 7 validate the 20 live agent files (the script's discovery scope omitted live `.claude/agents/**` — Phase A reviews found rules 6/7 never fired on agents). Then insert the bootstrap protocol block (~30 lines, body sourced from `design.md §"Bootstrap protocol for agent system prompts"`) at the TOC anchor of 38 system prompts (7 SKILL.md, 11 `.claude/workflow/prompts/*.md`, 20 `.claude/agents/*.md`), and apply the `name:roles:phases` cross-file suffix to outgoing workflow-doc refs in the 20 agent files (Track 4's D13 already converted the 7 SKILL.md read-lists — Track 5 verifies and gap-fills). The real two-branch `/migrate-workflow` replay is a post-merge acceptance procedure (D18): it can only run after this plan reaches develop, so Track 5's in-branch verification is a static D7-premise check plus local `--check` and telemetry smokes, and the two-branch replay is documented for the user to run post-merge.

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

These carry the bootstrap block AND the `:roles:phases` suffix on every outgoing workflow-doc ref. They are not annotated by Track 4 (D6: refs-only, no per-section annotations). Phase A review found the reindex script's discovery scope omitted live `.claude/agents/**` (it carried only a dead staged-agents glob), so rules 6 and 7 never fired on agent files; D17's HIGH-risk script-fix step scopes live agents into rules 6 and 7 before the agent sweep runs.

Migration verification per `design.md §"Migration replay semantics"` and D18: the real two-branch `/migrate-workflow` replay can run only after this plan squash-merges to develop. The drift gate ranges `git log` over a candidate branch's own HEAD and never fetches develop, and this plan's §1.8 plus workflow-sha bump reaches develop only at Phase 4 — so a candidate rebased onto current develop would see nothing from this plan. The two-branch replay is therefore a **post-merge acceptance procedure** the user runs in a fresh session on candidate branches rebased onto post-plan develop (candidates per memory: `ytdb-612-rollback-log`, `read-cache-concurrency-bug`, `ytdb-614-property-map`, `failed-wal-recovery`; two picks resolved post-merge, recorded in the Track 5 completion episode and Phase 4 `adr.md`). Track 5's in-branch verification is limited to runnable substitutes: a static D7-premise check (the plan's commits touch `.claude/...` only, never the branch's own `_workflow/**`), an optional local `/migrate-workflow` machinery smoke, the staged-scoped plus live-agent `workflow-reindex.py --check` green, and the `measure-read-share.py` smoke.

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

The track lands in six steps. The reindex agent-scope fix leads because the agent sweep depends on it; the agent refs sweep is split from agent bootstrap insertion because the sweep is the high-risk half.

1. **Reindex agent-scope fix (D17) — HIGH-risk, leads the track.** Scope live `.claude/agents/**/*.md` into `workflow-reindex.py` rules 6 (cross-file ref suffix) and 7 (bootstrap presence) only — never rules 1/2/3/4/5/8, since D6 keeps agents refs-only (no TOC, no per-section annotations, no `_workflow/**` stamp). Add the live-agents discovery path and a per-rule applicability gate so rules 2/3/4 do not demand a TOC on agent files; remove or leave inert the dead staged-agents glob. Regression tests assert a missing bootstrap heading and a non-subset / missing-suffix agent ref each produce findings, and that an agent file is never flagged for a missing TOC. The script edit lands on the live `.claude/scripts/` path (scripts are not staged per the plan Constraints). This is a step-level dimensional-review step.
2. **Bootstrap insertion — SKILL.md (7 files, staged) + read-list suffix verify/gap-fill.** Insert the canonical bootstrap block (body from `design.md §"Bootstrap protocol for agent system prompts"`) at each SKILL's TOC anchor per the three anchor shapes in `conventions.md §1.8(d)` (under-H1 / after-frontmatter / top-of-file — pick by the file's actual shape, not the two-shape "between frontmatter and H1" wording the glossary/I5 still carry). Then verify the startup read-list `:roles:phases` suffixes (Track 4's D13 conversion already swept these) and gap-fill any residual. Routed through `_workflow/staged-workflow/.claude/skills/` per §1.7.
3. **Bootstrap insertion — workflow prompts (11 files, staged).** Insert the bootstrap block at each prompt's TOC anchor. Ten of the eleven prompts are prose-first (no frontmatter, no real H1) → top-of-file anchor per §1.8(d); `design-review.md` is the one H1-bearing prompt → under-H1. Each prompt's role is fixed (e.g., `reviewer-technical` for `technical-review.md`), so the role/phase substitution is mechanical. Routed through `_workflow/staged-workflow/.claude/workflow/prompts/`.
4. **Bootstrap insertion — agent files (20 files, live).** Insert the bootstrap block at each agent file's anchor (frontmatter-then-block, or top-of-file). Agent files are modified live (not staged) per §1.7. Bootstrap only — the refs sweep is Step 5.
5. **Agent refs sweep (20 files, live) — HIGH-risk, depends on Step 1.** Apply the `:roles:phases` suffix to every outgoing workflow-doc ref in each agent file. The role tag matches the agent's calling role (e.g., `reviewer-dim-step` for dim-step agents); the phase tag matches when the agent runs (e.g., `phases=3B`). Authoring rules from Phase A review: use whole-file `name.md:roles:phases` plus a separate backticked `§X.Y` for any sub-section pin (the combined cross-file-with-subsection form fails rule 8 in live prose — the dominant case is the house-style `conventions.md §1.5` header in 19 of 20 agents); do not claim an H4-only token in a whole-file suffix (the rule 6 union is `##`/`###` only); backtick-wrap — not suffix — refs to non-annotatable or out-of-in-scope targets (`.claude/docs/*` such as `architecture.md` / `testing-details.md` / `mcp-steroid/skills.md`, `house-style.md`, `design*.md`, the Phase 4 final artifacts, `CLAUDE.md`, `MEMORY.md`, agent-file-as-target). Run the staged-scoped plus live-agent `workflow-reindex.py --check` (rules 2/3/4/5/6/7/8 green; rule_1 missing-stamp on staged copies is the documented Phase-4 promotion residue, not a defect). This is a step-level dimensional-review step.
6. **In-branch verification + final validation.** Static D7-premise check (the plan's commits touch `.claude/...` only, never the branch's own `_workflow/**`); optional local `/migrate-workflow` machinery smoke; staged-scoped plus live-agent `workflow-reindex.py --check` green over the full in-scope set; `measure-read-share.py` smoke from this worktree. Document the post-merge two-branch acceptance procedure (D18) in the track-completion episode for the user to run after this plan reaches develop.

Phase A may split this track if step count grows past 7 — natural split points are the three bootstrap-insertion categories (SKILL / prompts / agents) versus the script-fix and sweep steps. The real two-branch migration verification is no longer an in-track step (D18: post-merge acceptance procedure).

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

After this track lands (in-branch):

- The reindex agent-scope fix (D17) is in `workflow-reindex.py`: rules 6 and 7 fire on live `.claude/agents/**/*.md`, and no agent file is flagged for a missing TOC (rules 2/3/4 do not apply to agents). Regression tests cover a missing bootstrap heading and a non-subset agent ref.
- Every in-scope system-prompt file (38 total: 7 SKILL.md, 11 prompts, 20 agents) carries the bootstrap block at its TOC anchor, identifiable by the literal heading `## Reading workflow files (TOC protocol)`.
- Every outgoing workflow-doc reference in each of the 20 agent files carries the `:roles:phases` suffix (sub-section pins use a whole-file suffix plus a separate backticked `§X.Y`; non-annotatable targets are backtick-wrapped).
- Every workflow-doc reference in the 7 SKILL.md startup read-lists carries the `:roles:phases` suffix (verified — Track 4's D13 converted most; Track 5 gap-fills).
- The staged-scoped plus live-agent `workflow-reindex.py --check` is green on rules 2/3/4/5/6/7/8 over the in-scope set (staged SKILL / prompts plus live agents). The 49 rule_1 missing-stamp findings on staged copies are documented Phase-4 promotion residue, not defects; an unscoped full-tree `--check` stays RED until Phase 4 by design (the live tree is develop-state).
- The static D7-premise check passes: this plan's commits touch `.claude/...` only and never the branch's own `_workflow/**`, so `/migrate-workflow` replay is a content no-op for this plan.
- `python3 .claude/scripts/measure-read-share.py` runs cleanly from this worktree as a smoke test (output-format inspection; Phase 4 commits the real output).

Post-merge acceptance (D18 — documented procedure, not an in-track commit):

- After this plan squash-merges to develop, the user runs `/migrate-workflow` in a fresh session on two candidate branches rebased onto post-plan develop, confirming clean completion (a stamp-rewrite-only normalization or a silent skip). The two branches and the outcome are recorded in the Track 5 completion episode and Phase 4 `adr.md`.

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

- `.claude/scripts/workflow-reindex.py` — the D17 agent-scope fix (live `.claude/agents/**` into rules 6/7 only). Live path (scripts are not staged).
- 7 SKILL.md files under `.claude/skills/` — bootstrap + startup-read-list suffix verify/gap-fill (staged).
- 11 prompts under `.claude/workflow/prompts/` — bootstrap only (staged).
- 20 agent files under `.claude/agents/` — bootstrap insertion (Step 4) + refs sweep (Step 5), both live.

### Cross-file ref target classes (Step 5 authoring)

- **Suffix** (`:roles:phases`): in-scope `.claude/workflow/**` docs and prompts, and `<dir>/SKILL.md` targets.
- **Backtick-wrap, never suffix** (non-annotatable or out-of-in-scope): `.claude/docs/*` (`architecture.md`, `testing-details.md`, `mcp-steroid/skills.md`), `house-style.md`, `design*.md`, the Phase 4 final artifacts, `CLAUDE.md`, `MEMORY.md`, and any agent-file-as-target.
- **Sub-section pins**: whole-file `name.md:roles:phases` plus a separate backticked `§X.Y` (the combined cross-file-with-subsection form fails rule 8 in live prose); never claim an H4-only token (rule 6 union is `##`/`###` only).

### Out-of-scope

- `CLAUDE.md` — general-purpose, not workflow-specific.
- Non-workflow skill files — no Reads of workflow files at runtime.
- Per-section annotations on any file — Track 4 covered all annotated files.
- Workflow-doc content — Track 4 / Track 1.
- Scripts — Tracks 2, 3, **except** the D17 agent-scope fix to `workflow-reindex.py` that this track lands in Step 1.

### Inter-track dependencies

- **Depends on Track 1.** Bootstrap block content names role/phase tokens drawn from the §1.8 locked enums.
- **Depends on Track 2.** The reindex script's rule 7 (bootstrap presence check) and rule 6 (cross-file ref suffix on SKILL.md startup read-lists and agent files, hand-written) enforce this track's output. For agent files these rules fire only after this track's own D17 fix (Step 1) lands — Track 2's script shipped with the live-agents path out of scope. Rule 8 (in-file ref auto-stamp) also fires on this track's edits but is mechanical: any in-file refs the bootstrap insertion or refs sweep introduces get auto-stamped by `workflow-reindex.py --write` before commit.
- **Depends on Track 4.** Per-section TOC and annotations on the 7 SKILL.md and 11 prompts must exist before the bootstrap block points at the TOC-aware reading protocol. The agent-side `:roles:phases` suffix also points at annotated targets.
- **Intra-track: Step 5 depends on Step 1.** The agent refs sweep (Step 5) needs the D17 agent-scope fix (Step 1) so `--check` validates the suffixes it writes.
- **Sets up the post-merge acceptance procedure.** The real two-branch migration verification is the final acceptance gate from the issue, but it runs post-merge (D18) — this track documents the procedure and verifies the D7 no-op premise in-branch; it does not close the gate in-branch.

### Library/function signatures touched

- `workflow-reindex.py` discovery/validation internals (D17, Step 1): the in-scope-discovery path set and the per-rule applicability gate that scopes live `.claude/agents/**` into rules 6 and 7 only. The exact function names are an implementation detail of Step 1 (the script's discovery and `validate` loop); no public signature changes.
- Otherwise this track is bootstrap-block insertion and text substitution in Markdown files plus the in-branch verification smokes and the documented post-merge `/migrate-workflow` procedure.
