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
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-01T13:05Z [ctx=safe] Review + decomposition complete

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
- [x] Technical: PASS at iteration 2 (4 findings; T1 should-fix + T2/T3
  suggestions accepted and applied to the track description; T4 suggestion
  noted, left as-is — the 2-step decomposition makes the plan's "~2 steps"
  hint accurate, so no plan edit needed). Risk and Adversarial reviews
  skipped (Simple track, 1-2 steps, per the complexity table).
  Self-application carve-out (§1.7(h)): the technical and gate-verification
  sub-agents ran with hand-injected staging-aware reads (§1.7(d)) and
  prose-criteria (references verified as file paths / §-anchors via
  Read+Grep, not PSI), since the live machinery does not yet carry the
  YTDB-1046 / YTDB-1038 fixes this branch stages.

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
3. Add one staged-path worked example to `review-agent-selection.md`
   §Examples — workflow-machinery override, in the same commit. The existing
   worked examples use live `.claude/...` paths and re-state the glob-match
   outcomes in prose; none demonstrates the staged case this track fixes. The
   new example shows a staged path normalizing and the three glob-gated
   reviewers launching: a step editing
   `docs/adr/<dir>/_workflow/staged-workflow/.claude/skills/code-review/SKILL.md`
   normalizes to `.claude/skills/code-review/SKILL.md`, which matches the
   `prompt-design` and `instruction-completeness` globs, so both launch
   alongside the always-run consistency + context-budget pair. §Examples is
   not in the mirrored set (§Maintenance lists only §Workflow-review agents,
   §Workflow-machinery file set, §Per-agent file-pattern triggers, and
   §Workflow-machinery override), and `code-review/SKILL.md` carries no
   §Examples block, so this addition creates no new drift and needs no
   SKILL.md counterpart.

Ordering and invariants:
- Both edits land in one commit (S1). Express the rule as an anchored prefix
  strip: a changed path matching
  `docs/adr/<any-dir>/_workflow/staged-workflow/(.claude/…)` is replaced by the
  captured `.claude/…` remainder before per-agent glob matching. The match is
  anchored after the `docs/adr/<dir>/` head (the dir segment is variable) and
  the remainder begins at `.claude/`. A path not matching this exact anchored
  prefix passes through unchanged; a path that merely contains `.claude/` lower
  down does not normalize.
- The file-set categorization is unchanged: a staged file was already
  workflow-machinery by the `docs/adr/<dir>/` rule. Only the per-agent trigger
  step needs the prefix strip.

## Concrete Steps

1. Add the staged-path normalization preamble to `review-agent-selection.md`
   §Workflow-machinery override and mirror it into `code-review/SKILL.md`
   Step 5d, in one commit, bumping the single canonical
   `<!-- Last sync-checked … -->` date in `review-agent-selection.md`
   §Maintenance. The preamble runs ahead of the per-agent glob match: a
   changed path matching `docs/adr/<any-dir>/_workflow/staged-workflow/(.claude/…)`
   is replaced by the captured `.claude/…` remainder before matching, anchored
   after the `docs/adr/<dir>/` head; paths not matching the exact anchored
   prefix pass through unchanged. Both staged edits land in the same commit
   (S1). — risk: medium (review-selection infrastructure: changes which
   workflow reviewers launch on staged paths; multi-file mirror pair; no HIGH
   code triggers apply)  [ ]
2. Add one staged-path worked example to `review-agent-selection.md`
   §Examples — workflow-machinery override, showing a staged path normalizing
   and the three glob-gated reviewers launching alongside the always-run
   consistency + context-budget pair. §Examples is not a mirrored section, so
   this edits `review-agent-selection.md` only (no SKILL.md counterpart, no
   sync-date re-bump, no new drift). Sequential after Step 1 (it documents
   Step 1's rule). — risk: low (default: documentation; illustrative prose, no
   behavior change)  [ ]

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
- §Examples — workflow-machinery override gains a staged-path worked example
  whose stated agent set matches the normalized live path (the three glob-gated
  reviewers plus the always-run pair).
- The normalization paragraph in `review-agent-selection.md §Workflow-machinery
  override` and `code-review/SKILL.md` Step 5d carry the same normalization
  semantics, and the single commit touches both files plus the `§Maintenance`
  sync-date (S1 mirror integrity; `review-workflow-consistency` backstops this
  at Phase C).

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
  (add the normalization paragraph), §Examples — workflow-machinery override
  (add the staged-path worked example; not a mirrored section, so no SKILL.md
  counterpart), and §Maintenance sync-date comment.
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
