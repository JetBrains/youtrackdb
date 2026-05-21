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
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-21T14:58Z [ctx=info] Review + decomposition complete
- [x] 2026-05-21T15:33Z [ctx=safe] Step 1 complete (commit 45d7960af4)
- [x] 2026-05-21T15:38Z [ctx=safe] Step 2 complete (commit a5aa6cc436)
- [x] 2026-05-21T15:43Z [ctx=safe] Step 3 complete (commit 58a5f9193a)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- 2026-05-21T15:33Z — Pre-commit ephemeral-identifier gate fires on writer-side `SKILL.md` prose under `.claude/skills/`. Step 1 leaked one Track-by-label reference that the gate caught; the fix anchored on the existing `**POST and URL**` sub-block name plus the cache's observable behavior, both of which are stable identifiers. Steps 2 and 3 also write durable content under the same path and should expect the same rewrite-on-detection loop. See Episodes §Step 1.
- 2026-05-21T15:43Z — The `**POST and URL.**` sub-block in `## Wrap-up and submission` now owns the handoff-delete action that the design and the resume-reload cleanup contract both call out. The `### Edge cases / Gotchas` block in `design.md` §"Handoff and resume" describes the cleanup abstractly ("file is deleted automatically on successful submission") but does not name which sub-block owns the deletion. The Phase 4 `design-final.md` writer may want to pin the action-owner explicitly so the implementation-design correspondence stays unambiguous for future maintainers. See Episodes §Step 3.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (10 findings, all 10 accepted and applied as light track-file edits to `## Context and Orientation` and `## Plan of Work`). T1–T3 closed Track 2's three cross-track impacts (dispatch-log tri-state on resume, no-auto-dedup contract, snapshot-vs-cache head-SHA distinction). T4 reworded Plan of Work step 1 to edit the existing `**Session-start prelude.**` paragraph at `SKILL.md:69` rather than appending a second warning. T5 added per-branch consequences for the HEAD-mismatch three-way choice. T6 added the 422-mutation corner case to the Cleanup paragraph. T7 added mtime semantics to the many-match discovery clause. T8 aligned the state-list ordering with `design.md` §"Handoff and resume". T9–T10 (iter-2 suggestions) reordered the consequences block to match the lead-paragraph order and added a cross-reference from `## Interfaces and Dependencies` cleanup to the `## Context and Orientation` cleanup paragraph. No regressions; no risk or adversarial review needed (track is Markdown-only, Moderate complexity, no critical-path or architectural-decision characteristics).

## Context and Orientation

Track 1 has scaffolded the skill and the observation list. Track 2 has added
the DR-audit sub-agent and the `gh api` submission machinery. This track
adds the persistence layer that lets the reviewer pause a session and resume
it later without re-running expensive sub-agent dispatches.

State the handoff captures, by origin track (ordering matches `design.md`
§"Handoff and resume" — workflow-state-ordered, audit log before user output):

- PR ref, owner/repo, head SHA at session start — Track 1
- Local checkout path, HEAD at handoff time — Track 1
- `<dir>` resolved, artifact paths — Track 1
- Sub-agent dispatch log — Track 2
- Observation list — Track 1
- Reviewer notes — Track 3 (new free-form field)

The persisted head SHA is the session-start *snapshot*, used at resume only
for HEAD-drift detection. Track 2's in-memory wrap-up head-SHA cache (the
one that reverts to session-start on POST failure per `SKILL.md:141`) is
NOT persisted; resume re-derives it from a fresh `gh pr view` at preflight.

Filesystem contract:

- Handoff path: `/tmp/claude-code-review-workflow-pr-<N>-$PPID.md`
- Discovery glob on resume: `/tmp/claude-code-review-workflow-pr-<N>-*.md`
  (PR-keyed; PID wildcard so a new shell with a different PPID still finds
  the file).
