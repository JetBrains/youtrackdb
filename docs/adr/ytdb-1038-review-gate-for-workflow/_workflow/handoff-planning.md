# Handoff: Phase 1 (planning) — staging-aware review machinery

**Paused:** 2026-06-01
**Phase:** 1 (planning, /create-plan)
**Context level at pause:** info — user-requested pause for fresh context (not a context-pressure pause)
**Branch:** ytdb-1038-review-gate-for-workflow
**HEAD:** f97512c02f4dbaaf66c7382397907580fd54391b "[YTDB-1035] Recalibrate context-window monitor thresholds for Opus 4.8 (#1106)"
**Unpushed:** <no upstream — see workflow.md §What to do before ending a session> (the handoff commit sets the upstream)

## What I was investigating

Phase 0 research and the Phase 1 design document are complete. The next
session's only job is to write `implementation-plan.md` plus three
`plan/track-N.md` files from the finished `design.md`, then run create-plan
Step 5 (commit, push, open the draft PR). No codebase re-exploration is
needed; every edit-target file is already enumerated below and in the design.

## Already ruled out

Design decisions settled with the user this session — do not reopen:

- Editing each per-agent trigger glob for staged paths: rejected for one
  path-normalization rule (D1). See design.md §"Selection-side staging awareness".
- Orchestrator hand-injecting the staged-read caveat per review: rejected
  for a static, self-gating caveat in the prompt templates (D2). See
  design.md §"Read-side staging awareness".
- Caveat as a document section, new Phase A prompt files, or a dispatch
  rewrite: rejected. The caveat rides inside the fenced prompt body (D3),
  and the Phase A criteria fix is a marker-gated addendum inside the
  existing technical/risk/adversarial prompts (D4), not new files.

## Most promising lead

The design is the spec. `docs/adr/ytdb-1038-review-gate-for-workflow/_workflow/design.md`
(PASS through edit-design Mutation 1 + Mutation 2 — see design-mutations.md)
defines references D1–D4 and S1–S3 that the plan must DEFINE in its
Architecture Notes, and names every edit-target file. Write the plan from it.

## Open questions

- design.md Overview prerequisite sentence: the Mutation-1 cold-read
  suggested adding one "assumes familiarity with §1.7 staging" line to the
  Overview. Recorded, not applied (below should-fix; Overview at the 40-line
  cap). Leave as-is unless the user revisits.
- None blocking the plan.

## Raw notes / partial findings — the agreed plan to write

**Scope: three issues, one branch.**
- YTDB-1038 (read-side, Critical) — branch namesake.
- YTDB-1032 (selection-side, Normal) — folded in.
- YTDB-1046 (Phase A criteria, Normal) — filed this session; relates-to
  1038 + 1032; tag dev-workflow.

**This plan is workflow-modifying.** It edits `.claude/workflow/**` and
`.claude/skills/**`. The plan's `### Constraints` MUST carry the canonical
marker sentence verbatim (conventions.md §1.7(b), case-sensitive, terminal
period):
`This plan is workflow-modifying: it edits .claude/workflow/** or .claude/skills/**.`
All Phase B edits route through `_workflow/staged-workflow/` per §1.7.

