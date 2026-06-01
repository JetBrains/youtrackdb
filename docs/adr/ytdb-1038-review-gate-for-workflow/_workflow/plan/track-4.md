<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Track 4: Review-target delta-scoping for staged copies (YTDB-1038)

## Purpose / Big Picture
After this track, when a track first-creates a staged copy inside a reviewed range, the Phase C track reviewers and the high-risk Phase B step reviewers get a `diff <live> <staged>` delta and scope findings to the real change rather than re-reviewing already-promoted content.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

On a workflow-modifying plan a track's deliverable is a staged copy under
`_workflow/staged-workflow/.claude/...`. When that copy is first created in a
reviewed commit range the cumulative diff shows it as a whole-file add, even
though it is a copy of an already-live, already-reviewed file plus a small
edit. The orchestrator pre-stages the delta against the live counterpart and
the reviewer context block scopes findings to it.

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

`track-code-review.md §Phase C Startup` step 7 ("Pre-stage the cumulative diff
and changed-files list") writes the cumulative track diff to a temp file, and
`§Context passed to all sub-agents` (plus its backing `§Pre-staged diff and
changed-files list`) points every track reviewer at that diff by path. Neither
gives a freshly-created staged copy any special handling: the reviewer sees a
whole-file add.

`step-implementation.md §on_success(step, result)` sub-step 4(a) is the
high-risk step-review setup. Its "pre-stage the step diff and the
changed-files list" block writes the per-step diff to a temp file, and the
canonical `## Workflow Context` block in the same sub-step points the
step reviewers at it. Same blind spot: a staged copy first created in that
step's commit reads as a whole-file add.

On a workflow-modifying plan the changed file is often a staged copy under
`…/_workflow/staged-workflow/.claude/…`. When the track first creates it, the
whole-file add masks the real target: only the delta against the live
counterpart is the change worth review. Reviewers handed the whole-file add
spend effort on already-promoted content and risk phantom findings or scope
creep into the live machinery.

The two context blocks are parallel copies, not a shared include (S2): the
sub-step 4(a) block in `step-implementation.md` and the
`§Context passed to all sub-agents` block in `track-code-review.md` must carry
the delta note with matching meaning, or a Phase C review behaves differently
from its Phase B counterpart.

Concrete deliverables: a delta-staging step in each of the two orchestrator
setups, plus a matching scope note in each of the two context blocks.

## Plan of Work

The approach is orchestrator pre-staging (D5). In the Phase C diff-staging
step and the high-risk Phase B step-review setup, the orchestrator detects a
changed file that is a freshly-created staged copy (it matches the anchored
`…/_workflow/staged-workflow/.claude/…` prefix, is a new-file add in the
reviewed range, and has a live counterpart) and additionally stages a
`diff <live> <staged>` delta file. The context block points reviewers at that
delta with the note: scope findings to this delta; the rest is verbatim-copied
live content.

1. Add the delta-staging step to `track-code-review.md §Phase C Startup`
   (alongside step 7's cumulative-diff staging) and carry the scope note in
   `§Context passed to all sub-agents`.
2. Add the delta-staging step to `step-implementation.md` sub-step 4(a)
   (alongside its step-diff staging) and carry the scope note in that
   sub-step's `## Workflow Context` block.
3. Verify the scope note reads the same in both context blocks (S2).

Ordering and invariants:
- This track lands after Track 2. Both edit the two context blocks; Track 4
  layers its delta note onto Track 2's read caveat, so sequencing Track 4
  second avoids a staged-copy conflict on the same two blocks.
- The S2 parallel-block invariant, extended by this replan, now covers the
  delta note alongside the read caveat: the note must land in both the
  step-implementation sub-step 4(a) block and the track-code-review block with
  matching meaning.
- The trigger is precise: it fires only on the first creation of a staged copy
  (a new-file add). A later edit to an already-restaged file is an ordinary
  diff, not a whole-file add, so no delta is staged. Like the selection
  normalization, the trigger keys off the staged prefix and needs no marker —
  staged paths exist only on plans that carry the `§1.7(b)` marker anyway.

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
- On a workflow-modifying plan, when a reviewed range first-creates a staged
  copy, the orchestrator stages a `diff <live> <staged>` delta and the reviewer
  context block scopes findings to that delta.
- An ordinary edit to an already-restaged file (not a whole-file add) stages
  no delta; the reviewer sees the ordinary diff unchanged.
- The delta-scoping note reads the same in both context blocks — the
  step-implementation sub-step 4(a) `## Workflow Context` block and the
  track-code-review `§Context passed to all sub-agents` block (S2).
- The delta rides in the two parallel context blocks only, not the gate-check
  prompts (`dimensional-review-gate-check.md`, `review-gate-verification.md`).
- Reviewer-side self-diffing was not chosen: pre-staging is deterministic
  across the fan-out (D5).

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

In-scope files (all under `.claude/workflow/**`):
- `track-code-review.md` — the Phase C diff-staging step (`§Phase C Startup`
  step 7) and the `§Context passed to all sub-agents` block.
- `step-implementation.md` — sub-step 4(a), the high-risk Phase B step-review
  setup: its step-diff staging block and `## Workflow Context` context block.

Out-of-scope:
- `dimensional-review-gate-check.md` and `review-gate-verification.md` — the
  gate-checks re-check prior findings rather than scoping a fresh diff, so they
  take the Track 2 read caveat only, no delta note.
- Reviewer-side self-diffing — rejected alternative (D5).

Inter-track dependencies:
- **Depends on Track 2.** Both tracks edit the two context blocks; Track 2's
  read caveat lands first, and Track 4 layers the delta note onto it.

Staging: per `§1.7`, the two edits route through
`docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/...`; the live files
stay at develop's state until Phase 4 promotion.

Self-application (`§1.7(h)`): Track 4 edits the live review machinery this
branch stages, so its own Phase A and Phase C reviews run against the unfixed
live rules. The orchestrator hand-injects the staging and delta-scoping
guidance during this branch's execution — the same manual step the fix removes
for later plans.

Full design: design.md §"Read-side staging awareness".