- Cleanup: deleted on successful `gh api` POST; persists across any failure
  path until the next successful submission or until the reviewer deletes
  it manually. After a `422 Unprocessable Entity` POST result (Track 2's
  partial-success branch — the offending observation gets a
  `[REJECTED: <reason>]` tag and the skill returns to prune mode), the
  handoff file is not auto-updated to reflect the mutation. The reviewer
  must re-checkpoint before `/clear` if they want the rejection state to
  survive across sessions.

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
   sub-agent dispatch log, observation list, reviewer notes — order
   matches `design.md` §"Handoff and resume"), the file location
   (`/tmp/claude-code-review-workflow-pr-<N>-$PPID.md`), and the
   announcement of the resulting path to the reviewer. **Edit** (do not
   append to) the existing `**Session-start prelude.**` paragraph at
   `SKILL.md:69`: drop the "follow-up track" qualifier and the
   plain-text-stub fallback, add the handoff-checkpoint trigger phrases
   and the resulting-path announcement, preserve the existing
   "Observations live in this conversation only" sentence so the
   in-memory-state warning still surfaces.

2. Extend `SKILL.md` with the resume discovery: on
   `/review-workflow-pr <N>` invocation, glob
   `/tmp/claude-code-review-workflow-pr-<N>-*.md`. Branch on zero, one, or
   many matches. On one match offer to resume and ask the reviewer to
   confirm; on many, list candidates with mtimes (mtime = last checkpoint
   write for that PR) sorted newest-first and recommend the newest unless
   the reviewer explicitly wants an older state; on zero, proceed as a
   fresh session.

3. Extend `SKILL.md` with the resume reload and HEAD re-verification:
   re-read the chosen handoff file, parse the six sections, ask the
   reviewer for direction if the local HEAD has moved (refresh
   observations / abort + re-checkout / proceed without revalidation). On
   successful submission, delete the handoff file. On any failure path,
   leave it in place.

   **Dispatch-log re-presentation rules** (Track 2 cross-track impacts
   (a) and (b)):

   - Treat a missing `dispatchLog` section in the handoff as "audit never
     ran to completion", distinct from "audit ran and produced zero
     findings" (the latter is a `dispatchLog` entry with
     `findings_count=0` per `SKILL.md:121`). The re-presentation surfaces
     all three states distinctly so the reviewer can decide whether to
     re-run the audit.
   - Replay the `dispatchLog` entries as-written; do not auto-dedup
     repeated spawns of the same `sub-agent name`. The latest entry per
     `sub-agent name` is treated as the authoritative "last run" for the
     summary line, but earlier entries are preserved so the reviewer sees
     the spawn history.

   **HEAD-mismatch three-way choice consequences** (surface each in the
   reviewer-facing prompt, not just the choice labels):

   - **Refresh observations**: reuse the wrap-up `**Head-SHA re-fetch.**`
     refresh procedure verbatim (`SKILL.md:137`) — do not invent a
     parallel resume-specific procedure.
   - **Abort + re-checkout**: the in-memory state from the just-resumed
     session is preserved, so the reviewer can re-checkpoint before
     clearing if they want a fresh snapshot.
   - **Proceed without revalidation**: observation `line` values reference
     the saved HEAD's file content, but the wrap-up line validation runs
     against the new HEAD; most or all observations may be marked
     `[STALE: verify line]` at wrap-up.

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

1. Add `## Handoff and resume` to `SKILL.md` with the handoff-write subsection (reviewer-command trigger phrases, six-section file format matching `design.md` §"Handoff and resume" ordering, `/tmp/claude-code-review-workflow-pr-<N>-$PPID.md` write path, post-write path announcement to the reviewer) and **edit** the existing `**Session-start prelude.**` paragraph at `SKILL.md:69` per Plan of Work step 1 (drop the "follow-up track" qualifier and plain-text-stub fallback, add the handoff-checkpoint trigger phrases, preserve the "Observations live in this conversation only" sentence) — risk: low (default: documentation/instruction prose only; one Markdown file; no production code, no tests)  [x] commit: 45d7960af4
2. Add the resume-discovery subsection to `## Handoff and resume` in `SKILL.md` per Plan of Work step 2: glob `/tmp/claude-code-review-workflow-pr-<N>-*.md`; branch on zero (fresh session) / one (offer to resume with reviewer confirmation) / many (list candidates with mtimes sorted newest-first, recommend the newest unless the reviewer explicitly wants an older state) — risk: low (default: documentation/instruction prose only; one Markdown file; no production code, no tests)  [x] commit: a5aa6cc436
3. Add the resume-reload subsection to `## Handoff and resume` in `SKILL.md` per Plan of Work step 3: re-read the chosen handoff file and parse the six sections; HEAD re-verification with the three-way choice (refresh observations reusing `**Head-SHA re-fetch.**` at `SKILL.md:137` / abort + re-checkout preserving in-memory state / proceed without revalidation acknowledging the `[STALE: verify line]` risk); dispatch-log tri-state re-presentation (missing entry vs `findings_count=0` entry vs `findings_count>0` entry per `SKILL.md:121`); no-auto-dedup contract for the dispatch log; snapshot-vs-cache head-SHA distinction; cleanup discipline (delete on successful POST, persist on any failure, 422-mutation corner case needing re-checkpoint before `/clear` to survive across sessions) — risk: low (default: documentation/instruction prose only; one Markdown file; no production code, no tests)  [x] commit: 58a5f9193a

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed
step, identified by step number + commit SHA. Empty at Phase 1; Phase A
does not populate. -->

