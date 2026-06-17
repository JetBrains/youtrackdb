# Running the YouTrackDB development workflow — table of contents

The book runs to 16 chapters across 8 parts. It takes a new engineer from "I have never run this project's workflow" to "I can pick a tier, write a plan that passes review, decompose a track, drive the implement-test-commit loop, and read a review report." This file carries the chapter map and the cross-reference matrix that an evolution run reads to find which chapters a source change touches.

The table of contents is a living artifact owned by the book. A run adds, reorders, or splits chapters as the source workflow under `.claude/workflow/`, `.claude/skills/`, and `.claude/agents/` changes, and updates the cross-reference matrix in the same pass so the matrix never drifts from the chapters it indexes. See the START prompt in [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) Step 2.

## Overview

The arc builds one concept at a time. Part I orients the reader and runs a single small change end to end at low altitude, before any phase is opened in depth. Part II teaches the tier gate, so the reader knows which later chapters apply to their change. Parts III through VIII open the phases in order: research and design, the phase machine that drives execution, plan review, the per-track implement-and-review loop, the dimensional review agents, and the closing artifacts plus the operational concerns (mid-flight replanning, drift and migration, the house style). A reader who reads in order never meets an undefined term: each name is earned by the job it does before the name appears.

The concrete-before-abstract rule from [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md) governs every chapter. Chapter 2 walks a minimal change through the whole workflow before Chapter 3 names the tier gate; the tier gate is taught before the full-tier machinery; the review agents and the drift machinery come last, after the reader has the track and step structure in their head.

## Chapter map

Each chapter is one teaching moment. The per-chapter brief names the teaching goal, what earlier chapter it builds on, the mental models the reader leaves with, and the source files the chapter draws from. Three named figures are placed where the prose first leans on them: `fig-tier-gate` in Chapter 3, `fig-phase-state-machine` in Chapter 7, `fig-track-step-episode` in Chapter 10. Each is an inline Mermaid diagram like every other figure in the book; the convention is in [`../../workflow-book-builder/DIAGRAMS.md`](../../workflow-book-builder/DIAGRAMS.md).

- **Part I — Orientation.** What the workflow is, and the shape of one run, before any phase is opened in depth.

  - **Chapter 1 — The workflow at a glance.** Teach the problem the workflow solves and its five-phase shape, the skills that drive each phase, and the rule that one session handles one phase. Builds on nothing; it is the entry point. The reader leaves with two models: a change moves through research, planning, plan review, execution, and final artifacts; and each phase runs in its own session so context never bleeds across boundaries.
    Draws from: `.claude/workflow/workflow.md`, `.claude/workflow/conventions.md` (overview and glossary), `.claude/skills/create-plan/SKILL.md` and `.claude/skills/execute-tracks/SKILL.md` (entry-point descriptions).

  - **Chapter 2 — A minimal change from start to finish.** Walk one small change end to end at low altitude: request, a short plan, implement-test-commit, review, merge. Builds on Chapter 1. The reader leaves with the shape of a whole run in their head, so the later deep dives have somewhere to attach.
    Draws from: `.claude/workflow/workflow.md`, `.claude/skills/create-plan/SKILL.md`, `.claude/skills/execute-tracks/SKILL.md`, `.claude/workflow/commit-conventions.md`.

- **Part II — Sizing the change.** How much of the workflow a change needs, decided before any of it runs.

  - **Chapter 3 — Tiers and the tier gate.** Teach the two gate questions (is there a design question, and what is the change scope) and how their answers route a change to the `full`, `lite`, or `minimal` tier, with what each tier sheds. Builds on Chapter 2. The reader leaves able to place their own change in a tier and predict which later chapters apply. Carries `fig-tier-gate` (the decision tree with per-tier leaves).
    Draws from: `.claude/workflow/planning.md`, `.claude/workflow/conventions.md` (tier sections), `.claude/workflow/conventions-execution.md` (complexity tiers), `.claude/skills/create-plan/SKILL.md`.

