<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Track 1: Selection-side staging awareness (YTDB-1032)

## Purpose / Big Picture
After this track, the three glob-gated workflow reviewers launch on a staged workflow edit, so a workflow-modifying branch gets the same dimensional review coverage an in-place edit would.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

On a workflow-modifying plan the per-agent triggers match the live `.claude/...`
path while the change lives under the staged prefix, so
`review-workflow-prompt-design`, `-instruction-completeness`, and
`-hook-safety` never match and fail to launch (consistency and context-budget
always run; writing-style already fires via `docs/adr/**/*.md`). A
staged-path normalization rule strips the prefix before the globs run.

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

The agent-selection logic lives in two mirrored files: the canonical
`.claude/workflow/review-agent-selection.md` and its copy embedded in
`.claude/skills/code-review/SKILL.md` (Steps 5a/5b/5d/6). Both decide which
workflow reviewers fire for a given changed file.

Terminology used below:
- **Per-agent trigger** — the glob that gates whether a given workflow
  reviewer launches for a changed file.
- **Staged subtree** — the copy of the workflow files a workflow-modifying
  branch authors, kept under
  `docs/adr/<dir>/_workflow/staged-workflow/.claude/...` until Phase 4
  promotion.
- **Workflow-machinery file set** — the broader categorization that flags a
  diff as workflow machinery; counts anything under `docs/adr/<dir>/`.

Current state of the selection rules:
- Six workflow reviewers exist. Two always run (`review-workflow-consistency`,
  `review-workflow-context-budget`); `review-workflow-writing-style` fires
  through its `docs/adr/**/*.md` glob, which already matches staged `.md`
  files. The remaining three are gated on globs that name `.claude/...` paths
  only:
  - `review-workflow-prompt-design`: `.claude/skills/*/SKILL.md`,
    `.claude/agents/*.md`, `.claude/workflow/prompts/*.md`
  - `review-workflow-instruction-completeness`: the above plus
    `.claude/workflow/*.md`
  - `review-workflow-hook-safety`: `.claude/hooks/*.sh`, `.claude/scripts/**`,
    `.claude/settings*.json`
- A staged edit to `.claude/workflow/conventions.md` lands at
  `docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/conventions.md`,
  which matches none of those three globs. The
  `instruction-completeness` reviewer that should judge it never launches.

Concrete deliverables this track produces:
- A staged-path normalization paragraph in
  `review-agent-selection.md` §Workflow-machinery override.
- The mirrored paragraph in `code-review/SKILL.md` Step 5d, in the same
  commit, with the single canonical `<!-- Last sync-checked … -->` date in
  `review-agent-selection.md §Maintenance` bumped (S1). The sync stamp is
  single-location; `code-review/SKILL.md` carries no such comment.

## Plan of Work

The approach is one normalization rule applied before the per-agent globs run,
landed in both mirror files in a single commit (D1, S1).

1. Add the normalization rule to `review-agent-selection.md`
   §Workflow-machinery override (the section mirroring SKILL.md Step 5d):
   before the per-agent globs are matched — those globs live in
   §Per-agent file-pattern triggers, which mirrors SKILL.md Step 5b, not in
   the override section — strip the `docs/adr/<dir>/_workflow/staged-workflow/`
   prefix from any changed path and match the remainder against the globs. The
   override section is the right home because it already evaluates the
   per-agent triggers against the workflow-machinery subset of the diff; the
   normalization is a preamble that runs ahead of that evaluation. A staged
   file then evaluates exactly as its live counterpart would. State the precise gap
   verbatim (only the three glob-gated reviewers miss on staged paths;
   consistency and context-budget always run; writing-style already fires via
   `docs/adr/**/*.md`) rather than the issue's looser wording.
2. Mirror the same rule into `code-review/SKILL.md` Step 5d in the same
   commit, and bump the single canonical `<!-- Last sync-checked … -->` date
   in `review-agent-selection.md §Maintenance` (the only file that carries the
   stamp; `code-review/SKILL.md` has no such comment).

Ordering and invariants:
- Both edits land in one commit (S1). The normalization is scoped to the exact
  two-level `…/_workflow/staged-workflow/.claude/` prefix; a path that merely
  contains `.claude/` lower down must not normalize.
- The file-set categorization is unchanged: a staged file was already
  workflow-machinery by the `docs/adr/<dir>/` rule. Only the per-agent trigger
  step needs the prefix strip.

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

Track-level behavioral acceptance:
- A changed path
  `docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/conventions.md`
  normalizes to `.claude/workflow/conventions.md` and matches the
  `instruction-completeness` per-agent glob, so that reviewer launches.
- A path containing `.claude/` below the staged prefix (not at the exact
  two-level boundary) does not normalize.
- The mirror pair changes in one commit with the canonical sync-date in
  `review-agent-selection.md §Maintenance` bumped; `review-workflow-consistency`
  would flag drift otherwise.
- The writing-style reviewer outcome is unchanged: it still fires via
  `docs/adr/**/*.md`; normalization makes that intentional rather than an
  incidental overlap.

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

In-scope files:
- `.claude/workflow/review-agent-selection.md` — §Workflow-machinery override
  (add the normalization paragraph) and §Maintenance sync-date comment.
- `.claude/skills/code-review/SKILL.md` — Step 5d (mirror the paragraph). The
  sync stamp is single-canonical in `review-agent-selection.md §Maintenance`;
  `code-review/SKILL.md` carries no `<!-- Last sync-checked … -->` comment of
  its own.

Out-of-scope:
- The per-agent glob definitions themselves — normalization runs before them,
  it does not edit them.
- The file-set categorization rule (unchanged; a staged file is already
  workflow-machinery by the `docs/adr/<dir>/` rule).
- `workflow-reindex.py --check` — gains no mirror or staged-copy awareness
  (Non-Goal).

Inter-track dependencies: none. Track 1 is independent of Tracks 2 and 3 and
can land in any order relative to them.

Staging: per `§1.7`, both edits route through
`docs/adr/<dir>/_workflow/staged-workflow/.claude/...`; the live files stay at
develop's state until Phase 4 promotion.

Full design: design.md §"Selection-side staging awareness".
