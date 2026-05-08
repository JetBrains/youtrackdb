---
severity: medium
phase: phase-a
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Pre-Flight summary and sub-step 2c re-do the same reconstruction for gap-track backlogs

## Symptom

During Track 20 Phase A, the orchestrator built a long Pre-Flight
summary (~30 sub-sections of `**What/How/Constraints/Interactions**`)
in the reply to the user, per `track-review.md` § Track Pre-Flight
step 1 ("Build the summary. Read the plan-file Track N entry … and
the backlog's `## Track N:` section"). Track 20's backlog section
was in the recovery gap recorded in the plan's Operational Notes;
the orchestrator reconstructed the W/H/C/I block from the Scope
indicator + design's Component Map + post-Track-7 coverage baseline +
Track 19's accumulated patterns.

After the user picked **Proceed**, sub-step 2c required a similar
W/H/C/I block to be written to disk as the step file's
`## Description`. Per the workflow, 2c "concatenates the plan-entry
intro with the backlog-section body". For gap tracks, no
backlog-section body exists — the orchestrator had to **re-do** the
same reconstruction (intro paragraph + W/H/C/I) authored seconds
earlier for the Pre-Flight reply.

The two reconstructions targeted the same content but were
re-authored independently, costing the orchestrator a non-trivial
amount of writing time twice. After iter-1 reviews accepted 11
should-fix items, the on-disk Description was rewritten a third
time. Three versions of substantively the same content for one
track.

## Reproduction context

- Phase: phase-a (Track Pre-Flight gate, sub-step 1 "Build the
  summary"; sub-step 2c "Create the step file atomically").
- Workflow doc(s) involved:
  - `.claude/workflow/track-review.md` § Track Pre-Flight step 1
    (build summary in reply, do not write to disk).
  - `.claude/workflow/track-review.md` § What You Do sub-step 2c
    (atomic Write of step file with `## Description` populated
    from plan-entry intro + backlog `**W/H/C/I**` subsections).
- Tool / sub-agent involved: orchestrator (both phases are
  main-agent work).
- ADR directory at the time: `docs/adr/unit-test-coverage/`.
- Trigger condition: any track whose plan-file entry carries an
  `> **Operational note:** Backlog section entirely in a gap …`
  marker (currently Tracks 18, 19, 20, 21 in this plan; the gap is
  a permanent artifact of the 2026-05-04 `git clean -fd` incident
  recorded in the plan's Operational Notes).

## Why it's a problem

Duplicate authoring of the W/H/C/I block costs the orchestrator
one full pass per occurrence — for Track 20 the summary was
~1 200 words, the on-disk Description was ~1 800 words, and the
content overlap was ~70%. Across the four remaining gap tracks
(20, 21, plus the inherited-DRY-scope subsections of 22 if they
re-trigger Pre-Flight) this is 3–4 wasted authoring passes per
track over the project's lifetime. The summary's content is
discarded once the user picks Proceed (Pre-Flight outputs aren't
persisted), so the duplication is invisible at review time but
real at session-budget time.

A future agent with less context budget could (a) keep the
Pre-Flight summary terse and lose the user-gate value, or (b)
write the full summary and then truncate at 2c, missing the
opportunity to carry forward the reconstruction.

## Proposed fix

Add a sub-rule to `track-review.md` § Track Pre-Flight step 1
explicitly handling gap tracks. Suggested addition (immediately
after the "Render the summary inline in the reply to the user; do
not write to disk yet" sentence):

> **For gap tracks** (the plan-entry carries an
> `> **Operational note:** Backlog section entirely in a gap …`
> line): the W/H/C/I block reconstructed during step 1 from the
> Scope indicator + design's Component Map + coverage-baseline.md
> per-package distribution + prior-track episodes is the
> authoritative source for sub-step 2c. Carry it forward verbatim
> when 2c writes the step file's `## Description`; do not
> re-author. Any user-supplied amendments via the `Amend` option
> in this gate are applied directly to the reconstructed block
> before 2c reuses it.

Optionally also: amend sub-step 2c (item b) to mention the gap-track
fallback explicitly: "If the backlog has no `## Track N:` section
because the track is in a recovery gap, source the
`**W/H/C/I**` subsections from the Pre-Flight summary built in
step 1 of the gate; do not re-author."

## Acceptance criteria

- `track-review.md` § Track Pre-Flight step 1 contains an explicit
  gap-track sub-rule directing 2c to re-use the step-1
  reconstruction.
- `track-review.md` § What You Do sub-step 2c (item b) names the
  fallback source ("Pre-Flight summary buffer") for gap tracks.
- A future Phase A session on Track 21 (also a gap track) does
  not re-author the W/H/C/I block twice; the orchestrator can
  point at the doc rule that says "carry forward".
- Regression check: grep `track-review.md` for "do not write to
  disk yet" — the sentence should still apply for non-gap tracks
  (the on-disk Description for non-gap tracks is sourced from the
  backlog directly, not from the Pre-Flight reply).