### Step 1 — commit 45d7960af4, 2026-05-21T15:33Z [ctx=safe]
**What was done:** Added `## Handoff and resume` to `SKILL.md` with the handoff-write subsection: reviewer-driven trigger phrases (`checkpoint`, `save state`, `we're about to /clear`), the `/tmp/claude-code-review-workflow-pr-<N>-$PPID.md` write path keyed by PR number and parent shell PID, the six-section file format matching `design.md` §"Handoff and resume" ordering, and the post-write path announcement. Rewrote the existing `**Session-start prelude.**` paragraph at `SKILL.md:69` to drop the "follow-up track" qualifier and the plain-text-stub fallback, add the handoff-checkpoint trigger phrases, and announce the resulting `/tmp` path, while preserving the "Observations live in this conversation only" sentence so the in-memory-state warning still surfaces.

**What was discovered:** The pre-commit ephemeral-identifier gate caught one durable-content track-label leak in the new prose where Track 2 was cited by label rather than by behavior. The fix anchored on the existing `**POST and URL**` sub-block name plus the cache's observable behavior, which are both stable in `SKILL.md`. This is the first gate firing on writer-side `SKILL.md` prose this track; Steps 2 and 3 also write durable content under `.claude/skills/`, so the same rewrite-on-detection loop is likely to fire again.

**What changed from the plan:** none

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

**Critical context:** none

### Step 2 — commit a5aa6cc436, 2026-05-21T15:38Z [ctx=safe]
**What was done:** Added the resume-discovery paragraph block to `## Handoff and resume` in `SKILL.md`. The new block specifies the discovery glob `/tmp/claude-code-review-workflow-pr-<N>-*.md` (PR-keyed prefix, parent-shell-PID wildcard so a new shell with a different PPID still finds the file) and a three-way branch on glob result: zero matches proceed as a fresh session; one match prints the path and asks for explicit reviewer confirmation, leaving the file in place on decline; many matches list candidates as a numbered table with full path and ISO mtime sorted newest-first, recommend the newest, and let the reviewer pick an older entry by index when they want an earlier state. Touched the section lead-in to surface that resume discovery now lives in this section while flagging that resume-reload lands in the next subsection.

**What was discovered:** The pre-commit ephemeral-identifier gate fired a second time on writer-side `SKILL.md` prose, this time on a standards-suffix token (`ISO-8601`). The regex `\b[A-Z]{1,3}-?[0-9]+\b` matches any short-uppercase-prefix + digit-suffix shape, which sweeps in self-contained tokens (ISO standards, RFC IDs, file-format versions) alongside the actual Track / Step / finding label leaks the rule targets. The §Allowed list's "self-contained references" reading covers the standards case; inspect-then-rewrite stays cheap. Step 3 should expect the same gate firing on plausible-looking durable-content tokens.

**What changed from the plan:** none

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

**Critical context:** none

