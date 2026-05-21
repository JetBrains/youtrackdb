# Track 3: Handoff writer and resume path

## Purpose / Big Picture

The reviewer can checkpoint a review session to a `/tmp` Markdown file and
resume from a fresh session without losing the observation list or the
sub-agent dispatch log.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the handoff mechanism: a Markdown file under `/tmp` keyed by
PR number, written when the reviewer explicitly asks, and discovered via a
PR-keyed glob on re-invocation. Resume re-verifies HEAD against the saved
head SHA, reloads the observation list and the sub-agent dispatch log, and
re-presents the list to the reviewer. The file is deleted on successful
submission.

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

Track 1 has scaffolded the skill and the observation list. Track 2 has added
the DR-audit sub-agent and the `gh api` submission machinery. This track
adds the persistence layer that lets the reviewer pause a session and resume
it later without re-running expensive sub-agent dispatches.

State the handoff captures, by origin track:

- PR ref, owner/repo, head SHA at session start — Track 1
- Local checkout path, HEAD at handoff time — Track 1
- `<dir>` resolved, artifact paths — Track 1
- Observation list — Track 1
- Sub-agent dispatch log — Track 2
- Reviewer notes — Track 3 (new free-form field)

Filesystem contract:

- Handoff path: `/tmp/claude-code-review-workflow-pr-<N>-$PPID.md`
- Discovery glob on resume: `/tmp/claude-code-review-workflow-pr-<N>-*.md`
  (PR-keyed; PID wildcard so a new shell with a different PPID still finds
  the file).
- Cleanup: deleted on successful `gh api` POST; persists across any failure
  path until the next successful submission or until the reviewer deletes
  it manually.

Concrete deliverables of this track:

- A new `## Handoff and resume` section in `SKILL.md` covering the file
  format, the write trigger (explicit reviewer command only), the
  glob-based discovery on re-invocation, the resume HEAD-verification
  dialog, and the cleanup on successful submission.
- The session-start warning that observations are in-memory until the
  reviewer asks to checkpoint.

## Plan of Work

Roughly (Phase A decomposes the exact step boundaries):

1. Extend `SKILL.md` with the handoff-write instructions: reviewer-command
   triggers (e.g. "checkpoint", "save state", "we're about to /clear"),
   the Markdown structure (PR context, local checkout, workflow directory,
   sub-agent dispatch log, observation list, reviewer notes), the file
   location (`/tmp/claude-code-review-workflow-pr-<N>-$PPID.md`), and the
   announcement of the resulting path to the reviewer. Add the session-
   start warning that observations are in-memory unless checkpointed.

2. Extend `SKILL.md` with the resume discovery: on
   `/review-workflow-pr <N>` invocation, glob
   `/tmp/claude-code-review-workflow-pr-<N>-*.md`. Branch on zero, one, or
   many matches. On one match offer to resume and ask the reviewer to
   confirm; on many, list candidates with mtimes and ask the reviewer to
   pick; on zero, proceed as a fresh session.

3. Extend `SKILL.md` with the resume reload and HEAD re-verification:
   re-read the chosen handoff file, parse the six sections, ask the
   reviewer for direction if the local HEAD has moved (refresh
   observations / abort + re-checkout / proceed without revalidation). On
   successful submission, delete the handoff file. On any failure path,
   leave it in place.

Ordering: step 1 first — the write path must exist before resume has
anything to read. Steps 2 and 3 form a single conceptual flow and can land
together.

Invariants this track preserves:

- The handoff file is written only on explicit reviewer request. No
  auto-write on context-pressure, pre-dispatch, or submission-failure
  events.
- The skill remains read-only against the workflow artifacts under review.
- Each observation's existing invariants from Track 1 still hold after a
  resume: `path` in the PR's changed file set, `line` within the file's
  current content.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster
here: one entry per step with description, `risk:` tag, and a `[ ]` status
checkbox. Per-step episodes do NOT live here; they live in `## Episodes`
below. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed
step, identified by step number + commit SHA. Empty at Phase 1; Phase A
does not populate. -->

## Validation and Acceptance

After this track lands, a reviewer can:

- Ask the skill to checkpoint mid-session, receive the `/tmp` file path,
  and verify the file is readable Markdown with the six documented
  sections.
- `/clear` the session, re-run `/review-workflow-pr <N>` in a fresh
  session, get offered to resume, accept, and see the observation list and
  sub-agent dispatch log restored.
- Run `/review-workflow-pr <N>` in a fresh session against a PR that has
  no handoff file and see the skill start clean without any resume prompt.
- Trigger a HEAD mismatch (e.g. a downstream push moved the head) on
  resume and get the three-way choice (refresh observations / abort +
  re-checkout / proceed without revalidation).
- Submit successfully and observe that the handoff file is deleted;
  re-run the skill and see a clean start.
- Cancel at the submission confirmation prompt and observe that the
  handoff file persists, so a later session can resume.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim
as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths
once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Often empty. -->

## Interfaces and Dependencies

In-scope files:

- `.claude/skills/review-workflow-pr/SKILL.md` (extended from Track 2)

Out-of-scope:

- Auto-trigger machinery (context-pressure polling, pre-dispatch
  checkpointing, submission-failure fallback). D5 makes this a Non-Goal.

Filesystem contract:

- Handoff write target: `/tmp/claude-code-review-workflow-pr-<N>-$PPID.md`
- Resume discovery glob: `/tmp/claude-code-review-workflow-pr-<N>-*.md`
- Cleanup: deleted on successful `gh api` POST.

Inter-track dependencies:

- Depends on Track 2's `SKILL.md` state surface (PR ref, head SHA,
  observation list, sub-agent dispatch log) and on the successful-
  submission hook where the file is deleted.

GitHub state interactions:

- None new in this track. The handoff captures already-fetched PR state;
  resume re-fetches the head SHA via Track 1's preflight code path to
  re-verify, but does not introduce new `gh` calls.
