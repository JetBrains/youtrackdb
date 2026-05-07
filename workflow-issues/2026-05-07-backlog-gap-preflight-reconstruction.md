---
severity: medium
phase: phase-a
source-session: 2026-05-07 /execute-tracks unit-test-coverage
---

# Track Pre-Flight gate has no rule for tracks whose backlog section is in a recovery gap

## Symptom

During Track 19 Phase A, the orchestrator opened
`implementation-backlog.md` to assemble the Pre-Flight summary per
`track-review.md` § Track Pre-Flight step 1 ("Build the summary.
Read the plan-file Track N entry … and the backlog's `## Track N:`
section …"). For Track 19 the backlog has no `## Track 19:` section
at all — the section was lost in the `git clean -fd` incident
recorded in the plan's Operational Notes, and Track 19 carries an
`> **Operational note:** Backlog section entirely in a gap —
reconstruct at Phase A from the Scope indicator above + the design's
Component Map cluster mapping for `core/storage/{config, memory,fs,
disk,collection,ridbag}*`.` line. The Track Pre-Flight gate, however,
has no sub-rule for "the backlog has no section to summarise" — the
agent had to invent the reconstruction protocol on the fly: regenerate
the W/H/C/I block from the design + Scope + episodes, present the
reconstruction as the summary, then carry it through the rest of the
gate.

## Reproduction context

- Phase: phase-a (Track Pre-Flight gate, sub-step 1 "Build the
  summary").
- Workflow doc(s) involved: `.claude/workflow/track-review.md`
  § Track Pre-Flight steps 1–3; the plan's Operational Notes
  section that defines the "reconstruct on demand" protocol but
  doesn't crosslink to the Track Pre-Flight rules.
- Tool / sub-agent involved: orchestrator (Pre-Flight gate is
  main-agent work).
- ADR directory at the time: `docs/adr/unit-test-coverage/`.
- Trigger condition: any track whose plan-file entry carries a
  `> **Operational note:** Backlog section entirely in a gap …`
  line (or, more generally, any track whose backlog section is
  missing when Pre-Flight reads the backlog). The current branch
  has 3 more such tracks (20, 21, 22) ahead — this will recur every
  remaining Phase A session on this branch.

## Why it's a problem

Each affected Phase A spends one or two extra orchestrator turns on
"figure out the right reconstruction approach" because the workflow
says the agent SHOULD reconstruct (Operational Notes) but doesn't
specify WHEN within the Pre-Flight flow the reconstruction happens
or HOW the reconstruction is presented to the user (as the summary
itself? as a note above the summary? as an Amend candidate?). The
ad-hoc resolution this session worked, but each future agent in
this position has to re-derive it. Worse, an agent could
legitimately misread the protocol and skip the reconstruction —
the Pre-Flight summary would then be just the plan-file entry's
intro paragraph + Scope, missing the W/H/C/I block entirely, and
the resulting step file would lack the detail Phase B needs.

## Proposed fix

Edit `.claude/workflow/track-review.md` § Track Pre-Flight step 1
to add a sub-rule:

> **Backlog-gap fallback:** if the plan-file Track N entry carries
> a `> **Operational note:** Backlog section entirely in a gap …`
> line, OR if `implementation-backlog.md` has no `## Track N:`
> section when the gate runs, the orchestrator MUST reconstruct
> the W/H/C/I block before assembling the summary. The
> reconstruction inputs are (a) the Scope indicator in the plan-
> file entry, (b) the design document's Component Map cluster
> mapping for the target packages or components, and (c) any
> cross-references from already-completed tracks' episodes. The
> reconstructed block becomes the Pre-Flight summary's body; the
> orchestrator surfaces a one-line note ("Reconstructed because
> backlog section is in a gap") above the summary so the user can
> Amend it more readily. Step file `## Description` is written
> from the reconstruction in sub-step 2c as usual.

Optionally also add a one-line crosslink in
`conventions-execution.md` §2.1 description-lifecycle table for
the "Phase A start — before step-file write" row, pointing at the
new Pre-Flight sub-rule.

## Acceptance criteria

- `track-review.md` § Track Pre-Flight step 1 has an explicit
  Backlog-gap fallback sub-rule with named reconstruction inputs.
- The sub-rule names the user-visible note ("Reconstructed
  because …") that goes above the summary so reconstructed
  Pre-Flight gates are visually distinct from normal ones.
- Regression check: `grep -n 'Backlog-gap fallback' .claude/workflow/track-review.md`
  shows the new sub-rule; future Phase A sessions on Tracks
  20 / 21 / 22 do not need to invent the reconstruction approach.