- **Part III — Research and design (Phases 0 and 1).** Turning a request into a frozen design and a derived plan.

  - **Chapter 4 — Phase 0: research before you plan.** Teach the research log, the interactive exploration that fills it, and why the log stays opaque to the user during Phase 0. Builds on Chapters 1 and 3. The reader leaves understanding that planning rests on a recorded research pass, not on a first guess.
    Draws from: `.claude/workflow/research.md`, `.claude/skills/create-plan/SKILL.md`, `.claude/workflow/conventions.md` (research-log entry).

  - **Chapter 5 — Phase 1: the design document.** Teach the design-first rule, the `edit-design` mutation discipline that gates every change to `design.md`, the freeze, and when a design decision escalates to the user. Builds on Chapter 4. The reader leaves understanding that the design is authored and reviewed before any plan exists, and frozen before the plan derives from it.
    Draws from: `.claude/workflow/planning.md` (design document), `.claude/workflow/design-document-rules.md`, `.claude/skills/edit-design/SKILL.md`, `.claude/workflow/design-decision-escalation.md`, `.claude/workflow/prompts/design-review.md`, `.claude/workflow/prompts/adversarial-review.md`.

  - **Chapter 6 — Phase 1: from a frozen design to a plan and tracks.** Teach Step 4b derivation, the plan as a derived mirror of the tracks, and the decomposition of work into dependency-ordered tracks and steps. Builds on Chapter 5. The reader leaves understanding the plan-and-track structure and why the plan is derived rather than authored directly.
    Draws from: `.claude/workflow/planning.md`, `.claude/skills/create-plan/SKILL.md`, `.claude/workflow/conventions.md` (plan structure), `.claude/workflow/conventions-execution.md` (decomposition rules), `.claude/workflow/plan-slim-rendering.md`.

- **Part IV — The phase machine.** The state model that drives execution and survives a cleared session.

  - **Chapter 7 — Phases, sessions, and the phase ledger.** Teach the one-session-per-phase rule, the phase ledger as the resume backbone, the startup auto-resume that reads it, and the difference between the numeric phases (0/1/2/3/4) and the per-track sub-phases (A/B/C). Builds on Chapters 1 and 6. The reader leaves with the model of how the workflow resumes itself after a session boundary. Carries `fig-phase-state-machine` (phases, gates, and the ESCALATE loop-back edges).
    Draws from: `.claude/workflow/workflow.md`, `.claude/skills/execute-tracks/SKILL.md`, `.claude/workflow/conventions.md` (phase-ledger entry), `.claude/workflow/mid-phase-handoff.md`.

- **Part V — Plan review (Phase 2).** The gate every plan clears before any code is written.

  - **Chapter 8 — Phase 2: reviewing the plan before any code.** Teach the two-step plan review (consistency, then structural), the autonomous classifier that auto-fixes mechanical findings and escalates only design decisions, and the `/review-plan` re-run. Builds on Chapters 6 and 7. The reader leaves understanding that a plan is validated against the design, the code, and itself before execution starts.
    Draws from: `.claude/workflow/implementation-review.md`, `.claude/workflow/structural-review.md`, `.claude/workflow/prompts/consistency-review.md`, `.claude/workflow/prompts/structural-review.md`, `.claude/skills/review-plan/SKILL.md`, `.claude/workflow/prompts/consistency-gate-verification.md`, `.claude/workflow/prompts/structural-gate-verification.md`.

- **Part VI — Executing a track (Phase 3).** The per-track loop: review and decompose, then implement, test, and commit.

  - **Chapter 9 — Phase A: pre-flight, review, and decomposition into steps.** Teach the track pre-flight gate (strategy assessment plus track summary), the technical, risk, and adversarial reviews of a track, decomposition into steps, and the per-step risk tag. Builds on Chapter 7. The reader leaves understanding how a track is checked and broken into implementable steps.
    Draws from: `.claude/workflow/track-review.md`, `.claude/workflow/risk-tagging.md`, `.claude/workflow/prompts/technical-review.md`, `.claude/workflow/prompts/risk-review.md`, `.claude/workflow/prompts/adversarial-review.md`, `.claude/workflow/review-mode.md`, `.claude/workflow/conventions-execution.md` (decomposition).

  - **Chapter 10 — Phase B: the implement-test-commit loop.** Teach step implementation, the implementer sub-agent the orchestrator delegates to, the per-step implement-test-commit loop, and the episode each step writes. Builds on Chapter 9. The reader leaves with the core working loop and the episode as its durable record. Carries `fig-track-step-episode` (plan to track to step to episode, with cross-links).
    Draws from: `.claude/workflow/step-implementation.md`, `.claude/workflow/implementer-rules.md`, `.claude/workflow/episode-format-reference.md`, `.claude/workflow/step-implementation-recovery.md`, `.claude/workflow/commit-conventions.md`, `.claude/workflow/code-review-protocol.md`.

