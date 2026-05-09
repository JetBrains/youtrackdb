---
severity: medium
phase: phase-a
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Inline-replanning §"Updating plan and backlog" lacks a case for splitting one mid-execution track into N new tracks

## Symptom

During inline replanning of Track 22 (split into 22a/22b/22c per a
user-accepted A3 BLOCKER from Phase A iter-1), the orchestrator had
to compose the file-location mechanics by hand because
`.claude/workflow/inline-replanning.md` § "Updating plan and backlog"
enumerates exactly six cases and none of them describes "split one
mid-execution track into N new tracks":

- Case 1 ("New track") covers each new sub-track but not the parent's
  removal.
- Case 3 ("Revising a mid-execution track") covers in-place revision
  but not splitting — it says to update the existing step file's
  `## Description`, which is wrong when the parent track is dissolved.
- Case 6 ("Removing a track") covers the parent's removal but does
  not address the new sub-tracks or the step file's fate.

The orchestrator interpreted "split" as "case 6 (remove parent) +
3× case 1 (add sub-tracks) + delete the parent's step file." This
worked, but it required the orchestrator to fuse cases on its own
authority. A future agent in the same situation could legitimately
choose to keep the parent's step file (renaming it to one sub-track)
or to scatter the parent's `## Description` across all sub-tracks'
step files, neither of which is currently forbidden by the doc.

## Reproduction context

- Phase: phase-a (inline replanning triggered from Phase A iter-1
  ESCALATE)
- Workflow doc(s) involved: `.claude/workflow/inline-replanning.md`
  § "Updating plan and backlog" (the six-case enumeration); also
  interacts with `conventions-execution.md` §2.1 "Description
  lifecycle" table
- Tool / sub-agent involved: orchestrator (no sub-agent)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any inline-replan that splits a `[ ]`
  mid-execution track (with a step file on disk) into ≥2 new tracks.
  Triggered when a Phase A reviewer's BLOCKER recommendation is
  "split into N sub-tracks" (a recurring pattern when a track grows
  beyond the D5 cap during decomposition).

## Why it's a problem

Three downstream risks:

1. **Step-file fate is undefined.** If a future agent retains the
   parent's step file and renames it to one sub-track's, the next
   `/execute-tracks` session enters State C on that sub-track with
   stale Phase A metadata (Reviews completed, Iteration 1 deferred
   resolution sections) carried over from a track that no longer
   exists. The Pre-Flight gate is skipped for that sub-track because
   the step file already exists, even though the sub-track is fresh
   work.
2. **Description content can be lost or scattered inconsistently.**
   The parent's `## Description` (which is the authoritative location
   per the Description lifecycle table once Phase A has run) holds
   carefully-stitched content. With no rule, one agent puts it all in
   sub-track A's backlog; another scatters it across all three
   sub-tracks' backlogs; a third puts it in `design.md`. This was the
   exact friction this session — see the companion proposal
   "Inherited-content preservation gap" (not filed but raised in the
   same reflection).
3. **Plan Review reset interaction is unclear.** The doc says to
   reset `## Plan Review` to `[ ]`, which is correct for any replan,
   but a split is structurally larger than a typical revise — the
   next session's State 0 must re-validate three new tracks
   simultaneously. The doc doesn't flag that consequence.

## Proposed fix

Add a seventh case to `inline-replanning.md` § "Updating plan and
backlog":

> 7. **Splitting one mid-execution track into N new tracks**
>    (status `[ ]` with a step file on disk; the inline-replan
>    decomposes the track because a Phase A reviewer or the user
>    finds its scope unmanageable). This is case 6 + N×case 1 with
>    explicit step-file handling:
>    - Remove the parent's plan-file checklist entry.
>    - The parent's backlog entry was already removed at Phase A
>      start, so no backlog cleanup is needed.
>    - Delete the parent's step file under `tracks/track-N.md`. The
>      orchestrator MUST first preserve any carefully-stitched
>      content (reconstructed inherited queues, recovery-gap
>      stitching, multi-iteration review history) by copying it into
>      the new sub-tracks' backlog entries — typically the first
>      sub-track's backlog absorbs the bulk, and the others
>      cross-reference. Do NOT rename the parent's step file to one
>      of the sub-tracks; the next session must enter State A
>      (fresh Phase A → Pre-Flight) on each new sub-track.
>    - Add N new plan-file checklist entries (intro paragraph +
>      `**Scope:**` + `**Depends on:**`) per case 1.
>    - Add N new backlog sections (`**What/How/Constraints/
>      Interactions**` + any track-level Mermaid diagram) per
>      case 1.
>    - Reset `## Plan Review` to `[ ]` per §6 (same as any other
>      replan). Note that the next session's State 0 will validate
>      all N new tracks together; the structural-review preview in
>      this replan should already have caught any cross-track
>      contradictions.

Also add a short forward-pointer in `conventions-execution.md` §2.1
"Description lifecycle" table for the "Inline replan — track split"
row so the description's authoritative location is unambiguous.

## Acceptance criteria

- `.claude/workflow/inline-replanning.md` § "Updating plan and
  backlog" enumerates seven cases (or six cases plus an explicit
  "track split" sub-case under case 6), and case 7 covers step-file
  deletion + content preservation rules.
- A grep for `track-split\|split.*track\|splitting.*track` in
  `inline-replanning.md` returns at least one hit covering the
  step-file fate.
- A future inline-replan that splits a mid-execution track follows
  the documented procedure without the orchestrator inventing
  step-file handling on its own authority.