**Decision Records to author in the plan (referenced by design.md):**
- D1 — normalization over glob-editing (selection). Alternatives: extend
  each literal trigger glob in both mirror files vs one prefix-strip rule.
  Chose normalization (DRY, one rule per file; the issue's "cheaper" path).
  Implemented in Track 1.
- D2 — read caveat self-gates on the §1.7(b) marker vs orchestrator
  hand-injection. Chose the static self-gating caveat in the templates
  (removes the per-review manual step; YTDB-1038 acceptance criterion).
  Implemented in Track 2.
- D3 — caveat in the fenced prompt body vs a document section. Chose the
  prompt body (no TOC/annotation churn in the host files). Implemented in Track 2.
- D4 — Phase A prose criteria via a marker-gated addendum in the existing
  technical/risk/adversarial prompts vs new workflow-aware prompt files vs a
  complexity-assessment dispatch swap. Chose the addendum (no new files, no
  dispatch change; same three reviewers self-adapt, mirroring the read
  caveat). Implemented in Track 3.

**Invariants to author in the plan (referenced by design.md):**
- S1 — selection mirror: `review-agent-selection.md` (§Workflow-machinery
  file set / §Per-agent file-pattern triggers / §Workflow-machinery override)
  and `code-review/SKILL.md` Step 5a/5b/5d/6 change in one commit with the
  `<!-- Last sync-checked … -->` date bumped. Enforced by
  review-workflow-consistency at Phase C; no script checks it. Track 1.
- S2 — parallel-block: the canonical context block in `step-implementation.md`
  sub-step 4(a) and its parallel copy in `track-code-review.md` must carry
  the same caveat. Track 2.
- S3 — uniformity: the read caveat reads the same across all nine prompts,
  and the Phase A addendum the same across the three criteria prompts; all
  three fixes key off the single §1.7(b) marker. Tracks 2 + 3.

**Tracks** (write one `plan/track-N.md` per track — the four Phase-1
sections `## Purpose / Big Picture`, `## Context and Orientation`,
`## Plan of Work`, `## Interfaces and Dependencies` — plus a Scope line in
the plan checklist):

- **Track 1 — Selection-side staging awareness (YTDB-1032).**
  `> **Scope:** ~2 steps covering the staged-path normalization rule + mirror sync.`
  In-scope: `.claude/workflow/review-agent-selection.md` (§Workflow-machinery
  override — add the normalization paragraph) and
  `.claude/skills/code-review/SKILL.md` (Step 5d mirror) — both in ONE commit
  per S1, sync-date bumped.
  Precise gap (state exactly, do not repeat the issue's looser wording):
  only `review-workflow-prompt-design`, `-instruction-completeness`, and
  `-hook-safety` miss on staged paths; consistency + context-budget always
  run; writing-style already fires via `docs/adr/**/*.md`.

- **Track 2 — Read-side staged-read caveat (YTDB-1038).**
  `> **Scope:** ~3 steps covering the caveat across nine review/gate prompts.`
  In-scope (nine prompt sites): the two canonical context blocks —
  `step-implementation.md` sub-step 4(a) + `track-code-review.md` (parallel
  copy, S2); `dimensional-review-gate-check.md`; the Phase 2 plan-review
  prompts `consistency-review.md` + `structural-review.md`; the four Phase A
  prompts `technical-review.md` + `risk-review.md` + `adversarial-review.md`
  + `review-gate-verification.md`. Caveat: marker-gated, §1.7(d) precedence
  (staged when present, else live); a short block inside the fenced prompt
  body for the two context blocks, a one-line mirror for the seven others.
  Self-gating relies on the slim plan retaining `### Constraints` — VERIFIED
  this session (render-slim-plan.py keeps the `pre` block verbatim).

- **Track 3 — Phase A criteria addendum (YTDB-1046).** `> **Depends on:** Track 2`.
  `> **Scope:** ~2 steps covering the workflow-machinery criteria addendum in technical/risk/adversarial.`
  In-scope: `technical-review.md`, `risk-review.md`, `adversarial-review.md`
  (the three criteria reviewers; NOT `review-gate-verification.md`, which is
  criteria-agnostic and gets only the Track 2 caveat). Addendum: marker-gated;
  verify named refs as file paths + §-anchors via grep/Read, not Java
  `findClass`; swap WAL/crash/migration/hot-caller criteria for
  coherence/non-contradiction/instruction-completeness/prompt-design/
  context-budget/dependent-prompt-breakage. Depends on Track 2 because the
  addendum references the read caveat in the same three files.

**Non-Goals (author in the plan):**
- This branch does not fix its own Phase A/C review (self-application
  carve-out, §1.7(h)); the orchestrator hand-injects during this branch.
- No new Phase A prompt files and no change to the `track-review.md`
  complexity-assessment dispatch (D4).
- `workflow-reindex.py --check` gains no mirror check and no staged-copy
  awareness (adjacent gap, noted in design.md edge cases).
- Does not fix the create-plan SKILL design-vs-plan ordering the user
  flagged (separate PR).

**Component Map:** reuse design.md §"Class Design" topology (selection mirror
pair; the three prompt-layer groups; NORM / CAVEAT / ADD nodes gated by the
marker). Intent bullets ≤5 lines each.

**Budgets / links:** plan ≤ ~1,500 lines; DR ≤30 lines; invariant ≤5;
integration bullet ≤3. Link to the design via
`**Full design**: design.md §"<section>"` rather than duplicating prose.

**Stamp:** all plan artifacts reuse
WORKFLOW_SHA = f97512c02f4dbaaf66c7382397907580fd54391b on line 1. design.md
already carries it.

## Resume notes

- Do NOT re-explore the codebase: research is complete; all edit-target
  files and their sections are enumerated above and in design.md.
- Do NOT re-create or re-review design.md: it PASSED edit-design Mutation 1
  (phase1-creation) + Mutation 2 (structural-rewrite); see design-mutations.md.
  Treat it as the frozen spec for the plan.
- Do NOT re-file issues: YTDB-1046 is filed and linked.
- Next action on resume: read `planning.md` + `design-document-rules.md`
  (the deferred create-plan Step 4 reads), then write `implementation-plan.md`
  (Goals; Constraints WITH the §1.7(b) marker; Architecture Notes carrying
  D1–D4 + S1–S3 + the Component Map + Non-Goals; the three-track checklist
  with Scope lines and Track 3's `Depends on: Track 2`) and the three
  `plan/track-N.md` files, all stamped f97512c02f…, then run create-plan
  Step 5 (commit; push; ask for the issue prefix → answer `YTDB-1038`; open
  the draft PR with a Plan section listing all three issues).
- create-plan startup detects THIS handoff at Step 1a (after the Step 1.5
  drift gate) and routes here before any state evaluation.
