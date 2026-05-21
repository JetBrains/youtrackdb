# Track 1: Skill scaffolding and research-mode runtime

## Purpose / Big Picture

A reviewer can invoke `/review-workflow-pr <N>` and land in research-mode Q&A
against a verified PR checkout, with observations auto-recorded into an
in-conversation list.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track delivers a usable skill stub. When invoked it accepts a PR
identifier, verifies the local checkout matches the PR head SHA, loads the
workflow review context, discovers the workflow artifacts under
`docs/adr/<dir>/_workflow/`, and enters research-mode Q&A. Observations the
skill detects during analysis are auto-recorded into an in-conversation list.
The end-of-session submission step is stubbed for this track and the reviewer
is shown the observation list as plain text without PR posting; Track 2 adds
the DR-audit sub-agent and the `gh api` submission machinery.

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

The skill lives under `.claude/skills/review-workflow-pr/`. At Phase 1 that
directory does not exist; this track creates it and the `SKILL.md` file
inside.

The reviewer pre-runs `gh pr checkout <N>` so the working tree is on the PR
head. Workflow artifacts under review live at `docs/adr/<dir>/_workflow/`:

- `implementation-plan.md` (required)
- `design.md` (required)
- `design-mechanics.md` (optional)
- `plan/track-*.md` (one per planned track)

`<dir>` defaults to the branch name (matching the `/create-plan` default).
When the branch-name directory does not exist, the skill lists
`docs/adr/*/_workflow/` directories present in the local checkout that
contain an `implementation-plan.md` and asks the reviewer to pick.

Workflow docs the skill reaches for on demand (no preload):

- `.claude/workflow/conventions.md`
- `.claude/workflow/research.md` (the skill's own behavior follows this)
- `.claude/workflow/design-document-rules.md`
- `.claude/workflow/planning.md`

Concrete deliverables of this track:

- `.claude/skills/review-workflow-pr/SKILL.md` with frontmatter (`name`,
  `description`, `argument-hint`, `user-invocable: true`) and instructions
  covering: argument resolution, PR detection, HEAD-SHA verification,
  workflow-doc loading on demand, artifact discovery, research-mode entry,
  observation list management, and an end-of-session stub that prints the
  observation list.
- A working end-to-end flow that ends with the observation list printed to
  stdout (no PR submission yet; that lands in Track 2).

## Plan of Work

The track lands the skill in roughly the following sequence (Phase A will
decompose the precise step boundaries):

1. Create `.claude/skills/review-workflow-pr/` and seed `SKILL.md` with
   frontmatter and the top-level outline. Document the invocation contract:
   PR identifier as `$ARGUMENTS`, default to the current branch's PR.
2. Write the preflight instructions: parse `$ARGUMENTS` (PR number, URL, or
   branch), resolve to a PR number via `gh pr view <ref>`, fetch head SHA
   and changed files via `gh pr view --json headRefOid,number,files`,
   resolve owner/repo via `gh repo view --json nameWithOwner`, verify local
   HEAD matches with `git rev-parse HEAD`, error out with a clear
   `gh pr checkout` command on mismatch.
3. Write the artifact discovery instructions: resolve `<dir>` (branch-name
   default, list-and-pick fallback when missing), enumerate the workflow
   artifacts under `docs/adr/<dir>/_workflow/`, fail clearly if the
   canonical files are missing.
4. Write the research-mode section: how the skill enters Q&A, how it
   presents itself to the reviewer at session start, how observations are
   recorded mid-conversation, how the skill behaves when the reviewer asks
   artifact-specific questions vs broad audits.
5. Write the end-of-session stub: show the observation list as a numbered
   table (index, `path:line`, source, body), note that Track 2 will add the
   real submission, exit cleanly.

Ordering: step 1 first (the file must exist before later steps add to it).
Steps 2-3 are independent of 4 within the same file but flow naturally in
the order above. Step 5 closes the file.

Invariants this track preserves:

- The skill is read-only relative to the workflow artifacts on the PR
  branch. No `Edit`, `Write`, or `git commit` against them.
- Workflow docs load lazily, only when the skill reaches for a rule it
  needs at that moment.
- House-style applies to the skill's own Markdown.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After this track lands, a reviewer can:

- Run `/review-workflow-pr <N>` against a workflow PR they have checked out
  and reach the research-mode prompt within one turn.
- Get a clear error message and `gh pr checkout` remediation when the local
  HEAD does not match the PR head.
- Get a clear error message when the canonical workflow artifacts are
  missing under `docs/adr/<dir>/_workflow/`.
- Ask to wrap up and see the full observation list printed as a numbered
  table; the skill notes that submission is not yet implemented.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

In-scope files for this track:

- `.claude/skills/review-workflow-pr/SKILL.md` (new)

Out-of-scope for this track (handled in Track 2):

- `.claude/skills/review-workflow-pr/dr-audit.md`
- The submission section of `SKILL.md` (stubbed in Track 1, replaced in
  Track 2)

Existing files referenced but not modified:

- `.claude/workflow/conventions.md`, `research.md`,
  `design-document-rules.md`, `planning.md`: read on demand for rule
  lookups during the research-mode conversation.

External tools the skill calls during this track:

- `gh pr view --json headRefOid,files,number`
- `gh repo view --json nameWithOwner`
- `git rev-parse HEAD`

Inter-track dependencies:

- Track 2 consumes the SKILL.md scaffolding from this track and extends the
  end-of-session section to replace the stub with the real submission flow.

No library or function signatures to declare; this is a Markdown skill.
