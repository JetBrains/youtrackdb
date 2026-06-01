<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Track 3: Phase A criteria addendum (YTDB-1046)

## Purpose / Big Picture
After this track, the Phase A technical, risk, and adversarial reviewers stop raising phantom `NOT FOUND` blockers on a workflow-machinery track and instead verify named references as file paths and `§`-anchors while applying prose-soundness criteria.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The Phase A technical, risk, and adversarial reviewers apply Java criteria
that misfire on a prose track and raise phantom `NOT FOUND` blockers. A
marker-gated addendum re-points the criteria to prose; the same three
reviewers self-adapt.

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

`track-review.md §Complexity Assessment` selects which Phase A reviews run by
step count and code cues, with no workflow branch. A workflow-machinery track
therefore gets the Java reviewers unchanged.

On such a track the names in the track file are workflow docs and `§`-anchors,
not Java FQNs:
- The technical reviewer's rule to verify every named class via `findClass`
  has no valid target and raises phantom `NOT FOUND` blockers.
- The WAL, crash, migration, and hot-caller criteria have nothing to bind to.

The three criteria reviewers in scope are `technical-review.md`,
`risk-review.md`, and `adversarial-review.md`. `review-gate-verification.md`
re-checks prior findings rather than generating criteria, so it is
criteria-agnostic and is out of scope here — it takes the Track 2 read caveat
alone, no addendum.

Concrete deliverables: one marker-gated addendum block in each of the three
criteria reviewer prompts.

## Plan of Work

The approach is one addendum block per criteria reviewer, gated on the same
`§1.7(b)` marker the read caveat uses (D4), byte-uniform across the three
(S3).

1. Add the addendum to `technical-review.md`, `risk-review.md`, and
   `adversarial-review.md`, gated on the marker. The addendum re-points the
   criteria:
   - verify named references as file paths and `§`-anchors with grep and Read,
     not as Java FQNs via `findClass`, so a missing target is no longer a
     phantom blocker;
   - replace WAL, crash, migration, and hot-caller concerns with rule
     coherence and non-contradiction, instruction completeness, prompt-design
     soundness, context-budget impact, and breakage of dependent prompts or
     agents.
2. Verify uniformity (S3): the addendum reads the same across the three
   criteria prompts.

Ordering and invariants:
- The same three reviewers still run; they read the marker and switch
  criteria, mirroring how the read caveat self-gates (D4). The
  complexity-assessment dispatch is untouched, so a track mixing prose and
  code gets one reviewer applying both lenses.
- This track lands after Track 2 because the addendum references the read
  caveat in the same three files; the caveat and the addendum cooperate (the
  caveat points the reviewer at the staged copy, the addendum tells it to
  verify that copy's `§`-anchors as paths).

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
- On a workflow-machinery track (plan carries the `§1.7(b)` marker), each of
  the three criteria reviewers verifies named references as file paths and
  `§`-anchors via grep and Read; a missing Java symbol is not raised as a
  blocker.
- The WAL, crash, migration, and hot-caller criteria are replaced by the
  prose-soundness criteria for such a track.
- The same three reviewers run with no dispatch change; a track mixing prose
  and code gets one reviewer applying both lenses by reading the marker plus
  the track's in-scope files.
- The addendum reads the same across the three prompts (S3).
- `review-gate-verification.md` gets no addendum.

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
- `technical-review.md`, `risk-review.md`, `adversarial-review.md` — the three
  Phase A criteria reviewers.

Out-of-scope:
- `review-gate-verification.md` — criteria-agnostic; takes the Track 2 read
  caveat only, no addendum.
- The complexity-assessment dispatch in `track-review.md` — unchanged; the
  same technical/risk/adversarial reviewers run (D4, Non-Goal).
- No new Phase A prompt files (Non-Goal).

Inter-track dependencies:
- **Depends on Track 2.** The addendum references the read caveat in
  `technical-review.md`, `risk-review.md`, and `adversarial-review.md`, so
  Track 2's caveat must land in those three files first.

Staging: per `§1.7`, all three edits route through
`docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/...`; the live
files stay at develop's state until Phase 4 promotion.

Full design: design.md §"Phase A criteria for workflow-machinery tracks".
