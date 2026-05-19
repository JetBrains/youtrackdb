# Track 5: Pointers in orchestrator files (Tier-B subset for chat-scale prose)

## Purpose / Big Picture
After this track lands, the top-level orchestrator file, the three user-invocable skill bodies, and the five mid-loop protocol files all name the Tier-B subset of house-style as the rule set for chat-scale prose, with structural rules explicitly exempted.

<!-- Reserved for Move 2. -->

Adds Tier-B subset pointer to `workflow.md` (top-level orchestrator), the three top-level `SKILL.md` files (`create-plan`, `execute-tracks`, `review-plan`), and the five mid-loop protocols (`mid-phase-handoff.md`, `inline-replanning.md`, `review-mode.md`, `review-iteration.md`, `design-decision-escalation.md`). Pointer wording explicitly exempts structural rules (BLUF lead, ≤200-word section cap, document-shape) from chat-scale prose.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Empty at Phase 1. -->

## Decision Log
<!-- Empty at Phase 1. -->

<!-- Reserved for Move 1. -->

## Outcomes & Retrospective
<!-- Empty at Phase 1. -->

## Context and Orientation

The orchestrator's prose surfaces are chat replies — short status updates, inline-replanning summaries, decision-escalation prompts, review-mode loop turns. They are not durable git-tracked artifacts; they land in the conversation context only.

The YTDB-837 description identifies the right rule subset:

> Pointer wording: *"User-facing prose follows the AI-tell subset of house-style — banned vocabulary, no negative parallelism, em-dash discipline. Structural rules (BLUF lead, section length caps) don't apply to chat replies."*

This wording is the template. Each file gets one bullet or paragraph (whichever fits the file's existing prose shape) near the top.

Files in scope:

- `.claude/workflow/workflow.md` — top-level orchestrator file, loaded at every `/execute-tracks` startup. Tier-B pointer near the top, before the per-phase sections.
- `.claude/skills/create-plan/SKILL.md` — Phase 0 + Phase 1 driver. Tier-B pointer in the skill body (after frontmatter).
- `.claude/skills/execute-tracks/SKILL.md` — Phase 3 driver. Tier-B pointer.
- `.claude/skills/review-plan/SKILL.md` — Manual re-run of plan review. Tier-B pointer.
- `.claude/workflow/mid-phase-handoff.md` — Pause-and-resume protocol; the orchestrator writes handoff prose to disk under `_workflow/handoff-*.md`. Tier-B pointer (the handoff body is borderline Tier-A but the protocol fires from chat-scale orchestrator state, so Tier-B is the right register for the prose the orchestrator generates).
- `.claude/workflow/inline-replanning.md` — Mid-loop replanning protocol; orchestrator generates replan summary prose. Tier-B pointer.
- `.claude/workflow/review-mode.md` — Conversational review-mode loop; orchestrator generates per-action prose. Tier-B pointer.
- `.claude/workflow/review-iteration.md` — Review-iteration protocol; orchestrator generates iteration-summary prose. Tier-B pointer.
- `.claude/workflow/design-decision-escalation.md` — Escalation prompts the orchestrator generates for the user. Tier-B pointer.

The Tier-B-subset reference (per D3) names the four source sections explicitly:

> Banned vocabulary (`.claude/output-styles/house-style.md § Banned vocabulary`); Banned sentence patterns (`§ Banned sentence patterns`); Banned analysis patterns (`§ Banned analysis patterns`); Em-dash discipline (`§ Em-dash discipline`).

The pointer explicitly exempts structural rules (BLUF lead at `§ BLUF lead`, ≤200-word section cap at `§ Structural rules`, document-shape rules at `§ Document-shape rules`).

## Plan of Work

The track delivers in two steps, by file group:

Step 1 — Add the Tier-B pointer to `workflow.md` and the three `SKILL.md` files. These four files are the user-visible entry points to the workflow; the pointer reaches every session.

Step 2 — Add the Tier-B pointer to the five mid-loop protocol files. These fire during specific situations (pause, replan, review, escalate) and their prose contributions are bounded.

Ordering constraints: Track 1 must complete first (the pointer cites the conventions.md anchor). Tracks 2, 3, 4 are independent.

Invariants to preserve: every file's existing frontmatter and § headings stay intact. The pointer never lands above frontmatter. No file gets more than one pointer.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Empty at Phase 1. -->

## Validation and Acceptance

- `workflow.md`, the three `SKILL.md` files, and the five mid-loop protocols each carry one Tier-B subset pointer (verified by `grep -l 'house-style' <files>` returning all nine).
- The pointer wording names the four source sections explicitly (D3 compliance).
- The pointer wording exempts structural rules from chat-scale prose explicitly.
- YTDB-837 acceptance bullet 5 holds: "Orchestrator files name the Tier-B subset and explicitly exempt structural rules."

<!-- Phase A placeholder. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**

Top-level orchestrator + skills:
- `.claude/workflow/workflow.md`
- `.claude/skills/create-plan/SKILL.md`
- `.claude/skills/execute-tracks/SKILL.md`
- `.claude/skills/review-plan/SKILL.md`

Mid-loop protocols:
- `.claude/workflow/mid-phase-handoff.md`
- `.claude/workflow/inline-replanning.md`
- `.claude/workflow/review-mode.md`
- `.claude/workflow/review-iteration.md`
- `.claude/workflow/design-decision-escalation.md`

**Out-of-scope files:**
- `.claude/workflow/conventions.md`, `conventions-execution.md`, `planning.md`, `research.md`, `implementation-review.md`, `track-review.md`, `track-code-review.md`, `structural-review.md`, `risk-tagging.md`, `step-implementation-recovery.md`, `track-skip.md`, `self-improvement-reflection.md`, `ephemeral-identifier-rule.md`, `branch-divergence-check.md`, `defensive-push-check.md`, `code-review-protocol.md`, `finding-synthesis-recipe.md`, `plan-slim-rendering.md`, `review-agent-selection.md`, `design-document-rules.md` — these are either rule files (Tier-A coverage handled implicitly by their being Markdown editable through the hook), or covered by other tracks, or carry no orchestrator chat-scale prose.

**Inter-track dependencies:**
- **Upstream**: Track 1 (cross-references the new conventions.md section heading).
- **Downstream**: none.

**Compatibility requirements:**
- Existing `SKILL.md` `name:` and `description:` fields stay unchanged (interface contracts read by the skill loader).
- `workflow.md` is loaded at every `/execute-tracks` startup; the pointer is additive context, not a behavior change.

**Library / function signatures relevant to this track:** none — pure documentation edits.
