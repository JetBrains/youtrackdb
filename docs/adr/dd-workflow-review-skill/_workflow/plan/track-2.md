# Track 2: DR-audit sub-agent and PR submission

## Purpose / Big Picture

A reviewer can audit the Decision Records via a focused sub-agent and submit
the accumulated observations to the PR as a single line-anchored review
(approve or request-changes).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the new DR-audit sub-agent prompt and the `gh api`
submission machinery. The DR audit walks each Decision Record in
`implementation-plan.md` and surfaces gaps in alternatives, rationale, risks,
and track references. The submission step composes a JSON payload for the
`pulls/{N}/reviews` endpoint, asks the reviewer to confirm once, and POSTs
the review (approve when the observation list is empty, request-changes
otherwise).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

## Context and Orientation

Track 1 has landed the skill scaffolding. The `SKILL.md` already accumulates
observations into an in-conversation list. The end-of-session step is stubbed
(prints the list to stdout).

This track creates one new file and extends one existing file:

- New: `.claude/skills/review-workflow-pr/dr-audit.md`. Sub-agent prompt
  instructing a fresh agent to parse Decision Records from a given
  `implementation-plan.md`, check the four-bullet form, verify
  `Implemented in:` references, optionally verify `Full design:` link
  targets, and return structured findings.
- Modified: `.claude/skills/review-workflow-pr/SKILL.md`. Adds the DR-audit
  dispatch wiring and replaces the end-of-session stub with the real
  prune-confirm-submit flow.

GitHub REST endpoint used for submission:

- `POST /repos/{owner}/{repo}/pulls/{N}/reviews`
- Body: `{ commit_id, body, event, comments[] }`
- `event` is one of `APPROVE`, `REQUEST_CHANGES`, `COMMENT`.
- Each `comments[]` entry: `{ path, line, side: "RIGHT", body }`.

The reviewer's GitHub auth comes from the existing `gh` CLI session
(verifiable by `gh api /user` if needed).

## Plan of Work

Roughly (Phase A decomposes the exact step boundaries):

1. Author `dr-audit.md`. The prompt instructs the sub-agent to read
   `implementation-plan.md`, enumerate `#### D<n>:` blocks, verify the
   four-bullet shape, verify `Implemented in: Track X` matches an existing
   track in the checklist, verify optional `Full design: design.md §...`
   links resolve, and return findings in a structured Markdown format the
   orchestrator can parse.
2. Extend `SKILL.md` to spawn the DR-audit sub-agent on reviewer request,
   translate findings into observations, and append to the list.
3. Implement the submission payload composer in `SKILL.md`: compose `body`
   (auto-generated summary), pick `event` (zero observations → `APPROVE`,
   otherwise `REQUEST_CHANGES`), build `comments[]` from the observation
   list, validate each comment's `path` against the PR's changed file list
   and each `line` against the current file content (cross-checked against
   `gh pr diff` for modified files so only added or modified lines are
   targeted).
4. Implement the wrap-up flow in `SKILL.md`: present the observation list
   as a numbered table, accept prune commands (`remove 3, 7`, `remove all
   from dr-audit`, `keep all`), re-fetch the head SHA via `gh pr view
   --json headRefOid` so the JSON references the current PR head, build
   the JSON payload, show a one-line confirmation prompt (for example
   `REQUEST_CHANGES with 12 comments to PR <N>?`), and on confirmation POST
   via `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews --input -`.
   Print the resulting review URL.
5. Handle the empty-list path: `event=APPROVE`, body is a one-line "All
   workflow artifacts review clean.", no `comments[]` in the payload.

Ordering: step 1 first (so step 2 has a target to spawn). Steps 3-4 share
file scope with step 2 and depend on the observation translation working;
they land after step 2. Step 5 is a small branch inside step 4 and can land
together with it.

Invariants this track preserves:

- No observation reaches the PR without the reviewer's explicit
  confirmation.
- Each comment's `path` is in the PR's changed file list at submission
  time.
- Each comment's `line` falls within the file's current content and within
  the PR diff (added or modified lines).

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Empty at Phase 1. -->

## Validation and Acceptance

After this track lands, a reviewer can:

- Ask the skill to audit the Decision Records and see one observation per
  identified gap, anchored at the citation line in the plan.
- Wrap up with an empty observation list, confirm once, and see the PR
  receive an `APPROVE` review with a one-line body.
- Wrap up with a non-empty observation list, prune as desired, confirm
  once, and see the PR receive a `REQUEST_CHANGES` review whose inline
  comments are anchored to the cited lines in `design.md`,
  `implementation-plan.md`, or the relevant track files.
- Cancel the submission at the confirmation prompt and return to prune
  mode without losing the observation list.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

In-scope files:

- `.claude/skills/review-workflow-pr/dr-audit.md` (new)
- `.claude/skills/review-workflow-pr/SKILL.md` (extended from Track 1)

External tools the skill calls during this track:

- `gh pr view --json headRefOid,files` (re-fetch at submission)
- `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews --input -`

Inter-track dependencies:

- Depends on Track 1's `SKILL.md` scaffolding (preflight, artifact
  discovery, research-mode entry, observation list).
- Track 3's handoff machinery consumes the sub-agent dispatch log added
  in this track.

GitHub REST API contract (binding for the JSON payload):

- Endpoint: `POST /repos/{owner}/{repo}/pulls/{N}/reviews`
- Required by GitHub: each `comments[]` element's `path` and `body`. Other
  fields are optional in the API and acquire defaults when omitted
  (`event` omitted yields a PENDING draft; `commit_id` omitted defaults
  to the current PR head; `body` is required only when `event` is
  `REQUEST_CHANGES` or `COMMENT`).
- The skill sends all of `event` (`APPROVE` | `REQUEST_CHANGES`),
  `body` (string), `commit_id` (SHA verified against the current PR
  head), and each comment's `line` plus `side: "RIGHT"` so the review
  is anchored, line-pinned, and never lands as a PENDING draft.