- **Part VII — Reviews.** How changed code is reviewed, and how a track closes.

  - **Chapter 11 — Reviewing changed code: the dimensional review agents.** Teach how a review fans out across dimensions, the triage-based selection that picks which agents run, the code, test, and workflow agent families, the finding severities, the review iteration loop, and finding synthesis. Builds on Chapter 10. The reader leaves understanding the review mechanism used at both step and track scope.
    Draws from: `.claude/skills/code-review/SKILL.md`, `.claude/workflow/review-agent-selection.md`, `.claude/workflow/review-iteration.md`, `.claude/workflow/finding-synthesis-recipe.md`, `.claude/agents/review-*.md` (the dimensional agent roster), `.claude/workflow/code-review-protocol.md`, `.claude/workflow/prompts/dimensional-review-gate-check.md`.

  - **Chapter 12 — Phase C: track-level code review and track completion.** Teach the dimensional review run at track scope, the review-mode approval loop, fixes applied through a fresh implementer, the completion episode, and closing the track. Builds on Chapter 11. The reader leaves able to read a track-level review report and understand how a track is signed off.
    Draws from: `.claude/workflow/track-code-review.md`, `.claude/workflow/review-mode.md`, `.claude/workflow/review-iteration.md`, `.claude/workflow/implementer-rules.md` (track-level fixes), `.claude/workflow/commit-conventions.md`, `.claude/workflow/episode-format-reference.md`.

- **Part VIII — Closing out and operations.** The durable artifacts, mid-flight changes, drift, and the house style.

  - **Chapter 13 — Phase 4: final artifacts and merge.** Teach the per-tier durable artifacts, the adversarial-verdict fold into the durable carrier, the promotion and cleanup commits, and the staging that keeps a workflow-modifying branch off the live workflow until promotion. Builds on Chapters 3 and 12. The reader leaves understanding what survives the merge and why.
    Draws from: `.claude/workflow/prompts/create-final-design.md`, `.claude/workflow/workflow.md` (final artifacts), `.claude/workflow/conventions.md` (§1.7 staging), `.claude/workflow/branch-divergence-check.md`.

  - **Chapter 14 — When things change mid-flight.** Teach inline replanning (the ESCALATE back-edge), mid-phase handoffs, step failure and the two-failure rule, and context-window management. Builds on Chapters 9 through 12. The reader leaves knowing how the workflow handles a plan that turns out wrong, a session that runs low on context, or a step that fails twice.
    Draws from: `.claude/workflow/inline-replanning.md`, `.claude/workflow/mid-phase-handoff.md`, `.claude/workflow/step-implementation-recovery.md`, `.claude/workflow/workflow.md` (context and failure handling), `.claude/workflow/track-skip.md`.

  - **Chapter 15 — Keeping a branch current: drift and migration.** Teach the workflow-SHA stamp, drift detection at startup, the branch-divergence gate, and `/migrate-workflow`. Builds on Chapters 7 and 13. The reader leaves understanding how a long-lived branch stays current with a workflow that keeps changing under it.
    Draws from: `.claude/workflow/workflow-drift-check.md`, `.claude/workflow/branch-divergence-check.md`, `.claude/skills/migrate-workflow/SKILL.md`, `.claude/workflow/conventions.md` (§1.6 stamps), `.claude/workflow/defensive-push-check.md`.

  - **Chapter 16 — Writing for the workflow and improving it.** Teach the house style applied to the artifacts an engineer writes (the §1.5 tier mapping), the AI-tell subset, the writing-style review agent, and the self-improvement reflection that files YouTrack issues at session end. Builds on every earlier chapter. The reader leaves knowing the writing standard the workflow enforces and the feedback loop that improves the workflow itself.
    Draws from: `.claude/workflow/conventions.md` (§1.5), `.claude/output-styles/house-style.md`, `.claude/skills/ai-tells/SKILL.md`, `.claude/workflow/self-improvement-reflection.md`, `.claude/agents/review-workflow-writing-style.md`, `.claude/skills/readability-feedback/SKILL.md`.

