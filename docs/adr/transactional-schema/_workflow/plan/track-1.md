<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 1: WAL replay lazy-consult fix (prerequisite)

## Purpose / Big Picture
After this track, a crash partway through an atomic operation's physical apply
replays cleanly instead of aborting the restore of a committed file-creating unit
and discarding every later unit.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Fix the lazy-consult WAL replay path so a crash between an atomic operation's end
record becoming durable and its physical apply completing no longer aborts the
restore. This is the prerequisite the reconciliation crash-recovery claim (I-A1,
D10) rests on: the transactional schema commit creates and drops collections and
engines inside the commit's own atomic operation, so its crash recovery is only
as sound as the replay path underneath it. The fix shares no files with the
schema or index subsystems, so it stands as an independently reviewable unit at
the front of the series.

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
<!-- The track-canonical live decision carrier (D7). Seeded from the frozen
design.md D-records this track owns. -->

#### D10 (recovery prerequisite): WAL replay must restore a committed file-creating unit lazily
- **Alternatives considered**: ship the transactional schema commit on the current replay path and accept the crash-recovery gap; add a separate deletion/reinit pool to sidestep the replay path entirely (rejected under D10 — the pool's only correctness benefit is already free, and its performance benefit needs a new WAL-logged clear-and-reinit op because the one "empty but keep the file" path, `truncateFile`, is not crash-safe).
- **Rationale**: D10 makes structural revertibility free through buffered file-create/delete intent applied only in `commitChanges`, which rollback skips. The "replay cleanly" half of that guarantee (F16) is conditional until the lazy-consult fix lands: today a crash between the end record becoming durable and the physical apply phase completing aborts the restore of a committed file-creating unit and discards all later units. The transactional schema commit is the first feature to lean on file-create/delete inside a recoverable atomic operation as a routine path, so the gap must close before its crash-recovery invariant is real.
- **Risks/Caveats**: the replay path is shared crash-recovery infrastructure; the regression must inject a crash at exactly the durable-end-record-before-apply window and assert later units survive. Out-of-scope failure injection elsewhere in replay is not this track's concern.
- **Implemented in**: this track (step references added during execution)
- **Full design**: design.md §"Commit-time reconciliation" (the WAL revertibility paragraph and its F55 recovery caveat)

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation
The WAL restore path replays atomic operations from the last checkpoint forward.
An atomic operation's end record makes the unit durable; the physical apply phase
then writes the buffered file creates and deletes. A crash in the window between
those two leaves the unit logically committed but physically unapplied. The
current replay logic aborts the restore of such a unit and discards every unit
that followed it in the log, which is the bug F55 names.

This track touches only that replay path and its regression coverage. It does not
introduce any schema, index, or commit-machinery code; those build on top of it
in later tracks. The crash-recovery half of I-A1 (Track 4) cites this fix as its
prerequisite, so Track 4's reconciliation crash tests assume this track has
landed.

## Plan of Work
Locate the lazy-consult replay decision point where a committed-but-unapplied
file-creating unit is currently aborted, and change it to restore the unit and
continue replaying later units rather than discarding them. Preserve the existing
behavior for genuinely incomplete units (those whose end record never became
durable). Add a crash-replay regression that creates a file-creating committed
unit, injects a crash in the durable-end-record-before-apply window, restarts,
and asserts both the unit and every later unit are restored.

Ordering constraint: the fix must not weaken recovery for the
end-record-never-durable case, which must still be discarded. The regression must
distinguish the two windows so a fix that blindly restores everything is caught.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. Per-step episodes
do NOT live here; they live in `## Episodes` below. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance
- A committed file-creating atomic operation that crashes between its durable end
  record and the completion of its physical apply is fully restored on restart,
  and every atomic operation that followed it in the log is also restored.
- A genuinely incomplete unit (end record never made durable) is still discarded
  on replay, with no regression to existing recovery behavior.
- The existing WAL recovery test suite continues to pass unchanged.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Often empty. -->

## Interfaces and Dependencies
- **In scope**: the WAL restore/replay logic that decides whether to restore or
  abort a committed file-creating atomic operation, and a crash-replay regression
  test.
- **Out of scope**: all schema, index, and commit-machinery code (later tracks);
  any replay behavior outside the durable-end-record-before-apply window.
- **Inter-track dependencies**: none upstream (this is the front of the series).
  Track 4 (commit-time reconciliation) consumes this fix — its I-A1 crash-recovery
  tests assume the lazy-consult fix has landed.
- **Signatures**: confined to the existing replay path; no public API change.