### Step 3 — commit 58a5f9193a, 2026-05-21T15:43Z [ctx=safe]
**What was done:** Added the resume-reload subsection to `## Handoff and resume` in `SKILL.md` across four sub-blocks: **Resume reload.** (re-read the chosen handoff, parse the six sections, restore observation list and `dispatchLog` with `[STALE: verify line]` and `[REJECTED: <reason>]` prefixes preserved, run artifact discovery against the recorded workflow directory, then enter research mode); **HEAD re-verification.** (three-way choice with each consequence surfaced inline — refresh observations reusing the wrap-up `**Head-SHA re-fetch.**` procedure verbatim as the named safe default, abort + re-checkout preserving in-memory state for re-checkpoint, proceed without revalidation acknowledging the `[STALE: verify line]` risk); **Dispatch-log re-presentation.** (tri-state handling: missing entry, `findings_count=0` entry, `findings_count>0` entry; no-auto-dedup contract preserved); **Cleanup discipline.** (delete only on successful POST, persist on every failure path, 422-mutation corner case calling out re-checkpoint before `/clear` so the rejection tag survives across sessions). Also tightened the existing `**POST and URL.**` sub-block in `## Wrap-up and submission` to own the handoff-delete action on the `.html_url`-returning success branch, since the resume-reload cleanup contract cross-references that sub-block as the load-bearing action that does the deletion.

**What was discovered:** The pre-existing `**POST and URL.**` sub-block did not document the handoff-delete action that the design and the Plan of Work step 3 both call out. The cleanup discipline cross-references the POST sub-block, so the cross-reference required the sub-block to own the delete step in prose. The pre-commit ephemeral-identifier gate did not fire on this step's diff, breaking the per-step-firing pattern from Steps 1 and 2; the new prose anchored on stable sub-block names (`**POST and URL.**`, `**Head-SHA re-fetch.**`, `**Path validation.**`, `**Line validation.**`, `**Sub-agent dispatch failure.**`) plus the `dispatchLog` and `findings_count` field labels documented elsewhere in `SKILL.md`, all stable identifiers.

**What changed from the plan:** Step 3 also touched `## Wrap-up and submission` (the `**POST and URL.**` sub-block) so the cleanup cross-reference resolves. The original step description only named `## Handoff and resume`. The expansion is within Track 3's purpose (the Plan of Work step 3 lists "delete on successful POST" as a cleanup-discipline item) and is recorded here so the Phase C track-level review and the Phase 4 `design-final.md` writer see the scope ahead of time. No upcoming steps are affected — this is the last step in the track.

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

**Critical context:** none

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

All three steps are Markdown-only edits to one file
(`.claude/skills/review-workflow-pr/SKILL.md`). No on-disk runtime state
is created at commit time; the handoff-write, resume-discovery, and
resume-reload behaviours documented in those edits only execute when a
reviewer invokes the skill at run time.

- **Step 1.** Idempotent — re-applying the SKILL.md edits fails when the
  `old_string` anchor no longer matches (signalling the implementer that
  the change already landed). Recovery: `git revert` the step commit; the
  handoff-write logic is not yet exercised by any reviewer session.
- **Step 2.** Same shape as Step 1. The resume-discovery glob is not
  fired until a reviewer invokes `/review-workflow-pr <N>` after Step 2
  lands. Recovery: `git revert` the step commit.
- **Step 3.** Same shape as Step 1. The resume-reload logic is not fired
  until a reviewer invokes `/review-workflow-pr <N>` against a PR that
  already has a handoff file on disk. Recovery: `git revert` the step
  commit.

Cross-step dependency. Steps 2 and 3 both add subsections to the
`## Handoff and resume` section that Step 1 creates; their `Edit`
anchors target the post-Step-1 file state. A revert of Step 1 forces a
revert of any later Steps 2 / 3 commits in reverse order (Step 3 first,
then Step 2).

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
- Cleanup: deleted on successful `gh api` POST. See `## Context and
  Orientation` Cleanup paragraph for the 422-mutation corner case.

Inter-track dependencies:

- Depends on Track 2's `SKILL.md` state surface (PR ref, head SHA,
  observation list, sub-agent dispatch log) and on the successful-
  submission hook where the file is deleted.

GitHub state interactions:

- None new in this track. The handoff captures already-fetched PR state;
  resume re-fetches the head SHA via Track 1's preflight code path to
  re-verify, but does not introduce new `gh` calls.

## Base commit

469ca1e7f693dec359b47b9beeb2d82edf671049
