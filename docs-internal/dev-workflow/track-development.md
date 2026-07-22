# Track-Based Development: YTDB Deltas

The generic track-based workflow protocol no longer lives in this repository. It ships with
the `ytdb-slate` npm package (pinned in `.pi/settings.json`) as two documents, cited by
absolute path in the orchestrator doctrine so agents can read the authoritative text on
demand:

- **track-workflow.md** — research phase, lazy research log, mandatory user design review,
  mandatory pre-implementation adversarial review, the per-track loop (agent code review →
  mandatory user review → marker commit), marker-commit mechanics, and the change-size
  scaling table.
- **pr-publishing.md** — umbrella draft PR mechanics: creation before implementation,
  description rules (Motivation, Planned changes, Tracks), ready-for-review flip checklist,
  user-performed merge.

This document carries ONLY the YTDB-specific deltas layered on that baseline. Draft-PR
publishing is ENABLED for this repository (`workflow.draftPRs` in `.pi/slate.json`), so
pr-publishing.md applies in full: every change gets an umbrella draft PR before
implementation, and the research log folds into its description.

## Base branch

The repository's default development branch is `develop`, not `main`. The umbrella PR
targets it, and track 01's base is the merge-base with it.

## Branch, title, and template conventions

- Branch names carry the YTDB issue number; CI auto-prefixes the PR title from the branch
  name. Title rules — multi-issue bracket lists, the `[no-test-number-check]` gate marker,
  per-push title/description sync — live in
  `docs-internal/agents/orchestrator-guidelines.md` § Git Conventions.
- The PR description follows the repository template at `.github/pull_request_template.md`,
  which instantiates the generic description rules owned by pr-publishing.md (Motivation,
  Planned changes with its subsections and hard guards, Tracks).

## Verification integration

Per-track implementation (step 1 of the generic track loop) follows YTDB's test policy and
pre-commit verification rules in `docs-internal/agents/orchestrator-guidelines.md`; the
exact command lines are in `docs-internal/agents/thread-guidelines.md`.

## Package pin bumps

Changing the `ytdb-slate` version pin in `.pi/settings.json` is a tracked change like any
other — it takes the full workflow. In addition, whoever bumps the pin MUST re-read this
document and `docs-internal/agents/slate-doctrine-extra.md` against the NEW package docs and
fix any skew: the deltas here are valid only relative to the package version they were
written against.
