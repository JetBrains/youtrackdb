---
severity: medium
phase: phase-a
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Cross-track handoff artifact format is undefined

## Symptom

Track 22a's plan entry and step file `**Interactions**:` block name
two cross-track handoffs:

- *"22b consumes 22a's PSI safe-delete confirmations: 22a's Phase A
  adversarial review classifies each `*DeadCodeTest`-pinned cluster
  into 'in-track-deletion-22b' vs 'issue-and-defer-22c' buckets."*
- *"22c consumes 22b's final cluster-disposition list as a filter:
  only WHEN-FIXED markers in clusters NOT deleted by 22b need YTDB
  issues."*

Both contracts name the *information content* of the handoff but
**neither names a canonical on-disk artifact** — no path, no
filename pattern, no rule for whether the artifact lives as a
subsection of the producing track's step file, as a sibling file
under `_workflow/tracks/`, as a plan-file addendum, or somewhere
else. Phase A iter-1 technical review (finding T3) flagged the gap
as a blocker, and the orchestrator improvised by adding a
`### Cluster classification table (load-bearing input for 22b/22c)`
subsection inside Track 22a's `## Description`.

## Reproduction context

- Phase: phase-a
- Workflow doc(s) involved: `.claude/workflow/conventions-execution.md` § "Step file content (`tracks/track-N.md`)"; `.claude/workflow/track-review.md` § "What You Do" sub-step 2c (which lists the step-file sections)
- Tool / sub-agent involved: Phase A orchestrator + adversarial-review sub-agent
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any track whose `**Interactions**:` block names a downstream-consumed artifact ("Track X consumes Track Y's …") without specifying the artifact's location

## Why it's a problem

Cross-track dependencies are a normal feature of multi-track plans
(Track 14's dead-code-deletion queue → Track 22; Track 18's
cross-track hints → Track 21; the new 22a/22b/22c chain). When a
producing track does not write the artifact in a discoverable
location, the consuming track's Phase A has to redo the work — the
exact failure mode finding T3 was guarding against.

The improvised solution (subsection of producing track's step file
`## Description`) works but has trade-offs: the step file already
hits 2,000+ lines on tracks with large inherited queues, so adding
a load-bearing artifact pushes it further; and there is no
discoverability rule for the consuming track's Phase A to know
where to look. Future cross-track-artifact-producing tracks will
re-invent the same shape, with drift.

## Proposed fix

Add a new subsection to `.claude/workflow/conventions-execution.md`
(after § "Step file content") titled
**"Cross-track handoff artifacts"** with:

- A canonical on-disk location: `_workflow/tracks/track-N-<slug>.md`
  (sibling to the producing track's step file, with a slug
  describing the artifact — e.g.,
  `tracks/track-22a-cluster-classification.md`).
- A canonical reference shape from the producing track's
  `**Interactions**:` block: `Artifact: tracks/track-N-<slug>.md`
  on a dedicated line.
- A canonical reading rule for the consuming track's Phase A:
  before reading the producing track's step file, check
  `_workflow/tracks/track-<producer>-*.md` for any sibling
  artifacts and load them as additional inputs.
- A note that subsection-of-step-file is acceptable for small
  artifacts (≤ ~50 lines) but anything larger should live in the
  sibling file to keep step-file Description sizes bounded.

Alternative (simpler but less powerful): just require any
downstream-consumed claim in `**Interactions**:` to name the
on-disk path that holds the artifact (whether subsection or
sibling file). The producing track's Phase A then has a clear
gate: write the artifact at the named path before commit, or
flag the missing artifact as a blocker.

## Acceptance criteria

- `.claude/workflow/conventions-execution.md` adds a § "Cross-track
  handoff artifacts" subsection codifying the location convention
  and discoverability rule.
- A Phase A pre-execution review (technical or adversarial) that
  finds an unnamed downstream-consumed artifact returns a blocker
  with the canonical fix template (e.g., "add `Artifact:
  tracks/track-N-<slug>.md` to the Interactions block").
- Regression check: `grep -rE "consumes Track [0-9]+[a-z]?'s" docs/adr/*/_workflow/implementation-plan.md` either has zero matches (all migrated to the new shape) or every match is co-located with an `Artifact:` line within ~5 lines.