The book is deliberately not exhaustive. The skills and agents outside the change workflow a new engineer runs are out of scope for this edition: `analyze-changes`, `create-presentation`, `fix-ci-failure`, the `profile-*` and `run-jmh-benchmarks-hetzner` skills, `review-docs`, `review-workflow-pr`, and the `dr-audit`, `pr-reviewer`, and `code-reviewer` wrapper agents. They are recorded here as out of scope so an evolution run does not mistake their absence for a gap.

## Cross-reference matrix

The matrix maps each chapter to the source files it draws from, so an evolution run can find which chapters a source change touches. When a run edits the chapter map, it updates this matrix in the same pass.

| Chapter | Primary sources | Secondary sources |
|---|---|---|
| 1 — The workflow at a glance | `workflow.md` | `conventions.md`, `create-plan/SKILL.md`, `execute-tracks/SKILL.md` |
| 2 — A minimal change from start to finish | `workflow.md`, `create-plan/SKILL.md`, `execute-tracks/SKILL.md` | `commit-conventions.md` |
| 3 — Tiers and the tier gate | `planning.md`, `conventions.md` (tiers) | `conventions-execution.md` (complexity tiers), `create-plan/SKILL.md` |
| 4 — Phase 0: research before you plan | `research.md` | `create-plan/SKILL.md`, `conventions.md` (research-log) |
| 5 — Phase 1: the design document | `planning.md` (design), `design-document-rules.md`, `edit-design/SKILL.md` | `design-decision-escalation.md`, `prompts/design-review.md`, `prompts/adversarial-review.md` |
| 6 — Phase 1: from a frozen design to a plan and tracks | `planning.md`, `create-plan/SKILL.md`, `conventions.md` (plan structure) | `conventions-execution.md` (decomposition), `plan-slim-rendering.md` |
| 7 — Phases, sessions, and the phase ledger | `workflow.md`, `conventions.md` (phase-ledger) | `execute-tracks/SKILL.md`, `mid-phase-handoff.md` |
| 8 — Phase 2: reviewing the plan before any code | `implementation-review.md`, `structural-review.md` | `prompts/consistency-review.md`, `prompts/structural-review.md`, `review-plan/SKILL.md`, `prompts/consistency-gate-verification.md`, `prompts/structural-gate-verification.md` |
| 9 — Phase A: pre-flight, review, and decomposition | `track-review.md`, `risk-tagging.md` | `prompts/technical-review.md`, `prompts/risk-review.md`, `prompts/adversarial-review.md`, `review-mode.md`, `conventions-execution.md` (decomposition) |
| 10 — Phase B: the implement-test-commit loop | `step-implementation.md`, `implementer-rules.md`, `episode-format-reference.md` | `step-implementation-recovery.md`, `commit-conventions.md`, `code-review-protocol.md` |
| 11 — Reviewing changed code: the dimensional review agents | `code-review/SKILL.md`, `review-agent-selection.md`, `.claude/agents/review-*.md` | `review-iteration.md`, `finding-synthesis-recipe.md`, `code-review-protocol.md`, `prompts/dimensional-review-gate-check.md` |
| 12 — Phase C: track-level code review and track completion | `track-code-review.md`, `review-mode.md` | `review-iteration.md`, `implementer-rules.md`, `commit-conventions.md`, `episode-format-reference.md` |
| 13 — Phase 4: final artifacts and merge | `prompts/create-final-design.md`, `workflow.md` (final artifacts), `conventions.md` (§1.7) | `branch-divergence-check.md` |
| 14 — When things change mid-flight | `inline-replanning.md`, `mid-phase-handoff.md`, `step-implementation-recovery.md` | `workflow.md` (context, failure), `track-skip.md` |
| 15 — Keeping a branch current: drift and migration | `workflow-drift-check.md`, `branch-divergence-check.md`, `migrate-workflow/SKILL.md` | `conventions.md` (§1.6), `defensive-push-check.md` |
| 16 — Writing for the workflow and improving it | `conventions.md` (§1.5), `house-style.md`, `self-improvement-reflection.md` | `ai-tells/SKILL.md`, `.claude/agents/review-workflow-writing-style.md`, `readability-feedback/SKILL.md` |
